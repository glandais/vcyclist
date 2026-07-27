package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import kotlin.time.Instant

/**
 * Instant of the **first** `<trkpt>` that carries a `<time>` tag, in document order (all tracks,
 * all segments — same order as [GpxTrack.points]). `null` if no track point in the document is
 * timestamped.
 *
 * A parse → enhance → write round-trip loses this information today because [pointsToPath]
 * normalises `time` down to a `Path`-relative clock (`time(0) == 0`, see `VirtualizeService`).
 * Reading it here — from the raw, pre-conversion [GpxDocument] — lets a caller (CLI, JS façade)
 * reuse the source file's own start time as the `startTime` argument of [pathsToGpxDocument] /
 * [GpxWriter.write], instead of losing it or requiring the user to retype it (see task g05).
 */
val GpxDocument.startTime: Instant?
    get() =
        tracks
            .asSequence()
            .flatMap { it.points.asSequence() }
            .firstNotNullOfOrNull { it.timeEpochMs }
            ?.let { Instant.fromEpochMilliseconds(it) }

/**
 * Convert the **first** track of [this] document to a [Path], computing derived data.
 * Throws if the document has no track. Matches the TS `GPXParser.parse(...).tracks[0]` usage.
 *
 * Segments of that track are concatenated — see [GpxTrack.toPath] for what that implies on
 * distance. This is the pre-g02 behaviour, kept as the legitimate shortcut for the common
 * single-track case ; it is **not** deprecated.
 */
fun GpxDocument.firstTrackAsPath(): Path {
    val track = tracks.firstOrNull() ?: error("GpxDocument has no track")
    return track.toPath()
}

/**
 * One [Path] per `<trk>`, in document order. Segments of a same track are **concatenated**
 * (see [GpxTrack.toPath]).
 *
 * Tracks with no point at all are skipped, so the result never contains a parasitic `Path(0)`.
 * The result may therefore be shorter than [GpxDocument.tracks], and may be empty.
 */
fun GpxDocument.tracksAsPaths(): List<Path> =
    tracks
        .filter { it.points.isNotEmpty() }
        .map { it.toPath() }

/**
 * One [Path] per `<trkseg>`, across **all** tracks, in document order. Empty segments are
 * skipped.
 *
 * Use this rather than [tracksAsPaths] when the inter-segment discontinuity matters : each
 * returned [Path] is continuous, so no phantom distance is introduced by the pause/teleport
 * between two segments. This is the shape gpx2web produces natively (one `GPXPath` per
 * `<trkseg>`).
 */
fun GpxDocument.segmentsAsPaths(): List<Path> =
    tracks
        .flatMap { it.segments }
        .filter { it.points.isNotEmpty() }
        .map { it.toPath() }

/**
 * Materialise a [Path] from a [GpxTrack], concatenating its segments.
 *
 * **Distance across a segment boundary jumps.** The points of segment *n+1* follow those of
 * segment *n* with no marker, so [Path.computeDerivedData] measures the straight-line gap
 * between the last point of one segment and the first of the next, and folds it into
 * `totalDistance`. That is deliberate — it preserves the pre-g02 behaviour of
 * [GpxDocument.firstTrackAsPath] — but if the artefact is unacceptable, use
 * [GpxDocument.segmentsAsPaths] instead, which never crosses a boundary.
 *
 * Point-level conversion is described on [GpxSegment.toPath].
 */
fun GpxTrack.toPath(): Path = pointsToPath(points)

/**
 * Materialise a [Path] from a single [GpxSegment]:
 * - latitude/longitude converted to radians (engine internal unit),
 * - elevation defaults to 0.0 if absent,
 * - time defaults to 0L if absent,
 * - heart rate / cadence / temperature / power copied through when present.
 *
 * [Path.computeDerivedData] is invoked before returning so downstream consumers can
 * immediately read `totalDistance`, `bearing`, `speed`, etc.
 */
fun GpxSegment.toPath(): Path = pointsToPath(points)

private fun pointsToPath(points: List<GpxTrackPoint>): Path {
    val path = Path(points.size)
    for ((i, p) in points.withIndex()) {
        path.setLatitude(i, p.latitudeDeg * MathConstants.DEG_TO_RAD)
        path.setLongitude(i, p.longitudeDeg * MathConstants.DEG_TO_RAD)
        path.setElevation(i, p.elevationM ?: 0.0)
        path.setTime(i, (p.timeEpochMs ?: 0L).toDouble())
        p.powerW?.let { path.setPInputPower(i, it) }
        p.heartRate?.let { path.setHeartRate(i, it.toDouble()) }
        p.cadence?.let { path.setCadence(i, it.toDouble()) }
        p.temperatureC?.let { path.setTemperature(i, it) }
    }
    path.computeDerivedData()
    return path
}
