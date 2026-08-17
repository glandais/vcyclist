package io.github.glandais.cli.mixin

import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderCriticalPower
import io.github.glandais.engine.physics.PowerProviderDurability
import io.github.glandais.engine.physics.PowerProviderSlewLimited
import io.github.glandais.engine.physics.PowerProviderTerrainPacing
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
        description = ["Critical Power in watts, used by every model but 'constant' (default: \${DEFAULT-VALUE})"],
    )
    var criticalPowerW: Double = EngineConstants.DEFAULT_CRITICAL_POWER_W

    @field:CommandLine.Option(
        names = ["--cyclist-wprime"],
        description = ["Anaerobic work capacity W' in joules, used by 'critical-power' (default: \${DEFAULT-VALUE})"],
    )
    var wPrimeJ: Double = EngineConstants.DEFAULT_W_PRIME_J

    @field:CommandLine.Option(
        names = ["--cyclist-model"],
        converter = [PowerModelConverter::class],
        description = [
            "How the rider chooses power: \${COMPLETION-CANDIDATES} (default: \${DEFAULT-VALUE}). " +
                "'durability' fades with work above CP; 'critical-power' spends a W' reserve and " +
                "settles at CP.",
        ],
    )
    var powerModel: PowerModel = PowerModel.CONSTANT

    @field:CommandLine.Option(
        names = ["--cyclist-pacing"],
        description = [
            "Ride harder uphill and into headwind, easier downhill and with tailwind. A heuristic, " +
                "not an optimiser: increases are dispersed over ~300 m, decreases are immediate.",
        ],
    )
    var terrainPacing: Boolean = false

    @field:CommandLine.Option(
        names = ["--cyclist-slew"],
        description = [
            "Limit how fast power may change, in W/s; 0 disables the limit (default: \${DEFAULT-VALUE})",
        ],
    )
    var maxSlewWPerS: Double = 0.0

    fun toCyclist(): Cyclist =
        Cyclist(
            massKg = massKg,
            maxBrakeG = maxBrakeG ?: roadCondition.maxBrakeG,
            cd = cd,
            frontalAreaM2 = frontalAreaM2,
            maxLeanAngleDeg = maxLeanAngleDeg ?: roadCondition.leanAngleDeg,
            maxSpeedKmH = maxSpeedKmH,
        )

    /**
     * Power is a separate strategy in vcyclist — see the class KDoc.
     *
     * Composition order : the fatigue model chooses a target, terrain pacing redistributes it, and
     * the slew limiter smooths whatever comes out — so the rate limit is the last word.
     */
    fun toPowerProvider(): CyclistPowerProvider {
        var provider = basePowerProvider()
        if (terrainPacing) provider = PowerProviderTerrainPacing(provider)
        if (maxSlewWPerS > 0.0) provider = PowerProviderSlewLimited(provider, maxSlewWPerS)
        return provider
    }

    private fun basePowerProvider(): CyclistPowerProvider =
        when (powerModel) {
            PowerModel.CONSTANT -> PowerProviderConstant(powerW, useHarmonics = useHarmonics)
            PowerModel.DURABILITY ->
                PowerProviderDurability(
                    powerW = powerW,
                    criticalPowerW = criticalPowerW,
                    useHarmonics = useHarmonics,
                )
            PowerModel.CRITICAL_POWER ->
                PowerProviderCriticalPower(
                    powerW = powerW,
                    criticalPowerW = criticalPowerW,
                    wPrimeJ = wPrimeJ,
                    useHarmonics = useHarmonics,
                )
        }

    /** The three ways the simulated rider can choose its power. */
    enum class PowerModel(
        val cliName: String,
    ) {
        /** Hold [powerW] for the whole ride. */
        CONSTANT("constant"),

        /** Fade with work accumulated above CP — [PowerProviderDurability]. */
        DURABILITY("durability"),

        /** Spend a W' reserve, then settle at CP — [PowerProviderCriticalPower]. */
        CRITICAL_POWER("critical-power"),
        ;

        override fun toString(): String = cliName
    }

    /** Case-insensitive [PowerModel] parsing, accepting the hyphenated CLI spelling. */
    class PowerModelConverter : CommandLine.ITypeConverter<PowerModel> {
        override fun convert(value: String): PowerModel =
            PowerModel.entries.firstOrNull { it.cliName.equals(value, ignoreCase = true) }
                ?: throw CommandLine.TypeConversionException(
                    "expected one of ${PowerModel.entries.map { it.cliName }} but was '$value'",
                )
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
