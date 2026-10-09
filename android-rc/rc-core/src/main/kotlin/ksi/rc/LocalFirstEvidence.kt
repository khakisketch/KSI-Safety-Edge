package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

enum class EvidenceSource { LOCAL, SERVER }

/** Query result plus where it came from (never ambiguous to the caller). */
data class EvidenceSlice<T>(val items: T, val source: EvidenceSource)

/**
 * Local-first evidence query (A3-b): the RC serves what it holds locally
 * (offline field use); the server is consulted ONLY when the local store
 * has nothing for that query. Contents are never merged or rewritten —
 * the source label tells the caller exactly what it is looking at.
 */
class LocalFirstEvidence(
    private val store: EvidenceStore,
    private val core: CoreApi,
) {
    fun events(flightId: String): EvidenceSlice<List<ObjectNode>> {
        val local = store.eventsFor(flightId)
        if (local.isNotEmpty()) return EvidenceSlice(local, EvidenceSource.LOCAL)
        return EvidenceSlice(itemsOf(core.getEvents(flightId)), EvidenceSource.SERVER)
    }

    fun observations(flightId: String): EvidenceSlice<List<ObjectNode>> {
        val local = store.observationsFor(flightId)
        if (local.isNotEmpty()) return EvidenceSlice(local, EvidenceSource.LOCAL)
        return EvidenceSlice(
            itemsOf(core.getObservations(flightId)), EvidenceSource.SERVER,
        )
    }

    /**
     * Local bytes when the RC holds a verified complete artifact; the
     * server copy is fetched ONLY when the local one is absent/incomplete.
     * Null when neither side has it (a Core 404 is an honest "not there").
     */
    fun artifactBytes(artifactId: String): EvidenceSlice<ByteArray>? {
        store.artifactBytes(artifactId)?.let {
            return EvidenceSlice(it, EvidenceSource.LOCAL)
        }
        return try {
            EvidenceSlice(core.downloadObject(artifactId), EvidenceSource.SERVER)
        } catch (exc: CoreApiError) {
            if (exc.status == 404) null else throw exc
        }
    }

    private fun itemsOf(view: ObjectNode): List<ObjectNode> =
        view.get("items")?.mapNotNull { it as? ObjectNode } ?: emptyList()
}
