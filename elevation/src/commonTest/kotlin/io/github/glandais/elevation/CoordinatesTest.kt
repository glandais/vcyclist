package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinatesTest {
    @Test
    fun `LatLon defaults elevation to null`() {
        val p = LatLon(48.8566, 2.3522)
        assertNull(p.elevation)
    }

    @Test
    fun `LatLon can carry elevation`() {
        val p = LatLon(48.8566, 2.3522, 35.0)
        assertEquals(35.0, p.elevation)
    }

    @Test
    fun `toCoordinatesElevation defaults missing elevation to zero`() {
        val p: Coordinates = LatLon(0.0, 0.0)
        val withEle = p.toCoordinatesElevation()
        assertEquals(0.0, withEle.elevation)
    }

    @Test
    fun `toCoordinatesElevation preserves existing elevation`() {
        val p: Coordinates = LatLon(0.0, 0.0, 42.5)
        val withEle = p.toCoordinatesElevation()
        assertEquals(42.5, withEle.elevation)
    }

    @Test
    fun `LatLonElevation always exposes a non-null elevation`() {
        val p = LatLonElevation(45.0, 6.0, 1800.0)
        val asBase: Coordinates = p
        assertEquals(1800.0, asBase.elevation)
    }
}
