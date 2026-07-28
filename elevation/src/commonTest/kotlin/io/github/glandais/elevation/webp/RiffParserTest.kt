package io.github.glandais.elevation.webp

import io.github.glandais.elevation.InlineTerrariumTileFixture
import io.github.glandais.elevation.InlineWebpFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Container-level tests. Every case that is not one of the two real fixtures is a container built
 * by hand here, because the failures worth pinning — a lossy file, a size that lies, a missing pad
 * byte — are precisely the ones no encoder will produce on request.
 */
class RiffParserTest {
    // --- Real fixtures -------------------------------------------------------------------

    @Test
    fun findsTheVp8lChunkOfTheFourByOneFixture() {
        val payload = RiffParser.vp8lPayload(InlineWebpFixture.BYTES)
        assertEquals(
            37,
            payload.size,
            "the 4x1 fixture declares a 37-byte VP8L chunk; a different size means the walk landed " +
                "on the wrong offset",
        )
        assertEquals(
            0x2F,
            payload[0].toInt() and 0xFF,
            "the payload must start at the VP8L signature byte, not at the chunk header",
        )
    }

    @Test
    fun findsTheVp8lChunkOfTheFourByFourTerrariumFixture() {
        val payload = RiffParser.vp8lPayload(InlineTerrariumTileFixture.BYTES)
        assertEquals(
            50,
            payload.size,
            "the 4x4 Terrarium fixture declares a 50-byte VP8L chunk",
        )
        assertEquals(
            0x2F,
            payload[0].toInt() and 0xFF,
            "the payload must start at the VP8L signature byte",
        )
    }

    @Test
    fun theReturnedPayloadIsExactlyTheBytesAfterTheEightByteChunkHeader() {
        val file = InlineWebpFixture.BYTES
        val payload = RiffParser.vp8lPayload(file)
        // The fixture holds a single chunk, so its payload starts at 12 + 8 = 20.
        val expected = file.copyOfRange(20, 20 + payload.size)
        assertTrue(
            payload.contentEquals(expected),
            "the payload must be the raw chunk body; an off-by-one here shifts every bit the " +
                "decoder reads",
        )
    }

    // --- Codecs that are deliberately out of scope ---------------------------------------

    @Test
    fun aLossyFileIsRejectedWithItsFourccNamed() {
        val message = failureFor(riff("VP8 " to ByteArray(10)))
        assertTrue("VP8 " in message, "the message must name the fourcc found, was: $message")
        assertTrue(
            "VP8L" in message,
            "the message must say which codec is supported so the host knows what to serve, was: $message",
        )
    }

    @Test
    fun anExtendedVp8xFileIsRejectedWithItsFourccNamed() {
        val message = failureFor(riff("VP8X" to ByteArray(10)))
        assertTrue("VP8X" in message, "the message must name the fourcc found, was: $message")
        assertTrue("VP8L" in message, "the message must name the supported codec, was: $message")
    }

    @Test
    fun anExtendedFileIsRejectedEvenWhenItEmbedsAVp8lChunk() {
        // VP8X carries canvas size and feature flags that this decoder ignores entirely, so
        // honouring the inner VP8L would hand back pixels that are quietly not the image.
        val message = failureFor(riff("VP8X" to ByteArray(10), "VP8L" to byteArrayOf(0x2F)))
        assertTrue("VP8X" in message, "the extended container must be refused by name, was: $message")
    }

    @Test
    fun aSeparateAlphaPlaneIsRejectedWithItsFourccNamed() {
        val message = failureFor(riff("ALPH" to ByteArray(4)))
        assertTrue("ALPH" in message, "the message must name the fourcc found, was: $message")
    }

    @Test
    fun aFileWithNoVp8lChunkListsTheChunksItDidContain() {
        val message = failureFor(riff("ICCP" to ByteArray(4), "EXIF" to ByteArray(6)))
        assertTrue("ICCP" in message, "the message must list what was actually there, was: $message")
        assertTrue("EXIF" in message, "the message must list what was actually there, was: $message")
    }

    // --- Malformed containers -------------------------------------------------------------

    @Test
    fun anEmptyArrayIsRejectedRatherThanReadOutOfBounds() {
        val message = failureFor(ByteArray(0))
        assertTrue("RIFF" in message, "the message must say what was expected, was: $message")
    }

    @Test
    fun anArrayShorterThanTheTwelveBytePreambleIsRejected() {
        val message = failureFor(InlineWebpFixture.BYTES.copyOfRange(0, 11))
        assertTrue(
            "11" in message,
            "the message must report the length actually seen so a truncated download is obvious, was: $message",
        )
    }

    @Test
    fun aFileThatIsNotRiffIsRejectedNamingWhatWasFoundInstead() {
        val bogus = InlineWebpFixture.BYTES.copyOf()
        bogus[3] = 'X'.code.toByte()
        val message = failureFor(bogus)
        assertTrue("RIFX" in message, "the message must quote the tag found, was: $message")
    }

    @Test
    fun aRiffFileThatIsNotWebpIsRejectedNamingItsFormType() {
        val bogus = InlineWebpFixture.BYTES.copyOf()
        "AVI ".encodeToByteArray().copyInto(bogus, 8)
        val message = failureFor(bogus)
        assertTrue("AVI " in message, "the message must quote the form type found, was: $message")
    }

    @Test
    fun aNonPrintableTagIsRenderedAsHexRatherThanAsReplacementCharacters() {
        val bogus = ByteArray(12) { 0 }
        val message = failureFor(bogus)
        assertTrue(
            "\\x00" in message,
            "binary garbage must be shown as escaped hex, not mangled through UTF-8, was: $message",
        )
    }

    @Test
    fun aRiffSizeLargerThanTheFileIsRejectedAsTruncated() {
        val truncated = InlineWebpFixture.BYTES.copyOfRange(0, InlineWebpFixture.BYTES.size - 4)
        val message = failureFor(truncated)
        assertTrue(
            "truncated" in message,
            "a clipped download must be named as such, was: $message",
        )
    }

    @Test
    fun aChunkWhoseDeclaredSizeRunsPastTheEndIsRejected() {
        val file = riff("VP8L" to byteArrayOf(0x2F, 0, 0, 0, 0))
        // Inflate only the chunk size, leaving the RIFF size consistent with the real length.
        file[16] = 0xF0.toByte()
        val message = failureFor(file)
        assertTrue("truncated" in message, "an over-long chunk size must be caught, was: $message")
        assertTrue("VP8L" in message, "the message must name the offending chunk, was: $message")
    }

    @Test
    fun aChunkSizeWithTheHighBitSetIsRejectedRatherThanReadAsNegative() {
        // 0xFFFFFFFF is -1 in an Int, and a negative size sails straight through a naive
        // `start + size <= end` bounds check.
        val file = riff("VP8L" to byteArrayOf(0x2F, 0, 0, 0, 0))
        for (i in 0 until 4) file[16 + i] = 0xFF.toByte()
        val message = failureFor(file)
        assertTrue("truncated" in message, "a size of 0xFFFFFFFF must be refused, was: $message")
    }

    // --- Padding ---------------------------------------------------------------------------

    @Test
    fun anOddSizedChunkIsFollowedByItsPadByteWhenWalkingOn() {
        // 3 bytes of ICCP means one pad byte before VP8L starts; a walker that skips the pad reads
        // its fourcc one byte early and finds garbage.
        val vp8l = byteArrayOf(0x2F, 0x03, 0x00, 0x00, 0x00)
        val payload = RiffParser.vp8lPayload(riff("ICCP" to byteArrayOf(1, 2, 3), "VP8L" to vp8l))
        assertTrue(
            payload.contentEquals(vp8l),
            "the VP8L payload after an odd-sized chunk must be found intact; got " +
                payload.joinToString(",") { (it.toInt() and 0xFF).toString() },
        )
    }

    @Test
    fun theFourByOneFixtureItselfExercisesTheOddSizeCase() {
        // 37 bytes of VP8L: the fixture would already have caught a missing pad byte had the chunk
        // not been the last one. Asserting the parity keeps that documented rather than incidental.
        assertEquals(
            1,
            RiffParser.vp8lPayload(InlineWebpFixture.BYTES).size % 2,
            "the 4x1 fixture is expected to hold an odd-sized VP8L chunk",
        )
    }

    @Test
    fun anOddSizedChunkMissingItsPadByteIsRejectedRatherThanDecodedAsGarbage() {
        val file = riff("ICCP" to byteArrayOf(1, 2, 3), "VP8L" to byteArrayOf(0x2F, 0, 0, 0, 0))
        // Drop the pad byte and shrink the RIFF size to match: the walk now lands one byte early.
        val broken = file.copyOfRange(0, 23) + file.copyOfRange(24, file.size)
        writeUint32(broken, 4, broken.size - 8)
        assertFailsWith<IllegalStateException>(
            "a desynchronised walk must fail loudly instead of returning whatever it found",
        ) { RiffParser.vp8lPayload(broken) }
    }

    // --- Helpers ----------------------------------------------------------------------------

    /** Runs the parser expecting failure and returns the message, so each test asserts on wording. */
    private fun failureFor(bytes: ByteArray): String =
        assertFailsWith<IllegalStateException>("this input must be rejected") {
            RiffParser.vp8lPayload(bytes)
        }.message ?: "<no message>"

    /** Assembles a well-formed RIFF/WEBP container out of the given chunks, padding as the spec asks. */
    private fun riff(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = mutableListOf<Byte>()
        body += "WEBP".encodeToByteArray().toList()
        for ((fourcc, payload) in chunks) {
            body += fourcc.encodeToByteArray().toList()
            body += uint32(payload.size)
            body += payload.toList()
            if (payload.size % 2 == 1) body += 0
        }
        val out = mutableListOf<Byte>()
        out += "RIFF".encodeToByteArray().toList()
        out += uint32(body.size)
        out += body
        return out.toByteArray()
    }

    private fun uint32(value: Int): List<Byte> =
        listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    private fun writeUint32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        uint32(value).forEachIndexed { i, b -> bytes[offset + i] = b }
    }
}
