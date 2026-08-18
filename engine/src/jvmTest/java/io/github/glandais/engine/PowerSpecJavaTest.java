package io.github.glandais.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.github.glandais.engine.gpx.GpxParser;
import io.github.glandais.engine.gpx.GpxToPathKt;
import io.github.glandais.engine.path.Path;
import io.github.glandais.engine.physics.CyclistPowerSpec;
import io.github.glandais.engine.physics.PowerModel;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Pins that the classes the three wire doors parse into are reachable from <b>Java</b>, which is
 * the only thing no Kotlin test can check.
 *
 * <p>Step S5 of {@code docs/tasks/surface-alignment.md}. {@link CyclistPowerSpec} is the single
 * class the CLI, the JS façade and the WASI module all parse into, and it had no factory at all:
 * changing one field from Java meant passing all seven arguments positionally and naming
 * {@code DEFAULT_CYCLIST_POWER_W}, {@code DEFAULT_CRITICAL_POWER_W} and {@code DEFAULT_W_PRIME_J}
 * yourself, because {@code copy()} is Kotlin-only. {@link CoursePhysics} and {@link Course} were
 * in the same position, and {@code EnhancerJvm.enhanceCourseBlocking} needs a {@code CoursePhysics}
 * no factory produced — so a Java caller could configure a rider and then had nowhere to hand it.
 *
 * <p>Every assertion below is written the short way on purpose. If a factory disappears or narrows,
 * this stops compiling, which is the point.
 */
public class PowerSpecJavaTest {

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
        return GpxToPathKt.firstTrackAsPath(GpxParser.INSTANCE.parse(GPX, true));
    }

    @Test
    public void cyclistPowerSpecIsReachableWithoutNamingEveryDefault() {
        // The whole point: one field, and the other six keep the engine's defaults.
        CyclistPowerSpec spec = EngineModelJvm.cyclistPowerSpec(PowerModel.CRITICAL_POWER);

        assertEquals(PowerModel.CRITICAL_POWER, spec.getModel());
        assertEquals(
                "the untouched fields must be the class's own defaults, not this test's guesses",
                new CyclistPowerSpec().getPowerW(),
                spec.getPowerW(),
                1e-12);
        assertNotNull("and the spec has to be usable, not merely constructible", spec.toProvider());
    }

    @Test
    public void aFullyConfiguredRideCanBeAssembledAndRunFromJava() throws Exception {
        Course course =
                EngineModelJvm.course(
                        samplePath(), EngineModelJvm.cyclist(72.0), EngineModelJvm.bike());
        CoursePhysics physics =
                EngineModelJvm.coursePhysics(
                        course, EngineModelJvm.cyclistPowerSpec(PowerModel.CONSTANT, 200.0).toProvider());

        // This is the call that had no reachable argument before S5.
        Path enhanced =
                EnhancerJvm.enhanceCourseAsync(physics, EngineModelJvm.enhanceOptions(false))
                        .get(60, TimeUnit.SECONDS);

        assertTrue("the pipeline must produce a path", enhanced.getSize() > 0);
    }

    @Test
    public void coursePhysicsDefaultsComeFromTheClass() {
        Course course = EngineModelJvm.course(samplePath());
        CoursePhysics physics = EngineModelJvm.coursePhysics(course);

        assertEquals(
                "a default-argument factory must agree with the data class it builds",
                JvmBridgeFixtures.defaultCoursePhysics(course),
                physics);
    }
}
