package io.github.glandais.elevation

/**
 * wasmWasi actual — live network tests are impossible on this target (no HTTP client, see
 * `TileFetcher.wasmWasi.kt`), so the gate is hard-wired off rather than reading an
 * environment variable.
 */
actual fun integrationEnabled(): Boolean = false
