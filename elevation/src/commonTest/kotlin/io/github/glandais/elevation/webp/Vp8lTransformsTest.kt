package io.github.glandais.elevation.webp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the four inverse VP8L transforms.
 *
 * The predictor expectations are **not** taken from [Vp8lTransforms]: this file carries a second,
 * deliberately naive implementation of the spec's pseudo-code (per-channel `IntArray`s, one
 * operation per line, no bit-twiddling) and asserts the two agree. A transform that is wrong in
 * the same way in both would have to be wrong twice, in two different shapes — which is the only
 * cheap defence available before a real tile can be decoded end to end.
 *
 * Everything else — border rules, sign handling, the red-before-blue ordering, the packed index
 * layout — is asserted against numbers computed by hand in the test's comments.
 */
class Vp8lTransformsTest {
    // ---------------------------------------------------------------------------------------
    // Reference implementation, transcribed straight from the spec's pseudo-code.
    // ---------------------------------------------------------------------------------------

    /** Splits a packed ARGB pixel into `[alpha, red, green, blue]`. */
    private fun channels(pixel: Int): IntArray =
        intArrayOf(
            (pixel shr 24) and 0xFF,
            (pixel shr 16) and 0xFF,
            (pixel shr 8) and 0xFF,
            pixel and 0xFF,
        )

    /** Re-packs `[alpha, red, green, blue]`, masking each channel to 8 bits. */
    private fun pack(c: IntArray): Int =
        ((c[0] and 0xFF) shl 24) or
            ((c[1] and 0xFF) shl 16) or
            ((c[2] and 0xFF) shl 8) or
            (c[3] and 0xFF)

    private fun addRef(
        a: Int,
        b: Int,
    ): Int {
        val x = channels(a)
        val y = channels(b)
        return pack(IntArray(4) { (x[it] + y[it]) and 0xFF })
    }

    private fun average2Ref(
        a: Int,
        b: Int,
    ): Int {
        val x = channels(a)
        val y = channels(b)
        return pack(IntArray(4) { (x[it] + y[it]) / 2 })
    }

    private fun selectRef(
        left: Int,
        top: Int,
        topLeft: Int,
    ): Int {
        val l = channels(left)
        val t = channels(top)
        val tl = channels(topLeft)
        var pL = 0
        var pT = 0
        for (k in 0..3) {
            val estimate = l[k] + t[k] - tl[k]
            pL += abs(estimate - l[k])
            pT += abs(estimate - t[k])
        }
        return if (pL < pT) left else top
    }

    private fun clampRef(v: Int): Int =
        if (v < 0) {
            0
        } else if (v > 255) {
            255
        } else {
            v
        }

    private fun clampAddSubtractFullRef(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        val x = channels(a)
        val y = channels(b)
        val z = channels(c)
        return pack(IntArray(4) { clampRef(x[it] + y[it] - z[it]) })
    }

    private fun clampAddSubtractHalfRef(
        a: Int,
        b: Int,
    ): Int {
        val x = channels(a)
        val y = channels(b)
        return pack(IntArray(4) { clampRef(x[it] + (x[it] - y[it]) / 2) })
    }

    private fun predictRef(
        mode: Int,
        left: Int,
        top: Int,
        topLeft: Int,
        topRight: Int,
    ): Int =
        when (mode) {
            0 -> BLACK
            1 -> left
            2 -> top
            3 -> topRight
            4 -> topLeft
            5 -> average2Ref(average2Ref(left, topRight), top)
            6 -> average2Ref(left, topLeft)
            7 -> average2Ref(left, top)
            8 -> average2Ref(topLeft, top)
            9 -> average2Ref(top, topRight)
            10 -> average2Ref(average2Ref(left, topLeft), average2Ref(top, topRight))
            11 -> selectRef(left, top, topLeft)
            12 -> clampAddSubtractFullRef(left, top, topLeft)
            13 -> clampAddSubtractHalfRef(average2Ref(left, top), topLeft)
            else -> throw IllegalArgumentException("mode $mode")
        }

    /** The spec's inverse predictor transform, written the slow and obvious way. */
    private fun inversePredictorRef(
        residual: IntArray,
        width: Int,
        height: Int,
        tileBits: Int,
        transformData: IntArray,
    ): IntArray {
        val out = residual.copyOf()
        val tilesPerRow = (width + (1 shl tileBits) - 1) shr tileBits
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val prediction =
                    when {
                        x == 0 && y == 0 -> BLACK
                        y == 0 -> out[i - 1]
                        x == 0 -> out[i - width]
                        else -> {
                            val mode = (transformData[(y shr tileBits) * tilesPerRow + (x shr tileBits)] shr 8) and 0xFF
                            predictRef(
                                mode,
                                left = out[i - 1],
                                top = out[i - width],
                                topLeft = out[i - width - 1],
                                // The rightmost column borrows the first pixel of the current row.
                                topRight = out[i - width + 1],
                            )
                        }
                    }
                out[i] = addRef(out[i], prediction)
            }
        }
        return out
    }

    private fun hex(argb: IntArray): String = argb.joinToString(", ") { it.toUInt().toString(16).padStart(8, '0') }

    // ---------------------------------------------------------------------------------------
    // Predictor transform
    // ---------------------------------------------------------------------------------------

    @Test
    fun `each of the 14 predictors matches the spec pseudo-code on a 3x3 image`() {
        for (mode in 0..13) {
            val actual = SAMPLE_3X3.copyOf()
            // tileBits = 3 means one 8x8 tile, so the whole image runs on the same mode.
            Vp8lTransforms.inversePredictor(actual, 3, 3, 3, intArrayOf(mode shl 8))
            val expected = inversePredictorRef(SAMPLE_3X3, 3, 3, 3, intArrayOf(mode shl 8))
            assertTrue(
                expected.contentEquals(actual),
                "predictor $mode: expected [${hex(expected)}] but was [${hex(actual)}]",
            )
        }
    }

    @Test
    fun `predictors 1 to 4 pick the expected neighbour on a hand-computed image`() {
        // A residual of zero everywhere makes the reconstructed image *equal* to the prediction,
        // so this reads the neighbour choice directly, with no arithmetic in the way.
        // Row 0 (left-predicted from 0xFF000000) is 0xFF000000 everywhere; column 0 (top) too.
        // Hence every interior neighbour is 0xFF000000 as well and the image stays uniform: use a
        // non-zero residual on the borders instead so the neighbours differ from one another.
        val residual =
            intArrayOf(
                0x00000000,
                0x00000005,
                0x00000000,
                0x00000030,
                0x00000000,
                0x00000000,
                0x00000000,
                0x00000000,
                0x00000000,
            )
        // Row 0 → 0xFF000000, 0xFF000005, 0xFF000005 ; (0,1) → 0xFF000030.
        // For pixel (1,1): left = 0xFF000030, top = 0xFF000005, topLeft = 0xFF000000,
        // topRight = 0xFF000005.
        val expectedAt11 = mapOf(1 to 0x30, 2 to 0x05, 3 to 0x05, 4 to 0x00)
        for ((mode, expectedBlue) in expectedAt11) {
            val argb = residual.copyOf()
            Vp8lTransforms.inversePredictor(argb, 3, 3, 3, intArrayOf(mode shl 8))
            assertEquals(
                expectedBlue,
                argb[4] and 0xFF,
                "predictor $mode should pick the neighbour whose blue channel is $expectedBlue",
            )
            assertEquals(0xFF, (argb[4] shr 24) and 0xFF, "predictor $mode must keep alpha opaque")
        }
    }

    @Test
    fun `predictor addition wraps per channel instead of carrying into the next one`() {
        // Left pixel ends up as 0xFF000080; the second residual adds 0x80 of blue on top of it.
        // Per channel: 0x80 + 0x80 = 0x00 with the carry dropped. A single 32-bit add would give
        // 0xFF000100, i.e. one unit of *green* out of nowhere — the classic bug of this format.
        val argb = intArrayOf(0x00000080, 0x00000080)
        Vp8lTransforms.inversePredictor(argb, 2, 1, 3, intArrayOf(1 shl 8))
        assertEquals(0xFF000080.toInt(), argb[0], "first pixel is the residual over opaque black")
        assertEquals(
            0xFF000000.toInt(),
            argb[1],
            "blue must wrap to 0 without leaking a carry into green",
        )
    }

    @Test
    fun `predictor addition wraps the alpha channel too`() {
        // Alpha 0xFF (from opaque black) + 0x01 of residual alpha = 0x00, not 0x100.
        val argb = intArrayOf(0x01000000, 0x00000000)
        Vp8lTransforms.inversePredictor(argb, 2, 1, 3, intArrayOf(1 shl 8))
        assertEquals(0x00000000, argb[0], "alpha 0xFF + 0x01 wraps to 0x00")
    }

    @Test
    fun `the first pixel uses opaque black whatever the tile mode says`() {
        // Tile mode 2 (top) would read outside the image; the spec pins (0,0) to 0xFF000000.
        val argb = intArrayOf(0x00102030)
        Vp8lTransforms.inversePredictor(argb, 1, 1, 3, intArrayOf(2 shl 8))
        assertEquals(0xFF102030.toInt(), argb[0], "(0,0) is the residual added to opaque black")
    }

    @Test
    fun `the first row and the first column ignore the tile mode`() {
        // The tile declares mode 0 (constant black). Row 0 must still run the left predictor and
        // column 0 the top predictor; only the interior pixels get the constant.
        val argb = intArrayOf(0x00000001, 0x00000002, 0x00000003, 0x00000004, 0x00000005, 0x00000006)
        Vp8lTransforms.inversePredictor(argb, 3, 2, 3, intArrayOf(0 shl 8))
        // Hand-computed: row 0 = 0xFF000001, +2 = 0xFF000003, +3 = 0xFF000006.
        assertEquals(0xFF000001.toInt(), argb[0], "(0,0) over opaque black")
        assertEquals(0xFF000003.toInt(), argb[1], "(1,0) is left-predicted, not black-predicted")
        assertEquals(0xFF000006.toInt(), argb[2], "(2,0) is left-predicted")
        // (0,1) is top-predicted from 0xFF000001, so 0xFF000005.
        assertEquals(0xFF000005.toInt(), argb[3], "(0,1) is top-predicted, not black-predicted")
        // The interior finally obeys the tile: 0xFF000000 + residual.
        assertEquals(0xFF000005.toInt(), argb[4], "(1,1) uses the tile's mode 0 constant")
        assertEquals(0xFF000006.toInt(), argb[5], "(2,1) uses the tile's mode 0 constant")
    }

    @Test
    fun `the top-right neighbour of the last column wraps to the first pixel of the current row`() {
        // Predictor 3 (top-right) on a 2x2 image. Pixel (1,1) has no top-right inside the image;
        // the spec substitutes the leftmost pixel of the *current* row, which is (0,1).
        val argb = intArrayOf(0x00000000, 0x00000000, 0x00000042, 0x00000000)
        Vp8lTransforms.inversePredictor(argb, 2, 2, 3, intArrayOf(3 shl 8))
        // Row 0 is 0xFF000000 twice; (0,1) = top + 0x42 = 0xFF000042.
        assertEquals(0xFF000042.toInt(), argb[2], "(0,1) is top-predicted")
        assertEquals(
            0xFF000042.toInt(),
            argb[3],
            "(1,1) predicts from (0,1), the wrapped top-right, not from row 0",
        )
    }

    @Test
    fun `predictor 11 returns the top pixel when the two distances tie`() {
        // Only the green channel differs: left = 10, top = 20, topLeft = 15 → estimate 15,
        // both Manhattan distances are 5. The spec compares with a strict `pL < pT`, so the tie
        // goes to the top pixel. Flipping it silently offsets every pixel that hits the case.
        val argb = intArrayOf(0x00000F00, 0x00000500, 0x0000FB00, 0x00000000)
        // Row 0: (0,0) = 0xFF000F00 (green 15), (1,0) = green 15 + 5 = 20.
        // (0,1) = green 15 + 251 mod 256 = 10.
        Vp8lTransforms.inversePredictor(argb, 2, 2, 3, intArrayOf(11 shl 8))
        assertEquals(0xFF001400.toInt(), argb[1], "top pixel of the tie")
        assertEquals(0xFF000A00.toInt(), argb[2], "left pixel of the tie")
        assertEquals(
            0xFF001400.toInt(),
            argb[3],
            "a tie in Select must resolve to the top pixel (green 0x14), not the left one (0x0A)",
        )
    }

    @Test
    fun `predictor 13 truncates the half difference toward zero like C does`() {
        // Average2(L, T) = 10, TL = 13 → 10 + (10 - 13) / 2. C truncates toward zero: -3 / 2 = -1,
        // so the result is 9. A `shr 1` would floor to -2 and give 8.
        val argb = intArrayOf(0x0000000D, 0x000000FD, 0x000000FD, 0x00000000)
        // Row 0: (0,0) = 0xFF00000D (blue 13), (1,0) = 13 + 253 mod 256 = 10.
        // (0,1) = top 13 + 253 mod 256 = 10. So for (1,1): L blue = 10, T blue = 10, TL blue = 13.
        // Average2(L, T) blue = 10, alpha = 255. ClampAddSubtractHalf against TL:
        // blue = 10 + (10 - 13) / 2 = 10 - 1 = 9, alpha = 255 + 0 = 255.
        Vp8lTransforms.inversePredictor(argb, 2, 2, 3, intArrayOf(13 shl 8))
        assertEquals(0xFF000009.toInt(), argb[3], "blue 9 from the half-difference truncated toward zero")
    }

    @Test
    fun `predictor 12 clamps instead of wrapping`() {
        // L + T - TL on the blue channel = 0xF0 + 0xF0 - 0x00 = 480, clamped to 255 *before* the
        // residual is added. Wrapping would give 480 & 0xFF = 224 and a completely different pixel.
        val argb = intArrayOf(0x00000000, 0x000000F0, 0x000000F0, 0x00000000)
        // Row 0: (0,0) = 0xFF000000, (1,0) = 0xFF0000F0. (0,1) = 0xFF0000F0.
        // (1,1): L = 0xFF0000F0, T = 0xFF0000F0, TL = 0xFF000000 → alpha 255+255-255 = 255,
        // blue clamp(240 + 240 - 0) = 255.
        Vp8lTransforms.inversePredictor(argb, 2, 2, 3, intArrayOf(12 shl 8))
        assertEquals(0xFF0000FF.toInt(), argb[3], "blue saturates at 255")
    }

    @Test
    fun `each tile drives its own predictor mode`() {
        val width = 4
        val height = 4
        val tileBits = 1 // 2x2 tiles → 2 tiles per row, 2 tile rows
        val transformData = intArrayOf(1 shl 8, 12 shl 8, 7 shl 8, 13 shl 8)
        val residual =
            IntArray(width * height) { i ->
                (((i * 29 + 3) and 0xFF) shl 24) or
                    (((i * 53 + 7) and 0xFF) shl 16) or
                    (((i * 97 + 11) and 0xFF) shl 8) or
                    ((i * 131 + 17) and 0xFF)
            }
        val actual = residual.copyOf()
        Vp8lTransforms.inversePredictor(actual, width, height, tileBits, transformData)
        val expected = inversePredictorRef(residual, width, height, tileBits, transformData)
        assertTrue(
            expected.contentEquals(actual),
            "per-tile modes: expected [${hex(expected)}] but was [${hex(actual)}]",
        )
    }

    // ---------------------------------------------------------------------------------------
    // Cross-colour transform
    // ---------------------------------------------------------------------------------------

    @Test
    fun `cross colour reads green_to_red from the blue channel of the transform pixel`() {
        // The spec stores the ColorTransformElement as: red = red_to_blue, green = green_to_blue,
        // blue = green_to_red. Only red may move here — a decoder that swapped the two green_to_*
        // multipliers would touch blue instead, and still produce a plausible image.
        val multipliers = 0x00000040 // green_to_red = 64, everything else 0
        val argb = intArrayOf(pixel(0xFF, red = 10, green = 20, blue = 50))
        Vp8lTransforms.inverseCrossColor(argb, 1, 1, 3, intArrayOf(multipliers))
        // delta(64, 20) = (64 * 20) >> 5 = 40 → red = 50, blue untouched.
        assertEquals(pixel(0xFF, red = 50, green = 20, blue = 50), argb[0], "only red is corrected")
    }

    @Test
    fun `cross colour treats the multipliers as signed bytes`() {
        // green_to_red = 0xE0 = -32, green_to_blue = 0xC0 = -64, red_to_blue = 0.
        val multipliers = 0x0000C0E0
        val argb = intArrayOf(pixel(0xFF, red = 100, green = 20, blue = 100))
        Vp8lTransforms.inverseCrossColor(argb, 1, 1, 3, intArrayOf(multipliers))
        // delta(-32, 20) = -640 >> 5 = -20 → red = 80. Reading 0xE0 as +224 would give 240.
        // delta(-64, 20) = -1280 >> 5 = -40 → blue = 60. Reading 0xC0 as +192 would give 220.
        assertEquals(
            pixel(0xFF, red = 80, green = 20, blue = 60),
            argb[0],
            "negative multipliers must subtract, not add",
        )
    }

    @Test
    fun `cross colour treats the green channel as a signed byte`() {
        // green = 200 is -56 as an int8. green_to_red = 33 (deliberately not a power of two: with
        // 32 the two readings agree modulo 256 and the bug hides).
        val multipliers = 0x00000021
        val argb = intArrayOf(pixel(0xFF, red = 100, green = 200, blue = 0))
        Vp8lTransforms.inverseCrossColor(argb, 1, 1, 3, intArrayOf(multipliers))
        // delta(33, -56) = -1848 >> 5 = -58 (arithmetic shift floors) → red = 42.
        // Reading green as +200 would give (33 * 200) >> 5 = 206 → red = 50.
        assertEquals(
            pixel(0xFF, red = 42, green = 200, blue = 0),
            argb[0],
            "green above 127 is negative in the delta",
        )
    }

    @Test
    fun `cross colour feeds the already corrected red into the red_to_blue term`() {
        // green_to_red = 64, red_to_blue = 32, green_to_blue = 0.
        val multipliers = 0x00200040
        val argb = intArrayOf(pixel(0xFF, red = 10, green = 20, blue = 50))
        Vp8lTransforms.inverseCrossColor(argb, 1, 1, 3, intArrayOf(multipliers))
        // red = 10 + ((64 * 20) >> 5) = 50. Then blue = 50 + ((32 * 50) >> 5) = 100.
        // Using the *original* red (10) would give blue = 60 — the ordering is load-bearing.
        assertEquals(
            pixel(0xFF, red = 50, green = 20, blue = 100),
            argb[0],
            "blue must use the corrected red (50), not the original (10)",
        )
    }

    @Test
    fun `cross colour treats the corrected red as a signed byte as well`() {
        // green_to_red = 64, red_to_blue = 33 (again not a power of two, see above).
        val multipliers = 0x00210040
        val argb = intArrayOf(pixel(0xFF, red = 200, green = 20, blue = 50))
        Vp8lTransforms.inverseCrossColor(argb, 1, 1, 3, intArrayOf(multipliers))
        // red = 200 + 40 = 240, which is -16 as an int8.
        // blue = 50 + ((33 * -16) >> 5) = 50 + (-528 >> 5) = 50 - 17 = 33.
        // Reading red as +240 would give 50 + 247 = 297 & 0xFF = 41.
        assertEquals(
            pixel(0xFF, red = 240, green = 20, blue = 33),
            argb[0],
            "the corrected red is signed when it feeds red_to_blue",
        )
    }

    @Test
    fun `cross colour wraps red and blue to 8 bits and leaves alpha and green alone`() {
        val multipliers = 0x00000040 // green_to_red = 64
        val argb = intArrayOf(pixel(0x7F, red = 250, green = 20, blue = 30))
        Vp8lTransforms.inverseCrossColor(argb, 1, 1, 3, intArrayOf(multipliers))
        // red = 250 + 40 = 290 → 34 once masked; alpha 0x7F and green 20 untouched.
        assertEquals(pixel(0x7F, red = 34, green = 20, blue = 30), argb[0], "red wraps, alpha and green survive")
    }

    @Test
    fun `cross colour applies each tile's own multipliers`() {
        // 4x1 image, 2-pixel tiles: the left tile adds to red, the right tile subtracts.
        val transformData = intArrayOf(0x00000040, 0x000000C0) // +64 then -64
        val argb =
            intArrayOf(
                pixel(0xFF, red = 100, green = 32, blue = 0),
                pixel(0xFF, red = 100, green = 32, blue = 0),
                pixel(0xFF, red = 100, green = 32, blue = 0),
                pixel(0xFF, red = 100, green = 32, blue = 0),
            )
        Vp8lTransforms.inverseCrossColor(argb, 4, 1, 1, transformData)
        // delta(±64, 32) = ±64 → 164 on the left tile, 36 on the right one.
        assertEquals(pixel(0xFF, red = 164, green = 32, blue = 0), argb[0], "pixel 0, left tile")
        assertEquals(pixel(0xFF, red = 164, green = 32, blue = 0), argb[1], "pixel 1, left tile")
        assertEquals(pixel(0xFF, red = 36, green = 32, blue = 0), argb[2], "pixel 2, right tile")
        assertEquals(pixel(0xFF, red = 36, green = 32, blue = 0), argb[3], "pixel 3, right tile")
    }

    @Test
    fun `cross colour with zero multipliers is the identity`() {
        val argb = intArrayOf(pixel(0x12, 0x34, 0x56, 0x78), pixel(0x9A, 0xBC, 0xDE, 0xF0))
        val before = argb.copyOf()
        Vp8lTransforms.inverseCrossColor(argb, 2, 1, 3, intArrayOf(0))
        assertTrue(before.contentEquals(argb), "no multiplier means no change: [${hex(argb)}]")
    }

    // ---------------------------------------------------------------------------------------
    // Subtract green
    // ---------------------------------------------------------------------------------------

    @Test
    fun `subtract green adds the green channel back to red and blue`() {
        val argb = intArrayOf(pixel(0xFF, red = 10, green = 20, blue = 30))
        Vp8lTransforms.addGreenToBlueAndRed(argb)
        assertEquals(pixel(0xFF, red = 30, green = 20, blue = 50), argb[0], "red and blue both gain the green")
    }

    @Test
    fun `subtract green wraps per channel and never touches alpha or green`() {
        // 200 + 100 = 300 → 44 ; 250 + 100 = 350 → 94. A 32-bit add would push a carry into green.
        val argb = intArrayOf(pixel(0x7F, red = 200, green = 100, blue = 250))
        Vp8lTransforms.addGreenToBlueAndRed(argb)
        assertEquals(
            pixel(0x7F, red = 44, green = 100, blue = 94),
            argb[0],
            "red and blue wrap independently, alpha 0x7F and green 100 stay put",
        )
    }

    @Test
    fun `subtract green processes every pixel of the array`() {
        val argb = IntArray(5) { pixel(0xFF, red = it, green = 1, blue = it) }
        Vp8lTransforms.addGreenToBlueAndRed(argb)
        for (i in argb.indices) {
            assertEquals(pixel(0xFF, red = i + 1, green = 1, blue = i + 1), argb[i], "pixel $i")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Colour indexing
    // ---------------------------------------------------------------------------------------

    @Test
    fun `colour indexing at 8 bits per pixel maps one packed pixel to one output pixel`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFF112233.toInt(), 0xFF445566.toInt())
        val packed = intArrayOf(green(2), green(0), green(1), green(1), green(2), green(0))
        val out = Vp8lTransforms.inverseColorIndexing(packed, 3, 3, 2, palette, 8)
        assertEquals(6, out.size, "3x2 pixels")
        assertTrue(
            intArrayOf(palette[2], palette[0], palette[1], palette[1], palette[2], palette[0])
                .contentEquals(out),
            "palette entries are copied verbatim, alpha included: [${hex(out)}]",
        )
    }

    @Test
    fun `colour indexing at 4 bits per pixel reads the low nibble first and ignores the padding`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFFAAAAAA.toInt(), 0xFFBBBBBB.toInt())
        // width 3 with 2 indices per packed pixel → packedWidth 2, and the last packed pixel of
        // each row carries one index plus 4 bits of padding (0xF here) that must never be read.
        val packed =
            intArrayOf(
                green(0x21),
                green(0xF0), // indices 1, 2 then 0 (+ padding)
                green(0x02),
                green(0xF1), // indices 2, 0 then 1 (+ padding)
            )
        val out = Vp8lTransforms.inverseColorIndexing(packed, 2, 3, 2, palette, 4)
        assertTrue(
            intArrayOf(palette[1], palette[2], palette[0], palette[2], palette[0], palette[1])
                .contentEquals(out),
            "lowest bits first, padding ignored: [${hex(out)}]",
        )
    }

    @Test
    fun `colour indexing at 2 bits per pixel packs four indices per pixel`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFF010101.toInt(), 0xFF020202.toInt(), 0xFF030303.toInt())
        // Row of 6: 0, 1, 2, 3 in the first packed pixel (0b11_10_01_00 = 0xE4), then 1, 0 plus
        // padding bits deliberately set to 1 in the second (0xF1).
        val packed = intArrayOf(green(0xE4), green(0xF1))
        val out = Vp8lTransforms.inverseColorIndexing(packed, 2, 6, 1, palette, 2)
        assertTrue(
            intArrayOf(palette[0], palette[1], palette[2], palette[3], palette[1], palette[0])
                .contentEquals(out),
            "2-bit indices, lowest first: [${hex(out)}]",
        )
    }

    @Test
    fun `colour indexing at 1 bit per pixel packs eight indices per pixel`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        // Width 5: bits 0..4 are 1, 0, 1, 1, 0 → 0b01101 = 0x0D, with 0xE0 of padding on top.
        val packed = intArrayOf(green(0xED))
        val out = Vp8lTransforms.inverseColorIndexing(packed, 1, 5, 1, palette, 1)
        assertTrue(
            intArrayOf(palette[1], palette[0], palette[1], palette[1], palette[0]).contentEquals(out),
            "1-bit indices, lowest first: [${hex(out)}]",
        )
    }

    @Test
    fun `colour indexing returns a fresh array and leaves the packed image alone`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFF111111.toInt())
        val packed = intArrayOf(green(1), green(0))
        val before = packed.copyOf()
        val out = Vp8lTransforms.inverseColorIndexing(packed, 2, 2, 1, palette, 8)
        assertTrue(before.contentEquals(packed), "the packed image is read-only")
        assertEquals(2, out.size, "the output has width * height pixels, not packedWidth * height")
    }

    @Test
    fun `an index past the end of the palette throws instead of reading out of bounds`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFF111111.toInt())
        val packed = intArrayOf(green(0), green(5))
        val failure =
            assertFailsWith<IllegalStateException>("a corrupt index must fail loudly") {
                Vp8lTransforms.inverseColorIndexing(packed, 2, 2, 1, palette, 8)
            }
        assertTrue(
            failure.message!!.contains("5"),
            "the message should name the offending index, was: ${failure.message}",
        )
    }

    @Test
    fun `an index past the end of the palette throws for sub-byte packing too`() {
        val palette = intArrayOf(0xFF000000.toInt(), 0xFF111111.toInt(), 0xFF222222.toInt())
        // 4 bits per pixel: the second index of the packed pixel is 9, well past the 3 entries.
        val packed = intArrayOf(green(0x91))
        assertFailsWith<IllegalStateException>("index 9 is out of a 3-entry palette") {
            Vp8lTransforms.inverseColorIndexing(packed, 1, 2, 1, palette, 4)
        }
    }

    @Test
    fun `an unsupported bit depth is rejected`() {
        assertFailsWith<IllegalArgumentException>("only 1, 2, 4 and 8 bits per pixel exist in VP8L") {
            Vp8lTransforms.inverseColorIndexing(intArrayOf(green(0)), 1, 1, 1, intArrayOf(0), 3)
        }
    }

    @Test
    fun `a packed row too narrow for the image is rejected`() {
        assertFailsWith<IllegalArgumentException>("packedWidth 1 cannot hold 3 pixels at 8 bits each") {
            Vp8lTransforms.inverseColorIndexing(intArrayOf(green(0)), 1, 3, 1, intArrayOf(0), 8)
        }
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()

        /** An arbitrary but varied 3x3 residual image — every channel of every pixel differs. */
        val SAMPLE_3X3 =
            intArrayOf(
                0x00102030,
                0x40506070,
                0x8090A0B0.toInt(),
                0x00204060,
                0x11223344,
                0xC0D0E0F0.toInt(),
                0x0A0B0C0D,
                0x7F8081FE,
                0x01020304,
            )

        fun pixel(
            alpha: Int,
            red: Int,
            green: Int,
            blue: Int,
        ): Int = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

        /** A packed pixel carrying [value] in its green channel, where the palette indices live. */
        fun green(value: Int): Int = (0xFF shl 24) or (value shl 8)
    }
}
