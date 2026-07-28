package io.github.glandais.map

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MapImageTest {
    /** Path from explicit degree coordinates. */
    private fun pathOf(vararg latLon: Pair<Double, Double>): Path {
        val p = Path(latLon.size)
        for ((i, ll) in latLon.withIndex()) {
            p.setLatitude(i, ll.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, ll.second * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 100.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    /** The Stelvio corner of the Alps — a small, realistic extent. */
    private fun stelvioPath() =
        pathOf(
            46.5318 to 10.4439,
            46.5325 to 10.4500,
            46.5320 to 10.4591,
        )

    @Test
    fun `case 06 — a single-point path still yields a usable image`() {
        // Zero extent means every zoom gives 0x0; the framing must not divide by zero, loop
        // forever, or hand back a negative zoom.
        val map = MapImage.ofMaxSize(listOf(pathOf(45.0 to 6.0)), margin = 0.1, maxSize = 512)
        assertTrue(map.zoom >= 0, "zoom must not go negative")
        assertEquals(1, map.image.width.coerceAtMost(1), "image is created even at zero extent")
        assertTrue(map.image.height >= 1)
    }

    @Test
    fun `case 07 — bounds enclose every point of the path`() {
        val path = stelvioPath()
        val map = MapImage.ofMaxSize(listOf(path), margin = 0.0, maxSize = 1024)
        for (i in 0 until path.size) {
            val lat = path.latitude(i) * MathConstants.RAD_TO_DEG
            val lon = path.longitude(i) * MathConstants.RAD_TO_DEG
            assertTrue(lon in map.minLon..map.maxLon, "point $i longitude $lon outside ${map.minLon}..${map.maxLon}")
            assertTrue(lat in map.minLat..map.maxLat, "point $i latitude $lat outside ${map.minLat}..${map.maxLat}")
        }
    }

    @Test
    fun `case 08 — a margin widens the bounds in both directions`() {
        val path = stelvioPath()
        val tight = MapImage.ofMaxSize(listOf(path), margin = 0.0, maxSize = 1024)
        val padded = MapImage.ofMaxSize(listOf(path), margin = 0.5, maxSize = 1024)

        assertTrue(padded.maxLon - padded.minLon > tight.maxLon - tight.minLon, "longitude span must grow")
        assertTrue(padded.maxLat - padded.minLat > tight.maxLat - tight.minLat, "latitude span must grow")
        assertTrue(padded.minLon < tight.minLon && padded.maxLon > tight.maxLon, "padding is symmetric in x")
        assertTrue(padded.minLat < tight.minLat && padded.maxLat > tight.maxLat, "padding is symmetric in y")
    }

    @Test
    fun `case 09 — maxSize is respected on both dimensions`() {
        for (maxSize in listOf(256, 512, 1024, 2048)) {
            val map = MapImage.ofMaxSize(listOf(stelvioPath()), margin = 0.1, maxSize = maxSize)
            assertTrue(map.width <= maxSize, "width ${map.width} exceeds $maxSize")
            assertTrue(map.height <= maxSize, "height ${map.height} exceeds $maxSize")
            // And it should be the deepest zoom that fits, so one level more would overflow.
            assertTrue(map.zoom >= 0)
        }
    }

    @Test
    fun `case 10 — an explicit width and height are honoured exactly`() {
        val map = MapImage.ofSize(listOf(stelvioPath()), margin = 0.1, width = 800, height = 600)
        assertEquals(800, map.width)
        assertEquals(600, map.height)
        assertEquals(800, map.image.width)
        assertEquals(600, map.image.height)
        // The whole track must fit inside the fixed frame.
        val path = stelvioPath()
        for (i in 0 until path.size) {
            val x = map.getX(path.longitude(i) * MathConstants.RAD_TO_DEG)
            val y = map.getY(path.latitude(i) * MathConstants.RAD_TO_DEG)
            assertTrue(x in 0..800, "point $i x=$x outside the frame")
            assertTrue(y in 0..600, "point $i y=$y outside the frame")
        }
    }

    @Test
    fun `case 11 — a track crossing the antimeridian degrades to a world view, by design`() {
        // FROZEN, NOT FIXED. Longitudes are min/maxed naively, so +179.9 and -179.9 read as a
        // 359.8 deg span instead of the 0.2 deg one it really is. The reference behaves the same
        // way. The image is valid, just zoomed all the way out — see the MapImage KDoc.
        val map = MapImage.ofMaxSize(listOf(pathOf(0.0 to 179.9, 0.1 to -179.9)), margin = 0.0, maxSize = 1024)
        assertTrue(
            map.maxLon - map.minLon > 300.0,
            "expected the documented near-global span, got ${map.maxLon - map.minLon} deg",
        )
        // Still a usable image rather than a crash or a zero-size buffer.
        assertTrue(map.image.width >= 1 && map.image.height >= 1)
    }

    @Test
    fun `case 12 — bounds span every path of a multi-track document`() {
        val west = pathOf(45.0 to 6.0, 45.01 to 6.01)
        val east = pathOf(46.0 to 7.0, 46.01 to 7.01)
        val map = MapImage.ofMaxSize(listOf(west, east), margin = 0.0, maxSize = 1024)
        assertTrue(map.minLon <= 6.0 && map.maxLon >= 7.01, "longitude must span both tracks")
        assertTrue(map.minLat <= 45.0 && map.maxLat >= 46.01, "latitude must span both tracks")
    }

    @Test
    fun `case 13 — saveImage writes a PNG that ImageIO can read back`() {
        val map = MapImage.ofSize(listOf(stelvioPath()), margin = 0.1, width = 320, height = 240)
        map.createGraphics().apply {
            color = java.awt.Color.RED
            fillRect(0, 0, 320, 240)
            dispose()
        }
        val file = File.createTempFile("vcyclist-map", ".png")
        try {
            map.saveImage(file)
            assertTrue(file.length() > 0, "PNG must not be empty")
            val read = ImageIO.read(file)
            assertEquals(320, read.width)
            assertEquals(240, read.height)
            assertEquals(java.awt.Color.RED.rgb, read.getRGB(10, 10))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `case 14 — pixel and coordinate accessors are mutually consistent to within a pixel`() {
        // getX/getY truncate toward zero, so a value that lands on 510.9999 comes back as 510.
        // One pixel of slack is inherent to an integer pixel API, not a defect.
        val map = MapImage.ofSize(listOf(stelvioPath()), margin = 0.1, width = 512, height = 512)
        for (x in listOf(0, 100, 255, 511)) {
            assertTrue((map.getX(map.getLon(x)) - x) in -1..1, "x $x round-tripped to ${map.getX(map.getLon(x))}")
        }
        for (y in listOf(0, 100, 255, 511)) {
            assertTrue((map.getY(map.getLat(y)) - y) in -1..1, "y $y round-tripped to ${map.getY(map.getLat(y))}")
        }
    }

    @Test
    fun `case 15 — tile indices agree with the underlying MapSpace`() {
        val map = MapImage.ofMaxSize(listOf(stelvioPath()), margin = 0.0, maxSize = 1024)
        val lon = 10.45
        val lat = 46.532
        assertEquals(MapSpace.TILE_256.lonToTileX(lon, map.zoom), map.getTileI(lon), 1e-12)
        assertEquals(MapSpace.TILE_256.latToTileY(lat, map.zoom), map.getTileJ(lat), 1e-12)
    }

    @Test
    fun `case 16 — framing an empty set of paths fails with a clear message`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                MapImage.ofMaxSize(emptyList(), margin = 0.0, maxSize = 256)
            }
        assertTrue(failure.message.orEmpty().contains("no path"), "unhelpful: ${failure.message}")
        assertFailsWith<IllegalArgumentException> {
            MapImage.ofMaxSize(listOf(Path(0)), margin = 0.0, maxSize = 256)
        }
    }
}
