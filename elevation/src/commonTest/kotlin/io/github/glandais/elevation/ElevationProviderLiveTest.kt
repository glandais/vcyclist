package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end `ElevationProvider` check that runs on **every** target (JVM, JS/Node, JS/browser),
 * so no target is missing live coverage.
 *
 * Deliberately small: the byte-exactness of the decode is asserted by [ReferenceTileDigestTest];
 * this only confirms the tile → pixel → Terrarium → bilinear chain assembles correctly on top of
 * it. Gated on `INTEGRATION=1` — see `IntegrationGate.kt`.
 */
class ElevationProviderLiveTest {
    @Test
    fun montBlancAndDeadSeaResolveOnEveryTarget() =
        runTest {
            if (skipIfOffline("ElevationProviderLiveTest")) return@runTest
            val provider = ElevationProvider(ElevationProviderConfig(cacheSize = 16))

            val montBlanc = provider.getElevation(45.8326, 6.8652, interpolation = true)
            assertTrue(abs(montBlanc - 4805.0) < 50.0, "Mont Blanc got $montBlanc, expected 4805 ± 50 m")

            val deadSea = provider.getElevation(31.5, 35.5, interpolation = true)
            assertTrue(abs(deadSea - (-430.0)) < 50.0, "Dead Sea got $deadSea, expected -430 ± 50 m")
        }
}
