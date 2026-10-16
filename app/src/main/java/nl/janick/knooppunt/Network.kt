package nl.janick.knooppunt

import android.content.Context
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.PriorityQueue

/** Een fietsknooppunt (rcn_ref in OpenStreetMap). */
class KNode(val id: Long, val ref: String, val pos: GeoPoint)

/** Een stuk fietsroute tussen twee knooppunten. De punten lopen van a naar b. */
class KEdge(val a: KNode, val b: KNode, val pts: List<GeoPoint>, val length: Double) {
    val minLat = pts.minOf { it.latitude }
    val maxLat = pts.maxOf { it.latitude }
    val minLon = pts.minOf { it.longitude }
    val maxLon = pts.maxOf { it.longitude }
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
            val seq = if (forward) e.pts else e.pts.reversed()
            for ((i, p) in seq.withIndex()) {
                if (i == 0 && pts.isNotEmpty()) continue
                pts.add(p)
            }
            length += e.length
        }
        return Leg(pts, length, true)
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
                edges.add(KEdge(list[e.getInt(0)], list[e.getInt(1)], pts, length))
            }
            return if (edges.isEmpty()) null else Network(map, edges)
        } catch (_: Exception) {
            return null
        }
    }
}
