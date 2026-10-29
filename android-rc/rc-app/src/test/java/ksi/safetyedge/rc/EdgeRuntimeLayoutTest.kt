package ksi.safetyedge.rc

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Layout contract for the always-visible Edge runtime status area (Unit C):
 * it lives in the operation screen's right column ABOVE the scrolling
 * command deck — a sibling of the scroll region, never inside it — so it
 * can never be scrolled out of reach, and it never overlaps the video or
 * the telemetry band. Same host-only XML style as [IntegratedOpsLayoutTest].
 */
class EdgeRuntimeLayoutTest {

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

    private fun stringValue(name: String): String {
        val file = findModuleFile("src/main/res/values/strings.xml")
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val root = builder.parse(file).documentElement
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node is Element && node.tagName == "string" &&
                node.getAttribute("name") == name
            ) {
                return node.textContent.trim()
            }
        }
        throw IllegalStateException("missing @string/$name")
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

    private fun Element.textSp(): Float? =
        getAttribute("android:textSize").takeIf { it.endsWith("sp") }
            ?.removeSuffix("sp")?.toFloatOrNull()

    private fun Element.textRef(): String? =
        getAttribute("android:text").takeIf { it.isNotEmpty() }

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
    fun runtime_panel_is_fixed_above_the_deck_scroll_never_inside_it() {
        val root = parseLayout()
        val runtime = findById(root, "edgeRuntimePanel")
        assertTrue("edgeRuntimePanel must exist", runtime != null)
        assertTrue(
            "runtime panel must never live inside the scroll region",
            !isDescendantOf(root, "opsDeckScroll", runtime!!),
        )
        assertTrue(
            "runtime panel must never live inside the command deck",
            !isDescendantOf(root, "opsDeck", runtime),
        )
        assertTrue(
            "runtime panel must never overlay the video stage",
            !isDescendantOf(root, "videoStage", runtime),
        )
        assertTrue(
            "runtime panel must never overlay the video frame",
            !isDescendantOf(root, "videoFrame", runtime),
        )
        assertTrue(
            "runtime panel must never cover the telemetry band",
            !isDescendantOf(root, "telemetryStrip", runtime),
        )
        assertEquals(
            "runtime panel is fixed in the right column",
            "opsRightColumn",
            parentElement(root, runtime)?.idName(),
        )
        val right = findById(root, "opsRightColumn")!!
        val order = right.childElements().mapNotNull { it.idName() }
        // D2: 오른쪽 열 = [고정 상태 패널(실행 상태)] + [스크롤 데크].
        // 현장 확인 카드는 **데크 스크롤 안**(명령 조작 공간을 밀어내지 않기 위해).
        // 계약의 본질은 "상태 패널이 스크롤 밖 고정, 스크롤 위"이므로 목록이 아니라
        // 순서 관계를 검증한다.
        val deckScrollIndex = order.indexOf("opsDeckScroll")
        assertTrue("deck scroll must exist in the right column: $order", deckScrollIndex >= 0)
        val fixedPanels = order.filter { it == "edgeRuntimePanel" }
        assertTrue(
            "the fixed status panels must sit above the deck scroll: $order",
            fixedPanels.isNotEmpty() &&
                order.indexOf("edgeRuntimePanel") < deckScrollIndex,
        )
        for (id in fixedPanels) {
            assertTrue(
                "fixed panel $id must never live inside the scroll region",
                !isDescendantOf(root, "opsDeckScroll", findById(root, id)!!),
            )
        }
        // D2: 현장 확인 카드는 데크 안이므로 스크롤로 접근 가능하다.
        val siteCard = findById(root, "siteConfirmPanel")
        assertTrue("site confirm card must exist", siteCard != null)
        assertTrue(
            "the site card lives inside the scrolling deck (it must not squeeze the deck away)",
            isDescendantOf(root, "opsDeckScroll", siteCard!!),
        )
        assertTrue(
            "runtime panel is always visible (no gone default)",
            runtime.getAttribute("android:visibility") != "gone",
        )
    }

    @Test
    fun runtime_panel_stays_concise_while_full_report_lives_in_details() {
        val root = parseLayout()
        // Concise state + zero-risk safety stay fixed outside the scroll.
        for (id in listOf("tvEdgeRuntimeState", "tvEdgeRuntimeZeroNote")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue("$id must live under edgeRuntimePanel", isDescendantOf(root, "edgeRuntimePanel", view!!))
            assertTrue(
                "$id must keep outdoor legibility (12sp+)",
                (view.textSp() ?: 0f) >= 12f,
            )
        }
        // Technical detail + session/commit verdicts live UNCAPPED in the
        // existing scrolling detail (opened via 조작·상세) — the fixed panel
        // cannot grow no matter how long the report gets.
        for (id in listOf("tvEdgeRuntimeDetail", "tvSessionResult", "tvCommitState")) {
            val view = findById(root, id)
            assertTrue("$id must exist", view != null)
            assertTrue(
                "$id must live in the scrolling detail, not the fixed panel",
                isDescendantOf(root, "cockpitDetailsScroll", view!!),
            )
            assertTrue(
                "$id must not grow the fixed panel",
                !isDescendantOf(root, "edgeRuntimePanel", view),
            )
            assertTrue(
                "$id must keep outdoor legibility (12sp+)",
                (view.textSp() ?: 0f) >= 12f,
            )
            assertTrue(
                "$id must not truncate the full report in scrolling detail",
                view.getAttribute("android:maxLines").isEmpty() &&
                    view.getAttribute("android:ellipsize").isEmpty(),
            )
        }
        val zero = findById(root, "tvEdgeRuntimeZeroNote")!!
        assertTrue(zero.textRef()?.startsWith("@string/") == true)
        val name = zero.textRef()!!.removePrefix("@string/")
        assertEquals(
            "zero note is verbatim and always visible",
            "0건 관측(분석 수행됨) ≠ 분석 결과 없음",
            stringValue(name),
        )
        assertTrue(
            "zero note is always visible (no gone default)",
            zero.getAttribute("android:visibility") != "gone",
        )
        assertEquals(
            "panel state wording comes from the fixed title string",
            "Edge 실행 상태",
            stringValue(
                findById(root, "edgeRuntimePanel")!!
                    .childElements()
                    .first { it.tagName == "TextView" }
                    .textRef()!!.removePrefix("@string/"),
            ),
        )
    }

    @Test
    fun preparation_screen_binds_revisions_and_apply_state_separately() {
        val root = parseLayout()
        for (id in listOf("tvMissionZoneRevisions", "tvMissionApplyState")) {
            val view = findById(root, id)
            assertTrue("$id must exist on the preparation screen", view != null)
            assertTrue("$id must live on pageMission", isDescendantOf(root, "pageMission", view!!))
            assertTrue(
                "$id must never sit in the action/command column",
                !isDescendantOf(root, "opsDeck", view),
            )
        }
        val mapNote = findById(root, "tvMissionMapNote")!!
        assertTrue(mapNote.textRef()?.startsWith("@string/") == true)
        assertTrue(
            "no map editor is fabricated: the gap is stated",
            stringValue(mapNote.textRef()!!.removePrefix("@string/")).contains("미구현"),
        )
    }
}
