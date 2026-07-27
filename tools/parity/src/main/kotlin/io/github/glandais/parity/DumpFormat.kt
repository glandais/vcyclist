package io.github.glandais.parity

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared on-disk dump format for TS <-> Kotlin numeric parity.
 *
 * Byte-compatible with `tools/parity/ts/dumpFormat.ts` — see that file for the format
 * description. Two files per stage :
 *
 * - `<nn>-<stage>.f64` : raw IEEE-754 binary64, little-endian, row-major,
 *   `value(i, f)` at byte offset `(i * fieldCount + f) * 8`.
 * - `<nn>-<stage>.json` : header (stage, size, field order, path aggregates).
 *
 * Binary rather than text so no decimal formatting sits between the computed double and
 * the comparison — a difference in the report is always a difference in the value.
 */
object DumpFormat {
    /** Field order == [PointField] ordinal, identical to the TS `POINT_FIELDS` order. */
    val FIELD_NAMES: List<String> = PointField.entries.map { it.prop }

    /**
     * Write one pipeline stage of [path] into [outDir].
     *
     * @param index zero-based stage counter (prefixes the filename so stages sort in order)
     * @param timeOrigin epoch offset subtracted from every timestamp at parse time
     */
    fun writeStage(
        outDir: File,
        index: Int,
        stage: String,
        path: Path,
        timeOrigin: Double,
    ) {
        outDir.mkdirs()
        val stem = "%02d-%s".format(index, stage)
        val fieldCount = PointField.COUNT

        // Chunked so a 100 k-point stage never allocates a 300 MB array.
        val pointsPerChunk = 4096
        val buffer =
            ByteBuffer
                .allocate(pointsPerChunk * fieldCount * 8)
                .order(ByteOrder.LITTLE_ENDIAN)

        BufferedOutputStream(File(outDir, "$stem.f64").outputStream()).use { out ->
            var start = 0
            while (start < path.size) {
                val count = minOf(pointsPerChunk, path.size - start)
                buffer.clear()
                for (i in start until start + count) {
                    for (field in PointField.entries) {
                        buffer.putDouble(path.get(i, field))
                    }
                }
                out.write(buffer.array(), 0, count * fieldCount * 8)
                start += count
            }
        }

        val durationMs =
            if (path.size >= 2) path.time(path.size - 1) - path.time(0) else 0.0
        val fieldsJson = FIELD_NAMES.joinToString(",\n    ") { "\"$it\"" }
        File(outDir, "$stem.json").writeText(
            """
            {
              "stage": "$stage",
              "index": $index,
              "size": ${path.size},
              "fieldCount": $fieldCount,
              "fields": [
                $fieldsJson
              ],
              "totalDistance": ${json(path.totalDistance)},
              "durationMs": ${json(durationMs)},
              "elevationGain": ${json(path.elevationGain)},
              "elevationLoss": ${json(path.elevationLoss)},
              "minElevation": ${json(if (path.size == 0) 0.0 else path.minElevation)},
              "maxElevation": ${json(if (path.size == 0) 0.0 else path.maxElevation)},
              "timeOrigin": ${json(timeOrigin)}
            }
            """.trimIndent() + "\n",
        )
    }

    /**
     * JSON-safe rendering of a double. `Double.toString` is round-trip exact on the JVM, but
     * JSON has no literal for the non-finite values, so those degrade to `null` rather than
     * producing a file no parser will accept.
     */
    fun json(v: Double): String = if (v.isFinite()) v.toString() else "null"
}
