package io.github.glandais.engine.physics

/**
 * Marker subtype of [PowerProvider] for cyclist input power (positive values, before
 * drivetrain losses).
 *
 * ## The delivered-power feedback contract
 *
 * What a provider returns is not always what the rider rides: a decorator
 * ([PowerProviderTerrainPacing], [PowerProviderSlewLimited]) rescales it, and
 * [MuscularPowerProvider] zeroes it while the pedals are up. A provider whose *state* depends on
 * the power ridden — [PowerProviderCriticalPower]'s W′ reserve — must book that state against the
 * delivered figure, not its own answer, or a reserve that only ever sees its own rationed output
 * (never below CP) can never refill.
 *
 * So, after every [powerAt], whoever turned the answer into something else reports the result
 * back down the chain with [onDelivered] for the same `pointIndex`:
 * - a decorator calls `delegate.onDelivered(i, whatItReturned)` before returning, and forwards any
 *   [onDelivered] it receives to its delegate;
 * - [MuscularPowerProvider] calls it on the outermost provider with the power that actually
 *   reaches the cranks, after the pedal-strike cut.
 *
 * The last report for a point wins. A provider that keeps no such state ignores it (the default),
 * and one that is never told falls back to its own answer — so a bare provider driven directly
 * behaves exactly as before.
 */
fun interface CyclistPowerProvider : PowerProvider {
    /**
     * The power actually delivered at [pointIndex], which this provider was just asked about.
     * Default: ignored.
     */
    fun onDelivered(
        pointIndex: Int,
        powerW: Double,
    ) {
    }
}
