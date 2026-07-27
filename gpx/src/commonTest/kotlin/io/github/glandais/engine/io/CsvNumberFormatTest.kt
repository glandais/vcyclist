package io.github.glandais.engine.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CsvNumberFormatTest {
    @Test
    fun nan_formats_as_empty_string() {
        assertEquals("", CsvNumberFormat.format(Double.NaN))
    }

    @Test
    fun integral_value_has_no_trailing_dot_zero() {
        assertEquals("1", CsvNumberFormat.format(1.0))
        assertEquals("0", CsvNumberFormat.format(0.0))
        assertEquals("-2", CsvNumberFormat.format(-2.0))
    }

    @Test
    fun fixed_decimals_pads_and_rounds() {
        assertEquals("1.50", CsvNumberFormat.format(1.5, decimals = 2))
        assertEquals("1.00", CsvNumberFormat.format(1.0, decimals = 2))
        assertEquals("0.10", CsvNumberFormat.format(0.1, decimals = 2))
    }

    @Test
    fun shortest_round_trip_recovers_common_fractions() {
        assertEquals(0.1, CsvNumberFormat.format(0.1).toDouble())
        assertEquals(3.14159, CsvNumberFormat.format(3.14159).toDouble())
    }

    @Test
    fun never_uses_exponential_notation() {
        val tiny = CsvNumberFormat.format(1e-9)
        val huge = CsvNumberFormat.format(1e12)
        assertFalse(tiny.contains("e", ignoreCase = true))
        assertFalse(huge.contains("e", ignoreCase = true))
        assertEquals(1e-9, tiny.toDouble())
        assertEquals(1e12, huge.toDouble())
    }

    @Test
    fun negative_zero_does_not_render_a_minus_sign() {
        assertEquals("0", CsvNumberFormat.format(-0.0))
    }
}
