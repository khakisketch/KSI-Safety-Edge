package ksi.safetyedge.rc

/**
 * Pure-Kotlin (no Android) math for evidence-image zoom/pan.
 *
 * Consumed by Design's custom AppCompatImageView via a Matrix plus
 * ScaleGestureDetector: this class owns ONLY the numbers. No image I/O,
 * no gesture handling, no View references.
 *
 * Coordinate model (view pixels):
 * - `fitScale = min(viewW / imageW, viewH / imageH)` — the whole image
 *   fits inside the view.
 * - `displayed = image * fitScale * relativeScale`; `relativeScale == 1`
 *   is exactly fit.
 * - `(translationX, translationY)` is the displayed image's top-left
 *   offset in view coordinates.
 *
 * Rules:
 * - Initial (and [reset]) state is fit-center: `relativeScale == 1` with
 *   the image centered on each axis.
 * - Zoom range is `1..maxRelativeScale` (default 8) relative to fit;
 *   out-of-range factors clamp.
 * - Pan clamps so zoomed content never exposes blank beyond the fit
 *   frame: on an axis where the displayed image is smaller than (or
 *   equal to) the view, the image stays centered (no free pan); where it
 *   is larger, translation clamps to `[view - displayed, 0]`.
 * - [zoomBy] keeps the focus point stable (the image point under the
 *   focus stays under it) until clamping sacrifices stability at an edge
 *   — the result is always the clamped translation.
 * - [configure] (re)sets the geometry and RESETS zoom/pan to fit-center.
 *   Resize-then-preserve is deliberately not offered: after a size change
 *   the old translation is meaningless, so the caller re-applies zoom
 *   explicitly if desired.
 * - Defensive: [configure] rejects non-positive or non-finite dimensions
 *   with [IllegalArgumentException]; [zoomBy] ignores non-positive or
 *   non-finite factors; [panBy] ignores non-finite deltas. The state never
 *   holds NaN, infinities, zero/negative scale, or overflowed products
 *   (products are re-checked finite and clamped back to a valid state).
 * - Before the first valid [configure], [transform] returns the
 *   unconfigured identity `Transform(1, 1, 0, 0)` — never NaN.
 */
class EvidenceViewport(val maxRelativeScale: Float = 8f) {

    init {
        require(maxRelativeScale.isFinite() && maxRelativeScale >= 1f) {
            "maxRelativeScale must be finite and >= 1"
        }
    }

    data class Transform(
        /** Zoom relative to fit (1 == fit). Always in 1..maxRelativeScale. */
        val relativeScale: Float,
        /** Absolute pixels of displayed image per image pixel. */
        val pixelScale: Float,
        /** Displayed image top-left X in view coordinates. */
        val translationX: Float,
        /** Displayed image top-left Y in view coordinates. */
        val translationY: Float,
    )

    private var viewW: Float = 0f
    private var viewH: Float = 0f
    private var imageW: Float = 0f
    private var imageH: Float = 0f
    private var configured: Boolean = false
    private var scale: Float = 1f
    private var tx: Float = 0f
    private var ty: Float = 0f

    fun configure(viewWidth: Float, viewHeight: Float, imageWidth: Float, imageHeight: Float) {
        require(
            viewWidth.isFinite() && viewHeight.isFinite() &&
                imageWidth.isFinite() && imageHeight.isFinite() &&
                viewWidth > 0f && viewHeight > 0f &&
                imageWidth > 0f && imageHeight > 0f,
        ) { "viewport dimensions must be finite and positive" }
        viewW = viewWidth
        viewH = viewHeight
        imageW = imageWidth
        imageH = imageHeight
        configured = true
        reset()
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) {
        if (!configured) return
        if (!factor.isFinite() || factor <= 0f) return
        if (!focusX.isFinite() || !focusY.isFinite()) return
        val target = (scale * factor).coerceIn(1f, maxRelativeScale)
        if (!target.isFinite()) return
        if (target == scale) return
        val ratio = target / scale
        scale = target
        tx = focusX - (focusX - tx) * ratio
        ty = focusY - (focusY - ty) * ratio
        sanitizeAndClamp()
    }

    fun panBy(dx: Float, dy: Float) {
        if (!configured) return
        if (!dx.isFinite() || !dy.isFinite()) return
        tx += dx
        ty += dy
        sanitizeAndClamp()
    }

    fun reset() {
        scale = 1f
        if (configured) {
            val fit = fitScale()
            tx = (viewW - imageW * fit) / 2f
            ty = (viewH - imageH * fit) / 2f
        } else {
            tx = 0f
            ty = 0f
        }
    }

    val transform: Transform
        get() {
            if (!configured) return Transform(1f, 1f, 0f, 0f)
            return Transform(scale, fitScale() * scale, tx, ty)
        }

    private fun fitScale(): Float =
        minOf(viewW / imageW, viewH / imageH)

    private fun sanitizeAndClamp() {
        if (!scale.isFinite()) scale = 1f
        scale = scale.coerceIn(1f, maxRelativeScale)
        if (!tx.isFinite()) tx = 0f
        if (!ty.isFinite()) ty = 0f
        val fit = fitScale()
        var product = fit * scale
        if (!product.isFinite() || product <= 0f) {
            // Overflow-sized geometry: fall back to fit-center rather than
            // publishing NaN/infinite translations.
            scale = 1f
            product = fitScale()
        }
        tx = clampAxis(tx, viewW, imageW * product)
        ty = clampAxis(ty, viewH, imageH * product)
    }

    private fun clampAxis(t: Float, view: Float, displayed: Float): Float {
        if (!displayed.isFinite()) return (view / 2f).takeIf { it.isFinite() } ?: 0f
        return if (displayed <= view) {
            (view - displayed) / 2f
        } else {
            t.coerceIn(view - displayed, 0f)
        }
    }
}
