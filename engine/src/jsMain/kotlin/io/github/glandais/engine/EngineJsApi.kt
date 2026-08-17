@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
)

package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.climb.Climb
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.climb.ClimbPart
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxPathKind
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
import io.github.glandais.engine.path.dominantHeadwindAzimuthDeg
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.PowerProviderDurability
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/**
 * JS-facing snapshot of a single path point. Read-only ; built lazily by [pointAt]. Field units
 * are degrees, meters, m/s, watts, epoch ms, slope ratio.
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

    /** Lean angle (°) past which the rider stops pedalling; `90` disables the cut-off. */
    val maxPedalingLeanAngleDeg: Double?
}

/**
 * JS-side mirror of constant-wind input. [windDirection] is in **degrees**, `0` = north,
 * `90` = east — [toWindProvider] converts to the radians the engine uses internally.
 *
 * The angle names the direction the wind blows **toward**, not the meteorological "from" it was
 * long documented as. Task g31 established this by simulation; see
 * `PathWind.dominantHeadwindAzimuthDeg`, whose output goes into this field unchanged.
 */
external interface WindDto {
    val windSpeed: Double
    val windDirection: Double
}

/**
 * JS-side description of which [CyclistPowerProvider] to instantiate.
 *
 * - `type = "constant"` → [PowerProviderConstant] (requires [power], optional [useHarmonics]).
 * - `type = "durability"` → [PowerProviderDurability] (requires [power], optional
 *   [criticalPower] and [useHarmonics]) — power fades with work accumulated above CP.
 * - `type = "from_data"` → [PowerProviderFromData] singleton (replays `pInputPower` from the
 *   input path).
 */
external interface PowerProviderDto {
    val type: String
    val power: Double?
    val useHarmonics: Boolean?
    val criticalPower: Double?
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

// On Kotlin/JS the Path instance is returned directly (no JsReference handle needed: Kotlin/JS
// classes are first-class JS objects, opaque to consumers who only reach into them through
// pointAt / size helpers).

@JsExport
fun parseGpx(xml: String): Path = GpxParser.parse(xml).firstTrackAsPath()

/**
 * Parse [xml] and return **one Path per `<trk>`**, in document order. Tracks with no point are
 * skipped. Segments of a same track are concatenated — distance jumps across a segment
 * boundary ; use [parseGpxSegments] if that artefact matters.
 *
 * **Since task g24 this includes `<rte>` routes**, which used to be dropped silently. Use
 * [parseGpxTracksOnly] for the pre-g24 selection.
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
 * Like [parseGpxTracks] but **`<trk>` only** — routes are left out (task g29).
 *
 * `GpxPathKind` is deliberately not exported: an enum crosses to JavaScript as an object nobody
 * wants to import just to filter a list. Two named functions say the same thing at the call site.
 */
@JsExport
fun parseGpxTracksOnly(xml: String): Array<Path> =
    GpxParser
        .parse(xml)
        .tracksAsPaths(kinds = setOf(GpxPathKind.TRACK))
        .toTypedArray()

/** Like [parseGpxTracks] but **`<rte>` only** — recorded tracks are left out (task g29). */
@JsExport
fun parseGpxRoutesOnly(xml: String): Array<Path> =
    GpxParser
        .parse(xml)
        .tracksAsPaths(kinds = setOf(GpxPathKind.ROUTE))
        .toTypedArray()

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
    writeExtensions: Boolean = true,
): String =
    GpxWriter.write(
        paths.toList(),
        waypoints = waypoints.map { it.toGpxWaypoint() },
        writeExtensions = writeExtensions,
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

/**
 * Serialise [path] as a single-track GPX.
 *
 * [writeExtensions] `false` drops `<extensions>` — no power, heart rate, cadence or temperature,
 * and no `gpxtpx` namespace (task g23). `<ele>` and `<time>` are standard GPX and stay. Useful in
 * a browser for a download meant for a platform with a strict schema.
 */
@JsExport
fun writeGpx(
    path: Path,
    writeExtensions: Boolean = true,
): String = GpxWriter.write(path.toGpxDocument(trackName = "virtualized"), writeExtensions = writeExtensions)

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
    writeExtensions: Boolean = true,
): String =
    GpxWriter.write(
        path.toGpxDocument(
            trackName = "virtualized",
            startTime = Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()),
        ),
        writeExtensions = writeExtensions,
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
        maxPedalingLeanAngleDeg =
            maxPedalingLeanAngleDeg ?: EngineConstants.DEFAULT_MAX_PEDALING_LEAN_ANGLE_DEG,
    )
}

/**
 * Convert a JS [WindDto] into a [WindProvider]. The DTO's `windDirection` is in degrees (see
 * [WindDto] for which direction it names) ; we convert to the radians expected by
 * [Wind.directionRad]. `null` → [WindProviderNone].
 */
private fun WindDto?.toWindProvider(): WindProvider {
    if (this == null) return WindProviderNone
    return WindProviderConstant(Wind(speedMS = windSpeed, directionRad = windDirection * PI / 180.0))
}

/**
 * Convert a JS [PowerProviderDto] (or `null` → 250 W constant) into a [CyclistPowerProvider].
 *
 * - `"constant"` → [PowerProviderConstant] (default 250 W if `power` omitted).
 * - `"durability"` → [PowerProviderDurability] (default CP 250 W).
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
        "durability" ->
            PowerProviderDurability(
                powerW = power ?: 250.0,
                criticalPowerW = criticalPower ?: EngineConstants.DEFAULT_CRITICAL_POWER_W,
                useHarmonics = useHarmonics ?: false,
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
 * @return the complete FIT file. On Kotlin/JS a `ByteArray` surfaces as a JS `Int8Array`.
 */
@JsExport
fun pathToFit(
    path: Path,
    name: String,
    startTimeEpochMs: Double,
): ByteArray = path.toFitBytes(name, Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()))

/**
 * Encode **several** paths into one FIT course — one lap and one `TIMER`/`START`…`STOP` event
 * pair per path (task g25, exported here by g29).
 *
 * A separate function rather than a parameter on [pathToFit]: the shape of the first argument
 * changes, and Kotlin/JS has no overloading to lean on.
 *
 * [interPathGapMs] shifts each path after the first by that many milliseconds; `0` (the default)
 * runs them straight on from one another. It is **not** a pause — FIT expresses those with
 * `TIMER`/`PAUSE` events, which this port does not emit.
 */
@JsExport
fun pathsToFit(
    paths: Array<Path>,
    name: String,
    startTimeEpochMs: Double,
    interPathGapMs: Double = 0.0,
): ByteArray =
    paths.toList().toFitBytes(
        name,
        Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()),
        interPathGap = interPathGapMs.toLong().milliseconds,
    )

// ── Worst-case wind (task g31) ───────────────────────────────────────────────────────────────

/**
 * Azimuth in degrees of the constant wind that makes [path] hardest on average — the opposite of
 * the course's dominant orientation (task g26, exported by g31).
 *
 * The value goes **straight into** a `WindDto.directionDeg` or `Wind(speedMS, directionRad)`: no
 * 180° flip, no unit conversion beyond degrees → radians. The engine's angle convention is not the
 * meteorological one it claims to be, which is why this is exposed as a ready-to-use number rather
 * than as a vector the caller would have to interpret.
 *
 * This function says nothing about wind **speed** — only about the direction that hurts most.
 *
 * @return `NaN` when the question has no answer: fewer than 4 points, or a course whose mean
 *   direction cancels out (a perfectly symmetric loop). `0` is a valid answer, so check with
 *   `Number.isNaN`.
 */
@JsExport
fun dominantHeadwindAzimuth(path: Path): Double = path.dominantHeadwindAzimuthDeg()

/** Multi-path form of [dominantHeadwindAzimuth] — the tracks of one file weigh equally. */
@JsExport
fun dominantHeadwindAzimuthOfTracks(paths: Array<Path>): Double = paths.toList().dominantHeadwindAzimuthDeg()

// ── Climb detection (task g12) ───────────────────────────────────────────────────────────────

/**
 * A homogeneous-grade segment of a climb. Distances are absolute along the path, in meters;
 * [grade] is dimensionless (`0.08` = 8 %), matching [ClimbDto.averageGrade].
 */
external interface ClimbPartDto {
    val startDistanceM: Double
    val endDistanceM: Double
    val startElevationM: Double
    val endElevationM: Double
    val lengthM: Double
    val elevationGainM: Double
    val grade: Double
}

/** A detected climb, flattened for the JS boundary. */
external interface ClimbDto {
    val startIndex: Int
    val endIndex: Int
    val startDistanceM: Double
    val endDistanceM: Double
    val startElevationM: Double
    val endElevationM: Double
    val lengthM: Double
    val elevationGainM: Double

    /** Dimensionless: `0.08` = 8 %. */
    val averageGrade: Double

    /** Dimensionless, counting only the rising sections. */
    val climbingGrade: Double
    val positiveElevationM: Double
    val negativeElevationM: Double
    val parts: Array<ClimbPartDto>
}

private fun ClimbPart.toDto(): ClimbPartDto {
    val o = js("({})")
    o.startDistanceM = startDistanceM
    o.endDistanceM = endDistanceM
    o.startElevationM = startElevationM
    o.endElevationM = endElevationM
    o.lengthM = lengthM
    o.elevationGainM = elevationGainM
    o.grade = grade
    return o.unsafeCast<ClimbPartDto>()
}

private fun Climb.toDto(): ClimbDto {
    val o = js("({})")
    o.startIndex = startIndex
    o.endIndex = endIndex
    o.startDistanceM = startDistanceM
    o.endDistanceM = endDistanceM
    o.startElevationM = startElevationM
    o.endElevationM = endElevationM
    o.lengthM = lengthM
    o.elevationGainM = elevationGainM
    o.averageGrade = averageGrade
    o.climbingGrade = climbingGrade
    o.positiveElevationM = positiveElevationM
    o.negativeElevationM = negativeElevationM
    o.parts = parts.map { it.toDto() }.toTypedArray()
    return o.unsafeCast<ClimbDto>()
}

/** Detect climbs on [path] with the default options (those of gpx2web's `getClimbs`). */
@JsExport
fun detectClimbs(path: Path): Array<ClimbDto> = ClimbDetector.detect(path).map { it.toDto() }.toTypedArray()

/**
 * Detect climbs with explicit parameters. Flat scalars rather than an options DTO to keep the
 * JS boundary simple.
 */
@JsExport
fun detectClimbsWithOptions(
    path: Path,
    minMinClimbElevationM: Double,
    maxMinClimbElevationM: Double,
    minClimbElevationRatio: Double,
    minGradePercent: Double,
    maxDiffRealGrade: Double,
    booster: Double,
): Array<ClimbDto> =
    ClimbDetector
        .detect(
            path,
            ClimbOptions(
                minMinClimbElevationM = minMinClimbElevationM,
                maxMinClimbElevationM = maxMinClimbElevationM,
                minClimbElevationRatio = minClimbElevationRatio,
                minGradePercent = minGradePercent,
                maxDiffRealGradeRatio = maxDiffRealGrade,
                booster = booster,
            ),
        ).map { it.toDto() }
        .toTypedArray()
