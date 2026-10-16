package nl.janick.knooppunt

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

object NetworkBuilder {
    private val linkRegex = Regex("""(\d{1,3})\s*[-–>]+\s*(\d{1,3})""")

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
                for (p in w) {
                    val d = n.pos.distanceToAsDouble(p)
                    if (d < bestDist) { bestDist = d; best = n }
                }
            }
        }
        return best
    }

    fun build(rawNodes: Collection<JSONObject>, rawRelations: Collection<JSONObject>): Network {
        val nodes = HashMap<Long, KNode>()
        val byRef = HashMap<String, MutableList<KNode>>()
        for (raw in rawNodes) {
            val tags = raw.optJSONObject("tags") ?: continue
            val ref = tags.optString("rcn_ref")
            if (ref.isEmpty()) continue
            val lat = raw.optDouble("lat", Double.NaN)
            val lon = raw.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val node = KNode(raw.optLong("id"), ref, GeoPoint(lat, lon))
            nodes[node.id] = node
            byRef.getOrPut(norm(ref)) { ArrayList() }.add(node)
        }

        val edges = ArrayList<KEdge>()
        for (rel in rawRelations) {
            val tags = rel.optJSONObject("tags") ?: continue
            var text = tags.optString("ref")
            if (!linkRegex.containsMatchIn(text)) text = tags.optString("name")
            if (!linkRegex.containsMatchIn(text)) text = tags.optString("note")
            val m = linkRegex.find(text) ?: continue
            val refA = norm(m.groupValues[1])
            val refB = norm(m.groupValues[2])
            if (refA == refB) continue

            val members = rel.optJSONArray("members") ?: continue
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
            if (ways.isEmpty()) continue

            val a = nearest(byRef[refA], ways) ?: continue
            val b = nearest(byRef[refB], ways) ?: continue
            if (a.id == b.id) continue

            val chained = chain(ways, a.pos)
            if (chained.size < 2) continue
            val pts = ArrayList<GeoPoint>(chained.size + 2)
            pts.add(a.pos)
            pts.addAll(chained)
            pts.add(b.pos)
            edges.add(KEdge(a, b, pts, lengthOf(pts)))
        }
        return Network(nodes, edges)
    }
}

/** Haalt knooppuntgegevens op bij OpenStreetMap (Overpass) en bewaart ze per vakje op het toestel. */
object Overpass {
    private val servers = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    @Volatile var lastError = ""

    fun fetch(south: Double, west: Double, north: Double, east: Double): String? {
        val box = "$south,$west,$north,$east"
        val query = "[out:json][timeout:40];" +
            "(node[\"rcn_ref\"]($box);" +
            "relation[\"route\"=\"bicycle\"][\"network\"=\"rcn\"]($box););" +
            "out geom;"
        for (server in servers) {
            try {
                val c = URL(server).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 10000
                c.readTimeout = 45000
                c.setRequestProperty("User-Agent", "Knooppunt (nl.janick.knooppunt)")
                c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
                val code = c.responseCode
                if (code == 200) {
                    val text = c.inputStream.bufferedReader().use { it.readText() }
                    if (text.contains("\"elements\"") && !text.contains("runtime error")) return text
                    lastError = "server gaf onvolledig antwoord"
                } else {
                    lastError = "server meldt $code"
                }
            } catch (e: Exception) {
                lastError = e.javaClass.simpleName
            }
        }
        return null
    }

    fun cellFile(dir: File, y: Int, x: Int) = File(dir, "c20_${y}_${x}.json")
}
