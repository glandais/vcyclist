package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A misspelled key must be an error on this façade too.
 *
 * `EngineJsApi` has rejected unknown DTO keys since task 43; this one had **no check at all**, so a
 * mistyped `step` was silently ignored and the caller got the default — while the identical typo
 * was a hard error on every guarded engine DTO and on every WASI reader, which validates even the
 * provider config this door did not. An `external interface` ignores unknown properties in silence,
 * so nothing but this check can tell a caller they misspelled something.
 *
 * Step S7 of `docs/tasks/surface-alignment.md`.
 */
class ElevationJsApiKeyCheckTest {
    private fun obj(vararg pairs: Pair<String, Any?>): dynamic {
        val o = js("({})")
        for ((k, v) in pairs) o[k] = v
        return o
    }

    @Test
    fun `an unknown provider config key is refused, not ignored`() {
        val failure =
            assertFailsWith<IllegalStateException> {
                newElevationProvider(obj("zoomLevel" to 12, "zoomLevle" to 13).unsafeCast<ElevationProviderConfigDto>())
            }

        assertTrue("zoomLevle" in failure.message!!, "the message must name the offending key: ${failure.message}")
    }

    @Test
    fun `a correctly spelled provider config is still accepted`() {
        val provider =
            newElevationProvider(
                obj("zoomLevel" to 12, "cacheSize" to 10, "tileSize" to 256).unsafeCast<ElevationProviderConfigDto>(),
            )

        assertTrue(provider.config.zoomLevel == 12, "the guard must not reject what it should read")
    }

    @Test
    fun `null config remains the way to ask for every default`() {
        assertTrue(newElevationProvider(null).config.zoomLevel == ElevationProviderConfig().zoomLevel)
    }

    @Test
    fun `an unknown getElevationsAlong option is refused`() {
        val provider = newElevationProvider(null)

        val failure =
            assertFailsWith<IllegalStateException> {
                getElevationsAlong(
                    provider,
                    emptyArray(),
                    obj("step" to 10.0, "smoothing" to true).unsafeCast<GetElevationsAlongOptionsDto>(),
                )
            }

        assertTrue("smoothing" in failure.message!!, failure.message!!)
    }

    @Test
    fun `an unknown nested smoothing key is refused too`() {
        val provider = newElevationProvider(null)

        val failure =
            assertFailsWith<IllegalStateException> {
                getElevationsAlong(
                    provider,
                    emptyArray(),
                    obj("smoothingOptions" to obj("enabled" to true, "window" to 50.0))
                        .unsafeCast<GetElevationsAlongOptionsDto>(),
                )
            }

        assertTrue("window" in failure.message!!, failure.message!!)
    }

    @Test
    fun `the sampling defaults come from ElevationDefaults, not from this facade`() {
        // The values were literals in the signature and again in the façade — two spellings that
        // agree until somebody changes one. They live in commonMain now.
        assertTrue(ElevationDefaults.STEP_M == 10.0)
        assertTrue(ElevationDefaults.MIN_DISTANCE_M == 1.0)
        assertTrue(ElevationDefaults.INTERPOLATION)
    }
}
