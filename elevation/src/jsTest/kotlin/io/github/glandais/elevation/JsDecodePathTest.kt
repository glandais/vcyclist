package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Kotlin/JS mirror of `WasmBrowserDecodePathTest.decodesInlineWebpByteExactly`.
 *
 * This source set is shared by `jsNodeTest` and `jsBrowserTest`, so the same assertion covers
 * both branches of `TileFetcher.js.kt`: the browser branch (`fetch` → `createImageBitmap` →
 * canvas) and the Node branch (`globalThis.fetch` → `@jsquash/webp`). Offline — the fixture is a
 * `data:` URL, which both `fetch` implementations support.
 */
class JsDecodePathTest {
    @Test
    fun decodesInlineWebpByteExactly() =
        runTest {
            val tile = fetchAndDecodeTile(InlineWebpFixture.DATA_URL)
            assertEquals(InlineWebpFixture.WIDTH, tile.width, "width")
            assertEquals(InlineWebpFixture.HEIGHT, tile.height, "height")
            assertEquals(
                InlineWebpFixture.describe(InlineWebpFixture.EXPECTED_RGBA),
                InlineWebpFixture.describe(tile.rgba),
                "Kotlin/JS decode is not byte-exact — suspect premultiplyAlpha / " +
                    "colorSpaceConversion (browser) or the @jsquash decoder (Node)",
            )
        }
}
