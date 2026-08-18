package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The JS façade catches up with `--gpx-power-source` and the track name.
 *
 * Both existed in the core and the CLI and were unreachable from JS: `writeGpx` / `writeGpxAt`
 * were pinned to `GpxPowerSource.INPUT` and to the literal track name `"virtualized"`. The gap was
 * found while wiring the demo's GPX download, which shipped a physics simulation's output with the
 * physics stripped out. Same bridge contract as `EngineJsApiCatchupTest`: the parameter exists,
 * defaults the way the Kotlin API does, and actually reaches it.
 */
class EngineJsApiGpxPowerSourceTest {
    /** A path whose two power fields differ, so which one was written is unambiguous. */
    private fun simulatedPath(): io.github.glandais.engine.path.Path {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        for (i in 0 until path.size) {
            path.setPComputedPower(i, 321.0)
        }
        return path
    }

    @Test
    fun `the default stays input, so an existing caller sees no change`() {
        val path = simulatedPath()

        assertEquals(writeGpx(path, true, "input"), writeGpx(path), "null powerSource means input")
        assertFalse(writeGpx(path).contains("321"), "the simulated power is not written by default")
    }

    @Test
    fun `computed writes the simulated power`() {
        val xml = writeGpx(simulatedPath(), true, "computed")

        assertContains(xml, "321", message = "pComputedPower reaches <power>")
    }

    @Test
    fun `computed-or-input reaches the facade too`() {
        val xml = writeGpx(simulatedPath(), true, "computed-or-input")

        assertContains(xml, "321")
    }

    @Test
    fun `writeGpxAt takes the same value, alongside its timestamp`() {
        val path = simulatedPath()

        val xml = writeGpxAt(path, 1_767_225_600_000.0, true, "computed")

        assertContains(xml, "321", message = "power")
        // Not asserting a date: `writeGpxAt` adds the start to `time(i)`, and this path is parsed
        // rather than simulated, so its clock is still absolute epoch ms. What matters here is
        // that the start argument still reaches the writer alongside the new one.
        assertTrue(xml.contains("<time>"), "timestamps are written")
        assertTrue(
            writeGpxAt(path, 0.0, true, "computed") != xml,
            "a different start produces different timestamps",
        )
    }

    @Test
    fun `writeGpxTracks takes it as well`() {
        val xml = writeGpxTracks(arrayOf(simulatedPath()), emptyArray(), true, "computed")

        assertContains(xml, "321")
    }

    @Test
    fun `the track name is caller-settable, and still defaults to virtualized`() {
        val path = simulatedPath()

        assertContains(writeGpx(path), "<name>virtualized</name>")
        assertContains(writeGpx(path, true, null, "stelvio"), "<name>stelvio</name>")
        assertContains(writeGpxAt(path, 0.0, true, null, "stelvio"), "<name>stelvio</name>")
    }

    @Test
    fun `an unknown spelling throws instead of silently writing the wrong power`() {
        // The same contract requireOnlyKeys gives the option DTOs: a typo is an error, not a
        // GPX that quietly came out with the other power field in it.
        val error = assertFailsWith<IllegalArgumentException> { writeGpx(simulatedPath(), true, "COMPUTED") }

        assertTrue(error.message!!.contains("computed-or-input"), "the message lists the accepted values")
    }
}
