package io.github.glandais.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.path.ElevationCorrectionStatus;
import io.github.glandais.engine.path.ElevationCorrections;
import io.github.glandais.engine.path.ElevationGainJvm;
import io.github.glandais.engine.path.ElevationStepJvm;
import io.github.glandais.engine.path.Path;
import io.github.glandais.engine.path.PathSimplifierJvm;
import io.github.glandais.engine.path.TerrainProvenance;
import org.junit.Test;

/**
 * {@code ElevationCorrectionJvm}, in <b>Java on purpose</b>: the DEM correction with its bridge
 * decks and tunnels, for a caller that runs no physics, and the chain such a caller builds after it.
 */
public class ElevationCorrectionJavaTest {

    private static double maxGap(Path p) {
        double gap = 0.0;
        for (int i = 1; i < p.getSize(); i++) {
            gap = Math.max(gap, p.distance(i) - p.distance(i - 1));
        }
        return gap;
    }

    @Test
    public void theTunnelIsFlattenedAndReported() {
        Path input = JvmBridgeFixtures.tunnelWorldPath();
        Path out = ElevationCorrectionJvm.fixAndCorrectBlocking(input, JvmBridgeFixtures.tunnelWorldTerrain());
        ElevationCorrections report = out.getElevationCorrections();
        assertEquals(ElevationCorrectionStatus.APPLIED, report.getTunnels());
        assertEquals("no surface model on this provider", ElevationCorrectionStatus.UNAVAILABLE, report.getBridgeDecks());
        assertTrue(maxGap(out) <= Enhancer.REFINE_MAX_DISTANCE_M + 1e-6);
        TerrainProvenance terrain = report.getTerrain();
        assertTrue("read from IGN: " + terrain, terrain.getIgnLidarHdKm() > 1.0);
        assertEquals(0, terrain.getFailedTiles());
        assertNull("the input is never stamped", input.getElevationCorrections());
    }

    @Test
    public void theWorkingGridIsTheCallersChoice() {
        Path out =
                ElevationCorrectionJvm.fixAndCorrectBlocking(
                        JvmBridgeFixtures.tunnelWorldPath(),
                        JvmBridgeFixtures.tunnelWorldTerrain(),
                        EngineModelJvm.elevationCorrectionOptions(),
                        10.0);
        assertTrue("gap " + maxGap(out), maxGap(out) <= 10.0 + 1e-6);
        assertEquals(ElevationCorrectionStatus.APPLIED, out.getElevationCorrections().getTunnels());
    }

    @Test
    public void theTunnelScopeIsReachableFromJava() {
        ElevationCorrectionOptions everywhere = EngineModelJvm.elevationCorrectionOptions(true, true, TunnelScope.EVERYWHERE);
        assertEquals(TunnelScope.EVERYWHERE, everywhere.getTunnelScope());
        assertEquals(TunnelScope.MEASURED_TERRAIN, EngineModelJvm.elevationCorrectionOptions().getTunnelScope());
        assertEquals("measured-terrain", TunnelScope.Companion.byId("measured-terrain").getId());
    }

    @Test
    public void switchedOffTheStepsAreReportedNotRun() throws Exception {
        Path out =
                ElevationCorrectionJvm.fixAndCorrectAsync(
                                JvmBridgeFixtures.tunnelWorldPath(),
                                JvmBridgeFixtures.tunnelWorldTerrain(),
                                EngineModelJvm.elevationCorrectionOptions(false, false))
                        .get();
        assertEquals(ElevationCorrectionStatus.NOT_RUN, out.getElevationCorrections().getTunnels());
        assertEquals(ElevationCorrectionStatus.NOT_RUN, out.getElevationCorrections().getBridgeDecks());
    }

    @Test
    public void aCallerWithoutPhysicsChainsSmoothingGainAndSimplification() {
        Path p =
                ElevationCorrectionJvm.fixAndCorrectBlocking(
                        JvmBridgeFixtures.tunnelWorldPath(),
                        JvmBridgeFixtures.tunnelWorldTerrain(),
                        EngineModelJvm.elevationCorrectionOptions(),
                        10.0);
        p = ElevationStepJvm.smoothElevation(p, 150.0);
        ElevationGainJvm.annotate(p);
        double gain = p.getElevationGainFiltered();
        p = PathSimplifierJvm.simplify(p, 3.0, 3.0);
        assertEquals("the simplifier carries the D+", gain, p.getElevationGainFiltered(), 1e-9);
        assertTrue("the tunnel's mountain is not climbed: D+ " + gain, gain < 20.0);
        assertEquals(ElevationCorrectionStatus.APPLIED, p.getElevationCorrections().getTunnels());
    }
}
