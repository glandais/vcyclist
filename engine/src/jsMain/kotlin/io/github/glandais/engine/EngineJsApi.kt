@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
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
