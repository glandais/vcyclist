package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointFieldTest {
    @Test
    fun `39 fields exactly`() {
        assertEquals(39, PointField.entries.size)
        assertEquals(39, PointField.COUNT)
    }

    @Test
    fun `ordinals are unique`() {
        val ordinals = PointField.entries.map { it.ordinal }
        assertEquals(39, ordinals.toSet().size)
    }

    @Test
    fun `props are unique and non-blank`() {
        val props = PointField.entries.map { it.prop }
        assertEquals(39, props.toSet().size)
        assertTrue(props.all { it.isNotBlank() })
    }

    @Test
    fun `index equals ordinal`() {
        for (f in PointField.entries) assertEquals(f.ordinal, f.index)
    }

    @Test
    fun `latitude is first`() {
        assertEquals(0, PointField.LATITUDE.ordinal)
    }

    @Test
    fun `cadence is last`() {
        assertEquals(35, PointField.CADENCE.ordinal)
    }

    /**
     * Pins the exact set of NaN-defaulted fields. Flagging a field is a wire-format-visible
     * decision — every consumer of that slot must gate on `isNaN()` — so it should never happen
     * as a side effect of adding a field.
     */
    @Test
    fun `nanDefault is declared on exactly the intended fields`() {
        assertEquals(
            listOf("trajectoryCurvature"),
            PointField.entries.filter { it.nanDefault }.map { it.prop },
        )
    }

    @Test
    fun `byProp round-trip`() {
        for (f in PointField.entries) {
            assertEquals(f, PointField.byProp(f.prop))
        }
        assertNull(PointField.byProp("noSuchField"))
    }

    @Test
    fun `category groupings match TS structure`() {
        assertEquals(4, PointField.byCategory(PointFieldCategory.COORDINATES).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.TEMPORAL).size)
        assertEquals(1, PointField.byCategory(PointFieldCategory.ANGLES).size)
        assertEquals(1, PointField.byCategory(PointFieldCategory.ELEVATION).size)
        assertEquals(1, PointField.byCategory(PointFieldCategory.GRADE).size)
        assertEquals(2, PointField.byCategory(PointFieldCategory.RADIUS).size)
        assertEquals(1, PointField.byCategory(PointFieldCategory.AERO_COEF).size)
        assertEquals(2, PointField.byCategory(PointFieldCategory.CYCLIST_WIND).size)
        assertEquals(5, PointField.byCategory(PointFieldCategory.POWER_PHYSICS).size)
        assertEquals(6, PointField.byCategory(PointFieldCategory.POWER_CYCLIST).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.POWER_POST).size)
        assertEquals(4, PointField.byCategory(PointFieldCategory.SPEED).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.ENVIRONMENTAL).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.PHYSIOLOGICAL).size)
    }

    @Test
    fun `all fields are partitioned across categories`() {
        val sum = PointFieldCategory.entries.sumOf { PointField.byCategory(it).size }
        assertEquals(39, sum)
    }

    @Test
    fun `category coordinates order matches TS`() {
        assertEquals(
            listOf("latitude", "longitude", "distance", "dx"),
            PointField.byCategory(PointFieldCategory.COORDINATES).map { it.prop },
        )
    }

    @Test
    fun `angle fields exposed in radians`() {
        val expected =
            setOf(
                PointField.LATITUDE,
                PointField.LONGITUDE,
                PointField.BEARING,
                PointField.WIND_BEARING,
                PointField.WIND_ALPHA,
                PointField.WIND_DIRECTION,
            )
        assertEquals(expected, PointField.entries.filter { it.anglesInRadians }.toSet())
    }

    @Test
    fun `notSelectable fields are latitude, longitude, time`() {
        val expected = setOf(PointField.LATITUDE, PointField.LONGITUDE, PointField.TIME)
        assertEquals(expected, PointField.entries.filter { it.notSelectable }.toSet())
    }

    @Test
    fun `units belong to a closed set`() {
        val allowed =
            setOf(
                "radians",
                "meters",
                "ms",
                "%",
                "m/s",
                "watts",
                "celsius",
                "bpm",
                "rpm",
                "aero",
                "joules",
                "1/m",
            )
        for (f in PointField.entries) {
            assertTrue(f.unit in allowed, "Unexpected unit '${f.unit}' for $f")
        }
    }

    @Test
    fun `all shortDescriptions are non-blank`() {
        for (f in PointField.entries) assertTrue(f.shortDescription.isNotBlank())
    }

    @Test
    fun `14 categories with unique ids`() {
        assertEquals(14, PointFieldCategory.entries.size)
        assertEquals(
            14,
            PointFieldCategory.entries
                .map { it.id }
                .toSet()
                .size,
        )
    }

    @Test
    fun `POWER enum maps to pComputedPower prop`() {
        assertEquals("pComputedPower", PointField.POWER.prop)
        assertEquals(PointField.POWER, PointField.byProp("pComputedPower"))
    }
}
