package io.github.glandais.elevation

/**
 * Minimal, dependency-free SHA-256 — test-only.
 *
 * `kotlin-test` has no multiplatform digest, and pulling in a crypto library just to compare two
 * byte arrays across four targets is not worth it. This is the textbook FIPS 180-4 implementation;
 * it is verified against the published test vectors in `Sha256Test`.
 */
internal object Sha256 {
    // Spelled as unsigned hex (the form the standard prints) and converted, so the table cannot be
    // mis-transcribed into the wrong two's-complement negatives.
    private fun words(csv: String): IntArray = csv.split(",").map { it.trim().toUInt(16).toInt() }.toIntArray()

    private val K =
        words(
            "428a2f98,71374491,b5c0fbcf,e9b5dba5,3956c25b,59f111f1,923f82a4,ab1c5ed5," +
                "d807aa98,12835b01,243185be,550c7dc3,72be5d74,80deb1fe,9bdc06a7,c19bf174," +
                "e49b69c1,efbe4786,0fc19dc6,240ca1cc,2de92c6f,4a7484aa,5cb0a9dc,76f988da," +
                "983e5152,a831c66d,b00327c8,bf597fc7,c6e00bf3,d5a79147,06ca6351,14292967," +
                "27b70a85,2e1b2138,4d2c6dfc,53380d13,650a7354,766a0abb,81c2c92e,92722c85," +
                "a2bfe8a1,a81a664b,c24b8b70,c76c51a3,d192e819,d6990624,f40e3585,106aa070," +
                "19a4c116,1e376c08,2748774c,34b0bcb5,391c0cb3,4ed8aa4a,5b9cca4f,682e6ff3," +
                "748f82ee,78a5636f,84c87814,8cc70208,90befffa,a4506ceb,bef9a3f7,c67178f2",
        )

    fun digest(message: ByteArray): ByteArray {
        val h = words("6a09e667,bb67ae85,3c6ef372,a54ff53a,510e527f,9b05688c,1f83d9ab,5be0cd19")

        val bitLen = message.size.toLong() * 8L
        // message + 0x80 + zero padding to 56 mod 64 + 8-byte big-endian bit length
        val padded = ByteArray(((message.size + 8) / 64 + 1) * 64)
        message.copyInto(padded)
        padded[message.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()
        }

        val w = IntArray(64)
        var block = 0
        while (block < padded.size) {
            for (t in 0 until 16) {
                val o = block + t * 4
                w[t] =
                    ((padded[o].toInt() and 0xFF) shl 24) or
                    ((padded[o + 1].toInt() and 0xFF) shl 16) or
                    ((padded[o + 2].toInt() and 0xFF) shl 8) or
                    (padded[o + 3].toInt() and 0xFF)
            }
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] ushr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] ushr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }

            var a = h[0]
            var b = h[1]
            var c = h[2]
            var d = h[3]
            var e = h[4]
            var f = h[5]
            var g = h[6]
            var hh = h[7]

            for (t in 0 until 64) {
                val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[t] + w[t]
                val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g
                g = f
                f = e
                e = d + t1
                d = c
                c = b
                b = a
                a = t1 + t2
            }

            h[0] += a
            h[1] += b
            h[2] += c
            h[3] += d
            h[4] += e
            h[5] += f
            h[6] += g
            h[7] += hh
            block += 64
        }

        val out = ByteArray(32)
        for (i in 0 until 8) {
            out[i * 4] = (h[i] ushr 24).toByte()
            out[i * 4 + 1] = (h[i] ushr 16).toByte()
            out[i * 4 + 2] = (h[i] ushr 8).toByte()
            out[i * 4 + 3] = h[i].toByte()
        }
        return out
    }

    fun hex(message: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (b in digest(message)) {
            val v = b.toInt() and 0xFF
            sb.append(digits[v ushr 4]).append(digits[v and 0x0F])
        }
        return sb.toString()
    }

    private fun rotr(
        x: Int,
        n: Int,
    ): Int = (x ushr n) or (x shl (32 - n))
}
