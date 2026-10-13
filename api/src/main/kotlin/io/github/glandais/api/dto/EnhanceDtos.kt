package io.github.glandais.api.dto

import io.github.glandais.elevation.ElevationFunctions
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Appended to the description of every optional input field. */
const val SEE_DEFAULTS: String = "Absent or null: the default published by `GET /api/v1/defaults`."

/**
 * Pipeline options of `POST /enhance` — the 20 keys of the JS `EnhanceOptionsDto`
 * (`ENHANCE_OPTIONS_KEYS`) and of the WASI reader (`ENHANCE_KEYS`), spelled the same.
 *
 * Every field is nullable: absent means the door's default ([io.github.glandais.api.mapping.HttpDefaults]).
 */
@Serializable
@SerialName("EnhanceOptions")
data class EnhanceOptionsDto(
    @JsonSchema.Description("Replace elevations with the DEM's. $SEE_DEFAULTS")
    val fixElevation: Boolean? = null,
    @JsonSchema.Description("Compute cornering and braking speed limits. $SEE_DEFAULTS")
    val computeMaxSpeeds: Boolean? = null,
    @JsonSchema.Description("Run the time-stepping simulation. $SEE_DEFAULTS")
    val virtualizeTrack: Boolean? = null,
    @JsonSchema.Description("Resample the result to one point per second. $SEE_DEFAULTS")
    val computeOnePointPerSecond: Boolean? = null,
    @JsonSchema.Description("Simplify the result (Douglas-Peucker 3D). $SEE_DEFAULTS")
    val simplifyEnabled: Boolean? = null,
    @JsonSchema.Description("Simplification tolerance, metres. $SEE_DEFAULTS")
    val simplifyToleranceM: Double? = null,
    @JsonSchema.Description("Elevation exaggeration of the simplification. $SEE_DEFAULTS")
    val simplifyZExaggeration: Double? = null,
    @JsonSchema.Description("Annotate W' balance. $SEE_DEFAULTS")
    val wPrimeBalanceEnabled: Boolean? = null,
    @JsonSchema.Description("Critical power of the W' balance, watts. $SEE_DEFAULTS")
    val wPrimeBalanceCriticalPower: Double? = null,
    @JsonSchema.Description("W' of the W' balance, joules. $SEE_DEFAULTS")
    val wPrimeBalanceWPrime: Double? = null,
    @JsonSchema.Description("Estimate the trajectory curvature. $SEE_DEFAULTS")
    val curvatureEnabled: Boolean? = null,
    @JsonSchema.Description("Ride the racing line (moves every coordinate). $SEE_DEFAULTS")
    val racingLineEnabled: Boolean? = null,
    @JsonSchema.Description("Racing-line corridor, one of `GET /api/v1/catalog` `corridorModes`. $SEE_DEFAULTS")
    val racingLineCorridor: String? = null,
    @JsonSchema.Description("Road width where the GPX gives none, metres. $SEE_DEFAULTS")
    val racingLineRoadWidthM: Double? = null,
    @JsonSchema.Description(
        "Elevation-gain preset, one of `GET /api/v1/catalog` `elevationGainPresets`. Naming one takes its " +
            "own threshold and smoothing window. $SEE_DEFAULTS",
    )
    val elevationGainPreset: String? = null,
    @JsonSchema.Description("Elevation-gain dead band, metres; wins over the preset's. $SEE_DEFAULTS")
    val elevationGainThresholdM: Double? = null,
    @JsonSchema.Description("Compute the filtered elevation gain. $SEE_DEFAULTS")
    val elevationGainEnabled: Boolean? = null,
    @JsonSchema.Description("Half-width of the elevation smoothing kernel, metres. $SEE_DEFAULTS")
    val elevationSmoothWindowM: Double? = null,
    @JsonSchema.Description(
        "DEM zoom level, ${ElevationFunctions.MIN_ZOOM_LEVEL}..${ElevationFunctions.MAX_ZOOM_LEVEL}. Absent or null: the DEM source's own default zoom.",
    )
    val demZoom: Int? = null,
    @JsonSchema.Description("DEM source, one of `GET /api/v1/catalog` `elevationSources`. $SEE_DEFAULTS")
    val demSource: String? = null,
)

/** The rider — the keys of the JS `CyclistDto` and of the WASI reader. */
@Serializable
@SerialName("Cyclist")
data class CyclistDto(
    @JsonSchema.Description("Rider mass, kg. $SEE_DEFAULTS")
    val massKg: Double? = null,
    @JsonSchema.Description("Drag coefficient. $SEE_DEFAULTS")
    val cd: Double? = null,
    @JsonSchema.Description("Frontal area, m². $SEE_DEFAULTS")
    val frontalAreaM2: Double? = null,
    @JsonSchema.Description("Maximum lean angle, degrees (a tyre friction coefficient). $SEE_DEFAULTS")
    val maxLeanAngleDeg: Double? = null,
    @JsonSchema.Description("Maximum braking, g. $SEE_DEFAULTS")
    val maxBrakeG: Double? = null,
    @JsonSchema.Description("Speed cap, km/h. $SEE_DEFAULTS")
    val maxSpeedKmH: Double? = null,
    @JsonSchema.Description(
        "Road-surface preset, one of `GET /api/v1/catalog` `roadConditions`, case-insensitive. The preset " +
            "is the last word: it overrides `maxLeanAngleDeg` and `maxBrakeG` (a warning says so). Absent: none.",
    )
    val roadCondition: String? = null,
)

/** The bike — the keys of the JS `BikeDto` and of the WASI reader. */
@Serializable
@SerialName("Bike")
data class BikeDto(
    @JsonSchema.Description("Rolling resistance coefficient. $SEE_DEFAULTS")
    val crr: Double? = null,
    @JsonSchema.Description("Front wheel inertia, kg·m². $SEE_DEFAULTS")
    val inertiaFront: Double? = null,
    @JsonSchema.Description("Rear wheel inertia, kg·m². $SEE_DEFAULTS")
    val inertiaRear: Double? = null,
    @JsonSchema.Description("Wheel radius, m. $SEE_DEFAULTS")
    val wheelRadiusM: Double? = null,
    @JsonSchema.Description("Drivetrain efficiency, 0..1. $SEE_DEFAULTS")
    val efficiency: Double? = null,
    @JsonSchema.Description("Lean angle past which the rider stops pedalling, degrees. $SEE_DEFAULTS")
    val maxPedalingLeanAngleDeg: Double? = null,
)

/**
 * A constant wind — the keys of the JS `WindDto`. The whole object absent: no wind. A member
 * absent: that member of a calm wind (0).
 */
@Serializable
@SerialName("Wind")
data class WindDto(
    @JsonSchema.Description("Wind speed, m/s. Absent or null: 0.")
    val windSpeed: Double? = null,
    @JsonSchema.Description(
        "Direction the wind blows TOWARD, degrees clockwise from north — the convention of " +
            "`dominantHeadwindAzimuth`. Absent or null: 0.",
    )
    val windDirection: Double? = null,
)

/** The rider's power strategy — the keys of the JS `PowerProviderDto` and of the WASI reader. */
@Serializable
@SerialName("PowerProvider")
data class PowerProviderDto(
    @JsonSchema.Description(
        "Power model, one of `GET /api/v1/catalog` `powerModels`, case-insensitive. `from_data` replays the " +
            "GPX's power. $SEE_DEFAULTS",
    )
    val type: String? = null,
    @JsonSchema.Description("Target power, watts. $SEE_DEFAULTS")
    val power: Double? = null,
    @JsonSchema.Description("Add a harmonic variation to the power. $SEE_DEFAULTS")
    val useHarmonics: Boolean? = null,
    @JsonSchema.Description("Critical power, watts (durability and critical-power models). $SEE_DEFAULTS")
    val criticalPower: Double? = null,
    @JsonSchema.Description("W', joules (critical-power model). $SEE_DEFAULTS")
    val wPrime: Double? = null,
    @JsonSchema.Description("Redistribute the power with the terrain. $SEE_DEFAULTS")
    val pacing: Boolean? = null,
    @JsonSchema.Description("Maximum power change rate, W/s; 0 disables. $SEE_DEFAULTS")
    val maxSlewWPerS: Double? = null,
)

/**
 * The `request` part of `POST /enhance` — the envelope of WASI's `vcEnhanceWithCourse`, plus the
 * road-width override of the CLI's `--road-width`.
 */
@Serializable
@SerialName("EnhanceRequest")
data class EnhanceRequest(
    val options: EnhanceOptionsDto? = null,
    val cyclist: CyclistDto? = null,
    val bike: BikeDto? = null,
    val wind: WindDto? = null,
    val power: PowerProviderDto? = null,
    @JsonSchema.Description(
        "Road width written on EVERY point, metres, finite and > 0 — like the CLI's `--road-width`, it beats " +
            "the GPX's own widths and the OSM inference. Absent or null: the GPX's widths.",
    )
    val roadWidthOverrideM: Double? = null,
    val output: OutputDto? = null,
)

/**
 * One enhanced path.
 *
 * @property name the `<trk>` / `<rte>` name, `null` when absent
 * @property dominantHeadwindAzimuth the wind direction (degrees, blowing toward, clockwise from
 *   north) that would be a headwind over most of the ride; `null` when undefined
 * @property points every point, only when `?points=true`
 */
@Serializable
@SerialName("TrackResult")
data class TrackResultDto(
    val name: String?,
    val summary: PathSummaryDto,
    val dominantHeadwindAzimuth: Double?,
    val points: List<PointDto>?,
)

/**
 * `POST /enhance` in `summary` format.
 *
 * @property warnings the repairs applied to the XML, and every option the request gave that
 *   another one overrode (a road-condition preset over explicit grip values)
 */
@Serializable
@SerialName("EnhanceResponse")
data class EnhanceResponse(
    val tracks: List<TrackResultDto>,
    val warnings: List<String>,
)
