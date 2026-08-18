package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [ElevationSmoother.smoothProfile] is the kernel [ElevationSmoother.smooth] runs on. These pin
 * that they stay one implementation — the array entry point exists to avoid an n-sized
 * [LatLonElevation] allocation, not to become a second algorithm.
 */
class ElevationSmootherProfileTest {
    private fun track(n: Int): List<CoordinatesElevation> =
        List(n) { i ->
            LatLonElevation(
                latitude = 45.0 + i * 0.0002,
                longitude = 6.0,
                elevation = 1000.0 + 50.0 * sin(i / 9.0 * 2.0 * PI) + 0.4 * i,
            )
        }

    @Test
    fun smooth_and_smoothProfile_agree() {
        val points = track(200)
        val distances = Distance.cumulativeDistances(points)
        val elevations = DoubleArray(points.size) { points[it].elevation }

        for (window in listOf(10.0, 50.0, 150.0, 300.0)) {
            val viaList = ElevationSmoother.smooth(points, window)
            val viaArray = ElevationSmoother.smoothProfile(distances, elevations, window)
            for (i in points.indices) {
                assertEquals(viaList[i].elevation, viaArray[i], 1e-9, "index $i at window $window")
            }
        }
    }

    @Test
    fun smoothProfile_treats_a_non_positive_window_as_do_not_smooth() {
        val points = track(20)
        val distances = Distance.cumulativeDistances(points)
        val elevations = DoubleArray(points.size) { points[it].elevation }

        for (window in listOf(0.0, -1.0)) {
            val out = ElevationSmoother.smoothProfile(distances, elevations, window)
            assertTrue(out.contentEquals(elevations), "window $window should be a pass-through")
            assertTrue(out !== elevations, "the result must be a copy, not the caller's array")
        }
    }

    @Test
    fun smoothProfile_returns_a_copy_for_profiles_below_the_minimum() {
        val out = ElevationSmoother.smoothProfile(doubleArrayOf(0.0, 10.0), doubleArrayOf(100.0, 120.0), 50.0)
        assertTrue(out.contentEquals(doubleArrayOf(100.0, 120.0)))
    }

    @Test
    fun smoothProfile_rejects_mismatched_lengths() {
        val failure =
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                ElevationSmoother.smoothProfile(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0), 50.0)
            }
        assertTrue(failure.message!!.contains("same length"), failure.message!!)
    }

    @Test
    fun a_linear_ramp_passes_through_a_triangular_kernel_unchanged_away_from_the_ends() {
        // A symmetric kernel preserves any affine function. This is why smoothing a steady climb
        // costs almost nothing (sample.gpx loses 1.5 %) while smoothing noise costs a great deal.
        val n = 400
        val distances = DoubleArray(n) { it * 2.0 }
        val elevations = DoubleArray(n) { 100.0 + 0.08 * distances[it] }
        val out = ElevationSmoother.smoothProfile(distances, elevations, 150.0)
        for (i in 100 until n - 100) {
            assertEquals(elevations[i], out[i], 1e-9, "index $i")
        }
    }
}
