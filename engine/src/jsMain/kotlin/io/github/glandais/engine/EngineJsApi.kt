@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
)

package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.engine.climb.Climb
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.climb.ClimbPart
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxPathKind
import io.github.glandais.engine.gpx.GpxPowerSource
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.pathsToGpxDocument
import io.github.glandais.engine.gpx.segmentsAsPaths
import io.github.glandais.engine.gpx.toGpxDocument
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.engine.path.ElevationGainOptions
import io.github.glandais.engine.path.ElevationGainPreset
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.path.dominantHeadwindAzimuthDeg
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.CyclistPowerProvider
import io.github.glandais.engine.physics.CyclistPowerSpec
import io.github.glandais.engine.physics.PowerModel
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.Wind
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderConstant
import io.github.glandais.engine.physics.WindProviderNone
import io.github.glandais.engine.trajectory.CorridorMode
import io.github.glandais.engine.trajectory.CurvatureOptions
import io.github.glandais.engine.trajectory.RacingLine
import io.github.glandais.engine.trajectory.RacingLineOptions
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

    /**
     * W′ balance annotation pass (ledger R15). Defaults to `true`, which is what JS callers have
     * been getting all along — [WPrimeBalanceOptions] is enabled by default and nothing here ever
     * overrode it, so the `wPrimeBalance` field was already being written, at CP 250 W / W′ 20 kJ.
     * These three fields make it *calibrable*, they do not turn it on.
     */
    val wPrimeBalanceEnabled: Boolean?

    /**
     * CP (W) for the W′ balance **field**.
     *
     * This is **not** automatically [PowerProviderDto.criticalPower]. The two are independent by
     * design (see [WPrimeBalanceOptions] for why CP/W′ live on the options rather than on
     * [Cyclist]), so passing different values here and on the power provider yields a W′bal trace
     * that does not describe the rider being simulated. Legitimate — reporting one rider's effort
     * against another's physiology — but rarely intended.
     */
    val wPrimeBalanceCriticalPower: Double?

    /** W′ (J) for the W′ balance field. Same independence caveat as [wPrimeBalanceCriticalPower]. */
    val wPrimeBalanceWPrime: Double?

    /**
     * Curvature estimation. Defaults to on — it corrects `radius` and `speedMax`, so turning it
     * off restores the historical windowed estimate rather than saving work worth saving.
     */
    val curvatureEnabled: Boolean?

    /**
     * Optimal-trajectory ("racing line") stage. Off by default: enabling it **moves every
     * coordinate** of the result. The original positions are preserved in `sourceLatitude` /
     * `sourceLongitude`.
     */
    val racingLineEnabled: Boolean?

    /** `"lane"` (default), `"lane-left"`, or `"full-road"` — closed roads and time trials only. */
    val racingLineCorridor: String?

    /** Road width assumed where the GPX supplies none, in metres. */
    val racingLineRoadWidthM: Double?

    /**
     * Scale the reported climbing is measured at: `"raw"`, `"barometric"`, `"dem"`, `"gps"`.
     * See `docs/guides/elevation.md`.
     */
    val elevationGainPreset: String?

    /** Override the preset's hysteresis dead band, in metres. `0` disables it. */
    val elevationGainThresholdM: Double?

    /** Whether to measure the dead-banded climbing at all. Defaults to `true`. */
    val elevationGainEnabled: Boolean?

    /**
     * Triangular-kernel half-width for the elevation smoother, in metres.
     *
     * The single largest determinant of both the reported climbing and the gradients the
     * simulation rides — it costs a clean route ~1.5 % and a noisy GPS trace ~48 %.
     */
    val elevationSmoothWindowM: Double?

    /**
     * Web-Mercator zoom for the DEM lookup, `0..15`, used only when [fixElevation] is on.
     *
     * Not part of [EnhanceOptions] — it configures the provider, not the pipeline — so it is read
     * where the provider is built rather than in `toEnhanceOptions`.
     */
    val demZoom: Int?
}

/**
 * JS-side mirror of [Cyclist]. All fields required when the DTO is passed (Kotlin/JS does not
 * gracefully tolerate optional fields on `external interface` without `?`). Callers can pass
 * `null` for the whole DTO to fall back to engine defaults (80 kg / 0.7 Cd / 0.5 m² / 35° / 100
 * km/h / 0.4 g brake).
 *
 * Passing a key this interface does not declare is an **error** since task 43 — see
 * [requireOnlyKeys].
 */
external interface CyclistDto {
    val massKg: Double
    val cd: Double
    val frontalAreaM2: Double
    val maxLeanAngleDeg: Double
    val maxBrakeG: Double
    val maxSpeedKmH: Double

    /**
     * Road surface preset (ledger R9) : `"dry"` (the default) or `"wet"`, case-insensitive. Sets
     * cornering grip and braking together — wet cuts cornering speed by 1.58× and braking to
     * 0.23 g. Any other value throws.
     *
     * **The preset wins over [maxLeanAngleDeg] and [maxBrakeG] when present**, which is the
     * opposite of the CLI, where an explicit `--cyclist-max-angle` overrides `--road-condition`.
     * The CLI can tell "not given" from "given" because its fields are nullable ; here they are
     * not, so a JS caller always supplies all six and "explicit wins" would make the preset
     * unreachable. Omit `roadCondition` to keep the raw values — that is the pre-R9 behaviour, and
     * `"dry"` reproduces the shipped defaults bit-for-bit anyway.
     */
    val roadCondition: String?
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
 * [type] takes any [PowerModel] wire name — `"constant"`, `"durability"`, `"critical-power"` or
 * `"from_data"` — and the catalog is the single source of that list, so a model added to the
 * engine is reachable here without editing this file.
 *
 * [pacing] and [maxSlewWPerS] are **decorators**, not types : they compose over whichever [type]
 * was chosen, in the order [CyclistPowerSpec.toProvider] defines.
 */
external interface PowerProviderDto {
    val type: String
    val power: Double?
    val useHarmonics: Boolean?

    /** CP (W) for `"durability"` and `"critical-power"`. Defaults to 250 W. */
    val criticalPower: Double?

    /** W′ (J) for `"critical-power"` — the reserve spendable above CP. Defaults to 20 kJ. */
    val wPrime: Double?

    /**
     * Terrain pacing (ledger R19) : harder uphill and into headwind, easier downhill, with a causal
     * energy account so the effort is redistributed rather than added. Off by default.
     *
     * A heuristic with **no lookahead** — the rider reacts to the road it is on. Its magnitudes
     * (gradient gain, the ~300 m ramp, the energy-budget window) are the project's own rather than
     * sourced, so they are not exposed here ; use [PowerProviderTerrainPacing] directly from Kotlin
     * to change them.
     */
    val pacing: Boolean?

    /**
     * Power slew-rate limit (ledger R18) in W/s ; `0` or omitted disables it. 50 is the value
     * Zignoli & Biral use. Same semantics as the CLI's `--cyclist-slew`.
     *
     * Applied **outermost**, so it smooths whatever [type] and [pacing] produced.
     */
    val maxSlewWPerS: Double?
}

/**
 * Catalog entry for a [PointField]. Exposed to JS
 * via [fieldDefinitions] so consumers can render generic field-pickers without hard-coding
 * the 43-entry list.
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
 *
 * [trackNames] names the tracks positionally; a shorter list — or `null` — leaves the remaining
 * tracks unnamed, which is `pathsToGpxDocument`'s own contract and **not** the
 * [DEFAULT_TRACK_NAME] that [writeGpx] applies to its single track. Extra names are ignored.
 *
 * [startTimeEpochMs] is what [writeGpxAt] does, applied to every track at once: `<time>` =
 * `startTimeEpochMs + time(i)`. `null` (the default) writes the times as they stand. It is a
 * `Double` for the reason [writeGpxAt] gives.
 *
 * Both arrived late — the WASI door had accepted `trackName` and `startTimeEpochMs` since w09 and
 * this façade had no way to say either, so a multi-track export was the one GPX a JS caller could
 * not date or name.
 */
@JsExport
fun writeGpxTracks(
    paths: Array<Path>,
    waypoints: Array<WaypointDto> = emptyArray(),
    writeExtensions: Boolean = true,
    powerSource: String? = null,
    trackNames: Array<String>? = null,
    startTimeEpochMs: Double? = null,
): String =
    GpxWriter.write(
        pathsToGpxDocument(
            paths.toList(),
            trackNames = trackNames?.toList(),
            waypoints = waypoints.map { it.toGpxWaypoint() },
            startTime = startTimeEpochMs?.let { Instant.fromEpochMilliseconds(it.toLong()) },
            powerSource = powerSource.toGpxPowerSource(),
        ),
        writeExtensions = writeExtensions,
    )

/** The `<trk><name>` the GPX writers use when the caller names none. */
private const val DEFAULT_TRACK_NAME = "virtualized"

/**
 * Parse the wire spelling of [GpxPowerSource], rejecting an unknown one rather than silently
 * falling back — the same contract `requireOnlyKeys` gives the option DTOs, and the reason a
 * typo here is a thrown error instead of a GPX quietly missing its power.
 */
private fun String?.toGpxPowerSource(): GpxPowerSource {
    if (this == null) return GpxPowerSource.DEFAULT
    return GpxPowerSource.fromWire(this)
        ?: throw IllegalArgumentException(
            "powerSource must be one of ${GpxPowerSource.wireNames.joinToString(", ")}, got: $this",
        )
}

/**
 * The one input DTO that had no key check: a caller hand-building a waypoint could misspell `sym`
 * as `symbol`'s neighbour and silently write a `<wpt>` without it.
 */
private val WAYPOINT_KEYS =
    setOf("latitudeDeg", "longitudeDeg", "elevationM", "name", "description", "symbol", "type", "timeEpochMs")

private fun WaypointDto.toGpxWaypoint(): io.github.glandais.engine.gpx.GpxWaypoint {
    requireOnlyKeys("WaypointDto", WAYPOINT_KEYS)
    return io.github.glandais.engine.gpx.GpxWaypoint(
        latitudeDeg = latitudeDeg,
        longitudeDeg = longitudeDeg,
        elevationM = elevationM,
        name = name,
        description = description,
        symbol = symbol,
        type = type,
        timeEpochMs = timeEpochMs?.toLong(),
    )
}

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

/**
 * Cumulative ascent with the dead band applied, or `NaN` if the stage did not run.
 *
 * `NaN` rather than `null` so the export stays a plain `Double` on the JS side; use
 * [pathReportedElevationGain] to get the raw sum as a fallback instead.
 */
@JsExport
fun pathElevationGainFiltered(path: Path): Double = path.elevationGainFiltered

/** Counterpart of [pathElevationGainFiltered]. NEGATIVE by convention, or `NaN`. */
@JsExport
fun pathElevationLossFiltered(path: Path): Double = path.elevationLossFiltered

/** The figure to show a human: dead-banded when measured, the raw sum otherwise. */
@JsExport
fun pathReportedElevationGain(path: Path): Double = path.reportedElevationGain

/** Counterpart of [pathReportedElevationGain]. NEGATIVE by convention. */
@JsExport
fun pathReportedElevationLoss(path: Path): Double = path.reportedElevationLoss

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
 *
 * [powerSource] selects which of the path's two power fields lands in `<power>`: `"input"`,
 * `"computed"` or `"computed-or-input"` — the CLI's `--gpx-power-source` values, parsed through
 * the one catalogue in [GpxPowerSource]. `null` keeps the default, which writes the *input*
 * power: emitting simulated data into a format the ecosystem reads as a recording is the caller's
 * decision, not a default's.
 *
 * [trackName] names the `<trk><name>`; `null` keeps `"virtualized"`.
 */
@JsExport
fun writeGpx(
    path: Path,
    writeExtensions: Boolean = true,
    powerSource: String? = null,
    trackName: String? = null,
): String =
    GpxWriter.write(
        path.toGpxDocument(
            trackName = trackName ?: DEFAULT_TRACK_NAME,
            powerSource = powerSource.toGpxPowerSource(),
        ),
        writeExtensions = writeExtensions,
    )

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
    powerSource: String? = null,
    trackName: String? = null,
): String =
    GpxWriter.write(
        path.toGpxDocument(
            trackName = trackName ?: DEFAULT_TRACK_NAME,
            startTime = Instant.fromEpochMilliseconds(startTimeEpochMs.toLong()),
            powerSource = powerSource.toGpxPowerSource(),
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
        val provider = if (opts.fixElevation) elevationProviderFor(options) else null
        Enhancer.enhanceCourseDefault(path, elevationProvider = provider, options = opts)
    }

/**
 * **Every fallback here is read off [defaultJsOptions] or off the stage's own options object.**
 * Not one is written out.
 *
 * There are two default sites on this façade, and they used to disagree by construction: this
 * function spelled its fallbacks out, and `defaultJsOptions()` spelled the same values out again
 * for the `enhance(path, null)` path. A default changed in one did not apply to the other, and
 * `simplifyToleranceM` / `simplifyZExaggeration` / `curvatureEnabled` were `10.0` / `3.0` / `true`
 * here while `SimplifyPathOptions` and `CurvatureOptions.DEFAULT` held the real values — in a file
 * whose own KDoc forbids exactly that, and which is how the façades once defended 250 W against
 * the CLI's 280 W. `DoorDefaultsTest` now fails a reader that spells a default.
 */
internal fun EnhanceOptionsDto?.toEnhanceOptions(): EnhanceOptions {
    val d = defaultJsOptions()
    if (this == null) return d
    requireOnlyKeys("EnhanceOptionsDto", ENHANCE_OPTIONS_KEYS)
    return EnhanceOptions(
        fixElevation = fixElevation ?: d.fixElevation,
        computeMaxSpeeds = computeMaxSpeeds ?: d.computeMaxSpeeds,
        virtualizeTrack = virtualizeTrack ?: d.virtualizeTrack,
        computeOnePointPerSecond = computeOnePointPerSecond ?: d.computeOnePointPerSecond,
        simplifyPath =
            SimplifyPathOptions(
                enabled = simplifyEnabled ?: d.simplifyPath.enabled,
                toleranceM = simplifyToleranceM ?: d.simplifyPath.toleranceM,
                zExaggeration = simplifyZExaggeration ?: d.simplifyPath.zExaggeration,
            ),
        wPrimeBalance =
            WPrimeBalanceOptions(
                enabled = wPrimeBalanceEnabled ?: d.wPrimeBalance.enabled,
                criticalPowerW = wPrimeBalanceCriticalPower ?: d.wPrimeBalance.criticalPowerW,
                wPrimeJ = wPrimeBalanceWPrime ?: d.wPrimeBalance.wPrimeJ,
            ),
        curvature = CurvatureOptions(enabled = curvatureEnabled ?: d.curvature.enabled),
        racingLine =
            RacingLineOptions(
                enabled = racingLineEnabled ?: d.racingLine.enabled,
                corridor = parseCorridor(racingLineCorridor),
                defaultRoadWidthM = racingLineRoadWidthM ?: d.racingLine.defaultRoadWidthM,
            ),
        elevationGain =
            run {
                // Naming a preset takes ITS dead band, so `{ elevationGainPreset: 'gps' }` alone
                // gives 10 m and not `dem`'s 3 m. Naming none keeps the default object's band,
                // which is not the same as its preset's.
                val named = elevationGainPreset?.let { ElevationGainPreset.byId(it) }
                ElevationGainOptions(
                    enabled = elevationGainEnabled ?: d.elevationGain.enabled,
                    preset = named ?: d.elevationGain.preset,
                    thresholdM = elevationGainThresholdM ?: named?.thresholdM ?: d.elevationGain.thresholdM,
                )
            },
        elevationSmoothWindowM = elevationSmoothWindowM ?: d.elevationSmoothWindowM,
    )
}

/** The DEM provider the façade auto-instantiates, honouring `demZoom` when the caller set one. */
private fun elevationProviderFor(options: EnhanceOptionsDto?): ElevationProvider {
    val zoom = options?.demZoom ?: return ElevationProvider()
    return ElevationProvider(ElevationProviderConfig(zoomLevel = zoom))
}

/** Corridor mode by name, defaulting to the engine's own default rather than restating it. */
private fun parseCorridor(name: String?): CorridorMode = if (name == null) RacingLineOptions.DEFAULT.corridor else CorridorMode.byId(name)

/**
 * Safe defaults for browser/Node calls : skip elevation fetch (callers opt-in via
 * `fixElevation: true` which triggers a default [ElevationProvider] inside [enhance]), skip 1 Hz
 * resample (the 2024-stamped sample fixtures blow up `PointPerSecond` — see task 27 notes) and
 * skip simplify so smoke results stay deterministic.
 *
 * **This is the JS door's single default site**, and [toEnhanceOptions] reads every fallback off
 * it. The four booleans below are the only values this façade decides for itself — they differ
 * from the engine's on purpose, for the reasons above. Everything nested comes from the stage's
 * own options object, so a tolerance changed in `SimplifyPathOptions` moves this door with it.
 */
private fun defaultJsOptions(): EnhanceOptions =
    EnhanceOptions(
        fixElevation = false,
        computeMaxSpeeds = true,
        virtualizeTrack = true,
        computeOnePointPerSecond = false,
        simplifyPath = SimplifyPathOptions(enabled = false),
        curvature = CurvatureOptions(),
        racingLine = RacingLineOptions(),
    )

// ── Expanded JS API (task 34) ────────────────────────────────────────────────────────────────
//
// The `enhanceWithCourse` façade lets a JS caller build a full [CoursePhysics] (cyclist + bike
// + wind + power provider) from JSON-like DTO inputs and run the enhancement pipeline.
// `getField` and `fieldDefinitions` expose
// generic per-field access for the demo's UI which needs to plot any of the 43 fields.

/**
 * Convert a JS [CyclistDto] (or `null` → defaults) into a [Cyclist]. Note : the [Cyclist]
 * data class no longer carries a power field — the power strategy is supplied separately via
 * [PowerProviderDto] / [toCyclistPowerProvider].
 */
private fun CyclistDto?.toCyclist(): Cyclist {
    if (this == null) return Cyclist()
    requireOnlyKeys("CyclistDto", CYCLIST_KEYS)
    // The preset is the last word, and that rule now lives once, in `commonMain` — see `applyTo`.
    // This door's behaviour does not change; the CLI's did, to match it.
    return roadCondition?.toRoadCondition().applyTo(
        Cyclist(
            massKg = massKg,
            maxBrakeG = maxBrakeG,
            cd = cd,
            frontalAreaM2 = frontalAreaM2,
            maxLeanAngleDeg = maxLeanAngleDeg,
            maxSpeedKmH = maxSpeedKmH,
        ),
    )
}

/**
 * Parse the [CyclistDto.roadCondition] string. Case-insensitive, like the CLI's converter — `"wet"`
 * is what anyone types. The enum itself is not exported : a Kotlin enum crossing to JavaScript
 * arrives in a shape nobody wants to import just to say "it is raining" (the rule g29 settled on
 * for `GpxPathKind`).
 */
private fun String.toRoadCondition(): RoadCondition =
    RoadCondition.fromWire(this)
        ?: error("Unknown CyclistDto.roadCondition: $this (expected one of ${RoadCondition.wireNames})")

/** Convert a JS [BikeDto] (or `null` → defaults) into a [Bike]. */
private fun BikeDto?.toBike(): Bike {
    if (this == null) return Bike()
    requireOnlyKeys("BikeDto", BIKE_KEYS)
    return Bike(
        crr = crr,
        inertiaFront = inertiaFront,
        inertiaRear = inertiaRear,
        wheelRadiusM = wheelRadiusM,
        efficiency = efficiency,
        maxPedalingLeanAngleDeg = maxPedalingLeanAngleDeg ?: Bike().maxPedalingLeanAngleDeg,
    )
}

/**
 * Convert a JS [WindDto] into a [WindProvider]. The DTO's `windDirection` is in degrees (see
 * [WindDto] for which direction it names) ; we convert to the radians expected by
 * [Wind.directionRad]. `null` → [WindProviderNone].
 */
private fun WindDto?.toWindProvider(): WindProvider {
    if (this == null) return WindProviderNone
    requireOnlyKeys("WindDto", WIND_KEYS)
    return WindProviderConstant(Wind(speedMS = windSpeed, directionRad = windDirection * PI / 180.0))
}

/**
 * Convert a JS [PowerProviderDto] (or `null` → the [CyclistPowerSpec] defaults) into a provider.
 *
 * Every default now comes from [CyclistPowerSpec], i.e. from `EngineConstants`. Until task 43 this
 * façade hardcoded **250 W** where the CLI used `DEFAULT_CYCLIST_POWER_W` = **280 W**, so the same
 * "unconfigured rider" rode at two different powers depending on which surface you came through.
 */
private fun PowerProviderDto?.toCyclistPowerProvider(): CyclistPowerProvider = toPowerSpec().toProvider()

/**
 * Parse the DTO into the engine's own [CyclistPowerSpec]. The model → provider mapping and the
 * `base → pacing → slew` order live there, shared with the CLI and the WASI façade — see its KDoc
 * for why they are no longer written out per surface.
 */
private fun PowerProviderDto?.toPowerSpec(): CyclistPowerSpec {
    if (this == null) return CyclistPowerSpec()
    requireOnlyKeys("PowerProviderDto", POWER_PROVIDER_KEYS)
    val defaults = CyclistPowerSpec()
    return CyclistPowerSpec(
        model =
            PowerModel.fromIdOrNull(type)
                ?: error("Unknown PowerProviderDto.type: $type (expected one of ${PowerModel.ids})"),
        powerW = power ?: defaults.powerW,
        criticalPowerW = criticalPower ?: defaults.criticalPowerW,
        wPrimeJ = wPrime ?: defaults.wPrimeJ,
        useHarmonics = useHarmonics ?: defaults.useHarmonics,
        pacing = pacing ?: defaults.pacing,
        maxSlewWPerS = maxSlewWPerS ?: defaults.maxSlewWPerS,
    )
}

private val POWER_PROVIDER_KEYS =
    setOf("type", "power", "useHarmonics", "criticalPower", "wPrime", "pacing", "maxSlewWPerS")

private val CYCLIST_KEYS =
    setOf("massKg", "cd", "frontalAreaM2", "maxLeanAngleDeg", "maxBrakeG", "maxSpeedKmH", "roadCondition")

private val BIKE_KEYS =
    setOf("crr", "inertiaFront", "inertiaRear", "wheelRadiusM", "efficiency", "maxPedalingLeanAngleDeg")

private val WIND_KEYS = setOf("windSpeed", "windDirection")

private val ENHANCE_OPTIONS_KEYS =
    setOf(
        "fixElevation",
        "computeMaxSpeeds",
        "virtualizeTrack",
        "computeOnePointPerSecond",
        "simplifyEnabled",
        "simplifyToleranceM",
        "simplifyZExaggeration",
        "wPrimeBalanceEnabled",
        "wPrimeBalanceCriticalPower",
        "wPrimeBalanceWPrime",
        // Added by the merge, not by the branch that introduced the fields: this list arrived with
        // task 43, after feat/racing-line forked, so its four keys had nothing to update and the
        // two sides merged cleanly into a facade that rejected its own options. See task 44 — the
        // WASI equivalent conflicted loudly instead, only because it sits next to what it guards.
        "curvatureEnabled",
        "racingLineEnabled",
        "racingLineCorridor",
        "racingLineRoadWidthM",
        "elevationGainPreset",
        "elevationGainThresholdM",
        "elevationGainEnabled",
        "elevationSmoothWindowM",
        "demZoom",
    )

/**
 * Reject a DTO carrying a key this façade does not read (task 43).
 *
 * An `external interface` ignores unknown properties in silence, which is how the demo spent nine
 * ledger entries sending `tiringDuration` — a field renamed by R17 — and getting the default
 * instead of an error. The WASI façade has always been strict (`requireOnly`); this brings JS to
 * the same footing, so a stale caller is told rather than quietly misread.
 *
 * A typo is therefore now a hard error where it used to be a silently ignored setting. That is the
 * intended trade: the failure it prevents is invisible, and this one is not.
 */
private fun Any.requireOnlyKeys(
    dtoName: String,
    allowed: Set<String>,
) {
    val keys = js("Object.keys")(this).unsafeCast<Array<String>>()
    val unknown = keys.filterNot { it in allowed }
    if (unknown.isNotEmpty()) {
        error(
            "Unknown $dtoName key(s): ${unknown.joinToString()} — expected one of " +
                allowed.sorted().joinToString(),
        )
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
        val provider = if (opts.fixElevation) elevationProviderFor(options) else null
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
 * Enumerate the 43-entry [PointField] catalog as JS-friendly [FieldDefinitionDto] objects.
 * UIs use this to render generic field-pickers without hard-coding the list.
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
 *
 * [decimals] and [lineSeparator] arrived in S4 of the surface-alignment work. The WASI door had
 * accepted `decimals` since w09 while this signature had three parameters, so the same core writer
 * was configurable from one wire door and not the other. `null` on either keeps the [CsvOptions]
 * default rather than a literal restated here.
 */
@JsExport
fun pathToCsv(
    path: Path,
    separator: String,
    unitsInHeader: Boolean,
    decimals: Int? = null,
    lineSeparator: String? = null,
): String {
    val defaults = CsvOptions()
    return CsvWriter.write(
        path,
        CsvOptions(
            separator = separator.firstOrNull() ?: defaults.separator,
            unitsInHeader = unitsInHeader,
            decimals = decimals,
            lineSeparator = lineSeparator?.ifEmpty { null } ?: defaults.lineSeparator,
        ),
    )
}

/**
 * Serialise [path] to JSON, column-oriented (one array per [PointField]) — see [JsonWriter] /
 * task g07. Lets a browser demo hand the result straight to `JSON.parse` and feed a chart
 * library (e.g. Chart.js) without a server round-trip.
 *
 * [decimals] rounds every value; [includeMeta] drops the `size` / `fields` preamble when `false`.
 * Both were reachable from the WASI door and not from here until S4 — `null` keeps the
 * [JsonOptions] default, which is where the default lives.
 */
@JsExport
fun pathToJson(
    path: Path,
    pretty: Boolean,
    decimals: Int? = null,
    includeMeta: Boolean? = null,
): String {
    val defaults = JsonOptions()
    return JsonWriter.write(
        path,
        JsonOptions(pretty = pretty, decimals = decimals, includeMeta = includeMeta ?: defaults.includeMeta),
    )
}

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

/** Detect climbs on [path] with the default options — see `ClimbOptions`. */
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
    maxAnalysisPoints: Int? = null,
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
                maxAnalysisPoints = maxAnalysisPoints ?: ClimbOptions().maxAnalysisPoints,
            ),
        ).map { it.toDto() }
        .toTypedArray()

// ── Racing-line inspection ───────────────────────────────────────────────────────────────────

/**
 * One detected bend, as the racing-line analysis sees it.
 *
 * @see analyzeRacingLine
 */
external interface CornerDto {
    val fromIndex: Int
    val untilIndex: Int
    val apexIndex: Int

    /** `"GENTLE"`, `"CORNER"` or `"HAIRPIN"`. */
    val kind: String
    val turnRad: Double

    /** `+1` turning left, `-1` turning right. */
    val direction: Int

    /** 20th percentile of `1/|κ|` over the span — robust to the clothoid ends a mean is biased by. */
    val radiusQ20M: Double
    val radiusMinM: Double
    val lengthM: Double
}

/**
 * What the racing-line stage decided, without applying it.
 *
 * Every array has one entry per path station. `corridorLo`/`corridorHi` bound the lateral offset in
 * metres, positive to the left; `lateralOffsetM` is the line that was solved for inside them.
 */
external interface RacingLineReportDto {
    val size: Int
    val corners: Array<CornerDto>

    /** Curvature of the road itself, m⁻¹, positive turning left. */
    val centerlineCurvature: DoubleArray

    /** Curvature of the solved line, same convention. */
    val trajectoryCurvature: DoubleArray
    val corridorLo: DoubleArray
    val corridorHi: DoubleArray
    val roadHalfWidthM: DoubleArray
    val lateralOffsetM: DoubleArray

    /** Widest the corridor gets anywhere, `hi − lo`, in metres. */
    val maxCorridorWidthM: Double
    val newtonIterations: Int

    /** Final residual relative to the initial gradient — scale-free, so comparable across routes. */
    val relativeGradient: Double
    val converged: Boolean
    val activeConstraints: Int
}

private fun cornerObj(c: io.github.glandais.engine.trajectory.CornerSpan): CornerDto {
    val o = js("({})")
    o.fromIndex = c.fromIndex
    o.untilIndex = c.untilIndex
    o.apexIndex = c.apexIndex
    o.kind = c.kind.name
    o.turnRad = c.turnRad
    o.direction = c.direction
    o.radiusQ20M = c.radiusQ20M
    o.radiusMinM = c.radiusMinM
    o.lengthM = c.lengthM
    return o.unsafeCast<CornerDto>()
}

/**
 * Analyse what the racing line *would* do to [path], without touching it.
 *
 * Read-only by construction — it takes a `Path` and returns numbers, so asking the question can
 * never move a coordinate. That is the point: the stage rewrites every position it is applied to,
 * and being able to inspect the corridor it assumed and the offset it chose is what makes that
 * inspectable rather than merely optional.
 *
 * Returns `null` for a path too short or too degenerate to analyse, matching the Kotlin API's
 * contract of declining rather than guessing.
 *
 * The [options] are the same `racingLine*` keys [enhance] accepts; `enabled` is ignored here, since
 * calling this *is* the request.
 */
@JsExport
fun analyzeRacingLine(
    path: Path,
    options: EnhanceOptionsDto? = null,
): RacingLineReportDto? {
    val racingLine = options.toEnhanceOptions().racingLine
    val report = RacingLine.analyze(path, racingLine) ?: return null
    val o = js("({})")
    o.size = report.size
    o.corners = report.corners.map { cornerObj(it) }.toTypedArray()
    o.centerlineCurvature = report.centerlineCurvature
    o.trajectoryCurvature = report.trajectoryCurvature
    o.corridorLo = report.corridorLo
    o.corridorHi = report.corridorHi
    o.roadHalfWidthM = report.roadHalfWidthM
    o.lateralOffsetM = report.lateralOffsetM
    o.maxCorridorWidthM = report.maxCorridorWidthM
    o.newtonIterations = report.newtonIterations
    o.relativeGradient = report.relativeGradient
    o.converged = report.converged
    o.activeConstraints = report.activeConstraints
    return o.unsafeCast<RacingLineReportDto>()
}
