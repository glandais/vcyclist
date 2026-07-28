package io.github.glandais.elevation.webp

/**
 * A decoded lossless WebP image: [width] × [height] pixels, ARGB packed in an `Int` as
 * `(a shl 24) or (r shl 16) or (g shl 8) or b`, stored row-major in [argb].
 *
 * The same packing every other file in this package uses, so a decoded image can be handed
 * straight to [Vp8lTransforms] or compared against the fixtures without a conversion step.
 */
internal class Vp8lImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

/**
 * The VP8L entropy decoder: turns a RIFF/WEBP file holding a lossless chunk into ARGB pixels.
 *
 * This is the piece that reads the bitstream. The container walk ([RiffParser]), the bit order
 * ([Vp8lBitReader]), the canonical prefix codes ([HuffmanTree]) and the four inverse transforms
 * ([Vp8lTransforms]) each live in their own file and are tested on their own; what is left here is
 * the *structure* of the stream — the transform declarations, the Huffman groups, the LZ77 back
 * references and the colour cache — plus the wiring that applies the transforms in the right order.
 *
 * Everything below follows the *WebP Lossless Bitstream Specification*; where the specification is
 * ambiguous or where a reading of it is easy to get plausibly wrong, the comment names libwebp's
 * `src/dec/vp8l_dec.c` as the tie-breaker, because that is the encoder's counterpart and therefore
 * the definition in practice.
 *
 * ## The three orderings that decide whether a real tile decodes
 *
 *  1. **Colour cache before meta-Huffman.** The specification's grammar reads
 *     `spatially-coded-image = color-cache-info meta-prefix data`, and libwebp matches it:
 *     `DecodeImageStream` reads the cache bit, *then* calls `ReadHuffmanCodes`, which opens with
 *     the meta-Huffman bit. Swapping the two desynchronises the stream on the very first image
 *     that uses either.
 *  2. **Literals are green, red, blue, alpha.** The green symbol is read first because it is the
 *     one that also carries the length codes and the colour-cache indices; the other three follow
 *     in that order, which is *not* the order of the packed pixel.
 *  3. **Transforms are declared forwards and undone backwards.** The encoder applied them in the
 *     order it wrote them, so the decoder walks the list in reverse.
 *
 * ## Performance
 *
 * A 512 × 512 Terrarium tile is 262 144 pixels. Each costs one Huffman symbol walk of a few
 * integer operations (see the `puff.c` note in [HuffmanTree]) plus, for the ones that come from a
 * back reference or the colour cache, no bit reading at all. Measured on the JVM against a real
 * 512 × 512 Terrarium tile (400 kB, predictor + cross-colour, a 16-entry colour cache and a
 * 6-group meta-Huffman partition) this runs at **16 ms per tile** — two orders of magnitude under
 * the network round trip that fetched it, which is the only budget that matters here. No
 * lookup-table decoder is warranted, and building one would require [Vp8lBitReader] to grow a
 * peek/skip pair it deliberately does not have.
 */
internal object Vp8lDecoder {
    /** Transform type 0: spatial prediction from already-decoded neighbours. */
    private const val TRANSFORM_PREDICTOR = 0

    /** Transform type 1: cross-channel colour decorrelation. */
    private const val TRANSFORM_CROSS_COLOR = 1

    /** Transform type 2: `red -= green`, `blue -= green`. Carries no data at all. */
    private const val TRANSFORM_SUBTRACT_GREEN = 2

    /** Transform type 3: palette, possibly with several pixels packed per stored pixel. */
    private const val TRANSFORM_COLOR_INDEXING = 3

    /** Symbols 0..255 of the green code are literal green values. */
    private const val LITERAL_CODES = 256

    /** Symbols 256..279 of the green code are LZ77 length prefixes. */
    private const val LENGTH_CODES = 24

    /** The distance code's alphabet: 24 prefix codes plus the 16 that spell short distances. */
    private const val DISTANCE_CODES = 40

    /** First green symbol that means "take this entry out of the colour cache". */
    private const val CACHE_CODE_BASE = LITERAL_CODES + LENGTH_CODES

    /**
     * Largest colour cache the format allows, i.e. 2048 entries. libwebp's `MAX_CACHE_BITS` is the
     * same 11; a stream asking for more is malformed, and a stream asking for 0 while claiming to
     * have a cache is too — "no cache" is spelled by the preceding bit being clear.
     */
    private const val MAX_CACHE_BITS = 11

    /**
     * The multiplier of the colour cache's hash, straight from the specification. It is applied as
     * an unsigned 32-bit multiply — which is what Kotlin's wrapping `Int` multiply already is, bit
     * for bit — and the product's *top* [cacheBits] bits are the index, hence the `ushr`.
     */
    private const val CACHE_HASH_MULTIPLIER = 0x1e35a7bd

    /**
     * How many distance codes are spelled as a 2-D neighbourhood offset rather than as a plain
     * backward distance. Codes above this are `code - 120` pixels back.
     */
    private const val PLANE_CODES = 120

    /**
     * libwebp's `kCodeToPlane`, verbatim.
     *
     * The first 120 distance codes do not mean "n pixels back": they mean "the pixel at
     * `(-xoffset, -yoffset)`", ordered by increasing Euclidean-ish distance so that the near
     * neighbourhood — above, above-left, above-right — gets the shortest codes. Each byte packs
     * `yoffset` in its high nibble and `8 - xoffset` in its low one, which is how the table stays a
     * `uint8_t[120]` and how [planeCodeToDistance] recovers a *signed* xoffset without a signed
     * table.
     *
     * This is stored in libwebp's packed form on purpose rather than transcribed into 120
     * `(x, y)` pairs: the published pair table is easy to mis-order by a couple of entries around
     * the diagonals (the run `(4, 2), (-4, 2), (0, 5), (3, 4), …` reads like a typo and is not),
     * and such a slip would corrupt only the tiles that happen to use those codes.
     */
    private val CODE_TO_PLANE =
        intArrayOf(
            0x18,
            0x07,
            0x17,
            0x19,
            0x28,
            0x06,
            0x27,
            0x29,
            0x16,
            0x1a,
            0x26,
            0x2a,
            0x38,
            0x05,
            0x37,
            0x39,
            0x15,
            0x1b,
            0x36,
            0x3a,
            0x25,
            0x2b,
            0x48,
            0x04,
            0x47,
            0x49,
            0x14,
            0x1c,
            0x35,
            0x3b,
            0x46,
            0x4a,
            0x24,
            0x2c,
            0x58,
            0x45,
            0x4b,
            0x34,
            0x3c,
            0x03,
            0x57,
            0x59,
            0x13,
            0x1d,
            0x56,
            0x5a,
            0x23,
            0x2d,
            0x44,
            0x4c,
            0x55,
            0x5b,
            0x33,
            0x3d,
            0x68,
            0x02,
            0x67,
            0x69,
            0x12,
            0x1e,
            0x66,
            0x6a,
            0x22,
            0x2e,
            0x54,
            0x5c,
            0x43,
            0x4d,
            0x65,
            0x6b,
            0x32,
            0x3e,
            0x78,
            0x01,
            0x77,
            0x79,
            0x53,
            0x5d,
            0x11,
            0x1f,
            0x64,
            0x6c,
            0x42,
            0x4e,
            0x76,
            0x7a,
            0x21,
            0x2f,
            0x75,
            0x7b,
            0x31,
            0x3f,
            0x63,
            0x6d,
            0x52,
            0x5e,
            0x00,
            0x74,
            0x7c,
            0x41,
            0x4f,
            0x10,
            0x20,
            0x62,
            0x6e,
            0x30,
            0x73,
            0x7d,
            0x51,
            0x5f,
            0x40,
            0x72,
            0x7e,
            0x61,
            0x6f,
            0x50,
            0x71,
            0x7f,
            0x60,
            0x70,
        )

    /**
     * Decodes a complete RIFF/WEBP file holding a VP8L chunk.
     *
     * @throws IllegalStateException on anything malformed — a container that is not a lossless
     *   WebP, a bitstream that runs out, a prefix code that is over-subscribed, a back reference
     *   pointing before the start of the image, a palette index past the palette. Every message
     *   names what was expected and what was found; none of them let a broken tile through as
     *   plausible-looking elevations.
     */
    fun decode(bytes: ByteArray): Vp8lImage {
        val reader = Vp8lBitReader(RiffParser.vp8lPayload(bytes))
        val header = readVp8lHeader(reader)
        val width = header.width
        val height = header.height

        val transforms = readTransforms(reader, width, height)
        // Only the colour-indexing transform changes the geometry, and it does so for everything
        // read after it — including the main image. `packedWidth` is what the entropy coder sees.
        val packedWidth = currentWidthAfter(transforms, width)

        var argb = decodeImageStream(reader, packedWidth, height, allowMetaHuffman = true)
        var currentWidth = packedWidth

        for (index in transforms.indices.reversed()) {
            val transform = transforms[index]
            when (transform.type) {
                TRANSFORM_PREDICTOR -> {
                    checkGeometry(transform, currentWidth, "predictor")
                    Vp8lTransforms.inversePredictor(argb, currentWidth, height, transform.bits, transform.data)
                }

                TRANSFORM_CROSS_COLOR -> {
                    checkGeometry(transform, currentWidth, "cross-colour")
                    Vp8lTransforms.inverseCrossColor(argb, currentWidth, height, transform.bits, transform.data)
                }

                TRANSFORM_SUBTRACT_GREEN -> Vp8lTransforms.addGreenToBlueAndRed(argb)

                TRANSFORM_COLOR_INDEXING -> {
                    argb =
                        Vp8lTransforms.inverseColorIndexing(
                            packed = argb,
                            packedWidth = currentWidth,
                            width = transform.width,
                            height = height,
                            palette = transform.data,
                            bitsPerPixel = transform.bits,
                        )
                    currentWidth = transform.width
                }
            }
        }

        check(currentWidth == width && argb.size == width * height) {
            "VP8L decode ended with a ${currentWidth}x$height image of ${argb.size} pixels, " +
                "not the ${width}x$height the header declared"
        }
        return Vp8lImage(width, height, argb)
    }

    /**
     * The same pixels as packed RGBA bytes, in `RawTile.rgba` order — four bytes per pixel,
     * red, green, blue, alpha, row-major.
     *
     * ARGB-in-an-`Int` is what the transforms want; RGBA-as-bytes is what every image consumer in
     * this project wants. Keeping the split explicit here means neither half has to know about the
     * other's convention, and the channel order is written down exactly once.
     */
    fun decodeToRgba(bytes: ByteArray): ByteArray {
        val image = decode(bytes)
        val rgba = ByteArray(image.argb.size * 4)
        for (i in image.argb.indices) {
            val pixel = image.argb[i]
            val at = i * 4
            rgba[at] = ((pixel shr 16) and 0xFF).toByte()
            rgba[at + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgba[at + 2] = (pixel and 0xFF).toByte()
            rgba[at + 3] = ((pixel ushr 24) and 0xFF).toByte()
        }
        return rgba
    }

    // -------------------------------------------------------------------------------------------
    // Transforms
    // -------------------------------------------------------------------------------------------

    /**
     * One declared transform, with the geometry that was in effect when it was declared.
     *
     * [width] is load-bearing for the colour-indexing case and only for it: that transform is
     * declared while the image is still [width] pixels wide and shrinks it afterwards, so [width]
     * is exactly the width the inverse must expand *back* to. For the other three it is simply the
     * width they operate on, and [checkGeometry] asserts it never drifted.
     *
     * [bits] means the tile size exponent for the predictor and cross-colour transforms, and the
     * bits-per-pixel of the packing for colour indexing. [data] is the decoded sub-image, or the
     * palette, or empty for subtract-green.
     */
    private class Transform(
        val type: Int,
        val width: Int,
        val bits: Int,
        val data: IntArray,
    )

    /**
     * Reads the transform declarations that precede the image data.
     *
     * The list is a run of `1` bits, each introducing a 2-bit type, terminated by a `0`. A type may
     * appear **at most once** — the specification says so, and the reason is that the inverses do
     * not commute with themselves, so a repeated type has no defined meaning. libwebp rejects it
     * too (`VP8L_BITSTREAM_ERROR`); accepting it would silently apply a transform twice.
     */
    private fun readTransforms(
        reader: Vp8lBitReader,
        width: Int,
        height: Int,
    ): List<Transform> {
        val transforms = mutableListOf<Transform>()
        var seen = 0
        var currentWidth = width
        while (reader.readBit() == 1) {
            val type = reader.readBits(2)
            check(seen and (1 shl type) == 0) {
                "VP8L declares transform type $type twice; each of the four may appear at most once"
            }
            seen = seen or (1 shl type)
            when (type) {
                TRANSFORM_PREDICTOR, TRANSFORM_CROSS_COLOR -> {
                    val tileBits = reader.readBits(3) + 2
                    val data =
                        decodeImageStream(
                            reader,
                            subSampleSize(currentWidth, tileBits),
                            subSampleSize(height, tileBits),
                            allowMetaHuffman = false,
                        )
                    transforms += Transform(type, currentWidth, tileBits, data)
                }

                TRANSFORM_SUBTRACT_GREEN ->
                    transforms += Transform(type, currentWidth, bits = 0, data = IntArray(0))

                TRANSFORM_COLOR_INDEXING -> {
                    val paletteSize = reader.readBits(8) + 1
                    val palette = decodeImageStream(reader, paletteSize, 1, allowMetaHuffman = false)
                    undeltaPalette(palette)
                    val bitsPerPixel = paletteBitsPerPixel(paletteSize)
                    transforms += Transform(type, currentWidth, bitsPerPixel, palette)
                    // From here on — for any transform declared later *and* for the main image —
                    // the stream is narrower, because 8 / bitsPerPixel indices share one pixel.
                    currentWidth = subSampleSize(currentWidth, packingBits(bitsPerPixel))
                }
            }
        }
        return transforms
    }

    /**
     * The width the entropy coder sees, i.e. [width] narrowed by a colour-indexing transform if one
     * was declared. Recomputed from the list rather than returned alongside it so that
     * [readTransforms] stays a pure "what did the stream say" function.
     */
    private fun currentWidthAfter(
        transforms: List<Transform>,
        width: Int,
    ): Int {
        var result = width
        for (transform in transforms) {
            if (transform.type == TRANSFORM_COLOR_INDEXING) {
                result = subSampleSize(result, packingBits(transform.bits))
            }
        }
        return result
    }

    /**
     * Turns the delta-coded palette the stream carries into absolute colours.
     *
     * Palettes are stored as differences because neighbouring entries are usually close, and the
     * entropy coder pays for the difference rather than for the colour. The addition is the usual
     * **per channel modulo 256** one — a single 32-bit `+` would carry blue into green and produce
     * a palette that is subtly, and only sometimes, wrong.
     */
    private fun undeltaPalette(palette: IntArray) {
        for (i in 1 until palette.size) {
            palette[i] = addPixels(palette[i], palette[i - 1])
        }
    }

    /** Adds two pixels channel by channel, modulo 256. */
    private fun addPixels(
        a: Int,
        b: Int,
    ): Int =
        ((((a ushr 24) + (b ushr 24)) and 0xFF) shl 24) or
            (((((a shr 16) and 0xFF) + ((b shr 16) and 0xFF)) and 0xFF) shl 16) or
            (((((a shr 8) and 0xFF) + ((b shr 8) and 0xFF)) and 0xFF) shl 8) or
            (((a and 0xFF) + (b and 0xFF)) and 0xFF)

    /**
     * How many bits one palette index takes: 8, 4, 2 or 1 for palettes of more than 16, more than
     * 4, more than 2, and at most 2 entries.
     */
    private fun paletteBitsPerPixel(paletteSize: Int): Int =
        when {
            paletteSize > 16 -> 8
            paletteSize > 4 -> 4
            paletteSize > 2 -> 2
            else -> 1
        }

    /**
     * `log2(8 / bitsPerPixel)` — the shift that turns a real width into the packed one. Written as
     * a lookup rather than a division so the four legal values are visible at a glance.
     */
    private fun packingBits(bitsPerPixel: Int): Int =
        when (bitsPerPixel) {
            8 -> 0
            4 -> 1
            2 -> 2
            else -> 3
        }

    /** Guards the invariant that only colour indexing may change the width mid-pipeline. */
    private fun checkGeometry(
        transform: Transform,
        currentWidth: Int,
        name: String,
    ) {
        check(transform.width == currentWidth) {
            "the $name transform was declared for a width of ${transform.width} but is being " +
                "undone on an image $currentWidth pixels wide"
        }
    }

    // -------------------------------------------------------------------------------------------
    // Entropy-coded images
    // -------------------------------------------------------------------------------------------

    /**
     * Reads one entropy-coded image of [width] × [height] pixels: the optional colour cache, the
     * optional meta-Huffman partition, the Huffman groups, then the pixel data.
     *
     * [allowMetaHuffman] is the only difference between the two shapes the specification names. The
     * *spatially-coded image* — the main image — may split itself into blocks that each use their
     * own set of prefix codes; an *entropy-coded image* — a predictor or cross-colour sub-image, a
     * palette, and the meta-Huffman index image itself — may not, which is what stops the recursion
     * at one level. Both may carry a colour cache, and both read the cache bit first.
     */
    private fun decodeImageStream(
        reader: Vp8lBitReader,
        width: Int,
        height: Int,
        allowMetaHuffman: Boolean,
    ): IntArray {
        val cacheBits =
            if (reader.readBit() == 1) {
                val bits = reader.readBits(4)
                check(bits in 1..MAX_CACHE_BITS) {
                    "VP8L colour cache asks for $bits bits, outside the legal 1..$MAX_CACHE_BITS"
                }
                bits
            } else {
                0
            }

        var huffmanBits = 0
        var huffmanWidth = 0
        var groupOfBlock: IntArray? = null
        var numGroups = 1
        if (allowMetaHuffman && reader.readBit() == 1) {
            huffmanBits = reader.readBits(3) + 2
            huffmanWidth = subSampleSize(width, huffmanBits)
            val huffmanHeight = subSampleSize(height, huffmanBits)
            val entropyImage = decodeImageStream(reader, huffmanWidth, huffmanHeight, allowMetaHuffman = false)
            // The group index of a block lives in the red and green channels of its pixel, as a
            // 16-bit big-endian pair. Red is the high byte: a partition with more than 256 groups
            // is rare but perfectly legal, and dropping red decodes it into the wrong codes.
            val indices = IntArray(entropyImage.size)
            var groupCount = 0
            for (i in entropyImage.indices) {
                val group = (entropyImage[i] shr 8) and 0xFFFF
                indices[i] = group
                if (group >= groupCount) groupCount = group + 1
            }
            groupOfBlock = indices
            numGroups = groupCount
        }

        val groups = Array(numGroups) { HuffmanGroup.read(reader, cacheBits) }
        return decodePixels(reader, width, height, cacheBits, groups, groupOfBlock, huffmanBits, huffmanWidth)
    }

    /**
     * The five prefix codes one block of an image is decoded with.
     *
     * The alphabet sizes are fixed by the format except for green, which is extended by one symbol
     * per colour-cache entry — the cache indices ride in the same code as the literals and the
     * lengths, which is what makes a cache hit cost a single symbol.
     */
    private class HuffmanGroup(
        val green: HuffmanTree,
        val red: HuffmanTree,
        val blue: HuffmanTree,
        val alpha: HuffmanTree,
        val distance: HuffmanTree,
    ) {
        companion object {
            /**
             * Reads the five codes in the order the bitstream writes them: green, red, blue, alpha,
             * distance. That order is not the order of the packed pixel and not the order of the
             * literal read either — it is simply the order libwebp's `HuffIndex` enumerates.
             */
            fun read(
                reader: Vp8lBitReader,
                cacheBits: Int,
            ): HuffmanGroup {
                val greenAlphabet = LITERAL_CODES + LENGTH_CODES + if (cacheBits > 0) 1 shl cacheBits else 0
                return HuffmanGroup(
                    green = HuffmanTree.read(reader, greenAlphabet),
                    red = HuffmanTree.read(reader, LITERAL_CODES),
                    blue = HuffmanTree.read(reader, LITERAL_CODES),
                    alpha = HuffmanTree.read(reader, LITERAL_CODES),
                    distance = HuffmanTree.read(reader, DISTANCE_CODES),
                )
            }
        }
    }

    /**
     * The decode loop proper: reads symbols until [width] × [height] pixels have been produced.
     *
     * Three kinds of green symbol, and the colour cache is fed by **all three**. A literal is
     * inserted when it is written, every pixel a back reference copies is inserted as it is copied,
     * and a cache hit re-inserts the value it just read. That last one looks redundant — it hashes
     * to the slot it came from, so it is a no-op — but the first two are not, and getting the copy
     * case wrong is the classic failure of this decoder: small fixtures have no back references
     * long enough to matter, so they pass, and a real tile decodes into noise from the first cache
     * hit that follows a copy.
     *
     * [groupOfBlock] is `null` when the image has no meta-Huffman partition, in which case every
     * pixel uses group 0. When it is present the group is looked up per pixel from the position of
     * the *next* symbol to read — which is why the lookup sits at the top of the loop and not after
     * a back reference has advanced the cursor.
     */
    private fun decodePixels(
        reader: Vp8lBitReader,
        width: Int,
        height: Int,
        cacheBits: Int,
        groups: Array<HuffmanGroup>,
        groupOfBlock: IntArray?,
        huffmanBits: Int,
        huffmanWidth: Int,
    ): IntArray {
        val total = width * height
        val argb = IntArray(total)
        val cache = if (cacheBits > 0) IntArray(1 shl cacheBits) else null
        val cacheShift = 32 - cacheBits

        var x = 0
        var y = 0
        var pos = 0
        while (pos < total) {
            val group =
                if (groupOfBlock == null) {
                    groups[0]
                } else {
                    val block = (y shr huffmanBits) * huffmanWidth + (x shr huffmanBits)
                    groups[groupOfBlock[block]]
                }

            val green = group.green.readSymbol(reader)
            when {
                green < LITERAL_CODES -> {
                    val red = group.red.readSymbol(reader)
                    val blue = group.blue.readSymbol(reader)
                    val alpha = group.alpha.readSymbol(reader)
                    val pixel = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                    argb[pos++] = pixel
                    if (cache != null) cache[hash(pixel, cacheShift)] = pixel
                    x++
                    if (x == width) {
                        x = 0
                        y++
                    }
                }

                green < CACHE_CODE_BASE -> {
                    val length = readPrefixValue(reader, green - LITERAL_CODES)
                    val distanceCode = readPrefixValue(reader, group.distance.readSymbol(reader))
                    val distance = planeCodeToDistance(width, distanceCode)
                    check(distance <= pos) {
                        "VP8L back reference at pixel $pos points $distance pixels back, before the " +
                            "start of the image"
                    }
                    check(pos + length <= total) {
                        "VP8L back reference at pixel $pos copies $length pixels, past the end of " +
                            "the ${width}x$height image ($total pixels)"
                    }
                    // Pixel by pixel, forwards, deliberately: a back reference of distance 1 and
                    // length 8 is a run of the same colour, and the source range overlaps the
                    // destination. `copyInto` would read the not-yet-written tail.
                    var src = pos - distance
                    repeat(length) {
                        val pixel = argb[src++]
                        argb[pos++] = pixel
                        if (cache != null) cache[hash(pixel, cacheShift)] = pixel
                    }
                    val advanced = x + length
                    x = advanced % width
                    y += advanced / width
                }

                else -> {
                    val key = green - CACHE_CODE_BASE
                    check(cache != null) {
                        "VP8L green symbol $green is a colour-cache index, but the stream declared no cache"
                    }
                    check(key < cache.size) {
                        "VP8L colour-cache index $key is out of range for a ${cache.size}-entry cache"
                    }
                    val pixel = cache[key]
                    argb[pos++] = pixel
                    cache[hash(pixel, cacheShift)] = pixel
                    x++
                    if (x == width) {
                        x = 0
                        y++
                    }
                }
            }
        }
        return argb
    }

    /**
     * The colour cache's hash: the top [cacheBits] bits of a wrapping 32-bit multiply, where
     * `cacheShift` is `32 - cacheBits`.
     *
     * `ushr` and not `shr`, because the product's sign bit is data. Callers must not reach here
     * with `cacheBits == 0`: a shift of 32 is masked to 0 on every Kotlin target, so the index
     * would be the whole product and the array access would fail far from the cause — which is why
     * the cache array is `null` rather than empty in that case.
     */
    private fun hash(
        argb: Int,
        cacheShift: Int,
    ): Int = (CACHE_HASH_MULTIPLIER * argb) ushr cacheShift

    /**
     * Expands an LZ77 prefix code into the value it stands for — a length in pixels, or a distance
     * code — reading its extra bits from [reader].
     *
     * The first four codes are the literal values 1..4; past that, each pair of codes covers a
     * range twice as wide as the previous pair, so code `p` carries `(p - 2) / 2` extra bits above
     * a base of `(2 + (p and 1)) shl extraBits`. The widest case is distance code 39, with 18 extra
     * bits — comfortably inside what [Vp8lBitReader] serves in one call.
     */
    private fun readPrefixValue(
        reader: Vp8lBitReader,
        prefixCode: Int,
    ): Int {
        if (prefixCode < 4) return prefixCode + 1
        val extraBits = (prefixCode - 2) shr 1
        val offset = (2 + (prefixCode and 1)) shl extraBits
        return offset + reader.readBits(extraBits) + 1
    }

    /**
     * Turns a distance code into a backward distance in pixels, for an image [width] pixels wide.
     *
     * Codes above [PLANE_CODES] are plain distances, biased so that code 121 is 1. The first 120
     * name a neighbour instead: `xoffset` may be negative (the pixel above and to the right is a
     * fine predictor and is 120 pixels *forward* of "one row back"), so the arithmetic can land on
     * zero or below for a very narrow image. The specification clamps that to 1 rather than
     * rejecting it, and so does libwebp — dropping the clamp turns a legal one-pixel-wide image
     * into a decode failure.
     */
    private fun planeCodeToDistance(
        width: Int,
        distanceCode: Int,
    ): Int {
        if (distanceCode > PLANE_CODES) return distanceCode - PLANE_CODES
        check(distanceCode >= 1) { "VP8L distance code $distanceCode is not a valid code (they start at 1)" }
        val packed = CODE_TO_PLANE[distanceCode - 1]
        val distance = (packed shr 4) * width + (8 - (packed and 0x0F))
        return if (distance > 0) distance else 1
    }

    /**
     * `ceil(size / 2^bits)` — how many tiles of `2^bits` pixels a side it takes to cover [size].
     * The specification calls it `DIV_ROUND_UP`; every sub-image dimension in the format is one of
     * these, and rounding down instead loses the partial tile at the right or bottom edge.
     */
    private fun subSampleSize(
        size: Int,
        bits: Int,
    ): Int = (size + (1 shl bits) - 1) shr bits
}
