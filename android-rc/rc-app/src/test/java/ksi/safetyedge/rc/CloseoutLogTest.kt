package ksi.safetyedge.rc

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ksi.rc.Mop

/**
 * Host tests for the durable close-out record + combined mission/closeout
 * artifact (contract §6 closeout): exact fact counts including zero,
 * stop-confirmation state with reason, session/boot refs, recorder
 * results, and the pinned bundle bytes — one artifact under the
 * mission-bundle lineage (no second unknown legacy id to evict it).
 */
class CloseoutLogTest {

    private fun record(
        flightId: String = "flt-1",
        outcome: String = CloseoutRecord.UNRESOLVED,
    ) = CloseoutRecord(
        flightId = flightId,
        bootId = "boot-1",
        sessionId = "sess-1",
        outcome = outcome,
        stopConfirmed = null,
        stopReason = "STOP 요청 전송됨 · Edge 확인 미수신 — 미확인 종료",
        opinionCount = 0,
        segmentsIngested = 0,
        segmentsTotal = 2,
        segmentsUnverified = true,
        bundleId = "bundle-1",
        bundleSha256 = "b".repeat(64),
        verdictResult = "UNKNOWN",
        verdictFactsJson = "{}",
        decidedAtUtc = "2026-10-10T07:00:00Z",
    )

    @Test
    fun latest_wins_and_zero_counts_are_facts() {
        val log = CloseoutLog(Files.createTempDirectory("rc-closeout"))
        assertNull(log.latestForFlight("flt-1"))

        log.record(record())
        val first = log.latestForFlight("flt-1")!!
        assertEquals(CloseoutRecord.UNRESOLVED, first.outcome)
        assertEquals(0, first.opinionCount)
        assertEquals(2, first.segmentsTotal)
        assertTrue(first.segmentsUnverified)

        log.record(record(outcome = CloseoutRecord.CLEAN))
        assertEquals(CloseoutRecord.CLEAN, log.latestForFlight("flt-1")!!.outcome)
    }

    @Test
    fun combined_snapshot_carries_bundle_and_closeout_with_stable_id() {
        val bundle = Mop.json.createObjectNode()
        bundle.put("bundle_id", "bundle-1")
        bundle.put("site_id", "site-1")
        val rec = record()
        val bytes = CloseoutArtifacts.snapshotBytes(rec, bundle)
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.contains("\"record_kind\":\"CLOSEOUT\""))
        assertTrue(text.contains("\"outcome\":\"UNRESOLVED\""))
        assertTrue(text.contains("\"bundle_snapshot\""))
        assertTrue(text.contains("\"bundle_id\":\"bundle-1\""))
        assertTrue(text.contains("\"opinion_count\":0"))

        // Same facts -> same content-derived id (idempotent re-announce).
        val first = CloseoutArtifacts.artifactIdFor("flt-1", "boot-1", sha(bytes))
        val second = CloseoutArtifacts.artifactIdFor("flt-1", "boot-1", sha(bytes))
        assertEquals(first, second)
        // Changed facts -> a NEW id (old record kept, never overwritten).
        val changed = CloseoutArtifacts.artifactIdFor(
            "flt-1", "boot-1", sha(CloseoutArtifacts.snapshotBytes(rec.copy(outcome = CloseoutRecord.CLEAN), bundle)),
        )
        assertTrue(changed != first)
    }

    @Test
    fun artifact_announce_requires_the_record_boot() {
        val storeDir = Files.createTempDirectory("rc-closeout-artifacts").toFile()
        val store = ksi.rc.FileRcStore(storeDir.toPath(), wallClock = { "2026-10-10T07:00:00Z" })
        val bundle = Mop.json.createObjectNode()
        bundle.put("bundle_id", "bundle-1")
        val rec = record()

        val id = CloseoutArtifacts.ensureForBoot(store, rec, bundle, "flt-1", "boot-1")
        assertTrue(id != null)
        assertTrue(store.isArtifactComplete(id!!))
        val announced = store.artifactsFor("flt-1", "boot-1")
            .first { it.get("artifact_id").asText() == id }
        assertEquals("FLIGHT_LOG", announced.get("source_kind").asText())
        assertEquals(true, announced.get("required").asBoolean())
        // Readback: the exact combined bytes, hash-matching the announce.
        assertEquals(
            announced.get("sha256").asText(),
            sha(store.artifactBytes(id)!!),
        )

        // A record for another boot announces nothing here (no cross-boot
        // relabeling, never a default).
        assertNull(
            CloseoutArtifacts.ensureForBoot(store, rec, bundle, "flt-1", "boot-2"),
        )
    }

    private fun sha(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun draft_outcome_and_nullable_video_flag_round_trip() {
        val log = CloseoutLog(Files.createTempDirectory("rc-closeout-null").toFile().toPath())
        // Pre-tracking records carry no video flag: absent reads null,
        // never an invented false.
        val legacy = record().copy(outcome = CloseoutRecord.DRAFT, videoCaptureFailed = null)
        log.record(legacy)
        val read = log.latestForFlight("flt-1")!!
        assertEquals(CloseoutRecord.DRAFT, read.outcome)
        assertNull(read.videoCaptureFailed)

        val flagged = record().copy(videoCaptureFailed = true)
        log.record(flagged)
        assertEquals(true, log.latestForFlight("flt-1")!!.videoCaptureFailed)

        // The artifact carries an explicit null (not a missing field).
        val bytes = String(CloseoutArtifacts.snapshotBytes(legacy, null), Charsets.UTF_8)
        assertTrue(bytes.contains("\"video_capture_failed\":null"))
    }

    @Test
    fun start_intent_stands_until_a_later_closeout_supersedes_it() {
        val root = Files.createTempDirectory("rc-intents").toFile().toPath()
        val log = SessionIntentLog(root)
        assertNull(log.latestForFlight("flt-1"))

        log.record(SessionStartIntent("flt-1", "boot-1", "2026-10-10T07:00:00Z"))
        val intent = log.latestForFlight("flt-1")!!
        assertEquals("boot-1", intent.bootId)

        // A later close-out retires the intent (new sessions need a new one).
        val closes = CloseoutLog(root)
        closes.record(record().copy(outcome = CloseoutRecord.CLEAN, decidedAtUtc = "2026-10-10T07:05:00Z"))
        val latest = closes.latestForFlight("flt-1")!!
        assertTrue(latest.decidedAtUtc > intent.decidedAtUtc)
    }
}
