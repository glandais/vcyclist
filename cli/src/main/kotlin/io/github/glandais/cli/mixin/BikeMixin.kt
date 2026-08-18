package io.github.glandais.cli.mixin

import io.github.glandais.engine.Bike
import io.github.glandais.engine.EngineConstants
import picocli.CommandLine

/**
 * Bike parameters. Defaults come from [EngineConstants] so the CLI and the library can never
 * disagree — see [CyclistMixin] for why that matters.
 */
class BikeMixin {
    @field:CommandLine.Option(
        names = ["--bike-crr"],
        description = ["Rolling resistance coefficient (default: \${DEFAULT-VALUE})"],
    )
    var crr: Double = EngineConstants.DEFAULT_CRR

    @field:CommandLine.Option(
        names = ["--bike-inertia-front"],
        description = ["Front wheel rotational inertia, in kg·m² (default: \${DEFAULT-VALUE})"],
    )
    var inertiaFront: Double = EngineConstants.DEFAULT_INERTIA_FRONT

    @field:CommandLine.Option(
        names = ["--bike-inertia-rear"],
        description = ["Rear wheel rotational inertia, in kg·m² (default: \${DEFAULT-VALUE})"],
    )
    var inertiaRear: Double = EngineConstants.DEFAULT_INERTIA_REAR

    @field:CommandLine.Option(
        names = ["--bike-wheel-radius"],
        description = ["Wheel radius, in m (default: \${DEFAULT-VALUE})"],
    )
    var wheelRadiusM: Double = EngineConstants.DEFAULT_WHEEL_RADIUS_M

    @field:CommandLine.Option(
        names = ["--bike-efficiency"],
        description = ["Drivetrain efficiency, 0..1 (default: \${DEFAULT-VALUE})"],
    )
    var efficiency: Double = EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY

    @field:CommandLine.Option(
        names = ["--bike-max-pedal-angle"],
        description = [
            "Lean angle in degrees past which the rider stops pedalling for pedal clearance; " +
                "90 disables the cut-off (default: \${DEFAULT-VALUE})",
        ],
    )
    var maxPedalingLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_PEDALING_LEAN_ANGLE_DEG

    fun toBike(): Bike =
        Bike(
            crr = crr,
            inertiaFront = inertiaFront,
            inertiaRear = inertiaRear,
            wheelRadiusM = wheelRadiusM,
            efficiency = efficiency,
            maxPedalingLeanAngleDeg = maxPedalingLeanAngleDeg,
        )
}
