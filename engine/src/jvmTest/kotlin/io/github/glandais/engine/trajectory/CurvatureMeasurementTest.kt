package io.github.glandais.engine.trajectory

import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test

/**
 * Measures the estimator against the shipped fixtures, old versus new, and prints a table.
 *
 * Not an assertion — a measurement, run the way ledger entry R11 was measured. The numbers it
 * prints are what the ledger entry quotes; re-run it after any change to the estimator and update
 * the entry rather than trusting a prediction. The design this work came from predicted an
 * aggregate `durationMs` movement of 0.5–4 %, which a prior measurement showed to be an order of
 * magnitude optimistic on these same files — hence measuring rather than asserting.
 *
 * Gated on `MEASURE=1`, like every slow test in this repository: it runs the full pipeline ten
 * times, twice over a 128 km fixture.
 *
 *     MEASURE=1 ./gradlew :engine:jvmTest --tests '*CurvatureMeasurementTest*' --rerun-tasks -i
 */
class CurvatureMeasurementTest {
    // The test JVM's working directory is the module dir, not the repo root, and neither is
    // guaranteed; walk up until the fixture directory appears.
    private val gpxDir: File? =
        generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, "demo/public/gpx") }
            .firstOrNull { it.isDirectory }

    private val fixtures =
        listOf("stelvio", "strava", "sample", "garmin", "movescount")
            .mapNotNull { name -> gpxDir?.let { name to File(it, "$name.gpx") } }
            .filter { it.second.exists() }

    private class Stats(
        val label: String,
        val points: Int,
        val durationS: Double,
        val distanceKm: Double,
        val tightFraction: Double,
        val bindingFraction: Double,
        val minRadius: Double,
        val clampedAtMin: Int,
    )

    private fun statsOf(
        label: String,
        path: Path,
    ): Stats {
        var tight = 0.0
        var binding = 0.0
        var total = 0.0
        var minRadius = Double.MAX_VALUE
        var clamped = 0
        for (i in 0 until path.size) {
            val dt = path.dt(i)
            if (!dt.isFinite() || dt <= 0.0) continue
            total += dt
            val r = path.radius(i)
            if (r in 0.001..199.999) tight += dt
            if (path.speed(i) >= path.speedMax(i) - 0.05) binding += dt
            if (r > 0.0 && r < minRadius) minRadius = r
            if (r in 0.0..5.0001 && r > 0.0) clamped++
        }
        return Stats(
            label = label,
            points = path.size,
            durationS = path.durationMs / 1000.0,
            distanceKm = path.totalDistance / 1000.0,
            tightFraction = if (total > 0) tight / total else 0.0,
            bindingFraction = if (total > 0) binding / total else 0.0,
            minRadius = minRadius,
            clampedAtMin = clamped,
        )
    }

    private fun sortedRadii(path: Path): DoubleArray {
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

    private fun run(
        file: File,
        curvature: Boolean,
        raw: Boolean = false,
    ): Path {
        val parsed = GpxParser.parse(file.readText())
        val input = parsed.firstTrackAsPath()
        val options =
            EnhanceOptions.DEFAULT
                .copy(
                    fixElevation = false,
                    curvature = CurvatureOptions(enabled = curvature),
                ).let {
                    // For the per-point comparison, keep the two paths index-aligned by skipping the
                    // resample and the simplifier, which choose different points on different inputs.
                    if (raw) it.copy(computeOnePointPerSecond = false, simplifyPath = it.simplifyPath.copy(enabled = false)) else it
                }
        return runBlocking { Enhancer.enhanceCourseDefault(input, null, options) }
    }

    private val enabled: Boolean =
        System.getenv("MEASURE") == "1" || System.getProperty("measure") == "true"

    @Test
    fun measure() {
        if (!enabled) {
            println("[curvature] set MEASURE=1 to run the fixture measurement")
            return
        }
        if (fixtures.isEmpty()) {
            println("[curvature] no fixtures found under demo/public/gpx — skipped")
            return
        }
        println()
        println(
            "| fixture | dist km | duration old → new | Δ | radius<200m old → new | " +
                "binding old → new | min R old → new |",
        )
        println("|---|---|---|---|---|---|---|")
        for ((name, file) in fixtures) {
            val old = statsOf("$name/old", run(file, curvature = false))
            val new = statsOf("$name/new", run(file, curvature = true))
            val delta = (new.durationS - old.durationS) / old.durationS * 100.0
            println(
                "| `$name` | ${fmt(old.distanceKm, 1)} | " +
                    "${fmt(old.durationS, 0)} s → ${fmt(new.durationS, 0)} s | " +
                    "${if (delta >= 0) "+" else ""}${fmt(delta, 2)} % | " +
                    "${pct(old.tightFraction)} → ${pct(new.tightFraction)} | " +
                    "${pct(old.bindingFraction)} → ${pct(new.bindingFraction)} | " +
                    "${fmt(old.minRadius, 1)} m → ${fmt(new.minRadius, 1)} m |",
            )
        }
        println()
        // Per-point radius disagreement, which is the thing the change is actually about.
        for ((name, file) in fixtures) {
            val old = run(file, curvature = false, raw = true)
            val new = run(file, curvature = true, raw = true)
            if (old.size != new.size) {
                println("[$name] size differs (${old.size} vs ${new.size}) — skipping point compare")
                continue
            }
            run {
                val pcts = listOf(1, 5, 25, 50)
                val ro = sortedRadii(old)
                val rn = sortedRadii(new)
                val po = pcts.joinToString(" ") { "p$it=${fmt(ro[(ro.size * it) / 100], 1)}" }
                val pn = pcts.joinToString(" ") { "p$it=${fmt(rn[(rn.size * it) / 100], 1)}" }
                println("[$name] radius percentiles old: $po")
                println("[$name] radius percentiles new: $pn")
                println(
                    "[$name] at the 5 m clamp: old ${ro.count { it <= 5.001 }} / " +
                        "new ${rn.count { it <= 5.001 }} of ${ro.size} points",
                )
            }
            var worseThanHalf = 0
            var worseThanDouble = 0
            var counted = 0
            for (i in 0 until old.size - 1) {
                val a = old.radius(i)
                val b = new.radius(i)
                if (a <= 0.0 || b <= 0.0) continue
                counted++
                if (b < a / 2.0) worseThanHalf++
                if (b > a * 2.0) worseThanDouble++
            }
            println(
                "[$name] of $counted points: ${pct(worseThanHalf.toDouble() / counted)} " +
                    "now report a radius under half the old one (old was optimistic), " +
                    "${pct(worseThanDouble.toDouble() / counted)} more than double it",
            )
        }
        println()
    }

    private fun fmt(
        v: Double,
        digits: Int,
    ): String {
        if (!v.isFinite()) return "n/a"
        var scale = 1.0
        repeat(digits) { scale *= 10.0 }
        val r = kotlin.math.round(v * scale) / scale
        return if (digits == 0) r.toLong().toString() else r.toString()
    }

    private fun pct(f: Double): String = "${fmt(abs(f) * 100.0, 2)} %"
}
