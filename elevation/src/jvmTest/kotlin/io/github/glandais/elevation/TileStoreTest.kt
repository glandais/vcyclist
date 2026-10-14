package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The [TileStore] fetcher: the key it files a tile under, and the cache contract, offline. */
class TileStoreTest {
    /** The URL `TileManager` builds for [tc] from [config]'s template. */
    private fun urlOf(
        config: ElevationProviderConfig,
        tc: TileCoordinates,
    ): String =
        config.tileUrlTemplate
            .replace("{z}", tc.z.toString())
            .replace("{x}", tc.x.toString())
            .replace("{y}", tc.y.toString())
            .let { if ("{bbox}" in it) it.replace("{bbox}", wmsBbox4326(tc)) else it }

    @Test
    fun every_tier_reads_its_tile_back_from_the_url() {
        val configs =
            listOf(ElevationSource.MAPTERHORN.config(), ElevationSource.MAPTERHORN.config(5)) +
                IgnLidarHdLayer.entries.flatMap { listOf(it.config(15), it.config(9)) }
        for (config in configs) {
            val z = config.zoomLevel
            val n = 1 shl z
            // France at z15 is x ≈ 15 900..17 900, y ≈ 10 900..12 500; the pyramid's corners too.
            val xs = listOf(0, n / 2 - 1, n / 2, n - 1, (n * 0.505).toInt(), (n * 0.52).toInt())
            val ys = listOf(0, n / 2 - 1, n / 2, n - 1, (n * 0.34).toInt(), (n * 0.37).toInt())
            for (x in xs) {
                for (y in ys) {
                    val tc = TileCoordinates(x, y, z)
                    assertEquals(tc, tileCoordinatesOf(urlOf(config, tc), config), "${config.tileUrlTemplate} $tc")
                }
            }
        }
    }

    @Test
    fun the_layers_have_stable_names() {
        assertEquals("mapterhorn", tileLayerOf(ElevationSource.MAPTERHORN.config(12)))
        assertEquals("ign-lidar-hd-mnt", tileLayerOf(ElevationSource.IGN_LIDAR_HD.config(15)))
        assertEquals("ign-lidar-hd-mns", tileLayerOf(IgnLidarHdLayer.MNS.config(15)))
        assertEquals("ign-lidar-hd-mnh", tileLayerOf(IgnLidarHdLayer.MNH.config(14)))
        assertFailsWith<IllegalArgumentException> {
            tileLayerOf(ElevationProviderConfig(tileUrlTemplate = "https://example.org/{z}/{x}/{y}.png"))
        }
        assertEquals("mapterhorn/12/2047/1400.webp", TileKey("mapterhorn", 12, 2047, 1400, "webp").relativePath())
    }

    private class MapStore : TileStore {
        val entries = ConcurrentHashMap<TileKey, ByteArray>()
        val puts = AtomicInteger()
        var failGet = false
        var failPut = false

        override fun get(key: TileKey): ByteArray? {
            if (failGet) error("disk unreadable")
            return entries[key]
        }

        override fun put(
            key: TileKey,
            bytes: ByteArray,
        ) {
            puts.incrementAndGet()
            if (failPut) error("disk full")
            entries[key] = bytes
        }
    }

    private val mnt = IgnLidarHdLayer.MNT.config(15)
    private val tc = TileCoordinates(16_500, 11_400, 15)
    private val key = TileKey("ign-lidar-hd-mnt", 15, 16_500, 11_400, "bil")

    private fun bil(value: Float): ByteArray {
        val size = mnt.tileSize
        val b = ByteBuffer.allocate(size * size * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(size * size) { b.putFloat(value) }
        return b.array()
    }

    private fun elevationOf(tile: RawTile): Double = Tile(tile).getElevation(Pixel(tc, 0, 0))

    @Test
    fun a_miss_fetches_and_stores_the_raw_body() =
        runTest {
            val store = MapStore()
            val body = bil(123f)
            val fetched = ArrayList<String>()
            val fetcher =
                storedTileFetcher(mnt, store) { url ->
                    fetched += url
                    body
                }
            val tile = fetcher(urlOf(mnt, tc))
            assertEquals(123.0, elevationOf(tile), 1e-3)
            assertEquals(1, fetched.size)
            assertContentEquals(body, store.entries.getValue(key), "the BIL as received, not re-encoded")
        }

    @Test
    fun a_hit_never_touches_the_network() =
        runTest {
            val store = MapStore()
            store.entries[key] = bil(55f)
            val fetcher = storedTileFetcher(mnt, store) { error("network") }
            assertEquals(55.0, elevationOf(fetcher(urlOf(mnt, tc))), 1e-3)
            assertEquals(0, store.puts.get())
        }

    @Test
    fun an_entry_that_does_not_decode_is_fetched_again_and_overwritten() =
        runTest {
            val store = MapStore()
            store.entries[key] = "<xml>error</xml>".encodeToByteArray()
            val fetcher = storedTileFetcher(mnt, store) { bil(7f) }
            assertEquals(7.0, elevationOf(fetcher(urlOf(mnt, tc))), 1e-3)
            assertEquals(mnt.tileSize * mnt.tileSize * 4, store.entries.getValue(key).size)
        }

    @Test
    fun a_store_that_throws_never_fails_the_read() =
        runTest {
            val store =
                MapStore().apply {
                    failGet = true
                    failPut = true
                }
            val fetcher = storedTileFetcher(mnt, store) { bil(9f) }
            assertEquals(9.0, elevationOf(fetcher(urlOf(mnt, tc))), 1e-3)
            assertEquals(1, store.puts.get())
        }

    @Test
    fun a_body_that_is_not_a_tile_throws_and_is_never_stored() =
        runTest {
            val store = MapStore()
            val fetcher = storedTileFetcher(mnt, store) { "<ServiceException/>".encodeToByteArray() }
            assertFailsWith<IllegalStateException> { fetcher(urlOf(mnt, tc)) }
            assertTrue(store.entries.isEmpty())
            assertEquals(0, store.puts.get())
        }

    @Test
    fun every_tier_of_the_ign_provider_goes_through_the_store() {
        val store = MapStore()
        val ign = newElevationProvider(ElevationSource.IGN_LIDAR_HD, store)
        // Built, never asked: the tiers are configured as the catalogue says, the store behind each.
        assertEquals(IgnLidarHdLayer.MNS.config(null), ign.surfaceModel?.config)
        assertEquals(ElevationSource.MAPTERHORN.config(), ign.fallback?.config)
    }
}
