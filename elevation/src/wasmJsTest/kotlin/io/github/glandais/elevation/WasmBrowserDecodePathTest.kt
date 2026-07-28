@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * First tests in the `wasmJsTest` source set (task 3b). Before this, the Kotlin/Wasm
 * `fetch` → `createImageBitmap` → canvas → `getImageData` decode path in `TileFetcher.wasmJs.kt`
 * had **zero** coverage: `wasmJsBrowserTest` only executed `commonTest`.
 *
 * [decodesInlineWebpByteExactly] is offline (a `data:` URL) yet exercises the real production
 * code path, so it catches premultiplied-alpha / colour-space corruption on every
 * `./gradlew check`, without needing the network. The networked, full-size counterpart is
 * `ReferenceTileDigestTest` in `commonTest`, which also runs on this target.
 */
class WasmBrowserDecodePathTest {
    @Test
    fun decodesInlineWebpByteExactly() =
        runTest {
            val tile = fetchAndDecodeTile(InlineWebpFixture.DATA_URL)
            assertEquals(InlineWebpFixture.WIDTH, tile.width, "width")
            assertEquals(InlineWebpFixture.HEIGHT, tile.height, "height")
            assertEquals(
                InlineWebpFixture.describe(InlineWebpFixture.EXPECTED_RGBA),
                InlineWebpFixture.describe(tile.rgba),
                "Kotlin/Wasm createImageBitmap+canvas is not byte-exact — suspect " +
                    "premultiplyAlpha / colorSpaceConversion",
            )
        }

    @Test
    fun createImageBitmapIsAvailableInThisBrowser() {
        assertTrue(hasCreateImageBitmap(), "createImageBitmap must exist for fetchAndDecodeTile")
    }

    @Test
    fun integrationGateIsReadableFromWasm() {
        // Does not assert a value (it depends on INTEGRATION); it asserts the @JsFun bridge itself
        // works, so an INTEGRATION=1 browser run cannot silently fall back to "disabled".
        println("[wasm gate] integrationEnabled() = ${integrationEnabled()}")
    }
}

@JsFun("() => typeof createImageBitmap === 'function'")
private external fun hasCreateImageBitmap(): Boolean
