package io.github.glandais.engine.io

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField

/**
 * Options for [JsonWriter.write].
 *
 * @property fields columns to export, in order. `null` = every [PointField], in
 *   [PointField.entries] order (the 38-field default).
 * @property pretty indentation for human readability. `false` (default) = compact, single line,
 *   which minimises transfer size for a browser round-trip.
 * @property decimals fractional digits for every numeric value. `null` = shortest representation
 *   that round-trips (see [CsvNumberFormat]).
 * @property includeMeta include the `meta` block (aggregates + per-field units).
 */
data class JsonOptions(
    val fields: List<PointField>? = null,
    val pretty: Boolean = false,
    val decimals: Int? = null,
    val includeMeta: Boolean = true,
)

/**
 * JSON serialization of a [Path], **column-oriented** : one array per field rather than one
 * object per point.
 *
 * The column orientation is deliberate — see the "Steps" section of task g07. The obvious
 * alternative, one object per point (`points: [{distance: 0.0, elevation: 120.4, ...}, ...]`),
 * repeats every field name once per point. A chart library consumes series, not points : the
 * column-oriented shape below avoids
 * repeating up to 38 field names per point (~3x smaller) and maps directly onto a Chart.js
 * dataset (`{label: "elevation", data: fields.elevation}`).
 *
 * `commonMain`, no `kotlinx-serialization` : the document has a fixed, simple shape, so a
 * hand-rolled `StringBuilder` is enough and avoids pulling `kotlinx-serialization-json` onto 4
 * targets for this alone. Numeric formatting is shared with [CsvWriter] via [CsvNumberFormat] so
 * the two writers never disagree on how e.g. `1.0` is rendered.
 *
 * `NaN` and infinities are not valid JSON — [CsvWriter] renders a missing value as an empty CSV
 * cell, this writer renders it as JSON `null`, which is the same "value absent" semantic and is
 * what `Chart.js` expects to treat a series point as a gap.
 */
object JsonWriter {
    fun write(
        path: Path,
        options: JsonOptions = JsonOptions(),
    ): String {
        val fields = options.fields ?: PointField.entries
        val sb = StringBuilder()
        val indent = if (options.pretty) "  " else ""
        val nl = if (options.pretty) "\n" else ""
        val colon = if (options.pretty) ": " else ":"

        sb.append('{').append(nl)
        appendKeyValueRaw(sb, indent, "size", path.size.toString(), colon)
        sb.append(',').append(nl)

        if (options.includeMeta) {
            appendMeta(sb, path, fields, options.decimals, indent, nl, colon)
            sb.append(',').append(nl)
        }

        appendKeyRaw(sb, indent, "fields", colon)
        appendFields(sb, path, fields, options, indent, nl, colon)
        sb.append(nl).append('}')
        return sb.toString()
    }

    private fun appendMeta(
        sb: StringBuilder,
        path: Path,
        fields: List<PointField>,
        decimals: Int?,
        indent: String,
        nl: String,
        colon: String,
    ) {
        val inner = indent + indent
        appendKeyRaw(sb, indent, "meta", colon)
        sb.append('{').append(nl)
        appendKeyValueRaw(sb, inner, "totalDistance", numberOrNull(path.totalDistance, decimals), colon)
        sb.append(',').append(nl)
        appendKeyValueRaw(sb, inner, "durationMs", numberOrNull(path.durationMs, decimals), colon)
        sb.append(',').append(nl)
        appendKeyValueRaw(sb, inner, "elevationGain", numberOrNull(path.elevationGain, decimals), colon)
        sb.append(',').append(nl)
        appendKeyValueRaw(sb, inner, "elevationLoss", numberOrNull(path.elevationLoss, decimals), colon)
        sb.append(',').append(nl)
        appendKeyRaw(sb, inner, "units", colon)
        sb.append('{').append(nl)
        val innerUnits = inner + indent
        for ((i, field) in fields.withIndex()) {
            appendKeyValueRaw(sb, innerUnits, escapeJsonString(field.prop), "\"${escapeJsonString(field.unit)}\"", colon)
            if (i != fields.lastIndex) sb.append(',')
            sb.append(nl)
        }
        sb.append(inner).append('}').append(nl)
        sb.append(indent).append('}')
    }

    private fun appendFields(
        sb: StringBuilder,
        path: Path,
        fields: List<PointField>,
        options: JsonOptions,
        indent: String,
        nl: String,
        colon: String,
    ) {
        sb.append('{').append(nl)
        val inner = indent + indent
        for ((fi, field) in fields.withIndex()) {
            appendKeyRaw(sb, inner, escapeJsonString(field.prop), colon)
            sb.append('[')
            for (i in 0 until path.size) {
                if (i > 0) sb.append(',')
                sb.append(numberOrNull(path.get(i, field), options.decimals))
            }
            sb.append(']')
            if (fi != fields.lastIndex) sb.append(',')
            sb.append(nl)
        }
        sb.append(indent).append('}')
    }

    /** Renders [value] as a JSON number token, or `null` for `NaN` / infinities (invalid JSON). */
    private fun numberOrNull(
        value: Double,
        decimals: Int? = null,
    ): String =
        if (value.isNaN() || value.isInfinite()) {
            "null"
        } else {
            CsvNumberFormat.format(value, decimals)
        }

    private fun appendKeyRaw(
        sb: StringBuilder,
        indent: String,
        key: String,
        colon: String,
    ) {
        sb
            .append(indent)
            .append('"')
            .append(key)
            .append('"')
            .append(colon)
    }

    private fun appendKeyValueRaw(
        sb: StringBuilder,
        indent: String,
        key: String,
        rawValue: String,
        colon: String,
    ) {
        appendKeyRaw(sb, indent, key, colon)
        sb.append(rawValue)
    }

    /**
     * Escapes a string for embedding between JSON double quotes : quotes, backslashes, controls.
     *
     * `internal`, not `private` : [PointField.prop] / [PointField.unit] never contain characters
     * requiring escaping today, so this is exercised directly by [io.github.glandais.engine.io.JsonWriterTest]
     * rather than indirectly through [write].
     */
    internal fun escapeJsonString(value: String): String {
        val sb = StringBuilder(value.length)
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else ->
                    if (c.code < 0x20) {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
            }
        }
        return sb.toString()
    }
}
