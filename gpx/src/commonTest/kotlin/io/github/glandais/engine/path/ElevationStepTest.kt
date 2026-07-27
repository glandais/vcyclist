package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.RawTile
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ElevationStepTest {
    // ---- Helpers ------------------------------------------------------------

    /**
     * Synthetic fetcher : every pixel of every tile encodes `elevation = px * 100 + py`
     * via Terrarium encoding (`raw = elevation + 32768`, `r = raw >> 8`, `g = raw & 0xFF`).
     * Same pattern as `ElevationProviderTest.kt`.
     */
    private fun syntheticFetcher(tileSize: Int): suspend (String) -> RawTile =
        { url ->
            val rgba = ByteArray(tileSize * tileSize * 4)
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    val e = px * 100 + py
                    val raw = e + 32768
                    val r = (raw shr 8) and 0xFF
                    val g = raw and 0xFF
                    val idx = (py * tileSize + px) * 4
                    rgba[idx] = r.toByte()
                    rgba[idx + 1] = g.toByte()
                    rgba[idx + 2] = 0
                    rgba[idx + 3] = 255.toByte()
                }
            }
            // Silence unused param warning ; URL not relevant for synthetic decode.
            check(url.isNotEmpty())
            RawTile(tileSize, tileSize, rgba)
        }

    /** Fetcher whose every pixel encodes the same elevation [elev]. */
    private fun constantFetcher(
        tileSize: Int,
        elev: Int,
    ): suspend (String) -> RawTile =
        { url ->
            val raw = elev + 32768
            val r = (raw shr 8) and 0xFF
            val g = raw and 0xFF
            val rgba = ByteArray(tileSize * tileSize * 4)
            for (py in 0 until tileSize) {
                for (px in 0 until tileSize) {
                    val idx = (py * tileSize + px) * 4
                    rgba[idx] = r.toByte()
                    rgba[idx + 1] = g.toByte()
                    rgba[idx + 2] = 0
                    rgba[idx + 3] = 255.toByte()
                }
            }
            check(url.isNotEmpty())
            RawTile(tileSize, tileSize, rgba)
        }

    private fun providerWith(fetcher: suspend (String) -> RawTile): ElevationProvider =
        ElevationProvider(
            ElevationProviderConfig(
                zoomLevel = 0,
                tileSize = 4,
                cacheSize = 4,
                tileUrlTemplate = "test://{z}/{x}/{y}",
            ),
            fetcher = fetcher,
        )

    private fun buildPath(
        latDeg: DoubleArray,
        lonDeg: DoubleArray,
        eleM: DoubleArray,
        timeMs: DoubleArray? = null,
    ): Path {
        require(latDeg.size == lonDeg.size && latDeg.size == eleM.size)
        val n = latDeg.size
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, latDeg[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lonDeg[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, eleM[i])
            if (timeMs != null) p.setTime(i, timeMs[i])
        }
        p.computeDerivedData()
        return p
    }

    // ---- 1. fixElevation on empty path -------------------------------------

    @Test
    fun fixElevation_empty_returns_empty() =
        runTest {
            val src = Path(0)
            val out = ElevationStep.fixElevation(src, providerWith(constantFetcher(4, 250)))
            assertEquals(0, out.size)
            assertNotSame(src, out)
        }

    // ---- 2. fixElevation replaces elevations -------------------------------

    @Test
    fun fixElevation_replaces_elevations_with_provider_values() =
        runTest {
            // Constant fetcher : every pixel decodes to 777.0. All points get 777.0.
            val provider = providerWith(constantFetcher(4, 777))
            val src =
                buildPath(
                    latDeg = doubleArrayOf(0.0, 0.0, 0.0),
                    lonDeg = doubleArrayOf(0.0, 0.001, 0.002),
                    eleM = doubleArrayOf(100.0, 110.0, 120.0),
                )
            val out = ElevationStep.fixElevation(src, provider)
            assertEquals(3, out.size)
            for (i in 0 until out.size) {
                assertEquals(777.0, out.elevation(i), "point $i")
                assertNotEquals(src.elevation(i), out.elevation(i))
            }
        }

    // ---- 3. fixElevation preserves lat/lon/time/other slots ---------------

    @Test
    fun fixElevation_preserves_other_slots() =
        runTest {
            val provider = providerWith(constantFetcher(4, 500))
            val src =
                buildPath(
                    latDeg = doubleArrayOf(48.0, 48.001),
                    lonDeg = doubleArrayOf(2.0, 2.001),
                    eleM = doubleArrayOf(100.0, 200.0),
                    timeMs = doubleArrayOf(1_000.0, 2_000.0),
                )
            // Populate a non-coord slot we should see preserved.
            src.set(0, PointField.HEART_RATE, 142.0)
            src.set(1, PointField.HEART_RATE, 158.0)
            src.set(0, PointField.P_INPUT_POWER, 220.0)
            src.set(1, PointField.P_INPUT_POWER, 240.0)

            val out = ElevationStep.fixElevation(src, provider)
            assertEquals(src.latitude(0), out.latitude(0))
            assertEquals(src.longitude(0), out.longitude(0))
            assertEquals(src.latitude(1), out.latitude(1))
            assertEquals(src.longitude(1), out.longitude(1))
            assertEquals(src.time(0), out.time(0))
            assertEquals(src.time(1), out.time(1))
            assertEquals(142.0, out.get(0, PointField.HEART_RATE))
            assertEquals(158.0, out.get(1, PointField.HEART_RATE))
            assertEquals(220.0, out.get(0, PointField.P_INPUT_POWER))
            assertEquals(240.0, out.get(1, PointField.P_INPUT_POWER))
        }

    // ---- 4. fixElevation recomputes derived data ---------------------------

    @Test
    fun fixElevation_calls_computeDerivedData() =
        runTest {
            val provider = providerWith(constantFetcher(4, 1000))
            val src =
                buildPath(
                    latDeg = doubleArrayOf(48.0, 48.001, 48.002),
                    lonDeg = doubleArrayOf(2.0, 2.001, 2.002),
                    eleM = doubleArrayOf(100.0, 110.0, 120.0),
                )
            val expectedDistance = src.totalDistance
            val out = ElevationStep.fixElevation(src, provider)
            // distance derives from lat/lon only -> identical despite elevation change.
            assertEquals(expectedDistance, out.totalDistance, 1e-9)
            // elevation gain : input 100 -> 110 -> 120 = 20 m. New : flat 1000 -> 0 m.
            assertEquals(0.0, out.elevationGain)
            assertEquals(0.0, out.elevationLoss)
            assertEquals(1000.0, out.minElevation)
            assertEquals(1000.0, out.maxElevation)
        }

    // ---- 5. smoothElevation on empty path ----------------------------------

    @Test
    fun smoothElevation_empty_returns_empty() {
        val src = Path(0)
        val out = ElevationStep.smoothElevation(src)
        assertEquals(0, out.size)
        assertNotSame(src, out)
    }

    // ---- 6. smoothElevation on path < 3 points -----------------------------

    @Test
    fun smoothElevation_below_min_points_returns_elevations_unchanged() {
        val src =
            buildPath(
                latDeg = doubleArrayOf(48.0, 48.001),
                lonDeg = doubleArrayOf(2.0, 2.001),
                eleM = doubleArrayOf(100.0, 200.0),
            )
        val out = ElevationStep.smoothElevation(src)
        assertEquals(2, out.size)
        // ElevationSmoother short-circuits when size < 3 → elevations identical.
        assertEquals(100.0, out.elevation(0))
        assertEquals(200.0, out.elevation(1))
        assertNotSame(src, out)
    }

    // ---- 7. smoothElevation reduces spike ----------------------------------

    @Test
    fun smoothElevation_reduces_central_spike() {
        // Three points ~11m apart longitudinally at the equator
        // (1e-4 deg of longitude at equator ≈ 11 m).
        val src =
            buildPath(
                latDeg = doubleArrayOf(0.0, 0.0, 0.0),
                lonDeg = doubleArrayOf(0.0, 0.0001, 0.0002),
                eleM = doubleArrayOf(100.0, 200.0, 100.0),
            )
        // Window 100m : all three points enter every smoother window.
        val out = ElevationStep.smoothElevation(src, windowM = 100.0)
        assertEquals(3, out.size)
        // The middle peak is averaged with the 100m neighbors → reduced.
        assertTrue(out.elevation(1) < 200.0, "spike not reduced : ${out.elevation(1)}")
        assertTrue(out.elevation(1) > 100.0, "spike erased too much : ${out.elevation(1)}")
        // Endpoints get pulled towards the peak (triangular average with neighbor 200).
        assertTrue(out.elevation(0) > 100.0, "endpoint not lifted : ${out.elevation(0)}")
        assertTrue(out.elevation(0) < 200.0)
        assertTrue(out.elevation(2) > 100.0, "endpoint not lifted : ${out.elevation(2)}")
        assertTrue(out.elevation(2) < 200.0)
        // Total elevation is preserved (smoothing is a weighted average per point).
        val sumIn = 100.0 + 200.0 + 100.0
        val sumOut = out.elevation(0) + out.elevation(1) + out.elevation(2)
        assertTrue(abs(sumOut - sumIn) < sumIn, "smoothed values diverged : $sumOut vs $sumIn")
    }

    // ---- 8. smoothElevation preserves lat/lon/time -------------------------

    @Test
    fun smoothElevation_preserves_other_slots() {
        val src =
            buildPath(
                latDeg = doubleArrayOf(48.0, 48.0001, 48.0002, 48.0003),
                lonDeg = doubleArrayOf(2.0, 2.0001, 2.0002, 2.0003),
                eleM = doubleArrayOf(100.0, 200.0, 100.0, 110.0),
                timeMs = doubleArrayOf(0.0, 1_000.0, 2_000.0, 3_000.0),
            )
        src.set(0, PointField.HEART_RATE, 130.0)
        src.set(2, PointField.CADENCE, 90.0)
        val out = ElevationStep.smoothElevation(src, windowM = 50.0)
        for (i in 0 until src.size) {
            assertEquals(src.latitude(i), out.latitude(i), 1e-15, "lat $i")
            assertEquals(src.longitude(i), out.longitude(i), 1e-15, "lon $i")
            assertEquals(src.time(i), out.time(i), "time $i")
        }
        assertEquals(130.0, out.get(0, PointField.HEART_RATE))
        assertEquals(90.0, out.get(2, PointField.CADENCE))
    }

    // ---- 9. smoothElevation with custom windowM ----------------------------

    @Test
    fun smoothElevation_custom_window_smaller_than_spacing_keeps_elevations() {
        // Points are spaced enough that a very small window only includes self → no change.
        val src =
            buildPath(
                latDeg = doubleArrayOf(0.0, 0.0, 0.0),
                lonDeg = doubleArrayOf(0.0, 0.01, 0.02), // ~1.1km apart
                eleM = doubleArrayOf(100.0, 200.0, 300.0),
            )
        val out = ElevationStep.smoothElevation(src, windowM = 1.0)
        // Each point only sees itself -> weight = 1 -> elevation unchanged.
        assertEquals(100.0, out.elevation(0), 1e-9)
        assertEquals(200.0, out.elevation(1), 1e-9)
        assertEquals(300.0, out.elevation(2), 1e-9)
    }

    // ---- 10. Default window constant ---------------------------------------

    @Test
    fun default_smooth_window_is_150m() {
        assertEquals(150.0, ElevationStep.DEFAULT_SMOOTH_WINDOW_M)
    }

    // ---- 11. fixElevation differentiates per-point via synthetic fetcher --

    @Test
    fun fixElevation_with_synthetic_fetcher_produces_finite_elevations() =
        runTest {
            val provider = providerWith(syntheticFetcher(4))
            // Wide longitudes -> different tiles / pixels.
            val src =
                buildPath(
                    latDeg = doubleArrayOf(0.0, 0.0),
                    lonDeg = doubleArrayOf(-90.0, 90.0),
                    eleM = doubleArrayOf(0.0, 0.0),
                )
            val out = ElevationStep.fixElevation(src, provider)
            assertEquals(2, out.size)
            assertTrue(out.elevation(0).isFinite())
            assertTrue(out.elevation(1).isFinite())
            // Pixels differ on the x axis -> elevations differ.
            assertNotEquals(out.elevation(0), out.elevation(1))
            // Sanity bound : synthetic encoding produces e in [0, (tileSize-1)*100 + (tileSize-1)].
            assertTrue(abs(out.elevation(0)) <= 400.0)
            assertTrue(abs(out.elevation(1)) <= 400.0)
        }
}
