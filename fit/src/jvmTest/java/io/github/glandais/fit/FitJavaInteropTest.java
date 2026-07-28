package io.github.glandais.fit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.glandais.elevation.MathConstants;
import io.github.glandais.engine.path.Path;
import java.util.List;
import kotlin.time.Instant;
import org.junit.Test;

/**
 * Task g27 for `:fit`, in <b>Java on purpose</b>: the short forms below are what a Java consumer
 * writes, and nothing in a Kotlin test can prove they exist.
 */
public class FitJavaInteropTest {

    private static final Instant START = Instant.Companion.parse("2026-07-28T08:00:00Z");

    private static Path syntheticPath(double latBase) {
        Path p = new Path(4);
        for (int i = 0; i < 4; i++) {
            p.setLatitude(i, (latBase + i * 0.001) * MathConstants.DEG_TO_RAD);
            p.setLongitude(i, (6.39 + i * 0.001) * MathConstants.DEG_TO_RAD);
            p.setElevation(i, 350.0 + i);
            p.setTime(i, i * 10_000.0);
        }
        p.computeDerivedData();
        return p;
    }

    @Test
    public void toFitBytesDoesNotAskForTheSport() {
        Path path = syntheticPath(45.68);

        byte[] shortForm = PathToFitJvm.toFitBytes(path, "course", START);
        byte[] longForm = PathToFitJvm.toFitBytes(path, "course", START, FitSport.CYCLING);

        assertArrayEquals("the short form must mean CYCLING", longForm, shortForm);
        assertTrue(shortForm.length > 0);
    }

    @Test
    public void toFitCourseIsReachableInItsShortForm() {
        FitCourse course = PathToFitJvm.toFitCourse(syntheticPath(45.68), "course", START);

        assertEquals(4, course.getRecords().size());
        assertEquals(START, course.getRecords().get(0).getTimestamp());
    }

    @Test
    public void multiPathFormNeedsNeitherSportNorGap() {
        List<Path> paths = List.of(syntheticPath(45.68), syntheticPath(46.0));

        FitCourse course = PathToFitJvm.toFitCourse(paths, "multi", START);
        byte[] bytes = PathToFitJvm.toFitBytes(paths, "multi", START);

        assertEquals(2, course.getSegments().size());
        assertTrue(bytes.length > 0);
    }

    @Test
    public void interPathGapIsExpressedInMilliseconds() {
        List<Path> paths = List.of(syntheticPath(45.68), syntheticPath(46.0));

        FitCourse course = PathToFitJvm.toFitCourse(paths, "multi", START, FitSport.CYCLING, 300_000L);

        long firstEnd =
                course.getSegments().get(0).getRecords().get(3).getTimestamp().toEpochMilliseconds();
        long secondStart =
                course.getSegments().get(1).getRecords().get(0).getTimestamp().toEpochMilliseconds();
        assertEquals(300_000L, secondStart - firstEnd);
    }

    @Test
    public void epochMillisecondsFormMatchesTheInstantForm() {
        // The point of the overload: a Java caller starting from path.time(0) — a Double of epoch
        // milliseconds — no longer has to name kotlin.time.Instant to get back to one.
        List<Path> paths = List.of(syntheticPath(45.68), syntheticPath(46.0));
        long startMs = START.toEpochMilliseconds();

        assertArrayEquals(
                PathToFitJvm.toFitBytes(paths, "multi", START),
                PathToFitJvm.toFitBytes(paths, "multi", startMs));
        assertArrayEquals(
                PathToFitJvm.toFitBytes(paths.get(0), "course", START),
                PathToFitJvm.toFitBytes(paths.get(0), "course", startMs));
        assertEquals(
                START,
                PathToFitJvm.toFitCourse(paths, "multi", startMs).getRecords().get(0).getTimestamp());
    }

    @Test
    public void withoutTimeMakesANonMonotonicPathEncodable() {
        Path path = syntheticPath(45.68);
        path.setTime(2, 1.0); // clock steps backwards, as a resynced head unit does
        path.computeDerivedData();

        try {
            PathToFitJvm.toFitBytes(path, "course", START);
            fail("the writer must still reject a non-monotonic path");
        } catch (IllegalArgumentException expected) {
            // the precondition this method exists to let callers satisfy
        }

        assertTrue(PathToFitJvm.toFitBytes(path.withoutTime(), "course", START).length > 0);
    }
}
