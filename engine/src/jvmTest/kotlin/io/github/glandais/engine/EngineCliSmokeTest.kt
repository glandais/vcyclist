package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM smoke tests for [EngineCli]. Each case exercises [EngineCli.runCli] (not [EngineCli.main])
 * to avoid the `exitProcess` call killing the test JVM. Covers :
 *  1. empty args → `EXIT_USAGE` (64) + usage message printed to stderr
 *  2. enhance with a nonexistent input file → `EXIT_NO_INPUT` (66) + error printed to stderr
 *  3. full pipeline against the inline `SAMPLE_GPX` fixture → exit 0 and a valid GPX file is written
 *  4. unknown command → `EXIT_USAGE` (64)
 *  5. `help` command → exit 0
 */
class EngineCliSmokeTest {
    private val originalOut: PrintStream = System.out
    private val originalErr: PrintStream = System.err
    private lateinit var capturedOut: ByteArrayOutputStream
    private lateinit var capturedErr: ByteArrayOutputStream

    @BeforeTest
    fun captureStreams() {
        capturedOut = ByteArrayOutputStream()
        capturedErr = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut, true, "UTF-8"))
        System.setErr(PrintStream(capturedErr, true, "UTF-8"))
    }

    @AfterTest
    fun restoreStreams() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private fun stdout(): String = capturedOut.toString("UTF-8")

    private fun stderr(): String = capturedErr.toString("UTF-8")

    // ---- 1. empty args ------------------------------------------------------

    @Test
    fun `case 1 — empty args returns EXIT_USAGE and prints usage to stderr`() {
        val code = EngineCli.runCli(emptyArray())
        assertEquals(EngineCli.EXIT_USAGE, code)
        assertTrue(
            stderr().contains("Usage:"),
            "Expected usage string on stderr, got: ${stderr().take(200)}",
        )
    }

    // ---- 2. nonexistent file -----------------------------------------------

    @Test
    fun `case 2 — enhance with nonexistent input returns EXIT_NO_INPUT`() {
        val code = EngineCli.runCli(arrayOf("enhance", "/definitely/not/here.gpx"))
        assertEquals(EngineCli.EXIT_NO_INPUT, code)
        assertTrue(
            stderr().contains("does not exist"),
            "Expected 'does not exist' on stderr, got: ${stderr().take(200)}",
        )
    }

    // ---- 3. full pipeline ---------------------------------------------------

    @Test
    fun `case 3 — full pipeline produces a parseable GPX output file`() {
        val tmpDir = createTempDirectory(prefix = "engine-cli-smoke-")
        val input = createTempFile(tmpDir, "sample", ".gpx")
        input.writeText(GpxFixtures.SAMPLE_GPX)
        val output = tmpDir.resolve("out.gpx").toFile()

        val code =
            EngineCli.runCli(
                arrayOf("enhance", input.toFile().absolutePath, "-o", output.absolutePath),
            )
        assertEquals(0, code, "stdout=${stdout()}\nstderr=${stderr()}")
        assertTrue(output.exists(), "Output file ${output.absolutePath} should exist")
        assertTrue(output.length() > 0, "Output file should not be empty")

        // Re-parse the produced file with GpxParser to make sure it is well-formed GPX.
        val reparsed = GpxParser.parse(output.readText())
        assertTrue(reparsed.tracks.isNotEmpty(), "Output GPX should contain at least one track")
        val produced = reparsed.tracks[0]
        assertTrue(produced.points.isNotEmpty(), "Output track should contain trackpoints")
        // sample.gpx has 7 source points ; with the full pipeline (PointPerDistance densify,
        // 1 Hz resample, Douglas-Peucker simplify, all on by default since task 31 made the
        // pipeline match the TS reference), the simplifier collapses the short flat segment
        // down to a small number of points. Assert non-empty rather than an exact size (the
        // count is sensitive to PointPerDistance densification + simplify tolerance and may
        // drift across platforms).
        assertTrue(produced.points.isNotEmpty(), "Expected non-empty simplified output")

        val first = produced.points[0]
        // Latitude/longitude survive the round-trip (degrees, with the small float drift
        // documented by GpxWriter — assert with a generous tolerance).
        assertTrue(kotlin.math.abs(first.latitudeDeg - 45.680697) < 1e-4)
        assertTrue(kotlin.math.abs(first.longitudeDeg - 6.396115) < 1e-4)

        // Output banner mentions both the pipeline run and the produced file path.
        val out = stdout()
        assertTrue(out.contains("Running pipeline"), "stdout=$out")
        assertTrue(out.contains("Wrote ${output.absolutePath}"), "stdout=$out")
    }

    // ---- 3b. waypoints survive the full pipeline (g03) ----------------------

    @Test
    fun `case 3b — waypoints in the input GPX are preserved verbatim in the output`() {
        val tmpDir = createTempDirectory(prefix = "engine-cli-wpt-")
        val input = createTempFile(tmpDir, "waypoints", ".gpx")
        input.writeText(GpxFixtures.WAYPOINTS_GPX)
        val output = tmpDir.resolve("out.gpx").toFile()

        val code =
            EngineCli.runCli(
                arrayOf("enhance", input.toFile().absolutePath, "-o", output.absolutePath),
            )
        assertEquals(0, code, "stdout=${stdout()}\nstderr=${stderr()}")

        val source = GpxParser.parse(GpxFixtures.WAYPOINTS_GPX)
        val reparsed = GpxParser.parse(output.readText())
        assertEquals(source.waypoints.size, reparsed.waypoints.size)
        assertEquals(source.waypoints, reparsed.waypoints)
    }

    // ---- 4. unknown command -------------------------------------------------

    @Test
    fun `case 4 — unknown command returns EXIT_USAGE`() {
        val code = EngineCli.runCli(arrayOf("frobnicate"))
        assertEquals(EngineCli.EXIT_USAGE, code)
        assertTrue(
            stderr().contains("Unknown command"),
            "Expected 'Unknown command' on stderr, got: ${stderr().take(200)}",
        )
    }

    // ---- 5. help command ----------------------------------------------------

    @Test
    fun `case 5 — help command exits with 0 and prints usage to stdout`() {
        val code = EngineCli.runCli(arrayOf("help"))
        assertEquals(0, code)
        assertTrue(
            stdout().contains("Usage:"),
            "Expected usage string on stdout, got: ${stdout().take(200)}",
        )
    }
}
