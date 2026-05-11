package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PathSimplifierTest {
    private fun buildSyntheticPath(
        latDeg: DoubleArray,
        lonDeg: DoubleArray,
        eleM: DoubleArray,
    ): Path {
        require(latDeg.size == lonDeg.size && latDeg.size == eleM.size)
        val n = latDeg.size
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, latDeg[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lonDeg[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, eleM[i])
        }
        return p
    }

    // ---- 1. size 0 ----------------------------------------------------------

    @Test
    fun simplify_size0_returns_empty_path() {
        val src = Path(0)
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        assertEquals(0, out.size)
        assertNotSame(src, out, "should return a defensive copy")
    }

    // ---- 2. size 1 ----------------------------------------------------------

    @Test
    fun simplify_size1_returns_singleton_copy() {
        val src = buildSyntheticPath(doubleArrayOf(48.0), doubleArrayOf(2.0), doubleArrayOf(100.0))
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        assertEquals(1, out.size)
        assertNotSame(src, out, "should return a defensive copy")
        assertEquals(src.latitude(0), out.latitude(0))
        assertEquals(src.longitude(0), out.longitude(0))
        assertEquals(src.elevation(0), out.elevation(0))
    }

    // ---- 3. size 2 ----------------------------------------------------------

    @Test
    fun simplify_size2_preserves_both_endpoints() {
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.001),
                lonDeg = doubleArrayOf(2.0, 2.001),
                eleM = doubleArrayOf(100.0, 110.0),
            )
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        assertEquals(2, out.size)
        assertEquals(src.latitude(0), out.latitude(0))
        assertEquals(src.latitude(1), out.latitude(1))
        assertEquals(src.elevation(0), out.elevation(0))
        assertEquals(src.elevation(1), out.elevation(1))
    }

    // ---- 4. colinear 4-point path collapses to 2 ----------------------------

    @Test
    fun simplify_colinear4_with_small_tolerance_keeps_only_endpoints() {
        // 4 perfectly colinear points along longitude; constant elevation.
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.0, 48.0, 48.0),
                lonDeg = doubleArrayOf(2.0, 2.001, 2.002, 2.003),
                eleM = doubleArrayOf(100.0, 100.0, 100.0, 100.0),
            )
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        assertEquals(2, out.size)
        // First and last preserved.
        assertEquals(src.latitude(0), out.latitude(0))
        assertEquals(src.longitude(0), out.longitude(0))
        assertEquals(src.latitude(3), out.latitude(1))
        assertEquals(src.longitude(3), out.longitude(1))
    }

    // ---- 5. strong deviation, tiny tolerance → all kept ---------------------

    @Test
    fun simplify_strong_deviation_small_tolerance_keeps_all_points() {
        // Intermediate altitudes are 100 m above/below the line endpoint elevations.
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.001, 48.002, 48.003),
                lonDeg = doubleArrayOf(2.0, 2.001, 2.002, 2.003),
                eleM = doubleArrayOf(100.0, 200.0, 0.0, 100.0),
            )
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        assertEquals(4, out.size)
    }

    // ---- 6. strong deviation, huge tolerance → only endpoints ---------------

    @Test
    fun simplify_strong_deviation_huge_tolerance_collapses_to_endpoints() {
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.001, 48.002, 48.003),
                lonDeg = doubleArrayOf(2.0, 2.001, 2.002, 2.003),
                eleM = doubleArrayOf(100.0, 200.0, 0.0, 100.0),
            )
        val out = PathSimplifier.simplify(src, toleranceM = 1000.0)
        assertEquals(2, out.size)
        assertEquals(src.latitude(0), out.latitude(0))
        assertEquals(src.latitude(3), out.latitude(1))
    }

    // ---- 7. extreme tolerance always collapses to 2 -------------------------

    @Test
    fun simplify_extreme_tolerance_collapses_to_endpoints() {
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.5, 49.0, 49.5, 50.0),
                lonDeg = doubleArrayOf(2.0, 2.5, 3.0, 3.5, 4.0),
                eleM = doubleArrayOf(100.0, 500.0, -200.0, 800.0, 100.0),
            )
        val out = PathSimplifier.simplify(src, toleranceM = 1e9)
        assertEquals(2, out.size)
    }

    // ---- 8. first/last preserved across multiple tolerances -----------------

    @Test
    fun simplify_first_and_last_always_preserved() {
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.001, 48.002, 48.003, 48.004),
                lonDeg = doubleArrayOf(2.0, 2.001, 2.002, 2.003, 2.004),
                eleM = doubleArrayOf(100.0, 150.0, 80.0, 130.0, 100.0),
            )
        for (tol in listOf(0.01, 1.0, 10.0, 100.0, 1e6)) {
            val out = PathSimplifier.simplify(src, toleranceM = tol)
            assertTrue(out.size >= 2, "size>=2 for tol=$tol")
            assertEquals(src.latitude(0), out.latitude(0), "first lat preserved (tol=$tol)")
            assertEquals(src.longitude(0), out.longitude(0), "first lon preserved (tol=$tol)")
            assertEquals(src.elevation(0), out.elevation(0), "first ele preserved (tol=$tol)")
            val last = out.size - 1
            assertEquals(src.latitude(4), out.latitude(last), "last lat preserved (tol=$tol)")
            assertEquals(src.longitude(4), out.longitude(last), "last lon preserved (tol=$tol)")
            assertEquals(src.elevation(4), out.elevation(last), "last ele preserved (tol=$tol)")
        }
    }

    // ---- 9. non-coord slots preserved (pInputPower) -------------------------

    @Test
    fun simplify_preserves_non_coord_slots_for_retained_points() {
        // Long colinear path → intermediates filtered out at tol=1.0.
        // Tag every point with a unique pInputPower so we can verify which source index was kept.
        val n = 5
        val src =
            buildSyntheticPath(
                latDeg = DoubleArray(n) { 48.0 },
                lonDeg = DoubleArray(n) { 2.0 + it * 0.001 },
                eleM = DoubleArray(n) { 100.0 },
            )
        for (i in 0 until n) {
            src.setPInputPower(i, (100 + i).toDouble())
            src.setHeartRate(i, (140 + i).toDouble())
        }

        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        // Colinear at constant altitude → only first and last kept.
        assertEquals(2, out.size)
        // First point: source idx 0 → pInputPower=100, heartRate=140.
        assertEquals(100.0, out.pInputPower(0))
        assertEquals(140.0, out.heartRate(0))
        // Last point: source idx n-1=4 → pInputPower=104, heartRate=144.
        assertEquals(104.0, out.pInputPower(1))
        assertEquals(144.0, out.heartRate(1))
    }

    // ---- 9b. slot preservation also for retained intermediate ---------------

    @Test
    fun simplify_preserves_non_coord_slots_for_retained_intermediate() {
        // Strong elevation deviation at index 1 forces it to be retained.
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.0005, 48.001),
                lonDeg = doubleArrayOf(2.0, 2.0005, 2.001),
                eleM = doubleArrayOf(100.0, 500.0, 100.0),
            )
        for (i in 0 until src.size) {
            src.setPInputPower(i, (200 + i).toDouble())
        }
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        assertEquals(3, out.size)
        // Source-idx slots propagate to dst-idx slots 0..2 verbatim.
        assertEquals(200.0, out.pInputPower(0))
        assertEquals(201.0, out.pInputPower(1))
        assertEquals(202.0, out.pInputPower(2))
    }

    // ---- 10. computeDerivedData applied -------------------------------------

    @Test
    fun simplify_calls_computeDerivedData_so_distance_is_consistent() {
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.001, 48.002, 48.003),
                lonDeg = doubleArrayOf(2.0, 2.001, 2.002, 2.003),
                eleM = doubleArrayOf(100.0, 200.0, 0.0, 100.0),
            )
        val out = PathSimplifier.simplify(src, toleranceM = 1.0)
        // computeDerivedData must have run → distance(0) == 0, distance(last) > 0, totalDistance > 0.
        assertEquals(0.0, out.distance(0))
        assertTrue(out.distance(out.size - 1) > 0.0)
        assertTrue(out.totalDistance > 0.0)
        // Bounds non-empty as soon as size >= 1.
        assertTrue(out.boundsRad != Path.BoundsRad.EMPTY)
    }

    // ---- 11. zExaggeration retains more points when altitude varies ---------

    @Test
    fun simplify_higher_zExaggeration_retains_at_least_as_many_points() {
        // Path with significant altitude variation but small horizontal extent.
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.0001, 48.0002, 48.0003, 48.0004),
                lonDeg = doubleArrayOf(2.0, 2.0001, 2.0002, 2.0003, 2.0004),
                eleM = doubleArrayOf(100.0, 110.0, 90.0, 105.0, 100.0),
            )
        val tol = 5.0
        val outLow = PathSimplifier.simplify(src, toleranceM = tol, zExaggeration = 1.0)
        val outHigh = PathSimplifier.simplify(src, toleranceM = tol, zExaggeration = 20.0)
        assertTrue(
            outHigh.size >= outLow.size,
            "higher zExaggeration should not retain fewer points (low=${outLow.size}, high=${outHigh.size})",
        )
        // Sanity: with this tolerance and amplification, the high case should keep > 2.
        assertTrue(outHigh.size > 2, "expected > 2 retained for zExag=20, got ${outHigh.size}")
    }

    // ---- 12. idempotence ----------------------------------------------------

    @Test
    fun simplify_is_idempotent() {
        val src =
            buildSyntheticPath(
                latDeg = doubleArrayOf(48.0, 48.001, 48.002, 48.003, 48.004),
                lonDeg = doubleArrayOf(2.0, 2.001, 2.002, 2.003, 2.004),
                eleM = doubleArrayOf(100.0, 150.0, 80.0, 130.0, 100.0),
            )
        val once = PathSimplifier.simplify(src, toleranceM = 5.0)
        val twice = PathSimplifier.simplify(once, toleranceM = 5.0)
        assertEquals(once.size, twice.size)
        for (i in 0 until once.size) {
            assertEquals(once.latitude(i), twice.latitude(i), "lat mismatch at $i")
            assertEquals(once.longitude(i), twice.longitude(i), "lon mismatch at $i")
            assertEquals(once.elevation(i), twice.elevation(i), "ele mismatch at $i")
        }
    }
}
