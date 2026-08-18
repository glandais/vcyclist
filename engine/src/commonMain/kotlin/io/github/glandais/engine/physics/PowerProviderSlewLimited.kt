package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.abs

/**
 * Rate-limits another [CyclistPowerProvider] so the simulated rider cannot step power
 * instantaneously (ledger R18).
 *
 * ## Why
 *
 * A provider that reads the gradient — or any rule that reacts to terrain — produces a
 * discontinuous jump at every change, and [`04 §4.3`] names that as the specific artefact to
 * avoid: real power *"may be slow and dispersed over several hundred metres"* going into a climb.
 * This is the cheap half of that finding; the anticipation half is a pacing model (R19).
 *
 * ## The number
 *
 * [maxSlewWPerS] defaults to 50 W/s, verbatim from Zignoli & Biral's appendix
 * (*"vWnmax = 50 W/s, maximal power output variation"*), where it is a **hard constraint of an
 * optimal-control formulation, not a physiological measurement**. Nobody has measured how fast a
 * rider can actually change power; treat the default as a plausible bound and tune it.
 *
 * ## Composition
 *
 * A decorator rather than a change to [CyclistPowerProviderBase], so providers stay stateless
 * unless you ask for state, and so it composes:
 * `PowerProviderSlewLimited(PowerProviderDurability(...))`.
 *
 * Two behaviours worth knowing:
 * - **Rides start from zero.** The first point has no previous power, so the rider ramps up from
 *   a standstill at [maxSlewWPerS] rather than appearing at full power. That is the honest model
 *   and it resolves within a few points.
 * - **The pedal-strike cut-off is not rate-limited**, because it is applied downstream in
 *   [MuscularPowerProvider]. So power drops to zero the instant the bike leans past clearance and
 *   ramps back up on the way out — which is exactly the "drop quickly and locally, rise gradually"
 *   asymmetry the pacing literature describes, arrived at without modelling it.
 *
 * Stateful, like [PowerProviderDurability] and for the same reason: the limit is a function of the
 * previous point. The accumulator is keyed on `pointIndex` — re-reading a point is idempotent, a
 * backwards index resets. One instance per simulation, no concurrent use.
 *
 * @param delegate the provider whose output is being limited
 * @param maxSlewWPerS maximum |ΔP| per second
 */
class PowerProviderSlewLimited(
    val delegate: CyclistPowerProvider,
    val maxSlewWPerS: Double = EngineConstants.DEFAULT_MAX_POWER_SLEW_W_PER_S,
) : CyclistPowerProvider {
    init {
        require(maxSlewWPerS > 0.0) { "maxSlewWPerS must be > 0, got $maxSlewWPerS" }
    }

    private var lastIndex: Int = -1
    private var lastElapsedS: Double = 0.0

    /** Power delivered at the previous point — what the next one is measured against. */
    var lastPowerW: Double = 0.0
        private set

    override fun powerAt(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
    ): Double {
        val target = delegate.powerAt(course, path, pointIndex)

        if (pointIndex < lastIndex) reset()
        if (pointIndex == lastIndex) return lastPowerW

        val elapsedS = path.elapsed(pointIndex)
        val dtS = elapsedS - lastElapsedS
        val limited =
            if (dtS > 0.0 && dtS.isFinite()) {
                val budget = maxSlewWPerS * dtS
                val delta = target - lastPowerW
                if (abs(delta) <= budget) target else lastPowerW + (if (delta > 0) budget else -budget)
            } else {
                // No time passed (or no clock at all): nothing may change.
                lastPowerW
            }

        if (elapsedS.isFinite()) lastElapsedS = elapsedS
        lastIndex = pointIndex
        lastPowerW = limited
        return limited
    }

    /** Forget the previous point — call before reusing the instance on another course. */
    fun reset() {
        lastIndex = -1
        lastElapsedS = 0.0
        lastPowerW = 0.0
    }
}
