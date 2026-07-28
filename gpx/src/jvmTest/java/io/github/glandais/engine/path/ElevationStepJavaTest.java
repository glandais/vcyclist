package io.github.glandais.engine.path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.glandais.elevation.ElevationProvider;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/**
 * Task g22, in <b>Java on purpose</b>: {@code ElevationStep.fixElevation} is {@code suspend},
 * so before the bridge a Java caller had to hand-write a {@code Continuation}. Only a Java
 * source proves the door is open — from Kotlin this file would compile either way.
 */
public class ElevationStepJavaTest {

    @Test
    public void fixElevationBlockingRewritesEveryElevation() {
        Path source = JvmBridgeFixtures.samplePath();
        ElevationProvider provider = JvmBridgeFixtures.flatProvider(742);

        Path fixed = ElevationStepJvm.fixElevationBlocking(source, provider);

        assertEquals("size is preserved", source.getSize(), fixed.getSize());
        for (int i = 0; i < fixed.getSize(); i++) {
            assertEquals("point " + i, 742.0, fixed.elevation(i), 0.5);
        }
    }

    @Test
    public void fixElevationAsyncAgreesWithBlocking() throws Exception {
        Path source = JvmBridgeFixtures.samplePath();
        ElevationProvider provider = JvmBridgeFixtures.flatProvider(300);

        Path blocking = ElevationStepJvm.fixElevationBlocking(source, provider);
        CompletableFuture<Path> future = ElevationStepJvm.fixElevationAsync(source, provider);
        Path async = future.get(30, TimeUnit.SECONDS);

        assertEquals(blocking.getSize(), async.getSize());
        for (int i = 0; i < blocking.getSize(); i++) {
            assertEquals("point " + i, blocking.elevation(i), async.elevation(i), 1e-9);
        }
    }

    @Test
    public void smoothElevationNeedsNoBridge() {
        // Documents the scope of g22 rather than testing it: the synchronous half of
        // ElevationStep was already callable from Java, and stays the way to call it.
        Path source = JvmBridgeFixtures.samplePath();

        Path smoothed = ElevationStep.INSTANCE.smoothElevation(source, 150.0);

        assertTrue(smoothed.getSize() > 0);
    }
}
