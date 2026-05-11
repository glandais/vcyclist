package io.github.glandais.engine.physics

/**
 * Marker subtype of [PowerProvider] for cyclist input power (positive values, before
 * drivetrain losses).
 */
fun interface CyclistPowerProvider : PowerProvider
