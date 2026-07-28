package io.github.glandais.elevation

/**
 * Decodes a lowercase hex string into bytes — how the binary test fixtures are written down.
 *
 * Hex rather than base64 because there is no base64 API in the common stdlib that is stable
 * across the Kotlin version this project pins, and rather than a `byteArrayOf(…)` literal
 * because 70 bytes of `0x7F.toByte(),` per line buries the fixture in punctuation.
 */
fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have an even length, was ${hex.length}" }
    return ByteArray(hex.length / 2) { i ->
        val hi = hexDigit(hex[i * 2])
        val lo = hexDigit(hex[i * 2 + 1])
        ((hi shl 4) or lo).toByte()
    }
}

private fun hexDigit(c: Char): Int =
    when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> throw IllegalArgumentException("not a hex digit: '$c'")
    }
