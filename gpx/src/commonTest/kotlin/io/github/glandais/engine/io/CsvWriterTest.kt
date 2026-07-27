package io.github.glandais.engine.io

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CsvWriterTest {
    // ---- 1. empty path : header only -----------------------------------------

    @Test
    fun empty_path_writes_header_only_no_data_rows() {
        val csv = CsvWriter.write(Path(0))
        val lines = csv.split("\n")
        assertEquals(1, lines.size)
        assertEquals(PointField.COUNT, lines[0].split(",").size)
    }

    // ---- 2. all fields, 3 points ----------------------------------------------

    @Test
    fun all_fields_writes_header_plus_one_row_per_point_36_columns() {
        val p = Path(3)
        val csv = CsvWriter.write(p)
        val lines = csv.split("\n")
        assertEquals(4, lines.size)
        for (line in lines) {
            assertEquals(PointField.COUNT, line.split(",").size)
        }
    }

    // ---- 3. restricted field list, order preserved ----------------------------

    @Test
    fun restricted_fields_are_exported_in_requested_order() {
        val p = Path(1)
        p.setElevation(0, 100.0)
        p.setSpeed(0, 5.0)
        val csv =
            CsvWriter.write(
                p,
                CsvOptions(fields = listOf(PointField.SPEED, PointField.ELEVATION, PointField.DISTANCE)),
            )
        val lines = csv.split("\n")
        assertEquals(listOf("speed (m/s)", "elevation (meters)", "distance (meters)"), lines[0].split(","))
        assertEquals(listOf("5", "100", "0"), lines[1].split(","))
    }

    // ---- 4. units in header -----------------------------------------------------

    @Test
    fun units_in_header_true_appends_unit_in_parentheses() {
        val csv = CsvWriter.write(Path(0), CsvOptions(fields = listOf(PointField.ELEVATION)))
        assertEquals("elevation (meters)", csv)
    }

    // ---- 5. units in header false -------------------------------------------

    @Test
    fun units_in_header_false_is_bare_prop_name() {
        val csv =
            CsvWriter.write(
                Path(0),
                CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false),
            )
        assertEquals("elevation", csv)
    }

    // ---- 6. NaN -> empty cell ---------------------------------------------------

    @Test
    fun nan_field_renders_as_empty_cell() {
        val p = Path(1)
        // ELEVATION is left at its default 0.0 ; use a field guaranteed NaN-able via arithmetic.
        p.setElevation(0, Double.NaN)
        val csv = CsvWriter.write(p, CsvOptions(fields = listOf(PointField.ELEVATION)))
        val lines = csv.split("\n")
        assertEquals("elevation (meters)", lines[0])
        assertEquals("", lines[1])
    }

    // ---- 7. custom separator -----------------------------------------------------

    @Test
    fun custom_separator_is_respected() {
        val p = Path(1)
        p.setElevation(0, 100.0)
        p.setSpeed(0, 5.0)
        val csv =
            CsvWriter.write(
                p,
                CsvOptions(
                    fields = listOf(PointField.ELEVATION, PointField.SPEED),
                    separator = ';',
                    unitsInHeader = false,
                ),
            )
        assertEquals("elevation;speed\n100;5", csv)
    }

    // ---- 8. fixed decimals --------------------------------------------------------

    @Test
    fun decimals_option_forces_exact_fractional_digit_count() {
        val p = Path(1)
        p.setElevation(0, 100.0)
        val csv =
            CsvWriter.write(
                p,
                CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false, decimals = 2),
            )
        assertEquals("elevation\n100.00", csv)
    }

    // ---- 9. decimals = null : stable across the 4 targets --------------------

    @Test
    fun decimals_null_on_one_point_zero_is_platform_stable_not_1_dot_0() {
        val p = Path(1)
        p.setElevation(0, 1.0)
        val csv =
            CsvWriter.write(p, CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false))
        assertEquals("elevation\n1", csv)
    }

    // ---- 10. tiny value : no exponential notation -----------------------------

    @Test
    fun tiny_value_never_renders_in_exponential_notation() {
        val p = Path(1)
        p.setElevation(0, 1e-9)
        val csv =
            CsvWriter.write(p, CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false))
        val value = csv.split("\n")[1]
        assertFalse(value.contains("e", ignoreCase = true))
        assertEquals(1e-9, value.toDouble())
    }

    // ---- 11. huge value : no exponential notation ------------------------------

    @Test
    fun huge_value_never_renders_in_exponential_notation() {
        val p = Path(1)
        p.setElevation(0, 1e12)
        val csv =
            CsvWriter.write(p, CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false))
        val value = csv.split("\n")[1]
        assertFalse(value.contains("e", ignoreCase = true))
        assertEquals(1e12, value.toDouble())
    }

    // ---- 12. CRLF line separator, including after the header -------------------

    @Test
    fun crlf_line_separator_applies_after_header_too() {
        val p = Path(2)
        val csv =
            CsvWriter.write(
                p,
                CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false, lineSeparator = "\r\n"),
            )
        assertEquals("elevation\r\n0\r\n0", csv)
    }

    // ---- 13. header containing the separator gets quoted ------------------------

    @Test
    fun header_containing_separator_is_quoted() {
        // unitsInHeader renders "elevation (meters)", which contains a space : picking
        // `separator = ' '` is an unusual but legal CsvOptions and must not silently split the
        // header into two cells.
        val csv =
            CsvWriter.write(
                Path(0),
                CsvOptions(fields = listOf(PointField.ELEVATION), separator = ' ', unitsInHeader = true),
            )
        assertEquals("\"elevation (meters)\"", csv)
    }

    // ---- extra : negative numbers and rounding ----------------------------------

    @Test
    fun negative_value_keeps_its_sign() {
        val p = Path(1)
        p.setElevation(0, -12.5)
        val csv =
            CsvWriter.write(
                p,
                CsvOptions(fields = listOf(PointField.ELEVATION), unitsInHeader = false, decimals = 1),
            )
        assertEquals("elevation\n-12.5", csv)
    }
}
