package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 768x480dp density regression (RC 1920x1200 @400dpi, host-only XML parse —
 * NOT an Android render claim): logical-dp fixed minimum controls, never a
 * uniform shrink. Shell rail 76dp + header 48dp leave 692dp; the mission
 * command dock is a fixed 256dp column whose persistent upper stack
 * (title + concise max-2-line next action + 3 sequential rows) fits ≤260dp;
 * the full readiness facts stay in the bounded left support scroll; ops
 * children use bounded widths and the Edge runtime panel
 * (edgeRuntimePanel/tvEdgeRuntimeState) stays fixed outside the deck scroll;
 * hidden controls stay reachable via internal scroll.
 */
class RcDensity768LayoutTest {

    private fun findModuleFile(relative: String): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(8) {
            val direct = File(dir, relative)
            if (direct.isFile) return direct
            val prefixed = File(dir, "android-rc/rc-app/$relative")
            if (prefixed.isFile) return prefixed
            val parent = dir.parentFile ?: return@repeat
            dir = parent
        }
        throw IllegalStateException("cannot locate $relative from ${System.getProperty("user.dir")}")
    }

    private fun parseLayout(): Element {
        val file = findModuleFile("src/main/res/layout/activity_main.xml")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return builder.parse(file).documentElement
    }

    private fun dimenDp(name: String): Int {
        val file = findModuleFile("src/main/res/values/dimens.xml")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val root = builder.parse(file).documentElement
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == "dimen" && node.getAttribute("name") == name) {
                return node.textContent.trim().removeSuffix("dp").toInt()
            }
        }
        throw IllegalStateException("missing @dimen/$name")
    }

    private fun Element.childElements(): List<Element> {
        val out = mutableListOf<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element) out.add(node)
        }
        return out
    }

    private fun Element.idName(): String? =
        getAttribute("android:id").takeIf { it.isNotEmpty() }?.substringAfter("/")

    private fun Element.widthAttr(): String = getAttribute("android:layout_width")

    private fun Element.heightDp(): Int? =
        getAttribute("android:layout_height").takeIf { it.endsWith("dp") }
            ?.removeSuffix("dp")?.toIntOrNull()

    private fun Element.maxWidthDp(): Int? =
        getAttribute("android:maxWidth").takeIf { it.endsWith("dp") }
            ?.removeSuffix("dp")?.toIntOrNull()

    private fun Element.maxLines(): Int? =
        getAttribute("android:maxLines").takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun Element.textRef(): String? =
        getAttribute("android:text").takeIf { it.isNotEmpty() }

    private fun Element.marginTopDp(): Int? {
        val raw = getAttribute("android:layout_marginTop")
        if (raw.endsWith("dp")) return raw.removeSuffix("dp").toIntOrNull()
        return null
    }

    private fun Element.marginTopDpResolved(resolve: (String) -> Int): Int? {
        val raw = getAttribute("android:layout_marginTop")
        if (raw.endsWith("dp")) return raw.removeSuffix("dp").toIntOrNull()
        if (raw.startsWith("@dimen/")) return resolve(raw.removePrefix("@dimen/"))
        return null
    }

    private fun findById(root: Element, id: String): Element? {
        if (root.idName() == id) return root
        return root.childElements().firstNotNullOfOrNull { findById(it, id) }
    }

    private fun parentElement(root: Element, target: Element): Element? {
        for (child in root.childElements()) {
            if (child.isSameNode(target)) return root
            val found = parentElement(child, target)
            if (found != null) return found
        }
        return null
    }

    private fun isDescendantOf(root: Element, ancestorId: String, target: Element): Boolean {
        var current = parentElement(root, target)
        while (current != null) {
            if (current.idName() == ancestorId) return true
            current = parentElement(root, current)
        }
        return false
    }

    private fun isInsideAnyScroll(root: Element, target: Element): Boolean {
        var current = parentElement(root, target)
        while (current != null) {
            if (current.tagName == "ScrollView") return true
            current = parentElement(root, current)
        }
        return false
    }

    @Test
    fun shell_rail_76_and_header_48_leaving_692dp() {
        val root = parseLayout()
        assertEquals(76, dimenDp("rc_rail_width"))
        assertEquals(48, dimenDp("rc_header_height"))
        val rail = findById(root, "navRail")!!
        assertTrue("rail keeps the 76dp shell width", rail.widthAttr() in setOf("76dp", "@dimen/rc_rail_width"))
        val header = findById(root, "appHeader")!!
        assertTrue("header keeps the 48dp shell height", header.getAttribute("android:layout_height") in setOf("48dp", "@dimen/rc_header_height"))
        // 768 - 76 = 692dp of content width by construction.
        assertEquals(692, 768 - dimenDp("rc_rail_width"))
    }

    @Test
    fun mission_command_dock_upper_budget_stays_within_260dp() {
        val root = parseLayout()
        assertEquals(256, dimenDp("rc_command_dock_width"))
        assertEquals(48, dimenDp("rc_touch_min"))
        assertEquals(56, dimenDp("rc_record_start_height"))
        assertEquals(8, dimenDp("rc_mission_gap"))
        val dock = findById(root, "missionCommandDock")!!
        assertTrue("dock keeps the fixed 256dp width", dock.widthAttr() in setOf("256dp", "@dimen/rc_command_dock_width"))
        assertEquals("missionBodyRow", parentElement(root, dock)?.idName())

        fun dockHeight(id: String): Int {
            val view = findById(root, id)!!
            val direct = view.heightDp()
            if (direct != null) return direct
            return when (view.getAttribute("android:layout_height")) {
                "@dimen/rc_touch_min" -> dimenDp("rc_touch_min")
                "@dimen/rc_record_start_height" -> dimenDp("rc_record_start_height")
                else -> 0
            }
        }
        assertEquals("Prepare keeps 48dp", 48, dockHeight("btnPrepare"))
        assertEquals("Apply keeps 48dp", 48, dockHeight("btnApply"))
        assertEquals("KSI local record start keeps 56dp", 56, dockHeight("btnStartFlight"))

        // All 3 rows persistent: outside every scroll region.
        for (id in listOf("btnPrepare", "btnApply", "btnStartFlight")) {
            assertTrue("$id must never scroll away", !isInsideAnyScroll(root, findById(root, id)!!))
        }
        // Above the explanation scroll the dock holds EXACTLY the title, the
        // concise next-action line, and the rows — no duplicate labels, no
        // sequence note, no full-facts view (those would re-create the
        // start-row clipping at ~350dp available height).
        val above = dock.childElements().takeWhile { it.idName() != "missionDockExplainScroll" }
        val aboveTexts = above.filter { it.tagName == "TextView" }
        assertEquals("only title + next-action above the rows", 2, aboveTexts.size)
        val title = aboveTexts[0]
        assertEquals(1, title.maxLines())
        val next = findById(root, "tvMissionNextAction")!!
        assertTrue("concise next-action must be the second text above the rows", aboveTexts[1].isSameNode(next))
        assertTrue("next-action capped at 2 lines (no metadata pile-up)", (next.maxLines() ?: 99) <= 2)
        assertTrue(
            "next-action text must be a mission_next_* string, never the full facts",
            next.textRef()?.removePrefix("@string/")?.startsWith("mission_next_") == true,
        )
        assertTrue(
            "full readiness facts must not sit in the dock",
            above.none { it.idName() == "tvMissionReadiness" },
        )
        assertTrue(
            "full readiness facts stay in the bounded left support scroll",
            isDescendantOf(root, "missionContentScroll", findById(root, "tvMissionReadiness")!!),
        )
        val aboveIds = above.mapNotNull { it.idName() }
        assertTrue("next above rows", aboveIds.indexOf("tvMissionNextAction") < aboveIds.indexOf("rowPrepare"))
        assertTrue("rows sequential", aboveIds.indexOf("rowPrepare") < aboveIds.indexOf("btnStartFlight"))
        assertTrue("explanation scroll below the rows",
            dock.childElements().mapNotNull { it.idName() }.indexOf("missionDockExplainScroll") >
                aboveIds.indexOf("btnStartFlight"))

        // Static worst-case upper stack — every declared geometric term
        // included, resource-aware (a static bound, not a render
        // measurement; per-line caps of 20dp for single 14sp/13sp lines):
        // dock pads 8+8=16 + title 20 + next margin 4 + next 2 lines 40 +
        // next pads 4+4=8 + row margin 4 + buttons 48+48+56=152 +
        // between-rows gaps 8+8=16 → 260.
        val gap = dimenDp("rc_mission_gap")
        assertEquals(4, findById(root, "rowPrepare")!!.marginTopDpResolved(::dimenDp))
        assertEquals(8, findById(root, "btnApply")!!.marginTopDpResolved(::dimenDp))
        assertEquals(8, findById(root, "btnStartFlight")!!.marginTopDpResolved(::dimenDp))
        val nextMargin = next.marginTopDpResolved(::dimenDp) ?: 99
        assertEquals("next top margin 4dp", 4, nextMargin)
        val nextPadTop = next.getAttribute("android:paddingTop").takeIf { it.endsWith("dp") }
            ?.removeSuffix("dp")?.toIntOrNull()
        val nextPadBottom = next.getAttribute("android:paddingBottom").takeIf { it.endsWith("dp") }
            ?.removeSuffix("dp")?.toIntOrNull()
        assertEquals("next paddingTop 4dp", 4, nextPadTop)
        assertEquals("next paddingBottom 4dp", 4, nextPadBottom)
        val dockPad = dock.getAttribute("android:padding").takeIf { it.endsWith("dp") }
            ?.removeSuffix("dp")?.toIntOrNull() ?: -1
        assertEquals("dock padding 8dp all sides", 8, dockPad)
        val upper = (dockPad * 2) + 20 + nextMargin + 40 +
            (nextPadTop!! + nextPadBottom!!) + 4 + 152 + gap + gap
        assertEquals("conservative persistent stack must total exactly the 260dp budget", 260, upper)
        assertTrue("persistent dock upper stack must fit ≤260dp (static bound, got $upper)", upper <= 260)
    }

    @Test
    fun ops_children_use_bounded_widths_and_runtime_panel_stays_fixed() {
        val root = parseLayout()
        // wrap_content buttons in horizontal rows must carry maxWidth so
        // intrinsic text can never push the right column offscreen.
        // (The legacy header focus toggle is gone — settings holds the
        // only wide-video control.)
        for (id in listOf("btnConnectionDetail", "btnReconcile")) {
            val button = findById(root, id)!!
            assertEquals("wrap_content", button.widthAttr())
            assertTrue("$id must bound its intrinsic width", (button.maxWidthDp() ?: 0) > 0)
        }
        // View toggle buttons share the deck width (weighted 0dp).
        for (id in listOf("btnViewVideo", "btnViewMap")) {
            val button = findById(root, id)!!
            assertEquals("0dp", button.widthAttr())
            assertTrue("$id must weight-share the deck", (button.getAttribute("android:layout_weight").toFloatOrNull() ?: 0f) > 0f)
        }
        // The Edge runtime status area is edgeRuntimePanel/tvEdgeRuntimeState
        // (NOT the connection headline): fixed in the right column ABOVE the
        // scrolling deck — a sibling of the scroll, never inside it — so it
        // can never scroll away or overlay the video/telemetry.
        val runtime = findById(root, "edgeRuntimePanel")!!
        assertEquals("opsRightColumn", parentElement(root, runtime)?.idName())
        assertTrue("runtime panel must never live inside the deck scroll",
            !isDescendantOf(root, "opsDeckScroll", runtime))
        val rightOrder = findById(root, "opsRightColumn")!!.childElements().mapNotNull { it.idName() }
        assertTrue("runtime panel fixed above the deck scroll: $rightOrder",
            rightOrder.indexOf("edgeRuntimePanel") in 0 until rightOrder.indexOf("opsDeckScroll"))
        val state = findById(root, "tvEdgeRuntimeState")!!
        assertTrue("tvEdgeRuntimeState must live under edgeRuntimePanel",
            isDescendantOf(root, "edgeRuntimePanel", state))
        assertTrue("runtime panel must not overlay the video", !isDescendantOf(root, "videoStage", runtime))
        assertTrue("runtime panel must not cover the telemetry band", !isDescendantOf(root, "telemetryStrip", runtime))
        // The connection headline is a separate bounded summary in the top
        // row — it also stays out of the deck scroll and off the video.
        val headline = findById(root, "tvConnectionHeadline")!!
        assertTrue("headline must not scroll away", !isDescendantOf(root, "opsDeckScroll", headline))
        assertTrue("headline must not overlay the video", !isDescendantOf(root, "videoStage", headline))
        assertTrue("headline must fit (bounded)", (headline.maxWidthDp() ?: 0) > 0)
    }

    @Test
    fun support_scroll_children_keep_intrinsic_height() {
        val root = parseLayout()
        // Same failure class as the dock clipping: a 0dp+weight child of a
        // wrap_content scroll body measures 0 and hides mission state.
        // Every direct TextView/Button child of the mission support scroll
        // body must use an intrinsic height (wrap_content), never 0dp+weight.
        val scroll = findById(root, "missionContentScroll")!!
        val body = scroll.childElements().firstOrNull()
        assertTrue("support scroll must declare its body", body != null)
        val offenders = mutableListOf<String>()
        fun check(element: Element) {
            val h = element.getAttribute("android:layout_height")
            val w = element.getAttribute("android:layout_weight")
            if (h == "0dp" && w.isNotEmpty()) {
                offenders.add(element.idName() ?: element.tagName)
            }
        }
        body!!.childElements().forEach { check(it) }
        assertTrue("no 0dp+weight direct child in the support scroll body: $offenders", offenders.isEmpty())
        val mission = findById(root, "tvMission")!!
        assertTrue("tvMission must live in the support scroll",
            isDescendantOf(root, "missionContentScroll", mission))
        assertEquals("wrap_content", mission.getAttribute("android:layout_height"))
        assertTrue("tvMission must not carry a weight", mission.getAttribute("android:layout_weight").isEmpty())
        assertTrue("tvMission keeps its 4-line cap", mission.maxLines() == 4)
    }

    @Test
    fun detail_page_is_full_screen_not_a_video_dock() {
        val root = parseLayout()
        val panel = findById(root, "rightStatusPanel")!!
        assertEquals("match_parent", panel.widthAttr())
        assertEquals("match_parent", panel.getAttribute("android:layout_height"))
        val layout = findModuleFile("src/main/java/ksi/safetyedge/rc/RcWorkspaceLayout.kt").readText()
        assertTrue("detail page uses a full-screen frame",
            layout.contains("rightStatusPanel.layoutParams = FrameLayout.LayoutParams("))
        assertTrue(layout.contains("FrameLayout.LayoutParams.MATCH_PARENT"))
        val source = findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()
        val block = source.substringAfter("private fun setFlightPanelOpen(").substringBefore("\n    private fun ")
        assertTrue("detail visibility is the only mode switch",
            block.contains("rightStatusPanel.visibility = if (open)"))
        assertFalse("detail must not reserve video width", block.contains("rightMargin"))
        assertFalse("detail is no longer a side dock", block.contains("rc_command_dock_width"))
    }

    @Test
    fun all_four_nav_destinations_reachable_with_scroll_guard() {
        val root = parseLayout()
        // Adopted: 4 destinations (비행·임무·기록·설정). Evidence is a
        // records-internal filter reached from the records page only —
        // never a rail destination.
        for (id in listOf("btnNavObserve", "btnNavMission", "btnNavReview", "btnNavSettings")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue("$id must live under navRail", isDescendantOf(root, "navRail", view!!))
            assertTrue("$id must never be gone by default", view.getAttribute("android:visibility") != "gone")
        }
        assertTrue("evidence is not a rail destination", findById(root, "btnNavEvidence") == null)
        // Font-scale guard: upper destinations scroll internally; settings
        // stays fixed at the rail bottom — every destination reachable.
        for (id in listOf("btnNavObserve", "btnNavMission", "btnNavReview")) {
            assertTrue("$id must be reachable via the internal nav scroll",
                isDescendantOf(root, "navScroll", findById(root, id)!!))
        }
        assertTrue("settings stays fixed at the rail bottom",
            parentElement(root, findById(root, "btnNavSettings")!!)?.idName() == "navRail")
    }
}
