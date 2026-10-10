package ksi.safetyedge.rc

import ksi.rc.UploadStore

/**
 * A6 observation model: pure derivations from the rc-core store (the ONLY
 * source of truth). Nothing here invents data, converts Edge monotonic
 * times, or merges frames with observations — it shapes what the store
 * holds into view records with explicit labels:
 *
 * - DETECTED_ONLY = a tracked/detected object record, NOT a risk event;
 * - SHADOW/CANDIDATE events are risk CANDIDATES (no warning was issued);
 * - quality/withheld flags come from the observation's own contract fields;
 * - freshness is RC RECEIPT based (Edge monotonic stays Edge-domain).
 *
 * Android-free on purpose: unit-tested on the host JVM.
 */
object ObservationModel {
    /** Event card state derived from the store, not from the UI. */
    data class EvidenceLink(
        val artifactId: String,
        val complete: Boolean,
        val synced: Boolean,
    )

    data class EventCard(
        val eventId: String,
        val eventType: String,
        val mode: String,
        val detectedAtEdgeNs: Long,
        val observationQuality: String,
        val evidence: List<EvidenceLink>,
    ) {
        /** DETECTED_ONLY events are object detections, not risk events. */
        val detectionOnly: Boolean get() = eventType == "DETECTED_ONLY"

        val evidenceComplete: Boolean
            get() = evidence.isNotEmpty() && evidence.all { it.complete }

        val evidenceSynced: Boolean
            get() = evidence.isNotEmpty() && evidence.all { it.synced }
    }

    data class ObservationPulse(
        val count: Int,
        val withheld: Int,
        val degraded: Int,
        val lastReceivedUtc: String?,
    )

    /** Newest-first event cards (insertion order reversed), capped. */
    fun eventCards(
        store: UploadStore,
        flightId: String,
        limit: Int = 20,
    ): List<EventCard> =
        store.eventsFor(flightId).takeLast(limit).reversed().map { event ->
            val artifactIds = event.get("evidence_artifact_ids")
                ?.mapNotNull { it.asText() } ?: emptyList()
            EventCard(
                eventId = event.get("event_id")?.asText() ?: "-",
                eventType = event.get("event_type")?.asText() ?: "UNKNOWN",
                mode = event.get("mode")?.asText() ?: "UNKNOWN",
                detectedAtEdgeNs =
                    event.get("detected_at_edge_monotonic_ns")?.asLong() ?: -1L,
                observationQuality =
                    event.get("observation_quality")?.asText() ?: "UNKNOWN",
                evidence = artifactIds.map { id ->
                    EvidenceLink(
                        artifactId = id,
                        // Cheap "all bytes received" level — the verified
                        // hash level is artifactBytes (viewer/upload path).
                        complete = store.isArtifactComplete(id),
                        synced = store.isArtifactSynced(id),
                    )
                },
            )
        }

    fun observationPulse(store: UploadStore, flightId: String): ObservationPulse {
        val observations = store.observationsFor(flightId)
        return ObservationPulse(
            count = observations.size,
            withheld = observations.count {
                it.get("geometry_status")?.asText() == "WITHHELD"
            },
            degraded = observations.count {
                it.get("sync_quality")?.asText() == "DEGRADED"
            },
            lastReceivedUtc = store.lastReceivedUtc(flightId),
        )
    }

    /**
     * Age of the newest received record on the RC receipt clock, in ms.
     * Null when nothing was received or the timestamps do not parse —
     * the caller shows "no data", never a guessed value.
     */
    fun lastDataAgeMs(lastReceivedUtc: String?, nowUtc: String): Long? {
        if (lastReceivedUtc.isNullOrEmpty()) return null
        return try {
            val last = java.time.Instant.parse(lastReceivedUtc)
            val now = java.time.Instant.parse(nowUtc)
            (now.toEpochMilli() - last.toEpochMilli()).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }

    /** "no data for N s" threshold used to WARN, not to judge safety. */
    const val STALE_DATA_MS = 5_000L

    /** Display-only staleness threshold for the Manifold heartbeat; the
     *  real beat cadence is an Edge/contract item (H1). */
    const val HEARTBEAT_STALE_MS = 30_000L

    fun heartbeatAgeMs(store: UploadStore, flightId: String, nowUtc: String): Long? =
        lastDataAgeMs(store.lastHeartbeatUtc(flightId), nowUtc)

    fun observationAgeMs(store: UploadStore, flightId: String, nowUtc: String): Long? =
        lastDataAgeMs(store.lastObservationUtc(flightId), nowUtc)

    fun eventAgeMs(store: UploadStore, flightId: String, nowUtc: String): Long? =
        lastDataAgeMs(store.lastEventUtc(flightId), nowUtc)

    /** OK / STALE / NONE — display wording only, never a safety claim. */
    fun classifyFreshness(ageMs: Long?, staleMs: Long): String = when {
        ageMs == null -> "NONE"
        ageMs >= staleMs -> "STALE"
        else -> "OK"
    }

    /**
     * A8 AI verdict, separating "no events" from "no observation":
     * observations flowing with zero events is a healthy analysis with no
     * detections — NOT an AI failure and NOT a "safe" claim.
     */
    fun aiVerdict(observationCount: Int, eventCount: Int): String = when {
        observationCount == 0 -> "관측 공백 — 수신된 관측 없음"
        eventCount == 0 -> "분석됨(관측 ${observationCount}) · 사건 0건"
        else -> "사건 ${eventCount}건 수신 (관측 ${observationCount})"
    }
}
