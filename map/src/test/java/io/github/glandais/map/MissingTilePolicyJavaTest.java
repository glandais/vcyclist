package io.github.glandais.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.glandais.engine.path.Path;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins that the strict mode is reachable from Java in one line, with the checked exception
 * catchable — no Kotlin test can see either. If {@code @Throws} is dropped, {@code catch
 * (IOException e)} below stops compiling.
 */
class MissingTilePolicyJavaTest {
    private static Path stelvio() {
        Path p = new Path(2);
        p.setLatitude(0, Math.toRadians(46.5318));
        p.setLongitude(0, Math.toRadians(10.4439));
        p.setLatitude(1, Math.toRadians(46.5320));
        p.setLongitude(1, Math.toRadians(10.4591));
        p.setTime(1, 1000.0);
        p.computeDerivedData();
        return p;
    }

    @Test
    void failPolicyThrowsACatchableIOExceptionAndWritesNothing() throws Exception {
        File cache = Files.createTempDirectory("vcyclist-tiles").toFile();
        File out = new File(cache, "out.png");
        try {
            TileMapProducer viaConstructor = new TileMapProducer(cache, url -> null, MissingTilePolicy.FAIL);
            TileMapProducer viaFactory = MapFactoriesJvm.tileMapProducer(cache, url -> null, MissingTilePolicy.FAIL);
            for (TileMapProducer producer : List.of(viaConstructor, viaFactory)) {
                boolean caught = false;
                try {
                    producer.createTileMap(out, List.of(stelvio()), "https://t.example.invalid/{z}/{x}/{y}.png",
                            TileMapProducer.DEFAULT_MARGIN, 256);
                } catch (IOException e) {
                    caught = true;
                    assertTrue(e.getMessage().contains("t.example.invalid"), e.getMessage());
                }
                assertTrue(caught, "FAIL must throw IOException");
                assertFalse(out.exists(), "no output file may be left behind");
            }
        } finally {
            Files.walk(cache.toPath()).sorted(java.util.Comparator.reverseOrder()).map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    void theTwoArgumentFormsStillDefaultToSkip() throws Exception {
        File cache = Files.createTempDirectory("vcyclist-tiles").toFile();
        File out = new File(cache, "out.png");
        try {
            MapImage map = new TileMapProducer(cache, url -> null)
                    .createTileMap(out, List.of(stelvio()), "https://t.example.invalid/{z}/{x}/{y}.png",
                            TileMapProducer.DEFAULT_MARGIN, 256);
            assertTrue(map.getMissingTileCount() > 0);
            assertTrue(out.isFile());
            assertThrows(IllegalArgumentException.class, () -> MapFactoriesJvm.tileMapProducer(cache)
                    .createTileMap(out, List.of(stelvio()), " ", TileMapProducer.DEFAULT_MARGIN, 256));
        } finally {
            Files.walk(cache.toPath()).sorted(java.util.Comparator.reverseOrder()).map(java.nio.file.Path::toFile)
                    .forEach(File::delete);
        }
    }
}
