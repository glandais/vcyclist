package io.github.glandais.elevation.webp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests for the VP8L canonical Huffman decoder.
 *
 * Every bitstream here is built by hand with [BitStream], and every Huffman code used is derived in
 * the comments from the code lengths rather than copied from an implementation — the point of these
 * tests is to pin the canonical assignment against the specification, so deriving it from the code
 * under test would prove nothing.
 *
 * Bit order matters twice over and the two orders are opposite, which is the single most common way
 * to get VP8L wrong: multi-bit *fields* (`readBits`) are least-significant-bit first, while the bits
 * of a Huffman *code* arrive most-significant first. [BitStream.field] and [BitStream.code] make
 * that distinction explicit at every call site.
 */
class HuffmanTreeTest {
    @Test
    fun `canonical assignment orders codes by length then by symbol`() {
        // Code lengths: symbol 0 -> 2 bits, 1 -> 1 bit, 2 -> 3 bits, 3 -> 3 bits.
        // Canonical assignment, shortest first and by symbol index within a length:
        //   symbol 1 : "0"    (the only 1-bit code)
        //   symbol 0 : "10"   (first free 2-bit code)
        //   symbol 2 : "110"
        //   symbol 3 : "111"
        val tree = HuffmanTree.fromCodeLengths(intArrayOf(2, 1, 3, 3))
        val reader =
            BitStream()
                .code("0")
                .code("10")
                .code("110")
                .code("111")
                .field(value = 10, bits = 4)
                .reader()

        assertEquals(1, tree.readSymbol(reader), "the single 1-bit code \"0\" belongs to symbol 1")
        assertEquals(0, tree.readSymbol(reader), "\"10\" is the first 2-bit code and symbol 0 is its only candidate")
        assertEquals(2, tree.readSymbol(reader), "\"110\" is the first 3-bit code, taken by the lower symbol index 2")
        assertEquals(3, tree.readSymbol(reader), "\"111\" is the second 3-bit code, taken by symbol 3")
        assertEquals(
            10,
            reader.readBits(4),
            "the four codes must have consumed exactly 1+2+3+3 bits, leaving the marker field aligned",
        )
    }

    @Test
    fun `a single-symbol code consumes no bits at all`() {
        // The VP8L trap: with one symbol there is nothing to encode, so the decoder must not read.
        val tree = HuffmanTree.single(7)
        val reader = BitStream().field(value = 0b1011, bits = 4).reader()

        assertEquals(7, tree.readSymbol(reader), "a degenerate code always yields its only symbol")
        assertEquals(7, tree.readSymbol(reader), "reading it again must still yield that symbol")
        assertEquals(7, tree.readSymbol(reader), "and again, without ever touching the bitstream")
        assertEquals(
            0b1011,
            reader.readBits(4),
            "three reads of a degenerate code must have left the bit position untouched",
        )
    }

    @Test
    fun `code lengths marking exactly one symbol build the degenerate zero-bit code`() {
        // The declared length is 3, but only one symbol is present, so the code is still zero bits
        // wide: the length is meaningless once there is no choice left to encode.
        val tree = HuffmanTree.fromCodeLengths(intArrayOf(0, 3, 0, 0))
        val reader = BitStream().field(value = 0b110, bits = 3).reader()

        assertEquals(1, tree.readSymbol(reader), "the only symbol with a non-zero length is symbol 1")
        assertEquals(0b110, reader.readBits(3), "the degenerate code must not have consumed the declared 3 bits")
    }

    @Test
    fun `read accepts a simple code of one symbol spelled on eight bits`() {
        val reader =
            BitStream()
                .field(value = 1, bits = 1) // simple code
                .field(value = 0, bits = 1) // num_symbols - 1 = 0, so one symbol
                .field(value = 1, bits = 1) // is_first_8bits
                .field(value = 42, bits = 8) // the symbol itself
                .field(value = 5, bits = 3) // marker
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 256)

        assertEquals(42, tree.readSymbol(reader), "the spelled-out symbol is the only one the code can produce")
        assertEquals(5, reader.readBits(3), "a one-symbol simple code is degenerate and must consume zero bits")
    }

    @Test
    fun `read accepts a simple code of two symbols with a one-bit first symbol`() {
        // Two symbols, both getting a 1-bit code. Canonical order applies: symbol 1 < symbol 7, so
        // symbol 1 owns "0" and symbol 7 owns "1".
        val reader =
            BitStream()
                .field(value = 1, bits = 1) // simple code
                .field(value = 1, bits = 1) // num_symbols - 1 = 1, so two symbols
                .field(value = 0, bits = 1) // first symbol is spelled on 1 bit
                .field(value = 1, bits = 1) // first symbol = 1
                .field(value = 7, bits = 8) // second symbol = 7, always 8 bits
                .code("1")
                .code("0")
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 16)

        assertEquals(7, tree.readSymbol(reader), "\"1\" is the second 1-bit code and goes to the higher symbol 7")
        assertEquals(1, tree.readSymbol(reader), "\"0\" is the first 1-bit code and goes to the lower symbol 1")
    }

    @Test
    fun `a simple code assigns codes by symbol index not by transmission order`() {
        // The stream spells 9 first and 3 second, but canonical assignment sorts by symbol, so 3
        // gets "0". Mirroring the transmission order instead would decode every such tile inverted.
        val reader =
            BitStream()
                .field(value = 1, bits = 1)
                .field(value = 1, bits = 1)
                .field(value = 1, bits = 1) // first symbol on 8 bits
                .field(value = 9, bits = 8)
                .field(value = 3, bits = 8)
                .code("0")
                .code("1")
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 16)

        assertEquals(3, tree.readSymbol(reader), "the lower symbol index owns code \"0\" whichever was sent first")
        assertEquals(9, tree.readSymbol(reader), "the higher symbol index owns code \"1\"")
    }

    @Test
    fun `read accepts a normal code whose lengths are spelled out one by one`() {
        // Code-length code: only symbols 0 and 2 are used, one bit each, so 0 -> "0", 2 -> "1".
        // The lengths of the code-length code travel in the fixed order 17, 18, 0, 1, 2, ...
        val reader =
            BitStream()
                .field(value = 0, bits = 1) // normal code
                .field(value = 1, bits = 4) // num_code_lengths = 1 + 4 = 5
                .field(value = 0, bits = 3) // length of code-length symbol 17
                .field(value = 0, bits = 3) // length of code-length symbol 18
                .field(value = 1, bits = 3) // length of code-length symbol 0
                .field(value = 0, bits = 3) // length of code-length symbol 1
                .field(value = 1, bits = 3) // length of code-length symbol 2
                .field(value = 0, bits = 1) // no max_symbol limit
                .code("1")
                .code("1")
                .code("1")
                .code("1") // lengths 2, 2, 2, 2 for symbols 0..3
                .code("0")
                .code("0")
                .code("0")
                .code("0") // lengths 0, 0, 0, 0 for symbols 4..7
                .code("10")
                .code("01")
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 8)

        // Four symbols of length 2: 0 -> "00", 1 -> "01", 2 -> "10", 3 -> "11".
        assertEquals(2, tree.readSymbol(reader), "\"10\" is the third 2-bit code, owned by symbol 2")
        assertEquals(1, tree.readSymbol(reader), "\"01\" is the second 2-bit code, owned by symbol 1")
    }

    @Test
    fun `the repeat operators 16 17 and 18 expand runs of code lengths`() {
        // Code-length code over symbols 2, 16, 17 and 18, two bits each. Canonical by symbol:
        //   2 -> "00", 16 -> "01", 17 -> "10", 18 -> "11".
        // The transmission order 17, 18, 0, 1, 2, 3, 4, 5, 16 needs all 9 leading slots to reach 16.
        val reader =
            BitStream()
                .field(value = 0, bits = 1) // normal code
                .field(value = 5, bits = 4) // num_code_lengths = 5 + 4 = 9
                .field(value = 2, bits = 3) // 17
                .field(value = 2, bits = 3) // 18
                .field(value = 0, bits = 3) // 0
                .field(value = 0, bits = 3) // 1
                .field(value = 2, bits = 3) // 2
                .field(value = 0, bits = 3) // 3
                .field(value = 0, bits = 3) // 4
                .field(value = 0, bits = 3) // 5
                .field(value = 2, bits = 3) // 16
                .field(value = 0, bits = 1) // no max_symbol limit
                .code("00") // literal length 2 -> symbol 0
                .code("01")
                .field(value = 0, bits = 2) // 16: repeat the previous 2, three times
                .code("10")
                .field(value = 2, bits = 3) // 17: 3 + 2 = 5 zeros
                .code("11")
                .field(value = 0, bits = 7) // 18: 11 + 0 = 11 zeros
                .code("11")
                .code("00")
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 20)

        // Resulting lengths: 2, 2, 2, 2 then sixteen zeros, i.e. 0 -> "00" .. 3 -> "11".
        assertEquals(3, tree.readSymbol(reader), "operator 16 must have given symbol 3 a 2-bit length like symbol 0")
        assertEquals(0, tree.readSymbol(reader), "the literal length 2 of symbol 0 must be unaffected by the runs")
    }

    @Test
    fun `operator 16 before any literal repeats the default length of eight`() {
        // The code-length code holds a single symbol, 16, so reading it costs zero bits and only its
        // two extra bits are consumed. Nothing literal is ever read, so operator 16 repeats the
        // specified default of 8 — the whole 256-symbol alphabet ends up 8 bits wide, which is
        // exactly complete and assigns symbol i the 8-bit code of i.
        val stream =
            BitStream()
                .field(value = 0, bits = 1) // normal code
                .field(value = 5, bits = 4) // num_code_lengths = 9, enough to reach symbol 16
                .field(value = 0, bits = 3) // 17
                .field(value = 0, bits = 3) // 18
                .field(value = 0, bits = 3) // 0
                .field(value = 0, bits = 3) // 1
                .field(value = 0, bits = 3) // 2
                .field(value = 0, bits = 3) // 3
                .field(value = 0, bits = 3) // 4
                .field(value = 0, bits = 3) // 5
                .field(value = 1, bits = 3) // 16, the only used code-length symbol
                .field(value = 0, bits = 1) // no max_symbol limit
        repeat(42) { stream.field(value = 3, bits = 2) } // 42 runs of 3 + 3 = 6 lengths = 252
        stream.field(value = 1, bits = 2) // one last run of 3 + 1 = 4 lengths, total 256
        val reader =
            stream
                .code(binary(0, 8))
                .code(binary(255, 8))
                .code(binary(66, 8))
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 256)

        assertEquals(0, tree.readSymbol(reader), "with all lengths equal to 8 the code of symbol 0 is 00000000")
        assertEquals(255, tree.readSymbol(reader), "and the code of symbol 255 is 11111111")
        assertEquals(66, tree.readSymbol(reader), "and the code of symbol 66 is its 8-bit big-endian value")
    }

    @Test
    fun `the max_symbol limit stops the code-length loop early`() {
        // max_symbol budgets *reads from the code-length code*, not lengths produced. Here it caps
        // the loop at four reads, so symbols 4..7 keep their zero length and the bits that follow
        // belong to the rest of the stream.
        val reader =
            BitStream()
                .field(value = 0, bits = 1) // normal code
                .field(value = 1, bits = 4) // num_code_lengths = 5
                .field(value = 0, bits = 3) // 17
                .field(value = 0, bits = 3) // 18
                .field(value = 1, bits = 3) // 0 -> "0"
                .field(value = 0, bits = 3) // 1
                .field(value = 1, bits = 3) // 2 -> "1"
                .field(value = 1, bits = 1) // max_symbol limit present
                .field(value = 0, bits = 3) // length_nbits = 2 + 2 * 0 = 2
                .field(value = 2, bits = 2) // max_symbol = 2 + 2 = 4
                .code("1")
                .code("1")
                .code("1")
                .code("1") // four lengths of 2, then the loop stops
                .field(value = 5, bits = 3) // marker
                .reader()

        val tree = HuffmanTree.read(reader, alphabetSize = 8)

        assertEquals(
            5,
            reader.readBits(3),
            "the loop must stop after four reads, leaving the marker aligned instead of eating four more codes",
        )
        assertEquals(0, tree.readSymbol(BitStream().code("00").reader()), "symbols 0..3 got the four 2-bit codes")
        assertEquals(3, tree.readSymbol(BitStream().code("11").reader()), "and symbol 3 owns the last of them")
    }

    @Test
    fun `an over-subscribed code is rejected as over-subscribed`() {
        // Three codes of one bit: a binary tree of depth 1 holds two.
        val failure = assertFailsWith<IllegalStateException> { HuffmanTree.fromCodeLengths(intArrayOf(1, 1, 1)) }
        assertTrue(
            failure.message!!.contains("over-subscribed"),
            "the message must name over-subscription so it is distinguishable from an incomplete code, was: " +
                failure.message,
        )
    }

    @Test
    fun `an incomplete code is rejected as incomplete`() {
        // 1/2 + 1/4 + 1/8 < 1: some bit sequences would decode to no symbol at all.
        val failure = assertFailsWith<IllegalStateException> { HuffmanTree.fromCodeLengths(intArrayOf(1, 2, 3)) }
        assertTrue(
            failure.message!!.contains("incomplete"),
            "the message must name incompleteness so it is distinguishable from over-subscription, was: " +
                failure.message,
        )
    }

    @Test
    fun `a code with no used symbol is rejected`() {
        val failure = assertFailsWith<IllegalStateException> { HuffmanTree.fromCodeLengths(IntArray(8)) }
        assertTrue(
            failure.message!!.contains("incomplete"),
            "an empty code decodes nothing and must be reported as incomplete, was: " + failure.message,
        )
    }

    @Test
    fun `a code length beyond fifteen bits is rejected`() {
        val failure = assertFailsWith<IllegalStateException> { HuffmanTree.fromCodeLengths(intArrayOf(16, 1)) }
        assertTrue(
            failure.message!!.contains("invalid Huffman code length"),
            "VP8L caps prefix codes at 15 bits, was: " + failure.message,
        )
    }

    @Test
    fun `a repeat operator running past the alphabet is rejected`() {
        // The code-length code holds only symbol 18, which writes 11 zeros — three too many for an
        // alphabet of eight.
        val reader =
            BitStream()
                .field(value = 0, bits = 1) // normal code
                .field(value = 0, bits = 4) // num_code_lengths = 4
                .field(value = 0, bits = 3) // 17
                .field(value = 1, bits = 3) // 18, the only used code-length symbol
                .field(value = 0, bits = 3) // 0
                .field(value = 0, bits = 3) // 1
                .field(value = 0, bits = 1) // no max_symbol limit
                .field(value = 0, bits = 7) // extra bits of operator 18: 11 + 0 zeros
                .reader()

        val failure = assertFailsWith<IllegalStateException> { HuffmanTree.read(reader, alphabetSize = 8) }
        assertTrue(
            failure.message!!.contains("past the alphabet size"),
            "a run overflowing the alphabet must be reported rather than silently truncated, was: " + failure.message,
        )
    }

    @Test
    fun `a simple code naming a symbol outside the alphabet is rejected`() {
        val reader =
            BitStream()
                .field(value = 1, bits = 1) // simple code
                .field(value = 0, bits = 1) // one symbol
                .field(value = 1, bits = 1) // spelled on 8 bits
                .field(value = 200, bits = 8) // ... but the alphabet only has 16 symbols
                .reader()

        val failure = assertFailsWith<IllegalStateException> { HuffmanTree.read(reader, alphabetSize = 16) }
        assertTrue(
            failure.message!!.contains("outside the alphabet"),
            "the symbol must be range-checked against the alphabet, was: " + failure.message,
        )
    }

    /** Renders [value] as a [bits]-wide binary string, most significant bit first. */
    private fun binary(
        value: Int,
        bits: Int,
    ): String = (bits - 1 downTo 0).joinToString("") { if ((value ushr it) and 1 == 1) "1" else "0" }

    /**
     * Builds a VP8L bitstream bit by bit, in the order a decoder reads them.
     *
     * The two appenders exist to keep the two opposite bit orders visible at every call site:
     * [field] writes a multi-bit field least-significant bit first, the way `readBits` consumes it,
     * while [code] writes the bits of a Huffman code in the order they are matched, which is
     * most-significant first.
     */
    private class BitStream {
        private val bits = mutableListOf<Int>()

        /** Appends [value] on [bits] bits, least significant first — the `readBits` convention. */
        fun field(
            value: Int,
            bits: Int,
        ): BitStream {
            for (i in 0 until bits) this.bits.add((value ushr i) and 1)
            return this
        }

        /** Appends a Huffman code written as `"0"`/`"1"` characters, most significant bit first. */
        fun code(code: String): BitStream {
            for (character in code) bits.add(if (character == '1') 1 else 0)
            return this
        }

        /** Packs the accumulated bits into bytes, low bit of each byte first, and opens a reader. */
        fun reader(): Vp8lBitReader {
            val bytes = ByteArray((bits.size + 7) / 8)
            for (index in bits.indices) {
                if (bits[index] == 1) {
                    bytes[index / 8] = (bytes[index / 8].toInt() or (1 shl (index % 8))).toByte()
                }
            }
            return Vp8lBitReader(bytes)
        }
    }
}
