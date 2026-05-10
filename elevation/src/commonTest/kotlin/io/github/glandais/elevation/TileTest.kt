package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val EPS = 1e-9

private fun encodeTerrarium(elevationMeters: Int): Triple<Int, Int, Int> {
    val raw = elevationMeters + 32768
    val r = (raw shr 8) and 0xFF
    val g = raw and 0xFF
    return Triple(r, g, 0)
}

private fun makeRawTile(
    width: Int,
    height: Int,
    elevationAt: Map<Pair<Int, Int>, Int>,
): RawTile {
    val rgba = ByteArray(width * height * 4)
    val (defaultR, defaultG, defaultB) = encodeTerrarium(0)
    for (i in 0 until width * height) {
        rgba[i * 4] = defaultR.toByte()
        rgba[i * 4 + 1] = defaultG.toByte()
        rgba[i * 4 + 2] = defaultB.toByte()
        rgba[i * 4 + 3] = 255.toByte()
    }
    for ((pos, elev) in elevationAt) {
        val (r, g, b) = encodeTerrarium(elev)
        val offset = (pos.second * width + pos.first) * 4
        rgba[offset] = r.toByte()
        rgba[offset + 1] = g.toByte()
        rgba[offset + 2] = b.toByte()
        rgba[offset + 3] = 255.toByte()
    }
    return RawTile(width, height, rgba)
}

class TileTest {
    @Test
    fun `decodeTerrariumElevation - sea level sentinel`() {
        assertEquals(0.0, Tile.decodeTerrariumElevation(128, 0, 0))
    }

    @Test
    fun `decodeTerrariumElevation - 1000 m`() {
        assertEquals(1000.0, Tile.decodeTerrariumElevation(131, 232, 0))
    }

    @Test
    fun `decodeTerrariumElevation - max RGB ~ 32767dot996`() {
        val v = Tile.decodeTerrariumElevation(255, 255, 255)
        assertTrue((v - 32768.0).absoluteValue < 0.01, "v=$v")
    }

    @Test
    fun `decodeTerrariumElevation - all zero is min`() {
        assertEquals(-32768.0, Tile.decodeTerrariumElevation(0, 0, 0))
    }

    @Test
    fun `decodeTerrariumElevation - rounds to two decimals`() {
        // raw = 128 * 256 + 30 + 200/256 - 32768
        //     = 32768 + 30 + 0.78125 - 32768 = 30.78125 → 30.78 after rounding
        assertEquals(30.78, Tile.decodeTerrariumElevation(128, 30, 200))
    }

    @Test
    fun `Tile constructor from RawTile`() {
        val raw = makeRawTile(4, 4, mapOf((0 to 0) to 100))
        val tile = Tile(raw)
        assertEquals(4, tile.width)
        assertEquals(4, tile.height)
        assertEquals(100.0, tile.getElevation(Pixel(TileCoordinates(0, 0, 12), 0, 0)))
    }

    @Test
    fun `Tile getElevation reads encoded elevation at pixel`() {
        val raw = makeRawTile(4, 4, mapOf((2 to 1) to 1500))
        val tile = Tile(raw)
        assertEquals(1500.0, tile.getElevation(Pixel(TileCoordinates(0, 0, 12), 2, 1)))
    }

    @Test
    fun `Tile getElevation caches result (second call does not increment decodeCount)`() {
        val raw = makeRawTile(4, 4, mapOf((1 to 1) to 250))
        val tile = Tile(raw)
        val px = Pixel(TileCoordinates(0, 0, 12), 1, 1)
        val first = tile.getElevation(px)
        val countAfterFirst = tile.decodeCount
        val second = tile.getElevation(px)
        assertEquals(first, second)
        assertEquals(countAfterFirst, tile.decodeCount, "decodeCount unexpectedly increased: ${tile.decodeCount}")
    }

    @Test
    fun `Tile getElevation rejects negative x with TS message`() {
        val tile = Tile(makeRawTile(4, 4, emptyMap()))
        val ex =
            assertFailsWith<IllegalArgumentException> {
                tile.getElevation(Pixel(TileCoordinates(0, 0, 12), -1, 0))
            }
        assertEquals("Invalid x position: -1. Must be between 0 and 3", ex.message)
    }

    @Test
    fun `Tile getElevation rejects x at width with TS message`() {
        val tile = Tile(makeRawTile(256, 256, emptyMap()))
        val ex =
            assertFailsWith<IllegalArgumentException> {
                tile.getElevation(Pixel(TileCoordinates(0, 0, 12), 256, 0))
            }
        assertEquals("Invalid x position: 256. Must be between 0 and 255", ex.message)
    }

    @Test
    fun `Tile getElevation rejects y at height with TS message`() {
        val tile = Tile(makeRawTile(256, 256, emptyMap()))
        val ex =
            assertFailsWith<IllegalArgumentException> {
                tile.getElevation(Pixel(TileCoordinates(0, 0, 12), 0, 256))
            }
        assertEquals("Invalid y position: 256. Must be between 0 and 255", ex.message)
    }

    @Test
    fun `Tile getElevation rejects negative y`() {
        val tile = Tile(makeRawTile(4, 4, emptyMap()))
        val ex =
            assertFailsWith<IllegalArgumentException> {
                tile.getElevation(Pixel(TileCoordinates(0, 0, 12), 0, -1))
            }
        assertEquals("Invalid y position: -1. Must be between 0 and 3", ex.message)
    }

    @Test
    fun `decodeCount accumulates across distinct pixels`() {
        val raw = makeRawTile(4, 4, mapOf((0 to 0) to 10, (1 to 0) to 20, (2 to 0) to 30))
        val tile = Tile(raw)
        val t = TileCoordinates(0, 0, 12)
        tile.getElevation(Pixel(t, 0, 0))
        tile.getElevation(Pixel(t, 1, 0))
        tile.getElevation(Pixel(t, 2, 0))
        tile.getElevation(Pixel(t, 0, 0))
        tile.getElevation(Pixel(t, 1, 0))
        assertEquals(3, tile.decodeCount)
    }
}
