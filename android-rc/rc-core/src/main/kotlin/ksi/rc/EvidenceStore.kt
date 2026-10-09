package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

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

    fun observationsFor(flightId: String): List<ObjectNode>
    fun eventsFor(flightId: String): List<ObjectNode>
    fun summariesFor(flightId: String): List<ObjectNode>
    fun artifactsFor(flightId: String): List<ObjectNode>
    fun bootsFor(flightId: String): List<String>

    /** Earliest/latest received sample time for a (flight, boot), or null. */
    fun firstLastNs(flightId: String, bootId: String): Pair<Long, Long>?

    /** Data-plane sequence numbers received for a (boot, channel). */
    fun receivedSeqs(bootId: String, channel: String): Set<Long>

    /** Record an Edge-declared unrecoverable gap (idempotent by
     *  boot/channel/seq; the RC never declares gaps itself). */
    fun recordGap(gap: ObjectNode)

    /** Declared gaps for a boot, ordered by (channel, seq). */
    fun gapsForBoot(bootId: String): List<ObjectNode>
}
