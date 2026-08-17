package io.github.glandais.cli.command

import io.github.glandais.cli.ExitCodes
import io.github.glandais.cli.diskCachedElevationProvider
import io.github.glandais.cli.mixin.BikeMixin
import io.github.glandais.cli.mixin.CyclistMixin
import io.github.glandais.cli.mixin.FilesMixin
import io.github.glandais.cli.mixin.WindMixin
import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxPowerSource
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.pathsToGpxDocument
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.PowerProviderFromData
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.trajectory.CorridorMode
import io.github.glandais.engine.trajectory.CurvatureOptions
import io.github.glandais.engine.trajectory.RacingLineOptions
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
        names = ["--gpx-power-source"],
        description = [
            "Which power the written GPX carries: input (what the source file said, the",
            "default), computed (what the simulation produced), or computed-or-input.",
            "Unrelated to --gpx-power, which feeds the recorded power *into* the simulation.",
        ],
    )
    var gpxPowerSource: String = "input"

    @field:CommandLine.Option(
        names = ["--no-extensions"],
        description = [
            "Write a bare GPX: no <extensions>, so no power, heart rate, cadence or",
            "temperature. <ele> and <time> are standard GPX and are kept.",
        ],
    )
    var noExtensions: Boolean = false

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
        description = [
            "Correct elevations from DEM tiles, kept on disk under --cache. Downloads what",
            "the cache does not hold yet. (default: false)",
        ],
    )
    var fixElevation: Boolean? = null

    /**
     * Builds the provider used when `--fix-elevation` is on. A settable seam so the offline test
     * can substitute a canned-tile provider — the wiring below it is exactly what task g34 found
     * missing (the option parsed, then `null` reached the Enhancer and the step was skipped), so
     * it must stay testable without the network.
     */
    internal var elevationProviderFactory: () -> ElevationProvider = { diskCachedElevationProvider(files.cache) }

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
        names = ["--curvature"],
        negatable = true,
        description = [
            "Estimate turn radius by heading regression in a local planar frame instead of the",
            "older windowed bearing difference. Corrects tight bends, which the old estimate",
            "reported as up to twice too open. (default: true)",
        ],
    )
    var curvature: Boolean? = null

    @field:CommandLine.Option(
        names = ["--racing-line"],
        negatable = true,
        description = [
            "Ride the optimal trajectory through corners instead of the recorded line.",
            "REWRITES EVERY COORDINATE of the output; the originals are kept in the",
            "sourceLatitude/sourceLongitude fields. (default: \${DEFAULT-VALUE})",
        ],
    )
    var racingLine: Boolean? = null

    @field:CommandLine.Option(
        names = ["--corridor"],
        description = [
            "Which part of the road the racing line may use: lane, lane-left, full-road.",
            "'lane' keeps to your own side and never crosses the centreline.",
            "'full-road' uses the whole carriageway - CLOSED ROADS AND TIME TRIALS ONLY,",
            "it is illegal on any open road. (default: \${DEFAULT-VALUE})",
        ],
    )
    var corridor: String = RacingLineOptions.DEFAULT.corridor.id

    @field:CommandLine.Option(
        names = ["--road-width"],
        description = [
            "Road width in metres, assumed where the GPX carries none. The racing line's",
            "gain is proportional to this, so a wrong value is proportionally wrong.",
            "(default: \${DEFAULT-VALUE})",
        ],
    )
    var roadWidthM: Double = RacingLineOptions.DEFAULT.defaultRoadWidthM

    @field:CommandLine.Option(
        names = ["--one-point-per-second"],
        negatable = true,
        description = ["Resample to 1 Hz before simplifying (default: true)"],
    )
    var onePointPerSecond: Boolean? = null

    @field:CommandLine.Option(
        names = ["--gpx-power"],
        description = [
            "Replay the power recorded in the GPX instead of the constant --cyclist-power.",
            "gpxtools-cli spells this the same way.",
        ],
    )
    var useGpxPower: Boolean = false

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
        val power =
            parsePowerSource(gpxPowerSource) ?: run {
                spec.commandLine().err.println(
                    "--gpx-power-source is not one of input, computed, computed-or-input: $gpxPowerSource",
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
        // One provider for the whole batch, so its tile cache is shared across files — and none
        // at all otherwise: it is an HTTP client a run without --fix-elevation has no use for.
        val elevationProvider = if (options.fixElevation) elevationProviderFactory() else null

        val failures = mutableListOf<String>()
        for (input in inputs) {
            // One bad file must not abort the batch — the others are still worth processing, and
            // the caller learns about every failure at the end rather than the first one only.
            runCatching { processOne(input, inputs.size, options, start, power, elevationProvider) }
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
            curvature = CurvatureOptions(enabled = curvature ?: CurvatureOptions.DEFAULT.enabled),
            racingLine =
                RacingLineOptions(
                    enabled = racingLine ?: RacingLineOptions.DEFAULT.enabled,
                    corridor = CorridorMode.byId(corridor),
                    defaultRoadWidthM = roadWidthM,
                ),
        )

    /**
     * Stamp `--road-width` onto every point when the user actually typed it.
     *
     * Without this the flag is a no-op on any file a modern router produced. `GpxToPath` infers a
     * width from the OSM `highway` class, and file-derived data outranks an option default — so on
     * a route tagged `highway=secondary` the inferred 6.0 m would silently beat `--road-width 3`,
     * and the user would see no change at all.
     *
     * The precedence a typed flag deserves is above both inference *and* the file's own width: a
     * class-typical figure is a guess, a file's `<roadwidth>` is somebody else's measurement, and
     * an explicit argument is this user telling us about this road. Checked against
     * `hasMatchedOption` rather than against the default value, so passing the default explicitly
     * still counts as being told.
     */
    private fun applyRoadWidthOverride(path: Path) {
        val matched = spec.commandLine().parseResult?.hasMatchedOption("--road-width") ?: false
        if (!matched) return
        for (i in 0 until path.size) path.setRoadWidth(i, roadWidthM)
    }

    private fun processOne(
        input: File,
        inputCount: Int,
        options: EnhanceOptions,
        start: Instant?,
        powerSource: GpxPowerSource,
        elevationProvider: ElevationProvider?,
    ) {
        val out = spec.commandLine().out
        if (!quiet) out.println("Reading ${input.path}")

        val document = GpxParser.parse(input.readText())
        val paths = document.tracksAsPaths()
        paths.forEach(::applyRoadWidthOverride)
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

        val enhanced = runBlocking { paths.map { enhanceOne(it, options, elevationProvider) } }

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
                    pathsToGpxDocument(
                        enhanced,
                        name = input.nameWithoutExtension,
                        trackNames =
                            enhanced.indices.map { if (enhanced.size == 1) "virtualized" else "virtualized-${it + 1}" },
                        waypoints = document.waypoints,
                        startTime = start,
                        powerSource = powerSource,
                    ),
                    writeExtensions = !noExtensions,
                ),
            )
            if (!quiet) out.println("  wrote ${file.path}")
        }
        csvOut?.let { target ->
            trackOutputFiles(naming.resolve(target, "csv"), enhanced.size)
                .forEachIndexed { i, file -> writeText(file, CsvWriter.write(enhanced[i]), out) }
        }
        jsonOut?.let { target ->
            trackOutputFiles(naming.resolve(target, "json"), enhanced.size)
                .forEachIndexed { i, file -> writeText(file, JsonWriter.write(enhanced[i]), out) }
        }
        fitOut?.let { target ->
            val file = naming.resolve(target, "fit")
            file.absoluteFile.parentFile?.mkdirs()
            // Every track goes into the one FIT file, as a lap each (task g25).
            file.writeBytes(enhanced.toFitBytes(input.nameWithoutExtension, start!!))
            if (!quiet) out.println("  wrote ${file.path}")
        }
    }

    /**
     * `--gpx-power-source` → [GpxPowerSource], `null` when the value is not one of the three.
     *
     * Validated in [call] alongside `--start-time`, before any file is read: a bad value is a
     * usage error (exit 64), not a runtime one, and it must not be discovered after half the
     * batch has been written.
     *
     * The option name deliberately differs from the pre-existing `--gpx-power`, which concerns
     * the simulation's *input* (replay the recorded power instead of the rider model). picocli
     * refuses the collision outright, which is how the clash was found.
     */
    private fun parsePowerSource(value: String): GpxPowerSource? =
        when (value.lowercase()) {
            "input" -> GpxPowerSource.INPUT
            "computed" -> GpxPowerSource.COMPUTED
            "computed-or-input" -> GpxPowerSource.COMPUTED_OR_INPUT
            else -> null
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
        elevationProvider: ElevationProvider?,
    ): Path {
        val physics =
            CoursePhysics(
                course = Course(path = path, cyclist = cyclist.toCyclist(), bike = bike.toBike()),
                rhoProvider = RhoProviderEstimate,
                aeroProvider = AeroProviderConstant,
                windProvider = wind.toWindProvider(),
                // --gpx-power replays what the file recorded; otherwise the rider model applies.
                cyclistPowerProvider = if (useGpxPower) PowerProviderFromData else cyclist.toPowerProvider(),
            )
        return Enhancer.enhanceCourse(physics, options, elevationProvider)
    }
}

/**
 * One output file per track (task g28).
 *
 * CSV and JSON describe a single [io.github.glandais.engine.path.Path], unlike GPX and FIT which
 * hold several. Until g28 the CLI wrote `paths.first()` and dropped the rest **silently**: the
 * point count printed a line earlier covered every track, the file covered one.
 *
 * A single track keeps [target] exactly as the user spelled it — that is the dominant case and its
 * file name must not change. Several tracks produce `name-1.csv`, `name-2.csv`, …, which is the
 * shape gpx2web's `CSVFileWriter` produces (one file per `GPXPath`), and each file stays readable
 * on its own in a spreadsheet. Concatenating instead would have kept the defect, merely disguised.
 */
internal fun trackOutputFiles(
    target: File,
    trackCount: Int,
): List<File> =
    if (trackCount <= 1) {
        listOf(target)
    } else {
        val base = target.nameWithoutExtension
        val extension = target.extension
        (1..trackCount).map { index ->
            val name = if (extension.isEmpty()) "$base-$index" else "$base-$index.$extension"
            File(target.absoluteFile.parentFile, name)
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
