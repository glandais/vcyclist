package io.github.glandais.engine.physics

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pedal-ground clearance : no power past a lean angle (ledger R10). */
class PedalStrikeTest {
    private fun physics(
        path: Path,
        bike: Bike = Bike(),
    ): CoursePhysics =
        CoursePhysics(
            course = Course(path = path, cyclist = Cyclist.DEFAULT, bike = bike),
            rhoProvider = RhoProviderDefault,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = PowerProviderConstant(280.0),
        )

    /** One point carrying just the speed and radius the check reads. */
    private fun pointAt(
        speedMS: Double,
        radiusM: Double,
    ): Path =
        Path(1).apply {
            setSpeed(0, speedMS)
            setRadius(0, radiusM)
        }

    // ---- 1. The threshold ------------------------------------------------------

    @Test
    fun `the cut-off happens exactly at the configured lean angle`() {
        val radius = 30.0
        // Speed that leans the bike exactly 20 degrees on this radius.
        val vAt20 = sqrt(tan(20.0 * PI / 180.0) * EngineConstants.G * radius)

        assertTrue(MuscularPowerProvider.pedalsClear(physics(pointAt(vAt20 * 0.99, radius)), pointAt(vAt20 * 0.99, radius), 0))
        assertFalse(MuscularPowerProvider.pedalsClear(physics(pointAt(vAt20 * 1.01, radius)), pointAt(vAt20 * 1.01, radius), 0))
    }

    @Test
    fun `a straight road always keeps the pedals down`() {
        // 200 m is MaxSpeedComputer's "no measurable curvature" clamp; even at 100 km/h that is
        // 21.3 degrees of lean… which is past 20, so the *clamp* value is not automatically clear.
        // At a realistic 15 m/s it is 6.5 degrees.
        val path = pointAt(15.0, 200.0)
        assertTrue(MuscularPowerProvider.pedalsClear(physics(path), path, 0))
    }

    @Test
    fun `an absent radius fails open`() {
        // MaxSpeedComputer has not run: radius is 0. Failing closed would zero a whole ride.
        for (radius in listOf(0.0, -1.0, Double.NaN)) {
            val path = pointAt(15.0, radius)
            assertTrue(MuscularPowerProvider.pedalsClear(physics(path), path, 0), "radius = $radius")
        }
    }

    @Test
    fun `ninety degrees disables the cut-off`() {
        val bike = Bike(maxPedalingLeanAngleDeg = 90.0)
        val path = pointAt(30.0, 5.0) // absurd lean, ~80 degrees
        assertTrue(MuscularPowerProvider.pedalsClear(physics(path, bike), path, 0))
        assertEquals(Double.POSITIVE_INFINITY, bike.tanMaxPedalingLeanAngle)
    }

    // ---- 2. What the provider writes -------------------------------------------

    @Test
    fun `no power reaches the wheel while the pedals are up, but intent stays visible`() {
        val path = pointAt(12.0, 15.0) // ~44 degrees of lean
        val course = physics(path)

        val wheel = MuscularPowerProvider.powerAt(course, path, 0)

        assertEquals(0.0, wheel, 0.0, "no power may reach the chain")
        assertEquals(0.0, path.pCyclistProvidedMuscular(0), 0.0)
        assertEquals(0.0, path.pCyclistProvidedWheel(0), 0.0)
        assertEquals(280.0, path.pCyclistProvidedOptimalPower(0), 1e-9, "the rider's intent is still recorded")
    }

    @Test
    fun `power flows normally below the threshold`() {
        val path = pointAt(5.0, 60.0) // ~2.4 degrees
        val course = physics(path)

        val wheel = MuscularPowerProvider.powerAt(course, path, 0)
        assertEquals(280.0 * Bike.DEFAULT.efficiency, wheel, 1e-9)
        assertEquals(280.0, path.pCyclistProvidedMuscular(0), 1e-9)
    }

    // ---- 3. Through the pipeline -------------------------------------------------

    /** 180° hairpin of ~15 m radius between two 250 m straights. */
    private fun hairpinPath(): Path {
        val lat = mutableListOf<Double>()
        val lon = mutableListOf<Double>()
        for (i in 0 until 25) {
            lat.add(45.0)
            lon.add(3.0 + i * 1.27e-4)
        }
        for (k in 1..9) {
            val a = k * (PI / 9.0)
            lat.add(45.0 + 1.35e-4 * (1.0 - cos(a)))
            lon.add(3.0 + 24 * 1.27e-4 + 1.9e-4 * sin(a))
        }
        for (i in 1 until 25) {
            lat.add(45.0 + 2.7e-4)
            lon.add(3.0 + 24 * 1.27e-4 - i * 1.27e-4)
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

    private suspend fun enhance(bike: Bike): Path {
        val base = Enhancer.getDefaultCourse(hairpinPath())
        val course = base.copy(course = base.course.copy(bike = bike))
        return Enhancer.enhanceCourse(course, EnhanceOptions(fixElevation = false))
    }

    @Test
    fun `a hairpin is ridden without pedalling, and costs time`() =
        runTest {
            val withCutOff = enhance(Bike())
            val without = enhance(Bike(maxPedalingLeanAngleDeg = 90.0))

            val coasting = (0 until withCutOff.size).count { withCutOff.pCyclistProvidedMuscular(it) == 0.0 }
            assertTrue(coasting > 0, "the corner must be ridden with the pedals up")

            val cutOffS = (withCutOff.time(withCutOff.size - 1) - withCutOff.time(0)) / 1000.0
            val freeS = (without.time(without.size - 1) - without.time(0)) / 1000.0
            assertTrue(cutOffS > freeS, "not pedalling through a corner must cost time: $cutOffS vs $freeS")
            assertTrue((cutOffS - freeS) / freeS < 0.25, "one hairpin cannot cost a quarter of the ride")
        }

    @Test
    fun `a straight route is unaffected`() =
        runTest {
            val straight =
                Path(600).apply {
                    for (i in 0 until 600) {
                        setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
                        setLongitude(i, (3.0 + i * 1.27e-4) * MathConstants.DEG_TO_RAD)
                        setElevation(i, 100.0)
                        setTime(i, i * 1000.0)
                    }
                    computeDerivedData()
                }

            suspend fun durationS(bike: Bike): Double {
                val base = Enhancer.getDefaultCourse(straight)
                val course = base.copy(course = base.course.copy(bike = bike))
                val out = Enhancer.enhanceCourse(course, EnhanceOptions(fixElevation = false))
                return (out.time(out.size - 1) - out.time(0)) / 1000.0
            }

            assertEquals(durationS(Bike(maxPedalingLeanAngleDeg = 90.0)), durationS(Bike()), 0.0)
        }
}
