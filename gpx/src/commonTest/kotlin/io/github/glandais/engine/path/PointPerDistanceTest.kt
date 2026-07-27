package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for [PointPerDistance] (distance-based resampler).
 *
 * Distance assertions rely on `Path.computeDerivedData()` Haversine recomputation. Synthetic
 * paths are built along the equator with `longitude(i) = i * gap_m / R` so that the cumulative
 * distance after [Path.computeDerivedData] matches the intended gap exactly (Haversine at lat=0
 * with small deltaLon reduces to `R * deltaLon`).
 */
class PointPerDistanceTest {
    private companion object {
        // Earth mean radius in meters (matches `elevation.EarthConstants.MEAN_RADIUS`).
        const val EARTH_RADIUS_M: Double = 6_371_000.0
    }

    /**
     * Build a path of [count] points along the equator, spaced exactly [gapM] meters apart
     * (according to Haversine at lat=0).
     */
    private fun equatorPath(
        count: Int,
        gapM: Double,
        elevations: DoubleArray? = null,
    ): Path {
        val p = Path(count)
        for (i in 0 until count) {
            p.setLatitude(i, 0.0)
            // longitude in radians : i * gapM / R → Haversine yields ~gapM per increment.
            p.setLongitude(i, i * gapM / EARTH_RADIUS_M)
            p.setElevation(i, elevations?.get(i) ?: (100.0 + i * 10.0))
        }
        p.computeDerivedData()
        return p
    }

    // --- Test 1 : empty source -------------------------------------------------
    @Test
    fun emptySourceReturnsEmptyPath() {
        val out = PointPerDistance.compute(Path(0), -1.0, 10.0)
        assertEquals(0, out.size)
    }

    // --- Test 2 : single point ------------------------------------------------
    @Test
    fun singlePointReturnsSinglePoint() {
        val source = Path(1)
        source.setLatitude(0, 0.5)
        source.setLongitude(0, 0.25)
        source.setElevation(0, 123.0)
        source.computeDerivedData()
        val out = PointPerDistance.compute(source, -1.0, 10.0)
        assertEquals(1, out.size)
        assertEquals(0.5, out.latitude(0), absoluteTolerance = 1e-12)
        assertEquals(0.25, out.longitude(0), absoluteTolerance = 1e-12)
        assertEquals(123.0, out.elevation(0), absoluteTolerance = 1e-12)
    }

    // --- Test 3 : maxDistanceM must be > 0 ------------------------------------
    @Test
    fun maxDistanceZeroThrows() {
        val source = equatorPath(3, 10.0)
        assertFailsWith<IllegalArgumentException> {
            PointPerDistance.compute(source, -1.0, 0.0)
        }
    }

    @Test
    fun maxDistanceNegativeThrows() {
        val source = equatorPath(3, 10.0)
        assertFailsWith<IllegalArgumentException> {
            PointPerDistance.compute(source, -1.0, -5.0)
        }
    }

    // --- Test 4 : all points already within gap (no densification) ------------
    // 4 points at 10 m gap, min=-1 max=15 → all 4 points kept verbatim.
    @Test
    fun allPointsKeptWhenWithinMaxGap() {
        val source = equatorPath(4, 10.0)
        val out = PointPerDistance.compute(source, -1.0, 15.0)
        assertEquals(4, out.size)
        // Elevations preserved (no interpolation).
        for (i in 0 until 4) {
            assertEquals(100.0 + i * 10.0, out.elevation(i), absoluteTolerance = 1e-9)
        }
    }

    // --- Test 5 : densification triggers --------------------------------------
    // 4 points at 10 m gap, min=-1 max=5 :
    //   first point kept ; for i=1..3 each gap=10 > 5 → numSegments=2, spacing=5
    //   → 1 interpolated point + 1 copy per source point.
    // Total = 1 + 3 * 2 = 7 points.
    @Test
    fun gapLargerThanMaxIsDensified() {
        val source = equatorPath(4, 10.0)
        val out = PointPerDistance.compute(source, -1.0, 5.0)
        assertEquals(7, out.size)
        // Output distances must form a strictly increasing sequence ; consecutive gaps ≤ 5 m
        // (tolerance 1e-6 for Haversine round-trip).
        for (i in 1 until out.size) {
            val gap = out.distance(i) - out.distance(i - 1)
            assertTrue(gap > 0.0, "distance($i) not > distance(${i - 1}) : gap=$gap")
            assertTrue(gap <= 5.0 + 1e-6, "gap at index $i = $gap exceeds maxDistanceM=5")
        }
    }

    // --- Test 6 : truncation when minDistanceM filters everything -------------
    // 4 points at 1 m gap, min=5 max=10. No point reaches min=5 from the first kept point
    // → only the first point is in the output.
    @Test
    fun minDistanceFiltersTooClosePoints() {
        val source = equatorPath(4, 1.0)
        val out = PointPerDistance.compute(source, 5.0, 10.0)
        assertEquals(1, out.size)
        // Elevation of first source point preserved.
        assertEquals(100.0, out.elevation(0), absoluteTolerance = 1e-9)
    }

    // --- Test 7 : selective filtering ------------------------------------------
    // 4 points at 1 m gap (cumulative 0/1/2/3), min=2 max=5.
    //   i=1 : gap=1 < 2 → skip.
    //   i=2 : gap=2 ≤ 5 → copy ; lastAddedDistance=2.
    //   i=3 : gap=1 < 2 → skip.
    // → Output = 2 points (i=0 and i=2).
    @Test
    fun filterKeepsOnlyPointsAboveMinDistance() {
        val source = equatorPath(4, 1.0)
        val out = PointPerDistance.compute(source, 2.0, 5.0)
        assertEquals(2, out.size)
        // First point : source[0] elevation = 100.
        assertEquals(100.0, out.elevation(0), absoluteTolerance = 1e-9)
        // Second point : source[2] elevation = 120 (verbatim copy).
        assertEquals(120.0, out.elevation(1), absoluteTolerance = 1e-9)
    }

    // --- Test 8 : geometry preserved at interpolated mid-point ----------------
    // 2 points at 20 m gap, min=-1 max=10 → 1 interpolated point at coef=0.5.
    @Test
    fun interpolationPreservesLatLonGeometry() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 100.0)
        p.setLatitude(1, 0.0)
        p.setLongitude(1, 20.0 / EARTH_RADIUS_M)
        p.setElevation(1, 200.0)
        p.computeDerivedData()
        val out = PointPerDistance.compute(p, -1.0, 10.0)
        // numSegments = ceil(20 / 10) = 2 → 1 interpolated point + 1 copy. Plus the first point.
        assertEquals(3, out.size)
        // Middle point : coef=0.5 → lon = 10.0 / R.
        assertEquals(10.0 / EARTH_RADIUS_M, out.longitude(1), absoluteTolerance = 1e-12)
        assertEquals(0.0, out.latitude(1), absoluteTolerance = 1e-12)
    }

    // --- Test 9 : elevation interpolated linearly -----------------------------
    // 2 points at 20 m gap, elev 100 → 200, max=10 → 1 interpolated point at coef=0.5
    // → middle elevation = 150.
    @Test
    fun elevationInterpolatedLinearly() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 100.0)
        p.setLatitude(1, 0.0)
        p.setLongitude(1, 20.0 / EARTH_RADIUS_M)
        p.setElevation(1, 200.0)
        p.computeDerivedData()
        val out = PointPerDistance.compute(p, -1.0, 10.0)
        assertEquals(3, out.size)
        assertEquals(100.0, out.elevation(0), absoluteTolerance = 1e-9)
        assertEquals(150.0, out.elevation(1), absoluteTolerance = 1e-9)
        assertEquals(200.0, out.elevation(2), absoluteTolerance = 1e-9)
    }

    // --- Test 10 : non-coord slots (pInputPower) interpolated correctly -------
    @Test
    fun nonCoordinateSlotsInterpolated() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 100.0)
        p.setPInputPower(0, 100.0)
        p.setLatitude(1, 0.0)
        p.setLongitude(1, 20.0 / EARTH_RADIUS_M)
        p.setElevation(1, 100.0)
        p.setPInputPower(1, 200.0)
        p.computeDerivedData()
        val out = PointPerDistance.compute(p, -1.0, 10.0)
        assertEquals(3, out.size)
        assertEquals(100.0, out.pInputPower(0), absoluteTolerance = 1e-9)
        assertEquals(150.0, out.pInputPower(1), absoluteTolerance = 1e-9)
        assertEquals(200.0, out.pInputPower(2), absoluteTolerance = 1e-9)
    }

    // --- Test 11 : first point always preserved -------------------------------
    @Test
    fun firstPointAlwaysPreserved() {
        val source = equatorPath(5, 7.0)
        // Mark source[0] elevation distinctively.
        source.setElevation(0, 999.0)
        val out = PointPerDistance.compute(source, -1.0, 10.0)
        assertTrue(out.size >= 1)
        assertEquals(999.0, out.elevation(0), absoluteTolerance = 1e-9)
        // First point distance is always 0 (re-derived).
        assertEquals(0.0, out.distance(0), absoluteTolerance = 1e-12)
    }

    // --- Test 12 : NaN propagates through interpolation -----------------------
    @Test
    fun nanInSourceFieldPropagatesToInterpolated() {
        val p = Path(2)
        p.setLatitude(0, 0.0)
        p.setLongitude(0, 0.0)
        p.setElevation(0, 100.0)
        p.setTemperature(0, Double.NaN)
        p.setLatitude(1, 0.0)
        p.setLongitude(1, 20.0 / EARTH_RADIUS_M)
        p.setElevation(1, 200.0)
        p.setTemperature(1, 25.0)
        p.computeDerivedData()
        val out = PointPerDistance.compute(p, -1.0, 10.0)
        assertEquals(3, out.size)
        // Interpolated middle point must carry NaN (either side NaN propagates).
        assertTrue(out.temperature(1).isNaN(), "interpolated temperature should be NaN")
        // First point copy preserves NaN.
        assertTrue(out.temperature(0).isNaN())
        // Last point copy retains 25.0.
        assertEquals(25.0, out.temperature(2), absoluteTolerance = 1e-9)
    }

    // --- Test 13 : long path with random gaps respects [min, max] -------------
    // 200 points with deterministic pseudo-random gaps in [0.5, 50] m, min=1, max=2.
    // Output gaps must lie in (1, 2] m (apart from index 0 which is always kept).
    @Test
    fun longPathRespectsMinMaxGap() {
        // Build a deterministic source with varying gaps.
        val n = 200
        val gaps = DoubleArray(n - 1)
        // Deterministic LCG to avoid pulling in `kotlin.random.Random` and keep cross-target
        // reproducibility.
        var seed = 0x1234_5678L
        for (i in 0 until n - 1) {
            seed = (seed * 6364136223846793005L + 1442695040888963407L) and 0x7FFFFFFFFFFFFFFFL
            val u = (seed ushr 11).toDouble() / (1L shl 53).toDouble() // u in [0, 1)
            gaps[i] = 0.5 + u * 49.5 // gap in [0.5, 50)
        }
        val src = Path(n)
        var cum = 0.0
        for (i in 0 until n) {
            src.setLatitude(i, 0.0)
            // Lon chosen so cumulative Haversine distance = cum meters.
            src.setLongitude(i, cum / EARTH_RADIUS_M)
            src.setElevation(i, 100.0)
            if (i < n - 1) cum += gaps[i]
        }
        src.computeDerivedData()

        val out = PointPerDistance.compute(src, 1.0, 2.0)
        // Strictly increasing distances ; gaps ∈ (~1, 2] m (with small Haversine tolerance).
        for (i in 1 until out.size) {
            val gap = out.distance(i) - out.distance(i - 1)
            assertTrue(gap > 0.0, "distance($i) not strictly increasing : gap=$gap")
            assertTrue(gap <= 2.0 + 1e-6, "gap at index $i = $gap exceeds maxDistanceM=2")
        }
    }
}
