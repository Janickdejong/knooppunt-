package nl.janick.knooppunt

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class Place(val name: String, val address: String, val lat: Double, val lon: Double)

/** Straatnamen bij bochten, opgezocht via OpenStreetMap (Nominatim, maximaal 1 aanvraag per seconde). */
object Streets {
    private val cache = ConcurrentHashMap<String, String>()
    private val busy = ConcurrentHashMap<String, Long>()
    private val exec = Executors.newSingleThreadExecutor()
    private var lastCall = 0L

    private fun key(p: GeoPoint) = String.format(Locale.US, "%.4f,%.4f", p.latitude, p.longitude)

    /** De naam als die al bekend is. Leeg betekent: geen naam gevonden. */
    fun get(p: GeoPoint): String? = cache[key(p)]

    fun ensure(p: GeoPoint) {
        val k = key(p)
        if (cache.containsKey(k)) return
        val now = System.currentTimeMillis()
        val t = busy[k]
        if (t != null && now - t < 60_000L) return
        busy[k] = now
        exec.execute {
            try {
                val wait = 1100L - (System.currentTimeMillis() - lastCall)
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
                val url = URL(
                    String.format(
                        Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=17&accept-language=nl&lat=%.6f&lon=%.6f",
                        p.latitude, p.longitude
                    )
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
                if (c.responseCode == 200) {
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    val a = o.optJSONObject("address")
                    var name = ""
                    if (a != null) {
                        for (f in listOf("road", "cycleway", "pedestrian", "path", "footway")) {
                            val v = a.optString(f)
                            if (v.isNotEmpty()) { name = v; break }
                        }
                    }
                    if (name.isEmpty()) name = o.optString("name")
                    cache[k] = name
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Zoekt een adres of plaats (Nominatim). De uitkomst komt op een achtergrondthread terug. */
    fun search(query: String, cb: (List<Place>) -> Unit) {
        exec.execute {
            val out = ArrayList<Place>()
            try {
                val wait = 1100L - (System.currentTimeMillis() - lastCall)
                if (wait > 0) Thread.sleep(wait)
                lastCall = System.currentTimeMillis()
                val url = URL(
                    "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&accept-language=nl&countrycodes=nl,be,de&q=" +
                        URLEncoder.encode(query, "UTF-8")
                )
                val c = url.openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
                if (c.responseCode == 200) {
                    val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val full = o.optString("display_name")
                        val short = full.split(",").take(2).joinToString(",").trim()
                        out.add(Place(short, full, o.optDouble("lat"), o.optDouble("lon")))
                    }
                }
            } catch (_: Exception) {
            }
            cb(out)
        }
    }
}
