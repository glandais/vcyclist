package io.github.glandais.engine.io

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `commonTest` cannot embed a JSON parser (no multiplatform-portable one without a new
 * dependency), so these tests check the document shape via string assertions. The
 * `JSON.parse` acceptance test (case 9 of the task g07 matrix) lives in `jsTest` /
 * `wasmJsTest` — see `JsonWriterParseTest`.
 */
class JsonWriterTest {
    // ---- 1. empty path : size 0, empty series ----------------------------------

    @Test
    fun empty_path_has_size_zero_and_empty_series() {
        val json = JsonWriter.write(Path(0), JsonOptions(fields = listOf(PointField.ELEVATION)))
        assertTrue(json.contains("\"size\":0"))
        assertTrue(json.contains("\"elevation\":[]"))
    }

    // ---- 2. all fields, 3 points : 36 series of 3 values -----------------------

    @Test
    fun all_fields_produces_36_series_of_3_values_each() {
        val p = Path(3)
        val json = JsonWriter.write(p)
        for (field in PointField.entries) {
            assertTrue(json.contains("\"${field.prop}\":"), "missing series for ${field.prop}")
        }
        // Every field defaults to 0.0 ; each series must render exactly 3 comma-joined values.
        assertTrue(json.contains("\"distance\":[0,0,0]"))
    }

    // ---- 3. restricted field list : only requested series ----------------------

    @Test
    fun restricted_fields_only_emits_requested_series() {
        val p = Path(1)
        p.setElevation(0, 100.0)
        p.setSpeed(0, 5.0)
        val json =
            JsonWriter.write(
                p,
                JsonOptions(fields = listOf(PointField.SPEED, PointField.ELEVATION), includeMeta = false),
            )
        assertTrue(json.contains("\"speed\":[5]"))
        assertTrue(json.contains("\"elevation\":[100]"))
        assertFalse(json.contains("\"distance\":"))
    }

    // ---- 4. NaN -> null ----------------------------------------------------------

    @Test
    fun nan_value_renders_as_json_null() {
        val p = Path(1)
        p.setElevation(0, Double.NaN)
        val json = JsonWriter.write(p, JsonOptions(fields = listOf(PointField.ELEVATION), includeMeta = false))
        assertTrue(json.contains("\"elevation\":[null]"))
        assertFalse(json.contains("NaN"))
    }

    // ---- 5. Infinity -> null -------------------------------------------------------

    @Test
    fun infinite_value_renders_as_json_null() {
        val p = Path(2)
        p.setElevation(0, Double.POSITIVE_INFINITY)
        p.setElevation(1, Double.NEGATIVE_INFINITY)
        val json = JsonWriter.write(p, JsonOptions(fields = listOf(PointField.ELEVATION), includeMeta = false))
        assertTrue(json.contains("\"elevation\":[null,null]"))
        assertFalse(json.contains("Infinity"))
    }

    // ---- 6. includeMeta = false : no meta block --------------------------------

    @Test
    fun include_meta_false_omits_meta_block() {
        val json = JsonWriter.write(Path(1), JsonOptions(fields = listOf(PointField.ELEVATION), includeMeta = false))
        assertFalse(json.contains("\"meta\""))
    }

    // ---- 7. pretty = true : indented but same content --------------------------

    @Test
    fun pretty_is_indented_but_semantically_identical_to_compact() {
        val p = Path(2)
        p.setElevation(0, 12.5)
        p.setElevation(1, 13.0)
        val compact = JsonWriter.write(p, JsonOptions(fields = listOf(PointField.ELEVATION)))
        val pretty = JsonWriter.write(p, JsonOptions(fields = listOf(PointField.ELEVATION), pretty = true))
        assertTrue(pretty.contains("\n"))
        assertFalse(compact.contains("\n"))
        assertEquals(compact.replace(Regex("\\s"), ""), pretty.replace(Regex("\\s"), ""))
    }

    // ---- 8. meta aggregates match the Path's own fields ------------------------

    @Test
    fun meta_block_reports_paths_own_aggregates() {
        val p = Path(3)
        // computeDerivedData() recomputes distance from lat/lon (haversine) : feed real
        // coordinates so totalDistance ends up non-zero, rather than pre-setting distance.
        p.setLatitude(0, 0.7973324)
        p.setLatitude(1, 0.7973424)
        p.setLatitude(2, 0.7973524)
        p.setLongitude(0, 0.1116)
        p.setLongitude(1, 0.1116)
        p.setLongitude(2, 0.1116)
        p.setElevation(0, 100.0)
        p.setElevation(1, 112.0)
        p.setElevation(2, 105.0)
        p.setTime(0, 0.0)
        p.setTime(1, 1000.0)
        p.setTime(2, 5000.0)
        p.computeDerivedData()

        val json = JsonWriter.write(p, JsonOptions(fields = listOf(PointField.ELEVATION)))
        assertTrue(json.contains("\"totalDistance\":${CsvNumberFormat.format(p.totalDistance)}"))
        assertTrue(json.contains("\"durationMs\":${CsvNumberFormat.format(p.durationMs)}"))
        assertTrue(json.contains("\"elevationGain\":${CsvNumberFormat.format(p.elevationGain)}"))
        assertTrue(json.contains("\"elevationLoss\":${CsvNumberFormat.format(p.elevationLoss)}"))
        assertTrue(p.totalDistance > 0.0)
        assertTrue(p.durationMs > 0.0)
        assertTrue(p.elevationGain > 0.0)
    }

    // ---- extra : units block reflects PointField.unit --------------------------

    @Test
    fun meta_units_block_reflects_point_field_units() {
        val json = JsonWriter.write(Path(0), JsonOptions(fields = listOf(PointField.ELEVATION, PointField.SPEED)))
        assertTrue(json.contains("\"elevation\":\"meters\""))
        assertTrue(json.contains("\"speed\":\"m/s\""))
    }

    // ---- extra : decimals option applies to series values -----------------------

    @Test
    fun decimals_option_forces_exact_fractional_digit_count() {
        val p = Path(1)
        p.setElevation(0, 100.0)
        val json =
            JsonWriter.write(
                p,
                JsonOptions(fields = listOf(PointField.ELEVATION), includeMeta = false, decimals = 2),
            )
        assertTrue(json.contains("\"elevation\":[100.00]"))
    }

    // ---- extra : field/unit name escaping ---------------------------------------
    // No PointField name/unit needs escaping today, so this exercises JsonWriter's escaper
    // directly rather than indirectly through write().

    @Test
    fun string_escaping_handles_quotes_backslashes_and_control_chars() {
        assertEquals("a\\\"b", JsonWriter.escapeJsonString("a\"b"))
        assertEquals("a\\\\b", JsonWriter.escapeJsonString("a\\b"))
        assertEquals("a\\nb", JsonWriter.escapeJsonString("a\nb"))
        assertEquals("a\\tb", JsonWriter.escapeJsonString("a\tb"))
        assertEquals("a\\u0001b", JsonWriter.escapeJsonString("ab"))
    }
}
