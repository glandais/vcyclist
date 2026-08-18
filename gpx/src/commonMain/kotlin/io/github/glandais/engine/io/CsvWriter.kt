package io.github.glandais.engine.io

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField

/**
 * Options for [CsvWriter.write].
 *
 * @property fields columns to export, in order. `null` = every [PointField], in
 *   [PointField.entries] order (the 38-field default).
 * @property separator field separator. Not restricted to `,` : `;` is the common choice for
 *   spreadsheets configured for a French locale.
 * @property unitsInHeader append the unit in parentheses to each header cell, e.g.
 *   `elevation (meters)`. `false` → bare `elevation`.
 * @property decimals fractional digits for every numeric cell. `null` = shortest representation
 *   that round-trips (see [CsvNumberFormat]).
 * @property lineSeparator row terminator. `"\n"` by default ; use `"\r\n"` for Excel on Windows.
 */
data class CsvOptions(
    val fields: List<PointField>? = null,
    val separator: Char = ',',
    val unitsInHeader: Boolean = true,
    val decimals: Int? = null,
    val lineSeparator: String = "\n",
)

/**
 * CSV serialization of a [Path] : one header row, then one row per point.
 *
 * Headers and values come straight from [PointField], which is already the single source of
 * truth for a field's name, unit and category — no separate key/unit/converter framework.
 *
 * `commonMain`, no file I/O : this renders a `String`. Writing it to disk (JVM `:cli`, task g17)
 * or offering it as a browser download (`Blob` + `URL.createObjectURL`) is the caller's job.
 */
object CsvWriter {
    fun write(
        path: Path,
        options: CsvOptions = CsvOptions(),
    ): String {
        val fields = options.fields ?: PointField.entries
        val sb = StringBuilder()

        appendRow(sb, fields.map { headerFor(it, options.unitsInHeader) }, options)
        for (i in 0 until path.size) {
            sb.append(options.lineSeparator)
            appendRow(sb, fields.map { CsvNumberFormat.format(path.get(i, it), options.decimals) }, options)
        }
        return sb.toString()
    }

    private fun headerFor(
        field: PointField,
        unitsInHeader: Boolean,
    ): String = if (unitsInHeader) "${field.prop} (${field.unit})" else field.prop

    private fun appendRow(
        sb: StringBuilder,
        cells: List<String>,
        options: CsvOptions,
    ) {
        for ((i, cell) in cells.withIndex()) {
            if (i > 0) sb.append(options.separator)
            sb.append(escapeCsvCell(cell, options.separator))
        }
    }

    /**
     * RFC 4180 escaping : wrap [cell] in double quotes (doubling any embedded quote) if it
     * contains the [separator], a double quote, or a line break. Header cells never contain the
     * default `,` separator, but a caller-chosen `separator` (e.g. `;`) could collide with
     * punctuation in [PointField.unit] or [PointField.prop] — the check is cheap, so it always
     * runs rather than being skipped as "shouldn't happen".
     */
    private fun escapeCsvCell(
        cell: String,
        separator: Char,
    ): String {
        val needsQuoting = cell.any { it == separator || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) {
            "\"${cell.replace("\"", "\"\"")}\""
        } else {
            cell
        }
    }
}
