package io.github.glandais.elevation

/**
 * One fixed Terrarium tile, used as the cross-target byte-exactness fixture.
 *
 * `12/2126/1459` is the tile that `ELEVATION_COORDS[0]` (Mont Blanc summit, 45.8326 / 6.8652)
 * resolves to at the default zoom 12 — verified with the Web Mercator formula in
 * [ElevationFunctions.toTileCoordinates]. (The tile named in the original brief,
 * `12/2138/1450`, is **not** hit by any of the ten parity coordinates; neither is
 * `12/2138/1466`, which the pre-existing Node integration test fetched.)
 *
 * [RGBA_SHA256] is produced by the JVM decoder (TwelveMonkeys `imageio-webp` → `getRGB` →
 * packed RGBA) and frozen here. `ReferenceTileDigestTest` runs in `commonTest`, so **every**
 * target — JVM, JS/Node, JS/browser, Wasm/browser — must reproduce it byte for byte.
 *
 * A mismatch on a browser target is the signature of `createImageBitmap` applying
 * premultiplied alpha or a colour-space conversion, which would silently corrupt the
 * `R*256 + G + B/256 - 32768` elevation encoding.
 *
 * To re-measure after an upstream tile change:
 * `INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*ReferenceTileDigestDumpTest*' --rerun-tasks -i`
 */
object ReferenceTile {
    const val URL: String = "https://tiles.mapterhorn.com/12/2126/1459.webp"
    const val WIDTH: Int = 512
    const val HEIGHT: Int = 512

    /**
     * Measured 2026-07-28 on the JVM (TwelveMonkeys imageio-webp 3.13.1) and independently
     * corroborated with Pillow (`Image.open(...).convert("RGBA").tobytes()`), which produced the
     * identical digest — so this value is a property of the tile, not of one decoder.
     */
    const val RGBA_SHA256: String = "4686ce247e74053e1b2a63017582d67646fc34878906ccbeac5dcd50feff33bc"
}
