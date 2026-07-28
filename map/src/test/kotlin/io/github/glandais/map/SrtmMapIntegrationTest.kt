package io.github.glandais.map

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders from a real [ElevationProvider], downloading real DEM tiles. **Gated behind
 * `INTEGRATION=1`** like the `:elevation` integration tests and [TileMapIntegrationTest].
 *
 * Gated rather than merely slow-marked because it pulls from a third-party tile service. Running
 * it on every build would put load on a service that never agreed to it, and would make the suite
 * fail whenever the network hiccups.
 *
 * Set `VCYCLIST_DEM_URL` to point at a different DEM source; otherwise the provider's own default
 * applies, which is the one `:elevation` already ships and attributes.
 */
class SrtmMapIntegrationTest {
    private val enabled = System.getenv("INTEGRATION") == "1"

    /** A real alpine track: the Stelvio, which has plenty of relief to render. */
    private fun stelvio(): Path {
        val coords =
            listOf(
                46.5318 to 10.4439,
                46.5325 to 10.4500,
                46.5320 to 10.4591,
                46.5280 to 10.4620,
            )
        val p = Path(coords.size)
        for ((i, ll) in coords.withIndex()) {
            p.setLatitude(i, ll.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, ll.second * MathConstants.DEG_TO_RAD)
            p.setElevation(i, 2600.0 + i * 40.0)
            p.setTime(i, i * 1000.0)
        }
        p.computeDerivedData()
        return p
    }

    @Test
    fun `case 10 — a real mountain track renders a non-uniform relief`() {
        if (!enabled) {
            println("Skipping: set INTEGRATION=1 to run (this test downloads real DEM tiles)")
            return
        }
        val demUrl = System.getenv("VCYCLIST_DEM_URL")
        val config =
            if (demUrl != null) ElevationProviderConfig(tileUrlTemplate = demUrl) else ElevationProviderConfig()

        val out = File.createTempFile("vcyclist-srtm-integration", ".png")
        try {
            val map =
                SrtmMapProducer(ElevationProvider(config))
                    .createSrtmMap(out, listOf(stelvio()), maxSize = 400)

            assertTrue(out.length() > 0, "no PNG written")
            val image = ImageIO.read(out)
            assertEquals(map.width, image.width)
            assertEquals(map.height, image.height)

            // Real mountain terrain must not render as one flat colour, and must not be entirely
            // no-data either — both would mean the DEM never actually arrived.
            val colours =
                buildSet {
                    for (x in 0 until image.width step 3) {
                        for (y in 0 until image.height step 3) add(image.getRGB(x, y))
                    }
                }
            assertTrue(colours.size > 5, "relief looks uniform: only ${colours.size} distinct colours")
            val noData = SrtmMapProducer.NO_DATA_COLOR.rgb and 0xFFFFFF
            assertTrue(
                colours.any { (it and 0xFFFFFF) != noData },
                "every pixel is the no-data colour — the DEM was not reached",
            )
        } finally {
            out.delete()
        }
    }
}
