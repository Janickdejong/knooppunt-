package nl.janick.knooppunt

import org.osmdroid.tileprovider.cachemanager.CacheManager
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Slaat alleen de kaartplaatjes langs je route op, zodat de kaart ook zonder bereik werkt. */
object OfflineTiles {
    fun plan(route: List<GeoPoint>): List<Long> {
        val set = LinkedHashSet<Long>()
        if (route.size < 2) return emptyList()
        val dense = Track(route).pts
        for (z in 14..17) {
            val r = if (z <= 14) 0 else 1
            val n = 1 shl z
            var i = 0
            while (i < dense.size) {
                val p = dense[i]
                val x = floor((p.longitude + 180.0) / 360.0 * n).toInt()
                val rad = Math.toRadians(p.latitude)
                val y = floor((1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n).toInt()
                for (dx in -r..r) for (dy in -r..r) {
                    set.add(MapTileIndex.getTileIndex(z, x + dx, y + dy))
                }
                i += 8
            }
        }
        return set.toList()
    }

    fun download(map: MapView, tiles: List<Long>, progress: (Int, Int) -> Unit, done: (Int) -> Unit) {
        val src = map.tileProvider.tileSource as? OnlineTileSourceBase
        if (src == null) {
            done(0)
            return
        }
        val cm = CacheManager(map)
        var ok = 0
        for ((i, t) in tiles.withIndex()) {
            try {
                if (cm.loadTile(src, t)) ok++
            } catch (_: Exception) {
            }
            if (i % 20 == 0) progress(i, tiles.size)
            Thread.sleep(25)
        }
        done(ok)
    }
}
