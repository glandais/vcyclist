package io.github.glandais.elevation.webp

/**
 * The VP8L chunk of a RIFF/WEBP container.
 *
 * A `.webp` file is a RIFF container: the ASCII tag `RIFF`, a little-endian 32-bit size covering
 * everything after that size field, the form type `WEBP`, then a sequence of chunks. Each chunk is
 * a four-character tag, a little-endian 32-bit payload size, the payload itself, and — the trap
 * that silently desynchronises a naive walker — **a single pad byte when that size is odd**, which
 * the size field does not count. `InlineWebpFixture` happens to carry an odd-sized VP8L chunk (37
 * bytes), so [RiffParserTest] would catch a walker that forgot the padding on the very first
 * fixture it reads.
 *
 * Only the lossless codec is in scope here (see `docs/archive/tasks/w11-vp8l-decoder.md`): Mapterhorn
 * serves Terrarium tiles as VP8L, and a lossy decoder would be several thousand lines for a codec
 * that would corrupt elevations anyway — Terrarium packs metres into the R/G/B bytes, so any
 * quantisation is a 256 m error on R. Everything else is therefore rejected loudly, by name, so a
 * host that points this library at the wrong tile source reads the fourcc it actually got instead
 * of a generic parse failure.
 */
internal object RiffParser {
    /** Bytes in the RIFF preamble: `RIFF`, the size, and the `WEBP` form type. */
    private const val PREAMBLE_SIZE = 12

    /** Bytes in a chunk header: the fourcc plus its little-endian size. */
    private const val CHUNK_HEADER_SIZE = 8

    /**
     * Returns the VP8L chunk payload — the bytes **after** the 8-byte chunk header, i.e. what a
     * [Vp8lBitReader] can be handed directly.
     *
     * Throws [IllegalStateException] on anything else: a truncated or non-RIFF file, a chunk whose
     * declared size runs past the end of the array, or a container holding a codec that is not
     * VP8L. Every bounds check happens before the read, never after, so a hostile or clipped tile
     * cannot make this walk off the end of [bytes].
     */
    fun vp8lPayload(bytes: ByteArray): ByteArray {
        check(bytes.size >= PREAMBLE_SIZE) {
            "not a RIFF/WEBP file: expected at least $PREAMBLE_SIZE bytes of container header, got ${bytes.size}"
        }
        val riffTag = fourccAt(bytes, 0)
        check(riffTag == "RIFF") { "not a RIFF file: expected 'RIFF' at offset 0, found '$riffTag'" }
        val formTag = fourccAt(bytes, 8)
        check(formTag == "WEBP") { "not a WebP file: expected 'WEBP' at offset 8, found '$formTag'" }

        // The RIFF size counts everything after itself. Trusting it blindly is how a truncated
        // download turns into an out-of-bounds read three chunks later, so it is validated here
        // and the walk is bounded by whichever of the two ends comes first.
        val riffSize = uint32At(bytes, 4)
        val declaredEnd = 8L + riffSize
        check(declaredEnd <= bytes.size) {
            "truncated WebP: RIFF header declares $declaredEnd bytes but the file holds ${bytes.size}"
        }
        val end = declaredEnd.toInt()

        val seen = mutableListOf<String>()
        var offset = PREAMBLE_SIZE
        while (offset + CHUNK_HEADER_SIZE <= end) {
            val fourcc = fourccAt(bytes, offset)
            val size = uint32At(bytes, offset + 4)
            val payloadStart = offset + CHUNK_HEADER_SIZE
            check(payloadStart + size <= end) {
                "truncated WebP: chunk '$fourcc' at offset $offset declares $size bytes of payload, " +
                    "only ${end - payloadStart} remain"
            }
            if (fourcc == "VP8L") {
                return bytes.copyOfRange(payloadStart, payloadStart + size.toInt())
            }
            rejectKnownNonLossless(fourcc)
            seen += fourcc
            // The pad byte for odd sizes belongs to the container, not to the payload.
            offset = payloadStart + size.toInt() + (size.toInt() and 1)
        }
        throw IllegalStateException(
            "no 'VP8L' chunk in this WebP file — only lossless VP8L is supported; chunks found: " +
                if (seen.isEmpty()) "none" else seen.joinToString(", ") { "'$it'" },
        )
    }

    /**
     * Fails with the fourcc spelled out when the container announces a codec this decoder will
     * never grow.
     *
     * `VP8X` is refused even though an extended file *may* embed a VP8L chunk further down: the
     * extended form also carries the canvas size, tiling and animation state, and honouring the
     * inner VP8L while ignoring all that would hand back pixels that are silently not the image.
     */
    private fun rejectKnownNonLossless(fourcc: String) {
        val what =
            when (fourcc) {
                "VP8 " -> "lossy VP8"
                "VP8X" -> "the extended (VP8X) container"
                "ALPH" -> "a separate lossy alpha plane"
                "ANIM", "ANMF" -> "an animation"
                else -> return
            }
        throw IllegalStateException(
            "unsupported WebP: chunk '$fourcc' means $what, and only lossless VP8L is supported",
        )
    }

    /**
     * The four ASCII characters at [offset]. Read byte by byte rather than through
     * `decodeToString`, because a fourcc in a corrupt file is arbitrary binary and the UTF-8
     * decoder would replace it with `U+FFFD` — which makes for a useless error message. Non-print
     * bytes are rendered as `\xNN` so the message stays readable either way.
     */
    private fun fourccAt(
        bytes: ByteArray,
        offset: Int,
    ): String =
        buildString {
            for (i in 0 until 4) {
                val b = bytes[offset + i].toInt() and 0xFF
                if (b in 0x20..0x7E) append(b.toChar()) else append("\\x").append(hexByte(b))
            }
        }

    private fun hexByte(b: Int): String {
        val digits = "0123456789ABCDEF"
        return "${digits[b shr 4]}${digits[b and 0x0F]}"
    }

    /**
     * The little-endian unsigned 32-bit integer at [offset], widened to [Long].
     *
     * Long rather than Int on purpose: a corrupt size of `0xFFFFFFFF` is a perfectly ordinary bit
     * pattern that reads as `-1` in an Int, and a negative size sails straight through a
     * `payloadStart + size <= end` bounds check.
     */
    private fun uint32At(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)
}
