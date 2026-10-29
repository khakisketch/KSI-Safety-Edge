package ksi.safetyedge.rc

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Log
import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.Base64
import ksi.rc.EffectiveConfigHash
import ksi.rc.MissionState
import ksi.rc.Mop

/**
 * Bench-only workspace preview seeder (Design's new workspace needs
 * representative native states + actual image zoom, not empty-only proof).
 *
 * Runs ONLY on the physically separate BENCH package
 * (`applicationIdSuffix '.bench'`, parent-owned): it refuses anything but
 * a [FakeDroneLink], refuses a configured Core (bench never uploads), and
 * seeds ONLY the bench app's own RC store (`rc-store` under the bench
 * files dir) — never the production `ksi.safetyedge.rc` data, never a
 * device write/command, never an SDK call.
 *
 * Entry: `seed(app, scenario)` is called by the parent (MainActivity, after
 * app/session assignment) with the explicit intent extra
 * [INTENT_EXTRA] (`workspaceScenario`). Unknown/absent scenarios return
 * false and touch nothing. The device flavor's object of the same name
 * is an unconditional no-op (`device/.../WorkspacePreviewFixture.kt`).
 *
 * How it works: site + zone + mission-snapshot basis rows are saved with
 * deterministic UUIDv5 identities (idempotent re-saves — same content is a
 * no-op), a LOCAL SHADOW bundle is composed through the existing
 * [RcSession.composeLocalBundle] path (real canonical digest, real
 * `geometry_wgs84`), and the flight is driven through the REAL
 * prepare/apply/start/status ingest (`controller.prepare()/apply()`,
 * `RcSession.requestSessionStart()`, valid MOP envelopes through
 * `session.onLinkBytes`) — no reflection, no private fields, no mock UI
 * copy. Evidence is real `ARTIFACT_ANNOUNCE` + `ARTIFACT_CHUNK` storage
 * (the bundled ESO scene photo, see `bench/assets/CREDITS.md`) plus a
 * linked `DETECTION_EVENT`, so the zoom view decodes actual bytes.
 *
 * Scenarios (all synthetic; site names carry `[SIMULATED]`):
 * - `ready` — saved site + composed bundle, NO active flight;
 * - `recording` — APPLIED local record, session never started (no AI:
 *   the explicit MainActivity AI button reports pending);
 * - `observing` — session RUNNING with bound runtime status + engine-ok
 *   frame proof + full artifact;
 * - `partial` — stored record + event with the artifact announced but its
 *   chunks missing (filmstrip shows missing links);
 * - `apply-error` — Edge `MISSION_APPLY_REJECTED` on the real controller,
 *   no flight record.
 *
 * Never clears/deletes any existing record. Re-entry with the same
 * scenario is a no-op returning true; a contradictory active flight (or an
 * in-progress preparation) returns false instead of an unsafe reset.
 *
 * Known limits (seed-only truth, no ongoing peer): after the seed, new
 * outbound commands (timer `MISSION_STATUS_QUERY` polls, explicit AI
 * requests) get NO bench reply — the observing snapshot keeps its last
 * bound status, and the recording AI request stays pending (intended).
 */
object WorkspacePreviewFixture {
    /** Intent extra carrying the scenario (parent reads it, passes it here). */
    const val INTENT_EXTRA = "workspaceScenario"

    /** Explicit bench-only request for the still reference backdrop. */
    const val SCENE_EXTRA = "scenePreview"

    /** View-only camera-report samples. No camera commands, media or KSI state are produced. */
    fun cameraPresentation(scenario: String?): FlightCameraPresentation = when (scenario) {
        "camera-rec" -> FlightCameraPresentation(recording = true, recordingSeconds = 53, lensName = "WIDE", zoomRatio = 1.0, simulated = true)
        "camera-photo" -> FlightCameraPresentation(recording = false, lensName = "WIDE", zoomRatio = 1.0, photo = FlightCameraPresentation.Photo.CONFIRMED, simulated = true)
        "camera-shooting" -> FlightCameraPresentation(recording = false, lensName = "ZOOM", zoomRatio = 3.0, photo = FlightCameraPresentation.Photo.SHOOTING, simulated = true)
        "camera-photo-failed" -> FlightCameraPresentation(recording = false, lensName = "WIDE", zoomRatio = 1.0, photo = FlightCameraPresentation.Photo.FAILED, simulated = true)
        "camera-zoom" -> FlightCameraPresentation(recording = false, lensName = "ZOOM", zoomRatio = 6.0, simulated = true)
        "camera-stale" -> FlightCameraPresentation(recording = true, recordingSeconds = 53, lensName = "ZOOM", zoomRatio = 6.0, stale = true, simulated = true)
        else -> cameraPresentationAt(scenario, 0)
    }

    /**
     * Bench-only timed display input. This is a UI review harness for changes
     * over time (REC counter, photo moment, failure, stale), not a camera
     * connection or a command receipt. Device returns unknown and never ticks.
     */
    fun cameraTimelineEnabled(scenario: String?): Boolean = scenario?.replace('_', '-') in setOf(
        "camera-rec-live", "camera-photo-live", "camera-photo-fail-live",
        "camera-rec-photo-live", "camera-stale-live", "camera-lens-live",
    )

    fun cameraPresentationAt(scenario: String?, elapsedMs: Long): FlightCameraPresentation {
        if (scenario == null || !cameraTimelineEnabled(scenario)) return FlightCameraPresentation()
        // `am --es` treats the trailing `-live` as an option on this device;
        // the underscore alias keeps the review harness launchable without
        // changing the semantic scenario name.
        val key = scenario.replace('_', '-')
        val elapsed = elapsedMs.coerceAtLeast(0)
        val seconds = elapsed / 1_000
        fun photo(untilShooting: Long, untilConfirmed: Long, failure: Boolean): FlightCameraPresentation.Photo =
            when {
                elapsed < untilShooting -> FlightCameraPresentation.Photo.SHOOTING
                elapsed < untilConfirmed -> if (failure) FlightCameraPresentation.Photo.FAILED
                    else FlightCameraPresentation.Photo.CONFIRMED
                else -> FlightCameraPresentation.Photo.NONE
            }
        return when (key) {
            "camera-rec-live" -> FlightCameraPresentation(
                recording = true, recordingSeconds = seconds,
                lensName = "WIDE", zoomRatio = 1.0, simulated = true,
            )
            "camera-photo-live" -> FlightCameraPresentation(
                recording = false, lensName = "WIDE", zoomRatio = 1.0,
                photo = photo(1_200, 5_000, failure = false), simulated = true,
            )
            "camera-photo-fail-live" -> FlightCameraPresentation(
                recording = false, lensName = "WIDE", zoomRatio = 1.0,
                photo = photo(1_200, 6_000, failure = true), simulated = true,
            )
            "camera-rec-photo-live" -> FlightCameraPresentation(
                recording = true, recordingSeconds = seconds, lensName = "ZOOM", zoomRatio = 3.0,
                photo = photo(1_200, 5_000, failure = false), simulated = true,
            )
            "camera-stale-live" -> FlightCameraPresentation(
                recording = true, recordingSeconds = seconds.coerceAtMost(5),
                lensName = "ZOOM", zoomRatio = 6.0, stale = elapsed >= 2_500, simulated = true,
            )
            "camera-lens-live" -> FlightCameraPresentation(
                recording = false,
                lensName = if (elapsed < 2_000) "WIDE" else "ZOOM",
                zoomRatio = when { elapsed < 2_000 -> 1.0; elapsed < 4_000 -> 3.0; else -> 6.0 },
                simulated = true,
            )
            else -> FlightCameraPresentation()
        }
    }

    /**
     * A still, licensed reference scene for HUD legibility only. It is never
     * a live frame, camera report, or evidence of decoding/display. The
     * production flavor returns null.
     */
    fun videoBackdrop(activity: Activity): Drawable? {
        if (activity.intent?.getStringExtra(SCENE_EXTRA) != "reference") return null
        return try {
            val options = BitmapFactory.Options().apply { inSampleSize = 2 }
            activity.assets.open(ASSET_SCENE).use { input ->
                val bitmap = BitmapFactory.decodeStream(input, null, options) ?: return null
                BitmapDrawable(activity.resources, bitmap)
            }
        } catch (exc: Exception) {
            Log.w("WorkspaceFixture", "reference scene unavailable: ${exc.message}")
            null
        }
    }

    /** Bench asset backing the evidence artifact (ESO photo, CC BY 4.0). */
    const val ASSET_SCENE = "workspace-scene.jpg"

    /** Allowlisted scenarios; anything else returns false untouched. */
    val SCENARIOS: Set<String> = setOf(
        "ready", "recording", "observing", "partial", "apply-error", "mission-browser",
        "mission-prepared", "mission-applied",
    )

    /**
     * Seed the bench app's own store for [scenario].
     * Returns true when the scenario state holds (or already held).
     */
    fun seed(app: RcApp, scenario: String?): Boolean {
        if (scenario == null || scenario !in SCENARIOS) return false
        val session = app.session
        if (session.link !is FakeDroneLink) return false
        if (session.coreConfigured()) return false
        if (!session.storageAvailable) return false
        val image = try {
            app.assets.open(ASSET_SCENE).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
        // Missing licensed asset is an explicit seed-not-available refusal —
        // never silently substituted with a generated unrelated image.
        if (image == null || image.isEmpty()) {
            Log.w("WorkspaceFixture", "seed($scenario) refused: missing bench asset $ASSET_SCENE")
            return false
        }
        return seedSession(session, scenario, image)
    }

    /**
     * Host-testable core: same guards + seeding against any [RcSession]
     * (bench link, no Core). [imageBytes] backs the evidence artifact;
     * null means "no image available" and artifact-bearing scenarios
     * refuse honestly.
     */
    fun seedSession(session: RcSession, scenario: String?, imageBytes: ByteArray?): Boolean {
        if (scenario == null || scenario !in SCENARIOS) return false
        if (session.link !is FakeDroneLink) return false
        if (session.coreConfigured()) return false
        if (!session.storageAvailable) return false
        return try {
            when (scenario) {
                "ready" -> seedReady(session, scenario)
                "mission-browser" -> seedMissionBrowser(session)
                "mission-prepared", "mission-applied" -> seedPreparationResponse(session, scenario)
                "recording" -> seedFlight(session, scenario, imageBytes, startSession = false, fullArtifacts = true)
                "observing" -> seedFlight(session, scenario, imageBytes, startSession = true, fullArtifacts = true)
                "partial" -> seedFlight(session, scenario, imageBytes, startSession = false, fullArtifacts = false)
                "apply-error" -> seedApplyError(session, scenario)
                else -> false
            }
        } catch (exc: Exception) {
            Log.w("WorkspaceFixture", "seed($scenario) refused: ${exc.message}")
            false
        }
    }

    /** Deterministic artifact id for [scenario] (also used by tests). */
    fun artifactIdFor(scenario: String): String =
        Mop.newMsgId("fixture-workspace", scenario, "artifact")

    // -- scenarios ----------------------------------------------------------

    /** Explicit synthetic responses, without inventing a flight or runtime confirmation. */
    private fun seedPreparationResponse(session: RcSession, scenario: String): Boolean {
        if (session.activeFlight != null || session.controller != null) return false
        seedReady(session, scenario)
        val bundle = session.store.missionBundles().first { it.path("site_id").asText() == siteId(scenario) }
        val provisional = session.prepareBundle(bundle)
        val controller = session.controller ?: return false
        val boot = Mop.newMsgId("fixture-workspace", scenario, "boot")
        connectBenchLink(session)
        controller.prepare()
        session.pumpOutbound()
        session.onLinkBytes(controlBytes(scenario, provisional, boot, "MISSION_PREPARED",
            Mop.json.createObjectNode().put("bundle_sha256", bundle.path("bundle_sha256").asText()), 1))
        if (scenario == "mission-applied") {
            controller.apply()
            session.pumpOutbound()
            session.onLinkBytes(controlBytes(scenario, provisional, boot, "MISSION_APPLIED",
                Mop.json.createObjectNode().put("application_id", Mop.newMsgId("fixture-workspace", scenario, "application"))
                    .put("effective_edge_seq", 0L), 2))
        }
        return true
    }

    /** Two sites, two genuine digest-verified local bundles each; NO flight or Edge command. */
    private fun seedMissionBrowser(session: RcSession): Boolean {
        if (session.activeFlight != null || session.controller != null) return false
        for (name in listOf("mission-browser-a", "mission-browser-b")) {
            seedReady(session, name)
            val alternateId = Mop.newMsgId("fixture-workspace", name, "alternate-bundle")
            if (session.store.missionBundle(alternateId) == null) {
                val first = session.store.missionBundles().first { it.path("site_id").asText() == siteId(name) }
                val alternate = first.deepCopy()
                alternate.put("bundle_id", alternateId)
                alternate.put("bundle_sha256", ksi.rc.CanonicalJson.digestExcluding(alternate, "bundle_sha256"))
                session.store.saveMissionBundle(alternate)
            }
        }
        return true
    }

    private fun seedReady(session: RcSession, scenario: String): Boolean {
        if (session.store.activeFlight() != null) return false
        if (session.controller != null) return false
        if (session.store.missionBundles().any { it.get("site_id")?.asText() == siteId(scenario) }) {
            return true // already seeded: idempotent no-op.
        }
        saveBasis(session, scenario)
        session.composeLocalBundle(siteId(scenario), session.wallClock())
        return true
    }

    private fun seedFlight(
        session: RcSession,
        scenario: String,
        imageBytes: ByteArray?,
        startSession: Boolean,
        fullArtifacts: Boolean,
    ): Boolean {
        if (imageBytes == null) return false
        val active = session.store.activeFlight()
        if (active != null) {
            // Idempotent only for OUR flight; a foreign active flight is a
            // contradiction — never reset it.
            return active.get("site_id")?.asText() == siteId(scenario)
        }
        if (session.store.flights().any { it.get("site_id")?.asText() == siteId(scenario) }) {
            return true // earlier seed already ended: keep history, seed nothing new.
        }
        if (session.controller != null || session.pendingFlightId != null) return false
        saveBasis(session, scenario)
        val bundle = session.composeLocalBundle(siteId(scenario), session.wallClock()).bundle
        val bootId = Mop.newMsgId("fixture-workspace", scenario, "boot")
        val provisional = session.prepareBundle(bundle)
        val controller = session.controller
            ?: throw IllegalStateException("prepare attached no controller")
        connectBenchLink(session)
        controller.prepare()
        session.pumpOutbound()
        var seq = 0
        session.onLinkBytes(
            controlBytes(
                scenario, provisional, bootId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
                ++seq,
            ),
        )
        check(controller.state == MissionState.PREPARED) { "PREPARED not reached" }
        controller.apply()
        session.pumpOutbound()
        session.onLinkBytes(
            controlBytes(
                scenario, provisional, bootId, "MISSION_APPLIED",
                Mop.json.createObjectNode()
                    .put("application_id", Mop.newMsgId("fixture-workspace", scenario, "application"))
                    .put("effective_edge_seq", 0L),
                ++seq,
            ),
        )
        check(controller.state == MissionState.APPLIED) { "APPLIED not reached" }
        val record = session.startRecordingFromPrepared(bundle)
        val flightId = record.get("flight_id").asText()
        session.syncFlightState()
        check(session.store.flight(flightId)?.get("state")?.asText() == "APPLIED") {
            "flight did not follow the confirmed digest"
        }
        if (startSession) {
            session.requestSessionStart()
            session.pumpOutbound()
            val startMsgId = controller.endpoint.retransmissions()
                .first { it.get("type").asText() == "SESSION_START" }
                .get("msg_id").asText()
            session.onLinkBytes(
                controlBytes(
                    scenario, flightId, null, "ACK",
                    Mop.json.createObjectNode().put("ack_of", startMsgId),
                    ++seq,
                ),
            )
            check(controller.state == MissionState.SESSION_PENDING) { "session not pending" }
            session.onLinkBytes(
                controlBytes(scenario, flightId, bootId, "MISSION_STATUS", boundRunningStatus(bundle, bootId), ++seq),
            )
            check(controller.state == MissionState.RUNNING) { "RUNNING not confirmed" }
            check(controller.sessionActive && controller.analysisConfirmed) { "no bound frame proof" }
        }
        feedEvidence(session, scenario, flightId, bootId, imageBytes, fullArtifacts, seq)
        return true
    }

    private fun seedApplyError(session: RcSession, scenario: String): Boolean {
        if (session.store.activeFlight() != null) return false
        if (session.controller != null || session.pendingFlightId != null) {
            // Ours has no flight row; a re-entry sees the still-attached
            // REJECTED controller as already-seeded.
            val attached = session.controller
            return attached != null &&
                attached.state == MissionState.REJECTED &&
                session.store.missionBundles().any { it.get("site_id")?.asText() == siteId(scenario) }
        }
        if (session.store.missionBundles().any { it.get("site_id")?.asText() == siteId(scenario) }) {
            return true
        }
        saveBasis(session, scenario)
        val bundle = session.composeLocalBundle(siteId(scenario), session.wallClock()).bundle
        val bootId = Mop.newMsgId("fixture-workspace", scenario, "boot")
        val provisional = session.prepareBundle(bundle)
        val controller = session.controller
            ?: throw IllegalStateException("prepare attached no controller")
        connectBenchLink(session)
        controller.prepare()
        session.pumpOutbound()
        session.onLinkBytes(
            controlBytes(
                scenario, provisional, bootId, "MISSION_PREPARED",
                Mop.json.createObjectNode()
                    .put("bundle_sha256", bundle.get("bundle_sha256").asText()),
                1,
            ),
        )
        check(controller.state == MissionState.PREPARED) { "PREPARED not reached" }
        controller.apply()
        session.pumpOutbound()
        val reasons = Mop.json.createArrayNode()
            .add("policy_install_rejected")
            .add("fixture_synthetic_zone_revision")
        val rejection = Mop.json.createObjectNode()
        rejection.set<com.fasterxml.jackson.databind.JsonNode>("reasons", reasons)
        session.onLinkBytes(controlBytes(scenario, provisional, bootId, "MISSION_APPLY_REJECTED", rejection, 2))
        check(controller.state == MissionState.REJECTED) { "REJECTED not reported" }
        check(controller.lastRejectionReasons.isNotEmpty()) { "rejection reasons missing" }
        // Deliberately NO flight record: rejection precedes 기록 시작.
        check(session.store.activeFlight() == null) { "rejection must not fabricate a flight" }
        return true
    }

    // -- basis rows (deterministic, idempotent) ------------------------------

    private fun siteId(scenario: String): String =
        Mop.newMsgId("fixture-workspace", scenario, "site")

    private fun saveBasis(session: RcSession, scenario: String) {
        val site = siteId(scenario)
        val zone = Mop.newMsgId("fixture-workspace", scenario, "zone")
        val rev = Mop.newMsgId("fixture-workspace", scenario, "zone-rev")
        val missionRev = Mop.newMsgId("fixture-workspace", scenario, "mission-rev")
        val siteNode = Mop.json.createObjectNode().apply {
            put("site_id", site)
            put("org_id", Mop.newMsgId("fixture-workspace", "org"))
            put("name", "[SIMULATED] ESO 참고 현장 · $scenario")
            putNull("center_lat")
            putNull("center_lon")
            putNull("radius_m")
        }
        session.store.saveSite(siteNode)
        val revision = Mop.json.createObjectNode().apply {
            put("zone_id", zone)
            put("revision_id", rev)
            putNull("base_revision_id")
            put("site_id", site)
            val geometry = putObject("geometry_wgs84")
            geometry.put("type", "Polygon")
            val ring = geometry.putArray("coordinates").addArray()
            ring.addArray().add(127.0).add(37.0)
            ring.addArray().add(127.0008).add(37.0)
            ring.addArray().add(127.0008).add(37.0007)
            ring.addArray().add(127.0).add(37.0)
            put("rule_kind", "PERSON_ENTRY")
        }
        session.store.saveZoneRevision(revision)
        session.store.saveMissionSnapshot(
            missionSnapshot(
                site = site,
                missionRev = missionRev,
                zoneId = zone,
                rev = rev,
            ),
        )
    }

    private fun missionSnapshot(site: String, missionRev: String, zoneId: String, rev: String): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("mission_id", Mop.newMsgId("fixture-workspace", "mission", site))
        node.put("flight_id", Mop.newMsgId("fixture-workspace", "mission-flight", site))
        node.put("org_id", Mop.newMsgId("fixture-workspace", "org"))
        node.put("site_id", site)
        node.put("mission_revision_id", missionRev)
        node.put("origin", "SERVER")
        node.put("created_at_utc", "2026-10-24T00:00:00Z")
        val zoneArr = node.putArray("zones")
        val entry = zoneArr.addObject()
        entry.put("zone_id", zoneId)
        entry.put("revision_id", rev)
        entry.put("rule_kind", "PERSON_ENTRY")
        val geometry = entry.putObject("geometry_wgs84")
        geometry.put("type", "Polygon")
        val ring = geometry.putArray("coordinates").addArray()
        ring.addArray().add(127.0).add(37.0)
        ring.addArray().add(127.0008).add(37.0)
        ring.addArray().add(127.0008).add(37.0007)
        ring.addArray().add(127.0).add(37.0)
        entry.put("zone_sha256", "0".repeat(64))
        entry.put("enabled", true)
        node.putArray("model_profiles").addObject()
            .put("model_id", "ksi-person-detector")
            .put("model_digest", "a".repeat(64))
            .put("enabled", true)
        node.putObject("recording_policy").put("rc_proxy", true)
        node.put(
            "content_sha256",
            ksi.rc.CanonicalJson.digestExcluding(node, "content_sha256"),
        )
        return node
    }

    // -- MOP helpers (real ingest path, no reflection) -----------------------

    private fun connectBenchLink(session: RcSession) {
        val link = session.link
        if (link is FakeDroneLink && link.state == DroneLink.State.DISCONNECTED) {
            link.connect()
        }
    }

    private fun controlBytes(
        scenario: String,
        flightId: String,
        bootId: String?,
        type: String,
        payload: ObjectNode,
        seq: Int,
    ): ByteArray {
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("fixture-workspace", scenario, "ctl", type, seq.toString()),
            channel = "CONTROL_RELIABLE",
            type = type,
            flightId = flightId,
            edgeBootId = bootId,
            seq = null,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 1_000_000L + seq,
        )
        return Mop.json.writeValueAsBytes(env)
    }

    private fun dataBytes(
        scenario: String,
        flightId: String,
        bootId: String,
        type: String,
        channel: String,
        payload: ObjectNode,
        seq: Int,
    ): ByteArray {
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("fixture-workspace", scenario, "ev", type, seq.toString()),
            channel = channel,
            type = type,
            flightId = flightId,
            edgeBootId = bootId,
            seq = seq,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 2_000_000L + seq,
        )
        return Mop.json.writeValueAsBytes(env)
    }

    /**
     * Bound RUNNING report for OUR install: names our bundle digest AND the
     * install hash our bundle installs, with engine-ok frame proof. A
     * foreign or hash-less report would stay display-only — this one binds.
     */
    private fun boundRunningStatus(bundle: ObjectNode, bootId: String): ObjectNode {
        val installHash = EffectiveConfigHash.expectedFor(bundle)
            ?: throw IllegalStateException("composed bundle has no provable install hash")
        return Mop.json.createObjectNode().apply {
            put("edge_boot_id", bootId)
            put("applied_bundle_sha256", bundle.get("bundle_sha256").asText())
            put("effective_config_hash", installHash)
            put("runtime_state", "RUNNING")
            put("runtime_epoch", "fixture-epoch-1")
            put("config_applied", true)
            put("processed_frame_count", 42L)
            put("last_processed_frame_age_ms", 120L)
            val input = putObject("input_state")
            input.put("engine", "ok")
            input.put("video", "ok")
            input.put("telemetry", "ok")
        }
    }

    /**
     * Synthetic but schema-shaped evidence: one OBSERVATION, one SHADOW
     * SPATIAL_CANDIDATE event linking the artifact, and the artifact itself
     * (`ARTIFACT_ANNOUNCE` + chunks). `fullArtifacts = false` announces the
     * artifact but withholds every chunk — stored links with missing bytes
     * (the `partial` scenario). Never ACTIVE, never an Incident/Alert.
     */
    private fun feedEvidence(
        session: RcSession,
        scenario: String,
        flightId: String,
        bootId: String,
        imageBytes: ByteArray,
        full: Boolean,
        startSeq: Int,
    ) {
        var seq = startSeq
        val missionRev = Mop.newMsgId("fixture-workspace", scenario, "mission-rev")
        val obsId = Mop.newMsgId("fixture-workspace", scenario, "obs")
        val artifactId = artifactIdFor(scenario)
        session.onLinkBytes(
            dataBytes(
                scenario, flightId, bootId, "OBSERVATION", "EVENT_RELIABLE",
                observationPayload(flightId, bootId, missionRev, obsId), ++seq,
            ),
        )
        session.onLinkBytes(
            dataBytes(
                scenario, flightId, bootId, "DETECTION_EVENT", "EVENT_RELIABLE",
                eventPayload(flightId, obsId, artifactId), ++seq,
            ),
        )
        val announce = Mop.json.createObjectNode().apply {
            put("schema_version", 1)
            put("artifact_id", artifactId)
            put("flight_id", flightId)
            put("edge_boot_id", bootId)
            put("source_kind", "EDGE_SNAPSHOT")
            put("sha256", sha256(imageBytes))
            put("size_bytes", imageBytes.size.toLong())
            put("media_type", "image/jpeg")
            put("required", true)
            putNull("segment_index")
        }
        session.onLinkBytes(
            dataBytes(scenario, flightId, bootId, "ARTIFACT_ANNOUNCE", "EVIDENCE_TRANSFER", announce, ++seq),
        )
        if (full) {
            var offset = 0
            while (offset < imageBytes.size) {
                val end = minOf(offset + 4096, imageBytes.size)
                val chunk = Mop.json.createObjectNode().apply {
                    put("artifact_id", artifactId)
                    put("offset", offset.toLong())
                    put("data_b64", Base64.getEncoder().encodeToString(imageBytes.copyOfRange(offset, end)))
                }
                session.onLinkBytes(
                    dataBytes(scenario, flightId, bootId, "ARTIFACT_CHUNK", "EVIDENCE_TRANSFER", chunk, ++seq),
                )
                offset = end
            }
        }
    }

    private fun observationPayload(
        flightId: String,
        bootId: String,
        missionRev: String,
        obsId: String,
    ): ObjectNode {
        val payload = Mop.json.createObjectNode()
        val obs = payload.putObject("observation")
        obs.put("schema_version", 1)
        obs.put("observation_id", obsId)
        obs.put("flight_id", flightId)
        obs.put("mission_revision_id", missionRev)
        obs.put("edge_boot_id", bootId)
        obs.put("source_kind", "PSDK_EDGE")
        obs.put("edge_frame_seq", 7L)
        obs.put("edge_frame_id", "fixture-frame-7")
        obs.put("lens_id", "wide")
        obs.put("frame_received_monotonic_ns", 2_000_000L)
        obs.put("model_digest", "d".repeat(64))
        obs.put("sync_quality", "DEGRADED")
        obs.put("geometry_status", "UNVERIFIED")
        obs.putArray("telemetry_refs")
        val detections = obs.putArray("detections")
        val det = detections.addObject()
        det.put("detection_id", Mop.newMsgId("fixture-workspace", obsId, "det"))
        det.put("class_id", "person")
        det.put("score", 0.81)
        det.putArray("bbox_xyxy_px").add(10).add(20).add(60).add(120)
        det.putNull("track_id")
        det.putNull("position_wgs84")
        obs.putArray("quality_reasons").add("SIMULATED_FEED")
        obs.putArray("zone_revision_ids")
        return payload
    }

    private fun eventPayload(flightId: String, obsId: String, artifactId: String): ObjectNode {
        val payload = Mop.json.createObjectNode()
        val event = payload.putObject("event")
        event.put("schema_version", 1)
        event.put("event_id", Mop.newMsgId("fixture-workspace", flightId, "event"))
        event.put("flight_id", flightId)
        event.put("observation_id", obsId)
        event.put("episode_id", Mop.newMsgId("fixture-workspace", flightId, "episode"))
        event.put("event_type", "SPATIAL_CANDIDATE")
        event.put("mode", "SHADOW")
        event.put("detected_at_edge_monotonic_ns", 2_000_000L)
        event.putNull("zone_revision_id")
        event.putNull("policy_revision_id")
        event.putArray("evidence_artifact_ids").add(artifactId)
        val output = event.putObject("initial_model_output")
        output.put("rule_id", "fixture_rule")
        output.put("geometry", "GEO_ESTIMATE")
        output.put("synthetic_replay_geometry", true)
        event.put("observation_quality", "DEGRADED")
        event.put("mission_snapshot_hash", "b".repeat(64))
        return payload
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
