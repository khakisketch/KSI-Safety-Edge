package ksi.safetyedge.rc

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.roundToInt

/** Display-only inspection of the already verified image. Never changes evidence bytes or source. */
class ZoomEvidenceImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {
    // ImageView may invoke a setter from its superclass constructor.
    private var viewport: EvidenceViewport? = null
    private val matrixValue = Matrix()
    private var configured = false
    var onViewportChanged: ((Boolean, Int) -> Unit)? = null

    private val pinch = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                viewport?.zoomBy(detector.scaleFactor, detector.focusX, detector.focusY)
                applyViewport()
                return true
            }
        },
    )
    private val drag = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = hasImage()
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (pinch.isInProgress) return false
                viewport?.panBy(-distanceX, -distanceY)
                applyViewport()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if ((viewport?.transform?.relativeScale ?: 1f) > 1.01f) resetToFit()
                else {
                    viewport?.zoomBy(2f, e.x, e.y)
                    applyViewport()
                }
                return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean { performClick(); return true }
        },
    )

    init {
        viewport = EvidenceViewport()
        scaleType = ScaleType.MATRIX
        isFocusable = true
        isClickable = true
        contentDescription = "근거 이미지 · 확대·축소 버튼, 두 손가락 확대, 끌어서 이동"
    }

    fun hasImage(): Boolean = drawable != null && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        configureViewport()
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        configureViewport()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Resizing / changing selection resets to a whole-image view, never a crop attributed to a new frame.
        configureViewport()
    }

    private fun configureViewport() {
        val geometry = viewport ?: return
        configured = hasImage() && width > 0 && height > 0
        if (configured) geometry.configure(width.toFloat(), height.toFloat(), drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        applyViewport()
    }

    fun zoomIn() { zoomBy(1.5f) }
    fun zoomOut() { zoomBy(1f / 1.5f) }
    private fun zoomBy(factor: Float) {
        if (!configured) return
        viewport?.zoomBy(factor, width / 2f, height / 2f)
        applyViewport()
    }

    fun resetToFit() { viewport?.reset(); applyViewport() }

    private fun applyViewport() {
        val state = viewport?.transform ?: return
        matrixValue.reset()
        if (configured) {
            matrixValue.setScale(state.pixelScale, state.pixelScale)
            matrixValue.postTranslate(state.translationX, state.translationY)
        }
        imageMatrix = matrixValue
        onViewportChanged?.invoke(configured, (state.relativeScale * 100).roundToInt())
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!configured) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            requestFocus()
        }
        pinch.onTouchEvent(event)
        drag.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!configured) return super.onKeyDown(keyCode, event)
        val amount = 32 * resources.displayMetrics.density
        when (keyCode) {
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoomIn()
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> zoomOut()
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0 -> resetToFit()
            KeyEvent.KEYCODE_DPAD_LEFT -> { viewport?.panBy(amount, 0f); applyViewport() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { viewport?.panBy(-amount, 0f); applyViewport() }
            KeyEvent.KEYCODE_DPAD_UP -> { viewport?.panBy(0f, amount); applyViewport() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { viewport?.panBy(0f, -amount); applyViewport() }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
