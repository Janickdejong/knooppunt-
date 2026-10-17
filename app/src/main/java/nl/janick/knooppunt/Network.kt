package nl.janick.knooppunt

import android.content.Context
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.PriorityQueue

/** Een plaats langs de route, met wat OpenStreetMap erover weet. */
class Town(val name: String, val type: String, val pos: GeoPoint, val population: Int, val wiki: String?) {
    val label: String get() = when (type) { "city" -> "Stad"; "town" -> "Stad/plaats"; "village" -> "Dorp"; else -> "Gehucht" }
}

/** Een pauzeplek langs de route. */
class Poi(val name: String, val kind: String, val pos: GeoPoint) {
    val label: String get() = when (kind) {
        "toilets" -> "Toilet"
        "cafe" -> "Café"
        "restaurant" -> "Restaurant"
        "pub" -> "Café/kroeg"
        "ice_cream" -> "IJs"
        "sight" -> "Bezienswaardig"
        else -> "Snack"
    }
    val letter: String get() = when (kind) { "toilets" -> "WC"; "cafe" -> "C"; "restaurant" -> "R"; "pub" -> "K"; "ice_cream" -> "I"; "sight" -> "\u2605"; else -> "S" }
}

/** Een fietsknooppunt (rcn_ref in OpenStreetMap). */
class KNode(val id: Long, val ref: String, val pos: GeoPoint)

/** Een stuk fietsroute tussen twee knooppunten. De punten lopen van a naar b. */
class KEdge(val a: KNode, val b: KNode, val pts: List<GeoPoint>, val length: Double, val back: List<GeoPoint>? = null) {
    private val all = if (back != null) pts + back else pts
    val minLat = all.minOf { it.latitude }
    val maxLat = all.maxOf { it.latitude }
    val minLon = all.minOf { it.longitude }
    val maxLon = all.maxOf { it.longitude }
}

/** Het stuk route tussen twee gekozen knooppunten. connected=false betekent: geen verbinding gevonden. */
class Leg(val pts: List<GeoPoint>, val length: Double, val connected: Boolean)

class Network(val nodes: Map<Long, KNode>, val edges: List<KEdge>) {
    private val adj = HashMap<Long, MutableList<KEdge>>()

    init {
        for (e in edges) {
            adj.getOrPut(e.a.id) { ArrayList() }.add(e)
            adj.getOrPut(e.b.id) { ArrayList() }.add(e)
        }
    }

    /** Kortste weg over het knooppuntennetwerk (Dijkstra). */
    fun leg(from: KNode, to: KNode): Leg {
        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Pair<Long, KEdge>>()
        val queue = PriorityQueue<Pair<Double, Long>>(compareBy<Pair<Double, Long>> { it.first })
        dist[from.id] = 0.0
        queue.add(0.0 to from.id)
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()!!
            if (d > (dist[id] ?: Double.MAX_VALUE)) continue
            if (id == to.id) break
            for (e in adj[id] ?: continue) {
                val other = if (e.a.id == id) e.b else e.a
                val nd = d + e.length
                if (nd < (dist[other.id] ?: Double.MAX_VALUE)) {
                    dist[other.id] = nd
                    prev[other.id] = id to e
                    queue.add(nd to other.id)
                }
            }
        }
        if (!dist.containsKey(to.id) || from.id == to.id) {
            val d = from.pos.distanceToAsDouble(to.pos)
            return Leg(listOf(from.pos, to.pos), d, from.id == to.id)
        }
        // Terugwandelen van bestemming naar start.
        val steps = ArrayList<Pair<KEdge, Boolean>>()
        var cur = to.id
        while (cur != from.id) {
            val (p, e) = prev[cur]!!
            steps.add(e to (e.a.id == p))
            cur = p
        }
        steps.reverse()
        val pts = ArrayList<GeoPoint>()
        var length = 0.0
        for ((e, forward) in steps) {
            // Een eenrichtingspad voor fietsers kan terug een andere weg zijn.
            val seq = if (forward) e.pts else (e.back ?: e.pts.reversed())
            for ((i, p) in seq.withIndex()) {
                if (i == 0 && pts.isNotEmpty()) {
                    // Twee bordjes van hetzelfde knooppunt: een stukje rechtstreeks overbruggen.
                    val gap = pts.last().distanceToAsDouble(p)
                    if (gap < 5.0) continue
                    length += gap
                }
                pts.add(p)
            }
            length += e.length
        }
        return Leg(pts, length, true)
    }

    /** Kortste weg als lijst knooppunten; wegen in `avoid` kosten extra zodat een rondje niet heen en terug gaat. */
    private fun path(from: KNode, to: KNode, avoid: Set<KEdge>): Pair<List<KNode>, List<KEdge>>? {
        val dist = HashMap<Long, Double>()
        val prev = HashMap<Long, Pair<Long, KEdge>>()
        val queue = PriorityQueue<Pair<Double, Long>>(compareBy<Pair<Double, Long>> { it.first })
        dist[from.id] = 0.0
        queue.add(0.0 to from.id)
        while (queue.isNotEmpty()) {
            val (d, id) = queue.poll()!!
            if (d > (dist[id] ?: Double.MAX_VALUE)) continue
            if (id == to.id) break
            for (e in adj[id] ?: continue) {
                val other = if (e.a.id == id) e.b else e.a
                val nd = d + e.length * (if (e in avoid) 6.0 else 1.0)
                if (nd < (dist[other.id] ?: Double.MAX_VALUE)) {
                    dist[other.id] = nd
                    prev[other.id] = id to e
                    queue.add(nd to other.id)
                }
            }
        }
        if (!dist.containsKey(to.id) || from.id == to.id) return null
        val ns = ArrayList<KNode>()
        val es = ArrayList<KEdge>()
        var cur = to.id
        while (cur != from.id) {
            val (pr, e) = prev[cur]!!
            ns.add(nodes[cur]!!)
            es.add(e)
            cur = pr
        }
        ns.reverse()
        es.reverse()
        return ns to es
    }

    /** Maakt een rondje van ongeveer `target` meter vanaf `start` over de knooppunten (begin en eind = start). */
    fun loop(start: KNode, target: Double, seed: Long = System.nanoTime()): List<KNode>? {
        val rnd = java.util.Random(seed)
        val pool = nodes.values.filter { it.id != start.id }
        var best: List<KNode>? = null
        var bestScore = Double.MAX_VALUE
        for (attempt in 0 until 60) {
            val r = target / 3.6 * (0.8 + rnd.nextDouble() * 0.4)
            val bearing = rnd.nextDouble() * 360.0
            val spread = 50.0 + rnd.nextDouble() * 30.0
            val pa = start.pos.destinationPoint(r, bearing)
            val pb = start.pos.destinationPoint(r, bearing + (if (rnd.nextBoolean()) spread else -spread))
            val a = pool.minByOrNull { it.pos.distanceToAsDouble(pa) } ?: continue
            val b = pool.minByOrNull { it.pos.distanceToAsDouble(pb) } ?: continue
            if (a.id == b.id) continue
            val used = HashSet<KEdge>()
            val chain = arrayListOf(start)
            var total = 0.0
            var overlap = 0.0
            var ok = true
            for ((x, y) in listOf(start to a, a to b, b to start)) {
                val pr = path(x, y, used)
                if (pr == null) { ok = false; break }
                for (e in pr.second) {
                    if (e in used) overlap += e.length
                    used.add(e)
                    total += e.length
                }
                chain.addAll(pr.first)
            }
            if (!ok || total < 1.0) continue
            val score = Math.abs(total - target) / target + 1.5 * overlap / total
            if (score < bestScore) {
                bestScore = score
                best = chain
            }
        }
        return best
    }
}

/** Bouwt het netwerk stap voor stap op: nieuwe vakjes komen erbij zonder dat alles opnieuw wordt berekend. */
class NetworkBuilder {
    private class Link(val refA: String, val refB: String, val ways: List<List<GeoPoint>>)

    private val linkRegex = Regex("""(\d{1,3})\s*[-–>]+\s*(\d{1,3})""")
    private val nodes = HashMap<Long, KNode>()
    private val byRef = HashMap<String, MutableList<KNode>>()
    private val edges = ArrayList<KEdge>()
    private val seenRelations = HashSet<Long>()
    // Verbindingen waarvan een knooppunt nog in een vakje ligt dat niet geladen is.
    private val unlinked = ArrayList<Link>()

    private fun norm(ref: String): String = ref.trim().trimStart('0').ifEmpty { "0" }

    private fun lengthOf(pts: List<GeoPoint>): Double {
        var s = 0.0
        for (i in 1 until pts.size) s += pts[i - 1].distanceToAsDouble(pts[i])
        return s
    }

    /** Zet de losse stukken weg van een route op volgorde, beginnend bij start. */
    private fun chain(ways: List<List<GeoPoint>>, start: GeoPoint): List<GeoPoint> {
        val left = ways.toMutableList()
        val out = ArrayList<GeoPoint>()
        var cur = start
        while (left.isNotEmpty()) {
            var bestIndex = -1
            var bestReversed = false
            var bestDist = Double.MAX_VALUE
            for (i in left.indices) {
                val w = left[i]
                val d1 = cur.distanceToAsDouble(w.first())
                val d2 = cur.distanceToAsDouble(w.last())
                if (d1 < bestDist) { bestDist = d1; bestIndex = i; bestReversed = false }
                if (d2 < bestDist) { bestDist = d2; bestIndex = i; bestReversed = true }
            }
            if (bestIndex < 0 || bestDist > 200.0) break
            val w = if (bestReversed) left[bestIndex].reversed() else left[bestIndex]
            out.addAll(w)
            cur = w.last()
            left.removeAt(bestIndex)
        }
        return out
    }

    private fun nearest(candidates: List<KNode>?, ways: List<List<GeoPoint>>): KNode? {
        if (candidates == null) return null
        var best: KNode? = null
        var bestDist = 300.0
        for (n in candidates) {
            for (w in ways) {
                // Een knooppunt ligt aan het begin of eind van een stuk route.
                for (p in listOf(w.first(), w.last())) {
                    val d = n.pos.distanceToAsDouble(p)
                    if (d < bestDist) { bestDist = d; best = n }
                }
            }
        }
        return best
    }

    /** true = klaar met deze verbinding (gelukt of niet bruikbaar), false = later opnieuw proberen. */
    private fun tryLink(link: Link): Boolean {
        val a = nearest(byRef[link.refA], link.ways) ?: return false
        val b = nearest(byRef[link.refB], link.ways) ?: return false
        if (a.id == b.id) return true
        val chained = chain(link.ways, a.pos)
        if (chained.size < 2) return true
        val pts = ArrayList<GeoPoint>(chained.size + 2)
        pts.add(a.pos)
        pts.addAll(chained)
        pts.add(b.pos)
        edges.add(KEdge(a, b, pts, lengthOf(pts)))
        return true
    }

    private fun parse(rel: JSONObject): Link? {
        val tags = rel.optJSONObject("tags") ?: return null
        var text = tags.optString("ref")
        if (!linkRegex.containsMatchIn(text)) text = tags.optString("name")
        if (!linkRegex.containsMatchIn(text)) text = tags.optString("note")
        val m = linkRegex.find(text) ?: return null
        val refA = norm(m.groupValues[1])
        val refB = norm(m.groupValues[2])
        if (refA == refB) return null
        val members = rel.optJSONArray("members") ?: return null
        val ways = ArrayList<List<GeoPoint>>()
        for (i in 0 until members.length()) {
            val member = members.optJSONObject(i) ?: continue
            if (member.optString("type") != "way") continue
            val geometry = member.optJSONArray("geometry") ?: continue
            val pts = ArrayList<GeoPoint>()
            for (j in 0 until geometry.length()) {
                val g = geometry.optJSONObject(j) ?: continue
                pts.add(GeoPoint(g.optDouble("lat"), g.optDouble("lon")))
            }
            if (pts.size >= 2) ways.add(pts)
        }
        if (ways.isEmpty()) return null
        return Link(refA, refB, ways)
    }

    fun add(rawNodes: List<JSONObject>, rawRelations: List<JSONObject>) {
        var newNodes = false
        for (raw in rawNodes) {
            val id = raw.optLong("id")
            if (nodes.containsKey(id)) continue
            val tags = raw.optJSONObject("tags") ?: continue
            val ref = tags.optString("rcn_ref")
            if (ref.isEmpty()) continue
            val lat = raw.optDouble("lat", Double.NaN)
            val lon = raw.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val node = KNode(id, ref, GeoPoint(lat, lon))
            nodes[id] = node
            byRef.getOrPut(norm(ref)) { ArrayList() }.add(node)
            newNodes = true
        }
        for (rel in rawRelations) {
            val id = rel.optLong("id")
            if (!seenRelations.add(id)) continue
            val link = parse(rel) ?: continue
            if (!tryLink(link)) unlinked.add(link)
        }
        if (newNodes && unlinked.isNotEmpty()) {
            val it = unlinked.iterator()
            while (it.hasNext()) {
                if (tryLink(it.next())) it.remove()
            }
        }
    }

    fun snapshot(): Network = Network(HashMap(nodes), ArrayList(edges))
}

/** Haalt knooppuntgegevens op bij OpenStreetMap (Overpass) en bewaart ze per vakje op het toestel. */
object Overpass {
    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter"
    )

    // De server die het laatst werkte, probeert de app als eerste.
    @Volatile private var preferred = 0

    @Volatile var lastError = ""

    fun fetch(south: Double, west: Double, north: Double, east: Double): String? {
        val box = "$south,$west,$north,$east"
        val query = "[out:json][timeout:25];" +
            "(node[\"rcn_ref\"]($box);" +
            "relation[\"route\"=\"bicycle\"][\"network\"=\"rcn\"]($box););" +
            "out geom;"
        val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()
        // Twee rondes langs de servers; een drukke server is vaak een paar seconden later weer vrij.
        for (round in 0 until 2) {
            for (offset in servers.indices) {
                val index = (preferred + offset) % servers.size
                val server = servers[index]
                try {
                    val c = URL(server).openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 30000
                    c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
                    c.outputStream.use { it.write(body) }
                    val code = c.responseCode
                    if (code == 200) {
                        val text = c.inputStream.bufferedReader().use { it.readText() }
                        if (text.contains("\"elements\"") && !text.contains("runtime error")) {
                            preferred = index
                            return text
                        }
                        lastError = "onvolledig antwoord"
                    } else {
                        lastError = "melding $code"
                    }
                } catch (e: Exception) {
                    lastError = e.javaClass.simpleName
                }
            }
            if (round == 0) {
                try {
                    Thread.sleep(2500)
                } catch (_: InterruptedException) {
                    return null
                }
            }
        }
        return null
    }

    /** Afstand in meters van punt p tot het lijnstuk a-b (vlakke benadering, ruim genoeg voor 150 m). */
    private fun segDist(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val k = Math.cos(Math.toRadians(p.latitude))
        val mx = 111320.0 * k
        val my = 110540.0
        val ax = (a.longitude - p.longitude) * mx
        val ay = (a.latitude - p.latitude) * my
        val bx = (b.longitude - p.longitude) * mx
        val by = (b.latitude - p.latitude) * my
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-9) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(ax + t * dx, ay + t * dy)
    }

    /** Voert het werk voor elk stuk route tegelijk uit (drie tegelijk) en voegt de uitkomsten samen. null = alles mislukt. */
    private fun <T> parallel(chunks: List<List<GeoPoint>>, work: (List<GeoPoint>) -> List<T>?): List<T>? {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            val futures = chunks.map { c -> pool.submit<List<T>?> { try { work(c) } catch (_: Exception) { null } } }
            val all = ArrayList<T>()
            var ok = 0
            for (f in futures) {
                val r = f.get()
                if (r != null) { ok++; all.addAll(r) }
            }
            return if (ok == 0) null else all
        } finally {
            pool.shutdown()
        }
    }

    /** Cafés, restaurants en toiletten direct langs de route (150 m). */
    fun pois(line: List<GeoPoint>): List<Poi>? {
        if (line.size < 2) return null
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        return parallel(splitChunks(line, 6000.0)) { chunk ->
            val box = boxOf(chunk, 0.0025)
            val text = post("[out:json][timeout:25];nwr[\"amenity\"~\"^(cafe|restaurant|toilets|fast_food|pub|ice_cream)$\"]($box);out center 800;") ?: return@parallel null
            val out = ArrayList<Poi>()
            val arr = JSONObject(text).getJSONArray("elements")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val tags = o.optJSONObject("tags") ?: continue
                val kind = tags.optString("amenity")
                val name = tags.optString("name", "")
                if (kind != "toilets" && name.isEmpty()) continue
                val lat = if (o.has("lat")) o.getDouble("lat") else o.optJSONObject("center")?.getDouble("lat") ?: continue
                val lon = if (o.has("lon")) o.getDouble("lon") else o.optJSONObject("center")?.getDouble("lon") ?: continue
                val pos = GeoPoint(lat, lon)
                var near = Double.MAX_VALUE
                for (j in 1 until chunk.size) {
                    near = minOf(near, segDist(pos, chunk[j - 1], chunk[j]))
                    if (near < 150.0) break
                }
                if (near > 150.0 || !seen.add(o.getString("type") + o.getLong("id"))) continue
                out.add(Poi(name.ifEmpty { "Toilet" }, kind, pos))
            }
            out
        }
    }

    /** Plaatsen (stad, dorp, gehucht) tot ongeveer 1,5 km van de route. */
    fun towns(line: List<GeoPoint>): List<Town>? {
        if (line.size < 2) return null
        val seen = java.util.Collections.synchronizedSet(HashSet<Long>())
        return parallel(splitChunks(line, 12000.0)) { chunk ->
            val box = boxOf(chunk, 0.02)
            val text = post("[out:json][timeout:25];node[\"place\"~\"^(city|town|village|hamlet)$\"][\"name\"]($box);out;") ?: return@parallel null
            val out = ArrayList<Town>()
            val arr = JSONObject(text).getJSONArray("elements")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optJSONObject("tags") ?: continue
                val pos = GeoPoint(o.getDouble("lat"), o.getDouble("lon"))
                var near = Double.MAX_VALUE
                for (j in 1 until chunk.size) {
                    near = minOf(near, segDist(pos, chunk[j - 1], chunk[j]))
                    if (near < 1500.0) break
                }
                if (near > 1500.0 || !seen.add(o.getLong("id"))) continue
                out.add(Town(t.getString("name"), t.optString("place"), pos, t.optString("population").filter { it.isDigit() }.toIntOrNull() ?: 0, t.optString("wikipedia").ifEmpty { null }))
            }
            out
        }
    }

    /** Bezienswaardigheden direct langs de route (200 m). */
    fun sights(line: List<GeoPoint>): List<Poi>? {
        if (line.size < 2) return null
        val seen = java.util.Collections.synchronizedSet(HashSet<String>())
        return parallel(splitChunks(line, 6000.0)) { chunk ->
            val box = boxOf(chunk, 0.003)
            val q = "[out:json][timeout:25];(" +
                "nwr[\"tourism\"~\"^(attraction|museum|viewpoint|artwork)$\"][\"name\"]($box);" +
                "nwr[\"historic\"~\"^(castle|monument|windmill|ruins|fort|city_gate|manor|memorial|archaeological_site)$\"][\"name\"]($box);" +
                "nwr[\"man_made\"~\"^(windmill|watermill|tower|lighthouse)$\"][\"name\"]($box););out center 400;"
            val text = post(q) ?: return@parallel null
            val out = ArrayList<Poi>()
            val arr = JSONObject(text).getJSONArray("elements")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.optJSONObject("tags") ?: continue
                val lat = if (o.has("lat")) o.getDouble("lat") else o.optJSONObject("center")?.getDouble("lat") ?: continue
                val lon = if (o.has("lon")) o.getDouble("lon") else o.optJSONObject("center")?.getDouble("lon") ?: continue
                val pos = GeoPoint(lat, lon)
                var near = Double.MAX_VALUE
                for (j in 1 until chunk.size) {
                    near = minOf(near, segDist(pos, chunk[j - 1], chunk[j]))
                    if (near < 200.0) break
                }
                if (near > 200.0 || !seen.add(o.getString("type") + o.getLong("id"))) continue
                out.add(Poi(t.getString("name"), "sight", pos))
            }
            out
        }
    }

    private fun splitChunks(line: List<GeoPoint>, meters: Double): List<List<GeoPoint>> {
        val chunks = ArrayList<List<GeoPoint>>()
        var cur = ArrayList<GeoPoint>()
        var acc = 0.0
        for (i in line.indices) {
            if (i > 0) acc += line[i - 1].distanceToAsDouble(line[i])
            cur.add(line[i])
            if (acc >= meters) {
                chunks.add(cur)
                cur = arrayListOf(line[i])
                acc = 0.0
            }
        }
        if (cur.size >= 2) chunks.add(cur)
        return chunks
    }

    private fun boxOf(chunk: List<GeoPoint>, pad: Double): String = String.format(
        java.util.Locale.US, "%.5f,%.5f,%.5f,%.5f",
        chunk.minOf { it.latitude } - pad, chunk.minOf { it.longitude } - pad * 1.6,
        chunk.maxOf { it.latitude } + pad, chunk.maxOf { it.longitude } + pad * 1.6
    )

    /** Korte tekst over een plaats uit Wikipedia: (tekst, link). */
    fun wikiSummary(name: String, tag: String?): Pair<String, String?>? {
        var lang = "nl"
        var title = name
        if (tag != null && tag.contains(":")) {
            lang = tag.substringBefore(":")
            title = tag.substringAfter(":")
        }
        return try {
            val url = URL("https://$lang.wikipedia.org/api/rest_v1/page/summary/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "%20"))
            val c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 10000
            c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            if (o.optString("type") == "disambiguation") return null
            val extract = o.optString("extract")
            if (extract.isEmpty()) null else extract to o.optJSONObject("content_urls")?.optJSONObject("mobile")?.optString("page")
        } catch (_: Exception) {
            null
        }
    }

    private fun post(query: String): String? {
        val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()
        for (offset in servers.indices) {
            val server = servers[(preferred + offset) % servers.size]
            try {
                val c = URL(server).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 5000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
                c.outputStream.use { it.write(body) }
                if (c.responseCode != 200) { lastError = "melding ${c.responseCode}"; continue }
                val text = c.inputStream.bufferedReader().use { it.readText() }
                if (text.contains("\"elements\"")) return text
                lastError = "onvolledig antwoord"
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
        }
        return null
    }

    fun cellFile(dir: File, y: Int, x: Int) = File(dir, "c20_${y}_${x}.json")
}

/** Fietsroute over echte wegen en fietspaden (BRouter), om naar het startpunt te leiden. */
object Router {
    fun route(from: GeoPoint, to: GeoPoint): List<GeoPoint>? {
        try {
            val url = "https://brouter.de/brouter?lonlats=" +
                "${from.longitude},${from.latitude}%7C${to.longitude},${to.latitude}" +
                "&profile=trekking&alternativeidx=0&format=geojson"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
            if (c.responseCode != 200) return null
            val text = c.inputStream.bufferedReader().use { it.readText() }
            val coords = JSONObject(text).getJSONArray("features").getJSONObject(0)
                .getJSONObject("geometry").getJSONArray("coordinates")
            val out = ArrayList<GeoPoint>(coords.length())
            for (i in 0 until coords.length()) {
                val c2 = coords.getJSONArray(i)
                out.add(GeoPoint(c2.getDouble(1), c2.getDouble(0)))
            }
            return if (out.size >= 2) out else null
        } catch (_: Exception) {
            return null
        }
    }
}

/** De knooppunten van heel Nederland, bij het bouwen van de app in de APK gezet (assets/knooppunten.json). */
object Bundled {
    private fun decode(s: String): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var i = 0
        var lat = 0
        var lon = 0
        while (i < s.length) {
            var shift = 0
            var result = 0
            var b: Int
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            shift = 0
            result = 0
            do {
                b = s[i++].code - 63
                result = result or ((b and 0x1f) shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
            out.add(GeoPoint(lat / 1e6, lon / 1e6))
        }
        return out
    }

    /** null als de app zonder meegeleverde gegevens is gebouwd. */
    fun load(context: Context): Network? {
        try {
            val text = context.assets.open("knooppunten.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val rawNodes = root.getJSONArray("nodes")
            val list = ArrayList<KNode>(rawNodes.length())
            val map = HashMap<Long, KNode>()
            for (i in 0 until rawNodes.length()) {
                val n = rawNodes.getJSONArray(i)
                val node = KNode(n.getLong(0), n.getString(1), GeoPoint(n.getDouble(2), n.getDouble(3)))
                list.add(node)
                map[node.id] = node
            }
            val rawEdges = root.getJSONArray("edges")
            val edges = ArrayList<KEdge>(rawEdges.length())
            for (i in 0 until rawEdges.length()) {
                val e = rawEdges.getJSONArray(i)
                val pts = decode(e.getString(2))
                if (pts.size < 2) continue
                var length = 0.0
                for (k in 1 until pts.size) length += pts[k - 1].distanceToAsDouble(pts[k])
                val back = if (e.length() > 3) decode(e.getString(3)).takeIf { it.size >= 2 } else null
                edges.add(KEdge(list[e.getInt(0)], list[e.getInt(1)], pts, length, back))
            }
            return if (edges.isEmpty()) null else Network(map, edges)
        } catch (_: Exception) {
            return null
        }
    }
}
