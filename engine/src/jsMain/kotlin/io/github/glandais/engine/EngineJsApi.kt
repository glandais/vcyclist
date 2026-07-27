@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
)

package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
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
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderConstantWithTiring
import io.github.glandais.engine.physics.PowerProviderFromData
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import io.github.glandais.fit.toFitBytes
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise
import kotlin.math.PI
import kotlin.time.Instant

/**
 * JS-facing snapshot of a single path point. Read-only ; built lazily by [pointAt]. Field units
 * mirror the Kotlin/Wasm façade (degrees, meters, m/s, watts, epoch ms, slope ratio).
 */
external interface PointDto {
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
external interface WaypointDto {
    val latitudeDeg: Double
    val longitudeDeg: Double
    val elevationM: Double?
    val name: String?
    val description: String?
    val symbol: String?
    val type: String?
    val timeEpochMs: Double?
}

private fun waypointObj(w: io.github.glandais.engine.gpx.GpxWaypoint): WaypointDto {
    val o = js("({})")
    o.latitudeDeg = w.latitudeDeg
    o.longitudeDeg = w.longitudeDeg
    o.elevationM = w.elevationM
    o.name = w.name
    o.description = w.description
    o.symbol = w.symbol
    o.type = w.type
    o.timeEpochMs = w.timeEpochMs?.toDouble()
    return o.unsafeCast<WaypointDto>()
}

/** Parse [xml] and return every `<wpt>` in document order, as flat [WaypointDto] objects. */
@JsExport
fun parseGpxWaypoints(xml: String): Array<WaypointDto> =
    GpxParser
        .parse(xml)
        .waypoints
        .map(::waypointObj)
        .toTypedArray()

/**
 * JS-side mirror of [EnhanceOptions] / [SimplifyPathOptions]. Every flag is optional ; when a
 * caller leaves a field `undefined`, [defaultJsOptions] picks the JS-safe default (skip elevation
 * fetch, skip 1 Hz resample, skip simplify — see task 27 for the timestamp pitfall).
 */
external interface EnhanceOptionsDto {
    val fixElevation: Boolean?
    val computeMaxSpeeds: Boolean?
    val virtualizeTrack: Boolean?
    val computeOnePointPerSecond: Boolean?
    val simplifyEnabled: Boolean?
    val simplifyToleranceM: Double?
    val simplifyZExaggeration: Double?
}

/**
 * JS-side mirror of [Cyclist]. All fields required when the DTO is passed (Kotlin/JS does not
 * gracefully tolerate optional fields on `external interface` without `?`). Callers can pass
 * `null` for the whole DTO to fall back to engine defaults (80 kg / 0.7 Cd / 0.5 m² / 35° / 100
 * km/h / 0.6 g brake).
 */
external interface CyclistDto {
    val massKg: Double
    val cd: Double
    val frontalAreaM2: Double
    val maxLeanAngleDeg: Double
    val maxBrakeG: Double
    val maxSpeedKmH: Double
}

/**
 * JS-side mirror of [Bike]. Pass `null` to use defaults (Crr 0.004, road-bike inertias,
 * 0.7 m wheel radius, 0.95 drivetrain efficiency).
 */
external interface BikeDto {
    val crr: Double
    val inertiaFront: Double
    val inertiaRear: Double
    val wheelRadiusM: Double
    val efficiency: Double
}

/**
 * JS-side mirror of constant-wind input. [windDirection] is in **degrees** (meteorological
 * convention: 0 = North, 90 = East) — the helper [toWindProvider] converts to the radians the
 * engine internally uses.
 */
external interface WindDto {
    val windSpeed: Double
    val windDirection: Double
}

/**
 * JS-side description of which [CyclistPowerProvider] to instantiate.
 *
 * - `type = "constant"` → [PowerProviderConstant] (requires [power], optional [useHarmonics]).
 * - `type = "constant_tiring"` → [PowerProviderConstantWithTiring] (requires [power] and
 *   [tiringDuration] in seconds, optional [useHarmonics]).
 * - `type = "from_data"` → [PowerProviderFromData] singleton (replays `pInputPower` from the
 *   input path).
 */
external interface PowerProviderDto {
    val type: String
    val power: Double?
    val useHarmonics: Boolean?
    val tiringDuration: Double?
}

/**
 * Catalog entry for a [PointField], mirroring the TS `FIELD_DEFINITIONS` shape. Exposed to JS
 * via [fieldDefinitions] so consumers can render generic field-pickers without hard-coding
 * the 36-entry list.
 */
external interface FieldDefinitionDto {
    val prop: String
    val unit: String
    val shortDescription: String
    val categoryId: String
    val categoryName: String
    val notSelectable: Boolean
    val anglesInRadians: Boolean
}

private fun pointObj(
    latitudeDeg: Double,
    longitudeDeg: Double,
    elevation: Double,
    timeMs: Double,
    speed: Double,
    pComputedPower: Double,
    distance: Double,
    grade: Double,
): PointDto {
    val o = js("({})")
    o.latitudeDeg = latitudeDeg
    o.longitudeDeg = longitudeDeg
    o.elevation = elevation
    o.timeMs = timeMs
    o.speed = speed
    o.pComputedPower = pComputedPower
    o.distance = distance
    o.grade = grade
    return o.unsafeCast<PointDto>()
}

// Mirrors the Kotlin/Wasm façade in src/wasmJsMain — same free-function shape. On Kotlin/JS
// the Path instance is returned directly (no JsReference handle needed: Kotlin/JS classes are
// first-class JS objects, opaque to consumers who only reach into them through pointAt / size
// helpers).

@JsExport
fun parseGpx(xml: String): Path = GpxParser.parse(xml).firstTrackAsPath()

/**
 * Parse [xml] and return **one Path per `<trk>`**, in document order. Tracks with no point are
 * skipped. Segments of a same track are concatenated — distance jumps across a segment
 * boundary ; use [parseGpxSegments] if that artefact matters.
 */
@JsExport
fun parseGpxTracks(xml: String): Array<Path> = GpxParser.parse(xml).tracksAsPaths().toTypedArray()

/**
 * Parse [xml] and return **one Path per `<trkseg>`**, across all tracks, in document order.
 * Empty segments are skipped. Every returned Path is continuous.
 */
@JsExport
fun parseGpxSegments(xml: String): Array<Path> = GpxParser.parse(xml).segmentsAsPaths().toTypedArray()

/**
 * Serialise [paths] as a multi-track GPX document — one `<trk>` per Path. [waypoints], if given,
 * is written as `<wpt>` entries before the tracks (typically the source document's
 * [io.github.glandais.engine.gpx.GpxDocument.waypoints], forwarded so a parse → enhance → write
 * round-trip does not silently drop points of interest — see g03).
 */
@JsExport
fun writeGpxTracks(
    paths: Array<Path>,
    waypoints: Array<WaypointDto> = emptyArray(),
): String = GpxWriter.write(paths.toList(), waypoints = waypoints.map { it.toGpxWaypoint() })

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

@JsExport
fun pathSize(path: Path): Int = path.size

@JsExport
fun pathTotalDistance(path: Path): Double = path.totalDistance

@JsExport
fun pathDurationMs(path: Path): Double = path.durationMs

@JsExport
fun pathElevationGain(path: Path): Double = path.elevationGain

@JsExport
fun pathElevationLoss(path: Path): Double = path.elevationLoss

@JsExport
fun pointAt(
    path: Path,
    i: Int,
): PointDto =
    pointObj(
        latitudeDeg = path.latitudeDeg(i),
        longitudeDeg = path.longitudeDeg(i),
        elevation = path.elevation(i),
        timeMs = path.time(i),
        speed = path.speed(i),
        pComputedPower = path.pComputedPower(i),
        distance = path.distance(i),
        grade = path.grade(i),
    )

@JsExport
fun writeGpx(path: Path): String = GpxWriter.write(path.toGpxDocument(trackName = "virtualized"))

/**
 * Serialise [path] with an absolute `<time>` on every point : `<time> = startTimeEpochMs +
 * time(i)` milliseconds (see [io.github.glandais.engine.gpx.startTime] / task g05).
 *
 * [startTimeEpochMs] is a `Double`, not a `Long` : on Kotlin/JS `Long` crosses the boundary as a
 * `BigInt`, which is awkward for callers building it from `Date.now()` or `new Date(...).getTime()`
 * (both already `number`/`Double`). Epoch milliseconds fit exactly in a `Double` until the year
 * 287396 — the same reasoning already used by [pathDurationMs].
 */
@JsExport
fun writeGpxAt(
    path: Path,
    startTimeEpochMs: Double,
): String =
    GpxWriter.write(
        path.toGpxDocument(
            trackName = "virtualized",
            startTime = Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()),
        ),
    )

@JsExport
fun enhance(
    path: Path,
    options: EnhanceOptionsDto?,
): Promise<Path> =
    GlobalScope.promise {
        val opts = options.toEnhanceOptions()
        // Auto-instantiate a default ElevationProvider when the caller asked for fixElevation.
        // This makes `enhance(path, { fixElevation: true })` work end-to-end on Node (and browser
        // when network policy allows) without requiring callers to thread a provider through the
        // free-function façade. Skips allocation when fixElevation is off.
        val provider = if (opts.fixElevation) ElevationProvider() else null
        Enhancer.enhanceCourseDefault(path, elevationProvider = provider, options = opts)
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

// ── Expanded JS API (task 34) ────────────────────────────────────────────────────────────────
//
// The `enhanceWithCourse` façade lets a JS caller build a full [CoursePhysics] (cyclist + bike
// + wind + power provider) from JSON-like DTO inputs and run the enhancement pipeline. Mirrors
// the TS `Enhancer.enhanceCourse` entry point. `getField` and `fieldDefinitions` expose
// generic per-field access for the demo's UI which needs to plot any of the 36 fields.

/**
 * Convert a JS [CyclistDto] (or `null` → defaults) into a [Cyclist]. Note : the [Cyclist]
 * data class no longer carries a power field — the power strategy is supplied separately via
 * [PowerProviderDto] / [toCyclistPowerProvider].
 */
private fun CyclistDto?.toCyclist(): Cyclist {
    if (this == null) return Cyclist()
    return Cyclist(
        massKg = massKg,
        maxBrakeG = maxBrakeG,
        cd = cd,
        frontalAreaM2 = frontalAreaM2,
        maxLeanAngleDeg = maxLeanAngleDeg,
        maxSpeedKmH = maxSpeedKmH,
    )
}

/** Convert a JS [BikeDto] (or `null` → defaults) into a [Bike]. */
private fun BikeDto?.toBike(): Bike {
    if (this == null) return Bike()
    return Bike(
        crr = crr,
        inertiaFront = inertiaFront,
        inertiaRear = inertiaRear,
        wheelRadiusM = wheelRadiusM,
        efficiency = efficiency,
    )
}

/**
 * Convert a JS [WindDto] into a [WindProvider]. The DTO's `windDirection` is in degrees
 * (meteorological convention) ; we convert to the radians expected by [Wind.directionRad].
 * `null` → [WindProviderNone].
 */
private fun WindDto?.toWindProvider(): WindProvider {
    if (this == null) return WindProviderNone
    return WindProviderConstant(Wind(speedMS = windSpeed, directionRad = windDirection * PI / 180.0))
}

/**
 * Convert a JS [PowerProviderDto] (or `null` → 250 W constant) into a [CyclistPowerProvider].
 *
 * - `"constant"` → [PowerProviderConstant] (default 250 W if `power` omitted).
 * - `"constant_tiring"` → [PowerProviderConstantWithTiring] (default 7200 s duration).
 * - `"from_data"` → the [PowerProviderFromData] singleton.
 */
private fun PowerProviderDto?.toCyclistPowerProvider(): CyclistPowerProvider {
    if (this == null) return PowerProviderConstant(250.0, useHarmonics = false)
    return when (type) {
        "constant" ->
            PowerProviderConstant(
                power = power ?: 250.0,
                useHarmonics = useHarmonics ?: false,
            )
        "constant_tiring" ->
            PowerProviderConstantWithTiring(
                power = power ?: 250.0,
                useHarmonics = useHarmonics ?: false,
                durationSeconds = tiringDuration ?: 7200.0,
            )
        "from_data" -> PowerProviderFromData
        else -> error("Unknown PowerProviderDto.type: $type")
    }
}

/**
 * Enhance [path] using a fully custom [CoursePhysics] (cyclist + bike + wind + power provider).
 *
 * Any DTO parameter may be `null` ; defaults are picked from the engine
 * (see [CyclistDto], [BikeDto], [WindDto], [PowerProviderDto] KDoc for specifics).
 *
 * Like [enhance], when `options.fixElevation == true` a default [ElevationProvider] is
 * auto-instantiated (Terrarium tiles via HTTP).
 */
@JsExport
fun enhanceWithCourse(
    path: Path,
    cyclist: CyclistDto?,
    bike: BikeDto?,
    wind: WindDto?,
    power: PowerProviderDto?,
    options: EnhanceOptionsDto?,
): Promise<Path> =
    GlobalScope.promise {
        val opts = options.toEnhanceOptions()
        val provider = if (opts.fixElevation) ElevationProvider() else null
        val course =
            CoursePhysics(
                course = Course(path = path, cyclist = cyclist.toCyclist(), bike = bike.toBike()),
                rhoProvider = RhoProviderEstimate,
                aeroProvider = AeroProviderConstant,
                windProvider = wind.toWindProvider(),
                cyclistPowerProvider = power.toCyclistPowerProvider(),
            )
        Enhancer.enhanceCourse(course, opts, elevationProvider = provider)
    }

/**
 * Read field [fieldProp] (camelCase, e.g. `"elevation"` / `"speed"`) at point [i]. Throws if
 * the field name is unknown.
 */
@JsExport
fun getField(
    path: Path,
    i: Int,
    fieldProp: String,
): Double {
    val field =
        PointField.byProp(fieldProp)
            ?: error("Unknown PointField prop: $fieldProp")
    return path.get(i, field)
}

/**
 * Enumerate the 36-entry [PointField] catalog as JS-friendly [FieldDefinitionDto] objects.
 * Mirrors the TS `FIELD_DEFINITIONS` array — UIs use this to render generic field-pickers
 * without hard-coding the list.
 */
@JsExport
fun fieldDefinitions(): Array<FieldDefinitionDto> =
    PointField.entries
        .map { f ->
            val o = js("({})")
            o.prop = f.prop
            o.unit = f.unit
            o.shortDescription = f.shortDescription
            o.categoryId = f.category.id
            o.categoryName = f.category.displayName
            o.notSelectable = f.notSelectable
            o.anglesInRadians = f.anglesInRadians
            o.unsafeCast<FieldDefinitionDto>()
        }.toTypedArray()

/** Latitude of point [i] in degrees (convenience wrapper around [Path.latitudeDeg]). */
@JsExport
fun pathLatitudeDeg(
    path: Path,
    i: Int,
): Double = path.latitudeDeg(i)

/** Longitude of point [i] in degrees (convenience wrapper around [Path.longitudeDeg]). */
@JsExport
fun pathLongitudeDeg(
    path: Path,
    i: Int,
): Double = path.longitudeDeg(i)

/**
 * Serialise [path] to CSV (all 36 [PointField]s, one row per point) — see [CsvWriter] / task g06.
 * Lets a browser demo offer an "Export CSV" button without a server round-trip : the caller
 * wraps the returned `String` in a `Blob` and triggers a download via
 * `URL.createObjectURL`.
 *
 * [separator] takes only its first character (JS has no `Char` type at the interop boundary) ;
 * an empty string falls back to `,`.
 */
@JsExport
fun pathToCsv(
    path: Path,
    separator: String,
    unitsInHeader: Boolean,
): String =
    CsvWriter.write(
        path,
        CsvOptions(separator = separator.firstOrNull() ?: ',', unitsInHeader = unitsInHeader),
    )

/**
 * Serialise [path] to JSON, column-oriented (one array per [PointField]) — see [JsonWriter] /
 * task g07. Lets a browser demo hand the result straight to `JSON.parse` and feed a chart
 * library (e.g. Chart.js) without a server round-trip.
 */
@JsExport
fun pathToJson(
    path: Path,
    pretty: Boolean,
): String = JsonWriter.write(path, JsonOptions(pretty = pretty))

// ── FIT export (task g10) ────────────────────────────────────────────────────────────────────

/**
 * Encode [path] as a Garmin FIT Course file.
 *
 * @param startTimeEpochMs absolute start instant in Unix epoch milliseconds. FIT has no relative
 *   clock, so this is mandatory — `Double` rather than `Long` to avoid a BigInt at the JS
 *   boundary, matching the convention [pathDurationMs] and [writeGpxAt] already use.
 * @return the complete FIT file. On Kotlin/JS a `ByteArray` surfaces as a JS `Int8Array`; the
 *   Wasm façade returns a `Uint8Array` instead — see its KDoc for why they differ.
 */
@JsExport
fun pathToFit(
    path: Path,
    name: String,
    startTimeEpochMs: Double,
): ByteArray = path.toFitBytes(name, Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()))
