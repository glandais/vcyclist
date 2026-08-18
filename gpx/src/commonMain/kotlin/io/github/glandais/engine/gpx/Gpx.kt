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
    /**
     * The document's `<wpt>` elements, in document order. Waypoints are points of interest
     * (feed zone, summit, start) independent of any track — they live on the document, not on a
     * [GpxTrack], and never enter a [io.github.glandais.engine.path.Path] (see [GpxWaypoint]).
     */
    val waypoints: List<GpxWaypoint> = emptyList(),
)

/**
 * A `<wpt>` GPX element. A point of interest independent of the track : it does not enter the
 * [io.github.glandais.engine.path.Path] and is therefore untouched by resampling, simplification
 * or elevation correction (`fixElevation` is deliberately **not** applied to waypoints — their
 * altitude is often an intentional manual entry, e.g. a summit sign's official height).
 */
data class GpxWaypoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double? = null,
    val name: String? = null,
    val description: String? = null,
    /** Value of `<sym>` — icon suggested by the recording device. */
    val symbol: String? = null,
    /** Value of `<type>`. */
    val type: String? = null,
    /** Epoch milliseconds, `null` if `<time>` is absent or unreadable. */
    val timeEpochMs: Long? = null,
)

/**
 * Which GPX container a [GpxTrack] came from — or should be written to.
 *
 * GPX 1.1 has two ordered point containers: `<trk>` (a recorded track, split into `<trkseg>`)
 * and `<rte>` (a planned route, a flat list of `<rtept>`). vcyclist models both as [GpxTrack]
 * and keeps the distinction here, so a parse → write round-trip gives back the container the
 * file actually used.
 *
 * Both are written **in document order**, interleaved if that is how they were read. The GPX 1.1
 * sequence nominally wants every `<rte>` before every `<trk>`, but round-tripping a file
 * unchanged is worth more than that clause: reordering would silently rewrite the user's file,
 * and documents mixing the two containers are rare. Waypoints are still written first, since
 * putting them after the tracks is the ordering strict parsers actually reject in practice.
 */
enum class GpxPathKind {
    /** `<trk>` / `<trkseg>` / `<trkpt>` — a recorded track. */
    TRACK,

    /** `<rte>` / `<rtept>` — a planned route: no segments, usually no timestamps. */
    ROUTE,
}

/**
 * A `<trk>` element and its ordered list of `<trkseg>` segments — **or** a `<rte>`, in which
 * case [kind] is [GpxPathKind.ROUTE] and there is exactly one segment (routes have no segment
 * concept). The class is not renamed for the route case: it would break source compatibility
 * for every existing caller, for a cosmetic gain.
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
    /**
     * Which container this came from. **Last parameter on purpose**: existing positional calls
     * `GpxTrack(name, type, segments)` keep compiling, and the default preserves the pre-g24
     * behaviour of every caller that does not care.
     */
    val kind: GpxPathKind = GpxPathKind.TRACK,
    /**
     * Track-level default road width in metres, from `<trk><extensions><roadwidth>`. Applies to
     * every point that does not carry its own. Last parameter, like [kind], so existing
     * positional calls keep compiling.
     */
    val roadWidthM: Double? = null,
    /**
     * OSM `highway` classification, when a router stamped one. Used as a width proxy where no
     * explicit width is given — see `OsmHighway`.
     */
    val highway: String? = null,
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
            kind: GpxPathKind = GpxPathKind.TRACK,
        ): GpxTrack = GpxTrack(name = name, type = type, segments = listOf(GpxSegment(points)), kind = kind)
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
    /**
     * Rideable road width in metres, from a `<roadwidth>` extension. Appended last so existing
     * positional constructions keep compiling.
     */
    val roadWidthM: Double? = null,
    /**
     * OSM `highway` classification, when a router stamped one. Used as a width proxy where no
     * explicit width is given — see `OsmHighway`.
     */
    val highway: String? = null,
)
