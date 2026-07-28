package io.github.glandais.engine.path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import io.github.glandais.elevation.LatLonElevation;
import io.github.glandais.elevation.Vector3D;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Task g27 for `PathWind`, in <b>Java on purpose</b>: the facade's whole point is that the name
 * `PathWindJvm` is the API's, whereas `PathWindKt` is the compiler's and moves when the Kotlin
 * file is renamed. Only a Java source can pin that.
 *
 * <p>It also pins the frame, which the KDoc describes and no Kotlin test states in Java terms: the
 * returned vector is east-north, so a northbound course yields {@code y < 0} — the opposite sign
 * from the Web Mercator screen frame gpx2web consumers came from.
 */
public class PathWindJavaTest {

    private static Path meridian(double startLat, double stepDeg) {
        List<LatLonElevation> coordinates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            coordinates.add(new LatLonElevation(startLat + (i * stepDeg), 5.0, 100.0));
        }
        return PathJvm.fromCoordinates(coordinates);
    }

    @Test
    public void bothAritiesAreReachableUnderTheFacadeName() {
        Path path = meridian(45.0, 0.01);

        Vector3D single = PathWindJvm.dominantHeadwindDirection(path);
        Vector3D multiple = PathWindJvm.dominantHeadwindDirection(List.of(path));

        assertNotNull(single);
        assertNotNull(multiple);
        assertEquals(single.getX(), multiple.getX(), 1e-12);
        assertEquals(single.getY(), multiple.getY(), 1e-12);
    }

    @Test
    public void northboundCourseYieldsANegativeNorthComponent() {
        Vector3D vector = PathWindJvm.dominantHeadwindDirection(List.of(meridian(45.0, 0.01)));

        assertNotNull(vector);
        assertEquals("y is north, so a northbound course points south", -1.0, vector.getY(), 1e-9);
        assertEquals(0.0, vector.getX(), 1e-9);
        assertEquals(0.0, vector.getZ(), 1e-12);
    }

    @Test
    public void tooFewPointsYieldsNullRatherThanAZeroVector() {
        Path stub =
                PathJvm.fromCoordinates(
                        List.of(
                                new LatLonElevation(45.0, 5.0, 100.0),
                                new LatLonElevation(45.1, 5.0, 100.0)));

        assertNull(PathWindJvm.dominantHeadwindDirection(List.of(stub)));
    }

    @Test
    public void theAzimuthSignalsItsAbsenceWithNaNNotZero() {
        Path stub =
                PathJvm.fromCoordinates(
                        List.of(
                                new LatLonElevation(45.0, 5.0, 100.0),
                                new LatLonElevation(45.1, 5.0, 100.0)));

        assertTrue(Double.isNaN(PathWindJvm.dominantHeadwindAzimuthDeg(List.of(stub))));
        assertTrue(Double.isNaN(PathWindJvm.dominantHeadwindAzimuthDeg(stub)));

        double real = PathWindJvm.dominantHeadwindAzimuthDeg(List.of(meridian(45.0, 0.01)));
        assertTrue("0.0 is due north, a valid answer", !Double.isNaN(real));
    }
}
