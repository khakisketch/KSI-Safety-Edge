package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Typed Core HTTP client (A3-b): the upload surface the RC uses after the
 * flight (register -> applications -> sessions -> batches -> manifest
 * ingest) plus the read surface for local-first query fallback. Endpoints
 * and payload shapes are the frozen OpenAPI surface (`/api/v1/edge`) and
 * mirror the validated harness `CoreClient`.
 *
 * Nothing here retries or marks local state: [EvidenceUploader] owns the
 * resumability semantics, and only a server COMMITTED response may lead to
 * a SYNCED mark.
 */
class CoreApi(private val transport: CoreTransport) {
    private val json = Mop.json

    private fun raw(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        val response = transport.request(method, path, headers, body)
        if (response.status !in 200..299) {
            throw CoreApiError(method, path, response.status, response.bodyText)
        }
        return response.body
    }

    private fun jsonRequest(
        method: String,
        path: String,
        body: JsonNode? = null,
    ): ObjectNode {
        val payload = body?.let { json.writeValueAsBytes(it) }
        return json.readTree(raw(method, path, payload)) as ObjectNode
    }

    // -- upload surface ------------------------------------------------------

    fun registerFlight(body: ObjectNode): ObjectNode =
        jsonRequest("POST", "/flights", body)

    fun postApplication(flightId: String, application: ObjectNode): ObjectNode =
        jsonRequest("POST", "/flights/$flightId/mission-applications", application)

    fun postSession(flightId: String, session: ObjectNode): ObjectNode =
        jsonRequest("POST", "/flights/$flightId/flight-sessions", session)

    fun listSessions(flightId: String): ObjectNode =
        jsonRequest("GET", "/flights/$flightId/flight-sessions")

    fun postBatch(
        flightId: String,
        kind: String,
        clientBatchId: String,
        items: JsonNode,
    ): ObjectNode {
        val segment = BATCH_PATHS[kind]
            ?: throw IllegalArgumentException("unknown batch kind $kind")
        val body = json.createObjectNode()
        body.put("client_batch_id", clientBatchId)
        body.set<JsonNode>("items", items)
        return jsonRequest("POST", "/flights/$flightId/$segment", body)
    }

    fun openIngest(manifest: ObjectNode): ObjectNode =
        jsonRequest("POST", "/ingest-sessions", manifest)

    fun getIngest(uploadId: String): ObjectNode =
        jsonRequest("GET", "/ingest-sessions/$uploadId")

    fun putChunk(
        uploadId: String,
        artifactId: String,
        offset: Long,
        data: ByteArray,
        total: Long,
        sha256: String,
    ): ObjectNode {
        val headers = mapOf(
            "Content-Range" to "bytes $offset-${offset + data.size - 1}/$total",
            "X-Content-SHA256" to sha256,
            "Content-Type" to "application/octet-stream",
        )
        val response = raw(
            "PUT", "/ingest-sessions/$uploadId/objects/$artifactId", data, headers,
        )
        return json.readTree(response) as ObjectNode
    }

    fun completeIngest(uploadId: String, manifestSha256: String): ObjectNode {
        val body = json.createObjectNode().put("manifest_sha256", manifestSha256)
        return jsonRequest("POST", "/ingest-sessions/$uploadId/complete", body)
    }

    // -- read surface (local-first fallback) ---------------------------------

    fun getFlight(flightId: String): ObjectNode =
        jsonRequest("GET", "/flights/$flightId")

    /** Mission snapshot (carries `mission_revision_id` + `content_sha256`;
     *  the uploader resolves the manifest's mission digest from here). */
    fun getMissionSnapshot(missionId: String): ObjectNode =
        jsonRequest("GET", "/missions/$missionId/snapshot")

    fun getObservations(flightId: String): ObjectNode =
        getPaginated("/flights/$flightId/observations")

    fun getEvents(flightId: String): ObjectNode =
        getPaginated("/events?flight_id=$flightId")

    fun getCoverage(flightId: String): ObjectNode =
        jsonRequest("GET", "/flights/$flightId/coverage")

    fun getEvidence(eventId: String): ObjectNode =
        jsonRequest("GET", "/events/$eventId/evidence")

    fun downloadObject(artifactId: String): ByteArray =
        raw("GET", "/objects/$artifactId")

    /**
     * Follows `next_cursor` until exhausted and returns every page's items
     * concatenated. Evidence completeness matters more than one round trip:
     * a single unpaginated GET silently truncates at the server's page size
     * (default 100 rows). The page guard turns a misbehaving server (cursor
     * that never ends) into a loud failure instead of an infinite loop.
     */
    private fun getPaginated(path: String): ObjectNode {
        val items = json.createArrayNode()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val separator = if (path.contains('?')) '&' else '?'
            val requestPath = if (cursor == null) path else "$path${separator}cursor=$cursor"
            val page = jsonRequest("GET", requestPath)
            page.get("items")?.forEach { items.add(it) }
            val next = page.get("next_cursor")?.takeIf { !it.isNull }?.asText()
            if (next.isNullOrEmpty()) break
            cursor = next
            pages += 1
            check(pages < MAX_PAGES) { "pagination did not terminate for $path" }
        }
        return json.createObjectNode().apply { set<JsonNode>("items", items) }
    }

    companion object {
        val BATCH_PATHS = mapOf(
            "observations" to "observations:batch",
            "events" to "events:batch",
            "spatial" to "spatial-summaries:batch",
            "flight" to "flight-summaries:batch",
        )

        /** Server default is 100 rows per page; the page guard (100 pages)
         *  turns a cursor that never ends into a loud failure. */
        const val MAX_PAGES = 100
    }
}
