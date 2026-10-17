package nl.janick.knooppunt

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Typeface
import android.view.MotionEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay

/** Tekent de fietsknooppunten, de verbindingen ertussen en de gekozen route. */
class NetOverlay(private val dens: Float) : Overlay() {
    @Volatile var net: Network? = null
    var routeIds: Set<Long> = emptySet()
    var legs: List<Leg> = emptyList()
    var me: GeoPoint? = null
    var navigating = false
    var night = false
    var nextId: Long = -1L
    var meHeading: Float = Float.NaN
    var guide: List<GeoPoint> = emptyList()
    var onNodeTap: ((KNode) -> Unit)? = null
    var onUserMove: (() -> Unit)? = null
    var onNodeLong: ((KNode) -> Unit)? = null

    private val green = Color.parseColor("#1B6B3A")
    private val path = Path()
    private val tmp = Point()

    private fun stroke(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = widthDp * dens
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val edgePaint = stroke(Color.parseColor("#A63A5FD6"), 3f)
    private val routeCasing = stroke(Color.WHITE, 9f)
    private val routePaint = stroke(Color.parseColor("#F08A24"), 5.5f)
    private val guideCasing = stroke(Color.WHITE, 9f)
    private val guidePaint = stroke(Color.parseColor("#2563EB"), 5.5f)
    private val brokenPaint = stroke(Color.parseColor("#F08A24"), 4f).apply {
        pathEffect = DashPathEffect(floatArrayOf(14f * dens, 10f * dens), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = green
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val meFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#2563EB") }
    private val meRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val arrowOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL_AND_STROKE
        strokeWidth = 5f * dens
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()

    private fun addPath(pj: org.osmdroid.views.Projection, pts: List<GeoPoint>) {
        path.rewind()
        var first = true
        for (p in pts) {
            pj.toPixels(p, tmp)
            if (first) {
                path.moveTo(tmp.x.toFloat(), tmp.y.toFloat())
                first = false
            } else {
                path.lineTo(tmp.x.toFloat(), tmp.y.toFloat())
            }
        }
    }

    private fun radiusDp(zoom: Double): Float = when {
        zoom >= 15.0 -> 17f
        zoom >= 14.0 -> 15f
        zoom >= 13.0 -> 13f
        else -> 10f
    }

    override fun draw(c: Canvas, osmv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = osmv.projection
        val zoom = osmv.zoomLevelDouble
        // Tijdens het navigeren: één duidelijke lijn, zoals in een autonavigatie.
        val lineColor = when {
            !navigating -> Color.parseColor("#F08A24")
            night -> Color.parseColor("#2FD0F2")
            else -> Color.parseColor("#1A73E8")
        }
        val casingColor = if (navigating && night) Color.parseColor("#06222A") else Color.WHITE
        val lineWidth = (if (navigating) 8f else 5.5f) * dens
        val casingWidth = (if (navigating) 12.5f else 9f) * dens
        routePaint.color = lineColor
        routePaint.strokeWidth = lineWidth
        routeCasing.color = casingColor
        routeCasing.strokeWidth = casingWidth
        guidePaint.color = lineColor
        guidePaint.strokeWidth = lineWidth
        guideCasing.color = casingColor
        guideCasing.strokeWidth = casingWidth
        val bb = osmv.boundingBox
        val n = net

        if (n != null && zoom >= SHOW_FROM && !navigating) {
            for (e in n.edges) {
                if (e.maxLat < bb.latSouth || e.minLat > bb.latNorth ||
                    e.maxLon < bb.lonWest || e.minLon > bb.lonEast
                ) continue
                addPath(pj, e.pts)
                c.drawPath(path, edgePaint)
                val back = e.back
                if (back != null) {
                    addPath(pj, back)
                    c.drawPath(path, edgePaint)
                }
            }
        }

        if (guide.size >= 2) {
            addPath(pj, guide)
            c.drawPath(path, guideCasing)
            c.drawPath(path, guidePaint)
        }

        for (leg in legs) {
            addPath(pj, leg.pts)
            if (leg.connected) {
                c.drawPath(path, routeCasing)
                c.drawPath(path, routePaint)
            } else {
                c.drawPath(path, brokenPaint)
            }
        }

        // Alle knooppunten pas vanaf een bruikbaar zoomniveau; de gekozen route blijft altijd zichtbaar.
        if (n != null && (zoom >= SHOW_FROM || routeIds.isNotEmpty())) {
            val all = zoom >= SHOW_FROM && !navigating
            val r = (if (all) radiusDp(zoom) else if (navigating) 12f else 6f) * dens
            ring.strokeWidth = 2.5f * dens
            text.textSize = r * 1.05f
            val fm = text.fontMetrics
            val textOffset = -(fm.ascent + fm.descent) / 2f
            val w = osmv.width
            val h = osmv.height
            val shown = if (all) n.nodes.values else routeIds.mapNotNull { n.nodes[it] }
            for (node in shown) {
                if (node.pos.latitude < bb.latSouth || node.pos.latitude > bb.latNorth ||
                    node.pos.longitude < bb.lonWest || node.pos.longitude > bb.lonEast
                ) continue
                pj.toPixels(node.pos, tmp)
                if (tmp.x < -r || tmp.y < -r || tmp.x > w + r || tmp.y > h + r) continue
                val selected = routeIds.contains(node.id)
                fill.color = if (selected) green else Color.WHITE
                val isNext = node.id == nextId
                c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), if (isNext) r * 1.35f else r, fill)
                if (isNext) {
                    ring.color = Color.parseColor("#F08A24")
                    ring.strokeWidth = 5f * dens
                    c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), r * 1.35f, ring)
                    ring.color = green
                    ring.strokeWidth = 2.5f * dens
                } else {
                    c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), r, ring)
                }
                if ((all || navigating) && zoom >= 13.0) {
                    text.color = if (selected) Color.WHITE else Color.BLACK
                    c.drawText(node.ref, tmp.x.toFloat(), tmp.y + textOffset, text)
                }
            }
        }

        val m = me
        if (m != null) {
            pj.toPixels(m, tmp)
            val h = meHeading
            if (navigating && !h.isNaN()) {
                // Pijl in rijrichting, zoals in een autonavigatie.
                arrowPath.rewind()
                arrowPath.moveTo(0f, -17f * dens)
                arrowPath.lineTo(12f * dens, 13f * dens)
                arrowPath.lineTo(0f, 6f * dens)
                arrowPath.lineTo(-12f * dens, 13f * dens)
                arrowPath.close()
                c.save()
                c.translate(tmp.x.toFloat(), tmp.y.toFloat())
                c.rotate(h + osmv.mapOrientation)
                c.drawPath(arrowPath, arrowOutline)
                c.drawPath(arrowPath, meFill)
                c.restore()
            } else {
                c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), 11f * dens, meRing)
                c.drawCircle(tmp.x.toFloat(), tmp.y.toFloat(), 8f * dens, meFill)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        val a = event.actionMasked
        if (a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_POINTER_DOWN) onUserMove?.invoke()
        return false
    }

    private fun nodeAt(e: MotionEvent, mapView: MapView): KNode? {
        val n = net ?: return null
        if (navigating || mapView.zoomLevelDouble < 11.0) return null
        val pj = mapView.projection
        val limit = 30f * dens
        var best: KNode? = null
        var bestDist = Float.MAX_VALUE
        for (node in n.nodes.values) {
            pj.toPixels(node.pos, tmp)
            val dx = tmp.x - e.x
            val dy = tmp.y - e.y
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d < limit && d < bestDist) {
                bestDist = d
                best = node
            }
        }
        return best
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = nodeAt(e, mapView) ?: return false
        onNodeTap?.invoke(hit)
        return true
    }

    override fun onLongPress(e: MotionEvent, mapView: MapView): Boolean {
        val hit = nodeAt(e, mapView) ?: return false
        onNodeLong?.invoke(hit)
        return true
    }
}
