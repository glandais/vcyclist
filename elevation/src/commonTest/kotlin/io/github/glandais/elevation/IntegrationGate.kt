package io.github.glandais.elevation

/**
 * Skip-gate for live network tests, available on **every** target.
 *
 * The developer-facing switch is always the same: `INTEGRATION=1 ./gradlew …`. How that value
 * reaches the test runtime differs per target:
 *
 * - **JVM** — `System.getenv("INTEGRATION")` (or `-Dintegration=true`).
 * - **JS / Node** — `process.env.INTEGRATION`, propagated to the `KotlinJsTest` task by the
 *   `tasks.withType<KotlinJsTest>` block in `build.gradle.kts`.
 * - **JS / browser and Wasm / browser** — there is no `process` in a browser page. The Karma
 *   *Node* process does see `process.env.INTEGRATION` (same Gradle propagation), and
 *   `karma.config.d/integration.js` copies it into `config.client.integration`, which Karma
 *   ships to the page as `window.__karma__.config.integration`.
 *
 * Use [skipIfOffline] rather than calling this directly so a skipped test is *visible* in the
 * test output instead of silently passing.
 */
expect fun integrationEnabled(): Boolean

/**
 * Returns `true` (and prints why) when live network tests are disabled.
 *
 * ```kotlin
 * @Test fun foo() = runTest {
 *     if (skipIfOffline("foo")) return@runTest
 *     …
 * }
 * ```
 */
fun skipIfOffline(what: String): Boolean {
    if (!integrationEnabled()) {
        println("[skipped: $what — set INTEGRATION=1 to run live network tests]")
        return true
    }
    return false
}
