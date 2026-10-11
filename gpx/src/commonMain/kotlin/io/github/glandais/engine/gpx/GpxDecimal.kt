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
 * more (`45.832600000000014`), so the digits are shortened while they still read
 * back the same, which makes the text identical on every target.
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

        // Shorten while the text still reads back to `value` (a no-op on the JVM and JS).
        while (digits.length > 1) {
            val (shorter, shorterPoint) = round(digits, point, digits.length - 1)
            if (layout(negative, shorter, shorterPoint).toDouble() != value) break
            digits = shorter
            point = shorterPoint
        }
        return layout(negative, digits, point)
    }

    /** [digits] rounded half-up to [keep] significant digits, trailing zeros dropped. */
    private fun round(
        digits: String,
        point: Int,
        keep: Int,
    ): Pair<String, Int> {
        val kept = digits.substring(0, keep).toCharArray()
        if (digits[keep] >= '5') {
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
