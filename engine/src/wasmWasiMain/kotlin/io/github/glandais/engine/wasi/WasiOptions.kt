package io.github.glandais.engine.wasi

import io.github.glandais.engine.Bike
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.SimplifyPathOptions
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderDurability
import io.github.glandais.engine.physics.PowerProviderFromData
import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import kotlin.math.PI

/**
 * JSON options → engine objects, one reader per DTO of `EngineJsApi`.
 *
 * Two rules hold everywhere here, and they are the whole point of the file:
 *
 * 1. **The field names are the JS DTO's field names**, verbatim. A consumer moving from
 *    `@glandais/vcyclist-engine` to the `.wasm` rewrites the transport, not the vocabulary.
 * 2. **Defaults are read from the engine**, never copied. `Cyclist()`, `Bike()`,
 *    `ClimbOptions()` and friends already carry them from `EngineConstants`; spelling a number
 *    out here would be a second source of truth that drifts silently — the same reasoning that
 *    made `:cli` derive its defaults rather than restate them.
 *
 * The one deliberate difference with the JS façade: an **unknown key is an error**, not a shrug.
 * See [requireOnly] for why.
 */

private val ENHANCE_KEYS =
    setOf(
        "fixElevation",
        "computeMaxSpeeds",
        "virtualizeTrack",
        "computeOnePointPerSecond",
        "simplifyEnabled",
        "simplifyToleranceM",
        "simplifyZExaggeration",
    )

/**
 * Read an `EnhanceOptionsDto`-shaped object.
 *
 * The fallbacks are the JS façade's `defaultJsOptions()`, not `EnhanceOptions.DEFAULT`: no
 * elevation fetch (WASI has no network — see w05), no 1 Hz resample, no simplify. A host that
 * wants the full pipeline asks for it field by field, exactly as a JS caller does.
 */
internal fun JsonObj?.toEnhanceOptions(): EnhanceOptions {
    if (this == null) return defaultWasiOptions()
    requireOnly(ENHANCE_KEYS)
    val defaults = defaultWasiOptions()
    return EnhanceOptions(
        fixElevation = bool("fixElevation", defaults.fixElevation),
        computeMaxSpeeds = bool("computeMaxSpeeds", defaults.computeMaxSpeeds),
        virtualizeTrack = bool("virtualizeTrack", defaults.virtualizeTrack),
        computeOnePointPerSecond = bool("computeOnePointPerSecond", defaults.computeOnePointPerSecond),
        simplifyPath =
            SimplifyPathOptions(
                enabled = bool("simplifyEnabled", defaults.simplifyPath.enabled),
                toleranceM = double("simplifyToleranceM", defaults.simplifyPath.toleranceM),
                zExaggeration = double("simplifyZExaggeration", defaults.simplifyPath.zExaggeration),
            ),
    )
}

private fun defaultWasiOptions(): EnhanceOptions =
    EnhanceOptions(
        fixElevation = false,
        computeMaxSpeeds = true,
        virtualizeTrack = true,
        computeOnePointPerSecond = false,
        simplifyPath = SimplifyPathOptions(enabled = false),
    )

private val CYCLIST_KEYS =
    setOf("massKg", "cd", "frontalAreaM2", "maxLeanAngleDeg", "maxBrakeG", "maxSpeedKmH")

/** Read a `CyclistDto`-shaped object; `null` or absent fields fall back to [Cyclist]'s defaults. */
internal fun JsonObj?.toCyclist(): Cyclist {
    if (this == null) return Cyclist()
    requireOnly(CYCLIST_KEYS)
    val d = Cyclist()
    return Cyclist(
        massKg = double("massKg", d.massKg),
        maxBrakeG = double("maxBrakeG", d.maxBrakeG),
        cd = double("cd", d.cd),
        frontalAreaM2 = double("frontalAreaM2", d.frontalAreaM2),
        maxLeanAngleDeg = double("maxLeanAngleDeg", d.maxLeanAngleDeg),
        maxSpeedKmH = double("maxSpeedKmH", d.maxSpeedKmH),
    )
}

private val BIKE_KEYS =
    setOf("crr", "inertiaFront", "inertiaRear", "wheelRadiusM", "efficiency", "maxPedalingLeanAngleDeg")

/** Read a `BikeDto`-shaped object; `null` or absent fields fall back to [Bike]'s defaults. */
internal fun JsonObj?.toBike(): Bike {
    if (this == null) return Bike()
    requireOnly(BIKE_KEYS)
    val d = Bike()
    return Bike(
        crr = double("crr", d.crr),
        inertiaFront = double("inertiaFront", d.inertiaFront),
        inertiaRear = double("inertiaRear", d.inertiaRear),
        wheelRadiusM = double("wheelRadiusM", d.wheelRadiusM),
        efficiency = double("efficiency", d.efficiency),
        maxPedalingLeanAngleDeg = double("maxPedalingLeanAngleDeg", d.maxPedalingLeanAngleDeg),
    )
}

private val WIND_KEYS = setOf("windSpeed", "windDirection")

/**
 * Read a `WindDto`-shaped object. `windDirection` is in **degrees** and names the direction the
 * wind blows *toward* (task g31) — the same convention as the JS DTO, and the same one
 * `vcDominantHeadwindAzimuth` returns, so its output can be fed straight back in.
 */
internal fun JsonObj?.toWindProvider(): WindProvider {
    if (this == null) return WindProviderNone
    requireOnly(WIND_KEYS)
    return WindProviderConstant(
        Wind(
            speedMS = double("windSpeed", 0.0),
            directionRad = double("windDirection", 0.0) * PI / 180.0,
        ),
    )
}

private val POWER_KEYS = setOf("type", "power", "useHarmonics", "criticalPower")

/** Read a `PowerProviderDto`-shaped object; `null` → 250 W constant, as on the JS side. */
internal fun JsonObj?.toCyclistPowerProvider(): CyclistPowerProvider {
    if (this == null) return PowerProviderConstant(DEFAULT_POWER_W, useHarmonics = false)
    requireOnly(POWER_KEYS)
    return when (val type = string("type", "constant")) {
        "constant" ->
            PowerProviderConstant(
                power = double("power", DEFAULT_POWER_W),
                useHarmonics = bool("useHarmonics", false),
            )
        "durability" ->
            PowerProviderDurability(
                powerW = double("power", DEFAULT_POWER_W),
                criticalPowerW = double("criticalPower", EngineConstants.DEFAULT_CRITICAL_POWER_W),
                useHarmonics = bool("useHarmonics", false),
            )
        "from_data" -> PowerProviderFromData
        else -> throw IllegalArgumentException(
            "unknown power provider type '$type' — expected constant, durability or from_data",
        )
    }
}

/** Same figure the JS façade uses when a caller omits the power. */
private const val DEFAULT_POWER_W = 250.0

private val CLIMB_KEYS =
    setOf(
        "minMinClimbElevationM",
        "maxMinClimbElevationM",
        "minClimbElevationRatio",
        "minGradePercent",
        "maxDiffRealGrade",
        "booster",
    )

/**
 * Read climb-detection options. The key is `maxDiffRealGrade`, matching
 * `detectClimbsWithOptions`' parameter name on the JS side, even though the Kotlin field is
 * `maxDiffRealGradeRatio` — the JS name is the published one.
 */
internal fun JsonObj?.toClimbOptions(): ClimbOptions {
    if (this == null) return ClimbOptions()
    requireOnly(CLIMB_KEYS)
    val d = ClimbOptions()
    return ClimbOptions(
        minMinClimbElevationM = double("minMinClimbElevationM", d.minMinClimbElevationM),
        maxMinClimbElevationM = double("maxMinClimbElevationM", d.maxMinClimbElevationM),
        minClimbElevationRatio = double("minClimbElevationRatio", d.minClimbElevationRatio),
        minGradePercent = double("minGradePercent", d.minGradePercent),
        maxDiffRealGradeRatio = double("maxDiffRealGrade", d.maxDiffRealGradeRatio),
        booster = double("booster", d.booster),
    )
}

private val CSV_KEYS = setOf("separator", "unitsInHeader", "decimals")

/**
 * Read CSV options. `separator` takes its first character only, as the JS façade does, and an
 * empty string falls back to the [CsvOptions] default.
 */
internal fun JsonObj?.toCsvOptions(): CsvOptions {
    if (this == null) return CsvOptions()
    requireOnly(CSV_KEYS)
    val d = CsvOptions()
    val decimals = double("decimals", Double.NaN)
    return CsvOptions(
        separator = string("separator", "").firstOrNull() ?: d.separator,
        unitsInHeader = bool("unitsInHeader", d.unitsInHeader),
        decimals = if (decimals.isNaN()) d.decimals else decimals.toInt(),
    )
}

private val JSON_KEYS = setOf("pretty", "decimals", "includeMeta")

/** Read JSON-writer options. */
internal fun JsonObj?.toJsonOptions(): JsonOptions {
    if (this == null) return JsonOptions()
    requireOnly(JSON_KEYS)
    val d = JsonOptions()
    val decimals = double("decimals", Double.NaN)
    return JsonOptions(
        pretty = bool("pretty", d.pretty),
        decimals = if (decimals.isNaN()) d.decimals else decimals.toInt(),
        includeMeta = bool("includeMeta", d.includeMeta),
    )
}

private val WRITE_GPX_KEYS = setOf("writeExtensions", "startTimeEpochMs", "trackName")

/**
 * Options of the GPX writers, covering `writeGpx`, `writeGpxAt` and `writeGpxTracks` in one
 * shape: `startTimeEpochMs` present means the `writeGpxAt` behaviour (absolute `<time>` on every
 * point), absent means relative times are written as they stand.
 */
internal class WriteGpxOptions(
    val writeExtensions: Boolean,
    val startTimeEpochMs: Double?,
    val trackName: String,
)

internal fun JsonObj?.toWriteGpxOptions(): WriteGpxOptions {
    if (this == null) return WriteGpxOptions(writeExtensions = true, startTimeEpochMs = null, trackName = "virtualized")
    requireOnly(WRITE_GPX_KEYS)
    val start = double("startTimeEpochMs", Double.NaN)
    return WriteGpxOptions(
        writeExtensions = bool("writeExtensions", true),
        startTimeEpochMs = if (start.isNaN()) null else start,
        trackName = string("trackName", "virtualized"),
    )
}

private val FIT_KEYS = setOf("name", "startTimeEpochMs", "interPathGapMs")

/**
 * Options of the FIT writers — `pathToFit` and `pathsToFit` in one shape (task w12).
 *
 * `startTimeEpochMs` is **mandatory**, unlike in [WriteGpxOptions]: a `Path`'s clock is relative
 * (`time(0) == 0`) and FIT has no way to express that, so an absent start would silently date
 * every course to the FIT epoch. The JS façade makes it a required parameter for the same
 * reason; here it is a required key, and its absence is [WasiAbi.ERR_INVALID_ARGUMENT].
 *
 * `interPathGapMs` only means anything to `vcPathsToFit`, and `0` runs each path straight on
 * from the previous one.
 */
internal class FitOptions(
    val name: String,
    val startTimeEpochMs: Double,
    val interPathGapMs: Long,
)

internal fun JsonObj?.toFitOptions(): FitOptions {
    require(this != null) { "FIT export needs an options object carrying at least startTimeEpochMs" }
    requireOnly(FIT_KEYS)
    val start = double("startTimeEpochMs", Double.NaN)
    require(!start.isNaN()) { "FIT export needs startTimeEpochMs: a course has no relative clock" }
    return FitOptions(
        name = string("name", "virtualized"),
        startTimeEpochMs = start,
        interPathGapMs = double("interPathGapMs", 0.0).toLong(),
    )
}
