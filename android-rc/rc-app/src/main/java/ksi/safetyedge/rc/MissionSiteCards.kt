package ksi.safetyedge.rc

/**
 * Adopted site-first mission model: concise site cards plus the stored-plan
 * detail for the selected site. Pure Kotlin, no Android — host-tested.
 *
 * Data comes ONLY from what the RC already holds (synced sites, locally
 * stored mission bundles, local flight records). Nothing is invented:
 * - a site card shows the stored name + one honest status line + an
 *   optional suggestion chip (only when a GNSS suggestion actually named
 *   this site);
 * - a plan card shows the stored bundle (short ids, zone/rule counts from
 *   its own snapshot) + the plain past outcome counted from local flights
 *   for that site ("지난 결과 없음" when there are none);
 * - there is no route thumbnail: without a map tile source any shape would
 *   be invented, so the card shows the stored facts as text instead;
 * - the new polygon-first patrol is UNAVAILABLE (no map editor), disclosed
 *   rather than fabricated — the entry stays disabled with its reason.
 *
 * Selecting a card never starts, applies, or records anything; it only
 * selects ids for the existing prepare/apply/record path. A suggestion
 * chip never forces: tapping it selects, it never auto-confirms.
 */
object MissionSiteCards {

    /** One synced site, as the RC store holds it. */
    data class SiteInput(
        val siteId: String,
        val siteName: String?,
        /** Stored mission plans pinned to this site (newest last). */
        val plans: List<PlanInput>,
        /** Local flights recorded at this site (site-level context only). */
        val pastFlights: List<FlightInput>,
        /** True only when an actual GNSS suggestion named this site. */
        val suggested: Boolean,
        /**
         * True for a locally drafted site (client UUID, not a server id)
         * that the Core has not registered yet. Pending sites render with
         * the honest pending badge and stay selectable like synced sites
         * for NEW preparations — selecting never claims registration.
         */
        val isPending: Boolean = false,
        /** Pending reason suffix (no Core, offline, last failure...). */
        val pendingNote: String? = null,
    )

    /** One locally stored mission bundle for the site. */
    data class BundleInput(
        val bundleId: String,
        val bundleSha256: String?,
        val missionRevisionId: String?,
        /** Null = the snapshot fact itself is missing (never "0개"). */
        val zoneCount: Int?,
        /** Null = the policy fact itself is missing (never "0건"). */
        val ruleCount: Int?,
        /** Human-readable facts from the stored snapshot and policy only. */
        val details: List<String> = emptyList(),
    )

    /** One local flight record at the site (outcome source only). */
    data class FlightInput(
        val flightId: String,
        /** The bundle sha the flight actually recorded (null = unknown). */
        val bundleSha256: String?,
        val state: String,
        val observationCount: Int,
    )

    data class SiteCard(
        val siteId: String,
        val title: String,
        /** One line: stored-plan count + readiness fact. */
        val statusLine: String,
        /** Non-null only for a genuinely suggested site. */
        val suggestionChip: String?,
        val hasPlans: Boolean,
        /**
         * Non-null only for a pending (locally drafted) site: the exact
         * honest badge "이 기기에 저장됨 · 중앙 등록 대기" — never "등록됨".
         */
        val pendingBadge: String?,
    )

    data class PlanCard(
        val bundleId: String,
        /** Stored facts only: short bundle/revision ids + counts. */
        val factLine: String,
        /** No map tiles: the route shape is honestly unreceived. */
        val routeLine: String,
        /**
         * Plain past outcome counted ONLY from local flights that recorded
         * THIS bundle (matched by stored bundle sha). Flights of other
         * plans never leak into this card.
         */
        val pastLine: String,
        val hasPast: Boolean,
    )

    fun siteCard(input: SiteInput): SiteCard {
        val title = input.siteName?.takeIf { it.isNotBlank() } ?: "현장 ${input.siteId.take(8)}"
        val statusLine = if (input.isPending) {
            PendingSiteRegistration.statusLine(input.pendingNote)
        } else if (input.plans.isEmpty()) {
            "저장된 계획 없음 · 번들 내려받기가 필요합니다"
        } else {
            "저장 계획 ${input.plans.size}건 · " +
                if (input.pastFlights.isEmpty()) "지난 결과 없음" else "지난 결과 ${input.pastFlights.size}건"
        }
        return SiteCard(
            siteId = input.siteId,
            title = title,
            statusLine = statusLine,
            suggestionChip = if (input.suggested) "근처 현장 제안 · 선택은 조종자가 확정합니다" else null,
            hasPlans = input.plans.isNotEmpty(),
            pendingBadge = if (input.isPending) PendingSiteRegistration.PENDING_BADGE else null,
        )
    }

    fun siteCards(inputs: List<SiteInput>): List<SiteCard> = inputs.map { siteCard(it) }

    /**
     * Maps one pending draft to a selectable [SiteInput] (client UUID as
     * the id — never presented as a server id). Pending sites carry no
     * server plans; locally composed plans and flights recorded under the
     * client id may be attached by the caller. The suggestion flag stays
     * purely selective: a pending site with a confirmed location can be
     * suggested, never auto-confirmed.
     */
    fun pendingInput(
        draft: ksi.rc.PendingSiteDraft,
        plans: List<PlanInput> = emptyList(),
        pastFlights: List<FlightInput> = emptyList(),
        suggested: Boolean = false,
        pendingNote: String? = null,
    ): SiteInput = SiteInput(
        siteId = draft.clientSiteId,
        siteName = draft.name,
        plans = plans,
        pastFlights = pastFlights,
        suggested = suggested,
        isPending = true,
        pendingNote = pendingNote,
    )

    /**
     * One card per stored bundle. The past outcome of each plan counts ONLY
     * the flights that recorded that plan's own bundle sha — a site-wide
     * flight list is never spread across plans. A bundle whose sha matches
     * no flight claims no past result.
     */
    fun planCards(plans: List<PlanInput>): List<PlanCard> = plans.map { plan ->
        val bundle = plan.bundle
        val past = plan.pastFlights
        val recent = past.lastOrNull()?.let { flight ->
            when (flight.state) {
                "STARTED" -> "기록 중"
                "ENDED" -> "기록 종료 · 관측 ${flight.observationCount}건"
                else -> flight.state
            }
        }
        PlanCard(
            bundleId = bundle.bundleId,
            factLine = "계획 ${bundle.bundleId.take(8)} · 미션 rev " +
                "${bundle.missionRevisionId?.take(8) ?: "미확인"} · " +
                "구역 ${bundle.zoneCount?.let { "${it}개" } ?: "미확인"} · " +
                "규칙 ${bundle.ruleCount?.let { "${it}건" } ?: "미확인"}",
            routeLine = "경로 자료 미수신",
            pastLine = if (past.isEmpty()) {
                "지난 결과 없음"
            } else {
                "지난 결과 ${past.size}건 · 최근 ${recent ?: "알 수 없음"}"
            },
            hasPast = past.isNotEmpty(),
        )
    }

    /** A stored plan plus ONLY the flights that recorded its own bundle. */
    data class PlanInput(
        val bundle: BundleInput,
        val pastFlights: List<FlightInput>,
    )
}
