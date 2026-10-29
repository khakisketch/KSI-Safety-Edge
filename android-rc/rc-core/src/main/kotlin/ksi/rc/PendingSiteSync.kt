package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Offline-first site registration (현장 등록): a pending-site draft journal
 * plus a deferred uploader against the frozen `POST /sites` contract.
 *
 * A draft is created with NO network (name + an optional confirmed
 * location), stored durably in `pending_sites.ndjson` via
 * [DurableJsonLinesJournal], and uploaded later when a Core is configured
 * AND reachable. The journal is the house pattern (same as
 * [SessionResultLog]/[OperatorActionLog]): append-only events, fsync before
 * any in-memory claim, torn tails repaired, corrupt lines skipped with a
 * counter, and a zero-filesystem `unavailable` holder for the degraded path.
 *
 * Server contract facts this file depends on (`core/.../routers/sites.py`,
 * read-only — the server already has `POST /sites`):
 * - SAME (org, name) returns the EXISTING row (name-keyed dedup); the
 *   location is filled only when the server row currently has none. This is
 *   dedup, NOT idempotent-create: two different real places sharing a name
 *   collide silently, so the upload outcome distinguishes CREATED (the
 *   server minted a new id) from MATCHED_EXISTING (the server returned a
 *   pre-existing row for the same name) and the UI must surface the match
 *   honestly — never claim a new site was registered when the server
 *   returned an existing row.
 * - `org_id` in the POST body must equal the caller's org. The RC never
 *   invents it: it is read from already-synced site facts (the same source
 *   [MissionBundleBuilder] uses). No synced site with an org yet = no
 *   upload attempt; the draft stays pending with its reason.
 *
 * Forward-only alias: once a draft resolves, [PendingSiteJournal]
 * maps the client UUID to the canonical server site id for NEW
 * preparations and Core-facing reporting. Stored flight rows, bundles, and
 * sealed evidence are NEVER rewritten or re-pinned — a flight started under
 * a pending id keeps its recorded id; the alias applies forward only.
 */
object PendingSiteValidation {
    /** Mirrors the server `_parse_location` bounds exactly. */
    const val MAX_RADIUS_M = 100_000.0

    /** Stable problem codes (UI copy lives in the app, not here). */
    const val BLANK_NAME = "BLANK_NAME"
    const val PARTIAL_LOCATION = "PARTIAL_LOCATION"
    const val BAD_LAT = "BAD_LAT"
    const val BAD_LON = "BAD_LON"
    const val BAD_RADIUS = "BAD_RADIUS"

    fun validateName(name: String): String? =
        if (name.isBlank()) BLANK_NAME else null

    /**
     * Location is all-or-nothing (center point + radius travel together,
     * server rule). Null triple = unregistered, which is valid.
     */
    fun validateLocation(
        centerLat: Double?,
        centerLon: Double?,
        radiusM: Double?,
    ): List<String> {
        if (centerLat == null && centerLon == null && radiusM == null) {
            return emptyList()
        }
        if (centerLat == null || centerLon == null || radiusM == null) {
            return listOf(PARTIAL_LOCATION)
        }
        val problems = mutableListOf<String>()
        if (!(centerLat in -90.0..90.0)) problems.add(BAD_LAT)
        if (!(centerLon in -180.0..180.0)) problems.add(BAD_LON)
        if (!(radiusM > 0.0 && radiusM <= MAX_RADIUS_M)) problems.add(BAD_RADIUS)
        return problems
    }

    fun validateDraft(
        name: String,
        centerLat: Double?,
        centerLon: Double?,
        radiusM: Double?,
    ): List<String> =
        listOfNotNull(validateName(name)) + validateLocation(centerLat, centerLon, radiusM)
}

/** One locally drafted site (unresolved until the Core answers). */
data class PendingSiteDraft(
    /** Locally scoped draft key (random UUID v4). NEVER a server id. */
    val clientSiteId: String,
    val name: String,
    val centerLat: Double?,
    val centerLon: Double?,
    val radiusM: Double?,
    val createdUtc: String,
)

/** How the server answered a draft upload. */
enum class PendingSyncOutcome {
    /** The server minted a new id for this draft. */
    CREATED,

    /**
     * The server returned a PRE-EXISTING row for the same name
     * (name-keyed dedup). The operator must be told the central site with
     * the same name was linked — never that a new site was registered.
     */
    MATCHED_EXISTING,
}

data class PendingUploadResult(
    val clientSiteId: String,
    val canonicalSiteId: String,
    val outcome: PendingSyncOutcome,
    /**
     * Set only on MATCHED_EXISTING when the draft carried a location the
     * server did NOT keep: the central row already has its own coordinates
     * (fill-only-if-empty), so the draft's location was dropped, not
     * merged. The operator must be told — a silent drop would let a field
     * position disagree with the central record under the same name.
     */
    val droppedLocation: DroppedLocation? = null,
)

/** A draft location the server did not keep, with what it kept instead. */
data class DroppedLocation(
    val draftLat: Double,
    val draftLon: Double,
    val draftRadiusM: Double,
    val serverLat: Double,
    val serverLon: Double,
    val serverRadiusM: Double,
)

data class PendingSyncReport(
    /** True once the run used the network (probe ran or draft uploads were
     *  attempted; results may be partial). False when no network was used:
     *  nothing pending, no Core configured, org unattributable, or
     *  unreachable — the drafts are untouched, see [detail]. */
    val attempted: Boolean,
    val detail: String?,
    val results: List<PendingUploadResult>,
)

/** One draft plus its durable resolution state (for the site list). */
data class PendingDraftStatus(
    val draft: PendingSiteDraft,
    /** Null = still pending (never synced). */
    val outcome: PendingSyncOutcome?,
    /** Null = still pending. */
    val canonicalSiteId: String?,
    /** Latest upload-attempt failure, null when never attempted. */
    val lastAttemptError: String?,
) {
    /** True when the draft resolved to a canonical id. */
    val isSynced: Boolean get() = outcome != null
}

/**
 * Durable pending-site draft journal (house pattern). Events, never
 * rewrites:
 * - `draft`: the created draft (full fields);
 * - `attempt`: a failed upload attempt (truncated error, no throwaway);
 * - `synced`: the canonical outcome (CREATED or MATCHED_EXISTING).
 */
class PendingSiteJournal private constructor(
    root: Path,
    private val wallClock: () -> String,
    private val journal: DurableJsonLinesJournal,
) {
    constructor(
        root: Path,
        wallClock: () -> String = { java.time.Instant.now().toString() },
    ) : this(root, wallClock, DurableJsonLinesJournal(root.toFile(), FILE_NAME))

    /** Test-only seam: fault injection without changing call shapes. */
    internal constructor(
        root: Path,
        wallClock: () -> String,
        io: JournalIo,
    ) : this(root, wallClock, DurableJsonLinesJournal(root.toFile(), FILE_NAME, io))

    private val drafts = linkedMapOf<String, ObjectNode>()
    private val synced = linkedMapOf<String, ObjectNode>()
    private val attempts = linkedMapOf<String, ObjectNode>()

    /** Complete-but-corrupt lines skipped while loading (diagnostics). */
    private var corruptSkipped = 0

    /** Torn tails repaired plus corrupt lines skipped while loading. */
    val recoveredPartialWrites: Int
        get() = journal.recoveredPartialWrites + corruptSkipped

    init {
        for (line in journal.readCompletedLines()) {
            val node = try {
                Mop.json.readTree(line) as? ObjectNode
            } catch (_: Exception) {
                null
            }
            if (node == null) {
                corruptSkipped += 1
                continue
            }
            val kind = node.get("kind")?.takeIf { it.isTextual }?.asText()
            val clientId = node.get("client_site_id")
                ?.takeIf { it.isTextual }?.asText()
            if ((kind != "draft" && kind != "attempt" && kind != "synced") ||
                clientId.isNullOrEmpty()
            ) {
                corruptSkipped += 1
                continue
            }
            when (kind) {
                "draft" -> drafts.putIfAbsent(clientId, node)
                "attempt" -> attempts[clientId] = node
                "synced" -> synced.putIfAbsent(clientId, node)
            }
        }
    }

    /**
     * Creates a pending draft (no network, no Core needed). Validates first:
     * an invalid draft throws WITHOUT writing anything.
     */
    @Synchronized
    fun createDraft(
        name: String,
        centerLat: Double?,
        centerLon: Double?,
        radiusM: Double?,
    ): PendingSiteDraft {
        val problems = PendingSiteValidation.validateDraft(name, centerLat, centerLon, radiusM)
        require(problems.isEmpty()) {
            "invalid pending site draft: ${problems.joinToString(",")}"
        }
        val draft = PendingSiteDraft(
            clientSiteId = UUID.randomUUID().toString(),
            name = name.trim(),
            centerLat = centerLat,
            centerLon = centerLon,
            radiusM = radiusM,
            createdUtc = wallClock(),
        )
        // Durable FIRST: publishing the cache entry before the bytes are
        // fsynced would let a retry read a draft that never reached disk.
        journal.appendLine(Mop.json.writeValueAsString(toNode(draft)))
        drafts[draft.clientSiteId] = toNode(draft)
        return draft
    }

    /** Drafts that never resolved (in creation order). */
    @Synchronized
    fun pendingDrafts(): List<PendingSiteDraft> =
        drafts.values.mapNotNull { fromNode(it) }
            .filter { it.clientSiteId !in synced }

    /** All drafts ever created (including resolved ones). */
    @Synchronized
    fun allDrafts(): List<PendingSiteDraft> =
        drafts.values.mapNotNull { fromNode(it) }

    @Synchronized
    fun statusFor(clientSiteId: String): PendingDraftStatus? {
        val draft = drafts[clientSiteId]?.let { fromNode(it) } ?: return null
        val sync = synced[clientSiteId]
        val outcome = sync?.get("outcome")?.takeIf { it.isTextual }?.asText()
            ?.let { runCatching { PendingSyncOutcome.valueOf(it) }.getOrNull() }
        return PendingDraftStatus(
            draft = draft,
            outcome = outcome,
            canonicalSiteId = sync?.get("canonical_site_id")
                ?.takeIf { it.isTextual }?.asText(),
            lastAttemptError = attempts[clientSiteId]?.get("error")
                ?.takeIf { it.isTextual }?.asText(),
        )
    }

    @Synchronized
    fun statuses(): List<PendingDraftStatus> =
        drafts.keys.mapNotNull { statusFor(it) }

    /**
     * Canonical id for a client key (a draft that resolved), else the input
     * unchanged. Forward-only alias for Core-facing reporting: the stored
     * flight rows are never rewritten by this.
     */
    @Synchronized
    fun resolveForCore(siteId: String): String =
        synced[siteId]?.get("canonical_site_id")
            ?.takeIf { it.isTextual }?.asText() ?: siteId

    @Synchronized
    fun canonicalIdFor(clientSiteId: String): String? =
        synced[clientSiteId]?.get("canonical_site_id")
            ?.takeIf { it.isTextual }?.asText()

    @Synchronized
    fun isSynced(clientSiteId: String): Boolean = clientSiteId in synced

    /** Records a failed upload attempt (stays pending). */
    @Synchronized
    fun recordAttempt(clientSiteId: String, error: String) {
        check(drafts.containsKey(clientSiteId)) {
            "no such pending site draft $clientSiteId"
        }
        check(!synced.containsKey(clientSiteId)) {
            "draft $clientSiteId already synced; attempts after resolution are refused"
        }
        val node = Mop.json.createObjectNode()
        node.put("kind", "attempt")
        node.put("client_site_id", clientSiteId)
        node.put("attempted_utc", wallClock())
        node.put("error", error.take(300))
        journal.appendLine(Mop.json.writeValueAsString(node))
        attempts[clientSiteId] = node
    }

    /**
     * Records the canonical outcome. Idempotent for the same outcome;
     * a DIFFERENT canonical id/outcome for the same draft is refused
     * (history is never rewritten).
     */
    @Synchronized
    fun recordSynced(
        clientSiteId: String,
        canonicalSiteId: String,
        outcome: PendingSyncOutcome,
    ) {
        check(drafts.containsKey(clientSiteId)) {
            "no such pending site draft $clientSiteId"
        }
        require(canonicalSiteId.isNotBlank()) { "canonical site id must not be blank" }
        val prior = synced[clientSiteId]
        if (prior != null) {
            check(
                prior.get("canonical_site_id")?.asText() == canonicalSiteId &&
                    prior.get("outcome")?.asText() == outcome.name,
            ) {
                "draft $clientSiteId already resolved differently; history is never rewritten"
            }
            return
        }
        val node = Mop.json.createObjectNode()
        node.put("kind", "synced")
        node.put("client_site_id", clientSiteId)
        node.put("canonical_site_id", canonicalSiteId)
        node.put("outcome", outcome.name)
        node.put("synced_utc", wallClock())
        journal.appendLine(Mop.json.writeValueAsString(node))
        synced[clientSiteId] = node
    }

    /** Explicit recovery after a poisoned failure (see journal). */
    @Synchronized
    fun repairAfterFailure(): Int = journal.repairAfterFailure()

    private fun toNode(draft: PendingSiteDraft): ObjectNode {
        val node = Mop.json.createObjectNode()
        node.put("kind", "draft")
        node.put("schema_version", 1)
        node.put("client_site_id", draft.clientSiteId)
        node.put("name", draft.name)
        if (draft.centerLat == null) node.putNull("center_lat")
        else node.put("center_lat", draft.centerLat)
        if (draft.centerLon == null) node.putNull("center_lon")
        else node.put("center_lon", draft.centerLon)
        if (draft.radiusM == null) node.putNull("radius_m")
        else node.put("radius_m", draft.radiusM)
        node.put("created_utc", draft.createdUtc)
        return node
    }

    private fun fromNode(node: ObjectNode): PendingSiteDraft? {
        return try {
            PendingSiteDraft(
                clientSiteId = node.get("client_site_id")
                    ?.takeIf { it.isTextual }?.asText() ?: return null,
                name = node.get("name")
                    ?.takeIf { it.isTextual }?.asText() ?: return null,
                centerLat = node.get("center_lat")
                    ?.takeIf { it.isNumber }?.asDouble(),
                centerLon = node.get("center_lon")
                    ?.takeIf { it.isNumber }?.asDouble(),
                radiusM = node.get("radius_m")
                    ?.takeIf { it.isNumber }?.asDouble(),
                createdUtc = node.get("created_utc")
                    ?.takeIf { it.isTextual }?.asText() ?: return null,
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val FILE_NAME = "pending_sites.ndjson"

        /**
         * Unavailable readonly holder for the app degraded path: zero
         * filesystem allocation, zero reads; every write (and repair)
         * throws [reason]. Reads are empty — only ever shown behind the
         * storage-unavailable UI flag, never as healthy facts. No mock
         * data, nothing writable.
         */
        fun unavailable(
            reason: IOException,
            wallClock: () -> String = { java.time.Instant.now().toString() },
        ): PendingSiteJournal = PendingSiteJournal(
            Paths.get(""),
            wallClock,
            DurableJsonLinesJournal.unavailable(reason),
        )
    }
}

/**
 * Deferred uploader for pending-site drafts (no retries, no store marks of
 * its own beyond the journal + additive site cache writes):
 * - reachability is checked first: an offline field day fails fast with
 *   `attempted=false` rather than burning through POSTs;
 * - `org_id` is the TOKEN's org, read from the reachable Core's own site
 *   list (caller-scoped: the server filters by token org). A locally
 *   stored org is only a fallback when the server list is EMPTY (nothing
 *   to validate against) — a stored org is never trusted over a live
 *   list, so a token switch cannot POST under a stale org. More than one
 *   distinct org on the list = refuse (never guess). A 403 on POST
 *   re-derives once from the live list and retries under the fresh org;
 * - per draft, `POST /sites` classifies CREATED vs MATCHED_EXISTING by
 *   whether the returned id pre-existed the upload (a server row the POST
 *   did not create). The known-id set grows WITHIN the run, so two drafts
 *   sharing one name in a single run resolve to one server row (first
 *   CREATED, rest MATCHED_EXISTING) instead of forking a duplicate.
 *   CREATED rows are stored additively; MATCHED rows are stored only when
 *   the id is locally unknown (additive-only) and NEVER overwrite a stored
 *   row — a server location fill on a known row arrives through the normal
 *   sync path, not as a rewrite here. When a MATCHED row already has its
 *   own coordinates, the draft's location is dropped (fill-only-if-empty):
 *   the drop is carried on the result ([DroppedLocation]) and surfaced,
 *   never silent;
 * - a transport/server failure records an attempt fact and keeps the
 *   draft pending; the next `syncOnce` retries (the server dedups by
 *   name, so a retry after a half-committed POST resolves to
 *   MATCHED_EXISTING instead of forking a duplicate). A failed pre-upload
 *   probe (after a passing health check) returns an attempted report with
 *   the reason instead of throwing: no draft was touched, retry later.
 */
class PendingSiteSync(
    private val core: CoreApi,
    private val journal: PendingSiteJournal,
    private val store: MissionStore,
) {
    fun syncOnce(): PendingSyncReport {
        val pending = journal.pendingDrafts()
        if (pending.isEmpty()) {
            return PendingSyncReport(
                attempted = false,
                detail = "등록 대기 현장 없음",
                results = emptyList(),
            )
        }
        if (!core.health()) {
            return PendingSyncReport(
                attempted = false,
                detail = "Core에 연결할 수 없어 중앙 등록을 시도하지 않았습니다 · 이 기기에 저장됨",
                results = emptyList(),
            )
        }
        // The token's org comes from the live list (caller-scoped). A
        // stored org is trusted ONLY when the list is empty — there is
        // nothing to validate against, and a stale local org would POST
        // under the wrong org (the server answers 403, handled below).
        val serverSites = try {
            itemsOf(core.listSites())
        } catch (exc: Exception) {
            return PendingSyncReport(
                attempted = true,
                detail = "중앙 현장 목록을 가져오지 못했습니다 · 이 기기에 저장됨",
                results = emptyList(),
            )
        }
        val serverOrgs = orgsOf(serverSites)
        val orgId = when {
            serverOrgs.size == 1 -> serverOrgs.single()
            serverOrgs.isEmpty() -> orgFromStore()
                ?: return PendingSyncReport(
                    attempted = false,
                    detail = "중앙 조직 미확인 · 동기화된 현장과 중앙 목록에 조직 정보가 없어 중앙 등록을 보류합니다",
                    results = emptyList(),
                )
            else -> return PendingSyncReport(
                attempted = false,
                detail = "중앙 조직이 여럿 확인됨 · 어느 조직에도 중앙 등록하지 않고 대기합니다",
                results = emptyList(),
            )
        }
        return uploadAll(pending, orgId, preloaded = serverSites)
    }

    private fun uploadAll(
        pending: List<PendingSiteDraft>,
        orgId: String,
        preloaded: List<ObjectNode>,
    ): PendingSyncReport {
        // Ids the server ALREADY has (probed before any POST): a POST
        // answer carrying one of these is a name-keyed match, not a
        // creation. Read-only probe — nothing stored from this list. The
        // set grows within the run so same-name drafts in ONE run share
        // one server row instead of the second misreading as a creation.
        // Both the org and the id set refresh below when a 403 proves the
        // org went stale mid-run (token switch): the retry uses the live
        // list, never a second guess.
        var org = orgId
        var knownIds = preloaded
            .mapNotNull {
                it.get("site_id")?.takeIf { f -> f.isTextual }?.asText()
            }
            .toMutableSet()
        val results = mutableListOf<PendingUploadResult>()
        for (draft in pending) {
            try {
                val view = try {
                    core.createSite(
                        orgId = org,
                        name = draft.name,
                        centerLat = draft.centerLat,
                        centerLon = draft.centerLon,
                        radiusM = draft.radiusM,
                    )
                } catch (exc: CoreApiError) {
                    if (exc.status != 403) throw exc
                    // The token's org moved under us (or the empty-list
                    // fallback was stale): re-derive once from the live
                    // list and retry under the fresh org. Anything else —
                    // still unknown, unchanged, or unlistable — keeps the
                    // original failure as the attempt fact.
                    val fresh = itemsOf(core.listSites())
                    val freshOrgs = orgsOf(fresh)
                    if (freshOrgs.size != 1 || freshOrgs.single() == org) throw exc
                    org = freshOrgs.single()
                    knownIds = fresh
                        .mapNotNull {
                            it.get("site_id")?.takeIf { f -> f.isTextual }?.asText()
                        }
                        .toMutableSet()
                    core.createSite(
                        orgId = org,
                        name = draft.name,
                        centerLat = draft.centerLat,
                        centerLon = draft.centerLon,
                        radiusM = draft.radiusM,
                    )
                }
                val canonical = view.get("site_id")
                    ?.takeIf { it.isTextual }?.asText()
                    ?: throw IllegalStateException("site without site_id")
                val outcome = if (canonical in knownIds) {
                    PendingSyncOutcome.MATCHED_EXISTING
                } else {
                    PendingSyncOutcome.CREATED
                }
                // Additive-only site cache write: a CREATED row is new by
                // construction; a MATCHED row is stored only when locally
                // unknown. A known id is NEVER overwritten here.
                if (outcome == PendingSyncOutcome.CREATED || store.site(canonical) == null) {
                    store.saveSite(view)
                }
                journal.recordSynced(draft.clientSiteId, canonical, outcome)
                knownIds.add(canonical)
                results.add(
                    PendingUploadResult(
                        draft.clientSiteId,
                        canonical,
                        outcome,
                        droppedLocationOf(draft, view, outcome),
                    ),
                )
            } catch (exc: Exception) {
                // Journal write failure propagates (fail-closed): a poisoned
                // journal must surface, not silently drop the attempt fact.
                // A later retry is still safe — the server dedups by name.
                journal.recordAttempt(
                    draft.clientSiteId,
                    exc.message ?: exc.javaClass.simpleName,
                )
            }
        }
        return PendingSyncReport(attempted = true, detail = null, results = results)
    }

    private fun orgFromStore(): String? = store.sites()
        .mapNotNull {
            it.get("org_id")?.takeIf { f -> f.isTextual }?.asText()
                ?.takeIf { org -> org.isNotBlank() }
        }
        .firstOrNull()

    /**
     * A draft location the server did not keep: the MATCHED row already
     * has its own coordinates (fill-only-if-empty), so the draft's
     * coordinates were dropped. Null when there is nothing to warn about:
     * created rows, unregistered drafts, server-filled locations, and
     * identical coordinates.
     */
    private fun droppedLocationOf(
        draft: PendingSiteDraft,
        view: ObjectNode,
        outcome: PendingSyncOutcome,
    ): DroppedLocation? {
        if (outcome != PendingSyncOutcome.MATCHED_EXISTING) return null
        val draftLat = draft.centerLat
        val draftLon = draft.centerLon
        val draftRadius = draft.radiusM
        if (draftLat == null || draftLon == null || draftRadius == null) return null
        val serverLat = view.get("center_lat")?.takeIf { it.isNumber }?.asDouble()
        val serverLon = view.get("center_lon")?.takeIf { it.isNumber }?.asDouble()
        val serverRadius = view.get("radius_m")?.takeIf { it.isNumber }?.asDouble()
        if (serverLat == null || serverLon == null || serverRadius == null) {
            // The server filled its empty row from the draft (or both sides
            // unregistered): nothing was dropped.
            return null
        }
        if (draftLat == serverLat && draftLon == serverLon && draftRadius == serverRadius) {
            return null
        }
        return DroppedLocation(
            draftLat, draftLon, draftRadius,
            serverLat, serverLon, serverRadius,
        )
    }

    /**
     * Distinct caller-scoped orgs visible in a server site list (normally
     * zero or one — the server filters by token org). More than one is
     * never resolved by guessing; the caller refuses instead.
     */
    private fun orgsOf(items: List<ObjectNode>): Set<String> = items.mapNotNull {
        it.get("org_id")?.takeIf { f -> f.isTextual }?.asText()
            ?.takeIf { org -> org.isNotBlank() }
    }.toSet()

    private fun itemsOf(view: ObjectNode): List<ObjectNode> =
        view.get("items")?.mapNotNull { it as? ObjectNode } ?: emptyList()
}
