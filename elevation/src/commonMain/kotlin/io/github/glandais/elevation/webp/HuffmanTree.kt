package io.github.glandais.elevation.webp

/**
 * A canonical Huffman code, as VP8L stores them.
 *
 * The WebP lossless bitstream ("Decoding of Huffman Codes" in the specification) never transmits
 * the codes themselves: it transmits, for every symbol of the alphabet, the *length in bits* of its
 * code, and both sides rebuild the same assignment from those lengths. That assignment is the
 * classic canonical one — shorter codes first, ties broken by increasing symbol index — and it is
 * the only thing this class implements. `libwebp` does exactly the same in `huffman_utils.c`; the
 * shapes below are named after the concepts there so the two can be diffed.
 *
 * Two traps live in this file, and both are load-bearing for real tiles:
 *
 *  1. **The single-symbol code consumes zero bits.** When exactly one symbol is used, there is no
 *     choice to encode, so the encoder emits nothing at all and the decoder must not read a bit.
 *     A tree walk would happily consume one bit and desynchronise the whole bitstream from there
 *     on — the tile decodes into noise, not into an error. See [single].
 *  2. **Codes are written MSB-of-the-code first into an LSB-first bit reader.** Exactly like
 *     DEFLATE: the bit reader hands out bits from the low end of each byte, but the bits of a
 *     Huffman code arrive most-significant first. So the decoder accumulates `code = code * 2 + bit`
 *     while the reader walks the bytes upwards, which is the "reverse the bits" canonical layout.
 *
 * ### Why this is not the two-level lookup table `libwebp` uses
 *
 * `libwebp` decodes a symbol with a single indexed load into a 2^8 root table plus, rarely, one
 * sub-table lookup. That trick needs to *peek* at the next 8 bits without consuming them, then skip
 * exactly as many as the matched code is long. [Vp8lBitReader] deliberately exposes no peek/skip
 * pair — only `readBits` / `readBit` — so the table cannot be indexed without over-consuming.
 *
 * What is implemented instead is the counts/offsets decoder from zlib's `puff.c`: the symbols are
 * sorted once by (length, symbol) at build time, and decoding walks *code lengths*, not tree nodes,
 * with a couple of integer operations per length. It is O(code length) per symbol, bounded by
 * [MAX_CODE_LENGTH] = 15, with no pointer chasing and no per-tree allocation beyond two small
 * arrays. The one concession to speed is [prefetch]: no code is shorter than the shortest length in
 * the code, so that many bits can always be pulled in a single `readBits` call, which for a
 * well-populated alphabet removes most of the per-bit calls.
 */
internal class HuffmanTree private constructor(
    /**
     * `counts[len]` is the number of symbols whose code is `len` bits long, for `len` in
     * `1..MAX_CODE_LENGTH`. Index 0 is unused: a length of zero means "symbol absent", not "symbol
     * with an empty code".
     */
    private val counts: IntArray,
    /**
     * The used symbols, sorted by increasing code length and, within one length, by increasing
     * symbol index. That ordering *is* the canonical assignment: the n-th entry of a given length
     * owns the n-th code of that length.
     */
    private val symbols: IntArray,
    /**
     * The only symbol of a degenerate code, or `-1` for a normal code. Kept as a plain field rather
     * than a subclass so that [readSymbol] stays a single non-virtual call on every target.
     */
    private val soleSymbol: Int,
    /**
     * Number of bits [readSymbol] may safely pull in one go before it has to go bit by bit — the
     * shortest code length in this code, capped at 8 so the reversal table below suffices. Zero for
     * a degenerate code, which reads nothing at all.
     */
    private val prefetch: Int,
) {
    /**
     * Reads one symbol from [reader].
     *
     * Consumes exactly as many bits as the matched code is long — and, for the degenerate
     * single-symbol code, none at all.
     *
     * @throws IllegalStateException if the bits read match no code. For a code built by
     *   [fromCodeLengths] this can only happen once [reader] has run out of input, since the
     *   builder rejects incomplete codes up front.
     */
    fun readSymbol(reader: Vp8lBitReader): Int {
        if (soleSymbol >= 0) return soleSymbol
        // `first` is the numeric value of the first code of the current length, `index` the offset
        // of that length's first symbol in `symbols`. Both stay at zero while no code is shorter
        // than `prefetch`, which is why pulling `prefetch` bits at once is legitimate.
        var code = REVERSED_BYTE[reader.readBits(prefetch)] ushr (8 - prefetch)
        var first = 0
        var index = 0
        var len = prefetch
        while (true) {
            val count = counts[len]
            if (code - first < count) return symbols[index + (code - first)]
            index += count
            first = (first + count) shl 1
            if (len == MAX_CODE_LENGTH) break
            code = (code shl 1) or reader.readBit()
            len++
        }
        error(
            "invalid Huffman code in the VP8L bitstream: no symbol matches the $MAX_CODE_LENGTH " +
                "bits read (reader exhausted: ${reader.exhausted})",
        )
    }

    companion object {
        /**
         * The longest code VP8L allows. The specification caps prefix codes at 15 bits, which is
         * also what `libwebp`'s `MAX_ALLOWED_CODE_LENGTH` enforces; longer lengths cannot even be
         * spelled, since the code-length alphabet stops at 15.
         */
        const val MAX_CODE_LENGTH: Int = 15

        /**
         * The order in which the lengths of the *code-length code* are transmitted. Frequently used
         * operators come first so that a stream can stop early via `num_code_lengths` and leave the
         * rest at zero. Copied verbatim from the specification (`kCodeLengthCodeOrder`) — it is not
         * derivable from anything, and getting it wrong silently swaps the repeat operators.
         */
        private val CODE_LENGTH_CODE_ORDER =
            intArrayOf(17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

        /** Size of the code-length alphabet: literal lengths 0..15 plus the three operators 16..18. */
        private const val CODE_LENGTH_ALPHABET_SIZE = 19

        /** First code-length symbol that is an operator rather than a literal length. */
        private const val CODE_LENGTH_LITERALS = 16

        /**
         * The length operator 16 repeats "the previous non-zero length", but a stream is allowed to
         * use it before any literal length has been seen. The specification then mandates 8, which
         * is also `libwebp`'s `DEFAULT_CODE_LENGTH`. Defaulting to 0 instead produces a code that is
         * merely *different*, not invalid, so the mistake survives every small fixture and only
         * shows up as garbage pixels on real tiles.
         */
        private const val DEFAULT_CODE_LENGTH = 8

        /** Extra bits carried by operators 16, 17 and 18 respectively. */
        private val REPEAT_EXTRA_BITS = intArrayOf(2, 3, 7)

        /** Minimum repeat counts of operators 16, 17 and 18 respectively. */
        private val REPEAT_OFFSETS = intArrayOf(3, 3, 11)

        /**
         * Bit-reversal of every byte value, used to turn the LSB-first group returned by
         * `readBits(n)` into the MSB-first accumulator [readSymbol] needs. A 256-entry table is
         * cheaper than reversing bit by bit, which would defeat the point of prefetching.
         */
        private val REVERSED_BYTE =
            IntArray(256) { value ->
                var reversed = 0
                for (bit in 0 until 8) {
                    reversed = (reversed shl 1) or ((value ushr bit) and 1)
                }
                reversed
            }

        /**
         * Builds the canonical code described by [codeLengths] (index = symbol, value = length in
         * bits, 0 = symbol absent).
         *
         * A code with a single used symbol is turned into [single], because that is what VP8L means
         * by it — see the class documentation.
         *
         * @throws IllegalStateException if the code is over-subscribed (the lengths describe more
         *   codes than a binary tree of that depth can hold) or incomplete (some codes would never
         *   be reachable). Both messages name which of the two happened; a decoder that accepted
         *   either would return wrong symbols instead of failing.
         */
        fun fromCodeLengths(codeLengths: IntArray): HuffmanTree {
            val counts = IntArray(MAX_CODE_LENGTH + 1)
            var used = 0
            var lastUsedSymbol = -1
            for (symbol in codeLengths.indices) {
                val length = codeLengths[symbol]
                if (length < 0 || length > MAX_CODE_LENGTH) {
                    error("invalid Huffman code length $length for symbol $symbol, expected 0..$MAX_CODE_LENGTH")
                }
                if (length > 0) {
                    counts[length]++
                    used++
                    lastUsedSymbol = symbol
                }
            }
            if (used == 0) {
                error("incomplete Huffman code: every symbol has a zero code length, so nothing can be decoded")
            }
            if (used == 1) {
                // The degenerate case. Whatever length the stream declared for that symbol, there is
                // no choice left to encode, so the code is zero bits wide.
                return single(lastUsedSymbol)
            }
            // Kraft-McMillan, walked incrementally: `left` is the number of unused nodes at the
            // current depth. Going one level down doubles it; each code of that length consumes one.
            var left = 1
            for (length in 1..MAX_CODE_LENGTH) {
                left = left shl 1
                left -= counts[length]
                if (left < 0) {
                    error(
                        "over-subscribed Huffman code: the code lengths describe more codes of " +
                            "length $length than a binary tree of that depth can hold",
                    )
                }
            }
            if (left > 0) {
                error(
                    "incomplete Huffman code: $left code(s) of length $MAX_CODE_LENGTH are left " +
                        "unassigned, so some bit sequences decode to no symbol",
                )
            }
            // Sort the used symbols by (length, symbol). `offsets[length]` walks forward as symbols
            // are placed, which is a single linear pass rather than an actual sort.
            val offsets = IntArray(MAX_CODE_LENGTH + 2)
            for (length in 1..MAX_CODE_LENGTH) {
                offsets[length + 1] = offsets[length] + counts[length]
            }
            val symbols = IntArray(used)
            for (symbol in codeLengths.indices) {
                val length = codeLengths[symbol]
                if (length > 0) {
                    symbols[offsets[length]++] = symbol
                }
            }
            var shortest = 1
            while (counts[shortest] == 0) shortest++
            return HuffmanTree(counts, symbols, soleSymbol = -1, prefetch = minOf(shortest, 8))
        }

        /**
         * The degenerate code of a single symbol, which consumes no bits at all.
         *
         * VP8L reaches this shape in two ways: a "simple" prefix code announcing one symbol, and a
         * normal code whose lengths happen to mark exactly one symbol as present. Both must decode
         * without touching the bitstream — see trap 1 in the class documentation.
         */
        fun single(symbol: Int): HuffmanTree =
            HuffmanTree(IntArray(MAX_CODE_LENGTH + 1), intArrayOf(symbol), soleSymbol = symbol, prefetch = 0)

        /**
         * Reads a complete VP8L "prefix code" from the bitstream: either a simple code (1-2 symbols,
         * spelled out) or a normal one (code-length code, then the code lengths, with the 16/17/18
         * repeat operators). [alphabetSize] is the number of symbols the code may contain.
         *
         * @throws IllegalStateException on any malformed encoding — a symbol outside the alphabet, a
         *   repeat running past the end of the alphabet, or a resulting code that is
         *   over-subscribed or incomplete.
         */
        fun read(
            reader: Vp8lBitReader,
            alphabetSize: Int,
        ): HuffmanTree =
            if (reader.readBit() == 1) {
                readSimple(reader, alphabetSize)
            } else {
                fromCodeLengths(readNormalCodeLengths(reader, alphabetSize))
            }

        /**
         * Reads a "simple" prefix code: one or two symbols spelled out literally, each getting a
         * one-bit code.
         *
         * The lengths are not transmitted — they are implicitly 1 — and the canonical assignment
         * still applies, so the *lower-numbered* symbol gets code 0 whatever order the two arrived
         * in. Routing through [fromCodeLengths] rather than special-casing here is what guarantees
         * that, and it also folds the "two identical symbols" corner case into [single].
         */
        private fun readSimple(
            reader: Vp8lBitReader,
            alphabetSize: Int,
        ): HuffmanTree {
            val numSymbols = reader.readBits(1) + 1
            val isFirst8Bits = reader.readBits(1)
            val codeLengths = IntArray(alphabetSize)
            val first = reader.readBits(if (isFirst8Bits == 1) 8 else 1)
            codeLengths[checkedSymbol(first, alphabetSize)] = 1
            if (numSymbols == 2) {
                // The second symbol is always 8 bits wide, whatever width the first one used.
                val second = reader.readBits(8)
                codeLengths[checkedSymbol(second, alphabetSize)] = 1
            }
            return fromCodeLengths(codeLengths)
        }

        /**
         * Reads the code lengths of a "normal" prefix code: first the code-length code itself, then
         * the per-symbol lengths decoded with it.
         */
        private fun readNormalCodeLengths(
            reader: Vp8lBitReader,
            alphabetSize: Int,
        ): IntArray {
            val numCodeLengths = reader.readBits(4) + 4
            val codeLengthCodeLengths = IntArray(CODE_LENGTH_ALPHABET_SIZE)
            for (i in 0 until numCodeLengths) {
                codeLengthCodeLengths[CODE_LENGTH_CODE_ORDER[i]] = reader.readBits(3)
            }
            val codeLengthTree = fromCodeLengths(codeLengthCodeLengths)

            // The optional max_symbol limit. It counts *symbols read from the code-length code*, not
            // code lengths produced: one operator 18 can write 11 lengths while costing a single
            // unit of the budget. Counting the wrong thing truncates real tiles halfway through
            // their alphabet while any hand-made fixture without operators still passes.
            var remainingReads =
                if (reader.readBit() == 1) {
                    val lengthNbits = 2 + 2 * reader.readBits(3)
                    val maxSymbol = 2 + reader.readBits(lengthNbits)
                    if (maxSymbol > alphabetSize) {
                        error("VP8L max_symbol $maxSymbol exceeds the alphabet size $alphabetSize")
                    }
                    maxSymbol
                } else {
                    alphabetSize
                }

            val codeLengths = IntArray(alphabetSize)
            var symbol = 0
            var previousNonZero = DEFAULT_CODE_LENGTH
            while (symbol < alphabetSize && remainingReads > 0) {
                remainingReads--
                val codeLength = codeLengthTree.readSymbol(reader)
                if (codeLength < CODE_LENGTH_LITERALS) {
                    codeLengths[symbol++] = codeLength
                    if (codeLength != 0) previousNonZero = codeLength
                } else {
                    val slot = codeLength - CODE_LENGTH_LITERALS
                    val repeat = reader.readBits(REPEAT_EXTRA_BITS[slot]) + REPEAT_OFFSETS[slot]
                    if (symbol + repeat > alphabetSize) {
                        error(
                            "VP8L repeat operator $codeLength writes $repeat lengths from symbol " +
                                "$symbol, past the alphabet size $alphabetSize",
                        )
                    }
                    // 16 repeats the previous non-zero length; 17 and 18 write runs of zeros, and
                    // neither of the three updates `previousNonZero`.
                    val repeated = if (codeLength == CODE_LENGTH_LITERALS) previousNonZero else 0
                    repeat(repeat) { codeLengths[symbol++] = repeated }
                }
            }
            return codeLengths
        }

        /** Rejects a spelled-out symbol that does not belong to the alphabet being read. */
        private fun checkedSymbol(
            symbol: Int,
            alphabetSize: Int,
        ): Int {
            if (symbol >= alphabetSize) {
                error("VP8L simple prefix code names symbol $symbol, outside the alphabet of size $alphabetSize")
            }
            return symbol
        }
    }
}
