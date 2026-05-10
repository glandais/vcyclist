package io.github.glandais.elevation

// NOTE: jsMain (Node) target requires `sharp` for WebP/PNG decoding.
// Activate when a Node consumer needs it by replacing this stub with a real implementation
// using `fetch` (Node 18+) and `sharp` to produce raw RGBA bytes.
actual suspend fun fetchAndDecodeTile(url: String): RawTile =
    throw NotImplementedError(
        "fetchAndDecodeTile is not implemented for the JS (Node) target. " +
            "Add `sharp` as an npm dependency and implement TileFetcher.js.kt.",
    )
