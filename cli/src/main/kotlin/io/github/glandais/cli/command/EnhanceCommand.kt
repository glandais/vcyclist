package io.github.glandais.cli.command

import io.github.glandais.cli.ExitCodes
import io.github.glandais.cli.mixin.BikeMixin
import io.github.glandais.cli.mixin.CyclistMixin
import io.github.glandais.cli.mixin.FilesMixin
import io.github.glandais.cli.mixin.WindMixin
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.fit.toFitBytes
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable
import kotlin.time.Instant

/**
 * Runs the enhancement pipeline over one or more GPX files.
 *
 * ## Why this is one command, not gpx2web's two
 *
 * gpxtools-cli splits the work between `process` and `virtualize`, whose options overlap almost
 * entirely. vcyclist has a single `Enhancer` pipeline, and reproducing the split would mean
 * inventing a distinction the code does not make. So there is one `enhance` command, with the
 * pipeline stages exposed as flags. `cli/README.md` maps the old commands onto it.
 *
 * Every pipeline flag corresponds to a field of [EnhanceOptions] — no option here exists that
 * the engine cannot express.
 */
@CommandLine.Command(
    name = "enhance",
    description = ["Run the physics pipeline over GPX files and write the results."],
    mixinStandardHelpOptions = true,
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = [
        "0:success",
        "${ExitCodes.USAGE}:bad arguments",
        "${ExitCodes.NO_INPUT}:no readable input file",
        "${ExitCodes.RUNTIME}:one or more files failed to process",
    ],
)
class EnhanceCommand : Callable<Int> {
    @field:CommandLine.Mixin
    var files: FilesMixin = FilesMixin()

    @field:CommandLine.Mixin
    var cyclist: CyclistMixin = CyclistMixin()

    @field:CommandLine.Mixin
    var bike: BikeMixin = BikeMixin()

    @field:CommandLine.Mixin
    var wind: WindMixin = WindMixin()

    @field:CommandLine.Option(
        names = ["--gpx"],
        description = ["Write the enhanced GPX. With several inputs this is a folder."],
    )
    var gpxOut: File? = null

    @field:CommandLine.Option(names = ["--csv"], description = ["Write a CSV export."])
    var csvOut: File? = null

    @field:CommandLine.Option(names = ["--json"], description = ["Write a JSON export."])
    var jsonOut: File? = null

    @field:CommandLine.Option(
        names = ["--fit"],
        description = ["Write a Garmin FIT course. Requires --start-time: FIT has no relative clock."],
    )
    var fitOut: File? = null

    @field:CommandLine.Option(
        names = ["--start-time"],
        description = ["Absolute instant of the first point, ISO-8601 (e.g. 2026-08-01T08:00:00Z)."],
    )
    var startTime: String? = null

    // ---- Negatable pipeline flags --------------------------------------------
    //
    // These are `Boolean?`, not `Boolean`, and that is load-bearing. picocli's `negatable`
    // options applied to a NON-null boolean **toggle the field's initial value**: with an
    // initial `true`, `--no-virtualize` sets it to true and `--virtualize` sets it to false —
    // the exact opposite of what anyone would expect, and silent. With a nullable field the
    // behaviour is the intuitive one: absent leaves null, `--x` gives true, `--no-x` gives
    // false. The real default is then applied here, in [pipelineOptions].
    //
    // Caught by a test that ran the flag end to end rather than by reading the annotation.

    @field:CommandLine.Option(
        names = ["--fix-elevation"],
        negatable = true,
        description = ["Correct elevations from DEM tiles. Requires network access. (default: false)"],
    )
    var fixElevation: Boolean? = null

    @field:CommandLine.Option(
        names = ["--virtualize"],
        negatable = true,
        description = ["Simulate the ride physics (default: true)"],
    )
    var virtualize: Boolean? = null

    @field:CommandLine.Option(
        names = ["--simplify"],
        negatable = true,
        description = ["Simplify the output with Douglas-Peucker (default: true)"],
    )
    var simplify: Boolean? = null

    @field:CommandLine.Option(
        names = ["--simplify-tolerance"],
        description = ["Douglas-Peucker tolerance in metres (default: \${DEFAULT-VALUE})"],
    )
    var simplifyToleranceM: Double = SimplifyPathOptions().toleranceM

    @field:CommandLine.Option(
        names = ["--one-point-per-second"],
        negatable = true,
        description = ["Resample to 1 Hz before simplifying (default: true)"],
    )
    var onePointPerSecond: Boolean? = null

    @field:CommandLine.Option(names = ["-q", "--quiet"], description = ["Print nothing on success."])
    var quiet: Boolean = false

    /**
     * `--xlsx` exists only to explain itself. XLSX is explicitly not ported (see
     * `PLAN-GPX2WEB.md`), and telling a gpx2web user "unknown option" would leave them guessing
     * whether they mistyped it.
     */
    @field:CommandLine.Option(names = ["--xlsx"], hidden = true, description = ["Not supported; use --csv."])
    var xlsx: File? = null

    @field:CommandLine.Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (xlsx != null) {
            spec.commandLine().err.println(
                "XLSX export is not supported by vcyclist. Use --csv instead — see cli/README.md.",
            )
            return ExitCodes.USAGE
        }
        if (fitOut != null && startTime == null) {
            spec.commandLine().err.println(
                "--fit requires --start-time: the FIT format has no relative clock, so the absolute " +
                    "instant of the first point must be given explicitly.",
            )
            return ExitCodes.USAGE
        }
        val start =
            startTime?.let {
                runCatching { Instant.parse(it) }.getOrElse { _ ->
                    spec.commandLine().err.println("--start-time is not a valid ISO-8601 instant: $it")
                    return ExitCodes.USAGE
                }
            }

        val skipped = mutableListOf<String>()
        val inputs = files.collectGpxFiles { file, why -> skipped.add("${file.path} $why") }
        if (inputs.isEmpty()) {
            spec.commandLine().err.println("No GPX file to process.")
            skipped.forEach { spec.commandLine().err.println("  skipped: $it") }
            return ExitCodes.NO_INPUT
        }

        val options = pipelineOptions()

        val failures = mutableListOf<String>()
        for (input in inputs) {
            // One bad file must not abort the batch — the others are still worth processing, and
            // the caller learns about every failure at the end rather than the first one only.
            runCatching { processOne(input, inputs.size, options, start) }
                .onFailure { failures.add("${input.path}: ${it.message ?: it::class.simpleName}") }
        }

        if (failures.isNotEmpty()) {
            spec.commandLine().err.println("${failures.size} of ${inputs.size} file(s) failed:")
            failures.forEach { spec.commandLine().err.println("  $it") }
            return ExitCodes.RUNTIME
        }
        return 0
    }

    /** Resolve the nullable flags against their real defaults — see the note on the fields. */
    internal fun pipelineOptions(): EnhanceOptions =
        EnhanceOptions(
            // Off by default: correcting elevations downloads DEM tiles, and a CLI should not
            // reach the network unless asked.
            fixElevation = fixElevation ?: false,
            computeMaxSpeeds = true,
            virtualizeTrack = virtualize ?: true,
            computeOnePointPerSecond = onePointPerSecond ?: true,
            simplifyPath = SimplifyPathOptions(enabled = simplify ?: true, toleranceM = simplifyToleranceM),
        )

    private fun processOne(
        input: File,
        inputCount: Int,
        options: EnhanceOptions,
        start: Instant?,
    ) {
        val out = spec.commandLine().out
        if (!quiet) out.println("Reading ${input.path}")

        val document = GpxParser.parse(input.readText())
        val paths = document.tracksAsPaths()
        require(paths.isNotEmpty()) { "no track with any point" }

        if (!quiet) {
            out.println(
                "  -> ${paths.size} track(s), ${paths.sumOf { it.size }} points, " +
                    "%.1f m, gain %.1f m".format(
                        paths.sumOf { it.totalDistance },
                        paths.sumOf { it.elevationGain },
                    ),
            )
        }

        val enhanced = runBlocking { paths.map { enhanceOne(it, options) } }

        if (!quiet) {
            out.println(
                "  -> ${enhanced.sumOf { it.size }} points, %.1f m, duration %.1f s".format(
                    enhanced.sumOf { it.totalDistance },
                    enhanced.sumOf { it.durationMs } / 1000.0,
                ),
            )
        }

        val naming = OutputNaming(input, inputCount)
        gpxOut?.let { target ->
            val file = naming.resolve(target, "gpx")
            file.absoluteFile.parentFile?.mkdirs()
            file.writeText(
                GpxWriter.write(
                    enhanced,
                    name = input.nameWithoutExtension,
                    trackNames = enhanced.indices.map { if (enhanced.size == 1) "virtualized" else "virtualized-${it + 1}" },
                    waypoints = document.waypoints,
                    startTime = start,
                ),
            )
            if (!quiet) out.println("  wrote ${file.path}")
        }
        csvOut?.let { target -> writeText(naming.resolve(target, "csv"), CsvWriter.write(enhanced.first()), out) }
        jsonOut?.let { target -> writeText(naming.resolve(target, "json"), JsonWriter.write(enhanced.first()), out) }
        fitOut?.let { target ->
            val file = naming.resolve(target, "fit")
            file.absoluteFile.parentFile?.mkdirs()
            file.writeBytes(enhanced.first().toFitBytes(input.nameWithoutExtension, start!!))
            if (!quiet) out.println("  wrote ${file.path}")
        }
    }

    private fun writeText(
        file: File,
        content: String,
        out: java.io.PrintWriter,
    ) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(content)
        if (!quiet) out.println("  wrote ${file.path}")
    }

    private suspend fun enhanceOne(
        path: Path,
        options: EnhanceOptions,
    ): Path {
        val physics =
            CoursePhysics(
                course = Course(path = path, cyclist = cyclist.toCyclist(), bike = bike.toBike()),
                rhoProvider = RhoProviderEstimate,
                aeroProvider = AeroProviderConstant,
                windProvider = wind.toWindProvider(),
                cyclistPowerProvider = cyclist.toPowerProvider(),
            )
        return Enhancer.enhanceCourse(physics, options, elevationProvider = null)
    }
}

/**
 * Decides where a given output goes.
 *
 * With one input the target is taken literally, so `--csv out.csv` writes `out.csv`. With
 * several, a single literal path would have each file overwrite the last, so the target is
 * treated as a **folder** and outputs are named after their input. gpx2web's `FilesMixin` always
 * works in folder mode; this keeps the single-file case ergonomic without losing batch support.
 */
internal class OutputNaming(
    private val input: File,
    private val inputCount: Int,
) {
    fun resolve(
        target: File,
        extension: String,
    ): File =
        if (inputCount == 1) {
            target
        } else {
            File(target, "${input.nameWithoutExtension}.$extension")
        }
}
