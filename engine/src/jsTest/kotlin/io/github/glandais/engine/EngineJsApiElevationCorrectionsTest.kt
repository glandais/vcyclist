@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.TunnelWorld
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E7 on the JS door: `bridgeDecksEnabled` / `tunnelsEnabled` reach the core options, and
 * [pathElevationCorrections] hands the report back as a plain object.
 */
class EngineJsApiElevationCorrectionsTest {
    private fun dto(js: dynamic): EnhanceOptionsDto = js.unsafeCast<EnhanceOptionsDto>()

    @Test
    fun both_keys_are_forwarded() {
        val tunnelsOff = dto(js("({ tunnelsEnabled: false })")).toEnhanceOptions()
        assertEquals(false, tunnelsOff.elevationCorrections.tunnels)
        assertEquals(ElevationCorrectionOptions.DEFAULT.bridgeDecks, tunnelsOff.elevationCorrections.bridgeDecks)

        val decksOff = dto(js("({ bridgeDecksEnabled: false })")).toEnhanceOptions()
        assertEquals(false, decksOff.elevationCorrections.bridgeDecks)
        assertEquals(ElevationCorrectionOptions.DEFAULT.tunnels, decksOff.elevationCorrections.tunnels)
    }

    @Test
    fun the_fallbacks_are_the_engine_default() {
        assertEquals(ElevationCorrectionOptions.DEFAULT, dto(js("({})")).toEnhanceOptions().elevationCorrections)
        assertEquals(ElevationCorrectionOptions.DEFAULT, null.toEnhanceOptions().elevationCorrections)
    }

    @Test
    fun an_unknown_key_throws_from_the_call_not_from_its_promise() {
        val path = parseGpx(GpxFixtures.SAMPLE_GPX)
        // No `await`: a rejected promise would not fail this block, a synchronous throw does.
        val failure = assertFailsWith<IllegalStateException> { enhance(path, dto(js("({ tunnelEnabled: false })"))) }
        assertTrue("tunnelEnabled" in failure.message!!, failure.message!!)
        assertFailsWith<IllegalStateException> {
            enhanceWithCourse(path, null, null, null, null, dto(js("({ bridgeDeckEnabled: true })")))
        }
        // The DEM provider is built before the promise too: an unknown source is a call-site error.
        val badSource = assertFailsWith<IllegalArgumentException> { enhance(path, dto(js("({ fixElevation: true, demSource: 'nope' })"))) }
        assertTrue("nope" in badSource.message!!, badSource.message!!)
        assertFailsWith<IllegalArgumentException> {
            enhanceWithCourse(path, null, null, null, null, dto(js("({ fixElevation: true, demSource: 'nope' })")))
        }
    }

    @Test
    fun a_tunnel_is_reported_applied() =
        runTest {
            val enhanced =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(TunnelWorld.path(stepM = 25.0)),
                    EnhanceOptions(
                        computeMaxSpeeds = false,
                        virtualizeTrack = false,
                        computeOnePointPerSecond = false,
                        simplifyPath = SimplifyPathOptions(enabled = false),
                    ),
                    TunnelWorld.terrain(),
                )
            val report = assertNotNull(pathElevationCorrections(enhanced))
            assertEquals("applied", report.tunnels)
            assertEquals(1, report.tunnelsApplied)
            assertEquals("unavailable", report.bridgeDecks, "DeckWorld's terrain carries no surface model")
            assertTrue(js("Array.isArray")(report.warnings).unsafeCast<Boolean>(), "warnings is a real JS array")
            assertEquals(0, report.warnings.size)
        }

    @Test
    fun a_failed_surface_read_is_a_warning() =
        runTest {
            val broken = DeckWorld.terrain().withSurfaceModel(DeckWorld.surface { error("HTTP 429") })
            val enhanced =
                Enhancer.enhanceCourse(
                    Enhancer.getDefaultCourse(DeckWorld.path(stepM = 25.0)),
                    EnhanceOptions(computeMaxSpeeds = false, virtualizeTrack = false, computeOnePointPerSecond = false),
                    broken,
                )
            val report = assertNotNull(pathElevationCorrections(enhanced))
            assertEquals("fetch-failed", report.bridgeDecks)
            assertEquals(1, report.warnings.size)
            assertTrue(report.warnings[0].startsWith("bridge decks"), report.warnings[0])
        }

    @Test
    fun a_path_that_was_never_enhanced_has_no_report() {
        assertNull(pathElevationCorrections(parseGpx(GpxFixtures.SAMPLE_GPX)))
    }
}
