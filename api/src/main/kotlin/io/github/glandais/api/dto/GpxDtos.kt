package io.github.glandais.api.dto

import io.github.glandais.engine.gpx.GpxPathKind
import io.github.glandais.engine.gpx.GpxWaypoint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Whole-track statistics of one path. Every `NaN` or infinite figure is `null` — kotlinx refuses
 * `NaN`, and it is the convention of the WASI ABI.
 *
 * @property durationMs `time(last) - time(0)`, in milliseconds
 * @property elevationGain unfiltered sum of positive deltas, scale-dependent
 * @property elevationGainFiltered hysteresis-filtered ascent, `null` until the `elevationGain`
 *   stage of the pipeline has run (never on a parsed GPX)
 * @property reportedElevationGain the filtered figure when present, the raw sum otherwise — the
 *   one to show a human
 * @property bounds bounding box in **degrees**; `null` for an empty path
 */
@Serializable
@SerialName("PathSummary")
data class PathSummaryDto(
    val size: Int,
    val totalDistance: Double,
    val durationMs: Double?,
    val minElevation: Double?,
    val maxElevation: Double?,
    val elevationGain: Double,
    val elevationLoss: Double,
    val elevationGainFiltered: Double?,
    val elevationLossFiltered: Double?,
    val reportedElevationGain: Double,
    val reportedElevationLoss: Double,
    val bounds: BoundsDto?,
)

/** A bounding box, in degrees. */
@Serializable
@SerialName("Bounds")
data class BoundsDto(
    val minLatitude: Double,
    val minLongitude: Double,
    val maxLatitude: Double,
    val maxLongitude: Double,
)

/**
 * One point of a path — the `PointDto` of the JS façade and of the WASI ABI, field for field:
 * degrees, metres, milliseconds since the start, m/s, watts, metres, grade. Absent values are
 * `null`.
 */
@Serializable
@SerialName("Point")
data class PointDto(
    val latitudeDeg: Double?,
    val longitudeDeg: Double?,
    val elevation: Double?,
    val timeMs: Double?,
    val speed: Double?,
    val pComputedPower: Double?,
    val distance: Double?,
    val grade: Double?,
)

/** A `<wpt>` of the document — the `WaypointDto` of the JS façade and of the WASI ABI. */
@Serializable
@SerialName("Waypoint")
data class WaypointDto(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double?,
    val name: String?,
    val description: String?,
    val symbol: String?,
    val type: String?,
    val timeEpochMs: Long?,
) {
    companion object {
        fun of(w: GpxWaypoint): WaypointDto =
            WaypointDto(w.latitudeDeg, w.longitudeDeg, w.elevationM, w.name, w.description, w.symbol, w.type, w.timeEpochMs)
    }
}

/**
 * One selected path of a parsed GPX.
 *
 * @property name the `<trk>` / `<rte>` name, `null` when absent
 * @property kind `track` (`<trk>`) or `route` (`<rte>`)
 * @property points every point, only when `?points=true`
 */
@Serializable
@SerialName("ParsedPath")
data class ParsedPathDto(
    val name: String?,
    val kind: String,
    val summary: PathSummaryDto,
    val points: List<PointDto>?,
)

/**
 * `POST /gpx/parse`.
 *
 * @property name `<metadata><name>`, `noname` when absent
 * @property startTimeEpochMs the first `<time>` of the document, `null` when nothing is timestamped
 * @property paths the selected paths, after `kinds`, `split` and `trackIndex`
 * @property warnings the repairs applied to the XML before it parsed
 */
@Serializable
@SerialName("ParsedGpx")
data class ParsedGpxDto(
    val name: String,
    val startTimeEpochMs: Long?,
    val waypoints: List<WaypointDto>,
    val paths: List<ParsedPathDto>,
    val warnings: List<String>,
)

/** The wire spelling of a [GpxPathKind]: `track` or `route`. */
val GpxPathKind.wire: String get() = name.lowercase()
