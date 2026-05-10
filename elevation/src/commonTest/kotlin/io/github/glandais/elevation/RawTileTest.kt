package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class RawTileTest {
    @Test
    fun `valid construction`() {
        val raw = RawTile(2, 2, ByteArray(2 * 2 * 4))
        assertEquals(2, raw.width)
        assertEquals(2, raw.height)
        assertEquals(16, raw.rgba.size)
    }

    @Test
    fun `inconsistent rgba size is rejected`() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                RawTile(2, 2, ByteArray(10))
            }
        assertTrue(ex.message!!.contains("rgba size 10"))
        assertTrue(ex.message!!.contains("16"))
    }

    @Test
    fun `equals true for identical content`() {
        val a = RawTile(2, 2, ByteArray(16) { it.toByte() })
        val b = RawTile(2, 2, ByteArray(16) { it.toByte() })
        assertEquals(a, b)
    }

    @Test
    fun `hashCode identical for identical content`() {
        val a = RawTile(2, 2, ByteArray(16) { it.toByte() })
        val b = RawTile(2, 2, ByteArray(16) { it.toByte() })
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals false on content diff`() {
        val a = RawTile(2, 2, ByteArray(16) { it.toByte() })
        val differing = ByteArray(16) { it.toByte() }.also { it[5] = 99 }
        val b = RawTile(2, 2, differing)
        assertNotEquals(a, b)
    }
}
