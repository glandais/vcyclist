package io.github.glandais.api.dto

import io.ktor.openapi.JsonSchema
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How `format=gpx` writes the document — the keys of WASI's write-GPX options, plus `trackNames`
 * and `includeWaypoints`.
 */
@Serializable
@SerialName("GpxWriteOptions")
data class GpxWriteOptionsDto(
    @JsonSchema.Description(
        "Write `<extensions>` (power, heart rate, cadence, temperature, road width). `false` keeps plain " +
            "GPX 1.1. $SEE_DEFAULTS",
    )
    val writeExtensions: Boolean? = null,
    @JsonSchema.Description(
        "Instant of point 0, epoch milliseconds: every point gets an absolute `<time>`. Absent or null: times " +
            "are written as the path carries them.",
    )
    val startTimeEpochMs: Long? = null,
    @JsonSchema.Description(
        "`<trk><name>` of every track: `name`, `name-2`, `name-3`… when there are several. Exclusive with " +
            "`trackNames`. Absent or null: on `/enhance`, the default published by `GET /api/v1/defaults`; on " +
            "`/export`, the input's own names.",
    )
    val trackName: String? = null,
    @JsonSchema.Description(
        "One `<trk><name>` per written track, in order; its size must be the number of tracks (else 422 " +
            "`track-names-mismatch`). Exclusive with `trackName`.",
    )
    val trackNames: List<String>? = null,
    @JsonSchema.Description(
        "Which power `<power>` carries, one of `GET /api/v1/catalog` `gpxPowerSources`. $SEE_DEFAULTS",
    )
    val powerSource: String? = null,
    @JsonSchema.Description("Copy the input document's `<wpt>` waypoints. $SEE_DEFAULTS")
    val includeWaypoints: Boolean? = null,
)

/** How `format=csv` writes the single path — the keys of the JS / WASI CSV options, plus `fields`. */
@Serializable
@SerialName("CsvOptions")
data class CsvOptionsDto(
    @JsonSchema.Description("Field separator, exactly one character. $SEE_DEFAULTS")
    val separator: String? = null,
    @JsonSchema.Description("Append each column's unit to its header, `elevation (meters)`. $SEE_DEFAULTS")
    val unitsInHeader: Boolean? = null,
    @JsonSchema.Description("Fractional digits of every number. Absent or null: the shortest exact form.")
    val decimals: Int? = null,
    @JsonSchema.Description("Row terminator, not empty. $SEE_DEFAULTS")
    val lineSeparator: String? = null,
    @JsonSchema.Description(
        "Columns, in order, by `prop` of `GET /api/v1/fields`; not empty. Absent or null: every field.",
    )
    val fields: List<String>? = null,
)

/** How `format=json` writes the single path's column document — the keys of the JS / WASI options, plus `fields`. */
@Serializable
@SerialName("JsonOptions")
data class JsonOptionsDto(
    @JsonSchema.Description("Indent the document. $SEE_DEFAULTS")
    val pretty: Boolean? = null,
    @JsonSchema.Description("Fractional digits of every number. Absent or null: the shortest exact form.")
    val decimals: Int? = null,
    @JsonSchema.Description("Include the `meta` block (aggregates and units). $SEE_DEFAULTS")
    val includeMeta: Boolean? = null,
    @JsonSchema.Description(
        "Columns, in order, by `prop` of `GET /api/v1/fields`; not empty. Absent or null: every field.",
    )
    val fields: List<String>? = null,
)

/** How `format=fit` encodes the course — the keys of WASI's FIT options. */
@Serializable
@SerialName("FitOptions")
data class FitOptionsDto(
    @JsonSchema.Description("Course name. $SEE_DEFAULTS")
    val name: String? = null,
    @JsonSchema.Description(
        "Instant of the first point, epoch milliseconds. **Required** for `format=fit`: FIT has no relative " +
            "clock (else 422 `missing-start-time`).",
    )
    val startTimeEpochMs: Long? = null,
    @JsonSchema.Description("Clock shift between two consecutive paths, milliseconds, >= 0. $SEE_DEFAULTS")
    val interPathGapMs: Long? = null,
)

/**
 * The writer settings of `/enhance` and `/export`: `gpx`, `csv`, `json`, `fit`, one per format.
 * Only the member matching the resolved format is read; giving another one is 400
 * `invalid-option` — an accepted key that would not be used.
 *
 * The members carry no `@JsonSchema.Description`: a description on an object-typed property makes
 * Ktor emit a second copy of the schema (`CsvOptions2`…) instead of a `$ref`.
 */
@Serializable
@SerialName("Output")
data class OutputDto(
    val gpx: GpxWriteOptionsDto? = null,
    val csv: CsvOptionsDto? = null,
    val json: JsonOptionsDto? = null,
    val fit: FitOptionsDto? = null,
)

/** The `request` part of `POST /export`: only the writer settings, nothing is simulated. */
@Serializable
@SerialName("ExportRequest")
data class ExportRequest(
    val output: OutputDto? = null,
)
