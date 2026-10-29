package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host pins for the A9-4 false-healthy investigation ("기체 연결됨" with
 * no aircraft): the LINK layer never fabricates an aircraft fact on any
 * route — bench double, test TCP route, and the default contract all
 * report null (unknown). A `true` can only arrive from the MSDK read
 * ([ksi.msdk.SdkAircraftConnection]), so a false "연결됨" is an MSDK
 * report to surface honestly, never a link/kind leak to patch over.
 */
class AircraftLinkFactTest {

    @Test
    fun bench_link_reports_aircraft_unknown() {
        assertNull(
            "a fake link must never claim an aircraft",
            FakeDroneLink().aircraftConnected,
        )
    }

    @Test
    fun test_tcp_link_reports_aircraft_unknown() {
        val link = TcpDroneLink("127.0.0.1", 9)
        try {
            assertNull(
                "the dev route carries Edge bytes, not an aircraft claim",
                link.aircraftConnected,
            )
            assertEquals(
                "the route must stay labelled so it is never read as product",
                "TEST_TCP",
                link.linkKind,
            )
        } finally {
            link.disconnect()
        }
    }

    @Test
    fun default_link_kind_is_the_product_path() {
        assertEquals("AIRCRAFT", FakeDroneLink().linkKind)
    }

    @Test
    fun unverified_report_line_shows_only_when_unknown_and_reported() {
        val line = "MSDK 연결 보고 있음 — 기체 확인 절차 없음"
        assertEquals(
            line,
            unverifiedReportLine(
                aircraftUnknown = true,
                msdkReportsConnection = true,
                line = line,
            ),
        )
        // Connected, disconnected-but-known, or no report: no extra line —
        // the step itself already says the fact, never duplicated.
        assertNull(
            unverifiedReportLine(aircraftUnknown = false, msdkReportsConnection = true, line = line),
        )
        assertNull(
            unverifiedReportLine(aircraftUnknown = true, msdkReportsConnection = false, line = line),
        )
        assertNull(
            unverifiedReportLine(aircraftUnknown = false, msdkReportsConnection = false, line = line),
        )
    }
}
