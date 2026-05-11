package io.github.glandais.elevation

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageBitmap
import org.w3c.fetch.Response
import org.w3c.files.Blob
import kotlin.js.Promise

// True when running under Node.js or Bun (no DOM, has process.versions.node).
private val isNode: Boolean =
    js(
        "typeof window === 'undefined' && typeof process !== 'undefined' " +
            "&& process.versions != null && process.versions.node != null",
    ) as Boolean

// Bypasses the kotlinx-browser-js `fetch(input, init)` declaration which has no default for
// `init` and would serialise an empty `RequestInit()` as `{cache: null, ...}` — Chrome rejects
// `null` on enum-typed fields (`cache`, `mode`, …). The Wasm target has a `init = null` default
// so this is js-target-only plumbing. Used by `decodeBrowser`.
private fun fetchUrlBrowser(url: String): Promise<Response> = js("fetch(url)").unsafeCast<Promise<Response>>()

// Node: globalThis.fetch is native since Node 18 and Bun. Returns a Web `Response`.
private fun fetchUrlNode(url: String): Promise<Response> = js("globalThis.fetch(url)").unsafeCast<Promise<Response>>()

// Wrap the response.arrayBuffer() promise — Web standard, available in Node 18+.
private fun responseArrayBuffer(res: Response): Promise<dynamic> = js("res.arrayBuffer()").unsafeCast<Promise<dynamic>>()

// Load @jsquash/webp lazily so webpack does NOT resolve it at bundle time for the browser
// target. The `require()` is hidden behind `eval()` to defeat webpack's static resolver —
// combined with `webpack.config.d/externals.js`, the browser bundle stays jsquash-free.
private fun decodeWebpNode(buffer: dynamic): Promise<dynamic> =
    js("eval('require')('@jsquash/webp/decode/index.js').default(buffer)")
        .unsafeCast<Promise<dynamic>>()

actual suspend fun fetchAndDecodeTile(url: String): RawTile = if (isNode) decodeNode(url) else decodeBrowser(url)

private suspend fun decodeBrowser(url: String): RawTile {
    val res: Response = fetchUrlBrowser(url).await()
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

private suspend fun decodeNode(url: String): RawTile {
    val res: Response = fetchUrlNode(url).await()
    check(res.ok) { "Tile fetch failed for $url: HTTP ${res.status}" }
    val ab: dynamic = responseArrayBuffer(res).await()
    val image: dynamic = decodeWebpNode(ab).await()
    val width: Int = (image.width as Number).toInt()
    val height: Int = (image.height as Number).toInt()
    val src: dynamic = image.data // Uint8ClampedArray
    val int8 = Int8Array(src.buffer, (src.byteOffset as Number).toInt(), (src.byteLength as Number).toInt())
    val rgba: ByteArray = int8.unsafeCast<ByteArray>()
    return RawTile(width, height, rgba)
}
