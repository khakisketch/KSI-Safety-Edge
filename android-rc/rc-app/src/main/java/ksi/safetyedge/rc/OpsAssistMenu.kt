package ksi.safetyedge.rc

/**
 * Round49: on-demand assist destinations for the observe header menu.
 *
 * The fixed dock is gone by default, so physical-controller duplicates no
 * longer occupy the screen. Each menu entry opens a bounded dialog hosting
 * the existing command views (same ids, same listeners, same gate-first
 * sendCommand) plus the shared request-result view. No Android classes —
 * host-testable.
 */
object OpsAssistMenu {

    data class Dialog(val holderId: Int, val titleRes: Int)

    fun dialogFor(itemId: Int): Dialog? = when (itemId) {
        R.id.photoGroup -> Dialog(R.id.photoGroup, R.string.cmd_photo)
        R.id.recGroup -> Dialog(R.id.recGroup, R.string.ops_camera_title)
        R.id.zoomGroup -> Dialog(R.id.zoomGroup, R.string.ops_camera_title)
        R.id.gimbalGroup -> Dialog(R.id.gimbalGroup, R.string.ops_camera_title)
        R.id.flightGroup -> Dialog(R.id.flightGroup, R.string.ops_flight_title)
        else -> null
    }
}
