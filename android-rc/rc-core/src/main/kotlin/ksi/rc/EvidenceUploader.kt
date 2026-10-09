package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/** Per-boot ingest outcome (COMMITTED / PARTIAL). */
data class BootUploadReport(
    val bootId: String,
    val uploadId: String?,
    val status: String,
    val committed: Boolean,
    /** Required artifacts the server still counts as missing (PARTIAL). */
    val missingArtifacts: List<String>,
    /** Local artifacts whose bytes are not complete+verified (never sent). */
    val incompleteLocal: List<String>,
    /** Artifacts marked SYNCED by THIS run (COMMITTED only). */
    val synced: List<String>,
    val chunksSent: Int,
    /** RC receipt-time window carried into the manifest (may be null when
     *  the boot had nothing receivable and was reported instead). */
    val receivedStartedUtc: String?,
    val receivedEndedUtc: String?,
)

data class BatchUploadReport(
    val kind: String,
    val clientBatchId: String,
    val serverBatchId: String?,
    val accepted: Int,
    val duplicates: Int,
    val skipped: Boolean,
)

data class FlightUploadReport(
    val flightView: ObjectNode?,
    val applicationsUploaded: Int,
    val sessionsUploaded: Int,
    /** Server-held sessions whose stored snapshot no longer matches the
     *  locally rebuilt one (sessions are immutable server records: late data
     *  lands in a new manifest/batches, not in the old session). Surfaced so
     *  the app/operator can see the drift instead of it staying silent. */
    val sessionDrift: List<String>,
    val batches: List<BatchUploadReport>,
    val boots: List<BootUploadReport>,
    val uploadState: String,
    /** Where the manifest UTC instants come from. A verified Edge clock
     *  anchor does not exist yet (H1): this is RC receipt time, NOT the
     *  confirmed event time. */
    val timeBasis: String = TIME_BASIS,
) {
    companion object {
        const val TIME_BASIS = "RC_RECEIVE_APPROX"
    }
}

/**
 * RC -> Core upload orchestration (A3-b). Semantics (user contract,
 * 2026-10-09):
 *
 * - **COMMITTED before SYNCED**: a local artifact is marked synced only
 *   after the server's ingest session returns COMMITTED for its manifest
 *   (which verifies required artifact sizes AND sha256 on the server side).
 *   Nothing deletes evidence.
 * - **Restart/resend safe**: every step is idempotent — the flight register
 *   and record posts dedupe by content, batches by content-derived client
 *   id, the ingest session resumes by manifest digest (`RESUMING` + the
 *   server's uploaded-artifact list), and upload receipts are durable in
 *   the store. `uploadFlight` can be re-run at any time; a transport
 *   failure aborts it and the next call resumes.
 * - **Local bytes only**: an artifact whose local coverage+hash is not
 *   complete is never sent (it is reported in `incompleteLocal`); the boot
 *   upload completes honestly as PARTIAL in that case.
 *
 * Receipts are trusted locally: a server that lost its state while the RC
 * directory is intact is not re-detected automatically (a manual receipt
 * reset forces re-upload, which is safe — the server re-verifies sha256 at
 * every ingest commit).
 */
class EvidenceUploader(
    private val core: CoreApi,
    private val store: UploadStore,
    /** Chunk size for resumable object upload (default 64 KiB). */
    private val chunkBytes: Int = 64 * 1024,
) {
    init {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
    }

    private val manifestBuilder = ManifestBuilder(store)
    private val sessionBuilder = SessionBuilder(store)

    fun uploadFlight(spec: FlightUploadSpec): FlightUploadReport {
        val boots = sessionBuilder.sortedBoots(spec.flightId)
        // Register time is the earliest RC receipt instant — never a
        // converted Edge monotonic value (no verified cross-device clock).
        val firstUtc = boots.mapNotNull {
            store.receivedFirstLastUtc(spec.flightId, it)?.first
        }.minOrNull()
        if (firstUtc == null) {
            // Nothing stamped was ever received: there is no honest time
            // for a register body, so nothing is sent.
            return FlightUploadReport(
                flightView = null,
                applicationsUploaded = 0,
                sessionsUploaded = 0,
                sessionDrift = emptyList(),
                batches = emptyList(),
                boots = emptyList(),
                uploadState = "NO_DATA",
            )
        }
        core.registerFlight(registerBody(spec, firstUtc))

        val applicationsUploaded = uploadApplications(spec)
        val (sessionsUploaded, sessionDrift) = uploadSessions(spec, boots)
        val batches = uploadBatches(spec)
        val bootReports = boots.map { uploadBoot(spec, it) }
        val view = core.getFlight(spec.flightId)
        return FlightUploadReport(
            flightView = view,
            applicationsUploaded = applicationsUploaded,
            sessionsUploaded = sessionsUploaded,
            sessionDrift = sessionDrift,
            batches = batches,
            boots = bootReports,
            uploadState = view.get("upload_state")?.asText() ?: "UNKNOWN",
        )
    }

    private fun registerBody(spec: FlightUploadSpec, firstUtc: String): ObjectNode {
        val body = Mop.json.createObjectNode()
        body.put("flight_id", spec.flightId)
        body.put("site_id", spec.siteId)
        body.put("rc_device_id", spec.rcDeviceId)
        body.put("mission_id", spec.missionId)
        body.put("mission_revision_id", spec.missionRevisionId)
        body.put("bundle_sha256", spec.bundleSha256)
        // RC receipt time (approximate flight window); the register body is
        // immutable on the server, so the end instant stays null here.
        body.put("started_at_utc", firstUtc)
        body.putNull("ended_at_utc")
        return body
    }

    private fun uploadApplications(spec: FlightUploadSpec): Int {
        var uploaded = 0
        for (application in store.applicationsFor(spec.flightId)) {
            val id = application.get("application_id").asText()
            if (store.isApplicationUploaded(id)) continue
            core.postApplication(spec.flightId, application)
            store.markApplicationUploaded(id)
            uploaded += 1
        }
        return uploaded
    }

    private fun uploadSessions(
        spec: FlightUploadSpec,
        boots: List<String>,
    ): Pair<Int, List<String>> {
        // Sessions are immutable snapshots on the server: post each boot's
        // session once, skip ids the server already holds, and report any
        // snapshot whose content no longer matches (late data).
        val serverSessions = core.listSessions(spec.flightId)
            .get("items")?.mapNotNull { it as? ObjectNode } ?: emptyList()
        val serverById = serverSessions.associateBy {
            it.get("session_id")?.asText() ?: ""
        }
        var uploaded = 0
        val drift = mutableListOf<String>()
        for (boot in boots) {
            val session = sessionBuilder.build(spec, boot)
            val sessionId = session.get("session_id").asText()
            val serverCopy = serverById[sessionId]
            if (serverCopy != null) {
                if (!CanonicalJson.canonicalBytes(serverCopy)
                        .contentEquals(CanonicalJson.canonicalBytes(session))
                ) {
                    drift.add(sessionId)
                }
                continue
            }
            core.postSession(spec.flightId, session)
            uploaded += 1
        }
        return uploaded to drift
    }

    private fun uploadBatches(spec: FlightUploadSpec): List<BatchUploadReport> {
        val byKind = linkedMapOf(
            "observations" to store.observationsFor(spec.flightId),
            "events" to store.eventsFor(spec.flightId),
            // The Edge does not emit spatial summaries yet (harness parity:
            // skipped when empty); they will use `spatial-summaries:batch`.
            "spatial" to emptyList<ObjectNode>(),
            "flight" to store.summariesFor(spec.flightId),
        )
        val reports = mutableListOf<BatchUploadReport>()
        for ((kind, items) in byKind) {
            if (items.isEmpty()) continue
            val itemsArray = Mop.json.createArrayNode()
            items.forEach { itemsArray.add(it) }
            // Content-derived client batch id: a retry of identical content
            // reuses the id (server-side dedup), while new items create a
            // NEW batch instead of a permanent 409 (the harness used a
            // flight+kind id, which cannot survive data growth).
            val clientBatchId = Mop.newMsgId(
                "batch", spec.flightId, kind, CanonicalJson.digestSha256(itemsArray),
            )
            val existingReceipt = store.batchReceipt(kind, clientBatchId)
            if (existingReceipt != null) {
                reports.add(
                    BatchUploadReport(
                        kind, clientBatchId, existingReceipt, 0, 0, skipped = true,
                    ),
                )
                continue
            }
            val response = core.postBatch(spec.flightId, kind, clientBatchId, itemsArray)
            val serverId = response.get("server_batch_id")?.asText()
            if (serverId != null) store.recordBatchReceipt(kind, clientBatchId, serverId)
            reports.add(
                BatchUploadReport(
                    kind = kind,
                    clientBatchId = clientBatchId,
                    serverBatchId = serverId,
                    accepted = response.get("accepted_ids")?.size() ?: 0,
                    duplicates = response.get("duplicate_ids")?.size() ?: 0,
                    skipped = false,
                ),
            )
        }
        return reports
    }

    private fun uploadBoot(spec: FlightUploadSpec, bootId: String): BootUploadReport {
        val manifest = try {
            manifestBuilder.build(spec, bootId)
        } catch (exc: IllegalStateException) {
            // No stamped receipt time for this boot: nothing honest to send.
            return BootUploadReport(
                bootId = bootId,
                uploadId = null,
                status = "NO_RECEIPT_TIME",
                committed = false,
                missingArtifacts = emptyList(),
                incompleteLocal = emptyList(),
                synced = emptyList(),
                chunksSent = 0,
                receivedStartedUtc = null,
                receivedEndedUtc = null,
            )
        }
        val manifestSha = manifest.get("manifest_sha256").asText()
        val open = core.openIngest(manifest)
        val uploadId = open.get("upload_id")?.asText() ?: ""
        val status = core.getIngest(uploadId)
        val resident = status.get("uploaded_artifact_ids")
            ?.mapNotNull { if (it.isTextual) it.asText() else null }
            ?.toSet() ?: emptySet()
        val entries = manifest.get("artifacts")
            ?.mapNotNull { it as? ObjectNode } ?: emptyList()
        val incompleteLocal = mutableListOf<String>()
        var chunksSent = 0
        for (entry in entries) {
            val artifactId = entry.get("artifact_id").asText()
            if (artifactId in resident) continue
            val bytes = store.artifactBytes(artifactId)
            if (bytes == null) {
                // Never upload unverified bytes: the artifact is incomplete
                // or its sha256 does not match the announcement.
                incompleteLocal.add(artifactId)
                continue
            }
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkBytes, bytes.size)
                core.putChunk(
                    uploadId = uploadId,
                    artifactId = artifactId,
                    offset = offset.toLong(),
                    data = bytes.copyOfRange(offset, end),
                    total = entry.get("size_bytes").asLong(),
                    sha256 = entry.get("sha256").asText(),
                )
                chunksSent += 1
                offset = end
            }
        }
        val done = core.completeIngest(uploadId, manifestSha)
        val doneStatus = done.get("status")?.asText() ?: "UNKNOWN"
        val committed = doneStatus == "COMMITTED"
        val missing = done.get("missing_artifact_ids")
            ?.mapNotNull { if (it.isTextual) it.asText() else null } ?: emptyList()
        val synced = mutableListOf<String>()
        if (committed) {
            // The server verified required artifacts (size + sha256); the
            // remaining entries are exactly what this RC delivered.
            for (entry in entries) {
                val artifactId = entry.get("artifact_id").asText()
                if (artifactId in incompleteLocal) continue
                store.markArtifactSynced(artifactId)
                synced.add(artifactId)
            }
        }
        return BootUploadReport(
            bootId = bootId,
            uploadId = uploadId,
            status = doneStatus,
            committed = committed,
            missingArtifacts = missing,
            incompleteLocal = incompleteLocal,
            synced = synced,
            chunksSent = chunksSent,
            receivedStartedUtc = manifest.get("flight_started_at_utc").asText(),
            receivedEndedUtc = manifest.get("flight_ended_at_utc").asText(),
        )
    }
}
