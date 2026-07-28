@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.glandais.elevation

/**
 * Kotlin/Wasm has neither `dynamic` nor `js("…")`; the JS→Wasm direction goes through a `@JsFun`
 * snippet (see `docs/kotlin-wasm-jvm-webp.md` §2). The flag is published to the page by
 * `karma.config.d/integration.js` as `__karma__.config.integration`.
 */
@JsFun(
    "() => !!(globalThis.__karma__ && globalThis.__karma__.config && globalThis.__karma__.config.integration === true)",
)
private external fun karmaIntegrationFlag(): Boolean

actual fun integrationEnabled(): Boolean = karmaIntegrationFlag()
