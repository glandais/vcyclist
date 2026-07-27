package io.github.glandais.engine.gpx

/**
 * Result of [GpxXmlRepair.repairVerbose] : the (possibly) repaired XML, plus a human-readable
 * trace of every repair actually applied (empty when the input needed nothing).
 */
data class RepairResult(
    val xml: String,
    val repairs: List<String>,
)

/**
 * Cleans up raw GPX XML text **before** parsing, to rescue files produced by approximate GPS
 * devices or exporters. Every repair is independent and a no-op on an already-valid document:
 * `repair(validXml) == validXml` and `repair(repair(x)) == repair(x)` both hold.
 *
 * Loosely inspired by `io.github.glandais.gpx.io.read.GpxXmlRepair` (gpx2web, 131 lines), but
 * **not a literal port** : the Java version works on raw `byte[]` and repairs a wrong-charset
 * decoding (bytes that are valid ISO-8859-1 but were meant to be read as UTF-8) by re-decoding
 * the bytes. `vcyclist`'s [GpxParser.parse] entry point takes an already-decoded [String] — by
 * the time we see it, a wrong-charset decode has already lost information (invalid UTF-8 byte
 * sequences become `U+FFFD` in a strict decoder) and cannot be reconstructed from the String
 * alone. Attempting the same trick at the String level (checking whether every char fits in a
 * byte, then re-decoding as UTF-8) was considered and rejected : it risks silently corrupting a
 * *correctly* decoded file whose accented Latin-1 characters happen to align into a
 * syntactically valid UTF-8 sequence — a false positive with no error message, which is worse
 * than the status quo. See the g04 task's "Résultat" section for the full inventory this
 * decision is based on.
 *
 * What *does* transfer cleanly to a String-based API, and is kept from the Java source:
 * recombining a UTF-16 surrogate pair written as two adjacent numeric character references
 * (`&#55358;&#56600;`) into the single supplementary-plane reference XML 1.0 actually allows.
 * The other two repairs below are new, chosen from the task's own "attendus typiques" list —
 * they target exactly the malformed-device-export symptoms this task exists for, and are safe
 * because each fixes syntax that is *always* illegal XML 1.0, never a legitimate character.
 *
 * Implementation note: deliberately scans with hand-written index loops rather than [Regex].
 * A GPX file can be several MB (one line per point, no line breaks) ; a backtracking regex
 * engine invoked per-character is a needless cost on the common case (a valid file, repaired
 * 0 times) and a possible pathological-input hazard on the broken case.
 */
object GpxXmlRepair {
    private val PREDEFINED_ENTITIES = setOf("amp", "lt", "gt", "apos", "quot")

    /** Max characters scanned between `&` and a candidate `;` before giving up on a reference. */
    private const val MAX_REFERENCE_BODY_LENGTH = 20

    fun repair(xml: String): String = repairVerbose(xml).xml

    /** Variante instrumentée : renvoie aussi la liste des réparations appliquées. */
    fun repairVerbose(xml: String): RepairResult {
        val repairs = mutableListOf<String>()

        var current = xml
        stripBom(current)?.let {
            current = it
            repairs += "removed leading UTF-8 BOM (U+FEFF)"
        }
        stripIllegalControlChars(current)?.let { (repairedXml, count) ->
            current = repairedXml
            repairs += "removed $count illegal XML 1.0 control character(s)"
        }
        recombineSurrogatePairReferences(current)?.let { (repairedXml, count) ->
            current = repairedXml
            repairs += "recombined $count surrogate-pair numeric character reference(s)"
        }
        escapeBareAmpersands(current)?.let { (repairedXml, count) ->
            current = repairedXml
            repairs += "escaped $count bare '&' character(s)"
        }

        return RepairResult(current, repairs)
    }

    /** A leading byte-order-mark survives naive byte->String decoding and breaks strict parsers
     * expecting the very first character to start `<?xml` or `<gpx`. */
    private fun stripBom(xml: String): String? = if (xml.isNotEmpty() && xml[0] == '\uFEFF') xml.substring(1) else null

    /**
     * XML 1.0 forbids most C0 control characters outside `\t \n \r`, and the whole surrogate
     * range outside of valid pairs. Buggy firmware occasionally embeds a stray NUL byte or
     * similar in a `<name>`/`<desc>`, which a strict parser rejects outright. Valid surrogate
     * pairs (real astral characters, e.g. emoji in a track name) are recognised and kept intact.
     */
    private fun stripIllegalControlChars(xml: String): Pair<String, Int>? {
        var count = 0
        var sb: StringBuilder? = null
        var i = 0
        val n = xml.length
        while (i < n) {
            val c = xml[i]
            if (c.isHighSurrogate() && i + 1 < n && xml[i + 1].isLowSurrogate()) {
                sb?.append(c)?.append(xml[i + 1])
                i += 2
                continue
            }
            val code = c.code
            val allowed = code == 0x9 || code == 0xA || code == 0xD || code in 0x20..0xD7FF || code in 0xE000..0xFFFD
            if (allowed) {
                sb?.append(c)
            } else {
                if (sb == null) sb = StringBuilder(xml.length).append(xml, 0, i)
                count++
            }
            i++
        }
        return if (count > 0) Pair(sb.toString(), count) else null
    }

    /**
     * A GPS export occasionally writes a literal `&` (e.g. `Café & Croissant` in a `<name>`)
     * instead of `&amp;`. Escapes any `&` that does not start a syntactically valid character or
     * predefined entity reference — leaving `&#233;`, `&amp;`, `&lt;`, etc. untouched.
     */
    private fun escapeBareAmpersands(xml: String): Pair<String, Int>? {
        var count = 0
        var sb: StringBuilder? = null
        var i = 0
        val n = xml.length
        while (i < n) {
            val c = xml[i]
            if (c == '&') {
                if (findReferenceEnd(xml, i) != -1) {
                    sb?.append(c)
                } else {
                    if (sb == null) sb = StringBuilder(xml.length + 16).append(xml, 0, i)
                    sb.append("&amp;")
                    count++
                }
            } else {
                sb?.append(c)
            }
            i++
        }
        return if (count > 0) Pair(sb.toString(), count) else null
    }

    /**
     * Port of `GpxXmlRepair.recombineSurrogatePairs` (gpx2web) : scans references one by one
     * rather than matching pairs directly, so that in `&#65;&#55358;&#56600;` the real pair
     * (positions 2-3) is not missed because a two-at-a-time pattern already consumed the first
     * reference as a (mismatched) partner.
     */
    private fun recombineSurrogatePairReferences(xml: String): Pair<String, Int>? {
        val refs = findNumericReferences(xml)
        if (refs.size < 2) return null

        var count = 0
        val sb = StringBuilder(xml.length)
        var copiedUpTo = 0
        var i = 0
        while (i < refs.size) {
            val high = refs[i]
            val low = refs.getOrNull(i + 1)
            if (low != null && high.end == low.start && isHighSurrogate(high.value) && isLowSurrogate(low.value)) {
                sb.append(xml, copiedUpTo, high.start)
                val codePoint = 0x10000 + (high.value - 0xD800) * 0x400 + (low.value - 0xDC00)
                sb.append("&#").append(codePoint).append(';')
                copiedUpTo = low.end
                count++
                i += 2
            } else {
                i++
            }
        }
        if (count == 0) return null
        sb.append(xml, copiedUpTo, xml.length)
        return Pair(sb.toString(), count)
    }

    private data class NumericRef(
        val start: Int,
        val end: Int,
        val value: Int,
    )

    private fun findNumericReferences(xml: String): List<NumericRef> {
        val refs = mutableListOf<NumericRef>()
        var i = 0
        val n = xml.length
        while (i < n) {
            if (xml[i] == '&' && i + 1 < n && xml[i + 1] == '#') {
                val end = findReferenceEnd(xml, i)
                if (end != -1) {
                    val value = parseNumericReferenceValue(xml.substring(i + 2, end))
                    if (value != -1) refs.add(NumericRef(i, end + 1, value))
                    i = end + 1
                    continue
                }
            }
            i++
        }
        return refs
    }

    private fun parseNumericReferenceValue(body: String): Int =
        try {
            val hex = body.isNotEmpty() && (body[0] == 'x' || body[0] == 'X')
            val raw = (if (hex) body.substring(1) else body).toLong(if (hex) 16 else 10)
            if (raw in 0..0x10FFFF) raw.toInt() else -1
        } catch (e: NumberFormatException) {
            -1
        }

    private fun isHighSurrogate(codePoint: Int) = codePoint in 0xD800..0xDBFF

    private fun isLowSurrogate(codePoint: Int) = codePoint in 0xDC00..0xDFFF

    /**
     * @return the index of the terminating `;` of a well-formed char/entity reference starting
     *   at `xml[amp]` (which must be `&`), or -1 when none is found within
     *   [MAX_REFERENCE_BODY_LENGTH] characters.
     */
    private fun findReferenceEnd(
        xml: String,
        amp: Int,
    ): Int {
        val n = xml.length
        var j = amp + 1
        if (j >= n) return -1
        if (xml[j] == '#') {
            j++
            val hex = j < n && (xml[j] == 'x' || xml[j] == 'X')
            if (hex) j++
            val digitsStart = j
            while (j < n && j - digitsStart < MAX_REFERENCE_BODY_LENGTH && (if (hex) xml[j].isHexDigit() else xml[j].isDigit())) {
                j++
            }
            return if (j > digitsStart && j < n && xml[j] == ';') j else -1
        }
        val nameStart = j
        while (j < n && j - nameStart < MAX_REFERENCE_BODY_LENGTH && xml[j] != ';' && xml[j] != '&' && !xml[j].isWhitespace()) {
            j++
        }
        if (j >= n || xml[j] != ';') return -1
        return if (xml.substring(nameStart, j) in PREDEFINED_ENTITIES) j else -1
    }

    private fun Char.isHexDigit(): Boolean = isDigit() || this in 'a'..'f' || this in 'A'..'F'
}
