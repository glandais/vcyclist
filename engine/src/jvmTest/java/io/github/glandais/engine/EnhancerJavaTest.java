package io.github.glandais.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.gpx.GpxParser;
import io.github.glandais.engine.gpx.GpxToPathKt;
import io.github.glandais.engine.path.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Task g22, in <b>Java on purpose</b>. Without a provider nothing in the pipeline actually
 * suspends, so this is the cheapest possible proof that the whole {@link Enhancer} surface is
 * reachable from Java — the network-bound half is covered by the {@code :elevation} bridge test.
 */
public class EnhancerJavaTest {

    private static final String GPX =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                    + "  <trk><name>java</name><trkseg>\n"
                    + "    <trkpt lat=\"45.0000\" lon=\"6.0000\"><ele>1000.0</ele></trkpt>\n"
                    + "    <trkpt lat=\"45.0020\" lon=\"6.0020\"><ele>1010.0</ele></trkpt>\n"
                    + "    <trkpt lat=\"45.0040\" lon=\"6.0040\"><ele>1030.0</ele></trkpt>\n"
                    + "  </trkseg></trk>\n"
                    + "</gpx>";

    private static Path samplePath() {
        // `repairOnFailure` has a default value that Java cannot use — exactly the gap g27
        // closes with @JvmOverloads. Spelled out here so this test does not depend on it.
        return GpxToPathKt.firstTrackAsPath(GpxParser.INSTANCE.parse(GPX, true));
    }

    @Test
    public void enhanceCourseDefaultBlockingRunsTheWholePipeline() {
        Path enhanced = EnhancerJvm.enhanceCourseDefaultBlocking(samplePath());

        assertTrue("pipeline produces points", enhanced.getSize() > 0);
        assertTrue("distance is computed", enhanced.getTotalDistance() > 0.0);
    }

    @Test
    public void enhanceCourseBlockingTakesAnExplicitCourse() {
        // The explicit entry point refuses fixElevation without a provider since task g34, so
        // an offline caller now has to say so — through the g27 factory, since Java cannot
        // reach the Kotlin defaults of EnhanceOptions.
        Path enhanced =
                EnhancerJvm.enhanceCourseBlocking(
                        Enhancer.INSTANCE.getDefaultCourse(samplePath()),
                        EngineModelJvm.enhanceOptions(false));

        assertTrue(enhanced.getSize() > 0);
    }

    @Test
    public void enhanceCoursesBlockingReturnsOneResultPerInput() {
        List<Path> enhanced = EnhancerJvm.enhanceCoursesBlocking(List.of(samplePath(), samplePath()));

        assertEquals(2, enhanced.size());
        assertEquals(enhanced.get(0).getSize(), enhanced.get(1).getSize());
    }

    @Test
    public void asyncAgreesWithBlocking() throws Exception {
        Path blocking = EnhancerJvm.enhanceCourseDefaultBlocking(samplePath());
        Path async = EnhancerJvm.enhanceCourseDefaultAsync(samplePath()).get(60, TimeUnit.SECONDS);

        assertEquals(blocking.getSize(), async.getSize());
        assertEquals(blocking.getTotalDistance(), async.getTotalDistance(), 1e-9);
    }
}
