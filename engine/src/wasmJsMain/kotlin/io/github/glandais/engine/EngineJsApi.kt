@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.climb.Climb
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.segmentsAsPaths
import io.github.glandais.engine.gpx.toGpxDocument
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.engine.path.Path
import io.github.glandais.fit.toFitBytes
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise
import kotlin.time.Instant

/**
 * JS-facing snapshot of a single path point. Read-only ; built lazily by [pointAt].
 *
 * Numeric units mirror the engine internals :
 * - `latitudeDeg`/`longitudeDeg` in degrees (converted from the radians stored on [Path]),
 * - `elevation` in meters,
 * - `timeMs` in epoch milliseconds,
 * - `speed` in m/s,
 * - `pComputedPower` in watts,
 * - `distance` in meters (cumulative from path start),
 * - `grade` as a slope ratio (rise/run).
 */
external interface PointDto : JsAny {
    val latitudeDeg: Double
    val longitudeDeg: Double
    val elevation: Double
    val timeMs: Double
    val speed: Double
    val pComputedPower: Double
    val distance: Double
    val grade: Double
}

/**
 * JS-facing snapshot of a `<wpt>` waypoint (see [io.github.glandais.engine.gpx.GpxWaypoint]).
 * Flat DTO ; built by [parseGpxWaypoints]. `timeEpochMs` is `null` when `<time>` is absent, same
 * as the Kotlin model.
 */
external interface WaypointDto : JsAny {
    val latitudeDeg: Double
    val longitudeDeg: Double
    val elevationM: Double?
    val name: String?
    val description: String?
    val symbol: String?
    val type: String?
    val timeEpochMs: Double?
}

@JsFun(
    """(latitudeDeg, longitudeDeg, elevationM, name, description, symbol, type, timeEpochMs) =>
    ({ latitudeDeg, longitudeDeg, elevationM, name, description, symbol, type, timeEpochMs })""",
)
private external fun waypointObj(
    latitudeDeg: Double,
    longitudeDeg: Double,
    elevationM: Double?,
    name: String?,
    description: String?,
    symbol: String?,
    type: String?,
    timeEpochMs: Double?,
): WaypointDto

private fun io.github.glandais.engine.gpx.GpxWaypoint.toDto(): WaypointDto =
    waypointObj(
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        elevationM = elevationM,
        name = name,
        description = description,
        symbol = symbol,
        type = type,
        timeEpochMs = timeEpochMs?.toDouble(),
    )

private fun WaypointDto.toGpxWaypoint(): io.github.glandais.engine.gpx.GpxWaypoint =
    io.github.glandais.engine.gpx.GpxWaypoint(
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        elevationM = elevationM,
        name = name,
        description = description,
        symbol = symbol,
        type = type,
        timeEpochMs = timeEpochMs?.toLong(),
    )

/** Parse [xml] and return every `<wpt>` in document order, as flat [WaypointDto] objects. */
@JsExport
fun parseGpxWaypoints(xml: String): JsArray<WaypointDto> {
    val waypoints = GpxParser.parse(xml).waypoints
    val out = JsArray<WaypointDto>()
    for ((i, w) in waypoints.withIndex()) out[i] = w.toDto()
    return out
}

/**
 * JS-side mirror of [EnhanceOptions] / [SimplifyPathOptions]. Every flag is optional ; when a
 * caller leaves a field `undefined`, [defaultJsOptions] picks the JS-safe default (skip elevation
 * fetch, skip 1 Hz resample, skip simplify — see task 27 for the timestamp pitfall).
 */
external interface EnhanceOptionsDto : JsAny {
    val fixElevation: Boolean?
    val computeMaxSpeeds: Boolean?
    val virtualizeTrack: Boolean?
    val computeOnePointPerSecond: Boolean?
    val simplifyEnabled: Boolean?
    val simplifyToleranceM: Double?
    val simplifyZExaggeration: Double?
}

@JsFun(
    """(latitudeDeg, longitudeDeg, elevation, timeMs, speed, pComputedPower, distance, grade) =>
    ({ latitudeDeg, longitudeDeg, elevation, timeMs, speed, pComputedPower, distance, grade })""",
)
private external fun pointObj(
    latitudeDeg: Double,
    longitudeDeg: Double,
    elevation: Double,
    timeMs: Double,
    speed: Double,
    pComputedPower: Double,
    distance: Double,
    grade: Double,
): PointDto

// Kotlin/Wasm 2.3.x restricts @JsExport to top-level functions, so the public API is shaped as
// free functions taking a JsReference<Path> handle. JS callers treat the handle as an opaque
// token — they read fields through pointAt / pathSize / pathTotalDistance / etc.

@JsExport
fun parseGpx(xml: String): JsReference<Path> = GpxParser.parse(xml).firstTrackAsPath().toJsReference()

/**
 * Parse [xml] and return **one handle per `<trk>`**, in document order. Tracks with no point are
 * skipped. Segments of a same track are concatenated — distance jumps across a segment boundary ;
 * use [parseGpxSegments] if that artefact matters.
 *
 * Unlike the Kotlin/JS façade, which returns a plain `Array<Path>`, Wasm has to hand back a
 * `JsArray` of opaque handles : `Path` is not a `JsAny`, so it can only cross the boundary
 * wrapped in a [JsReference].
 */
@JsExport
fun parseGpxTracks(xml: String): JsArray<JsReference<Path>> = GpxParser.parse(xml).tracksAsPaths().toHandleArray()

/**
 * Parse [xml] and return **one handle per `<trkseg>`**, across all tracks, in document order.
 * Empty segments are skipped. Every returned Path is continuous.
 */
@JsExport
fun parseGpxSegments(xml: String): JsArray<JsReference<Path>> = GpxParser.parse(xml).segmentsAsPaths().toHandleArray()

/**
 * Serialise the handles in [paths] as a multi-track GPX document — one `<trk>` per Path.
 * [waypoints], if given, is written as `<wpt>` entries before the tracks (typically the source
 * document's [io.github.glandais.engine.gpx.GpxDocument.waypoints], forwarded so a parse →
 * enhance → write round-trip does not silently drop points of interest — see g03).
 */
@JsExport
fun writeGpxTracks(
    paths: JsArray<JsReference<Path>>,
    waypoints: JsArray<WaypointDto> = JsArray(),
): String =
    GpxWriter.write(
        List(paths.length) { i -> paths[i]!!.get() },
        waypoints = List(waypoints.length) { i -> waypoints[i]!!.toGpxWaypoint() },
    )

private fun List<Path>.toHandleArray(): JsArray<JsReference<Path>> {
    val out = JsArray<JsReference<Path>>()
    for ((i, p) in withIndex()) out[i] = p.toJsReference()
    return out
}

@JsExport
fun pathSize(handle: JsReference<Path>): Int = handle.get().size

@JsExport
fun pathTotalDistance(handle: JsReference<Path>): Double = handle.get().totalDistance

@JsExport
fun pathDurationMs(handle: JsReference<Path>): Double = handle.get().durationMs

@JsExport
fun pathElevationGain(handle: JsReference<Path>): Double = handle.get().elevationGain

@JsExport
fun pathElevationLoss(handle: JsReference<Path>): Double = handle.get().elevationLoss

@JsExport
fun pointAt(
    handle: JsReference<Path>,
    i: Int,
): PointDto {
    val p = handle.get()
    return pointObj(
        latitudeDeg = p.latitudeDeg(i),
        longitudeDeg = p.longitudeDeg(i),
        elevation = p.elevation(i),
        timeMs = p.time(i),
        speed = p.speed(i),
        pComputedPower = p.pComputedPower(i),
        distance = p.distance(i),
        grade = p.grade(i),
    )
}

@JsExport
fun writeGpx(handle: JsReference<Path>): String = GpxWriter.write(handle.get().toGpxDocument(trackName = "virtualized"))

/**
 * Serialise the path behind [handle] with an absolute `<time>` on every point :
 * `<time> = startTimeEpochMs + time(i)` milliseconds (see
 * [io.github.glandais.engine.gpx.startTime] / task g05). Mirrors the Kotlin/JS façade.
 *
 * [startTimeEpochMs] is a `Double`, not a `Long` : Kotlin/Wasm's `Long` is not a `JsAny` and would
 * need its own bridging, whereas epoch milliseconds already fit exactly in a `Double` until the
 * year 287396 — the same reasoning already used by [pathDurationMs].
 */
@JsExport
fun writeGpxAt(
    handle: JsReference<Path>,
    startTimeEpochMs: Double,
): String =
    GpxWriter.write(
        handle.get().toGpxDocument(
            trackName = "virtualized",
            startTime = Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()),
        ),
    )

@JsExport
fun enhance(
    handle: JsReference<Path>,
    options: EnhanceOptionsDto?,
): Promise<JsReference<Path>> =
    GlobalScope.promise {
        val opts = options.toEnhanceOptions()
        // Auto-instantiate a default ElevationProvider when the caller asked for fixElevation.
        // Mirrors the Kotlin/JS façade. Skips allocation when fixElevation is off.
        val provider = if (opts.fixElevation) ElevationProvider() else null
        val out = Enhancer.enhanceCourseDefault(handle.get(), elevationProvider = provider, options = opts)
        out.toJsReference()
    }

private fun EnhanceOptionsDto?.toEnhanceOptions(): EnhanceOptions {
    if (this == null) return defaultJsOptions()
    return EnhanceOptions(
        fixElevation = fixElevation ?: false,
        computeMaxSpeeds = computeMaxSpeeds ?: true,
        virtualizeTrack = virtualizeTrack ?: true,
        computeOnePointPerSecond = computeOnePointPerSecond ?: false,
        simplifyPath =
            SimplifyPathOptions(
                enabled = simplifyEnabled ?: false,
                toleranceM = simplifyToleranceM ?: 10.0,
                zExaggeration = simplifyZExaggeration ?: 3.0,
            ),
    )
}

/**
 * Safe defaults for browser/Node calls : skip elevation fetch (callers opt-in via
 * `fixElevation: true` which triggers a default [ElevationProvider] inside [enhance]), skip 1 Hz
 * resample (the 2024-stamped sample fixtures blow up `PointPerSecond` — see task 27 notes) and
 * skip simplify so smoke results stay deterministic.
 */
private fun defaultJsOptions(): EnhanceOptions =
    EnhanceOptions(
        fixElevation = false,
        computeMaxSpeeds = true,
        virtualizeTrack = true,
        computeOnePointPerSecond = false,
        simplifyPath = SimplifyPathOptions(enabled = false),
    )

/**
 * Serialise the path behind [handle] to CSV (all 36 [io.github.glandais.engine.path.PointField]s,
 * one row per point) — see
 * [CsvWriter] / task g06. Mirrors the Kotlin/JS façade's `pathToCsv`.
 *
 * [separator] takes only its first character (no `Char` at the Wasm/JS interop boundary) ; an
 * empty string falls back to `,`.
 */
@JsExport
fun pathToCsv(
    handle: JsReference<Path>,
    separator: String,
    unitsInHeader: Boolean,
): String =
    CsvWriter.write(
        handle.get(),
        CsvOptions(separator = separator.firstOrNull() ?: ',', unitsInHeader = unitsInHeader),
    )

/**
 * Serialise the path behind [handle] to JSON, column-oriented (one array per
 * [io.github.glandais.engine.path.PointField]) — see [JsonWriter] / task g07. Mirrors the
 * Kotlin/JS façade's `pathToJson`.
 */
@JsExport
fun pathToJson(
    handle: JsReference<Path>,
    pretty: Boolean,
): String = JsonWriter.write(handle.get(), JsonOptions(pretty = pretty))

// ── FIT export (task g10) ────────────────────────────────────────────────────────────────────

/**
 * Encode the path behind [handle] as a Garmin FIT Course file.
 *
 * **Return type differs from the Kotlin/JS façade.** Kotlin/JS exports a `ByteArray` directly
 * (it surfaces as an `Int8Array`). Kotlin/Wasm can export neither: `ByteArray` is not a `JsAny`,
 * and `org.khronos.webgl.Uint8Array` is rejected by the exporter ("Can't export not-primary
 * constructor"). The value returned here **is** a JS `Uint8Array` — statically typed as `JsAny`
 * because that is the only thing the boundary accepts. Callers get unsigned bytes here and
 * signed ones on Kotlin/JS; both describe the same file, and `Uint8Array` is the friendlier
 * shape to hand to a `Blob` or `fetch` anyway.
 *
 * @param startTimeEpochMs absolute start instant in Unix epoch milliseconds — FIT has no
 *   relative clock. `Double` avoids a BigInt at the boundary, as [pathDurationMs] does.
 */
@JsExport
fun pathToFit(
    handle: JsReference<Path>,
    name: String,
    startTimeEpochMs: Double,
): JsAny {
    val bytes = handle.get().toFitBytes(name, Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()))
    val out = newUint8Array(bytes.size)
    for (i in bytes.indices) setUint8(out, i, bytes[i].toInt())
    return out
}

@JsFun("(n) => new Uint8Array(n)")
private external fun newUint8Array(n: Int): JsAny

@JsFun("(arr, i, v) => { arr[i] = v & 0xFF; }")
private external fun setUint8(
    arr: JsAny,
    i: Int,
    v: Int,
)

// ── Climb detection (task g12) ───────────────────────────────────────────────────────────────

/**
 * A homogeneous-grade segment of a climb. Distances are absolute along the path, in meters;
 * [grade] is dimensionless (`0.08` = 8 %).
 */
external interface ClimbPartDto : JsAny {
    val startDistanceM: Double
    val endDistanceM: Double
    val startElevationM: Double
    val endElevationM: Double
    val lengthM: Double
    val elevationGainM: Double
    val grade: Double
}

/**
 * A detected climb.
 *
 * The nested `parts` array was the thing to verify first on this target, per the g12 spec: a
 * `JsArray` of `JsAny` **inside** another exported DTO crosses the Wasm boundary without
 * trouble, because the object is built entirely on the JS side by [climbObj] and Kotlin only
 * ever holds an opaque reference to it. No flattening or second accessor function was needed.
 */
external interface ClimbDto : JsAny {
    val startIndex: Int
    val endIndex: Int
    val startDistanceM: Double
    val endDistanceM: Double
    val startElevationM: Double
    val endElevationM: Double
    val lengthM: Double
    val elevationGainM: Double
    val averageGrade: Double
    val climbingGrade: Double
    val positiveElevationM: Double
    val negativeElevationM: Double
    val parts: JsArray<ClimbPartDto>
}

@JsFun(
    """(startDistanceM, endDistanceM, startElevationM, endElevationM, lengthM, elevationGainM, grade) =>
    ({ startDistanceM, endDistanceM, startElevationM, endElevationM, lengthM, elevationGainM, grade })""",
)
private external fun climbPartObj(
    startDistanceM: Double,
    endDistanceM: Double,
    startElevationM: Double,
    endElevationM: Double,
    lengthM: Double,
    elevationGainM: Double,
    grade: Double,
): ClimbPartDto

@JsFun(
    """(startIndex, endIndex, startDistanceM, endDistanceM, startElevationM, endElevationM, lengthM,
        elevationGainM, averageGrade, climbingGrade, positiveElevationM, negativeElevationM, parts) =>
    ({ startIndex, endIndex, startDistanceM, endDistanceM, startElevationM, endElevationM, lengthM,
       elevationGainM, averageGrade, climbingGrade, positiveElevationM, negativeElevationM, parts })""",
)
private external fun climbObj(
    startIndex: Int,
    endIndex: Int,
    startDistanceM: Double,
    endDistanceM: Double,
    startElevationM: Double,
    endElevationM: Double,
    lengthM: Double,
    elevationGainM: Double,
    averageGrade: Double,
    climbingGrade: Double,
    positiveElevationM: Double,
    negativeElevationM: Double,
    parts: JsArray<ClimbPartDto>,
): ClimbDto

private fun Climb.toDto(): ClimbDto {
    val partArray = JsArray<ClimbPartDto>()
    for ((i, part) in parts.withIndex()) {
        partArray[i] =
            climbPartObj(
                part.startDistanceM,
                part.endDistanceM,
                part.startElevationM,
                part.endElevationM,
                part.lengthM,
                part.elevationGainM,
                part.grade,
            )
    }
    return climbObj(
        startIndex,
        endIndex,
        startDistanceM,
        endDistanceM,
        startElevationM,
        endElevationM,
        lengthM,
        elevationGainM,
        averageGrade,
        climbingGrade,
        positiveElevationM,
        negativeElevationM,
        partArray,
    )
}

private fun List<Climb>.toDtoArray(): JsArray<ClimbDto> {
    val out = JsArray<ClimbDto>()
    for ((i, climb) in withIndex()) out[i] = climb.toDto()
    return out
}

/** Detect climbs on the path behind [handle] with the default options. */
@JsExport
fun detectClimbs(handle: JsReference<Path>): JsArray<ClimbDto> = ClimbDetector.detect(handle.get()).toDtoArray()

/** Detect climbs with explicit parameters. Same signature as the Kotlin/JS façade. */
@JsExport
fun detectClimbsWithOptions(
    handle: JsReference<Path>,
    minMinClimbElevationM: Double,
    maxMinClimbElevationM: Double,
    minClimbElevationRatio: Double,
    minGradePercent: Double,
    maxDiffRealGrade: Double,
    booster: Double,
): JsArray<ClimbDto> =
    ClimbDetector
        .detect(
            handle.get(),
            ClimbOptions(
                minMinClimbElevationM = minMinClimbElevationM,
                maxMinClimbElevationM = maxMinClimbElevationM,
                minClimbElevationRatio = minClimbElevationRatio,
                minGradePercent = minGradePercent,
                maxDiffRealGradeRatio = maxDiffRealGrade,
                booster = booster,
            ),
        ).toDtoArray()
