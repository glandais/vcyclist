package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WindProviderTest {
    private fun course(): Pair<Course, Path> {
        val path = Path(3)
        return Course(path) to path
    }

    // ---- 1. Wind.NONE sentinel ----------------------------------------------

    @Test
    fun wind_none_sentinel_is_zero_zero() {
        assertEquals(0.0, Wind.NONE.speedMS, 0.0)
        assertEquals(0.0, Wind.NONE.directionRad, 0.0)
        assertEquals(Wind(0.0, 0.0), Wind.NONE)
    }

    // ---- 2. WindProviderNone returns NONE at every index --------------------

    @Test
    fun wind_provider_none_returns_NONE_at_every_index() {
        val (c, path) = course()
        for (i in 0 until path.size) {
            val w = WindProviderNone.wind(c, path, i)
            assertEquals(Wind.NONE, w)
        }
    }

    // ---- 3. WindProviderConstant returns the configured wind ----------------

    @Test
    fun wind_provider_constant_returns_configured_east_wind() {
        val (c, path) = course()
        val provider = WindProviderConstant(Wind(5.0, PI / 2.0))
        val w = provider.wind(c, path, 0)
        assertEquals(5.0, w.speedMS, 0.0)
        assertEquals(PI / 2.0, w.directionRad, 0.0)
    }

    // ---- 4. Wind data-class equality / hashCode -----------------------------

    @Test
    fun wind_data_class_equality_and_hashcode() {
        val a = Wind(3.0, 1.0)
        val b = Wind(3.0, 1.0)
        val c = Wind(3.0, 2.0)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertTrue(a != c)
    }

    // ---- 5. No defensive copy (same instance returned) ----------------------

    @Test
    fun wind_provider_constant_returns_same_instance_no_defensive_copy() {
        val (c, path) = course()
        val wind = Wind(7.5, PI)
        val provider = WindProviderConstant(wind)
        val w1 = provider.wind(c, path, 0)
        val w2 = provider.wind(c, path, 2)
        assertSame(wind, w1)
        assertSame(wind, w2)
    }

    // ---- 6. South wind (π) preserved ----------------------------------------

    @Test
    fun wind_provider_constant_preserves_south_direction() {
        val (c, path) = course()
        val provider = WindProviderConstant(Wind(10.0, PI))
        val w = provider.wind(c, path, 0)
        assertEquals(PI, w.directionRad, 0.0)
        assertEquals(10.0, w.speedMS, 0.0)
    }

    // ---- 7. SAM lambda usage validates the `fun interface` shape -----------

    @Test
    fun wind_provider_can_be_built_from_a_sam_lambda() {
        val (c, path) = course()
        val provider = WindProvider { _, _, idx -> Wind(idx.toDouble(), 0.0) }
        assertEquals(0.0, provider.wind(c, path, 0).speedMS, 0.0)
        assertEquals(1.0, provider.wind(c, path, 1).speedMS, 0.0)
        assertEquals(2.0, provider.wind(c, path, 2).speedMS, 0.0)
    }
}
