package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which model answered each point of an `ign-lidar-hd` provider, offline. */
class TerrainOriginTest {
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

    /** IGN answers 321 m north of 47° N, no data south of it, and an HTTP 500 east of 5° E. */
    private fun ign(): ElevationProvider =
        ElevationSource.IGN_LIDAR_HD.provider { config ->
            { url ->
                when (config.encoding) {
                    TileEncoding.TERRARIUM -> flatTerrarium(config.tileSize, 123.0)
                    TileEncoding.BIL32 -> {
                        val (south, west) =
                            url
                                .substringAfter("BBOX=")
                                .substringBefore('&')
                                .split(',')
                                .map { it.toDouble() }
                        if (west > 5.0) throw TileFetchException(url, 500)
                        decodeBil32Tile(bil(config.tileSize, if (south > 47.0) 321f else -9999f), config.tileSize, url)
                    }
                }
            }
        }

    @Test
    fun every_point_says_which_model_answered_it() =
        runTest {
            val points =
                listOf(
                    LatLon(48.86, 2.35), // Paris: IGN
                    LatLon(45.0, 1.0), // Corrèze, an IGN survey gap here: Mapterhorn, in France
                    LatLon(45.07, 7.69), // Turin: Mapterhorn abroad, IGN never asked
                    LatLon(48.0, 6.0), // Vosges, IGN tile down: Mapterhorn after an error
                )
            val provider = ign()
            val samples = provider.setElevationsWithOrigin(points)
            assertEquals(
                listOf(
                    TerrainOrigin.IGN_LIDAR_HD,
                    TerrainOrigin.MAPTERHORN_MEASURED,
                    TerrainOrigin.MAPTERHORN,
                    TerrainOrigin.MAPTERHORN_MEASURED,
                ),
                samples.map { it.origin },
            )
            assertEquals(listOf(false, false, false, true), samples.map { it.afterError })
            assertEquals(listOf(321.0, 123.0, 123.0, 123.0), samples.map { it.elevation }.map { kotlin.math.round(it) })
            // The same values as the plain read.
            assertEquals(provider.setElevations(points).map { it.elevation }, samples.map { it.elevation })
        }

    @Test
    fun an_unknown_source_and_a_missing_one_are_said_so() =
        runTest {
            val custom = ElevationProviderConfig(zoomLevel = 3, tileSize = 4, tileUrlTemplate = "custom://{z}/{x}/{y}")
            val tile = flatTerrarium(4, 50.0)
            val other = ElevationProvider(custom) { tile }.setElevationsWithOrigin(listOf(LatLon(45.0, 6.0))).single()
            assertEquals(TerrainOrigin.OTHER, other.origin)

            val empty = RawTile(4, 4, ByteArray(64).also { for (i in 3 until 64 step 4) it[i] = 255.toByte() })
            val none = ElevationProvider(custom) { empty }.setElevationsWithOrigin(listOf(LatLon(45.0, 6.0))).single()
            assertEquals(TerrainOrigin.NONE, none.origin)
            assertEquals(0.0, none.elevation)
        }

    @Test
    fun only_lidar_derived_terrain_counts_as_measured() {
        assertTrue(TerrainOrigin.IGN_LIDAR_HD.measured)
        assertTrue(TerrainOrigin.MAPTERHORN_MEASURED.measured)
        assertFalse(TerrainOrigin.MAPTERHORN.measured)
        assertFalse(TerrainOrigin.OTHER.measured)
        assertFalse(TerrainOrigin.NONE.measured)
        assertEquals(listOf("ign-lidar-hd", "mapterhorn-measured", "mapterhorn", "other", "none"), TerrainOrigin.ids)
    }
}
