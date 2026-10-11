package io.github.glandais.engine.gpx

/**
 * `Double` → `xsd:decimal` text, the type GPX 1.1 gives `lat`, `lon` and `ele`.
 *
 * `Double.toString()` is not usable as is: the JVM switches to scientific notation below `1e-3`
 * (`-5.0E-4`, a longitude ~35 m from Greenwich), Kotlin/JS below `1e-6` (`1e-7`), and an exponent
 * is not an `xsd:decimal`. The two also disagree on integers (`100.0` on the JVM, `100` on JS).
 *
 * `CsvNumberFormat` already rolls a plain formatter, but it caps the fraction at 12 digits and
 * pads with zeros when that is not enough to round-trip, which a degree converted from radians
 * routinely needs (`45.83260000000001`). So this one starts from the platform's digits and only
 * rewrites their layout: exponent expanded, no trailing `.0`, `-0` written `0`. The JVM and JS
 * already print the **shortest** digits that read back to the same `Double`; Kotlin/Wasm can print
 * more (`45.832600000000014`), so the digits are shortened while they still read back the same.
 *
 * "Read back" is checked with [exactValue], not `String.toDouble()`: Kotlin/Wasm's parser is off by
 * one ULP on some 16-17 digit inputs, and trusting it shortened `7.2489838096998644` to
 * `7.248983809699864`, which every correct reader parses as the neighbouring `Double`. The exact
 * check covers up to 2^53 significant units scaled by at most 10^22 — every coordinate, elevation,
 * power or temperature; outside it the platform's own digits are kept. So the text always reads
 * back exactly, and is the same on every target for those values; at extreme magnitudes, or when
 * two equally short digit strings both read back, Wasm may still pick a different last digit.
 */
internal object GpxDecimal {
    /** `NaN` and the infinities have no `xsd:decimal` form; they keep `toString()`, as before. */
    fun format(value: Double): String {
        if (!value.isFinite()) return value.toString()
        val text = value.toString()
        val negative = text.startsWith('-')
        val unsigned = if (negative) text.substring(1) else text
        val expAt = unsigned.indexOfFirst { it == 'e' || it == 'E' }
        val mantissa = if (expAt == -1) unsigned else unsigned.substring(0, expAt)
        val exponent = if (expAt == -1) 0 else unsigned.substring(expAt + 1).removePrefix("+").toInt()

        val dot = mantissa.indexOf('.')
        val allDigits = if (dot == -1) mantissa else mantissa.removeRange(dot, dot + 1)
        val leadingZeros = allDigits.indexOfFirst { it != '0' }
        if (leadingZeros == -1) return "0"
        var digits = allDigits.substring(leadingZeros).trimEnd('0')
        // Position of the decimal point within `digits`, once the exponent is applied.
        var point = (if (dot == -1) mantissa.length else dot) + exponent - leadingZeros

        // Shorten while the text still reads back to `value` (a no-op on the JVM and JS). The
        // nearest shorter neighbour first, then the other one: Wasm's extra digit can sit on the
        // far side of a half (`6000.1407253806065` for the shortest `6000.140725380606`).
        while (digits.length > 1) {
            val keep = digits.length - 1
            val up = digits[keep] >= '5'
            val shorter =
                round(digits, point, keep, up).takeIf { readsBack(it, negative, value) }
                    ?: round(digits, point, keep, !up).takeIf { readsBack(it, negative, value) }
                    ?: break
            digits = shorter.first
            point = shorter.second
        }
        return layout(negative, digits, point)
    }

    private fun readsBack(
        candidate: Pair<String, Int>,
        negative: Boolean,
        value: Double,
    ): Boolean {
        val readBack = exactValue(candidate.first, candidate.second) ?: return false
        return (if (negative) -readBack else readBack) == value
    }

    /**
     * The correctly rounded `Double` nearest to `0.digits × 10^point`, or `null` when it cannot be
     * computed exactly. Clinger's fast path: an integer below 2^53 and a power of ten up to 10^22
     * are both exact doubles, and IEEE 754 rounds their product or quotient correctly — which is
     * what a correct decimal reader returns, whatever the platform's `toDouble()` does.
     */
    private fun exactValue(
        digits: String,
        point: Int,
    ): Double? {
        if (digits.length > MAX_LONG_DIGITS) return null
        val significand = digits.toLong()
        if (significand > MAX_EXACT_INTEGER) return null
        val scale = point - digits.length
        return when {
            scale in 0..MAX_EXACT_POWER -> significand.toDouble() * POWERS_OF_TEN[scale]
            -scale in 1..MAX_EXACT_POWER -> significand.toDouble() / POWERS_OF_TEN[-scale]
            else -> null
        }
    }

    /** Below 10^18, so any digit string this long fits a `Long`. */
    private const val MAX_LONG_DIGITS = 18

    /** 2^53: every integer up to it is an exact `Double`. */
    private const val MAX_EXACT_INTEGER = 1L shl 53

    /** 10^22 is the largest power of ten that is an exact `Double`. */
    private const val MAX_EXACT_POWER = 22

    private val POWERS_OF_TEN =
        doubleArrayOf(
            1e0,
            1e1,
            1e2,
            1e3,
            1e4,
            1e5,
            1e6,
            1e7,
            1e8,
            1e9,
            1e10,
            1e11,
            1e12,
            1e13,
            1e14,
            1e15,
            1e16,
            1e17,
            1e18,
            1e19,
            1e20,
            1e21,
            1e22,
        )

    /** [digits] cut to [keep] significant digits, rounded [up] or down, trailing zeros dropped. */
    private fun round(
        digits: String,
        point: Int,
        keep: Int,
        up: Boolean,
    ): Pair<String, Int> {
        val kept = digits.substring(0, keep).toCharArray()
        if (up) {
            var i = keep - 1
            while (i >= 0 && kept[i] == '9') kept[i--] = '0'
            if (i < 0) return Pair("1", point + 1)
            kept[i] = kept[i] + 1
        }
        return Pair(kept.concatToString().trimEnd('0'), point)
    }

    private fun layout(
        negative: Boolean,
        digits: String,
        point: Int,
    ): String {
        val plain =
            when {
                point <= 0 -> "0." + "0".repeat(-point) + digits
                point >= digits.length -> digits + "0".repeat(point - digits.length)
                else -> digits.substring(0, point) + "." + digits.substring(point)
            }
        return if (negative) "-$plain" else plain
    }
}
