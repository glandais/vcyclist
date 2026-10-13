package io.github.glandais.api.mapping

import io.github.glandais.api.dto.ClimbDto
import io.github.glandais.api.dto.ClimbOptionsDto
import io.github.glandais.api.dto.ClimbPartDto
import io.github.glandais.api.dto.CornerDto
import io.github.glandais.api.dto.EnhanceOptionsDto
import io.github.glandais.api.dto.RacingLineReportDto
import io.github.glandais.api.http.ApiJson
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.FieldError
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.engine.climb.Climb
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.climb.ClimbPart
import io.github.glandais.engine.trajectory.CornerSpan
import io.github.glandais.engine.trajectory.RacingLineOptions
import io.github.glandais.engine.trajectory.RacingLineReport
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject

/*
 * The analysis routes: request DTOs → engine options, engine results → response DTOs, and the
 * inverse for `GET /defaults`. Same rules as `EnhanceMapping.kt`: every fallback read off
 * [HttpDefaults], every rule the WASI reader's.
 */

/**
 * The climb-detector options, every absent field read off [HttpDefaults.climb]. The wire key
 * `maxDiffRealGrade` is `maxDiffRealGradeRatio`, as in WASI. An out-of-domain value trips the
 * `require` of [ClimbOptions]' `init` — an `IllegalArgumentException`, answered `invalid-option`.
 */
fun ClimbOptionsDto?.toClimbOptions(): ClimbOptions {
    val d = HttpDefaults.climb
    if (this == null) return d
    return ClimbOptions(
        minMinClimbElevationM = minMinClimbElevationM ?: d.minMinClimbElevationM,
        maxMinClimbElevationM = maxMinClimbElevationM ?: d.maxMinClimbElevationM,
        minClimbElevationRatio = minClimbElevationRatio ?: d.minClimbElevationRatio,
        minGradePercent = minGradePercent ?: d.minGradePercent,
        maxDiffRealGradeRatio = maxDiffRealGrade ?: d.maxDiffRealGradeRatio,
        booster = booster ?: d.booster,
        maxAnalysisPoints = maxAnalysisPoints ?: d.maxAnalysisPoints,
    )
}

/** The DTO [toClimbOptions] reads back into these options, for `GET /defaults`. */
fun ClimbOptions.toDto(): ClimbOptionsDto =
    ClimbOptionsDto(
        minMinClimbElevationM = minMinClimbElevationM,
        maxMinClimbElevationM = maxMinClimbElevationM,
        minClimbElevationRatio = minClimbElevationRatio,
        minGradePercent = minGradePercent,
        maxDiffRealGrade = maxDiffRealGradeRatio,
        booster = booster,
        maxAnalysisPoints = maxAnalysisPoints,
    )

/** The keys of the pipeline options `POST /analysis/racing-line` reads; any other is refused. */
private val RACING_LINE_KEYS: Set<String> = setOf("racingLineEnabled", "racingLineCorridor", "racingLineRoadWidthM")

/**
 * The racing-line options of `POST /analysis/racing-line`: the `racingLine` member of
 * [toEnhanceOptions], so the corridor and the road width are read by the very code `/enhance`
 * uses, and every setting the wire does not carry keeps [HttpDefaults.enhance]'s value.
 *
 * Only `racingLineEnabled`, `racingLineCorridor` and `racingLineRoadWidthM` are read. Any other
 * non-null key is `invalid-option` — the JS `analyzeRacingLine` silently ignores them, but here an
 * accepted key must be a used key. `racingLineEnabled: false` is refused too: calling the route is
 * the request, and a switched-off analysis is a contradiction, not an option.
 */
fun EnhanceOptionsDto?.toRacingLineOptions(at: String = "/options"): RacingLineOptions {
    if (this == null) return HttpDefaults.enhance.racingLine
    // Derived from the serializer, not listed by hand: a key added to EnhanceOptionsDto is refused
    // here until this route reads it.
    val given = ApiJson.encodeToJsonElement(EnhanceOptionsDto.serializer(), this).jsonObject
    val unread = given.filter { (key, value) -> value !is JsonNull && key !in RACING_LINE_KEYS }.keys.sorted()
    if (unread.isNotEmpty()) {
        val pointers = unread.map { "$at/$it" }
        throw ApiProblem(
            ProblemKind.INVALID_OPTION,
            "${pointers.joinToString(", ")}: only racingLine* keys are read here (${RACING_LINE_KEYS.joinToString(", ")})",
            errors = pointers.map { FieldError(it, "only racingLine* keys are read here") },
        )
    }
    if (racingLineEnabled == false) {
        throw invalidOption("$at/racingLineEnabled", "calling this operation is the request; false is a contradiction")
    }
    return toEnhanceOptions(at).racingLine
}

/** The response shape of a climb part: the names of `WasiJsonOutput.climbPartJson`. */
fun ClimbPart.toDto(): ClimbPartDto =
    ClimbPartDto(
        startDistanceM = startDistanceM,
        endDistanceM = endDistanceM,
        startElevationM = startElevationM,
        endElevationM = endElevationM,
        lengthM = lengthM,
        elevationGainM = elevationGainM,
        grade = grade,
    )

/** The response shape of a climb: the names of `WasiJsonOutput.climbsJson`. */
fun Climb.toDto(): ClimbDto =
    ClimbDto(
        startIndex = startIndex,
        endIndex = endIndex,
        startDistanceM = startDistanceM,
        endDistanceM = endDistanceM,
        startElevationM = startElevationM,
        endElevationM = endElevationM,
        lengthM = lengthM,
        elevationGainM = elevationGainM,
        averageGrade = averageGrade,
        climbingGrade = climbingGrade,
        positiveElevationM = positiveElevationM,
        negativeElevationM = negativeElevationM,
        parts = parts.map { it.toDto() },
    )

/** The response shape of a corner; `kind` is the [io.github.glandais.engine.trajectory.CornerKind] name, as in JS and WASI. */
fun CornerSpan.toDto(): CornerDto =
    CornerDto(
        fromIndex = fromIndex,
        untilIndex = untilIndex,
        apexIndex = apexIndex,
        kind = kind.name,
        turnRad = turnRad,
        direction = direction,
        radiusQ20M = radiusQ20M,
        radiusMinM = radiusMinM,
        lengthM = lengthM,
    )

/** The response shape of a racing-line report; every `NaN` becomes `null`, as in the WASI ABI. */
fun RacingLineReport.toDto(): RacingLineReportDto =
    RacingLineReportDto(
        size = size,
        corners = corners.map { it.toDto() },
        centerlineCurvature = centerlineCurvature.toNullableList(),
        trajectoryCurvature = trajectoryCurvature.toNullableList(),
        corridorLo = corridorLo.toNullableList(),
        corridorHi = corridorHi.toNullableList(),
        roadHalfWidthM = roadHalfWidthM.toNullableList(),
        lateralOffsetM = lateralOffsetM.toNullableList(),
        maxCorridorWidthM = maxCorridorWidthM.finiteOrNull(),
        newtonIterations = newtonIterations,
        relativeGradient = relativeGradient.finiteOrNull(),
        converged = converged,
        activeConstraints = activeConstraints,
    )

private fun DoubleArray.toNullableList(): List<Double?> = map { it.finiteOrNull() }
