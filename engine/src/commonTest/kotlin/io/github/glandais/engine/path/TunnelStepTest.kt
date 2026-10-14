package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.IgnLidarHdLayer
import io.github.glandais.elevation.TileEncoding
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.TunnelScope
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Step 3c on [DeckWorld]'s eastward road, its terrain replaced by [TunnelWorld.mnt]: flat at 10 m,
 * a mountain over [TunnelWorld.START_M]..[TunnelWorld.END_M] with cut faces.
 */
internal object TunnelWorld {
    const val START_M = 1_000.0
    const val END_M = 1_400.0
    const val GROUND_M = 10.0

    fun mnt(x: Double): Double {
        if (x <= START_M || x >= END_M) return GROUND_M
        val face = min(x - START_M, END_M - x)
        return GROUND_M + min(face, 120.0 * sin(PI * (x - START_M) / (END_M - START_M)))
    }

    fun terrain(): ElevationProvider = DeckWorld.terrain(::mnt)

    /** The route through the mountain, its elevation already the terrain (as step 2 leaves it). */
    fun path(stepM: Double = 2.0): Path {
        val p = DeckWorld.path(stepM = stepM)
        for (i in 0 until p.size) p.setElevation(i, mnt(p.distance(i)))
        p.computeDerivedData()
        return p
    }
}

class TunnelStepTest {
    private companion object {
        const val REFRESH_M = Enhancer.PRE_FIX_SPACING_M
    }

    @Test
    fun the_mountain_over_the_road_becomes_the_line_between_its_portals() =
        runTest {
            val input = TunnelWorld.path()
            val result = TunnelStep.apply(input, TunnelWorld.terrain(), refreshM = REFRESH_M)
            assertEquals(TunnelStatus.APPLIED, result.status)
            val t = result.spans.single()
            assertTrue(abs(t.startM - TunnelWorld.START_M) <= 10.0 && abs(t.endM - TunnelWorld.END_M) <= 10.0, "portals $t")
            val margin = REFRESH_M
            assertEquals(listOf((t.startM - margin)..(t.endM + margin)), result.written)
            for (i in 0 until input.size) {
                val d = input.distance(i)
                when {
                    d >= t.startM && d <= t.endM ->
                        assertEquals(TunnelWorld.GROUND_M, result.path.elevation(i), 1.0, "inside the tunnel at $d m")
                    d >= t.startM - margin && d <= t.endM + margin ->
                        assertEquals(TunnelWorld.GROUND_M, result.path.elevation(i), 1.0, "refreshed margin at $d m")
                    else ->
                        assertEquals(input.elevation(i).toRawBits(), result.path.elevation(i).toRawBits(), "outside at $d m")
                }
                assertEquals(input.latitude(i), result.path.latitude(i))
                assertEquals(input.distance(i), result.path.distance(i))
            }
        }

    @Test
    fun a_portal_on_a_deck_is_pinned_to_the_deck_and_the_deck_never_written_over() =
        runTest {
            // A tunnel opening onto a bridge: the deck of step 3b holds the entry portal.
            val input = TunnelWorld.path()
            for (i in 0 until input.size) if (input.distance(i) in 950.0..1_050.0) input.setElevation(i, 12.0)
            input.computeDerivedData()
            val deck = 950.0..1_050.0
            val result = TunnelStep.apply(input, TunnelWorld.terrain(), protected = listOf(deck), refreshM = REFRESH_M)
            assertEquals(TunnelStatus.APPLIED, result.status)
            assertEquals(emptyList(), result.dropped)
            val t = result.spans.single()
            assertTrue(t.startM in deck, "entry on the deck: $t")
            for (i in 0 until input.size) {
                val d = input.distance(i)
                if (d in deck) {
                    assertEquals(input.elevation(i).toRawBits(), result.path.elevation(i).toRawBits(), "deck at $d m")
                } else if (d > deck.endInclusive && d <= t.endM) {
                    // The line from the deck (12 m) to the exit (10 m): no step at the deck edge.
                    val line = 12.0 + (TunnelWorld.GROUND_M - 12.0) * (d - t.startM) / (t.endM - t.startM)
                    assertEquals(line, result.path.elevation(i), 1.0, "inside at $d m")
                }
            }
        }

    @Test
    fun a_deck_wholly_between_the_portals_drops_the_tunnel() =
        runTest {
            // The terrain model kept a bridge's pylons and the detector took them for a mountain; the
            // deck of step 3b lies between the "portals". Writing the line around the deck would leave
            // a vertical step at each of its edges: the tunnel is dropped, the path comes back as it was.
            val input = TunnelWorld.path()
            val deck = 1_150.0..1_250.0
            val result = TunnelStep.apply(input, TunnelWorld.terrain(), protected = listOf(deck), refreshM = REFRESH_M)
            assertEquals(TunnelStatus.NO_TUNNEL, result.status)
            assertEquals(1, result.dropped.size)
            assertEquals(emptyList(), result.spans)
            assertSame(input, result.path)
        }

    @Test
    fun the_samples_of_step_3b_are_the_samples_it_would_read() =
        runTest {
            val input = TunnelWorld.path()
            val stations = alongTrackStations(input, TunnelDetectorOptions.DEFAULT.stepM)
            val sampled = TunnelWorld.terrain().setElevationsWithOrigin(stations)
            val samples = sampled.map { it.elevation }.toDoubleArray()
            val origins = sampled.map { it.origin }
            val read = TunnelStep.apply(input, TunnelWorld.terrain(), refreshM = REFRESH_M)
            val reused = TunnelStep.apply(input, TunnelWorld.terrain(), samples = samples, refreshM = REFRESH_M, origins = origins)
            assertEquals(read.spans, reused.spans)
            for (i in 0 until input.size) {
                assertEquals(read.path.elevation(i).toRawBits(), reused.path.elevation(i).toRawBits())
            }
            assertFailsWith<IllegalArgumentException> {
                TunnelStep.apply(
                    input,
                    TunnelWorld.terrain(),
                    samples = samples.copyOf(samples.size - 1),
                    refreshM = REFRESH_M,
                    origins = origins,
                )
            }
            assertFailsWith<IllegalArgumentException>("samples without origins") {
                TunnelStep.apply(input, TunnelWorld.terrain(), samples = samples, refreshM = REFRESH_M)
            }
        }

    @Test
    fun open_ground_and_a_bridge_canyon_are_no_tunnel() =
        runTest {
            // DeckWorld's own terrain: banks and a river 10 m below them, a dip, never a bump.
            val input = DeckWorld.path()
            val result = TunnelStep.apply(input, DeckWorld.terrain(), refreshM = REFRESH_M)
            assertEquals(TunnelStatus.NO_TUNNEL, result.status)
            assertSame(input, result.path)
        }

    @Test
    fun a_tile_that_cannot_be_read_leaves_the_path_as_it_was() =
        runTest {
            val input = TunnelWorld.path()
            val broken = ElevationProvider(IgnLidarHdLayer.MNT.config(15)) { error("HTTP 429") }
            val result = TunnelStep.apply(input, broken, refreshM = REFRESH_M)
            assertEquals(TunnelStatus.FETCH_FAILED, result.status)
            assertSame(input, result.path)
        }

    @Test
    fun a_defect_past_the_read_throws_instead_of_passing_for_a_failed_fetch() {
        val input = TunnelWorld.path()
        val stations = alongTrackStations(input, TunnelDetectorOptions.DEFAULT.stepM)
        assertFailsWith<IllegalArgumentException> {
            // Fewer latitudes than samples: the detector's own precondition, outside any catch.
            TunnelStep.flatten(
                input,
                DoubleArray(stations.size),
                stations.map { it.latitude }.drop(1),
                stations.map { it.longitude },
                TunnelDetectorOptions.DEFAULT,
                refreshM = REFRESH_M,
            )
        }
    }

    /** [TunnelWorld]'s terrain, its tiles declared as [source]'s: where it lies decides the origin. */
    private fun declared(source: ElevationSource): ElevationProvider =
        ElevationProvider(
            ElevationProviderConfig(
                zoomLevel = 15,
                tileSize = 256,
                tileUrlTemplate = "host://wms?BBOX={bbox}&x=1",
                encoding = TileEncoding.BIL32,
            ),
        ) { url -> DeckWorld.tile(url, TunnelWorld::mnt) }.servingSource(source)

    @Test
    fun on_unmeasured_terrain_the_tunnel_is_left_as_read_and_counted() =
        runTest {
            // TunnelWorld lies at 47° N, 1° E: in France. Declared Mapterhorn there is measured.
            val france = TunnelStep.apply(TunnelWorld.path(), declared(ElevationSource.MAPTERHORN), refreshM = REFRESH_M)
            assertEquals(TunnelStatus.APPLIED, france.status)

            // The same tiles from an unknown source: unmeasured, so the tunnel is only counted.
            val unknown =
                TunnelStep.apply(
                    TunnelWorld.path(),
                    declared(ElevationSource.MAPTERHORN).servingSource(null),
                    refreshM = REFRESH_M,
                )
            assertEquals(TunnelStatus.NO_TUNNEL, unknown.status)
            assertEquals(1, unknown.outOfScope.size)
            assertSame(unknown.path, unknown.path, "nothing written")

            // Everywhere flattens it all the same.
            val everywhere =
                TunnelStep.apply(
                    TunnelWorld.path(),
                    declared(ElevationSource.MAPTERHORN).servingSource(null),
                    refreshM = REFRESH_M,
                    scope = TunnelScope.EVERYWHERE,
                )
            assertEquals(TunnelStatus.APPLIED, everywhere.status)
            assertEquals(everywhere.spans, france.spans)
        }
}
