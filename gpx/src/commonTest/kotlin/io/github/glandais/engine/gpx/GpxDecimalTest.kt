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

    /**
     * Values whose shortest form needs all 17 digits. Kotlin/Wasm's `toDouble()` reads the 16-digit
     * rounding of each back as the same `Double`, but a correct reader does not, so a shortening
     * check that trusted it wrote the neighbouring value.
     */
    @Test
    fun `seventeen-digit values are not shortened past what reads back exactly`() {
        assertEquals("7.2489838096998644", GpxDecimal.format(Double.fromBits(4619847648623432671L)))
        assertEquals("95.02940298837581", GpxDecimal.format(Double.fromBits(4636387516685115044L)))
        assertEquals("-56.476190168273114", GpxDecimal.format(Double.fromBits(-4590226902389115789L)))
        assertEquals("0.00045255466353816807", GpxDecimal.format(Double.fromBits(4556983783701853282L)))
    }
}
