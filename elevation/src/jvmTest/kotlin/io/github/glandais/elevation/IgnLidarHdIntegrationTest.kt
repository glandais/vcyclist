package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live HTTP tests against IGN's Géoplateforme WMS, and through it the Mapterhorn fallback.
 *
 * Skipped unless `INTEGRATION=1`, like [ElevationProviderIntegrationTest]:
 *
 *   INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*IgnLidarHdIntegrationTest*' --rerun-tasks
 */
class IgnLidarHdIntegrationTest {
    private fun skipped(): Boolean = skipIfOffline("IgnLidarHdIntegrationTest")

    @Test
    fun `the Puy de Dome summit reads the terrain model's own maximum`() =
        runTest {
            if (skipped()) return@runTest
            // 1462.8 m is the highest MNT sample on the summit (read off a 512 px BIL around it);
            // the 1465 m on the map is a built summit, which a terrain model strips.
            val ele = ElevationSource.IGN_LIDAR_HD.provider().getElevation(45.77248, 2.96406)
            assertTrue((ele - 1462.8).absoluteValue < 3.0, "Puy de Dôme summit got $ele, expected 1462.8 ± 3 m")
        }

    @Test
    fun `IGN and Mapterhorn agree to a few metres on open ground`() =
        runTest {
            if (skipped()) return@runTest
            // A gentle meadow on the Causse du Larzac: no road cut, no tree, no building.
            val ign = ElevationSource.IGN_LIDAR_HD.provider().getElevation(43.9667, 3.1950)
            val mapterhorn = ElevationSource.MAPTERHORN.provider().getElevation(43.9667, 3.1950)
            assertTrue((ign - mapterhorn).absoluteValue < 10.0, "IGN $ign m vs Mapterhorn $mapterhorn m")
        }

    @Test
    fun `outside France the Mapterhorn fallback answers instead of 0 m`() =
        runTest {
            if (skipped()) return@runTest
            // Turin, ~240 m, where IGN answers -9999 everywhere.
            val ele = ElevationSource.IGN_LIDAR_HD.provider().getElevation(45.0703, 7.6869)
            val mapterhorn = ElevationSource.MAPTERHORN.provider().getElevation(45.0703, 7.6869)
            assertTrue(ele > 150.0, "Turin read $ele m through the IGN source")
            assertTrue((ele - mapterhorn).absoluteValue < 1e-9, "fallback $ele != Mapterhorn $mapterhorn")
        }
}
