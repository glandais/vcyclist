// Appended verbatim to the generated karma.conf.js by the Kotlin Gradle plugin (KotlinKarma),
// for BOTH the js(IR) and the wasmJs browser test targets, inside
// `module.exports = function (config) { … }` and after `config.set({…})`.
//
// This snippet runs in the Karma *Node* process, whose environment build.gradle.kts already
// seeds with INTEGRATION (see the `tasks.withType<KotlinJsTest>` block). Karma ships
// `config.client` to the browser page as `window.__karma__.config`, which is how the flag
// crosses into the test bundle — see IntegrationGate.js.kt / IntegrationGate.wasmJs.kt.
//
// Mutate `config.client` in place rather than calling `config.set({client: …})`: `client.args`
// carries the --tests / --exclude filters that the Kotlin test runner parses, and must survive.
config.client = config.client || {};
config.client.integration = process.env.INTEGRATION === '1';

// Karma/mocha defaults to a 2 s timeout in the browser; a live tile fetch is ~370 KB of WebP
// plus decode. Mirrors the `useMocha { timeout = "30s" }` already set on the Node test task.
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
