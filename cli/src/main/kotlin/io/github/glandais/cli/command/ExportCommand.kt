package io.github.glandais.cli.command

import io.github.glandais.cli.ExitCodes
import io.github.glandais.cli.diskCachedElevationProvider
import io.github.glandais.cli.mixin.FilesMixin
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.fit.toFitBytes
import io.github.glandais.map.HttpTileFetcher
import io.github.glandais.map.SrtmMapProducer
import io.github.glandais.map.TileMapProducer
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable
import kotlin.time.Instant

/**
 * Derived outputs for a GPX file that is already as you want it: maps, FIT, CSV, JSON. No
 * physics — use `enhance` for that.
 *
 * ## `--tile-url` has no default, on purpose
 *
 * Following the decision recorded in task g14: every public tile server has a usage policy, and
 * shipping a default is what leads tools to hammer OpenStreetMap's servers without their users
 * realising. `--map` therefore requires `--tile-url`, and the error says why.
 */
@CommandLine.Command(
    name = "export",
    description = ["Produce maps and data exports from GPX files."],
    mixinStandardHelpOptions = true,
    exitCodeListHeading = "%nExit codes:%n",
    exitCodeList = [
        "0:success",
        "${ExitCodes.USAGE}:bad arguments",
        "${ExitCodes.NO_INPUT}:no readable input file",
        "${ExitCodes.RUNTIME}:one or more files failed to process",
    ],
)
class ExportCommand : Callable<Int> {
    @field:CommandLine.Mixin
    var files: FilesMixin = FilesMixin()

    @field:CommandLine.Option(
        names = ["--map"],
        description = ["Write a PNG of the track over map tiles. Requires --tile-url."],
    )
    var mapOut: File? = null

    @field:CommandLine.Option(
        names = ["--tile-url"],
        description = [
            "Tile URL pattern with {z}/{x}/{y} and optional {s}. No default: you choose the",
            "source and accept its usage policy. See map/README.md.",
        ],
    )
    var tileUrl: String? = null

    @field:CommandLine.Option(
        names = ["--elevation-map"],
        description = ["Write a PNG of the terrain coloured by altitude. Downloads DEM tiles."],
    )
    var elevationMapOut: File? = null

    @field:CommandLine.Option(
        names = ["--gpx"],
        description = ["Re-write the GPX, optionally re-stamped with --start-time."],
    )
    var gpxOut: File? = null

    @field:CommandLine.Option(
        names = ["--no-extensions"],
        description = [
            "Write a bare GPX: no <extensions>, so no power, heart rate, cadence or",
            "temperature. <ele> and <time> are standard GPX and are kept.",
        ],
    )
    var noExtensions: Boolean = false

    @field:CommandLine.Option(names = ["--csv"], description = ["Write a CSV export."])
    var csvOut: File? = null

    @field:CommandLine.Option(names = ["--json"], description = ["Write a JSON export."])
    var jsonOut: File? = null

    @field:CommandLine.Option(
        names = ["--fit"],
        description = ["Write a Garmin FIT course. Requires --start-time."],
    )
    var fitOut: File? = null

    @field:CommandLine.Option(
        names = ["--start-time"],
        description = ["Absolute instant of the first point, ISO-8601. Required by --fit."],
    )
    var startTime: String? = null

    @field:CommandLine.Option(names = ["--width"], description = ["Map width in pixels."])
    var width: Int? = null

    @field:CommandLine.Option(names = ["--height"], description = ["Map height in pixels."])
    var height: Int? = null

    @field:CommandLine.Option(
        names = ["--max-size"],
        description = ["Largest map dimension in pixels (default: \${DEFAULT-VALUE})"],
    )
    var maxSize: Int = 1024

    @field:CommandLine.Option(names = ["--zoom"], description = ["Explicit tile zoom level."])
    var zoom: Int? = null

    @field:CommandLine.Option(
        names = ["--margin"],
        description = ["Padding around the track, as a ratio (default: \${DEFAULT-VALUE})"],
    )
    var margin: Double = 0.1

    @field:CommandLine.Option(names = ["-q", "--quiet"], description = ["Print nothing on success."])
    var quiet: Boolean = false

    @field:CommandLine.Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (mapOut != null && tileUrl.isNullOrBlank()) {
            spec.commandLine().err.println(
                "--map requires --tile-url. vcyclist ships no default tile source on purpose: the " +
                    "choice of server carries that server's usage policy. See map/README.md.",
            )
            return ExitCodes.USAGE
        }
        if (fitOut != null && startTime == null) {
            spec.commandLine().err.println(
                "--fit requires --start-time: the FIT format has no relative clock.",
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
        if (listOfNotNull(mapOut, elevationMapOut, gpxOut, csvOut, jsonOut, fitOut).isEmpty()) {
            spec.commandLine().err.println(
                "Nothing to export. Pass at least one of --map, --elevation-map, --gpx, --csv, --json, --fit.",
            )
            return ExitCodes.USAGE
        }

        val skipped = mutableListOf<String>()
        val inputs = files.collectGpxFiles { file, why -> skipped.add("${file.path} $why") }
        if (inputs.isEmpty()) {
            spec.commandLine().err.println("No GPX file to process.")
            skipped.forEach { spec.commandLine().err.println("  skipped: $it") }
            return ExitCodes.NO_INPUT
        }

        val failures = mutableListOf<String>()
        for (input in inputs) {
            runCatching { exportOne(input, inputs.size, start) }
                .onFailure { failures.add("${input.path}: ${it.message ?: it::class.simpleName}") }
        }
        if (failures.isNotEmpty()) {
            spec.commandLine().err.println("${failures.size} of ${inputs.size} file(s) failed:")
            failures.forEach { spec.commandLine().err.println("  $it") }
            return ExitCodes.RUNTIME
        }
        return 0
    }

    private fun exportOne(
        input: File,
        inputCount: Int,
        start: Instant?,
    ) {
        val out = spec.commandLine().out
        if (!quiet) out.println("Reading ${input.path}")

        val document = GpxParser.parse(input.readText())
        val paths = document.tracksAsPaths()
        require(paths.isNotEmpty()) { "no track with any point" }
        val naming = OutputNaming(input, inputCount)

        gpxOut?.let { target ->
            write(
                naming.resolve(target, "gpx"),
                GpxWriter.write(
                    paths,
                    name = input.nameWithoutExtension,
                    waypoints = document.waypoints,
                    startTime = start,
                    writeExtensions = !noExtensions,
                ),
                out,
            )
        }

        mapOut?.let { target ->
            val file = naming.resolve(target, "png")
            file.absoluteFile.parentFile?.mkdirs()
            TileMapProducer(files.cache, HttpTileFetcher()).createTileMap(
                file = file,
                paths = paths,
                urlPattern = tileUrl!!,
                margin = margin,
                // Exactly one framing mode, in the same precedence the map module requires.
                maxSize = if (zoom == null && width == null) maxSize else null,
                width = if (zoom == null) width else null,
                height = if (zoom == null) height else null,
                zoom = zoom,
            )
            if (!quiet) out.println("  wrote ${file.path}")
        }

        elevationMapOut?.let { target ->
            val file = naming.resolve(target, "png")
            file.absoluteFile.parentFile?.mkdirs()
            // Disk-cached under --cache like the map tiles above — until g34 this provider
            // ignored --cache and re-downloaded every DEM tile on every run.
            val provider = diskCachedElevationProvider(files.cache)
            SrtmMapProducer(provider).createSrtmMap(file, paths, maxSize = maxSize, margin = margin)
            if (!quiet) out.println("  wrote ${file.path}")
        }

        // One file per track, like `enhance` — see `trackOutputFiles` (task g28).
        csvOut?.let { target ->
            trackOutputFiles(naming.resolve(target, "csv"), paths.size)
                .forEachIndexed { i, file -> write(file, CsvWriter.write(paths[i]), out) }
        }
        jsonOut?.let { target ->
            trackOutputFiles(naming.resolve(target, "json"), paths.size)
                .forEachIndexed { i, file -> write(file, JsonWriter.write(paths[i]), out) }
        }
        fitOut?.let { target ->
            val file = naming.resolve(target, "fit")
            file.absoluteFile.parentFile?.mkdirs()
            // Every track goes into the one FIT file, as a lap each (task g25).
            file.writeBytes(paths.toFitBytes(input.nameWithoutExtension, start!!))
            if (!quiet) out.println("  wrote ${file.path}")
        }
    }

    private fun write(
        file: File,
        content: String,
        out: java.io.PrintWriter,
    ) {
        file.absoluteFile.parentFile?.mkdirs()
        file.writeText(content)
        if (!quiet) out.println("  wrote ${file.path}")
    }
}
