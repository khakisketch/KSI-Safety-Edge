package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Adopted UI direction regression pins (host-only XML/source parse — NOT
 * an Android render claim): 4 destinations, site-first mission, slim
 * flight, event-kind-first records, settings wide-video entry.
 */
class AdoptedUiLayoutTest {

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

    private fun mainSource(): String =
        findModuleFile("src/main/java/ksi/safetyedge/rc/MainActivity.kt").readText()

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
    fun nav_targets_are_exactly_four_destinations() {
        val source = mainSource()
        val block = source.substringAfter("private val navTargets = listOf(")
            .substringBefore(")")
        for (id in listOf("btnNavObserve", "btnNavMission", "btnNavReview", "btnNavSettings")) {
            assertTrue("$id must be a destination", block.contains(id))
        }
        assertFalse("evidence is not a destination", block.contains("btnNavEvidence"))
        // Evidence stays reachable from records only, with an explicit back
        // entry — never a rail destination.
        assertTrue("evidence back entry exists", mainSource().contains("R.id.btnEvidenceBack"))
        val root = parseLayout()
        assertTrue("evidence page kept as records sub-view", findById(root, "pageEvidence") != null)
        val back = findById(root, "btnEvidenceBack")!!
        assertTrue("back entry lives in the evidence page", isDescendantOf(root, "pageEvidence", back))
    }

    @Test
    fun flight_details_are_on_demand_without_losing_required_glance_axes() {
        val root = parseLayout()
        assertEquals("gone", findById(root, "opsRightColumn")!!.getAttribute("android:visibility"))
        assertEquals("gone", findById(root, "rightStatusPanel")!!.getAttribute("android:visibility"))
        for (id in listOf("tvCompactAi", "tvCompactCamera", "tvCompactPreserve", "tvCompactSite")) {
            assertTrue("$id must remain in persistent summary", isDescendantOf(root, "supervisionCompactRow", findById(root, id)!!))
        }
        for (id in listOf("btnTaskDetails", "btnTaskStatus")) {
            assertEquals("@dimen/rc_touch_min", findById(root, id)!!.getAttribute("android:layout_height"))
        }
        val priority = findById(root, "cockpitPriorityRow")!!
        assertEquals("priority text and action share one row", "horizontal", priority.getAttribute("android:orientation"))
        assertEquals(280, dimenDp("rc_controls_panel_width"))
        val source = mainSource()
        assertTrue("operation tray has real toggle", source.contains("private fun setFlightControlsOpen(open: Boolean)"))
        assertTrue("navigation closes trays", source.contains("setFlightPanelOpen(false)"))
    }

    @Test
    fun connection_settings_precede_device_metadata_and_keep_persistent_labels() {
        val root = parseLayout()
        val settings = findById(root, "pageSettings")!!
        val content = settings.childElements().first()
        val orderedIds = content.childElements().mapNotNull { it.idName() }
        assertTrue("connection before operator", orderedIds.indexOf("etCoreUrl") < orderedIds.indexOf("etOperatorName"))
        assertTrue("operator before device", orderedIds.indexOf("etOperatorName") < orderedIds.indexOf("tvDeviceInfo"))
        for (id in listOf("etCoreUrl", "etCoreToken", "etOperatorName", "etOperatorOrg")) {
            assertTrue("$id needs a persistent label", content.childElements().any { it.getAttribute("android:labelFor") == "@id/$id" })
        }
        assertEquals("textPassword", findById(root, "etCoreToken")!!.getAttribute("android:inputType"))
    }

    @Test
    fun mission_is_site_first_without_spinner_duplicates() {
        val root = parseLayout()
        val source = mainSource()
        assertTrue("site spinner removed", findById(root, "spSite") == null)
        assertTrue("bundle spinner removed", findById(root, "spBundle") == null)
        val list = findById(root, "siteCardList")!!
        assertTrue("site cards live in the mission scroll", isDescendantOf(root, "missionContentScroll", list))
        val detail = findById(root, "siteCardDetail")!!
        assertTrue("plan detail lives in the mission scroll", isDescendantOf(root, "missionContentScroll", detail))
        assertEquals("plan detail closed by default", "gone", detail.getAttribute("android:visibility"))
        // No auto-select, no auto-expand: an empty choice stays empty until
        // the operator picks (genuine active session excepted), and nothing
        // reopens the disclosure behind a collapse tap.
        assertFalse("no first-site fallback", source.contains("siteIds.firstOrNull()"))
        // New polygon-first patrol: disclosed unavailable, entry disabled.
        val draw = findById(root, "btnNewPatrolDraw")!!
        assertEquals("draw entry stays disabled", "false", draw.getAttribute("android:enabled"))
        assertTrue("draw entry keeps 48dp", (draw.getAttribute("android:layout_height").removeSuffix("dp").toIntOrNull() ?: 0) >= 48)
        // General flight (no site) is a persistent row OUTSIDE the scroll —
        // task access, not scroll traversal.
        val general = findById(root, "btnGeneralFlight")!!
        assertTrue("general flight outside the mission scroll", !isDescendantOf(root, "missionContentScroll", general))
        assertTrue(
            "general flight in the persistent mission header row",
            isDescendantOf(root, "generalFlightRow", general),
        )
        assertEquals("persistent row outside the scroll", "pageMission", parentElement(root, findById(root, "generalFlightRow")!!)?.idName())
        // Prep dock hidden until a real plan is explicitly selected.
        assertTrue(
            "dock visibility follows explicit plan choice",
            source.contains("missionCommandDock") && source.contains("selectedBundleId != null"),
        )
        // The existing sync/selection facts stay addressable by id.
        assertTrue("site summary line kept", findById(root, "tvMissionSite") != null)
        assertTrue("bundle summary line kept", findById(root, "tvMissionBundle") != null)
    }

    @Test
    fun flight_keeps_slim_left_status_and_readonly_summary() {
        val root = parseLayout()
        for (id in listOf(
            "tvTeleAuthorityValue", "tvTeleModeValue", "tvTeleBatteryValue",
            "tvTeleLinksValue", "tvTelePositionValue",
        )) {
            assertTrue("slim status keeps $id", findById(root, id) != null)
        }
        val summary = findById(root, "supervisionCompactRow")!!
        assertEquals("summary is one slim line", 24, dimenDp("rc_supervision_summary_height"))
        assertEquals(
            "@dimen/rc_supervision_summary_height",
            summary.getAttribute("android:minHeight"),
        )
        // Read-only: below the 48dp touch floor, never a tap target.
        assertFalse("summary is not clickable", summary.getAttribute("android:clickable") == "true")
        assertFalse("summary is not focusable", summary.getAttribute("android:focusable") == "true")
        val source = mainSource()
        assertFalse(
            "no click handler on the summary",
            source.contains("R.id.supervisionCompactRow)?.setOnClickListener"),
        )
        // Persistent site/spatial context composes on the 24dp row.
        val site = findById(root, "tvCompactSite")!!
        assertTrue("site token lives in the compact row", isDescendantOf(root, "supervisionCompactRow", site))
        assertTrue(
            "site token warns while unapplied",
            source.contains("site_compact_unknown") && source.contains("site_compact_unapplied_format"),
        )
    }

    @Test
    fun records_viewer_first_with_closed_overlay() {
        val root = parseLayout()
        val source = mainSource()
        assertTrue("flat event text removed", findById(root, "tvFlightEvents") == null)
        // LEFT bounded kind rows first: compact 48dp session selector, then
        // event stepper, then the rows scroll with the remaining height.
        val sessions = findById(root, "reviewSessionsColumn")!!
        val sessionOrder = sessions.childElements().mapNotNull { it.idName() }
        assertTrue("compact session selector first: $sessionOrder", sessionOrder.firstOrNull() == "spHistoryFlight")
        assertTrue("event stepper in the sessions column", isDescendantOf(root, "reviewSessionsColumn", findById(root, "btnEventPrev")!!))
        val rows = findById(root, "eventRowList")!!
        assertTrue("event rows live in the sessions column", isDescendantOf(root, "reviewSessionsColumn", rows))
        // Duplicative session context/stepper moved to the overlay.
        for (id in listOf("tvFlights", "btnFlightPrev", "btnFlightNext")) {
            assertTrue("$id lives in the closed overlay", isDescendantOf(root, "reviewOverlay", findById(root, id)!!))
        }
        // RIGHT primary viewer: fixed order, viewer takes the remaining
        // height, filmstrip/custody fixed.
        val content = findById(root, "reviewContentRow")!!
        val order = content.childElements().mapNotNull { it.idName() }
        assertEquals(
            "sessions left, viewer right: $order",
            listOf("reviewSessionsColumn", "reviewViewerColumn"),
            order,
        )
        val stage = findById(root, "evidenceViewerStage")!!
        assertEquals("viewer stage is weighted, not fixed", "0dp", stage.getAttribute("android:layout_height"))
        assertTrue("viewer fills the remaining height", (stage.getAttribute("android:layout_weight").toFloatOrNull() ?: 0f) > 0f)
        val viewer = findById(root, "ivEvidence")!!
        assertEquals("image fills stage", "match_parent", viewer.getAttribute("android:layout_height"))
        assertEquals("evidenceViewerStage", parentElement(root, viewer)?.idName())
        assertTrue("empty state shares the actual viewer", isDescendantOf(root, "evidenceViewerStage", findById(root, "tvEvidenceEmpty")!!))
        val recovery = findById(root, "btnReviewEmptyRecovery")!!
        assertEquals("empty recovery stays a real touch target", "48dp", recovery.getAttribute("android:layout_height"))
        assertTrue("empty recovery uses real custody flow", source.contains("on(R.id.btnReviewEmptyRecovery) { showEvidencePage() }"))
        assertTrue("filmstrip fixed height via dimens", findById(root, "filmstripList") != null)
        val filmstripScroll = parentElement(root, findById(root, "filmstripList")!!)!!
        assertEquals("filmstrip fixed 56dp via dimens", "@dimen/rc_filmstrip_height", filmstripScroll.getAttribute("android:layout_height"))
        assertEquals(56, dimenDp("rc_filmstrip_height"))
        // Custody persistent OUTSIDE the overlay-covered frame.
        val custody = findById(root, "tvCustodyLine")!!
        assertEquals("custody is a persistent page footer", "pageReview", parentElement(root, custody)?.idName())
        val overlay = findById(root, "reviewOverlay")!!
        assertEquals("overlay closed by default", "gone", overlay.getAttribute("android:visibility"))
        for (id in listOf(
            "tvEventDetail", "btnFlagFalsePositive", "btnViewEvidence", "btnFetchEvidence",
            "tvFlightDetail", "tvFlightArtifacts", "tvObservationSummary", "tvObservationSync",
        )) {
            val view = findById(root, id)!!
            assertTrue("$id lives in the closed overlay", isDescendantOf(root, "reviewOverlay", view))
        }
        // Overlay opens on demand and navigation closes it.
        assertTrue("overlay opens on demand", source.contains("on(R.id.btnReviewDetail)"))
        assertTrue("navigation closes the overlay", source.contains("R.id.reviewOverlay") && source.contains("View.GONE"))
        // Event rows keep the 48dp two-line budget (code-built buttons).
        assertTrue("rows keep the 48dp minimum", source.contains("minimumHeight = (48 *"))
        assertTrue("rows cap at two lines", source.contains("maxLines = 2"))
    }

    @Test
    fun assist_requests_live_in_the_deck_not_the_nav_menu() {
        val root = parseLayout()
        val source = mainSource()
        // Nav menu: 4 destinations only.
        val popup = source.substringAfter("private fun showNavPopup(").substringBefore("private fun openAssist(")
        for (id in listOf("btnNavObserve", "btnNavMission", "btnNavReview", "btnNavSettings")) {
            assertTrue("$id in the nav menu", popup.contains(id))
        }
        assertFalse("no evidence in the menu", popup.contains("btnNavEvidence"))
        assertFalse("no camera/flight requests in the menu", popup.contains("photoGroup"))
        // Contextual deck entries reuse the same gated dialogs.
        val row = findById(root, "requestEntryRow")!!
        assertTrue("request row lives in the deck", isDescendantOf(root, "opsDeck", row))
        val deckOrder = findById(root, "opsDeck")!!.childElements().mapNotNull { it.idName() }
        assertTrue("controls precede optional site work", deckOrder.indexOf("requestEntryRow") < deckOrder.indexOf("siteConfirmPanel"))
        assertEquals("two bounded request rows, not five squeezed buttons", "vertical", row.getAttribute("android:orientation"))
        assertTrue("at most four controls per 280dp tray row", row.childElements().all { it.childElements().size <= 4 })
        assertTrue("multiline button labels must not shift and clip neighboring targets", row.childElements().all { it.getAttribute("android:baselineAligned") == "false" })
        for (id in listOf("btnReqPhoto", "btnReqRec", "btnReqZoom", "btnReqGimbal", "btnReqFlight")) {
            val view = findById(root, id)!!
            assertTrue("$id in the request row", isDescendantOf(root, "requestEntryRow", view))
            assertTrue("$id keeps 48dp", (view.getAttribute("android:layout_height").removeSuffix("dp").toIntOrNull() ?: 0) >= 48)
        }
        assertTrue("deck entries reuse the gated dialog path", source.contains("openAssist(R.id."))
    }

    @Test
    fun settings_holds_the_only_wide_video_control() {
        val root = parseLayout()
        assertTrue("legacy header toggle removed", findById(root, "btnFocusMode") == null)
        val wide = findById(root, "btnWideVideo")!!
        assertTrue("wide video lives in settings", isDescendantOf(root, "pageSettings", wide))
        assertTrue("wide video keeps 48dp", (wide.getAttribute("android:layout_height").removeSuffix("dp").toIntOrNull() ?: 0) >= 48)
        assertTrue("wide video toggles the focus flag", mainSource().contains("on(R.id.btnWideVideo)"))
        assertFalse("no header toggle binding remains", mainSource().contains("on(R.id.btnFocusMode)"))
    }
}
