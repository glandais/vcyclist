package io.github.glandais.fit

import com.garmin.fit.types.CoursePoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** [FitCoursePointType] mirrors the SDK's `CoursePoint` profile type, and must keep doing so. */
class FitCoursePointTypeTest {
    @Test
    fun `every entry maps to the SDK value of the same name`() {
        for (entry in FitCoursePointType.entries) {
            val sdk = CoursePoint.fromValue(entry.value.toUByte())
            assertNotEquals(CoursePoint.INVALID, sdk, "${entry.name} (${entry.value})")
            assertEquals(entry.name, sdk.name)
        }
    }

    @Test
    fun `the enum covers the whole profile type`() {
        // CoursePoint has an INVALID sentinel of its own, which this enum leaves out. A profile
        // bump that adds a type fails here, loudly.
        assertEquals(CoursePoint.entries.size - 1, FitCoursePointType.entries.size)
    }
}
