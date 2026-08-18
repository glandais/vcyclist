package io.github.glandais.elevation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * Task g22, written in <b>Java on purpose</b>: the bridges exist so a Java consumer never has to
 * touch a {@code Continuation}, and only a Java source proves that. From Kotlin every one of
 * these calls compiles whether the bridges exist or not, so a Kotlin test would assert nothing
 * about the property under test.
 */
public class ElevationProviderJavaTest {

    @Test
    public void getElevationBlockingIsCallableWithAndWithoutTheOptionalArgument() {
        ElevationProvider provider = JvmBridgeFixtures.syntheticProvider();

        double shortForm = ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0);
        double longForm = ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0, true);

        assertEquals("the @JvmOverloads short form must mean interpolation = true", longForm, shortForm, 0.0);
    }

    @Test
    public void getElevationsAlongIsReachableFromJavaAtAllThreeWidths() throws Exception {
        // Step S5: this was the ONE member of the public facade with no bridge at all, so a Java
        // caller could not invoke it without hand-writing a Continuation — and it is the one with
        // five defaulted parameters, so it is also the one that most needed the ladder.
        ElevationProvider provider = JvmBridgeFixtures.syntheticProvider();
        List<Coordinates> path = new ArrayList<>();
        path.add(ElevationProviderJvm.latLon(45.0, 6.0));
        path.add(ElevationProviderJvm.latLon(45.001, 6.001));

        List<CoordinatesElevation> shortForm =
                ElevationProviderJvm.getElevationsAlongBlocking(provider, path);
        List<CoordinatesElevation> configured =
                ElevationProviderJvm.getElevationsAlongBlocking(
                        provider,
                        path,
                        25.0,
                        1.0,
                        true,
                        ElevationProviderJvm.smoothingOptions(50.0, true),
                        ElevationProviderJvm.filterOptions(10.0, 3.0, true));
        List<CoordinatesElevation> async =
                ElevationProviderJvm.getElevationsAlongAsync(provider, path).get(60, TimeUnit.SECONDS);

        assertFalse("densifying two points must produce some", shortForm.isEmpty());
        assertFalse(configured.isEmpty());
        assertEquals("the future must agree with the blocking form", shortForm.size(), async.size());
    }

    @Test
    public void smoothingAndFilterOptionFactoriesDefaultToTheDataClasses() {
        // Both option types had no factory either, so `new SmoothingOptions()` did not compile and
        // every field became mandatory.
        assertEquals(JvmBridgeFixtures.defaultSmoothingOptions(), ElevationProviderJvm.smoothingOptions());
        assertEquals(JvmBridgeFixtures.defaultFilterOptions(), ElevationProviderJvm.filterOptions());
    }

    @Test
    public void setElevationsBlockingHandlesFiveHundredPoints() {
        ElevationProvider provider = JvmBridgeFixtures.syntheticProvider();
        List<Coordinates> coordinates = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            coordinates.add(new LatLon(i * 0.001, i * 0.002, null));
        }

        List<CoordinatesElevation> result = ElevationProviderJvm.setElevationsBlocking(provider, coordinates);

        assertEquals(500, result.size());
        for (CoordinatesElevation c : result) {
            assertTrue("elevation in synthetic range: " + c.getElevation(), c.getElevation() >= 0.0 && c.getElevation() <= 400.0);
        }
    }

    @Test
    public void asyncAgreesWithBlocking() throws Exception {
        ElevationProvider provider = JvmBridgeFixtures.syntheticProvider();
        List<Coordinates> coordinates = List.of(new LatLon(0.0, 0.0, null), new LatLon(10.0, 20.0, null));

        List<CoordinatesElevation> blocking = ElevationProviderJvm.setElevationsBlocking(provider, coordinates);
        List<CoordinatesElevation> async =
                ElevationProviderJvm.setElevationsAsync(provider, coordinates).get(30, TimeUnit.SECONDS);

        assertEquals(blocking, async);
    }

    @Test
    public void blockingPropagatesTheFailureUnwrapped() {
        ElevationProvider provider = JvmBridgeFixtures.failingProvider("tile store is down");

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0));

        // ElevationCalculator wraps with its own context; what matters is that the original
        // message survives the bridge rather than being swallowed by a Continuation.
        assertTrue(
                "message should carry the root cause, was " + thrown.getMessage(),
                thrown.getMessage().contains("tile store is down"));
    }

    @Test
    public void asyncReportsTheFailureAsACompletionException() {
        ElevationProvider provider = JvmBridgeFixtures.failingProvider("tile store is down");

        CompletableFuture<Double> future = ElevationProviderJvm.getElevationAsync(provider, 0.0, 0.0);

        ExecutionException thrown = assertThrows(ExecutionException.class, () -> future.get(30, TimeUnit.SECONDS));
        assertTrue(
                "cause should be the original IllegalStateException, was " + thrown.getCause(),
                thrown.getCause() instanceof IllegalStateException);
        assertTrue(
                "message should carry the root cause, was " + thrown.getCause().getMessage(),
                thrown.getCause().getMessage().contains("tile store is down"));
    }

    @Test
    public void asyncRunsOnTheSuppliedExecutor() throws Exception {
        ElevationProvider provider = JvmBridgeFixtures.syntheticProvider();
        AtomicReference<String> threadName = new AtomicReference<>();
        ExecutorService executor =
                Executors.newSingleThreadExecutor(
                        runnable -> {
                            Thread thread = new Thread(runnable, "g22-bridge-pool");
                            thread.setDaemon(true);
                            return thread;
                        });
        try {
            Executor recording =
                    command ->
                            executor.execute(
                                    () -> {
                                        threadName.compareAndSet(null, Thread.currentThread().getName());
                                        command.run();
                                    });

            Double elevation =
                    ElevationProviderJvm.getElevationAsync(provider, 0.0, 0.0, true, recording).get(30, TimeUnit.SECONDS);

            assertNotNull(elevation);
            assertEquals("the caller's executor must be the one used", "g22-bridge-pool", threadName.get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void cancellingTheFutureCancelsTheCoroutine() throws Exception {
        JvmBridgeFixtures.SlowProvider slow = new JvmBridgeFixtures.SlowProvider();

        CompletableFuture<Double> future = ElevationProviderJvm.getElevationAsync(slow.getProvider(), 0.0, 0.0);
        JvmBridgeFixtures.awaitStarted(slow);

        assertTrue("future must report that it was cancelled", future.cancel(true));

        // The coroutine observes the cancellation asynchronously; give it a bounded window.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!slow.wasCancelled() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("the suspended work must be cancelled, not left running", slow.wasCancelled());
    }
}
