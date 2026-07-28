@file:JvmName("TabularWritersJvm")

package io.github.glandais.engine.io

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField

/**
 * Java-callable form of [CsvWriter] and [JsonWriter], and of their option objects (task g27).
 * See `GpxWriterJvm` for why these facades exist.
 *
 * The option classes need a factory of their own: `@JvmOverloads` on a `data class` constructor
 * is not available here (the class lives in `commonMain`), and even where it is, it never covers
 * `copy()`. Without a factory, a Java caller who wants "CSV with a semicolon separator" has to
 * spell out all five fields.
 */
@JvmOverloads
fun writeCsv(
    path: Path,
    options: CsvOptions = CsvOptions(),
): String = CsvWriter.write(path, options)

@JvmOverloads
fun writeJson(
    path: Path,
    options: JsonOptions = JsonOptions(),
): String = JsonWriter.write(path, options)

@JvmOverloads
fun csvOptions(
    fields: List<PointField>? = null,
    separator: Char = ',',
    unitsInHeader: Boolean = true,
    decimals: Int? = null,
    lineSeparator: String = "\n",
): CsvOptions = CsvOptions(fields, separator, unitsInHeader, decimals, lineSeparator)

@JvmOverloads
fun jsonOptions(
    fields: List<PointField>? = null,
    pretty: Boolean = false,
    decimals: Int? = null,
    includeMeta: Boolean = true,
): JsonOptions = JsonOptions(fields, pretty, decimals, includeMeta)
