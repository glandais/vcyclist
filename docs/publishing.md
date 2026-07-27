# Publishing vcyclist

This document explains how the vcyclist artefacts ship to **Maven Central** and **npm**.

## What gets published

| Registry | Artefact | Coordinates / name |
|---|---|---|
| Maven Central | `:engine` (jvm, js, wasm-js variants) | `io.github.glandais:vcyclist-engine:<version>` |
| Maven Central | `:elevation` (jvm, js, wasm-js variants) | `io.github.glandais:vcyclist-elevation:<version>` |
| Maven Central | `:gpx` (jvm, js, wasm-js variants) | `io.github.glandais:vcyclist-gpx:<version>` |
| npm | engine — Kotlin/JS | `@glandais/vcyclist-engine` |
| npm | engine — Kotlin/Wasm | `@glandais/vcyclist-engine-wasm` |
| npm | elevation — Kotlin/JS | `@glandais/vcyclist-elevation` |
| npm | elevation — Kotlin/Wasm | `@glandais/vcyclist-elevation-wasm` |

`:codegen` is a build-time JVM helper and is **not** published.

### Why `:gpx` ships to Maven Central but not to npm

`:gpx` was extracted from `:engine` in gpx2web task g01. Kotlin/JS emits one bundle per
Gradle module, so publishing it as a separate npm package would force the demo and every
consumer of `@glandais/vcyclist-engine` to install **two** packages — exactly the breakage
g01 set out to avoid. Instead, `:gpx` declares no `binaries.library()` / `packageJson` /
`npmPublish*`, and its JS output is emitted **inside** the engine bundle as
`vcyclist-gpx.js` (visible in `engine/build/dist/js/productionLibrary/`). One npm package,
same imports, byte-identical `.d.ts`.

Maven Central is different : `:engine` declares `api(project(":gpx"))`, so the published POM
references `io.github.glandais:vcyclist-gpx` and that artefact **must** exist. It is
therefore released alongside `:engine` and `:elevation` in `.releaserc.json`'s `publishCmd`.

If a JS consumer ever wants parsing without physics, revisit this : the alternative is
`@glandais/vcyclist-engine` declaring `@glandais/vcyclist-gpx` as a dependency, at the cost
of keeping two npm packages version-locked.

## The release flow

Releases are fully automated via [semantic-release](https://semantic-release.gitbook.io/) and
mirror the workflow of the sibling projects (`elevation`, `virtual-cyclist`, `gpx2web`).

1. Developer commits to a feature branch using
   [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, …).
2. Pull request is merged into `develop` (the default branch).
3. The workflow `.github/workflows/release.yml` triggers on the `develop` push.
4. The job runs `./gradlew check` (full tests on JVM + JS Node + JS browser + Wasm browser),
   then `npx semantic-release` which :
   - analyses commits since the last tag,
   - bumps the version in `gradle.properties` via a `sed -i` in
     `@semantic-release/exec.prepareCmd`,
   - assembles the artefacts (`./gradlew :engine:assemble :elevation:assemble`),
   - publishes to Maven Central via `publishAndReleaseToMavenCentral` (vanniktech plugin,
     stages and immediately releases — no manual approval needed),
   - publishes the four npm packages via `npmPublishJs` / `npmPublishWasm`,
   - generates/updates `CHANGELOG.md`,
   - creates a Git tag + GitHub Release,
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
          :engine:wasmJsBrowserProductionLibraryDistribution \
          :elevation:jsBrowserProductionLibraryDistribution \
          :elevation:wasmJsBrowserProductionLibraryDistribution
ls engine/build/dist/js/productionLibrary/
ls engine/build/dist/wasmJs/productionLibrary/
# If the directory layout differs from the assumption in build.gradle.kts,
# adjust the workingDir of the npmPublishJs / npmPublishWasm tasks.

# 2. Publish to the local Maven repo to inspect the POM and signatures.
./gradlew :engine:publishToMavenLocal :gpx:publishToMavenLocal :elevation:publishToMavenLocal
find ~/.m2/repository/io/github/glandais -name 'vcyclist-*.pom'

# 3. Dry-run an npm pack (no upload).
(cd engine/build/dist/js/productionLibrary && npm pack --dry-run)

# 4. Dry-run semantic-release end-to-end.
GITHUB_TOKEN=dummy npx semantic-release --dry-run --no-ci
```

## First-time setup checklist

1. **Sonatype Central Portal** : the namespace `io.github.glandais` is already claimed (it
   is used by `gpx2web` via `io.github.glandais.gpx2web`). Adding the `vcyclist-engine` and
   `vcyclist-elevation` artefacts under the existing namespace requires no extra claim,
   only a valid Central Portal token (`CENTRAL_USERNAME` / `CENTRAL_TOKEN`).
2. **npm scope** : ensure `@glandais` org access and that the user has publish rights on
   the four package names (the namespace is shared with `@glandais/elevation` and
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
`{elevation,engine}/build/dist/{js,wasmJs}/productionLibrary/package.json`.

Current runtime deps :

| Package | `dependencies` | Notes |
|---|---|---|
| `@glandais/vcyclist-elevation` (JS) | `@jsquash/webp@1.4.0` | Used on Node.js / Bun for WebP decoding of Terrarium tiles. Loaded lazily via `eval('require')` so browser bundlers do not include it. |
| `@glandais/vcyclist-elevation-wasm` | — | Browser-only ; tile decoding goes through `createImageBitmap` + canvas. |
| `@glandais/vcyclist-engine` (JS) | `@js-joda/core@3.2.0` + `@jsquash/webp@1.4.0` | `@js-joda/core` from `kotlinx-datetime`. `@jsquash/webp` is transitive via the `api(project(":elevation"))` dep. |
| `@glandais/vcyclist-engine-wasm` | `@js-joda/core@3.2.0` | Same datetime dep as the JS variant ; no Node tile decoder needed (browser-only target). |

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
- **The Wasm npm package is empty / missing the `.wasm` file** — the `binaries.library()`
  output path for Kotlin/Wasm may differ across Kotlin minor versions ; re-list
  `build/dist/wasmJs/productionLibrary/` and adjust the `workingDir` of `npmPublishWasm`.
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
