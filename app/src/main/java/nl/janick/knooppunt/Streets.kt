package nl.janick.knooppunt

import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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
}
