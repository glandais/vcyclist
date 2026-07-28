# Publishing vcyclist

This document explains how the vcyclist artefacts ship to **Maven Central** and **npm**.

## What gets published

| Registry | Artefact | Coordinates / name |
|---|---|---|
| Maven Central | `:engine` (jvm, js variants) | `io.github.glandais:vcyclist-engine:<version>` |
| Maven Central | `:elevation` (jvm, js variants) | `io.github.glandais:vcyclist-elevation:<version>` |
| Maven Central | `:gpx` (jvm, js variants) | `io.github.glandais:vcyclist-gpx:<version>` |
| Maven Central | `:fit` (jvm, js variants) | `io.github.glandais:vcyclist-fit:<version>` |
| Maven Central | `:map` (jvm only) | `io.github.glandais:vcyclist-map:<version>` |
| npm | engine — Kotlin/JS | `@glandais/vcyclist-engine` |
| npm | elevation — Kotlin/JS | `@glandais/vcyclist-elevation` |
| GitHub Release | `:cli` executable jar | `vcyclist-cli-<version>-all.jar` |

Five Maven Central artefacts, two npm packages, one release asset. `:fit` is **not** published
to npm — see below.

Since the `wasmWasi` target landed, the four core modules also publish a `-wasm-wasi` variant
(`io.github.glandais:vcyclist-<module>-wasm-wasi`), produced by the standard KMP layout with no
extra configuration. Those are **klibs**, consumable by another Kotlin build — not the standalone
`.wasm`. The executable module is a separate artefact, still to be attached to the release (task
w07); it is built by `./gradlew :engine:wasmModule` and its contract is
[`wasm-wasi-abi.md`](wasm-wasi-abi.md).

`:codegen` is a build-time JVM helper and is **not** published.

`:cli` is an **application**, not a library, so it is **not** published to Maven Central either —
nobody should compile against a command-line tool. Its distributable is the self-contained jar
produced by `./gradlew :cli:executableJar`, intended for attachment to the GitHub release.

`:map` is published to Maven Central (`vcyclist-map`) but **not** to npm: it renders with
`java.awt`, which has no JS equivalent. It is a plain `kotlin-jvm` module rather than a
KMP one; the shared publishing configuration in the root `build.gradle.kts` applies to it
unchanged (verified in g19 — `vcyclist-map-<version>.{jar,pom,module}` plus sources and javadoc).

### `:fit` and the Garmin SDK licence — the decision

**Settled in g19: publishing `vcyclist-fit` to Maven Central does not redistribute Garmin's
SDK, and is not blocked.** The reasoning, stated precisely because the short version is easy to
get wrong in both directions:

Garmin publishes the SDK itself, on both registries — `com.garmin:fit` on Maven Central and
`@garmin/fitsdk` on npm. vcyclist reaches it the way the publisher intended: by **declaring a
coordinate** that the consumer's own resolver fetches from Garmin's distribution.
`vcyclist-fit`'s POM names `com.garmin:fit`; the npm `package.json` names `@garmin/fitsdk`.
**Neither embeds a byte of it.** The FIT Protocol License Agreement's §2.c prohibition on making
the Licensed Technology "available to any third party" governs redistribution; a dependency
declaration on a package the licensor has itself published publicly is the intended consumption
path, not a redistribution of it.

Two facts that a previous version of this section got wrong, and that anyone re-examining the
question should have in front of them:

- The Garmin dependency is **not** jvmMain-only. `fit/build.gradle.kts` declares
  `npm("@garmin/fitsdk")` for the JS target too.
- Because `:engine` does `api(project(":fit"))`, **every `npm install @glandais/vcyclist-engine`
  pulls `@garmin/fitsdk` in transitively**, whether or not the consumer ever writes a FIT file.
  Verified in g19 by installing the packed tarball into an empty project: npm resolved
  `@garmin/fitsdk` and `@jsquash/webp` without being asked.

That widens the reach — every engine consumer accepts Garmin's licence terms in practice — but
does not change the conclusion, since it remains a coordinate rather than a copy.

**Decided (g19): accepted as-is.** `@garmin/fitsdk` weighs 1.3 MB next to the engine bundle's
3.2 MB, and the alternative — an `optionalDependency` plus a dynamic import in the JS
`FitEncoder` actual, on the model of `@jsquash/webp` — buys that back at the cost of a refactor.
Should the weight ever matter, that is the route: make the dependency optional and load it on
the first `pathToFit` call, rather than removing the façade.

It is documented here rather than left in a build file because it is a real obligation that
someone installing `vcyclist-engine` for the physics alone would not expect.

**Not a legal opinion.** Maven Central artefacts cannot be deleted once published. If that
matters to you, have the terms reviewed before the first release that includes `:fit`.

### `:fit` on npm — decided: not published

`:fit` declares **no `@JsExport` at all**, so `@glandais/vcyclist-fit` would ship a bundle with
no reachable public API. The FIT façade (`pathToFit`) deliberately lives in `:engine` instead —
see the comment in `engine/build.gradle.kts`, which explains that `Path` handles cannot cross a
bundle boundary. The JS output of `:fit` already ships *inside* `@glandais/vcyclist-engine` as
`vcyclist-fit.js`, exactly as `:gpx` does.

`:fit:npmPublishJs` is therefore **not in `publishCmd`**. The Gradle task still exists, so
re-adding it is a one-line edit if `:fit` ever grows a JS API of its own. No documented import
path resolves to that package name, so nothing breaks by its absence.

`:fit` **is** published to Maven Central, where the JVM variant is fully functional.


## The release flow

Releases are fully automated via [semantic-release](https://semantic-release.gitbook.io/) and
mirror the workflow of the sibling projects (`elevation`, `virtual-cyclist`, `gpx2web`).

1. Developer commits to a feature branch using
   [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, …).
2. Pull request is merged into `develop` (the default branch).
3. The workflow `.github/workflows/release.yml` triggers on the `develop` push.
4. The job runs `./gradlew check` (full tests on JVM + JS Node + JS browser), then
   `npx semantic-release` which :
   - analyses commits since the last tag,
   - bumps the version in `gradle.properties` via a `sed -i` in
     `@semantic-release/exec.prepareCmd`,
   - builds the CLI jar (`:cli:executableJar`),
   - publishes the five modules to Maven Central via `publishAndReleaseToMavenCentral`
     (vanniktech plugin, stages and immediately releases — no manual approval needed),
   - publishes the two npm packages via `npmPublishJs`,
   - generates/updates `CHANGELOG.md`,
   - creates a Git tag + GitHub Release, with the CLI jar attached as an asset,
   - commits the version + changelog back to `develop` with `[skip ci]`.

**`gradle.properties` always reflects the last released version. Never bump it manually
between releases — semantic-release rewrites it on each run with `sed`.** If you need to
test a future version locally, override on the CLI: `./gradlew -Pversion=1.2.3 …`.

## Required GitHub Secrets

| Secret | Purpose |
|---|---|
| `CENTRAL_USERNAME` | Sonatype Central Portal — user token name |
| `CENTRAL_TOKEN` | Sonatype Central Portal — user token password |
| `GPG_PRIVATE_KEY` | ASCII-armored GPG private key (`gpg --armor --export-secret-keys <KEY_ID>`) |
| `GPG_PASSPHRASE` | Passphrase of the GPG key |

No `NPM_TOKEN` is required — the workflow uses npm's OIDC provenance via
`permissions: id-token: write` and `actions/setup-node` with `registry-url`.

The same GPG key as `gpx2web` can be reused if it has not expired.

## Local dry-runs

Before pushing to `develop`, you can rehearse the release locally :

```bash
# 1. Confirm the build artefact paths used by binaries.library().
./gradlew :engine:jsBrowserProductionLibraryDistribution \
          :elevation:jsBrowserProductionLibraryDistribution
ls engine/build/dist/js/productionLibrary/
# If the directory layout differs from the assumption in build.gradle.kts,
# adjust the workingDir of the npmPublishJs task.

# 2. Publish to the local Maven repo to inspect the POM and signatures.
./gradlew :engine:publishToMavenLocal :gpx:publishToMavenLocal :elevation:publishToMavenLocal
find ~/.m2/repository/io/github/glandais -name 'vcyclist-*.pom'

# 3. Dry-run an npm pack (no upload).
(cd engine/build/dist/js/productionLibrary && npm pack --dry-run)

# 4. Dry-run semantic-release end-to-end.
GITHUB_TOKEN=dummy npx semantic-release --dry-run --no-ci
```

## Verifying that a release does not break existing consumers

The g01 extraction of `:gpx` out of `:engine` promised that existing consumers need change
nothing. That promise is only worth what a real consumer proves, so **run this against a
throwaway project outside the repo** before any release that changes module boundaries.
Reasoning about `api` vs `implementation` is not a substitute — and on the npm side it would
have given the wrong answer twice, as below.

### Maven

```bash
./gradlew publishToMavenLocal
```

Then, in an empty Gradle project with `mavenLocal()` first in `repositories` and **only**
`implementation("io.github.glandais:vcyclist-engine:<version>")` as a dependency, compile code
using the pre-split import paths — `io.github.glandais.engine.path.Path`,
`…engine.gpx.GpxParser`, `…engine.gpx.GpxWriter`, `io.github.glandais.engine.Enhancer` — and run
it. `vcyclist-gpx` must arrive transitively without being named.

*g19 result: passes.* A consumer depending only on `vcyclist-engine`, with imports written
before the split, compiles and runs unmodified.

### npm

**Pack, do not link.** `npm install /path/to/build/dist/js/productionLibrary` creates a symlink,
and Node resolves that package's own `require`s from the link's *realpath* — so its
dependencies are looked up inside `engine/build/`, where they are not, and the check fails with
a `Cannot find module '@garmin/fitsdk'` that says nothing about the real package:

```bash
npm pack engine/build/dist/js/productionLibrary          # -> glandais-vcyclist-engine-<v>.tgz
cd /tmp/consumer && npm install /path/to/glandais-vcyclist-engine-<v>.tgz
```

A tarball install reproduces a registry install faithfully, transitive dependencies included.

Then call `parseGpx` / `enhance` / `writeGpx`. **Use a namespace import, not named imports** —
the Kotlin/JS UMD bundle has exactly one top-level export, `io`:

```js
const engine = require('@glandais/vcyclist-engine').io.github.glandais.engine;
const out = await engine.enhance(engine.parseGpx(xml), null);
```

*g19 result: passes* — 3 input points to 1021 enhanced points, 2001.5 m, valid GPX out. Note
that this is also how the export shape was found to contradict the README, which documented
named imports that never worked; the snippets there were corrected in g19.

## First-time setup checklist

1. **Sonatype Central Portal** : the namespace `io.github.glandais` is already claimed (it
   is used by `gpx2web` via `io.github.glandais.gpx2web`). Adding the `vcyclist-engine` and
   `vcyclist-elevation` artefacts under the existing namespace requires no extra claim,
   only a valid Central Portal token (`CENTRAL_USERNAME` / `CENTRAL_TOKEN`).
2. **npm scope** : ensure `@glandais` org access and that the user has publish rights on
   the two package names (the namespace is shared with `@glandais/elevation` and
   `@glandais/virtual-cyclist`, so the org already exists).
3. **GitHub Secrets** : configure the four secrets above on the repo settings page.
4. **Branch protection** : `develop` is the **default and only protected branch**. Require
   passing CI (`./gradlew check` workflow) before merge, and require linear history so the
   semantic-release auto-commit (the `[skip ci]` version bump back to `develop`) stays as a
   fast-forward. There is no separate `main` branch — releases tag `develop` directly.
5. **Initial release** : push a `feat: initial release` commit to `develop` — semantic-release
   will pick version `1.0.0` (configurable via the
   [`@semantic-release/commit-analyzer` `preset`](https://github.com/semantic-release/commit-analyzer)
   if a `0.x` pre-release line is preferred).

## Runtime dependencies declared in the npm packages

The generated `package.json` for each published bundle is built from the Gradle source set
deps (Kotlin/JS auto-propagates `npm("…", "x.y.z")` declarations) plus the `compilations
{ packageJson { customField(…) } }` block in `*/build.gradle.kts`. After running
`./gradlew :elevation:jsBrowserProductionLibraryDistribution
:engine:jsBrowserProductionLibraryDistribution` you can inspect them in
`{elevation,engine}/build/dist/js/productionLibrary/package.json`.

Current runtime deps :

| Package | `dependencies` | Notes |
|---|---|---|
| `@glandais/vcyclist-elevation` (JS) | `@jsquash/webp@1.4.0` | Used on Node.js / Bun for WebP decoding of Terrarium tiles ; the browser branch decodes via `createImageBitmap` + canvas instead. Loaded lazily via `eval('require')` so browser bundlers do not include it. |
| `@glandais/vcyclist-engine` (JS) | `@js-joda/core@3.2.0` + `@jsquash/webp@1.4.0` | `@js-joda/core` from `kotlinx-datetime`. `@jsquash/webp` is transitive via the `api(project(":elevation"))` dep. |

Browser consumers : the `eval('require')('@jsquash/webp/decode.js')` call is opaque to
webpack / Rollup / Vite static analyzers, so the package is installed in `node_modules` but
never bundled into the browser build. If you want to skip the install entirely for a
pure-browser app, use `npm install --omit=optional` after we promote `@jsquash/webp` to
`optionalDependencies` — currently it is a regular `dependencies` entry so Node consumers
get it transparently.

## Troubleshooting

- **`publishToMavenCentral` fails with `401 Unauthorized`** — re-issue the Central Portal
  user token at <https://central.sonatype.com/> and update the `CENTRAL_USERNAME` /
  `CENTRAL_TOKEN` secrets.
- **GPG signing fails with `secret key not available`** — the imported key has expired ;
  regenerate it (`gpg --full-generate-key`), publish the public key to a keyserver
  (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>`), and update both secrets.
- **`npm publish` fails with `403 Forbidden`** — typically a name conflict or scope-access
  issue ; verify the package name and that the GitHub Actions OIDC trust relationship is
  set up on the npm `@glandais` org.
- **Central Portal indexes new artefacts ~30 min after publication** — be patient before
  retrying ; `./gradlew publishToMavenCentral` is idempotent but the search UI lags.

## GitHub Pages demo

The `:demo` module (Vue 3 + Vite, consumes the Kotlin/JS engine) is published
automatically to GitHub Pages on every push to `develop` that touches
`demo/`, `engine/`, `gpx/`, `elevation/`, or the Gradle build files.

- **Workflow** : [`.github/workflows/gh-pages.yml`](../.github/workflows/gh-pages.yml)
- **URL** : `https://glandais.github.io/vcyclist/`
- **Build target** : `./gradlew :demo:assemble` with `DEPLOY_TARGET=gh-pages`,
  which switches Vite's `base` to `/vcyclist/` so the asset paths resolve under
  the project sub-path.
- **One-time repo setup** : in `Settings → Pages`, set `Source: GitHub Actions`.
  No `gh-pages` branch is created ; the artefact is published directly via
  `actions/deploy-pages`.
- **Manual trigger** : the workflow accepts `workflow_dispatch` so a PR's
  feature branch can preview-deploy on demand (still uses the production
  `/vcyclist/` base path — switch back to `./` for local serving).
- **`[skip ci]` interaction** : `chore(release): X.Y.Z [skip ci]` commits
  pushed by semantic-release skip the Pages workflow (and `release.yml`),
  so there is no infinite loop.

To test locally with the production base path :

```bash
DEPLOY_TARGET=gh-pages ./gradlew :demo:assemble
grep -E '(src=|href=)' demo/dist/index.html
# Asset paths must start with /vcyclist/ — not / or ./
```

## See also

- [`README.md`](../README.md) — install snippets for consumers.
- [`CLAUDE.md`](../CLAUDE.md) — release-related conventions for future Claude sessions.
- Sibling projects for reference patterns :
  - `../elevation/` — npm-only via semantic-release.
  - `../virtual-cyclist/` — npm-only via semantic-release.
  - `../gpx2web/` — Maven Central via the same Sonatype Central Portal plugin family.
