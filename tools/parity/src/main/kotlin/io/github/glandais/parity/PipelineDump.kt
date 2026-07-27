package io.github.glandais.parity

import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PathSimplifier
import io.github.glandais.engine.path.PointPerDistance
import io.github.glandais.engine.path.PointPerSecond
import io.github.glandais.engine.physics.MaxSpeedComputer
import io.github.glandais.engine.physics.VirtualizeService
import java.io.File

/**
 * Kotlin side of the end-to-end parity cascade.
 *
 * Re-implements [Enhancer.enhanceCourse] step by step (same calls, same order) so the
 * [Path] can be dumped *between* stages — a divergence at stage N is only diagnosable if
 * stage N-1 is known to match.
 *
 * Keep in lockstep with :
 * - `engine/src/commonMain/.../Enhancer.kt` (the behaviour being mirrored)
 * - `tools/parity/ts/pipelineDump.ts` (the TS counterpart)
 *
 * `fixElevation` is intentionally not wired here : the deterministic cascade runs with no
 * network, and elevation parity is measured separately (see `tools/parity/README.md`).
 */
private class StageWriter(
    private val outDir: File,
) {
    private var index = 0
    var timeOrigin: Double = 0.0

    fun dump(
        stage: String,
        path: Path,
    ) {
        DumpFormat.writeStage(outDir, index, stage, path, timeOrigin)
        System.err.println("  [kt] %02d-%s: %d pts".format(index, stage, path.size))
        index++
    }
}

fun main(argv: Array<String>) {
    val args = argv.toList()

    fun arg(name: String): String? {
        val i = args.indexOf("--$name")
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    val gpxFile = arg("gpx") ?: error("usage: --gpx <file.gpx> --out <dir> [--simplify]")
    val outDir = arg("out") ?: error("usage: --gpx <file.gpx> --out <dir> [--simplify]")
    val doSimplify = args.contains("--simplify")

    val writer = StageWriter(File(outDir))

    // ---- parse ---------------------------------------------------------------
    var path = GpxParser.parse(File(gpxFile).readText()).firstTrackAsPath()

    // Normalise the time axis to t0 = 0, symmetrically with the TS runner. Harness
    // decision, not a pipeline one : it keeps the absolute epoch (~1.7e12 ms) out of the
    // comparison, where it would swamp millisecond-level differences at float64
    // resolution. The offset is recorded in every header so nothing is lost.
    if (path.size > 0) {
        writer.timeOrigin = path.time(0)
        for (i in 0 until path.size) {
            path.setTime(i, path.time(i) - writer.timeOrigin)
        }
        path.computeDerivedData()
    }
    writer.dump("parsed", path)

    // ---- pipeline, mirroring Enhancer.enhanceCourse ---------------------------
    val course = Enhancer.getDefaultCourse(path)

    path = PointPerDistance.compute(path, minDistanceM = -1.0, maxDistanceM = 30.0)
    writer.dump("ppd-30", path)

    path = PointPerDistance.compute(path, minDistanceM = 1.0, maxDistanceM = 2.0)
    writer.dump("ppd-2", path)

    path = ElevationStep.smoothElevation(path)
    writer.dump("smooth", path)

    var working = course.copy(course = course.course.copy(path = path))
    MaxSpeedComputer.computeMaxSpeeds(working.course) // mutates `path` in place
    writer.dump("maxspeed", path)

    path = VirtualizeService.virtualizeTrack(working)
    working = working.copy(course = working.course.copy(path = path))
    writer.dump("virtualize", path)

    path = PointPerSecond.computeOnePointPerSecond(path)
    writer.dump("pointpersecond", path)

    if (doSimplify) {
        path = PathSimplifier.simplify(path, 10.0, 3.0)
        writer.dump("simplify", path)
    }
}
