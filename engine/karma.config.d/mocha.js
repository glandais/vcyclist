// Appended verbatim to the generated karma.conf.js by the Kotlin Gradle plugin (KotlinKarma),
// for the js(IR) browser test target, inside
// `module.exports = function (config) { … }` and after `config.set({…})`.
//
// Karma/mocha defaults to a 2 s timeout in the browser. That is too tight on a loaded CI
// runner — it is what made `FitRoundTripTest."case 08 — a ten-thousand-point course still
// round-trips"` fail the release build of 2026-08-17 with an empty `Error`, while passing
// everywhere else. Mirrors the `useMocha { timeout = "30s" }` already set on this module's
// Node test task, and the same snippet in `:elevation`.
//
// Mutate `config.client` in place rather than calling `config.set({client: …})`: `client.args`
// carries the --tests / --exclude filters that the Kotlin test runner parses, and must survive.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
