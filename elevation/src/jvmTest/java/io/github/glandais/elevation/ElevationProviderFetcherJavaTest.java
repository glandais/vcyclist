package io.github.glandais.elevation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * Task g32, in <b>Java on purpose</b>: a Java consumer must be able to supply its own tile
 * fetcher — the point where a disk cache plugs in — without writing a line of Kotlin.
 *
 * <p>Every fetcher here serves tiles from memory, so a network call would be a failure, not a
 * slow test: the URL template points at a scheme nothing can resolve.
 */
public class ElevationProviderFetcherJavaTest {

    private static final int TILE_SIZE = 4;

    private static ElevationProviderConfig config() {
        return ElevationProviderJvm.elevationProviderConfig(0, 100, "test://{z}/{x}/{y}", TILE_SIZE);
    }

    /** Elevation at pixel (px, py) is px * 100 + py, like the Kotlin fixtures. */
    private static RawTile syntheticTile() {
        byte[] rgba = new byte[TILE_SIZE * TILE_SIZE * 4];
        for (int py = 0; py < TILE_SIZE; py++) {
            for (int px = 0; px < TILE_SIZE; px++) {
                int raw = px * 100 + py + 32768;
                int idx = (py * TILE_SIZE + px) * 4;
                rgba[idx] = (byte) ((raw >> 8) & 0xFF);
                rgba[idx + 1] = (byte) (raw & 0xFF);
                rgba[idx + 2] = 0;
                rgba[idx + 3] = (byte) 255;
            }
        }
        return new RawTile(TILE_SIZE, TILE_SIZE, rgba);
    }

    @Test
    public void aJavaFetcherServesTilesWithoutTouchingTheNetwork() {
        AtomicInteger calls = new AtomicInteger();
        ElevationProvider provider =
                ElevationProviderJvm.newElevationProvider(
                        config(),
                        url -> {
                            calls.incrementAndGet();
                            return syntheticTile();
                        });

        double elevation = ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0);

        assertTrue("elevation in the synthetic range: " + elevation, elevation >= 0.0 && elevation <= 400.0);
        assertEquals("exactly one tile was needed", 1, calls.get());
    }

    @Test
    public void theFetcherSeesUrlsBuiltFromTheConfiguredTemplate() {
        List<String> urls = new ArrayList<>();
        ElevationProvider provider =
                ElevationProviderJvm.newElevationProvider(
                        config(),
                        url -> {
                            urls.add(url);
                            return syntheticTile();
                        });

        ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0);

        assertEquals(List.of("test://0/0/0"), urls);
    }

    @Test
    public void theShortOverloadUsesTheDefaultConfiguration() {
        ElevationProvider provider = ElevationProviderJvm.newElevationProvider(url -> syntheticTile());

        assertEquals(12, provider.getConfig().getZoomLevel());
    }

    @Test
    public void theLibrarysOwnCacheStillSitsInFront() {
        AtomicInteger calls = new AtomicInteger();
        ElevationProvider provider =
                ElevationProviderJvm.newElevationProvider(
                        config(),
                        url -> {
                            calls.incrementAndGet();
                            return syntheticTile();
                        });

        // Two points of the same tile: the caller's fetcher must be asked once, not twice. The two
        // cache levels are complementary — decoded tiles here, compressed bytes on the caller's side.
        ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0);
        ElevationProviderJvm.getElevationBlocking(provider, 0.001, 0.001);

        assertEquals(1, calls.get());
    }

    @Test
    public void aFailingFetcherPropagatesItsException() {
        ElevationProvider provider =
                ElevationProviderJvm.newElevationProvider(
                        config(),
                        url -> {
                            throw new IllegalStateException("cache directory is read-only");
                        });

        IllegalStateException thrown =
                assertThrows(
                        IllegalStateException.class,
                        () -> ElevationProviderJvm.getElevationBlocking(provider, 0.0, 0.0));

        assertTrue(
                "message should carry the root cause, was " + thrown.getMessage(),
                thrown.getMessage().contains("cache directory is read-only"));
    }

    /**
     * The test that separates the correct implementation from the naive one. A fetcher that
     * blocks must run on a pool that tolerates blocking; adapting it without {@code
     * Dispatchers.IO} would serialise the tiles and the elapsed time would be the sum rather than
     * roughly the longest single fetch.
     *
     * <p>Zoom 4 rather than 0, because at zoom 0 the whole world is one tile and there would be
     * nothing to fetch in parallel.
     */
    @Test
    public void blockingFetchersRunInParallel() {
        int tiles = 4;
        long sleepMs = 200;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger peakConcurrent = new AtomicInteger();

        ElevationProvider provider =
                ElevationProviderJvm.newElevationProvider(
                        ElevationProviderJvm.elevationProviderConfig(4, 100, "test://{z}/{x}/{y}", TILE_SIZE),
                        url -> {
                            peakConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                            try {
                                Thread.sleep(sleepMs);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                concurrent.decrementAndGet();
                            }
                            return syntheticTile();
                        });

        List<Coordinates> coordinates =
                List.of(
                        new LatLon(0.0, -170.0, null),
                        new LatLon(0.0, -60.0, null),
                        new LatLon(0.0, 60.0, null),
                        new LatLon(0.0, 170.0, null));

        long startNanos = System.nanoTime();
        List<CoordinatesElevation> result = ElevationProviderJvm.setElevationsBlocking(provider, coordinates);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(coordinates.size(), result.size());
        assertTrue("expected overlapping fetches, peak was " + peakConcurrent.get(), peakConcurrent.get() > 1);
        assertTrue(
                "expected parallel fetches, took " + elapsedMs + " ms for " + tiles + " tiles of " + sleepMs + " ms",
                elapsedMs < tiles * sleepMs);
    }

    @Test
    public void attributionIsReachableFromTheConfigFactory() {
        Attribution mine = ElevationProviderJvm.attribution("My own DEM", "https://example.org/licence");

        ElevationProviderConfig config =
                ElevationProviderJvm.elevationProviderConfig(12, 100, "https://tiles.example.org/{z}/{x}/{y}.webp", 512, mine);

        assertEquals("My own DEM", config.getAttribution().getText());
        assertEquals("https://example.org/licence", config.getAttribution().getUrl());
        // A caller serving their own tiles must not keep crediting Mapterhorn.
        assertEquals(mine, ElevationProviderJvm.newElevationProvider(config, url -> syntheticTile()).getAttribution());
    }

    @Test
    public void attributionDefaultsAreUntouchedByTheShortForms() {
        ElevationProviderConfig shortForm = ElevationProviderJvm.elevationProviderConfig(12, 100);
        ElevationProviderConfig kotlinDefault = ElevationProviderJvm.elevationProviderConfig();

        assertEquals(kotlinDefault.getAttribution(), shortForm.getAttribution());
        assertTrue(shortForm.getAttribution().getText().contains("Mapterhorn"));
        // url is a Kotlin default on Attribution too — the one-argument form must compile.
        assertEquals(null, ElevationProviderJvm.attribution("no link").getUrl());
    }
}
