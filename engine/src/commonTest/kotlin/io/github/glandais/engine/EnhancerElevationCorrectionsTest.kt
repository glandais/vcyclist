package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.BridgeDeckResult
import io.github.glandais.engine.path.BridgeDeckStatus
import io.github.glandais.engine.path.DeckCandidate
import io.github.glandais.engine.path.DeckWorld
import io.github.glandais.engine.path.ElevationCorrectionReport
import io.github.glandais.engine.path.ElevationCorrectionStatus
import io.github.glandais.engine.path.ElevationCorrections
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.path.TunnelResult
import io.github.glandais.engine.path.TunnelSpan
import io.github.glandais.engine.path.TunnelStatus
import io.github.glandais.engine.path.TunnelWorld
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * E7: the switches of steps 3b and 3c ([EnhanceOptions.elevationCorrections]) and the report the
 * enhancer stamps on its output ([Path.elevationCorrections]).
 */
class EnhancerElevationCorrectionsTest {
    private val noPhysics =
        EnhanceOptions(
            computeMaxSpeeds = false,
            virtualizeTrack = false,
            computeOnePointPerSecond = false,
            simplifyPath = SimplifyPathOptions(enabled = false),
        )

    private suspend fun run(
        path: Path,
        provider: ElevationProvider?,
        options: EnhanceOptions = noPhysics,
    ): Path = Enhancer.enhanceCourse(Enhancer.getDefaultCourse(path), options, provider)

    private fun EnhanceOptions.with(
        bridgeDecks: Boolean = ElevationCorrectionOptions.DEFAULT.bridgeDecks,
        tunnels: Boolean = ElevationCorrectionOptions.DEFAULT.tunnels,
    ) = copy(elevationCorrections = ElevationCorrectionOptions(bridgeDecks, tunnels))

    /** Every slot of every point, as raw bits. */
    private fun dump(p: Path): List<Long> = (0 until p.size).flatMap { i -> PointField.entries.map { p.get(i, it).toRawBits() } }

    private fun report(p: Path): ElevationCorrections = assertNotNull(p.elevationCorrections, "the enhancer stamps a report")

    private fun deckProvider(surfaceFetches: (() -> Unit)? = null): ElevationProvider =
        DeckWorld.terrain().withSurfaceModel(
            DeckWorld.surface { url ->
                surfaceFetches?.invoke()
                DeckWorld.tile(url, DeckWorld::mns)
            },
        )

    @Test
    fun the_option_is_last_and_on_by_default() {
        assertEquals(ElevationCorrectionOptions(bridgeDecks = true, tunnels = true), ElevationCorrectionOptions.DEFAULT)
        assertEquals(ElevationCorrectionOptions.DEFAULT, EnhanceOptions.DEFAULT.elevationCorrections)
    }

    @Test
    fun without_a_dem_correction_neither_step_runs_but_the_report_is_there() =
        runTest {
            val r = report(run(DeckWorld.path(stepM = 25.0), deckProvider(), noPhysics.copy(fixElevation = false)))
            assertEquals(
                ElevationCorrections(ElevationCorrectionStatus.NOT_RUN, 0, 0, ElevationCorrectionStatus.NOT_RUN, 0, 0, emptyList()),
                r,
            )
        }

    @Test
    fun the_one_argument_call_reports_not_run() =
        runTest {
            val r = report(Enhancer.enhanceCourseDefault(DeckWorld.path(stepM = 25.0)))
            assertEquals(ElevationCorrectionStatus.NOT_RUN, r.bridgeDecks)
            assertEquals(ElevationCorrectionStatus.NOT_RUN, r.tunnels)
        }

    @Test
    fun the_input_path_is_never_stamped() =
        runTest {
            val input = DeckWorld.path(stepM = 25.0)
            run(input, deckProvider())
            assertNull(input.elevationCorrections)
        }

    @Test
    fun a_surface_model_reports_the_deck_applied() =
        runTest {
            val r = report(run(DeckWorld.path(stepM = 25.0), deckProvider()))
            assertEquals(ElevationCorrectionStatus.APPLIED, r.bridgeDecks)
            assertEquals(1, r.bridgeDecksApplied)
            assertEquals(0, r.bridgeDecksDropped)
            assertEquals(ElevationCorrectionStatus.NONE_FOUND, r.tunnels)
            assertEquals(emptyList(), r.warnings)
        }

    @Test
    fun without_a_surface_model_decks_are_unavailable_and_no_warning_is_raised() =
        runTest {
            val r = report(run(DeckWorld.path(stepM = 25.0), DeckWorld.terrain()))
            assertEquals(ElevationCorrectionStatus.UNAVAILABLE, r.bridgeDecks)
            assertEquals(0, r.bridgeDecksApplied)
            assertEquals(emptyList(), r.warnings)
        }

    @Test
    fun decks_switched_off_never_read_the_surface_and_ride_as_without_one() =
        runTest {
            val input = DeckWorld.path(stepM = 25.0)
            var fetches = 0
            val off = run(input, deckProvider { fetches++ }, noPhysics.with(bridgeDecks = false))
            assertEquals(0, fetches, "the surface model is never read")
            assertEquals(ElevationCorrectionStatus.NOT_RUN, report(off).bridgeDecks)
            assertContentEquals(dump(run(input, DeckWorld.terrain())), dump(off))
            // And on, it is read: the counter counts.
            run(input, deckProvider { fetches++ })
            assertTrue(fetches > 0)
        }

    @Test
    fun a_surface_tile_that_cannot_be_read_is_reported_and_leaves_the_valley() =
        runTest {
            val input = DeckWorld.path(stepM = 25.0)
            val broken = DeckWorld.terrain().withSurfaceModel(DeckWorld.surface { error("HTTP 429") })
            val failed = run(input, broken)
            val r = report(failed)
            assertEquals(ElevationCorrectionStatus.FETCH_FAILED, r.bridgeDecks)
            assertEquals(listOf(ElevationCorrectionReport.DECKS_FETCH_FAILED), r.warnings)
            assertTrue(r.warnings.single().startsWith("bridge decks"))
            assertContentEquals(dump(run(input, DeckWorld.terrain())), dump(failed))
        }

    @Test
    fun a_tunnel_is_reported_applied() =
        runTest {
            val r = report(run(TunnelWorld.path(stepM = 25.0), TunnelWorld.terrain()))
            assertEquals(ElevationCorrectionStatus.APPLIED, r.tunnels)
            assertEquals(1, r.tunnelsApplied)
            assertEquals(0, r.tunnelsDropped)
            assertEquals(ElevationCorrectionStatus.UNAVAILABLE, r.bridgeDecks)
            assertEquals(emptyList(), r.warnings)
        }

    @Test
    fun tunnels_switched_off_are_not_run_and_the_mountain_stays() =
        runTest {
            val input = TunnelWorld.path(stepM = 25.0)
            val on = run(input, TunnelWorld.terrain())
            val off = run(input, TunnelWorld.terrain(), noPhysics.with(tunnels = false))
            assertEquals(ElevationCorrectionStatus.NOT_RUN, report(off).tunnels)
            assertEquals(0, report(off).tunnelsApplied)
            assertTrue(off.reportedElevationGain > on.reportedElevationGain + 100.0, "the fictitious climb stays")
        }

    @Test
    fun the_full_pipeline_carries_the_report_to_the_final_path() =
        runTest {
            val r = report(run(TunnelWorld.path(stepM = 25.0), TunnelWorld.terrain(), EnhanceOptions.DEFAULT))
            assertEquals(ElevationCorrectionStatus.APPLIED, r.tunnels)
        }

    @Test
    fun each_path_of_several_carries_its_own_report() =
        runTest {
            val out =
                Enhancer.enhanceCourses(
                    // The second stops before the mountain: flat ground, no tunnel.
                    listOf(TunnelWorld.path(stepM = 25.0), DeckWorld.path(lengthM = 800.0, stepM = 25.0)),
                    TunnelWorld.terrain(),
                    noPhysics,
                )
            assertEquals(ElevationCorrectionStatus.APPLIED, report(out[0]).tunnels)
            assertEquals(ElevationCorrectionStatus.NONE_FOUND, report(out[1]).tunnels)
        }

    // ---- ElevationCorrectionReport.of ----------------------------------------------------------

    private val anyPath = Path(0)

    private fun decks(
        status: BridgeDeckStatus,
        dropped: List<List<DeckCandidate>> = emptyList(),
    ) = BridgeDeckResult(anyPath, status, null, emptyList(), dropped)

    private fun tunnels(
        status: TunnelStatus,
        spans: List<TunnelSpan> = emptyList(),
        dropped: List<TunnelSpan> = emptyList(),
    ) = TunnelResult(anyPath, status, spans, dropped = dropped)

    private fun candidate(
        startM: Double,
        endM: Double,
    ) = DeckCandidate((startM / 5).toInt(), (endM / 5).toInt(), startM, endM, null, null, emptyList())

    @Test
    fun every_deck_status_maps_to_its_report_status() {
        val expected =
            mapOf(
                BridgeDeckStatus.APPLIED to ElevationCorrectionStatus.APPLIED,
                BridgeDeckStatus.NO_DECK to ElevationCorrectionStatus.NONE_FOUND,
                BridgeDeckStatus.NO_COVERAGE to ElevationCorrectionStatus.NO_COVERAGE,
                BridgeDeckStatus.FETCH_FAILED to ElevationCorrectionStatus.FETCH_FAILED,
            )
        assertEquals(BridgeDeckStatus.entries.toSet(), expected.keys)
        for ((s, r) in expected) {
            assertEquals(r, ElevationCorrectionReport.of(decks(s), decksUnavailable = false, tunnels = null).bridgeDecks)
        }
        assertEquals(ElevationCorrectionStatus.NOT_RUN, ElevationCorrectionReport.of(null, false, null).bridgeDecks)
        assertEquals(ElevationCorrectionStatus.UNAVAILABLE, ElevationCorrectionReport.of(null, true, null).bridgeDecks)
    }

    @Test
    fun every_tunnel_status_maps_to_its_report_status() {
        val expected =
            mapOf(
                TunnelStatus.APPLIED to ElevationCorrectionStatus.APPLIED,
                TunnelStatus.NO_TUNNEL to ElevationCorrectionStatus.NONE_FOUND,
                TunnelStatus.FETCH_FAILED to ElevationCorrectionStatus.FETCH_FAILED,
            )
        assertEquals(TunnelStatus.entries.toSet(), expected.keys)
        for ((s, r) in expected) {
            assertEquals(r, ElevationCorrectionReport.of(null, false, tunnels(s)).tunnels)
        }
        assertEquals(ElevationCorrectionStatus.NOT_RUN, ElevationCorrectionReport.of(null, false, null).tunnels)
    }

    @Test
    fun a_tunnel_read_that_failed_warns() {
        val r = ElevationCorrectionReport.of(null, true, tunnels(TunnelStatus.FETCH_FAILED))
        assertEquals(ElevationCorrectionStatus.FETCH_FAILED, r.tunnels)
        assertEquals(listOf(ElevationCorrectionReport.TUNNELS_FETCH_FAILED), r.warnings)
        assertTrue(r.warnings.single().startsWith("tunnels"))
    }

    @Test
    fun dropped_decks_are_counted_and_located_in_a_warning() {
        val dropped = listOf(listOf(candidate(1_000.0, 1_100.0), candidate(1_120.0, 1_440.0)), listOf(candidate(12_345.0, 12_400.0)))
        val r = ElevationCorrectionReport.of(decks(BridgeDeckStatus.APPLIED, dropped), false, null)
        assertEquals(2, r.bridgeDecksDropped)
        assertEquals(
            listOf("bridge decks: 2 detected deck(s) kept the valley floor (fewer than two surface anchors) at km 1.0–1.4, km 12.3–12.4"),
            r.warnings,
        )
    }

    @Test
    fun dropped_tunnels_are_counted_without_a_warning() {
        val span = TunnelSpan(200, 280, 0.0, 0.0, 0.0, 0.0, 1.0, 5.0)
        val r = ElevationCorrectionReport.of(null, true, tunnels(TunnelStatus.NO_TUNNEL, dropped = listOf(span)))
        assertEquals(1, r.tunnelsDropped)
        assertEquals(ElevationCorrectionStatus.NONE_FOUND, r.tunnels)
        assertEquals(emptyList(), r.warnings)
    }

    @Test
    fun several_failures_give_several_warnings_in_step_order() {
        val r =
            ElevationCorrectionReport.of(
                decks(BridgeDeckStatus.FETCH_FAILED),
                false,
                tunnels(TunnelStatus.FETCH_FAILED),
            )
        assertEquals(listOf(ElevationCorrectionReport.DECKS_FETCH_FAILED, ElevationCorrectionReport.TUNNELS_FETCH_FAILED), r.warnings)
    }
}
