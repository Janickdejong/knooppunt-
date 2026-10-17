package nl.janick.knooppunt

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.min

/** Groot bochtsymbool: rechtdoor, links, rechts, een pijl in een richting, of een vinkje. */
class TurnArrowView(context: Context) : View(context) {
    private var mode = STRAIGHT
    private var angle = 0f
    private var ink = Color.parseColor("#0E1726")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ink
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ink
    }
    private val path = Path()

    fun setColor(color: Int) {
        ink = color
        line.color = color
        head.color = color
        invalidate()
    }

    fun set(mode: Int, angle: Float) {
        this.mode = mode
        this.angle = angle
        invalidate()
    }

    private fun drawStraight(c: Canvas, s: Float) {
        path.rewind()
        path.moveTo(0.5f * s, 0.78f * s)
        path.lineTo(0.5f * s, 0.30f * s)
        c.drawPath(path, line)
        path.rewind()
        path.moveTo(0.5f * s, 0.16f * s)
        path.lineTo(0.33f * s, 0.38f * s)
        path.lineTo(0.67f * s, 0.38f * s)
        path.close()
        c.drawPath(path, head)
    }

    private fun drawRight(c: Canvas, s: Float) {
        path.rewind()
        path.moveTo(0.34f * s, 0.80f * s)
        path.lineTo(0.34f * s, 0.52f * s)
        path.lineTo(0.56f * s, 0.52f * s)
        c.drawPath(path, line)
        path.rewind()
        path.moveTo(0.78f * s, 0.52f * s)
        path.lineTo(0.55f * s, 0.34f * s)
        path.lineTo(0.55f * s, 0.70f * s)
        path.close()
        c.drawPath(path, head)
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        line.strokeWidth = s * 0.11f
        when (mode) {
            ARRIVED -> {
                path.rewind()
                path.moveTo(0.28f * s, 0.52f * s)
                path.lineTo(0.44f * s, 0.68f * s)
                path.lineTo(0.74f * s, 0.34f * s)
                c.drawPath(path, line)
            }
            POINTER -> {
                c.save()
                c.rotate(angle, width / 2f, height / 2f)
                drawStraight(c, s)
                c.restore()
            }
            RIGHT -> drawRight(c, s)
            LEFT -> {
                c.save()
                c.scale(-1f, 1f, width / 2f, height / 2f)
                drawRight(c, s)
                c.restore()
            }
            else -> drawStraight(c, s)
        }
    }

    companion object {
        const val STRAIGHT = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val POINTER = 3
        const val ARRIVED = 4
    }
}
