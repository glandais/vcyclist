package io.github.glandais.engine.gpx

import nl.adaptivity.xmlutil.EventType
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
     *
     * When [repairOnFailure] is true (the default) and the first attempt fails, [GpxXmlRepair]
     * is applied once and parsing is retried on the repaired text — this is what lets the
     * browser demo accept files from approximate GPS devices/exporters instead of rejecting
     * them outright. The repair pass itself costs a full string copy, so it only runs after a
     * failure, never speculatively on the (common) valid file. If the retry also fails, the
     * **second** attempt's exception is what propagates (the first describes a string that no
     * longer exists after repair, so it would be a misleading message) with a note that a
     * repair was attempted. Set [repairOnFailure] to false to see the original parse failure
     * verbatim, or call [GpxXmlRepair.repair] / [GpxXmlRepair.repairVerbose] explicitly beforehand
     * for diagnostics (which repairs were applied).
     */
    fun parse(
        xml: String,
        repairOnFailure: Boolean = true,
    ): GpxDocument =
        try {
            parseOnce(xml)
        } catch (firstFailure: IllegalArgumentException) {
            if (!repairOnFailure) throw firstFailure
            val repaired = GpxXmlRepair.repair(xml)
            try {
                parseOnce(repaired)
            } catch (secondFailure: IllegalArgumentException) {
                throw IllegalArgumentException(
                    "Invalid GPX XML (repair attempted but parsing still failed): ${secondFailure.message}",
                    secondFailure,
                )
            }
        }

    private fun parseOnce(xml: String): GpxDocument {
        // Catches any Exception, not just XmlException: some backends (e.g. the kxml-based
        // Kotlin/JS reader on unambiguously non-XML input such as plain text) surface an
        // IllegalStateException instead. Our own IllegalArgumentException (missing/invalid
        // lat/lon) passes through unwrapped so its message stays precise.
        val reader =
            try {
                xmlStreaming.newReader(xml)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid GPX XML: ${e.message}", e)
            }
        return try {
            parseDocument(reader)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
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
        val waypoints = mutableListOf<GpxWaypoint>()
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
                        "wpt" -> waypoints.add(parseWaypoint(reader))
                        else -> skipElement(reader)
                    }
                EventType.END_ELEMENT -> return GpxDocument(name = name, tracks = tracks, waypoints = waypoints)
                else -> Unit // ignore
            }
        }
        return GpxDocument(name = name, tracks = tracks, waypoints = waypoints)
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
     * Parse a `<wpt>` element. Same required-attribute contract as `<trkpt>` (`lat`/`lon`
     * mandatory, everything else optional). Extensions are intentionally **not** parsed — the
     * spec only carries the standard GPX 1.1 waypoint fields (`ele`, `name`, `desc`, `sym`,
     * `type`, `time`).
     */
    private fun parseWaypoint(reader: XmlReader): GpxWaypoint {
        val latStr = reader.getAttributeValue(null, "lat")
        val lonStr = reader.getAttributeValue(null, "lon")
        if (latStr.isNullOrBlank() || lonStr.isNullOrBlank()) {
            throw IllegalArgumentException(
                "Invalid waypoint: missing latitude or longitude attribute",
            )
        }
        val lat =
            latStr.toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid waypoint: latitude '$latStr' is not a valid number",
                )
        val lon =
            lonStr.toDoubleOrNull()
                ?: throw IllegalArgumentException(
                    "Invalid waypoint: longitude '$lonStr' is not a valid number",
                )

        var elevation: Double? = null
        var timeMs: Long? = null
        var name: String? = null
        var description: String? = null
        var symbol: String? = null
        var type: String? = null

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
                        "name" -> name = readElementText(reader).trim().ifEmpty { null }
                        "desc" -> description = readElementText(reader).trim().ifEmpty { null }
                        "sym" -> symbol = readElementText(reader).trim().ifEmpty { null }
                        "type" -> type = readElementText(reader).trim().ifEmpty { null }
                        else -> skipElement(reader)
                    }
                EventType.END_ELEMENT ->
                    return GpxWaypoint(
                        latitudeDeg = lat,
                        longitudeDeg = lon,
                        elevationM = elevation,
                        name = name,
                        description = description,
                        symbol = symbol,
                        type = type,
                        timeEpochMs = timeMs,
                    )
                else -> Unit
            }
        }
        return GpxWaypoint(
            latitudeDeg = lat,
            longitudeDeg = lon,
            elevationM = elevation,
            name = name,
            description = description,
            symbol = symbol,
            type = type,
            timeEpochMs = timeMs,
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
                // ENTITY_REF matters : some xmlutil backends (the JVM/kxml reader in particular)
                // surface a predefined entity such as `&amp;` as its own event rather than folding
                // it into the surrounding TEXT. Without this branch the entity — and everything it
                // stood for — was silently dropped, so `<name>Ravito &amp; eau</name>` parsed as
                // "Ravito  eau". `reader.text` already carries the resolved character.
                EventType.TEXT,
                EventType.CDSECT,
                EventType.IGNORABLE_WHITESPACE,
                EventType.ENTITY_REF,
                -> sb.append(reader.text)
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
