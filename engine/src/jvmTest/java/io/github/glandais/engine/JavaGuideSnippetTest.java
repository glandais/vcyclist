package io.github.glandais.engine;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.climb.ClimbDetectorJvm;
import io.github.glandais.engine.gpx.GpxParserJvm;
import io.github.glandais.engine.gpx.GpxToPathJvm;
import io.github.glandais.engine.gpx.GpxWriterJvm;
import io.github.glandais.engine.io.TabularWritersJvm;
import io.github.glandais.engine.path.Path;
import io.github.glandais.engine.path.PathSimplifierJvm;
import io.github.glandais.engine.trajectory.CorridorMode;
import io.github.glandais.engine.trajectory.CurvatureOptions;
import io.github.glandais.engine.trajectory.RacingLineOptions;
import org.junit.Test;

/**
 * Compiles and runs the Java snippets of {@code docs/guides/using-from-java.md} and of the root
 * README's "Java" quick start. A documentation example that does not compile is worse than no
 * example, and this is the only way to know.
 */
public class JavaGuideSnippetTest {

    private static final String XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                    + "  <trk><trkseg>\n"
                    + "    <trkpt lat=\"45.0000\" lon=\"6.0000\"><ele>1000.0</ele></trkpt>\n"
                    + "    <trkpt lat=\"45.0020\" lon=\"6.0020\"><ele>1010.0</ele></trkpt>\n"
                    + "  </trkseg></trk>\n"
                    + "</gpx>";

    /** The README quick start, and the guide's "A complete round trip" section. */
    @Test
    public void roundTripSnippetCompilesAndRuns() {
        Path input = GpxToPathJvm.firstTrackAsPath(GpxParserJvm.parse(XML));
        Path enhanced = EnhancerJvm.enhanceCourseDefaultBlocking(input);
        String out = GpxWriterJvm.write(enhanced);

        assertTrue(out.contains("<trk>"));
    }

    /** The guide's "Building the inputs" section: the {@code EngineModelJvm} factories. */
    @Test
    public void factorySnippetCompilesAndRuns() {
        Cyclist rider = EngineModelJvm.cyclist(72.0);
        Bike bike = EngineModelJvm.bike();
        EnhanceOptions options = EngineModelJvm.enhanceOptions();

        assertNotNull(rider);
        assertNotNull(bike);
        assertNotNull(options);
    }

    /**
     * The four capabilities that were unreachable from Java until the factories were widened:
     * {@code maxPedalingLeanAngleDeg} on the bike, and W'bal / curvature / racing line on the
     * options. The Kotlin-side arity guard is {@code EngineModelJvmCoverageTest}; this pins that
     * Java can actually call them, which no Kotlin test can check.
     */
    @Test
    public void everyCapabilityIsReachableFromJava() {
        Bike bike = EngineModelJvm.bike(0.004, 0.05, 0.07, 0.35, 0.976, 25.0);

        WPrimeBalanceOptions wBal = EngineModelJvm.wPrimeBalanceOptions(true, 260.0, 22000.0);
        CurvatureOptions curvature = EngineModelJvm.curvatureOptions(false);
        RacingLineOptions racingLine =
                EngineModelJvm.racingLineOptions(true, CorridorMode.FULL_ROAD, 7.0);

        EnhanceOptions options =
                EngineModelJvm.enhanceOptions(
                        false,
                        true,
                        true,
                        true,
                        EngineModelJvm.simplifyPathOptions(),
                        wBal,
                        curvature,
                        racingLine);

        assertTrue(bike.getMaxPedalingLeanAngleDeg() == 25.0);
        assertTrue(options.getWPrimeBalance().getCriticalPowerW() == 260.0);
        assertTrue(!options.getCurvature().getEnabled());
        assertTrue(options.getRacingLine().getEnabled());
        assertTrue(options.getRacingLine().getCorridor() == CorridorMode.FULL_ROAD);
        assertTrue(options.getRacingLine().getDefaultRoadWidthM() == 7.0);
    }

    /** The guide's "Outputs" section. FIT is covered by {@code FitJavaInteropTest} in {@code :fit}. */
    @Test
    public void outputSnippetCompilesAndRuns() {
        Path enhanced =
                EnhancerJvm.enhanceCourseDefaultBlocking(
                        GpxToPathJvm.firstTrackAsPath(GpxParserJvm.parse(XML)));

        String gpx = GpxWriterJvm.write(enhanced);
        String bare = GpxWriterJvm.write(enhanced, "noname", null, null, false);
        String csv = TabularWritersJvm.writeCsv(enhanced);
        String json = TabularWritersJvm.writeJson(enhanced);
        Path smaller = PathSimplifierJvm.simplify(enhanced, 10.0);

        assertTrue(gpx.contains("<trk>"));
        assertTrue(!bare.contains("<extensions>"));
        assertTrue(csv.length() > 0);
        assertTrue(json.length() > 0);
        assertTrue(smaller.getSize() > 0);
        assertNotNull(ClimbDetectorJvm.detect(enhanced));
    }
}
