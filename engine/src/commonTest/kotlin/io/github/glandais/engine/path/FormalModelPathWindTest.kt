package io.github.glandais.engine.path

import io.github.glandais.elevation.MathConstants
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counterexample from the Lean executable model `formal/physics/Angles.lean` (`fiji` vs
 * `fijiShifted`).
 *
 * Contract: [dominantHeadwindAzimuthDeg] is the azimuth of the wind opposing the course's mean
 * bearing — a property of the shape of the ride, so translating a course in longitude must not
 * change it. The rest of the path model supports courses crossing the antimeridian (`4162fd2`,
 * `FieldInterpolationTest`, `LocalFrame` unwraps longitudes about the first point).
 *
 * `meanDirectionFromStart` computes `dx = (lon_i − lon₀) · cos(lat₀)` without wrapping the
 * longitude difference into (−π, π]. An eastbound ride from 179.99°E to 179.99°W reads every
 * point after the crossing as ~360° to the *west*, and the answer flips: 90° instead of 270°.
 */
class FormalModelPathWindTest {
    private fun eastbound(startLonDeg: Double): Path {
        val n = 10
        return Path(n).apply {
            for (i in 0 until n) {
                var lon = startLonDeg + 0.0025 * i
                if (lon > 180.0) lon -= 360.0
                setLatitude(i, -16.8 * MathConstants.DEG_TO_RAD)
                setLongitude(i, lon * MathConstants.DEG_TO_RAD)
                setElevation(i, 0.0)
                setTime(i, i * 30_000.0)
            }
            computeDerivedData()
        }
    }

    @Test
    fun headwind_azimuth_is_unchanged_when_the_course_crosses_the_antimeridian() {
        val shifted = eastbound(169.99).dominantHeadwindAzimuthDeg()
        assertEquals(270.0, shifted, 0.5, "precondition: an eastbound ride gets a westward wind")

        val crossing = eastbound(179.99).dominantHeadwindAzimuthDeg()
        assertTrue(
            abs(crossing - shifted) < 0.5,
            "same eastbound ride across the antimeridian: azimuth $crossing°, expected $shifted°",
        )
    }
}
