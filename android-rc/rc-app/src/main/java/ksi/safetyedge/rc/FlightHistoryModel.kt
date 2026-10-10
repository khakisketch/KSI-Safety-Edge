package ksi.safetyedge.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.util.UUID
import ksi.rc.Mop
import ksi.rc.RcFullStore

/**
 * A7 flight-history model: pure derivations from the rc-core store (the
 * only source). It renders flight records — never a bare event list — each
 * carrying the scene those flights were flown under (site / mission / zone
 * revisions pinned at start), detection vs risk-candidate counts, evidence
 * completeness, observation gaps, and the sync state.
 *
 * Operator annotations are append-only: a "false positive suspected" mark
 * is a NEW `operator-annotation.v1` record; undo is a REVOKE_PREVIOUS
 * record. Detections and evidence are never edited or deleted.
 */
object FlightHistoryModel {
    const val ANNOTATION_FALSE_POSITIVE = "FALSE_POSITIVE_SUSPECTED"
    const val ANNOTATION_NEEDS_REVIEW = "NEEDS_REVIEW"
    const val ANNOTATION_REVOKE = "REVOKE_PREVIOUS"

    data class FlightRow(
        val flightId: String,
        val state: String,
        val siteId: String,
        val siteName: String?,
        val missionRevisionId: String,
        val bundleSha256: String,
        val zoneRevisionIds: List<String>,
        val startedAtUtc: String?,
        val endedAtUtc: String?,
        val lastReceivedUtc: String?,
        val observationCount: Int,
        val eventCount: Int,
        val detectionOnlyCount: Int,
        val candidateCount: Int,
        val artifactCount: Int,
        val artifactCompleteCount: Int,
        val artifactSyncedCount: Int,
        val aiUptimeRatio: Double?,
        val coveredFrames: Int?,
        val skippedFrames: Int?,
        val mopGapCount: Int,
        val annotationCount: Int,
        val falsePositiveCount: Int,
    )

    data class ZonePin(
        val revisionId: String,
        val zoneId: String?,
        val ruleKind: String?,
        /** Present in the local mission cache (A5); false = never downloaded. */
        val stored: Boolean,
    )

    data class ArtifactRow(
        val artifactId: String,
        val mediaType: String,
        val sizeBytes: Long,
        /** All bytes received (cheap level); hash-verified on view/upload. */
        val complete: Boolean,
        val synced: Boolean,
    )

    data class EventRow(
        val card: ObservationModel.EventCard,
        val effectiveAnnotation: String?,
        val annotationCount: Int,
    )

    data class FlightDetail(
        val row: FlightRow,
        val zonePins: List<ZonePin>,
        val events: List<EventRow>,
        val artifacts: List<ArtifactRow>,
        val summaryPresent: Boolean,
    )

    /** Newest flight first (history view). */
    fun flights(store: RcFullStore): List<FlightRow> =
        store.flights().asReversed().map { rowOf(store, it) }

    fun detail(store: RcFullStore, flightId: String): FlightDetail? {
        val record = store.flight(flightId) ?: return null
        val row = rowOf(store, record)
        val zonePins = row.zoneRevisionIds.map { revisionId ->
            val revision = store.zoneRevision(revisionId)
            ZonePin(
                revisionId = revisionId,
                zoneId = revision?.get("zone_id")?.asText(),
                ruleKind = revision?.get("rule_kind")?.asText(),
                stored = revision != null,
            )
        }
        val events = ObservationModel.eventCards(store, flightId).map { card ->
            val records = store.annotationsFor(card.eventId)
            EventRow(card, effectiveAnnotation(records), records.size)
        }
        val artifacts = store.artifactsFor(flightId).map { artifact ->
            val id = artifact.get("artifact_id").asText()
            ArtifactRow(
                artifactId = id,
                mediaType = artifact.get("media_type")?.asText()
                    ?: "application/octet-stream",
                sizeBytes = artifact.get("size_bytes")?.asLong() ?: 0L,
                complete = store.isArtifactComplete(id),
                synced = store.isArtifactSynced(id),
            )
        }
        return FlightDetail(
            row = row,
            zonePins = zonePins,
            events = events,
            artifacts = artifacts,
            summaryPresent = store.summariesFor(flightId).isNotEmpty(),
        )
    }

    private fun rowOf(store: RcFullStore, record: ObjectNode): FlightRow {
        val flightId = record.get("flight_id").asText()
        val siteId = record.get("site_id")?.asText() ?: "-"
        val events = store.eventsFor(flightId)
        val artifacts = store.artifactsFor(flightId)
        val summary = store.summariesFor(flightId).lastOrNull()
        val eventAnnotations = events.map {
            store.annotationsFor(it.get("event_id").asText())
        }
        return FlightRow(
            flightId = flightId,
            state = record.get("state")?.asText() ?: "UNKNOWN",
            siteId = siteId,
            siteName = store.site(siteId)?.get("name")?.asText(),
            missionRevisionId = record.get("mission_revision_id")?.asText() ?: "-",
            bundleSha256 = record.get("bundle_sha256")?.asText() ?: "-",
            zoneRevisionIds = record.get("zone_revision_ids")
                ?.mapNotNull { if (it.isTextual) it.asText() else null } ?: emptyList(),
            startedAtUtc = record.get("started_at_utc")?.takeIf { it.isTextual }?.asText(),
            endedAtUtc = record.get("ended_at_utc")?.takeIf { it.isTextual }?.asText(),
            lastReceivedUtc = store.lastReceivedUtc(flightId),
            observationCount = store.observationsFor(flightId).size,
            eventCount = events.size,
            detectionOnlyCount = events.count {
                it.get("event_type")?.asText() == "DETECTED_ONLY"
            },
            candidateCount = events.count {
                it.get("event_type")?.asText() != "DETECTED_ONLY"
            },
            artifactCount = artifacts.size,
            artifactCompleteCount = artifacts.count {
                store.isArtifactComplete(it.get("artifact_id").asText())
            },
            artifactSyncedCount = artifacts.count {
                store.isArtifactSynced(it.get("artifact_id").asText())
            },
            aiUptimeRatio = summary?.get("ai_uptime_ratio")
                ?.takeIf { it.isNumber }?.asDouble(),
            coveredFrames = summary?.get("covered_frames")
                ?.takeIf { it.isNumber }?.asInt(),
            skippedFrames = summary?.get("skipped_frames")
                ?.takeIf { it.isNumber }?.asInt(),
            mopGapCount = store.bootsFor(flightId).sumOf { store.gapsForBoot(it).size },
            annotationCount = eventAnnotations.sumOf { it.size },
            falsePositiveCount = eventAnnotations.count {
                effectiveAnnotation(it) == ANNOTATION_FALSE_POSITIVE
            },
        )
    }

    /**
     * Append-only chain -> effective state: a FALSE_POSITIVE_SUSPECTED or
     * NEEDS_REVIEW record sets the state; a later REVOKE_PREVIOUS clears it.
     */
    fun effectiveAnnotation(records: List<ObjectNode>): String? {
        var effective: String? = null
        for (record in records) {
            when (record.get("annotation_type")?.asText()) {
                ANNOTATION_FALSE_POSITIVE -> effective = ANNOTATION_FALSE_POSITIVE
                ANNOTATION_NEEDS_REVIEW -> effective = ANNOTATION_NEEDS_REVIEW
                ANNOTATION_REVOKE -> effective = null
            }
        }
        return effective
    }

    /** New `FALSE_POSITIVE_SUSPECTED` record for an event (never edits it). */
    fun falsePositiveAnnotation(
        eventId: String,
        flightId: String,
        deviceId: String,
        artifactIds: List<String>,
        createdAtLocal: String,
    ): ObjectNode = build(
        eventId, flightId, deviceId, ANNOTATION_FALSE_POSITIVE,
        artifactIds, createdAtLocal, null,
    )

    /** Undo: a NEW REVOKE_PREVIOUS record superseding the given annotation. */
    fun revokePrevious(
        target: ObjectNode,
        deviceId: String,
        createdAtLocal: String,
    ): ObjectNode = build(
        eventId = target.get("event_id").asText(),
        flightId = target.get("flight_id").asText(),
        deviceId = deviceId,
        annotationType = ANNOTATION_REVOKE,
        artifactIds = emptyList(),
        createdAtLocal = createdAtLocal,
        supersedesAnnotationId = target.get("annotation_id").asText(),
    )

    private fun build(
        eventId: String,
        flightId: String,
        deviceId: String,
        annotationType: String,
        artifactIds: List<String>,
        createdAtLocal: String,
        supersedesAnnotationId: String?,
    ): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("schema_version", 1)
        node.put("annotation_id", UUID.randomUUID().toString())
        node.put("event_id", eventId)
        node.put("flight_id", flightId)
        // Operator identity is DEVICE-scoped until an operator login exists
        // (A9/H1): both ids are real device ids, never fabricated names.
        node.put("actor_id", deviceId)
        node.put("client_id", deviceId)
        node.put("annotation_type", annotationType)
        node.putNull("reason")
        val evidence = node.putArray("evidence_ids")
        artifactIds.forEach { evidence.add(it) }
        node.put("created_at_local", createdAtLocal)
        if (supersedesAnnotationId == null) {
            node.putNull("supersedes_annotation_id")
        } else {
            node.put("supersedes_annotation_id", supersedesAnnotationId)
        }
        return node
    }
}
