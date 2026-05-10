package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-12

class ConstantsTest {
    @Test
    fun `earth - WGS84 semi-major axis`() {
        assertEquals(6_378_137.0, EarthConstants.SEMI_MAJOR_AXIS)
    }

    @Test
    fun `earth - mean radius`() {
        assertEquals(6_371_000.0, EarthConstants.MEAN_RADIUS)
    }

    @Test
    fun `earth - WGS84 first eccentricity squared`() {
        assertTrue((EarthConstants.FIRST_ECCENTRICITY_SQUARED - 0.006_694_379_990_14).absoluteValue < EPS)
    }

    @Test
    fun `earth - mean radius lesser than semi-major axis`() {
        assertTrue(EarthConstants.MEAN_RADIUS < EarthConstants.SEMI_MAJOR_AXIS)
    }

    @Test
    fun `math - DEG_TO_RAD`() {
        assertTrue((MathConstants.DEG_TO_RAD - PI / 180.0).absoluteValue < EPS)
    }

    @Test
    fun `math - RAD_TO_DEG`() {
        assertTrue((MathConstants.RAD_TO_DEG - 180.0 / PI).absoluteValue < EPS)
    }

    @Test
    fun `math - reciprocal conversion`() {
        assertTrue((MathConstants.DEG_TO_RAD * MathConstants.RAD_TO_DEG - 1.0).absoluteValue < EPS)
    }

    @Test
    fun `algorithm - min smoothing points`() {
        assertEquals(3, AlgorithmConstants.MIN_SMOOTHING_POINTS)
    }
}
