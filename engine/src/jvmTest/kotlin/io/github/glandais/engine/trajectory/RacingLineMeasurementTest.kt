package io.github.glandais.engine.trajectory

import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Measures the racing line against the shipped fixtures. Gated on `MEASURE=1`.
 *
 *     MEASURE=1 ./gradlew :engine:jvmTest --tests '*RacingLineMeasurementTest*' --rerun-tasks -i
 */
class RacingLineMeasurementTest {
    private val gpxDir: File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "demo/public/gpx") }
            .firstOrNull { it.isDirectory }

    private val fixtures =
        listOf("stelvio", "strava", "sample")
            .mapNotNull { name -> gpxDir?.let { name to File(it, "$name.gpx") } }
            .filter { it.second.exists() }

    private val enabled: Boolean =
        System.getenv("MEASURE") == "1" || System.getProperty("measure") == "true"

    private fun run(
        file: File,
        racing: Boolean,
        corridor: CorridorMode = CorridorMode.FULL_ROAD,
    ): Path {
        val input = GpxParser.parse(file.readText()).firstTrackAsPath()
        val options =
            EnhanceOptions.DEFAULT.copy(
                fixElevation = false,
                computeOnePointPerSecond = false,
                simplifyPath = SimplifyPathOptions(enabled = false),
                racingLine = RacingLineOptions(enabled = racing, corridor = corridor),
            )
        return runBlocking { Enhancer.enhanceCourseDefault(input, null, options) }
    }

    private fun radii(path: Path): DoubleArray {
        val v = DoubleArray(path.size)
        var c = 0
        for (i in 0 until path.size - 1) {
            val r = path.radius(i)
            if (r > 0.0 && r.isFinite()) v[c++] = r
        }
        val t = v.copyOf(c)
        t.sort()
        return t
    }

    @Test
    fun measure() {
        if (!enabled) {
            println("[racing-line] set MEASURE=1 to run the fixture measurement")
            return
        }
        for ((name, file) in fixtures) {
            val off = run(file, racing = false)
            val on = run(file, racing = true)
            val ro = radii(off)
            val rn = radii(on)

            fun pct(
                a: DoubleArray,
                p: Int,
            ) = a[(a.size * p) / 100]
            println(
                "[$name] duration ${off.durationMs / 1000} s -> ${on.durationMs / 1000} s " +
                    "(${"%.2f".format((on.durationMs - off.durationMs) / off.durationMs * 100)} %), " +
                    "distance ${"%.0f".format(off.totalDistance)} -> ${"%.0f".format(on.totalDistance)} m",
            )
            println(
                "   radius p1 ${"%.1f".format(pct(ro, 1))} -> ${"%.1f".format(pct(rn, 1))}, " +
                    "p10 ${"%.1f".format(pct(ro, 10))} -> ${"%.1f".format(pct(rn, 10))}, " +
                    "p50 ${"%.1f".format(pct(ro, 50))} -> ${"%.1f".format(pct(rn, 50))}",
            )
            // Where did the line make the corner *tighter* than the road?
            var worse = 0
            var better = 0
            for (i in 0 until minOf(off.size, on.size)) {
                val a = off.radius(i)
                val b = on.radius(i)
                if (a <= 0.0 || b <= 0.0) continue
                if (b < a * 0.9) worse++
                if (b > a * 1.1) better++
            }
            println("   stations tightened $worse, opened $better of ${minOf(off.size, on.size)}")
            var maxOffset = 0.0
            for (i in 0 until on.size) {
                val n = on.lateralOffset(i)
                if (!n.isNaN() && abs(n) > maxOffset) maxOffset = abs(n)
            }
            println("   max |offset| ${"%.2f".format(maxOffset)} m")

            // How fast is the line weaving, and how far has the linearisation drifted from truth?
            val input = GpxParser.parse(file.readText()).firstTrackAsPath()
            val prepared = run(file, racing = false)
            val frame = LocalFrame.project(prepared, 3.0)!!
            CurvatureEstimator.computeHeadings(frame)
            CurvatureEstimator.computeCurvature(frame, doubleArrayOf(3.0, 6.0, 12.0, 25.0), 0.05, 3.0)
            val report = RacingLine.analyze(prepared, RacingLineOptions(corridor = CorridorMode.FULL_ROAD))!!
            val slopes = DoubleArray(report.size)
            var c = 0
            for (i in 1 until report.size - 1) {
                val ds = frame.s[i + 1] - frame.s[i - 1]
                if (ds > 0) slopes[c++] = abs((report.lateralOffsetM[i + 1] - report.lateralOffsetM[i - 1]) / ds)
            }
            val sl = slopes.copyOf(c)
            sl.sort()
            println(
                "   |n'| p50 ${"%.3f".format(sl[c / 2])} p90 ${"%.3f".format(sl[(c * 9) / 10])} " +
                    "max ${"%.3f".format(sl[c - 1])}  (design assumes ~0.3 peak)",
            )
            println("   input size ${input.size}")
        }
    }
}
