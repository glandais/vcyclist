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
 * @param elevationGain how cumulative ascent is measured — runs last, writes two cached scalars
 *   and no point. See `docs/guides/elevation.md`.
 * @param elevationSmoothWindowM triangular-kernel half-width for the elevation smoother, in
 *   metres. The single largest determinant of both the reported D+ and the gradients the
 *   simulation rides — it costs `sample.gpx` 1.7 % and `sports-tracker.gpx` 49 % — and it had
 *   never been measured because no caller could reach it. Ledger row R28.
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
