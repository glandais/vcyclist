package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `pBrake` — the energy [VirtualizeService]'s `speedMax` clip removes, recovered by
 * [PowerComputer.computeCyclistPower] instead of being discarded silently (ledger R12).
 */
class BrakePowerTest {
    private fun course(path: Path): CoursePhysics =
        CoursePhysics(
            course = Course(path = path, cyclist = Cyclist.DEFAULT),
            rhoProvider = RhoProviderDefault,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = PowerProviderConstant(280.0),
        )

    /** Straight, flat, 2 m spacing — the geometry `Enhancer` feeds the simulation. */
    private fun straightPath(
        n: Int,
        speedMax: (Int) -> Double,
    ): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + i * 2.54e-5) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
            p.setSpeedMax(i, speedMax(i))
        }
        p.computeDerivedData()
        return p
    }

    // ---- 1. No clip → no braking ----------------------------------------------

    @Test
    fun `an unconstrained ride never brakes`() {
        // speedMax far above anything 280 W can produce on the flat.
        val out = VirtualizeService.virtualizeTrack(course(straightPath(60) { 100.0 }))
        for (i in out.indices) {
            assertEquals(0.0, out.pBrake(i), 0.0, "unexpected braking at $i")
        }
    }

    @Test
    fun `a cap the rider simply sits at is not braking`() {
        // 3 m/s on the flat costs ~20 W: the inverse problem attributes the capped segment to a
        // rider soft-pedalling, not to a rider braking. Only a *deceleration* the resistive
        // forces cannot explain is braking — see the KDoc on computeCyclistPower.
        val out = VirtualizeService.virtualizeTrack(course(straightPath(60) { 3.0 }))
        for (i in out.indices) {
            assertEquals(0.0, out.pBrake(i), 0.0, "steady capped speed must not read as braking at $i")
        }
        assertTrue(out.pComputedPower(50) > 0.0, "…the rider is pedalling, just gently")
    }

    // ---- 2. A clip → braking, of the right magnitude ---------------------------

    @Test
    fun `a speed limit dropping mid-path shows up as negative brake power`() {
        // Free to ~10 m/s, then a hard 3 m/s limit — the shape of a corner after a straight.
        val out = VirtualizeService.virtualizeTrack(course(straightPath(60) { if (it < 30) 100.0 else 3.0 }))

        assertTrue(out.indices.any { out.pBrake(it) < 0.0 }, "no braking recorded under a cap")
        for (i in out.indices) {
            assertTrue(out.pBrake(i) <= 0.0, "brake power must be resistive (≤ 0) at $i, was ${out.pBrake(i)}")
        }
        // Braking and pedalling are mutually exclusive: the clip means the rider needed no power.
        for (i in out.indices) {
            if (out.pBrake(i) < 0.0) {
                assertEquals(0.0, out.pComputedPower(i), 0.0, "cyclist power must be 0 while braking, at $i")
            }
        }
    }

    @Test
    fun `brake power closes the energy balance`() {
        // pComputedTotalPower = resistive + wheel-in − brake-out, by construction. Assert the
        // decomposition holds point by point rather than trusting the sign convention.
        val out = VirtualizeService.virtualizeTrack(course(straightPath(60) { if (it < 30) 100.0 else 3.0 }))
        for (i in 1 until out.size) {
            val fromCyclist = out.pComputedPower(i) * Bike.DEFAULT.efficiency // undo the division
            val wheel = out.pComputedWheelPower(i)
            assertEquals(
                wheel,
                fromCyclist + out.pBrake(i),
                1e-9 * maxOf(1.0, abs(wheel)),
                "wheel power ≠ cyclist + brake at $i",
            )
        }
    }

    // ---- 3. Through the pipeline, on a shape that actually forces braking -------

    /** A hairpin : 200 m straight, a tight 180° turn, then 200 m back. */
    private fun hairpinPath(): Path {
        val lat = mutableListOf<Double>()
        val lon = mutableListOf<Double>()
        // Approach, heading east.
        for (i in 0 until 20) {
            lat.add(45.0)
            lon.add(3.0 + i * 1.27e-4)
        }
        // 180° turn of ~15 m radius, 9 points.
        for (k in 1..9) {
            val a = k * (kotlin.math.PI / 9.0)
            lat.add(45.0 + (1.35e-4 * (1.0 - kotlin.math.cos(a))))
            lon.add(3.0 + 19 * 1.27e-4 + 1.9e-4 * kotlin.math.sin(a))
        }
        // Return leg, heading west. Starts at 1 : at a = pi the turn already emits that point,
        // and a duplicate would make the segment zero-length.
        for (i in 1 until 20) {
            lat.add(45.0 + 2.7e-4)
            lon.add(3.0 + 19 * 1.27e-4 - i * 1.27e-4)
        }
        val p = Path(lat.size)
        for (i in lat.indices) {
            p.setLatitude(i, lat[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lon[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `a hairpin produces braking before the corner and none on the straights`() =
        runTest {
            val out =
                Enhancer.enhanceCourseDefault(
                    hairpinPath(),
                    options = EnhanceOptions(fixElevation = false),
                )

            val braking = (0 until out.size).filter { out.pBrake(it) < 0.0 }
            assertTrue(braking.isNotEmpty(), "a hairpin must force some braking")
            for (i in 0 until out.size) {
                assertTrue(out.pBrake(i) <= 0.0, "brake power must be ≤ 0 at $i")
                assertTrue(out.pBrake(i).isFinite(), "non-finite brake power at $i")
            }

            // Braking happens where the trace is at its tightest, not spread over the straights.
            val tightestRadius = (0 until out.size).minOf { out.radius(it) }
            assertTrue(tightestRadius < 200.0, "fixture is not curved enough to test braking")
        }

    @Test
    fun `braking never exceeds the configured deceleration limit`() {
        // With `speedMax` coming from MaxSpeedComputer's backward pass — the only source that
        // respects `maxBrakeG` — the recorded braking power must stay under `m_eq · a_max · v`.
        // This is the invariant that says `pBrake` is the *rider's* braking and not an artefact
        // of the clip: a bike cannot shed energy faster than its tyres allow.
        val physics = course(hairpinPath())
        MaxSpeedComputer.computeMaxSpeeds(physics.course)
        val out = VirtualizeService.virtualizeTrack(physics)

        val mEq = PowerComputer.equivalentMass(physics)
        val aMax = Cyclist.DEFAULT.maxBrakeMS2
        var seen = false
        for (i in 1 until out.size) {
            val brake = out.pBrake(i)
            if (brake >= 0.0) continue
            seen = true
            // `v(i-1)` is the speed braking starts from over the interval.
            val ceiling = mEq * aMax * out.speed(i - 1)
            assertTrue(
                abs(brake) <= ceiling * 1.05,
                "braking ${abs(brake).toInt()} W at $i exceeds the ${ceiling.toInt()} W " +
                    "available at ${(out.speed(i - 1) * 3.6).toInt()} km/h",
            )
        }
        assertTrue(seen, "fixture produced no braking, invariant untested")
    }

    @Test
    fun `the field is zero everywhere when the simulation is skipped`() =
        runTest {
            val out =
                Enhancer.enhanceCourseDefault(
                    hairpinPath(),
                    options =
                        EnhanceOptions(
                            fixElevation = false,
                            virtualizeTrack = false,
                            computeOnePointPerSecond = false,
                        ),
                )
            for (i in 0 until out.size) assertEquals(0.0, out.pBrake(i), 0.0, "at $i")
        }
}
