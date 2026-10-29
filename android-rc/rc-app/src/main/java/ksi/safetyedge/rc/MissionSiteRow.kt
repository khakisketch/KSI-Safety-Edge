package ksi.safetyedge.rc

import android.content.Context
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/** Presentation only. The caller retains every site-selection/recording gate. */
object MissionSiteRow {
    fun create(
        context: Context,
        card: MissionSiteCards.SiteCard,
        selected: Boolean,
        onSelect: () -> Unit,
    ): View {
        fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
        fun label(value: String, style: Int) = TextView(context).apply {
            text = value
            setTextAppearance(style)
            includeFontPadding = false
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundResource(R.drawable.mission_row_background)
            isSelected = selected
            isClickable = true
            isFocusable = true
            minimumHeight = dp(96)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
            contentDescription = buildString {
                append(card.title).append(". ").append(card.statusLine)
                if (selected) append(". 선택됨")
                card.pendingBadge?.let { append(". ").append(it) }
                card.suggestionChip?.let { append(". ").append(it) }
                append(". 계획 보기")
            }
            setOnClickListener { onSelect() }
            addView(label(card.title, R.style.TextAppearance_Ksi_Body).apply {
                typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
            addView(label(card.statusLine.replaceFirst(" · ", "\n"), R.style.TextAppearance_Ksi_Label).apply {
                // A missing-plan/pending reason must not disappear after one line.
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            card.pendingBadge?.let { badge ->
                addView(label(badge, R.style.TextAppearance_Ksi_Label).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.ksi_attention))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            card.suggestionChip?.let { suggestion ->
                addView(label(suggestion, R.style.TextAppearance_Ksi_Label).apply {
                    setTextColor(ContextCompat.getColor(context, R.color.ksi_attention))
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            }
            addView(label(if (selected) "선택됨" else "계획 보기", R.style.TextAppearance_Ksi_Label).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setTextColor(ContextCompat.getColor(context, R.color.ksi_primary))
                compoundDrawablePadding = dp(4)
                setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                    WorkspaceIcons.drawable(context, WorkspaceIcons.Kind.NEXT, R.color.ksi_primary, 16), null)
                // Only the parent is actionable; this is not a sub-48dp button.
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }
}
