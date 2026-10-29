package ksi.safetyedge.rc

import android.app.Activity
import android.graphics.drawable.Drawable

/**
 * Device flavor: the workspace preview fixture NEVER acts here.
 *
 * Seeding synthetic evidence into the production package
 * (`ksi.safetyedge.rc`, real RC store, real device writes/commands) is
 * forbidden — this object is an unconditional no-op so a stray
 * `workspaceScenario` intent extra can never plant fixture rows on a
 * field device. All preview seeding lives in the physically separate
 * BENCH package only (`src/bench/.../WorkspacePreviewFixture.kt`).
 */
object WorkspacePreviewFixture {
    /** Camera preview intent extras cannot manufacture a report on the operational package. */
    fun cameraPresentation(scenario: String?): FlightCameraPresentation = FlightCameraPresentation()
    /** No timed display simulation or reference raster on the operational package. */
    fun cameraTimelineEnabled(scenario: String?): Boolean = false
    fun cameraPresentationAt(scenario: String?, elapsedMs: Long): FlightCameraPresentation = FlightCameraPresentation()
    fun videoBackdrop(activity: Activity): Drawable? = null
    /** Device: always false, touches nothing. */
    fun seed(app: RcApp, scenario: String?): Boolean = false
}
