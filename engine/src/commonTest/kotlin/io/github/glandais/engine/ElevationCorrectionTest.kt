package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.IgnLidarHdLayer
import io.github.glandais.elevation.TileEncoding
import io.github.glandais.elevation.TileFetchException
import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.ElevationCorrectionStatus
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.TunnelWorld
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Steps 1–3c as a caller without physics reaches them, on [DeckWorld] and [TunnelWorld]. */
class ElevationCorrectionTest {
    /** The route as a sparse input: one point every 50 m, so the step's own grid shows. */
    private fun sparse(): Path = DeckWorld.path(stepM = 50.0)

    private fun maxGap(p: Path): Double = (1 until p.size).maxOf { p.distance(it) - p.distance(it - 1) }

    private fun elevationAt(
        p: Path,
        d: Double,
    ): Double = p.elevation((0 until p.size).minBy { kotlin.math.abs(p.distance(it) - d) })

    private val deckMid = (DeckWorld.DECK_START_M + DeckWorld.DECK_END_M) / 2

    @Test
    fun the_deck_is_ridden_on_the_enhancers_grid_by_default() =
        runTest {
            val input = sparse()
            val out = ElevationCorrection.fixAndCorrect(input, DeckWorld.terrain().withSurfaceModel(DeckWorld.surface()))
            val report = out.elevationCorrections!!
            assertEquals(ElevationCorrectionStatus.APPLIED, report.bridgeDecks)
            assertEquals(1, report.bridgeDecksApplied)
            assertTrue(maxGap(out) <= Enhancer.REFINE_MAX_DISTANCE_M + 1e-6, "gap ${maxGap(out)}")
            assertEquals(DeckWorld.DECK_M, elevationAt(out, deckMid), 1.0)
            assertNull(input.elevationCorrections, "the caller's path is never stamped")
        }

    @Test
    fun a_wider_grid_keeps_the_deck_and_bounds_the_points() =
        runTest {
            for (spacing in listOf(5.0, 10.0)) {
                val out =
                    ElevationCorrection.fixAndCorrect(
                        sparse(),
                        DeckWorld.terrain().withSurfaceModel(DeckWorld.surface()),
                        maxSpacingM = spacing,
                    )
                assertEquals(ElevationCorrectionStatus.APPLIED, out.elevationCorrections!!.bridgeDecks, "at $spacing m")
                assertTrue(maxGap(out) <= spacing + 1e-6, "gap ${maxGap(out)} at $spacing m")
                assertTrue(out.size <= 3_000.0 / (spacing / 2) + 2, "${out.size} points at $spacing m")
                assertEquals(DeckWorld.DECK_M, elevationAt(out, deckMid), 1.0, "deck at $spacing m")
            }
        }

    @Test
    fun the_tunnel_runs_on_a_provider_without_surface_model() =
        runTest {
            val out = ElevationCorrection.fixAndCorrect(DeckWorld.path(stepM = 50.0), TunnelWorld.terrain(), maxSpacingM = 10.0)
            val report = out.elevationCorrections!!
            assertEquals(ElevationCorrectionStatus.UNAVAILABLE, report.bridgeDecks)
            assertEquals(ElevationCorrectionStatus.APPLIED, report.tunnels)
            assertEquals(TunnelWorld.GROUND_M, elevationAt(out, (TunnelWorld.START_M + TunnelWorld.END_M) / 2), 1.0)
        }

    @Test
    fun switched_off_neither_step_reads_a_tile() =
        runTest {
            val unreadable = DeckWorld.surface { error("the surface model was read") }
            val out =
                ElevationCorrection.fixAndCorrect(
                    sparse(),
                    DeckWorld.terrain().withSurfaceModel(unreadable),
                    ElevationCorrectionOptions(bridgeDecks = false, tunnels = false),
                )
            val report = out.elevationCorrections!!
            assertEquals(ElevationCorrectionStatus.NOT_RUN, report.bridgeDecks)
            assertEquals(ElevationCorrectionStatus.NOT_RUN, report.tunnels)
            assertTrue(report.warnings.isEmpty())
        }

    @Test
    fun a_surface_tile_that_cannot_be_read_is_reported_not_thrown() =
        runTest {
            val unreadable = DeckWorld.surface { error("HTTP 429") }
            val out = ElevationCorrection.fixAndCorrect(sparse(), DeckWorld.terrain().withSurfaceModel(unreadable))
            assertEquals(ElevationCorrectionStatus.FETCH_FAILED, out.elevationCorrections!!.bridgeDecks)
            assertTrue(out.elevationCorrections!!.warnings.isNotEmpty())
        }

    @Test
    fun the_smoother_keeps_the_report() =
        runTest {
            val out = ElevationCorrection.fixAndCorrect(sparse(), DeckWorld.terrain().withSurfaceModel(DeckWorld.surface()))
            assertEquals(out.elevationCorrections, ElevationStep.smoothElevation(out).elevationCorrections)
        }

    @Test
    fun the_spacing_must_be_positive() =
        runTest {
            for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertFailsWith<IllegalArgumentException> {
                    ElevationCorrection.fixAndCorrect(sparse(), DeckWorld.terrain(), maxSpacingM = bad)
                }
            }
        }

    @Test
    fun a_dead_terrain_tile_is_read_from_the_fallback_reported_and_rides_no_deck() =
        runTest {
            // IGN's terrain is down; the fallback serves the same ground; the surface model works.
            val terrain =
                ElevationProvider(IgnLidarHdLayer.MNT.config(15)) { url -> throw TileFetchException(url, 500) }
                    .withFallback(DeckWorld.terrain())
                    .withSurfaceModel(DeckWorld.surface())
            val out = ElevationCorrection.fixAndCorrect(sparse(), terrain)
            val report = out.elevationCorrections!!
            assertEquals(DeckWorld.BANK_M, elevationAt(out, 200.0), 0.5, "read from the fallback")
            assertEquals(ElevationCorrectionStatus.FETCH_FAILED, report.bridgeDecks, "no deck on a mix of models")
            assertTrue(report.warnings.any { it.startsWith("terrain: ") }, "${report.warnings}")
            assertTrue(elevationAt(out, deckMid) < DeckWorld.DECK_M - 5.0, "the valley floor stays")
        }

    @Test
    fun a_healthy_run_carries_no_terrain_warning() =
        runTest {
            val out = ElevationCorrection.fixAndCorrect(sparse(), DeckWorld.terrain().withSurfaceModel(DeckWorld.surface()))
            assertTrue(out.elevationCorrections!!.warnings.none { it.startsWith("terrain: ") })
        }

    @Test
    fun the_report_says_where_every_kilometre_was_read() =
        runTest {
            val out = ElevationCorrection.fixAndCorrect(sparse(), DeckWorld.terrain().withSurfaceModel(DeckWorld.surface()))
            val terrain = out.elevationCorrections!!.terrain!!
            assertEquals(3.0, terrain.ignLidarHdKm, 0.01)
            assertEquals(0.0, terrain.mapterhornKm + terrain.mapterhornMeasuredKm + terrain.otherKm + terrain.noDataKm, 1e-9)
            assertEquals(0, terrain.failedTiles)
        }

    @Test
    fun the_failed_kilometres_are_in_the_warning() =
        runTest {
            val terrain =
                ElevationProvider(IgnLidarHdLayer.MNT.config(15)) { url -> throw TileFetchException(url, 500) }
                    .withFallback(DeckWorld.terrain())
            val report = ElevationCorrection.fixAndCorrect(sparse(), terrain).elevationCorrections!!
            assertEquals(3.0, report.terrain!!.fallbackAfterErrorKm, 0.01)
            assertTrue(report.terrain!!.failedTiles > 0)
            assertTrue(report.warnings.any { it.startsWith("terrain: ") && "3.0 km" in it }, "${report.warnings}")
        }

    @Test
    fun no_deck_rides_an_ign_surface_over_another_terrain() =
        runTest {
            // The same ground and the same deck, but the terrain comes from a source that is not IGN.
            val elsewhere =
                ElevationProviderConfig(
                    zoomLevel = 15,
                    tileSize = 256,
                    tileUrlTemplate = "https://dem.example.invalid/wms?BBOX={bbox}&L=1",
                    encoding = TileEncoding.BIL32,
                )
            val terrain = ElevationProvider(elsewhere) { url -> DeckWorld.tile(url, DeckWorld::mnt) }.withSurfaceModel(DeckWorld.surface())
            val report = ElevationCorrection.fixAndCorrect(sparse(), terrain).elevationCorrections!!
            assertEquals(ElevationCorrectionStatus.NO_COVERAGE, report.bridgeDecks)
            assertEquals(3.0, report.terrain!!.otherKm, 0.01)
        }

    @Test
    fun without_a_dem_correction_there_is_no_provenance() =
        runTest {
            val out = Enhancer.enhanceCourseDefault(sparse(), null, EnhanceOptions.DEFAULT.copy(computeOnePointPerSecond = false))
            assertNull(out.elevationCorrections!!.terrain)
        }
}
