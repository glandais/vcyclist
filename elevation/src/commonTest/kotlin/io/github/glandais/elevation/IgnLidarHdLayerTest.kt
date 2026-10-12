package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Little-endian float32 grid, the body IGN's WMS answers with. */
private fun bilBytes(
    tileSize: Int,
    sample: (x: Int, y: Int) -> Float,
): ByteArray {
    val out = ByteArray(tileSize * tileSize * 4)
    for (y in 0 until tileSize) {
        for (x in 0 until tileSize) {
            val bits = sample(x, y).toRawBits()
            val o = (y * tileSize + x) * 4
            for (k in 0 until 4) out[o + k] = (bits shr (8 * k)).toByte()
        }
    }
    return out
}

class IgnLidarHdLayerTest {
    private val tile = TileCoordinates(x = 16_934, y = 11_728, z = 15)

    private suspend fun urlOf(config: ElevationProviderConfig): String {
        var seen = ""
        TileManager(config.tileUrlTemplate, 1) { url ->
            seen = url
            RawTile(1, 1, ByteArray(4))
        }.getTile(tile)
        return seen
    }

    @Test
    fun `each layer asks the WMS for its own LAYERS name, as BIL32 at 256 px and 20 requests per second`() =
        runTest {
            val names =
                mapOf(
                    IgnLidarHdLayer.MNT to "IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G",
                    IgnLidarHdLayer.MNS to "IGNF_LIDAR-HD_MNS_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G",
                    IgnLidarHdLayer.MNH to "IGNF_LIDAR-HD_MNH_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G",
                )
            assertEquals(IgnLidarHdLayer.entries.toSet(), names.keys, "every layer is pinned")
            for ((layer, name) in names) {
                val config = layer.config()
                val url = urlOf(config)
                assertTrue(url.startsWith("https://data.geopf.fr/wms-r/wms?"), url)
                assertTrue("LAYERS=$name&" in url, url)
                assertTrue("BBOX=${wmsBbox4326(tile)}&" in url, url)
                assertTrue("WIDTH=256&HEIGHT=256" in url, url)
                assertTrue("FORMAT=image%2Fx-bil%3Bbits%3D32" in url, url)
                assertTrue("{" !in url, "unfilled placeholder in $url")
                assertEquals(256, config.tileSize)
                assertEquals(TileEncoding.BIL32, config.encoding)
                assertEquals(20, config.maxRequestsPerSecond)
                assertEquals(15, config.zoomLevel)
                assertEquals(13, layer.config(13).zoomLevel)
                assertEquals("© IGN — LiDAR HD ${layer.name}, Licence Ouverte Etalab 2.0", config.attribution.text)
            }
        }

    @Test
    fun `the IGN LiDAR HD source is the MNT layer, spelled once`() {
        assertEquals(IgnLidarHdLayer.MNT.config(), ElevationSource.IGN_LIDAR_HD.config())
        assertEquals(IgnLidarHdLayer.MNT.config(13), ElevationSource.IGN_LIDAR_HD.config(13))
    }

    @Test
    fun `the DEM catalogue stays closed to the surface and height layers`() {
        assertEquals(listOf("mapterhorn", "ign-lidar-hd"), ElevationSource.ids)
        for (id in listOf("mns", "mnh", "MNS", "MNH", "ign-lidar-hd-mns", "ign-lidar-hd-mnh")) {
            assertFailsWith<IllegalArgumentException> { ElevationSource.byId(id) }
        }
        for (source in ElevationSource.entries) {
            val template = source.config().tileUrlTemplate
            assertFalse("MNS" in template || "MNH" in template, "$source reads $template")
        }
    }

    private fun layerProvider(sample: (x: Int, y: Int) -> Float): ElevationProvider =
        IgnLidarHdLayer.MNS.provider(zoomLevel = 0) { config ->
            { decodeBil32Tile(bilBytes(config.tileSize, sample), config.tileSize) }
        }

    @Test
    fun `a layer's no-data reads NaN, never 0 m and never a fallback`() =
        runTest {
            val provider = layerProvider { _, _ -> -9999f }
            assertNull(provider.fallback)
            assertTrue(provider.getElevation(45.0, 6.0).isNaN())
            assertTrue(provider.getElevation(45.0, 6.0, interpolation = false).isNaN())
            val batch = provider.setElevations(listOf(LatLon(45.0, 6.0), LatLon(-10.0, 120.0)))
            assertTrue(batch.all { it.elevation.isNaN() }, "$batch")
        }

    @Test
    fun `a single no-data pixel makes its bilinear neighbourhood NaN, and only that`() =
        runTest {
            // z0, 256 px: the no-data column is x = 128, i.e. longitude 0..1.4°.
            val provider = layerProvider { x, _ -> if (x == 128) -9999f else 42f }
            assertTrue(provider.getElevation(10.0, 0.7).isNaN(), "on the no-data pixel")
            assertTrue(provider.getElevation(10.0, -0.7).isNaN(), "a neighbour weighs the no-data pixel")
            assertEquals(42.0, provider.getElevation(10.0, 20.0), 0.01)
        }

    @Test
    fun `a layer provider refuses a fallback`() {
        val fallback =
            ElevationProvider(ElevationProviderConfig(zoomLevel = 0, tileSize = 4, tileUrlTemplate = "f://{z}/{x}/{y}")) {
                decodeBil32Tile(bilBytes(4) { _, _ -> 7f }, 4)
            }
        assertFailsWith<IllegalArgumentException> { layerProvider { _, _ -> -9999f }.withFallback(fallback) }
    }

    @Test
    fun `the DEM source still falls back to Mapterhorn, and a plain provider still reads 0 m`() =
        runTest {
            val ign =
                ElevationSource.IGN_LIDAR_HD.provider(zoomLevel = 0) { config ->
                    when (config.encoding) {
                        TileEncoding.BIL32 -> { _ -> decodeBil32Tile(bilBytes(config.tileSize) { _, _ -> -9999f }, config.tileSize) }
                        TileEncoding.TERRARIUM -> { _ -> decodeBil32Tile(bilBytes(config.tileSize) { _, _ -> 321f }, config.tileSize) }
                    }
                }
            assertEquals(321.0, ign.getElevation(45.0, 6.0), 0.01)
            val plain =
                ElevationProvider(ElevationProviderConfig(zoomLevel = 0, tileSize = 4, tileUrlTemplate = "t://{z}/{x}/{y}")) {
                    decodeBil32Tile(bilBytes(4) { _, _ -> -9999f }, 4)
                }
            assertEquals(0.0, plain.getElevation(45.0, 6.0), 1e-12)
        }
}
