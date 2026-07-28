package io.github.glandais.elevation.webp

import io.github.glandais.elevation.InlineTerrariumTileFixture
import io.github.glandais.elevation.InlineWebpFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Bit-order and header tests.
 *
 * Every expected value below is hand-computed from the byte pattern in the test itself rather than
 * taken from a run of the code — a reader that is consistently wrong (MSB-first, say) would agree
 * with itself perfectly and only disagree with arithmetic done on paper.
 */
class Vp8lBitReaderTest {
    // --- Bit order ---------------------------------------------------------------------------

    @Test
    fun theFirstBitReadIsTheLeastSignificantBitOfTheFirstByte() {
        // 0x01 is 0b0000_0001: one bit set, and it is the one VP8L reads first.
        val reader = Vp8lBitReader(byteArrayOf(0x01))
        assertEquals(1, reader.readBit(), "the low bit of the first byte must come out first")
        for (i in 1 until 8) {
            assertEquals(0, reader.readBit(), "bit $i of 0x01 is clear")
        }
    }

    @Test
    fun aMultiBitReadPlacesTheFirstBitReadAtTheLeastSignificantEnd() {
        // 0xD6 is 0b1101_0110. Low nibble first: 0b0110 = 6, then the high nibble 0b1101 = 13.
        val reader = Vp8lBitReader(byteArrayOf(0xD6.toByte()))
        assertEquals(6, reader.readBits(4), "the low nibble of 0xD6 is 0x6, and it is read first")
        assertEquals(13, reader.readBits(4), "the high nibble of 0xD6 is 0xD")
    }

    @Test
    fun aReadStraddlingAByteBoundaryTakesTheLowByteFirst() {
        // Bytes 0xFF 0x00. Four bits taken (all set), then eight: 1111 from the tail of the first
        // byte, then 0000 from the second, first-read at the least significant end -> 0b0000_1111.
        val reader = Vp8lBitReader(byteArrayOf(0xFF.toByte(), 0x00))
        assertEquals(15, reader.readBits(4), "the low nibble of 0xFF")
        assertEquals(
            15,
            reader.readBits(8),
            "the four remaining set bits land in the low half of the result, the zeros above them",
        )
    }

    @Test
    fun aTwentyFourBitReadAssemblesTheThreeBytesLittleEndian() {
        val reader = Vp8lBitReader(byteArrayOf(0x78, 0x56, 0x34))
        assertEquals(
            0x345678,
            reader.readBits(24),
            "24 bits LSB-first over three bytes is exactly a little-endian 24-bit integer",
        )
    }

    @Test
    fun readingZeroBitsYieldsZeroAndConsumesNothing() {
        val reader = Vp8lBitReader(byteArrayOf(0xFF.toByte()))
        assertEquals(0, reader.readBits(0), "an empty read has no value")
        assertEquals(255, reader.readBits(8), "an empty read must not have advanced the stream")
    }

    @Test
    fun readingZeroBitsAtTheVeryEndDoesNotThrow() {
        val reader = Vp8lBitReader(byteArrayOf(0x00))
        reader.readBits(8)
        assertEquals(0, reader.readBits(0), "a zero-width read needs no bits, exhausted or not")
    }

    @Test
    fun everyWidthFromOneToTwentyFourAgreesWithAPerBitReference() {
        // The reference is deliberately naive — one bit at a time, shifted into place — so that the
        // accumulator's refill and masking arithmetic is checked against something that cannot
        // share its bugs.
        val bytes = ByteArray(256) { ((it * 37 + 11) and 0xFF).toByte() }
        for (width in 1..24) {
            val fast = Vp8lBitReader(bytes)
            val slow = NaiveBitReader(bytes)
            // Enough reads to cross many byte boundaries at every alignment.
            repeat((bytes.size * 8) / width) { step ->
                assertEquals(
                    slow.readBits(width),
                    fast.readBits(width),
                    "read $step of width $width disagrees with the per-bit reference",
                )
            }
        }
    }

    @Test
    fun anOddWidthKeepsTheStreamAlignedAcrossManyReads() {
        // 3 bits at a time over 3 bytes: the reads never line up with byte boundaries until the
        // eighth, which is exactly where an accumulator that forgot to drop consumed bits drifts.
        val reader = Vp8lBitReader(byteArrayOf(0xFF.toByte(), 0x00, 0xFF.toByte()))
        // Bits 0-7 set, 8-15 clear, 16-23 set. Read at offsets 0, 3, 6, 9, 12, 15, 18, 21: the
        // third read is 1,1,0 (LSB-first, so 3) and the sixth is 0,1,1 (so 6).
        val expected = listOf(7, 7, 3, 0, 0, 6, 7, 7)
        expected.forEachIndexed { i, value ->
            assertEquals(value, reader.readBits(3), "3-bit read number $i")
        }
    }

    // --- Bounds ------------------------------------------------------------------------------

    @Test
    fun aWidthAboveTwentyFourIsRefusedAsAProgrammingError() {
        val reader = Vp8lBitReader(ByteArray(8))
        assertFailsWith<IllegalArgumentException>("VP8L never reads more than 24 bits at once") {
            reader.readBits(25)
        }
    }

    @Test
    fun aNegativeWidthIsRefused() {
        val reader = Vp8lBitReader(ByteArray(8))
        assertFailsWith<IllegalArgumentException>("a negative bit count is meaningless") {
            reader.readBits(-1)
        }
    }

    @Test
    fun readingPastTheEndThrowsRatherThanReturningZeros() {
        val reader = Vp8lBitReader(byteArrayOf(0x00))
        reader.readBits(8)
        assertFailsWith<IllegalStateException>(
            "a truncated stream must fail; zeros would decode into a plausible but wrong image",
        ) { reader.readBit() }
    }

    @Test
    fun aReadThatOnlyPartlyFitsThrowsInsteadOfReturningWhatFits() {
        val reader = Vp8lBitReader(byteArrayOf(0x0F))
        assertFailsWith<IllegalStateException>("12 bits are not available in a single byte") {
            reader.readBits(12)
        }
    }

    @Test
    fun anEmptyStreamIsExhaustedFromTheStartAndRefusesEveryRead() {
        val reader = Vp8lBitReader(ByteArray(0))
        assertTrue(reader.exhausted, "there is nothing to read in an empty array")
        assertFailsWith<IllegalStateException>("an empty stream has no bits") { reader.readBit() }
    }

    @Test
    fun exhaustedTurnsTrueOnlyAfterTheLastBitHasBeenHandedOut() {
        val reader = Vp8lBitReader(byteArrayOf(0xAA.toByte()))
        assertFalse(reader.exhausted, "nothing has been read yet")
        reader.readBits(7)
        assertFalse(
            reader.exhausted,
            "one bit is still buffered; a reader that reported exhaustion here would let a decoder " +
                "stop mid-symbol",
        )
        reader.readBit()
        assertTrue(reader.exhausted, "all eight bits are gone")
    }

    // --- Header ------------------------------------------------------------------------------

    @Test
    fun theHeaderOfTheFourByOneFixtureReportsItsDimensions() {
        val header = readVp8lHeader(Vp8lBitReader(RiffParser.vp8lPayload(InlineWebpFixture.BYTES)))
        assertEquals(InlineWebpFixture.WIDTH, header.width, "the fixture is 4 pixels wide")
        assertEquals(InlineWebpFixture.HEIGHT, header.height, "the fixture is 1 pixel tall")
    }

    @Test
    fun theHeaderOfTheFourByFourTerrariumFixtureReportsItsDimensions() {
        val header =
            readVp8lHeader(Vp8lBitReader(RiffParser.vp8lPayload(InlineTerrariumTileFixture.BYTES)))
        assertEquals(InlineTerrariumTileFixture.SIZE, header.width, "the Terrarium fixture is 4 wide")
        assertEquals(InlineTerrariumTileFixture.SIZE, header.height, "the Terrarium fixture is 4 tall")
    }

    @Test
    fun theAlphaBitOfBothFixturesIsClearBecauseTheEncoderFoundThemOpaque() {
        // Both fixtures decode to A = 255 everywhere, yet libwebp writes alpha_is_used = 0 for
        // them: the bit says "alpha carries information", not "an alpha channel exists". Asserting
        // the false here is what stops a future decoder from branching on it to skip a channel.
        val small = readVp8lHeader(Vp8lBitReader(RiffParser.vp8lPayload(InlineWebpFixture.BYTES)))
        val tile =
            readVp8lHeader(Vp8lBitReader(RiffParser.vp8lPayload(InlineTerrariumTileFixture.BYTES)))
        assertFalse(small.hasAlpha, "the 4x1 fixture's header bytes are 2F 03 00 00 00: bit 28 is clear")
        assertFalse(tile.hasAlpha, "the 4x4 fixture's header bytes are 2F 03 C0 00 00: bit 28 is clear")
    }

    @Test
    fun theAlphaBitIsReportedWhenTheEncoderDidSetIt() {
        // Same 4x1 geometry, alpha_is_used forced on: bit 28 of the 32-bit body.
        val header = readVp8lHeader(Vp8lBitReader(headerBytes(width = 4, height = 1, alpha = true, version = 0)))
        assertTrue(header.hasAlpha, "bit 28 set must surface as hasAlpha")
    }

    @Test
    fun theHeaderIsExactlyFiveBytesLong() {
        val reader = Vp8lBitReader(headerBytes(width = 16384, height = 16384, alpha = false, version = 0))
        val header = readVp8lHeader(reader)
        assertEquals(16384, header.width, "14 bits biased by one top out at 16384")
        assertEquals(16384, header.height, "14 bits biased by one top out at 16384")
        assertTrue(
            reader.exhausted,
            "the header must consume 8 + 14 + 14 + 1 + 3 = 40 bits and leave the reader byte-aligned",
        )
    }

    @Test
    fun aStreamNotStartingWithTheSignatureByteIsRejected() {
        val bytes = headerBytes(width = 4, height = 1, alpha = false, version = 0)
        bytes[0] = 0x2E
        val message =
            assertFailsWith<IllegalStateException>("0x2E is not the VP8L signature") {
                readVp8lHeader(Vp8lBitReader(bytes))
            }.message ?: "<no message>"
        assertTrue("2E" in message, "the message must quote the byte found, was: $message")
    }

    @Test
    fun aNonZeroVersionIsRejectedWithTheValueFound() {
        val bytes = headerBytes(width = 4, height = 1, alpha = false, version = 3)
        val message =
            assertFailsWith<IllegalStateException>("version is reserved and must be zero") {
                readVp8lHeader(Vp8lBitReader(bytes))
            }.message ?: "<no message>"
        assertTrue("3" in message, "the message must quote the version found, was: $message")
    }

    @Test
    fun aHeaderCutShortIsRejectedRatherThanCompletedWithZeros() {
        val bytes = headerBytes(width = 4, height = 1, alpha = false, version = 0).copyOfRange(0, 3)
        assertFailsWith<IllegalStateException>("four bytes are not a VP8L header") {
            readVp8lHeader(Vp8lBitReader(bytes))
        }
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** Builds the five header bytes by hand, so the tests never depend on an encoder being available. */
    private fun headerBytes(
        width: Int,
        height: Int,
        alpha: Boolean,
        version: Int,
    ): ByteArray {
        val body =
            ((width - 1).toLong() and 0x3FFF) or
                (((height - 1).toLong() and 0x3FFF) shl 14) or
                ((if (alpha) 1L else 0L) shl 28) or
                ((version.toLong() and 0x7L) shl 29)
        return byteArrayOf(
            0x2F,
            (body and 0xFF).toByte(),
            ((body shr 8) and 0xFF).toByte(),
            ((body shr 16) and 0xFF).toByte(),
            ((body shr 24) and 0xFF).toByte(),
        )
    }

    /** One bit at a time, the obvious way — the oracle the fast reader is checked against. */
    private class NaiveBitReader(
        private val bytes: ByteArray,
    ) {
        private var bitPos = 0

        fun readBits(count: Int): Int {
            var result = 0
            for (i in 0 until count) {
                val byte = bytes[bitPos ushr 3].toInt() and 0xFF
                val bit = (byte ushr (bitPos and 7)) and 1
                result = result or (bit shl i)
                bitPos++
            }
            return result
        }
    }
}
