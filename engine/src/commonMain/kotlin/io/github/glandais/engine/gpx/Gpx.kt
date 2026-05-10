package io.github.glandais.engine.gpx

/**
 * Raw GPX document, post-parse, pre-conversion.
 *
 * Preserves the file's units verbatim:
 * - latitude / longitude in **degrees** (as found in the GPX `lat`/`lon` attributes)
 * - time in **epoch milliseconds** (`null` if the `<time>` tag is absent or unparseable)
 * - optional fields for everything that isn't always present (elevation, time, extensions).
 *
 * Conversion to the internal [io.github.glandais.engine.path.Path] format
 * (radians, derived stats) happens via the bridge in `GpxToPath.kt`.
 */
data class GpxDocument(
    /** Value of `<metadata><name>`, or `"noname"` if absent. */
    val name: String = "noname",
    val tracks: List<GpxTrack>,
)

data class GpxTrack(
    /** Value of `<trk><name>`, or `null` if absent. */
    val name: String? = null,
    /** Value of `<trk><type>`, or `null` if absent (e.g. "cycling", "running"). */
    val type: String? = null,
    val points: List<GpxTrackPoint>,
)

/**
 * A single `<trkpt>` entry.
 *
 * Required: [latitudeDeg], [longitudeDeg]. Everything else may be absent depending on
 * the source device. Extension fields ([heartRate], [cadence], [temperatureC], [powerW])
 * are populated by matching on **local element name** ignoring the XML namespace prefix.
 */
data class GpxTrackPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double? = null,
    /** Epoch milliseconds. `null` if the `<time>` tag is absent or fails to parse. */
    val timeEpochMs: Long? = null,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    /** Ambient temperature in Celsius. */
    val temperatureC: Double? = null,
    /** Power in watts. */
    val powerW: Double? = null,
)
