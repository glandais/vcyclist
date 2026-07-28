@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.glandais.engine

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Kotlin/Wasm climb façade, in headless Chrome via Karma. The nested `parts` array is the
 * thing the g12 spec said to verify first on this target.
 */
class EngineJsApiClimbTest {
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
        val climbs = detectClimbs(climbPath().toJsReference())
        assertEquals(1, climbs.length)
        val climb = climbs[0]!!
        assertEquals(0, climb.startIndex)
        assertEquals(100, climb.endIndex)
        assertEquals(500.0, climb.elevationGainM, 1.0)
        assertEquals(0.05, climb.averageGrade, 0.002)
    }

    @Test
    fun `the nested parts array survives the Wasm boundary`() {
        val climb = detectClimbs(climbPath().toJsReference())[0]!!
        assertTrue(climb.parts.length > 0, "parts must not be empty")
        val part = climb.parts[0]!!
        assertEquals(climb.startDistanceM, part.startDistanceM, 1e-9)
        assertTrue(part.lengthM > 0.0)
        assertEquals(part.elevationGainM / part.lengthM, part.grade, 1e-9)
        // Tiling, read entirely through the nested DTO.
        var total = 0.0
        for (i in 0 until climb.parts.length) total += climb.parts[i]!!.lengthM
        assertEquals(climb.lengthM, total, 1e-6)
        assertEquals(climb.endDistanceM, climb.parts[climb.parts.length - 1]!!.endDistanceM, 1e-9)
    }

    @Test
    fun `detectClimbsWithOptions honours a raised grade threshold`() {
        val handle = climbPath().toJsReference()
        assertEquals(1, detectClimbs(handle).length)
        assertEquals(0, detectClimbsWithOptions(handle, 10.0, 35.0, 100.0, 9.0, 1.3, 1.3).length)
    }
}
