package ksi.safetyedge.rc

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.PathParser

/** Authored 24-unit line icons. These are UI affordances, never telemetry or command state. */
object WorkspaceIcons {
    enum class Kind { MENU, DOWN, BACK, NEXT, CAMERA, VIDEO, ZOOM, MINUS, PLUS, FIT, STATUS, LOCATION, RECORDS, SETTINGS, CONNECTION, MORE, MAP, LENS, CLOSE }

    fun drawable(context: Context, kind: Kind, colorRes: Int, sizeDp: Int = 20): Drawable {
        val paths = when (kind) {
            Kind.MENU -> listOf("M4,6 L20,6 M4,12 L20,12 M4,18 L20,18")
            Kind.DOWN -> listOf("M6,9 L12,15 L18,9")
            Kind.BACK -> listOf("M15,5 L8,12 L15,19")
            Kind.NEXT -> listOf("M9,5 L16,12 L9,19")
            Kind.CAMERA -> listOf("M4,7 L8,7 L10,4 L15,4 L17,7 L21,7 L21,20 L3,20 L3,7 Z", "M16,13.5 A4,4 0,1 1,8,13.5 A4,4 0,1 1,16,13.5")
            Kind.VIDEO -> listOf("M3,6 L15,6 L15,18 L3,18 Z M15,10 L21,7 L21,17 L15,14")
            Kind.ZOOM -> listOf("M16,10 A6,6 0,1 1,4,10 A6,6 0,1 1,16,10 M14.5,14.5 L21,21 M7,10 L13,10 M10,7 L10,13")
            Kind.MINUS -> listOf("M5,12 L19,12")
            Kind.PLUS -> listOf("M5,12 L19,12 M12,5 L12,19")
            Kind.FIT -> listOf("M8,4 L4,4 L4,8 M16,4 L20,4 L20,8 M4,16 L4,20 L8,20 M16,20 L20,20 L20,16", "M8,8 L16,8 L16,16 L8,16 Z")
            Kind.STATUS -> listOf("M2,12 C5,6 8,4 12,4 C16,4 19,6 22,12 C19,18 16,20 12,20 C8,20 5,18 2,12 Z", "M15,12 A3,3 0,1 1,9,12 A3,3 0,1 1,15,12")
            Kind.LOCATION -> listOf("M19,9 C19,4 15,2 12,2 C9,2 5,4 5,9 C5,14 12,22 12,22 C12,22 19,14 19,9 Z", "M14.5,9 A2.5,2.5 0,1 1,9.5,9 A2.5,2.5 0,1 1,14.5,9")
            Kind.RECORDS -> listOf("M5,3 L15,3 L19,7 L19,21 L5,21 Z M15,3 L15,7 L19,7 M8,11 L16,11 M8,15 L16,15")
            Kind.SETTINGS -> listOf("M4,6 L20,6 M4,12 L20,12 M4,18 L20,18 M8,3 L8,9 M16,9 L16,15 M10,15 L10,21")
            Kind.CONNECTION -> listOf("M8,8 L5,8 L5,16 L8,16 M16,8 L19,8 L19,16 L16,16 M8,12 L16,12 M10,5 L14,5 M10,19 L14,19")
            Kind.MORE -> listOf("M4,12 L6,12 M11,12 L13,12 M18,12 L20,12")
            Kind.MAP -> listOf("M3,5 L9,3 L15,6 L21,4 L21,19 L15,21 L9,18 L3,20 Z M9,3 L9,18 M15,6 L15,21")
            Kind.LENS -> listOf("M21,12 A9,9 0,1 1,3,12 A9,9 0,1 1,21,12", "M17,12 A5,5 0,1 1,7,12 A5,5 0,1 1,17,12", "M13,12 A1,1 0,1 1,11,12 A1,1 0,1 1,13,12")
            Kind.CLOSE -> listOf("M6,6 L18,18 M18,6 L6,18")
        }.mapNotNull(PathParser::createPathFromPathData)
        val size = (sizeDp * context.resources.displayMetrics.density + .5f).toInt()
        return object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ContextCompat.getColor(context, colorRes)
                style = Paint.Style.STROKE
                strokeWidth = 1.7f
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private var opacity = 255
            override fun draw(canvas: Canvas) {
                val save = canvas.save()
                canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
                canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
                paint.alpha = if (state.isEmpty() || state.contains(android.R.attr.state_enabled)) opacity else (opacity * .45f).toInt()
                paths.forEach { canvas.drawPath(it, paint) }
                canvas.restoreToCount(save)
            }
            override fun getIntrinsicWidth() = size
            override fun getIntrinsicHeight() = size
            override fun isStateful() = true
            override fun onStateChange(state: IntArray): Boolean { invalidateSelf(); return true }
            override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
            override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            @Deprecated("Drawable opacity is deprecated")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }
    }
}
