package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CyclistTest {
    @Test
    fun default_mass_kg_is_80() {
        assertEquals(80.0, Cyclist.DEFAULT.massKg)
    }

    @Test
    fun default_cd_and_frontal_area_match_constants() {
        assertEquals(0.7, Cyclist.DEFAULT.cd)
        assertEquals(0.5, Cyclist.DEFAULT.frontalAreaM2)
    }

    @Test
    fun aerodynamic_drag_area_is_cd_times_frontal_area() {
        val expected = 0.7 * 0.5
        assertTrue(abs(Cyclist.DEFAULT.aerodynamicDragArea - expected) < 1e-12)
    }

    @Test
    fun tan_max_lean_angle_matches_tan_35_deg() {
        val expected = tan(35.0 * PI / 180.0)
        assertTrue(abs(Cyclist.DEFAULT.tanMaxLeanAngle - expected) < 1e-12)
    }

    @Test
    fun max_lean_angle_rad_matches_degree_conversion() {
        val expected = 35.0 * PI / 180.0
        assertTrue(abs(Cyclist.DEFAULT.maxLeanAngleRad - expected) < 1e-12)
    }

    @Test
    fun max_brake_ms2_is_g_units_times_g() {
        val expected = 0.6 * 9.8
        assertTrue(abs(Cyclist.DEFAULT.maxBrakeMS2 - expected) < 1e-12)
    }

    @Test
    fun max_speed_ms_is_kmh_divided_by_3_6() {
        val expected = 100.0 / 3.6
        assertTrue(abs(Cyclist.DEFAULT.maxSpeedMS - expected) < 1e-12)
    }

    @Test
    fun custom_mass_overrides_default_keeping_other_defaults() {
        val c = Cyclist(massKg = 90.0)
        assertEquals(90.0, c.massKg)
        assertEquals(EngineConstants.DEFAULT_MAX_BRAKE_G, c.maxBrakeG)
        assertEquals(EngineConstants.DEFAULT_DRAG_COEFFICIENT, c.cd)
        assertEquals(EngineConstants.DEFAULT_FRONTAL_AREA_M2, c.frontalAreaM2)
        assertEquals(EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG, c.maxLeanAngleDeg)
        assertEquals(EngineConstants.DEFAULT_MAX_SPEED_KMH, c.maxSpeedKmH)
    }

    @Test
    fun copy_changes_max_brake_g_and_keeps_other_fields() {
        val c = Cyclist.DEFAULT.copy(maxBrakeG = 0.8)
        assertEquals(0.8, c.maxBrakeG)
        assertEquals(Cyclist.DEFAULT.massKg, c.massKg)
        assertEquals(Cyclist.DEFAULT.cd, c.cd)
        assertEquals(Cyclist.DEFAULT.frontalAreaM2, c.frontalAreaM2)
        assertEquals(Cyclist.DEFAULT.maxLeanAngleDeg, c.maxLeanAngleDeg)
        assertEquals(Cyclist.DEFAULT.maxSpeedKmH, c.maxSpeedKmH)
    }
}
