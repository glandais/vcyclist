package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Live HTTP integration tests against tiles.mapterhorn.com.
 *
 * These tests are skipped silently unless `INTEGRATION=1` (env) or `-Dintegration=true` (system
 * property) is set, so an offline `./gradlew :elevation:jvmTest` continues to pass. To run them:
 *
 *   INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ElevationProviderIntegrationTest*' --rerun-tasks
 */
class ElevationProviderIntegrationTest {
    // The gate itself lives in commonTest (`IntegrationGate.kt`) so every target shares it.
    private fun skipped(): Boolean = skipIfOffline("ElevationProviderIntegrationTest")

    private fun newProvider(cacheSize: Int = 16): ElevationProvider = ElevationProvider(ElevationProviderConfig(cacheSize = cacheSize))

    @Test
    fun `Mont Blanc altitude is close to 4805 m`() =
        runTest {
            if (skipped()) return@runTest
            val provider = newProvider()
            val ele = provider.getElevation(45.8326, 6.8652)
            assertTrue(
                (ele - 4805.0).absoluteValue < 50.0,
                "Mont Blanc elevation got $ele, expected within ±50 m of 4805",
            )
        }

    @Test
    fun `Dead Sea shore altitude is close to -430 m`() =
        runTest {
            if (skipped()) return@runTest
            val provider = newProvider()
            val ele = provider.getElevation(31.5, 35.5)
            assertTrue(
                (ele - (-430.0)).absoluteValue < 50.0,
                "Dead Sea elevation got $ele, expected within ±50 m of -430",
            )
        }

    @Test
    fun `Death Valley Badwater Basin altitude is close to -85 m`() =
        runTest {
            if (skipped()) return@runTest
            val provider = newProvider()
            val ele = provider.getElevation(36.250, -116.832)
            assertTrue(
                (ele - (-85.0)).absoluteValue < 50.0,
                "Death Valley elevation got $ele, expected within ±50 m of -85",
            )
        }

    @Test
    fun `second call to same coords is served from cache`() =
        runTest {
            if (skipped()) return@runTest

            var httpCalls = 0
            val countingFetcher: suspend (String) -> RawTile = { url ->
                httpCalls++
                fetchAndDecodeTile(url)
            }
            val provider =
                ElevationProvider(
                    config = ElevationProviderConfig(cacheSize = 8),
                    fetcher = countingFetcher,
                )

            val ele1 = provider.getElevation(45.8326, 6.8652)
            val callsAfterFirst = httpCalls
            val ele2 = provider.getElevation(45.8326, 6.8652)

            assertTrue(callsAfterFirst >= 1, "first call must trigger at least one HTTP fetch")
            // Bilinear interpolation may touch 1..4 neighbouring tiles. Either way the second call
            // must reuse the cache without any extra HTTP fetch.
            assertEquals(
                callsAfterFirst,
                httpCalls,
                "second call must be entirely served from cache (no extra HTTP)",
            )
            assertTrue(
                (ele1 - ele2).absoluteValue < 1e-9,
                "deterministic re-query must return the exact same elevation",
            )
        }

    @Test
    fun `default attribution targets mapterhorn`() {
        if (skipped()) return
        val provider = newProvider()
        val attr = provider.attribution
        assertTrue("mapterhorn" in attr.text.lowercase(), "attribution text: ${attr.text}")
        assertTrue(attr.url?.contains("mapterhorn") == true, "attribution url: ${attr.url}")
    }

    @Test
    fun `getElevationsAlong on a small Alpine path returns a densified profile`() =
        runTest {
            if (skipped()) return@runTest
            val provider = newProvider()

            val path =
                listOf(
                    LatLon(45.8350, 6.8500),
                    LatLon(45.8400, 6.8700),
                    LatLon(45.8500, 6.8800),
                    LatLon(45.8550, 6.8900),
                )
            val profile =
                provider.getElevationsAlong(
                    path = path,
                    step = 100.0,
                    minDistance = 10.0,
                    interpolation = true,
                )

            assertTrue(profile.size >= 10, "profile size: ${profile.size}")

            val outliers = profile.count { it.elevation < 1000.0 || it.elevation > 5500.0 }
            assertEquals(0, outliers, "found $outliers elevation outliers in profile")

            assertTrue(
                (profile.first().latitude - path.first().latitude).absoluteValue < 1e-6,
                "first lat mismatch",
            )
            assertTrue(
                (profile.last().latitude - path.last().latitude).absoluteValue < 1e-6,
                "last lat mismatch",
            )
        }
}
