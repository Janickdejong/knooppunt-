package nl.janick.knooppunt

import android.content.Context
import android.location.Location
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Neemt tijdens het navigeren je gefietste route op. */
class RideRecorder {
    val startTime = System.currentTimeMillis()
    val lat = ArrayList<Double>()
    val lon = ArrayList<Double>()
    val time = ArrayList<Long>()
    var distance = 0.0
    var maxSpeed = 0.0
    var movingMs = 0L
    var paused = false
    var endTime = 0L
    private var last: Location? = null

    fun add(l: Location) {
        if (paused) {
            // Tijdens een pauze telt er niets mee; wel onthouden waar je staat, zodat er na de pauze geen sprong komt.
            last = Location(l)
            return
        }
        if (l.hasSpeed() && l.speed > maxSpeed && l.speed < 30f) maxSpeed = l.speed.toDouble()
        val p = last
        if (p == null) {
            last = Location(l)
            lat.add(l.latitude); lon.add(l.longitude); time.add(l.time)
            return
        }
        val d = p.distanceTo(l)
        if (d < 5f) return
        distance += d
        movingMs += minOf(l.time - p.time, 15000L).coerceAtLeast(0L)
        last = Location(l)
        lat.add(l.latitude); lon.add(l.longitude); time.add(l.time)
    }

    fun durationMs(): Long = (if (endTime > 0) endTime else System.currentTimeMillis()) - startTime
    fun avgSpeed(): Double = if (movingMs > 0) distance / (movingMs / 1000.0) else 0.0
}

class RideInfo(val file: File, val name: String, val start: Long, val distance: Double, val moving: Long, val max: Double, val thumb: DoubleArray, val total: Long = 0L)

object Rides {
    private fun dir(ctx: Context) = File(ctx.filesDir, "rides").also { it.mkdirs() }

    fun save(ctx: Context, r: RideRecorder, name: String) {
        val o = JSONObject()
        o.put("name", name)
        o.put("start", r.startTime)
        o.put("dist", r.distance)
        o.put("moving", r.movingMs)
        o.put("total", r.durationMs())
        o.put("max", r.maxSpeed)
        val pts = JSONArray()
        for (i in r.lat.indices) {
            pts.put(JSONArray().put(r.lat[i]).put(r.lon[i]).put(r.time[i]))
        }
        o.put("pts", pts)
        File(dir(ctx), "${r.startTime}.json").writeText(o.toString())
    }

    fun list(ctx: Context): List<RideInfo> {
        val out = ArrayList<RideInfo>()
        for (f in dir(ctx).listFiles() ?: emptyArray()) {
            try {
                val o = JSONObject(f.readText())
                val pts = o.optJSONArray("pts") ?: JSONArray()
                val step = maxOf(1, pts.length() / 40)
                val flat = ArrayList<Double>()
                var i = 0
                while (i < pts.length()) {
                    val p = pts.getJSONArray(i)
                    flat.add(p.getDouble(0))
                    flat.add(p.getDouble(1))
                    i += step
                }
                out.add(RideInfo(f, o.optString("name"), o.optLong("start"), o.optDouble("dist"), o.optLong("moving"), o.optDouble("max"), flat.toDoubleArray(), o.optLong("total")))
            } catch (_: Exception) {
            }
        }
        return out.sortedByDescending { it.start }
    }

    fun gpx(file: File): String {
        val o = JSONObject(file.readText())
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<gpx version=\"1.1\" creator=\"Knooppunt\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        sb.append("  <trk>\n    <name>").append(o.optString("name").replace("&", "&amp;").replace("<", "&lt;")).append("</name>\n    <trkseg>\n")
        val pts = o.optJSONArray("pts") ?: JSONArray()
        for (i in 0 until pts.length()) {
            val p = pts.getJSONArray(i)
            sb.append(String.format(Locale.US, "      <trkpt lat=\"%.6f\" lon=\"%.6f\"><time>%s</time></trkpt>\n", p.getDouble(0), p.getDouble(1), iso.format(Date(p.getLong(2)))))
        }
        sb.append("    </trkseg>\n  </trk>\n</gpx>\n")
        return sb.toString()
    }
}
