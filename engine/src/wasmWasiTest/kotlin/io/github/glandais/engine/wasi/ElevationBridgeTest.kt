package io.github.glandais.engine.wasi

import io.github.glandais.elevation.ElevationFunctions
import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants
import io.github.glandais.elevation.RawTile
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That elevations land on the **right points** when the pipeline runs through
 * [runSynchronously].
 *
 * This test exists because they did not. The w05 smoke against a real host gave a profile 3 to
 * 9 m away from the JVM's on the same trace, from identical coordinates and byte-identical
 * tiles; a third implementation of the Terrarium formulas (Python) agreed with the JVM. The
 * cause is on this side of the boundary, in how the coroutine machinery of `BatchCalculator` —
 * `coroutineScope { async { … } }` behind a `Semaphore`, results written by index — behaves
 * under the dispatcher the bridge picks.
 *
 * The check does not need a host: a fetcher is injected, and each fake tile is uniform, so the
 * expected elevation of a point is a pure function of the tile it falls in. If a result lands on
 * the wrong index, the value is off by a whole tile and the assertion is unmissable.
 */
class ElevationBridgeTest {
    private val zoom = 12
    private val tileSize = 4

    /**
     * A **linear ramp** in global pixel coordinates, so the expected value is a closed form.
     *
     * A uniform-per-tile fake was the first attempt and it was wrong: bilinear interpolation
     * legitimately reaches across a tile edge, so a point near a boundary mixes two tiles and no
     * per-tile constant can predict it. A plane is the fixture that survives interpolation —
     * bilinear interpolation of a linear function is that same function, exactly. Both slopes are
     * multiples of 1/256, so Terrarium encodes them without rounding.
     */
    private fun rampElevation(
        globalX: Double,
        globalY: Double,
    ): Double = 1000.0 + 0.5 * globalX + 0.25 * globalY

    /** The ramp, sampled over one tile and Terrarium-encoded. */
    private fun rampTile(
        tileX: Int,
        tileY: Int,
    ): RawTile {
        val rgba = ByteArray(tileSize * tileSize * 4)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val elevation =
                    rampElevation(
                        (tileX * tileSize + px).toDouble(),
                        (tileY * tileSize + py).toDouble(),
                    )
                val raw = ((elevation + 32768.0) * 256.0).toInt()
                val o = (py * tileSize + px) * 4
                rgba[o] = ((raw shr 16) and 0xFF).toByte()
                rgba[o + 1] = ((raw shr 8) and 0xFF).toByte()
                rgba[o + 2] = (raw and 0xFF).toByte()
                rgba[o + 3] = 255.toByte()
            }
        }
        return RawTile(tileSize, tileSize, rgba)
    }

    private fun provider(): ElevationProvider =
        ElevationProvider(
            ElevationProviderConfig(
                zoomLevel = zoom,
                tileSize = tileSize,
                cacheSize = 32,
                tileUrlTemplate = "fake://{z}/{x}/{y}",
            ),
        ) { url ->
            val parts = url.removePrefix("fake://").split('/')
            rampTile(parts[1].toInt(), parts[2].toInt())
        }

    /** Points spread over several tiles, so a mix-up cannot hide inside one tile. */
    private fun path(): Path {
        val lats = listOf(46.53, 46.60, 45.10, 46.53, 44.20, 46.60, 43.75, 45.10)
        val lons = listOf(10.44, 11.90, 6.20, 10.44, 5.10, 11.90, 7.30, 6.20)
        val p = Path(lats.size)
        for (i in lats.indices) {
            p.setLatitude(i, lats[i] * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, lons[i] * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 0.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    private fun expectedAt(
        i: Int,
        source: Path,
    ): Double {
        val pf =
            ElevationFunctions.toPixelFloat(
                LatLon(source.latitudeDeg(i), source.longitudeDeg(i)),
                zoom,
                tileSize,
            )
        return rampElevation(
            pf.tile.x * tileSize + pf.x,
            pf.tile.y * tileSize + pf.y,
        )
    }

    @Test
    fun `every point gets the elevation of its own tile, not of a neighbour's`() {
        val source = path()

        val fixed = runSynchronously { ElevationStep.fixElevation(source, provider()) }

        for (i in 0 until source.size) {
            assertEquals(
                expectedAt(i, source),
                fixed.elevation(i),
                0.02,
                "point $i (${source.latitudeDeg(i)}, ${source.longitudeDeg(i)}) got the wrong tile's elevation",
            )
        }
    }

    @Test
    fun `repeated coordinates resolve to the same elevation, cache or no cache`() {
        val source = path()

        val fixed = runSynchronously { ElevationStep.fixElevation(source, provider()) }

        // Points 0 and 3, 1 and 5, 2 and 7 are the same coordinates.
        assertEquals(fixed.elevation(0), fixed.elevation(3), 1e-9, "0 and 3 are the same place")
        assertEquals(fixed.elevation(1), fixed.elevation(5), 1e-9, "1 and 5 are the same place")
        assertEquals(fixed.elevation(2), fixed.elevation(7), 1e-9, "2 and 7 are the same place")
    }
}
