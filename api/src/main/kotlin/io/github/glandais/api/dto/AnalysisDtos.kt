package io.github.glandais.api.dto

import io.ktor.openapi.JsonSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Climb-detector options — the seven keys of the WASI reader (`CLIMB_KEYS`), spelled the same:
 * `maxDiffRealGrade` is the published name of `ClimbOptions.maxDiffRealGradeRatio`.
 */
@Serializable
@SerialName("ClimbOptions")
data class ClimbOptionsDto(
    @JsonSchema.Description("Floor of the dynamic elevation threshold, metres. $SEE_DEFAULTS")
    val minMinClimbElevationM: Double? = null,
    @JsonSchema.Description("Ceiling of the dynamic elevation threshold, metres. $SEE_DEFAULTS")
    val maxMinClimbElevationM: Double? = null,
    @JsonSchema.Description("The path's total ascent is divided by this to size the threshold. $SEE_DEFAULTS")
    val minClimbElevationRatio: Double? = null,
    @JsonSchema.Description("Minimum average grade of a climb, percent. $SEE_DEFAULTS")
    val minGradePercent: Double? = null,
    @JsonSchema.Description("Upper bound on climbingGrade / averageGrade, a ratio. $SEE_DEFAULTS")
    val maxDiffRealGrade: Double? = null,
    @JsonSchema.Description("Exponent of the grade when scoring a candidate. $SEE_DEFAULTS")
    val booster: Double? = null,
    @JsonSchema.Description("Bound on the points the O(n²) search looks at, at least 2. $SEE_DEFAULTS")
    val maxAnalysisPoints: Int? = null,
)

/** The `request` part of `POST /analysis/climbs`. */
@Serializable
@SerialName("ClimbRequest")
data class ClimbRequest(
    val options: ClimbOptionsDto? = null,
)

/** A homogeneous-grade segment of a climb — the names of `WasiJsonOutput.climbPartJson`. */
@Serializable
@SerialName("ClimbPart")
data class ClimbPartDto(
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElevationM: Double,
    val endElevationM: Double,
    val lengthM: Double,
    val elevationGainM: Double,
    @JsonSchema.Description("Grade, a ratio: 0.08 is 8 %.")
    val grade: Double,
)

/** A detected climb — the names of `WasiJsonOutput.climbsJson`. Grades are ratios. */
@Serializable
@SerialName("Climb")
data class ClimbDto(
    val startIndex: Int,
    val endIndex: Int,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElevationM: Double,
    val endElevationM: Double,
    val lengthM: Double,
    val elevationGainM: Double,
    @JsonSchema.Description("Net gain over length, a ratio: 0.08 is 8 %.")
    val averageGrade: Double,
    @JsonSchema.Description("Positive elevation over rising distance, a ratio.")
    val climbingGrade: Double,
    val positiveElevationM: Double,
    @JsonSchema.Description("Sum of the descents inside the climb, negative.")
    val negativeElevationM: Double,
    val parts: List<ClimbPartDto>,
)

/** `POST /analysis/climbs`: the climbs of each selected path, in path order. */
@Serializable
@SerialName("ClimbsResponse")
data class ClimbsResponse(
    val tracks: List<List<ClimbDto>>,
    val warnings: List<String>,
)

/**
 * The `request` part of `POST /analysis/racing-line`: the pipeline options object, of which only
 * `racingLineEnabled`, `racingLineCorridor` and `racingLineRoadWidthM` are read — as the JS
 * `analyzeRacingLine` does. Any other key given is refused (`invalid-option`): an accepted key must
 * be a used key.
 */
@Serializable
@SerialName("RacingLineRequest")
data class RacingLineRequest(
    val options: EnhanceOptionsDto? = null,
)

/** A detected corner — the names of `WasiJsonOutput.cornerJson`. */
@Serializable
@SerialName("Corner")
data class CornerDto(
    val fromIndex: Int,
    val untilIndex: Int,
    val apexIndex: Int,
    @JsonSchema.Description("One of `GET /api/v1/catalog` `cornerKinds`.")
    val kind: String,
    val turnRad: Double,
    @JsonSchema.Description("+1 left, -1 right.")
    val direction: Int,
    val radiusQ20M: Double,
    val radiusMinM: Double,
    val lengthM: Double,
)

/**
 * What the racing line would do to a path — the names of `WasiJsonOutput.racingLineReportJson`.
 * The per-station arrays carry `null` where a value was not computed (`NaN` in the engine).
 */
@Serializable
@SerialName("RacingLineReport")
data class RacingLineReportDto(
    val size: Int,
    val corners: List<CornerDto>,
    val centerlineCurvature: List<Double?>,
    val trajectoryCurvature: List<Double?>,
    val corridorLo: List<Double?>,
    val corridorHi: List<Double?>,
    val roadHalfWidthM: List<Double?>,
    val lateralOffsetM: List<Double?>,
    val maxCorridorWidthM: Double?,
    val newtonIterations: Int,
    val relativeGradient: Double?,
    @JsonSchema.Description("Whether the solver converged; a non-converged line is still reported.")
    val converged: Boolean,
    val activeConstraints: Int,
)

/** `POST /analysis/racing-line`: one report per selected path, `null` for a path too short or degenerate. */
@Serializable
@SerialName("RacingLineResponse")
data class RacingLineResponse(
    val tracks: List<RacingLineReportDto?>,
    val warnings: List<String>,
)

/**
 * `POST /analysis/headwind`: the compass azimuth, in degrees, the wind should blow **toward** to be
 * a headwind on most of the course — over every selected path together, and for each one. `null`
 * when there is no answer (fewer than 4 points, or a course whose mean direction cancels out).
 */
@Serializable
@SerialName("HeadwindResponse")
data class HeadwindResponse(
    val overall: Double?,
    val tracks: List<Double?>,
    val warnings: List<String>,
)
