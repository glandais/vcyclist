package io.github.glandais.engine.wasi

/**
 * A small JSON reader and writer, written by hand rather than pulled from kotlinx-serialization.
 *
 * That is a **measured** decision, not a reflex (task w03): adding
 * `kotlinx-serialization-json:1.9.0` and one four-field `@Serializable` class took the optimized
 * `.wasm` from 148 904 to 281 030 bytes — **+89 %** for an options object, well past the ~50 %
 * threshold `docs/PLAN-WASM-WASI.md` set for choosing the library. That cost is the serialization
 * machinery itself and only amortises over many classes; this ABI has a handful of flat option
 * objects and emits column arrays, so it never would.
 *
 * The scope is deliberately narrow — enough for the ABI, nothing more:
 *
 * - **Reading**: objects, arrays, strings, numbers, `true` / `false` / `null`. No streaming, no
 *   comments, no big integers. Inputs are option objects a host wrote on purpose, not arbitrary
 *   documents off the network.
 * - **Writing**: string concatenation through the helpers below, since every output shape here
 *   is known at compile time.
 *
 * Non-finite doubles are written as `null`, because JSON has no `NaN` and no `Infinity`. That
 * matters in practice: `dominantHeadwindAzimuthDeg` returns `NaN` for a symmetric loop, and a
 * host parsing `NaN` would choke on a token no standard parser accepts.
 */
internal sealed interface JsonValue

internal class JsonObj(
    val fields: Map<String, JsonValue>,
) : JsonValue

internal class JsonArr(
    val items: List<JsonValue>,
) : JsonValue

internal class JsonStr(
    val value: String,
) : JsonValue

internal class JsonNum(
    val value: Double,
) : JsonValue

internal class JsonBool(
    val value: Boolean,
) : JsonValue

internal data object JsonNull : JsonValue

// ── Reading ──────────────────────────────────────────────────────────────────────────────────

/**
 * Parse [text] as a JSON object. Anything else — an array, a bare scalar, trailing junk — is an
 * [IllegalArgumentException], which the exports map to `ERR_INVALID_ARGUMENT`.
 */
internal fun parseJsonObject(text: String): JsonObj {
    val parser = MiniJsonParser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    require(parser.atEnd()) { "trailing content after the JSON object at offset ${parser.offset}" }
    require(value is JsonObj) { "expected a JSON object, got ${value::class.simpleName}" }
    return value
}

private class MiniJsonParser(
    private val text: String,
) {
    var offset = 0
        private set

    fun atEnd(): Boolean = offset >= text.length

    fun skipWhitespace() {
        while (offset < text.length && text[offset].isWhitespace()) offset++
    }

    fun parseValue(): JsonValue {
        skipWhitespace()
        require(!atEnd()) { "unexpected end of JSON input" }
        return when (val c = text[offset]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> JsonStr(parseString())
            't' -> literal("true", JsonBool(true))
            'f' -> literal("false", JsonBool(false))
            'n' -> literal("null", JsonNull)
            else -> {
                require(c == '-' || c.isDigit()) { "unexpected character '$c' at offset $offset" }
                parseNumber()
            }
        }
    }

    private fun literal(
        text_: String,
        value: JsonValue,
    ): JsonValue {
        require(text.startsWith(text_, offset)) { "invalid literal at offset $offset" }
        offset += text_.length
        return value
    }

    private fun parseObject(): JsonObj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            offset++
            return JsonObj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> Unit
                '}' -> return JsonObj(fields)
                else -> throw IllegalArgumentException("expected ',' or '}' at offset ${offset - 1}, got '$c'")
            }
        }
    }

    private fun parseArray(): JsonArr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            offset++
            return JsonArr(items)
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> Unit
                ']' -> return JsonArr(items)
                else -> throw IllegalArgumentException("expected ',' or ']' at offset ${offset - 1}, got '$c'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            when (c) {
                '"' -> return sb.toString()
                '\\' ->
                    when (val esc = next()) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            require(offset + 4 <= text.length) { "truncated \\u escape at offset $offset" }
                            sb.append(text.substring(offset, offset + 4).toInt(16).toChar())
                            offset += 4
                        }
                        else -> throw IllegalArgumentException("invalid escape '\\$esc' at offset ${offset - 1}")
                    }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): JsonNum {
        val start = offset
        if (peek() == '-') offset++
        while (!atEnd() && (text[offset].isDigit() || text[offset] in ".eE+-")) offset++
        val raw = text.substring(start, offset)
        val value = raw.toDoubleOrNull() ?: throw IllegalArgumentException("invalid number '$raw' at offset $start")
        return JsonNum(value)
    }

    private fun peek(): Char? = if (atEnd()) null else text[offset]

    private fun next(): Char {
        require(!atEnd()) { "unexpected end of JSON input" }
        return text[offset++]
    }

    private fun expect(c: Char) {
        val got = next()
        require(got == c) { "expected '$c' at offset ${offset - 1}, got '$got'" }
    }
}

// ── Typed access, with the strictness the ABI promises ────────────────────────────────────────

/**
 * Fail unless every key of this object is in [allowed].
 *
 * A silently ignored key is the failure mode this ABI most wants to avoid: a host that writes
 * `cyclistWeight` instead of `massKg` would otherwise get a plausible simulation run with the
 * default rider, and no way to notice. The message names the offender **and** what was expected,
 * because a host debugging through a numeric sentinel has nothing else to go on.
 */
internal fun JsonObj.requireOnly(allowed: Set<String>) {
    val unknown = fields.keys.filterNot { it in allowed }
    require(unknown.isEmpty()) {
        "unknown option(s) ${unknown.joinToString()} — expected one of ${allowed.sorted().joinToString()}"
    }
}

/** The `Boolean` at [key], or [fallback] when the key is absent or `null`. */
internal fun JsonObj.bool(
    key: String,
    fallback: Boolean,
): Boolean =
    when (val v = fields[key]) {
        null, JsonNull -> fallback
        is JsonBool -> v.value
        else -> throw IllegalArgumentException("option '$key' must be a boolean")
    }

/** The `Double` at [key], or [fallback] when the key is absent or `null`. */
internal fun JsonObj.double(
    key: String,
    fallback: Double,
): Double =
    when (val v = fields[key]) {
        null, JsonNull -> fallback
        is JsonNum -> v.value
        else -> throw IllegalArgumentException("option '$key' must be a number")
    }

/** The `String` at [key], or [fallback] when the key is absent or `null`. */
internal fun JsonObj.string(
    key: String,
    fallback: String,
): String =
    when (val v = fields[key]) {
        null, JsonNull -> fallback
        is JsonStr -> v.value
        else -> throw IllegalArgumentException("option '$key' must be a string")
    }

/** The nested object at [key], or `null` when the key is absent or `null`. */
internal fun JsonObj.obj(key: String): JsonObj? =
    when (val v = fields[key]) {
        null, JsonNull -> null
        is JsonObj -> v
        else -> throw IllegalArgumentException("option '$key' must be an object")
    }

// ── Writing ──────────────────────────────────────────────────────────────────────────────────

/** Encode [value] as a JSON string literal, escaping what RFC 8259 requires. */
internal fun jsonString(value: String): String {
    val sb = StringBuilder(value.length + 2)
    sb.append('"')
    for (c in value) {
        when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

/** Encode [value], or `null` when it is not finite — JSON knows neither `NaN` nor `Infinity`. */
internal fun jsonNumber(value: Double): String = if (value.isFinite()) value.toString() else "null"

/** Assemble `{"k":v,…}` from already-encoded values, dropping nothing and quoting the keys. */
internal fun jsonObject(vararg entries: Pair<String, String>): String =
    entries.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}:$v" }

/** Assemble `[…]` from already-encoded values. */
internal fun jsonArray(items: List<String>): String = items.joinToString(",", "[", "]")
