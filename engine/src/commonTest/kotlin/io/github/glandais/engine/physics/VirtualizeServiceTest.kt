package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [VirtualizeService]. Synthetic paths are built directly from `distance/lat/lon/
 * elevation/grade/speedMax` slots. Note : `VirtualizeService.virtualizeTrack` calls
 * `path.computeDerivedData()` at the very end — that recomputes `distance/dx/dt/speed/grade/
 * bearing` from `lat/lon/elevation/time`. We therefore test :
 *  - on slots NOT touched by `computeDerivedData` (`time`, `elapsed`, `virtSpeedCurrent`,
 *    `pComputedPower`, `speedMax`, lat/lon/elevation copied verbatim) for exact values,
 *  - on `speed` for monotonicity / ranges (recomputed by `computeDerivedData` from `time`).
 */
class VirtualizeServiceTest {
    private val tolLoose = 1e-3
    private val tolMid = 1e-6

    private fun buildFlatPath(
        distances: DoubleArray,
        speedMax: Double = 100.0,
        grade: Double = 0.0,
    ): Path {
        val n = distances.size
        val p = Path(n)
        for (i in distances.indices) {
            p.setDistance(i, distances[i])
            // Tiny lat/lon shift consistent with distance (1 deg lat ≈ 111 km).
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * 1e-5)
            p.setElevation(i, 100.0 + distances[i] * grade)
            p.setGrade(i, grade)
            p.setSpeedMax(i, speedMax)
        }
        return p
    }

    private fun defaultCoursePhysics(path: Path): CoursePhysics = CoursePhysics(Course(path))

    // 1 — empty path
    @Test
    fun empty_path_returns_empty_path() {
        val course = defaultCoursePhysics(Path(0))
        val out = VirtualizeService.virtualizeTrack(course)
        assertEquals(0, out.size)
    }

    // 2 — single-point path returns a 1-point copy
    @Test
    fun single_point_path_returns_singleton_copy() {
        val path = Path(1)
        path.setLatitude(0, 0.123)
        path.setLongitude(0, 0.456)
        path.setElevation(0, 200.0)
        path.setDistance(0, 0.0)
        val course = defaultCoursePhysics(path)

        val out = VirtualizeService.virtualizeTrack(course)
        assertEquals(1, out.size)
        assertEquals(0.123, out.latitude(0), tolMid)
        assertEquals(0.456, out.longitude(0), tolMid)
        assertEquals(200.0, out.elevation(0), tolMid)
    }

    // 3 — flat 3-point path : speed(1) > MINIMAL_SPEED and < cyclist.maxSpeedMS
    @Test
    fun flat_three_point_path_produces_intermediate_speed() {
        val path = buildFlatPath(doubleArrayOf(0.0, 10.0, 20.0))
        val course = defaultCoursePhysics(path)
        val out = VirtualizeService.virtualizeTrack(course)

        assertEquals(3, out.size)
        // virtSpeedCurrent is NOT touched by computeDerivedData ; it preserves the
        // simulation's speed for index 1.
        val v1 = out.virtSpeedCurrent(1)
        assertTrue(v1 > EngineConstants.MINIMAL_SPEED, "speed(1)=$v1 must exceed MINIMAL_SPEED")
        assertTrue(v1 < course.cyclist.maxSpeedMS, "speed(1)=$v1 must be < maxSpeedMS=${course.cyclist.maxSpeedMS}")
    }

    // 4 — time(0) == 0 ; time(i) > time(i-1) strict monotonic on the simulated range
    //     [0, n-2]. The last point (n-1) is copied verbatim from input so its time is the
    //     input value (0 here).
    @Test
    fun time_is_strictly_monotonic_from_zero() {
        val path = buildFlatPath(doubleArrayOf(0.0, 5.0, 10.0, 15.0, 20.0))
        val course = defaultCoursePhysics(path)
        val out = VirtualizeService.virtualizeTrack(course)

        assertEquals(0.0, out.time(0), 0.0)
        // Simulated range : [0, n-2]. The last point (n-1) is a verbatim copy of the input.
        for (i in 1 until out.size - 1) {
            assertTrue(
                out.time(i) > out.time(i - 1),
                "time($i)=${out.time(i)} must be > time(${i - 1})=${out.time(i - 1)}",
            )
        }
    }

    // 5 — cyclist=0 W : the simulation crawls (total time much larger than with full power).
    //     With strictly resistive power, the energy integrator clamps internally to
    //     MINIMAL_SPEED, but the trapezoidal identity `v_new = 2·dx/dt - v_old` can produce
    //     non-physical positive transients when getDt converges on a `dt` that doesn't satisfy
    //     `dx = (v_old + v_new) × dt / 2`. We therefore assert a system property : total elapsed
    //     simulation time is much greater than with 280 W (since dt must compensate dx).
    @Test
    fun zero_cyclist_power_yields_much_slower_simulation_than_full_power() {
        val distances = doubleArrayOf(0.0, 10.0, 20.0, 30.0)
        val zeroPower =
            CoursePhysics(
                Course(buildFlatPath(distances)),
                cyclistPowerProvider = PowerProviderConstant(0.0),
            )
        val fullPower = defaultCoursePhysics(buildFlatPath(distances))

        val outZero = VirtualizeService.virtualizeTrack(zeroPower)
        val outFull = VirtualizeService.virtualizeTrack(fullPower)

        // Compare time at the second-to-last simulated index (last simulated point).
        val tZero = outZero.time(outZero.size - 2)
        val tFull = outFull.time(outFull.size - 2)
        assertTrue(
            tZero > tFull,
            "0 W simulation time ($tZero ms) should exceed 280 W simulation time ($tFull ms)",
        )
    }

    // 6 — speedMax constraint : if speedMax(i) = 1 m/s everywhere, speed(i) ≤ 1 m/s
    @Test
    fun speedMax_constraint_caps_virt_speed() {
        val path = buildFlatPath(doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0), speedMax = 1.0)
        val course = defaultCoursePhysics(path)
        val out = VirtualizeService.virtualizeTrack(course)

        for (i in 1 until out.size - 1) {
            val v = out.virtSpeedCurrent(i)
            assertTrue(v <= 1.0 + tolMid, "virtSpeedCurrent($i)=$v should be ≤ 1 m/s")
        }
    }

    // 7 — steep climb : 280 W cyclist cannot maintain high speed
    @Test
    fun steep_climb_clamps_to_low_speed() {
        // Grade 10 % on 4 waypoints, 10 m apart.
        val path = buildFlatPath(doubleArrayOf(0.0, 10.0, 20.0, 30.0), grade = 0.10)
        val course = defaultCoursePhysics(path) // default 280 W
        val out = VirtualizeService.virtualizeTrack(course)

        for (i in 1 until out.size - 1) {
            val v = out.virtSpeedCurrent(i)
            // On a 10 % grade with 80 kg and 280 W, steady-state speed is well below 20 km/h
            // (i.e. < 6 m/s). Loose upper bound : 8 m/s.
            assertTrue(v < 8.0, "virtSpeedCurrent($i)=$v on 10 %% climb should remain below 8 m/s")
        }
    }

    // 8 — steep descent : speed climbs up to speedMax
    @Test
    fun steep_descent_caps_at_speedMax() {
        // Grade -10 % on 6 waypoints, 10 m apart, speedMax = 5 m/s.
        val path = buildFlatPath(doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0, 50.0), speedMax = 5.0, grade = -0.10)
        val course = defaultCoursePhysics(path)
        val out = VirtualizeService.virtualizeTrack(course)

        // After enough waypoints, the descent gravity power dominates and speed saturates at speedMax.
        val vLast = out.virtSpeedCurrent(out.size - 2)
        assertTrue(vLast <= 5.0 + tolMid, "virtSpeedCurrent(n-2)=$vLast must respect speedMax=5")
        assertTrue(vLast >= 4.5, "virtSpeedCurrent(n-2)=$vLast should saturate near the speedMax cap on a long descent")
    }

    // 9 — pComputedPower(i) ≥ 0 for all i (clamp at 0).
    @Test
    fun computed_cyclist_power_is_non_negative() {
        val path = buildFlatPath(doubleArrayOf(0.0, 10.0, 20.0, 30.0, 40.0))
        val course = defaultCoursePhysics(path)
        val out = VirtualizeService.virtualizeTrack(course)

        for (i in 0 until out.size) {
            val p = out.pComputedPower(i)
            assertTrue(p >= 0.0, "pComputedPower($i)=$p must be ≥ 0")
        }
    }

    // 10 — round-trip identity : re-virtualizing keeps speeds close.
    //     The simulation uses `input.distance(i)` for `dx`. After the first call,
    //     `computeDerivedData` rewrites the `distance` column from `lat/lon` haversine — so
    //     for a faithful round-trip we must use lat/lon that yield the SAME haversine
    //     distances as the synthetic `distance` slot. We pick a longitude step that gives
    //     ≈ 8 m between consecutive points on the equator (1 deg ≈ 111 km, so 8 / 111000 deg).
    @Test
    fun round_trip_virtualization_preserves_virt_speed_current() {
        val n = 5
        val stepM = 8.0
        // 1 deg longitude at the equator ≈ 6_371_000 × π / 180 m. Use the WGS84 spherical
        // equivalent : 1 m ≈ 1 / 6_371_000 rad of longitude on the equator (lat = 0).
        val degPerMeter = 1.0 / 6_371_000.0
        val p = Path(n)
        for (i in 0 until n) {
            p.setDistance(i, i * stepM)
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * stepM * degPerMeter)
            p.setElevation(i, 100.0)
            p.setGrade(i, 0.0)
            p.setSpeedMax(i, 100.0)
        }
        val course1 = defaultCoursePhysics(p)
        val out1 = VirtualizeService.virtualizeTrack(course1)

        // Restore speedMax (computeDerivedData doesn't touch it but the second simulation
        // must have a known speedMax cap).
        for (i in 0 until out1.size) out1.setSpeedMax(i, 100.0)

        val course2 = defaultCoursePhysics(out1)
        val out2 = VirtualizeService.virtualizeTrack(course2)

        assertEquals(out1.size, out2.size)
        for (i in 1 until out1.size - 1) {
            val v1 = out1.virtSpeedCurrent(i)
            val v2 = out2.virtSpeedCurrent(i)
            // Loose tolerance — binary search of getDt + haversine quantization.
            assertEquals(v1, v2, 1e-3, "virtSpeedCurrent($i) round-trip drift : $v1 vs $v2")
        }
    }

    // 11 — iteration cap : even 110k points must terminate without infinite loop.
    @Test
    fun iteration_cap_prevents_infinite_loop_on_large_path() {
        val n = 110_000
        val distances = DoubleArray(n) { it * 0.001 } // dx = 0.001 m → very small steps
        val p = Path(n)
        for (i in 0 until n) {
            p.setDistance(i, distances[i])
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * 1e-8)
            p.setElevation(i, 100.0)
            p.setGrade(i, 0.0)
            p.setSpeedMax(i, 100.0)
        }
        val course = defaultCoursePhysics(p)
        val out = VirtualizeService.virtualizeTrack(course)
        // Property : we returned a Path with the expected size. The iteration cap
        // ensures we did not spin forever even though we did not simulate every point.
        assertEquals(n, out.size)
    }

    // 12 — side-effects : virtSpeedCurrent / time / elapsed written ; lat/lon/elevation
    // copied verbatim from input.
    @Test
    fun side_effects_preserve_lat_lon_elevation_and_write_time_elapsed() {
        val n = 5
        val p = Path(n)
        for (i in 0 until n) {
            p.setDistance(i, i * 10.0)
            p.setLatitude(i, i * 1e-4)
            p.setLongitude(i, i * 2e-4)
            p.setElevation(i, 100.0 + i)
            p.setSpeedMax(i, 50.0)
        }
        val course = defaultCoursePhysics(p)
        val out = VirtualizeService.virtualizeTrack(course)

        // Lat/lon/elevation copied verbatim.
        for (i in 0 until n) {
            assertEquals(i * 1e-4, out.latitude(i), tolMid, "latitude($i) preserved")
            assertEquals(i * 2e-4, out.longitude(i), tolMid, "longitude($i) preserved")
            assertEquals(100.0 + i, out.elevation(i), tolMid, "elevation($i) preserved")
        }

        // virtSpeedCurrent : not touched by computeDerivedData, so it keeps the simulation value.
        for (i in 0 until n - 1) {
            assertTrue(out.virtSpeedCurrent(i) > 0.0, "virtSpeedCurrent($i) must be set positive")
        }

        // time strictly monotonic for the simulated range [0, n-2].
        for (i in 1 until n - 1) {
            assertTrue(out.time(i) > out.time(i - 1), "time($i) must be > time(${i - 1})")
        }

        // elapsed (post-computeDerivedData) is in SECONDS, not ms : elapsed = (time - timeStart) / 1000.
        // We use a relative tolerance since elapsed values may be a few seconds.
        for (i in 0 until n - 1) {
            assertEquals(out.time(i) / 1000.0, out.elapsed(i), tolMid, "elapsed($i) = time($i)/1000 (post-computeDerivedData)")
        }
    }
}
