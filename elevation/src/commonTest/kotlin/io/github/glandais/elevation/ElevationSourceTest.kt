package io.github.glandais.elevation

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Little-endian float32 grid, the body IGN's WMS answers with. */
private fun bil(
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

/** A Terrarium tile reading [metres] everywhere. */
private fun flatTerrarium(
    tileSize: Int,
    metres: Int,
): RawTile {
    val rgba = ByteArray(tileSize * tileSize * 4)
    val raw = metres + 32768
    for (i in rgba.indices step 4) {
        rgba[i] = (raw shr 8).toByte()
        rgba[i + 1] = raw.toByte()
        rgba[i + 3] = 255.toByte()
    }
    return RawTile(tileSize, tileSize, rgba)
}

class ElevationSourceTest {
    // ── BIL32 decoding ──────────────────────────────────────────────────────────────────────

    @Test
    fun `a BIL32 sample survives the Terrarium re-encoding to the centimetre`() {
        val values = floatArrayOf(1696.7573f, 0.0f, -3.25f, 4808.72f)
        val tile = Tile(decodeBil32Tile(bil(2) { x, y -> values[y * 2 + x] }, 2))
        for (i in values.indices) {
            val got = tile.getElevation(Pixel(TileCoordinates(0, 0, 0), i % 2, i / 2))
            assertTrue(abs(got - values[i]) <= 0.01, "sample $i: ${values[i]} decoded as $got")
        }
    }

    @Test
    fun `IGN's no-data marker and NaN both read as no data`() {
        val tile = Tile(decodeBil32Tile(bil(2) { x, _ -> if (x == 0) -9999f else Float.NaN }, 2))
        for (x in 0..1) {
            assertTrue(tile.getElevation(Pixel(TileCoordinates(0, 0, 0), x, 0)).isNaN())
        }
    }

    @Test
    fun `a body of the wrong length is rejected, and an XML one is named`() {
        val short = assertFailsWith<IllegalStateException> { decodeBil32Tile(ByteArray(12), 2, "u") }
        assertTrue("12 bytes, expected 16" in short.message!!, short.message)
        val xml = assertFailsWith<IllegalStateException> { decodeBil32Tile("<ServiceException/>".encodeToByteArray(), 2) }
        assertTrue("XML" in xml.message!!, xml.message)
    }

    @Test
    fun `the Terrarium code 0 is reserved for no data`() {
        assertEquals(1, encodeTerrarium(-40_000.0))
        assertEquals(0xFFFFFF, encodeTerrarium(40_000.0))
    }

    // ── WMS geometry ────────────────────────────────────────────────────────────────────────

    @Test
    fun `the WMS bbox is south, west, north, east of the XYZ tile`() {
        val parts = wmsBbox4326(TileCoordinates(x = 0, y = 0, z = 1)).split(',').map { it.toDouble() }
        assertEquals(4, parts.size)
        assertEquals(0.0, parts[0], 1e-9)
        assertEquals(-180.0, parts[1], 1e-12)
        assertEquals(EarthConstants.WEB_MERCATOR_MAX_LAT, parts[2], 1e-9)
        assertEquals(0.0, parts[3], 1e-12)
    }

    @Test
    fun `the IGN template is filled with the tile's bbox and asks for its own tile size`() =
        runTest {
            val config = ElevationSource.IGN_LIDAR_HD.config()
            var seen = ""
            TileManager(config.tileUrlTemplate, 1) { url ->
                seen = url
                flatTerrarium(1, 0)
            }.getTile(TileCoordinates(x = 16_934, y = 11_728, z = 15))
            assertTrue(seen.startsWith("https://data.geopf.fr/wms-r/wms?"), seen)
            assertTrue("LAYERS=IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G" in seen, seen)
            assertTrue("WIDTH=${config.tileSize}&HEIGHT=${config.tileSize}" in seen, seen)
            assertTrue("BBOX=${wmsBbox4326(TileCoordinates(16_934, 11_728, 15))}&" in seen, seen)
            assertTrue("{" !in seen, "unfilled placeholder in $seen")
        }

    // ── Catalogue ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `sources are found by wire id, and an unknown id is an error`() {
        for (source in ElevationSource.entries) assertSame(source, ElevationSource.byId(source.id))
        val ex = assertFailsWith<IllegalArgumentException> { ElevationSource.byId("ign") }
        assertTrue("mapterhorn, ign-lidar-hd" in ex.message!!, ex.message)
    }

    @Test
    fun `Mapterhorn's config is the library default, so naming it changes nothing`() {
        assertEquals(ElevationProviderConfig(), ElevationSource.MAPTERHORN.config())
        assertEquals(ElevationSource.MAPTERHORN, ElevationSource.DEFAULT)
    }

    @Test
    fun `IGN LiDAR HD is BIL, at its own default zoom, overridable, and falls back to Mapterhorn`() {
        val ign = ElevationSource.IGN_LIDAR_HD
        assertEquals(TileEncoding.BIL32, ign.config().encoding)
        assertEquals(ign.defaultZoomLevel, ign.config().zoomLevel)
        assertEquals(13, ign.config(13).zoomLevel)
        assertEquals(ElevationSource.MAPTERHORN, ign.fallback)
        assertNull(ElevationSource.MAPTERHORN.fallback)
    }

    @Test
    fun `provider chains the fallback at its own default zoom, whatever zoom the primary got`() {
        val configs = mutableListOf<ElevationProviderConfig>()
        val provider =
            ElevationSource.IGN_LIDAR_HD.provider(zoomLevel = 14) { config ->
                configs += config
                { flatTerrarium(config.tileSize, 0) }
            }
        assertEquals(14, provider.config.zoomLevel)
        assertEquals(ElevationSource.MAPTERHORN.config(), provider.fallback?.config)
        assertEquals(listOf(TileEncoding.BIL32, TileEncoding.TERRARIUM), configs.map { it.encoding })
    }

    // ── Surface model (bridge decks, E3) ────────────────────────────────────────────────────

    @Test
    fun `IGN LiDAR HD at its default zoom carries the MNS as its surface model, through the same fetcherFor`() {
        val configs = mutableListOf<ElevationProviderConfig>()
        val provider =
            ElevationSource.IGN_LIDAR_HD.provider { config ->
                configs += config
                { flatTerrarium(config.tileSize, 0) }
            }
        val surface = assertNotNull(provider.surfaceModel)
        assertEquals(IgnLidarHdLayer.MNS.config(), surface.config)
        assertNull(surface.surfaceModel)
        assertTrue(IgnLidarHdLayer.MNS.config() in configs, "the MNS is fetched through fetcherFor: $configs")
        assertNull(provider.fallback?.surfaceModel, "the Mapterhorn fallback carries none")
    }

    @Test
    fun `no surface model at another zoom, nor for Mapterhorn, nor for a hand-built provider`() {
        assertNull(ElevationSource.IGN_LIDAR_HD.provider(14) { config -> { flatTerrarium(config.tileSize, 0) } }.surfaceModel)
        assertNull(ElevationSource.MAPTERHORN.provider { config -> { flatTerrarium(config.tileSize, 0) } }.surfaceModel)
        assertNull(ElevationProvider(ElevationSource.IGN_LIDAR_HD.config()) { flatTerrarium(256, 0) }.surfaceModel)
    }

    @Test
    fun `withFallback keeps the surface model, withSurfaceModel null detaches it and keeps the fallback`() {
        val fetcher: (ElevationProviderConfig) -> suspend (String) -> RawTile = { config -> { flatTerrarium(config.tileSize, 0) } }
        val ign = ElevationSource.IGN_LIDAR_HD.provider(null, fetcher)
        val surface = ign.surfaceModel
        val other = ElevationSource.MAPTERHORN.provider(null, fetcher)
        assertSame(surface, ign.withFallback(other).surfaceModel)
        val bare = ign.withSurfaceModel(null)
        assertNull(bare.surfaceModel)
        assertSame(ign.fallback, bare.fallback)
        assertSame(surface, bare.withSurfaceModel(surface).surfaceModel)
    }

    @Test
    fun `a surface model must be a raw layer, and a raw layer carries none`() {
        val fetcher: (ElevationProviderConfig) -> suspend (String) -> RawTile = { config -> { flatTerrarium(config.tileSize, 0) } }
        val terrain = ElevationSource.MAPTERHORN.provider(null, fetcher)
        val mns = IgnLidarHdLayer.MNS.provider(null, fetcher)
        assertFailsWith<IllegalArgumentException> { terrain.withSurfaceModel(ElevationSource.MAPTERHORN.provider(null, fetcher)) }
        assertFailsWith<IllegalArgumentException> { mns.withSurfaceModel(IgnLidarHdLayer.MNS.provider(null, fetcher)) }
        assertFailsWith<IllegalArgumentException> { mns.withSurfaceModel(null) }
        assertSame(mns, terrain.withSurfaceModel(mns).surfaceModel)
    }

    // ── No data, end to end ─────────────────────────────────────────────────────────────────

    private fun noDataProvider(): ElevationProvider =
        ElevationProvider(ElevationProviderConfig(zoomLevel = 0, tileSize = 4, tileUrlTemplate = "t://{z}/{x}/{y}")) {
            decodeBil32Tile(bil(4) { _, _ -> -9999f }, 4)
        }

    @Test
    fun `a no-data point without fallback reads 0 m, never NaN`() =
        runTest {
            assertEquals(0.0, noDataProvider().getElevation(45.0, 6.0))
            assertEquals(0.0, noDataProvider().getElevation(45.0, 6.0, interpolation = false))
        }

    @Test
    fun `a no-data point is asked of the fallback, interpolated or not`() =
        runTest {
            val fallback =
                ElevationProvider(ElevationProviderConfig(zoomLevel = 0, tileSize = 4, tileUrlTemplate = "f://{z}/{x}/{y}")) {
                    flatTerrarium(4, 321)
                }
            val provider = noDataProvider().withFallback(fallback)
            assertEquals(321.0, provider.getElevation(45.0, 6.0))
            assertEquals(321.0, provider.getElevation(45.0, 6.0, interpolation = false))
            assertEquals(
                listOf(321.0, 321.0),
                provider.setElevations(listOf(LatLon(45.0, 6.0), LatLon(-10.0, 120.0))).map { it.elevation },
            )
        }

    @Test
    fun `a point with data never touches the fallback`() =
        runTest {
            var asked = 0
            val fallback =
                ElevationProvider(ElevationProviderConfig(zoomLevel = 0, tileSize = 4, tileUrlTemplate = "f://{z}/{x}/{y}")) {
                    asked++
                    flatTerrarium(4, 321)
                }
            val provider =
                ElevationProvider(ElevationProviderConfig(zoomLevel = 0, tileSize = 4, tileUrlTemplate = "t://{z}/{x}/{y}")) {
                    decodeBil32Tile(bil(4) { _, _ -> 1200f }, 4)
                }.withFallback(fallback)
            assertEquals(1200.0, provider.getElevation(45.0, 6.0), 0.01)
            assertEquals(0, asked)
        }
}

class TileRequestPacingTest {
    @Test
    fun `requests start no faster than the configured rate, even when launched together`() =
        runTest {
            val starts = mutableListOf<Long>()
            val fetch =
                pacedTileBytesFetcher(4, { _ ->
                    starts += testScheduler.currentTime
                    ByteArray(0)
                }, now = { testScheduler.currentTime })
            kotlinx.coroutines.coroutineScope {
                repeat(5) { launch { fetch("u$it") } }
            }
            assertEquals(listOf(0L, 250L, 500L, 750L, 1000L), starts.sorted())
        }

    @Test
    fun `a 429 is retried after the server's Retry-After, then succeeds`() =
        runTest {
            var calls = 0
            val fetch =
                pacedTileBytesFetcher(0, { url ->
                    calls++
                    if (calls < 3) throw TileFetchException(url, 429, retryAfterSeconds = 4)
                    byteArrayOf(1)
                })
            assertEquals(1, fetch("u").size)
            assertEquals(3, calls)
            assertEquals(8_000L, testScheduler.currentTime, "two waits of the advertised 4 s")
        }

    @Test
    fun `without Retry-After the backoff doubles, and gives up after maxAttempts`() =
        runTest {
            var calls = 0
            val fetch =
                pacedTileBytesFetcher(0, { url ->
                    calls++
                    throw TileFetchException(url, 503)
                }, maxAttempts = 4)
            val ex = assertFailsWith<TileFetchException> { fetch("u") }
            assertEquals(503, ex.status)
            assertEquals(4, calls)
            assertEquals(1_000L + 2_000L + 4_000L, testScheduler.currentTime)
        }

    @Test
    fun `a 404 is an answer, not a congestion — no retry`() =
        runTest {
            var calls = 0
            val fetch =
                pacedTileBytesFetcher(0, { url ->
                    calls++
                    throw TileFetchException(url, 404)
                })
            assertFailsWith<TileFetchException> { fetch("u") }
            assertEquals(1, calls)
        }
}
