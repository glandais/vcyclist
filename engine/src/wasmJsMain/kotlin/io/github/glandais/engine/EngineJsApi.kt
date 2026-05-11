@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise

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
