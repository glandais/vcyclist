package io.github.glandais.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnhanceOptionsTest {
    @Test
    fun default_has_all_steps_enabled_with_default_simplify() {
        val d = EnhanceOptions.DEFAULT
        assertTrue(d.fixElevation)
        assertTrue(d.computeMaxSpeeds)
        assertTrue(d.virtualizeTrack)
        assertTrue(d.computeOnePointPerSecond)
        assertEquals(SimplifyPathOptions(), d.simplifyPath)
        assertTrue(d.simplifyPath.enabled)
    }

    @Test
    fun simplify_path_options_defaults() {
        val s = SimplifyPathOptions()
        assertTrue(s.enabled)
        assertEquals(10.0, s.toleranceM)
        assertEquals(3.0, s.zExaggeration)
    }

    @Test
    fun copy_with_fix_elevation_false_keeps_other_flags() {
        val o = EnhanceOptions(fixElevation = false)
        assertEquals(false, o.fixElevation)
        assertTrue(o.computeMaxSpeeds)
        assertTrue(o.virtualizeTrack)
        assertTrue(o.computeOnePointPerSecond)
        assertEquals(SimplifyPathOptions(), o.simplifyPath)
    }
}
