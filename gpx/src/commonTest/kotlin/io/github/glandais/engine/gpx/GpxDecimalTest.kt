package io.github.glandais.engine.gpx

import kotlin.test.Test
import kotlin.test.assertEquals

class GpxDecimalTest {
    @Test
    fun `small magnitudes are written without an exponent`() {
        assertEquals("-0.0005", GpxDecimal.format(-0.0005))
        assertEquals("0.0004", GpxDecimal.format(0.0004))
        assertEquals("0.0000001", GpxDecimal.format(1e-7))
    }

    @Test
    fun `integers and large magnitudes are plain digits, the same on every target`() {
        assertEquals("100", GpxDecimal.format(100.0))
        assertEquals("-2", GpxDecimal.format(-2.0))
        assertEquals("1000000000000000000000", GpxDecimal.format(1e21))
    }

    @Test
    fun `zero is written 0, whatever its sign`() {
        assertEquals("0", GpxDecimal.format(0.0))
        assertEquals("0", GpxDecimal.format(-0.0))
    }

    @Test
    fun `shortest round-trip digits are kept beyond twelve decimals`() {
        for (v in listOf(45.83260000000001, 6.8652, -0.1278, 350.1, 1.0 / 3.0, 123456.789e-9)) {
            val text = GpxDecimal.format(v)
            assertEquals(v, text.toDouble(), text)
        }
        assertEquals("45.83260000000001", GpxDecimal.format(45.83260000000001))
    }
}
