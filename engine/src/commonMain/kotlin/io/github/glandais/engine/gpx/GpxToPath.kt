package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path

/**
 * Convert the **first** track of [this] document to a [Path], computing derived data.
 * Throws if the document has no track. Matches the TS `GPXParser.parse(...).tracks[0]` usage.
 */
fun GpxDocument.firstTrackAsPath(): Path {
    val track = tracks.firstOrNull() ?: error("GpxDocument has no track")
    return track.toPath()
}

/**
 * Materialise a [Path] from a [GpxTrack]:
 * - latitude/longitude converted to radians (engine internal unit),
 * - elevation defaults to 0.0 if absent,
 * - time defaults to 0L if absent,
 * - heart rate / cadence / temperature / power copied through when present.
 *
 * [Path.computeDerivedData] is invoked before returning so downstream consumers can
 * immediately read `totalDistance`, `bearing`, `speed`, etc.
 */
fun GpxTrack.toPath(): Path {
    val n = points.size
    val path = Path(n)
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
