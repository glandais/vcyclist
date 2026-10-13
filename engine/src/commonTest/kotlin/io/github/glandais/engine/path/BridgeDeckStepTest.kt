package io.github.glandais.engine.path

import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** [BridgeDeckStep] on [DeckWorld]: a synthetic bridge served as IGN-shaped tiles. */
class BridgeDeckStepTest {
    @Test
    fun `stations lie every step along the path, interpolated between its points`() {
        val p = Path(3)
        val lons = doubleArrayOf(2.0, 2.0001, 2.0003)
        for (i in 0 until 3) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lons[i] * MathConstants.DEG_TO_RAD)
        }
        p.computeDerivedData()
        val stations = alongTrackStations(p, 5.0)
        assertEquals((p.totalDistance / 5.0).toInt() + 1, stations.size)
        assertEquals(LatLon(45.0, 2.0).latitude, stations.first().latitude, 1e-12)
        for ((k, s) in stations.withIndex()) {
            val d = Distance.haversine(LatLon(45.0, 2.0), LatLon(s.latitude, s.longitude))
            assertEquals(5.0 * k, d, 1e-6, "station $k")
        }
        assertTrue(alongTrackStations(Path(0), 5.0).isEmpty())
    }

    @Test
    fun `a deck over the river replaces the valley, and nothing else moves`() =
        runTest {
            // 2.5 m between points: every station, the pins included, is a path point.
            val input = DeckWorld.path(stepM = 2.5)
            val result = BridgeDeckStep.apply(input, DeckWorld.terrain(), DeckWorld.surface())
            assertEquals(BridgeDeckStatus.APPLIED, result.status)
            val rep = result.replacements.single()
            val out = result.path
            val lo = rep.first * 5.0
            val hi = rep.last * 5.0
            assertTrue(lo in 940.0..1_000.0 && hi in 1_400.0..1_460.0, "stretch $lo..$hi")
            for (i in 0 until out.size) {
                val d = input.distance(i)
                for (field in PointField.entries) {
                    if (field == PointField.ELEVATION || field == PointField.GRADE) continue
                    assertEquals(input.get(i, field).toRawBits(), out.get(i, field).toRawBits(), "$field at $i")
                }
                if (d < lo || d > hi) {
                    assertEquals(input.elevation(i).toRawBits(), out.elevation(i).toRawBits(), "elevation outside the stretch, at $i")
                }
                if (d in 1_050.0..1_350.0) assertEquals(DeckWorld.DECK_M, out.elevation(i), 0.05, "deck at $d m")
            }
            // Continuity at the pins: the points there keep the input's elevation.
            for (pin in listOf(lo, hi)) {
                val i = (0 until input.size).minBy { abs(input.distance(it) - pin) }
                assertEquals(pin, input.distance(i), 1e-6)
                assertEquals(input.elevation(i), out.elevation(i), 1e-6, "pin at $pin m")
            }
            assertTrue(out.maxElevation > DeckWorld.DECK_M - 0.1)
        }

    @Test
    fun `a route outside the surface's coverage comes back as the same instance`() =
        runTest {
            val input = DeckWorld.path()
            val result = BridgeDeckStep.apply(input, DeckWorld.terrain(), DeckWorld.surface { url -> DeckWorld.tile(url) { -9999.0 } })
            assertEquals(BridgeDeckStatus.NO_COVERAGE, result.status)
            assertSame(input, result.path)
        }

    @Test
    fun `no canyon under the structure is no deck`() =
        runTest {
            val input = DeckWorld.path()
            val result = BridgeDeckStep.apply(input, DeckWorld.terrain { DeckWorld.BANK_M }, DeckWorld.surface())
            assertEquals(BridgeDeckStatus.NO_DECK, result.status)
            assertSame(input, result.path)
            assertNotNull(result.detection)
        }

    @Test
    fun `a surface tile that cannot be read leaves the path as it was`() =
        runTest {
            val input = DeckWorld.path()
            val result = BridgeDeckStep.apply(input, DeckWorld.terrain(), DeckWorld.surface { error("HTTP 400") })
            assertEquals(BridgeDeckStatus.FETCH_FAILED, result.status)
            assertSame(input, result.path)
        }

    @Test
    fun `a cancelled job is not mistaken for a failed fetch`() =
        runTest {
            var finished = false
            val job =
                launch {
                    BridgeDeckStep.apply(DeckWorld.path(), DeckWorld.terrain(), DeckWorld.surface { awaitCancellation() })
                    finished = true
                }
            testScheduler.advanceUntilIdle()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertFalse(finished, "the step returned FETCH_FAILED to a cancelled caller")
        }

    @Test
    fun `a defect past the tile reads throws, it is no failed fetch`() =
        runTest {
            // The calculator wraps a fetch error in an IllegalStateException: the same type, thrown by
            // the detection side, must reach the caller.
            val e =
                assertFailsWith<IllegalStateException> {
                    BridgeDeckStep.apply(DeckWorld.path(), DeckWorld.terrain(), DeckWorld.surface()) { _, _, _, _ ->
                        error("a builder defect")
                    }
                }
            assertEquals("a builder defect", e.message)
            // And the real steps 3–4 are not guarded either: mismatched samples are a require.
            assertFailsWith<IllegalArgumentException> {
                BridgeDeckStep.replaceDecks(DeckWorld.path(), DoubleArray(3), DoubleArray(4), DeckDetectorOptions.DEFAULT)
            }
        }

    /** A hand-built stretch of stations `first..last` on the 5 m grid, its deck [deck] on the spans. */
    private fun replacement(
        first: Int,
        last: Int,
        spanFirst: Int,
        spanLast: Int,
        deck: (Int) -> Double,
    ): DeckReplacement {
        val z = DoubleArray(last - first + 1) { j -> if (first + j in spanFirst..spanLast) deck(first + j) else Double.NaN }
        val anchors = (spanFirst..spanLast).toList().toIntArray()
        val span = DeckCandidate(spanFirst, spanLast, spanFirst * 5.0, spanLast * 5.0, null, null, listOf(spanFirst..spanLast))
        return DeckReplacement(first, last, spanFirst, spanLast, spanFirst, spanLast, anchors, z, listOf(span))
    }

    private fun nearest(
        p: Path,
        d: Double,
    ): Int = (0 until p.size).minBy { abs(p.distance(it) - d) }

    @Test
    fun `a deck at the route's start or end is held flat to it, past the last station too`() {
        // 2 m between points, so the pins (100 m, 900 m) are path points; the last one lies 2 m past
        // the last station (1 000 m).
        val input = DeckWorld.path(lengthM = 1_002.0, stepM = 2.0)
        assertEquals(200, (input.distance(input.size - 1) / 5.0).toInt())
        val start = replacement(0, 20, 0, 19) { 18.0 }
        val end = replacement(180, 200, 181, 200) { k -> 18.0 + 0.1 * (k - 181) }
        assertFalse(start.pinnedStart)
        assertFalse(end.pinnedEnd)
        val out = BridgeDeckStep.write(input, listOf(start, end), 5.0)
        for (i in 0 until input.size) {
            val d = input.distance(i)
            when {
                d <= 95.0 + 1e-6 -> assertEquals(18.0, out.elevation(i), 1e-6, "start deck at $d m")
                d < 100.0 - 1e-6 -> assertTrue(out.elevation(i) in 10.0..18.0, "start ramp at $d m")
                d <= 900.0 - 1e-6 -> assertEquals(input.elevation(i).toRawBits(), out.elevation(i).toRawBits(), "untouched at $d m")
                d in 905.0..1_000.0 -> assertEquals(18.0 + 0.1 * (d / 5.0 - 181), out.elevation(i), 1e-6, "end deck at $d m")
            }
        }
        // The far pin keeps the road's own elevation; the point past the last station holds its value.
        assertEquals(input.elevation(nearest(input, 100.0)), out.elevation(nearest(input, 100.0)), 1e-6)
        assertEquals(input.elevation(nearest(input, 900.0)), out.elevation(nearest(input, 900.0)), 1e-6)
        assertTrue(input.distance(input.size - 1) > 1_000.0)
        assertEquals(19.9, out.elevation(input.size - 1), 1e-9, "held flat past station 200")
    }

    @Test
    fun `two decks one station apart share their pin, which keeps the road's elevation`() {
        // 2.5 m between points: every station is a path point. Pins at 250, 355 and 455 m.
        val input = DeckWorld.path(lengthM = 1_000.0, stepM = 2.5)
        val a = replacement(50, 71, 51, 70) { 18.0 }
        val b = replacement(71, 91, 72, 90) { 15.0 }
        assertEquals(a.last, b.first)
        val out = BridgeDeckStep.write(input, listOf(a, b), 5.0)
        for (pin in listOf(250.0, 355.0, 455.0)) {
            val i = nearest(input, pin)
            assertEquals(pin, input.distance(i), 1e-6)
            assertEquals(input.elevation(i), out.elevation(i), 1e-6, "pin at $pin m")
        }
        for (i in 0 until input.size) {
            val d = input.distance(i)
            when {
                d in 255.0..350.0 -> assertEquals(18.0, out.elevation(i), 1e-6, "deck a at $d m")
                d in 360.0..450.0 -> assertEquals(15.0, out.elevation(i), 1e-6, "deck b at $d m")
                d < 250.0 - 1e-6 || d > 455.0 + 1e-6 ->
                    assertEquals(input.elevation(i).toRawBits(), out.elevation(i).toRawBits(), "untouched at $d m")
            }
        }
        // Continuous through the shared pin: linear ramps on each side, no step.
        val i = nearest(input, 355.0)
        assertEquals((18.0 + input.elevation(i)) / 2, out.elevation(i - 1), 1e-6)
        assertEquals((15.0 + input.elevation(i)) / 2, out.elevation(i + 1), 1e-6)
    }
}
