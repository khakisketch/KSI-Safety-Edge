package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Host-JVM math checks for [EvidenceViewport]: fit-center start,
 * 1..max zoom clamp, focus stability, pan clamps, axis-fit centering,
 * reset/resize behavior, and NaN/zero/overflow guards.
 */
class EvidenceViewportTest {

    private fun viewport(): EvidenceViewport =
        EvidenceViewport().also { it.configure(400f, 300f, 800f, 600f) }

    private fun assertNear(expected: Float, actual: Float, eps: Float = 1e-3f) {
        assertTrue("expected=$expected actual=$actual", abs(expected - actual) <= eps)
    }

    private fun assertFinite(t: EvidenceViewport.Transform) {
        assertTrue(t.relativeScale.isFinite())
        assertTrue(t.pixelScale.isFinite())
        assertTrue(t.translationX.isFinite())
        assertTrue(t.translationY.isFinite())
    }

    @Test
    fun fitCenterInitial() {
        // fit = min(400/800, 300/600) = 0.5 -> 400x300 exactly fills view.
        val t = viewport().transform
        assertNear(1f, t.relativeScale)
        assertNear(0.5f, t.pixelScale)
        assertNear(0f, t.translationX)
        assertNear(0f, t.translationY)
        assertFinite(t)
    }

    @Test
    fun tallImageCentersOnFittingAxis() {
        val v = EvidenceViewport()
        // fit = min(400/200, 300/600) = 0.5 -> displayed 100x300: X centered.
        v.configure(400f, 300f, 200f, 600f)
        val t = v.transform
        assertNear(1f, t.relativeScale)
        assertNear(150f, t.translationX)
        assertNear(0f, t.translationY)
    }

    @Test
    fun zoomClampsToOneAndMax() {
        val v = viewport()
        v.zoomBy(0.1f, 200f, 150f)
        assertNear(1f, v.transform.relativeScale)
        v.zoomBy(100f, 200f, 150f)
        assertNear(8f, v.transform.relativeScale)
        assertFinite(v.transform)
    }

    @Test
    fun zoomKeepsFocusPointStable() {
        val v = viewport()
        v.zoomBy(2f, 100f, 75f)
        val t = v.transform
        assertNear(2f, t.relativeScale)
        // Displayed 800x600 from top-left (0,0): focus (100,75) shows image
        // point (200,150); after 2x it must stay under the focus.
        assertNear(-100f, t.translationX)
        assertNear(-75f, t.translationY)
    }

    @Test
    fun focusStabilityYieldsToClampAtEdge() {
        val v = viewport()
        // Focus at the extreme corner zooms toward it, but translation
        // clamps to [view - displayed, 0] = [-400, 0] x [-300, 0].
        v.zoomBy(2f, 0f, 0f)
        val t = v.transform
        assertNear(2f, t.relativeScale)
        assertNear(0f, t.translationX)
        assertNear(0f, t.translationY)
    }

    @Test
    fun panClampsNoBlankBeyondFit() {
        val v = viewport()
        v.zoomBy(2f, 200f, 150f)
        v.panBy(-10000f, -10000f)
        var t = v.transform
        assertNear(-400f, t.translationX)
        assertNear(-300f, t.translationY)
        v.panBy(10000f, 10000f)
        t = v.transform
        assertNear(0f, t.translationX)
        assertNear(0f, t.translationY)
        assertFinite(t)
    }

    @Test
    fun noFreePanWhenAxisFits() {
        val v = EvidenceViewport()
        // Displayed 100x300 at fit: X axis fits -> stays centered; Y fills.
        v.configure(400f, 300f, 200f, 600f)
        v.panBy(50f, 25f)
        val t = v.transform
        assertNear(150f, t.translationX)
        assertNear(0f, t.translationY)
    }

    @Test
    fun resetRestoresFitCenter() {
        val v = viewport()
        v.zoomBy(4f, 200f, 150f)
        v.panBy(-30f, -20f)
        v.reset()
        val t = v.transform
        assertNear(1f, t.relativeScale)
        assertNear(0f, t.translationX)
        assertNear(0f, t.translationY)
    }

    @Test
    fun configureResetsZoomAndPan() {
        val v = viewport()
        v.zoomBy(4f, 200f, 150f)
        v.configure(400f, 300f, 800f, 600f)
        val t = v.transform
        assertNear(1f, t.relativeScale)
        assertNear(0f, t.translationX)
        assertFinite(t)
    }

    @Test(expected = IllegalArgumentException::class)
    fun configureRejectsZeroDimensions() {
        EvidenceViewport().configure(0f, 300f, 800f, 600f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun configureRejectsNonFiniteDimensions() {
        EvidenceViewport().configure(Float.NaN, 300f, 800f, 600f)
    }

    @Test
    fun badZoomAndPanAreIgnored() {
        val v = viewport()
        v.zoomBy(Float.NaN, 200f, 150f)
        v.zoomBy(0f, 200f, 150f)
        v.zoomBy(-2f, 200f, 150f)
        v.zoomBy(Float.POSITIVE_INFINITY, 200f, 150f)
        v.panBy(Float.NaN, 0f)
        v.panBy(0f, Float.POSITIVE_INFINITY)
        val t = v.transform
        assertNear(1f, t.relativeScale)
        assertNear(0f, t.translationX)
        assertNear(0f, t.translationY)
        assertFinite(t)
    }

    @Test
    fun unconfiguredTransformIsIdentityNeverNaN() {
        val t = EvidenceViewport().transform
        assertNear(1f, t.relativeScale)
        assertFinite(t)
    }

    @Test(expected = IllegalArgumentException::class)
    fun badMaxScaleRejected() {
        EvidenceViewport(maxRelativeScale = 0.5f)
    }
}
