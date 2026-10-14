package io.github.glandais.engine

import io.github.glandais.engine.path.ElevationGainOptions
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.trajectory.CurvatureOptions
import io.github.glandais.engine.trajectory.RacingLineOptions

/**
 * Options for [io.github.glandais.engine.path.Path] simplification via Douglas-Peucker 3D.
 *
 * @param enabled whether simplification is active (default `true`)
 * @param toleranceM maximum allowed perpendicular distance in meters (default 10)
 * @param zExaggeration elevation exaggeration factor for ECEF conversion (default 3)
 */
data class SimplifyPathOptions(
    val enabled: Boolean = true,
    val toleranceM: Double = 10.0,
    val zExaggeration: Double = 3.0,
)

/**
 * Options for the W′ balance pass
 * ([io.github.glandais.engine.physiology.WPrimeBalanceComputer]).
 *
 * ## Why CP and W′ live here and not on `Cyclist`
 *
 * They are rider properties and they will move to
 * [Cyclist][io.github.glandais.engine.Cyclist] the day something *reacts* to them (ledger R16).
 * Today nothing does : the pass only annotates an already-simulated path, so putting them on the
 * rider would add two parameters that no physics reads, and would ripple into the CLI mixin
 * cross-assertion, the WASI options DTO and the JS façade for no behavioural gain.
 *
 * @param enabled whether the W′bal field is computed (default `true` — it changes no other field)
 * @param criticalPowerW Critical Power in watts, finite and `> 0`
 *   (default [EngineConstants.DEFAULT_CRITICAL_POWER_W])
 * @param wPrimeJ anaerobic work capacity in joules, finite and `> 0`
 *   (default [EngineConstants.DEFAULT_W_PRIME_J])
 * @throws IllegalArgumentException when either is non-positive, infinite or NaN
 */
data class WPrimeBalanceOptions(
    val enabled: Boolean = true,
    val criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W,
    val wPrimeJ: Double = EngineConstants.DEFAULT_W_PRIME_J,
) {
    init {
        // Finite as well as positive, like `ElevationGainOptions`: an infinite W′ makes the first
        // sub-CP recovery step evaluate `∞ − (∞ − ∞)·e^(−x/∞)` = NaN, and the NaN then runs
        // through the whole trace.
        require(criticalPowerW > 0.0 && criticalPowerW.isFinite()) {
            "criticalPowerW must be finite and > 0, got $criticalPowerW"
        }
        require(wPrimeJ > 0.0 && wPrimeJ.isFinite()) { "wPrimeJ must be finite and > 0, got $wPrimeJ" }
    }
}

/**
 * Switches for the enhancer's elevation corrections, steps 3b and 3c. Both apply only after a DEM
 * correction (`EnhanceOptions.fixElevation`); what each actually did is reported on the returned
 * path as `Path.elevationCorrections`.
 *
 * @param bridgeDecks **allow** step 3b, which rides each bridge deck the LiDAR surface model shows
 *   instead of the valley floor the terrain model reads under it. A permission, not a promise: the
 *   step runs only when the elevation provider carries a surface model — IGN LiDAR HD at its
 *   default zoom — and reports `unavailable` otherwise (Mapterhorn, every WASI host). Turning it off
 *   also saves the surface-model fetches, and makes the profile independent of the network.
 * @param tunnels allow step 3c, which flattens each tunnel the terrain model betrays onto the line
 *   between its portals, on any DEM. Its precision is unmeasured on tracks with steep real knolls
 *   and in regions only GLO-30 covers, where a false tunnel flattens a real climb: the switch is
 *   for those.
 * @param tunnelScope where step 3c may flatten what it detects: only on terrain whose precision was
 *   measured (the default), or everywhere. See [TunnelScope].
 */
data class ElevationCorrectionOptions(
    val bridgeDecks: Boolean = true,
    val tunnels: Boolean = true,
    val tunnelScope: TunnelScope = TunnelScope.MEASURED_TERRAIN,
) {
    companion object {
        /** Both corrections allowed. */
        val DEFAULT = ElevationCorrectionOptions()
    }
}

/**
 * Options controlling the `Enhancer` pipeline (introduced in task 25). By default every step is
 * enabled and simplification is on with `tolerance=10`, `zExag=3`.
 *
 * @param fixElevation pull elevation from a tile provider (task 24)
 * @param computeMaxSpeeds compute cornering + braking max speeds (task 20)
 * @param virtualizeTrack run power-based virtualization (task 21) — implies [computeMaxSpeeds]
 * @param computeOnePointPerSecond resample to 1 Hz (task 22)
 * @param wPrimeBalance W′ balance annotation options — runs after the 1 Hz resample, writes one
 *   field and changes nothing else
 * @param simplifyPath Douglas-Peucker simplification options (task 23)
 * @param curvature curvature-estimation options — writes `trajectoryCurvature`, which
 *   `MaxSpeedComputer` prefers over its own windowed estimate
 * @param racingLine optimal-trajectory options. Off by default: enabling it **moves every
 *   coordinate**. When on it supersedes [curvature], since it writes the curvature of the line
 *   actually ridden rather than of the centreline.
 * @param elevationGain how cumulative ascent is measured — runs just before simplification, on the
 *   full-resolution trace, and writes two cached scalars (carried through simplification) and no
 *   point. See `docs/guides/elevation.md`.
 * @param elevationSmoothWindowM triangular-kernel half-width for the elevation smoother, in
 *   metres. It decides the gradients the simulation rides, and so the duration — 17.6 % on
 *   `stelvio.gpx`'s switchbacks between 10 and 300 m, about 1 % elsewhere — and it had never been
 *   measured because no caller could reach it. The reported D+ reads the profile *before* this
 *   smoothing, so the window barely moves it. Ledger row R28.
 * @param elevationCorrections which elevation corrections (bridge decks, tunnels) are allowed after
 *   [fixElevation]; they never run without it. Bridge decks additionally need a provider with a
 *   surface model, so the effective value is derived in `Enhancer`. The outcome is stamped on the
 *   returned path as `Path.elevationCorrections`.
 */
data class EnhanceOptions(
    val fixElevation: Boolean = true,
    val computeMaxSpeeds: Boolean = true,
    val virtualizeTrack: Boolean = true,
    val computeOnePointPerSecond: Boolean = true,
    val simplifyPath: SimplifyPathOptions = SimplifyPathOptions(),
    // Appended last on purpose : `simplifyPath` is passed positionally by `EngineModelJvm`, and
    // inserting ahead of it would silently re-map an existing Java call site.
    val wPrimeBalance: WPrimeBalanceOptions = WPrimeBalanceOptions(),
    val curvature: CurvatureOptions = CurvatureOptions(),
    val racingLine: RacingLineOptions = RacingLineOptions(),
    val elevationGain: ElevationGainOptions = ElevationGainOptions(preset = EngineConstants.DEFAULT_ELEVATION_GAIN_PRESET),
    val elevationSmoothWindowM: Double = ElevationStep.DEFAULT_SMOOTH_WINDOW_M,
    // Last, like `wPrimeBalance`: `EngineModelJvm` passes these positionally.
    val elevationCorrections: ElevationCorrectionOptions = ElevationCorrectionOptions.DEFAULT,
) {
    init {
        require(elevationSmoothWindowM > 0.0 && elevationSmoothWindowM.isFinite()) {
            "elevationSmoothWindowM must be finite and > 0, got $elevationSmoothWindowM"
        }
    }

    companion object {
        /** All steps enabled with the shipped defaults. */
        val DEFAULT = EnhanceOptions()
    }
}

/**
 * Where step 3c may flatten the tunnels it detects. Its precision was measured on LiDAR-derived
 * terrain only — IGN LiDAR HD, and Mapterhorn in France (`TerrainOrigin.measured`); elsewhere
 * Mapterhorn may be GLO-30, where a probe on the Dadès gorge found three tunnels that do not exist,
 * and a false tunnel flattens a real climb. The wire form is [id], on every door.
 */
enum class TunnelScope(
    val id: String,
) {
    /**
     * Only where every station from 100 m before a portal to 100 m after the other was read from a
     * measured terrain; a tunnel elsewhere is left as read, counted and located in a warning.
     */
    MEASURED_TERRAIN("measured-terrain"),

    /** Wherever one is detected, on any terrain: the behaviour before 6.8. */
    EVERYWHERE("everywhere"),
    ;

    companion object {
        /** Every [id], in declaration order. */
        val ids: List<String> = entries.map { it.id }

        /** The scope spelled [id]; fails with the list of known ids otherwise. */
        fun byId(id: String): TunnelScope =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown tunnel scope '$id' (expected one of $ids)")
    }
}
