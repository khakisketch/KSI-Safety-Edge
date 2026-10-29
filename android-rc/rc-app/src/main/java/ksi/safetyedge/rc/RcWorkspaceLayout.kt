package ksi.safetyedge.rc

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Settled workspace composition (round73): programmatic NATIVE composition
 * reusing ALL 230 inflated original view objects and IDs, so existing
 * business bindings in MainActivity keep working.
 *
 * install() is called immediately after setContentView, BEFORE any
 * findViewById bindings in MainActivity.onCreate. It captures every
 * inflated view into [idMap], detaches the original root from
 * android.R.id.content, and attaches the new vertical root:
 * common header (48dp) + inline tvError (auto-height, min 48) +
 * pageFrame (weight 1) + hiddenLegacyContainer (GONE).
 *
 * The original root and every parked view stay ATTACHED under the hidden
 * legacy container, so every existing view id is still findable. Moved
 * functional views are moved ONCE via the cached map (never duplicated).
 * No business-state writes and no command calls happen here, except
 * explicit proxy clicks on existing buttons (header connection proxy,
 * supervision pill -> btnTaskStatus, compact camera -> btnTaskDetails,
 * plan-manage menu -> the three parked management buttons).
 *
 * Round73 visual settlement (no behavior change): flat ghost / tonal /
 * primary roles from the existing button_* resources on light surfaces,
 * near-opaque dark video chrome (4dp, no stroke) on the scene, authored
 * WorkspaceIcons (never unicode chevrons), 48dp hit floor everywhere,
 * alignment/hairline grouping instead of nested boxes. All 230 objects,
 * callbacks, states and Back handling are unchanged; the only additive
 * surface is R.id.workspaceMissionManage (plan-manage menu entry) and
 * fun bindConnections(edge: String).
 */
class RcWorkspaceLayout(private val activity: AppCompatActivity) {

    private val dm get() = activity.resources.displayMetrics
    private fun dp(v: Int): Int = (v * dm.density + 0.5f).toInt()

    /** Anchor text, not the entire touch box, to the same typographic rail. */
    private fun alignTextBaseline(view: TextView, baselineDp: Int) {
        view.setIncludeFontPadding(false)
        view.gravity = Gravity.TOP or (view.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        val top = (dp(baselineDp) + view.paint.fontMetricsInt.ascent).coerceAtLeast(0)
        view.setPadding(view.paddingLeft, top, view.paddingRight, 0)
    }

    /** Every originally inflated view by id (captured before detach). */
    private val idMap = mutableMapOf<Int, View>()

    private fun req(id: Int): View =
        idMap[id] ?: activity.findViewById<View>(id)
            ?: throw IllegalStateException("RcWorkspaceLayout: missing required id=$id")

    private fun detach(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    private lateinit var parkBox: LinearLayout
    private fun park(v: View) {
        detach(v)
        parkBox.addView(v)
    }

    private fun parkById(id: Int) {
        val v = idMap[id] ?: activity.findViewById<View>(id) ?: return
        park(v)
    }

    // ---- settled button roles (round73) ----------------------------------
    // Flat ghost / tonal / primary on light surfaces reuse the existing
    // button_* resources (stateful enabled/disabled/focused). Video chrome
    // is a near-opaque dark ripple (4dp, no stroke) for scene overlays.

    private fun rolePrimary(b: Button) {
        b.setBackgroundResource(R.drawable.button_primary)
        b.setTextColor(ContextCompat.getColorStateList(activity, R.color.button_primary_text))
        b.isAllCaps = false
        b.minWidth = 0
    }

    private fun roleTonal(b: Button) {
        b.setBackgroundResource(R.drawable.button_secondary)
        b.setTextColor(ContextCompat.getColorStateList(activity, R.color.button_secondary_text))
        b.isAllCaps = false
        b.minWidth = 0
    }

    private fun roleGhost(b: Button, dark: Boolean) {
        b.setBackgroundResource(R.drawable.button_ghost)
        if (dark) {
            val fg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
            b.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 115), fg)))
        } else b.setTextColor(ContextCompat.getColorStateList(activity, R.color.button_ghost_text))
        b.isAllCaps = false
        b.minWidth = 0
    }

    private fun icon(kind: WorkspaceIcons.Kind, colorRes: Int, sizeDp: Int = 20): Drawable =
        WorkspaceIcons.drawable(activity, kind, colorRes, sizeDp)

    /** Near-opaque dark chrome for scene overlays: 4dp, no stroke. */
    private fun videoChrome(): Drawable {
        val r = dp(4).toFloat()
        val fill = ContextCompat.getColor(activity, R.color.ksi_video_bg)
        fun flat(alpha: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(fill)
            this.alpha = alpha
            if (stroke > 0) {
                setStroke(stroke, ContextCompat.getColor(activity, R.color.ksi_video_fg))
            }
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.ksi_video_grid)),
            flat(232, 0),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.WHITE)
            },
        )
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), flat(140, 0))
            addState(intArrayOf(android.R.attr.state_focused), flat(232, dp(2)))
            addState(intArrayOf(), ripple)
        }
    }

    /** Bounded dark sheet for on-video detail, not a bright settings panel. */
    private fun videoPanel(): Drawable {
        val fg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        val bg = ContextCompat.getColor(activity, R.color.ksi_video_bg)
        return GradientDrawable().apply {
            cornerRadius = dp(6).toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(bg, 246))
            setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 70))
        }
    }

    /** Transparent ghost ripple for dark chrome (header menu). */
    private fun ghostOnDark(): Drawable {
        val r = dp(4).toFloat()
        fun flat(stroke: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = r
            setColor(Color.TRANSPARENT)
            if (stroke > 0) {
                setStroke(stroke, ContextCompat.getColor(activity, R.color.ksi_video_fg))
            }
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.ksi_video_grid)),
            flat(0),
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = r
                setColor(Color.WHITE)
            },
        )
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), flat(dp(2)))
            addState(intArrayOf(), ripple)
        }
    }

    private fun roleVideo(b: Button) {
        b.background = videoChrome()
        b.setTextColor(ContextCompat.getColor(activity, R.color.ksi_video_fg))
        b.isAllCaps = false
        b.minWidth = 0
    }

    private fun outlinedFlightControl(): Drawable = android.graphics.drawable.LayerDrawable(arrayOf(
        GradientDrawable().apply {
            cornerRadius = dp(4).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(
                ContextCompat.getColor(activity, R.color.ksi_video_fg), 140))
        },
        ghostOnDark(),
    ))

    // New header views.
    private lateinit var workspaceHeaderContext: TextView
    private lateinit var workspaceHeaderConnection: TextView
    private lateinit var headerBrand: TextView

    // Flight views.
    private lateinit var workspaceHudNumbers: TextView
    private lateinit var flightHud: FlightHudView
    private var hudStyle: FlightHudStyle = FlightHudStyle.DEFAULT
    private var hudStyleButtons: Map<FlightHudStyle, Button> = emptyMap()
    /** MainActivity persists the display preference; this view only reports taps. */
    var onHudStyleSelected: ((FlightHudStyle) -> Unit)? = null
    private lateinit var btnStartFlight: View
    private lateinit var pillBox: LinearLayout
    private lateinit var flightOfflineContext: StoredContextPanel
    private lateinit var flightOfflineLayer: FrameLayout
    private lateinit var videoRecover: Button
    private lateinit var recordContext: StoredContextPanel
    private lateinit var recordFallback: LinearLayout
    private lateinit var recordStage: FrameLayout
    private lateinit var recordSummary: TextView
    private var evidenceVisible = false
    private var evidenceLayoutKey: String? = null

    // Mission views.
    private lateinit var browserColumn: LinearLayout
    private lateinit var prepPanel: LinearLayout
    private lateinit var workspaceMissionSteps: TextView
    private lateinit var workspaceMissionSummary: TextView
    private lateinit var workspaceMissionRejection: TextView
    private var missionRejectionDetail: String? = null
    private lateinit var missionMapView: StoredZonePreviewView
    private lateinit var btnMissionOpenPreparation: Button
    private lateinit var btnMissionBack: Button
    private lateinit var btnMissionDetails: Button
    private lateinit var missionBottomAction: LinearLayout
    private lateinit var missionMainColumn: LinearLayout
    private lateinit var missionSelectionBody: LinearLayout
    private lateinit var missionPlanScroll: ScrollView
    private lateinit var missionPreviewContext: LinearLayout
    private lateinit var missionPreviewSite: TextView
    private var missionRestoredPreparationNote: String? = null
    private lateinit var missionRequestNote: TextView
    private lateinit var missionPendingAction: Button
    private lateinit var missionBoundPlan: Button
    private var missionHasSite = false
    private var missionHasSelection = false

    // Records views.
    private lateinit var workspaceEvidenceExpand: Button
    private lateinit var workspaceZoomOut: Button
    private lateinit var workspaceZoomValue: TextView
    private lateinit var workspaceObservationRecovery: Button
    private lateinit var workspaceReviewContext: TextView
    private lateinit var recordHeader: LinearLayout
    private lateinit var recordEventNav: LinearLayout
    private lateinit var recordTools: LinearLayout
    private lateinit var recordZoomTools: LinearLayout
    private lateinit var workspaceZoomIn: Button
    private lateinit var workspaceZoomReset: Button
    private lateinit var workspaceCustodyEdge: TextView
    private lateinit var workspaceCustodyRc: TextView
    private lateinit var workspaceCustodyCore: TextView
    private var evidenceExpanded = false
    private lateinit var reviewSessionsColumn: View
    private lateinit var filmstripScroll: View

    // Settings views.
    private lateinit var settingsContent: FrameLayout
    private lateinit var settingsPanels: List<View>
    private lateinit var settingsTabs: List<Button>
    private lateinit var coreEditorToggle: Button
    private lateinit var coreEditorForm: View
    private lateinit var aircraftRow: TextView
    private lateinit var edgeConnectionRow: TextView
    private lateinit var videoConnectionRow: TextView
    private lateinit var settingsVideoRecover: Button
    private lateinit var flightPriorityDetail: TextView

    private lateinit var pageObserve: View
    private lateinit var pageMission: View
    private lateinit var pageReview: View
    private lateinit var pageEvidence: View
    private lateinit var pageSettings: View
    private lateinit var navRail: View
    private lateinit var appHeader: View
    private val destinationTabs = mutableMapOf<Int, Button>()
    private lateinit var flightPill: View
    private lateinit var flightCamera: View
    private var liveVideoVisible = false
    private var flightHasRecord = false
    private lateinit var flightSceneActions: LinearLayout
    private lateinit var flightMiniMap: StoredZonePreviewView
    private lateinit var flightStage: FrameLayout
    private lateinit var flightVideoNotice: View
    private lateinit var flightEmptyState: LinearLayout
    private var flightScenePreview: ImageView? = null
    private var flightSceneBadge: TextView? = null
    private var scenePreviewVisible = false
    private var cameraSimulationScenario: String? = null
    private var cameraSimulationStart = 0L
    private val cameraTicker = object : Runnable {
        override fun run() {
            val scenario = cameraSimulationScenario ?: return
            val stage = if (::flightStage.isInitialized) flightStage else return
            if (!stage.isAttachedToWindow || stage.windowVisibility != View.VISIBLE) return
            val elapsed = SystemClock.elapsedRealtime() - cameraSimulationStart
            bindCameraPresentation(WorkspacePreviewFixture.cameraPresentationAt(scenario, elapsed))
            stage.postDelayed(this, 250)
        }
    }
    private val flightTelemetry = mutableListOf<TextView>()
    private val flightTelemetryLabels = listOf("고도 H", "거리 D", "속도", "상승속도", "방위")
    private lateinit var flightLens: TextView
    private lateinit var flightCameraFeedback: TextView
    private var flightCameraState: FlightCameraPresentation? = null
    private lateinit var flightPhotoFrame: View
    private var flightMapExpanded = false
    private val flightStatusValues = mutableListOf<TextView>()
    private lateinit var flightStatusEnd: Button
    private lateinit var flightStatusEndNote: TextView
    private lateinit var cameraLensValue: TextView
    private lateinit var cameraRecValue: TextView
    private lateinit var cameraPhotoValue: TextView
    private lateinit var cameraStateNote: TextView
    private lateinit var flightDetailStateContent: View
    private lateinit var flightDetailCameraContent: View
    private lateinit var flightDetailDetectionsContent: View
    private lateinit var flightDetailStateTab: TextView
    private lateinit var flightDetailCameraTab: TextView
    private lateinit var flightDetailDetectionsTab: TextView
    private lateinit var flightDetectionHeadline: TextView
    private lateinit var flightDetectionDetail: TextView
    private lateinit var flightDetectionOverlay: TextView
    private lateinit var flightDetectionList: LinearLayout
    private var flightDetailTab = FlightDetailTab.STATE

    enum class FlightDetailTab { STATE, CAMERA, DETECTIONS }
    private lateinit var missionOpenFlight: Button
    private lateinit var prepareValue: TextView
    private lateinit var applyValue: TextView
    private lateinit var recordValue: TextView
    private lateinit var missionConnectionNote: TextView
    private lateinit var missionEmpty: LinearLayout
    private lateinit var recordIndex: LinearLayout
    private lateinit var recordIndexRows: LinearLayout
    private lateinit var recordIndexTitle: TextView
    private lateinit var recordDetail: LinearLayout
    private lateinit var recordDetailTitle: TextView
    private var recordDetailOpen = false
    private var recordRowsKey: String? = null
    private var selectedRecordRow: FlightHistoryModel.FlightRow? = null

    private fun text(label: String, size: Float = 13f, quiet: Boolean = false, bold: Boolean = false) = TextView(activity).apply {
        this.text = label
        textSize = size
        setTextColor(ContextCompat.getColor(activity, if (quiet) R.color.ksi_muted else R.color.ksi_fg))
        if (bold) typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        fontFeatureSettings = "tnum"
        includeFontPadding = false
    }

    private fun action(label: String, primary: Boolean = false, click: () -> Unit) = Button(activity).apply {
        text = label
        textSize = 12f
        minimumHeight = dp(48)
        setPadding(dp(12), 0, dp(12), 0)
        if (primary) rolePrimary(this) else roleGhost(this, false)
        setOnClickListener { click() }
    }

    private fun line() = View(activity).apply {
        setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_border))
        layoutParams = LinearLayout.LayoutParams(-1, dp(1))
    }

    fun install() {
        collectIds()
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val originalRoot = content.getChildAt(0)
            ?: throw IllegalStateException("RcWorkspaceLayout: empty content")
        content.removeView(originalRoot)

        val newRoot = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }

        val hiddenLegacy = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        parkBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        hiddenLegacy.addView(
            parkBox,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Keep the original root (with every not-moved view) attached so all
        // existing ids stay findable.
        hiddenLegacy.addView(
            originalRoot,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        buildHeader()
        val tvError = req(R.id.tvError)
        detach(tvError)
        tvError.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        if (tvError is TextView) {
            tvError.minHeight = dp(48)
        }

        val pageFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }

        newRoot.addView(appHeader)
        newRoot.addView(tvError)
        newRoot.addView(pageFrame)
        newRoot.addView(hiddenLegacy)
        content.addView(newRoot)

        // Move the five page containers themselves into the new frame.
        pageObserve = req(R.id.pageObserve)
        pageMission = req(R.id.pageMission)
        pageReview = req(R.id.pageReview)
        pageEvidence = req(R.id.pageEvidence)
        pageSettings = req(R.id.pageSettings)
        navRail = req(R.id.navRail)
        // navRail stays attached under hiddenLegacy (metadata only, GONE).
        navRail.visibility = View.GONE
        listOf(pageObserve, pageMission, pageReview, pageEvidence, pageSettings).forEach { page ->
            detach(page)
            page.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            pageFrame.addView(page)
        }

        buildFlight()
        buildMission()
        buildRecords()
        buildEvidence()
        buildSettings()

        onDestination(R.id.btnNavObserve)
    }

    // ------------------------------------------------------------------ root

    private fun collectIds() {
        idMap.clear()
        val content = activity.findViewById<FrameLayout>(android.R.id.content)
        val root = content.getChildAt(0) ?: return
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val v = stack.removeLast()
            val id = v.id
            if (id != View.NO_ID && !idMap.containsKey(id)) idMap[id] = v
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) stack.add(v.getChildAt(i))
            }
        }
    }

    private fun buildHeader() {
        appHeader = req(R.id.appHeader)
        val btnNavMenu = req(R.id.btnNavMenu) as Button
        val tvPageTitle = req(R.id.tvPageTitle) as TextView
        val tvTopSim = req(R.id.tvTopSim)
        val btnConnectionDetail = req(R.id.btnConnectionDetail)
        detach(appHeader)
        // Park header metadata; reachable via existing dialogs, still findable.
        parkById(R.id.tvTopUtc)
        parkById(R.id.tvTopContext)
        parkById(R.id.tvConnectionHeadline)
        parkById(R.id.btnReviewDetail)
        parkById(R.id.btnOpenEvidence)
        // Proxy target stays attached (hidden) so performClick() works.
        detach(btnConnectionDetail)
        btnConnectionDetail.visibility = View.GONE
        parkBox.addView(btnConnectionDetail)

        // Detach kept header children so the header can be rebuilt in order.
        detach(btnNavMenu)
        detach(tvPageTitle)
        detach(tvTopSim)

        val header = appHeader as LinearLayout
        header.removeAllViews()
        header.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
        header.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
        )
        header.setPadding(dp(12), 0, dp(8), 0)
        header.gravity = Gravity.CENTER_VERTICAL

        val videoFg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        btnNavMenu.apply {
            roleGhost(this, dark = true)
            background = ghostOnDark()
            setTextColor(videoFg)
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.VISIBLE
            minimumHeight = dp(48)
            minWidth = 0
            compoundDrawablePadding = dp(4)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(48)).apply {
                marginEnd = dp(8)
            }
        }

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        tvPageTitle.apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(videoFg)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        workspaceHeaderContext = TextView(activity).apply {
            id = R.id.workspaceHeaderContext
            textSize = 11f
            maxLines = 2
            setTextColor(videoFg)
            alpha = 0.72f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        titleCol.addView(tvPageTitle)
        titleCol.addView(workspaceHeaderContext)

        workspaceHeaderConnection = TextView(activity).apply {
            id = R.id.workspaceHeaderConnection
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            textSize = 12f
            maxLines = 2
            ellipsize = null
            setTextColor(videoFg)
            minimumHeight = dp(48)
            isClickable = true
            isFocusable = true
            setOnClickListener { btnConnectionDetail.performClick() }
            layoutParams = LinearLayout.LayoutParams(dp(236), dp(48)).apply {
                marginStart = dp(8)
            }
        }

        // Four stable destinations, not a hidden navigation drawer. Original
        // IDs retain their existing MainActivity listeners and navigation.
        park(btnNavMenu)
        park(titleCol)
        header.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface))
        header.setPadding(dp(16), 0, dp(12), 0)
        headerBrand = text("KSI", 16f, bold = true)
        header.addView(headerBrand, LinearLayout.LayoutParams(dp(48), dp(48)))
        alignTextBaseline(headerBrand, 29)
        listOf(R.id.btnNavObserve to "비행", R.id.btnNavMission to "임무",
            R.id.btnNavReview to "기록", R.id.btnNavSettings to "설정").forEach { (id, label) ->
            val tab = req(id) as Button
            detach(tab)
            tab.text = label
            tab.contentDescription = "$label 화면"
            tab.textSize = 14f
            tab.setPadding(dp(8), 0, dp(8), 0)
            tab.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
            roleGhost(tab, false)
            alignTextBaseline(tab, 29)
            header.addView(tab, LinearLayout.LayoutParams(dp(60), dp(48)).apply { marginEnd = dp(8) })
            destinationTabs[id] = tab
        }
        header.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        // Source labels are kept bound, not promoted above the current task.
        (tvTopSim as? TextView)?.apply { textSize = 10f; setPadding(dp(6), dp(3), dp(6), dp(3)) }
        header.addView(tvTopSim)
        workspaceHeaderConnection.apply {
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            textSize = 11f
            maxLines = 1
            setIncludeFontPadding(false)
            setPadding(dp(12), 0, 0, 0)
            background = settingsRowBackground(false)
            layoutParams = LinearLayout.LayoutParams(dp(316), dp(48))
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon(WorkspaceIcons.Kind.NEXT, R.color.ksi_muted, 16), null)
            compoundDrawablePadding = dp(4)
        }
        header.addView(workspaceHeaderConnection)
        alignTextBaseline(workspaceHeaderConnection, 29)
        // Keep the end chevron on the text rail, not in the remaining
        // padded touch-box height below a TOP-gravity line.
        workspaceHeaderConnection.setPadding(workspaceHeaderConnection.paddingLeft,
            workspaceHeaderConnection.paddingTop, workspaceHeaderConnection.paddingRight,
            (dp(48 - 29) - workspaceHeaderConnection.paint.fontMetricsInt.descent).coerceAtLeast(0))
        header.visibility = View.VISIBLE
        appHeader = header
    }

    private fun applyMenuFor(navId: Int) {
        val flight = navId == R.id.btnNavObserve
        val headerInk = if (flight) R.color.ksi_video_fg else R.color.ksi_fg
        appHeader.setBackgroundColor(ContextCompat.getColor(activity, if (flight) R.color.ksi_video_bg else R.color.ksi_surface))
        headerBrand.setTextColor(ContextCompat.getColor(activity, headerInk))
        workspaceHeaderConnection.setTextColor(ContextCompat.getColor(activity, headerInk))
        workspaceHeaderConnection.background = if (flight) ghostOnDark() else settingsRowBackground(false)
        workspaceHeaderConnection.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon(WorkspaceIcons.Kind.NEXT, headerInk, 16), null)
        destinationTabs.forEach { (id, tab) ->
            val selected = id == navId
            tab.isSelected = selected
            tab.background = if (flight) ghostOnDark() else settingsRowBackground(false)
            if (selected) {
                val base = if (flight) ghostOnDark() else settingsRowBackground(false)
                tab.background = android.graphics.drawable.LayerDrawable(arrayOf(base, GradientDrawable().apply {
                    setColor(ContextCompat.getColor(activity, if (flight) R.color.ksi_video_fg else R.color.ksi_primary))
                })).apply {
                    setLayerSize(1, dp(28), dp(2))
                    setLayerGravity(1, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                }
            }
            tab.setTextColor(ContextCompat.getColor(activity, if (flight) R.color.ksi_video_fg else if (selected) R.color.ksi_primary else R.color.ksi_muted))
            tab.typeface = android.graphics.Typeface.create(if (selected) "sans-serif-medium" else "sans-serif", 0)
        }
        val menu = (idMap[R.id.btnNavMenu] as? Button) ?: activity.findViewById(R.id.btnNavMenu)
        menu ?: return
        val videoFg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        val label = when (navId) {
            R.id.btnNavMission -> "임무"
            R.id.btnNavReview -> "기록"
            R.id.btnNavSettings -> "설정"
            else -> "비행"
        }
        menu.text = label
        menu.contentDescription = "$label 화면 메뉴"
        menu.setCompoundDrawablesRelativeWithIntrinsicBounds(
            null, null, icon(WorkspaceIcons.Kind.DOWN, R.color.ksi_video_fg, 20), null,
        )
        menu.setTextColor(videoFg)
    }

    // ----------------------------------------------------------------- flight

    private fun buildFlight() {
        val page = pageObserve as LinearLayout
        // Snapshot direct children, then reset the container.
        val direct = mutableListOf<View>()
        for (i in 0 until page.childCount) direct.add(page.getChildAt(i))
        page.removeAllViews()
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, 0, 0, 0)
        page.background = null
        page.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))

        val telemetryStrip = idMap[R.id.telemetryStrip]
        val supervisionCompactRow = req(R.id.supervisionCompactRow) as LinearLayout
        val cockpitPriorityRow = idMap[R.id.cockpitPriorityRow]
        val observeWorkRow = req(R.id.observeWorkRow) as LinearLayout
        val cockpitRecordActionRow = req(R.id.cockpitRecordActionRow) as LinearLayout

        // Park telemetry (parent reads the 5 values) and any unknown direct
        // children that are not part of the settled composition.
        if (telemetryStrip != null) park(telemetryStrip)
        direct.forEach { child ->
            if (child !== supervisionCompactRow && child !== cockpitPriorityRow &&
                child !== observeWorkRow && child !== cockpitRecordActionRow &&
                child !== telemetryStrip
            ) {
                park(child)
            }
        }

        // Problem-only priority row stays at flow top (parent toggles it).
        if (cockpitPriorityRow != null) {
            cockpitPriorityRow.setPadding(dp(8), 0, dp(8), 0)
            (req(R.id.tvPrimaryAlert) as TextView).textSize = 12f
            (req(R.id.tvPrimaryAction) as TextView).textSize = 11f
            (req(R.id.btnPriorityDetails) as Button).apply {
                roleGhost(this, dark = true)
                background = ghostOnDark()
                text = "상태 상세"
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(dp(96), dp(48))
            }
            detach(cockpitPriorityRow)
            page.addView(
                cockpitPriorityRow,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // ---- work row -------------------------------------------------
        detach(observeWorkRow)
        observeWorkRow.orientation = LinearLayout.HORIZONTAL
        observeWorkRow.background = null
        // Clear the work row; its columns are re-added below.
        val workChildren = mutableListOf<View>()
        for (i in 0 until observeWorkRow.childCount) workChildren.add(observeWorkRow.getChildAt(i))
        observeWorkRow.removeAllViews()
        val observeLeftColumn = req(R.id.observeLeftColumn) as LinearLayout
        val opsRightColumn = req(R.id.opsRightColumn) as LinearLayout
        detach(observeLeftColumn)
        detach(opsRightColumn)
        observeLeftColumn.background = null
        opsRightColumn.background = null
        // Park anything else that lived in the work row.
        workChildren.forEach { child ->
            if (child !== observeLeftColumn && child !== opsRightColumn) park(child)
        }
        observeLeftColumn.orientation = LinearLayout.VERTICAL
        observeLeftColumn.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        opsRightColumn.layoutParams =
            LinearLayout.LayoutParams(dp(272), LinearLayout.LayoutParams.MATCH_PARENT)
        opsRightColumn.visibility = View.GONE
        observeWorkRow.addView(observeLeftColumn)
        observeWorkRow.addView(opsRightColumn)
        observeWorkRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        )

        // ---- left column: video frame ---------------------------------
        val leftChildren = mutableListOf<View>()
        for (i in 0 until observeLeftColumn.childCount) leftChildren.add(observeLeftColumn.getChildAt(i))
        observeLeftColumn.removeAllViews()
        val videoFrame = req(R.id.videoFrame) as FrameLayout
        detach(videoFrame)
        videoFrame.background = null
        leftChildren.forEach { child -> if (child !== videoFrame) park(child) }
        videoFrame.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        observeLeftColumn.addView(videoFrame)

        // videoFrame children: videoStage (MATCH) + rightStatusPanel (272 END).
        val frameChildren = mutableListOf<View>()
        for (i in 0 until videoFrame.childCount) frameChildren.add(videoFrame.getChildAt(i))
        frameChildren.forEach { videoFrame.removeView(it) }
        val videoStage = req(R.id.videoStage) as FrameLayout
        val rightStatusPanel = req(R.id.rightStatusPanel)
        detach(videoStage)
        detach(rightStatusPanel)
        videoStage.background = null
        rightStatusPanel.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
        rightStatusPanel.setPadding(dp(8), dp(8), dp(8), dp(8))
        frameChildren.forEach { child ->
            if (child !== videoStage && child !== rightStatusPanel) park(child)
        }
        videoFrame.addView(
            videoStage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // Bounded dark side sheet: the video center, camera cluster above,
        // map at bottom-left and tools at bottom-right stay visible.
        // The detail is a deliberate full-screen mode, not a side dock that
        // steals video width during normal flight.
        rightStatusPanel.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END or Gravity.TOP,
        ).apply { setMargins(0, 0, 0, 0) }
        rightStatusPanel.visibility = View.GONE
        videoFrame.addView(rightStatusPanel)

        // videoStage: reset children, then SurfaceView + placeholder + overlays.
        val stageChildren = mutableListOf<View>()
        for (i in 0 until videoStage.childCount) stageChildren.add(videoStage.getChildAt(i))
        stageChildren.forEach { videoStage.removeView(it) }
        val surfaceVideo = req(R.id.surfaceVideo)
        val tvVideoPlaceholder = req(R.id.tvVideoPlaceholder) as TextView
        val tvMapPlaceholder = req(R.id.tvMapPlaceholder) as TextView
        val cockpitHeaderAlertStack = idMap[R.id.cockpitHeaderAlertStack]
        detach(surfaceVideo)
        detach(tvVideoPlaceholder)
        detach(tvMapPlaceholder)
        stageChildren.forEach { child ->
            if (child !== surfaceVideo && child !== tvVideoPlaceholder &&
                child !== tvMapPlaceholder && child !== cockpitHeaderAlertStack
            ) {
                park(child)
            }
        }
        if (cockpitHeaderAlertStack != null) park(cockpitHeaderAlertStack)
        videoStage.addView(
            surfaceVideo,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        // No unavailable video-sized dead box. While frames are absent,
        // available pinned geometry or actual stored sites lead the work area.
        flightOfflineLayer = FrameLayout(activity)
        flightOfflineContext = StoredContextPanel(activity, offerSites = true)
        flightOfflineLayer.addView(flightOfflineContext, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
        val videoRecoveryRow = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
            setPadding(dp(12), 0, dp(12), 0)
        }
        tvVideoPlaceholder.apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_video_fg))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            maxLines = 2
            maxWidth = Int.MAX_VALUE
            minWidth = 0
            minHeight = dp(48)
            setPadding(0, 0, 0, 0)
        }
        videoRecoveryRow.addView(tvVideoPlaceholder, LinearLayout.LayoutParams(0, dp(48), 1f))
        videoRecover = Button(activity).apply {
            id = R.id.workspaceVideoRecover
            text = "영상 연결 확인"
            textSize = 12f
            minWidth = 0
            minimumHeight = dp(48)
            setOnClickListener { if (tvVideoPlaceholder.isClickable) tvVideoPlaceholder.performClick() }
        }
        roleVideo(videoRecover)
        videoRecoveryRow.addView(videoRecover, LinearLayout.LayoutParams(dp(128), dp(48)))
        flightOfflineLayer.addView(videoRecoveryRow, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        videoStage.addView(flightOfflineLayer, FrameLayout.LayoutParams(-1, -1))

        // Supervision pill overlay: left-top, width 260, flat dark, no stroke.
        detach(supervisionCompactRow)
        val pillChildren = mutableListOf<View>()
        for (i in 0 until supervisionCompactRow.childCount) {
            pillChildren.add(supervisionCompactRow.getChildAt(i))
        }
        pillChildren.forEach { supervisionCompactRow.removeView(it) }
        val tvCompactAi = req(R.id.tvCompactAi) as TextView
        val tvCompactCamera = req(R.id.tvCompactCamera) as TextView
        val tvCompactPreserve = req(R.id.tvCompactPreserve) as TextView
        val tvCompactSite = idMap[R.id.tvCompactSite]
        listOf(tvCompactAi, tvCompactCamera, tvCompactPreserve).forEach { detach(it) }
        if (tvCompactSite != null) park(tvCompactSite)
        pillChildren.forEach { child ->
            if (child !== tvCompactAi && child !== tvCompactCamera &&
                child !== tvCompactPreserve && child !== tvCompactSite
            ) {
                park(child)
            }
        }
        val videoFg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        tvCompactAi.apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(videoFg)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            maxLines = 1
            maxWidth = dp(220)
            layoutParams = LinearLayout.LayoutParams(-2, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        tvCompactPreserve.apply {
            textSize = 10f
            setTextColor(videoFg)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            maxLines = 1
            maxWidth = dp(220)
            layoutParams = LinearLayout.LayoutParams(-2, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        pillBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            setPadding(0, 0, 0, 0)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        supervisionCompactRow.orientation = LinearLayout.VERTICAL
        supervisionCompactRow.isFocusable = true
        supervisionCompactRow.isClickable = true
        supervisionCompactRow.setBackgroundColor(Color.TRANSPARENT)
        supervisionCompactRow.setPadding(0, 0, 0, 0)
        supervisionCompactRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        supervisionCompactRow.addView(tvCompactAi)
        supervisionCompactRow.addView(tvCompactPreserve)
        pillBox.addView(
            supervisionCompactRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // The AI/KSI edge text is display-only. Detail is entered from the
        // explicit More > 비행 상세 action, never by brushing the HUD.
        pillBox.isClickable = false
        pillBox.isFocusable = false
        supervisionCompactRow.isClickable = false
        supervisionCompactRow.isFocusable = false

        // Actual Edge start CTA directly below the pill (parent toggles).
        val btnStart = req(R.id.btnStart)
        detach(btnStart)
        if (btnStart is Button) rolePrimary(btnStart)
        btnStart.layoutParams = LinearLayout.LayoutParams(
            dp(144), dp(48),
        ).apply { topMargin = dp(4) }
        btnStart.visibility = View.GONE

        val pillColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.TOP,
            ).apply { setMargins(dp(12), dp(12), 0, 0) }
        }
        pillColumn.addView(pillBox)
        pillColumn.addView(btnStart)
        workspaceObservationRecovery = Button(activity).apply {
            id = R.id.workspaceObservationRecovery
            text = "응답·연결 확인"
            textSize = 12f
            isAllCaps = false
            minimumHeight = dp(48)
            minWidth = 0
            visibility = View.GONE
            setOnClickListener {
                req(R.id.btnReconcile).performClick()
                req(R.id.btnTaskStatus).performClick()
            }
        }
        roleVideo(workspaceObservationRecovery)
        pillColumn.addView(workspaceObservationRecovery, LinearLayout.LayoutParams(dp(144), dp(48)))
        videoStage.addView(pillColumn)
        flightPill = pillColumn
        flightSceneActions = pillColumn

        // Physical shutter/record/dials are primary. The screen reports the
        // lens, zoom and camera feedback; fallback commands live in More.
        val btnReqPhoto = req(R.id.btnReqPhoto) as Button
        val btnReqRec = req(R.id.btnReqRec) as Button
        val btnReqZoom = req(R.id.btnReqZoom) as Button
        listOf(tvCompactCamera, btnReqPhoto, btnReqRec, btnReqZoom).forEach { park(it) }
        // Camera state is read-only edge text, not a card. The physical dial
        // is primary; the touch fallback and full result live in the bounded
        // camera detail sheet, opened deliberately from More.
        val cameraColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(0, dp(12), dp(12), 0)
                gravity = Gravity.END or Gravity.TOP
            }
        }
        flightCameraFeedback = text("", 11f, bold = true).apply {
            id = R.id.workspaceCameraFeedback
            gravity = Gravity.CENTER_VERTICAL
            fontFeatureSettings = "tnum"
            setIncludeFontPadding(false)
            maxLines = 2
            maxWidth = dp(140)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            visibility = View.GONE
        }
        cameraColumn.addView(flightCameraFeedback, LinearLayout.LayoutParams(-2, -2).apply {
            marginEnd = dp(6)
        })
        flightLens = TextView(activity).apply {
            id = R.id.workspaceFlightLens
            textSize = 11f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(4)
            setIncludeFontPadding(false)
            maxLines = 2
            maxWidth = dp(180)
            setTextColor(videoFg)
            setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(WorkspaceIcons.Kind.LENS, R.color.ksi_video_fg, 18), null, null, null,
            )
            contentDescription = "카메라 렌즈·줌 상태. 상세는 더보기에서 확인"
        }
        cameraColumn.addView(flightLens, LinearLayout.LayoutParams(-2, -2))
        videoStage.addView(cameraColumn)
        flightCamera = cameraColumn
        val tools = Button(activity).apply {
            id = R.id.workspaceFlightTools
            // Cardless control: dots over the HUD gradient, not a filled box.
            background = ghostOnDark()
            setTextColor(videoFg)
            isAllCaps = false
            minWidth = 0
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(WorkspaceIcons.Kind.MORE, R.color.ksi_video_fg, 20), null, null, null)
            contentDescription = "카메라·비행 보조 및 KSI 기록 메뉴"
            tooltipText = "조작 및 상세"
            setOnClickListener { showFlightTools(this) }
        }
        videoStage.addView(tools, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(12); bottomMargin = dp(12)
        })
        // Remaining ops entries (gimbal/flight) stay operable inside the
        // existing controls tray; parked views below stay attached so all
        // parent bindings keep working.
        val opsDeckForEntries = idMap[R.id.opsDeck] as? LinearLayout
        val btnReqGimbal = idMap[R.id.btnReqGimbal]
        val btnReqFlight = idMap[R.id.btnReqFlight]
        if (opsDeckForEntries != null) {
            listOf(btnReqGimbal, btnReqFlight).forEach { entry ->
                if (entry != null) {
                    detach(entry)
                    opsDeckForEntries.addView(
                        entry,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
                        ).apply { topMargin = dp(4) },
                    )
                }
            }
        } else {
            parkById(R.id.btnReqGimbal)
            parkById(R.id.btnReqFlight)
        }

        // Map placeholder overlay: bottom-start, 144x72. Actual text is set
        // by the parent; never a fake image or map here.
        tvMapPlaceholder.textSize = 12f
        tvMapPlaceholder.setTypeface(tvMapPlaceholder.typeface, android.graphics.Typeface.NORMAL)
        tvMapPlaceholder.layoutParams = FrameLayout.LayoutParams(dp(144), dp(72)).apply {
            setMargins(dp(12), 0, 0, dp(12))
            gravity = Gravity.START or Gravity.BOTTOM
        }
        videoStage.addView(tvMapPlaceholder)

        page.addView(observeWorkRow)

        // ---- bottom record action row (48, dark) -------------------------
        detach(cockpitRecordActionRow)
        val recordChildren = mutableListOf<View>()
        for (i in 0 until cockpitRecordActionRow.childCount) {
            recordChildren.add(cockpitRecordActionRow.getChildAt(i))
        }
        recordChildren.forEach { cockpitRecordActionRow.removeView(it) }
        val btnTaskPrepare = req(R.id.btnTaskPrepare) as Button
        val btnTaskDetails = req(R.id.btnTaskDetails) as Button
        val btnEndFlight = req(R.id.btnEndFlight) as Button
        val btnTaskStatus = idMap[R.id.btnTaskStatus]
        val btnTaskVideoRecover = idMap[R.id.btnTaskVideoRecover]
        detach(btnTaskPrepare)
        detach(btnTaskDetails)
        detach(btnEndFlight)
        recordChildren.forEach { child ->
            if (child !== btnTaskPrepare && child !== btnTaskDetails &&
                child !== btnTaskStatus && child !== btnTaskVideoRecover
            ) {
                park(child)
            }
        }
        // Existing btnTaskStatus stays parked (attached) so its proxy works.
        if (btnTaskStatus != null) park(btnTaskStatus)
        if (btnTaskVideoRecover != null) park(btnTaskVideoRecover)
        cockpitRecordActionRow.orientation = LinearLayout.HORIZONTAL
        cockpitRecordActionRow.gravity = Gravity.CENTER_VERTICAL
        cockpitRecordActionRow.setBackgroundColor(
            ContextCompat.getColor(activity, R.color.ksi_video_bg),
        )
        cockpitRecordActionRow.setPadding(dp(8), 0, dp(4), 0)
        cockpitRecordActionRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
        )
        workspaceHudNumbers = TextView(activity).apply {
            id = R.id.workspaceHudNumbers
            textSize = 13f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(videoFg)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
        }
        btnTaskPrepare.apply {
            rolePrimary(this)
            text = "기록 준비"
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(144), dp(48))
        }
        btnTaskDetails.apply {
            roleGhost(this, dark = true)
            background = ghostOnDark()
            setTextColor(videoFg)
            text = "비행 조작"
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(48))
        }
        btnEndFlight.apply {
            roleTonal(this)
            text = "KSI 기록 종료"
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(144), dp(48))
        }
        cockpitRecordActionRow.addView(workspaceHudNumbers)
        cockpitRecordActionRow.addView(btnTaskPrepare)
        cockpitRecordActionRow.addView(btnTaskDetails)
        cockpitRecordActionRow.addView(btnEndFlight)
        page.addView(cockpitRecordActionRow)
        buildFlightDeck()
    }

    /** Flight remains a flight surface even before a camera input arrives. */
    private fun buildFlightDeck() {
        val inputState = req(R.id.tvVideoPlaceholder) as TextView
        val videoFg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        // Fixed overlay height: a 1.3x system font must not turn the HUD
        // into a thick bottom band. FlightHudView caps its own text/graphic
        // enlargement and the center empty state is lifted above it.
        val hudHeight = dp(170)
        detach(inputState)
        flightOfflineLayer.removeAllViews()
        flightOfflineLayer.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_grid))
        val stage = req(R.id.videoStage) as FrameLayout
        flightStage = stage

        // A bench-only still reference scene makes HUD legibility reviewable
        // over bright sky and busy ground. It is not a decoded frame or a
        // camera report, and the production flavor returns null.
        val backdrop = WorkspacePreviewFixture.videoBackdrop(activity)
        if (backdrop != null) {
            val preview = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageDrawable(backdrop)
                contentDescription = "정지 참고 장면. 실제 영상이나 카메라 입력이 아닙니다."
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            // Insert directly above the offline layer but below the existing
            // AI/camera/tools siblings so the reference scene never covers HUD.
            stage.addView(preview, 2, FrameLayout.LayoutParams(-1, -1))
            flightScenePreview = preview
            scenePreviewVisible = true
            val badge = text("참고 장면 · 실제 영상 아님 · ESO/E. Graf · CC BY 4.0", 10f).apply {
                setTextColor(videoFg)
                background = videoChrome()
                setPadding(dp(8), dp(4), dp(8), dp(4))
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
            }
            stage.addView(badge, 3, FrameLayout.LayoutParams(-2, dp(28), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                // Keep the bench-only scene label above the cardless HUD.
                bottomMargin = hudHeight + dp(10)
            })
            flightSceneBadge = badge
        }

        // When no picture exists, the center is a quiet empty state rather
        // than a floating status/action bar competing with the corner HUD.
        flightEmptyState = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)
            // The cardless HUD owns the bottom edge; keep the no-video
            // message/action in the free center area above it.
            translationY = -dp(60).toFloat()
        }
        flightEmptyState.addView(
            ImageView(activity).apply {
                setImageDrawable(icon(WorkspaceIcons.Kind.VIDEO, R.color.ksi_video_fg, 30))
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { gravity = Gravity.CENTER; bottomMargin = dp(8) },
        )
        flightEmptyState.addView(inputState.apply {
            textSize = 13f
            setTypeface(android.graphics.Typeface.create("sans-serif", 0))
            setIncludeFontPadding(false)
            maxLines = 2
            minWidth = 0
            maxWidth = dp(360)
            minHeight = 0
            gravity = Gravity.CENTER
            setTextColor(videoFg)
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER })
        detach(videoRecover)
        videoRecover.text = "연결 확인"
        videoRecover.contentDescription = "영상 연결 상태 확인"
        videoRecover.textSize = 12f
        videoRecover.setTypeface(android.graphics.Typeface.create("sans-serif", 0))
        videoRecover.setIncludeFontPadding(false)
        videoRecover.gravity = Gravity.CENTER
        videoRecover.setPadding(dp(16), 0, dp(16), 0)
        videoRecover.background = outlinedFlightControl()
        videoRecover.compoundDrawablePadding = dp(6)
        videoRecover.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, icon(WorkspaceIcons.Kind.NEXT, R.color.ksi_video_fg, 14), null)
        flightEmptyState.addView(videoRecover, LinearLayout.LayoutParams(dp(132), dp(48)).apply {
            gravity = Gravity.CENTER; topMargin = dp(8)
        })
        stage.addView(flightEmptyState)
        flightVideoNotice = flightEmptyState
        flightEmptyState.visibility = if (scenePreviewVisible) View.GONE else View.VISIBLE
        if (scenePreviewVisible) flightOfflineLayer.visibility = View.GONE

        // Cardless bottom HUD: display-only overlay fed by bindFlightTelemetry.
        // Insert below the tools/map siblings so those controls stay tappable
        // and keep their own contrast. No filled bar or card is created.
        flightHud = FlightHudView(activity).apply {
            id = R.id.workspaceFlightHud
            style = hudStyle
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val hudParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            hudHeight,
            Gravity.BOTTOM,
        )
        val toolsIndex = stage.indexOfChild(req(R.id.workspaceFlightTools))
        if (toolsIndex >= 0) {
            stage.addView(flightHud, toolsIndex, hudParams)
        } else {
            stage.addView(flightHud, hudParams)
        }

        park(req(R.id.tvMapPlaceholder))
        flightMiniMap = StoredZonePreviewView(activity, compact = true, minimal = true)
        flightMiniMap.id = R.id.workspaceFlightMap
        flightMiniMap.onExpand = {
            if (req(R.id.rightStatusPanel).visibility == View.VISIBLE || req(R.id.opsRightColumn).visibility == View.VISIBLE)
                activity.onBackPressedDispatcher.onBackPressed()
            setFlightMapExpanded(!flightMapExpanded)
        }
        stage.addView(flightMiniMap, FrameLayout.LayoutParams(dp(132), dp(92), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(12); bottomMargin = dp(12)
        })
        (flightPill.layoutParams as FrameLayout.LayoutParams).apply { width = -2 }
        pillBox.background = null
        pillBox.setPadding(0, 0, 0, 0)
        pillBox.gravity = Gravity.CENTER_VERTICAL
        (req(R.id.supervisionCompactRow) as LinearLayout).apply {
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(-2, -2)
        }
        listOf(req(R.id.tvCompactAi), req(R.id.tvCompactPreserve)).forEach { view ->
            (view as TextView).apply {
                maxWidth = dp(220)
                setIncludeFontPadding(false)
                setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
                layoutParams = LinearLayout.LayoutParams(-2, -2)
            }
        }
        flightSceneActions.getChildAt(0).layoutParams = LinearLayout.LayoutParams(-2, -2)
        val prepare = req(R.id.btnTaskPrepare) as Button
        detach(prepare)
        roleVideo(prepare)
        park(prepare)
        val footer = req(R.id.cockpitRecordActionRow) as LinearLayout
        park(workspaceHudNumbers)
        listOf(req(R.id.btnTaskDetails), req(R.id.btnEndFlight)).forEach { park(it) }
        footer.layoutParams = LinearLayout.LayoutParams(-1, -2)
        footer.minimumHeight = dp(28)
        footer.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        footer.setPadding(dp(12), 0, dp(12), 0)
        // One left-anchored instrument cluster, not five equal full-width
        // blocks. The spare right side stays empty instead of pulling each
        // label and value apart.
        flightTelemetryLabels.forEach { label ->
            val value = text("$label  미수신", 12f).apply {
                setTextColor(ContextCompat.getColor(activity, R.color.ksi_video_fg))
                maxLines = 1
                minimumHeight = dp(28)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
                setIncludeFontPadding(false)
                fontFeatureSettings = "tnum"
                alignTextBaseline(this, 18)
            }
            flightTelemetry.add(value)
            footer.addView(value, LinearLayout.LayoutParams(-2, -2).apply {
                marginEnd = dp(6)
            })
        }
        footer.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        // The values now live in the cardless HUD overlay. Keep the footer
        // attached for binding/legacy ids but out of the layout so the video
        // recovers the old 28dp band and the HUD sits over the bottom edge.
        footer.visibility = View.GONE
        flightPhotoFrame = View(activity).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), ContextCompat.getColor(activity, R.color.ksi_video_fg))
            }
        }
        stage.addView(flightPhotoFrame, FrameLayout.LayoutParams(-1, -1).apply { setMargins(dp(8), dp(8), dp(8), dp(8)) })
        val cameraScenario = activity.intent?.getStringExtra("cameraScenario")
        bindCameraPresentation(WorkspacePreviewFixture.cameraPresentation(cameraScenario))
        if (cameraScenario != null && WorkspacePreviewFixture.cameraTimelineEnabled(cameraScenario)) {
            cameraSimulationScenario = cameraScenario
            cameraSimulationStart = SystemClock.elapsedRealtime()
            stage.postDelayed(cameraTicker, 0)
        }
        buildFlightStatusPanel(req(R.id.rightStatusPanel) as LinearLayout)
        stage.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) layoutFlightOverlays(right - left)
        }
    }

    /** Edge anchors stay stable as details reserve a smaller canvas. */
    private fun layoutFlightOverlays(stageWidth: Int) {
        if (stageWidth <= 0) return
        val narrow = stageWidth < dp(660)
        if (narrow && flightMapExpanded) setFlightMapExpanded(false)
    }

    private fun layoutFlightAvailability() {
        val hasPicture = liveVideoVisible || scenePreviewVisible
        if (liveVideoVisible) {
            flightScenePreview?.visibility = View.GONE
            flightSceneBadge?.visibility = View.GONE
        } else if (scenePreviewVisible) {
            flightScenePreview?.visibility = View.VISIBLE
            flightSceneBadge?.visibility = View.VISIBLE
        }
        flightOfflineLayer.visibility = if (hasPicture) View.GONE else View.VISIBLE
        flightVideoNotice.visibility = if (hasPicture) View.GONE else View.VISIBLE
        if (!hasPicture) {
            // The centered empty state always keeps its factual status line,
            // even when the input model has frames but no attached surface.
            (req(R.id.tvVideoPlaceholder) as TextView).visibility = View.VISIBLE
        }
        flightPill.visibility = View.VISIBLE
        flightCamera.visibility = View.VISIBLE
        val actions = flightSceneActions
        listOf(req(R.id.btnStart), workspaceObservationRecovery).forEach { control ->
            (control as? TextView)?.maxWidth = dp(196)
            control.layoutParams = LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(4) }
        }
        req(R.id.cockpitRecordActionRow).setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
        (req(R.id.btnTaskDetails) as Button).let {
            roleGhost(it, true)
            it.background = outlinedFlightControl()
            it.text = "조작"
        }
    }

    private fun showFlightTools(anchor: View) {
        android.widget.PopupMenu(activity, anchor).apply {
            menu.add(0, R.id.workspaceFlightStateInfo, 0, "비행 상세")
            listOf(R.id.btnReqPhoto to "사진 촬영", R.id.btnReqRec to "기체 녹화",
                R.id.btnReqGimbal to "짐벌", R.id.btnTaskDetails to "비행 조작").forEachIndexed { index, (id, label) ->
                menu.add(0, id, index + 1, label)
            }
            if (flightHasRecord) menu.add(0, R.id.btnEndFlight, 5, "KSI 기록 종료")
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.workspaceFlightStateInfo) {
                    openFlightDetail(FlightDetailTab.STATE)
                } else {
                    req(item.itemId).performClick()
                }
                true
            }
            show()
        }
    }

    fun bindCameraPresentation(state: FlightCameraPresentation) {
        flightCameraState = state
        val hasReport = state.lensName != null || state.zoomRatio != null ||
            state.recording != null || state.photo != FlightCameraPresentation.Photo.UNKNOWN
        // Unknown is a detail fact, not a persistent card. The flight edge
        // shows only a small camera entry; full state is in the sheet.
        flightLens.text = if (hasReport) state.lensLabel() else "카메라 ?"
        flightLens.alpha = if (hasReport) 1f else 0.72f
        val simulated = if (state.simulated) {
            ". 시뮬레이션 표시 입력. 실제 카메라 보고나 저장 결과가 아닙니다."
        } else ""
        flightLens.contentDescription = "카메라 렌즈·줌 상태. 상세는 더보기에서 확인$simulated"
        val feedback = state.feedback()
        flightCameraFeedback.text = feedback
        flightCameraFeedback.visibility = if (feedback == null) View.GONE else View.VISIBLE
        flightCameraFeedback.contentDescription = listOfNotNull(
            feedback,
            if (state.simulated) "시뮬레이션 표시 입력. 실제 카메라 보고나 저장 결과가 아닙니다." else null,
        ).joinToString(". ")
        val tone = when {
            state.stale -> R.color.ksi_attention_video
            state.photo == FlightCameraPresentation.Photo.FAILED -> R.color.ksi_problem_video
            state.recording == true -> R.color.ksi_problem_video
            else -> R.color.ksi_video_fg
        }
        flightCameraFeedback.setTextColor(ContextCompat.getColor(activity, tone))
        flightPhotoFrame.visibility = if (state.photo == FlightCameraPresentation.Photo.SHOOTING && !state.stale) View.VISIBLE else View.GONE
        if (::cameraLensValue.isInitialized) {
            cameraLensValue.text = state.lensLabel()
            cameraRecValue.text = CameraDetailCopy.rec(
                state.recording, state.recordingSeconds, state.stale,
            )
            cameraPhotoValue.text = CameraDetailCopy.photo(state.photo)
            cameraStateNote.text = if (state.simulated) {
                "[SIMULATED] 표시 입력 · 실제 카메라 보고/저장 아님\n영상 위 표시는 프레임 매핑 확인 후"
            } else {
                "실제 카메라 보고가 없으면 미수신으로 남습니다.\n영상 위 표시는 프레임 매핑 확인 후"
            }
        }
    }

    private fun setFlightMapExpanded(expanded: Boolean) {
        flightMapExpanded = expanded
        flightMiniMap.expanded = expanded
        flightMiniMap.layoutParams = (flightMiniMap.layoutParams as FrameLayout.LayoutParams).apply {
            width = dp(if (expanded) 288 else 132)
            height = dp(if (expanded) 192 else 92)
        }
        flightMiniMap.invalidate()
    }

    private fun buildFlightStatusPanel(panel: LinearLayout) {
        val original = (0 until panel.childCount).map { panel.getChildAt(it) }
        panel.removeAllViews()
        val videoFg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        val videoMuted = androidx.core.graphics.ColorUtils.setAlphaComponent(videoFg, 190)
        fun darkLine() = View(activity).apply {
            setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(videoFg, 40))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
        fun valueRow(label: String, value: TextView): LinearLayout {
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(40)
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(text(label, 11f).apply { setTextColor(videoMuted) }, LinearLayout.LayoutParams(dp(84), -2))
            value.apply {
                gravity = Gravity.END
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(videoFg)
            }
            row.addView(value, LinearLayout.LayoutParams(0, -2, 1f))
            return row
        }
        panel.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_video_bg))
        panel.setPadding(dp(12), dp(12), dp(12), dp(12))
        val title = req(R.id.tvOpsPanelTitle) as TextView
        val legacy = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        original.filter { it !== title }.forEach { legacy.addView(it) }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        title.apply {
            text = "비행 상세"
            textSize = 16f
            maxLines = 1
            setTextColor(videoFg)
        }
        heading.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        heading.addView(Button(activity).apply {
            roleGhost(this, true)
            background = ghostOnDark()
            setTextColor(videoFg)
            contentDescription = "비행 상세 닫기"
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(WorkspaceIcons.Kind.CLOSE, R.color.ksi_video_fg, 20), null, null, null)
            setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        panel.addView(heading)

        // One bounded detail surface: status facts and camera/zoom state.
        // No white AlertDialog, no 232dp zoom popup, no zoom card on the video.
        val tabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        fun tab(label: String, selected: Boolean): TextView = text(label, 12f, bold = selected).apply {
            gravity = Gravity.CENTER
            setTextColor(if (selected) videoFg else videoMuted)
            background = GradientDrawable().apply {
                setColor(if (selected) androidx.core.graphics.ColorUtils.setAlphaComponent(videoFg, 22)
                    else Color.TRANSPARENT)
                cornerRadius = dp(4).toFloat()
            }
        }
        flightDetailStateTab = tab("상태", true)
        flightDetailCameraTab = tab("카메라", false)
        flightDetailDetectionsTab = tab("감지", false)
        tabs.addView(flightDetailStateTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(flightDetailCameraTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        tabs.addView(flightDetailDetectionsTab, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(tabs)
        flightDetailStateTab.setOnClickListener { selectFlightDetailTab(FlightDetailTab.STATE) }
        flightDetailCameraTab.setOnClickListener { selectFlightDetailTab(FlightDetailTab.CAMERA) }
        flightDetailDetectionsTab.setOnClickListener { selectFlightDetailTab(FlightDetailTab.DETECTIONS) }

        val content = FrameLayout(activity)
        panel.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val facts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        flightPriorityDetail = text("", 13f).apply {
            setTextColor(videoFg)
            visibility = View.GONE
            setPadding(0, dp(8), 0, dp(8))
        }
        facts.addView(flightPriorityDetail, LinearLayout.LayoutParams(-1, -2))
        facts.addView(action("연결 상태와 복구 확인") { req(R.id.btnConnectionDetail).performClick() }.apply {
            roleGhost(this, true)
            background = outlinedFlightControl()
            setTextColor(videoFg)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        listOf("AI 관측", "카메라 조작권", "자동 조절", "KSI 기록", "원본 보존").forEach { name ->
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(31)
                setPadding(0, dp(2), 0, dp(2))
            }
            row.addView(text(name, 11f).apply { setTextColor(videoMuted) }, LinearLayout.LayoutParams(dp(84), -2))
            val value = text("미확인", 12f).apply {
                gravity = Gravity.END
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(videoFg)
            }
            flightStatusValues.add(value)
            row.addView(value, LinearLayout.LayoutParams(0, -2, 1f))
            facts.addView(row)
            facts.addView(darkLine())
        }
        flightStatusEndNote = text("KSI 종료는 기체 비행·녹화를 중지하지 않습니다.", 10f).apply {
            setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(videoFg, 160))
        }
        flightStatusEnd = action("KSI 기록 종료") { req(R.id.btnEndFlight).performClick() }.apply {
            roleGhost(this, true)
            background = outlinedFlightControl()
            setTextColor(videoFg)
        }
        facts.addView(flightStatusEndNote)
        facts.addView(flightStatusEnd, LinearLayout.LayoutParams(-1, dp(48)))
        facts.addView(action("연결·관측 근거 상세") {
            val detail = req(R.id.cockpitDetailsScroll)
            val home = detail.parent as ViewGroup
            val index = home.indexOfChild(detail)
            val params = detail.layoutParams
            home.removeView(detail)
            val dialog = androidx.appcompat.app.AlertDialog.Builder(activity)
                .setTitle("연결·관측 근거")
                .setView(detail)
                .setPositiveButton("닫기", null)
                .setOnDismissListener {
                    (detail.parent as? ViewGroup)?.removeView(detail)
                    home.addView(detail, index.coerceIn(0, home.childCount), params)
                }.create()
            dialog.setOnShowListener {
                detail.layoutParams = detail.layoutParams.apply { height = dp(248) }
                dialog.window?.setLayout(dp(360), -2)
            }
            dialog.show()
        }.apply {
            roleGhost(this, true)
            background = outlinedFlightControl()
            setTextColor(videoFg)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        facts.layoutParams = FrameLayout.LayoutParams(dp(480), FrameLayout.LayoutParams.WRAP_CONTENT)
        flightDetailStateContent = ScrollView(activity).apply {
            addView(facts); isFillViewport = false
        }
        content.addView(flightDetailStateContent, FrameLayout.LayoutParams(-1, -1))

        val cameraFacts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        cameraLensValue = text("렌즈·줌 미수신", 12f)
        cameraRecValue = text("미수신", 12f)
        cameraPhotoValue = text("미수신", 12f)
        cameraStateNote = text("", 10f).apply {
            setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(videoFg, 170))
            maxLines = 3
        }
        cameraFacts.addView(valueRow("렌즈·줌", cameraLensValue))
        cameraFacts.addView(darkLine())
        cameraFacts.addView(valueRow("REC", cameraRecValue))
        cameraFacts.addView(darkLine())
        cameraFacts.addView(valueRow("사진", cameraPhotoValue))
        cameraFacts.addView(darkLine())
        cameraFacts.addView(cameraStateNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        cameraFacts.layoutParams = FrameLayout.LayoutParams(dp(480), FrameLayout.LayoutParams.WRAP_CONTENT)
        flightDetailCameraContent = ScrollView(activity).apply {
            addView(cameraFacts); isFillViewport = false
            visibility = View.GONE
        }
        content.addView(flightDetailCameraContent, FrameLayout.LayoutParams(-1, -1))

        val detectionFacts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        flightDetectionHeadline = text("기록 없음 · 활성 KSI 세션 없음", 12f, bold = true).apply {
            setTextColor(videoFg)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        flightDetectionDetail = text("관측 세션 시작 후 Edge 관측이 표시됩니다", 10f).apply {
            setTextColor(videoMuted)
            maxLines = 3
        }
        flightDetectionOverlay = text("", 9f).apply {
            setTextColor(androidx.core.graphics.ColorUtils.setAlphaComponent(videoFg, 170))
            maxLines = 5
        }
        flightDetectionList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        detectionFacts.addView(flightDetectionHeadline)
        detectionFacts.addView(flightDetectionDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        detectionFacts.addView(flightDetectionOverlay, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        detectionFacts.addView(darkLine(), LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(6) })
        detectionFacts.addView(flightDetectionList)
        detectionFacts.layoutParams = FrameLayout.LayoutParams(dp(480), FrameLayout.LayoutParams.WRAP_CONTENT)
        flightDetailDetectionsContent = ScrollView(activity).apply {
            addView(detectionFacts); isFillViewport = false
            visibility = View.GONE
        }
        content.addView(flightDetailDetectionsContent, FrameLayout.LayoutParams(-1, -1))

        panel.addView(legacy)
        selectFlightDetailTab(FlightDetailTab.STATE)
        flightCameraState?.let { bindCameraPresentation(it) }
    }

    /** Open the one bounded detail surface on the requested segment. */
    fun selectFlightDetailTab(tab: FlightDetailTab) {
        flightDetailTab = tab
        if (!::flightDetailStateContent.isInitialized ||
            !::flightDetailDetectionsContent.isInitialized
        ) return
        val fg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        val muted = androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 170)
        val panels = listOf(
            FlightDetailTab.STATE to flightDetailStateContent,
            FlightDetailTab.CAMERA to flightDetailCameraContent,
            FlightDetailTab.DETECTIONS to flightDetailDetectionsContent,
        )
        panels.forEach { (target, view) ->
            view.visibility = if (target == tab) View.VISIBLE else View.GONE
        }
        val tabs = listOf(
            FlightDetailTab.STATE to flightDetailStateTab,
            FlightDetailTab.CAMERA to flightDetailCameraTab,
            FlightDetailTab.DETECTIONS to flightDetailDetectionsTab,
        )
        tabs.forEach { (target, button) ->
            val selected = target == tab
            button.setTextColor(if (selected) fg else muted)
            button.setTypeface(
                android.graphics.Typeface.create(
                    "sans-serif",
                    if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
                ),
                0,
            )
            button.background = GradientDrawable().apply {
                setColor(
                    if (selected) androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 22)
                    else Color.TRANSPARENT,
                )
                cornerRadius = dp(4).toFloat()
            }
        }
    }

    /**
     * Open the deliberate full-screen detail mode at [tab]. This is not a
     * side dock: no video width is reserved, and the flight HUD is not shown
     * underneath. The app header/nav remain for orientation/back.
     */
    private fun openFlightDetail(tab: FlightDetailTab) {
        val open = req(R.id.rightStatusPanel).visibility == View.VISIBLE
        selectFlightDetailTab(tab)
        if (!open) (idMap[R.id.btnTaskStatus] as? View)?.performClick()
    }

    /**
     * Read-only AI detection feed from the same [AiObservationPanel] data the
     * right ops area uses. No on-video boxes: the overlay note explicitly
     * stays "held" until frame correspondence is verified.
     */
    fun bindDetectionFeed(panel: AiObservationPanel.Panel) {
        if (!::flightDetectionHeadline.isInitialized) return
        val fg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        val muted = androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 180)
        flightDetectionHeadline.text = panel.headline
        flightDetectionHeadline.setTextColor(
            ContextCompat.getColor(
                activity,
                if (panel.unavailable) R.color.ksi_attention_video else R.color.ksi_video_fg,
            ),
        )
        flightDetectionDetail.text = panel.detail
        flightDetectionOverlay.text = listOf(panel.overlayNote, panel.mapNote)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        flightDetectionList.removeAllViews()
        if (panel.candidates.isEmpty()) {
            flightDetectionList.addView(
                text(
                    panel.emptyListNote,
                    10f,
                ).apply {
                    setTextColor(muted)
                    setPadding(0, dp(4), 0, dp(4))
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        } else {
            panel.candidates.forEach { row ->
                flightDetectionList.addView(
                    text(
                        "[${row.kind}] ${row.eventId.take(8)}\n${row.quality} · ${row.evidenceSummary}",
                        10f,
                    ).apply {
                        setTextColor(fg)
                        maxLines = 3
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        setPadding(0, dp(4), 0, dp(4))
                        contentDescription =
                            "후보 ${row.eventId}. ${row.kind}. ${row.quality}. ${row.evidenceSummary}."
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                flightDetectionList.addView(
                    View(activity).apply {
                        setBackgroundColor(androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 40))
                    },
                    LinearLayout.LayoutParams(-1, dp(1)),
                )
            }
            flightDetectionList.addView(
                text("대응은 ‘관측 후보’에서 · 영상 박스는 프레임 대응 검증 전까지 보류", 9f).apply {
                    setTextColor(muted)
                    setPadding(0, dp(6), 0, 0)
                },
                LinearLayout.LayoutParams(-1, -2),
            )
        }
        flightDetailDetectionsContent.contentDescription = buildString {
            append("감지. ").append(panel.headline).append(". ").append(panel.detail).append(". ")
            panel.candidates.forEach {
                append("[${it.kind}] ${it.eventId.take(8)} ${it.quality} ${it.evidenceSummary}. ")
            }
            append(panel.overlayNote)
        }
    }

    // ---------------------------------------------------------------- mission

    private fun createMissionMapView(): StoredZonePreviewView =
        StoredZonePreviewView(activity)

    private fun buildMission() {
        val page = pageMission as LinearLayout
        val direct = mutableListOf<View>()
        for (i in 0 until page.childCount) direct.add(page.getChildAt(i))
        page.removeAllViews()
        page.orientation = LinearLayout.HORIZONTAL
        page.setPadding(0, 0, 0, 0)
        page.background = null
        page.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_bg))
        direct.forEach { park(it) }

        // Park mission fact/support views (parent keeps binding them; the
        // settled steps view below renders only bindMission inputs).
        listOf(
            R.id.tvMissionReadiness, R.id.tvMission, R.id.tvMissionSite,
            R.id.tvMissionBundle, R.id.tvMissionRevision, R.id.tvMissionZone,
            R.id.tvMissionZoneRevisions, R.id.tvMissionMapNote,
            R.id.tvMissionFlightId, R.id.tvMissionFlightState, R.id.tvMissionStarted,
            R.id.tvStatus, R.id.tvMissionSyncResult, R.id.tvLocalBundleResult,
            R.id.tvStartFlightNote, R.id.missionContentScroll, R.id.missionContentColumn,
            R.id.missionBodyRow, R.id.generalFlightRow, R.id.rowSyncButtons,
            R.id.rowPrepare, R.id.missionDockExplainScroll,
        ).forEach { parkById(it) }

        // Master/detail: sites stay compact; plan comparison gets the full remaining width.
        browserColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_bg))
            layoutParams = LinearLayout.LayoutParams(dp(232), LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val browserHeadingRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            )
        }
        val browserHeading = TextView(activity).apply {
            text = "현장"
            setTextAppearance(R.style.TextAppearance_Ksi_Section)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        // Plan management lives behind one on-demand menu entry (same three
        // parked buttons, same actions, mirrored enabled state).
        val btnSyncBundle = req(R.id.btnSyncBundle) as Button
        val btnComposeLocal = req(R.id.btnComposeLocal) as Button
        val btnGeneralFlight = req(R.id.btnGeneralFlight) as Button
        detach(btnSyncBundle)
        detach(btnComposeLocal)
        detach(btnGeneralFlight)
        park(btnSyncBundle)
        park(btnComposeLocal)
        park(btnGeneralFlight)
        val workspaceMissionManage = Button(activity).apply {
            id = R.id.workspaceMissionManage
            text = ""
            contentDescription = "계획 관리"
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = dp(48)
            isSingleLine = true
            compoundDrawablePadding = dp(4)
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { showPlanManageMenu() }
        }
        roleGhost(workspaceMissionManage, dark = false)
        workspaceMissionManage.setCompoundDrawablesRelativeWithIntrinsicBounds(
            icon(WorkspaceIcons.Kind.MORE, R.color.ksi_fg, 20), null, null, null,
        )
        browserHeadingRow.addView(browserHeading)
        browserHeadingRow.addView(workspaceMissionManage)

        val browserScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val browserScrollInner = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val siteCardList = req(R.id.siteCardList)
        val siteCardDetail = req(R.id.siteCardDetail)
        detach(siteCardList)
        detach(siteCardDetail)
        browserScrollInner.addView(
            siteCardList,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        browserScroll.addView(browserScrollInner)

        val btnAddPendingSite = req(R.id.btnAddPendingSite) as Button
        val btnSyncSites = req(R.id.btnSyncSites) as Button
        detach(btnAddPendingSite)
        detach(btnSyncSites)
        btnAddPendingSite.apply {
            roleTonal(this)
            text = "현장 추가"
            textSize = 13f
            setPadding(dp(8), 0, dp(8), 0)
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
        }
        btnSyncSites.apply {
            roleGhost(this, dark = false)
            text = "목록 갱신"
            textSize = 13f
            setPadding(dp(8), 0, dp(8), 0)
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
        }
        val browserBottom = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            ).apply { topMargin = dp(8) }
        }
        browserBottom.addView(
            btnAddPendingSite,
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        browserBottom.addView(
            btnSyncSites,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) },
        )

        browserColumn.addView(browserHeadingRow)
        browserColumn.addView(browserScroll)
        browserColumn.addView(browserBottom)

        // ---- main map workspace (weight 1, vertical) ----------------------
        val mainColumn = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        missionMainColumn = mainColumn
        mainColumn.setPadding(dp(24), dp(20), dp(24), dp(12))
        missionPreviewContext = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        missionPreviewSite = text("", 16f, bold = true)
        missionPreviewContext.addView(missionPreviewSite)
        missionPreviewContext.addView(text("저장 좌표 개요 · 실제 지도·비행 경로·현재 위치 아님", 12f, quiet = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        mainColumn.addView(missionPreviewContext, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        missionSelectionBody = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        missionPlanScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            addView(siteCardDetail, FrameLayout.LayoutParams(-1, -2))
        }
        missionSelectionBody.addView(missionPlanScroll, LinearLayout.LayoutParams(0, -1, 1f))
        missionEmpty = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(16), dp(8), dp(16))
        }
        missionEmpty.addView(ImageView(activity).apply {
            setImageDrawable(icon(WorkspaceIcons.Kind.RECORDS, R.color.ksi_muted, 32))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(32), dp(32)).apply { bottomMargin = dp(16) })
        missionEmpty.addView(text("관측 계획 준비", 20f, bold = true))
        missionEmpty.addView(text("왼쪽에서 현장을 선택하고\n저장된 관측 계획과 작업 구역을 확인하세요.", 14f, quiet = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        missionEmpty.addView(text("계획 설정 → 장치 응답 확인 → KSI 기록", 12f, quiet = true),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        mainColumn.addView(missionEmpty, LinearLayout.LayoutParams(-1, 0, 1f))
        missionMapView = createMissionMapView().apply {
            id = R.id.workspaceMissionMap
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        missionSelectionBody.addView(missionMapView, LinearLayout.LayoutParams(dp(160), -1).apply { marginStart = dp(12) })
        workspaceMissionSummary = TextView(activity).apply {
            id = R.id.workspaceMissionSummary
            textSize = 12f
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            maxLines = 3
            ellipsize = null
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            setPadding(0, dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        missionBottomAction = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            minimumHeight = dp(72)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface_subtle))
        }
        btnMissionOpenPreparation = Button(activity).apply {
            id = R.id.workspaceMissionOpenPreparation
            text = "관측 준비"
            isAllCaps = false
            textSize = 13f
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(dp(128), dp(48))
            setOnClickListener {
                browserColumn.visibility = View.GONE
                prepPanel.visibility = View.VISIBLE
                missionBottomAction.visibility = View.GONE
                layoutMissionSelection()
            }
        }
        rolePrimary(btnMissionOpenPreparation)
        missionBottomAction.addView(workspaceMissionSummary, LinearLayout.LayoutParams(0, -2, 1f))
        missionBottomAction.addView(btnMissionOpenPreparation)
        mainColumn.addView(missionSelectionBody, LinearLayout.LayoutParams(-1, 0, 1f))
        mainColumn.addView(missionBottomAction)

        // ---- prep panel (original missionCommandDock, 292, flat surface) --
        val dock = req(R.id.missionCommandDock) as LinearLayout
        detach(dock)
        val dockChildren = mutableListOf<View>()
        for (i in 0 until dock.childCount) dockChildren.add(dock.getChildAt(i))
        dockChildren.forEach { dock.removeView(it) }
        val tvMissionNextAction = req(R.id.tvMissionNextAction)
        val btnPrepare = req(R.id.btnPrepare) as Button
        val btnApply = req(R.id.btnApply) as Button
        btnStartFlight = req(R.id.btnStartFlight)
        val btnReconcile = req(R.id.btnReconcile) as Button
        val btnStop = idMap[R.id.btnStop]
        detach(tvMissionNextAction)
        detach(btnPrepare)
        detach(btnApply)
        detach(btnStartFlight)
        detach(btnReconcile)
        dockChildren.forEach { child ->
            if (child !== tvMissionNextAction && child !== btnPrepare &&
                child !== btnApply && child !== btnStartFlight
            ) {
                park(child)
            }
        }
        // Edge stop stays available in the flight detail tray.
        if (btnStop != null) {
            detach(btnStop)
            val opsDeck = idMap[R.id.opsDeck] as? ViewGroup
            if (opsDeck != null) {
                opsDeck.addView(
                    btnStop,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
                    ),
                )
            } else {
                park(btnStop)
            }
        }
        dock.orientation = LinearLayout.VERTICAL
        dock.background = null
        dock.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface))
        dock.setPadding(dp(16), dp(12), dp(16), dp(12))
        dock.layoutParams = LinearLayout.LayoutParams(dp(320), LinearLayout.LayoutParams.MATCH_PARENT)
        dock.visibility = View.GONE

        val prepHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            )
        }
        val prepTitle = TextView(activity).apply {
            text = "관측 준비"
            textSize = 16f
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        btnMissionBack = Button(activity).apply {
            id = R.id.workspaceMissionBack
            text = ""
            contentDescription = "계획 목록으로 돌아가기"
            isAllCaps = false
            minimumHeight = dp(48)
            minWidth = dp(48)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener {
                prepPanel.visibility = View.GONE
                browserColumn.visibility = View.VISIBLE
                missionBottomAction.visibility = View.VISIBLE
                layoutMissionSelection()
            }
        }
        roleGhost(btnMissionBack, dark = false)
        btnMissionBack.setCompoundDrawablesRelativeWithIntrinsicBounds(
            icon(WorkspaceIcons.Kind.BACK, R.color.ksi_fg, 20), null, null, null,
        )
        prepHeader.addView(btnMissionBack)
        prepHeader.addView(prepTitle)

        val stepsScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(4) }
        }
        workspaceMissionSteps = TextView(activity).apply {
            id = R.id.workspaceMissionSteps
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        park(workspaceMissionSteps)
        val phases = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        missionRequestNote = text("", 13f).apply {
            id = R.id.workspaceMissionRequestNote
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface_subtle))
        }
        missionConnectionNote = text("장치 연결 끊김 · 마지막 응답 기준", 12f, quiet = true).apply { visibility = View.GONE }
        phases.addView(missionConnectionNote,
            LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        fun phase(number: String, label: String): TextView {
            val row = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(48) }
            row.addView(text(number, 12f, quiet = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(24), -2))
            row.addView(text(label, 13f).apply { gravity = Gravity.CENTER_VERTICAL }, LinearLayout.LayoutParams(0, -2, 1f))
            val value = text("확인 전", 12f, quiet = true).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                maxLines = 2
            }
            row.addView(value, LinearLayout.LayoutParams(dp(116), -2))
            phases.addView(row)
            phases.addView(line())
            return value
        }
        prepareValue = phase("1", "장치 준비")
        applyValue = phase("2", "설정 적용")
        recordValue = phase("3", "KSI 기록")
        // Keep the three phase facts ahead of potentially long recovery copy.
        // The note remains in this scroll, while actions and return stay fixed.
        phases.addView(missionRequestNote, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        stepsScroll.addView(
            phases,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        // Sticky rejection sits BEFORE actions, never inside the scroll.
        workspaceMissionRejection = TextView(activity).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_problem))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_problem_bg))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        listOf(btnPrepare, btnApply, btnStartFlight as Button, btnReconcile).forEach {
            it.minimumHeight = dp(48)
            it.minWidth = 0
        }
        val scopeExplain = TextView(activity).apply {
            text = "기체 비행 별도 · 카메라 별도"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        btnMissionDetails = Button(activity).apply {
            id = R.id.workspaceMissionDetails
            text = "상세"
            contentDescription = "준비·적용 상세"
            isAllCaps = false
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            ).apply { topMargin = dp(4) }
            setOnClickListener { showMissionDetailsDialog() }
        }
        roleGhost(btnMissionDetails, dark = false)

        dock.addView(prepHeader)
        dock.addView(stepsScroll)
        dock.addView(tvMissionNextAction)
        dock.addView(workspaceMissionRejection)
        missionPendingAction = Button(activity).apply {
            id = R.id.workspaceMissionPending
            textSize = 13f
            minimumHeight = dp(48)
            isEnabled = false
            visibility = View.GONE
        }
        roleTonal(missionPendingAction)
        dock.addView(missionPendingAction, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        dock.addView(
            btnPrepare,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            },
        )
        dock.addView(
            btnApply,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            },
        )
        dock.addView(
            btnStartFlight,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            },
        )
        dock.addView(
            btnReconcile,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(8)
            },
        )
        park(scopeExplain)
        // A full-height, persistent detail entry; never a clipped scroll tail
        // between the current request and the return to Flight.
        prepHeader.addView(btnMissionDetails, LinearLayout.LayoutParams(dp(64), dp(48)))
        missionBoundPlan = Button(activity).apply {
            id = R.id.workspaceMissionBoundPlan
            text = "준비 중인 계획 보기"
            minimumHeight = dp(48)
            textSize = 13f
            visibility = View.GONE
        }
        roleTonal(missionBoundPlan)
        dock.addView(missionBoundPlan, LinearLayout.LayoutParams(-1, dp(48)))
        missionOpenFlight = action("비행 화면으로") { req(R.id.btnNavObserve).performClick() }
        dock.addView(missionOpenFlight, LinearLayout.LayoutParams(-1, dp(48)))
        prepPanel = dock

        page.addView(browserColumn)
        page.addView(mainColumn)
        page.addView(prepPanel)
    }

    /** On-demand plan management: the SAME three parked buttons, mirrored. */
    private fun showPlanManageMenu() {
        val anchor = (idMap[R.id.workspaceMissionManage] as? Button)
            ?: activity.findViewById<Button>(R.id.workspaceMissionManage)
            ?: return
        val popup = PopupMenu(activity, anchor)
        val targets = listOf(R.id.btnSyncBundle, R.id.btnComposeLocal, R.id.btnGeneralFlight)
        targets.forEachIndexed { index, id ->
            val src = (idMap[id] as? Button) ?: activity.findViewById<Button>(id)
            if (src != null) {
                popup.menu.add(0, id, index, src.text).isEnabled = src.isEnabled
            }
        }
        popup.setOnMenuItemClickListener { item ->
            val src = (idMap[item.itemId] as? View)
                ?: activity.findViewById<View>(item.itemId)
            // Explicit selection only: the original button performs its own
            // existing action. No direct business calls from this menu.
            if (src?.isEnabled == true) src.performClick()
            true
        }
        popup.show()
    }

    private fun showMissionDetailsDialog() {
        // Safety facts on 3 axes, read from the live original views.
        fun textOf(id: Int): String {
            val v = idMap[id] ?: activity.findViewById<View>(id) ?: return "-"
            return (v as? TextView)?.text?.toString() ?: "-"
        }
        val technicalBody = "적용 상태\n${textOf(R.id.tvMissionApplyState)}\n\n" +
            "요청 결과\n${missionRejectionDetail ?: "거부 보고 없음"}\n\n" +
            "Edge 적용 응답과 실제 실행은 별도입니다. 기체 비행·카메라 녹화는 시작하지 않습니다.\n\n" +
            "구역 개정\n${textOf(R.id.tvMissionZoneRevisions)}\n\n" +
            "로컬 구성\n${textOf(R.id.tvLocalBundleResult)}"
        val operatorBody = "장치 준비 · ${prepareValue.text}\n설정 적용 · ${applyValue.text}\nKSI 기록 · ${recordValue.text}\n\n" +
            if (missionRejectionDetail != null) {
                "관측 장치가 설정 적용을 거부했습니다.\n장치 응답을 확인한 뒤 필요하면 다시 준비를 요청하세요.\n\n" +
                    "기체 비행·카메라 녹화는 시작하지 않습니다."
            } else {
                "${missionRequestNote.text}\n\n기체 비행·카메라 녹화·실제 관측 실행·원본 보존은 별도 확인합니다."
            }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("준비·적용 상세")
            .setMessage(operatorBody)
            .setNeutralButton("기술 근거 보기", null)
            .setPositiveButton("닫기", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            // Same scrollable dialog, not another modal. Unknown/raw reason
            // codes and full provenance stay available without dominating the first view.
            dialog.setMessage("$operatorBody\n\n기술 근거\n$technicalBody")
            it.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- records

    private fun buildRecords() {
        val page = pageReview as LinearLayout
        val direct = mutableListOf<View>()
        for (i in 0 until page.childCount) direct.add(page.getChildAt(i))
        page.removeAllViews()
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, 0, 0, 0)
        page.background = null
        page.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_bg))
        direct.forEach { child ->
            // reviewContentRow and tvCustodyLine are rebuilt below; the frame
            // wrapper and criteria note are parked (still attached).
            park(child)
        }

        val reviewContentRow = req(R.id.reviewContentRow) as LinearLayout
        val reviewOverlay = req(R.id.reviewOverlay)
        val tvCustodyLine = req(R.id.tvCustodyLine) as TextView
        detach(reviewContentRow)
        detach(reviewOverlay)
        detach(tvCustodyLine)
        // Full custody metadata stays reachable (parked, attached); the
        // settled 3-cell strip below carries the exact counts.
        park(tvCustodyLine)

        // Reorder: viewer left, sessions right.
        val rowChildren = mutableListOf<View>()
        for (i in 0 until reviewContentRow.childCount) rowChildren.add(reviewContentRow.getChildAt(i))
        rowChildren.forEach { reviewContentRow.removeView(it) }
        val reviewSessionsColumn = req(R.id.reviewSessionsColumn) as LinearLayout
        val reviewViewerColumn = req(R.id.reviewViewerColumn) as LinearLayout
        detach(reviewSessionsColumn)
        detach(reviewViewerColumn)
        rowChildren.forEach { child ->
            if (child !== reviewSessionsColumn && child !== reviewViewerColumn) park(child)
        }
        reviewContentRow.orientation = LinearLayout.HORIZONTAL
        reviewContentRow.background = null
        reviewContentRow.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )

        // ---- viewer column ------------------------------------------------
        val viewerChildren = mutableListOf<View>()
        for (i in 0 until reviewViewerColumn.childCount) {
            viewerChildren.add(reviewViewerColumn.getChildAt(i))
        }
        viewerChildren.forEach { reviewViewerColumn.removeView(it) }
        val evidenceViewerStage = req(R.id.evidenceViewerStage) as FrameLayout
        recordStage = evidenceViewerStage
        val filmstripScrollLocal = req(R.id.filmstripScroll)
        val tvEvidenceMeta = idMap[R.id.tvEvidenceMeta]
        detach(evidenceViewerStage)
        detach(filmstripScrollLocal)
        evidenceViewerStage.background = null
        evidenceViewerStage.setBackgroundColor(
            ContextCompat.getColor(activity, R.color.ksi_video_bg),
        )
        viewerChildren.forEach { child ->
            if (child !== evidenceViewerStage && child !== filmstripScrollLocal &&
                child !== tvEvidenceMeta
            ) {
                park(child)
            }
        }
        if (tvEvidenceMeta != null) park(tvEvidenceMeta)
        reviewViewerColumn.orientation = LinearLayout.VERTICAL
        reviewViewerColumn.background = null
        reviewViewerColumn.setPadding(dp(8), dp(8), dp(8), dp(8))
        reviewViewerColumn.layoutParams =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)

        val viewerHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            )
        }
        val spHistoryFlight = req(R.id.spHistoryFlight)
        detach(spHistoryFlight)
        park(spHistoryFlight)
        workspaceEvidenceExpand = Button(activity).apply {
            id = R.id.workspaceEvidenceExpand
            text = "시점 접기"
            setPadding(dp(8), 0, dp(8), 0)
            isAllCaps = false
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = dp(96)
            isSingleLine = true
            compoundDrawablePadding = dp(4)
            layoutParams = LinearLayout.LayoutParams(dp(96), dp(48)).apply {
                marginStart = dp(4)
            }
            setOnClickListener { toggleEvidenceExpand() }
        }
        roleGhost(workspaceEvidenceExpand, dark = false)
        workspaceEvidenceExpand.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        viewerHeader.addView(action("", click = { showRecordIndex() }).apply {
            contentDescription = "기록 목록으로 돌아가기"
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon(WorkspaceIcons.Kind.BACK, R.color.ksi_fg), null, null, null)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        recordDetailTitle = text("기록", 14f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        viewerHeader.addView(recordDetailTitle, LinearLayout.LayoutParams(0, dp(48), 1f))
        viewerHeader.addView(workspaceEvidenceExpand)
        recordHeader = viewerHeader
        workspaceReviewContext = TextView(activity).apply {
            id = R.id.workspaceReviewContext
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            setPadding(dp(6), dp(3), dp(6), dp(3))
            visibility = View.GONE
        }

        // evidenceViewerStage keeps the existing ZoomEvidenceImageView
        // (whatever class the parent inflated) plus the empty state.
        evidenceViewerStage.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        )
        val empty = req(R.id.evidenceViewerEmpty) as LinearLayout
        val emptyText = req(R.id.tvEvidenceEmpty) as TextView
        val recovery = req(R.id.btnReviewEmptyRecovery) as Button
        detach(empty); detach(emptyText); detach(recovery)
        empty.removeAllViews()
        empty.orientation = LinearLayout.HORIZONTAL
        empty.gravity = Gravity.CENTER_VERTICAL
        empty.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface_subtle))
        empty.setPadding(dp(8), dp(4), dp(8), dp(4))
        emptyText.apply {
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.NORMAL)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        empty.addView(emptyText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        roleGhost(recovery, dark = false)
        recovery.text = "수신·보관"
        recovery.textSize = 12f
        empty.addView(recovery, LinearLayout.LayoutParams(dp(112), dp(48)))
        recordFallback = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface))
        }
        recordFallback.setPadding(dp(16), dp(16), dp(16), dp(8))
        recordFallback.addView(text("관측 요약", 20f, bold = true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        recordFallback.addView(empty, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        recordContext = StoredContextPanel(activity, offerSites = false)
        park(recordContext)
        recordSummary = TextView(activity).apply {
            textSize = 15f
            fontFeatureSettings = "tnum"
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            setLineSpacing(dp(8).toFloat(), 1f)
            setPadding(0, dp(16), 0, dp(12))
        }
        recordFallback.addView(recordSummary, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT))
        recordFallback.addView(action("당시 계획·구역 보기") {
            detach(recordContext)
            val scroll = ScrollView(activity).apply { addView(recordContext, FrameLayout.LayoutParams(-1, dp(260))) }
            val dialog = AlertDialog.Builder(activity).setTitle("기록 당시 계획").setView(scroll).setPositiveButton("닫기", null).create()
            dialog.setOnDismissListener { park(recordContext) }
            dialog.show()
        }, LinearLayout.LayoutParams(dp(170), dp(48)))
        recordStage.addView(recordFallback, FrameLayout.LayoutParams(-1, -1))

        // Zoom tools + filmstrip share ONE horizontal 48 row (default and
        // expanded alike): 324 tools group + weight-1 filmstrip.
        val toolRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(324), dp(48))
        }
        workspaceZoomOut = Button(activity).apply {
            id = R.id.workspaceZoomOut
            text = ""
            contentDescription = "축소"
            isAllCaps = false
            minimumHeight = dp(48)
            minWidth = dp(48)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        roleGhost(workspaceZoomOut, dark = false)
        workspaceZoomOut.setCompoundDrawablesRelativeWithIntrinsicBounds(
            icon(WorkspaceIcons.Kind.MINUS, R.color.ksi_fg, 20), null, null, null,
        )
        workspaceZoomValue = TextView(activity).apply {
            id = R.id.workspaceZoomValue
            text = "-"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(dp(56), dp(48))
        }
        workspaceZoomIn = Button(activity).apply {
            id = R.id.workspaceZoomIn
            text = ""
            contentDescription = "확대"
            isAllCaps = false
            minimumHeight = dp(48)
            minWidth = dp(48)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        roleGhost(workspaceZoomIn, dark = false)
        workspaceZoomIn.setCompoundDrawablesRelativeWithIntrinsicBounds(
            icon(WorkspaceIcons.Kind.PLUS, R.color.ksi_fg, 20), null, null, null,
        )
        workspaceZoomReset = Button(activity).apply {
            id = R.id.workspaceZoomReset
            text = "맞춤"
            contentDescription = "맞춤 보기"
            isAllCaps = false
            textSize = 12f
            minimumHeight = dp(48)
            minWidth = dp(64)
            isSingleLine = true
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(2)
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(48)).apply {
                marginStart = dp(4)
            }
        }
        roleGhost(workspaceZoomReset, dark = false)
        workspaceZoomReset.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        val btnReviewDetail = req(R.id.btnReviewDetail) as Button
        detach(btnReviewDetail)
        btnReviewDetail.apply {
            roleTonal(this)
            visibility = View.VISIBLE
            text = "상세"
            contentDescription = "판정·보관 상세"
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
        }
        toolRow.addView(workspaceZoomOut)
        toolRow.addView(workspaceZoomValue)
        toolRow.addView(workspaceZoomIn)
        toolRow.addView(workspaceZoomReset)
        toolRow.addView(
            btnReviewDetail,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) },
        )
        recordZoomTools = toolRow

        filmstripScrollLocal.layoutParams = LinearLayout.LayoutParams(
            0, dp(48), 1f,
        ).apply { marginStart = dp(4) }
        filmstripScroll = filmstripScrollLocal

        reviewViewerColumn.addView(viewerHeader)
        reviewViewerColumn.addView(workspaceReviewContext, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        reviewViewerColumn.addView(evidenceViewerStage)
        val sourceCaption = req(R.id.tvEvidenceMeta) as TextView
        detach(sourceCaption)
        sourceCaption.apply {
            visibility = View.VISIBLE
            textSize = 11f
            maxLines = 2
            ellipsize = null
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            setPadding(dp(4), dp(3), dp(4), dp(3))
        }
        reviewViewerColumn.addView(sourceCaption, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        recordTools = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            ).apply { topMargin = dp(4) }
        }
        recordTools.addView(toolRow)
        recordTools.addView(filmstripScrollLocal)
        reviewViewerColumn.addView(recordTools)

        // ---- sessions column (208, one 1dp divider) ------------------------
        val sessionChildren = mutableListOf<View>()
        for (i in 0 until reviewSessionsColumn.childCount) {
            sessionChildren.add(reviewSessionsColumn.getChildAt(i))
        }
        sessionChildren.forEach { reviewSessionsColumn.removeView(it) }
        val eventRowList = req(R.id.eventRowList)
        val btnEventPrev = req(R.id.btnEventPrev) as Button
        val btnEventNext = req(R.id.btnEventNext) as Button
        val btnOpenEvidence = req(R.id.btnOpenEvidence) as Button
        detach(eventRowList)
        detach(btnEventPrev)
        detach(btnEventNext)
        detach(btnOpenEvidence)
        sessionChildren.forEach { child ->
            if (child !== eventRowList && child !== btnEventPrev &&
                child !== btnEventNext && child !== btnOpenEvidence
            ) {
                park(child)
            }
        }
        reviewSessionsColumn.orientation = LinearLayout.VERTICAL
        reviewSessionsColumn.background = null
        reviewSessionsColumn.setPadding(dp(8), dp(8), dp(8), dp(8))
        reviewSessionsColumn.layoutParams =
            LinearLayout.LayoutParams(dp(208), LinearLayout.LayoutParams.MATCH_PARENT)
        val sessionsHeading = TextView(activity).apply {
            text = "관측 시점"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val eventScroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(4) }
        }
        eventScroll.addView(
            eventRowList,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val eventNavRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            ).apply { topMargin = dp(4) }
        }
        btnEventPrev.apply {
            text = "이전"
            contentDescription = "이전 관측"
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(2)
        }
        roleGhost(btnEventPrev, dark = false)
        btnEventPrev.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        btnEventNext.apply {
            text = "다음"
            contentDescription = "다음 관측"
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(2)
        }
        roleGhost(btnEventNext, dark = false)
        btnEventNext.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, null)
        recordEventNav = eventNavRow
        eventNavRow.addView(
            btnEventPrev,
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        eventNavRow.addView(
            btnEventNext,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(4) },
        )
        btnOpenEvidence.apply {
            roleTonal(this)
            text = "수신·보관"
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            )
        }
        (btnOpenEvidence.layoutParams as LinearLayout.LayoutParams).topMargin = dp(4)
        reviewSessionsColumn.addView(sessionsHeading)
        reviewSessionsColumn.addView(eventScroll)
        reviewSessionsColumn.addView(eventNavRow)
        btnOpenEvidence.visibility = View.VISIBLE
        reviewSessionsColumn.addView(btnOpenEvidence)
        this.reviewSessionsColumn = reviewSessionsColumn

        val divider = View(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_border))
            layoutParams = LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        reviewContentRow.addView(reviewViewerColumn)
        reviewContentRow.addView(divider)
        reviewContentRow.addView(reviewSessionsColumn)

        val frame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ).apply { topMargin = dp(4) }
        }
        frame.addView(
            reviewContentRow,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        detach(reviewOverlay)
        reviewOverlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        frame.addView(reviewOverlay)

        // Custody strip: 3 equal cells, 52 high, wrap content, outside overlay.
        val custodyStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = null
            minimumHeight = dp(52)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        fun custodyCell(id: Int): TextView = TextView(activity).apply {
            this.id = id
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            minimumHeight = dp(52)
            gravity = Gravity.CENTER_VERTICAL
        }
        custodyStrip.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface_subtle))
        workspaceCustodyEdge = custodyCell(R.id.workspaceCustodyEdge)
        workspaceCustodyRc = custodyCell(R.id.workspaceCustodyRc)
        workspaceCustodyCore = custodyCell(R.id.workspaceCustodyCore)
        listOf(workspaceCustodyEdge, workspaceCustodyRc, workspaceCustodyCore).forEach {
            it.isFocusable = true
            it.background = settingsRowBackground(false)
            it.setOnClickListener { req(R.id.btnOpenEvidence).performClick() }
            custodyStrip.addView(
                it,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }

        recordDetail = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        recordDetail.addView(frame)
        recordDetail.addView(custodyStrip)
        page.addView(recordDetail, LinearLayout.LayoutParams(-1, -1))
        buildRecordIndex(page)
    }

    private fun buildRecordIndex(page: LinearLayout) {
        recordIndex = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
        }
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        recordIndexTitle = text("기록", 23f, bold = true)
        title.addView(recordIndexTitle)
        title.addView(text("이 RC에 저장된 KSI 기록", 12f, quiet = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        heading.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(action("수신·전송 확인") { req(R.id.btnOpenEvidence).performClick() }, LinearLayout.LayoutParams(dp(142), dp(48)))
        recordIndex.addView(heading, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(20) })
        val labels = LinearLayout(activity).apply { setPadding(dp(12), 0, dp(28), dp(8)) }
        labels.addView(text("현장·기록 시각", 11f, quiet = true), LinearLayout.LayoutParams(0, -2, 1f))
        labels.addView(text("관측 수신", 11f, quiet = true), LinearLayout.LayoutParams(dp(96), -2))
        labels.addView(text("자료 수신", 11f, quiet = true), LinearLayout.LayoutParams(dp(96), -2))
        labels.addView(text("기록 상태", 11f, quiet = true), LinearLayout.LayoutParams(dp(72), -2))
        recordIndex.addView(labels)
        recordIndex.addView(line())
        recordIndexRows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(recordIndexRows); isFillViewport = false }
        recordIndex.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(recordIndex, LinearLayout.LayoutParams(-1, -1))
    }

    fun showRecordIndex() {
        if (evidenceExpanded) toggleEvidenceExpand()
        recordDetailOpen = false
        recordDetail.visibility = View.GONE
        recordIndex.visibility = View.VISIBLE
    }

    /** Called only by an explicit record navigation or close action. */
    fun openRecordDetail() {
        if (evidenceExpanded) toggleEvidenceExpand()
        recordDetailOpen = true
        recordIndex.visibility = View.GONE
        recordDetail.visibility = View.VISIBLE
    }

    fun bindRecordIndex(rows: List<FlightHistoryModel.FlightRow>, selectedId: String?, storageAvailable: Boolean, onSelect: (String) -> Unit) {
        val selected = rows.firstOrNull { it.flightId == selectedId }
        selectedRecordRow = selected
        recordDetailTitle.text = if (selectedId == null) "선택한 기록 없음" else "선택한 기록을 찾을 수 없음"
        selected?.let { row ->
            recordDetailTitle.text = "${row.siteName ?: row.siteId.take(8)}\n${recordTime(row.startedAtUtc)} · ${recordState(row.state)}"
        }
        val key = "$storageAvailable:${rows.hashCode()}"
        if (recordRowsKey == key) return
        recordRowsKey = key
        recordIndexTitle.text = if (storageAvailable) "기록  ${rows.size}" else "기록"
        recordIndexRows.removeAllViews()
        if (!storageAvailable || rows.isEmpty()) {
            recordIndexRows.addView(text(if (storageAvailable) "아직 저장된 기록이 없습니다.\n임무에서 계획을 준비하고 KSI 기록을 시작하세요."
                else "저장소를 읽을 수 없습니다.\n설정의 진단에서 저장소 상태를 확인하세요.", 14f, quiet = true),
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
            recordIndexRows.addView(action(if (storageAvailable) "임무 준비" else "진단 보기", true) {
                req(if (storageAvailable) R.id.btnNavMission else R.id.btnNavSettings).performClick()
            }, LinearLayout.LayoutParams(dp(160), dp(48)).apply { topMargin = dp(16) })
        }
        rows.forEach { row ->
            val entry = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(82)
                setPadding(dp(12), dp(12), dp(8), dp(12))
                background = settingsRowBackground(false)
                isClickable = true
                isFocusable = true
                contentDescription = "${row.siteName ?: row.siteId.take(8)}, ${recordTime(row.startedAtUtc)}, 관측 수신 ${row.observationCount}건, ${recordState(row.state)}. 기록 열기"
                setOnClickListener {
                    onSelect(row.flightId)
                }
            }
            val context = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            context.addView(text(row.siteName ?: row.siteId.take(8), 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            context.addView(text(recordTime(row.startedAtUtc), 12f, quiet = true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            entry.addView(context, LinearLayout.LayoutParams(0, -2, 1f))
            entry.addView(text("${row.observationCount}건", 13f), LinearLayout.LayoutParams(dp(96), -2))
            entry.addView(text("${row.artifactCompleteCount} / ${row.artifactCount}", 13f), LinearLayout.LayoutParams(dp(96), -2))
            entry.addView(text(recordState(row.state), 12f, quiet = true), LinearLayout.LayoutParams(dp(72), -2))
            entry.addView(text("", 12f).apply { setCompoundDrawablesRelativeWithIntrinsicBounds(icon(WorkspaceIcons.Kind.NEXT, R.color.ksi_muted, 16), null, null, null) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            recordIndexRows.addView(entry)
            recordIndexRows.addView(line())
        }
    }

    private fun recordTime(raw: String?): String = runCatching {
        java.time.Instant.parse(raw).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("MM.dd · HH:mm"))
    }.getOrDefault("시각 미확인")

    private fun recordState(state: String) = when (state) {
        "ENDED" -> "종료"
        "STARTED", "APPLIED", "RUNNING", "SESSION_PENDING", "STOPPED" -> "기록 중"
        else -> "미확인"
    }

    private fun toggleEvidenceExpand() {
        evidenceExpanded = !evidenceExpanded
        reviewSessionsColumn.visibility = if (evidenceExpanded) View.GONE else View.VISIBLE
        // Only the list folds away. Judgement, event navigation and frame
        // selection remain alongside the image, not behind an overlay.
        workspaceReviewContext.visibility = if (evidenceExpanded) View.VISIBLE else View.GONE
        // Tools stay in the single horizontal row in both states; the
        // filmstrip is never hidden and never re-stacked vertically.
        (req(R.id.btnReviewDetail) as Button).text = "상세"
        listOf(R.id.btnEventPrev, R.id.btnEventNext).forEach { id ->
            val button = req(id)
            detach(button)
            if (evidenceExpanded) recordHeader.addView(button, recordHeader.childCount - 1,
                LinearLayout.LayoutParams(dp(64), dp(48)))
            else recordEventNav.addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        workspaceEvidenceExpand.text = if (evidenceExpanded) "시점 보기" else "시점 접기"
    }

    // --------------------------------------------------------------- evidence

    private fun buildEvidence() {
        val page = pageEvidence as LinearLayout
        page.background = null
        page.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_bg))
        page.setPadding(dp(12), dp(12), dp(12), dp(12))
        val btnEvidenceBack = idMap[R.id.btnEvidenceBack] as? Button
        btnEvidenceBack?.apply {
            roleGhost(this, dark = false)
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = (layoutParams as? LinearLayout.LayoutParams)?.also {
                it.height = dp(48)
            } ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48),
            )
        }
        // Not the primary screen: existing bindings (summary/source,
        // upload/server-state) stay as inflated.
    }

    // --------------------------------------------------------------- settings

    private fun settingsRowBackground(selected: Boolean): Drawable =
        if (selected) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 0f
                setColor(ContextCompat.getColor(activity, R.color.ksi_surface_subtle))
            }
        } else {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 0f
                setColor(Color.TRANSPARENT)
            }
        }

    private fun paintSettingsTab(tab: Button, selected: Boolean) {
        tab.background = settingsRowBackground(selected)
        tab.setTextColor(
            ContextCompat.getColor(
                activity,
                if (selected) R.color.ksi_fg else R.color.ksi_muted,
            ),
        )
        tab.setTypeface(tab.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    }

    private fun buildSettings() {
        val page = pageSettings
        page.background = null
        page.setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_bg))
        page.setPadding(0, 0, 0, 0)
        // Capture the original settings content, then reset the container.
        val originalChildren = mutableListOf<View>()
        if (page is ViewGroup) {
            for (i in 0 until page.childCount) originalChildren.add(page.getChildAt(i))
            page.removeAllViews()
        }
        for (child in originalChildren) {
            // Keep the whole original inner column parked (attached) if it is
            // a plain container; ID'd inputs are pulled out below first.
            park(child)
        }

        // Pull the actual ID'd inputs out of the parked tree via the id map.
        fun take(id: Int): View = req(id).also { detach(it) }

        val etCoreUrl = take(R.id.etCoreUrl)
        val etCoreToken = take(R.id.etCoreToken)
        val btnSaveCore = take(R.id.btnSaveCore) as Button
        val btnTestEdgeLink = take(R.id.btnTestEdgeLink) as Button
        val tvTestEdgeLink = take(R.id.tvTestEdgeLink)
        val tvUploadGate = take(R.id.tvUploadGate) as TextView
        val etOperatorName = take(R.id.etOperatorName)
        val etOperatorOrg = take(R.id.etOperatorOrg)
        val btnSaveOperator = take(R.id.btnSaveOperator) as Button
        val tvOperator = take(R.id.tvOperator) as TextView
        val tvDeviceInfo = take(R.id.tvDeviceInfo)
        val tvAppVersion = take(R.id.tvAppVersion)
        val tvClockNote = take(R.id.tvClockNote)
        val btnWideVideo = take(R.id.btnWideVideo) as Button
        val btnToggleDeveloper = take(R.id.btnToggleDeveloper) as Button
        val developerTools = take(R.id.developerTools)
        val tvConnectionHeadline = take(R.id.tvConnectionHeadline) as TextView
        // The headline stays bound (attached in the park box) for the
        // connection dialog and headline refresh; the panel shows the three
        // source rows instead of the stacked paragraph.
        park(tvConnectionHeadline)

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            background = null
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val category = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            layoutParams = LinearLayout.LayoutParams(dp(144), LinearLayout.LayoutParams.MATCH_PARENT)
            setPadding(dp(8), dp(8), dp(4), dp(8))
        }
        fun tab(id: Int, label: String, kind: WorkspaceIcons.Kind): Button = Button(activity).apply {
            this.id = id
            text = label
            isAllCaps = false
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            minWidth = 0
            isSingleLine = true
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesRelativeWithIntrinsicBounds(
                icon(kind, R.color.ksi_muted, 20), null, null, null,
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            )
        }
        val tabConnections = tab(R.id.workspaceSettingsConnections, "연결·전송", WorkspaceIcons.Kind.MENU)
        val tabOperator = tab(R.id.workspaceSettingsOperator, "조종사", WorkspaceIcons.Kind.RECORDS)
        val tabDisplay = tab(R.id.workspaceSettingsDisplay, "화면", WorkspaceIcons.Kind.SETTINGS)
        val tabDiagnostics = tab(R.id.workspaceSettingsDiagnostics, "진단", WorkspaceIcons.Kind.STATUS)
        settingsTabs = listOf(tabConnections, tabOperator, tabDisplay, tabDiagnostics)
        settingsTabs.forEach { category.addView(it) }

        settingsContent = FrameLayout(activity).apply {
            id = R.id.workspaceSettingsContent
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        row.addView(category)
        row.addView(settingsContent)
        when (page) {
            is ScrollView -> {
                page.isFillViewport = true
                page.addView(row)
            }
            is ViewGroup -> page.addView(row)
        }

        fun panel(): LinearLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = null
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_surface))
        }

        fun panelScroll(): Pair<ScrollView, LinearLayout> {
            val scroll = ScrollView(activity).apply {
                isVerticalScrollBarEnabled = true
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.MATCH_PARENT,
                )
            }
            val inner = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                background = null
            }
            scroll.addView(
                inner,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            return scroll to inner
        }

        fun hairline(): View = View(activity).apply {
            setBackgroundColor(ContextCompat.getColor(activity, R.color.ksi_border))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            ).apply {
                topMargin = 0
                bottomMargin = 0
            }
        }

        /** One source row: fixed label + live value, min 64, hairline below. */
        fun sourceRow(label: String, value: View, recovery: String? = null, onRecover: (() -> Unit)? = null): LinearLayout {
            val holder = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                minimumHeight = dp(56)
                gravity = Gravity.CENTER_VERTICAL
            }
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            }
            val tag = TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            if (value is TextView) {
                value.textSize = 13f
                value.setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            }
            value.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            (value as? View)?.minimumHeight = dp(48)
            line.addView(tag)
            line.addView(value)
            if (recovery != null && onRecover != null) {
                line.addView(action(recovery, click = onRecover).apply { textSize = 11f; isSingleLine = true }, LinearLayout.LayoutParams(dp(96), dp(48)))
            }
            holder.addView(line)
            holder.addView(hairline())
            return holder
        }

        // ---- Connections panel (default) ----------------------------------
        val connectionsPanel = panel().apply { visibility = View.VISIBLE }
        val (connScroll, connInner) = panelScroll()
        connInner.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        val connTitle = TextView(activity).apply {
            text = "연결·전송"
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
        }
        aircraftRow = TextView(activity).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        edgeConnectionRow = TextView(activity).apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        videoConnectionRow = TextView(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        tvUploadGate.apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
        }
        connInner.addView(connTitle, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        connInner.addView(text("장비", 12f, quiet = true))
        connInner.addView(sourceRow("기체", aircraftRow, "상태 보기") { req(R.id.btnConnectionDetail).performClick() })
        connInner.addView(sourceRow("MOP 링크", edgeConnectionRow, "연결 확인") { req(R.id.btnConnectionDetail).performClick() })
        val videoRow = sourceRow("영상 입력", videoConnectionRow, "연결 확인") {
            if (req(R.id.tvVideoPlaceholder).isClickable) req(R.id.tvVideoPlaceholder).performClick()
        }
        settingsVideoRecover = ((videoRow.getChildAt(0) as LinearLayout).getChildAt(2) as Button)
        connInner.addView(videoRow)
        // Core config row: inline END editor entry, not a full-width card.
        val coreRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val coreLabel = TextView(activity).apply {
            text = "서버 전송"
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        coreEditorToggle = Button(activity).apply {
            id = R.id.workspaceCoreEditorToggle
            text = "서버 설정"
            isAllCaps = false
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = dp(112)
            layoutParams = LinearLayout.LayoutParams(dp(112), dp(48))
        }
        roleGhost(coreEditorToggle, dark = false)
        coreRow.addView(coreLabel)
        coreRow.addView(coreEditorToggle)
        connInner.addView(coreRow)
        connInner.addView(sourceRow("전송 설정", tvUploadGate))
        val coreHint = TextView(activity).apply {
            text = "전송·보관 결과는 해당 기록에서 확인하세요."
            textSize = 11f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        connInner.addView(coreHint)
        coreEditorForm = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(420), -2)
        }.also { form ->
            val f = form as LinearLayout
            fun label(text: String, forId: Int): TextView = TextView(activity).apply {
                this.text = text
                textSize = 12f
                labelFor = forId
                setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            }
            val tokenNote = TextView(activity).apply {
                text = "토큰은 이 기기의 보안 저장소에만 둡니다."
                textSize = 11f
                setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            }
            f.addView(label("서버 주소", R.id.etCoreUrl))
            f.addView(etCoreUrl)
            f.addView(label("접근 토큰", R.id.etCoreToken))
            f.addView(etCoreToken)
            rolePrimary(btnSaveCore)
            btnSaveCore.apply {
                textSize = 12f
                isSingleLine = true
                minimumHeight = dp(48)
                minWidth = dp(144)
                layoutParams = LinearLayout.LayoutParams(dp(144), dp(48)).apply {
                    topMargin = dp(4)
                    gravity = Gravity.END
                }
            }
            f.addView(btnSaveCore)
            f.addView(tokenNote)
            (etCoreUrl.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                height = dp(48)
                width = LinearLayout.LayoutParams.MATCH_PARENT
            }
            (etCoreToken.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
                height = dp(48)
                width = LinearLayout.LayoutParams.MATCH_PARENT
            }
            etCoreUrl.minimumHeight = dp(48)
            etCoreToken.minimumHeight = dp(48)
        }
        connInner.addView(coreEditorForm)
        connectionsPanel.addView(connScroll.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        })
        // panelScroll() already attached its sole inner column.
        coreEditorToggle.setOnClickListener {
            coreEditorForm.visibility =
                if (coreEditorForm.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        // ---- Operator panel -------------------------------------------------
        val operatorPanel = panel()
        val (operatorScroll, operatorInner) = panelScroll()
        operatorScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        fun opLabel(text: String, forId: Int): TextView = TextView(activity).apply {
            this.text = text
            textSize = 12f
            labelFor = forId
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
        }
        operatorInner.addView(opLabel("조종사 이름", R.id.etOperatorName))
        operatorInner.addView(etOperatorName)
        operatorInner.addView(opLabel("소속", R.id.etOperatorOrg))
        operatorInner.addView(etOperatorOrg)
        operatorInner.layoutParams = FrameLayout.LayoutParams(
            dp(420),
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        etOperatorName.minimumHeight = dp(48)
        etOperatorOrg.minimumHeight = dp(48)
        rolePrimary(btnSaveOperator)
        btnSaveOperator.apply {
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = dp(144)
            layoutParams = LinearLayout.LayoutParams(dp(144), dp(48)).apply {
                topMargin = dp(4)
                gravity = Gravity.END
            }
        }
        operatorInner.addView(btnSaveOperator)
        tvOperator.apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
        }
        operatorInner.addView(tvOperator)
        operatorPanel.addView(operatorScroll)

        // ---- Display panel ---------------------------------------------------
        val displayPanel = panel()
        val (displayScroll, displayInner) = panelScroll()
        displayScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        // Display-only flight HUD choice (H1/H2/H3). The buttons report the
        // choice to MainActivity, which persists it in AppConfig; this view
        // never decides or stores a product preference on its own.
        val hudStyleLabel = TextView(activity).apply {
            text = "비행 HUD"
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_fg))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(6) }
        }
        fun hudStyleButton(id: Int, style: FlightHudStyle): Button = Button(activity).apply {
            this.id = id
            text = style.label
            textSize = 12f
            isAllCaps = false
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = 0
            layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setOnClickListener { onHudStyleSelected?.invoke(style) }
        }
        val hudBare = hudStyleButton(R.id.workspaceHudStyleBare, FlightHudStyle.BARE)
        val hudScale = hudStyleButton(R.id.workspaceHudStyleScale, FlightHudStyle.SCALE)
        val hudReticle = hudStyleButton(R.id.workspaceHudStyleReticle, FlightHudStyle.RETICLE)
        val hudStyleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48),
            )
        }
        hudStyleRow.addView(hudBare, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        hudStyleRow.addView(hudScale, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
        hudStyleRow.addView(hudReticle, LinearLayout.LayoutParams(0, dp(48), 1f))
        val hudStyleNote = TextView(activity).apply {
            text = "비행 화면 하단 계기 방식입니다. 선택 즉시 적용되며, 실제 텔레메트리 값만 표시합니다."
            textSize = 11f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(6) }
        }
        displayInner.addView(hudStyleLabel)
        displayInner.addView(hudStyleRow)
        displayInner.addView(hudStyleNote)
        hudStyleButtons = mapOf(
            FlightHudStyle.BARE to hudBare,
            FlightHudStyle.SCALE to hudScale,
            FlightHudStyle.RETICLE to hudReticle,
        )
        paintHudStyleButtons()

        roleTonal(btnWideVideo)
        btnWideVideo.apply {
            textSize = 12f
            isSingleLine = true
            minimumHeight = dp(48)
            minWidth = dp(112)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48),
            ).apply { topMargin = dp(24) }
        }
        val displayNote = TextView(activity).apply {
            text = "영상 크게 보기는 물리 입력(조종기 버튼)과 같은 화면 전환을 사용합니다."
            textSize = 12f
            setTextColor(ContextCompat.getColor(activity, R.color.ksi_muted))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
        }
        displayInner.addView(btnWideVideo)
        displayInner.addView(displayNote)
        displayInner.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        displayPanel.addView(displayScroll)

        // ---- Diagnostics panel ------------------------------------------------
        val diagnosticsPanel = panel()
        val (diagnosticsScroll, diagnosticsInner) = panelScroll()
        diagnosticsScroll.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
        roleGhost(btnTestEdgeLink, dark = false)
        btnTestEdgeLink.apply {
            minimumHeight = dp(48)
            minWidth = dp(112)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48),
            )
        }
        roleGhost(btnToggleDeveloper, dark = false)
        btnToggleDeveloper.apply {
            minimumHeight = dp(48)
            minWidth = dp(112)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(48),
            ).apply { topMargin = dp(8) }
        }
        tvDeviceInfo.minimumHeight = dp(48)
        tvAppVersion.minimumHeight = dp(48)
        tvClockNote.minimumHeight = dp(48)
        tvTestEdgeLink.minimumHeight = dp(48)
        diagnosticsInner.addView(tvDeviceInfo)
        diagnosticsInner.addView(tvAppVersion)
        diagnosticsInner.addView(tvClockNote)
        diagnosticsInner.addView(btnTestEdgeLink)
        diagnosticsInner.addView(tvTestEdgeLink)
        diagnosticsInner.addView(btnToggleDeveloper)
        diagnosticsInner.addView(developerTools)
        diagnosticsInner.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        diagnosticsPanel.addView(diagnosticsScroll)

        settingsPanels = listOf(connectionsPanel, operatorPanel, displayPanel, diagnosticsPanel)
        settingsPanels.forEach { settingsContent.addView(it) }
        val tabTargets = listOf(connectionsPanel, operatorPanel, displayPanel, diagnosticsPanel)
        settingsTabs.forEachIndexed { index, tabBtn ->
            tabBtn.setOnClickListener { selectSettingsTab(index, tabTargets) }
        }
        selectSettingsTab(0, tabTargets)
    }

    private fun selectSettingsTab(index: Int, targets: List<View>) {
        targets.forEachIndexed { i, panel ->
            panel.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        settingsTabs.forEachIndexed { i, tabBtn ->
            tabBtn.isSelected = i == index
            paintSettingsTab(tabBtn, i == index)
            tabBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(
                when (tabBtn.id) {
                    R.id.workspaceSettingsConnections ->
                        icon(WorkspaceIcons.Kind.MENU, if (i == index) R.color.ksi_fg else R.color.ksi_muted, 20)
                    R.id.workspaceSettingsOperator ->
                        icon(WorkspaceIcons.Kind.RECORDS, if (i == index) R.color.ksi_fg else R.color.ksi_muted, 20)
                    R.id.workspaceSettingsDisplay ->
                        icon(WorkspaceIcons.Kind.SETTINGS, if (i == index) R.color.ksi_fg else R.color.ksi_muted, 20)
                    else ->
                        icon(WorkspaceIcons.Kind.STATUS, if (i == index) R.color.ksi_fg else R.color.ksi_muted, 20)
                },
                null, null, null,
            )
        }
    }

    // ------------------------------------------------------------ destination

    fun onDestination(navId: Int) {
        navRail.visibility = View.GONE
        (appHeader as? View)?.visibility = View.VISIBLE
        val menu = idMap[R.id.btnNavMenu] as? Button ?: activity.findViewById(R.id.btnNavMenu)
        pageObserve.visibility = if (navId == R.id.btnNavObserve) View.VISIBLE else View.GONE
        pageMission.visibility = if (navId == R.id.btnNavMission) View.VISIBLE else View.GONE
        pageReview.visibility = if (navId == R.id.btnNavReview) View.VISIBLE else View.GONE
        pageSettings.visibility = if (navId == R.id.btnNavSettings) View.VISIBLE else View.GONE
        // pageEvidence is a direct destination owned by the parent's evidence
        // path; leave it unless this is one of the four rail destinations.
        if (navId == R.id.btnNavObserve || navId == R.id.btnNavMission ||
            navId == R.id.btnNavReview || navId == R.id.btnNavSettings
        ) {
            pageEvidence.visibility = View.GONE
        }
        applyMenuFor(navId)
        menu?.visibility = View.VISIBLE
        // Flight and evidence titles are owned by the parent (actual site /
        // evidence facts); the rail only owns its three static labels.
        val evidenceOpen = pageEvidence.visibility == View.VISIBLE
        if (!evidenceOpen) {
            val title = idMap[R.id.tvPageTitle] as? TextView ?: activity.findViewById(R.id.tvPageTitle)
            when (navId) {
                R.id.btnNavMission -> title?.text = "현장·계획"
                R.id.btnNavReview -> title?.text = "관측 기록"
                R.id.btnNavSettings -> title?.text = "설정·진단"
                else -> Unit
            }
        }
    }

    // ------------------------------------------------------------------ binds

    fun bindHeader(
        authority: String,
        mode: String,
        battery: String,
        position: String,
        aircraft: String,
        simulated: Boolean,
    ) {
        val facts = listOf(aircraft, battery, authority, mode, position).filter { it.isNotBlank() }
        workspaceHeaderConnection.text = listOf(aircraft, battery, authority).filter { it.isNotBlank() }.joinToString(" · ")
        workspaceHeaderConnection.contentDescription = facts.joinToString(". ")
        val ctx = listOf(authority, mode).filter { it.isNotBlank() }.joinToString(" · ")
        workspaceHeaderContext.text = ctx
        val sim = idMap[R.id.tvTopSim] ?: activity.findViewById(R.id.tvTopSim)
        sim?.visibility = if (simulated) View.VISIBLE else View.GONE
        if (::aircraftRow.isInitialized) {
            // The row label already says '기체': strip a duplicated prefix.
            val raw = aircraft.trim()
            val stripped = when {
                raw.isBlank() -> ""
                raw.startsWith("기체:") -> raw.removePrefix("기체:").trim()
                raw.startsWith("기체·") -> raw.removePrefix("기체·").trim()
                raw.startsWith("기체 ") -> raw.removePrefix("기체 ").trim()
                else -> raw
            }
            aircraftRow.text = stripped.ifBlank { "미수신" }
        }
    }

    /** Actual 관측 장치 link state text, passed through untouched. */
    fun bindConnections(edge: String) {
        if (::edgeConnectionRow.isInitialized) {
            edgeConnectionRow.text = edge
        }
    }

    fun bindVideoConnection(status: String, recoverable: Boolean) {
        if (!::videoConnectionRow.isInitialized) return
        videoConnectionRow.text = "$status · 수신은 화면 표시 확인이 아님"
        settingsVideoRecover.isEnabled = recoverable
        settingsVideoRecover.contentDescription = if (recoverable) "영상 연결 확인" else "현재 영상 연결 확인 동작 없음"
    }

    fun bindPriorityDetail(item: OperatorStatus.Item?) {
        if (!::flightPriorityDetail.isInitialized) return
        flightPriorityDetail.visibility = if (item == null) View.GONE else View.VISIBLE
        flightPriorityDetail.text = item?.let {
            listOf(it.label, it.detail.takeIf(String::isNotBlank), it.action?.let { action -> "다음 조치: $action" })
                .filterNotNull().joinToString("\n")
        }.orEmpty()
    }

    fun bindOfflineSites(cards: List<MissionSiteCards.SiteCard>, selectable: Boolean, onChoose: (String) -> Unit) {
        // Site selection belongs to Mission; Flight never becomes a catalogue.
    }

    fun bindVideoAvailability(showing: Boolean, recoverable: Boolean) {
        liveVideoVisible = showing
        videoRecover.visibility = if (recoverable) View.VISIBLE else View.GONE
        videoRecover.text = "연결 확인"
        layoutFlightAvailability()
    }

    fun bindRecordContext(bundle: ObjectNode?, summary: String) {
        recordContext.bindPlan(bundle)
        recordSummary.text = summary.replace(" · ", "\n")
        updateEvidenceLayout()
    }

    fun bindEvidenceAvailability(showing: Boolean) {
        evidenceVisible = showing
        updateEvidenceLayout()
    }

    private fun updateEvidenceLayout() {
        recordFallback.visibility = if (evidenceVisible) View.GONE else View.VISIBLE
        req(R.id.ivEvidence).visibility = if (evidenceVisible) View.VISIBLE else View.GONE
        recordContext.visibility = View.VISIBLE
        recordTools.visibility = if (evidenceVisible) View.VISIBLE else View.GONE
        workspaceEvidenceExpand.visibility = if (evidenceVisible) View.VISIBLE else View.GONE
        req(R.id.tvEvidenceMeta).visibility = if (evidenceVisible) View.VISIBLE else View.GONE
        val key = "$evidenceVisible:${recordContext.hasGeometry}"
        if (key == evidenceLayoutKey) return
        evidenceLayoutKey = key
        recordStage.setBackgroundColor(ContextCompat.getColor(activity,
            if (evidenceVisible) R.color.ksi_video_bg else R.color.ksi_surface))
        recordStage.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
    }

    /** Short edge label; the full source phrase stays in the detail sheet. */
    private fun compactAiLabel(ai: String): String = when {
        ai.startsWith("관측 실행 중") -> "관측 중"
        ai.startsWith("관측 실행 보고됨") -> "관측 보고됨"
        ai.startsWith("관측 기동 중") -> "관측 시작 중"
        ai.startsWith("관측 저하") -> "관측 저하"
        ai.startsWith("관측 중지") -> "관측 중지"
        ai.startsWith("통신 단절") -> "통신 단절"
        ai.startsWith("설정 적용됨") -> "관측 시작 전"
        ai.startsWith("요청 수신") -> "관측 시작 대기"
        else -> "관측 상태 미수신"
    }

    fun bindSupervision(
        ai: String,
        camera: String,
        preservation: String,
        stage: ObservationStartPolicy.Stage,
        canStart: Boolean,
        hasRecord: Boolean,
    ) {
        val tvAi = idMap[R.id.tvCompactAi] as? TextView ?: activity.findViewById(R.id.tvCompactAi)
        val tvCam = idMap[R.id.tvCompactCamera] as? TextView ?: activity.findViewById(R.id.tvCompactCamera)
        val tvPres = idMap[R.id.tvCompactPreserve] as? TextView
            ?: activity.findViewById(R.id.tvCompactPreserve)
        // Dark, video-readable facts (never the autonomous status color).
        val fg = ContextCompat.getColor(activity, R.color.ksi_video_fg)
        flightHasRecord = hasRecord
        val cameraFacts = camera.split(" · ", limit = 2)
        listOf(ai, cameraFacts.firstOrNull().orEmpty().removePrefix("카메라: ").removePrefix("카메라 "),
            cameraFacts.getOrNull(1)?.removePrefix("자동 ") ?: "미확인",
            if (hasRecord) "기록 중" else "시작 전", preservation).forEachIndexed { index, value ->
            flightStatusValues[index].text = value.ifBlank { "미확인" }
        }
        flightStatusEnd.visibility = if (hasRecord) View.VISIBLE else View.GONE
        flightStatusEndNote.visibility = if (hasRecord) View.VISIBLE else View.GONE
        if (hasRecord && (req(R.id.tvPrimaryAlert) as TextView).text.contains("준비 미완")) {
            // This pre-start summary duplicates the persistent AI state.
            // Aircraft, storage, link-loss and actual runtime faults remain.
            req(R.id.cockpitPriorityRow).visibility = View.GONE
        }
        tvAi?.apply {
            text = when (stage) {
                ObservationStartPolicy.Stage.REQUEST_PENDING -> "관측 시작 응답 대기"
                ObservationStartPolicy.Stage.RECOVER -> "관측 요청 미확인"
                else -> compactAiLabel(ai)
            }
            textSize = 13f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(fg)
        }
        // No persistent "AI 미확인" card. With no record and nothing to
        // start/recover, the AI row is simply absent; the state remains in
        // the bounded detail sheet.
        pillBox.visibility = if (hasRecord || stage == ObservationStartPolicy.Stage.REQUEST_PENDING ||
            stage == ObservationStartPolicy.Stage.RECOVER) View.VISIBLE else View.GONE
        tvCam?.apply {
            text = camera.ifBlank { "카메라 미확인" }.removePrefix("카메라: ")
                .replace("카메라 미확인", "조작권 미확인").replace(" · ", "\n")
            contentDescription = camera
            setTextColor(fg)
        }
        tvPres?.apply {
            text = if (hasRecord) "KSI 기록 중 · 원본 ${preservation.removePrefix("로컬 ").ifBlank { "미확인" }}" else ""
            visibility = if (hasRecord) View.VISIBLE else View.GONE
            textSize = 10f
            maxLines = 2
            ellipsize = null
            setTextColor(fg)
        }
        val start = idMap[R.id.btnStart] ?: activity.findViewById(R.id.btnStart)
        (start as? Button)?.apply {
            text = "관측 시작"
            contentDescription = "자동 관측 시작"
            rolePrimary(this)
        }
        start?.visibility =
            if (stage == ObservationStartPolicy.Stage.READY && canStart) View.VISIBLE else View.GONE
        workspaceObservationRecovery.visibility = if (stage == ObservationStartPolicy.Stage.REQUEST_PENDING ||
            stage == ObservationStartPolicy.Stage.RECOVER) View.VISIBLE else View.GONE
        if (workspaceObservationRecovery.visibility == View.VISIBLE) {
            roleVideo(workspaceObservationRecovery)
        }
        // Re-apply settled roles: the parent may reset button backgrounds on
        // refresh without a state change.
        (idMap[R.id.btnTaskPrepare] as? Button)?.let { roleVideo(it); it.text = "임무 준비" }
        (idMap[R.id.btnTaskDetails] as? Button)?.let {
            roleGhost(it, dark = true)
            it.background = outlinedFlightControl()
            it.setTextColor(fg)
        }
        (idMap[R.id.btnEndFlight] as? Button)?.let {
            roleGhost(it, dark = true)
            it.background = outlinedFlightControl()
        }
        val prepare = idMap[R.id.btnTaskPrepare] ?: activity.findViewById(R.id.btnTaskPrepare)
        prepare?.visibility = View.GONE
        // Aircraft camera recording and the KSI record are independent.
        // Starting one must not remove the other's manual entry point.
        val rec = idMap[R.id.btnReqRec] ?: activity.findViewById(R.id.btnReqRec)
        rec?.visibility = View.VISIBLE
        layoutFlightAvailability()
    }

    fun bindPreparationRecovery(note: String?) {
        missionRestoredPreparationNote = note
    }

    fun bindMission(
        bundle: ObjectNode?,
        siteName: String?,
        stateName: String?,
        hasRecord: Boolean,
        needsRevalidation: Boolean,
        rejection: String?,
        next: MissionNextAction.Next,
        facts: WorkspaceMissionFacts.Facts,
        unsentRequestTypes: Set<String> = emptySet(),
        selectionMismatch: Boolean = false,
    ) {
        // Forward the actual bundle to the stored-zone preview.
        missionHasSelection = bundle != null
        missionMapView.setBundle(bundle)
        missionPreviewSite.text = siteName ?: "현장 미확인"
        layoutMissionSelection()
        flightMiniMap.setBundle(bundle)
        val bundleId = bundle?.path("bundle_id")?.takeIf { it.isTextual }?.asText()
        val shortId = bundleId?.take(8) ?: "없음"
        val state = stateName ?: "미확인"
        prepareValue.text = MissionPreparationPresentation.step(facts.prepare, "MISSION_PREPARE" in unsentRequestTypes)
        applyValue.text = MissionPreparationPresentation.step(facts.apply, "MISSION_APPLY" in unsentRequestTypes)
        missionRequestNote.text = if (selectionMismatch) "다른 계획이 준비 중입니다. 준비 중인 계획으로 돌아가 상태를 확인하세요."
            else missionRestoredPreparationNote
                ?: MissionPreparationPresentation.guidance(next, !facts.linkStale, unsentRequestTypes.isNotEmpty())
        missionBoundPlan.visibility = if (selectionMismatch) View.VISIBLE else View.GONE
        missionConnectionNote.visibility = if (facts.linkStale) View.VISIBLE else View.GONE
        prepareValue.contentDescription = "장치 준비 ${prepareValue.text}" + if (facts.linkStale) ". 연결 끊김. 마지막 응답 기준" else ""
        applyValue.contentDescription = "설정 적용 ${applyValue.text}" + if (facts.linkStale) ". 연결 끊김. 마지막 응답 기준" else ""
        recordValue.text = if (hasRecord) "기록 중" else "시작 전"
        req(R.id.tvMissionNextAction).visibility = View.GONE
        val zones = bundle?.path("mission_snapshot")?.path("zones")
        val zoneSummary = if (zones?.isArray == true) "구역 ${zones.size()}개" else "구역 미제공"
        workspaceMissionSummary.text = if (bundle == null) "현장·계획을 선택하세요"
            else "선택 계획 $shortId · $zoneSummary\n기체 비행·카메라 별도"
        workspaceMissionSummary.contentDescription = "${siteName ?: "현장 미선택"}. ${workspaceMissionSummary.text}"
        val btnPrepare = idMap[R.id.btnPrepare] ?: activity.findViewById(R.id.btnPrepare)
        val btnApply = idMap[R.id.btnApply] ?: activity.findViewById(R.id.btnApply)
        val startFlight = idMap[R.id.btnStartFlight] ?: activity.findViewById(R.id.btnStartFlight)
        val btnReconcile = idMap[R.id.btnReconcile] ?: activity.findViewById(R.id.btnReconcile)
        missionPendingAction.visibility = if (!selectionMismatch && next == MissionNextAction.Next.WAITING) View.VISIBLE else View.GONE
        missionPendingAction.text = MissionPreparationPresentation.pendingAction(unsentRequestTypes.isNotEmpty())
        missionPendingAction.contentDescription = "${missionPendingAction.text}. 응답 전까지 다음 요청을 보내지 않습니다."
        missionOpenFlight.visibility = View.VISIBLE
        // Only the current phase's primary stays visible; unknown (incl.
        // rejected / needs-revalidation / unrecognized state) is an explicit
        // reconcile, never a silent done.
        btnPrepare?.visibility = if (!selectionMismatch && (next == MissionNextAction.Next.PREPARE ||
            (!hasRecord && (stateName == "REJECTED" || needsRevalidation)))) View.VISIBLE else View.GONE
        btnApply?.visibility = if (!selectionMismatch && next == MissionNextAction.Next.APPLY) View.VISIBLE else View.GONE
        startFlight?.visibility =
            if (!selectionMismatch && next == MissionNextAction.Next.START_RECORD) View.VISIBLE else View.GONE
        btnReconcile?.visibility = if (!selectionMismatch && next == MissionNextAction.Next.UNKNOWN) View.VISIBLE else View.GONE
        val retry = !hasRecord && (stateName == "REJECTED" || needsRevalidation)
        (btnPrepare as? Button)?.apply {
            text = if (retry) "다시 준비 요청" else "장치 준비 요청"
            if (retry || next == MissionNextAction.Next.PREPARE) rolePrimary(this) else roleTonal(this)
        }
        (btnReconcile as? Button)?.apply {
            text = "장치 응답 확인"
            if (retry) roleTonal(this) else rolePrimary(this)
        }
        (btnApply as? Button)?.apply { text = "설정 적용 요청"; rolePrimary(this) }
        (startFlight as? Button)?.apply { text = "KSI 기록 시작"; rolePrimary(this) }
        if (!rejection.isNullOrBlank()) {
            missionRejectionDetail = rejection
            workspaceMissionRejection.text = "적용 거부 · 상세 확인"
            workspaceMissionRejection.contentDescription = rejection
            workspaceMissionRejection.visibility = View.VISIBLE
        } else {
            missionRejectionDetail = null
            workspaceMissionRejection.visibility = View.GONE
        }
    }

    fun bindCustody(total: Int?, complete: Int?, synced: Int?, closeUnresolved: Boolean) {
        // Null is never rendered as 0; close-out unknown stays visible.
        // The filmstrip is never hidden here: custody is a strip below it.
        workspaceCustodyEdge.text = "관측 장치 원본\n미확인"
        // With no observation to select, the work surface is the record
        // summary. Its actual stored artifact counts must not disappear into
        // an event-level unknown. An event with missing links stays unknown.
        val recordOnly = selectedRecordRow?.takeIf { it.eventCount == 0 }
        val countTotal = recordOnly?.artifactCount ?: total
        val countComplete = recordOnly?.artifactCompleteCount ?: complete
        val countSynced = recordOnly?.artifactSyncedCount ?: synced
        val rcName = if (recordOnly != null) "기록 자료 수신" else "관측 자료 수신"
        val rcCore = if (countTotal == null || countComplete == null) {
            "이 RC 수신\n미확인"
        } else {
            "$rcName\n$countComplete / $countTotal"
        }
        workspaceCustodyRc.text =
            if (closeUnresolved) "$rcCore · 마감 미확인" else rcCore
        workspaceCustodyCore.text = if (countSynced == null || countTotal == null) {
            "RC → 서버\n미확인"
        } else {
            "RC → 서버\n전송 확인 $countSynced / $countTotal"
        }
    }

    /** With no chosen plan, browsing is the work surface, not an empty map. */
    fun bindMissionSite(hasSite: Boolean) {
        missionHasSite = hasSite
        layoutMissionSelection()
    }

    private fun layoutMissionSelection() {
        val preparing = prepPanel.visibility == View.VISIBLE
        missionMainColumn.visibility = View.VISIBLE
        missionPreviewContext.visibility = if (preparing) View.VISIBLE else View.GONE
        missionEmpty.visibility = if (!missionHasSite && !missionHasSelection && !preparing) View.VISIBLE else View.GONE
        missionSelectionBody.visibility = if (missionHasSite || missionHasSelection) View.VISIBLE else View.GONE
        missionPlanScroll.visibility = if (!preparing && missionHasSite) View.VISIBLE else View.GONE
        // Never compress plan text into a narrow column beside a map. The
        // stored-coordinate overview belongs to the explicit preparation step.
        missionMapView.visibility = if (preparing) View.VISIBLE else View.GONE
        missionMapView.layoutParams = LinearLayout.LayoutParams(-1, -1)
        missionBottomAction.visibility = if (missionHasSelection) View.VISIBLE else View.GONE
        btnMissionOpenPreparation.visibility = if (preparing) View.GONE else View.VISIBLE
        workspaceMissionSummary.visibility = if (missionHasSelection) View.VISIBLE else View.GONE
        browserColumn.layoutParams = LinearLayout.LayoutParams(
            dp(232),
            LinearLayout.LayoutParams.MATCH_PARENT,
        )
    }

    fun bindRecordLabel(label: String) {
        workspaceHudNumbers.text = label
    }

    /**
     * Apply the persisted display preference. This only changes how the
     * read-only telemetry is drawn; it never touches commands, camera,
     * recording or session state.
     */
    fun setHudStyle(style: FlightHudStyle) {
        hudStyle = style
        if (::flightHud.isInitialized) flightHud.style = style
        paintHudStyleButtons()
    }

    private fun paintHudStyleButtons() {
        val fg = ContextCompat.getColor(activity, R.color.ksi_fg)
        val muted = ContextCompat.getColor(activity, R.color.ksi_muted)
        hudStyleButtons.forEach { (style, button) ->
            val selected = style == hudStyle
            button.isSelected = selected
            button.background = hudStyleButtonBackground(selected)
            button.setTextColor(if (selected) fg else muted)
            button.setTypeface(
                button.typeface,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }
    }

    /** Segmented choice: all three look tappable; the selected one is filled. */
    private fun hudStyleButtonBackground(selected: Boolean): Drawable {
        val shape = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(6).toFloat()
            setColor(
                if (selected) ContextCompat.getColor(activity, R.color.ksi_surface)
                else Color.TRANSPARENT,
            )
            setStroke(
                dp(if (selected) 2 else 1),
                ContextCompat.getColor(
                    activity,
                    if (selected) R.color.ksi_muted else R.color.ksi_border,
                ),
            )
        }
        return RippleDrawable(
            ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.ksi_surface_subtle)),
            shape,
            null,
        )
    }

    fun bindFlightTelemetry(altitude: String, distance: String, speed: String, verticalSpeed: String, heading: String) {
        // The cardless HUD renders the same already formatted, read-only
        // strings; no second telemetry source or invented value is created.
        if (::flightHud.isInitialized) {
            flightHud.bind(altitude, distance, speed, verticalSpeed, heading)
        }
        listOf(altitude, distance, speed, verticalSpeed, heading).forEachIndexed { i, value ->
            flightTelemetry[i].apply {
                text = android.text.SpannableString("${flightTelemetryLabels[i]}  $value").apply {
                    setSpan(android.text.style.RelativeSizeSpan(.83f), 0, flightTelemetryLabels[i].length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(android.text.style.ForegroundColorSpan(androidx.core.graphics.ColorUtils.setAlphaComponent(
                        ContextCompat.getColor(activity, R.color.ksi_video_fg), 190)), 0, flightTelemetryLabels[i].length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                textSize = if (value.length > 9) 11f else 12f
                alignTextBaseline(this, 18)
            }
        }
    }

    fun bindReviewContext(summary: String) {
        workspaceReviewContext.text = summary.replace("\n", " · ")
    }

    fun bindZoom(enabled: Boolean, percent: Int) {
        workspaceZoomOut.isEnabled = enabled && percent > 100
        workspaceZoomIn.isEnabled = enabled && percent < 800
        workspaceZoomReset.isEnabled = enabled
        workspaceZoomValue.text = if (enabled) "$percent%" else "보기 없음"
    }

    /** View state survives font/theme recreation, never protocol state. */
    fun saveUiState(state: android.os.Bundle) {
        state.putBoolean("workspace.expandedMap", flightMapExpanded)
        state.putBoolean("workspace.preparation", prepPanel.visibility == View.VISIBLE)
        state.putBoolean("workspace.recordDetail", recordDetailOpen)
        state.putBoolean("workspace.expandedEvidence", evidenceExpanded)
        state.putInt("workspace.settingsTab", settingsTabs.indexOfFirst { it.isSelected }.coerceAtLeast(0))
    }

    fun restoreUiState(state: android.os.Bundle) {
        setFlightMapExpanded(state.getBoolean("workspace.expandedMap"))
        val preparing = state.getBoolean("workspace.preparation")
        prepPanel.visibility = if (preparing && missionHasSelection) View.VISIBLE else View.GONE
        browserColumn.visibility = if (prepPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        layoutMissionSelection()
        recordDetailOpen = state.getBoolean("workspace.recordDetail")
        recordDetail.visibility = if (recordDetailOpen) View.VISIBLE else View.GONE
        recordIndex.visibility = if (recordDetailOpen) View.GONE else View.VISIBLE
        if (state.getBoolean("workspace.expandedEvidence") && !evidenceExpanded) toggleEvidenceExpand()
        selectSettingsTab(state.getInt("workspace.settingsTab", 0).coerceIn(0, settingsTabs.lastIndex), settingsPanels)
    }

    /** Local disclosures close before the controller's system Back leaves a task. */
    fun handleBack(): Boolean {
        if (pageObserve.visibility == View.VISIBLE && flightMapExpanded) {
            setFlightMapExpanded(false)
            flightMiniMap.requestFocus()
            return true
        }
        if (pageMission.visibility == View.VISIBLE && prepPanel.visibility == View.VISIBLE) {
            prepPanel.visibility = View.GONE
            browserColumn.visibility = View.VISIBLE
            missionBottomAction.visibility = View.VISIBLE
            layoutMissionSelection()
            btnMissionOpenPreparation.requestFocus()
            return true
        }
        if (pageReview.visibility == View.VISIBLE && evidenceExpanded) {
            workspaceEvidenceExpand.performClick()
            workspaceEvidenceExpand.requestFocus()
            return true
        }
        if (pageReview.visibility == View.VISIBLE && recordDetailOpen) {
            showRecordIndex()
            return true
        }
        if (pageSettings.visibility == View.VISIBLE && settingsPanels[0].visibility == View.VISIBLE && coreEditorForm.visibility == View.VISIBLE) {
            coreEditorForm.visibility = View.GONE
            coreEditorToggle.requestFocus()
            return true
        }
        return false
    }
}
