@file:JvmName("GpxFromPathJvm")

package io.github.glandais.engine.gpx

import io.github.glandais.engine.path.Path
import kotlin.time.Instant

/**
 * Java-callable form of the `Path` → GPX conversions (task g27). See `GpxWriterJvm` for the
 * rationale.
 *
 * These are Kotlin extension functions, so Java already saw them as statics taking the receiver
 * first; what it could not do is omit `type`, `startTime` or `trackNames`.
 */
@JvmOverloads
fun toGpxTrack(
    path: Path,
    name: String? = null,
    type: String? = "cycling",
    startTime: Instant? = null,
    powerSource: GpxPowerSource = GpxPowerSource.INPUT,
): GpxTrack = path.toGpxTrack(name, type, startTime, powerSource)

@JvmOverloads
fun toGpxDocument(
    path: Path,
    name: String = "noname",
    trackName: String? = null,
    startTime: Instant? = null,
    powerSource: GpxPowerSource = GpxPowerSource.INPUT,
): GpxDocument = path.toGpxDocument(name, trackName, startTime, powerSource)

/**
 * Multi-path form. Named `toGpxDocument`, not `pathsToGpxDocument`: a same-package facade with
 * the **same name and signature** as the common function shadows it for every Kotlin caller
 * compiled against this source set — and calls itself. That is not a hypothetical; the first
 * version of this file did exactly that, and `GpxWriterTest` case 24 died on a
 * `StackOverflowError`. Overloading on the receiver type instead keeps both reachable.
 */
@JvmOverloads
fun toGpxDocument(
    paths: List<Path>,
    name: String = "noname",
    trackNames: List<String>? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
    startTime: Instant? = null,
    powerSource: GpxPowerSource = GpxPowerSource.INPUT,
): GpxDocument =
    io.github.glandais.engine.gpx.pathsToGpxDocument(
        paths,
        name = name,
        trackNames = trackNames,
        waypoints = waypoints,
        startTime = startTime,
        powerSource = powerSource,
    )
