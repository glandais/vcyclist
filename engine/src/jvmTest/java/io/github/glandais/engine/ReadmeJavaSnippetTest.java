package io.github.glandais.engine;

import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.gpx.GpxParser;
import io.github.glandais.engine.gpx.GpxToPathKt;
import io.github.glandais.engine.gpx.GpxWriter;
import io.github.glandais.engine.path.Path;
import org.junit.Test;

/**
 * Compiles and runs the Java snippet of the root README's "Use from Java" section. A README
 * example that does not compile is worse than no example, and this is the only way to know.
 */
public class ReadmeJavaSnippetTest {

    @Test
    public void readmeSnippetCompilesAndRuns() {
        String xml =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                        + "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                        + "  <trk><trkseg>\n"
                        + "    <trkpt lat=\"45.0000\" lon=\"6.0000\"><ele>1000.0</ele></trkpt>\n"
                        + "    <trkpt lat=\"45.0020\" lon=\"6.0020\"><ele>1010.0</ele></trkpt>\n"
                        + "  </trkseg></trk>\n"
                        + "</gpx>";

        Path input = GpxToPathKt.firstTrackAsPath(GpxParser.INSTANCE.parse(xml, true));
        Path enhanced = EnhancerJvm.enhanceCourseDefaultBlocking(input);
        String out = GpxWriter.INSTANCE.write(enhanced, "virtualized", null, null, true);

        assertTrue(out.contains("<trk>"));
    }
}
