package io.github.glandais.engine

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.MaxSpeedComputer
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Wet/dry road conditions and the µ ≡ tan θ identity (ledger R9). */
class RoadConditionTest {
    // ---- 1. The presets --------------------------------------------------------

    @Test
    fun `dry is exactly the shipped default`() {
        assertEquals(EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG, RoadCondition.DRY.leanAngleDeg, 0.0)
        assertEquals(EngineConstants.DEFAULT_MAX_BRAKE_G, RoadCondition.DRY.maxBrakeG, 0.0)
        // Bit-exact, so selecting "dry" can never shift an existing simulation.
        assertEquals(Cyclist(), Cyclist().withRoadCondition(RoadCondition.DRY))
    }

    @Test
    fun `wet keeps the rider's margin and takes the road's grip away`() {
        // 77.8 % of the available grip, as in the dry default, applied to the wet limit.
        assertEquals(0.2801, RoadCondition.WET.mu, 1e-4)
        assertEquals(15.65, RoadCondition.WET.leanAngleDeg, 0.01)
        assertEquals(0.2286, RoadCondition.WET.maxBrakeG, 1e-4)

        // The same fraction of the physical limit in both conditions — that is the definition.
        assertEquals(
            RoadCondition.DRY.mu / EngineConstants.DRY_ROAD_MU,
            RoadCondition.WET.mu / EngineConstants.WET_ROAD_MU,
            1e-12,
        )
    }

    @Test
    fun `wet cuts cornering speed by the 1_58x the research reports`() {
        // v_max = √(µ·g·R), so the ratio is √(µ_dry / µ_wet) = √2.5 — independent of R.
        val ratio = sqrt(RoadCondition.DRY.mu / RoadCondition.WET.mu)
        assertEquals(1.581, ratio, 1e-3)
        assertEquals(sqrt(EngineConstants.DRY_ROAD_MU / EngineConstants.WET_ROAD_MU), ratio, 1e-12)
    }

    // ---- 2. µ is the lean angle wearing a different hat -------------------------

    @Test
    fun `mu and the lean angle are the same parameter`() {
        val c = Cyclist.DEFAULT
        assertEquals(c.tanMaxLeanAngle, c.mu, 0.0)
        assertEquals(0.700, c.mu, 1e-3)

        // Both spellings of "wet" agree.
        val viaMu = Cyclist().withMu(RoadCondition.WET.mu)
        val viaPreset = Cyclist().withRoadCondition(RoadCondition.WET)
        assertEquals(viaPreset.maxLeanAngleDeg, viaMu.maxLeanAngleDeg, 1e-12)
        // …but only the preset also moves braking, which is the point of having it.
        assertEquals(EngineConstants.DEFAULT_MAX_BRAKE_G, viaMu.maxBrakeG, 0.0)
        assertEquals(RoadCondition.WET.maxBrakeG, viaPreset.maxBrakeG, 0.0)
    }

    // ---- 3. Cornering speeds, against Zignoli's simulated riders ----------------

    @Test
    fun `a 15 m hairpin gives plausible corner speeds in both conditions`() {
        fun vMaxKmh(mu: Double) = sqrt(mu * EngineConstants.G * 15.0) * 3.6

        // Zignoli simulates ~29 km/h dry and ~25 km/h wet on a flat R = 15 m hairpin.
        assertEquals(36.5, vMaxKmh(RoadCondition.DRY.mu), 0.5)
        assertEquals(23.1, vMaxKmh(RoadCondition.WET.mu), 0.5)
    }

    // ---- 4. The MAX_RADIUS clamp must not become a speed cap --------------------

    private fun straightPath(n: Int): Path {
        val p = Path(n)
        for (i in 0 until n) {
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (3.0 + i * 2.54e-5) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `a straight road is not speed-capped by the radius clamp, wet or dry`() {
        for (condition in RoadCondition.entries) {
            val cyclist = Cyclist().withRoadCondition(condition)
            val course = Course(path = straightPath(400), cyclist = cyclist)
            MaxSpeedComputer.computeMaxSpeeds(course)

            // Away from the end-of-track sentinel, the only limit on a straight is the rider's own.
            for (i in 0 until course.path.size - 50) {
                assertEquals(
                    cyclist.maxSpeedMS,
                    course.path.speedMaxIncline(i),
                    1e-9,
                    "$condition capped a straight road at index $i",
                )
            }
        }
    }

    // ---- 5. End to end : wet costs time only where there are corners ------------

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

    private suspend fun durationS(
        path: Path,
        condition: RoadCondition,
    ): Double {
        val course =
            Enhancer.getDefaultCourse(path).let {
                it.copy(course = it.course.copy(cyclist = Cyclist().withRoadCondition(condition)))
            }
        val out = Enhancer.enhanceCourse(course, EnhanceOptions(fixElevation = false))
        return (out.time(out.size - 1) - out.time(0)) / 1000.0
    }

    @Test
    fun `wet costs time on a technical route`() =
        runTest {
            val dry = durationS(hairpinPath(), RoadCondition.DRY)
            val wet = durationS(hairpinPath(), RoadCondition.WET)
            val penalty = (wet - dry) / dry

            // Zignoli: 1.8-3.4 % over 40 km with technical sections. This fixture is one hairpin
            // in 500 m, so the corner weighs far more than it would over a real course — the
            // assertion is only that the penalty is real and of a believable sign and size.
            assertTrue(penalty > 0.01, "wet must cost time on a hairpin, got ${penalty * 100} %")
            assertTrue(penalty < 0.5, "implausible wet penalty: ${penalty * 100} %")
        }

    @Test
    fun `wet costs almost nothing on a straight route`() =
        runTest {
            val dry = durationS(straightPath(1200), RoadCondition.DRY)
            val wet = durationS(straightPath(1200), RoadCondition.WET)
            val penalty = (wet - dry) / dry

            // Zignoli: 0-0.5 % without technical sections. Not *exactly* zero, and the reason is
            // worth knowing: nothing in the wet preset touches Crr, air density or power, but
            // every track ends, and `MaxSpeedComputer` brakes to its end-of-track sentinel with
            // the wet deceleration (0.23 g instead of 0.4 g). That costs a fixed ~1 s at the
            // finish, so it shrinks as the route grows — it is a real effect, not an artefact.
            assertTrue(penalty >= 0.0, "wet cannot be faster: ${penalty * 100} %")
            assertTrue(penalty < 0.005, "straight-route penalty must stay under 0.5 %, got ${penalty * 100} %")
        }
}
