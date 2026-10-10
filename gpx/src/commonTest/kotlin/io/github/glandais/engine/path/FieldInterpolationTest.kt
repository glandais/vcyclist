package io.github.glandais.engine.path

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FieldInterpolationTest {
    private val deg = PI / 180.0

    @Test
    fun linearFieldIsPlainLinear() {
        assertEquals(15.0, FieldInterpolation.interpolate(PointField.ELEVATION, 10.0, 20.0, 0.5), 1e-12)
        // A latitude is not circular, even across a large delta.
        assertEquals(0.0, FieldInterpolation.interpolate(PointField.LATITUDE, -1.5, 1.5, 0.5), 1e-12)
    }

    @Test
    fun nanOnEitherSideIsNan() {
        assertTrue(FieldInterpolation.interpolate(PointField.LONGITUDE, Double.NaN, 1.0, 0.5).isNaN())
        assertTrue(FieldInterpolation.interpolate(PointField.ELEVATION, 1.0, Double.NaN, 0.5).isNaN())
    }

    @Test
    fun longitudeWithinRangeIsUnchanged() {
        val v = FieldInterpolation.interpolate(PointField.LONGITUDE, 10 * deg, 30 * deg, 0.25)
        assertEquals(15 * deg, v, 1e-12)
    }

    @Test
    fun longitudeTakesShortestArcAcrossAntimeridian() {
        val v1 = 179.0 * deg
        val v2 = -179.0 * deg
        // A quarter of the way: 179.5°, still on the eastern side.
        assertEquals(179.5 * deg, FieldInterpolation.interpolate(PointField.LONGITUDE, v1, v2, 0.25), 1e-9)
        // Three quarters: past the seam, folded back to -179.5°.
        assertEquals(-179.5 * deg, FieldInterpolation.interpolate(PointField.LONGITUDE, v1, v2, 0.75), 1e-9)
        // Reverse direction.
        assertEquals(179.5 * deg, FieldInterpolation.interpolate(PointField.SOURCE_LONGITUDE, v2, v1, 0.75), 1e-9)
    }

    @Test
    fun unboundedAngleStaysContinuous() {
        // A wind direction in [0, 2π): 350° → 10° goes through 0°/360°, not through 180°.
        val v = FieldInterpolation.interpolate(PointField.WIND_DIRECTION, 350 * deg, 10 * deg, 0.25)
        assertEquals(355 * deg, v, 1e-9)
        val w = FieldInterpolation.interpolate(PointField.WIND_DIRECTION, 350 * deg, 10 * deg, 0.75)
        assertTrue(abs(w - 365 * deg) < 1e-9 || abs(w - 5 * deg) < 1e-9, "w = ${w / deg}°")
    }

    @Test
    fun pointPerSecondAcrossAntimeridianStaysLocal() {
        // 10 s over ~100 m straddling the ±180° meridian at the equator.
        val dLon = 100.0 / 6_371_000.0
        val p = Path(2)
        p.setLongitude(0, PI - dLon / 2.0)
        p.setLongitude(1, -PI + dLon / 2.0)
        p.setTime(0, 0.0)
        p.setTime(1, 10_000.0)
        p.computeDerivedData()
        val out = PointPerSecond.computeOnePointPerSecond(p)
        assertTrue(out.size >= 10)
        for (i in 0 until out.size) {
            assertTrue(abs(abs(out.longitude(i)) - PI) <= dLon, "lon($i) = ${out.longitudeDeg(i)}°")
        }
        assertTrue(abs(out.totalDistance - p.totalDistance) / p.totalDistance < 0.005, "${out.totalDistance}")
    }
}
