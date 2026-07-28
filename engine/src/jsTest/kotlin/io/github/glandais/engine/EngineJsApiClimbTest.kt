package io.github.glandais.engine

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Kotlin/JS climb façade — runs under Node and in headless Chrome via Karma. */
class EngineJsApiClimbTest {
    /** 10 km rising 500 m: one climb at 5 %. */
    private fun climbPath(): Path {
        val p = Path(101)
        for (i in 0 until 101) {
            p.setLatitude(i, (45.0 + i * (100.0 / 111_320.0)) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 500.0 + i * 5.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `detectClimbs returns a climb with usable scalar fields`() {
        val climbs = detectClimbs(climbPath())
        assertEquals(1, climbs.size)
        val climb = climbs[0]
        assertEquals(0, climb.startIndex)
        assertEquals(100, climb.endIndex)
        assertEquals(500.0, climb.elevationGainM, 1.0)
        assertEquals(0.05, climb.averageGrade, 0.002)
        assertTrue(climb.lengthM > 9_000.0)
    }

    @Test
    fun `the nested parts array survives the JS boundary`() {
        val climb = detectClimbs(climbPath())[0]
        assertTrue(climb.parts.isNotEmpty(), "parts must not be empty")
        // Reading through the nested DTO is the point of this test.
        val part = climb.parts[0]
        assertEquals(climb.startDistanceM, part.startDistanceM, 1e-9)
        assertTrue(part.lengthM > 0.0)
        assertEquals(part.elevationGainM / part.lengthM, part.grade, 1e-9)
        // The parts tile the climb.
        assertEquals(climb.endDistanceM, climb.parts.last().endDistanceM, 1e-9)
        assertEquals(climb.lengthM, climb.parts.sumOf { it.lengthM }, 1e-6)
    }

    @Test
    fun `detectClimbsWithOptions honours a raised grade threshold`() {
        val path = climbPath()
        // Default finds the 5 % climb…
        assertEquals(1, detectClimbs(path).size)
        // …and a 9 % minimum hides it.
        val strict = detectClimbsWithOptions(path, 10.0, 35.0, 100.0, 9.0, 1.3, 1.3)
        assertEquals(0, strict.size)
    }

    @Test
    fun `a flat path yields no climb`() {
        val p = Path(50)
        for (i in 0 until 50) {
            p.setLatitude(i, (45.0 + i * (100.0 / 111_320.0)) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 500.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        assertEquals(0, detectClimbs(p).size)
    }
}
