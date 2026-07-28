package io.github.glandais.elevation

/**
 * Download the tile at [url] and decode it into a [RawTile] (RGBA bytes).
 *
 * Supports any image format that the target's image decoder supports — typically WebP and PNG
 * for Terrarium tiles. Throws if the URL cannot be reached or the response cannot be decoded.
 *
 * Each target provides its own implementation:
 * - JVM: java.net.http.HttpClient + ImageIO (TwelveMonkeys WebP).
 * - JS/browser: fetch + createImageBitmap + canvas.
 * - JS (Node): stub — sharp not wired yet.
 *
 * @throws IllegalStateException if HTTP status is not 2xx or decoding fails
 */
expect suspend fun fetchAndDecodeTile(url: String): RawTile
