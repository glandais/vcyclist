package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.pathsToGpxDocument
import io.github.glandais.engine.gpx.tracksAsPaths
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess
import kotlin.time.Instant

/**
 * JVM entry point for the engine CLI. Minimal usage :
 *
 * ```
 * engine enhance <input.gpx> [-o <output.gpx>]
 * ```
 *
 * Runs [Enhancer.enhanceCourseDefault] with `fixElevation = false` (no network
 * `ElevationProvider` available in a plain JVM run). All other pipeline steps run with
 * their defaults (max speeds, virtualize, 1 Hz resample, Douglas-Peucker simplify). The
 * task 29 fix made the 1 Hz resample safe on real GPX with absolute 2024 timestamps.
 *
 * Invoked from Gradle :
 *
 * ```
 * ./gradlew :engine:run -Pargs="enhance ../virtual-cyclist/gpx/sample.gpx -o /tmp/out.gpx"
 * ```
 */
object EngineCli {
    /** Exit code used when arguments are missing or malformed (`EX_USAGE`). */
    const val EXIT_USAGE: Int = 64

    /** Exit code used when an input file does not exist (`EX_NOINPUT`). */
    const val EXIT_NO_INPUT: Int = 66

    /** Exit code used when an unhandled exception bubbles up from the pipeline. */
    const val EXIT_RUNTIME: Int = 70

    /**
     * Testable entry point : runs the CLI and returns an exit code instead of calling
     * [exitProcess]. Tests use this directly so they can assert on the returned code without
     * killing the JVM.
     */
    fun runCli(args: Array<String>): Int {
        if (args.isEmpty()) {
            printUsage(System.err)
            return EXIT_USAGE
        }
        return when (args[0]) {
            "enhance" -> enhance(args.drop(1))
            "help", "-h", "--help" -> {
                printUsage(System.out)
                0
            }
            else -> {
                System.err.println("Unknown command: ${args[0]}")
                printUsage(System.err)
                EXIT_USAGE
            }
        }
    }

    /** Real `main` : delegates to [runCli] and exits with the returned code if non-zero. */
    @JvmStatic
    fun main(args: Array<String>) {
        val code = runCli(args)
        if (code != 0) exitProcess(code)
    }

    private fun enhance(args: List<String>): Int {
        if (args.isEmpty()) {
            System.err.println("Missing input file")
            printUsage(System.err)
            return EXIT_USAGE
        }
        val input = File(args[0])
        val outputIdx = args.indexOf("-o")
        val output =
            if (outputIdx >= 0 && outputIdx + 1 < args.size) {
                File(args[outputIdx + 1])
            } else {
                null
            }
        val startTimeIdx = args.indexOf("--start-time")
        val startTime: Instant?
        if (startTimeIdx >= 0) {
            if (startTimeIdx + 1 >= args.size) {
                System.err.println("Missing value for --start-time")
                printUsage(System.err)
                return EXIT_USAGE
            }
            val raw = args[startTimeIdx + 1]
            startTime =
                try {
                    Instant.parse(raw)
                } catch (e: IllegalArgumentException) {
                    System.err.println("Invalid --start-time '$raw' (expected ISO-8601, e.g. 2026-07-27T08:00:00Z)")
                    return EXIT_USAGE
                }
        } else {
            startTime = null
        }

        if (!input.exists()) {
            System.err.println("Input file does not exist: ${input.absolutePath}")
            return EXIT_NO_INPUT
        }

        return try {
            runEnhance(input, output, startTime)
            0
        } catch (e: Exception) {
            System.err.println("Pipeline failed: ${e.message}")
            e.printStackTrace(System.err)
            EXIT_RUNTIME
        }
    }

    private fun runEnhance(
        input: File,
        output: File?,
        startTime: Instant? = null,
    ) {
        println("Reading ${input.absolutePath}")
        val xml = input.readText()
        val doc = GpxParser.parse(xml)
        val inputPaths = doc.tracksAsPaths()
        if (inputPaths.isEmpty()) {
            throw IllegalArgumentException("No track with any point in ${input.name}")
        }
        println(
            "  -> ${inputPaths.size} track(s), " +
                "${inputPaths.sumOf { it.size }} points total, " +
                "${"%.1f".format(inputPaths.sumOf { it.totalDistance })} m, " +
                "gain ${"%.1f".format(inputPaths.sumOf { it.elevationGain })} m, " +
                "${doc.waypoints.size} waypoint(s)",
        )

        // fixElevation=false (no HTTP provider here). Other steps run with defaults : the
        // task 29 fix makes `VirtualizeService` produce a sane `time(n-1)` so the downstream
        // 1 Hz resample + Douglas-Peucker simplify are safe on real GPX with absolute
        // 2024 timestamps.
        val options = EnhanceOptions.DEFAULT.copy(fixElevation = false)

        println("Running pipeline (fixElevation=false, all other steps default)...")
        val results =
            runBlocking {
                Enhancer.enhanceCourses(
                    inputPaths,
                    elevationProvider = null,
                    options = options,
                )
            }
        println(
            "  -> ${results.size} track(s), " +
                "${results.sumOf { it.size }} points total, " +
                "${"%.1f".format(results.sumOf { it.totalDistance })} m, " +
                "duration ${"%.1f".format(results.sumOf { it.durationMs } / 1000.0)} s",
        )

        if (output != null) {
            val outXml =
                GpxWriter.write(
                    pathsToGpxDocument(
                        results,
                        name = input.nameWithoutExtension,
                        // Single track keeps the pre-g02 name verbatim, so single-track output is
                        // unchanged ; multi-track output gets a 1-based suffix.
                        trackNames =
                            results.indices.map { i ->
                                if (results.size == 1) "virtualized" else "virtualized-${i + 1}"
                            },
                        // Waypoints are not track points : the enhancement pipeline never sees
                        // them, so they must be copied verbatim from the source document to the
                        // output one — see g03.
                        waypoints = doc.waypoints,
                        // Absent unless --start-time was passed : no implicit "now" default, to
                        // keep the CLI's output reproducible (see g05 spec Notes).
                        startTime = startTime,
                    ),
                )
            output.absoluteFile.parentFile?.mkdirs()
            output.writeText(outXml)
            println("Wrote ${output.absolutePath} (${outXml.length} chars)")
        } else {
            println("(no -o flag : skipping output)")
        }
    }

    private fun printUsage(out: java.io.PrintStream) {
        out.println(
            """
            |Usage: engine enhance <input.gpx> [-o <output.gpx>] [--start-time <ISO-8601>]
            |       engine help
            |
            |Runs the virtual-cyclist enhancement pipeline on the input GPX file with default
            |Cyclist (80 kg / 280 W) and Bike (Crr 0.004) parameters. No elevation correction
            |is performed (no HTTP access) ; every other pipeline step runs with its defaults.
            |
            |--start-time <ISO-8601> : instant of the first output point (e.g.
            |   2026-07-27T08:00:00Z). Every point's <time> becomes startTime + time(i). Absent
            |   by default : the output then carries no <time> tag at all (no implicit "now" —
            |   reproducibility over convenience, see docs/tasks/g05-gpx-start-time.md).
            |
            |Gradle usage:
            |   ./gradlew :engine:run -Pargs="enhance input.gpx -o /tmp/out.gpx --start-time 2026-07-27T08:00:00Z"
            """.trimMargin(),
        )
    }
}
