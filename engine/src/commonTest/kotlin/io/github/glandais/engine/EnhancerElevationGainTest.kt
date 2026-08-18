package io.github.glandais.engine

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.ElevationGain
import io.github.glandais.engine.path.ElevationGainOptions
import io.github.glandais.engine.path.ElevationGainPreset
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step 6 of the pipeline, and the `sourceElevation` field that makes it independent of the 150 m
 * physics kernel.
 */
class EnhancerElevationGainTest {
    /** A 2 km ride, climbing 40 m with a 3 m-amplitude ripple riding on it. */
    private fun rippledPath(n: Int = 60): Path {
        val p = Path(n)
        for (i in 0 until n) {
            val f = i / (n - 1).toDouble()
            p.setLatitude(i, 45.0 * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.0 + f * 0.0254) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 500.0 + 40.0 * f + 3.0 * kotlin.math.sin(i * 1.4))
            p.setTime(i, i * 4_000.0)
        }
        p.computeDerivedData()
        return p
    }

    private val noPhysics =
        EnhanceOptions(
            fixElevation = false,
            computeMaxSpeeds = false,
            virtualizeTrack = false,
            computeOnePointPerSecond = false,
            simplifyPath = SimplifyPathOptions(enabled = false),
        )

    @Test
    fun the_pipeline_writes_the_filtered_gain() =
        runTest {
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(rippledPath()), noPhysics)
            assertFalse(out.elevationGainFiltered.isNaN(), "step 6 did not run")
            assertFalse(out.elevationLossFiltered.isNaN())
            assertTrue(out.elevationGainFiltered >= 0.0)
            assertTrue(out.elevationLossFiltered <= 0.0)
            assertEquals(out.elevationGainFiltered, out.reportedElevationGain)
            assertEquals(out.elevationLossFiltered, out.reportedElevationLoss)
        }

    @Test
    fun disabling_the_stage_leaves_the_reported_figure_as_the_raw_sum() =
        runTest {
            val options = noPhysics.copy(elevationGain = ElevationGainOptions(enabled = false))
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(rippledPath()), options)
            assertTrue(out.elevationGainFiltered.isNaN(), "the stage should not have run")
            assertEquals(out.elevationGain, out.reportedElevationGain)
            assertEquals(out.elevationLoss, out.reportedElevationLoss)
        }

    @Test
    fun the_smoother_keeps_the_pre_smoothing_profile() =
        runTest {
            val src = rippledPath()
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(src), noPhysics)

            var differing = 0
            for (i in 0 until out.size) {
                assertFalse(out.sourceElevation(i).isNaN(), "sourceElevation unset at $i")
                if (abs(out.sourceElevation(i) - out.elevation(i)) > 1e-9) differing++
            }
            assertTrue(
                differing > out.size / 2,
                "the 150 m kernel should have moved most elevations: only $differing of ${out.size}",
            )
        }

    @Test
    fun a_second_smoothing_pass_does_not_overwrite_the_original_profile() {
        val src = rippledPath()
        val once = ElevationStep.smoothElevation(src, windowM = 150.0)
        val twice = ElevationStep.smoothElevation(once, windowM = 150.0)
        for (i in 0 until src.size) {
            assertEquals(src.elevation(i), twice.sourceElevation(i), 1e-9, "index $i")
        }
    }

    @Test
    fun the_gain_is_measured_on_the_source_profile_not_the_smoothed_one() =
        runTest {
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(rippledPath()), noPhysics)

            // Same options, but forced onto the delivered (150 m-smoothed) profile by clearing the
            // source field. If step 6 were reading `elevation`, these two would agree.
            val stripped = out.copy()
            for (i in 0 until stripped.size) {
                stripped.setSourceElevation(i, Double.NaN)
            }
            val onSmoothed = ElevationGain.compute(stripped, ElevationGainOptions.DEFAULT)

            assertTrue(
                out.elevationGainFiltered > onSmoothed.gainM + 1.0,
                "expected the source profile to report more than the 150 m one: " +
                    "${out.elevationGainFiltered} vs ${onSmoothed.gainM}",
            )
        }

    @Test
    fun the_raw_preset_agrees_with_the_paths_own_sum() =
        runTest {
            val options = noPhysics.copy(elevationGain = ElevationGainOptions.RAW)
            val src = rippledPath()
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(src), options)
            // RAW reads `sourceElevation`, i.e. the profile before the 150 m kernel, while
            // `elevationGain` is the sum over the delivered one — so this pins the control against
            // the *input*, which the densifier only interpolates linearly.
            assertEquals(src.elevationGain, out.elevationGainFiltered, 0.5)
        }

    @Test
    fun a_larger_preset_threshold_reports_less() =
        runTest {
            val src = rippledPath()
            var previous = Double.MAX_VALUE
            for (preset in listOf(ElevationGainPreset.RAW, ElevationGainPreset.BAROMETRIC, ElevationGainPreset.GPS)) {
                val options = noPhysics.copy(elevationGain = ElevationGainOptions.of(preset))
                val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(src), options)
                assertTrue(
                    out.elevationGainFiltered <= previous + 1e-9,
                    "${preset.id} reported ${out.elevationGainFiltered}, more than the previous $previous",
                )
                previous = out.elevationGainFiltered
            }
        }
}
