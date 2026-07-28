package io.github.glandais.cli.command

import io.github.glandais.cli.ExitCodes
import io.github.glandais.cli.RootCommand
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.map.ElevationSampler
import io.github.glandais.map.SrtmMapProducer
import picocli.CommandLine
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `export` argument handling and the outputs that need no network.
 *
 * `--map` and `--elevation-map` both download (tiles and DEM respectively), so only their
 * argument validation is covered here; actually rendering them is exercised by `:map`'s own
 * tests and by the gated integration tests there.
 */
class ExportCommandTest {
    private val work: File =
        File.createTempFile("vcyclist-cli-export", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanup() {
        work.deleteRecursively()
    }

    private fun gpxFixture(name: String = "sample.gpx"): File {
        val file = File(work, name)
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><trkseg>
    <trkpt lat="46.5000" lon="10.4000"><ele>1000</ele></trkpt>
    <trkpt lat="46.5090" lon="10.4000"><ele>1050</ele></trkpt>
    <trkpt lat="46.5180" lon="10.4000"><ele>1100</ele></trkpt>
  </trkseg></trk>
</gpx>
""",
        )
        return file
    }

    private class Run(
        val code: Int,
        val out: String,
        val err: String,
    )

    private fun run(vararg args: String): Run {
        val out = StringWriter()
        val err = StringWriter()
        val code =
            CommandLine(RootCommand())
                .setOut(PrintWriter(out, true))
                .setErr(PrintWriter(err, true))
                .execute(*args)
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun `case 11 — map without tile-url is refused, and the message explains why`() {
        val result = run("export", gpxFixture().path, "--map", File(work, "m.png").path)
        assertEquals(ExitCodes.USAGE, result.code)
        assertContains(result.err, "--tile-url")
        // Not just "missing argument" — the user should learn there is deliberately no default.
        assertContains(result.err, "usage policy")
    }

    @Test
    fun `case 12 — csv and json export without touching the network`() {
        val csv = File(work, "out.csv")
        val json = File(work, "out.json")
        val result = run("export", gpxFixture().path, "--csv", csv.path, "--json", json.path)

        assertEquals(0, result.code, result.err)
        assertTrue(csv.isFile && csv.length() > 0, "no CSV written")
        assertTrue(json.isFile && json.length() > 0, "no JSON written")
        assertEquals(4, csv.readLines().filter { it.isNotBlank() }.size, "header plus three points")
    }

    @Test
    fun `case 13 — fit export requires a start time and is written when given one`() {
        val fit = File(work, "out.fit")
        assertEquals(ExitCodes.USAGE, run("export", gpxFixture().path, "--fit", fit.path).code)

        val ok = run("export", gpxFixture().path, "--fit", fit.path, "--start-time", "2026-08-01T08:00:00Z")
        assertEquals(0, ok.code, ok.err)
        assertEquals(".FIT", fit.readBytes().copyOfRange(8, 12).decodeToString())
    }

    @Test
    fun `case 14 — asking for nothing is a usage error, not a silent success`() {
        val result = run("export", gpxFixture().path)
        assertEquals(ExitCodes.USAGE, result.code)
        assertContains(result.err, "Nothing to export")
    }

    @Test
    fun `case 15 — a missing input exits with NO_INPUT`() {
        val result = run("export", File(work, "absent.gpx").path, "--csv", File(work, "o.csv").path)
        assertEquals(ExitCodes.NO_INPUT, result.code)
    }

    @Test
    fun `case 16 — batch mode names outputs after their inputs and survives a bad file`() {
        gpxFixture("a.gpx")
        gpxFixture("b.gpx")
        File(work, "broken.gpx").writeText("definitely not xml")
        val outDir = File(work, "csvs")

        val result =
            run(
                "export",
                File(work, "a.gpx").path,
                File(work, "b.gpx").path,
                File(work, "broken.gpx").path,
                "--csv",
                outDir.path,
            )
        assertEquals(ExitCodes.RUNTIME, result.code)
        assertTrue(File(outDir, "a.csv").isFile, "a.csv missing")
        assertTrue(File(outDir, "b.csv").isFile, "b.csv missing")
        assertContains(result.err, "1 of 3 file(s) failed")
    }

    @Test
    fun `case 17 — quiet suppresses output on success`() {
        val result = run("export", gpxFixture().path, "--csv", File(work, "q.csv").path, "--quiet")
        assertEquals(0, result.code)
        assertEquals("", result.out.trim())
    }

    @Test
    fun `case 18 — the elevation map renders from an injected sampler, no network`() {
        // Exercises the same rendering path the CLI uses, minus the DEM download, so the wiring
        // is covered without a network dependency.
        val png = File(work, "relief.png")
        val paths = GpxParser.parse(gpxFixture().readText()).tracksAsPaths()
        SrtmMapProducer(
            ElevationSampler { points -> DoubleArray(points.size) { 1000.0 + points[it].latitude * 100 } },
        ).createSrtmMap(png, paths, maxSize = 128)

        assertTrue(png.isFile && png.length() > 0, "no PNG written")
        val image = ImageIO.read(png)
        assertTrue(maxOf(image.width, image.height) <= 128, "maxSize not respected: ${image.width}x${image.height}")
    }

    @Test
    fun `case 19 — export --no-extensions rewrites the GPX without extensions`() {
        val input = gpxFixture()
        val output = File(work, "bare.gpx")
        assertEquals(0, run("export", input.path, "--gpx", output.path, "--no-extensions").code)

        val xml = output.readText()
        assertTrue(!xml.contains("<extensions>"), xml)
        assertTrue(!xml.contains("gpxtpx"), xml)
        assertTrue(xml.contains("<trkpt"), xml)
    }
}
