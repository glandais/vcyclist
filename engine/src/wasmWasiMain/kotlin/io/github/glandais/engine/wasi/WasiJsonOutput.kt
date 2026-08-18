package io.github.glandais.engine.wasi

import io.github.glandais.engine.climb.Climb
import io.github.glandais.engine.climb.ClimbPart
import io.github.glandais.engine.gpx.GpxWaypoint
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.trajectory.CornerSpan
import io.github.glandais.engine.trajectory.RacingLineReport

// The JSON shapes this ABI emits, one function per `EngineJsApi` DTO.
//
// Field for field, they are the JS DTOs — `PointDto`, `WaypointDto`, `FieldDefinitionDto`,
// `ClimbDto`, `ClimbPartDto`. A host that already consumes the npm package can reuse its types
// unchanged; that symmetry is the reason JSON was chosen over a pile of numeric exports in the
// first place.
//
// Not to be confused with `io.github.glandais.engine.io.JsonWriter`, which serialises a whole
// path column-wise and is reached through `vcPathToJson`. This file is about the small objects.

/** Mirror of `PointDto`: one point, in degrees / meters / m·s⁻¹ / watts / epoch ms. */
internal fun pointJson(
    path: Path,
    i: Int,
): String =
    jsonObject(
        "latitudeDeg" to jsonNumber(path.latitudeDeg(i)),
        "longitudeDeg" to jsonNumber(path.longitudeDeg(i)),
        "elevation" to jsonNumber(path.elevation(i)),
        "timeMs" to jsonNumber(path.time(i)),
        "speed" to jsonNumber(path.speed(i)),
        "pComputedPower" to jsonNumber(path.pComputedPower(i)),
        "distance" to jsonNumber(path.distance(i)),
        "grade" to jsonNumber(path.grade(i)),
    )

/** Mirror of `WaypointDto`. Absent optionals are `null`, as they are on the JS side. */
internal fun waypointJson(w: GpxWaypoint): String =
    jsonObject(
        "latitudeDeg" to jsonNumber(w.latitudeDeg),
        "longitudeDeg" to jsonNumber(w.longitudeDeg),
        "elevationM" to (w.elevationM?.let(::jsonNumber) ?: "null"),
        "name" to (w.name?.let(::jsonString) ?: "null"),
        "description" to (w.description?.let(::jsonString) ?: "null"),
        "symbol" to (w.symbol?.let(::jsonString) ?: "null"),
        "type" to (w.type?.let(::jsonString) ?: "null"),
        "timeEpochMs" to (w.timeEpochMs?.toDouble()?.let(::jsonNumber) ?: "null"),
    )

/**
 * Mirror of `FieldDefinitionDto`, plus an `index` the JS array does not need.
 *
 * That index **is** the field identifier of the numeric exports (`vcGetField`,
 * `vcPathFieldBytes`): passing a 36-character name through linear memory for every read would
 * cost more than the read. It is `PointField.entries`' ordinal, so this catalog is how a host
 * learns the mapping instead of hard-coding it — the list has grown before and will again.
 */
internal fun fieldDefinitionsJson(): String =
    jsonArray(
        PointField.entries.mapIndexed { index, f ->
            jsonObject(
                "index" to index.toString(),
                "prop" to jsonString(f.prop),
                "unit" to jsonString(f.unit),
                "shortDescription" to jsonString(f.shortDescription),
                "categoryId" to jsonString(f.category.id),
                "categoryName" to jsonString(f.category.displayName),
                "notSelectable" to f.notSelectable.toString(),
                "anglesInRadians" to f.anglesInRadians.toString(),
            )
        },
    )

/** Mirror of `ClimbPartDto`. */
private fun climbPartJson(p: ClimbPart): String =
    jsonObject(
        "startDistanceM" to jsonNumber(p.startDistanceM),
        "endDistanceM" to jsonNumber(p.endDistanceM),
        "startElevationM" to jsonNumber(p.startElevationM),
        "endElevationM" to jsonNumber(p.endElevationM),
        "lengthM" to jsonNumber(p.lengthM),
        "elevationGainM" to jsonNumber(p.elevationGainM),
        "grade" to jsonNumber(p.grade),
    )

/** Mirror of `ClimbDto`, nested parts included. Grades are ratios: `0.08` is 8 %. */
internal fun climbsJson(climbs: List<Climb>): String =
    jsonArray(
        climbs.map { c ->
            jsonObject(
                "startIndex" to c.startIndex.toString(),
                "endIndex" to c.endIndex.toString(),
                "startDistanceM" to jsonNumber(c.startDistanceM),
                "endDistanceM" to jsonNumber(c.endDistanceM),
                "startElevationM" to jsonNumber(c.startElevationM),
                "endElevationM" to jsonNumber(c.endElevationM),
                "lengthM" to jsonNumber(c.lengthM),
                "elevationGainM" to jsonNumber(c.elevationGainM),
                "averageGrade" to jsonNumber(c.averageGrade),
                "climbingGrade" to jsonNumber(c.climbingGrade),
                "positiveElevationM" to jsonNumber(c.positiveElevationM),
                "negativeElevationM" to jsonNumber(c.negativeElevationM),
                "parts" to jsonArray(c.parts.map(::climbPartJson)),
            )
        },
    )

/** Mirror of `CornerDto`. `kind` is the [CornerKind] name, as on the JS side. */
private fun cornerJson(c: CornerSpan): String =
    jsonObject(
        "fromIndex" to c.fromIndex.toString(),
        "untilIndex" to c.untilIndex.toString(),
        "apexIndex" to c.apexIndex.toString(),
        "kind" to jsonString(c.kind.name),
        "turnRad" to jsonNumber(c.turnRad),
        "direction" to c.direction.toString(),
        "radiusQ20M" to jsonNumber(c.radiusQ20M),
        "radiusMinM" to jsonNumber(c.radiusMinM),
        "lengthM" to jsonNumber(c.lengthM),
    )

/**
 * Mirror of `RacingLineReportDto`, corners nested.
 *
 * One asymmetry with the JS façade, and it is inherent rather than an oversight: the per-point
 * arrays carry `NaN` where a value was not computed, and JSON has no `NaN` literal, so
 * [jsonNumber] emits `null` for those slots — the convention this ABI already uses everywhere.
 * A host reading `centerlineCurvature` therefore sees `null`, not `NaN`, and must treat the two
 * as the same "not computed". The JS `DoubleArray` keeps the real `NaN`.
 */
internal fun racingLineReportJson(r: RacingLineReport): String =
    jsonObject(
        "size" to r.size.toString(),
        "corners" to jsonArray(r.corners.map(::cornerJson)),
        "centerlineCurvature" to jsonNumberArray(r.centerlineCurvature),
        "trajectoryCurvature" to jsonNumberArray(r.trajectoryCurvature),
        "corridorLo" to jsonNumberArray(r.corridorLo),
        "corridorHi" to jsonNumberArray(r.corridorHi),
        "roadHalfWidthM" to jsonNumberArray(r.roadHalfWidthM),
        "lateralOffsetM" to jsonNumberArray(r.lateralOffsetM),
        "maxCorridorWidthM" to jsonNumber(r.maxCorridorWidthM),
        "newtonIterations" to r.newtonIterations.toString(),
        "relativeGradient" to jsonNumber(r.relativeGradient),
        "converged" to r.converged.toString(),
        "activeConstraints" to r.activeConstraints.toString(),
    )

/** `[…]` of numbers, non-finite slots becoming `null` — see [racingLineReportJson]. */
private fun jsonNumberArray(values: DoubleArray): String = values.joinToString(",", "[", "]", transform = ::jsonNumber)
