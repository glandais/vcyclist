package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TilesTest {
    @Test
    fun `TileCoordinates equality and hashCode`() {
        val a = TileCoordinates(1, 2, 3)
        val b = TileCoordinates(1, 2, 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, TileCoordinates(1, 2, 4))
    }

    @Test
    fun `Pixel equality`() {
        val tile = TileCoordinates(10, 20, 12)
        val a = Pixel(tile, 100, 200)
        val b = Pixel(tile, 100, 200)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, Pixel(tile, 100, 201))
    }

    @Test
    fun `RGBColor equality`() {
        assertEquals(RGBColor(255, 128, 0), RGBColor(255, 128, 0))
        assertNotEquals(RGBColor(255, 128, 0), RGBColor(255, 128, 1))
    }
}
