package ksi.safetyedge.rc

import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.Gravity
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.node.ObjectNode

/** Available stored context, explicitly NOT an aircraft video, basemap or evidence photograph. */
class StoredContextPanel(private val activity: AppCompatActivity, private val offerSites: Boolean) : FrameLayout(activity) {
    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()
    private val zone = StoredZonePreviewView(activity)
    private val choices = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val choicesScroll = ScrollView(activity).apply { isFillViewport = false }
    private var cardsKey: String? = null
    private var choose: ((String) -> Unit)? = null
    val hasGeometry: Boolean get() = zone.hasGeometry

    init {
        setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
        addView(zone, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            if (offerSites) { leftMargin = dp(284); rightMargin = dp(144); topMargin = dp(8) }
        })
        choicesScroll.addView(choices, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(choicesScroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(284); rightMargin = dp(144); topMargin = dp(16); bottomMargin = dp(64)
        })
        zone.visibility = GONE
        choicesScroll.visibility = if (offerSites) VISIBLE else GONE
    }

    fun bindPlan(bundle: ObjectNode?) {
        zone.setBundle(bundle)
        zone.visibility = if (zone.hasGeometry) VISIBLE else GONE
        choicesScroll.visibility = if (offerSites && !zone.hasGeometry) VISIBLE else GONE
    }

    fun bindSites(cards: List<MissionSiteCards.SiteCard>, selectable: Boolean, onChoose: (String) -> Unit) {
        choose = onChoose
        val key = cards.joinToString { "${it.siteId}|${it.title}|${it.statusLine}|${it.pendingBadge}" } + selectable
        if (key == cardsKey) return
        cardsKey = key
        choices.removeAllViews()
        choices.addView(TextView(activity).apply {
            text = if (cards.isEmpty()) "저장 현장 없음" else "관측할 현장 선택"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_video_fg))
            setPadding(dp(8), 0, dp(8), dp(8))
        })
        if (cards.isEmpty()) {
            choices.addView(TextView(activity).apply {
                text = "‘기록 준비’에서 현장을 추가하세요."
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.ksi_video_fg))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            })
        }
        cards.forEach { card ->
            choices.addView(Button(activity).apply {
                text = listOfNotNull(card.title, card.statusLine, card.pendingBadge).joinToString("\n")
                textSize = 12f
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minWidth = 0
                minimumHeight = dp(64)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setTextColor(ContextCompat.getColor(activity, R.color.ksi_video_fg))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(4).toFloat()
                    setColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
                    setStroke(dp(1), ContextCompat.getColor(activity, R.color.ksi_video_grid))
                }
                isEnabled = selectable
                contentDescription = "${card.title}. ${card.statusLine}. 임무에서 계획 선택."
                setOnClickListener { if (isEnabled) choose?.invoke(card.siteId) }
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(8)
                }
            })
        }
    }
}
