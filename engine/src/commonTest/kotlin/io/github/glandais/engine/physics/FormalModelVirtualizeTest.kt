package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Counterexample found by the TLA+ / Lean models of `VirtualizeService.virtualizeTrack`.
 *
 * KDoc contract : "the simulation runs from `i = 1` to `i = n - 1` (inclusive) ; the last point
 * is simulated like all the others". The `MAX_ITERATIONS = 100_000` cap breaks the loop after
 * 100 002 simulated segments, leaving every later slot at `Path(n)`'s zero initialisation :
 * `time = 0`, `latitude = longitude = 0`. At the pipeline's 1–2 m spacing that is any ride longer
 * than about 200 km.
 */
class FormalModelVirtualizeTest {
    private fun flatPath(
        n: Int,
        spacingM: Double,
    ): Path {
        val p = Path(n)
        val dLonRad = spacingM / 6_371_000.0
        for (i in 0 until n) {
            p.setLatitude(i, 0.0)
            p.setLongitude(i, i * dLonRad)
            p.setElevation(i, 100.0)
            p.setDistance(i, i * spacingM)
            p.setGrade(i, 0.0)
            p.setSpeedMax(i, 100.0)
        }
        return p
    }

    @Test
    fun every_point_is_simulated_on_a_path_longer_than_the_iteration_cap() {
        val n = 100_010
        val input = flatPath(n, 2.0)
        val out = VirtualizeService.virtualizeTrack(CoursePhysics(Course(input)))

        var firstBad = -1
        for (i in 1 until n) {
            if (!(out.time(i) > out.time(i - 1))) {
                firstBad = i
                break
            }
        }
        assertTrue(
            firstBad == -1,
            "time must be strictly monotone over all $n points, but time($firstBad)=" +
                "${if (firstBad >= 0) out.time(firstBad) else 0.0} <= time(${firstBad - 1})=" +
                "${if (firstBad >= 1) out.time(firstBad - 1) else 0.0} : the point was never simulated",
        )
        val last = n - 1
        assertTrue(
            abs(out.longitude(last) - input.longitude(last)) < 1e-12,
            "last point longitude must be copied from the input (${input.longitude(last)}), " +
                "got ${out.longitude(last)}",
        )
    }

    @Test
    fun enhancer_virtualizes_a_210_km_ride_end_to_end() =
        runTest(timeout = 10.minutes) {
            // Two fixes ~211 km apart on the equator : PointPerDistance(1, 2) densifies to > 100k
            // points before VirtualizeService runs.
            val src = Path(2)
            src.setLatitude(0, 0.0)
            src.setLongitude(0, 0.0)
            src.setLatitude(1, 0.0)
            src.setLongitude(1, 1.9 * PI / 180.0)
            src.setElevation(0, 100.0)
            src.setElevation(1, 100.0)
            src.computeDerivedData()

            val options =
                EnhanceOptions(
                    fixElevation = false,
                    computeOnePointPerSecond = false,
                    simplifyPath = SimplifyPathOptions(enabled = false),
                )
            val out = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(src), options)
            assertTrue(out.size > 100_003, "precondition : densified size ${out.size}")

            var firstBad = -1
            for (i in 1 until out.size) {
                if (!(out.time(i) > out.time(i - 1))) {
                    firstBad = i
                    break
                }
            }
            assertTrue(
                firstBad == -1,
                "virtualized time must be strictly monotone over ${out.size} points, but drops at " +
                    "index $firstBad (time=${if (firstBad >= 0) out.time(firstBad) else 0.0} ms)",
            )
        }
}
