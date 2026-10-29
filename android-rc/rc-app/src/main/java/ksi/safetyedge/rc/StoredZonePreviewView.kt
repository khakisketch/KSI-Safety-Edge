package ksi.safetyedge.rc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.math.cos

/** Read-only outline of coordinates in the pinned snapshot. Not a basemap, route, or live position. */
class StoredZonePreviewView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, private val compact: Boolean = false, private val minimal: Boolean = false) : View(context, attrs) {
    private data class Point(val lon: Double, val lat: Double)
    private data class Zone(val name: String, val rings: List<List<Point>>)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var zones: List<Zone> = emptyList()
    private var bundleId: String? = null
    private var signature: String? = "__uninitialized__"
    private var sourceZoneCount = 0
    private var remainingPoints = 16_384
    val hasGeometry: Boolean get() = zones.isNotEmpty()
    var onExpand: (() -> Unit)? = null
    var expanded = false
    private val density get() = resources.displayMetrics.density
    private val fontScale get() = resources.displayMetrics.scaledDensity
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    init {
        setOnClickListener {
            if (compact && onExpand != null) onExpand?.invoke()
            else if (zones.size > 1) androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("저장 구역 ${zones.size}개")
                .setMessage(zones.joinToString("\n") { it.name } +
                    "\n\n저장 좌표 개요입니다. 실제 지도·비행 경로·현재 위치가 아닙니다.")
                .setPositiveButton("닫기", null).show()
        }
    }

    fun setBundle(bundle: ObjectNode?) {
        val next = bundle?.get("bundle_sha256")?.asText() ?: bundle?.toString()
        if (next == signature) return
        signature = next
        bundleId = bundle?.get("bundle_id")?.asText()
        sourceZoneCount = bundle?.get("mission_snapshot")?.get("zones")?.size() ?: 0
        remainingPoints = 16_384
        zones = bundle?.get("mission_snapshot")?.get("zones")?.take(128)?.mapNotNull { zone ->
            val geometry = zone.get("geometry_wgs84") ?: return@mapNotNull null
            val coordinates = geometry.get("coordinates") ?: return@mapNotNull null
            val rings = when (geometry.get("type")?.asText()) {
                "Polygon" -> parseRings(coordinates)
                "MultiPolygon" -> if (!coordinates.isArray || coordinates.size() > 128) emptyList() else {
                    val polygons = coordinates.map(::parseRings)
                    if (polygons.any { it.isEmpty() }) emptyList() else polygons.flatten()
                }
                else -> emptyList()
            }
            if (rings.isEmpty()) null else Zone(
                zone.get("name")?.asText()?.takeIf { it.isNotBlank() }?.take(40)
                    ?: "구역 ${zone.get("zone_id")?.asText()?.take(6) ?: "이름 없음"}",
                rings,
            )
        }.orEmpty()
        contentDescription = if (bundle == null) "현장·계획 선택 전. 지도 타일 미연결."
            else "선택한 계획의 저장 좌표 구역 ${zones.size}/$sourceZoneCount 개 표시. 실제 지도, 비행 경로 또는 현재 위치가 아닙니다."
        isClickable = compact || zones.size > 1
        isFocusable = compact || zones.size > 1
        if (compact) contentDescription = "$contentDescription 지도 펼침 또는 접기."
        if (zones.size > 1) contentDescription = "$contentDescription 구역 이름 상세 열기."
        invalidate()
    }

    private fun parseRings(node: JsonNode): List<List<Point>> {
        if (!node.isArray || node.size() > 128) return emptyList()
        val rings = mutableListOf<List<Point>>()
        for (ring in node) {
            // Never connect a truncated or partially invalid ring into a
            // plausible-looking polygon. Omitted geometry is stated below.
            if (!ring.isArray || ring.size() < 4 || ring.size() > 4096 || ring.size() > remainingPoints) return emptyList()
            val points = mutableListOf<Point>()
            for (pair in ring) {
                if (!pair.isArray || pair.size() < 2 || !pair[0].isNumber || !pair[1].isNumber) return emptyList()
                val lon = pair[0].asDouble(); val lat = pair[1].asDouble()
                if (!lon.isFinite() || !lat.isFinite() || lon !in -180.0..180.0 || lat !in -90.0..90.0) return emptyList()
                points.add(Point(lon, lat))
            }
            if (points.first() != points.last()) return emptyList()
            remainingPoints -= points.size
            rings.add(points)
        }
        return rings
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The compact map lives on the video. With actual stored geometry it
        // uses near-opaque chrome; with no geometry the minimal flight map is
        // cardless (icon/text + shadow only), so the absent tiles do not
        // become a large empty card on the HUD.
        val onVideo = compact
        val cardlessEmpty = onVideo && minimal && zones.isEmpty()
        fun tint(id: Int, alpha: Int = 255) = ColorUtils.setAlphaComponent(color(id), alpha)
        if (!cardlessEmpty) {
            canvas.drawColor(
                if (onVideo) tint(R.color.ksi_video_bg, 252) else color(R.color.ksi_surface_subtle),
            )
        }
        if (cardlessEmpty) {
            paint.setShadowLayer(3f * density, 0f, 1.5f * density, 0xCC000000.toInt())
        }
        // An outline workspace, not invented cartography. A decorative grid
        // must not suggest cached map tiles or a georeferenced flight map.
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textSize = (if (onVideo) 10f else 11f) * fontScale
        paint.color = if (onVideo) tint(R.color.ksi_video_fg, 200) else color(R.color.ksi_muted)
        val headerX = (if (onVideo) 10 else 12) * density
        val headerY = (if (onVideo) 16 else 22) * density
        val header = if (compact) { if (hasGeometry) "저장 구역 ${zones.size}개" else "지도" }
            else "저장 구역 개요 · 지도 타일 미연결"
        val narrowHeader = !compact && paint.measureText(header) > width - headerX * 2
        if (narrowHeader) {
            canvas.drawText("저장 구역 개요", headerX, headerY, paint)
            canvas.drawText("지도 타일 미연결", headerX, headerY + paint.fontSpacing, paint)
        } else {
            canvas.drawText(header, headerX, headerY, paint)
        }
        if (compact) {
            val expand = WorkspaceIcons.drawable(
                context,
                if (expanded) WorkspaceIcons.Kind.CLOSE else WorkspaceIcons.Kind.FIT,
                if (onVideo) R.color.ksi_video_fg else R.color.ksi_muted,
                if (expanded) 18 else 14,
            )
            expand.setBounds(
                width - ((if (expanded) 30 else 26) * density).toInt(), (6 * density).toInt(),
                width - (10 * density).toInt(), ((if (expanded) 26 else 20) * density).toInt(),
            )
            expand.draw(canvas)
            if (!cardlessEmpty) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = density
                paint.color = if (onVideo) tint(R.color.ksi_video_fg, 80) else color(R.color.ksi_border)
                canvas.drawRect(density / 2, density / 2, width - density / 2, height - density / 2, paint)
                paint.style = Paint.Style.FILL
            }
        }
        if (compact && zones.isEmpty()) {
            val iconDp = if (expanded) 28 else 22
            val map = WorkspaceIcons.drawable(
                context, WorkspaceIcons.Kind.MAP,
                if (onVideo) R.color.ksi_video_fg else R.color.ksi_muted, iconDp,
            )
            val middle = height / 2
            val half = iconDp / 2 * density
            map.setBounds(
                (width / 2 - half).toInt(), (middle - half - 2 * density).toInt(),
                (width / 2 + half).toInt(), (middle + half - 2 * density).toInt(),
            )
            map.draw(canvas)
            paint.color = if (onVideo) tint(R.color.ksi_video_fg, 170) else color(R.color.ksi_muted)
            paint.textSize = (if (expanded) 10f else 9f) * fontScale
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(
                "타일 미연결", width / 2f,
                middle + (if (expanded) 32 else 24) * density, paint,
            )
            paint.textAlign = Paint.Align.LEFT
            if (cardlessEmpty) paint.clearShadowLayer()
            return
        }
        // The compact well is a glance surface; its full disclosure is one tap
        // away. Keep the expanded well's footer because it has room.
        if ((!compact || expanded) && zones.size < sourceZoneCount) {
            paint.textSize = 9 * fontScale
            paint.color = if (onVideo) tint(R.color.ksi_video_fg, 170) else color(R.color.ksi_muted)
            canvas.drawText(
                "일부 구역 표시 안 됨 · ${zones.size}/$sourceZoneCount 개 · 상세",
                10 * density, height - 8 * density, paint,
            )
        } else if ((!compact || expanded) && zones.size > 1) {
            paint.textSize = 9 * fontScale
            paint.color = if (onVideo) tint(R.color.ksi_video_fg, 170) else color(R.color.ksi_muted)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("구역 ${zones.size}개 · 상세", width - 10 * density, height - 8 * density, paint)
            paint.textAlign = Paint.Align.LEFT
        }
        if (zones.isEmpty()) {
            val icon = WorkspaceIcons.drawable(context, WorkspaceIcons.Kind.LOCATION, R.color.ksi_muted, 28)
            icon.setBounds(
                width / 2 - (14 * density).toInt(), height / 2 - (52 * density).toInt(),
                width / 2 + (14 * density).toInt(), height / 2 - (24 * density).toInt(),
            )
            icon.draw(canvas)
            paint.textAlign = Paint.Align.CENTER
            paint.color = color(R.color.ksi_fg)
            paint.textSize = 14 * fontScale
            canvas.drawText(
                if (bundleId == null) "현장과 계획을 선택하세요" else "표시할 저장 구역이 없습니다",
                width / 2f, height / 2f, paint,
            )
            paint.textSize = 12 * fontScale
            paint.color = color(R.color.ksi_muted)
            canvas.drawText("실제 지도·비행 경로·현재 위치와는 별도입니다", width / 2f, height / 2f + 24 * density, paint)
            paint.textAlign = Paint.Align.LEFT
            return
        }
        val points = zones.flatMap { it.rings.flatten() }
        val cosLat = cos(points.map { it.lat }.average() * Math.PI / 180).coerceAtLeast(.01)
        val minX = points.minOf { it.lon } * cosLat; val maxX = points.maxOf { it.lon } * cosLat
        val minY = points.minOf { it.lat }; val maxY = points.maxOf { it.lat }
        val pad = (if (onVideo) 24 else if (narrowHeader) 56 else 38) * density
        val usableW = (width - pad * 2).coerceAtLeast(1f)
        val usableH = (height - pad * 2).coerceAtLeast(1f)
        val scale = minOf(usableW / (maxX - minX).coerceAtLeast(.000001), usableH / (maxY - minY).coerceAtLeast(.000001))
        val offsetX = (width - (maxX - minX) * scale) / 2
        val offsetY = (height - (maxY - minY) * scale) / 2
        fun px(p: Point) = (offsetX + (p.lon * cosLat - minX) * scale).toFloat()
        fun py(p: Point) = (offsetY + (maxY - p.lat) * scale).toFloat()
        zones.forEach { zone ->
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                zone.rings.forEach { ring ->
                    moveTo(px(ring.first()), py(ring.first()))
                    ring.drop(1).forEach { lineTo(px(it), py(it)) }; close()
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = if (onVideo) tint(R.color.ksi_accent, 90) else color(R.color.ksi_candidate_selected_bg)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (if (onVideo) 1.5f else 2f) * density
            paint.color = if (onVideo) tint(R.color.ksi_video_fg, 235) else color(R.color.ksi_primary)
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 11 * fontScale
            paint.color = color(R.color.ksi_fg)
            // Nearly coincident stored zones must not turn into an illegible
            // stack of identifiers. All real names remain in the disclosure;
            // geometry and its multiplicity are not changed or deduplicated.
            if (zones.size == 1 && !compact) {
                // A caption in the reserved footer, not text straddling an
                // arbitrary polygon edge (which may be concave or nearly vertical).
                val label = android.text.TextUtils.ellipsize(zone.name,
                    android.text.TextPaint(paint), (width - 24 * density).coerceAtLeast(1f),
                    android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(label, 12 * density, height - 12 * density, paint)
            }
        }
    }
}
