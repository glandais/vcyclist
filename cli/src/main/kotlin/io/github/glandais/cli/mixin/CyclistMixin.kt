package io.github.glandais.cli.mixin

import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import picocli.CommandLine

/**
 * Rider parameters, shared by every subcommand that runs the physics.
 *
 * ## Defaults come from [EngineConstants], never from literals here
 *
 * A CLI default that drifts from the library default is a guaranteed bug: the same command would
 * produce different numbers from the same input depending on whether it went through the CLI.
 * `CyclistMixinTest` asserts the correspondence field by field.
 *
 * ## Two option defaults differ from gpxtools-cli
 *
 * The option *names* match gpxtools-cli so a gpx2web user can switch without relearning, but two
 * of its defaults do not match vcyclist's library values, and the library wins:
 *
 * | option | gpxtools-cli | vcyclist |
 * |---|---|---|
 * | `--cyclist-max-angle` | 45° | 35° ([EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG]) |
 * | `--cyclist-max-speed` | 90 km/h | 100 km/h ([EngineConstants.DEFAULT_MAX_SPEED_KMH]) |
 *
 * Recorded here for the g20 correspondence matrix.
 *
 * ## Power is not part of [Cyclist]
 *
 * gpx2web bundles power into its `Cyclist`. In vcyclist power is a strategy
 * ([CyclistPowerProvider]), so `--cyclist-power` feeds [toPowerProvider] instead.
 */
class CyclistMixin {
    @field:CommandLine.Option(
        names = ["--cyclist-weight"],
        description = ["Cyclist weight including the bike, in kg (default: \${DEFAULT-VALUE})"],
    )
    var massKg: Double = EngineConstants.DEFAULT_CYCLIST_MASS_KG

    @field:CommandLine.Option(
        names = ["--cyclist-power"],
        description = ["Sustained cyclist power in watts (default: \${DEFAULT-VALUE})"],
    )
    var powerW: Double = EngineConstants.DEFAULT_CYCLIST_POWER_W

    @field:CommandLine.Option(
        names = ["--cyclist-harmonics"],
        description = ["Add harmonic variation to the power output"],
    )
    var useHarmonics: Boolean = false

    @field:CommandLine.Option(
        names = ["--cyclist-max-brake"],
        description = ["Maximum braking deceleration, in g (default: \${DEFAULT-VALUE})"],
    )
    var maxBrakeG: Double = EngineConstants.DEFAULT_MAX_BRAKE_G

    @field:CommandLine.Option(
        names = ["--cyclist-cd"],
        description = ["Drag coefficient (default: \${DEFAULT-VALUE})"],
    )
    var cd: Double = EngineConstants.DEFAULT_DRAG_COEFFICIENT

    @field:CommandLine.Option(
        names = ["--cyclist-a"],
        description = ["Frontal area, in m² (default: \${DEFAULT-VALUE})"],
    )
    var frontalAreaM2: Double = EngineConstants.DEFAULT_FRONTAL_AREA_M2

    @field:CommandLine.Option(
        names = ["--cyclist-max-angle"],
        description = ["Maximum lean angle in cornering, in degrees (default: \${DEFAULT-VALUE})"],
    )
    var maxLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG

    @field:CommandLine.Option(
        names = ["--cyclist-max-speed"],
        description = ["Maximum speed, in km/h (default: \${DEFAULT-VALUE})"],
    )
    var maxSpeedKmH: Double = EngineConstants.DEFAULT_MAX_SPEED_KMH

    fun toCyclist(): Cyclist =
        Cyclist(
            massKg = massKg,
            maxBrakeG = maxBrakeG,
            cd = cd,
            frontalAreaM2 = frontalAreaM2,
            maxLeanAngleDeg = maxLeanAngleDeg,
            maxSpeedKmH = maxSpeedKmH,
        )

    /** Power is a separate strategy in vcyclist — see the class KDoc. */
    fun toPowerProvider(): CyclistPowerProvider = PowerProviderConstant(powerW, useHarmonics = useHarmonics)
}
