package io.github.glandais.engine.io

import kotlin.math.abs
import kotlin.math.round

/**
 * Multiplatform-stable `Double` → decimal-string formatting.
 *
 * `Double.toString()` renders **differently** on JVM, Kotlin/JS and Kotlin/Wasm : `1.0` prints
 * as `"1.0"` on the JVM but `"1"` on JS, and all three switch to exponential notation at
 * different magnitude thresholds. A CSV (or JSON, see gpx2web task g07) read by a spreadsheet or
 * a human must never contain `1.234E-5` and must render the exact same string on every target —
 * so this object rolls its own formatter instead of delegating to the platform.
 *
 * `internal` (not `private` to `CsvWriter`) : g07's `JsonWriter` reuses it verbatim for the same
 * reason (see docs/tasks/g07-export-json.md).
 */
internal object CsvNumberFormat {
    /** Largest number of fractional digits [format] will ever try when [decimals] is `null`. */
    private const val MAX_AUTO_DECIMALS = 12

    private val pow10Long =
        LongArray(MAX_AUTO_DECIMALS + 1).also { arr ->
            var p = 1L
            for (i in arr.indices) {
                arr[i] = p
                p *= 10L
            }
        }

    /**
     * Format [value] as a plain (never exponential) decimal string using `.` as the decimal
     * separator, regardless of the current platform/locale.
     *
     * - `NaN` → `""` (empty string : vcyclist uses `NaN` as its "field absent" marker, and an
     *   empty CSV cell is what a spreadsheet expects for a missing value, not the literal `NaN`).
     * - [decimals] `null` → the shortest fixed-point representation that round-trips back to
     *   [value] (tried from 0 up to [MAX_AUTO_DECIMALS] fractional digits) ; this is our
     *   platform-independent analogue of "shortest round-tripping `toString`", picked instead of
     *   a fixed decimal count so integers stay `"1"` rather than `"1.000000000000"`.
     * - [decimals] non-null → exactly that many fractional digits, rounded half-away-from-zero.
     */
    fun format(
        value: Double,
        decimals: Int? = null,
    ): String {
        if (value.isNaN()) return ""
        if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
        return if (decimals != null) {
            require(decimals in 0..MAX_AUTO_DECIMALS) {
                "decimals must be in 0..$MAX_AUTO_DECIMALS, was $decimals"
            }
            formatFixed(value, decimals)
        } else {
            formatShortestRoundTrip(value)
        }
    }

    private fun formatShortestRoundTrip(value: Double): String {
        for (d in 0..MAX_AUTO_DECIMALS) {
            val candidate = formatFixed(value, d)
            if (candidate.toDouble() == value) return candidate
        }
        return formatFixed(value, MAX_AUTO_DECIMALS)
    }

    /** Fixed-point formatting with exactly [decimals] fractional digits, never exponential. */
    private fun formatFixed(
        value: Double,
        decimals: Int,
    ): String {
        val negative = value < 0.0
        val absValue = abs(value)
        val scale = pow10Long[decimals].toDouble()
        val scaledLong = round(absValue * scale).toLong()
        val whole = pow10Long[decimals]
        val intPart = scaledLong / whole
        val sign = if (negative && scaledLong != 0L) "-" else ""
        return if (decimals == 0) {
            "$sign$intPart"
        } else {
            val fracPart = (scaledLong % whole).toString().padStart(decimals, '0')
            "$sign$intPart.$fracPart"
        }
    }
}
