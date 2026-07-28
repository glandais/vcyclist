package io.github.glandais.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.glandais.elevation.ElevationProvider;
import io.github.glandais.elevation.ElevationProviderJvm;
import io.github.glandais.elevation.LatLon;
import io.github.glandais.elevation.LatLonElevation;
import io.github.glandais.engine.gpx.GpxDocument;
import io.github.glandais.engine.gpx.GpxModelJvm;
import io.github.glandais.engine.gpx.GpxParserJvm;
import io.github.glandais.engine.gpx.GpxPathKind;
import io.github.glandais.engine.gpx.GpxToPathJvm;
import io.github.glandais.engine.gpx.GpxTrack;
import io.github.glandais.engine.gpx.GpxTrackPoint;
import io.github.glandais.engine.gpx.GpxWaypoint;
import io.github.glandais.engine.gpx.GpxWriterJvm;
import io.github.glandais.engine.io.CsvOptions;
import io.github.glandais.engine.io.TabularWritersJvm;
import io.github.glandais.engine.path.ElevationStepJvm;
import io.github.glandais.engine.path.Path;
import io.github.glandais.engine.path.PathJvm;
import io.github.glandais.engine.path.PathSimplifierJvm;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;

/**
 * Task g27, in <b>Java on purpose</b>. Every call below is written in its <i>shortest</i> form:
 * that is the property under test, and it is invisible from Kotlin, where the same source
 * compiles whether the JVM facades exist or not.
 *
 * <p>Each test also pins the short form against the fully-spelled one, so a facade that silently
 * passed a different default would fail here rather than in a user's output.
 */
public class JavaInteropTest {

    private static final String GPX =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                    + "  <trk><name>t</name><trkseg>\n"
                    + "    <trkpt lat=\"45.0000\" lon=\"6.0000\"><ele>1000.0</ele></trkpt>\n"
                    + "    <trkpt lat=\"45.0010\" lon=\"6.0010\"><ele>1010.0</ele></trkpt>\n"
                    + "    <trkpt lat=\"45.0020\" lon=\"6.0020\"><ele>1025.0</ele></trkpt>\n"
                    + "    <trkpt lat=\"45.0030\" lon=\"6.0030\"><ele>1040.0</ele></trkpt>\n"
                    + "  </trkseg></trk>\n"
                    + "</gpx>";

    private static final String ROUTE_ONLY_GPX =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<gpx version=\"1.1\" creator=\"test\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
                    + "  <rte><name>r</name>\n"
                    + "    <rtept lat=\"45.0000\" lon=\"6.0000\"><ele>1000.0</ele></rtept>\n"
                    + "    <rtept lat=\"45.0010\" lon=\"6.0010\"><ele>1010.0</ele></rtept>\n"
                    + "    <rtept lat=\"45.0020\" lon=\"6.0020\"><ele>1025.0</ele></rtept>\n"
                    + "  </rte>\n"
                    + "</gpx>";

    private static Path samplePath() {
        return GpxToPathJvm.firstTrackAsPath(GpxParserJvm.parse(GPX));
    }

    @Test
    public void parseTakesOneArgument() {
        GpxDocument shortForm = GpxParserJvm.parse(GPX);
        GpxDocument longForm = GpxParserJvm.parse(GPX, true);

        assertEquals(longForm, shortForm);
        assertEquals(1, shortForm.getTracks().size());
    }

    @Test
    public void simplifyDoesNotAskTheCallerForZExaggeration() {
        Path path = samplePath();

        Path shortForm = PathSimplifierJvm.simplify(path, 5.0);
        Path longForm = PathSimplifierJvm.simplify(path, 5.0, 3.0);

        assertEquals("the short form must mean zExaggeration = 3.0", longForm.getSize(), shortForm.getSize());
    }

    @Test
    public void smoothElevationHasADefaultWindow() {
        Path path = samplePath();

        Path shortForm = ElevationStepJvm.smoothElevation(path);
        Path longForm = ElevationStepJvm.smoothElevation(path, 150.0);

        for (int i = 0; i < path.getSize(); i++) {
            assertEquals("point " + i, longForm.elevation(i), shortForm.elevation(i), 1e-12);
        }
    }

    @Test
    public void writeGpxTakesJustAPath() {
        Path path = samplePath();

        String shortForm = GpxWriterJvm.write(path);
        String longForm = GpxWriterJvm.write(path, "noname", null, null, true);

        assertEquals(longForm, shortForm);
        assertTrue(shortForm.contains("<trkpt"));
    }

    @Test
    public void writeGpxWithoutExtensionsIsReachable() {
        String bare = GpxWriterJvm.write(samplePath(), "noname", null, null, false);

        assertTrue("no extensions expected: " + bare, !bare.contains("<extensions>"));
    }

    @Test
    public void writeGpxAcceptsADocument() {
        GpxDocument document = GpxParserJvm.parse(GPX);

        assertEquals(GpxWriterJvm.write(document, true), GpxWriterJvm.write(document));
    }

    @Test
    public void tabularWritersHaveDefaultOptions() {
        Path path = samplePath();

        assertEquals(TabularWritersJvm.writeCsv(path, new CsvOptions(null, ',', true, null, "\n")), TabularWritersJvm.writeCsv(path));
        assertTrue(TabularWritersJvm.writeJson(path).startsWith("{"));

        // And a non-default option is reachable without spelling the other four out.
        CsvOptions semicolons = TabularWritersJvm.csvOptions(null, ';');
        assertTrue(TabularWritersJvm.writeCsv(path, semicolons).contains(";"));
    }

    @Test
    public void gpxModelCanBeBuiltByHand() {
        GpxTrackPoint point = GpxModelJvm.trackPoint(45.0, 6.0);
        GpxWaypoint waypoint = GpxModelJvm.waypoint(45.0, 6.0);
        GpxTrack track = GpxModelJvm.track(List.of(point, point));
        GpxDocument document = GpxModelJvm.document(List.of(track));

        assertEquals(1, document.getTracks().size());
        assertEquals(2, track.getPoints().size());
        assertNotNull(waypoint);
        assertTrue(GpxWriterJvm.write(document).contains("<trkpt"));
    }

    @Test
    public void elevationTypesHaveJavaFactories() {
        LatLon coordinates = ElevationProviderJvm.latLon(45.0, 6.0);
        ElevationProvider provider = ElevationProviderJvm.newElevationProvider();

        assertEquals(45.0, coordinates.getLatitude(), 1e-12);
        assertEquals(null, coordinates.getElevation());
        assertEquals(12, provider.getConfig().getZoomLevel());
        assertEquals(512, ElevationProviderJvm.elevationProviderConfig(12, 100).getTileSize());
    }

    @Test
    public void tracksAsPathsTakesBothContainersByDefault() {
        GpxDocument document = GpxParserJvm.parse(GPX);

        List<Path> shortForm = GpxToPathJvm.tracksAsPaths(document);
        List<Path> longForm =
                GpxToPathJvm.tracksAsPaths(document, EnumSet.allOf(GpxPathKind.class));

        assertEquals("the short form must mean every kind", longForm.size(), shortForm.size());
        assertTrue(shortForm.size() > 0);
    }

    @Test
    public void routeOnlyDocumentIsNotEmptyThroughTheFacade() {
        // The default a Java caller could not spell before this facade: EnumSet.of(TRACK) is the
        // obvious guess and it drops every <rte>, which is exactly the pre-g24 regression.
        GpxDocument document = GpxParserJvm.parse(ROUTE_ONLY_GPX);

        assertEquals(1, GpxToPathJvm.tracksAsPaths(document).size());
        assertEquals(
                0, GpxToPathJvm.tracksAsPaths(document, EnumSet.of(GpxPathKind.TRACK)).size());
    }

    @Test
    public void fromCoordinatesReturnsAUsablePath() {
        // Before this, distance(i) stayed 0 until the caller thought to call computeDerivedData()
        // — a path that looked built and had no length.
        Path path =
                PathJvm.fromCoordinates(
                        List.of(
                                new LatLonElevation(45.0, 6.0, 1000.0),
                                new LatLonElevation(45.01, 6.0, 1010.0),
                                new LatLonElevation(45.02, 6.0, 1020.0)));

        assertTrue("distances must be derived by the factory", path.distance(2) > 0.0);
        assertTrue(path.getTotalDistance() > 0.0);
    }

    @Test
    public void withoutTimeClearsTheClockAndKeepsTheGeometry() {
        Path timed = PathJvm.fromCoordinates(
                List.of(
                        new LatLonElevation(45.0, 6.0, 1000.0),
                        new LatLonElevation(45.01, 6.0, 1010.0)));
        timed.setTime(0, 5_000.0);
        timed.setTime(1, 1_000.0); // backwards: what the FIT writer rejects
        timed.computeDerivedData();

        Path stripped = timed.withoutTime();

        assertEquals(0.0, stripped.time(0), 0.0);
        assertEquals(0.0, stripped.time(1), 0.0);
        assertEquals(timed.getTotalDistance(), stripped.getTotalDistance(), 1e-9);
        assertEquals("the source path is untouched", 5_000.0, timed.time(0), 0.0);
    }
}
