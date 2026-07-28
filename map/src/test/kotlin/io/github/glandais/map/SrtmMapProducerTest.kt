package io.github.glandais.map

import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.awt.Color
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hypsometric rendering, against a synthetic relief. No network: the [ElevationSampler] is
 * supplied directly, which also makes the number of DEM round-trips observable.
 */
class SrtmMapProducerTest {
    /** Counts calls and points, so a regression to per-pixel lookups shows up as a number. */
    private class CountingSampler(
        private val elevationAt: (LatLon) -> Double,
    ) : ElevationSampler {
        var calls = 0
            private set
        var pointsRequested = 0
            private set

        override suspend fun sample(points: List<LatLon>): DoubleArray {
            calls++
            pointsRequested += points.size
            return DoubleArray(points.size) { elevationAt(points[it]) }
        }
    }

    /** Altitude rising with latitude — a tilted plane. */
    private fun tiltedPlane() = CountingSampler { 1000.0 + (it.latitude - 46.0) * 100_000.0 }

    private fun flatRelief() = CountingSampler { 500.0 }

    private fun noCoverage() = CountingSampler { Double.NaN }

    private fun pathOf(vararg latLon: Pair<Double, Double>): Path {
        val p = Path(latLon.size)
        for ((i, ll) in latLon.withIndex()) {
            p.setLatitude(i, ll.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, ll.second * MathConstants.DEG_TO_RAD)
            // Rising elevation so the track ramp spans blue -> red.
            p.setElevation(i, 2000.0 + i * 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    private fun stelvio() = pathOf(46.5318 to 10.4439, 46.5325 to 10.4500, 46.5320 to 10.4591)

    private fun outputFile() = File.createTempFile("vcyclist-srtm", ".png").also { it.deleteOnExit() }

    @Test
    fun `case 01 — a tilted plane renders as a monotonic gradient`() {
        val file = outputFile()
        val map = SrtmMapProducer(tiltedPlane()).createSrtmMap(file, listOf(stelvio()), maxSize = 256)
        val image = ImageIO.read(file)

        // Altitude rises with latitude and y increases southwards, so relative altitude falls
        // down the image. Read the RED channel, not blue: on the cyan -> yellow -> magenta ramp
        // blue goes 255 -> 0 -> 255, so it is deliberately NOT monotonic, while red rises 0 ->
        // 255 -> 255. Sampled in column 2, away from the track overlay.
        val column = 2
        val top = Color(image.getRGB(column, 1))
        val middle = Color(image.getRGB(column, image.height / 2))
        val bottom = Color(image.getRGB(column, image.height - 2))
        assertTrue(
            top.red >= middle.red && middle.red >= bottom.red,
            "expected a monotonic ramp down the image, got ${top.red}, ${middle.red}, ${bottom.red}",
        )
        assertTrue(top != bottom, "a tilted plane must not render uniformly")
        assertTrue(map.width <= 256 && map.height <= 256)
    }

    @Test
    fun `case 02 — a flat relief renders uniformly`() {
        val file = outputFile()
        SrtmMapProducer(flatRelief()).createSrtmMap(file, listOf(stelvio()), maxSize = 128)
        val image = ImageIO.read(file)
        // Corners are far from the track overlay.
        val a = image.getRGB(0, 0)
        val b = image.getRGB(image.width - 1, 0)
        val c = image.getRGB(0, image.height - 1)
        assertEquals(a, b, "flat relief must be uniform")
        assertEquals(a, c, "flat relief must be uniform")
        // Mid-ramp yellow, chosen explicitly rather than reached by dividing by zero.
        assertEquals(Color(255, 255, 0).rgb, a)
    }

    @Test
    fun `case 03 — missing DEM coverage is painted a documented neutral colour, not black`() {
        val file = outputFile()
        SrtmMapProducer(noCoverage()).createSrtmMap(file, listOf(stelvio()), maxSize = 128)
        val image = ImageIO.read(file)
        val corner = Color(image.getRGB(0, 0))
        assertEquals(SrtmMapProducer.NO_DATA_COLOR, corner)
        // Explicitly not black: black would read as "lowest altitude" on the ramp.
        assertTrue(corner != Color.BLACK, "no-data must be distinguishable from sea level")
    }

    @Test
    fun `case 04 — maxSize is respected`() {
        for (maxSize in listOf(64, 128, 512)) {
            val map =
                SrtmMapProducer(tiltedPlane())
                    .createSrtmMap(outputFile(), listOf(stelvio()), maxSize = maxSize)
            assertTrue(map.width <= maxSize && map.height <= maxSize, "${map.width}x${map.height} exceeds $maxSize")
        }
    }

    @Test
    fun `case 05 — a margin widens the rendered bounds`() {
        val tight =
            SrtmMapProducer(tiltedPlane())
                .createSrtmMap(outputFile(), listOf(stelvio()), maxSize = 256, margin = 0.0)
        val padded =
            SrtmMapProducer(tiltedPlane())
                .createSrtmMap(outputFile(), listOf(stelvio()), maxSize = 256, margin = 0.5)
        assertTrue(padded.maxLon - padded.minLon > tight.maxLon - tight.minLon)
        assertTrue(padded.maxLat - padded.minLat > tight.maxLat - tight.minLat)
    }

    @Test
    fun `case 06 — the track is visible over the relief`() {
        val file = outputFile()
        SrtmMapProducer(flatRelief()).createSrtmMap(file, listOf(stelvio()), maxSize = 512)
        val image = ImageIO.read(file)
        // The relief is flat, so the background is a single colour: anything that differs from
        // it came from the track. Comparing against the actual background beats guessing channel
        // thresholds — a red track on a yellow background differs only in green, which is exactly
        // what an earlier version of this assertion missed.
        val background = image.getRGB(0, 0)
        var trackPixels = 0
        for (x in 0 until image.width) {
            for (y in 0 until image.height) {
                if (image.getRGB(x, y) != background) trackPixels++
            }
        }
        assertTrue(trackPixels > 0, "the track was not drawn over the relief")
    }

    @Test
    fun `case 07 — every track of a multi-track render is drawn`() {
        val file = outputFile()
        val west = pathOf(46.5318 to 10.4439, 46.5325 to 10.4460)
        val east = pathOf(46.5300 to 10.4550, 46.5310 to 10.4591)
        val map =
            SrtmMapProducer(flatRelief())
                .createSrtmMap(file, listOf(west, east), maxSize = 512, margin = 0.1)
        val image = ImageIO.read(file)

        // Look for track pixels near EACH track's own location within the single rendered frame.
        // Comparing pixel counts across two separate renders would be unsound, because adding a
        // path changes the bounds and therefore the whole framing.
        // Flat relief, so the background is uniform; anything different is track.
        val background = image.getRGB(0, 0)

        fun trackPixelsNear(path: Path): Int {
            var n = 0
            for (i in 0 until path.size) {
                val cx = map.getX(path.longitude(i) * MathConstants.RAD_TO_DEG)
                val cy = map.getY(path.latitude(i) * MathConstants.RAD_TO_DEG)
                for (dx in -4..4) {
                    for (dy in -4..4) {
                        val x = cx + dx
                        val y = cy + dy
                        if (x !in 0 until image.width || y !in 0 until image.height) continue
                        if (image.getRGB(x, y) != background) n++
                    }
                }
            }
            return n
        }
        assertTrue(trackPixelsNear(west) > 0, "the first track was not drawn")
        assertTrue(trackPixelsNear(east) > 0, "the second track was not drawn")
    }

    @Test
    fun `case 08 — the PNG is readable and correctly sized`() {
        val file = outputFile()
        val map = SrtmMapProducer(tiltedPlane()).createSrtmMap(file, listOf(stelvio()), maxSize = 300)
        val image = ImageIO.read(file)
        assertEquals(map.width, image.width)
        assertEquals(map.height, image.height)
        assertTrue(file.length() > 0)
    }

    @Test
    fun `case 09 — elevation lookups are batched and bounded, not one per pixel`() {
        // The guard against a silent performance regression. The reference does one lookup per
        // pixel; this must stay far below that, in a single batched round-trip.
        val sampler = tiltedPlane()
        val map = SrtmMapProducer(sampler).createSrtmMap(outputFile(), listOf(stelvio()), maxSize = 512)

        val pixels = map.width * map.height
        assertEquals(1, sampler.calls, "the DEM must be queried in exactly one batched call")
        assertTrue(
            sampler.pointsRequested <= pixels,
            "sampled ${sampler.pointsRequested} points for only $pixels pixels",
        )

        // And on an image far larger than the cap, sampling must be a small fraction of the
        // pixels — this is the assertion that would fail if someone reverted to per-pixel.
        val big = tiltedPlane()
        val bigMap = SrtmMapProducer(big).createSrtmMap(outputFile(), listOf(stelvio()), maxSize = 4096)
        val bigPixels = bigMap.width.toLong() * bigMap.height
        assertEquals(1, big.calls, "still exactly one batched call at 4096 px")
        assertTrue(
            big.pointsRequested <= SrtmMapProducer.DEFAULT_MAX_SAMPLES + 2 * (bigMap.width + bigMap.height),
            "sampled ${big.pointsRequested} points, above the cap",
        )
        assertTrue(
            big.pointsRequested * 4L < bigPixels,
            "sampled ${big.pointsRequested} for $bigPixels pixels — barely better than per-pixel",
        )
    }

    @Test
    fun `case 11 — a lower sample cap reduces the number of lookups`() {
        val coarse = tiltedPlane()
        val fine = tiltedPlane()
        SrtmMapProducer(coarse, maxSamples = 256).createSrtmMap(outputFile(), listOf(stelvio()), maxSize = 512)
        SrtmMapProducer(fine, maxSamples = 65_536).createSrtmMap(outputFile(), listOf(stelvio()), maxSize = 512)
        assertTrue(
            coarse.pointsRequested < fine.pointsRequested,
            "the cap should govern sampling: ${coarse.pointsRequested} vs ${fine.pointsRequested}",
        )
    }

    @Test
    fun `case 12 — the two colour ramps match the reference`() {
        // Packed RGB without an alpha byte, as the reference produces and as `setRGB` on a
        // TYPE_INT_RGB image expects — hence the mask against java.awt.Color's ARGB.
        fun rgb(c: Color) = c.rgb and 0xFFFFFF

        // Terrain: cyan -> yellow -> magenta.
        assertEquals(rgb(Color(0, 255, 255)), SrtmMapProducer.terrainColor(0.0))
        assertEquals(rgb(Color(255, 255, 0)), SrtmMapProducer.terrainColor(0.5))
        assertEquals(rgb(Color(255, 0, 255)), SrtmMapProducer.terrainColor(1.0))
        // Track: blue -> green -> red.
        assertEquals(rgb(Color(0, 0, 255)), SrtmMapProducer.trackColor(0.0))
        assertEquals(rgb(Color(0, 255, 0)), SrtmMapProducer.trackColor(0.5))
        assertEquals(rgb(Color(255, 0, 0)), SrtmMapProducer.trackColor(1.0))
        // Out-of-range input is clamped rather than producing a nonsense colour.
        assertEquals(SrtmMapProducer.terrainColor(0.0), SrtmMapProducer.terrainColor(-1.0))
        assertEquals(SrtmMapProducer.terrainColor(1.0), SrtmMapProducer.terrainColor(2.0))
    }
}
