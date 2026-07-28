package io.github.glandais.elevation

/**
 * Kotlin/JS actual — covers **both** `jsNodeTest` and `jsBrowserTest`, which share this source set.
 *
 * - Node: `process.env.INTEGRATION` (Gradle propagates the env to the `KotlinJsTest` task).
 * - Browser: `window.__karma__.config.integration`, set by `karma.config.d/integration.js`.
 */
actual fun integrationEnabled(): Boolean =
    js(
        """
        (typeof process !== 'undefined' && process.env && process.env.INTEGRATION === '1') ||
        (typeof window !== 'undefined' && !!window.__karma__ && !!window.__karma__.config &&
            window.__karma__.config.integration === true)
        """,
    ) as Boolean
