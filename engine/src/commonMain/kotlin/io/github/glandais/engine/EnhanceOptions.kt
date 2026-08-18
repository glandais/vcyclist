package io.github.glandais.engine

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
 * @param criticalPowerW Critical Power in watts (default [EngineConstants.DEFAULT_CRITICAL_POWER_W])
 * @param wPrimeJ anaerobic work capacity in joules (default [EngineConstants.DEFAULT_W_PRIME_J])
 */
data class WPrimeBalanceOptions(
    val enabled: Boolean = true,
    val criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W,
    val wPrimeJ: Double = EngineConstants.DEFAULT_W_PRIME_J,
) {
    init {
        require(criticalPowerW > 0.0) { "criticalPowerW must be > 0, got $criticalPowerW" }
        require(wPrimeJ > 0.0) { "wPrimeJ must be > 0, got $wPrimeJ" }
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
) {
    companion object {
        /** All steps enabled with the shipped defaults. */
        val DEFAULT = EnhanceOptions()
    }
}
