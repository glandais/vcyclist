package io.github.glandais.engine.wasi

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.path.Path
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The `suspend` → synchronous bridge, and the fact that the enhancement pipeline really does run
 * through it on this target.
 *
 * This is the one test that exercises the actual simulation under wasmtime rather than a
 * marshalling detail — the export wrapper around it lives behind `read_input`, so the end-to-end
 * run is w09's job, but nothing stops calling `Enhancer` directly here.
 */
class RunSynchronouslyTest {
    private fun path(points: Int = 40): Path {
        val p = Path(points)
        for (i in 0 until points) {
            p.setLatitude(i, (45.0 + i * 0.0005) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + i * 0.0005) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 500.0 + i * 2.0)
            p.setTime(i, i * 10_000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `a block that never suspends returns its value`() {
        assertEquals(42, runSynchronously { 42 })
    }

    @Test
    fun `an exception propagates unchanged rather than being swallowed`() {
        val thrown =
            assertFailsWith<IllegalStateException> {
                runSynchronously { throw IllegalStateException("from inside the coroutine") }
            }

        assertEquals("from inside the coroutine", thrown.message)
    }

    @Test
    fun `a block that really suspends fails loudly instead of returning a wrong value`() {
        val thrown =
            assertFailsWith<IllegalStateException> {
                runSynchronously { suspendCoroutine<Int> { /* never resumed */ } }
            }

        assertTrue(thrown.message!!.contains("suspended"), thrown.message!!)
        assertTrue(thrown.message!!.contains("w05"), "the message must point at the way out")
    }

    @Test
    fun `the enhancement pipeline runs to completion through the bridge`() {
        val input = path()

        val enhanced =
            runSynchronously {
                Enhancer.enhanceCourseDefault(
                    input,
                    options =
                        EnhanceOptions(
                            fixElevation = false,
                            computeMaxSpeeds = true,
                            virtualizeTrack = true,
                            computeOnePointPerSecond = false,
                            simplifyPath = SimplifyPathOptions(enabled = false),
                        ),
                )
            }

        assertTrue(enhanced.size > 0, "the pipeline must produce points")
        assertTrue(enhanced.totalDistance > 0.0, "and a distance")
        assertTrue(enhanced.durationMs > 0.0, "and a simulated duration")
        assertTrue(enhanced.speed(enhanced.size / 2) > 0.0, "the simulation must have moved the rider")
    }
}
