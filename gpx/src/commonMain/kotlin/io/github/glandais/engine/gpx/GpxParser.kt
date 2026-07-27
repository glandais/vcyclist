package io.github.glandais.engine.gpx

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlException
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.time.Instant

/**
 * Multi-namespace GPX parser. Recognises track points, metadata and extension fields
 * (power, heart rate, cadence, temperature) from various GPS providers
 * (Garmin TrackPointExtension v1, Cluetrust gpxdata, Amazfit, generic).
 *
 * The strategy is **local-name based**: namespace prefixes/URIs are intentionally ignored
 * when matching extension elements, which makes the parser robust against the wild diversity
 * of GPX dialects found in the wild. This matches the behaviour of the reference TS parser
 * (`virtual-cyclist/src/gpx/ExtensionParser.ts`).
 */
object GpxParser {
    /**
     * Parse a full GPX XML string. Throws [IllegalArgumentException] on malformed XML
     * or on missing/invalid `lat`/`lon` attributes inside a `<trkpt>` element.
     */
    fun parse(xml: String): GpxDocument {
        val reader =
            try {
                xmlStreaming.newReader(xml)
            } catch (e: XmlException) {
                throw IllegalArgumentException("Invalid GPX XML: ${e.message}", e)
            }
        return try {
            parseDocument(reader)
        } catch (e: XmlException) {
            throw IllegalArgumentException("Invalid GPX XML: ${e.message}", e)
        } finally {
            reader.close()
        }
    }

    private fun parseDocument(reader: XmlReader): GpxDocument {
        // Advance to the root element.
        while (reader.hasNext()) {
            val ev = reader.next()
            if (ev == EventType.START_ELEMENT) {
                if (reader.localName != "gpx") {
                    throw IllegalArgumentException(
                        "Invalid GPX file: missing gpx root element (found ${reader.localName})",
                    )
                }
                return parseGpxRoot(reader)
            }
        }
        throw IllegalArgumentException("Invalid GPX file: missing gpx root element")
    }

    private fun parseGpxRoot(reader: XmlReader): GpxDocument {
        var name = "noname"
        val tracks = mutableListOf<GpxTrack>()
        // Caller has just consumed the <gpx> START_ELEMENT.
        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.START_ELEMENT ->
                    when (reader.localName) {
                        "metadata" -> {
                            val metaName = parseMetadataName(reader)
                            if (metaName != null) name = metaName
                        }
                        "trk" -> tracks.add(parseTrack(reader))
                        else -> skipElement(reader)
                    }
                EventType.END_ELEMENT -> return GpxDocument(name = name, tracks = tracks)
                else -> Unit // ignore
            }
        }
        return GpxDocument(name = name, tracks = tracks)
    }

    private fun parseMetadataName(reader: XmlReader): String? {
        var name: String? = null
        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.START_ELEMENT -> {
                    if (reader.localName == "name" && name == null) {
                        name = readElementText(reader).trim().ifEmpty { null }
                    } else {
                        skipElement(reader)
                    }
                }
                EventType.END_ELEMENT -> return name
                else -> Unit
            }
        }
        return name
    }

    /**
     * Parse a `<trk>` into one [GpxSegment] per `<trkseg>`. Segments are preserved verbatim,
     * **including empty ones** : the document model stays faithful to the file, and it is the
     * `GpxToPath` conversion layer that decides to skip empties.
     */
    private fun parseTrack(reader: XmlReader): GpxTrack {
        var name: String? = null
        var type: String? = null
        val segments = mutableListOf<GpxSegment>()
        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.START_ELEMENT ->
                    when (reader.localName) {
                        "name" -> name = readElementText(reader).trim().ifEmpty { null }
                        "type" -> type = readElementText(reader).trim().ifEmpty { null }
                        "trkseg" -> segments.add(parseTrackSegment(reader))
                        else -> skipElement(reader)
                    }
                EventType.END_ELEMENT -> return GpxTrack(name = name, type = type, segments = segments)
                else -> Unit
            }
        }
        return GpxTrack(name = name, type = type, segments = segments)
    }

    private fun parseTrackSegment(reader: XmlReader): GpxSegment {
        val points = mutableListOf<GpxTrackPoint>()
        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.START_ELEMENT ->
                    if (reader.localName == "trkpt") {
                        points.add(parseTrackPoint(reader))
                    } else {
                        skipElement(reader)
                    }
                EventType.END_ELEMENT -> return GpxSegment(points)
                else -> Unit
            }
        }
        return GpxSegment(points)
    }

    private fun parseTrackPoint(reader: XmlReader): GpxTrackPoint {
        // lat/lon are required attributes — read before consuming children.
        val latStr = reader.getAttributeValue(null, "lat")
        val lonStr = reader.getAttributeValue(null, "lon")
        if (latStr.isNullOrBlank() || lonStr.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Invalid track point: missing latitude or longitude attribute",
            )
        }
        val lat =
            latStr.toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid track point: latitude '$latStr' is not a valid number",
                )
        val lon =
            lonStr.toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid track point: longitude '$lonStr' is not a valid number",
                )

        var elevation: Double? = null
        var timeMs: Long? = null
        val ext = ExtensionsAccumulator()

        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.START_ELEMENT ->
                    when (reader.localName) {
                        "ele" -> {
                            val txt = readElementText(reader).trim()
                            if (txt.isNotEmpty()) elevation = txt.toDoubleOrNull() ?: elevation
                        }
                        "time" -> {
                            val txt = readElementText(reader).trim()
                            if (txt.isNotEmpty()) timeMs = parseTimeIsoToMs(txt)
                        }
                        "extensions" -> parseExtensions(reader, ext)
                        else -> skipElement(reader)
                    }
                EventType.END_ELEMENT ->
                    return GpxTrackPoint(
                        latitudeDeg = lat,
                        longitudeDeg = lon,
                        elevationM = elevation,
                        timeEpochMs = timeMs,
                        heartRate = ext.heartRate,
                        cadence = ext.cadence,
                        temperatureC = ext.temperatureC,
                        powerW = ext.powerW,
                    )
                else -> Unit
            }
        }
        return GpxTrackPoint(
            latitudeDeg = lat,
            longitudeDeg = lon,
            elevationM = elevation,
            timeEpochMs = timeMs,
            heartRate = ext.heartRate,
            cadence = ext.cadence,
            temperatureC = ext.temperatureC,
            powerW = ext.powerW,
        )
    }

    /**
     * Recursively scan an `<extensions>` element for known leaf names. Recurses through
     * containers such as `<TrackPointExtension>` (Garmin) without requiring namespace
     * awareness — we only care about the **local name** of each leaf element.
     */
    private fun parseExtensions(
        reader: XmlReader,
        ext: ExtensionsAccumulator,
    ) {
        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.START_ELEMENT -> {
                    val ln = reader.localName
                    when (ln.lowercase()) {
                        "power" -> {
                            readNumeric(reader)?.let { ext.powerW = ext.powerW ?: it }
                        }
                        "hr", "heartrate" -> {
                            readNumeric(reader)?.let {
                                if (ext.heartRate == null) ext.heartRate = it.toInt()
                            }
                        }
                        "cad", "cadence" -> {
                            readNumeric(reader)?.let {
                                if (ext.cadence == null) ext.cadence = it.toInt()
                            }
                        }
                        "atemp", "temperature", "temp" -> {
                            readNumeric(reader)?.let {
                                ext.temperatureC = ext.temperatureC ?: it
                            }
                        }
                        else -> {
                            // Recurse into containers (e.g. <TrackPointExtension>) and other
                            // wrapper elements ; their child leaves carry the values we want.
                            parseExtensions(reader, ext)
                        }
                    }
                }
                EventType.END_ELEMENT -> return
                else -> Unit
            }
        }
    }

    /** Read the text content of the currently-open element and consume its END_ELEMENT. */
    private fun readElementText(reader: XmlReader): String {
        val sb = StringBuilder()
        while (reader.hasNext()) {
            val ev = reader.next()
            when (ev) {
                EventType.TEXT, EventType.CDSECT, EventType.IGNORABLE_WHITESPACE -> sb.append(reader.text)
                EventType.START_ELEMENT -> skipElement(reader) // unexpected nested ; skip
                EventType.END_ELEMENT -> return sb.toString()
                else -> Unit
            }
        }
        return sb.toString()
    }

    /** Read text then parse as Double. Returns null on empty/unparseable. */
    private fun readNumeric(reader: XmlReader): Double? {
        val txt = readElementText(reader).trim()
        return txt.toDoubleOrNull()
    }

    /** Consume everything up to and including the matching END_ELEMENT. */
    private fun skipElement(reader: XmlReader) {
        var depth = 1
        while (reader.hasNext() && depth > 0) {
            when (reader.next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                else -> Unit
            }
        }
    }

    /**
     * Parse an ISO-8601 instant into epoch milliseconds. Returns null on parse failure
     * to mirror the TS parser's "swallow and continue" semantics.
     */
    private fun parseTimeIsoToMs(text: String): Long? =
        try {
            Instant.parse(text).toEpochMilliseconds()
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: Throwable) {
            // Any other Throwable raised by Instant.parse on malformed input → swallow,
            // matching the TS semantics that simply skip invalid `<time>` tags.
            null
        }

    private class ExtensionsAccumulator(
        var heartRate: Int? = null,
        var cadence: Int? = null,
        var temperatureC: Double? = null,
        var powerW: Double? = null,
    )
}
