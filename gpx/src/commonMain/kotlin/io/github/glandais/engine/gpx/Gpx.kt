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

/**
 * A `<trk>` element and its ordered list of `<trkseg>` segments.
 *
 * A segment boundary is a **physical discontinuity** — a pause, a lost fix, a teleport. Keeping
 * segments distinct (rather than flattening them at parse time) lets callers decide whether that
 * discontinuity matters : see [GpxDocument.tracksAsPaths] (concatenates, jump included) versus
 * [GpxDocument.segmentsAsPaths] (one [io.github.glandais.engine.path.Path] per segment, no jump).
 */
data class GpxTrack(
    /** Value of `<trk><name>`, or `null` if absent. */
    val name: String? = null,
    /** Value of `<trk><type>`, or `null` if absent (e.g. "cycling", "running"). */
    val type: String? = null,
    val segments: List<GpxSegment>,
) {
    /**
     * All points of all segments, concatenated in document order. Kept as the pre-g02 accessor so
     * existing callers compile unchanged ; use [segments] when the discontinuities matter.
     */
    val points: List<GpxTrackPoint> by lazy { segments.flatMap { it.points } }

    companion object {
        /**
         * Build a single-segment track. Source-compatible with the pre-g02 constructor
         * `GpxTrack(name = …, type = …, points = …)` — Kotlin resolves the named `points`
         * argument to this factory since the primary constructor takes `segments`.
         */
        operator fun invoke(
            name: String? = null,
            type: String? = null,
            points: List<GpxTrackPoint>,
        ): GpxTrack = GpxTrack(name = name, type = type, segments = listOf(GpxSegment(points)))
    }
}

/** A single `<trkseg>` element. May legitimately be empty (some devices emit `<trkseg/>`). */
data class GpxSegment(
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
