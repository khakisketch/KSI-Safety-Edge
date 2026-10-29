package ksi.safetyedge.rc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Read-only, cardless flight HUD drawn over the bottom of the video surface.
 *
 * The view is display-only: it renders the already formatted telemetry
 * strings and never issues a command, starts a recording or invents a
 * missing/stale value. Three presentations are available:
 * - [FlightHudStyle.BARE]: text + small compass ticks (H1)
 * - [FlightHudStyle.SCALE]: a thin compass scale line + values (H2)
 * - [FlightHudStyle.RETICLE]: a compass ring + values (H3)
 *
 * A bottom gradient scrim and text shadows provide contrast; there is no
 * filled bar, pod or card container. The map and tools controls are separate
 * siblings and are drawn above this view.
 */
class FlightHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var style: FlightHudStyle = FlightHudStyle.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                updateContentDescription()
                invalidate()
            }
        }

    private var altitudeRaw = "미수신"
    private var distanceRaw = "미수신"
    private var speedRaw = "미수신"
    private var verticalSpeedRaw = "미수신"
    private var headingRaw = "미수신"

    private var altitude = FlightHudModel.Value(altitudeRaw, null)
    private var distance = FlightHudModel.Value(distanceRaw, null)
    private var speed = FlightHudModel.Value(speedRaw, null)
    private var verticalSpeed = FlightHudModel.Value(verticalSpeedRaw, null)
    private var heading = FlightHudModel.Value(headingRaw, null)
    private var headingDegrees: Double? = null
    private var staleBadge: String? = null

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    // The HUD is a compact overlay: respect font enlargement up to 1.15x so
    // a 1.3x system font cannot push the ring/values into the center state.
    private val fontScale = (scaledDensity / density).coerceIn(1f, 1.15f)

    private val fg = ContextCompat.getColor(context, R.color.ksi_video_fg)
    private val amber = ContextCompat.getColor(context, R.color.ksi_attention_video)
    private val muted = ColorUtils.setAlphaComponent(fg, 220)
    private val shadow = 0xCC000000.toInt()

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = fg
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(dp(3f), 0f, dp(1.5f), shadow)
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = muted
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        setShadowLayer(dp(3f), 0f, dp(1.5f), shadow)
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }

    private val lineShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.2f)
        color = 0xCC000000.toInt()
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = amber
    }

    private val fillShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAA000000.toInt()
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateContentDescription()
    }

    /**
     * Bind the already formatted, read-only telemetry strings. Staleness is
     * carried by the formatter as "·지연N초"; it is split out for a warning
     * badge and never dropped.
     */
    fun bind(
        altitude: String,
        distance: String,
        speed: String,
        verticalSpeed: String,
        heading: String,
    ) {
        altitudeRaw = altitude
        distanceRaw = distance
        speedRaw = speed
        verticalSpeedRaw = verticalSpeed
        headingRaw = heading
        this.altitude = FlightHudModel.splitStale(altitude)
        this.distance = FlightHudModel.splitStale(distance)
        this.speed = FlightHudModel.splitStale(speed)
        this.verticalSpeed = FlightHudModel.splitStale(verticalSpeed)
        this.heading = FlightHudModel.splitStale(heading)
        headingDegrees = FlightHudModel.headingDegrees(heading)
        staleBadge = FlightHudModel.staleBadge(
            listOf(
                "고도" to this.altitude.stale,
                "거리" to this.distance.stale,
                "속도" to this.speed.stale,
                "상승" to this.verticalSpeed.stale,
                "방위" to this.heading.stale,
            ),
        )
        updateContentDescription()
        invalidate()
    }

    private fun updateContentDescription() {
        contentDescription = buildString {
            append("비행 HUD ").append(style.label).append(". ")
            append("고도 H ").append(altitudeRaw).append(". ")
            append("거리 D ").append(distanceRaw).append(". ")
            append("속도 ").append(speedRaw).append(". ")
            append("상승속도 ").append(verticalSpeedRaw).append(". ")
            append("방위 ").append(headingRaw).append('.')
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        gradientPaint.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                0x66000000,
                0xBB000000.toInt(),
                0xDD000000.toInt(),
            ),
            floatArrayOf(0f, 0.35f, 0.70f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
        when (style) {
            FlightHudStyle.BARE -> drawBare(canvas)
            FlightHudStyle.SCALE -> drawScale(canvas)
            FlightHudStyle.RETICLE -> drawReticle(canvas)
        }
    }

    // ---------------------------------------------------------------- H1 Bare

    private fun drawBare(canvas: Canvas) {
        val s = fontScale
        val baseline = height - dp(30f * s)
        val tickBottom = baseline - dp(50f * s)
        drawCompassBand(canvas, width / 2f, tickBottom, dp(180f * s), headingDegrees)
        drawValueRow(
            canvas,
            baseline,
            listOf(
                "고도 H" to altitude.text,
                "거리 D" to distance.text,
                "속도" to speed.text,
                "방위" to heading.text,
            ),
        )
        drawStale(canvas)
    }

    private fun drawCompassBand(
        canvas: Canvas,
        cx: Float,
        bottom: Float,
        width: Float,
        headingDegrees: Double?,
    ) {
        val s = fontScale
        val center = FlightHudModel.tapeCenter(headingDegrees)
        val pxPerDegree = width / 90f
        for (offset in -45..45 step 5) {
            val major = offset % 15 == 0
            val x = cx + offset * pxPerDegree
            val h = if (major) dp(14f * s) else dp(8f * s)
            hudLine(canvas, x, bottom - h, x, bottom, if (major) fg else muted)
        }
        listOf(-30, 0, 30).forEach { offset ->
            val x = cx + offset * pxPerDegree
            val label = FlightHudModel.tapeLabel(center + offset)
            drawLabel(canvas, label, x, bottom + dp(18f * s), if (offset == 0) fg else muted, bold = offset == 0)
        }
        if (headingDegrees != null) {
            caret(canvas, cx, bottom - dp(14f * s), dp(5f * s))
        }
    }

    // --------------------------------------------------------------- H2 Scale

    private fun drawScale(canvas: Canvas) {
        val s = fontScale
        val baseline = height - dp(30f * s)
        val lineY = baseline - dp(50f * s)
        val lineWidth = min(dp(400f * s), safeWidth() - dp(24f))
        drawCompassScale(canvas, width / 2f, lineY, lineWidth, headingDegrees)
        drawValueRow(
            canvas,
            baseline,
            listOf(
                "고도 H" to altitude.text,
                "거리 D" to distance.text,
                "속도" to speed.text,
                "방위" to heading.text,
            ),
        )
        drawStale(canvas)
    }

    private fun drawCompassScale(
        canvas: Canvas,
        cx: Float,
        y: Float,
        width: Float,
        headingDegrees: Double?,
    ) {
        val s = fontScale
        val center = FlightHudModel.tapeCenter(headingDegrees)
        hudLine(canvas, cx - width / 2f, y, cx + width / 2f, y, fg)
        val pxPerDegree = width / 120f
        for (offset in -60..60 step 5) {
            val major = offset % 15 == 0
            val x = cx + offset * pxPerDegree
            val h = if (major) dp(13f * s) else dp(7f * s)
            hudLine(canvas, x, y - h, x, y, if (major) fg else muted)
        }
        listOf(-60, -30, 0, 30, 60).forEach { offset ->
            val x = cx + offset * pxPerDegree
            val label = FlightHudModel.tapeLabel(center + offset)
            drawLabel(canvas, label, x, y - dp(18f * s), if (offset == 0) fg else muted, bold = offset == 0)
        }
        if (headingDegrees != null) {
            caret(canvas, cx, y - dp(13f * s), dp(5f * s))
        }
    }

    // ------------------------------------------------------------- H3 Reticle

    private fun drawReticle(canvas: Canvas) {
        val s = fontScale
        val cx = width / 2f
        val ringBottom = height - dp(36f * s)
        val radius = dp(30.5f * s)
        val cy = ringBottom - radius

        ringPaint.color = 0xAA000000.toInt()
        ringPaint.strokeWidth = dp(2.8f)
        canvas.drawCircle(cx + dp(1f), cy + dp(2f), radius, ringPaint)
        ringPaint.color = fg
        ringPaint.strokeWidth = dp(1.2f)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        for (deg in 0 until 360 step 10) {
            val angle = Math.toRadians(deg.toDouble())
            val major = deg % 30 == 0
            val inner = radius - dp(if (major) 7f * s else 4f * s)
            val c = cos(angle).toFloat()
            val si = sin(angle).toFloat()
            hudLine(
                canvas,
                cx + inner * c, cy + inner * si,
                cx + radius * c, cy + radius * si,
                if (major) fg else muted,
            )
        }

        drawLabel(canvas, "N", cx, cy - radius - dp(8f * s), fg, bold = true, sizeSp = 8.5f)
        drawLabel(canvas, "E", cx + radius + dp(12f * s), cy + dp(3f * s), muted, sizeSp = 8.5f)
        drawLabel(canvas, "S", cx, cy + radius + dp(13f * s), muted, sizeSp = 8.5f)
        drawLabel(canvas, "W", cx - radius - dp(12f * s), cy + dp(3f * s), muted, sizeSp = 8.5f)

        drawLabel(canvas, "방위", cx, cy - dp(14f * s), muted, sizeSp = 8.5f)
        drawValue(canvas, heading.text, cx, cy - dp(1f * s), sizeSp = 11.5f)
        drawLabel(canvas, "속도", cx, cy + dp(11f * s), muted, sizeSp = 8.5f)
        drawValue(canvas, speed.text, cx, cy + dp(23f * s), sizeSp = 9.5f)

        headingDegrees?.let { drawHeadingMarker(canvas, cx, cy, radius, it) }

        drawValueGroup(canvas, cx - dp(82f * s), cy, "고도 H", altitude.text)
        drawValueGroup(canvas, cx + dp(82f * s), cy, "거리 D", distance.text)
        drawStale(canvas)
    }

    private fun drawHeadingMarker(canvas: Canvas, cx: Float, cy: Float, radius: Float, degrees: Double) {
        val angle = Math.toRadians(degrees - 90.0)
        val tipR = radius + dp(4f)
        val baseR = radius - dp(5f)
        val spread = Math.toRadians(5.0)
        val tipX = cx + tipR * cos(angle).toFloat()
        val tipY = cy + tipR * sin(angle).toFloat()
        val leftX = cx + baseR * cos(angle - spread).toFloat()
        val leftY = cy + baseR * sin(angle - spread).toFloat()
        val rightX = cx + baseR * cos(angle + spread).toFloat()
        val rightY = cy + baseR * sin(angle + spread).toFloat()
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(leftX, leftY)
            lineTo(rightX, rightY)
            close()
        }
        canvas.save()
        canvas.translate(dp(1f), dp(2f))
        canvas.drawPath(path, fillShadowPaint)
        canvas.restore()
        canvas.drawPath(path, fillPaint)
    }

    // ------------------------------------------------------------- shared HUD

    private fun drawValueRow(canvas: Canvas, baseline: Float, values: List<Pair<String, String>>) {
        val count = values.size
        val cell = min(dp(112f * fontScale), safeWidth() / count)
        val start = width / 2f - (cell * (count - 1)) / 2f
        values.forEachIndexed { index, (label, value) ->
            val x = start + index * cell
            drawLabel(
                canvas, label, x, baseline - dp(20f * fontScale), muted,
                sizeSp = 9.5f,
            )
            drawValue(canvas, value, x, baseline)
        }
    }

    private fun drawValueGroup(canvas: Canvas, cx: Float, cy: Float, label: String, value: String) {
        drawLabel(canvas, label, cx, cy - dp(4f * fontScale), muted, sizeSp = 9.5f)
        drawValue(canvas, value, cx, cy + dp(18f * fontScale))
    }

    private fun drawStale(canvas: Canvas) {
        val badge = staleBadge ?: return
        labelPaint.textSize = sp(8.5f)
        labelPaint.color = amber
        labelPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(badge, width / 2f, height - dp(8f * fontScale), labelPaint)
        labelPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private fun drawLabel(
        canvas: Canvas,
        text: String,
        x: Float,
        baseline: Float,
        color: Int,
        bold: Boolean = false,
        sizeSp: Float = 9.5f,
    ) {
        labelPaint.textSize = sp(sizeSp)
        labelPaint.color = color
        labelPaint.typeface = Typeface.create(
            if (bold) "sans-serif-medium" else "sans-serif",
            Typeface.NORMAL,
        )
        canvas.drawText(text, x, baseline, labelPaint)
    }

    private fun drawValue(canvas: Canvas, text: String, x: Float, baseline: Float, sizeSp: Float? = null) {
        valuePaint.textSize = sp(sizeSp ?: if (text.length > 9) 11.5f else 13.5f)
        valuePaint.color = fg
        canvas.drawText(text, x, baseline, valuePaint)
    }

    private fun hudLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int) {
        canvas.drawLine(x1 + dp(1f), y1 + dp(2f), x2 + dp(1f), y2 + dp(2f), lineShadowPaint)
        linePaint.color = color
        canvas.drawLine(x1, y1, x2, y2, linePaint)
    }

    private fun caret(canvas: Canvas, cx: Float, y: Float, halfWidth: Float) {
        val height = dp(7f)
        val path = Path().apply {
            moveTo(cx, y)
            lineTo(cx - halfWidth, y - height)
            lineTo(cx + halfWidth, y - height)
            close()
        }
        canvas.save()
        canvas.translate(dp(1f), dp(2f))
        canvas.drawPath(path, fillShadowPaint)
        canvas.restore()
        canvas.drawPath(path, fillPaint)
    }

    private fun safeWidth(): Float = width - dp(156f) - dp(72f)

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * scaledDensity
}
