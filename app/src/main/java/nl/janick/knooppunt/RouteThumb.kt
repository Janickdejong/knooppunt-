package nl.janick.knooppunt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** Een kleine tekening van de vorm van een route. pts = lat, lon, lat, lon, ... */
class RouteThumb(context: Context, private val pts: DoubleArray) : View(context) {
    private val d = resources.displayMetrics.density
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F2EC") }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F08A24")
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val startDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1B6B3A") }
    private val endDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1D2733") }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f * d, 12f * d, bg)
        val n = pts.size / 2
        if (n < 2) return
        var minLat = Double.MAX_VALUE
        var maxLat = -Double.MAX_VALUE
        var minLon = Double.MAX_VALUE
        var maxLon = -Double.MAX_VALUE
        for (i in 0 until n) {
            minLat = min(minLat, pts[2 * i])
            maxLat = max(maxLat, pts[2 * i])
            minLon = min(minLon, pts[2 * i + 1])
            maxLon = max(maxLon, pts[2 * i + 1])
        }
        val kx = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanX = max((maxLon - minLon) * kx, 1e-6)
        val spanY = max(maxLat - minLat, 1e-6)
        val pad = 12f * d
        val scale = min((w - 2 * pad) / spanX, (h - 2 * pad) / spanY).toFloat()
        val ox = (w - (spanX * scale).toFloat()) / 2f
        val oy = (h - (spanY * scale).toFloat()) / 2f
        fun px(i: Int) = ox + ((pts[2 * i + 1] - minLon) * kx * scale).toFloat()
        fun py(i: Int) = h - (oy + ((pts[2 * i] - minLat) * scale).toFloat())
        path.rewind()
        for (i in 0 until n) {
            if (i == 0) path.moveTo(px(i), py(i)) else path.lineTo(px(i), py(i))
        }
        canvas.drawPath(path, line)
        canvas.drawCircle(px(0), py(0), 4.5f * d, startDot)
        canvas.drawCircle(px(n - 1), py(n - 1), 4.5f * d, endDot)
    }
}
