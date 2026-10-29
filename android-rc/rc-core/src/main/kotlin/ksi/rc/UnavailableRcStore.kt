package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException

/**
 * Fail-closed stand-in for [FileRcStore] when the app's durable open itself
 * throws (inaccessible storage root, torn journal the open could not
 * repair): there are no readable facts to retain, so reads report
 * nothing/unknown and every mutating call throws the captured [openError].
 *
 * This is a last-resort construction fallback, NOT a mock store: the
 * counter-corrupt case (facts retained) is served by the real [FileRcStore]
 * in degraded mode ([FileRcStore.storageUnavailable]). Callers must gate on
 * that flag and never present these empty reads as healthy/zero facts.
 */
class UnavailableRcStore(val openError: IOException) : RcFullStore {
    private fun fail(): Nothing = throw openError

    // -- RcStore -------------------------------------------------------------
    override fun nextMsgCounter(): Long = fail()
    override fun isProcessed(msgId: String): Boolean = false
    override fun storeMessage(env: ObjectNode): Unit = fail()
    override fun markProcessed(msgId: String): Unit = fail()
    override fun processedMsgIds(): Set<String> = emptySet()
    override fun recordApplication(application: ObjectNode): Unit = fail()
    override fun applicationsFor(flightId: String): List<ObjectNode> = emptyList()

    // -- EvidenceStore ---------------------------------------------------------
    override fun storeObservation(observation: ObjectNode, bootId: String): Unit = fail()
    override fun storeEvent(event: ObjectNode, bootId: String): Unit = fail()
    override fun storeFlightSummary(summary: ObjectNode, bootId: String): Unit = fail()
    override fun announceArtifact(announce: ObjectNode, bootId: String): Unit = fail()
    override fun writeChunk(artifactId: String, offset: Long, bytes: ByteArray): Unit = fail()
    override fun artifactBytes(artifactId: String): ByteArray? = null
    override fun isArtifactComplete(artifactId: String): Boolean = false
    override fun isArtifactIntact(artifactId: String): Boolean = false
    override fun observationsFor(flightId: String): List<ObjectNode> = emptyList()
    override fun eventsFor(flightId: String): List<ObjectNode> = emptyList()
    override fun summariesFor(flightId: String): List<ObjectNode> = emptyList()
    override fun artifactsFor(flightId: String): List<ObjectNode> = emptyList()
    override fun bootsFor(flightId: String): List<String> = emptyList()
    override fun firstLastNs(flightId: String, bootId: String): Pair<Long, Long>? = null
    override fun receivedFirstLastUtc(flightId: String, bootId: String): Pair<String, String>? = null
    override fun lastReceivedUtc(flightId: String): String? = null
    override fun lastHeartbeatUtc(flightId: String): String? = null
    // An unavailable store knows nothing: "not confirmed" is not "none".
    override fun lastHeartbeatUtcAny(): String? = null
    override fun lastObservationUtc(flightId: String): String? = null
    override fun lastEventUtc(flightId: String): String? = null
    override fun receivedSeqs(bootId: String, channel: String): Set<Long> = emptySet()
    override fun recordGap(gap: ObjectNode): Unit = fail()
    override fun gapsForBoot(bootId: String): List<ObjectNode> = emptyList()

    // -- UploadStore -------------------------------------------------------------
    override fun observationsFor(flightId: String, bootId: String): List<ObjectNode> = emptyList()
    override fun eventsFor(flightId: String, bootId: String): List<ObjectNode> = emptyList()
    override fun artifactsFor(flightId: String, bootId: String): List<ObjectNode> = emptyList()
    override fun markApplicationUploaded(applicationId: String): Unit = fail()
    override fun isApplicationUploaded(applicationId: String): Boolean = false
    override fun recordBatchReceipt(kind: String, clientBatchId: String, serverBatchId: String): Unit =
        fail()
    override fun batchReceipt(kind: String, clientBatchId: String): String? = null
    override fun recordResponseBatchReceipt(receipt: ObjectNode): Unit = fail()
    override fun responseBatchReceipt(kind: String, clientBatchId: String): ObjectNode? = null
    override fun responseBatchReceipts(kind: String, flightId: String): List<ObjectNode> =
        emptyList()
    override fun markArtifactSynced(artifactId: String): Unit = fail()
    override fun isArtifactSynced(artifactId: String): Boolean = false
    override fun recordCommitReceipt(receipt: ObjectNode): Unit = fail()
    override fun commitReceipt(manifestSha256: String): ObjectNode? = null
    override fun commitReceiptsFor(flightId: String): List<ObjectNode> = emptyList()
    override fun queueAnnotation(annotation: ObjectNode): Unit = fail()
    override fun annotationsFor(eventId: String): List<ObjectNode> = emptyList()
    override fun pendingAnnotations(): List<ObjectNode> = emptyList()
    override fun markAnnotationUploaded(annotationId: String): Unit = fail()
    override fun isAnnotationUploaded(annotationId: String): Boolean = false

    // -- MissionStore --------------------------------------------------------
    override fun startFlight(record: ObjectNode): Unit = fail()
    override fun updateFlight(flightId: String, state: String, endedAtUtc: String?): Unit = fail()
    override fun flight(flightId: String): ObjectNode? = null
    override fun flights(): List<ObjectNode> = emptyList()
    override fun activeFlight(): ObjectNode? = null
    override fun saveSite(site: ObjectNode): Unit = fail()
    override fun site(siteId: String): ObjectNode? = null
    override fun sites(): List<ObjectNode> = emptyList()
    override fun saveZoneRevision(revision: ObjectNode): Unit = fail()
    override fun zoneRevision(revisionId: String): ObjectNode? = null
    override fun zoneRevisions(siteId: String): List<ObjectNode> = emptyList()
    override fun saveMissionSnapshot(snapshot: ObjectNode): Unit = fail()
    override fun missionSnapshot(missionRevisionId: String): ObjectNode? = null
    override fun missionSnapshots(): List<ObjectNode> = emptyList()
    override fun saveMissionBundle(bundle: ObjectNode): Unit = fail()
    override fun missionBundle(bundleId: String): ObjectNode? = null
    override fun missionBundles(): List<ObjectNode> = emptyList()
}
