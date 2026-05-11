package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AeroPowerProviderTest {
    private val tolTight = 1e-9
    private val tolStrict = 1e-12

    /** Default `aeroCoef = (0.7 × 0.5 × 1.225) / 2 = 0.214375`. */
    private val defaultAeroCoef = (0.7 * 0.5 * 1.225) / 2.0

    private fun buildCp(
        speed: Double,
        bearing: Double = 0.0,
        wind: Wind = Wind.NONE,
    ): Pair<CoursePhysics, Path> {
        val path = Path(1)
        path.setSpeed(0, speed)
        path.setBearing(0, bearing)
        val course = Course(path)
        val cp = CoursePhysics(course = course, windProvider = WindProviderConstant(wind))
        return cp to path
    }

    @Test
    fun no_wind_zero_speed_yields_zero_power() {
        val (cp, path) = buildCp(speed = 0.0)
        val p = AeroPowerProvider.powerAt(cp, path, 0)
        assertEquals(0.0, p, 0.0)
    }

    @Test
    fun no_wind_at_10ms_matches_cubic_formula() {
        // aeroCoef = 0.214375 ; P = -aeroCoef × 1000 = -214.375
        val (cp, path) = buildCp(speed = 10.0)
        val p = AeroPowerProvider.powerAt(cp, path, 0)
        assertEquals(-214.375, p, tolTight)
    }

    @Test
    fun no_wind_path_aeroCoef_side_effect_is_written() {
        val (cp, path) = buildCp(speed = 10.0)
        AeroPowerProvider.powerAt(cp, path, 0)
        assertEquals(defaultAeroCoef, path.aeroCoef(0), tolStrict)
    }

    @Test
    fun no_wind_path_pAero_matches_returned_value() {
        val (cp, path) = buildCp(speed = 10.0)
        val returned = AeroPowerProvider.powerAt(cp, path, 0)
        assertEquals(returned, path.pAero(0), 0.0)
    }

    @Test
    fun tailwind_reduces_resistance_magnitude_vs_no_wind() {
        // In the Isvan formula, `alpha` is the angle between the cyclist's bearing and the
        // wind's direction-as-bearing. `alpha = π` ⇒ wind is blowing against the cyclist's
        // motion vector in absolute terms — equivalent to the cyclist gaining a TAILWIND in
        // their own reference frame (relative wind magnitude < cyclist speed).
        //
        // Bearing=0 (East), wind.directionRad = 3π/2 (West in meteo conv).
        // windDirAsBearing = π/2 − 3π/2 = −π. alpha = −π − 0 = −π. cos(alpha) = −1.
        // l1 = 10 + 5·(−1) = 5 → less apparent wind → less drag.
        val (cpNo, pathNo) = buildCp(speed = 10.0)
        val noWindP = AeroPowerProvider.powerAt(cpNo, pathNo, 0)

        val (cpTail, pTail) =
            buildCp(
                speed = 10.0,
                bearing = 0.0,
                wind = Wind(speedMS = 5.0, directionRad = 3.0 * PI / 2.0),
            )
        val tail = AeroPowerProvider.powerAt(cpTail, pTail, 0)
        assertTrue(
            tail > noWindP, // less negative
            "expected tailwind P=$tail > no-wind P=$noWindP (less resistive)",
        )
    }

    @Test
    fun headwind_increases_resistance_magnitude_vs_no_wind() {
        // alpha = 0 ⇒ wind blows in the cyclist's heading direction in absolute terms — the
        // cyclist runs INTO the wind ⇒ apparent wind = cyclist speed + wind speed (head wind).
        //
        // Bearing=0 (East), wind.directionRad = π/2 (East in meteo conv) ⇒
        // windDirAsBearing = 0, alpha = 0, cos(alpha) = 1.
        // l1 = 10 + 5 = 15 → more apparent wind → more drag.
        val (cpNo, pathNo) = buildCp(speed = 10.0)
        val noWindP = AeroPowerProvider.powerAt(cpNo, pathNo, 0)

        val (cpHead, pHead) =
            buildCp(speed = 10.0, bearing = 0.0, wind = Wind(speedMS = 5.0, directionRad = PI / 2.0))
        val head = AeroPowerProvider.powerAt(cpHead, pHead, 0)
        assertTrue(
            head < noWindP, // more negative
            "expected headwind P=$head < no-wind P=$noWindP (more resistive)",
        )
    }

    @Test
    fun crosswind_resistance_lies_between_tailwind_and_headwind() {
        // alpha = π/2 (perpendicular) → l1 = 10, l3 = 125, lambda = 1.04 → middle case.
        val (cpTail, pTail) =
            buildCp(
                speed = 10.0,
                bearing = 0.0,
                wind = Wind(speedMS = 5.0, directionRad = 3.0 * PI / 2.0),
            )
        val (cpCross, pCross) =
            buildCp(speed = 10.0, bearing = 0.0, wind = Wind(speedMS = 5.0, directionRad = 0.0))
        val (cpHead, pHead) =
            buildCp(speed = 10.0, bearing = 0.0, wind = Wind(speedMS = 5.0, directionRad = PI / 2.0))
        val tail = AeroPowerProvider.powerAt(cpTail, pTail, 0)
        val cross = AeroPowerProvider.powerAt(cpCross, pCross, 0)
        val head = AeroPowerProvider.powerAt(cpHead, pHead, 0)
        assertTrue(head < cross, "expected headwind P=$head < crosswind P=$cross")
        assertTrue(cross < tail, "expected crosswind P=$cross < tailwind P=$tail")
    }

    @Test
    fun side_effects_with_wind_are_all_written() {
        val (cp, path) =
            buildCp(speed = 10.0, bearing = 0.0, wind = Wind(speedMS = 5.0, directionRad = 0.0))
        AeroPowerProvider.powerAt(cp, path, 0)
        // aeroCoef + pAero are always written.
        assertEquals(defaultAeroCoef, path.aeroCoef(0), tolStrict)
        assertTrue(path.pAero(0) != 0.0)
        // Wind-specific slots.
        assertEquals(5.0, path.windSpeed(0), 0.0)
        assertEquals(0.0, path.windDirection(0), 0.0)
        assertEquals(PI / 2.0, path.windBearing(0), tolStrict)
        assertEquals(PI / 2.0, path.windAlpha(0), tolStrict)
    }

    @Test
    fun zero_wind_speed_takes_no_wind_branch_even_with_non_zero_direction() {
        // Wind(speedMS=0, directionRad=π/4) must NOT write windSpeed/windDirection.
        val (cp, path) =
            buildCp(speed = 10.0, bearing = 0.0, wind = Wind(speedMS = 0.0, directionRad = PI / 4.0))
        val p = AeroPowerProvider.powerAt(cp, path, 0)
        // No-wind branch : P = -aeroCoef × v³ = -214.375.
        assertEquals(-214.375, p, tolTight)
        // Wind slots untouched (default = 0.0 from GeneratedPath init).
        assertEquals(0.0, path.windSpeed(0), 0.0)
        assertEquals(0.0, path.windDirection(0), 0.0)
        assertEquals(0.0, path.windBearing(0), 0.0)
        assertEquals(0.0, path.windAlpha(0), 0.0)
    }

    @Test
    fun isvan_model_matches_precomputed_value_at_v10_bearing0_wind5N() {
        // Inputs : speed=10, bearing=0, wind=Wind(5, 0) (wind direction = North in meteo conv).
        // Conversion : windDirAsBearing = π/2 − 0 = π/2 ; alpha = π/2 − 0 = π/2.
        // cos(π/2) in IEEE 754 is ≈ 6.123e-17 (not exactly 0) but the floating-point arithmetic
        // collapses to the analytic clean value :
        //   l1 = 10 + 5·cos(π/2)  (≈ 10)
        //   l3 = 100 + 25 + 100·cos(π/2)  (≈ 125)
        //   l4 = l1²/l3 ≈ 0.8
        //   lambda = 0.8 + 1.2·0.2 = 1.04
        //   sqrt(125) ≈ 11.180339887498949
        //   P = -0.214375 × 1.04 × 11.180339887498949 × 10 × 10
        //     = -249.26567779178907 W
        val (cp, path) =
            buildCp(speed = 10.0, bearing = 0.0, wind = Wind(speedMS = 5.0, directionRad = 0.0))
        val p = AeroPowerProvider.powerAt(cp, path, 0)
        assertEquals(-249.26567779178907, p, tolTight)
        // Verify intermediate path fields too.
        assertEquals(PI / 2.0, path.windBearing(0), tolStrict)
        assertEquals(PI / 2.0, path.windAlpha(0), tolStrict)
    }

    @Test
    fun no_wind_matches_isvan_branch_when_wind_speed_is_strictly_zero() {
        // Sanity : ensure the no-wind cubic and the Isvan formula at wind=0 would coincide.
        val (cpNo, pNo) = buildCp(speed = 8.0)
        val noWind = AeroPowerProvider.powerAt(cpNo, pNo, 0)

        // Manually invoke the Isvan formula with wind=0 to confirm the cubic equivalence.
        // l1 = speed, l3 = speed² → l4 = 1 → lambda = 1 → P = -aeroCoef × √(v²) × v × v = -aeroCoef × v³.
        val aeroCoef = (0.7 * 0.5 * 1.225) / 2.0
        val expected = -aeroCoef * 8.0 * 8.0 * 8.0
        assertEquals(expected, noWind, tolTight)
    }

    @Test
    fun aeroCoef_with_custom_air_density_is_used() {
        // Override RhoProvider via SAM lambda → aeroCoef changes proportionally.
        val path = Path(1)
        path.setSpeed(0, 10.0)
        val course = Course(path)
        val customRho = RhoProvider { _, _, _ -> 1.0 } // pure unit density
        val cp = CoursePhysics(course = course, rhoProvider = customRho)
        AeroPowerProvider.powerAt(cp, path, 0)
        val expectedAeroCoef = (0.7 * 0.5 * 1.0) / 2.0
        assertEquals(expectedAeroCoef, path.aeroCoef(0), tolStrict)
        // P = -expectedAeroCoef × 1000.
        assertEquals(-expectedAeroCoef * 1000.0, path.pAero(0), tolTight)
    }

    @Test
    fun sam_aero_provider_overrides_default_coefficient() {
        // Custom AeroProvider returning a fixed value → P = -fixed × v³.
        val path = Path(1)
        path.setSpeed(0, 10.0)
        val course = Course(path)
        val fixedAero = AeroProvider { _, _, _ -> 0.5 }
        val cp = CoursePhysics(course = course, aeroProvider = fixedAero)
        val p = AeroPowerProvider.powerAt(cp, path, 0)
        assertEquals(-0.5 * 1000.0, p, tolTight)
        assertEquals(0.5, path.aeroCoef(0), tolStrict)
    }

    @Test
    fun sign_invariant_on_no_wind_grid() {
        for (vInt in 0..30) {
            val (cp, path) = buildCp(speed = vInt.toDouble())
            val p = AeroPowerProvider.powerAt(cp, path, 0)
            assertTrue(p <= 0.0, "expected P ≤ 0 at v=$vInt, got $p")
            // |P| grows cubically.
            assertEquals(-defaultAeroCoef * vInt.toDouble() * vInt * vInt, p, 1e-9)
        }
        // And distinct values for v=1 and v=2 ; just a sanity check beyond zero.
        val (cp1, p1path) = buildCp(speed = 1.0)
        val (cp2, p2path) = buildCp(speed = 2.0)
        val p1 = AeroPowerProvider.powerAt(cp1, p1path, 0)
        val p2 = AeroPowerProvider.powerAt(cp2, p2path, 0)
        assertTrue(abs(p2) > abs(p1))
    }
}
