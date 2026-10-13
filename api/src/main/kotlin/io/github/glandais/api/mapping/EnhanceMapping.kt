package io.github.glandais.api.mapping

import io.github.glandais.api.dto.BikeDto
import io.github.glandais.api.dto.CyclistDto
import io.github.glandais.api.dto.EnhanceOptionsDto
import io.github.glandais.api.dto.EnhanceRequest
import io.github.glandais.api.dto.PowerProviderDto
import io.github.glandais.api.dto.WindDto
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.FieldError
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.elevation.ElevationFunctions
import io.github.glandais.elevation.ElevationSource
import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.Bike
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.RoadCondition
import io.github.glandais.engine.applyTo
import io.github.glandais.engine.path.ElevationGainOptions
import io.github.glandais.engine.path.ElevationGainPreset
import io.github.glandais.engine.physics.CyclistPowerSpec
import io.github.glandais.engine.physics.PowerModel
import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import io.github.glandais.engine.trajectory.CorridorMode

/*
 * Request DTOs → engine objects, and the inverse for `GET /defaults`.
 *
 * Every fallback is read off [HttpDefaults] (`val d = HttpDefaults.…`), never written out. Every
 * rule — the road-condition preset is the last word, a named elevation-gain preset takes its own
 * dead band — is the one the JS and WASI readers apply, not a new one. Mapping happens before any
 * computation: an invalid value, or a `require` in the `init` of an engine options class (an
 * `IllegalArgumentException`, which StatusPages answers as `invalid-option`), is a 400 that never
 * takes the compute gate.
 */

/** An `invalid-option` problem about the value at JSON [pointer]. */
internal fun invalidOption(
    pointer: String,
    message: String,
): ApiProblem = ApiProblem(ProblemKind.INVALID_OPTION, "$pointer: $message", errors = listOf(FieldError(pointer, message)))

/**
 * Parses a wire enum through the engine's own catalogue, turning its `IllegalArgumentException`
 * (or `null`) into an `invalid-option` naming [pointer].
 */
private inline fun <T : Any> wireEnum(
    pointer: String,
    value: String,
    expected: List<String>,
    parse: (String) -> T?,
): T {
    val parsed =
        try {
            parse(value)
        } catch (_: IllegalArgumentException) {
            null
        }
    return parsed ?: throw invalidOption(pointer, "unknown value '$value', expected one of ${expected.joinToString(", ")}")
}

/** The pipeline options, every absent field read off [HttpDefaults.enhance]. */
fun EnhanceOptionsDto?.toEnhanceOptions(at: String = "/options"): EnhanceOptions {
    val d = HttpDefaults.enhance
    if (this == null) return d
    val corridor =
        racingLineCorridor?.let { v ->
            wireEnum("$at/racingLineCorridor", v, CorridorMode.entries.map { it.id }, CorridorMode::byId)
        }
    val named =
        elevationGainPreset?.let { v ->
            wireEnum("$at/elevationGainPreset", v, ElevationGainPreset.entries.map { it.id }, ElevationGainPreset::byId)
        }
    return d.copy(
        fixElevation = fixElevation ?: d.fixElevation,
        computeMaxSpeeds = computeMaxSpeeds ?: d.computeMaxSpeeds,
        virtualizeTrack = virtualizeTrack ?: d.virtualizeTrack,
        computeOnePointPerSecond = computeOnePointPerSecond ?: d.computeOnePointPerSecond,
        simplifyPath =
            d.simplifyPath.copy(
                enabled = simplifyEnabled ?: d.simplifyPath.enabled,
                toleranceM = simplifyToleranceM ?: d.simplifyPath.toleranceM,
                zExaggeration = simplifyZExaggeration ?: d.simplifyPath.zExaggeration,
            ),
        wPrimeBalance =
            d.wPrimeBalance.copy(
                enabled = wPrimeBalanceEnabled ?: d.wPrimeBalance.enabled,
                criticalPowerW = wPrimeBalanceCriticalPower ?: d.wPrimeBalance.criticalPowerW,
                wPrimeJ = wPrimeBalanceWPrime ?: d.wPrimeBalance.wPrimeJ,
            ),
        curvature = d.curvature.copy(enabled = curvatureEnabled ?: d.curvature.enabled),
        racingLine =
            d.racingLine.copy(
                enabled = racingLineEnabled ?: d.racingLine.enabled,
                corridor = corridor ?: d.racingLine.corridor,
                defaultRoadWidthM = racingLineRoadWidthM ?: d.racingLine.defaultRoadWidthM,
            ),
        elevationGain =
            if (named != null) {
                // Naming a preset takes ITS dead band and window (`{"elevationGainPreset":"gps"}`
                // alone gives 10 m, not dem's 3 m); an explicit threshold still wins. Same rule as
                // EngineJsApi and WasiOptions.
                ElevationGainOptions(
                    enabled = elevationGainEnabled ?: d.elevationGain.enabled,
                    preset = named,
                    thresholdM = elevationGainThresholdM ?: named.thresholdM,
                    smoothWindowM = named.smoothWindowM,
                )
            } else {
                // Naming none keeps the default object's band, which is not always its preset's.
                d.elevationGain.copy(
                    enabled = elevationGainEnabled ?: d.elevationGain.enabled,
                    thresholdM = elevationGainThresholdM ?: d.elevationGain.thresholdM,
                )
            },
        elevationSmoothWindowM = elevationSmoothWindowM ?: d.elevationSmoothWindowM,
        elevationCorrections =
            d.elevationCorrections.copy(
                bridgeDecks = bridgeDecksEnabled ?: d.elevationCorrections.bridgeDecks,
                tunnels = tunnelsEnabled ?: d.elevationCorrections.tunnels,
            ),
    )
}

/** The DEM source `demSource` names (exact, case-sensitive id), [HttpDefaults.elevationSource] when absent. */
fun EnhanceOptionsDto?.demSource(at: String = "/options"): ElevationSource {
    val id = this?.demSource ?: return HttpDefaults.elevationSource
    return wireEnum("$at/demSource", id, ElevationSource.ids, ElevationSource::byId)
}

/** The DEM zoom `demZoom` names, `null` for the source's own default. */
fun EnhanceOptionsDto?.demZoom(at: String = "/options"): Int? {
    val zoom = this?.demZoom ?: return null
    if (!ElevationFunctions.isValidZoomLevel(zoom)) throw invalidOption("$at/demZoom", "invalid zoom level $zoom")
    return zoom
}

/**
 * The rider, every absent field read off [HttpDefaults.cyclist], then the road-condition preset
 * applied — **the preset is the last word** ([applyTo]).
 *
 * @return the rider, and a warning when the preset overrode an explicit `maxLeanAngleDeg` or
 *   `maxBrakeG` (the CLI's `roadConditionWarning`), `null` otherwise
 */
fun CyclistDto?.toCyclist(at: String = "/cyclist"): Pair<Cyclist, String?> {
    val d = HttpDefaults.cyclist
    if (this == null) return d to null
    val condition =
        roadCondition?.let { v -> wireEnum("$at/roadCondition", v, RoadCondition.wireNames, RoadCondition::fromWire) }
    val cyclist =
        condition.applyTo(
            Cyclist(
                massKg = massKg ?: d.massKg,
                maxBrakeG = maxBrakeG ?: d.maxBrakeG,
                cd = cd ?: d.cd,
                frontalAreaM2 = frontalAreaM2 ?: d.frontalAreaM2,
                maxLeanAngleDeg = maxLeanAngleDeg ?: d.maxLeanAngleDeg,
                maxSpeedKmH = maxSpeedKmH ?: d.maxSpeedKmH,
            ),
        )
    val overridden =
        listOfNotNull(
            "$at/maxLeanAngleDeg".takeIf { maxLeanAngleDeg != null },
            "$at/maxBrakeG".takeIf { maxBrakeG != null },
        )
    val warning =
        if (condition == null || overridden.isEmpty()) {
            null
        } else {
            "$at/roadCondition=${condition.wireName} overrides ${overridden.joinToString(" and ")}: a road-surface " +
                "preset sets cornering grip and braking together, so it is the last word. Drop roadCondition to " +
                "use your own values."
        }
    return cyclist to warning
}

/** The bike, every absent field read off [HttpDefaults.bike]. */
fun BikeDto?.toBike(): Bike {
    val d = HttpDefaults.bike
    if (this == null) return d
    return Bike(
        crr = crr ?: d.crr,
        inertiaFront = inertiaFront ?: d.inertiaFront,
        inertiaRear = inertiaRear ?: d.inertiaRear,
        wheelRadiusM = wheelRadiusM ?: d.wheelRadiusM,
        efficiency = efficiency ?: d.efficiency,
        maxPedalingLeanAngleDeg = maxPedalingLeanAngleDeg ?: d.maxPedalingLeanAngleDeg,
    )
}

/**
 * A constant wind, its direction converted from degrees to radians. No object: no wind. A member
 * absent: [Wind.NONE]'s.
 */
fun WindDto?.toWindProvider(): WindProvider {
    if (this == null) return WindProviderNone
    val calm = Wind.NONE
    return WindProviderConstant(
        Wind(
            speedMS = windSpeed ?: calm.speedMS,
            directionRad = windDirection?.let { it * MathConstants.DEG_TO_RAD } ?: calm.directionRad,
        ),
    )
}

/**
 * The power strategy, every absent field read off [HttpDefaults.power]. Wire → property names:
 * `type` → `model`, `power` → `powerW`, `criticalPower` → `criticalPowerW`, `wPrime` → `wPrimeJ`.
 * Call [CyclistPowerSpec.toProvider] once per path: several providers carry state.
 */
fun PowerProviderDto?.toPowerSpec(at: String = "/power"): CyclistPowerSpec {
    val d = HttpDefaults.power
    if (this == null) return d
    val model = type?.let { v -> wireEnum("$at/type", v, PowerModel.ids, PowerModel::fromIdOrNull) } ?: d.model
    return CyclistPowerSpec(
        model = model,
        powerW = power ?: d.powerW,
        criticalPowerW = criticalPower ?: d.criticalPowerW,
        wPrimeJ = wPrime ?: d.wPrimeJ,
        useHarmonics = useHarmonics ?: d.useHarmonics,
        pacing = pacing ?: d.pacing,
        maxSlewWPerS = maxSlewWPerS ?: d.maxSlewWPerS,
    )
}

/**
 * Everything `POST /enhance` reads from its request, mapped and validated before any computation.
 *
 * @property demZoom `null` for the source's own default zoom
 * @property roadWidthOverrideM written on every point when not `null`
 * @property warnings what the request asked for and did not get (an overridden grip value)
 */
class EnhanceSettings(
    val options: EnhanceOptions,
    val demSource: ElevationSource,
    val demZoom: Int?,
    val cyclist: Cyclist,
    val bike: Bike,
    val wind: WindProvider,
    val power: CyclistPowerSpec,
    val roadWidthOverrideM: Double?,
    val warnings: List<String>,
)

/** Maps the whole request; absent → [HttpDefaults]. */
fun EnhanceRequest?.toSettings(): EnhanceSettings {
    val (cyclist, warning) = this?.cyclist.toCyclist()
    val width = this?.roadWidthOverrideM
    if (width != null && !(width.isFinite() && width > 0.0)) {
        throw invalidOption("/roadWidthOverrideM", "must be finite and > 0, was $width")
    }
    return EnhanceSettings(
        options = this?.options.toEnhanceOptions(),
        demSource = this?.options.demSource(),
        demZoom = this?.options.demZoom(),
        cyclist = cyclist,
        bike = this?.bike.toBike(),
        wind = this?.wind.toWindProvider(),
        power = this?.power.toPowerSpec(),
        roadWidthOverrideM = width,
        warnings = listOfNotNull(warning),
    )
}

// ── Inverse mappers, for `GET /defaults` only ──────────────────────────────────────────────────

/** The DTO that [toEnhanceOptions], [demSource] and [demZoom] read back into these values. */
fun EnhanceOptions.toDto(
    source: ElevationSource,
    zoom: Int?,
): EnhanceOptionsDto =
    EnhanceOptionsDto(
        fixElevation = fixElevation,
        computeMaxSpeeds = computeMaxSpeeds,
        virtualizeTrack = virtualizeTrack,
        computeOnePointPerSecond = computeOnePointPerSecond,
        simplifyEnabled = simplifyPath.enabled,
        simplifyToleranceM = simplifyPath.toleranceM,
        simplifyZExaggeration = simplifyPath.zExaggeration,
        wPrimeBalanceEnabled = wPrimeBalance.enabled,
        wPrimeBalanceCriticalPower = wPrimeBalance.criticalPowerW,
        wPrimeBalanceWPrime = wPrimeBalance.wPrimeJ,
        curvatureEnabled = curvature.enabled,
        racingLineEnabled = racingLine.enabled,
        racingLineCorridor = racingLine.corridor.id,
        racingLineRoadWidthM = racingLine.defaultRoadWidthM,
        elevationGainPreset = elevationGain.preset.id,
        elevationGainThresholdM = elevationGain.thresholdM,
        elevationGainEnabled = elevationGain.enabled,
        elevationSmoothWindowM = elevationSmoothWindowM,
        bridgeDecksEnabled = elevationCorrections.bridgeDecks,
        tunnelsEnabled = elevationCorrections.tunnels,
        demZoom = zoom,
        demSource = source.id,
    )

/** The DTO [toCyclist] reads back into this rider, with no road-condition preset. */
fun Cyclist.toDto(): CyclistDto =
    CyclistDto(
        massKg = massKg,
        cd = cd,
        frontalAreaM2 = frontalAreaM2,
        maxLeanAngleDeg = maxLeanAngleDeg,
        maxBrakeG = maxBrakeG,
        maxSpeedKmH = maxSpeedKmH,
        roadCondition = null,
    )

/** The DTO [toBike] reads back into this bike. */
fun Bike.toDto(): BikeDto =
    BikeDto(
        crr = crr,
        inertiaFront = inertiaFront,
        inertiaRear = inertiaRear,
        wheelRadiusM = wheelRadiusM,
        efficiency = efficiency,
        maxPedalingLeanAngleDeg = maxPedalingLeanAngleDeg,
    )

/** The DTO [toPowerSpec] reads back into this spec. */
fun CyclistPowerSpec.toDto(): PowerProviderDto =
    PowerProviderDto(
        type = model.id,
        power = powerW,
        useHarmonics = useHarmonics,
        criticalPower = criticalPowerW,
        wPrime = wPrimeJ,
        pacing = pacing,
        maxSlewWPerS = maxSlewWPerS,
    )
