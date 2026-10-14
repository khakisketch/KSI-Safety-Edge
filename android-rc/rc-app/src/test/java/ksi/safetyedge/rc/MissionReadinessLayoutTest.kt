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
 *   activity_main.xml (sync row -> sync chip -> prepare/apply row ->
 *   local-record start -> start note -> additional-check/reconcile row ->
 *   observe session row); MainActivity performs no runtime remove/add
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

    @Test
    fun readiness_view_is_static_and_directly_before_mission() {
        val root = parseLayout()
        val readiness = findById(root, "tvMissionReadiness")
        assertTrue("tvMissionReadiness must be declared in XML", readiness != null)
        assertEquals("13sp", readiness!!.textSize())
        val mission = findById(root, "tvMission")!!
        val parent = parentElement(root, mission)!!
        val ids = parent.childElements().map { it.idName() }
        val readinessIndex = ids.indexOf("tvMissionReadiness")
        val missionIndex = ids.indexOf("tvMission")
        assertTrue("tvMissionReadiness must precede tvMission", readinessIndex >= 0 && readinessIndex < missionIndex)
        assertEquals(
            "readiness sits directly before the mission detail",
            missionIndex - 1,
            readinessIndex,
        )
    }

    @Test
    fun mission_actions_follow_the_final_order_statically() {
        val root = parseLayout()
        val syncButton = findById(root, "btnSyncSites")!!
        val panel = parentElement(root, parentElement(root, syncButton)!!)!!
        // panel children, described by id or by text/child signature.
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
        val prepareRow = order.indexOf("rowPrepare")
        val startFlight = order.indexOf("btnStartFlight")
        val startNote = order.indexOf("tvStartFlightNote")
        val additionalRow = order.indexOf("rowAdditionalCheck")
        val startStopRow = order.indexOf("rowStartStop")
        val boundary = order.indexOf("mission_session_boundary")
        listOf(
            "rowSyncButtons" to syncRow,
            "tvMissionSyncResult" to syncChip,
            "rowPrepare" to prepareRow,
            "btnStartFlight" to startFlight,
            "tvStartFlightNote" to startNote,
            "rowAdditionalCheck" to additionalRow,
            "rowStartStop" to startStopRow,
            "mission_session_boundary" to boundary,
        ).forEach { (name, index) ->
            assertTrue("$name must exist in the actions panel (order=$order)", index >= 0)
        }
        assertTrue("sync chip directly after sync row: $order", syncChip == syncRow + 1)
        assertTrue("prepare row after sync chip: $order", prepareRow > syncChip)
        assertTrue("local-record start after prepare row: $order", startFlight > prepareRow)
        assertTrue("start note directly after start button: $order", startNote == startFlight + 1)
        assertTrue("additional-check row directly after start note: $order", additionalRow == startNote + 1)
        assertTrue("observe session row after additional-check row: $order", startStopRow > additionalRow)
        assertTrue("boundary note last: $order", boundary > startStopRow)

        val prepare = findById(root, "rowPrepare")!!
        val prepareButtons = prepare.childElements().mapNotNull { it.idName() }
        assertTrue("prepare row keeps btnPrepare", prepareButtons.contains("btnPrepare"))
        assertTrue("prepare row keeps btnApply", prepareButtons.contains("btnApply"))
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
