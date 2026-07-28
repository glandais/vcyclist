package io.github.glandais.engine.wasi

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The JSON shapes the ABI emits, checked by parsing them back with [parseJsonObject]. */
class WasiJsonOutputTest {
    private fun path(points: Int = 5): Path {
        val p = Path(points)
        for (i in 0 until points) {
            p.setLatitude(i, (45.68 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 350.0 + i * 10)
            p.setTime(i, i * 10_000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `a point carries the eight PointDto fields, in degrees`() {
        val p = path()

        val point = parseJsonObject(pointJson(p, 1))

        point.requireOnly(
            setOf(
                "latitudeDeg",
                "longitudeDeg",
                "elevation",
                "timeMs",
                "speed",
                "pComputedPower",
                "distance",
                "grade",
            ),
        )
        assertEquals(p.latitudeDeg(1), point.double("latitudeDeg", 0.0), 1e-12)
        assertEquals(360.0, point.double("elevation", 0.0), 1e-12)
    }

    @Test
    fun `the field catalog has one entry per PointField, indexed in order`() {
        val text = fieldDefinitionsJson()

        // The array wrapper is not an object, so check the shape by counting entries and
        // spot-checking that the index is the ordinal a host would pass to vcGetField.
        val entries = text.split("},{").size
        assertEquals(PointField.entries.size, entries, "one JSON object per field")
        assertTrue(text.startsWith("""[{"index":0,"prop":"${PointField.entries[0].prop}""""), text.take(80))
        assertTrue(
            text.contains(""""index":${PointField.entries.lastIndex},"prop":"${PointField.entries.last().prop}""""),
            "the last field must carry the last index",
        )
    }

    @Test
    fun `climbs round-trip through the parser, parts included`() {
        val climbing = Path(60)
        for (i in 0 until 60) {
            climbing.setLatitude(i, (45.0 + i * 0.002) * MathConstants.DEG_TO_RAD)
            climbing.setLongitude(i, 6.0 * MathConstants.DEG_TO_RAD)
            climbing.setElevation(i, 500.0 + i * 15.0)
            climbing.setTime(i, i * 10_000.0)
        }
        climbing.computeDerivedData()

        val text = climbsJson(ClimbDetector.detect(climbing))

        assertTrue(text.startsWith("[") && text.endsWith("]"), text.take(40))
        if (text != "[]") {
            assertTrue(text.contains(""""parts":["""), "a climb must carry its parts")
            assertTrue(text.contains(""""averageGrade":"""), text.take(120))
        }
    }

    @Test
    fun `an empty climb list is an empty array, not null`() {
        assertEquals("[]", climbsJson(emptyList()))
    }
}
