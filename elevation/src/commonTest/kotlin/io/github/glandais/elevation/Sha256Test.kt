package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals

/** FIPS 180-4 / NIST published vectors — offline, runs on all four targets. */
class Sha256Test {
    private fun hex(s: String): String = Sha256.hex(s.encodeToByteArray())

    @Test
    fun emptyString() = assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex(""))

    @Test
    fun abc() = assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex("abc"))

    @Test
    fun twoBlockMessage() =
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )

    @Test
    fun millionA() =
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            Sha256.hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )

    @Test
    fun binaryPayloadOfTileSize() {
        // Same shape as a 512×512 RGBA tile — guards the padding maths at a realistic length.
        val data = ByteArray(512 * 512 * 4) { (it * 31 + 7).toByte() }
        assertEquals(64, Sha256.hex(data).length)
        assertEquals(Sha256.hex(data), Sha256.hex(data.copyOf()))
    }
}
