package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Builds the `flight-upload-manifest.v1` for one Edge boot (A3-b). The
 * shape, gap-run merging, and synthesized-gap rule mirror the validated
 * harness (`rc_upload.build_manifest` + `_synthesize_missing_gaps`); the
 * digest is the same RFC 8785 field-excluded SHA256 the server verifies.
 *
 * TIME BASIS (A5 fix): the manifest's UTC instants are **RC receipt
 * times** (`received_wall_utc`), the only verified wall-clock the RC has.
 * Edge monotonic ns lives in a different device domain and is preserved
 * inside the records — it is NEVER converted into UTC here. A boot with
 * no receipt time cannot be manifested (the uploader reports it instead
 * of inventing a timestamp).
 *
 * The RC never invents gaps: it records only the Edge's declarations
 * ([EvidenceIngest] HEARTBEAT); `SYNTHESIZED:*` rows are added solely for
 * seqs missing inside a channel where the Edge already declared a gap.
 */
class ManifestBuilder(private val store: UploadStore) {
    fun build(spec: FlightUploadSpec, bootId: String): ObjectNode {
        synthesizeMissingGaps(spec.flightId, bootId)
        val (startedUtc, endedUtc) = store.receivedFirstLastUtc(spec.flightId, bootId)
            ?: throw IllegalStateException(
                "no RC receipt time for boot $bootId (nothing received)",
            )
        val topSeq = maxOf(
            store.receivedSeqs(bootId, "EVENT_RELIABLE").maxOrNull() ?: 0L,
            store.receivedSeqs(bootId, "EVIDENCE_TRANSFER").maxOrNull() ?: 0L,
        )
        val manifest = Mop.json.createObjectNode()
        manifest.put("schema_version", 1)
        manifest.put("flight_id", spec.flightId)
        manifest.put("mission_revision_id", spec.missionRevisionId)
        manifest.put("mission_sha256", spec.missionSha256)
        manifest.put("rc_device_id", spec.rcDeviceId)
        manifest.put("edge_boot_id", bootId)
        manifest.put("event_count", store.eventsFor(spec.flightId, bootId).size)
        manifest.put("edge_last_seq", topSeq)
        manifest.put("rc_received_last_seq", topSeq)
        manifest.put("flight_started_at_utc", startedUtc)
        manifest.put("flight_ended_at_utc", endedUtc)
        val listed = ingestibleArtifacts(spec.flightId, bootId)
        // FLIGHT_LOG snapshots supersede PER LINEAGE: the store keeps every
        // snapshot (append-only evidence — nothing is ever deleted), but the
        // manifest carries only the latest entry of EACH lineage (e.g. the
        // operator-action lineage and the session-result lineage travel
        // side by side; neither evicts the other). Otherwise a stale
        // partial snapshot would hold the flight PARTIAL forever even
        // after a newer complete snapshot exists. Lineage is derived from
        // the content-derived artifact id (no schema change, no new
        // fields). Unknown derivations get their OWN per-artifact lineage
        // (`legacy:<artifactId>`): a required log with an app-defined id
        // (e.g. a bundle snapshot vs a closeout log) is NEVER evicted by
        // another unknown log — every unknown REQUIRED artifact is
        // retained. Boots without any FLIGHT_LOG entry are unaffected (no
        // empty artifact is ever synthesized here).
        val latestFlightLog = linkedMapOf<String, String>()
        for (artifact in listed) {
            if (artifact.get("source_kind")?.asText() != OperatorActionArtifacts.SOURCE_KIND) {
                continue
            }
            val logId = artifact.get("artifact_id")?.asText() ?: continue
            val logSha = artifact.get("sha256")?.asText() ?: ""
            latestFlightLog[flightLogLineage(spec.flightId, bootId, logId, logSha)] = logId
        }
        val artifacts = manifest.putArray("artifacts")
        for (artifact in listed) {
            if (artifact.get("source_kind")?.asText() == OperatorActionArtifacts.SOURCE_KIND) {
                val entryId = artifact.get("artifact_id")?.asText()
                val entrySha = artifact.get("sha256")?.asText() ?: ""
                val entryLineage = if (entryId == null) "legacy:"
                else flightLogLineage(spec.flightId, bootId, entryId, entrySha)
                if (latestFlightLog[entryLineage] != entryId) continue
            }
            val entry = artifacts.addObject()
            entry.put("artifact_id", artifact.get("artifact_id").asText())
            entry.put("source_kind", artifact.get("source_kind").asText())
            entry.put("sha256", artifact.get("sha256").asText())
            entry.put("size_bytes", artifact.get("size_bytes").asLong())
            entry.put("media_type", artifact.get("media_type").asText())
            entry.put("required", artifact.get("required")?.asBoolean() ?: true)
            val segment = artifact.get("segment_index")
            if (segment != null && segment.isNumber) {
                entry.put("segment_index", segment.asLong())
            } else {
                entry.putNull("segment_index")
            }
        }
        val gaps = manifest.putArray("gaps")
        for (run in gapRuns(bootId)) {
            val gap = gaps.addObject()
            gap.put("kind", "MOP_EVENT_GAP")
            gap.put("start_ms", run.first().get("seq").asLong() * FRAME_MS)
            gap.put("duration_ms", run.size.toLong() * FRAME_MS)
            gap.put(
                "reason",
                run.map { it.get("reason").asText() }.toSortedSet().joinToString(";"),
            )
        }
        manifest.put(
            "manifest_sha256",
            CanonicalJson.digestExcluding(manifest, "manifest_sha256"),
        )
        return manifest
    }

    /**
     * Artifacts the frozen flight-ingest contract accepts. The
     * `flight-upload-manifest.v1` `source_kind` enum is CLOSED: an entry
     * with any other kind makes the Core reject the WHOLE manifest (422),
     * which would hold one new artifact kind hostage against every other
     * artifact of the boot. Non-listed artifacts (e.g. sealed machine
     * assessments, `EDGE_ASSESSMENT`) stay retained locally and reach the
     * Core through the producer-neutral collection path (whose
     * `source_kind` is free-form), never by relabelling them here.
     */
    fun ingestibleArtifacts(flightId: String, bootId: String): List<ObjectNode> =
        store.artifactsFor(flightId, bootId).filter {
            (it.get("source_kind")?.asText() ?: "") in INGESTIBLE_SOURCE_KINDS
        }

    /** Announced artifacts withheld from the flight-ingest manifest for the
     *  reason above (retained locally, never deleted, never relabelled). */
    fun excludedArtifacts(flightId: String, bootId: String): List<ObjectNode> =
        store.artifactsFor(flightId, bootId).filter {
            (it.get("source_kind")?.asText() ?: "") !in INGESTIBLE_SOURCE_KINDS
        }

    /** Consecutive (channel, seq+1) runs from the declared gaps. */
    private fun gapRuns(bootId: String): List<List<ObjectNode>> {
        val declared = store.gapsForBoot(bootId)
        val runs = mutableListOf<MutableList<ObjectNode>>()
        for (gap in declared) {
            val current = runs.lastOrNull()
            val previous = current?.lastOrNull()
            if (previous != null &&
                previous.get("channel").asText() == gap.get("channel").asText() &&
                gap.get("seq").asLong() == previous.get("seq").asLong() + 1L
            ) {
                current.add(gap)
            } else {
                runs.add(mutableListOf(gap))
            }
        }
        return runs
    }

    /**
     * FLIGHT_LOG lineage of one manifest artifact: the content-derived id
     * is a pure function of (flight, boot, content sha), so a lineage can
     * be re-derived without any schema change. Unknown ids each travel as
     * their OWN `legacy:<artifactId>` lineage — grouping ALL unknowns
     * together would let one required log evict another. Known lineages
     * still supersede only their own lineage.
     */
    private fun flightLogLineage(
        flightId: String,
        bootId: String,
        artifactId: String,
        sha256Hex: String,
    ): String = when {
        OperatorActionArtifacts.artifactIdFor(flightId, bootId, sha256Hex) == artifactId ->
            "operator-actions"
        SessionResultArtifacts.isLineage(flightId, bootId, artifactId, sha256Hex) ->
            SessionResultArtifacts.LINEAGE
        else -> "legacy:$artifactId"
    }

    private fun synthesizeMissingGaps(flightId: String, bootId: String) {
        val declared = store.gapsForBoot(bootId).map {
            it.get("channel").asText() to it.get("seq").asLong()
        }.toSet()
        for (channel in DATA_CHANNELS) {
            val received = store.receivedSeqs(bootId, channel)
            if (received.isEmpty()) continue
            // Only extend gap rows where the Edge already gave up on this
            // channel (harness parity: no invented gaps for leftovers).
            if (declared.none { it.first == channel }) continue
            for (seq in 1..received.max()) {
                if (seq in received || (channel to seq.toLong()) in declared) continue
                val gap = Mop.json.createObjectNode()
                gap.put("schema_version", 1)
                gap.put("edge_boot_id", bootId)
                gap.put("channel", channel)
                gap.put("seq", seq.toLong())
                gap.put("reason", "SYNTHESIZED:$channel")
                gap.put("destination", "RC_MANIFEST")
                gap.put("flight_id", flightId)
                store.recordGap(gap)
            }
        }
    }

    companion object {
        /** Harness gap-run accounting assumption (33 ms frames). */
        const val FRAME_MS = 33L

        val DATA_CHANNELS = listOf("EVENT_RELIABLE", "EVIDENCE_TRANSFER")

        /**
         * The frozen `flight-upload-manifest.v1` `source_kind` enum
         * (contracts/, byte-identical). Adding a kind needs a new `.vN`
         * contract file + approval — never a silent local widening.
         */
        val INGESTIBLE_SOURCE_KINDS = setOf(
            "EDGE_SNAPSHOT",
            "EDGE_CLIP",
            "RC_PROXY_SEGMENT",
            "CAMERA_SD_ORIGINAL",
            "FLIGHT_LOG",
            "TELEMETRY_LOG",
        )
    }
}

/**
 * Builds the `flight-session.v1` for one Edge boot (mirrors the harness
 * `synthesize_session`): lens epochs from the received observations, the
 * session id derivation shared with [MissionController], and the
 * applications recorded during this boot.
 */
class SessionBuilder(private val store: UploadStore) {
    fun build(spec: FlightUploadSpec, bootId: String): ObjectNode {
        val boots = sortedBoots(spec.flightId)
        val position = boots.indexOf(bootId).let { if (it < 0) 0 else it }
        val (firstNs, lastNs) = store.firstLastNs(spec.flightId, bootId) ?: (0L to 0L)
        val session = Mop.json.createObjectNode()
        session.put("schema_version", 1)
        session.put(
            "session_id",
            Uuid5.of(Mop.NAMESPACE, "session|${spec.flightId}|$bootId").toString(),
        )
        session.put("flight_id", spec.flightId)
        session.put("edge_boot_id", bootId)
        session.put("started_at_edge_monotonic_ns", firstNs)
        session.put("ended_at_edge_monotonic_ns", lastNs)
        session.put("end_reason", "COMPLETED")
        val appIds = session.putArray("mission_application_ids")
        for (application in store.applicationsFor(spec.flightId)) {
            if (application.get("edge_boot_id")?.asText() == bootId) {
                appIds.add(application.get("application_id").asText())
            }
        }
        val lenses = linkedMapOf<String, MutableList<Long>>()
        for (observation in store.observationsFor(spec.flightId, bootId)) {
            val lens = observation.get("lens_id")?.asText() ?: continue
            val ts = observation.get("frame_received_monotonic_ns")?.asLong() ?: continue
            lenses.getOrPut(lens) { mutableListOf() }.add(ts)
        }
        val epochs = session.putArray("tracker_epochs")
        for ((lens, times) in lenses.entries.sortedBy { it.key }) {
            val epoch = epochs.addObject()
            epoch.put("camera_stream_id", "$lens-0")
            epoch.put("tracker_epoch", position)
            epoch.put("lens_id", lens)
            epoch.put("started_at", times.min())
            epoch.put("ended_at", times.max())
        }
        return session
    }

    /** Boots ordered by their first received sample (harness parity). */
    fun sortedBoots(flightId: String): List<String> =
        store.bootsFor(flightId)
            .sortedBy { store.firstLastNs(flightId, it)?.first ?: 0L }
}
