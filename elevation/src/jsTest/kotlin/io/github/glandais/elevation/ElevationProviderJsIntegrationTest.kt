package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live HTTP integration tests against tiles.mapterhorn.com from the Kotlin/JS target (Node + browser).
 *
 * Mirrors the JVM `ElevationProviderIntegrationTest`. This source set is shared by `jsNodeTest`
 * and `jsBrowserTest`, so both run. Gated by `INTEGRATION=1` — see `IntegrationGate.kt`.
 */
class ElevationProviderJsIntegrationTest {
    @Test
    fun montBlancAltitudeIsCloseTo4805m() =
        runTest {
            if (skipIfOffline("ElevationProviderJsIntegrationTest")) return@runTest
            val provider = ElevationProvider()
            val alt = provider.getElevation(45.8326, 6.8652, interpolation = true)
            assertTrue(abs(alt - 4805.0) < 50.0, "Mont Blanc altitude $alt should be 4805 ± 50 m")
        }

    @Test
    fun deadSeaAltitudeIsCloseToMinus430m() =
        runTest {
            if (skipIfOffline("ElevationProviderJsIntegrationTest")) return@runTest
            val provider = ElevationProvider()
            val alt = provider.getElevation(31.5, 35.5, interpolation = true)
            assertTrue(abs(alt - (-430.0)) < 50.0, "Dead Sea altitude $alt should be -430 ± 50 m")
        }

    @Test
    fun deathValleyAltitudeIsCloseToMinus85m() =
        runTest {
            if (skipIfOffline("ElevationProviderJsIntegrationTest")) return@runTest
            val provider = ElevationProvider()
            val alt = provider.getElevation(36.250, -116.832, interpolation = true)
            assertTrue(abs(alt - (-85.0)) < 50.0, "Death Valley altitude $alt should be -85 ± 50 m")
        }

    @Test
    fun getElevationsAlongMontBlancPathReturnsDensifiedProfile() =
        runTest {
            if (skipIfOffline("ElevationProviderJsIntegrationTest")) return@runTest
            val provider = ElevationProvider()
            val path =
                listOf(
                    LatLon(45.83, 6.86, null),
                    LatLon(45.84, 6.87, null),
                    LatLon(45.85, 6.88, null),
                    LatLon(45.83, 6.88, null),
                )
            val profile = provider.getElevationsAlong(path, step = 100.0)
            assertTrue(profile.size >= 10, "Expected >= 10 densified points, got ${profile.size}")
            val altitudes = profile.map { it.elevation }
            val maxAlt = altitudes.max()
            val minAlt = altitudes.min()
            assertTrue(minAlt > 1000.0, "min altitude $minAlt should be > 1000 m around Mont Blanc")
            assertTrue(maxAlt < 5500.0, "max altitude $maxAlt should be < 5500 m around Mont Blanc")
        }
}
