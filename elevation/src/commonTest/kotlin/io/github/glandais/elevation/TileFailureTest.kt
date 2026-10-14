package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A provider with a fallback reads from it the points of a tile it cannot fetch. */
class TileFailureTest {
    /** A 4 × 4 Terrarium tile at [metres] everywhere. */
    private fun flat(metres: Double): RawTile {
        val packed = encodeTerrarium(metres)
        val rgba = ByteArray(4 * 4 * 4)
        for (i in 0 until 16) {
            rgba[i * 4] = (packed shr 16).toByte()
            rgba[i * 4 + 1] = (packed shr 8).toByte()
            rgba[i * 4 + 2] = packed.toByte()
            rgba[i * 4 + 3] = 255.toByte()
        }
        return RawTile(4, 4, rgba)
    }

    private val small = ElevationProviderConfig(zoomLevel = 3, tileSize = 4, tileUrlTemplate = "primary://{z}/{x}/{y}")
    private val fallback = ElevationProvider(small.copy(tileUrlTemplate = "fallback://{z}/{x}/{y}")) { flat(100.0) }

    @Test
    fun a_tile_that_cannot_be_fetched_is_read_from_the_fallback_once() =
        runTest {
            var asked = 0
            val primary =
                ElevationProvider(small) {
                    asked++
                    throw TileFetchException(it, 500)
                }.withFallback(fallback)
            val log = TileFailureLog()
            withContext(log) {
                assertEquals(100.0, primary.getElevation(45.0, 6.0), 0.01)
                // The bilinear read may span up to four tiles: each dead one is asked once.
                val first = asked
                assertEquals(log.failedTiles.size, first)
                assertEquals(100.0, primary.getElevation(45.0001, 6.0001), 0.01)
                assertEquals(first, asked, "a dead tile is remembered, not asked again")
            }
            val failure = log.failedTiles.first()
            assertEquals("primary://{z}/{x}/{y}", failure.urlTemplate)
            assertTrue("HTTP 500" in failure.message, failure.message)
        }

    @Test
    fun a_remembered_tile_is_recorded_by_every_run_that_meets_it() =
        runTest {
            val primary = ElevationProvider(small) { error("HTTP 500") }.withFallback(fallback)
            val first = TileFailureLog()
            withContext(first) { primary.getElevation(45.0, 6.0) }
            val second = TileFailureLog()
            withContext(second) { primary.getElevation(45.0, 6.0) }
            assertTrue(first.failedTiles.isNotEmpty())
            assertEquals(first.failedTiles, second.failedTiles, "the second run is told, though it fetched nothing")
            // Without a log in the context the read simply succeeds.
            assertEquals(100.0, primary.getElevation(45.0, 6.0), 0.01)
        }

    @Test
    fun a_dead_tile_is_asked_again_after_its_delay() =
        runTest {
            var clock = 0L
            var asked = 0
            val tm =
                TileManager("primary://{z}/{x}/{y}", 4) {
                    asked++
                    if (asked == 1) error("HTTP 500") else flat(7.0)
                }.apply { unreadable = UnreadableTiles("primary://{z}/{x}/{y}", 4, retryAfterMs = 1_000L, now = { clock }) }
            val tc = TileCoordinates(4, 2, 3)
            assertTrue(tm.getTile(tc).getElevation(Pixel(tc, 0, 0)).isNaN(), "no data while remembered")
            clock = 999L
            assertTrue(tm.getTile(tc).getElevation(Pixel(tc, 0, 0)).isNaN())
            assertEquals(1, asked)
            clock = 1_000L
            assertEquals(7.0, tm.getTile(tc).getElevation(Pixel(tc, 0, 0)), 0.01)
            assertEquals(2, asked)
        }

    @Test
    fun without_a_fallback_the_error_propagates() =
        runTest {
            val alone = ElevationProvider(small) { error("HTTP 500") }
            assertFailsWith<IllegalStateException> { alone.getElevation(45.0, 6.0) }
        }

    @Test
    fun a_cancellation_is_never_taken_for_a_dead_tile() =
        runTest {
            val primary = ElevationProvider(small) { throw CancellationException("cancelled") }.withFallback(fallback)
            // ElevationCalculator wraps whatever reaches it (see BridgeDeckStep); the point is that
            // the read fails instead of quietly answering the fallback's 100 m.
            val e = assertFailsWith<IllegalStateException> { primary.getElevation(45.0, 6.0) }
            assertTrue(e.cause is CancellationException, "${e.cause}")
        }

    @Test
    fun a_failing_fallback_still_fails_the_read() =
        runTest {
            val dead = ElevationProvider(small.copy(tileUrlTemplate = "fallback://{z}/{x}/{y}")) { error("HTTP 502") }
            val primary = ElevationProvider(small) { error("HTTP 500") }.withFallback(dead)
            assertFailsWith<IllegalStateException> { primary.getElevation(45.0, 6.0) }
        }
}
