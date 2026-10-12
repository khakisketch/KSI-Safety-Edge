package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.File
import java.nio.file.Files
import ksi.rc.CanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CI regression (C65): the WorkManager suite never executed before the
 * x86_64 runner existed, so a stale [DemoFeed.demoBundle] shape (no
 * mission_snapshot) surfaced only when SyncWorkerTest actually ran. This
 * host test pins that the demo bundle stays digest-valid and satisfies
 * [RcSession.startFlightFromBundle] — the exact call the WM tests make.
 */
class DemoBundleShapeTest {
    private fun newSession(): RcSession {
        val dir = Files.createTempDirectory("demo-bundle").toFile()
        return RcSession(
            storeDir = File(dir, "rc-store"),
            rcDeviceId = "test-device",
            wallClock = { "2026-10-12T00:00:00Z" },
            link = FakeDroneLink(),
            coreConfig = { null },
        )
    }

    @Test
    fun demo_bundle_is_digest_valid_and_starts_a_flight() {
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        assertTrue(CanonicalJson.verifyDigest(bundle, "bundle_sha256"))
        val snapshot = bundle.get("mission_snapshot")
        assertNotNull(snapshot)
        assertTrue(CanonicalJson.verifyDigest(snapshot, "content_sha256"))

        val session = newSession()
        val record = session.startFlightFromBundle(bundle)
        assertFalse(record.get("flight_id").asText().isEmpty())
        assertEquals(bundle.get("bundle_id").asText(), record.get("bundle_id").asText())
        session.endFlight()
    }

    @Test
    fun tampered_demo_bundle_is_refused() {
        val bundle = DemoFeed.demoBundle(
            "00000000-0000-4000-8000-0000000000b1",
            "00000000-0000-4000-8000-0000000000b2",
        )
        (bundle.get("mission_snapshot") as ObjectNode).put("mission_id", "tampered")
        val session = newSession()
        try {
            session.startFlightFromBundle(bundle)
            fail("tampered bundle must be refused")
        } catch (_: IllegalStateException) {
            // expected: the digest check fails closed before any pinning
        }
    }
}
