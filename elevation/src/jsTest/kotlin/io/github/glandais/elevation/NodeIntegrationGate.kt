package io.github.glandais.elevation

/**
 * Skip-gate for live network tests in JS Node target. Mirrors the JVM
 * `ElevationProviderIntegrationTest` gate (env `INTEGRATION=1` or system prop
 * `integration=true`). Returns false on non-Node runtimes (browser test pages),
 * so this helper is safe to call from `jsBrowserTest` too — it will simply skip.
 */
internal fun integrationEnabled(): Boolean =
    js(
        "typeof process !== 'undefined' && process.env && process.env.INTEGRATION === '1'",
    ) as Boolean
