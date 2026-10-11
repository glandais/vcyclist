package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Counterexample from the Lean executable model `formal/physics/Angles.lean` (`bearing` vs
 * `bearingRef`).
 *
 * Contract: `Path.computeBearing` is a local equirectangular bearing, with the convention the physics relies on (`AeroPowerProvider`, `PathWindAzimuthTest`):
 * eastbound `0`, northbound `-π/2`. A bearing is a property of the direction of travel and must not
 * depend on which meridian the rider is on.
 *
 * It did: `x` was computed per point as `lon · cos(lat)` with each point's *own* latitude, so a
 * purely northward step changed `x` by `lon · (cos lat₂ − cos lat₁) ≈ −lon · sin(lat) · Δlat`, a
 * spurious east-west component proportional to the absolute longitude. At 0° the bearing was
 * right; at 3°E it was off by 1.8°, at 120°W (California) a due-north rider got −38.4° instead of
 * −90°, at 170°E −150.7°, and `AeroPowerProvider` turned that into the wrong wind angle. The
 * bearing now projects the wrapped longitude difference at the segment's mean latitude.
 */
class FormalModelBearingTest {
    private fun northbound(lonDeg: Double): Path {
        val n = 5
        val lat0 = 37.0 * MathConstants.DEG_TO_RAD
        val step = 20.0 / 6_371_000.0 // 20 m of latitude
        return Path(n).apply {
            for (i in 0 until n) {
                setLatitude(i, lat0 + i * step)
                setLongitude(i, lonDeg * MathConstants.DEG_TO_RAD)
                setElevation(i, 0.0)
                setTime(i, i * 4000.0)
            }
            computeDerivedData()
        }
    }

    @Test
    fun bearing_of_a_due_north_ride_does_not_depend_on_longitude() {
        val wrong =
            listOf(0.0, 3.0, -120.0, 170.0).mapNotNull { lonDeg ->
                val bearingDeg = northbound(lonDeg).bearing(2) * 180.0 / PI
                if (abs(bearingDeg - -90.0) < 0.5) null else "lon $lonDeg° -> $bearingDeg°"
            }
        assertTrue(wrong.isEmpty(), "due-north ride, expected bearing -90° at every longitude: $wrong")
    }

    @Test
    fun bearing_of_a_due_east_ride_across_the_antimeridian_is_east() {
        val n = 5
        val lat = 10.0 * MathConstants.DEG_TO_RAD
        val step = 20.0 / (6_371_000.0 * kotlin.math.cos(lat))
        val path =
            Path(n).apply {
                for (i in 0 until n) {
                    var lon = PI - 2 * step + i * step
                    if (lon > PI) lon -= 2 * PI
                    setLatitude(i, lat)
                    setLongitude(i, lon)
                    setElevation(i, 0.0)
                    setTime(i, i * 4000.0)
                }
                computeDerivedData()
            }
        val bearingDeg = path.bearing(2) * 180.0 / PI
        assertTrue(abs(bearingDeg) < 0.5, "eastbound across 180°, expected 0°, got $bearingDeg°")
    }
}
