package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Builds the `flight-upload-manifest.v1` for one Edge boot (A3-b). The
 * shape, gap-run merging, and synthesized-gap rule mirror the validated
 * harness (`rc_upload.build_manifest` + `_synthesize_missing_gaps`); the
 * digest is the same RFC 8785 field-excluded SHA256 the server verifies.
 *
 * The RC never invents gaps: it records only the Edge's declarations
 * ([EvidenceIngest] HEARTBEAT); `SYNTHESIZED:*` rows are added solely for
 * seqs missing inside a channel where the Edge already declared a gap.
 *
 * Time mapping: manifest UTC instants are the Edge monotonic ns mapped by
 * [toUtc]. In the harness this mapper is a fixture epoch; on the real RC it
 * is supplied by the app from the Edge's clock anchor (H1/MSDK follow-up).
 */
class ManifestBuilder(
    private val store: UploadStore,
    private val toUtc: (Long) -> String,
) {
    fun build(spec: FlightUploadSpec, bootId: String): ObjectNode {
        synthesizeMissingGaps(spec.flightId, bootId)
        val (firstNs, lastNs) = store.firstLastNs(spec.flightId, bootId) ?: (0L to 0L)
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
        manifest.put("flight_started_at_utc", toUtc(firstNs))
        manifest.put("flight_ended_at_utc", toUtc(lastNs))
        val artifacts = manifest.putArray("artifacts")
        for (artifact in store.artifactsFor(spec.flightId, bootId)) {
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
