package ksi.safetyedge.rc

import ksi.rc.PendingDraftStatus
import ksi.rc.PendingSyncOutcome
import ksi.rc.PendingSyncReport

/**
 * Mission-screen wiring for offline-first site registration (현장 추가):
 * pure Kotlin, no Android — host-tested. MainActivity binds to these
 * facts and copies; no string is invented in the Activity layer.
 *
 * Data comes ONLY from what the RC already holds (synced sites, the
 * durable pending-site journal, Core reachability the session reports).
 * Nothing is invented:
 * - a pending draft keeps its client UUID as its selectable id (never
 *   presented as a server id) and NEVER reads as 등록됨;
 * - a server name-keyed match surfaces as a link to the central site with
 *   the same name, never as a new registration;
 * - a failed upload attempt keeps the draft pending with its reason;
 * - storage down refuses creation fail-closed (no silent memory-only
 *   drafts) behind the storage banner.
 */
object PendingSiteMissionFlow {

    /**
     * Creation refusal when durable storage is down (fail-closed): the
     * caller shows this banner instead of opening the dialog. Null when
     * the journal is usable — never a silent memory-only draft.
     */
    fun creationRefusal(storageAvailable: Boolean): String? =
        if (storageAvailable) null else StorageHealth.UNAVAILABLE_BANNER

    /**
     * Pending reason suffix for one unresolved draft (null = plain pending
     * badge, no reason to state). Precedence: a real upload failure first,
     * then the reason nothing was ever attempted.
     */
    fun pendingNoteFor(
        status: PendingDraftStatus,
        coreConfigured: Boolean,
        orgKnown: Boolean,
    ): String? = when {
        status.lastAttemptError != null -> PendingSiteRegistration.ATTEMPT_FAILED_PREFIX
        !coreConfigured -> PendingSiteRegistration.NO_CORE_REASON
        !orgKnown -> PendingSiteRegistration.ORG_UNKNOWN_REASON
        else -> null
    }

    /**
     * Selection honesty: an id stays selected only when it names a synced
     * site or an unresolved pending draft. A resolved draft's client UUID
     * is NOT selectable — its canonical site id takes over (the caller
     * migrates via [ksi.rc.PendingSiteJournal.canonicalIdFor]).
     */
    fun isSelectableSiteId(
        siteId: String?,
        syncedIds: Collection<String>,
        pendingIds: Collection<String>,
    ): Boolean = siteId != null && (siteId in syncedIds || siteId in pendingIds)

    /**
     * Honest sync-result lines for a [PendingSyncReport]: one line per
     * resolved draft (created vs name-matched), or the untouched reason
     * when nothing was attempted (offline field day, no Core, unknown org).
     * [nameFor] reads the draft name from the journal (drafts survive
     * resolution there); unknown ids fall back to a short id, never a
     * fabricated name. A name-matched draft whose location the server did
     * NOT keep carries the mismatch warning — the central record's
     * coordinates stand, and the operator must confirm the right place.
     */
    fun resolutionLines(
        report: PendingSyncReport,
        nameFor: (String) -> String?,
    ): List<String> {
        if (!report.attempted || report.results.isEmpty()) {
            return listOfNotNull(report.detail)
        }
        return report.results.map { result ->
            val name = nameFor(result.clientSiteId) ?: result.clientSiteId.take(8)
            val matched = result.outcome == PendingSyncOutcome.MATCHED_EXISTING
            buildString {
                append(name)
                append(" · ${PendingSiteRegistration.resolutionNotice(matched)}")
                if (result.droppedLocation != null) {
                    append(" · ${PendingSiteRegistration.MATCHED_LOCATION_WARN}")
                }
            }
        }
    }

    /**
     * Pending count suffix for sync/creation confirmations ("대기 N건").
     * Null when nothing is pending — zero is stated by the empty state,
     * never as a count here.
     */
    fun pendingCountSuffix(pendingCount: Int): String? =
        if (pendingCount > 0) "대기 ${pendingCount}건" else null
}
