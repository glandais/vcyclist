// `runCurrent()` is `@ExperimentalCoroutinesApi`.
@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.glandais.elevation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Counterexamples found by the formal models in scratchpad/formal/elevation-cache-batch
 * (LruCache.tla, and executable models of `BatchCalculator` and `Distance`).
 */
class FormalModelElevationTest {
    /**
     * TLC trace (LruCacheCancelSpurious.cfg, invariant NoSpuriousCancel): p1 owns the load of k,
     * p2 awaits p1's deferred, p1 is cancelled -> p1 completes the shared deferred with its own
     * CancellationException -> p2, never cancelled, throws CancellationException.
     */
    @Test
    fun `a waiter that was not cancelled does not inherit the loader owner's cancellation`() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            val cache =
                LruCache<String, String>(maxSize = 4, loader = { k ->
                    gate.await()
                    "v-$k"
                })
            val owner = launch { cache.get("k") }
            runCurrent()
            val waiter = async { runCatching { cache.get("k") } }
            runCurrent()
            owner.cancel()
            runCurrent()
            gate.complete(Unit)
            val result = waiter.await()
            assertFalse(
                result.exceptionOrNull() is CancellationException,
                "waiter was never cancelled but get() threw ${result.exceptionOrNull()}",
            )
            assertEquals("v-k", result.getOrNull())
        }

    private fun flatTileBatchCalculator(): BatchCalculator {
        val size = 4
        val fetch: suspend (String) -> RawTile = { _ ->
            val rgba = ByteArray(size * size * 4)
            for (i in 0 until size * size) {
                val raw = 32768 + 100
                rgba[i * 4] = ((raw shr 8) and 0xFF).toByte()
                rgba[i * 4 + 1] = (raw and 0xFF).toByte()
                rgba[i * 4 + 3] = 255.toByte()
            }
            RawTile(size, size, rgba)
        }
        val tm = TileManager("test://{z}/{x}/{y}", 16, fetch)
        return BatchCalculator(ElevationCalculator(tm, tileSize = size))
    }

    @Test
    fun `a densely sampled path is not collapsed to its first point`() =
        runTest {
            // 201 fixes 0.5 m apart along the equator: a ~100 m route where every
            // consecutive pair is closer than the default 1 m minDistance.
            val degPerMetre = 1.0 / 111_195.0
            val path = (0..200).map { LatLon(0.0, it * 0.5 * degPerMetre) }
            val routeLength = Distance.haversine(path.first(), path.last())
            assertTrue(routeLength > 99.0, "fixture should be ~100 m, was $routeLength")

            val bc = flatTileBatchCalculator()
            val out = bc.getElevationsAlong(path, zoomLevel = 0, step = 10.0, minDistance = 1.0)

            // De-duplicating near points must not truncate the route: the profile must
            // still reach (within minDistance) the end of the path.
            val endGap = Distance.haversine(out.last(), path.last())
            assertTrue(
                out.size >= 2 && endGap <= 1.0,
                "100 m path collapsed to ${out.size} point(s); last output is $endGap m from the route end",
            )
        }

    @Test
    fun `haversine of antipodal points is half the circumference, not NaN`() {
        val d = Distance.haversine(LatLon(-82.0, -90.0), LatLon(82.0, 90.0))
        assertFalse(d.isNaN(), "haversine returned NaN for antipodal points")
        val expected = PI * EarthConstants.MEAN_RADIUS
        assertTrue(abs(d - expected) / expected < 1e-6, "expected ~$expected, got $d")
    }

    @Test
    fun `cumulative distances stay finite across an antipodal hop`() {
        val points = listOf(LatLon(-82.0, -90.0), LatLon(82.0, 90.0), LatLon(82.0, 90.001))
        val cum = Distance.cumulativeDistances(points)
        assertTrue(cum.all { it.isFinite() }, "cumulative distances poisoned: ${cum.toList()}")
    }
}
