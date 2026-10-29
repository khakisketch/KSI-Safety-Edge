package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Round47 task-first cockpit layout regression (host-only XML parse — NOT
 * an Android render claim): the persistent observe page is organized around
 * the operator task (video + telemetry/authority/link facts + current
 * action), not six equal module cells. A compact 48dp observation row keeps
 * the concise Edge state + record/sync support facts with details and
 * video-recovery entries; the bottom-fixed task bar pins the permanent
 * control gate plus scoped actions. Scoped buttons name their
 * recipient (Edge vs KSI local record vs DJI-separated flight commands),
 * and every control keeps the 48dp glove minimum with dimensions sourced
 * from dimens.xml.
 */
class CockpitTaskLayoutTest {

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

    private fun stringValues(): Map<String, String> {
        val file = findModuleFile("src/main/res/values/strings.xml")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val root = builder.parse(file).documentElement
        val out = mutableMapOf<String, String>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == "string") {
                out[node.getAttribute("name")] = node.textContent.trim()
            }
        }
        return out
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

    private fun Element.heightDpResolved(): Int? {
        val raw = getAttribute("android:layout_height")
        if (raw.endsWith("dp")) return raw.removeSuffix("dp").toIntOrNull()
        if (raw.startsWith("@dimen/")) return dimenDp(raw.removePrefix("@dimen/"))
        return null
    }

    private fun Element.minHeightDpResolved(): Int? {
        val raw = getAttribute("android:minHeight")
        if (raw.endsWith("dp")) return raw.removeSuffix("dp").toIntOrNull()
        if (raw.startsWith("@dimen/")) return dimenDp(raw.removePrefix("@dimen/"))
        return null
    }

    private fun Element.maxLines(): Int? =
        getAttribute("android:maxLines").takeIf { it.isNotEmpty() }?.toIntOrNull()

    private fun Element.textName(): String? =
        getAttribute("android:text").takeIf { it.startsWith("@string/") }?.removePrefix("@string/")

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
    fun slim_flight_removes_the_duplicate_status_strip() {
        val root = parseLayout()
        // Adopted flight slimming: the concise Edge/support/problem lines
        // lived here as a third copy of the fixed Edge panel and the 24dp
        // supervision summary. The strip, its three text views, and the old
        // bar toggle (which duplicated the details label) are gone.
        for (id in listOf("cockpitStatusStrip", "tvTaskEdgeState", "tvTaskSupport", "tvTaskAlert", "btnTogglePanel")) {
            assertTrue("$id must not return as a duplicate surface", findById(root, id) == null)
        }
        // Concise state still has its homes: the 24dp glance summary under
        // the HUD and the fixed Edge panel beside the deck — neither inside
        // the scrolling detail, never over the video.
        val summary = findById(root, "supervisionCompactRow")!!
        assertTrue("glance summary covers the video column", !isDescendantOf(root, "videoStage", summary))
        val panel = findById(root, "edgeRuntimePanel")!!
        assertTrue("fixed panel is no video overlay", !isDescendantOf(root, "videoStage", panel))
        // The two operable entries moved to the bottom task bar, keeping
        // 48dp and their floors.
        val bar = findById(root, "cockpitRecordActionRow")!!
        val details = findById(root, "btnTaskDetails")!!
        assertTrue("details entry lives in the task bar", isDescendantOf(root, "cockpitRecordActionRow", details))
        assertTrue("details entry keeps 48dp", (details.heightDpResolved() ?: 0) >= 48)
        assertEquals("details also has a real width floor", "@dimen/rc_touch_min", details.getAttribute("android:minWidth"))
        val recover = findById(root, "btnTaskVideoRecover")!!
        assertTrue("video recovery lives in the task bar", isDescendantOf(root, "cockpitRecordActionRow", recover))
        assertTrue("recovery action keeps 48dp", (recover.heightDpResolved() ?: 0) >= 48)
        assertEquals("recovery also has a real width floor", "@dimen/rc_touch_min", recover.getAttribute("android:minWidth"))
        assertEquals("recovery hidden until video disconnects", "gone", recover.getAttribute("android:visibility"))
        assertTrue("task bar never overlays the video", !isDescendantOf(root, "videoStage", bar))
    }

    @Test
    fun task_bar_keeps_scoped_actions_without_a_permanent_gate() {
        val root = parseLayout()
        val strings = stringValues()
        val bar = findById(root, "cockpitRecordActionRow")!!
        assertEquals("pageObserve", parentElement(root, bar)?.idName())
        assertTrue(
            "task bar keeps the fixed minimum via dimens",
            (bar.minHeightDpResolved() ?: 0) >= 48,
        )
        // Round49: physical-controller duplicates were removed from the
        // default surfaces. No permanent gate line may live in the bar.
        assertTrue("no permanent gate view in the task bar", findById(root, "tvControlGate") == null)
        val reason = findById(root, "tvControlGateReason")!!
        assertTrue("request reason lives behind the details disclosure", isDescendantOf(root, "cockpitDetailsScroll", reason))
        assertTrue("request reason shown in full (no ellipsized cap)", reason.maxLines() == null)
        val detailText = strings[reason.textName()] ?: ""
        assertTrue("request reason is app-specific: $detailText", detailText.contains("앱 비행 요청 불가"))
        assertTrue("request reason names the missing authority: $detailText", detailText.contains("제어 주체 미확인"))
        assertTrue("request reason disclaims physical RC: $detailText", detailText.contains("물리 조종기"))
        assertFalse("request reason never merges ACTIVE approval: $detailText", detailText.contains("ACTIVE"))
        val prepare = findById(root, "btnTaskPrepare")!!
        assertEquals("준비 열기", strings[prepare.textName()])
        assertTrue("prepare entry keeps 48dp", (prepare.heightDpResolved() ?: 0) >= 48)
        assertEquals("prepare also has a real width floor", "@dimen/rc_touch_min", prepare.getAttribute("android:minWidth"))
        // Adopted: one 조작·상세 entry — the bar's old toggle duplicated its
        // label and is gone; the surviving entry toggles the panel.
        val toggle = findById(root, "btnTaskDetails")!!
        assertEquals("비행 조작", strings[toggle.textName()])
        assertTrue("details entry lives in the task bar", isDescendantOf(root, "cockpitRecordActionRow", toggle))
        assertTrue("details toggle keeps 48dp", (toggle.heightDpResolved() ?: 0) >= 48)
        assertEquals("toggle also has a real width floor", "@dimen/rc_touch_min", toggle.getAttribute("android:minWidth"))
    }

    @Test
    fun scope_buttons_name_recipient_not_merged_authority() {
        val strings = stringValues()
        assertTrue("prepare names Edge: ${strings["btn_prepare"]}", strings["btn_prepare"]!!.contains("Edge"))
        assertTrue("apply names Edge: ${strings["btn_apply"]}", strings["btn_apply"]!!.contains("Edge"))
        assertTrue(
            "record start names KSI scope: ${strings["btn_start_flight_recording"]}",
            strings["btn_start_flight_recording"]!!.contains("KSI"),
        )
        assertTrue(
            "session start names Edge scope: ${strings["btn_mission_start"]}",
            strings["btn_mission_start"]!!.contains("Edge"),
        )
        assertTrue(
            "session stop names Edge scope: ${strings["btn_mission_stop"]}",
            strings["btn_mission_stop"]!!.contains("Edge"),
        )
        // Flight commands stay separate (DJI authority scope, still gated —
        // no fabricated handover text on the task surfaces).
        for (name in listOf("cmd_takeoff", "cmd_land", "cmd_rth")) {
            assertTrue("$name must exist", strings.containsKey(name))
            assertTrue("$name must not pose as Edge work", !strings[name]!!.contains("Edge"))
        }
    }

    @Test
    fun edge_technical_details_moved_to_scrolling_detail_no_fixed_toggle() {
        val root = parseLayout()
        // The fixed panel keeps concise state + zero-risk safety only; the
        // technical report cannot grow it (round45 deck-squeeze failure).
        for (id in listOf("tvEdgeRuntimeDetail", "tvSessionResult", "tvCommitState")) {
            val view = findById(root, id)!!
            assertTrue(
                "$id must live in the scrolling detail",
                isDescendantOf(root, "cockpitDetailsScroll", view),
            )
            assertTrue(
                "$id must not grow the fixed panel",
                !isDescendantOf(root, "edgeRuntimePanel", view),
            )
            assertTrue(
                "$id must not truncate the full report",
                view.getAttribute("android:maxLines").isEmpty() &&
                    view.getAttribute("android:ellipsize").isEmpty(),
            )
        }
        assertTrue(
            "no fixed-panel disclosure toggle may re-grow the panel",
            findById(root, "btnEdgeRuntimeDetail") == null,
        )
        val panel = findById(root, "edgeRuntimePanel")!!
        val panelTexts = panel.childElements().filter { it.tagName == "TextView" }.mapNotNull { it.idName() }.toSet()
        assertEquals(
            "fixed panel keeps concise state + zero-risk note only",
            setOf("tvEdgeRuntimeState", "tvEdgeRuntimeZeroNote"),
            panelTexts,
        )
    }

    @Test
    fun task_views_source_dimensions_from_dimens() {
        val root = parseLayout()
        assertEquals(48, dimenDp("rc_task_row_min_height"))
        assertEquals(24, dimenDp("rc_supervision_summary_height"))
        assertTrue(dimenDp("rc_task_bar_min_height") >= 48)
        assertEquals(4, dimenDp("rc_task_inner_gap"))
        assertEquals(2, dimenDp("rc_task_text_gap"))
        for (id in listOf("btnTaskDetails", "btnTaskVideoRecover", "btnTaskPrepare")) {
            val view = findById(root, id)!!
            assertTrue(
                "$id height must source from dimens (got '${view.getAttribute("android:layout_height")}')",
                view.getAttribute("android:layout_height").startsWith("@dimen/"),
            )
        }
        val summary = findById(root, "supervisionCompactRow")!!
        assertTrue(
            "glance summary minHeight must source from dimens",
            summary.getAttribute("android:minHeight").startsWith("@dimen/"),
        )
        // New task-view margins/padding source from dimens, not literals.
        for ((id, attr) in listOf(
            "btnTaskVideoRecover" to "android:layout_marginEnd",
            "btnTaskPrepare" to "android:layout_marginEnd",
            "tvControlGateReason" to "android:padding",
            "tvEdgeRuntimeDetail" to "android:layout_marginTop",
        )) {
            val raw = findById(root, id)!!.getAttribute(attr)
            assertTrue(
                "$id $attr must source from dimens (got '$raw')",
                raw.startsWith("@dimen/"),
            )
        }
    }
}
