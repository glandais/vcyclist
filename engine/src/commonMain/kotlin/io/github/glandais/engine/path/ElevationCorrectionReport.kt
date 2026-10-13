package io.github.glandais.engine.path

import kotlin.math.roundToLong

/**
 * Builds the [ElevationCorrections] report the enhancer stamps on its output from what steps 3b
 * and 3c returned. The **single wording site** of the warnings: every door relays these strings,
 * prefixed with its own file or track, and none rewords them.
 *
 * A warning is raised only when the output is knowingly wrong or depends on the network: a tile
 * that could not be read, or a detected deck left on the valley floor. `unavailable` and `not-run`
 * are the normal case on most sources, and a dropped tunnel is the intended behaviour (a bridge's
 * pylons taken for a mountain): those are reported by status and count only.
 */
internal object ElevationCorrectionReport {
    /**
     * Step 3b reads the terrain model and the surface model under one guard ([BridgeDeckStep]), so
     * either failing lands here: the text names both rather than blame the wrong layer.
     */
    const val DECKS_FETCH_FAILED: String =
        "bridge decks: a terrain- or surface-model tile could not be read; every bridge on this path keeps the terrain " +
            "model's valley floor, and the same input may give a different profile once the tiles are reachable"

    const val TUNNELS_FETCH_FAILED: String =
        "tunnels: a terrain tile could not be read; no tunnel on this path was flattened"

    /**
     * @param decks step 3b's result, `null` when it did not run
     * @param decksUnavailable step 3b was allowed (DEM correction on, option on) but the provider
     *   carries no surface model; only read when [decks] is `null`
     * @param tunnels step 3c's result, `null` when it did not run
     */
    fun of(
        decks: BridgeDeckResult?,
        decksUnavailable: Boolean,
        tunnels: TunnelResult?,
    ): ElevationCorrections {
        val warnings = ArrayList<String>()
        val deckStatus =
            when {
                decks == null -> if (decksUnavailable) ElevationCorrectionStatus.UNAVAILABLE else ElevationCorrectionStatus.NOT_RUN
                else -> decks.status.toReport()
            }
        if (decks?.status == BridgeDeckStatus.FETCH_FAILED) warnings += DECKS_FETCH_FAILED
        val droppedDecks = decks?.dropped?.filter { it.isNotEmpty() } ?: emptyList()
        if (droppedDecks.isNotEmpty()) warnings += droppedDecksWarning(droppedDecks)
        if (tunnels?.status == TunnelStatus.FETCH_FAILED) warnings += TUNNELS_FETCH_FAILED
        return ElevationCorrections(
            bridgeDecks = deckStatus,
            bridgeDecksApplied = decks?.replacements?.size ?: 0,
            bridgeDecksDropped = droppedDecks.size,
            tunnels = tunnels?.status?.toReport() ?: ElevationCorrectionStatus.NOT_RUN,
            tunnelsApplied = tunnels?.spans?.size ?: 0,
            tunnelsDropped = tunnels?.dropped?.size ?: 0,
            warnings = warnings,
        )
    }

    /**
     * The warning for deck regions left on the valley floor, with where they are: the distances of
     * step 3's densified path, in km to 0.1. Later stages (the racing line, 1 Hz, simplification)
     * shift the distance axis by metres, which a 100 m label absorbs but a per-point overlay would
     * not — why the corrected spans themselves are not exposed (`elevation-bridge-decks.md`, Notes).
     */
    fun droppedDecksWarning(dropped: List<List<DeckCandidate>>): String {
        val where = dropped.joinToString(", ") { "km ${km(it.first().startM)}–${km(it.last().endM)}" }
        return "bridge decks: ${dropped.size} detected deck(s) kept the valley floor (fewer than two surface anchors) at $where"
    }

    /** [m] metres as kilometres with one decimal (no `String.format` in common code). */
    private fun km(m: Double): String {
        val tenths = (m.coerceAtLeast(0.0) / 100.0).roundToLong()
        return "${tenths / 10}.${tenths % 10}"
    }

    private fun BridgeDeckStatus.toReport(): ElevationCorrectionStatus =
        when (this) {
            BridgeDeckStatus.APPLIED -> ElevationCorrectionStatus.APPLIED
            BridgeDeckStatus.NO_DECK -> ElevationCorrectionStatus.NONE_FOUND
            BridgeDeckStatus.NO_COVERAGE -> ElevationCorrectionStatus.NO_COVERAGE
            BridgeDeckStatus.FETCH_FAILED -> ElevationCorrectionStatus.FETCH_FAILED
        }

    private fun TunnelStatus.toReport(): ElevationCorrectionStatus =
        when (this) {
            TunnelStatus.APPLIED -> ElevationCorrectionStatus.APPLIED
            TunnelStatus.NO_TUNNEL -> ElevationCorrectionStatus.NONE_FOUND
            TunnelStatus.FETCH_FAILED -> ElevationCorrectionStatus.FETCH_FAILED
        }
}
