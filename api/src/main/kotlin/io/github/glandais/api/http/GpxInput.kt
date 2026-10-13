package io.github.glandais.api.http

import io.github.glandais.api.ApiConfig
import io.github.glandais.api.apiServices
import io.github.glandais.engine.gpx.GpxDocument
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxPathKind
import io.github.glandais.engine.gpx.GpxSegment
import io.github.glandais.engine.gpx.GpxTrack
import io.github.glandais.engine.gpx.GpxXmlRepair
import io.github.glandais.engine.gpx.segmentsAsPaths
import io.github.glandais.engine.gpx.toPath
import io.github.glandais.engine.path.Path
import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveMultipart
import kotlinx.serialization.KSerializer

/**
 * A GPX upload, parsed and narrowed to the paths the query selected.
 *
 * [paths], [names] and [kinds] are aligned: entry *i* of each describes the same path.
 *
 * @property document the whole parsed document (waypoints, metadata name, every track)
 * @property names the `<trk>` / `<rte>` name of each path, `null` when absent
 * @property kinds the container each path came from
 * @property request the decoded `request` part, `null` when absent or for a raw upload
 * @property warnings the repairs applied to the XML before it parsed
 */
data class GpxInput<R>(
    val document: GpxDocument,
    val paths: List<Path>,
    val names: List<String?>,
    val kinds: List<GpxPathKind>,
    val request: R?,
    val warnings: List<String>,
)

/** `?kinds=` values, their selection. */
enum class GpxKindsParam(
    val id: String,
    val kinds: Set<GpxPathKind>,
) {
    ALL("all", GpxPathKind.entries.toSet()),
    TRACKS("tracks", setOf(GpxPathKind.TRACK)),
    ROUTES("routes", setOf(GpxPathKind.ROUTE)),
    ;

    companion object {
        val DEFAULT: GpxKindsParam = ALL
        val ids: List<String> get() = entries.map { it.id }

        fun byIdOrNull(id: String): GpxKindsParam? = entries.firstOrNull { it.id == id }
    }
}

/** `?split=` values: one path per `<trk>` (segments concatenated) or one per `<trkseg>`. */
enum class GpxSplitParam(
    val id: String,
) {
    TRACKS("tracks"),
    SEGMENTS("segments"),
    ;

    companion object {
        val DEFAULT: GpxSplitParam = TRACKS
        val ids: List<String> get() = entries.map { it.id }

        fun byIdOrNull(id: String): GpxSplitParam? = entries.firstOrNull { it.id == id }
    }
}

/** The query parameters every GPX-reading operation accepts, and only those (plus its own). */
object GpxQuery {
    const val KINDS: String = "kinds"
    const val SPLIT: String = "split"
    const val TRACK_INDEX: String = "trackIndex"
    const val REPAIR: String = "repair"

    /** Default of [REPAIR]: a server read is as forgiving as the browser demo. */
    const val REPAIR_DEFAULT: Boolean = true

    val names: Set<String> = setOf(KINDS, SPLIT, TRACK_INDEX, REPAIR)
}

/** Multipart part names. */
object GpxParts {
    const val GPX: String = "gpx"
    const val REQUEST: String = "request"
}

/** The media types a raw GPX body may be sent as. */
val GpxBodyTypes: List<ContentType> = listOf(MediaTypes.Gpx, ContentType.Application.Xml, ContentType.Text.Xml)

/**
 * Reads a GPX upload — `multipart/form-data` with a `gpx` part and an optional `request` part, or
 * a raw `application/gpx+xml` (`application/xml`, `text/xml`) body — and parses it.
 *
 * Every check that needs no computation runs first, in this order, so a malformed request never
 * takes the compute gate: query keys (`kinds`, `split`, `trackIndex`, `repair` and [extraQuery]),
 * query values, the media type, the body size, the multipart parts, the `request` JSON. Then the
 * XML is parsed under the [ComputeGate]: strictly first; on failure, when `repair` is on,
 * [GpxXmlRepair.repairVerbose] runs and the repaired text is parsed, each repair becoming a
 * warning.
 *
 * @param requestSerializer the route's request DTO; `null` when the route takes none, which makes
 *   a `request` part an unknown key
 * @throws ApiProblem `unknown-key`, `invalid-option`, `invalid-body`, `unsupported-media-type`,
 *   `payload-too-large`, `invalid-gpx`, `empty-gpx`, `track-index-out-of-range`, `too-many-points`
 */
suspend fun <R> ApplicationCall.receiveGpxInput(
    requestSerializer: KSerializer<R>?,
    config: ApiConfig,
    extraQuery: Set<String> = emptySet(),
): GpxInput<R> = receiveGpxUpload(requestSerializer, config, extraQuery).parse(apiServices.computeGate, config)

/**
 * A GPX upload read and checked, not parsed yet: what [receiveGpxUpload] returns so a route can
 * map its [request] into engine objects — and answer 400 — before any computation starts.
 */
class GpxUpload<R>(
    private val xml: String,
    val request: R?,
    private val kinds: GpxKindsParam,
    private val split: GpxSplitParam,
    private val trackIndex: Int?,
    private val repair: Boolean,
) {
    /**
     * Parses the XML under [gate] and narrows it to the selected paths.
     *
     * @throws ApiProblem `invalid-gpx`, `empty-gpx`, `track-index-out-of-range`, `too-many-points`,
     *   `timeout`
     */
    suspend fun parse(
        gate: ComputeGate,
        config: ApiConfig,
    ): GpxInput<R> =
        gate.run {
            val (document, warnings) = parseGpx(xml, repair)
            val selected = select(document, kinds.kinds, split, trackIndex, config.maxTrackPoints)
            GpxInput(
                document = document,
                paths = selected.map { it.toPath() },
                names = selected.map { it.track.name },
                kinds = selected.map { it.track.kind },
                request = request,
                warnings = warnings,
            )
        }
}

/**
 * The first half of [receiveGpxInput]: every check that needs no computation — query keys and
 * values, media type, size, multipart parts, the strict decode of `request` — without parsing.
 *
 * @throws ApiProblem `unknown-key`, `invalid-option`, `invalid-body`, `unsupported-media-type`,
 *   `payload-too-large`
 */
suspend fun <R> ApplicationCall.receiveGpxUpload(
    requestSerializer: KSerializer<R>?,
    config: ApiConfig,
    extraQuery: Set<String> = emptySet(),
): GpxUpload<R> {
    requireOnlyQuery(*(GpxQuery.names + extraQuery).toTypedArray())
    val kinds = queryEnum(GpxQuery.KINDS, GpxKindsParam::byIdOrNull) ?: GpxKindsParam.DEFAULT
    val split = queryEnum(GpxQuery.SPLIT, GpxSplitParam::byIdOrNull) ?: GpxSplitParam.DEFAULT
    val trackIndex = queryInt(GpxQuery.TRACK_INDEX)
    if (trackIndex != null && trackIndex < 0) {
        throw ApiProblem(ProblemKind.INVALID_OPTION, "Query parameter '${GpxQuery.TRACK_INDEX}' must be >= 0, was $trackIndex")
    }
    val repair = queryBoolean(GpxQuery.REPAIR) ?: GpxQuery.REPAIR_DEFAULT

    val contentType = request.contentType().withoutParameters()
    val (xml, request) =
        when {
            contentType.match(ContentType.MultiPart.FormData) -> receiveGpxMultipart(requestSerializer, config.maxBodyBytes)
            GpxBodyTypes.any { contentType.match(it) } -> receiveBoundedBytes(config.maxBodyBytes).decodeToString() to null
            else -> throw ApiProblem(
                ProblemKind.UNSUPPORTED_MEDIA_TYPE,
                "Content-Type '$contentType' is not accepted: send multipart/form-data, or a raw " +
                    GpxBodyTypes.joinToString(", ") + " body",
            )
        }
    return GpxUpload(xml, request, kinds, split, trackIndex, repair)
}

/** Reads the parts of a multipart upload: the `gpx` text and the decoded `request`. */
private suspend fun <R> ApplicationCall.receiveGpxMultipart(
    requestSerializer: KSerializer<R>?,
    maxBodyBytes: Long,
): Pair<String, R?> {
    requireDeclaredLengthAtMost(maxBodyBytes)
    var gpx: ByteArray? = null
    var requestText: String? = null
    val unknown = mutableListOf<String>()
    var consumed = 0L

    suspend fun readPart(part: PartData): ByteArray? {
        val remaining = maxBodyBytes - consumed
        val bytes =
            when (part) {
                is PartData.FormItem -> part.value.encodeToByteArray().also { if (it.size > remaining) throw payloadTooLarge(maxBodyBytes) }
                is PartData.FileItem -> part.provider().readBounded(remaining) { throw payloadTooLarge(maxBodyBytes) }
                is PartData.BinaryChannelItem -> part.provider().readBounded(remaining) { throw payloadTooLarge(maxBodyBytes) }
                is PartData.BinaryItem -> null
            }
        consumed += bytes?.size ?: 0
        return bytes
    }

    // A stream over the limit fails inside Ktor's multipart parser, which runs in its own coroutine
    // and cancels the call with an IOException: StatusPages maps that one (see isBodyLimitExceeded).
    receiveMultipart(formFieldLimit = maxBodyBytes).forEachPart { part ->
        try {
            when (val name = part.name) {
                GpxParts.GPX -> {
                    if (gpx != null) throw ApiProblem(ProblemKind.INVALID_BODY, "Part '${GpxParts.GPX}' given more than once")
                    gpx = readPart(part)
                        ?: throw ApiProblem(ProblemKind.INVALID_BODY, "Part '${GpxParts.GPX}' must be a file or a form field")
                }

                GpxParts.REQUEST -> {
                    if (requestSerializer == null) {
                        unknown += "part:$name"
                    } else {
                        if (requestText != null) {
                            throw ApiProblem(ProblemKind.INVALID_BODY, "Part '${GpxParts.REQUEST}' given more than once")
                        }
                        requestText = readPart(part)?.decodeToString()
                            ?: throw ApiProblem(
                                ProblemKind.INVALID_BODY,
                                "Part '${GpxParts.REQUEST}' must be a JSON form field or file",
                            )
                    }
                }

                else -> unknown += "part:${name.orEmpty()}"
            }
        } finally {
            part.release()
        }
    }

    if (unknown.isNotEmpty()) {
        val allowed = listOfNotNull(GpxParts.GPX, GpxParts.REQUEST.takeIf { requestSerializer != null })
        throw ApiProblem(
            ProblemKind.UNKNOWN_KEY,
            "Unknown multipart part(s): ${unknown.joinToString(", ")}; allowed: ${allowed.joinToString(", ")}",
            unknownKeys = unknown,
        )
    }
    val gpxBytes = gpx ?: throw ApiProblem(ProblemKind.INVALID_BODY, "Missing multipart part '${GpxParts.GPX}'")
    val request = requestText?.let { decodeStrict(requestSerializer!!, it) }
    return gpxBytes.decodeToString() to request
}

/**
 * Parses [xml] strictly; on failure and when [repair] is on, repairs it and parses again.
 *
 * @return the document and the repairs applied (empty when the strict parse succeeded)
 * @throws ApiProblem `invalid-gpx`
 */
internal fun parseGpx(
    xml: String,
    repair: Boolean,
): Pair<GpxDocument, List<String>> {
    val strictFailure =
        try {
            return GpxParser.parse(xml, repairOnFailure = false) to emptyList()
        } catch (e: IllegalArgumentException) {
            e
        }
    if (!repair) {
        throw ApiProblem(ProblemKind.INVALID_GPX, strictFailure.message, cause = strictFailure)
    }
    val repaired = GpxXmlRepair.repairVerbose(xml)
    if (repaired.repairs.isEmpty()) {
        // Nothing to repair: parsing again would fail the same way.
        throw ApiProblem(ProblemKind.INVALID_GPX, strictFailure.message, cause = strictFailure)
    }
    return try {
        GpxParser.parse(repaired.xml, repairOnFailure = false) to repaired.repairs
    } catch (e: IllegalArgumentException) {
        throw ApiProblem(
            ProblemKind.INVALID_GPX,
            "${e.message} (after repair: ${repaired.repairs.joinToString("; ")})",
            cause = e,
        )
    }
}

/**
 * One path to materialise: a whole [track] (segments concatenated), or one of its [segment]s.
 * Kept unconverted until the selection is final, so `trackIndex` converts one path, not all.
 */
internal class PathSource(
    val track: GpxTrack,
    val segment: GpxSegment?,
) {
    val pointCount: Int get() = segment?.points?.size ?: track.points.size

    /**
     * Converts through the engine's own functions: [GpxTrack.toPath] for a track, and
     * [segmentsAsPaths] on a one-segment copy for a segment, the only public route that applies
     * the track's default road width to its segments.
     */
    fun toPath(): Path =
        if (segment == null) {
            track.toPath()
        } else {
            GpxDocument(tracks = listOf(track.copy(segments = listOf(segment)))).segmentsAsPaths(setOf(track.kind)).single()
        }
}

/**
 * The paths [kinds] and [split] select, without the empty ones — the same selection as
 * `tracksAsPaths` / `segmentsAsPaths` — narrowed to [trackIndex] when given.
 *
 * @throws ApiProblem `empty-gpx`, `track-index-out-of-range`, `too-many-points`
 */
internal fun select(
    document: GpxDocument,
    kinds: Set<GpxPathKind>,
    split: GpxSplitParam,
    trackIndex: Int?,
    maxTrackPoints: Int,
): List<PathSource> {
    val tracks = document.tracks.filter { it.kind in kinds }
    val all =
        when (split) {
            GpxSplitParam.TRACKS -> tracks.filter { it.points.isNotEmpty() }.map { PathSource(it, null) }
            GpxSplitParam.SEGMENTS ->
                tracks.flatMap { track -> track.segments.filter { it.points.isNotEmpty() }.map { PathSource(track, it) } }
        }
    if (all.isEmpty()) {
        throw ApiProblem(ProblemKind.EMPTY_GPX, "The GPX has no ${kinds.joinToString("/") { it.name.lowercase() }} with at least one point")
    }
    val selected =
        if (trackIndex == null) {
            all
        } else {
            listOf(
                all.getOrNull(trackIndex)
                    ?: throw ApiProblem(
                        ProblemKind.TRACK_INDEX_OUT_OF_RANGE,
                        "trackIndex $trackIndex is out of range: the selection has ${all.size} path(s), indices 0..${all.size - 1}",
                    ),
            )
        }
    val points = selected.sumOf { it.pointCount.toLong() }
    if (points > maxTrackPoints) {
        throw ApiProblem(ProblemKind.TOO_MANY_POINTS, "The selection has $points points; the limit is $maxTrackPoints")
    }
    return selected
}
