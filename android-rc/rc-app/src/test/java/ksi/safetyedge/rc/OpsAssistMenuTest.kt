package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Host tests for the Round49 on-demand assist mapping: menu entries resolve
 * to the existing holder views and titles — never to a new command path —
 * and unknown ids resolve to null (nav fallback).
 */
class OpsAssistMenuTest {

    @Test
    fun groups_reuse_existing_holders_and_titles() {
        assertEquals(
            OpsAssistMenu.Dialog(R.id.photoGroup, R.string.cmd_photo),
            OpsAssistMenu.dialogFor(R.id.photoGroup),
        )
        assertEquals(
            OpsAssistMenu.Dialog(R.id.recGroup, R.string.ops_camera_title),
            OpsAssistMenu.dialogFor(R.id.recGroup),
        )
        assertEquals(
            OpsAssistMenu.Dialog(R.id.zoomGroup, R.string.ops_camera_title),
            OpsAssistMenu.dialogFor(R.id.zoomGroup),
        )
        assertEquals(
            OpsAssistMenu.Dialog(R.id.gimbalGroup, R.string.ops_camera_title),
            OpsAssistMenu.dialogFor(R.id.gimbalGroup),
        )
        assertEquals(
            OpsAssistMenu.Dialog(R.id.flightGroup, R.string.ops_flight_title),
            OpsAssistMenu.dialogFor(R.id.flightGroup),
        )
    }

    @Test
    fun unknown_ids_fall_back_to_nav() {
        assertNull(OpsAssistMenu.dialogFor(R.id.btnNavMission))
        assertNull(OpsAssistMenu.dialogFor(R.id.btnNavSettings))
        assertNull(OpsAssistMenu.dialogFor(-1))
    }
}
