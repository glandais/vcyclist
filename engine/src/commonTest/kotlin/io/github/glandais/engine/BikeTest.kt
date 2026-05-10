package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BikeTest {
    @Test
    fun default_crr_is_0_004() {
        assertEquals(0.004, Bike.DEFAULT.crr)
    }

    @Test
    fun total_inertia_is_front_plus_rear() {
        val expected = 0.05 + 0.07
        assertTrue(abs(Bike.DEFAULT.totalInertia - expected) < 1e-12)
    }

    @Test
    fun wheel_diameter_is_twice_radius() {
        val expected = 2.0 * 0.7
        assertTrue(abs(Bike.DEFAULT.wheelDiameterM - expected) < 1e-12)
    }

    @Test
    fun wheel_circumference_is_two_pi_radius() {
        val expected = 2.0 * PI * 0.7
        assertTrue(abs(Bike.DEFAULT.wheelCircumferenceM - expected) < 1e-12)
    }

    @Test
    fun equivalent_mass_is_total_inertia_over_radius_squared() {
        val expected = 0.12 / (0.7 * 0.7)
        assertTrue(abs(Bike.DEFAULT.equivalentMass - expected) < 1e-9)
    }

    @Test
    fun power_loss_factor_is_one_minus_efficiency() {
        val expected = 1.0 - 0.976
        assertTrue(abs(Bike.DEFAULT.powerLossFactor - expected) < 1e-12)
    }

    @Test
    fun wheel_power_multiplies_input_by_efficiency() {
        val expected = 100.0 * 0.976
        assertTrue(abs(Bike.DEFAULT.wheelPower(100.0) - expected) < 1e-12)
    }

    @Test
    fun rolling_resistance_force_multiplies_crr_by_normal_force() {
        val expected = 0.004 * 800.0
        assertTrue(abs(Bike.DEFAULT.rollingResistanceForce(800.0) - expected) < 1e-12)
    }

    @Test
    fun custom_crr_overrides_default_keeping_other_defaults() {
        val b = Bike(crr = 0.005)
        assertEquals(0.005, b.crr)
        assertEquals(EngineConstants.DEFAULT_INERTIA_FRONT, b.inertiaFront)
        assertEquals(EngineConstants.DEFAULT_INERTIA_REAR, b.inertiaRear)
        assertEquals(EngineConstants.DEFAULT_WHEEL_RADIUS_M, b.wheelRadiusM)
        assertEquals(EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY, b.efficiency)
    }
}
