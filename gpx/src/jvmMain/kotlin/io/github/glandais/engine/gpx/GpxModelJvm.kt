@file:JvmName("GpxModelJvm")

package io.github.glandais.engine.gpx

/**
 * Java-callable factories for the GPX document model (task g27).
 *
 * Reading a GPX never needs these — [GpxParser] returns fully-built objects. Writing one from
 * scratch does: every type here is a `data class` whose optional fields are Kotlin defaults, so
 * from Java `new GpxTrackPoint(lat, lon)` does not compile and all eight arguments are mandatory,
 * `null` included.
 *
 * Only the types a caller assembles by hand are covered. `GpxSegment` takes a single mandatory
 * list and needs nothing.
 */
@JvmOverloads
fun trackPoint(
    latitudeDeg: Double,
    longitudeDeg: Double,
    elevationM: Double? = null,
    timeEpochMs: Long? = null,
    heartRate: Int? = null,
    cadence: Int? = null,
    temperatureC: Double? = null,
    powerW: Double? = null,
): GpxTrackPoint = GpxTrackPoint(latitudeDeg, longitudeDeg, elevationM, timeEpochMs, heartRate, cadence, temperatureC, powerW)

@JvmOverloads
fun waypoint(
    latitudeDeg: Double,
    longitudeDeg: Double,
    elevationM: Double? = null,
    name: String? = null,
    description: String? = null,
    symbol: String? = null,
    type: String? = null,
    timeEpochMs: Long? = null,
): GpxWaypoint = GpxWaypoint(latitudeDeg, longitudeDeg, elevationM, name, description, symbol, type, timeEpochMs)

/** Single-segment track, the shape a caller building a document by hand almost always wants. */
@JvmOverloads
fun track(
    points: List<GpxTrackPoint>,
    name: String? = null,
    type: String? = null,
    kind: GpxPathKind = GpxPathKind.TRACK,
): GpxTrack = GpxTrack(name = name, type = type, segments = listOf(GpxSegment(points)), kind = kind)

@JvmOverloads
fun document(
    tracks: List<GpxTrack>,
    name: String = "noname",
    waypoints: List<GpxWaypoint> = emptyList(),
): GpxDocument = GpxDocument(name = name, tracks = tracks, waypoints = waypoints)
