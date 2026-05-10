package io.github.glandais.elevation

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8ClampedArray
import org.khronos.webgl.toByteArray
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageBitmap
import org.w3c.fetch.Response
import org.w3c.files.Blob

/** Re-views the Uint8ClampedArray as an Int8Array sharing the same buffer — zero copy on JS side. */
@JsFun("(arr) => new Int8Array(arr.buffer, arr.byteOffset, arr.byteLength)")
private external fun uint8ClampedAsInt8(arr: Uint8ClampedArray): Int8Array

actual suspend fun fetchAndDecodeTile(url: String): RawTile {
    val res: Response = window.fetch(url).await()
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
        val rgba = uint8ClampedAsInt8(data.data).toByteArray()
        return RawTile(bitmap.width, bitmap.height, rgba)
    } finally {
        bitmap.close()
    }
}
