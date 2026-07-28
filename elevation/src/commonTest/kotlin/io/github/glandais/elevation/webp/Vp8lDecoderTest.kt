package io.github.glandais.elevation.webp

import io.github.glandais.elevation.InlineTerrariumTileFixture
import io.github.glandais.elevation.InlineWebpFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * End-to-end tests for [Vp8lDecoder].
 *
 * The reference values come from libwebp by way of Pillow (see [Vp8lFixtures]), so nothing here is
 * self-referential: an agreement between this decoder and the fixtures is an agreement with the
 * encoder that produced them. Byte-exactness is the only acceptable outcome — these files are
 * Terrarium tiles in production, where elevation is `R * 256 + G + B / 256 - 32768` and a single
 * unit of drift on red is 256 metres.
 *
 * The one test that does not use a pre-encoded file is the overlapping back reference: no fixture
 * is guaranteed to contain that shape, so its bitstream is written out by hand, bit by bit, in
 * [buildOverlapFixture].
 */
class Vp8lDecoderTest {
    // ---------------------------------------------------------------------------------------
    // The reference files
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every reference fixture decodes to exactly the bytes libwebp produces`() {
        for (case in Vp8lFixtures.ALL) {
            val actual = Vp8lDecoder.decodeToRgba(case.webp)
            assertEquals(
                case.expectedRgba.size,
                actual.size,
                "${case.name}: decoded ${actual.size} RGBA bytes for a ${case.width}x${case.height} " +
                    "image, expected ${case.expectedRgba.size}",
            )
            assertBytesEqual(case.name, case.expectedRgba, actual)
        }
    }

    @Test
    fun `every reference fixture reports the dimensions its header declares`() {
        for (case in Vp8lFixtures.ALL) {
            val image = Vp8lDecoder.decode(case.webp)
            assertEquals(case.width, image.width, "${case.name}: decoded the wrong width")
            assertEquals(case.height, image.height, "${case.name}: decoded the wrong height")
            assertEquals(
                case.width * case.height,
                image.argb.size,
                "${case.name}: the pixel array does not match the ${case.width}x${case.height} geometry",
            )
        }
    }

    @Test
    fun `the four pixel inline fixture decodes to its bracketed byte values`() {
        val actual = Vp8lDecoder.decodeToRgba(InlineWebpFixture.BYTES)
        assertBytesEqual("InlineWebpFixture", InlineWebpFixture.EXPECTED_RGBA, actual)
    }

    @Test
    fun `the synthetic Terrarium tile decodes to the elevations it was generated from`() {
        val expected = InlineTerrariumTileFixture.expectedRawTile()
        val image = Vp8lDecoder.decode(InlineTerrariumTileFixture.BYTES)
        assertEquals(InlineTerrariumTileFixture.SIZE, image.width, "the synthetic tile is square")
        assertEquals(InlineTerrariumTileFixture.SIZE, image.height, "the synthetic tile is square")
        assertBytesEqual(
            "InlineTerrariumTileFixture",
            expected.rgba,
            Vp8lDecoder.decodeToRgba(InlineTerrariumTileFixture.BYTES),
        )
        // Spelled out in metres as well, because that is the unit a wrong channel is felt in.
        for (py in 0 until InlineTerrariumTileFixture.SIZE) {
            for (px in 0 until InlineTerrariumTileFixture.SIZE) {
                val pixel = image.argb[py * InlineTerrariumTileFixture.SIZE + px]
                val elevation =
                    ((pixel shr 16) and 0xFF) * 256 +
                        ((pixel shr 8) and 0xFF) +
                        (pixel and 0xFF) / 256.0 - 32768.0
                assertEquals(
                    InlineTerrariumTileFixture.elevationAt(px, py).toDouble(),
                    elevation,
                    0.0,
                    "elevation decoded at ($px, $py) does not match the value the fixture encodes",
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // The shape no fixture guarantees
    // ---------------------------------------------------------------------------------------

    /**
     * An LZ77 back reference of distance 1 and length 7 is a run: every pixel it copies is one it
     * has just written. A decoder that reaches for a bulk range copy reads the tail of the source
     * range before the loop has filled it, and produces one correct pixel followed by transparent
     * black — which is why this gets its own hand-built stream rather than being left to chance.
     */
    @Test
    fun `a back reference may overlap the pixels it is still writing`() {
        val image = Vp8lDecoder.decode(buildOverlapFixture())
        assertEquals(8, image.width, "the hand-built fixture is 8 pixels wide")
        assertEquals(1, image.height, "the hand-built fixture is one row tall")
        val expected = (255 shl 24) or (10 shl 16) or (20 shl 8) or 30
        for (i in 0 until 8) {
            assertEquals(
                expected,
                image.argb[i],
                "pixel $i should be the literal repeated by the distance-1 back reference, " +
                    "got 0x${image.argb[i].toUInt().toString(16)}",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Malformed input
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a VP8L payload cut short fails instead of returning a half decoded image`() {
        val case = Vp8lFixtures.GRADIENT
        val payload = RiffParser.vp8lPayload(case.webp)
        // A well-formed container around a bitstream that stops mid-image: the RIFF walk succeeds
        // and the failure has to come from the decoder running out of bits.
        val truncated = wrapInRiff(payload.copyOfRange(0, payload.size - 6))
        val failure =
            assertFailsWith<IllegalStateException>("a truncated bitstream must not decode") {
                Vp8lDecoder.decode(truncated)
            }
        assertTrue(
            failure.message!!.contains("exhausted"),
            "the message should say the bitstream ran out, was: ${failure.message}",
        )
    }

    @Test
    fun `a header declaring more rows than the stream encodes fails`() {
        val case = Vp8lFixtures.GRADIENT
        val payload = RiffParser.vp8lPayload(case.webp)
        // Same pixels, but the header now claims 400 rows instead of 12. The decode loop keeps
        // asking for symbols long after the encoder stopped writing them.
        val lying = wrapInRiff(withDeclaredSize(payload, case.width, 400))
        val failure =
            assertFailsWith<IllegalStateException>("declared geometry must match the data") {
                Vp8lDecoder.decode(lying)
            }
        assertTrue(
            failure.message!!.contains("exhausted"),
            "the message should say the bitstream ran out, was: ${failure.message}",
        )
    }

    @Test
    fun `a container without a VP8L chunk is refused by name`() {
        val lossy = wrapInRiff(byteArrayOf(0, 1, 2, 3), fourcc = "VP8 ")
        val failure =
            assertFailsWith<IllegalStateException>("lossy WebP is out of scope") {
                Vp8lDecoder.decode(lossy)
            }
        assertTrue(
            failure.message!!.contains("VP8 "),
            "the message should name the fourcc that was found, was: ${failure.message}",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Compares two byte arrays and, on a mismatch, reports the first differing pixel with its
     * neighbourhood — a raw `assertContentEquals` on 4 kB of RGBA prints two walls of numbers and
     * tells you nothing about *where* the decode went wrong.
     */
    private fun assertBytesEqual(
        what: String,
        expected: ByteArray,
        actual: ByteArray,
    ) {
        assertEquals(expected.size, actual.size, "$what: decoded ${actual.size} bytes, expected ${expected.size}")
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                val pixel = i / 4
                val channel = "rgba"[i % 4]
                val from = maxOf(0, (pixel - 1) * 4)
                val to = minOf(expected.size, (pixel + 2) * 4)
                throw AssertionError(
                    "$what: first mismatch at byte $i (pixel $pixel, channel '$channel'): " +
                        "expected ${expected[i].toInt() and 0xFF}, got ${actual[i].toInt() and 0xFF}\n" +
                        "  expected around it: ${hexWindow(expected, from, to)}\n" +
                        "  actual   around it: ${hexWindow(actual, from, to)}",
                )
            }
        }
    }

    private fun hexWindow(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): String =
        (from until to).joinToString(" ") {
            val v = bytes[it].toInt() and 0xFF
            val digits = "0123456789abcdef"
            "${digits[v shr 4]}${digits[v and 0xF]}"
        }

    /** Wraps a raw VP8L payload back into a minimal, well-formed RIFF/WEBP container. */
    private fun wrapInRiff(
        payload: ByteArray,
        fourcc: String = "VP8L",
    ): ByteArray {
        val padding = payload.size and 1
        val out = ByteArray(12 + 8 + payload.size + padding)
        writeAscii(out, 0, "RIFF")
        writeUint32(out, 4, 4 + 8 + payload.size + padding)
        writeAscii(out, 8, "WEBP")
        writeAscii(out, 12, fourcc)
        writeUint32(out, 16, payload.size)
        payload.copyInto(out, 20)
        return out
    }

    /**
     * Rewrites the width and height of a VP8L payload's 5-byte header, leaving everything after it
     * untouched. The layout is signature (8 bits), `width - 1` (14), `height - 1` (14), the alpha
     * hint (1) and the version (3) — so the two dimensions straddle bytes 1 to 4 and the hint and
     * version bits have to be carried across from the original byte 4.
     */
    private fun withDeclaredSize(
        payload: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val out = payload.copyOf()
        val w = width - 1
        val h = height - 1
        out[1] = (w and 0xFF).toByte()
        out[2] = (((w shr 8) and 0x3F) or ((h and 0x03) shl 6)).toByte()
        out[3] = ((h shr 2) and 0xFF).toByte()
        out[4] = (((h shr 10) and 0x0F) or (payload[4].toInt() and 0xF0)).toByte()
        return out
    }

    private fun writeAscii(
        target: ByteArray,
        offset: Int,
        text: String,
    ) {
        for (i in text.indices) target[offset + i] = text[i].code.toByte()
    }

    private fun writeUint32(
        target: ByteArray,
        offset: Int,
        value: Int,
    ) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value shr 8) and 0xFF).toByte()
        target[offset + 2] = ((value shr 16) and 0xFF).toByte()
        target[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    /**
     * An LSB-first bit writer — the exact mirror of [Vp8lBitReader], and the only way to author a
     * VP8L stream by hand without hand-assembling bytes.
     */
    private class BitWriter {
        private val bytes = mutableListOf<Byte>()
        private var accumulator = 0L
        private var buffered = 0

        fun write(
            value: Int,
            count: Int,
        ) {
            require(count in 0..24) { "the reader serves at most 24 bits, so the writer emits at most 24" }
            if (count == 0) return
            accumulator = accumulator or ((value.toLong() and ((1L shl count) - 1L)) shl buffered)
            buffered += count
            while (buffered >= 8) {
                bytes += (accumulator and 0xFF).toByte()
                accumulator = accumulator ushr 8
                buffered -= 8
            }
        }

        fun toByteArray(): ByteArray {
            val tail = if (buffered > 0) listOf((accumulator and 0xFF).toByte()) else emptyList()
            return (bytes + tail).toByteArray()
        }
    }

    /**
     * Writes an 8 × 1 VP8L image whose single literal pixel is followed by a back reference of
     * length 7 at distance 1.
     *
     * Every field is spelled out because the point of the fixture is that a reader can check it
     * against the specification. In order: the 5-byte header; no transforms; no colour cache; no
     * meta-Huffman partition; then the five prefix codes.
     *
     * The green code has to be a *normal* code rather than a simple one: a simple code spells its
     * symbols in 8 bits and the length prefix this stream needs is symbol 261, which does not fit.
     * So the code-length code is built with just two symbols — the literal length 1 and the
     * operator 18, "write 11 + 7 bits of zeros" — and the `max_symbol` budget is used to stop after
     * five reads rather than spelling out all 280 lengths.
     */
    private fun buildOverlapFixture(): ByteArray {
        val w = BitWriter()
        w.write(0x2F, 8) // VP8L signature
        w.write(7, 14) // width - 1
        w.write(0, 14) // height - 1
        w.write(0, 1) // alpha hint
        w.write(0, 3) // version

        w.write(0, 1) // no transform
        w.write(0, 1) // no colour cache
        w.write(0, 1) // no meta-Huffman partition

        // --- green code: a normal code holding symbols 20 (a literal) and 261 (length prefix 5).
        w.write(0, 1) // not a simple code
        w.write(0, 4) // num_code_lengths = 4, i.e. lengths for symbols 17, 18, 0, 1
        w.write(0, 3) // symbol 17: unused
        w.write(1, 3) // symbol 18: one bit
        w.write(0, 3) // symbol 0: unused
        w.write(1, 3) // symbol 1: one bit
        w.write(1, 1) // max_symbol is present
        w.write(1, 3) // length_nbits = 2 + 2 * 1 = 4
        w.write(3, 4) // max_symbol = 2 + 3 = 5 reads
        // Canonically, symbol 1 owns code 0 and symbol 18 owns code 1.
        w.write(1, 1) // operator 18 …
        w.write(9, 7) // … repeated 11 + 9 = 20 times: symbols 0..19 are unused
        w.write(0, 1) // length 1 for symbol 20
        w.write(1, 1) // operator 18 …
        w.write(127, 7) // … 11 + 127 = 138 zeros: symbols 21..158
        w.write(1, 1) // operator 18 …
        w.write(91, 7) // … 11 + 91 = 102 zeros: symbols 159..260
        w.write(0, 1) // length 1 for symbol 261

        writeSingleSymbolCode(w, 10) // red
        writeSingleSymbolCode(w, 30) // blue
        writeSingleSymbolCode(w, 255) // alpha
        writeSingleSymbolCode(w, 1) // distance: code 1 means value 2, i.e. plane code 2

        // --- data.
        w.write(0, 1) // green symbol 20; red, blue and alpha cost no bits at all
        w.write(1, 1) // green symbol 261 = length prefix 5
        w.write(0, 1) // its single extra bit: length = ((2 + 1) shl 1) + 0 + 1 = 7
        // The distance code is degenerate too, so the distance symbol costs nothing: symbol 1
        // decodes to the value 2, whose plane code is (xoffset 1, yoffset 0) — a distance of 1.

        return wrapInRiff(w.toByteArray())
    }

    /** A "simple" prefix code holding one symbol, spelled in 8 bits. Reading it costs no bits. */
    private fun writeSingleSymbolCode(
        w: BitWriter,
        symbol: Int,
    ) {
        w.write(1, 1) // simple code
        w.write(0, 1) // one symbol
        w.write(1, 1) // spelled in 8 bits
        w.write(symbol, 8)
    }
}
