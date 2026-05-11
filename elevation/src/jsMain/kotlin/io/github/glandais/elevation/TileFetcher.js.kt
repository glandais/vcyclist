package io.github.glandais.elevation

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import kotlin.js.Promise
import org.khronos.webgl.Int8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageBitmap
import org.w3c.fetch.Response
import org.w3c.files.Blob

// Bypasses the kotlinx-browser-js `fetch(input, init)` declaration which has no default for
// `init` and would serialise an empty `RequestInit()` as `{cache: null, ...}` — Chrome rejects
// `null` on enum-typed fields (`cache`, `mode`, …). The Wasm target has a `init = null` default
// so this is js-target-only plumbing.
private fun fetchUrl(url: String): Promise<Response> =
    js("fetch(url)").unsafeCast<Promise<Response>>()

actual suspend fun fetchAndDecodeTile(url: String): RawTile {
    val res: Response = fetchUrl(url).await()
    check(res.ok) { "Tile fetch failed for $url: HTTP ${res.status}" }
    val blob: Blob = res.blob().await()

    val bitmap: ImageBitmap = window.createImageBitmap(blob).await()
    try {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = bitmap.width
        canvas.height = bitmap.height
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(bitmap, 0.0, 0.0)
        val data = ctx.getImageData(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
        val src = data.data
        // Reinterpret Uint8ClampedArray as Int8Array — ByteArray at Kotlin/JS runtime IS Int8Array,
        // so unsafeCast is zero-copy and equivalent to the wasm path's `toByteArray()` reinterpret.
        val int8 = Int8Array(src.buffer, src.byteOffset, src.byteLength)
        val rgba: ByteArray = int8.unsafeCast<ByteArray>()
        return RawTile(bitmap.width, bitmap.height, rgba)
    } finally {
        bitmap.close()
    }
}
