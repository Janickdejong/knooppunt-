package nl.janick.knooppunt

import org.osmdroid.util.GeoPoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class Turn(val dist: Double, val dir: Int, val index: Int)

/** Een lijn om te volgen: punten om de ~10 m, met afstanden, voortgang en bochten. */
class Track(raw: List<GeoPoint>, densify: Boolean = true) {
    val pts: List<GeoPoint>
    val cum: DoubleArray
    var progress = 0

    init {
        if (densify) {
            val out = ArrayList<GeoPoint>()
            for (i in raw.indices) {
                if (i == 0) {
                    out.add(raw[0])
                    continue
                }
                val a = raw[i - 1]
                val b = raw[i]
                val steps = max(1, ceil(a.distanceToAsDouble(b) / 10.0).toInt())
                for (k in 1 until steps) {
                    val f = k.toDouble() / steps
                    out.add(GeoPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f))
                }
                out.add(b)
            }
            pts = out
        } else {
            pts = raw
        }
        val c = DoubleArray(pts.size)
        for (i in 1 until pts.size) c[i] = c[i - 1] + pts[i - 1].distanceToAsDouble(pts[i])
        cum = c
    }

    /** Zoekt het dichtstbijzijnde punt, werkt de voortgang bij en geeft de afstand ernaartoe. */
    fun locate(here: GeoPoint): Double {
        var best = -1
        var bestDist = Double.MAX_VALUE
        val from = max(0, progress - 2)
        val to = min(pts.size - 1, progress + 150)
        for (i in from..to) {
            val d = here.distanceToAsDouble(pts[i])
            if (d < bestDist) {
                bestDist = d
                best = i
            }
        }
        if (bestDist > 60.0) {
            for (i in pts.indices) {
                val d = here.distanceToAsDouble(pts[i])
                if (d < bestDist) {
                    bestDist = d
                    best = i
                }
            }
        }
        if (best >= 0) progress = best
        return bestDist
    }

    fun remaining(): Double = cum[cum.size - 1] - cum[progress]

    /** Een punt zo'n 80 m verderop, om de richting te bepalen. */
    fun aim(): GeoPoint = pts[min(pts.size - 1, progress + 8)]

    private fun turnAngle(i: Int): Double {
        val b1 = pts[i - 3].bearingTo(pts[i])
        val b2 = pts[i].bearingTo(pts[i + 3])
        var d = b2 - b1
        while (d > 180) d -= 360
        while (d < -180) d += 360
        return d
    }

    /** De eerstvolgende bocht binnen 300 m: afstand en richting (1 = rechts, -1 = links). */
    fun turnAhead(maxDist: Double = 300.0): Pair<Double, Int>? {
        var i = max(progress, 3)
        while (i + 3 < pts.size && cum[i] - cum[progress] <= maxDist) {
            val angle = turnAngle(i)
            if (abs(angle) >= 45) {
                var bestIndex = i
                var bestAngle = angle
                var j = i + 1
                while (j < i + 6 && j + 3 < pts.size) {
                    val a = turnAngle(j)
                    if (abs(a) > abs(bestAngle)) {
                        bestAngle = a
                        bestIndex = j
                    }
                    j++
                }
                return (cum[bestIndex] - cum[progress]) to (if (bestAngle > 0) 1 else -1)
            }
            i++
        }
        return null
    }

    /** De eerstvolgende bochten (binnen maxDist): afstand, richting (1 = rechts) en plek op de lijn. */
    fun turnsAhead(maxDist: Double = 300.0, count: Int = 2): List<Turn> {
        val out = ArrayList<Turn>()
        var i = max(progress, 3)
        while (i + 3 < pts.size && cum[i] - cum[progress] <= maxDist && out.size < count) {
            val angle = turnAngle(i)
            if (abs(angle) >= 45) {
                var bestIndex = i
                var bestAngle = angle
                var j = i + 1
                while (j < i + 6 && j + 3 < pts.size) {
                    val a = turnAngle(j)
                    if (abs(a) > abs(bestAngle)) {
                        bestAngle = a
                        bestIndex = j
                    }
                    j++
                }
                out.add(Turn(cum[bestIndex] - cum[progress], if (bestAngle > 0) 1 else -1, bestIndex))
                i = bestIndex + 6
            } else {
                i++
            }
        }
        return out
    }

    /** Een punt kort na de bocht: daar ligt de straat waarin je afslaat. */
    fun pointAfter(index: Int): GeoPoint = pts[min(pts.size - 1, index + 4)]
}
