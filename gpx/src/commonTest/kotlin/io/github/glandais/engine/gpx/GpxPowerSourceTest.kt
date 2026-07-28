package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task g30: which of a [Path]'s two power fields lands in `<power>`.
 *
 * The asymmetry this settles was found in g23: an enhanced GPX carried no power at all while the
 * FIT of the same ride carried the simulated one, because the writer reads `pInputPower` and
 * `PathToFit` reads `pComputedPower`.
 */
class GpxPowerSourceTest {
    /**
     * [inputPower] is what a source file's `<power>` would have given; [computedPower] is what
     * `PowerComputer` writes. `NaN` for either means "this path does not have it".
     */
    private fun path(
        inputPower: Double,
        computedPower: Double,
        size: Int = 3,
    ): Path {
        val p = Path(size)
        for (i in 0 until size) {
            p.setLatitude(i, (45.0 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 1000.0 + i)
            p.setTime(i, i * 1000.0)
            p.setPInputPower(i, inputPower)
            p.setPComputedPower(i, computedPower)
        }
        p.computeDerivedData()
        return p
    }

    private fun powersOf(track: GpxTrack): List<Double?> = track.points.map { it.powerW }

    @Test
    fun `case 01 — the default is the input power, as before g30`() {
        val track = path(inputPower = 210.0, computedPower = 305.0).toGpxTrack()

        assertEquals(listOf(210.0, 210.0, 210.0), powersOf(track))
    }

    @Test
    fun `case 02 — the default is explicitly INPUT, not merely accidental`() {
        val source = path(inputPower = 210.0, computedPower = 305.0)

        assertEquals(
            source.toGpxTrack(),
            source.toGpxTrack(powerSource = GpxPowerSource.INPUT),
        )
    }

    @Test
    fun `case 03 — COMPUTED writes the simulated power`() {
        val track = path(inputPower = 210.0, computedPower = 305.0).toGpxTrack(powerSource = GpxPowerSource.COMPUTED)

        assertEquals(listOf(305.0, 305.0, 305.0), powersOf(track))
    }

    @Test
    fun `case 04 — COMPUTED on a path that was never simulated writes nothing`() {
        // A path straight out of GpxToPath: pComputedPower is the zero-initialised slot.
        val track = path(inputPower = 210.0, computedPower = 0.0).toGpxTrack(powerSource = GpxPowerSource.COMPUTED)

        assertEquals(listOf(null, null, null), powersOf(track), "a flat 0 W line is worse than no line")
    }

    @Test
    fun `case 05 — COMPUTED_OR_INPUT prefers the simulation`() {
        val track =
            path(inputPower = 210.0, computedPower = 305.0).toGpxTrack(powerSource = GpxPowerSource.COMPUTED_OR_INPUT)

        assertEquals(listOf(305.0, 305.0, 305.0), powersOf(track))
    }

    @Test
    fun `case 06 — COMPUTED_OR_INPUT falls back point by point`() {
        val p = path(inputPower = 210.0, computedPower = 0.0)
        p.setPComputedPower(1, 288.0) // only the middle point was simulated

        val track = p.toGpxTrack(powerSource = GpxPowerSource.COMPUTED_OR_INPUT)

        assertEquals(listOf(210.0, 288.0, 210.0), powersOf(track))
    }

    @Test
    fun `case 07 — a path with neither power writes none, whatever the source`() {
        val p = path(inputPower = Double.NaN, computedPower = 0.0)

        for (source in GpxPowerSource.entries) {
            assertEquals(listOf(null, null, null), powersOf(p.toGpxTrack(powerSource = source)), "source $source")
        }
    }

    @Test
    fun `case 08 — the g23 asymmetry is now reachable end to end`() {
        // The exact case that surfaced the problem: enhanced trace, no sensors in the input.
        val enhanced = path(inputPower = Double.NaN, computedPower = 240.0)

        val default = GpxWriter.write(enhanced.toGpxDocument())
        val computed = GpxWriter.write(enhanced.toGpxDocument(powerSource = GpxPowerSource.COMPUTED))

        assertTrue(!default.contains("<power>"), "the default still writes nothing: $default")
        assertTrue(computed.contains("<power>"), computed)
        // Through the parser, not as text: Double.toString renders 240.0 as "240" on Kotlin/JS.
        assertEquals(
            240.0,
            GpxParser
                .parse(computed)
                .tracks[0]
                .points[0]
                .powerW,
        )
    }

    @Test
    fun `case 09 — the choice reaches the multi-track document`() {
        val paths = listOf(path(inputPower = 100.0, computedPower = 200.0), path(inputPower = 110.0, computedPower = 210.0))

        val document = pathsToGpxDocument(paths, powerSource = GpxPowerSource.COMPUTED)

        assertEquals(listOf(200.0, 200.0, 200.0), powersOf(document.tracks[0]))
        assertEquals(listOf(210.0, 210.0, 210.0), powersOf(document.tracks[1]))
    }

    @Test
    fun `case 10 — a written computed power comes back as an input power`() {
        // Inherent to the format, and the reason INPUT is the default: <power> has no provenance,
        // so a round-trip launders a simulated value into a measured-looking one.
        val enhanced = path(inputPower = Double.NaN, computedPower = 240.0)
        val xml = GpxWriter.write(enhanced.toGpxDocument(powerSource = GpxPowerSource.COMPUTED))

        val reparsed = GpxParser.parse(xml).tracks[0].toPath()

        assertEquals(240.0, reparsed.pInputPower(0), 1e-9, "reappears as input power")
        assertEquals(0.0, reparsed.pComputedPower(0), "the parser has no computed slot to fill")
        assertEquals(
            240.0,
            GpxParser
                .parse(xml)
                .tracks[0]
                .points[0]
                .powerW,
        )
    }
}
