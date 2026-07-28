package io.github.glandais.engine.wasi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hand-rolled JSON of [MiniJson], which exists because kotlinx-serialization costs +89 % on
 * the binary (task w03). Hand-rolled means it has to earn its keep here rather than in a
 * library's test suite.
 */
class MiniJsonTest {
    @Test
    fun `reads the scalar types an options object can hold`() {
        val o = parseJsonObject("""{"a":1.5,"b":true,"c":"x","d":null,"e":-2e3}""")

        assertEquals(1.5, o.double("a", 0.0))
        assertEquals(true, o.bool("b", false))
        assertEquals("x", o.string("c", ""))
        assertEquals(7.0, o.double("d", 7.0), "an explicit null must fall back like an absent key")
        assertEquals(-2000.0, o.double("e", 0.0))
    }

    @Test
    fun `an absent key falls back without complaining`() {
        val o = parseJsonObject("{}")

        assertEquals(3.0, o.double("nope", 3.0))
        assertEquals(true, o.bool("nope", true))
        assertNull(o.obj("nope"))
    }

    @Test
    fun `whitespace and nesting are handled`() {
        val o = parseJsonObject("""  { "outer" : { "inner" : [1, 2, {"deep": true}] } }  """)

        val outer = o.obj("outer")
        assertTrue(outer != null && outer.fields.containsKey("inner"))
    }

    @Test
    fun `escapes survive a round trip`() {
        val text = "quote\" backslash\\ newline\n tab\t unicode é"

        val decoded = parseJsonObject("""{"s":${jsonString(text)}}""").string("s", "")

        assertEquals(text, decoded)
    }

    @Test
    fun `a wrong type is an error rather than a silent default`() {
        val o = parseJsonObject("""{"n":"12"}""")

        val thrown = assertFailsWith<IllegalArgumentException> { o.double("n", 0.0) }
        assertTrue(thrown.message!!.contains("number"), thrown.message!!)
    }

    @Test
    fun `unknown keys are refused, and the message names them`() {
        val o = parseJsonObject("""{"massKg":70,"cyclistWeight":70}""")

        val thrown = assertFailsWith<IllegalArgumentException> { o.requireOnly(setOf("massKg")) }
        assertTrue(thrown.message!!.contains("cyclistWeight"), thrown.message!!)
        assertTrue(thrown.message!!.contains("massKg"), "must also say what was expected: ${thrown.message}")
    }

    @Test
    fun `malformed input is refused, not half-parsed`() {
        assertFailsWith<IllegalArgumentException> { parseJsonObject("{") }
        assertFailsWith<IllegalArgumentException> { parseJsonObject("""{"a":}""") }
        assertFailsWith<IllegalArgumentException> { parseJsonObject("""{"a":1}trailing""") }
        assertFailsWith<IllegalArgumentException> { parseJsonObject("[1,2]") }
        assertFailsWith<IllegalArgumentException> { parseJsonObject("""{"a":tru}""") }
    }

    @Test
    fun `non-finite numbers are written as null, since JSON has no NaN`() {
        assertEquals("null", jsonNumber(Double.NaN))
        assertEquals("null", jsonNumber(Double.POSITIVE_INFINITY))
        assertEquals("1.5", jsonNumber(1.5))
    }

    @Test
    fun `the writers produce something the reader accepts`() {
        val text = jsonObject("k" to jsonNumber(1.0), "s" to jsonString("a\"b"), "arr" to jsonArray(listOf("1", "2")))

        val parsed = parseJsonObject(text)

        assertEquals(1.0, parsed.double("k", 0.0))
        assertEquals("a\"b", parsed.string("s", ""))
    }
}
