package io.github.glandais.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Web Mercator projection. Pure arithmetic, so every expectation is derived, not recorded. */
class MapSpaceTest {
    private val space = MapSpace.TILE_256

    @Test
    fun `case 01 — the origin sits at the centre of the single zoom-0 tile`() {
        // At zoom 0 the world is one 256 px tile; (0, 0) degrees is its exact centre.
        assertEquals(128.0, space.lonToX(0.0, 0), 1e-9)
        assertEquals(128.0, space.latToY(0.0, 0), 1e-9)
        assertEquals(256, space.maxPixels(0))
    }

    @Test
    fun `case 02 — longitude round-trips through pixel space away from the clamped edge`() {
        // The eastern clamp (case 07) costs precision within one pixel of +180 deg, which at
        // zoom 0 is 1.4 deg wide. Away from it the round-trip is exact.
        for (lon in listOf(-179.9, -90.0, -1.5, 0.0, 6.396115, 90.0, 170.0)) {
            for (zoom in listOf(0, 5, 12, 18)) {
                val back = space.xToLon(space.lonToX(lon, zoom), zoom)
                assertEquals(lon, back, 1e-9, "lon $lon at zoom $zoom")
            }
        }
    }

    @Test
    fun `case 03 — latitude round-trips through pixel space`() {
        for (lat in listOf(-84.0, -45.0, 0.0, 45.680697, 60.0, 84.0)) {
            for (zoom in listOf(0, 5, 12, 18)) {
                val back = space.yToLat(space.latToY(lat, zoom), zoom)
                assertEquals(lat, back, 1e-9, "lat $lat at zoom $zoom")
            }
        }
    }

    @Test
    fun `case 04 — latitudes beyond the Mercator limits are clamped, not projected to infinity`() {
        // The projection diverges at the poles, so the renderer clamps rather than failing —
        // unlike `:elevation`, which throws, because a lookup should reject an impossible input
        // while an image should still render.
        val atLimit = space.latToY(MapSpace.MAX_LAT, 10)
        assertEquals(atLimit, space.latToY(89.9, 10), 1e-9, "north pole clamps to the limit")
        assertEquals(space.latToY(MapSpace.MIN_LAT, 10), space.latToY(-89.9, 10), 1e-9)
        assertTrue(atLimit.isFinite(), "clamped projection must be finite")
        // MAX_LAT is by definition the latitude where y = 0; floating point leaves it a hair
        // negative (-2e-10 at zoom 10), which is why the assertion is a tolerance, not `>= 0`.
        assertEquals(0.0, atLimit, 1e-6, "MAX_LAT is the top edge of the world")
    }

    @Test
    fun `case 05 — one zoom level deeper doubles the pixel coordinates`() {
        for (zoom in 0 until 18) {
            val lon = 6.396115
            val lat = 45.680697
            assertEquals(2 * space.lonToX(lon, zoom), space.lonToX(lon, zoom + 1), 1e-6, "x at zoom $zoom")
            assertEquals(2 * space.latToY(lat, zoom), space.latToY(lat, zoom + 1), 1e-6, "y at zoom $zoom")
        }
    }

    @Test
    fun `case 06 — world size is tileSize times two to the zoom`() {
        assertEquals(256, space.maxPixels(0))
        assertEquals(512, space.maxPixels(1))
        assertEquals(256 * 1024, space.maxPixels(10))
        // A different tile size scales everything.
        assertEquals(512, MapSpace(512).maxPixels(0))
    }

    @Test
    fun `case 07 — the eastern and southern edges are clamped inside the image`() {
        // The last pixel column is reserved, so +180 deg does not
        // land one pixel past the right edge. The cost is that longitudes within one pixel of
        // +180 collapse onto it — see case 02.
        val zoom = 10
        val mp = space.maxPixels(zoom)
        assertEquals(mp - 1.0, space.lonToX(180.0, zoom), 1e-9)
        assertEquals(mp - 1.0, space.latToY(-90.0, zoom), 1e-9)
        // At zoom 0 that clamp is a whole 1.4 deg wide, which is why case 02 stops at 170 deg.
        assertEquals(255.0, space.lonToX(179.9, 0), 1e-9)
    }

    @Test
    fun `case 08 — tile indices are pixel coordinates divided by the tile size`() {
        val zoom = 12
        val lon = 6.396115
        assertEquals(space.lonToX(lon, zoom) / 256.0, space.lonToTileX(lon, zoom), 1e-12)
        assertEquals(space.latToY(45.68, zoom) / 256.0, space.latToTileY(45.68, zoom), 1e-12)
    }

    @Test
    fun `case 09 — tile bounds enclose the tile's own corners`() {
        val zoom = 8
        val tileX = 133
        val tileY = 90
        val (minLon, minLat, maxLon, maxLat) = space.tileBounds(zoom, tileX, tileY).toList()
        assertTrue(minLon < maxLon, "longitude bounds ordered")
        assertTrue(minLat < maxLat, "latitude bounds ordered")
        // The tile's own index must come back from a point inside it.
        val midLon = (minLon + maxLon) / 2
        val midLat = (minLat + maxLat) / 2
        assertEquals(tileX, space.lonToTileX(midLon, zoom).toInt())
        assertEquals(tileY, space.latToTileY(midLat, zoom).toInt())
    }

    @Test
    fun `case 10 — an invalid zoom or tile size is rejected`() {
        assertFailsWith<IllegalArgumentException> { space.maxPixels(-1) }
        assertFailsWith<IllegalArgumentException> { space.maxPixels(MapSpace.MAX_ZOOM + 1) }
        assertFailsWith<IllegalArgumentException> { MapSpace(0) }
    }
}
