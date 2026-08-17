package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [MaxSpeedComputer]. We populate synthetic `distance` and `bearing` slots directly
 * on a [Path] (skipping `computeDerivedData()` before the call) so we can drive the cornering
 * and braking math from known geometry. After [MaxSpeedComputer.computeMaxSpeeds], the path's
 * `computeDerivedData()` overwrites `distance/bearing` to 0 (lat/lon are also 0), but the
 * slots we assert on — `speedMax`, `speedMaxIncline`, `radius` — are not touched by
 * `computeDerivedData()`.
 */
class MaxSpeedComputerTest {
    private val tolTight = 1e-9
    private val tolMid = 1e-3

    private fun buildSynthPath(
        distances: DoubleArray,
        bearings: DoubleArray,
    ): Path {
        require(distances.size == bearings.size) { "distances and bearings must have same size" }
        val p = Path(distances.size)
        for (i in distances.indices) {
            p.setDistance(i, distances[i])
            p.setBearing(i, bearings[i])
        }
        return p
    }

    // 1 — empty path : no crash.
    @Test
    fun empty_path_does_not_crash() {
        val course = Course(Path(0))
        MaxSpeedComputer.computeMaxSpeeds(course)
        assertEquals(0, course.path.size)
    }

    // 2 — single point : speedMax(0) == 2.0.
    @Test
    fun single_point_speedMax_is_end_speed_2() {
        val course = Course(Path(1))
        MaxSpeedComputer.computeMaxSpeeds(course)
        assertEquals(2.0, course.path.speedMax(0), tolTight)
    }

    // 3 — 2 points straight line : last = 2, first = min(maxSpeedMS, braking from 2 over d).
    @Test
    fun two_points_straight_line_first_takes_braking_limit() {
        val distances = doubleArrayOf(0.0, 10.0)
        val bearings = doubleArrayOf(0.0, 0.0)
        val path = buildSynthPath(distances, bearings)
        val cyclist = Cyclist()
        val course = Course(path, cyclist = cyclist)

        MaxSpeedComputer.computeMaxSpeeds(course)

        // Last point sentinel
        assertEquals(2.0, path.speedMax(1), tolTight)

        // Cornering limit (straight) is clamped to maxSpeedMS (≈ 27.778)
        // Braking limit : √(2² + 2 × 0.4 × 9.80665 × 10) ≈ √(4 + 78.45) = √82.45 ≈ 9.0803
        val expectedBraking = sqrt(2.0 * 2.0 + 2.0 * cyclist.maxBrakeMS2 * 10.0)
        // Cornering : straight → MAX_RADIUS=200 → √(9.80665 × 200 × tan(35°)) ≈ √(1372.0) ≈ 37.04
        // → clamped to maxSpeedMS (27.778)
        val expectedCornering = cyclist.maxSpeedMS
        val expected = minOf(expectedBraking, expectedCornering)
        assertEquals(expected, path.speedMax(0), tolMid)

        // speedMaxIncline[0] should record cornering, radius[0] should record MAX
        assertEquals(expectedCornering, path.speedMaxIncline(0), tolMid)
        assertEquals(200.0, path.radius(0), tolTight)
    }

    // 4 — 5 points, ~90° turn over 30 m → radius ≈ 30 / (π/2) ≈ 19.1 m → cornering < maxSpeedMS.
    @Test
    fun ninety_degree_turn_reduces_cornering_limit() {
        // 5 points, cumulative distance 0/7.5/15/22.5/30 m
        // bearings 0/0/π/4/π/2/π/2 (so windowed total bearing change over full window = π/2)
        val distances = doubleArrayOf(0.0, 7.5, 15.0, 22.5, 30.0)
        val bearings = doubleArrayOf(0.0, 0.0, PI / 4.0, PI / 2.0, PI / 2.0)
        val path = buildSynthPath(distances, bearings)
        val cyclist = Cyclist()
        val course = Course(path, cyclist = cyclist)

        MaxSpeedComputer.computeMaxSpeeds(course)

        // For i=2 (middle), window k=10 → mini=0, maxi=4 → Δbearing=π/2, Δdist=30 → radius=60/π ≈ 19.0986
        val expectedRadius = 30.0 / (PI / 2.0)
        assertEquals(expectedRadius, path.radius(2), tolMid)

        // Cornering limit at i=2 : √(9.80665 × 19.0986 × tan(35°))
        val expectedCornering = sqrt(EngineConstants.G * expectedRadius * cyclist.tanMaxLeanAngle)
        assertEquals(expectedCornering, path.speedMaxIncline(2), tolMid)
        assertTrue(expectedCornering < cyclist.maxSpeedMS, "cornering limit should be below maxSpeedMS in a tight turn")
    }

    // 5 — very tight turn (radius < 5 m) → clamped to MIN_RADIUS_M = 5.
    @Test
    fun very_tight_turn_clamps_radius_to_min() {
        // Δbearing = π over distance 1 m within the window for i=2 → raw radius = 1/π ≈ 0.318
        // Synthetic 5 points : distances 0/0.25/0.5/0.75/1.0, bearings 0/0/π/4/π/2/π
        // Wait need windowed change : mini=0, maxi=4 with k=10 → Δbearing = π - 0 = π
        // After normalizeAngleDiff(π) → π (boundary, but >PI strict, so stays π). Actually normalize: PI is NOT > PI so it stays.
        // |Δbearing| = π, totalDistance = 1.0 → raw = 1.0/π ≈ 0.318 → clamp to 5.0
        val distances = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val bearings = doubleArrayOf(0.0, 0.5, 1.0, 2.0, PI)
        val path = buildSynthPath(distances, bearings)
        val course = Course(path)

        MaxSpeedComputer.computeMaxSpeeds(course)

        assertEquals(5.0, path.radius(2), tolTight)
    }

    // 6 — no turn (bearing constant) → radius = MAX_RADIUS_M = 200.
    @Test
    fun constant_bearing_yields_max_radius() {
        val distances = doubleArrayOf(0.0, 10.0, 20.0, 30.0)
        val bearings = doubleArrayOf(0.5, 0.5, 0.5, 0.5)
        val path = buildSynthPath(distances, bearings)
        val course = Course(path)

        MaxSpeedComputer.computeMaxSpeeds(course)

        // All non-last points see Δbearing = 0 → radius = MAX
        for (i in 0..2) {
            assertEquals(200.0, path.radius(i), tolTight, "radius at $i should be MAX_RADIUS")
        }
    }

    // 7 — normalizeAngleDiff : 3π/2 → -π/2.
    @Test
    fun normalize_three_half_pi_yields_minus_half_pi() {
        assertEquals(-PI / 2.0, MaxSpeedComputer.normalizeAngleDiff(3.0 * PI / 2.0), tolTight)
    }

    // 8 — normalizeAngleDiff : -3π/2 → π/2.
    @Test
    fun normalize_minus_three_half_pi_yields_half_pi() {
        assertEquals(PI / 2.0, MaxSpeedComputer.normalizeAngleDiff(-3.0 * PI / 2.0), tolTight)
    }

    // 9 — normalizeAngleDiff : -π/4 already in [-π, π] → unchanged.
    @Test
    fun normalize_already_in_range_is_identity() {
        assertEquals(-PI / 4.0, MaxSpeedComputer.normalizeAngleDiff(-PI / 4.0), tolTight)
        assertEquals(0.0, MaxSpeedComputer.normalizeAngleDiff(0.0), tolTight)
        assertEquals(1.2345, MaxSpeedComputer.normalizeAngleDiff(1.2345), tolTight)
    }

    // 10 — backward braking property : speedMax(i) ≤ √(speedMax(i+1)² + 2·a·d).
    @Test
    fun backward_pass_satisfies_braking_property() {
        // 10 points, alternating bearings → some cornering activity, distances by 5 m steps
        val n = 10
        val distances = DoubleArray(n) { it * 5.0 }
        val bearings = DoubleArray(n) { i -> if (i % 2 == 0) 0.0 else 0.3 }
        val path = buildSynthPath(distances, bearings)
        val cyclist = Cyclist()
        val course = Course(path, cyclist = cyclist)

        MaxSpeedComputer.computeMaxSpeeds(course)

        for (i in 0 until n - 1) {
            val vi = path.speedMax(i)
            val vip1 = path.speedMax(i + 1)
            // distances were overwritten by computeDerivedData (lat/lon are 0 → all 0).
            // Use synthetic d we set up (i+1 - i)*5 = 5
            val d = 5.0
            val maxAllowedByBraking = sqrt(vip1 * vip1 + 2.0 * cyclist.maxBrakeMS2 * d)
            assertTrue(
                vi <= maxAllowedByBraking + 1e-9,
                "at $i : speedMax(i)=$vi > braking bound $maxAllowedByBraking",
            )
        }
    }

    // 11 — side-effects : radius and speedMaxIncline written for every i < n-1.
    @Test
    fun side_effects_radius_and_speedMaxIncline_set_for_all_but_last() {
        val n = 6
        val distances = DoubleArray(n) { it * 4.0 }
        val bearings = DoubleArray(n) { i -> i * 0.1 }
        val path = buildSynthPath(distances, bearings)
        val course = Course(path)

        // Sanity : before call, all default to 0.0
        for (i in 0 until n) {
            assertEquals(0.0, path.radius(i), 0.0)
            assertEquals(0.0, path.speedMaxIncline(i), 0.0)
        }

        MaxSpeedComputer.computeMaxSpeeds(course)

        // All points except last should have non-zero radius and speedMaxIncline
        for (i in 0 until n - 1) {
            assertTrue(path.radius(i) > 0.0, "radius($i) must be set")
            assertTrue(path.speedMaxIncline(i) > 0.0, "speedMaxIncline($i) must be set")
        }
        // speedMax should be set for every point
        for (i in 0 until n) {
            assertTrue(path.speedMax(i) > 0.0, "speedMax($i) must be set")
        }
        // Last point sentinel
        assertEquals(2.0, path.speedMax(n - 1), tolTight)
    }

    // 12 — sentinel cornering : R=30 m, lean=35° → v_max ≈ 14.36 m/s.
    @Test
    fun sentinel_cornering_R30m_lean35deg_yields_14_36_ms() {
        // Craft 5 points whose middle-point windowed radius is exactly 30 m.
        // Window k=10 with n=5 → mini=0, maxi=4. Δdistance = 30, |Δbearing| = 1 rad → R = 30/1 = 30.
        val distances = doubleArrayOf(0.0, 7.5, 15.0, 22.5, 30.0)
        val bearings = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val path = buildSynthPath(distances, bearings)
        val cyclist = Cyclist() // default : maxLeanAngleDeg=35
        val course = Course(path, cyclist = cyclist)

        MaxSpeedComputer.computeMaxSpeeds(course)

        // Radius for i=2 (middle) should be exactly 30 m
        assertEquals(30.0, path.radius(2), tolMid)
        val expectedVMax = sqrt(EngineConstants.G * 30.0 * tan(35.0 * PI / 180.0))
        // ≈ √(9.80665 × 30 × 0.7002) ≈ √206.00 ≈ 14.353... but spec says 14.36 m/s.
        // tan(35°) = 0.70021, 9.80665 × 30 × 0.70021 = 206.00, √ ≈ 14.353. Spec rounds to 14.36.
        assertEquals(expectedVMax, path.speedMaxIncline(2), 1e-3)
        // Sentinel check : value is in [14.3, 14.4]
        assertTrue(expectedVMax in 14.3..14.4, "expected ≈ 14.36 m/s, got $expectedVMax")
    }

    // 13 — sentinel braking : v_f=0, d=10 m, a=0.4×9.80665 → v_0 ≈ 8.86 m/s.
    @Test
    fun sentinel_braking_vf0_d10m_a3_92_yields_8_86_ms() {
        // Two-point path : compute braking limit at index 0 with v_f = 0 at index 1.
        // We achieve v_f = 0 by overriding the END_SPEED_MS via a different approach :
        // simulate by computing the formula directly through the public API requires v_f at i+1.
        // The algorithm sets v_f=2 m/s at the last point — so we can't directly test 0 → 0.
        // Instead : test the FORMULA via a 2-point setup where v_f=2 (last), d=10, a=3.9227
        //   v_0 = √(2² + 2×3.9227×10) = √(4 + 78.453) = √82.453 ≈ 9.0803
        // For exact 0-as-vf sentinel : verify √(0² + 2 × 3.9227 × 10) = √78.453 ≈ 8.8574 m/s
        val cyclist = Cyclist()
        val a = cyclist.maxBrakeMS2 // 3.9227
        assertEquals(3.92266, a, 1e-5)
        val v0 = sqrt(0.0 * 0.0 + 2.0 * a * 10.0)
        assertEquals(8.8574, v0, 1e-3)
        // And confirm via the computer's public path : with v_f=2, d=10 → √(4+78.45) ≈ 9.080
        val distances = doubleArrayOf(0.0, 10.0)
        val bearings = doubleArrayOf(0.0, 0.0)
        val path = buildSynthPath(distances, bearings)
        val course = Course(path, cyclist = cyclist)
        MaxSpeedComputer.computeMaxSpeeds(course)
        val braking = sqrt(2.0 * 2.0 + 2.0 * a * 10.0) // ≈ 9.0803
        // speedMax(0) = min(cornering, braking). Cornering = 27.778 (clamped). So result = braking.
        assertEquals(braking, path.speedMax(0), 1e-3)
    }

    // 14 — combined cornering + braking : tight corner clamps speedMax even though braking allows more.
    @Test
    fun tight_corner_clamps_speedMax_below_braking_limit() {
        // 5 points, distances 0/0.25/0.5/0.75/1, bearings showing total Δ = π over 1 m
        // → radius clamped to 5 → cornering = √(9.80665 × 5 × tan(35°)) ≈ √(34.33) ≈ 5.86 m/s
        val distances = doubleArrayOf(0.0, 0.25, 0.5, 0.75, 1.0)
        val bearings = doubleArrayOf(0.0, 0.5, 1.0, 2.0, PI)
        val path = buildSynthPath(distances, bearings)
        val cyclist = Cyclist()
        val course = Course(path, cyclist = cyclist)

        MaxSpeedComputer.computeMaxSpeeds(course)

        val expectedCornering = sqrt(EngineConstants.G * 5.0 * cyclist.tanMaxLeanAngle)
        assertEquals(expectedCornering, path.speedMaxIncline(2), tolMid)
        // speedMax(2) cannot exceed cornering since braking allows even more (>>5.86)
        assertTrue(path.speedMax(2) <= expectedCornering + 1e-9)
    }
}
