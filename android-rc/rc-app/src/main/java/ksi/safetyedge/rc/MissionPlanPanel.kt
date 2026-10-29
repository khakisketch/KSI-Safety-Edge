package ksi.safetyedge.rc

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Native stored-plan browser. Selection has no command side effects. */
class MissionPlanPanel(private val context: Context, private val host: LinearLayout) {
    private data class Content(
        val site: MissionSiteCards.SiteInput,
        val selected: String?,
        val active: String?,
        val preparing: String?,
    )
    private var content: Content? = null
    private val pastLabels = mutableMapOf<String, TextView>()
    private val historyButtons = mutableMapOf<String, Button>()

    private fun Content.structure() = copy(site = site.copy(
        plans = site.plans.map { it.copy(pastFlights = emptyList()) },
        pastFlights = emptyList(), suggested = false,
    ))

    fun clear() {
        content = null
        pastLabels.clear()
        historyButtons.clear()
        host.removeAllViews()
        host.visibility = View.GONE
    }

    fun bind(
        site: MissionSiteCards.SiteInput,
        selected: String?,
        active: String?,
        preparing: String?,
        onSelect: (String) -> Unit,
        onHistory: (String) -> Unit,
    ) {
        val next = Content(site, selected, active, preparing)
        if (content == next) return // Polling must not destroy focus or scroll.
        val siteChanged = content?.site?.siteId != site.siteId
        val sameStructure = content?.structure() == next.structure()
        content = next
        val plans = MissionSiteCards.planCards(site.plans)
        if (sameStructure) {
            // Observation counts may change while the operator reads history.
            // Update facts in place rather than discarding focused views.
            plans.forEach { plan ->
                pastLabels[plan.bundleId]?.text = plan.routeLine + " · " + plan.pastLine
                historyButtons[plan.bundleId]?.visibility = if (plan.hasPast) View.VISIBLE else View.GONE
            }
            return
        }
        pastLabels.clear()
        historyButtons.clear()
        host.removeAllViews()
        host.visibility = View.VISIBLE
        host.addView(label(MissionSiteCards.siteCard(site).title, 16f, bold = true))
        if (site.isPending) host.addView(label(PendingSiteRegistration.PENDING_BADGE, 12f, quiet = true))
        if (plans.isEmpty()) {
            host.addView(label("저장된 계획 없음", 16f, bold = true))
            host.addView(label("계획 관리에서 번들을 내려받거나 로컬 계획을 구성하세요. 일반 비행은 별도입니다.", 13f, quiet = true))
            return
        }
        host.addView(label("저장된 관측 계획 ${plans.size}건 · 선택만으로 장치에 요청하지 않습니다.", 12f, quiet = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(4) })
        if (preparing != null) {
            host.addView(label("계획 ${preparing.take(8)} 준비 중 · 다른 계획은 선택할 수 없습니다. 준비 중인 계획의 현장으로 돌아가세요.", 12f, quiet = true))
        }
        plans.forEachIndexed { index, plan ->
            val bundle = site.plans[index].bundle
            val chosen = plan.bundleId == selected
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundResource(R.drawable.mission_row_background)
                isSelected = chosen
            }
            val heading = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            val facts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            bundle.details.forEach { facts.addView(label(it, 14f, bold = true)) }
            facts.addView(label("계획 ${plan.bundleId.take(8)}", 12f, quiet = true))
            facts.addView(label("구역 ${bundle.zoneCount?.let { "${it}개" } ?: "미확인"} · 규칙 ${bundle.ruleCount?.let { "${it}건" } ?: "미확인"} · 개정 ${bundle.missionRevisionId?.take(8) ?: "미확인"}", 13f))
            heading.addView(facts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            heading.addView(Button(context).apply {
                text = if (chosen) "선택된 계획" else "이 계획 선택"
                isAllCaps = false
                textSize = 13f
                minimumHeight = dp(48)
                minWidth = dp(48)
                setBackgroundResource(if (chosen) R.drawable.button_ghost else R.drawable.button_secondary)
                setTextColor(ContextCompat.getColorStateList(context, R.color.button_ghost_text))
                isEnabled = !chosen && MissionSelection.canChoose(plan.bundleId, active, preparing)
                contentDescription = "계획 ${plan.bundleId} · $text. 선택만 하며 장치 요청·기록 시작 없음"
                setOnClickListener {
                    onSelect(plan.bundleId)
                }
            }, LinearLayout.LayoutParams(dp(112), -2))
            card.addView(heading)
            card.addView(label(plan.routeLine + " · " + plan.pastLine, 12f, quiet = true).also { pastLabels[plan.bundleId] = it },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            card.addView(Button(context).apply {
                text = "지난 결과 보기"
                isAllCaps = false
                textSize = 13f
                minimumHeight = dp(48)
                setBackgroundResource(R.drawable.button_ghost)
                setTextColor(ContextCompat.getColorStateList(context, R.color.button_ghost_text))
                visibility = if (plan.hasPast) View.VISIBLE else View.GONE
                setOnClickListener {
                    content?.site?.plans?.firstOrNull { it.bundle.bundleId == plan.bundleId }
                        ?.pastFlights?.lastOrNull()?.flightId?.let(onHistory)
                }
                historyButtons[plan.bundleId] = this
            }, LinearLayout.LayoutParams(-1, -2))
            host.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        if (siteChanged) host.post { (host.parent as? android.widget.ScrollView)?.scrollTo(0, 0) }
    }

    private fun label(value: String, size: Float, bold: Boolean = false, quiet: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context, if (quiet) R.color.ksi_muted else R.color.ksi_fg))
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        includeFontPadding = false
        fontFeatureSettings = "tnum"
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()
}
