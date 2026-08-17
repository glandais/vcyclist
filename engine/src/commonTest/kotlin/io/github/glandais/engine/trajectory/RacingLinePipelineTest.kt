package io.github.glandais.engine.trajectory

import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RacingLinePipelineTest {
    private fun corneredPath(): Path {
        val inbound = CurvatureFixtures.straight(150.0, 5.0)
        val ex = inbound.first.last()
        val ey = inbound.second.last()
        val arc = CurvatureFixtures.arc(30.0, PI / 2, 5.0, startX = ex, startY = ey)
        val (bx, by) = CurvatureFixtures.arcEnd(30.0, PI / 2, ex, ey, 0.0)
        val outbound = CurvatureFixtures.straight(150.0, 5.0, headingRad = PI / 2, startX = bx, startY = by)
        val (xs, ys) = CurvatureFixtures.join(inbound, arc, outbound)
        return CurvatureFixtures.pathOf(xs, ys)
    }

    private val baseOptions =
        EnhanceOptions.DEFAULT.copy(
            fixElevation = false,
            virtualizeTrack = false,
            computeOnePointPerSecond = false,
        )

    /**
     * **The guard that makes the whole phase safe to have landed.**
     *
     * Everything in this branch is opt-in, and this is the assertion that says so. If the racing
     * line ever leaked into a default run it would silently rewrite every coordinate of every
     * caller's output.
     */
    @Test
    fun `disabled leaves the pipeline output identical`() =
        runTest {
            val path = corneredPath()
            val without = Enhancer.enhanceCourseDefault(path, null, baseOptions)
            val explicitlyOff =
                Enhancer.enhanceCourseDefault(
                    path,
                    null,
                    baseOptions.copy(racingLine = RacingLineOptions(enabled = false)),
                )
            assertEquals(without.size, explicitlyOff.size)
            for (i in 0 until without.size) {
                assertTrue(without.latitude(i) == explicitlyOff.latitude(i), "latitude moved at $i")
                assertTrue(without.longitude(i) == explicitlyOff.longitude(i), "longitude moved at $i")
                assertTrue(without.speedMax(i) == explicitlyOff.speedMax(i), "speedMax changed at $i")
                assertTrue(without.lateralOffset(i).isNaN(), "offset written while disabled at $i")
                assertTrue(without.sourceLatitude(i).isNaN(), "source latitude written while disabled at $i")
            }
        }

    @Test
    fun `enabled moves coordinates and records the offset`() =
        runTest {
            val path = corneredPath()
            val options =
                baseOptions.copy(
                    racingLine = RacingLineOptions(enabled = true, corridor = CorridorMode.FULL_ROAD),
                )
            val result = Enhancer.enhanceCourseDefault(path, null, options)

            var wrote = 0
            var maxOffset = 0.0
            for (i in 0 until result.size) {
                val n = result.lateralOffset(i)
                if (!n.isNaN()) {
                    wrote++
                    if (abs(n) > maxOffset) maxOffset = abs(n)
                }
            }
            assertTrue(wrote == result.size, "offset written at only $wrote of ${result.size} stations")
            assertTrue(maxOffset > 0.5, "the line barely moved: max offset $maxOffset m")
        }

    /**
     * The line is 2.5 m wide at most, and the pipeline simplifies at 10 m by default. Without the
     * cap the stage would compute a trajectory, write it out, and have Douglas-Peucker throw it
     * away — an expensive no-op that would look like the feature not working.
     */
    @Test
    fun `the simplifier does not erase the line`() =
        runTest {
            val path = corneredPath()
            val options =
                baseOptions.copy(
                    simplifyPath = SimplifyPathOptions(enabled = true, toleranceM = 10.0),
                    racingLine = RacingLineOptions(enabled = true, corridor = CorridorMode.FULL_ROAD),
                )
            val result = Enhancer.enhanceCourseDefault(path, null, options)
            var maxOffset = 0.0
            for (i in 0 until result.size) {
                val n = result.lateralOffset(i)
                if (!n.isNaN() && abs(n) > maxOffset) maxOffset = abs(n)
            }
            assertTrue(maxOffset > 0.5, "simplification erased the racing line (max offset $maxOffset m)")
        }

    @Test
    fun `the original coordinates survive the whole pipeline`() =
        runTest {
            val path = corneredPath()
            val options =
                baseOptions.copy(
                    simplifyPath = SimplifyPathOptions(enabled = false),
                    racingLine = RacingLineOptions(enabled = true, corridor = CorridorMode.FULL_ROAD),
                )
            val result = Enhancer.enhanceCourseDefault(path, null, options)
            for (i in 0 until result.size) {
                assertTrue(!result.sourceLatitude(i).isNaN(), "source latitude lost at $i")
                assertTrue(!result.sourceLongitude(i).isNaN(), "source longitude lost at $i")
            }
        }

    @Test
    fun `the racing line supersedes the plain curvature pass`() =
        runTest {
            val path = corneredPath()
            val options =
                baseOptions.copy(
                    simplifyPath = SimplifyPathOptions(enabled = false),
                    racingLine = RacingLineOptions(enabled = true, corridor = CorridorMode.FULL_ROAD),
                )
            val result = Enhancer.enhanceCourseDefault(path, null, options)
            // `trajectoryCurvature` must describe the ridden line, so `radius` follows it.
            for (i in 0 until result.size - 1) {
                assertTrue(!result.trajectoryCurvature(i).isNaN(), "curvature not written at $i")
                assertTrue(result.radius(i) > 0.0, "radius not written at $i")
            }
        }

    @Test
    fun `enabling the line does not change the point count`() =
        runTest {
            val path = corneredPath()
            val options = baseOptions.copy(simplifyPath = SimplifyPathOptions(enabled = false))
            val off = Enhancer.enhanceCourseDefault(path, null, options)
            val on =
                Enhancer.enhanceCourseDefault(
                    path,
                    null,
                    options.copy(racingLine = RacingLineOptions(enabled = true)),
                )
            assertEquals(off.size, on.size, "the stage must not resize the path")
        }
}
