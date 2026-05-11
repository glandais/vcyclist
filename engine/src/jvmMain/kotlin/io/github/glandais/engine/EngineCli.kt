package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * JVM entry point for the engine CLI. Minimal usage :
 *
 * ```
 * engine enhance <input.gpx> [-o <output.gpx>]
 * ```
 *
 * Runs [Enhancer.enhanceCourseDefault] with `fixElevation = false` (no network
 * `ElevationProvider` available in a plain JVM run) and disables the 1 Hz resample +
 * Douglas-Peucker simplify steps because the upstream sample fixtures carry absolute
 * 2024 timestamps (epoch ≈ 1.7e12 ms). [io.github.glandais.engine.physics.VirtualizeService]
 * snaps the **last** sample to the original timestamp, so a naive `PointPerSecond` would
 * allocate ~1.7 billion second slots and OOM — see task 27 spec.
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

        if (!input.exists()) {
            System.err.println("Input file does not exist: ${input.absolutePath}")
            return EXIT_NO_INPUT
        }

        return try {
            runEnhance(input, output)
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
    ) {
        println("Reading ${input.absolutePath}")
        val xml = input.readText()
        val doc = GpxParser.parse(xml)
        val inputPath = doc.firstTrackAsPath()
        println(
            "  -> ${inputPath.size} points, " +
                "${"%.1f".format(inputPath.totalDistance)} m, " +
                "gain ${"%.1f".format(inputPath.elevationGain)} m",
        )

        // fixElevation=false (no HTTP provider here).
        // computeOnePointPerSecond=false + simplifyPath disabled : VirtualizeService snaps the
        // last point to the original 2024 epoch timestamp, so a naive 1 Hz resample would
        // explode. Smoke goal is to demonstrate the pipeline end-to-end ; tighter parity is
        // covered by `EnhancerParityTest` on small inline fixtures.
        val options =
            EnhanceOptions.DEFAULT.copy(
                fixElevation = false,
                computeOnePointPerSecond = false,
                simplifyPath = SimplifyPathOptions(enabled = false),
            )

        println("Running pipeline (fixElevation=false, computeOnePointPerSecond=false, simplifyPath=off)...")
        val result =
            runBlocking {
                Enhancer.enhanceCourseDefault(
                    inputPath,
                    elevationProvider = null,
                    options = options,
                )
            }
        println(
            "  -> ${result.size} points, " +
                "${"%.1f".format(result.totalDistance)} m, " +
                "duration ${"%.1f".format(result.durationMs / 1000.0)} s",
        )

        if (output != null) {
            val outXml =
                GpxWriter.write(
                    result.toGpxDocument(
                        name = input.nameWithoutExtension,
                        trackName = "virtualized",
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
            |Usage: engine enhance <input.gpx> [-o <output.gpx>]
            |       engine help
            |
            |Runs the virtual-cyclist enhancement pipeline on the input GPX file with default
            |Cyclist (80 kg / 280 W) and Bike (Crr 0.004) parameters. No elevation correction
            |is performed (no HTTP access). 1 Hz resample and Douglas-Peucker simplification
            |are disabled in this CLI to avoid issues with absolute-epoch timestamps in raw
            |GPX fixtures.
            |
            |Gradle usage:
            |   ./gradlew :engine:run -Pargs="enhance input.gpx -o /tmp/out.gpx"
            """.trimMargin(),
        )
    }
}
