package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EPS = 1e-12

class ElevationFunctionsTest {
    // -------- normalizePixel --------

    @Test
    fun `normalizePixel - normal pixel coordinates`() {
        val p = Pixel(TileCoordinates(100, 200, 12), 128, 64)
        assertEquals(p, ElevationFunctions.normalizePixel(p, 256))
    }

    @Test
    fun `normalizePixel - negative x`() {
        val p = Pixel(TileCoordinates(100, 200, 12), -1, 128)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(255, r.x)
        assertEquals(99, r.tile.x)
        assertEquals(128, r.y)
        assertEquals(200, r.tile.y)
    }

    @Test
    fun `normalizePixel - negative y`() {
        val p = Pixel(TileCoordinates(100, 200, 12), 128, -1)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(128, r.x)
        assertEquals(100, r.tile.x)
        assertEquals(255, r.y)
        assertEquals(199, r.tile.y)
    }

    @Test
    fun `normalizePixel - x at tileSize`() {
        val p = Pixel(TileCoordinates(100, 200, 12), 256, 128)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(0, r.x)
        assertEquals(101, r.tile.x)
        assertEquals(128, r.y)
        assertEquals(200, r.tile.y)
    }

    @Test
    fun `normalizePixel - y at tileSize`() {
        val p = Pixel(TileCoordinates(100, 200, 12), 128, 256)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(128, r.x)
        assertEquals(100, r.tile.x)
        assertEquals(0, r.y)
        assertEquals(201, r.tile.y)
    }

    @Test
    fun `normalizePixel - clamps to zero on negative tile at z=2`() {
        val p = Pixel(TileCoordinates(0, 0, 2), -10, -10)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(0, r.tile.x)
        assertEquals(0, r.tile.y)
        assertEquals(2, r.tile.z)
    }

    @Test
    fun `normalizePixel - clamps to max tile at z=2`() {
        val p = Pixel(TileCoordinates(3, 3, 2), 300, 300)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(3, r.tile.x)
        assertEquals(3, r.tile.y)
        assertEquals(2, r.tile.z)
    }

    @Test
    fun `normalizePixel - mixed under- and over-flow at z=4`() {
        val p = Pixel(TileCoordinates(8, 8, 4), -10, 300)
        val r = ElevationFunctions.normalizePixel(p, 256)
        assertEquals(246, r.x)
        assertEquals(7, r.tile.x)
        assertEquals(44, r.y)
        assertEquals(9, r.tile.y)
    }

    @Test
    fun `normalizePixel - zoom boundary edges`() {
        val p0 = Pixel(TileCoordinates(0, 0, 0), -1, -1)
        val r0 = ElevationFunctions.normalizePixel(p0, 256)
        assertEquals(0, r0.tile.x)
        assertEquals(0, r0.tile.y)

        val p15 = Pixel(TileCoordinates(32767, 32767, 15), 256, 256)
        val r15 = ElevationFunctions.normalizePixel(p15, 256)
        assertEquals(32767, r15.tile.x)
        assertEquals(32767, r15.tile.y)
    }

    // -------- validators --------

    @Test
    fun `isValidLatitude bounds`() {
        assertTrue(ElevationFunctions.isValidLatitude(0.0))
        assertTrue(ElevationFunctions.isValidLatitude(85.0511))
        assertTrue(ElevationFunctions.isValidLatitude(-85.0511))
        assertFalse(ElevationFunctions.isValidLatitude(85.0512))
        assertFalse(ElevationFunctions.isValidLatitude(-85.0512))
    }

    @Test
    fun `isValidLongitude bounds`() {
        assertTrue(ElevationFunctions.isValidLongitude(0.0))
        assertTrue(ElevationFunctions.isValidLongitude(180.0))
        assertTrue(ElevationFunctions.isValidLongitude(-180.0))
        assertFalse(ElevationFunctions.isValidLongitude(180.0001))
        assertFalse(ElevationFunctions.isValidLongitude(-180.0001))
    }

    @Test
    fun `isValidZoomLevel bounds`() {
        assertTrue(ElevationFunctions.isValidZoomLevel(0))
        assertTrue(ElevationFunctions.isValidZoomLevel(15))
        assertFalse(ElevationFunctions.isValidZoomLevel(-1))
        assertFalse(ElevationFunctions.isValidZoomLevel(16))
    }

    @Test
    fun `degToRad - 180 degrees equals PI`() {
        assertTrue((ElevationFunctions.degToRad(180.0) - PI).absoluteValue < EPS)
    }

    // -------- toTileCoordinates / Float / toPixel --------

    @Test
    fun `toTileCoordinatesFloat - lat out of bounds throws with the documented message`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationFunctions.toTileCoordinatesFloat(LatLon(86.0, 0.0), 12)
            }
        assertEquals("Invalid latitude: 86. Must be between -85.0511 and 85.0511", ex.message)
    }

    @Test
    fun `toTileCoordinatesFloat - negative lat out of bounds`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationFunctions.toTileCoordinatesFloat(LatLon(-86.0, 0.0), 12)
            }
        assertEquals("Invalid latitude: -86. Must be between -85.0511 and 85.0511", ex.message)
    }

    @Test
    fun `toTileCoordinatesFloat - lon out of bounds throws with the documented message`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationFunctions.toTileCoordinatesFloat(LatLon(0.0, 181.0), 12)
            }
        assertEquals("Invalid longitude: 181. Must be between -180 and 180", ex.message)
    }

    @Test
    fun `toTileCoordinatesFloat - zoom out of bounds throws with the documented message`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                ElevationFunctions.toTileCoordinatesFloat(LatLon(0.0, 0.0), -1)
            }
        assertEquals("Invalid zoom level: -1. Must be between 0 and 15", ex.message)

        val ex2 =
            assertFailsWith<IllegalArgumentException> {
                ElevationFunctions.toTileCoordinatesFloat(LatLon(0.0, 0.0), 16)
            }
        assertEquals("Invalid zoom level: 16. Must be between 0 and 15", ex2.message)
    }

    @Test
    fun `toTileCoordinatesFloat - world center at z=0`() {
        val r = ElevationFunctions.toTileCoordinatesFloat(LatLon(0.0, 0.0), 0)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
        assertEquals(0, r.z)
        assertTrue((r.xFloat - 0.5).absoluteValue < EPS, "xFloat=${r.xFloat}")
        assertTrue((r.yFloat - 0.5).absoluteValue < EPS, "yFloat=${r.yFloat}")
    }

    @Test
    fun `toTileCoordinatesFloat - world center at z=12`() {
        val n = 1 shl 12
        val r = ElevationFunctions.toTileCoordinatesFloat(LatLon(0.0, 0.0), 12)
        assertEquals(n / 2, r.x)
        assertEquals(n / 2, r.y)
        assertEquals(12, r.z)
        assertTrue((r.xFloat - n / 2.0).absoluteValue < EPS)
        assertTrue((r.yFloat - n / 2.0).absoluteValue < EPS)
    }

    @Test
    fun `toTileCoordinatesFloat - upper bounds accepted`() {
        val r = ElevationFunctions.toTileCoordinatesFloat(LatLon(85.0511, 180.0), 0)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
        assertEquals(0, r.z)
    }

    @Test
    fun `toTileCoordinates matches toTileCoordinatesFloat xy`() {
        for (z in listOf(0, 5, 12, 15)) {
            val pts = listOf(LatLon(0.0, 0.0), LatLon(45.0, 90.0), LatLon(-30.0, -120.0))
            for (p in pts) {
                val a = ElevationFunctions.toTileCoordinatesFloat(p, z)
                val b = ElevationFunctions.toTileCoordinates(p, z)
                assertEquals(TileCoordinates(a.x, a.y, a.z), b)
            }
        }
    }

    @Test
    fun `toPixel - center of world at z=12 with tileSize 256`() {
        val r = ElevationFunctions.toPixel(LatLon(0.0, 0.0), 12, 256)
        assertEquals(2048, r.tile.x)
        assertEquals(2048, r.tile.y)
        assertEquals(12, r.tile.z)
        assertEquals(0, r.x)
        assertEquals(0, r.y)
    }
}
