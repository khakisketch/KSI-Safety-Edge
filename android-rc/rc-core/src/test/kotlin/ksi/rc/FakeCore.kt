package ksi.rc

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.security.MessageDigest

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
}

private class HttpError(val status: Int, val code: String) : RuntimeException(code)

/**
 * In-memory Core **contract double** for uploader tests. It mirrors the
 * semantics of `core/src/ksi_core/routers/{flights,ingest}.py` that the
 * uploader depends on: content-idempotent registers/records, content-hash
 * batch dedup with 409 conflicts, manifest-digest ingest resume
 * (`RESUMING` + uploaded list), Content-Range chunk accounting, and the
 * COMMITTED/PARTIAL decision with required-artifact size AND sha256 checks.
 * It is NOT the real server: schema validation, auth, and durability are
 * exercised by the real-Core E2E test instead.
 */
class FakeCore : CoreTransport {
    val requests = mutableListOf<String>()
    val flights = linkedMapOf<String, ObjectNode>()
    val applications = linkedMapOf<String, ObjectNode>()
    val sessions = linkedMapOf<String, ObjectNode>()
    private val batches = linkedMapOf<String, ObjectNode>()
    private val items = linkedMapOf<String, MutableList<ObjectNode>>()
    private val ingestBySha = linkedMapOf<String, String>()

    inner class Ingest(val uploadId: String, val manifest: ObjectNode) {
        var status = "OPEN"
        var durableId: String? = null
    }

    val ingests = linkedMapOf<String, Ingest>()
    private val objects = linkedMapOf<String, ByteArray>()
    private val ranges = linkedMapOf<String, MutableList<IntRange>>()

    // -- A5 mission surface ----------------------------------------------------
    val sites = linkedMapOf<String, ObjectNode>()
    private val zonesBySite = linkedMapOf<String, MutableList<ObjectNode>>()
    val missions = linkedMapOf<String, ObjectNode>()
    val bundles = linkedMapOf<String, ObjectNode>()
    private var idCounter = 0

    private fun newId(): String =
        "00000000-0000-4000-8000-%012d".format(++idCounter + 5000)

    var chunkPuts = 0
    /** Injected transport failure: chunk PUT number N throws (1-based). */
    var failAtChunkPut: Int? = null
    /** Health endpoint switch (A8 reachability tests). */
    var healthy = true
    /** Simulates a server that accepts chunk PUTs but stores nothing —
     *  local data complete yet the commit reports it missing. */
    var ignoreChunkWrites = false

    // -- operator annotations (append-only) ------------------------------------
    val annotationsByEvent = linkedMapOf<String, MutableList<ObjectNode>>()
    var failNextAnnotationPost = false

    // -- structured operator responses (queryable route) -----------------------
    /** Older-server simulation: route unknown -> 404 for both endpoints. */
    var responsesRouteSupported = true
    private val responseBatches = linkedMapOf<String, ObjectNode>()
    private val responsesByFlight = linkedMapOf<String, MutableList<ObjectNode>>()

    override fun request(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): CoreHttpResponse {
        requests.add("$method $path")
        if (method == "PUT" && path.contains("/objects/")) {
            chunkPuts += 1
            if (failAtChunkPut != null && chunkPuts == failAtChunkPut) {
                throw IOException("injected transport failure at chunk PUT #$chunkPuts")
            }
        }
        if (method == "GET" && path.startsWith("/objects/")) {
            val artifactId = path.removePrefix("/objects/")
            val found = objects.entries.lastOrNull { it.key.endsWith("/$artifactId") }
            return if (found != null) {
                CoreHttpResponse(200, found.value)
            } else {
                CoreHttpResponse(404, errorJson("NOT_FOUND", "object not found"))
            }
        }
        return try {
            val result = route(method, path, headers, body ?: ByteArray(0))
            CoreHttpResponse(
                result.first,
                Mop.json.writeValueAsBytes(result.second),
            )
        } catch (exc: HttpError) {
            CoreHttpResponse(exc.status, errorJson(exc.code, exc.code))
        }
    }

    private fun errorJson(code: String, message: String): ByteArray =
        Mop.json.writeValueAsBytes(
            Mop.json.createObjectNode().put("code", code).put("message", message),
        )

    private fun route(
        method: String,
        path: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): Pair<Int, JsonNode> {
        val queryless = path.substringBefore('?')
        val segments = queryless.trim('/').split('/')
        return when {
            method == "GET" && queryless == "/healthz" -> {
                if (!healthy) throw HttpError(503, "UNAVAILABLE")
                200 to Mop.json.createObjectNode().put("status", "ok")
            }

            method == "GET" && queryless == "/sites" -> 200 to listSites()

            method == "POST" && queryless == "/sites" ->
                200 to createSite(Mop.json.readTree(body) as ObjectNode)

            segments.size == 3 && segments[0] == "sites" &&
                segments[2] == "zone-revisions" && method == "GET" ->
                200 to listZoneRevisions(segments[1])

            segments.size == 3 && segments[0] == "sites" &&
                segments[2] == "zone-revisions" && method == "POST" ->
                200 to createZoneRevision(segments[1], Mop.json.readTree(body) as ObjectNode)

            method == "POST" && queryless == "/missions" ->
                200 to createMission(Mop.json.readTree(body) as ObjectNode)

            segments.size == 3 && segments[0] == "missions" &&
                segments[2] == "snapshot" && method == "GET" ->
                200 to missionSnapshot(segments[1])

            method == "GET" && queryless == "/mission-bundles" -> 200 to listBundles()

            method == "POST" && queryless == "/mission-bundles" ->
                200 to createBundle(Mop.json.readTree(body) as ObjectNode)

            segments.size == 2 && segments[0] == "mission-bundles" &&
                method == "GET" -> 200 to getBundle(segments[1])

            method == "POST" && queryless == "/flights" ->
                200 to registerFlight(Mop.json.readTree(body) as ObjectNode)

            method == "GET" && segments.size == 2 && segments[0] == "flights" ->
                200 to flightView(segments[1])

            segments.size == 3 && segments[0] == "flights" &&
                segments[2] == "mission-applications" && method == "POST" ->
                200 to recordApplication(segments[1], body)

            segments.size == 3 && segments[0] == "flights" &&
                segments[2] == "flight-sessions" && method == "POST" ->
                200 to recordSession(segments[1], body)

            segments.size == 3 && segments[0] == "flights" &&
                segments[2] == "flight-sessions" && method == "GET" ->
                200 to listSessions(segments[1])

            segments.size == 3 && segments[0] == "flights" &&
                segments[2].endsWith(":batch") && method == "POST" ->
                200 to ingestBatch(segments[1], segments[2], body)

            method == "POST" && queryless == "/ingest-sessions" ->
                200 to openIngest(body)

            segments.size == 2 && segments[0] == "ingest-sessions" &&
                method == "GET" -> 200 to ingestStatus(segments[1])

            segments.size == 4 && segments[0] == "ingest-sessions" &&
                segments[2] == "objects" && method == "PUT" ->
                200 to putChunk(segments[1], segments[3], headers, body)

            segments.size == 3 && segments[0] == "ingest-sessions" &&
                segments[2] == "complete" && method == "POST" ->
                200 to completeIngest(segments[1], body)

            method == "GET" && queryless == "/events" -> {
                val params = queryParams(path)
                val flightId = params["flight_id"] ?: ""
                200 to listItems("events", flightId, path)
            }

            segments.size == 3 && segments[0] == "events" &&
                segments[2] == "annotations" && method == "POST" ->
                200 to postAnnotation(
                    segments[1], Mop.json.readTree(body) as ObjectNode,
                )

            segments.size == 3 && segments[0] == "events" &&
                segments[2] == "annotations" && method == "GET" ->
                200 to listAnnotations(segments[1])

            segments.size == 3 && segments[0] == "flights" &&
                segments[2] == "observations" && method == "GET" ->
                200 to listItems("observations", segments[1], path)

            method == "POST" && queryless == "/operator-responses" ->
                if (!responsesRouteSupported) throw HttpError(404, "NOT_FOUND")
                else 200 to ingestResponseBatch(Mop.json.readTree(body) as ObjectNode)

            segments.size == 3 && segments[0] == "flights" &&
                segments[2] == "operator-responses" && method == "GET" ->
                if (!responsesRouteSupported) throw HttpError(404, "NOT_FOUND")
                else 200 to listResponseItems(segments[1])

            else -> throw HttpError(404, "NOT_FOUND")
        }
    }

    // -- A5 mission surface routes ---------------------------------------------

    private fun listSites(): ObjectNode {
        val items = Mop.json.createArrayNode()
        sites.values.forEach { items.add(it) }
        val response = Mop.json.createObjectNode()
        response.set<JsonNode>("items", items)
        response.putNull("next_cursor")
        return response
    }

    private fun createSite(body: ObjectNode): ObjectNode {
        val name = body.get("name")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val existing = sites.values.firstOrNull { it.get("name")?.asText() == name }
        if (existing != null) {
            // Same name = same site (server rule): fill the location only
            // when the stored row currently has none — never overwrite.
            val lat = body.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
            val lon = body.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
            val radius = body.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
            if (lat != null && lon != null && radius != null &&
                existing.get("center_lat")?.isNumber != true
            ) {
                existing.put("center_lat", lat)
                existing.put("center_lon", lon)
                existing.put("radius_m", radius)
            }
            return existing.deepCopy()
        }
        val site = Mop.json.createObjectNode()
        site.put("site_id", newId())
        site.put("org_id", "org-a")
        site.put("name", name)
        val lat = body.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
        val lon = body.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
        val radius = body.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
        if (lat != null && lon != null && radius != null) {
            site.put("center_lat", lat)
            site.put("center_lon", lon)
            site.put("radius_m", radius)
        } else {
            site.putNull("center_lat")
            site.putNull("center_lon")
            site.putNull("radius_m")
        }
        sites[site.get("site_id").asText()] = site
        return site.deepCopy()
    }

    private fun listZoneRevisions(siteId: String): ObjectNode {
        val items = Mop.json.createArrayNode()
        zonesBySite[siteId]?.forEach { items.add(it) }
        val response = Mop.json.createObjectNode()
        response.set<JsonNode>("items", items)
        return response
    }

    private fun createZoneRevision(siteId: String, body: ObjectNode): ObjectNode {
        val zoneId = body.get("zone_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val revisions = zonesBySite.getOrPut(siteId) { mutableListOf() }
        val latest = revisions.lastOrNull { it.get("zone_id")?.asText() == zoneId }
        val expected = latest?.get("revision_id")?.asText() ?: ""
        val actualBase = body.get("base_revision_id")?.takeIf { !it.isNull }?.asText() ?: ""
        if (actualBase != expected) throw HttpError(409, "CONFLICT")
        // Server-assigned revision id (mirrors sites.py create_zone_revision:
        // the request supplies zone/geometry/rule_kind only).
        val revision = Mop.json.createObjectNode()
        revision.put("zone_id", zoneId)
        val revisionId = newId()
        revision.put("revision_id", revisionId)
        if (body.get("base_revision_id")?.isNull != false) {
            revision.putNull("base_revision_id")
        } else {
            revision.put("base_revision_id", body.get("base_revision_id").asText())
        }
        revision.put("site_id", siteId)
        revision.set<JsonNode>(
            "geometry_wgs84",
            (body.get("geometry_wgs84") as? JsonNode)
                ?: throw HttpError(422, "CONTRACT_INVALID"),
        )
        revision.put(
            "rule_kind",
            body.get("rule_kind")?.asText()
                ?: throw HttpError(422, "CONTRACT_INVALID"),
        )
        revisions.add(revision)
        val response = Mop.json.createObjectNode()
        response.put("zone_id", zoneId)
        response.put("revision_id", revisionId)
        response.put("durable", true)
        return response
    }

    private fun createMission(body: ObjectNode): ObjectNode {
        val revisionId = body.get("mission_revision_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        if (!CanonicalJson.verifyDigest(body, "content_sha256")) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        val missionId = body.get("mission_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val existing = missions[missionId]
        if (existing != null &&
            existing.get("mission_revision_id")?.asText() == revisionId &&
            !same(existing, body)
        ) {
            throw HttpError(409, "CONFLICT")
        }
        missions[missionId] = body.deepCopy()
        val response = Mop.json.createObjectNode()
        response.put("mission_id", missionId)
        response.put("mission_revision_id", revisionId)
        response.put("content_sha256", body.get("content_sha256").asText())
        response.put("site_id", body.get("site_id").asText())
        return response
    }

    private fun missionSnapshot(missionId: String): ObjectNode =
        missions[missionId]?.deepCopy() ?: throw HttpError(404, "NOT_FOUND")

    private fun listBundles(): ObjectNode {
        val items = Mop.json.createArrayNode()
        bundles.values.forEach { items.add(it) }
        val response = Mop.json.createObjectNode()
        response.set<JsonNode>("items", items)
        return response
    }

    private fun createBundle(body: ObjectNode): ObjectNode {
        if (!CanonicalJson.verifyDigest(body, "bundle_sha256")) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        val id = body.get("bundle_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val existing = bundles[id]
        if (existing != null) {
            if (!same(existing, body)) throw HttpError(409, "CONFLICT")
            return Mop.json.createObjectNode().put("bundle_id", id).put("status", "EXISTS")
        }
        bundles[id] = body.deepCopy()
        return Mop.json.createObjectNode()
            .put("bundle_id", id)
            .put("bundle_sha256", body.get("bundle_sha256").asText())
            .put("status", "CREATED")
    }

    private fun getBundle(bundleId: String): ObjectNode =
        bundles[bundleId]?.deepCopy() ?: throw HttpError(404, "NOT_FOUND")

    // -- operator annotations (append-only, idempotent by id) ------------------

    private fun postAnnotation(eventId: String, body: ObjectNode): ObjectNode {
        if (failNextAnnotationPost) {
            failNextAnnotationPost = false
            throw HttpError(500, "ERROR")
        }
        if (body.get("event_id")?.asText() != eventId) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        val id = body.get("annotation_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val bucket = annotationsByEvent.getOrPut(eventId) { mutableListOf() }
        val existing = bucket.firstOrNull { it.get("annotation_id")?.asText() == id }
        if (existing != null) {
            if (!same(existing, body)) throw HttpError(409, "CONFLICT")
            return Mop.json.createObjectNode().put("annotation_id", id).put("durable", true)
        }
        bucket.add(body.deepCopy())
        return Mop.json.createObjectNode().put("annotation_id", id).put("durable", true)
    }

    private fun listAnnotations(eventId: String): ObjectNode {
        val items = Mop.json.createArrayNode()
        annotationsByEvent[eventId]?.forEach { items.add(it) }
        val response = Mop.json.createObjectNode()
        response.set<JsonNode>("items", items)
        return response
    }

    // -- structured operator responses (mirrors routers/responses.py) ----------

    private fun isUuid(value: String): Boolean = try {
        java.util.UUID.fromString(value)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Schema-shape + digest + proof-gating check (real server parity). */
    private fun checkResponseItem(item: ObjectNode) {
        val required = listOf(
            "schema_version", "response_id", "candidate_id", "flight_id",
            "site_id", "session_id", "action", "note", "evidence",
            "acted_at_utc", "delivery_state", "transmission_proof",
            "receipt_proof", "completion_proof", "content_sha256",
        )
        for (field in required) {
            if (!item.has(field)) throw HttpError(422, "CONTRACT_INVALID")
        }
        if (!isUuid(item.get("response_id").asText()) ||
            !isUuid(item.get("flight_id").asText())
        ) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        if (item.get("action").asText() !in
            setOf("CONFIRM", "HOLD", "ESCALATE_REQUEST")
        ) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        if (!CanonicalJson.verifyDigest(item, "content_sha256")) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        // Proof gating (domain parity): a claim above REQUESTED without its
        // proof never validates.
        val state = item.get("delivery_state").asText()
        val proofs = mapOf(
            "SENT_CONFIRMED" to listOf("transmission_proof"),
            "RECEIVED_CONFIRMED" to listOf("transmission_proof", "receipt_proof"),
            "COMPLETED_CONFIRMED" to
                listOf("transmission_proof", "receipt_proof", "completion_proof"),
        )
        for (field in proofs[state] ?: emptyList()) {
            if (item.get(field)?.takeIf { it.isTextual }?.asText().isNullOrEmpty()) {
                throw HttpError(422, "CONTRACT_INVALID")
            }
        }
    }

    private fun ingestResponseBatch(body: ObjectNode): ObjectNode {
        val clientBatchId = body.get("client_batch_id")?.takeIf { it.isTextual }?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val itemsNode = body.get("items")
        if (itemsNode == null || !itemsNode.isArray) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        val items = itemsNode.map {
            it as? ObjectNode ?: throw HttpError(422, "CONTRACT_INVALID")
        }
        items.forEach { checkResponseItem(it) }
        val flightIds = items.map { it.get("flight_id").asText() }.toSet()
        if (flightIds.size != 1) throw HttpError(422, "CONTRACT_INVALID")
        val flightId = flightIds.single()
        requireFlight(flightId)
        val contentHash = CanonicalJson.digestSha256(itemsNode)
        val existing = responseBatches[clientBatchId]
        if (existing != null) {
            // Same id + same content -> idempotent replay; different -> 409.
            if (existing.get("content_hash").asText() != contentHash) {
                throw HttpError(409, "CONFLICT")
            }
            return batchReply(existing)
        }
        val bucket = responsesByFlight.getOrPut(flightId) { mutableListOf() }
        val accepted = Mop.json.createArrayNode()
        val duplicates = Mop.json.createArrayNode()
        val seenInBatch = mutableSetOf<String>()
        for (item in items) {
            val id = item.get("response_id").asText()
            if (!seenInBatch.add(id)) {
                duplicates.add(id)
                continue
            }
            val stored = bucket.firstOrNull { it.get("response_id")?.asText() == id }
            if (stored != null) {
                if (same(stored, item)) {
                    duplicates.add(id)
                } else {
                    throw HttpError(409, "CONFLICT")
                }
            } else {
                bucket.add(item.deepCopy())
                accepted.add(id)
            }
        }
        val record = Mop.json.createObjectNode()
        record.put("content_hash", contentHash)
        record.put("server_batch_id", "server-responses-${responseBatches.size + 1}")
        record.set<JsonNode>("accepted_ids", accepted)
        record.set<JsonNode>("duplicate_ids", duplicates)
        responseBatches[clientBatchId] = record
        return batchReply(record)
    }

    private fun batchReply(record: ObjectNode): ObjectNode {
        val response = Mop.json.createObjectNode()
        response.put("server_batch_id", record.get("server_batch_id").asText())
        response.put("durable", true)
        response.set<JsonNode>("accepted_ids", record.get("accepted_ids").deepCopy())
        response.set<JsonNode>("duplicate_ids", record.get("duplicate_ids").deepCopy())
        return response
    }

    private fun listResponseItems(flightId: String): ObjectNode {
        requireFlight(flightId)
        val items = Mop.json.createArrayNode()
        responsesByFlight[flightId]?.forEach { items.add(it) }
        val response = Mop.json.createObjectNode()
        response.set<JsonNode>("items", items)
        return response
    }

    private fun requireFlight(flightId: String): ObjectNode =
        flights[flightId] ?: throw HttpError(404, "NOT_FOUND")

    private fun same(a: JsonNode, b: JsonNode): Boolean =
        CanonicalJson.canonicalBytes(a).contentEquals(CanonicalJson.canonicalBytes(b))

    private fun registerFlight(body: ObjectNode): ObjectNode {
        val id = body.get("flight_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val required = listOf(
            "flight_id", "site_id", "rc_device_id", "mission_id",
            "mission_revision_id", "bundle_sha256", "started_at_utc",
        )
        val missing = required.filter { !body.has(it) }
        if (missing.isNotEmpty()) {
            throw HttpError(422, "CONTRACT_INVALID: missing $missing")
        }
        val existing = flights[id]
        if (existing != null) {
            if (!same(existing, body)) throw HttpError(409, "CONFLICT")
        } else {
            flights[id] = body.deepCopy()
        }
        return flightView(id)
    }

    private fun flightView(flightId: String): ObjectNode {
        val flight = requireFlight(flightId)
        val sessionList = ingests.values.filter {
            it.manifest.get("flight_id")?.asText() == flightId
        }
        val total = sessionList.size
        val committed = sessionList.count { it.status == "COMMITTED" }
        val state = when {
            total == 0 -> "LOCAL_ONLY"
            committed == total -> "COMPLETE"
            sessionList.any { it.status == "PARTIAL" } || committed > 0 -> "PARTIAL"
            else -> "UPLOADING"
        }
        val view = flight.deepCopy()
        view.put("upload_state", state)
        val completeness = view.putObject("completeness")
        completeness.put("manifests_total", total)
        completeness.put("manifests_committed", committed)
        completeness.put(
            "missing_artifact_count",
            sessionList.sumOf { missingFor(it).size },
        )
        return view
    }

    private fun recordApplication(flightId: String, body: ByteArray): ObjectNode {
        requireFlight(flightId)
        val app = Mop.json.readTree(body) as ObjectNode
        val id = app.get("application_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val existing = applications[id]
        if (existing != null) {
            if (!same(existing, app)) throw HttpError(409, "CONFLICT")
        } else {
            applications[id] = app.deepCopy()
        }
        return Mop.json.createObjectNode()
            .put("application_id", id).put("durable", true)
    }

    private fun recordSession(flightId: String, body: ByteArray): ObjectNode {
        requireFlight(flightId)
        val session = Mop.json.readTree(body) as ObjectNode
        val id = session.get("session_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val existing = sessions[id]
        if (existing != null) {
            if (!same(existing, session)) throw HttpError(409, "CONFLICT")
        } else {
            sessions[id] = session.deepCopy()
        }
        return Mop.json.createObjectNode().put("session_id", id).put("durable", true)
    }

    private fun listSessions(flightId: String): ObjectNode {
        requireFlight(flightId)
        val items = Mop.json.createArrayNode()
        for (session in sessions.values) {
            if (session.get("flight_id")?.asText() == flightId) items.add(session)
        }
        return Mop.json.createObjectNode().set(
            "items", items,
        )
    }

    private fun idField(kind: String): String = when (kind) {
        "observations" -> "observation_id"
        "events" -> "event_id"
        else -> "summary_id"
    }

    private fun ingestBatch(
        flightId: String,
        segment: String,
        body: ByteArray,
    ): ObjectNode {
        val kind = when (segment) {
            "observations:batch" -> "observations"
            "events:batch" -> "events"
            "spatial-summaries:batch" -> "spatial"
            "flight-summaries:batch" -> "flight"
            else -> throw HttpError(404, "NOT_FOUND")
        }
        requireFlight(flightId)
        val node = Mop.json.readTree(body) as ObjectNode
        val clientBatchId = node.get("client_batch_id")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        val itemsNode = node.get("items")
        if (itemsNode == null || !itemsNode.isArray) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        val contentHash = CanonicalJson.digestSha256(itemsNode)
        val key = "$kind|$clientBatchId"
        val existing = batches[key]
        if (existing != null) {
            if (existing.get("content_hash").asText() != contentHash) {
                throw HttpError(409, "CONFLICT")
            }
            val response = Mop.json.createObjectNode()
            response.put("server_batch_id", existing.get("server_batch_id").asText())
            response.put("durable", true)
            response.set<JsonNode>("accepted_ids", existing.get("accepted_ids").deepCopy())
            response.set<JsonNode>("duplicate_ids", existing.get("duplicate_ids").deepCopy())
            return response
        }
        val field = idField(kind)
        val bucket = items.getOrPut("$kind|$flightId") { mutableListOf() }
        val accepted = Mop.json.createArrayNode()
        val duplicates = Mop.json.createArrayNode()
        val seenInBatch = mutableSetOf<String>()
        for (itemNode in itemsNode) {
            val item = itemNode as? ObjectNode
                ?: throw HttpError(422, "CONTRACT_INVALID")
            val id = item.get(field)?.asText()
                ?: throw HttpError(422, "CONTRACT_INVALID")
            if (!seenInBatch.add(id)) {
                duplicates.add(id)
                continue
            }
            val existingItem = bucket.firstOrNull {
                it.get(field)?.asText() == id
            }
            if (existingItem != null) {
                if (same(existingItem, item)) {
                    duplicates.add(id)
                } else {
                    throw HttpError(409, "CONFLICT")
                }
            } else {
                bucket.add(item.deepCopy())
                accepted.add(id)
            }
        }
        val serverBatchId = "server-batch-${batches.size + 1}"
        val record = Mop.json.createObjectNode()
        record.put("content_hash", contentHash)
        record.put("server_batch_id", serverBatchId)
        record.set<JsonNode>("accepted_ids", accepted)
        record.set<JsonNode>("duplicate_ids", duplicates)
        batches[key] = record
        val response = record.deepCopy()
        response.put("durable", true)
        return response
    }

    private fun artifactSpecs(session: Ingest): List<ObjectNode> =
        session.manifest.get("artifacts")?.mapNotNull { it as? ObjectNode }
            ?: emptyList()

    private fun coveredBytes(uploadId: String, artifactId: String): Long {
        val merged = mergedRanges(uploadId, artifactId)
        return merged.sumOf { (it.last - it.first + 1).toLong() }
    }

    private fun mergedRanges(uploadId: String, artifactId: String): List<IntRange> {
        val raw = (ranges["$uploadId/$artifactId"] ?: emptyList()).sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in raw) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    private fun uploadedMissing(session: Ingest): Pair<List<String>, List<String>> {
        val uploaded = mutableListOf<String>()
        val missing = mutableListOf<String>()
        for (spec in artifactSpecs(session)) {
            val id = spec.get("artifact_id").asText()
            if (coveredBytes(session.uploadId, id) >= spec.get("size_bytes").asLong()) {
                uploaded.add(id)
            } else {
                missing.add(id)
            }
        }
        return uploaded to missing
    }

    /** Server `_missing_for_session`: required artifacts whose size or
     *  sha256 does not match the manifest. */
    private fun missingFor(session: Ingest): List<String> {
        val missing = mutableListOf<String>()
        for (spec in artifactSpecs(session)) {
            if (!(spec.get("required")?.asBoolean() ?: true)) continue
            val id = spec.get("artifact_id").asText()
            val size = spec.get("size_bytes").asLong()
            if (coveredBytes(session.uploadId, id) < size) {
                missing.add(id)
                continue
            }
            val bytes = objects["${session.uploadId}/$id"]
            if (bytes == null || sha256Hex(bytes) != spec.get("sha256").asText()) {
                missing.add(id)
            }
        }
        return missing
    }

    private fun openIngest(body: ByteArray): ObjectNode {
        val manifest = Mop.json.readTree(body) as ObjectNode
        val sha = manifest.get("manifest_sha256")?.asText()
            ?: throw HttpError(422, "CONTRACT_INVALID")
        if (CanonicalJson.digestExcluding(manifest, "manifest_sha256") != sha) {
            throw HttpError(422, "CONTRACT_INVALID")
        }
        val existingId = ingestBySha[sha]
        if (existingId != null) {
            val session = ingests.getValue(existingId)
            val (_, missing) = uploadedMissing(session)
            val response = Mop.json.createObjectNode()
            response.put("upload_id", session.uploadId)
            response.put("status", "RESUMING")
            response.set<JsonNode>("missing_artifact_ids", toArray(missing))
            return response
        }
        val uploadId = "upload-${ingests.size + 1}"
        val session = Ingest(uploadId, manifest.deepCopy())
        ingests[uploadId] = session
        ingestBySha[sha] = uploadId
        val all = artifactSpecs(session).map { it.get("artifact_id").asText() }
        val response = Mop.json.createObjectNode()
        response.put("upload_id", uploadId)
        response.put("status", "NEW")
        response.set<JsonNode>("missing_artifact_ids", toArray(all))
        return response
    }

    private fun toArray(values: List<String>): JsonNode {
        val array = Mop.json.createArrayNode()
        values.forEach { array.add(it) }
        return array
    }

    private fun ingestStatus(uploadId: String): ObjectNode {
        val session = ingests[uploadId] ?: throw HttpError(404, "NOT_FOUND")
        val (uploaded, missing) = uploadedMissing(session)
        val response = Mop.json.createObjectNode()
        response.put("upload_id", uploadId)
        response.put("status", session.status)
        response.put("manifest_sha256", session.manifest.get("manifest_sha256").asText())
        response.set<JsonNode>("uploaded_artifact_ids", toArray(uploaded))
        response.set<JsonNode>("missing_artifact_ids", toArray(missing))
        return response
    }

    private fun putChunk(
        uploadId: String,
        artifactId: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ObjectNode {
        val session = ingests[uploadId] ?: throw HttpError(404, "NOT_FOUND")
        val spec = artifactSpecs(session).firstOrNull {
            it.get("artifact_id").asText() == artifactId
        } ?: throw HttpError(404, "NOT_FOUND")
        val size = spec.get("size_bytes").asLong()
        if (session.status == "COMMITTED") {
            return Mop.json.createObjectNode()
                .put("received_bytes", size).put("complete", true)
        }
        val range = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$")
            .find(headers["Content-Range"]?.trim() ?: "")
            ?: throw HttpError(422, "RANGE_FORMAT")
        val start = range.groupValues[1].toLong()
        val end = range.groupValues[2].toLong()
        val total = range.groupValues[3].toLong()
        if (total != size || end < start || end >= total) {
            throw HttpError(422, "RANGE_MISMATCH")
        }
        if (body.size.toLong() != end - start + 1) {
            throw HttpError(422, "RANGE_LENGTH")
        }
        if (ignoreChunkWrites) {
            // Simulates a server that drops stored bytes: local data is
            // complete but the commit will report it missing.
            return Mop.json.createObjectNode()
                .put("received_bytes", coveredBytes(uploadId, artifactId))
                .put("complete", false)
        }
        val key = "$uploadId/$artifactId"
        val buffer = objects.getOrPut(key) { ByteArray(total.toInt()) }
        if (buffer.size != total.toInt()) throw HttpError(422, "RANGE_MISMATCH")
        System.arraycopy(body, 0, buffer, start.toInt(), body.size)
        ranges.getOrPut(key) { mutableListOf() }.add(start.toInt()..end.toInt())
        val covered = coveredBytes(uploadId, artifactId)
        return Mop.json.createObjectNode()
            .put("received_bytes", covered)
            .put("complete", covered >= size)
    }

    private fun completeIngest(uploadId: String, body: ByteArray): ObjectNode {
        val session = ingests[uploadId] ?: throw HttpError(404, "NOT_FOUND")
        val bodyNode = Mop.json.readTree(body) as ObjectNode
        val sha = session.manifest.get("manifest_sha256").asText()
        if (bodyNode.get("manifest_sha256")?.asText() != sha) {
            throw HttpError(409, "CONFLICT")
        }
        if (session.status == "COMMITTED") {
            val response = Mop.json.createObjectNode()
            response.put("status", "COMMITTED")
            response.put("durable_commit_id", session.durableId)
            response.put("manifest_sha256", sha)
            response.set<JsonNode>("missing_artifact_ids", Mop.json.createArrayNode())
            return response
        }
        val missing = missingFor(session)
        if (missing.isNotEmpty()) {
            session.status = "PARTIAL"
            val response = Mop.json.createObjectNode()
            response.put("status", "PARTIAL")
            response.putNull("durable_commit_id")
            response.put("manifest_sha256", sha)
            response.set<JsonNode>("missing_artifact_ids", toArray(missing))
            return response
        }
        session.status = "COMMITTED"
        session.durableId = "commit-$uploadId"
        val response = Mop.json.createObjectNode()
        response.put("status", "COMMITTED")
        response.put("durable_commit_id", session.durableId)
        response.put("manifest_sha256", sha)
        response.set<JsonNode>("missing_artifact_ids", Mop.json.createArrayNode())
        return response
    }

    private fun queryParams(path: String): Map<String, String> =
        path.substringAfter('?', "")
            .split('&')
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }

    /** Page size for list endpoints (server default 100; tests can shrink). */
    var pageSize = 100

    private fun listItems(kind: String, flightId: String, path: String): ObjectNode {
        val all = items["$kind|$flightId"] ?: emptyList()
        val params = queryParams(path)
        val limit = (params["limit"]?.toIntOrNull() ?: pageSize).coerceIn(1, 500)
        val start = params["cursor"]?.toIntOrNull() ?: 0
        val page = all.drop(start.coerceAtLeast(0)).take(limit)
        val array = Mop.json.createArrayNode()
        page.forEach { array.add(it) }
        val response = Mop.json.createObjectNode()
        response.set<JsonNode>("items", array)
        if (start + page.size < all.size) {
            response.put("next_cursor", (start + page.size).toString())
        } else {
            response.putNull("next_cursor")
        }
        return response
    }

    // -- assertion helpers -----------------------------------------------------

    fun batchedItems(kind: String, flightId: String): List<ObjectNode> =
        items["$kind|$flightId"]?.toList() ?: emptyList()

    fun responsesFor(flightId: String): List<ObjectNode> =
        responsesByFlight[flightId]?.toList() ?: emptyList()

    fun latestObjectBytes(artifactId: String): ByteArray? =
        objects.entries.lastOrNull { it.key.endsWith("/$artifactId") }?.value
}
