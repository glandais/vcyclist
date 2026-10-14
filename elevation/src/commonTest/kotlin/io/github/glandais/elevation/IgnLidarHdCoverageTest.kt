package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IgnLidarHdCoverageTest {
    /** French ground at the edge of the country: border towns, capes, islands, the Alpine border. */
    private val france =
        mapOf(
            "Bray-Dunes" to (51.08 to 2.52),
            "Calais" to (50.95 to 1.85),
            "Cap Gris-Nez" to (50.87 to 1.58),
            "Le Tréport" to (50.06 to 1.37),
            "Cap d'Antifer" to (49.69 to 0.16),
            "Barfleur" to (49.67 to -1.26),
            "La Hague" to (49.72 to -1.95),
            "Sept-Îles" to (48.88 to -3.49),
            "Ouessant" to (48.46 to -5.10),
            "Île de Sein" to (48.04 to -4.85),
            "Belle-Île" to (47.30 to -3.20),
            "Île d'Yeu" to (46.70 to -2.35),
            "Oléron" to (45.95 to -1.40),
            "Hendaye" to (43.36 to -1.78),
            "Arnéguy" to (43.11 to -1.28),
            "Somport" to (42.80 to -0.53),
            "Bagnères-de-Luchon" to (42.79 to 0.59),
            "Bourg-Madame" to (42.43 to 1.95),
            "Prats-de-Mollo" to (42.40 to 2.48),
            "Cerbère" to (42.44 to 3.17),
            "Porquerolles" to (43.00 to 6.20),
            "Menton" to (43.78 to 7.52),
            "Tende" to (44.09 to 7.59),
            "Col de Larche" to (44.42 to 6.89),
            "Montgenèvre" to (44.93 to 6.72),
            "Bonneval-sur-Arc" to (45.37 to 7.05),
            "Col du Petit-Saint-Bernard" to (45.68 to 6.88),
            "Chamonix" to (45.92 to 6.87),
            "Saint-Gingolph" to (46.39 to 6.80),
            "Divonne" to (46.36 to 6.14),
            "Jougne" to (46.76 to 6.39),
            "Goumois" to (47.26 to 6.95),
            "Saint-Louis" to (47.59 to 7.56),
            "Neuf-Brisach" to (48.02 to 7.58),
            "Lauterbourg" to (48.97 to 8.23),
            "Wissembourg" to (49.04 to 7.94),
            "Apach" to (49.47 to 6.37),
            "Longwy" to (49.52 to 5.76),
            "Givet" to (50.17 to 4.85),
            "Jeumont" to (50.29 to 4.10),
            "Halluin" to (50.78 to 3.12),
            "Bonifacio" to (41.39 to 9.16),
            "Cap Corse" to (43.01 to 9.41),
            "Îles Sanguinaires" to (41.88 to 8.58),
            "Paris" to (48.86 to 2.35),
        )

    private val abroad =
        mapOf(
            "London" to (51.51 to -0.13),
            "Brussels" to (50.85 to 4.35),
            "Luxembourg" to (49.61 to 6.13),
            "Frankfurt" to (50.11 to 8.68),
            "Zürich" to (47.37 to 8.54),
            "Turin" to (45.07 to 7.69),
            "Barcelona" to (41.39 to 2.17),
            "Madrid" to (40.42 to -3.70),
            "Bilbao" to (43.26 to -2.93),
            "Sardinia, Olbia" to (40.92 to 9.50),
            "Morocco, Dadès" to (31.53 to -5.92),
        )

    @Test
    fun french_ground_is_inside_and_abroad_is_outside() {
        for ((name, ll) in france) assertTrue(IgnLidarHdCoverage.contains(ll.first, ll.second), name)
        for ((name, ll) in abroad) assertFalse(IgnLidarHdCoverage.contains(ll.first, ll.second), name)
    }

    private fun tileAt(
        latitude: Double,
        longitude: Double,
        z: Int,
    ): TileCoordinates {
        val n = (1 shl z).toDouble()
        val lat = latitude * PI / 180.0
        val x = floor((longitude + 180.0) / 360.0 * n).toInt()
        val y = floor((1.0 - ln(tan(lat) + 1.0 / kotlin.math.cos(lat)) / PI) / 2.0 * n).toInt()
        return TileCoordinates(x, y, z)
    }

    @Test
    fun a_tile_is_fetched_when_it_touches_the_coverage() {
        for (z in listOf(15, 12, 9)) {
            for ((name, ll) in france) assertTrue(IgnLidarHdCoverage.intersects(tileAt(ll.first, ll.second, z)), "$name z$z")
            // IGN's own zoom. A z12 tile is ~10 km wide and a city a few km off the outline may
            // share one with France; that only costs a request.
            if (z == 15) {
                for ((name, ll) in abroad) assertFalse(IgnLidarHdCoverage.intersects(tileAt(ll.first, ll.second, z)), "$name z$z")
            }
        }
        // A tile larger than the country: no corner inside, the polygon inside the tile.
        assertTrue(IgnLidarHdCoverage.intersects(TileCoordinates(0, 0, 0)))
        assertTrue(IgnLidarHdCoverage.intersects(tileAt(46.5, 2.5, 4)))
        assertFalse(IgnLidarHdCoverage.intersects(tileAt(-33.9, 151.2, 4)), "Sydney")
    }

    @Test
    fun abroad_the_ign_source_requests_nothing_from_ign() =
        runTest {
            val asked = mutableListOf<String>()
            val provider =
                ElevationSource.IGN_LIDAR_HD.provider { config ->
                    { url ->
                        asked += url
                        when (config.encoding) {
                            TileEncoding.BIL32 -> decodeBil32Tile(bil(config.tileSize, 321f), config.tileSize, url)
                            TileEncoding.TERRARIUM -> flatTerrarium(config.tileSize, 123.0)
                        }
                    }
                }
            assertEquals(123.0, provider.getElevation(45.07, 7.69), 0.01, "Turin: read from the fallback")
            assertTrue(asked.none { "geopf" in it }, "no IGN request abroad: $asked")
            assertTrue(provider.surfaceModel!!.getElevation(45.07, 7.69).isNaN(), "no surface model abroad")
            assertTrue(asked.none { "geopf" in it }, "nor for the surface model: $asked")

            assertEquals(321.0, provider.getElevation(48.86, 2.35), 0.01, "Paris: read from IGN")
            assertTrue(asked.any { "geopf" in it })
        }

    private fun bil(
        size: Int,
        value: Float,
    ): ByteArray {
        val out = ByteArray(size * size * 4)
        val bits = value.toRawBits()
        for (i in 0 until size * size) for (k in 0 until 4) out[i * 4 + k] = (bits shr (8 * k)).toByte()
        return out
    }

    private fun flatTerrarium(
        size: Int,
        metres: Double,
    ): RawTile {
        val packed = encodeTerrarium(metres)
        val rgba = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            rgba[i * 4] = (packed shr 16).toByte()
            rgba[i * 4 + 1] = (packed shr 8).toByte()
            rgba[i * 4 + 2] = packed.toByte()
            rgba[i * 4 + 3] = 255.toByte()
        }
        return RawTile(size, size, rgba)
    }
}
