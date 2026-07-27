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
 * Extensions are written exactly as the upstream `sample.gpx` does:
 *  - heart rate / cadence / ambient temperature → inside `<gpxtpx:TrackPointExtension>`
 *  - power → at the top of `<extensions>`, non-namespaced (matches the TS reference writer)
 */
object GpxWriter {
    // ---------- Namespace identity --------------------------------------------------

    private const val NS_GPX = "http://www.topografix.com/GPX/1/1"
    private const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private const val NS_GARMIN_TPX = "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"
    private const val PREFIX_XSI = "xsi"
    private const val PREFIX_GARMIN_TPX = "gpxtpx"
    private const val SCHEMA_LOCATION = "$NS_GPX http://www.topografix.com/GPX/1/1/gpx.xsd"

    /** Value of the `creator` attribute on the root `<gpx>` element. */
    const val CREATOR: String = "@glandais/vcyclist"

    // ---------- Public entry points -------------------------------------------------

    /** Serialise [document] to a GPX 1.1 XML string. */
    fun write(document: GpxDocument): String {
        val out = StringBuilder()
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        val writer =
            xmlStreaming.newGenericWriter(
                output = out,
                isRepairNamespaces = false,
                xmlDeclMode = XmlDeclMode.None,
            )
        try {
            writeDocument(writer, document)
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
    ): String = write(path.toGpxDocument(name = name, trackName = trackName, startTime = startTime))

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
    ): String =
        write(
            pathsToGpxDocument(paths, name = name, trackNames = trackNames, waypoints = waypoints, startTime = startTime),
        )

    // ---------- Implementation ------------------------------------------------------

    private fun writeDocument(
        w: XmlWriter,
        document: GpxDocument,
    ) {
        // Pre-register namespace prefixes so subsequent startTag/attribute calls resolve them.
        w.setPrefix("", NS_GPX)
        w.setPrefix(PREFIX_XSI, NS_XSI)
        w.setPrefix(PREFIX_GARMIN_TPX, NS_GARMIN_TPX)

        w.startTag(NS_GPX, "gpx", "")
        // Root attributes.
        w.attribute(null, "version", null, "1.1")
        w.attribute(null, "creator", null, CREATOR)
        // Namespace declarations (in the order they should appear on disk).
        w.namespaceAttr("", NS_GPX)
        w.namespaceAttr(PREFIX_XSI, NS_XSI)
        w.namespaceAttr(PREFIX_GARMIN_TPX, NS_GARMIN_TPX)
        w.attribute(NS_XSI, "schemaLocation", PREFIX_XSI, SCHEMA_LOCATION)

        writeMetadata(w, document.name)
        // GPX 1.1 schema order is metadata, wpt*, rte*, trk* — a writer that puts <wpt> after
        // <trk> produces a file that strict parsers reject. <rte> stays unsupported (see g02).
        for (waypoint in document.waypoints) writeWaypoint(w, waypoint)
        for (track in document.tracks) writeTrack(w, track)

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
    ) {
        w.startTag(NS_GPX, "trk", "")
        track.name?.let { writeSimpleText(w, "name", it) }
        track.type?.let { writeSimpleText(w, "type", it) }
        // One <trkseg> per segment, preserving the discontinuities the parser saw. A track built
        // through the single-segment `GpxTrack(points = …)` factory still emits exactly one
        // <trkseg>, so single-track output is byte-identical to pre-g02.
        for (segment in track.segments) {
            w.startTag(NS_GPX, "trkseg", "")
            for (p in segment.points) writeTrackPoint(w, p)
            w.endTag(NS_GPX, "trkseg", "")
        }
        w.endTag(NS_GPX, "trk", "")
    }

    private fun writeTrackPoint(
        w: XmlWriter,
        p: GpxTrackPoint,
    ) {
        w.startTag(NS_GPX, "trkpt", "")
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
        if (hasGarminExt || hasPower) {
            w.startTag(NS_GPX, "extensions", "")
            if (hasPower) {
                // <power> at extensions root, non-namespaced — matches sample.gpx convention.
                writeSimpleText(w, "power", p.powerW.toString())
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

        w.endTag(NS_GPX, "trkpt", "")
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
