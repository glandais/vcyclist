package io.github.glandais.elevation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

/**
 * The DEM source catalogue, from <b>Java</b>: the enum, its two {@code config} overloads, the
 * provider factories and the BIL decoder a custom fetcher needs for an IGN tile. Offline — the
 * providers are built, never asked.
 */
public class ElevationSourceJavaTest {

    @Test
    public void aSourceIsNamedByEnumOrByWireId() {
        assertEquals(ElevationSource.IGN_LIDAR_HD, ElevationSource.Companion.byId("ign-lidar-hd"));
        assertEquals(ElevationSource.MAPTERHORN, ElevationSource.Companion.getDEFAULT());
    }

    @Test
    public void configHasAJavaOverloadWithoutTheZoom() {
        ElevationProviderConfig config = ElevationSource.IGN_LIDAR_HD.config();
        assertEquals(TileEncoding.BIL32, config.getEncoding());
        assertEquals(ElevationSource.IGN_LIDAR_HD.getDefaultZoomLevel(), config.getZoomLevel());
        assertEquals(13, ElevationSource.IGN_LIDAR_HD.config(13).getZoomLevel());
    }

    @Test
    public void theFactoriesChainTheFallback() {
        ElevationProvider ign = ElevationProviderJvm.newElevationProvider(ElevationSource.IGN_LIDAR_HD);
        assertNotNull(ign.getFallback());
        assertEquals(ElevationSource.MAPTERHORN.config(), ign.getFallback().getConfig());

        ElevationProvider zoomed = ElevationProviderJvm.newElevationProvider(ElevationSource.IGN_LIDAR_HD, 14);
        assertEquals(14, zoomed.getConfig().getZoomLevel());
        assertEquals("the fallback keeps its own zoom", 12, zoomed.getFallback().getConfig().getZoomLevel());

        assertNull(ElevationProviderJvm.newElevationProvider(ElevationSource.MAPTERHORN).getFallback());
    }

    @Test
    public void aBilTileDecodesFromJava() {
        ByteBuffer bil = ByteBuffer.allocate(2 * 2 * 4).order(ByteOrder.LITTLE_ENDIAN);
        bil.putFloat(100f).putFloat(200f).putFloat(300f).putFloat(-9999f);

        RawTile raw = TileFetcherJvm.decodeBil32TileBytes(bil.array(), 2);
        Tile tile = new Tile(raw);
        TileCoordinates origin = new TileCoordinates(0, 0, 0);

        assertEquals(300.0, tile.getElevation(new Pixel(origin, 0, 1)), 0.01);
        assertEquals(Double.NaN, tile.getElevation(new Pixel(origin, 1, 1)), 0.0);
    }

    @Test
    public void theConfigFactoryTakesTheEncoding() {
        ElevationProviderConfig config =
                ElevationProviderJvm.elevationProviderConfig(
                        15,
                        10,
                        "https://example.invalid/wms?BBOX={bbox}",
                        256,
                        ElevationProviderJvm.attribution("mine"),
                        TileEncoding.BIL32);
        assertEquals(TileEncoding.BIL32, config.getEncoding());
    }
}
