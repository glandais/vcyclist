package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class PowerProviderFromDataTest {
    private fun buildCp(): Pair<CoursePhysics, Path> {
        val path = Path(1)
        val course = Course(path)
        val cp = CoursePhysics(course = course, cyclistPowerProvider = PowerProviderFromData)
        return cp to path
    }

    @Test
    fun reads_pInputPower_verbatim() {
        val (cp, path) = buildCp()
        path.setPInputPower(0, 150.0)
        val p = PowerProviderFromData.powerAt(cp, path, 0)
        assertEquals(150.0, p, 0.0)
    }

    @Test
    fun zero_input_returns_zero() {
        val (cp, path) = buildCp()
        // pInputPower defaults to 0.0
        val p = PowerProviderFromData.powerAt(cp, path, 0)
        assertEquals(0.0, p, 0.0)
    }

    @Test
    fun does_not_write_optimal_or_harmonics_slots() {
        val (cp, path) = buildCp()
        path.setPInputPower(0, 175.0)
        PowerProviderFromData.powerAt(cp, path, 0)
        // The object bypasses the pipeline → optimal/harmonics slots untouched (init = 0.0).
        assertEquals(0.0, path.pCyclistProvidedOptimalPower(0), 0.0)
        assertEquals(0.0, path.pCyclistProvidedOptimalPowerWithHarmonics(0), 0.0)
    }

    @Test
    fun preserves_negative_values_unchanged() {
        // Negative power is rare but should pass through unchanged (no clamping).
        val (cp, path) = buildCp()
        path.setPInputPower(0, -42.0)
        val p = PowerProviderFromData.powerAt(cp, path, 0)
        assertEquals(-42.0, p, 0.0)
    }
}
