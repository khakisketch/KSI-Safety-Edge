package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Static regression pins for the integrated operation screen (observe
 * page): the approved 1920x1200 work row — telemetry band full width on
 * top, then a horizontal row with LEFT video (+ 6-fact status strip
 * beneath it) beside the RIGHT scrolling ops deck column. The priority
 * row stays problem-only, full width, directly below the telemetry band.
 * The AI candidate/response UI lives inside the deck's AI area (never
 * over the video or telemetry), and every deck button keeps the 48dp
 * glove minimum. Same host-only XML style as
 * [MissionReadinessLayoutTest].
 */
class IntegratedOpsLayoutTest {

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

    private fun stringNames(): Set<String> {
        val file = findModuleFile("src/main/res/values/strings.xml")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val root = builder.parse(file).documentElement
        val out = mutableSetOf<String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == "string") {
                out.add(node.getAttribute("name"))
            }
        }
        return out
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

    private fun Element.weight(): Float? =
        getAttribute("android:layout_weight").takeIf { it.isNotEmpty() }?.toFloatOrNull()

    private fun Element.minHeightDp(): Int? =
        getAttribute("android:minHeight").takeIf { it.endsWith("dp") }
            ?.removeSuffix("dp")?.toIntOrNull()

    private fun Element.minHeightDpResolved(): Int? {
        val raw = getAttribute("android:minHeight")
        if (raw.endsWith("dp")) return raw.removeSuffix("dp").toIntOrNull()
        if (!raw.startsWith("@dimen/")) return null
        val dims = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(findModuleFile("src/main/res/values/dimens.xml")).documentElement
        return dims.childElements().firstOrNull {
            it.getAttribute("name") == raw.substringAfter("@dimen/")
        }?.textContent?.trim()?.removeSuffix("dp")?.toIntOrNull()
    }

    private fun Element.heightDp(): Int? {
        val value = getAttribute("android:layout_height")
        if (value.endsWith("dp")) return value.removeSuffix("dp").toIntOrNull()
        if (!value.startsWith("@dimen/")) return null
        val dims = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(findModuleFile("src/main/res/values/dimens.xml")).documentElement
        return dims.childElements().firstOrNull {
            it.getAttribute("name") == value.substringAfter("@dimen/")
        }?.textContent?.trim()?.removeSuffix("dp")?.toIntOrNull()
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

    @Test
    fun telemetry_band_priority_row_and_work_row_stack_in_order() {
        val root = parseLayout()
        val observe = findById(root, "pageObserve")!!
        val strip = findById(root, "telemetryStrip")!!
        val priority = findById(root, "cockpitPriorityRow")!!
        val workRow = findById(root, "observeWorkRow")!!

        // Telemetry, the problem-only priority row, and the work row are
        // direct children of the observe page, in that order: flight facts
        // first, urgent items next, the video/deck row last.
        assertEquals("pageObserve", parentElement(root, strip)?.idName())
        assertEquals("pageObserve", parentElement(root, priority)?.idName())
        assertEquals("pageObserve", parentElement(root, workRow)?.idName())
        val order = observe.childElements().mapNotNull { it.idName() }
        val stripIndex = order.indexOf("telemetryStrip")
        val priorityIndex = order.indexOf("cockpitPriorityRow")
        val workIndex = order.indexOf("observeWorkRow")
        listOf(stripIndex, priorityIndex, workIndex).forEach {
            assertTrue("observe page must declare telemetry/priority/workRow: $order", it >= 0)
        }
        assertTrue("telemetry band above priority row: $order", stripIndex < priorityIndex)
        assertTrue("priority row above work row: $order", priorityIndex < workIndex)
        assertTrue("work row fills below the bands", (workRow.weight() ?: 0f) > 0f)
        assertEquals("horizontal", workRow.getAttribute("android:orientation"))
        assertTrue("observe page keeps no permanent scroll: facts visible", observe.tagName == "LinearLayout")
    }

    @Test
    fun video_and_deck_sit_side_by_side_with_video_majority() {
        val root = parseLayout()
        val workRow = findById(root, "observeWorkRow")!!
        val left = findById(root, "observeLeftColumn")!!
        val right = findById(root, "opsRightColumn")!!
        val runtime = findById(root, "edgeRuntimePanel")!!
        val scroll = findById(root, "opsDeckScroll")!!
        val video = findById(root, "videoFrame")!!
        val deck = findById(root, "opsDeck")!!

        assertEquals("observeWorkRow", parentElement(root, left)?.idName())
        assertEquals("observeWorkRow", parentElement(root, right)?.idName())
        // The runtime status area is fixed above the scrolling command deck:
        // both are children of the right column, in that order.
        assertEquals("opsRightColumn", parentElement(root, runtime)?.idName())
        assertEquals("opsRightColumn", parentElement(root, scroll)?.idName())
        val rightOrder = right.childElements().mapNotNull { it.idName() }
        // D2: 오른쪽 열 = [고정 상태 패널(실행 상태)] + [고정 명령 도크] + [스크롤 데크].
        // 계약의 본질은 "고정 영역이 스크롤 밖이고 스크롤 위에 있다"이므로,
        // 목록 전체가 아니라 그 순서 관계를 검증한다.
        val deckScrollIndex = rightOrder.indexOf("opsDeckScroll")
        assertTrue("deck scroll must exist in the right column: $rightOrder", deckScrollIndex >= 0)
        val dockIndex = rightOrder.indexOf("commandDock")
        assertTrue("fixed commandDock must be a direct right-column child: $rightOrder", dockIndex >= 0)
        assertTrue(
            "command dock fixed above the deck scroll: $rightOrder",
            dockIndex < deckScrollIndex,
        )
        for (fixedId in listOf("edgeRuntimePanel", "commandDock")) {
            val index = rightOrder.indexOf(fixedId)
            assertTrue("$fixedId must be a direct child of the right column", index >= 0)
            assertTrue(
                "$fixedId must be fixed above the deck scroll: $rightOrder",
                index < deckScrollIndex,
            )
            assertTrue(
                "$fixedId must never live inside the deck scroll",
                !isDescendantOf(root, "opsDeckScroll", findById(root, fixedId)!!),
            )
        }
        // D2: 현장 확인 카드는 명령 데크 **안**에 있다. 실기(400dpi) 측정에서
        // 고정 영역에 카드를 두면 명령 데크 스크롤이 0px가 되어 조작 공간이
        // 사라졌다. 카드는 스크롤로 접근 가능해야 한다.
        val siteCard = findById(root, "siteConfirmPanel")
        assertTrue("site confirm card must exist", siteCard != null)
        assertTrue(
            "the site card must not squeeze the command deck out of the right column",
            isDescendantOf(root, "opsDeckScroll", siteCard!!),
        )
        assertEquals("observeLeftColumn", parentElement(root, video)?.idName())
        // Adopted flight slimming: the left column keeps the video; the
        // duplicate status strip is gone — concise state lives on the 24dp
        // glance summary, the fixed Edge panel, and the conditional
        // priority row instead.
        assertTrue("duplicate status strip must not return", findById(root, "cockpitStatusStrip") == null)
        val leftOrder = left.childElements().mapNotNull { it.idName() }
        assertTrue(
            "video frame stays in the left column: $leftOrder",
            leftOrder.contains("videoFrame"),
        )
        assertTrue("deck scrolls inside the right column", scroll.tagName == "ScrollView")
        assertEquals("opsDeckScroll", parentElement(root, deck)?.idName())

        // Round70: no permanent reservation for duplicated runtime facts.
        // At 768dp, the on-demand 280dp tray leaves 488dp for video.
        assertEquals("gone", right.getAttribute("android:visibility"))
        assertEquals("@dimen/rc_controls_panel_width", right.getAttribute("android:layout_width"))
        assertTrue("video fills the space reclaimed by the closed tray", (left.weight() ?: 0f) > 0f)
        // The scroll fills the column below the fixed runtime panel.
        assertTrue("deck scroll fills below the runtime panel", (scroll.weight() ?: 0f) > 0f)
    }

    @Test
    fun ai_area_lives_in_the_deck_never_over_the_flight_facts() {
        val root = parseLayout()
        val aiPanel = findById(root, "aiPanel")!!
        val deck = findById(root, "opsDeck")!!
        assertTrue("aiPanel must be inside opsDeck", isDescendantOf(root, "opsDeck", aiPanel) || parentElement(root, aiPanel) == deck)
        assertTrue("AI area must not overlay the video stage", !isDescendantOf(root, "videoStage", aiPanel))
        assertTrue("AI area must not overlay the video frame", !isDescendantOf(root, "videoFrame", aiPanel))
        assertTrue("AI area must not sit in the status side panel", !isDescendantOf(root, "rightStatusPanel", aiPanel))
        assertTrue("AI area must not cover the telemetry band", !isDescendantOf(root, "telemetryStrip", aiPanel))
        for (id in listOf("tvAiState", "tvAiCandidates", "tvAiOverlayNote", "tvAiMapNote")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue("$id must live under opsDeck", isDescendantOf(root, "opsDeck", view!!))
        }
    }

    @Test
    fun command_dock_gone_by_default_with_on_demand_groups_and_result() {
        val root = parseLayout()
        val dock = findById(root, "commandDock")
        assertTrue("commandDock must exist", dock != null)
        assertEquals("opsRightColumn", parentElement(root, dock!!)?.idName())
        // Round49: physical-controller duplicates left the default screen.
        // The dock is gone until a menu assist entry opens a bounded dialog.
        assertEquals("dock hidden by default", "gone", dock.getAttribute("android:visibility"))
        // The request result view still exists under the dock: dialogs
        // reparent it on demand instead of silently writing it hidden.
        val result = findById(root, "tvCommandResult")
        assertTrue("tvCommandResult must exist", result != null)
        assertTrue(
            "tvCommandResult must live in the dock",
            isDescendantOf(root, "commandDock", result!!),
        )
        assertTrue(
            "tvCommandResult must never live inside the deck scroll",
            !isDescendantOf(root, "opsDeckScroll", result),
        )
        for (id in listOf("btnTakeoff", "btnLand", "btnRth")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue(
                "$id must live in the dock flightGroup (same buttons, same gates)",
                isDescendantOf(root, "flightGroup", view!!),
            )
        }
        for (id in listOf("btnRecStart", "btnRecStop")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue(
                "$id must live in the dock recGroup",
                isDescendantOf(root, "recGroup", view!!),
            )
        }
        for (id in listOf("btnZoomIn", "btnZoomOut")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue(
                "$id must live in the dock zoomGroup",
                isDescendantOf(root, "zoomGroup", view!!),
            )
        }
        for (id in listOf("btnGimbalUp", "btnGimbalDown")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue(
                "$id must live in the dock gimbalGroup",
                isDescendantOf(root, "gimbalGroup", view!!),
            )
        }
        for (groupId in listOf("flightGroup", "recGroup", "zoomGroup", "gimbalGroup")) {
            val group = findById(root, groupId)
            assertTrue("$groupId must exist", group != null)
            assertEquals(
                "$groupId must be hidden until its assist entry opens it",
                "gone",
                group!!.getAttribute("android:visibility"),
            )
            assertTrue(
                "$groupId must never live inside the deck scroll",
                !isDescendantOf(root, "opsDeckScroll", group),
            )
        }
        // Assist entries live in the header menu (runtime), not as fixed
        // dock buttons: no permanent duplicates of physical controls.
        for (id in listOf("btnOpenRec", "btnOpenGimbal", "btnOpenFlight")) {
            assertTrue("$id must not exist as a fixed button", findById(root, id) == null)
        }
        assertTrue(
            "command dock must not overlay the video stage",
            !isDescendantOf(root, "videoStage", dock),
        )
        assertTrue(
            "command dock must not overlay the video frame",
            !isDescendantOf(root, "videoFrame", dock),
        )
        assertTrue(
            "command dock must not cover the telemetry band",
            !isDescendantOf(root, "telemetryStrip", dock),
        )
    }

    @Test
    fun hidden_dock_costs_no_fixed_space_and_scroll_keeps_room() {
        val root = parseLayout()
        val dock = findById(root, "commandDock")!!
        assertEquals("dock gone by default", "gone", dock.getAttribute("android:visibility"))
        // Static lower bound from the resource values (not a render
        // measurement): only visible fixed children count. The dock and all
        // command groups are gone, so the default fixed cost is zero — the
        // regression pin against re-growing a full fixed stack that eats
        // the AI scroll at 464dp viewports.
        var lower = 0
        val dockGone = dock.getAttribute("android:visibility") == "gone"
        for (child in dock.childElements()) {
            if (dockGone || child.getAttribute("android:visibility") == "gone") continue
            when (child.tagName) {
                "LinearLayout" -> lower += child.childElements()
                    .filter { it.tagName == "Button" }
                    .map { it.heightDp() ?: 0 }
                    .maxOrNull() ?: 0
                "TextView" -> lower += child.minHeightDp() ?: 0
                else -> throw AssertionError("unexpected fixed dock child: ${child.tagName}")
            }
        }
        assertEquals("hidden dock contributes no fixed height", 0, lower)
        val scroll = findById(root, "opsDeckScroll")!!
        assertTrue("deck scroll keeps a nonzero weight with the dock hidden", (scroll.weight() ?: 0f) > 0f)
    }

    @Test
    fun response_ui_lives_in_the_ai_area_never_over_video_or_telemetry() {
        val root = parseLayout()
        for (id in listOf(
            "aiCandidateList", "responsePanel", "tvResponseCandidate",
            "btnRespondConfirm", "btnRespondHold", "btnRespondEscalate",
            "etResponseNote", "tvResponseResult",
        )) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue("$id must live under aiPanel", isDescendantOf(root, "aiPanel", view!!))
            assertTrue("$id must not overlay the video stage", !isDescendantOf(root, "videoStage", view))
            assertTrue("$id must not overlay the video frame", !isDescendantOf(root, "videoFrame", view))
            assertTrue("$id must not cover the telemetry band", !isDescendantOf(root, "telemetryStrip", view))
            assertTrue("$id must not sit in the status side panel", !isDescendantOf(root, "rightStatusPanel", view))
        }
        assertEquals(
            "response panel hidden until a candidate is selected",
            "gone",
            findById(root, "responsePanel")!!.getAttribute("android:visibility"),
        )
        assertEquals(
            "operator note capped at 280 chars",
            "280",
            findById(root, "etResponseNote")!!.getAttribute("android:maxLength"),
        )
    }

    @Test
    fun deck_command_buttons_keep_the_glove_minimum() {
        val root = parseLayout()
        val dock = findById(root, "commandDock")!!
        val dockButtons = mutableListOf<Element>()
        fun collect(element: Element) {
            if (element.tagName == "Button") dockButtons.add(element)
            element.childElements().forEach { collect(it) }
        }
        collect(dock)
        // Round49: the dock holds only on-demand groups (gone by default)
        // plus the shared result view. Every original command button keeps
        // its id, listener and 48dp minimum inside its group.
        val expectedDock = setOf(
            "btnPhoto", "btnTakeoff", "btnLand", "btnRth",
            "btnGimbalUp", "btnGimbalDown",
            "btnZoomIn", "btnZoomOut",
            "btnRecStart", "btnRecStop",
        )
        assertTrue(
            "dock must keep every flight/camera command button",
            dockButtons.mapNotNull { it.idName() }.containsAll(expectedDock),
        )
        dockButtons.filter { it.idName() in expectedDock }.forEach { button ->
            assertEquals(
                "${button.idName()} must keep the 48dp glove minimum",
                "48dp",
                button.getAttribute("android:layout_height"),
            )
        }
        val deck = findById(root, "opsDeck")!!
        val deckButtons = mutableListOf<Element>()
        fun collectDeck(element: Element) {
            if (element.tagName == "Button") deckButtons.add(element)
            element.childElements().forEach { collectDeck(it) }
        }
        collectDeck(deck)
        val expectedDeck = setOf("btnViewVideo", "btnViewMap")
        assertTrue(
            "deck must keep the view toggle buttons",
            deckButtons.mapNotNull { it.idName() }.containsAll(expectedDeck),
        )
        deckButtons.filter { it.idName() in expectedDeck }.forEach { button ->
            assertEquals(
                "${button.idName()} must keep the 48dp glove minimum",
                "48dp",
                button.getAttribute("android:layout_height"),
            )
        }
        // The new response action row is equally glove-usable.
        for (id in listOf("btnRespondConfirm", "btnRespondHold", "btnRespondEscalate")) {
            val button = findById(root, id)!!
            val height = button.heightDp() ?: button.minHeightDp() ?: 0
            assertTrue("$id must be at least 48dp tall", height >= 48)
        }
        val note = findById(root, "etResponseNote")!!
        assertTrue("note input must be at least 48dp tall", (note.minHeightDp() ?: 0) >= 48)
        val result = findById(root, "tvResponseResult")!!
        assertTrue("response result line must be at least 48dp tall", (result.minHeightDp() ?: 0) >= 48)
    }

    @Test
    fun no_permanent_warning_card_occupies_the_screen() {
        val root = parseLayout()
        val priority = findById(root, "cockpitPriorityRow")!!
        assertEquals(
            "priority overlay stays hidden until a real problem exists",
            "gone",
            priority.getAttribute("android:visibility"),
        )
        // Full width directly under the telemetry band: it cannot be
        // missed, and it cannot cover the video.
        assertEquals("pageObserve", parentElement(root, priority)?.idName())
        assertTrue("AI response UI must not cover the priority row", !isDescendantOf(root, "cockpitPriorityRow", findById(root, "responsePanel")!!))
    }

    @Test
    fun map_placeholder_is_a_sibling_not_an_overlay_on_facts() {
        val root = parseLayout()
        val mapPlaceholder = findById(root, "tvMapPlaceholder")
        assertTrue("tvMapPlaceholder must exist", mapPlaceholder != null)
        assertTrue(
            "map placeholder must live inside the video stage (the selected view), not over facts",
            isDescendantOf(root, "videoStage", mapPlaceholder!!),
        )
        assertEquals("gone", mapPlaceholder.getAttribute("android:visibility"))
        assertEquals("bottom|end", mapPlaceholder.getAttribute("android:layout_gravity"))
        assertEquals("@dimen/rc_map_preview_width", mapPlaceholder.getAttribute("android:layout_width"))
        assertEquals("@dimen/rc_map_preview_height", mapPlaceholder.getAttribute("android:layout_height"))
        assertEquals("true", mapPlaceholder.getAttribute("android:clickable"))
        val status = findById(root, "tvMapStatus")
        assertTrue("tvMapStatus must exist", status != null)
        assertTrue("map status must live under opsDeck", isDescendantOf(root, "opsDeck", status!!))
    }

    @Test
    fun missing_map_keeps_video_surface() {
        // Round50: selecting MAP without a source reports the miss as an
        // overlay status — the video surface is never cleared.
        val root = parseLayout()
        val surface = findById(root, "surfaceVideo")
        assertTrue("surfaceVideo must exist", surface != null)
        assertTrue(
            "surfaceVideo must not default to gone (missing map keeps video)",
            surface!!.getAttribute("android:visibility") != "gone",
        )
        val stage = findById(root, "videoStage")!!
        val order = stage.childElements().mapNotNull { it.idName() }
        val surfaceIndex = order.indexOf("surfaceVideo")
        val placeholderIndex = order.indexOf("tvMapPlaceholder")
        assertTrue("video stage must contain both surface and placeholder: $order", surfaceIndex >= 0 && placeholderIndex >= 0)
        assertTrue(
            "map placeholder must paint after (over) the surface, not replace it: $order",
            placeholderIndex > surfaceIndex,
        )
    }

    @Test
    fun map_view_binding_keeps_video_and_enables_the_opposite_entry() {
        // Source wiring guard only, not an Android click/render test. The
        // missing-provider path must never blank the SurfaceView, and the
        // map entry must be available while video is selected.
        val source = findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()
        val binding = source.substringAfter("private fun refreshObserveView() {")
            .substringBefore("private fun refreshOpsDeck()")
        assertTrue(binding.contains("surfaceVideo.visibility = View.VISIBLE"))
        assertTrue(binding.contains("setButtonEnabled(R.id.btnViewVideo, map)"))
        assertTrue(binding.contains("setButtonEnabled(R.id.btnViewMap, !map)"))
    }

    @Test
    fun telemetry_strip_is_compact_single_line_readout() {
        // Round50: five label/value facts on one wrapping-free line each,
        // 32dp read-only band — labels stay >=12sp, values keep ids and
        // single-line truncation with full detail in content descriptions.
        val root = parseLayout()
        val strip = findById(root, "telemetryStrip")!!
        assertEquals(
            "strip uses the compact read-only height, never the 48dp touch band",
            "@dimen/rc_telemetry_min_height",
            strip.getAttribute("android:minHeight"),
        )
        assertEquals("compact band resolves to 32dp", 32, strip.minHeightDpResolved())
        val groups = strip.childElements().filter { it.tagName == "LinearLayout" }
        assertEquals("strip keeps exactly the five essential facts", 5, groups.size)
        for (group in groups) {
            assertEquals(
                "fact label and value share one line: ${group.childElements().map { it.tagName }}",
                "horizontal",
                group.getAttribute("android:orientation"),
            )
            val label = group.childElements().firstOrNull { it.tagName == "TextView" && it.idName() == null }!!
            assertTrue(
                "compact labels stay >= 12sp: ${label.getAttribute("android:textSize")}",
                (label.getAttribute("android:textSize").removeSuffix("sp").toIntOrNull() ?: 0) >= 12,
            )
        }
        for (id in listOf("tvTeleAuthorityValue", "tvTeleModeValue", "tvTeleBatteryValue", "tvTeleLinksValue", "tvTelePositionValue")) {
            val value = findById(root, id)
            assertTrue("$id must exist", value != null)
            assertEquals("$id stays single-line", "1", value!!.getAttribute("android:maxLines"))
            assertTrue("$id keeps ellipsis, never silent clipping", value.getAttribute("android:ellipsize").isNotEmpty())
        }
    }

    @Test
    fun new_deck_strings_all_exist_in_korean() {
        val root = parseLayout()
        val deck = findById(root, "opsDeck")!!
        val names = stringNames()
        val refs = mutableSetOf<String>()
        fun collect(element: Element) {
            element.getAttribute("android:text").takeIf { it.startsWith("@string/") }
                ?.removePrefix("@string/")?.let { refs.add(it) }
            element.getAttribute("android:hint").takeIf { it.startsWith("@string/") }
                ?.removePrefix("@string/")?.let { refs.add(it) }
            element.childElements().forEach { collect(it) }
        }
        collect(deck)
        collect(findById(root, "commandDock")!!)
        // The overlay honesty note reuses the existing string.
        refs.add(
            findById(root, "tvMapPlaceholder")!!
                .getAttribute("android:text").removePrefix("@string/"),
        )
        assertTrue("deck must reference strings (found none)", refs.isNotEmpty())
        refs.forEach { name ->
            assertTrue("@string/$name must exist in strings.xml", names.contains(name))
        }
    }
}
