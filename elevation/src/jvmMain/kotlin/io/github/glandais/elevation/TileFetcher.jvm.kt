package io.github.glandais.elevation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

private val httpClient: HttpClient by lazy {
    HttpClient
        .newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

actual suspend fun fetchAndDecodeTile(url: String): RawTile =
    withContext(Dispatchers.IO) {
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
        check(response.statusCode() in 200..299) {
            "Tile fetch failed for $url: HTTP ${response.statusCode()}"
        }
        decodeBytes(response.body(), url)
    }

private fun decodeBytes(
    bytes: ByteArray,
    sourceUrl: String,
): RawTile {
    val img =
        ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("No ImageIO decoder for tile at $sourceUrl")
    val w = img.width
    val h = img.height
    val argb = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
    val rgba = ByteArray(w * h * 4)
    for (i in argb.indices) {
        val p = argb[i]
        rgba[i * 4] = (p shr 16).toByte()
        rgba[i * 4 + 1] = (p shr 8).toByte()
        rgba[i * 4 + 2] = p.toByte()
        rgba[i * 4 + 3] = (p shr 24).toByte()
    }
    return RawTile(w, h, rgba)
}
