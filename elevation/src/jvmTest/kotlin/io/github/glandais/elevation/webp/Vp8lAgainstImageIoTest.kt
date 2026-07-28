package io.github.glandais.elevation.webp

import io.github.glandais.elevation.ReferenceTile
import io.github.glandais.elevation.Sha256
import io.github.glandais.elevation.decodeTileBytes
import io.github.glandais.elevation.integrationEnabled
import kotlinx.coroutines.test.runTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure-Kotlin VP8L decoder (task w11) against the one this project already trusts.
 *
 * The `commonTest` unit tests prove the decoder on five small fixtures whose expected pixels come
 * from libwebp through Pillow. This proves it on **a real 512 x 512 Mapterhorn tile**, against
 * **TwelveMonkeys** — the JVM decoder every elevation this project has ever computed is based on.
 * Two independent implementations agreeing byte for byte over a megapixel of real data is a
 * different kind of evidence from a 32 x 32 fixture agreeing.
 *
 * It belongs in `jvmTest` because that is the only target where both decoders exist side by side:
 * `decodeTileBytes` here *is* ImageIO.
 *
 * Gated on `INTEGRATION=1`, like every test in this repository that touches the network.
 */
class Vp8lAgainstImageIoTest {
    private fun download(url: String): ByteArray {
        val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
        val request =
            HttpRequest
                .newBuilder(URI.create(url))
                .header("User-Agent", "vcyclist-tests/1.0")
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode(), "downloading $url")
        return response.body()
    }

    @Test
    fun `a real tile decodes byte for byte like TwelveMonkeys`() =
        runTest {
            if (!integrationEnabled()) return@runTest

            val webp = download(ReferenceTile.URL)
            assertTrue(webp.size > 10_000, "a 512x512 tile should not be ${webp.size} bytes")

            val ours = Vp8lDecoder.decodeToRgba(webp)
            val theirs = decodeTileBytes(webp, ReferenceTile.URL)

            assertEquals(theirs.width * theirs.height * 4, ours.size, "pixel count")
            assertEquals(
                Sha256.hex(theirs.rgba),
                Sha256.hex(ours),
                "the two decoders disagree on ${ReferenceTile.URL} — find the first differing pixel",
            )
            assertContentEquals(theirs.rgba, ours, "byte for byte")
            assertEquals(ReferenceTile.RGBA_SHA256, Sha256.hex(ours), "the frozen reference digest")
        }
}
