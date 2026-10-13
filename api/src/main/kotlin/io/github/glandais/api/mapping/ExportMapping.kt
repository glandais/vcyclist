package io.github.glandais.api.mapping

import io.github.glandais.api.dto.CsvOptionsDto
import io.github.glandais.api.dto.FitOptionsDto
import io.github.glandais.api.dto.GpxWriteOptionsDto
import io.github.glandais.api.dto.JsonOptionsDto
import io.github.glandais.api.dto.OutputDto
import io.github.glandais.api.http.ApiProblem
import io.github.glandais.api.http.FieldError
import io.github.glandais.api.http.OutputFormat
import io.github.glandais.api.http.ProblemKind
import io.github.glandais.engine.gpx.GpxPowerSource
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/*
 * Writer settings (`output.*`) → engine objects, and the inverse for `GET /defaults`.
 *
 * Every fallback is read off [HttpDefaults]. Everything is mapped before any computation: a bad
 * separator, an unknown field, a missing FIT start time are 4xx that never take the compute gate.
 */

/** What `format=gpx` writes. `trackName == null && trackNames == null`: the route decides. */
data class GpxWriteSettings(
    val writeExtensions: Boolean,
    val startTime: Instant?,
    val trackName: String?,
    val trackNames: List<String>?,
    val powerSource: GpxPowerSource,
    val includeWaypoints: Boolean,
)

/** What `format=fit` encodes. */
data class FitSettings(
    val name: String,
    val startTime: Instant,
    val interPathGap: Duration,
)

/** The resolved format together with its writer settings — what an export needs, mapped. */
sealed interface OutputPlan {
    val format: OutputFormat

    data object Summary : OutputPlan {
        override val format: OutputFormat get() = OutputFormat.SUMMARY
    }

    data class Gpx(
        val settings: GpxWriteSettings,
    ) : OutputPlan {
        override val format: OutputFormat get() = OutputFormat.GPX
    }

    data class Fit(
        val settings: FitSettings,
    ) : OutputPlan {
        override val format: OutputFormat get() = OutputFormat.FIT
    }

    data class Csv(
        val options: CsvOptions,
    ) : OutputPlan {
        override val format: OutputFormat get() = OutputFormat.CSV
    }

    data class Json(
        val options: JsonOptions,
    ) : OutputPlan {
        override val format: OutputFormat get() = OutputFormat.JSON
    }
}

/**
 * Maps the member of `output` that [format] reads. Any **other** member given is a key accepted
 * and not used, so `invalid-option` (« `output.csv` given but format is `gpx` »).
 *
 * @throws ApiProblem `invalid-option`, `missing-start-time`
 */
fun OutputDto?.toPlan(
    format: OutputFormat,
    at: String = "/output",
): OutputPlan {
    val given =
        listOfNotNull(
            OutputFormat.GPX.takeIf { this?.gpx != null },
            OutputFormat.CSV.takeIf { this?.csv != null },
            OutputFormat.JSON.takeIf { this?.json != null },
            OutputFormat.FIT.takeIf { this?.fit != null },
        )
    val unused = given.filter { it != format }
    if (unused.isNotEmpty()) {
        throw ApiProblem(
            ProblemKind.INVALID_OPTION,
            unused.joinToString(", ") { "output.${it.id}" } + " given but format is ${format.id}: only output.${format.id} is read",
            errors = unused.map { FieldError("$at/${it.id}", "not read by format ${format.id}") },
        )
    }
    return when (format) {
        OutputFormat.SUMMARY -> OutputPlan.Summary
        OutputFormat.GPX -> OutputPlan.Gpx(this?.gpx.toGpxWrite("$at/gpx"))
        OutputFormat.FIT -> OutputPlan.Fit(this?.fit.toFit("$at/fit"))
        OutputFormat.CSV -> OutputPlan.Csv(this?.csv.toCsvOptions("$at/csv"))
        OutputFormat.JSON -> OutputPlan.Json(this?.json.toJsonOptions("$at/json"))
    }
}

/** Resolves `fields` by `PointField.prop`; empty or unknown → `invalid-option` naming each pointer. */
private fun List<String>.toFields(at: String): List<PointField> {
    if (isEmpty()) throw invalidOption(at, "must name at least one field; omit it for every field")
    val errors = mutableListOf<FieldError>()
    val fields =
        mapIndexedNotNull { i, prop ->
            PointField.byProp(prop) ?: null.also { errors += FieldError("$at/$i", "unknown field '$prop'; see GET /api/v1/fields") }
        }
    if (errors.isNotEmpty()) {
        throw ApiProblem(ProblemKind.INVALID_OPTION, errors.joinToString("; ") { "${it.pointer}: ${it.message}" }, errors = errors)
    }
    return fields
}

/**
 * Checks `decimals` against the writers' own domain by formatting one number with it: the bound
 * lives in the engine's number formatter, and restating it here would be a second site.
 */
private fun checkDecimals(
    decimals: Int?,
    at: String,
) {
    if (decimals == null) return
    try {
        CsvWriter.write(Path(1), CsvOptions(fields = listOf(PointField.entries.first()), decimals = decimals))
    } catch (e: IllegalArgumentException) {
        throw invalidOption(at, e.message ?: "invalid value $decimals")
    }
}

/** CSV options, every absent field read off [HttpDefaults.csv]. */
fun CsvOptionsDto?.toCsvOptions(at: String = "/output/csv"): CsvOptions {
    val d = HttpDefaults.csv
    if (this == null) return d
    val sep =
        separator?.let {
            // WASI takes the first character silently; a door that can say no, says no.
            if (it.length != 1) throw invalidOption("$at/separator", "must be exactly one character, was \"$it\"")
            it.single()
        }
    if (lineSeparator != null && lineSeparator.isEmpty()) throw invalidOption("$at/lineSeparator", "must not be empty")
    checkDecimals(decimals, "$at/decimals")
    return d.copy(
        fields = fields?.toFields("$at/fields") ?: d.fields,
        separator = sep ?: d.separator,
        unitsInHeader = unitsInHeader ?: d.unitsInHeader,
        decimals = decimals ?: d.decimals,
        lineSeparator = lineSeparator ?: d.lineSeparator,
    )
}

/** Column-JSON options, every absent field read off [HttpDefaults.json]. */
fun JsonOptionsDto?.toJsonOptions(at: String = "/output/json"): JsonOptions {
    val d = HttpDefaults.json
    if (this == null) return d
    checkDecimals(decimals, "$at/decimals")
    return d.copy(
        fields = fields?.toFields("$at/fields") ?: d.fields,
        pretty = pretty ?: d.pretty,
        decimals = decimals ?: d.decimals,
        includeMeta = includeMeta ?: d.includeMeta,
    )
}

/** GPX writer settings; absent fields from [HttpDefaults]. The track names stay `null` when not given. */
fun GpxWriteOptionsDto?.toGpxWrite(at: String = "/output/gpx"): GpxWriteSettings {
    val dto = this ?: GpxWriteOptionsDto()
    if (dto.trackName != null && dto.trackNames != null) {
        throw invalidOption("$at/trackNames", "give trackName or trackNames, not both")
    }
    val powerSource =
        dto.powerSource?.let { v ->
            // fromWire is case-sensitive and the wire names are lowercase: lowercase first, as the CLI.
            GpxPowerSource.fromWire(v.lowercase())
                ?: throw invalidOption(
                    "$at/powerSource",
                    "unknown value '$v', expected one of ${GpxPowerSource.wireNames.joinToString(", ")}",
                )
        }
    return GpxWriteSettings(
        writeExtensions = dto.writeExtensions ?: HttpDefaults.GPX_WRITE_EXTENSIONS,
        startTime = dto.startTimeEpochMs?.let(Instant::fromEpochMilliseconds),
        trackName = dto.trackName,
        trackNames = dto.trackNames,
        powerSource = powerSource ?: HttpDefaults.gpxPowerSource,
        includeWaypoints = dto.includeWaypoints ?: HttpDefaults.GPX_INCLUDE_WAYPOINTS,
    )
}

/**
 * FIT settings. `startTimeEpochMs` is required: a path's clock is relative and FIT has none.
 *
 * @throws ApiProblem `missing-start-time`, `invalid-option`
 */
fun FitOptionsDto?.toFit(at: String = "/output/fit"): FitSettings {
    val start =
        this?.startTimeEpochMs
            ?: throw ApiProblem(
                ProblemKind.MISSING_START_TIME,
                "format=fit needs $at/startTimeEpochMs: FIT has no relative clock",
                errors = listOf(FieldError("$at/startTimeEpochMs", "required for format=fit")),
            )
    val gap = interPathGapMs ?: HttpDefaults.FIT_INTER_PATH_GAP_MS
    if (gap < 0) throw invalidOption("$at/interPathGapMs", "must be >= 0, was $gap")
    return FitSettings(
        name = name ?: HttpDefaults.TRACK_NAME,
        startTime = Instant.fromEpochMilliseconds(start),
        interPathGap = gap.milliseconds,
    )
}

// ── Inverse mappers, for `GET /defaults` only ──────────────────────────────────────────────────

/** The DTO [toCsvOptions] reads back into these options. */
fun CsvOptions.toDto(): CsvOptionsDto =
    CsvOptionsDto(
        separator = separator.toString(),
        unitsInHeader = unitsInHeader,
        decimals = decimals,
        lineSeparator = lineSeparator,
        fields = fields?.map { it.prop },
    )

/** The DTO [toJsonOptions] reads back into these options. */
fun JsonOptions.toDto(): JsonOptionsDto =
    JsonOptionsDto(
        pretty = pretty,
        decimals = decimals,
        includeMeta = includeMeta,
        fields = fields?.map { it.prop },
    )

/** The GPX writer defaults, `trackName` being the one `/enhance` applies. */
fun gpxWriteDefaultsDto(): GpxWriteOptionsDto =
    GpxWriteOptionsDto(
        writeExtensions = HttpDefaults.GPX_WRITE_EXTENSIONS,
        startTimeEpochMs = null,
        trackName = HttpDefaults.TRACK_NAME,
        trackNames = null,
        powerSource = HttpDefaults.gpxPowerSource.wireName,
        includeWaypoints = HttpDefaults.GPX_INCLUDE_WAYPOINTS,
    )

/** The FIT defaults; `startTimeEpochMs` has none — it is required. */
fun fitDefaultsDto(): FitOptionsDto =
    FitOptionsDto(
        name = HttpDefaults.TRACK_NAME,
        startTimeEpochMs = null,
        interPathGapMs = HttpDefaults.FIT_INTER_PATH_GAP_MS,
    )
