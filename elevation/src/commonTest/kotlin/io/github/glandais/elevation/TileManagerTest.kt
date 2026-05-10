package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun makeSeaLevelTile(
    width: Int = 2,
    height: Int = 2,
): RawTile {
    val rgba =
        ByteArray(width * height * 4) { i ->
            when (i % 4) {
                0 -> 128.toByte() // R → sea level
                3 -> 255.toByte() // A
                else -> 0
            }
        }
    return RawTile(width, height, rgba)
}

private class RecordingFetcher(
    private val responder: suspend (String) -> RawTile = { makeSeaLevelTile() },
) {
    val calls = mutableListOf<String>()

    suspend fun fetch(url: String): RawTile {
        calls += url
        return responder(url)
    }
}

class TileManagerTest {
    @Test
    fun `url template substitution`() =
        runTest {
            val rec = RecordingFetcher()
            val manager = TileManager("https://host/{z}/{x}/{y}.webp", cacheSize = 4, fetcher = rec::fetch)
            manager.getTile(TileCoordinates(x = 100, y = 200, z = 12))
            assertEquals(listOf("https://host/12/100/200.webp"), rec.calls)
        }

    @Test
    fun `cache hit does not re-fetch`() =
        runTest {
            val rec = RecordingFetcher()
            val manager = TileManager("https://host/{z}/{x}/{y}.webp", cacheSize = 4, fetcher = rec::fetch)
            val tc = TileCoordinates(1, 1, 12)
            manager.getTile(tc)
            manager.getTile(tc)
            assertEquals(1, rec.calls.size)
        }

    @Test
    fun `distinct tile coordinates trigger distinct fetches`() =
        runTest {
            val rec = RecordingFetcher()
            val manager = TileManager("https://host/{z}/{x}/{y}.webp", cacheSize = 4, fetcher = rec::fetch)
            manager.getTile(TileCoordinates(1, 1, 12))
            manager.getTile(TileCoordinates(2, 1, 12))
            assertEquals(2, rec.calls.size)
        }

    @Test
    fun `LRU eviction at cacheSize 2`() =
        runTest {
            val rec = RecordingFetcher()
            val manager = TileManager("https://host/{z}/{x}/{y}.webp", cacheSize = 2, fetcher = rec::fetch)
            manager.getTile(TileCoordinates(1, 1, 12))
            manager.getTile(TileCoordinates(2, 1, 12))
            manager.getTile(TileCoordinates(3, 1, 12))
            val keys = manager.cachedKeys()
            assertEquals(2, keys.size)
            assertTrue(TileCoordinates(1, 1, 12) !in keys, "oldest tile (1,1,12) should be evicted, keys=$keys")
        }

    @Test
    fun `fetcher exception propagates`() =
        runTest {
            val rec = RecordingFetcher(responder = { error("network down") })
            val manager = TileManager("https://host/{z}/{x}/{y}.webp", cacheSize = 4, fetcher = rec::fetch)
            assertFailsWith<IllegalStateException> {
                manager.getTile(TileCoordinates(1, 1, 12))
            }
        }

    @Test
    fun `clear empties the manager`() =
        runTest {
            val rec = RecordingFetcher()
            val manager = TileManager("https://host/{z}/{x}/{y}.webp", cacheSize = 4, fetcher = rec::fetch)
            manager.getTile(TileCoordinates(1, 1, 12))
            manager.getTile(TileCoordinates(2, 1, 12))
            manager.clear()
            assertEquals(emptyList(), manager.cachedKeys())
        }

    @Test
    fun `url without placeholders is used verbatim`() =
        runTest {
            val rec = RecordingFetcher()
            val fixed = "https://host/static-tile.png"
            val manager = TileManager(fixed, cacheSize = 4, fetcher = rec::fetch)
            manager.getTile(TileCoordinates(1, 1, 12))
            assertEquals(listOf(fixed), rec.calls)
        }
}
