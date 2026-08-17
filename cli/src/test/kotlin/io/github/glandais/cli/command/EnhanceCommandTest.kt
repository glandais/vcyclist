package io.github.glandais.cli.command

import com.garmin.fit.FitDecoder
import io.github.glandais.cli.ExitCodes
import io.github.glandais.cli.RootCommand
import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.RawTile
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.tracksAsPaths
import picocli.CommandLine
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end runs of `enhance` against a real GPX fixture, writing into a temp folder.
 *
 * `--fix-elevation` stays off throughout, so nothing here touches the network — a CLI test that
 * downloads is one that fails in CI for reasons unrelated to the CLI.
 */
class EnhanceCommandTest {
    private val work: File =
        File.createTempFile("vcyclist-cli-enhance", "").let {
            it.delete()
            it.mkdirs()
            it
        }

    @AfterTest
    fun cleanup() {
        work.deleteRecursively()
    }

    /** A short but real trace: 3 points climbing at a plausible gradient. */
    private fun gpxFixture(name: String = "sample.gpx"): File {
        val file = File(work, name)
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>test</name><trkseg>
    <trkpt lat="46.5000" lon="10.4000"><ele>1000</ele><time>2024-01-01T10:00:00Z</time></trkpt>
    <trkpt lat="46.5090" lon="10.4000"><ele>1050</ele><time>2024-01-01T10:04:00Z</time></trkpt>
    <trkpt lat="46.5180" lon="10.4000"><ele>1100</ele><time>2024-01-01T10:08:00Z</time></trkpt>
    <trkpt lat="46.5270" lon="10.4000"><ele>1160</ele><time>2024-01-01T10:12:00Z</time></trkpt>
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
    fun `case 01 — GPX in, GPX out, re-parsable`() {
        val input = gpxFixture()
        val output = File(work, "out.gpx")
        val result = run("enhance", input.path, "--gpx", output.path)

        assertEquals(0, result.code, result.err)
        assertTrue(output.isFile, "no GPX written")
        val paths = GpxParser.parse(output.readText()).tracksAsPaths()
        assertEquals(1, paths.size)
        assertTrue(paths.first().size >= 2, "output track is too short to be meaningful")
    }

    @Test
    fun `case 02 — CSV has a header and one row per point`() {
        val input = gpxFixture()
        val csv = File(work, "out.csv")
        val gpx = File(work, "out.gpx")
        assertEquals(0, run("enhance", input.path, "--csv", csv.path, "--gpx", gpx.path).code)

        val lines = csv.readLines().filter { it.isNotBlank() }
        val points =
            GpxParser
                .parse(gpx.readText())
                .tracksAsPaths()
                .first()
                .size
        assertEquals(points + 1, lines.size, "expected a header plus one row per point")
        assertContains(lines.first(), "latitude")
    }

    @Test
    fun `case 03 — JSON is written and well formed`() {
        val input = gpxFixture()
        val json = File(work, "out.json")
        assertEquals(0, run("enhance", input.path, "--json", json.path).code)

        val text = json.readText()
        assertTrue(text.startsWith("{"), "not a JSON object")
        assertTrue(text.trimEnd().endsWith("}"), "truncated JSON")
        assertContains(text, "\"size\"")
        // Braces balance — a cheap structural check without adding a JSON parser dependency.
        assertEquals(text.count { it == '{' }, text.count { it == '}' }, "unbalanced braces")
    }

    @Test
    fun `case 04 — FIT without a start time is refused, with the reason`() {
        val result = run("enhance", gpxFixture().path, "--fit", File(work, "out.fit").path)
        assertEquals(ExitCodes.USAGE, result.code)
        assertContains(result.err, "--start-time")
        assertContains(result.err, "relative clock")
    }

    @Test
    fun `case 05 — FIT with a start time is written and looks like a FIT file`() {
        val fit = File(work, "out.fit")
        val result =
            run("enhance", gpxFixture().path, "--fit", fit.path, "--start-time", "2026-08-01T08:00:00Z")
        assertEquals(0, result.code, result.err)
        assertTrue(fit.length() > 100, "FIT file suspiciously small: ${fit.length()} bytes")
        // Bytes 8..11 are the ".FIT" data-type marker.
        assertEquals(".FIT", fit.readBytes().copyOfRange(8, 12).decodeToString())
    }

    @Test
    fun `case 06 — no-virtualize keeps the recorded timing instead of simulating one`() {
        val input = gpxFixture()
        val virtualized = File(work, "v.gpx")
        val plain = File(work, "p.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", virtualized.path).code)
        assertEquals(0, run("enhance", input.path, "--gpx", plain.path, "--no-virtualize").code)

        fun duration(f: File) =
            GpxParser
                .parse(f.readText())
                .tracksAsPaths()
                .first()
                .durationMs

        // Note that speed is NOT the discriminator: `computeDerivedData` derives it from the
        // recorded timestamps, so a non-virtualized path has speeds too. What virtualization
        // replaces is the timing itself.
        //
        // The assertion is on the difference rather than on exact values, because both are also
        // shaped by resampling and simplification trimming the tail — over-fitting to those
        // numbers would make this test break on unrelated pipeline tuning.
        val simulated = duration(virtualized)
        val recorded = duration(plain)
        assertTrue(recorded > 0.0 && simulated > 0.0, "both runs must produce a timed track")
        assertTrue(
            kotlin.math.abs(simulated - recorded) / recorded > 0.1,
            "virtualization should produce its own timing, not echo the recorded one: " +
                "simulated $simulated ms vs recorded $recorded ms",
        )
    }

    @Test
    fun `case 06b — the negatable pipeline flags actually take effect`() {
        // Guards the picocli trap documented on EnhanceCommand's flags: with a non-null Boolean,
        // `negatable` toggles the initial value, so --no-virtualize silently did nothing and
        // --virtualize would have DISABLED it. Asserted on parsed state so a regression is
        // caught here rather than through a puzzling output difference.
        fun parsed(vararg args: String): EnhanceCommand = EnhanceCommand().also { CommandLine(it).parseArgs("in.gpx", *args) }

        assertEquals(true, parsed().pipelineOptions().virtualizeTrack, "default is on")
        assertEquals(false, parsed("--no-virtualize").pipelineOptions().virtualizeTrack)
        assertEquals(true, parsed("--virtualize").pipelineOptions().virtualizeTrack)

        assertEquals(true, parsed().pipelineOptions().simplifyPath.enabled)
        assertEquals(false, parsed("--no-simplify").pipelineOptions().simplifyPath.enabled)

        assertEquals(true, parsed().pipelineOptions().computeOnePointPerSecond)
        assertEquals(false, parsed("--no-one-point-per-second").pipelineOptions().computeOnePointPerSecond)

        // Elevation correction is off unless asked: a CLI should not reach the network silently.
        assertEquals(false, parsed().pipelineOptions().fixElevation)
        assertEquals(true, parsed("--fix-elevation").pipelineOptions().fixElevation)
    }

    @Test
    fun `case 07 — a coarser simplify tolerance yields fewer points`() {
        val input = gpxFixture()
        val fine = File(work, "fine.gpx")
        val coarse = File(work, "coarse.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", fine.path, "--simplify-tolerance", "1").code)
        assertEquals(0, run("enhance", input.path, "--gpx", coarse.path, "--simplify-tolerance", "200").code)

        fun points(f: File) =
            GpxParser
                .parse(f.readText())
                .tracksAsPaths()
                .first()
                .size
        assertTrue(
            points(coarse) < points(fine),
            "expected fewer points at tolerance 200 (${points(coarse)}) than at 1 (${points(fine)})",
        )
    }

    @Test
    fun `case 08 — rider options change the simulated outcome`() {
        val input = gpxFixture()
        val strong = File(work, "strong.gpx")
        val weak = File(work, "weak.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", strong.path, "--cyclist-power", "400").code)
        assertEquals(0, run("enhance", input.path, "--gpx", weak.path, "--cyclist-power", "120").code)

        fun duration(f: File) =
            GpxParser
                .parse(f.readText())
                .tracksAsPaths()
                .first()
                .durationMs
        assertTrue(
            duration(strong) < duration(weak),
            "400 W should finish faster than 120 W: ${duration(strong)} vs ${duration(weak)} ms",
        )
    }

    @Test
    fun `case 09 — a missing input file exits with NO_INPUT`() {
        val result = run("enhance", File(work, "absent.gpx").path, "--gpx", File(work, "o.gpx").path)
        assertEquals(ExitCodes.NO_INPUT, result.code)
        assertContains(result.err, "does not exist")
    }

    @Test
    fun `case 10 — one broken file among three does not stop the others`() {
        // The batch behaviour that matters: keep going, then report everything that failed.
        val good1 = gpxFixture("a.gpx")
        val good2 = gpxFixture("b.gpx")
        File(work, "broken.gpx").writeText("this is not XML at all, not even close")

        val outDir = File(work, "batch")
        val result = run("enhance", good1.path, good2.path, File(work, "broken.gpx").path, "--gpx", outDir.path)

        assertEquals(ExitCodes.RUNTIME, result.code, "a failed file must produce a non-zero exit")
        assertContains(result.err, "broken.gpx")
        assertContains(result.err, "1 of 3 file(s) failed")
        // The two good ones were still written, named after their inputs.
        assertTrue(File(outDir, "a.gpx").isFile, "a.gpx was not written")
        assertTrue(File(outDir, "b.gpx").isFile, "b.gpx was not written")
    }

    @Test
    fun `case 13 — quiet prints nothing on success`() {
        val result = run("enhance", gpxFixture().path, "--gpx", File(work, "q.gpx").path, "--quiet")
        assertEquals(0, result.code)
        assertEquals("", result.out.trim(), "expected silence on success, got: ${result.out}")
    }

    // No double quotes in the name: they are illegal in Windows filenames, and the name reaches the
    // filesystem through the test-report and class-file names.
    @Test
    fun `case 14 — xlsx is refused with a pointer to csv, not an unknown-option parse error`() {
        // A gpx2web user typing --xlsx should learn it was dropped and what to use instead,
        // rather than be left wondering whether they mistyped.
        val result = run("enhance", gpxFixture().path, "--xlsx", File(work, "o.xlsx").path)
        assertEquals(ExitCodes.USAGE, result.code)
        assertContains(result.err, "--csv")
        assertTrue("Unknown option" !in result.err, "should be a real message, not a parse error")
    }

    @Test
    fun `case 15 — a malformed start time is rejected before any work happens`() {
        val out = File(work, "never.gpx")
        val result =
            run("enhance", gpxFixture().path, "--gpx", out.path, "--fit", File(work, "x.fit").path, "--start-time", "yesterday")
        assertEquals(ExitCodes.USAGE, result.code)
        assertContains(result.err, "ISO-8601")
        assertTrue(!out.exists(), "nothing should have been written")
    }

    @Test
    fun `case 16 — waypoints survive the enhance round-trip`() {
        // Confirms the g03 work is actually reachable from the CLI.
        val input = File(work, "wpt.gpx")
        input.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <wpt lat="46.51" lon="10.40"><name>Feed zone</name></wpt>
  <trk><trkseg>
    <trkpt lat="46.5000" lon="10.4000"><ele>1000</ele></trkpt>
    <trkpt lat="46.5090" lon="10.4000"><ele>1050</ele></trkpt>
    <trkpt lat="46.5180" lon="10.4000"><ele>1100</ele></trkpt>
  </trkseg></trk>
</gpx>
""",
        )
        val out = File(work, "wpt-out.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", out.path).code)
        val waypoints = GpxParser.parse(out.readText()).waypoints
        assertEquals(1, waypoints.size, "the waypoint was dropped")
        assertEquals("Feed zone", waypoints.single().name)
    }

    @Test
    fun `exit codes are the ones EngineCli already used`() {
        // g18 replaces EngineCli with this; an existing script must keep working.
        assertEquals(64, ExitCodes.USAGE)
        assertEquals(66, ExitCodes.NO_INPUT)
        assertEquals(70, ExitCodes.RUNTIME)
        assertNotEquals(ExitCodes.USAGE, ExitCodes.NO_INPUT)
    }

    /** Same trace, with the sensor extensions an enhanced GPX carries over from its input. */
    private fun gpxFixtureWithExtensions(): File {
        val file = File(work, "sensors.gpx")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
  <trk><name>test</name><trkseg>
    <trkpt lat="46.5000" lon="10.4000"><ele>1000</ele><time>2024-01-01T10:00:00Z</time>
      <extensions><power>210</power><gpxtpx:TrackPointExtension><gpxtpx:hr>141</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
    <trkpt lat="46.5090" lon="10.4000"><ele>1050</ele><time>2024-01-01T10:04:00Z</time>
      <extensions><power>215</power><gpxtpx:TrackPointExtension><gpxtpx:hr>146</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
    <trkpt lat="46.5180" lon="10.4000"><ele>1100</ele><time>2024-01-01T10:08:00Z</time>
      <extensions><power>220</power><gpxtpx:TrackPointExtension><gpxtpx:hr>150</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions></trkpt>
  </trkseg></trk>
</gpx>
""",
        )
        return file
    }

    @Test
    fun `case 18 — without --no-extensions the GPX carries its sensor extensions over`() {
        val input = gpxFixtureWithExtensions()
        val output = File(work, "full.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", output.path).code)

        val xml = output.readText()
        assertContains(xml, "<extensions>")
        assertContains(xml, "gpxtpx")
    }

    @Test
    fun `case 19 — --no-extensions writes a bare, smaller, still valid GPX`() {
        val input = gpxFixtureWithExtensions()
        val full = File(work, "full.gpx")
        val bare = File(work, "bare.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", full.path).code)
        assertEquals(0, run("enhance", input.path, "--gpx", bare.path, "--no-extensions").code)

        val xml = bare.readText()
        assertTrue(!xml.contains("<extensions>"), "extensions should be gone: $xml")
        assertTrue(!xml.contains("gpxtpx"), "gpxtpx namespace should be gone: $xml")
        assertTrue(xml.contains("<ele>"), "elevations are standard GPX and must stay")
        assertTrue(bare.length() < full.length(), "bare=${bare.length()} full=${full.length()}")
        assertEquals(1, GpxParser.parse(xml).tracksAsPaths().size, "still parsable")
    }

    @Test
    fun `case 20 — a route-only GPX goes through the pipeline and comes out as a track`() {
        val input = File(work, "route.gpx")
        input.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <rte><name>planned</name>
    <rtept lat="46.5000" lon="10.4000"><ele>1000</ele></rtept>
    <rtept lat="46.5090" lon="10.4000"><ele>1050</ele></rtept>
    <rtept lat="46.5180" lon="10.4000"><ele>1100</ele></rtept>
  </rte>
</gpx>
""",
        )
        val output = File(work, "route-out.gpx")

        // Before g24 this exited RUNTIME on "no track with any point": the routes were dropped
        // silently at parse time and the pipeline saw an empty document.
        assertEquals(0, run("enhance", input.path, "--gpx", output.path).code)

        val xml = output.readText()
        // A virtualized route is a recording, not a plan: it comes out as <trk>, with timestamps.
        assertTrue(xml.contains("<trk>"), xml)
        assertTrue(!xml.contains("<rte>"), xml)
        assertTrue(
            GpxParser
                .parse(xml)
                .tracksAsPaths()
                .first()
                .size >= 2,
        )
    }

    @Test
    fun `case 21 — a multi-track GPX produces one FIT file with one lap per track`() {
        val input = File(work, "two-tracks.gpx")
        input.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>one</name><trkseg>
    <trkpt lat="46.5000" lon="10.4000"><ele>1000</ele></trkpt>
    <trkpt lat="46.5090" lon="10.4000"><ele>1050</ele></trkpt>
  </trkseg></trk>
  <trk><name>two</name><trkseg>
    <trkpt lat="45.0000" lon="6.0000"><ele>500</ele></trkpt>
    <trkpt lat="45.0090" lon="6.0000"><ele>540</ele></trkpt>
  </trkseg></trk>
</gpx>
""",
        )
        val fit = File(work, "out.fit")

        val result = run("enhance", input.path, "--fit", fit.path, "--start-time", "2026-08-01T08:00:00Z")

        assertEquals(0, result.code, result.err)
        assertTrue(fit.isFile, "no FIT written")
        // Pre-g25 only the first track was encoded, silently dropping the second.
        val messages = FitDecoder(fit.readBytes()).decode().messages
        assertEquals(2, messages.lapMesgs.size, "one lap per track")
        assertEquals(4, messages.eventMesgs.size, "a START and a STOP per track")
    }

    @Test
    fun `case 22 — by default the enhanced GPX carries no simulated power`() {
        val input = gpxFixture()
        val output = File(work, "default.gpx")
        assertEquals(0, run("enhance", input.path, "--gpx", output.path).code)

        // The trace has no <power> in, and the default writes what the file said: nothing.
        assertTrue(!output.readText().contains("<power>"), output.readText())
    }

    @Test
    fun `case 23 — --gpx-power-source computed writes the simulated power`() {
        val input = gpxFixture()
        val output = File(work, "computed.gpx")

        assertEquals(0, run("enhance", input.path, "--gpx", output.path, "--gpx-power-source", "computed").code)

        val xml = output.readText()
        assertContains(xml, "<power>")
        val powers =
            GpxParser
                .parse(xml)
                .tracks
                .first()
                .points
                .mapNotNull { it.powerW }
        assertTrue(powers.isNotEmpty(), "expected simulated power on the points")
        assertTrue(powers.all { it > 0.0 }, "simulated power should be positive: $powers")
    }

    @Test
    fun `case 24 — a bad --gpx-power-source value is refused, not ignored`() {
        val input = gpxFixture()
        val result = run("enhance", input.path, "--gpx", File(work, "x.gpx").path, "--gpx-power-source", "simulated")

        assertEquals(ExitCodes.USAGE, result.code)
        assertContains(result.err, "--gpx-power-source")
    }

    /** Two tracks, so the per-track export naming of g28 has something to name. */
    private fun twoTrackFixture(): File {
        val file = File(work, "two.gpx")
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>one</name><trkseg>
    <trkpt lat="46.5000" lon="10.4000"><ele>1000</ele></trkpt>
    <trkpt lat="46.5090" lon="10.4000"><ele>1050</ele></trkpt>
    <trkpt lat="46.5180" lon="10.4000"><ele>1100</ele></trkpt>
  </trkseg></trk>
  <trk><name>two</name><trkseg>
    <trkpt lat="45.0000" lon="6.0000"><ele>500</ele></trkpt>
    <trkpt lat="45.0090" lon="6.0000"><ele>540</ele></trkpt>
    <trkpt lat="45.0180" lon="6.0000"><ele>590</ele></trkpt>
  </trkseg></trk>
</gpx>
""",
        )
        return file
    }

    @Test
    fun `case 25 — a single-track CSV keeps the file name the user asked for`() {
        val input = gpxFixture()
        val csv = File(work, "single.csv")

        assertEquals(0, run("enhance", input.path, "--csv", csv.path).code)

        assertTrue(csv.isFile, "expected exactly ${csv.path}")
        assertTrue(!File(work, "single-1.csv").exists(), "no suffix for a single track")
    }

    @Test
    fun `case 26 — a two-track CSV writes one file per track, and says so`() {
        val input = twoTrackFixture()
        val csv = File(work, "out.csv")

        val result = run("enhance", input.path, "--csv", csv.path)

        assertEquals(0, result.code, result.err)
        val first = File(work, "out-1.csv")
        val second = File(work, "out-2.csv")
        assertTrue(first.isFile && second.isFile, "expected out-1.csv and out-2.csv, got ${work.list()?.toList()}")
        assertTrue(!csv.exists(), "the un-suffixed name is not written when there are several tracks")
        assertContains(result.out, "out-1.csv")
        assertContains(result.out, "out-2.csv")
    }

    @Test
    fun `case 27 — JSON follows the same rule as CSV`() {
        val input = twoTrackFixture()

        assertEquals(0, run("enhance", input.path, "--json", File(work, "out.json").path).code)

        assertTrue(File(work, "out-1.json").isFile)
        assertTrue(File(work, "out-2.json").isFile)
    }

    // ---- --fix-elevation actually reaches the provider (task g34) -------------
    //
    // Until g34 EnhanceCommand parsed the flag, built the options... and passed
    // `elevationProvider = null` to the Enhancer, which skipped the step. The command
    // succeeded, the file looked plausible, and nothing said the DEM was never consulted.
    // These tests run OFFLINE on purpose: the bug was wiring, and wiring must be checked
    // without the network or it will not be checked in CI at all.

    /** A provider whose every pixel decodes to [elevationM] (Terrarium encoding), no network. */
    private fun cannedProvider(
        elevationM: Int,
        fetched: MutableList<String> = mutableListOf(),
    ): ElevationProvider {
        val tileSize = 4
        val raw = elevationM + 32768
        val rgba = ByteArray(tileSize * tileSize * 4)
        for (pixel in 0 until tileSize * tileSize) {
            rgba[pixel * 4] = ((raw shr 8) and 0xFF).toByte()
            rgba[pixel * 4 + 1] = (raw and 0xFF).toByte()
            rgba[pixel * 4 + 3] = 255.toByte()
        }
        return ElevationProvider(
            ElevationProviderConfig(zoomLevel = 0, tileSize = tileSize, cacheSize = 4, tileUrlTemplate = "test://{z}/{x}/{y}"),
        ) { url ->
            fetched += url
            RawTile(tileSize, tileSize, rgba)
        }
    }

    /** Like [run], but through a hand-built [EnhanceCommand] so the provider seam is reachable. */
    private fun runCommand(
        command: EnhanceCommand,
        vararg args: String,
    ): Run {
        val out = StringWriter()
        val err = StringWriter()
        val code =
            CommandLine(command)
                .setOut(PrintWriter(out, true))
                .setErr(PrintWriter(err, true))
                .execute(*args)
        return Run(code, out.toString(), err.toString())
    }

    @Test
    fun `case 29 — --fix-elevation rewrites the elevations from the DEM tiles, not the input GPX`() {
        val fetched = mutableListOf<String>()
        val command = EnhanceCommand().also { it.elevationProviderFactory = { cannedProvider(500, fetched) } }
        val output = File(work, "fixed.gpx")

        val result = runCommand(command, gpxFixture().path, "--gpx", output.path, "--fix-elevation")

        assertEquals(0, result.code, result.err)
        assertTrue(fetched.isNotEmpty(), "the provider was never asked for a tile — the g34 no-op is back")
        val path = GpxParser.parse(output.readText()).tracksAsPaths().single()
        // The input climbs 1000 → 1160 m; the DEM says 500 m everywhere. If any input elevation
        // survives, the correction did not happen.
        for (i in 0 until path.size) {
            assertEquals(500.0, path.elevation(i), 1.0, "point $i kept an input elevation")
        }
    }

    @Test
    fun `case 30 — without --fix-elevation no provider is even built`() {
        var built = 0
        val command =
            EnhanceCommand().also { cmd ->
                cmd.elevationProviderFactory = {
                    built++
                    cannedProvider(500)
                }
            }

        val result = runCommand(command, gpxFixture().path, "--gpx", File(work, "plain.gpx").path)

        assertEquals(0, result.code, result.err)
        assertEquals(0, built, "a run without --fix-elevation must not instantiate an HTTP tile client")
    }

    @Test
    fun `case 31 — a failing tile fetch fails the run loudly instead of writing a plausible file`() {
        val command =
            EnhanceCommand().also { cmd ->
                cmd.elevationProviderFactory = {
                    ElevationProvider(
                        ElevationProviderConfig(zoomLevel = 0, tileSize = 4, cacheSize = 4, tileUrlTemplate = "test://{z}/{x}/{y}"),
                    ) { url -> throw IllegalStateException("tile unreachable: $url") }
                }
            }
        val output = File(work, "never-fixed.gpx")

        val result = runCommand(command, gpxFixture().path, "--gpx", output.path, "--fix-elevation")

        assertEquals(ExitCodes.RUNTIME, result.code, "a failed correction must not exit 0")
        assertContains(result.err, "tile unreachable")
        assertTrue(!output.exists(), "no output file must be written when the correction failed")
    }

    @Test
    fun `case 28 — every format describes the same set of tracks`() {
        val input = twoTrackFixture()
        val gpx = File(work, "all.gpx")

        assertEquals(
            0,
            run(
                "enhance",
                input.path,
                "--gpx",
                gpx.path,
                "--csv",
                File(work, "all.csv").path,
                "--fit",
                File(work, "all.fit").path,
                "--start-time",
                "2026-08-01T08:00:00Z",
            ).code,
        )

        val tracks = GpxParser.parse(gpx.readText()).tracksAsPaths()
        assertEquals(2, tracks.size, "GPX")
        assertEquals(
            2,
            FitDecoder(File(work, "all.fit").readBytes())
                .decode()
                .messages.lapMesgs.size,
            "FIT",
        )
        // One CSV per track, each with a header plus one row per point of *its* track.
        for ((i, track) in tracks.withIndex()) {
            val lines = File(work, "all-${i + 1}.csv").readLines().filter { it.isNotBlank() }
            assertEquals(track.size + 1, lines.size, "CSV of track ${i + 1}")
        }
    }
}
