package io.github.glandais.elevation.webp

import kotlin.math.abs

/**
 * The four inverse transforms of the WebP lossless (VP8L) bitstream, as specified in the
 * "Transformations" chapter of the *WebP Lossless Bitstream Specification*.
 *
 * A VP8L stream is entropy-coded *after* up to four reversible transforms have been applied by
 * the encoder, in a declared order. The decoder undoes them in reverse order; this object holds
 * the four inverses and nothing else — the entropy decoding (bit reader, Huffman groups, colour
 * cache, backward references) lives in its own files so each half can be tested on hand-built
 * data instead of on a real tile.
 *
 * ## Pixel representation
 *
 * A pixel is ARGB packed in an `Int` as `(a shl 24) or (r shl 16) or (g shl 8) or b`, stored
 * row-major at `argb[y * width + x]`. Kotlin has no unsigned `Int` arithmetic in the shape this
 * format wants, so every channel is extracted, computed and masked explicitly.
 *
 * ## The trap that runs through the whole file
 *
 * Every addition in VP8L is **per channel, modulo 256**. Doing it as a single 32-bit `+` on the
 * packed value is tempting and wrong: a carry out of blue leaks into green, out of green into
 * red, and so on. The corruption is small — one unit in a neighbouring channel — so a tiny
 * fixture still decodes to something that *looks* like an image, and a Terrarium tile still
 * yields plausible elevations that are quietly off by 256 m. [addPixels] is the only sanctioned
 * way to add two pixels here.
 *
 * The same reasoning applies to signs. The colour-transform multipliers and the green channel
 * feeding them are **signed** 8-bit values (`Int.toByte().toInt()` sign-extends, which is
 * exactly what the spec's `int8` operands mean); reading them as unsigned produces an image
 * that is again almost right.
 */
internal object Vp8lTransforms {
    /** The constant predictor of mode 0, and of the very first pixel: opaque black. */
    private const val OPAQUE_BLACK = 0xFF000000.toInt()

    /**
     * Undoes the predictor transform in place.
     *
     * The image is cut into square tiles of `2^tileBits` pixels a side. [transformData] is the
     * decoded transform image, `ceil(width / 2^tileBits)` pixels per row, and the predictor mode
     * (0..13) of a tile is the **green** channel of its pixel — the other channels are unused.
     * Each pixel of [argb] currently holds the residual; the transform replaces it by
     * `residual + prediction`, per channel modulo 256.
     *
     * The borders are *not* tile-driven, and this is a real trap: whatever mode the tile
     * declares, pixel (0, 0) is predicted by the constant `0xFF000000`, the rest of row 0 by its
     * left neighbour (mode 1) and column 0 of every other row by the pixel above (mode 2). A
     * decoder that honours the tile mode on the border reads outside the image and, worse,
     * usually still produces an image.
     *
     * One more border subtlety, handled here by plain flat indexing rather than by a special
     * case: the top-right neighbour of the rightmost pixel of a row is `argb[i - width + 1]`,
     * which lands on the *first pixel of the current row*. The spec mandates exactly that, and
     * that pixel has already been reconstructed when we need it.
     */
    fun inversePredictor(
        argb: IntArray,
        width: Int,
        height: Int,
        tileBits: Int,
        transformData: IntArray,
    ) {
        if (width <= 0 || height <= 0) return
        require(argb.size >= width * height) {
            "argb holds ${argb.size} pixels, fewer than the ${width * height} of the ${width}x$height image"
        }

        // Row 0: the origin against opaque black, then a running left-predictor.
        argb[0] = addPixels(argb[0], OPAQUE_BLACK)
        for (x in 1 until width) {
            argb[x] = addPixels(argb[x], argb[x - 1])
        }

        val tilesPerRow = (width + (1 shl tileBits) - 1) shr tileBits
        for (y in 1 until height) {
            val rowStart = y * width
            // Column 0 always predicts from the pixel above, tile mode notwithstanding.
            argb[rowStart] = addPixels(argb[rowStart], argb[rowStart - width])
            val tileRowStart = (y shr tileBits) * tilesPerRow
            for (x in 1 until width) {
                val mode = (transformData[tileRowStart + (x shr tileBits)] shr 8) and 0xFF
                val i = rowStart + x
                argb[i] = addPixels(argb[i], predict(mode, argb, i, width))
            }
        }
    }

    /**
     * Applies predictor [mode] at flat index [i], which is guaranteed to be neither on row 0 nor
     * in column 0 — so left, top, top-left and top-right all exist (see [inversePredictor] for
     * the wrap-around that makes top-right legal on the last column).
     *
     * Modes above 13 cannot occur in a well-formed stream: the encoder writes 4 bits and the spec
     * reserves 14 and 15. Failing loudly beats silently predicting black on a corrupt tile.
     */
    private fun predict(
        mode: Int,
        argb: IntArray,
        i: Int,
        width: Int,
    ): Int {
        val left = argb[i - 1]
        val top = argb[i - width]
        val topLeft = argb[i - width - 1]
        val topRight = argb[i - width + 1]
        return when (mode) {
            0 -> OPAQUE_BLACK
            1 -> left
            2 -> top
            3 -> topRight
            4 -> topLeft
            5 -> average2(average2(left, topRight), top)
            6 -> average2(left, topLeft)
            7 -> average2(left, top)
            8 -> average2(topLeft, top)
            9 -> average2(top, topRight)
            10 -> average2(average2(left, topLeft), average2(top, topRight))
            11 -> select(left, top, topLeft)
            12 -> clampAddSubtractFull(left, top, topLeft)
            13 -> clampAddSubtractHalf(average2(left, top), topLeft)
            else -> throw IllegalStateException("Unknown VP8L predictor mode $mode (valid range is 0..13)")
        }
    }

    /**
     * Adds two pixels channel by channel modulo 256 — the only correct way to combine a residual
     * with its prediction. See the class KDoc for why the single 32-bit add is a bug.
     */
    private fun addPixels(
        a: Int,
        b: Int,
    ): Int =
        ((((a ushr 24) + (b ushr 24)) and 0xFF) shl 24) or
            (((((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) and 0xFF) shl 16) or
            (((((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) and 0xFF) shl 8) or
            (((a and 0xFF) + (b and 0xFF)) and 0xFF)

    /**
     * `Average2` of the spec: the per-channel arithmetic mean, `(a + b) / 2`, truncated. Never
     * rounded up — several predictors nest two of these, so a rounding difference compounds.
     */
    private fun average2(
        a: Int,
        b: Int,
    ): Int =
        ((((a ushr 24) + (b ushr 24)) / 2) shl 24) or
            (((((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) / 2) shl 16) or
            (((((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) / 2) shl 8) or
            (((a and 0xFF) + (b and 0xFF)) / 2)

    /**
     * `Select(L, T, TL)` of the spec (predictor 11): a gradient test that returns the *pixel*
     * (not a blend) whose Manhattan distance — summed over all four channels — to the estimate
     * `L + T - TL` is smaller.
     *
     * The comparison is `pL < pT`, so a **tie returns the top pixel**. That asymmetry is not a
     * detail: the encoder subtracts the same prediction, so flipping the tie-break silently
     * offsets every pixel that hits it. The spec's own predictor table writes the call as
     * `Select(L, T, TL)`, matching libwebp's `(pa - pb <= 0) ? top : left`.
     */
    private fun select(
        left: Int,
        top: Int,
        topLeft: Int,
    ): Int {
        var distLeft = 0
        var distTop = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val l = (left ushr shift) and 0xFF
            val t = (top ushr shift) and 0xFF
            val tl = (topLeft ushr shift) and 0xFF
            val estimate = l + t - tl
            distLeft += abs(estimate - l)
            distTop += abs(estimate - t)
        }
        return if (distLeft < distTop) left else top
    }

    /**
     * `ClampAddSubtractFull` of the spec (predictor 12): `Clamp(a + b - c)` per channel, i.e. the
     * classic LOCO-I / gradient prediction clamped back into `0..255`.
     */
    private fun clampAddSubtractFull(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val v =
                clamp(
                    ((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF) - ((c ushr shift) and 0xFF),
                )
            out = out or (v shl shift)
        }
        return out
    }

    /**
     * `ClampAddSubtractHalf` of the spec (predictor 13, applied to `Average2(L, T)` and `TL`):
     * `Clamp(a + (a - b) / 2)` per channel.
     *
     * The division is C's, truncating **toward zero**, so `(-3) / 2 == -1` and not `-2`. Kotlin's
     * `Int` division does the same — but only because both truncate toward zero; anyone
     * tempted to "clean this up" with `shr 1` (which floors) will change the result for every
     * pixel where the half-difference is negative and odd.
     */
    private fun clampAddSubtractHalf(
        a: Int,
        b: Int,
    ): Int {
        var out = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val av = (a ushr shift) and 0xFF
            val bv = (b ushr shift) and 0xFF
            out = out or (clamp(av + (av - bv) / 2) shl shift)
        }
        return out
    }

    /** `Clamp` of the spec: saturate to the `0..255` range a channel has to live in. */
    private fun clamp(v: Int): Int =
        if (v < 0) {
            0
        } else if (v > 255) {
            255
        } else {
            v
        }

    /**
     * Undoes the cross-colour transform in place.
     *
     * Tiling is the same as [inversePredictor]: `2^tileBits` pixels a side,
     * `ceil(width / 2^tileBits)` entries per row of [transformData]. Each transform pixel packs a
     * `ColorTransformElement`, and the spec is explicit about which channel holds what:
     *
     * > Each `ColorTransformElement` `cte` is treated as a pixel in a subresolution image whose
     * > alpha component is `255`, red component is `cte.red_to_blue`, green component is
     * > `cte.green_to_blue`, and blue component is `cte.green_to_red`.
     *
     * So `green_to_red` is the **blue** channel and `green_to_blue` the **green** one — the
     * counter-intuitive pairing that the task sketch for this file had swapped, and that libwebp's
     * `ColorCodeToMultipliers` confirms (`green_to_red` at bits 0..7, `green_to_blue` at 8..15,
     * `red_to_blue` at 16..23). Swapping them decodes most tiles to something plausible, which is
     * precisely why it needs a test with two different multipliers.
     *
     * The inverse itself:
     * ```
     * tmp_red  += ColorTransformDelta(green_to_red,  green)
     * tmp_blue += ColorTransformDelta(green_to_blue, green)
     * tmp_blue += ColorTransformDelta(red_to_blue,   tmp_red and 0xFF)
     * ```
     * Ordering is load-bearing: the red fed to `red_to_blue` is the **already corrected** red,
     * masked back to 8 bits first. Alpha and green are untouched by this transform.
     */
    fun inverseCrossColor(
        argb: IntArray,
        width: Int,
        height: Int,
        tileBits: Int,
        transformData: IntArray,
    ) {
        if (width <= 0 || height <= 0) return
        require(argb.size >= width * height) {
            "argb holds ${argb.size} pixels, fewer than the ${width * height} of the ${width}x$height image"
        }

        val tilesPerRow = (width + (1 shl tileBits) - 1) shr tileBits
        for (y in 0 until height) {
            val rowStart = y * width
            val tileRowStart = (y shr tileBits) * tilesPerRow
            for (x in 0 until width) {
                val multipliers = transformData[tileRowStart + (x shr tileBits)]
                val greenToRed = (multipliers and 0xFF).toByte()
                val greenToBlue = ((multipliers shr 8) and 0xFF).toByte()
                val redToBlue = ((multipliers shr 16) and 0xFF).toByte()

                val i = rowStart + x
                val pixel = argb[i]
                val green = ((pixel shr 8) and 0xFF).toByte()
                val red = ((pixel shr 16) and 0xFF) + colorTransformDelta(greenToRed, green)
                val correctedRed = red and 0xFF
                val blue =
                    (pixel and 0xFF) +
                        colorTransformDelta(greenToBlue, green) +
                        colorTransformDelta(redToBlue, correctedRed.toByte())
                // Keep alpha and green (0xFF00FF00) exactly as they were.
                argb[i] = (pixel and 0xFF00FF00.toInt()) or (correctedRed shl 16) or (blue and 0xFF)
            }
        }
    }

    /**
     * `ColorTransformDelta(t, c) = (t * c) >> 5` with **both operands signed 8-bit**, hence the
     * [Byte] parameters: passing the raw `0..255` channel would turn every value above 127 into a
     * large positive number and skew the correction by up to 8 units.
     *
     * The shift is arithmetic (Kotlin's `shr`), so negative products round toward negative
     * infinity — which is what C's `>>` on a signed int does on every platform libwebp targets,
     * and what the encoder assumed. The spec declares the return type `int8`; truncating it or
     * not is immaterial because the caller masks the sum to 8 bits, and truncation preserves the
     * value modulo 256.
     */
    private fun colorTransformDelta(
        t: Byte,
        c: Byte,
    ): Int = (t.toInt() * c.toInt()) shr 5

    /**
     * Undoes the subtract-green transform in place: `red += green` and `blue += green`, each
     * modulo 256, alpha and green untouched.
     *
     * The cheapest of the four transforms, and the most frequently applied — photographic content
     * is strongly correlated across channels. There is no tiling and no transform image: the
     * transform is a single bit in the header.
     */
    fun addGreenToBlueAndRed(argb: IntArray) {
        for (i in argb.indices) {
            val pixel = argb[i]
            val green = (pixel shr 8) and 0xFF
            val red = (((pixel shr 16) and 0xFF) + green) and 0xFF
            val blue = ((pixel and 0xFF) + green) and 0xFF
            argb[i] = (pixel and 0xFF00FF00.toInt()) or (red shl 16) or blue
        }
    }

    /**
     * Undoes the colour-indexing (palette) transform, returning a **fresh** array of
     * `width * height` pixels — unlike the other three, this transform changes the pixel count, so
     * it cannot work in place.
     *
     * When the palette has at most 16 entries the encoder also packs several pixels into one, so
     * the entropy-coded image is narrower than the real one: [packed] holds [packedWidth] pixels
     * per row, each carrying `8 / bitsPerPixel` indices in its **green** channel, **lowest bits
     * first**. [bitsPerPixel] is 8, 4, 2 or 1 for palettes of more than 16, at most 16, at most 4
     * and at most 2 entries respectively.
     *
     * `packedWidth` is `ceil(width / (8 / bitsPerPixel))`, so the last packed pixel of a row can
     * carry indices that fall past `width`. Those are padding and are simply never read — which is
     * why this iterates over the output geometry, not over [packed].
     *
     * An index past the end of [palette] means a corrupt or hostile stream. It throws
     * [IllegalStateException] rather than reading out of bounds: on a target like `wasmWasi` an
     * unchecked read is a trap with no useful message, and the palette is attacker-controlled data
     * in a tile fetched over the network.
     */
    fun inverseColorIndexing(
        packed: IntArray,
        packedWidth: Int,
        width: Int,
        height: Int,
        palette: IntArray,
        bitsPerPixel: Int,
    ): IntArray {
        require(bitsPerPixel == 1 || bitsPerPixel == 2 || bitsPerPixel == 4 || bitsPerPixel == 8) {
            "bitsPerPixel must be 1, 2, 4 or 8, was $bitsPerPixel"
        }
        val pixelsPerPacked = 8 / bitsPerPixel
        require(packedWidth >= (width + pixelsPerPacked - 1) / pixelsPerPacked) {
            "packedWidth $packedWidth cannot hold $width pixels at $bitsPerPixel bits per pixel"
        }
        require(packed.size >= packedWidth * height) {
            "packed holds ${packed.size} pixels, fewer than the ${packedWidth * height} rows of $packedWidth demand"
        }

        val mask = (1 shl bitsPerPixel) - 1
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val packedRow = y * packedWidth
            val outRow = y * width
            for (x in 0 until width) {
                val green = (packed[packedRow + x / pixelsPerPacked] shr 8) and 0xFF
                val index = (green shr (bitsPerPixel * (x % pixelsPerPacked))) and mask
                if (index >= palette.size) {
                    throw IllegalStateException(
                        "Palette index $index at ($x, $y) is out of range for a ${palette.size}-entry palette",
                    )
                }
                out[outRow + x] = palette[index]
            }
        }
        return out
    }
}
