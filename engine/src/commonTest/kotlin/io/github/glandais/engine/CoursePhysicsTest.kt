package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProviderDefault
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class CoursePhysicsTest {
    private fun anyPath() =
        Path(1).also {
            it.setLatitude(0, 0.0)
            it.setLongitude(0, 0.0)
        }

    @Test
    fun defaults_use_default_providers() {
        val cp = CoursePhysics(course = Course(anyPath()))
        assertSame(RhoProviderDefault, cp.rhoProvider)
        assertSame(AeroProviderConstant, cp.aeroProvider)
        assertSame(WindProviderNone, cp.windProvider)
    }

    @Test
    fun delegate_properties_expose_inner_course() {
        val path = anyPath()
        val cyclist = Cyclist(massKg = 75.0)
        val bike = Bike(crr = 0.005)
        val course = Course(path = path, cyclist = cyclist, bike = bike)
        val cp = CoursePhysics(course = course)
        assertSame(path, cp.path)
        assertSame(cyclist, cp.cyclist)
        assertSame(bike, cp.bike)
    }

    @Test
    fun equality_uses_data_class_semantics() {
        val courseA = Course(anyPath())
        val courseB = Course(anyPath())
        val cp1 = CoursePhysics(course = courseA)
        val cp2 = CoursePhysics(course = courseA)
        val cp3 = CoursePhysics(course = courseB)
        assertEquals(cp1, cp2)
        // Different inner Course (different Path instance) → not equal.
        assertNotEquals(cp1, cp3)
    }

    @Test
    fun custom_providers_override_defaults() {
        val cp =
            CoursePhysics(
                course = Course(anyPath()),
                rhoProvider = RhoProviderEstimate,
                windProvider = WindProviderConstant(Wind(3.0, 1.0)),
            )
        assertSame(RhoProviderEstimate, cp.rhoProvider)
        assertSame(AeroProviderConstant, cp.aeroProvider) // default kept
        assertEquals(WindProviderConstant::class, cp.windProvider::class)
    }
}
