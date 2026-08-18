package io.github.glandais.engine.gpx

import io.github.glandais.engine.path.Path
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.newGenericWriter
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.time.Instant

/**
 * KMP-safe GPX writer. Emits XML 1.0 UTF-8 with the standard GPX namespace declared as
 * the default, plus Garmin TrackPointExtension v1 under the `gpxtpx` prefix and `xsi`
 * for the schema location.
 *
 * Extensions are written in the shape Garmin devices produce:
 *  - heart rate / cadence / ambient temperature → inside `<gpxtpx:TrackPointExtension>`
 *  - power → at the top of `<extensions>`, non-namespaced
 *
 * Every entry point takes `writeExtensions` (default `true`, i.e. the pre-g23 behaviour to the
 * byte). Pass `false` for a bare GPX — a strict import target, a readable diff, an old GPS unit,
 * or simply a smaller file, since on a 1 Hz track the extensions are most of the bytes.
 */
object GpxWriter {
    // ---------- Namespace identity --------------------------------------------------

    private const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    private const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private const val NS_GARMIN_TPX = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"

    /** This project's own extension namespace, for values no standard schema defines. */
    private const val NS_VCYCLIST = "https://github.com/glandais/vcyclist/xmlschemas/v1"
    private const val PREFIX_VCYCLIST = "vc"
    private const val PREFIX_XSI = "xsi"
    private const val PREFIX_GARMIN_TPX = "gpxtpx"
    private const val SCHEMA_LOCATION = "$NS_GPX http://www.topografix.com/GPX/1/1/gpx.xsd"

    /** Value of the `creator` attribute on the root `<gpx>` element. */
    const val CREATOR: String = "@glandais/vcyclist"

    // ---------- Public entry points -------------------------------------------------

    /**
     * Serialise [document] to a GPX 1.1 XML string.
     *
     * @param writeExtensions when `false`, no `<extensions>` element is emitted and the `gpxtpx`
     *   namespace is not declared on the root. `<ele>`, `<time>`, `<name>`, `<sym>` and `<type>`
     *   are GPX 1.1 elements, not extensions, and are written either way.
     */
    fun write(
        document: GpxDocument,
        writeExtensions: Boolean = true,
    ): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val writer =
            xmlStreaming.newGenericWriter(
                output = out,
                isRepairNamespaces = false,
                xmlDeclMode = XmlDeclMode.None,
            )
        try {
            writeDocument(writer, document, writeExtensions)
        } finally {
            writer.close()
        }
        return out.toString()
    }

    /**
     * Convenience: wrap [path] in a single-track document and serialise.
     * See [Path.toGpxTrack] for the [startTime] semantics.
     */
    fun write(
        path: Path,
        name: String = "noname",
        trackName: String? = null,
        startTime: Instant? = null,
        writeExtensions: Boolean = true,
    ): String =
        write(
            path.toGpxDocument(name = name, trackName = trackName, startTime = startTime),
            writeExtensions = writeExtensions,
        )

    /**
     * Convenience: serialise [paths] as a multi-track document — one `<trk>` per [Path].
     * See [pathsToGpxDocument] for the [trackNames] / [waypoints] / [startTime] semantics.
     */
    fun write(
        paths: List<Path>,
        name: String = "noname",
        trackNames: List<String>? = null,
        waypoints: List<GpxWaypoint> = emptyList(),
        startTime: Instant? = null,
        writeExtensions: Boolean = true,
    ): String =
        write(
            pathsToGpxDocument(paths, name = name, trackNames = trackNames, waypoints = waypoints, startTime = startTime),
            writeExtensions = writeExtensions,
        )

    // ---------- Implementation ------------------------------------------------------

    private fun writeDocument(
        w: XmlWriter,
        document: GpxDocument,
        writeExtensions: Boolean,
    ) {
        // Pre-register namespace prefixes so subsequent startTag/attribute calls resolve them.
        w.setPrefix("", NS_GPX)
        w.setPrefix(PREFIX_XSI, NS_XSI)
        // `gpxtpx` is only ever used inside <extensions>; declaring it in a file that has none
        // would be valid but noise, and would break a byte comparison with a reference file.
        // `xsi` stays either way — it carries schemaLocation, which is about GPX itself.
        if (writeExtensions) w.setPrefix(PREFIX_GARMIN_TPX, NS_GARMIN_TPX)
        // `vc` is declared only when something actually uses it, so a file with no widths is
        // byte-identical to what this writer produced before the namespace existed.
        val hasRoadWidth = writeExtensions && document.tracks.any { t -> t.points.any { it.roadWidthM != null } }
        if (hasRoadWidth) w.setPrefix(PREFIX_VCYCLIST, NS_VCYCLIST)

        w.startTag(NS_GPX, "gpx", "")
        // Root attributes.
        w.attribute(null, "version", null, "1.1")
        w.attribute(null, "creator", null, CREATOR)
        // Namespace declarations (in the order they should appear on disk).
        w.namespaceAttr("", NS_GPX)
        w.namespaceAttr(PREFIX_XSI, NS_XSI)
        if (writeExtensions) w.namespaceAttr(PREFIX_GARMIN_TPX, NS_GARMIN_TPX)
        if (hasRoadWidth) w.namespaceAttr(PREFIX_VCYCLIST, NS_VCYCLIST)
        w.attribute(NS_XSI, "schemaLocation", PREFIX_XSI, SCHEMA_LOCATION)

        writeMetadata(w, document.name)
        // <wpt> comes first: the GPX 1.1 sequence is metadata, wpt*, rte*, trk*, and a writer
        // that emitted waypoints after tracks would produce files strict parsers reject.
        //
        // Routes and tracks, however, are written **in document order**, interleaved if the
        // source interleaved them. Round-tripping a file unchanged matters more here than the
        // rte*-before-trk* clause: a document that mixes the two is rare, and reordering it
        // would silently rewrite the user's file. Documented in the KDoc of [GpxPathKind].
        for (waypoint in document.waypoints) writeWaypoint(w, waypoint)
        for (track in document.tracks) {
            when (track.kind) {
                GpxPathKind.ROUTE -> writeRoute(w, track, writeExtensions)
                GpxPathKind.TRACK -> writeTrack(w, track, writeExtensions)
            }
        }

        w.endTag(NS_GPX, "gpx", "")
    }

    private fun writeWaypoint(
        w: XmlWriter,
        waypoint: GpxWaypoint,
    ) {
        w.startTag(NS_GPX, "wpt", "")
        w.attribute(null, "lat", null, waypoint.latitudeDeg.toString())
        w.attribute(null, "lon", null, waypoint.longitudeDeg.toString())

        waypoint.elevationM?.let { writeSimpleText(w, "ele", it.toString()) }
        waypoint.timeEpochMs?.let { ms ->
            writeSimpleText(w, "time", Instant.fromEpochMilliseconds(ms).toString())
        }
        waypoint.name?.let { writeSimpleText(w, "name", it) }
        waypoint.description?.let { writeSimpleText(w, "desc", it) }
        waypoint.symbol?.let { writeSimpleText(w, "sym", it) }
        waypoint.type?.let { writeSimpleText(w, "type", it) }

        w.endTag(NS_GPX, "wpt", "")
    }

    private fun writeMetadata(
        w: XmlWriter,
        name: String,
    ) {
        w.startTag(NS_GPX, "metadata", "")
        writeSimpleText(w, "name", name)
        w.endTag(NS_GPX, "metadata", "")
    }

    private fun writeTrack(
        w: XmlWriter,
        track: GpxTrack,
        writeExtensions: Boolean,
    ) {
        w.startTag(NS_GPX, "trk", "")
        track.name?.let { writeSimpleText(w, "name", it) }
        track.type?.let { writeSimpleText(w, "type", it) }
        // One <trkseg> per segment, preserving the discontinuities the parser saw. A track built
        // through the single-segment `GpxTrack(points = …)` factory still emits exactly one
        // <trkseg>, so single-track output is byte-identical to pre-g02.
        for (segment in track.segments) {
            w.startTag(NS_GPX, "trkseg", "")
            for (p in segment.points) writeTrackPoint(w, p, writeExtensions)
            w.endTag(NS_GPX, "trkseg", "")
        }
        w.endTag(NS_GPX, "trk", "")
    }

    /**
     * Write a [GpxPathKind.ROUTE] track as `<rte>` / `<rtept>`.
     *
     * A route has no segments in the schema. One built by hand with several (impossible to get
     * from the parser, which always produces exactly one) is **concatenated** rather than
     * rejected: dropping points would be worse than losing a boundary the format cannot express.
     */
    private fun writeRoute(
        w: XmlWriter,
        route: GpxTrack,
        writeExtensions: Boolean,
    ) {
        w.startTag(NS_GPX, "rte", "")
        route.name?.let { writeSimpleText(w, "name", it) }
        route.type?.let { writeSimpleText(w, "type", it) }
        for (p in route.points) writeTrackPoint(w, p, writeExtensions, localName = "rtept")
        w.endTag(NS_GPX, "rte", "")
    }

    private fun writeTrackPoint(
        w: XmlWriter,
        p: GpxTrackPoint,
        writeExtensions: Boolean,
        localName: String = "trkpt",
    ) {
        w.startTag(NS_GPX, localName, "")
        w.attribute(null, "lat", null, p.latitudeDeg.toString())
        w.attribute(null, "lon", null, p.longitudeDeg.toString())

        p.timeEpochMs?.let { ms ->
            writeSimpleText(w, "time", Instant.fromEpochMilliseconds(ms).toString())
        }
        p.elevationM?.let { ele ->
            writeSimpleText(w, "ele", ele.toString())
        }

        val hasGarminExt = p.heartRate != null || p.cadence != null || p.temperatureC != null
        val hasPower = p.powerW != null
        val hasRoadWidth = p.roadWidthM != null
        if (writeExtensions && (hasGarminExt || hasPower || hasRoadWidth)) {
            w.startTag(NS_GPX, "extensions", "")
            if (hasPower) {
                // <power> at extensions root, non-namespaced — matches sample.gpx convention.
                writeSimpleText(w, "power", p.powerW.toString())
            }
            if (hasRoadWidth) {
                // Namespaced, unlike <power>: no standard schema defines a road width, so this is
                // ours to declare rather than to squat on a bare name. It lowercases to the same
                // local name the parser accepts, so the round-trip closes.
                writeNamespaced(w, NS_VCYCLIST, "roadWidth", PREFIX_VCYCLIST, p.roadWidthM.toString())
            }
            if (hasGarminExt) {
                w.startTag(NS_GARMIN_TPX, "TrackPointExtension", PREFIX_GARMIN_TPX)
                p.heartRate?.let {
                    writeNamespaced(w, NS_GARMIN_TPX, "hr", PREFIX_GARMIN_TPX, it.toString())
                }
                p.cadence?.let {
                    writeNamespaced(w, NS_GARMIN_TPX, "cad", PREFIX_GARMIN_TPX, it.toString())
                }
                p.temperatureC?.let {
                    writeNamespaced(w, NS_GARMIN_TPX, "atemp", PREFIX_GARMIN_TPX, it.toString())
                }
                w.endTag(NS_GARMIN_TPX, "TrackPointExtension", PREFIX_GARMIN_TPX)
            }
            w.endTag(NS_GPX, "extensions", "")
        }

        w.endTag(NS_GPX, localName, "")
    }

    /** Emit `<localName>text</localName>` in the default GPX namespace. */
    private fun writeSimpleText(
        w: XmlWriter,
        localName: String,
        text: String,
    ) {
        w.startTag(NS_GPX, localName, "")
        w.text(text)
        w.endTag(NS_GPX, localName, "")
    }

    /** Emit `<prefix:localName>text</prefix:localName>` in the supplied namespace. */
    private fun writeNamespaced(
        w: XmlWriter,
        namespace: String,
        localName: String,
        prefix: String,
        text: String,
    ) {
        w.startTag(namespace, localName, prefix)
        w.text(text)
        w.endTag(namespace, localName, prefix)
    }
}
