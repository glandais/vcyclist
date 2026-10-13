package io.github.glandais.api.service

import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.FieldError
import io.github.glandais.api.http.GpxQuery
import io.github.glandais.api.http.OutputFormat
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.api.mapping.HttpDefaults
import io.github.glandais.api.mapping.OutputPlan
import io.github.glandais.engine.gpx.GpxDocument
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.pathsToGpxDocument
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.engine.path.Path
import io.github.glandais.fit.toFitBytes
import io.ktor.http.ContentType

/**
 * A rendered file: its bytes, its `Content-Type`, and the filename of its
 * `Content-Disposition: attachment`.
 */
class ExportedBody(
    val bytes: ByteArray,
    val contentType: ContentType,
    val filename: String,
)

/**
 * Checks what a [plan] needs of the path count, **before** anything is computed: CSV and the
 * column JSON carry one path (`multiple-tracks` otherwise — never the first one in silence, the
 * lesson of F3), and `trackNames` names every written track.
 *
 * @throws ApiProblem `multiple-tracks`, `track-names-mismatch`
 */
fun checkPathCount(
    plan: OutputPlan,
    count: Int,
    at: String = "/output",
) {
    when (plan) {
        is OutputPlan.Csv, is OutputPlan.Json ->
            if (count > 1) {
                throw ApiProblem(
                    ProblemKind.MULTIPLE_TRACKS,
                    "format=${plan.format.id} carries a single path and the selection has $count: pick one with " +
                        "?${GpxQuery.TRACK_INDEX}=<0..${count - 1}>",
                )
            }

        is OutputPlan.Gpx -> {
            val names = plan.settings.trackNames
            if (names != null && names.size != count) {
                throw ApiProblem(
                    ProblemKind.TRACK_NAMES_MISMATCH,
                    "$at/gpx/trackNames has ${names.size} name(s) for $count track(s)",
                    errors = listOf(FieldError("$at/gpx/trackNames", "expected $count name(s), got ${names.size}")),
                )
            }
        }

        OutputPlan.Summary, is OutputPlan.Fit -> Unit
    }
}

/** `name`, `name-2`, `name-3`… — one per path. */
internal fun numberedNames(
    name: String,
    count: Int,
): List<String> = List(count) { i -> if (i == 0) name else "$name-${i + 1}" }

/**
 * Renders [paths] in [plan]'s file format.
 *
 * @param inputNames the names the paths came with, kept by GPX output when the request names
 *   none (`/export`); `null` names them after [HttpDefaults.TRACK_NAME] (`/enhance`, whose paths
 *   are a simulation, not the recording)
 * @param document the input document: its `<metadata><name>` and its waypoints
 * @throws ApiProblem `not-encodable` when FIT refuses a path (no point, time going backwards)
 */
fun export(
    plan: OutputPlan,
    paths: List<Path>,
    inputNames: List<String?>?,
    document: GpxDocument,
): ExportedBody {
    checkPathCount(plan, paths.size)
    val fallbackNames: List<String?> = inputNames ?: numberedNames(HttpDefaults.TRACK_NAME, paths.size)
    return when (plan) {
        OutputPlan.Summary -> error("summary is not a file")

        is OutputPlan.Gpx -> {
            val s = plan.settings
            val names: List<String?> = s.trackNames ?: s.trackName?.let { numberedNames(it, paths.size) } ?: fallbackNames
            val written =
                pathsToGpxDocument(
                    paths,
                    name = document.name,
                    waypoints = if (s.includeWaypoints) document.waypoints else emptyList(),
                    startTime = s.startTime,
                    powerSource = s.powerSource,
                )
            // pathsToGpxDocument takes non-null names only; an input track without one stays unnamed.
            val named = written.copy(tracks = written.tracks.mapIndexed { i, t -> t.copy(name = names[i]) })
            val text = GpxWriter.write(named, writeExtensions = s.writeExtensions)
            ExportedBody(text.encodeToByteArray(), plan.format.mediaType, filename(names.firstOrNull(), plan.format))
        }

        is OutputPlan.Fit -> {
            val s = plan.settings
            val bytes =
                try {
                    paths.toFitBytes(s.name, s.startTime, interPathGap = s.interPathGap)
                } catch (e: IllegalArgumentException) {
                    throw ApiProblem(ProblemKind.NOT_ENCODABLE, "FIT cannot encode this course: ${e.message}", cause = e)
                }
            ExportedBody(bytes, plan.format.mediaType, filename(s.name, plan.format))
        }

        is OutputPlan.Csv ->
            ExportedBody(
                CsvWriter.write(paths.single(), plan.options).encodeToByteArray(),
                plan.format.mediaType,
                filename(fallbackNames.single(), plan.format),
            )

        is OutputPlan.Json ->
            ExportedBody(
                JsonWriter.write(paths.single(), plan.options).encodeToByteArray(),
                plan.format.mediaType,
                filename(fallbackNames.single(), plan.format),
            )
    }
}

/** Longest filename stem kept. */
private const val MAX_STEM = 100

/**
 * `<stem>.<ext>`: [name] with every character outside `[A-Za-z0-9._-]` replaced by `_`, so it can
 * be quoted in a header as is; [HttpDefaults.TRACK_NAME] when nothing is left.
 */
internal fun filename(
    name: String?,
    format: OutputFormat,
): String {
    val stem =
        name
            .orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
            .take(MAX_STEM)
            .ifEmpty { HttpDefaults.TRACK_NAME }
    return "$stem.${format.extension}"
}
