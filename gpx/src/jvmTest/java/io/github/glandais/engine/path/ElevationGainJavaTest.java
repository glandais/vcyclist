package io.github.glandais.engine.path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * R27, in <b>Java on purpose</b>. {@link ElevationGainOptions} is a Kotlin data class whose
 * {@code thresholdM} and {@code smoothWindowM} default to the preset's, and Java cannot see a
 * default argument — so a Java caller either reaches the full constructor or is locked out. Only a
 * Java source proves which; from Kotlin this file would compile either way.
 */
public class ElevationGainJavaTest {

    private static Path hill() {
        double[] elevations = {100.0, 150.0, 120.0, 200.0, 180.0};
        Path path = new Path(elevations.length);
        for (int i = 0; i < elevations.length; i++) {
            path.setLatitude(i, 0.0);
            path.setLongitude(i, i * 0.001);
            path.setElevation(i, elevations[i]);
        }
        path.computeDerivedData();
        return path;
    }

    @Test
    public void thePresetCatalogIsReachableWithItsNumbers() {
        assertEquals(3.0, ElevationGainPreset.DEM.getThresholdM(), 1e-9);
        assertEquals(30.0, ElevationGainPreset.DEM.getSmoothWindowM(), 1e-9);
        assertEquals("dem", ElevationGainPreset.DEM.getId());
        assertEquals(ElevationGainPreset.GPS, ElevationGainPreset.Companion.byId("gps"));
    }

    @Test
    public void anUnknownPresetIsRejectedRatherThanDefaulted() {
        try {
            ElevationGainPreset.Companion.byId("strava");
            fail("expected an IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("barometric"));
        }
    }

    @Test
    public void optionsAreConstructibleWithoutKotlinDefaultArguments() {
        ElevationGainOptions options =
                new ElevationGainOptions(true, ElevationGainPreset.DEM, 3.0, 0.0);
        assertEquals(3.0, options.getThresholdM(), 1e-9);

        ElevationGainResult result = ElevationGain.INSTANCE.compute(hill(), options);
        assertEquals(130.0, result.getGainM(), 1e-9);
        assertEquals(-50.0, result.getLossM(), 1e-9);
        assertEquals(130.0, result.getRawGainM(), 1e-9);
        // Closure: the legs tile the profile, so the pair telescopes to the net change.
        assertEquals(80.0, result.getGainM() + result.getLossM(), 1e-9);
    }

    @Test
    public void theDefaultsFactoryIsReachableFromJava() {
        // The zero-argument path a Java caller actually wants: no constructor arithmetic, and the
        // same object Kotlin gets from ElevationGainOptions().
        ElevationGainOptions options = ElevationGainOptions.Companion.getDEFAULT();
        assertEquals(ElevationGainPreset.DEM, options.getPreset());
        assertEquals(3.0, options.getThresholdM(), 1e-9);
        assertEquals(30.0, options.getSmoothWindowM(), 1e-9);

        ElevationGainOptions raw =
                ElevationGainOptions.Companion.of(ElevationGainPreset.BAROMETRIC);
        assertEquals(2.0, raw.getThresholdM(), 1e-9);
    }

    @Test
    public void annotateCachesTheFigureOnThePathAndReportedFallsBack() {
        Path path = hill();
        assertTrue("absent until measured", Double.isNaN(path.getElevationGainFiltered()));
        assertEquals(path.getElevationGain(), path.getReportedElevationGain(), 1e-9);

        ElevationGain.INSTANCE.annotate(
                path, new ElevationGainOptions(true, ElevationGainPreset.DEM, 3.0, 0.0));

        assertEquals(130.0, path.getElevationGainFiltered(), 1e-9);
        assertEquals(130.0, path.getReportedElevationGain(), 1e-9);
        assertTrue("loss is negative by convention", path.getReportedElevationLoss() <= 0.0);
    }
}
