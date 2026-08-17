package io.github.glandais.cli.mixin

import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderDurability
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
 * ## `--road-condition` is a preset, and explicit options win over it
 *
 * `--road-condition=wet` sets both grip-dependent limits at once ([RoadCondition]). Passing
 * `--cyclist-max-angle` or `--cyclist-max-brake` explicitly overrides the preset for that one
 * value, which is why those two fields are nullable here : `null` means "not given on the command
 * line", and only then does the preset apply. The dry preset *is* the library default, so an
 * unconfigured CLI still builds exactly [Cyclist].
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
        description = ["Maximum braking deceleration, in g (default: 0.4 dry, 0.23 wet)"],
    )
    var maxBrakeG: Double? = null

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
        description = ["Maximum lean angle in cornering, in degrees (default: 35 dry, 15.6 wet)"],
    )
    var maxLeanAngleDeg: Double? = null

    @field:CommandLine.Option(
        names = ["--road-condition"],
        converter = [RoadConditionConverter::class],
        description = [
            "Road surface: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE}). " +
                "Sets cornering grip and braking together; wet cuts cornering speed by 1.58x.",
        ],
    )
    var roadCondition: RoadCondition = RoadCondition.DRY

    @field:CommandLine.Option(
        names = ["--cyclist-max-speed"],
        description = ["Maximum speed, in km/h (default: \${DEFAULT-VALUE})"],
    )
    var maxSpeedKmH: Double = EngineConstants.DEFAULT_MAX_SPEED_KMH

    @field:CommandLine.Option(
        names = ["--cyclist-cp"],
        description = ["Critical Power in watts, used by --cyclist-durability (default: \${DEFAULT-VALUE})"],
    )
    var criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W

    @field:CommandLine.Option(
        names = ["--cyclist-durability"],
        description = [
            "Fade power with accumulated work above --cyclist-cp instead of holding it constant",
        ],
    )
    var durability: Boolean = false

    fun toCyclist(): Cyclist =
        Cyclist(
            massKg = massKg,
            maxBrakeG = maxBrakeG ?: roadCondition.maxBrakeG,
            cd = cd,
            frontalAreaM2 = frontalAreaM2,
            maxLeanAngleDeg = maxLeanAngleDeg ?: roadCondition.leanAngleDeg,
            maxSpeedKmH = maxSpeedKmH,
        )

    /** Power is a separate strategy in vcyclist — see the class KDoc. */
    fun toPowerProvider(): CyclistPowerProvider =
        if (durability) {
            PowerProviderDurability(
                powerW = powerW,
                criticalPowerW = criticalPowerW,
                useHarmonics = useHarmonics,
            )
        } else {
            PowerProviderConstant(powerW, useHarmonics = useHarmonics)
        }

    /**
     * Case-insensitive [RoadCondition] parsing : `--road-condition=wet` is what anyone types, and
     * picocli's built-in enum conversion is case-sensitive.
     */
    class RoadConditionConverter : CommandLine.ITypeConverter<RoadCondition> {
        override fun convert(value: String): RoadCondition =
            RoadCondition.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw CommandLine.TypeConversionException(
                    "expected one of ${RoadCondition.entries.map { it.name.lowercase() }} but was '$value'",
                )
    }
}
