package io.github.glandais.elevation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.Test;

/**
 * {@link TileStore} in <b>Java on purpose</b>: a caller-owned store implemented as a plain Java
 * class, and the provider factories that take it. Offline — the providers are built, never asked.
 */
public class TileStoreJavaTest {

    static final class MapStore implements TileStore {
        final Map<String, byte[]> entries = new ConcurrentHashMap<>();

        @Override
        public byte[] get(TileKey key) {
            return entries.get(key.relativePath());
        }

        @Override
        public void put(TileKey key, byte[] bytes) {
            entries.put(key.relativePath(), bytes);
        }
    }

    @Test
    public void theKeyNamesTheLayerAndTheTile() {
        TileKey key = new TileKey(TileKey.MAPTERHORN, 12, 2047, 1400, "webp");
        assertEquals("mapterhorn/12/2047/1400.webp", key.relativePath());
        assertEquals(2047, key.getX());
        assertEquals("ign-lidar-hd-mns", TileKey.IGN_LIDAR_HD_MNS);
    }

    @Test
    public void theFactoriesTakeAStore() {
        MapStore store = new MapStore();
        ElevationProvider ign = ElevationProviderJvm.newElevationProvider(ElevationSource.IGN_LIDAR_HD, store);
        assertNotNull("bridge decks: the MNS rides along", ign.getSurfaceModel());
        assertEquals(ElevationSource.MAPTERHORN.config(), ign.getFallback().getConfig());

        ElevationProvider zoomed = ElevationProviderJvm.newElevationProvider(ElevationSource.IGN_LIDAR_HD, 14, store);
        assertEquals(14, zoomed.getConfig().getZoomLevel());
        assertEquals(12, ElevationProviderJvm.newElevationProvider(ElevationSource.MAPTERHORN, store).getConfig().getZoomLevel());
    }
}
