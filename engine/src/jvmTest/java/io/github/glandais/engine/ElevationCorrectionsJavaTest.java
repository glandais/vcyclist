package io.github.glandais.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.path.ElevationCorrectionStatus;
import io.github.glandais.engine.path.ElevationCorrections;
import io.github.glandais.engine.path.Path;
import org.junit.Test;

/**
 * E7 of {@code elevation-bridge-decks}, in <b>Java on purpose</b>: the switches of the elevation
 * corrections are reachable through {@code EngineModelJvm}, and their report through
 * {@code Path.getElevationCorrections()}, its status enum and its companion.
 */
public class ElevationCorrectionsJavaTest {

    private static EnhanceOptions noPhysics(ElevationCorrectionOptions corrections) {
        return EngineModelJvm.enhanceOptions(
                true,
                false,
                false,
                false,
                EngineModelJvm.simplifyPathOptions(false),
                EngineModelJvm.wPrimeBalanceOptions(),
                EngineModelJvm.curvatureOptions(),
                EngineModelJvm.racingLineOptions(),
                EngineModelJvm.elevationGainOptions(),
                EnhanceOptions.Companion.getDEFAULT().getElevationSmoothWindowM(),
                corrections);
    }

    @Test
    public void tunnelsSwitchedOffAreReportedNotRun() {
        Path enhanced =
                EnhancerJvm.enhanceCourseDefaultBlocking(
                        JvmBridgeFixtures.tunnelWorldPath(),
                        JvmBridgeFixtures.tunnelWorldTerrain(),
                        noPhysics(EngineModelJvm.elevationCorrectionOptions(true, false)));
        ElevationCorrections report = enhanced.getElevationCorrections();
        assertEquals(ElevationCorrectionStatus.NOT_RUN, report.getTunnels());
        assertEquals(0, report.getTunnelsApplied());
    }

    @Test
    public void theDefaultFlattensTheTunnel() {
        Path enhanced =
                EnhancerJvm.enhanceCourseDefaultBlocking(
                        JvmBridgeFixtures.tunnelWorldPath(),
                        JvmBridgeFixtures.tunnelWorldTerrain(),
                        noPhysics(EngineModelJvm.elevationCorrectionOptions()));
        ElevationCorrections report = enhanced.getElevationCorrections();
        assertEquals(ElevationCorrectionStatus.APPLIED, report.getTunnels());
        assertEquals(1, report.getTunnelsApplied());
        assertEquals(ElevationCorrectionStatus.UNAVAILABLE, report.getBridgeDecks());
        assertTrue(report.getWarnings().isEmpty());
    }

    @Test
    public void anUnreadableSurfaceTileIsOneWarning() {
        Path enhanced =
                EnhancerJvm.enhanceCourseDefaultBlocking(
                        JvmBridgeFixtures.deckWorldPath(),
                        JvmBridgeFixtures.deckWorldTerrainWithFailingSurface(),
                        noPhysics(EngineModelJvm.elevationCorrectionOptions()));
        ElevationCorrections report = enhanced.getElevationCorrections();
        assertEquals(ElevationCorrectionStatus.FETCH_FAILED, report.getBridgeDecks());
        assertEquals(1, report.getWarnings().size());
        assertTrue(report.getWarnings().get(0).startsWith("bridge decks"));
    }

    @Test
    public void aRawPathCarriesNoReportAndStatusesResolveById() {
        assertNull(JvmBridgeFixtures.tunnelWorldPath().getElevationCorrections());
        assertEquals(
                ElevationCorrectionStatus.APPLIED,
                ElevationCorrectionStatus.Companion.byId("applied"));
        assertEquals("fetch-failed", ElevationCorrectionStatus.FETCH_FAILED.getId());
    }
}
