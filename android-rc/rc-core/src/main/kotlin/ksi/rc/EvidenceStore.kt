package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Media type of a sealed machine-assessment JSON blob
 * (`ksi.assessment/v1`, SHADOW-only, zero incidents/alerts/human or
 * machine commands). The bytes travel the EXISTING generic artifact plane
 * (ARTIFACT_ANNOUNCE + ARTIFACT_CHUNK) — no new MOP type, no contract
 * change — and the sealed Edge revision stays the Core-receipt path.
 */
const val ASSESSMENT_MEDIA_TYPE = "application/vnd.ksi.assessment+json"

/** Source kind stamped on assessment artifact announcements. */
const val ASSESSMENT_SOURCE_KIND = "EDGE_ASSESSMENT"

/**
 * RC evidence store (A3): observations/events/summaries/artifacts received
 * from the Edge. The contracts (ledger §9, user 2026-10-09):
 * - relations between flight / boot / observation / event / artifact must
 *   survive an RC restart (implementations must reload them durably);
 * - evidence is never deleted before the server confirms durable storage
 *   (upload side, A3-b; this interface only grows and reads);
 * - local-first query: [observationsFor]/[eventsFor] serve the app before
 *   any server round-trip;
 * - gap/seq bookkeeping (A3-b): the RC NEVER declares gaps itself; it only
 *   records the Edge's `unrecoverable_gaps` declarations and the received
 *   data-plane sequence numbers, which feed the upload manifest.
 */
interface EvidenceStore {
    fun storeObservation(observation: ObjectNode, bootId: String)
    fun storeEvent(event: ObjectNode, bootId: String)
    fun storeFlightSummary(summary: ObjectNode, bootId: String)
    fun announceArtifact(announce: ObjectNode, bootId: String)
    fun writeChunk(artifactId: String, offset: Long, bytes: ByteArray)

    /** Assembled bytes when every declared byte has been written. */
    fun artifactBytes(artifactId: String): ByteArray?

    /**
     * CHEAP completeness (list rendering): every declared byte has been
     * received (range coverage == [0,size) and the file length matches).
     * This does NOT hash the content — [artifactBytes] is the verified
     * level (coverage + size + sha256) used by viewers and uploads. Both
     * levels are exposed on purpose so lists never pretend one is the other.
     */
    fun isArtifactComplete(artifactId: String): Boolean

    /**
     * VERIFIED level without handing out the bytes: range coverage, size and
     * the announced sha256 all match the stored file.
     *
     * WHY A SEPARATE CALL: [isArtifactComplete] is the cheap list level and
     * [artifactBytes] is the verified level but it materialises the whole
     * artifact in memory. A readiness check needs the verified truth for
     * artifacts it will not open (and must not claim "review ready" for a
     * file whose bytes do not match).
     */
    fun isArtifactIntact(artifactId: String): Boolean

    fun observationsFor(flightId: String): List<ObjectNode>
    fun eventsFor(flightId: String): List<ObjectNode>
    fun summariesFor(flightId: String): List<ObjectNode>
    fun artifactsFor(flightId: String): List<ObjectNode>

    /**
     * The stored announce for one artifact id, or null when never
     * announced. Read-only; used to reject conflicting re-announces
     * (same id, different bytes — the Edge/Core contract refuses those;
     * the RC must not silently keep the first and ACK the second).
     */
    fun announceFor(artifactId: String): ObjectNode? = null
    fun bootsFor(flightId: String): List<String>

    /**
     * Receipt metadata for one persisted detection event: the ACTUAL
     * indexed event's flight/boot linkage, RC receipt wall-clock, and
     * stored policy revision when the record carries one. Read-only; null
     * when the event was never stored — callers must resolve linkage from
     * this, never fall back to "latest" boot/receipt.
     */
    fun eventReceipt(eventId: String): EventReceipt? = null

    /** Earliest/latest received sample time for a (flight, boot), or null. */
    fun firstLastNs(flightId: String, bootId: String): Pair<Long, Long>?

    /**
     * Earliest/latest **RC receipt wall-clock** (ISO-8601) for a (flight,
     * boot), or null. This is the only honest time base the RC has without
     * a verified Edge clock anchor: Edge monotonic ns is a different device
     * domain and is NEVER converted into UTC here.
     */
    fun receivedFirstLastUtc(flightId: String, bootId: String): Pair<String, String>?

    /**
     * Latest RC receipt wall-clock across all received records of a flight
     * (observations/events/summaries/artifacts/gaps), or null. Drives
     * honest "no data for N s" indicators — receipt-based, never a
     * converted Edge timestamp.
     */
    fun lastReceivedUtc(flightId: String): String?

    // -- A8: per-channel freshness ---------------------------------------------
    // Heartbeat / observation / event receipts are tracked SEPARATELY: an
    // idle period with no events is not an AI failure, and a live AI does
    // not prove the Manifold process is alive (heartbeat) or vice versa.
    // All receipt-clock based.

    /** Latest HEARTBEAT envelope receipt wall-clock, or null. */
    fun lastHeartbeatUtc(flightId: String): String?

    /**
     * Latest HEARTBEAT receipt wall-clock across ALL flights, or null.
     *
     * Link liveness is a LINK fact, not a flight fact: the Edge's heartbeat
     * arrives before the mission handshake adopts the RC's flight id, so a
     * flight-scoped lookup would report "no heartbeat" while the Edge is
     * demonstrably alive. The clock is the RC's own receipt time (never the
     * peer's stamp) — the two devices' clocks are not comparable.
     */
    fun lastHeartbeatUtcAny(): String?

    /** Latest received OBSERVATION receipt wall-clock, or null. */
    fun lastObservationUtc(flightId: String): String?

    /** Latest received DETECTION_EVENT receipt wall-clock, or null. */
    fun lastEventUtc(flightId: String): String?

    /** Data-plane sequence numbers received for a (boot, channel). */
    fun receivedSeqs(bootId: String, channel: String): Set<Long>

    /** Record an Edge-declared unrecoverable gap (idempotent by
     *  boot/channel/seq; the RC never declares gaps itself). */
    fun recordGap(gap: ObjectNode)

    /** Declared gaps for a boot, ordered by (channel, seq). */
    fun gapsForBoot(bootId: String): List<ObjectNode>

    // -- Assessments (sealed machine facts, Unit A closure) -------------------
    //
    // Assessments arrive as generic artifacts (media [ASSESSMENT_MEDIA_TYPE])
    // and need no ingest change: the ANNOUNCE/CHUNK path already stores and
    // verifies them. What was missing is the LINK: which ticks were judged,
    // with what disposition and why. These reads parse only INTACT blobs
    // (announced + complete + sha256-verified) — an incomplete assessment is
    // never presented as judged, only counted as unreceived below.
    //
    // Custody honesty: the parsed JSON's own `custody` block still says
    // `rc_received:false` (it was sealed before receipt). The RC receipt is
    // a LOCAL fact — the store's receipt clock on the announce — and the
    // viewer must compose it, never re-read the JSON's false.

    /**
     * Verified assessment JSON objects of a flight (parsed from INTACT
     * blobs only), insertion order. Empty when none arrived intact — which
     * is "unreceived", never "zero findings".
     */
    fun assessmentsFor(flightId: String): List<ObjectNode> = emptyList()

    /**
     * Announced assessment artifacts of a flight whose bytes are NOT
     * intact (incomplete or digest-mismatched): unreceived judgements.
     * Zero with [assessmentsFor] empty means "nothing was ever announced".
     */
    fun pendingAssessmentCount(flightId: String): Int = 0
}

/**
 * Receipt metadata for one persisted detection event: the ACTUAL indexed
 * event's flight/boot linkage, RC receipt wall-clock, and stored policy
 * revision when the record carries one.
 *
 * - [flightId]: owning flight from the stored record, or null when the
 *   record carries none (never invented, never defaulted).
 * - [bootId]: the Edge boot that delivered the record.
 * - [receivedUtc]: RC receipt wall-clock (ISO-8601); null only for legacy
 *   files predating receipt stamping.
 * - [policyRevisionId]: the stored `policy_revision_id`, or null when the
 *   record lacks one.
 */
data class EventReceipt(
    val eventId: String,
    val flightId: String?,
    val bootId: String,
    val receivedUtc: String?,
    val policyRevisionId: String?,
)
