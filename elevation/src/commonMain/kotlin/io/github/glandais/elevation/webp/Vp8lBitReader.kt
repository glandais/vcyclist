package io.github.glandais.elevation.webp

/**
 * LSB-first bit reader over a byte array, as VP8L specifies.
 *
 * The bit order is the single most expensive thing to get wrong in this decoder, so it is worth
 * stating precisely. VP8L reads the byte stream in order, and within each byte it reads the
 * **least significant bit first**. A multi-bit read therefore returns the *first* bit read as the
 * *least significant* bit of the result: reading 4 bits from the byte `0b0000_1101` yields `13`,
 * and the next 4 bits come from the high nibble. Reads span byte boundaries freely — a 14-bit
 * dimension field, for instance, straddles two bytes and continues into a third.
 *
 * This is the opposite convention from VP8 (lossy), which is MSB-first, and from most other image
 * formats. `RFC 6386` describes the lossy reader; the lossless bitstream is specified separately
 * in the WebP Lossless Bitstream Specification, section "Preliminaries / Bit-stream". The 4 × 1
 * fixture in `InlineWebpFixture` proves the convention end to end: its header bytes are
 * `2F 03 00 00 00`, whose 14-bit width field only reads as `3` (i.e. a width of 4) under LSB-first
 * ordering.
 *
 * Implementation note on speed: a 512 × 512 tile is a quarter of a million pixels and several
 * million bit reads, so bits are refilled a byte at a time into a [Long] accumulator rather than
 * shifted out one at a time. The accumulator holds at most 31 live bits — a read asks for at most
 * 24, so the refill loop can only be entered holding 23 and top up once past the requested count —
 * which keeps it comfortably clear of the sign bit.
 */
internal class Vp8lBitReader(
    private val bytes: ByteArray,
) {
    /** Index of the next byte to pull into [accumulator]. */
    private var bytePos = 0

    /** The buffered bits, next-to-be-read at the least significant end. */
    private var accumulator = 0L

    /** How many of [accumulator]'s low bits are live. */
    private var bitsBuffered = 0

    /**
     * Reads [count] bits (0..24), least-significant bit first.
     *
     * The 24-bit ceiling is not arbitrary: it is the widest field the lossless format asks for
     * (the distance/length extra bits and the colour-cache entries all fit well under it), and it
     * is what lets the accumulator stay within a [Long] without a second refill path.
     *
     * Throws [IllegalStateException] when the stream runs out. Returning zeros past the end — what
     * several reference decoders do, flagging an error separately — would let a truncated tile
     * decode into a plausible-looking image, which is far worse than a failure for something whose
     * pixels are metres above sea level.
     */
    fun readBits(count: Int): Int {
        require(count in 0..24) { "VP8L reads at most 24 bits at a time, was asked for $count" }
        if (count == 0) return 0
        while (bitsBuffered < count) {
            check(bytePos < bytes.size) {
                "VP8L bitstream exhausted: needed $count bits, only $bitsBuffered left after " +
                    "${bytes.size} bytes"
            }
            accumulator = accumulator or ((bytes[bytePos].toLong() and 0xFF) shl bitsBuffered)
            bytePos++
            bitsBuffered += 8
        }
        val result = (accumulator and ((1L shl count) - 1L)).toInt()
        accumulator = accumulator ushr count
        bitsBuffered -= count
        return result
    }

    /** Reads exactly one bit. */
    fun readBit(): Int = readBits(1)

    /**
     * True once every bit of [bytes] has been consumed.
     *
     * Both halves matter: the last byte may have been pulled into the accumulator without its bits
     * having been handed out yet, and a stream that ends mid-byte is not exhausted until those
     * trailing bits are read. Callers use this to assert that a decode consumed its input rather
     * than stopping early on a malformed length.
     */
    val exhausted: Boolean
        get() = bytePos >= bytes.size && bitsBuffered == 0
}

/**
 * The 5-byte VP8L header: signature, dimensions, alpha hint, version.
 *
 * [hasAlpha] is a *hint* and nothing more — the specification calls it `alpha_is_used` and says a
 * decoder must not rely on it, since an encoder is free to clear it on an image that still carries
 * an alpha channel of all-`0xFF`. Both inline fixtures in this module are opaque and both leave the
 * bit at 0, even though their decoded pixels have `A = 255`. Use it for fast paths, never to decide
 * how many channels to write.
 */
internal class Vp8lHeader(
    val width: Int,
    val height: Int,
    val hasAlpha: Boolean,
)

/** The one byte every VP8L stream opens with. */
private const val VP8L_SIGNATURE = 0x2F

/**
 * Reads the VP8L header from [reader], which must be positioned at the start of the VP8L payload.
 * Consumes the 8-bit signature `0x2F`, 14 bits of `width - 1`, 14 bits of `height - 1`, 1 alpha bit
 * and 3 version bits — 40 bits, exactly five bytes, with nothing left over to realign.
 *
 * Dimensions are stored biased by one because a zero-sized image is meaningless, which buys the
 * format a 16384 × 16384 maximum out of 14 bits. The version field is reserved and must be zero;
 * a non-zero value means a future bitstream this code cannot read, so it is refused with the value
 * found rather than being decoded into nonsense.
 */
internal fun readVp8lHeader(reader: Vp8lBitReader): Vp8lHeader {
    val signature = reader.readBits(8)
    check(signature == VP8L_SIGNATURE) {
        "not a VP8L stream: expected signature 0x2F, found 0x${signature.toString(16).uppercase()}"
    }
    val width = reader.readBits(14) + 1
    val height = reader.readBits(14) + 1
    val hasAlpha = reader.readBit() == 1
    val version = reader.readBits(3)
    check(version == 0) { "unsupported VP8L version $version, only version 0 is specified" }
    return Vp8lHeader(width, height, hasAlpha)
}
