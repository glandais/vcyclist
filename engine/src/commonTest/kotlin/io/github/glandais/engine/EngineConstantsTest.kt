package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineConstantsTest {
    @Test
    fun g_is_9_8() {
        assertEquals(9.8, EngineConstants.G)
    }

    @Test
    fun minimal_speed_is_two_kmh_in_ms() {
        assertEquals(2.0 / 3.6, EngineConstants.MINIMAL_SPEED)
    }

    @Test
    fun default_crr_is_0_004() {
        assertEquals(0.004, EngineConstants.DEFAULT_CRR)
    }

    @Test
    fun default_inertia_front_is_0_05() {
        assertEquals(0.05, EngineConstants.DEFAULT_INERTIA_FRONT)
    }

    @Test
    fun default_inertia_rear_is_0_07() {
        assertEquals(0.07, EngineConstants.DEFAULT_INERTIA_REAR)
    }

    @Test
    fun default_wheel_radius_is_0_7() {
        assertEquals(0.7, EngineConstants.DEFAULT_WHEEL_RADIUS_M)
    }

    @Test
    fun default_drivetrain_efficiency_is_0_976() {
        assertEquals(0.976, EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY)
    }

    @Test
    fun default_cyclist_mass_is_80() {
        assertEquals(80.0, EngineConstants.DEFAULT_CYCLIST_MASS_KG)
    }

    @Test
    fun default_cyclist_power_is_280() {
        assertEquals(280.0, EngineConstants.DEFAULT_CYCLIST_POWER_W)
    }

    @Test
    fun default_max_brake_g_is_0_6() {
        assertEquals(0.6, EngineConstants.DEFAULT_MAX_BRAKE_G)
    }

    @Test
    fun default_max_lean_angle_deg_is_35() {
        assertEquals(35.0, EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG)
    }

    @Test
    fun default_max_lean_angle_rad_matches_radians_conversion() {
        val expected = 35.0 * PI / 180.0
        assertTrue(abs(EngineConstants.DEFAULT_MAX_LEAN_ANGLE_RAD - expected) < 1e-12)
    }

    @Test
    fun default_max_speed_is_100_kmh() {
        assertEquals(100.0, EngineConstants.DEFAULT_MAX_SPEED_KMH)
    }

    @Test
    fun default_drag_coefficient_is_0_7() {
        assertEquals(0.7, EngineConstants.DEFAULT_DRAG_COEFFICIENT)
    }

    @Test
    fun default_frontal_area_is_0_5() {
        assertEquals(0.5, EngineConstants.DEFAULT_FRONTAL_AREA_M2)
    }

    @Test
    fun default_air_density_is_1_225() {
        assertEquals(1.225, EngineConstants.DEFAULT_AIR_DENSITY)
    }
}
