package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * G17/G18 static regression pins (host-only, no Robolectric):
 *
 * - G17: the mission action order is declared statically in
 *   activity_main.xml. Full readiness facts plus support actions (sync row ->
 *   sync chip -> additional-check/reconcile row -> observe session row) live
 *   in the left bounded scroll; the fixed command dock holds only the concise
 *   next-action line (max 2, derived by MissionNextAction) plus the 3 command
 *   rows (prepare/apply row -> local-record start) with the explanation
 *   scroll below. MainActivity performs no runtime remove/add
 *   reordering. textSize roles (12/13/14sp) are preserved.
 * - G18: button lookups are guarded (bind-once cache + log + early return,
 *   no unguarded findViewById<Button> dereference).
 */
class MissionReadinessLayoutTest {
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

    private fun Element.textRef(): String? =
        getAttribute("android:text").takeIf { it.isNotEmpty() }

    private fun Element.textSize(): String? =
        getAttribute("android:textSize").takeIf { it.isNotEmpty() }

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
    fun full_readiness_in_support_scroll_concise_next_in_dock() {
        val root = parseLayout()
        // Full facts keep all lines: bounded left support scroll, 13sp kept.
        val readiness = findById(root, "tvMissionReadiness")
        assertTrue("tvMissionReadiness must be declared in XML", readiness != null)
        assertEquals("13sp", readiness!!.textSize())
        assertTrue(
            "full readiness facts must live in the bounded left scroll",
            isDescendantOf(root, "missionContentScroll", readiness),
        )
        assertTrue(
            "full readiness facts must never sit in the fixed dock",
            !isDescendantOf(root, "missionCommandDock", readiness),
        )
        // The dock's persistent line is the concise derived next action —
        // max 2 lines, never the full-facts heading.
        val next = findById(root, "tvMissionNextAction")
        assertTrue("tvMissionNextAction must be declared in XML", next != null)
        assertTrue(
            "concise next action must live in the fixed command dock",
            isDescendantOf(root, "missionCommandDock", next!!),
        )
        assertTrue(
            "concise next action must never live inside a scroll region",
            !isDescendantOf(root, "missionContentScroll", next) &&
                !isDescendantOf(root, "missionDockExplainScroll", next),
        )
        assertTrue(
            "next action capped at 2 lines",
            next.getAttribute("android:maxLines") == "2",
        )
        val dock = findById(root, "missionCommandDock")!!
        fun describe(element: Element): String {
            element.idName()?.let { return it }
            return element.textRef()?.substringAfter("/") ?: element.tagName
        }
        val order = dock.childElements().map { describe(it) }
        val nextIndex = order.indexOf("tvMissionNextAction")
        val prepareIndex = order.indexOf("rowPrepare")
        val startIndex = order.indexOf("btnStartFlight")
        assertTrue("next action before prepare row in dock: $order", nextIndex >= 0 && nextIndex < prepareIndex)
        assertTrue("prepare row before local-record start in dock: $order", prepareIndex < startIndex)
    }

    @Test
    fun mission_actions_follow_the_final_order_statically() {
        val root = parseLayout()
        // Support actions live in the left bounded scroll (moved, not copied).
        val syncButton = findById(root, "btnSyncSites")!!
        assertTrue(
            "sync row must live in the bounded left scroll",
            isDescendantOf(root, "missionContentScroll", syncButton),
        )
        val panel = parentElement(root, parentElement(root, syncButton)!!)!!
        fun describe(element: Element): String {
            element.idName()?.let { return it }
            if (element.tagName == "LinearLayout") {
                val kids = element.childElements().mapNotNull { it.idName() }.toSet()
                if (kids.contains("btnStart")) return "rowStartStop"
            }
            return element.textRef()?.substringAfter("/") ?: element.tagName
        }
        val order = panel.childElements().map { describe(it) }
        val syncRow = order.indexOf("rowSyncButtons")
        val syncChip = order.indexOf("tvMissionSyncResult")
        val additionalRow = order.indexOf("rowAdditionalCheck")
        val startStopRow = order.indexOf("rowStartStop")
        listOf(
            "rowSyncButtons" to syncRow,
            "tvMissionSyncResult" to syncChip,
            "rowAdditionalCheck" to additionalRow,
            "rowStartStop" to startStopRow,
        ).forEach { (name, index) ->
            assertTrue("$name must exist in the left scroll panel (order=$order)", index >= 0)
        }
        assertTrue("sync chip directly after sync row: $order", syncChip == syncRow + 1)
        assertTrue("additional-check row after sync chip: $order", additionalRow > syncChip)
        assertTrue("observe session row after additional-check row: $order", startStopRow > additionalRow)

        // The 3 command rows live in the fixed dock, in sequence.
        val dock = findById(root, "missionCommandDock")!!
        val dockOrder = dock.childElements().map { describe(it) }
        val prepareRow = dockOrder.indexOf("rowPrepare")
        val startFlight = dockOrder.indexOf("btnStartFlight")
        val explainScroll = dockOrder.indexOf("missionDockExplainScroll")
        val boundaryInExplain = run {
            val scroll = findById(root, "missionDockExplainScroll")!!
            val texts = mutableListOf<String>()
            fun collect(element: Element) {
                element.textRef()?.let { texts.add(it.substringAfter("/")) }
                element.childElements().forEach { collect(it) }
            }
            collect(scroll)
            texts.contains("mission_session_boundary")
        }
        assertTrue("prepare row in dock: $dockOrder", prepareRow >= 0)
        assertTrue("local-record start in dock after prepare: $dockOrder", startFlight > prepareRow)
        assertTrue("explanation scroll below the rows: $dockOrder", explainScroll > startFlight)
        assertTrue("start note lives below the rows in the explain scroll",
            isDescendantOf(root, "missionDockExplainScroll", findById(root, "tvStartFlightNote")!!))
        assertTrue("boundary note last, in the explain scroll", boundaryInExplain)

        val prepare = findById(root, "rowPrepare")!!
        val prepareButtons = prepare.childElements().mapNotNull { it.idName() }
        assertTrue("prepare row keeps btnPrepare", prepareButtons.contains("btnPrepare"))
        assertTrue("prepare row keeps btnApply", prepareButtons.contains("btnApply"))
        // The 3 rows are sequential (stacked), not side-by-side.
        assertEquals("vertical", prepare.getAttribute("android:orientation"))
        assertTrue(
            "btnReconcile lives in the additional-check row, not the prepare row",
            !prepareButtons.contains("btnReconcile"),
        )
        val additional = findById(root, "rowAdditionalCheck")!!
        val additionalKids = additional.childElements()
        assertTrue(
            "additional-check row keeps btnReconcile",
            additionalKids.any { it.idName() == "btnReconcile" },
        )
        val label = additionalKids.firstOrNull { it.tagName == "TextView" }
        assertTrue("additional-check row keeps its label", label?.textRef() == "@string/mission_additional_check")
        assertEquals("12sp", label!!.textSize())

        assertEquals("12sp", findById(root, "tvMissionSyncResult")!!.textSize())
        assertEquals("14sp", findById(root, "btnStartFlight")!!.textSize())
        assertEquals("13sp", findById(root, "tvStartFlightNote")!!.textSize())
        assertEquals("13sp", findById(root, "btnReconcile")!!.textSize())
    }

    @Test
    fun no_runtime_reordering_or_unguarded_button_lookup() {
        val source = findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()
        val installer = source.substringAfter("private fun installMissionReadinessUi()")
            .substringBefore("\n    private fun ")
        assertTrue(
            "installMissionReadinessUi must not remove views at runtime",
            !installer.contains("removeView("),
        )
        assertTrue(
            "installMissionReadinessUi must not add views at runtime",
            !installer.contains("addView("),
        )
        assertTrue(
            "installMissionReadinessUi must log missing static views",
            installer.contains("Log.w"),
        )
        val onBlock = source.substringAfter("private fun on(id: Int")
            .substringBefore("\n    /** G18")
        assertTrue(
            "on() must use the guarded lookup",
            onBlock.contains("buttonOrNull(id)"),
        )
        assertTrue(
            "buttons are bound once and reused",
            source.contains("private fun bindStaticButtons()") &&
                source.contains("navButtons[nav]") &&
                source.contains("btnConnect ?: buttonOrNull"),
        )
        assertTrue(
            "guarded enable helper exists",
            source.contains("private fun setButtonEnabled("),
        )
    }
}
