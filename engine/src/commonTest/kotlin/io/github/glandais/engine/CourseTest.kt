package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CourseTest {
    private fun newPath(): Path = Path(0)

    @Test
    fun course_uses_default_cyclist_and_bike_when_not_provided() {
        val path = newPath()
        val course = Course(path)
        assertEquals(Cyclist.DEFAULT, course.cyclist)
        assertEquals(Bike.DEFAULT, course.bike)
    }

    @Test
    fun course_retains_custom_cyclist_and_bike_overrides() {
        val path = newPath()
        val customCyclist = Cyclist(massKg = 70.0)
        val customBike = Bike(crr = 0.006)
        val course = Course(path, customCyclist, customBike)
        assertEquals(customCyclist, course.cyclist)
        assertEquals(customBike, course.bike)
    }

    @Test
    fun course_equality_holds_for_same_path_cyclist_bike() {
        val path = newPath()
        val cyclist = Cyclist(massKg = 85.0)
        val bike = Bike(crr = 0.005)
        val a = Course(path, cyclist, bike)
        val b = Course(path, cyclist, bike)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }
}
