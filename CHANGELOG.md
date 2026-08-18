# [5.0.0](https://github.com/glandais/vcyclist/compare/v4.3.0...v5.0.0) (2026-08-18)


* feat(engine)!: align the six façade surfaces, and make the alignment build-enforced ([54804e2](https://github.com/glandais/vcyclist/commit/54804e2d24f4661566ae39263ed5228bd697e99b))


### Bug Fixes

* **build:** make the TypeScript facade a finalizer of the JS distribution ([6529c51](https://github.com/glandais/vcyclist/commit/6529c51a36bc0ea5a62d0d687f49cf4e028eadb0))


### Features

* **engine:** measure cumulative ascent at a stated scale, the way Strava does ([1f2364e](https://github.com/glandais/vcyclist/commit/1f2364e3e2581d5d55348e3b27a37af1451075f5)), closes [#44](https://github.com/glandais/vcyclist/issues/44)
* **engine:** ship real TypeScript types, generated from the JS façades ([1a5b44e](https://github.com/glandais/vcyclist/commit/1a5b44e8fd36c8dfe3e84631cbc1a375fc8d5ed6))


### BREAKING CHANGES

* --road-condition now overrides --cyclist-max-angle and
--cyclist-max-brake instead of losing to them, matching the JS and WASI doors.
`enhance` warns on stderr when both are passed; drop --road-condition to keep your
own grip values.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>

# [4.3.0](https://github.com/glandais/vcyclist/compare/v4.2.1...v4.3.0) (2026-08-18)


### Bug Fixes

* **engine:** make every pipeline option reachable from Java ([5d7b833](https://github.com/glandais/vcyclist/commit/5d7b83353b3b09457ad688fbe9e073146a1ab447))


### Features

* **demo:** download the virtualized path as GPX or FIT ([a4013eb](https://github.com/glandais/vcyclist/commit/a4013eb7a94545f09184bc074204770cbec1ca11))
* **demo:** fold the elevation explorer into the Vue app ([dc24ef6](https://github.com/glandais/vcyclist/commit/dc24ef6174f9212ae34694affba989a5616c8b32))
* **engine:** relay --gpx-power-source and trackName to the JS and WASI doors ([0182362](https://github.com/glandais/vcyclist/commit/0182362af4ef489ae7961614df39169bcb96faa5))

## [4.2.1](https://github.com/glandais/vcyclist/compare/v4.2.0...v4.2.1) (2026-08-18)


### Bug Fixes

* **engine:** give `elapsed` and `dt` one unit, the second (task 45) ([7770a2d](https://github.com/glandais/vcyclist/commit/7770a2d24d31b4ef375f5c4e08e1bc109e09a661))

# [4.2.0](https://github.com/glandais/vcyclist/compare/v4.1.0...v4.2.0) (2026-08-18)


### Bug Fixes

* **cli:** report the offset at the apex, not the largest one in the corner ([efb9e78](https://github.com/glandais/vcyclist/commit/efb9e78cfdc09448e97f1df944936eace82f6408))
* **engine:** report the curvature the racing line actually has, not the analytic one ([e37c34b](https://github.com/glandais/vcyclist/commit/e37c34b2a577b99dd9bf74136d793192fd5d9383))


### Features

* **demo:** show the racing line, its corridor and the road it left (task 44) ([42ea52d](https://github.com/glandais/vcyclist/commit/42ea52d819045cedd55071e8ea0249cc84f6b4ff))
* **engine:** detect corners and build the racing-line corridor (Phase T task t04) ([1f25ccb](https://github.com/glandais/vcyclist/commit/1f25ccb0368a11b1049716a01b037d94642e858c))
* **engine:** estimate turn radius by heading regression (Phase T task t03) ([1d73706](https://github.com/glandais/vcyclist/commit/1d73706c79e1445099d193131c86efc308a6164e))
* **engine:** expose the racing line through the pipeline and all four surfaces (Phase T task t07) ([7f44f14](https://github.com/glandais/vcyclist/commit/7f44f14a638ee0f90541c5d1f74acc3266fbd6f2))
* **engine:** make the racing line inspectable from JS and the CLI (Phase T task t11) ([796ca4a](https://github.com/glandais/vcyclist/commit/796ca4af196f1c4309c1aec32bdfc6eb8aea0506))
* **engine:** port the racing-line analysis to WASI and close the option allowlist ([36d295e](https://github.com/glandais/vcyclist/commit/36d295ec3dd3bfbea30e1899a925731563b55e23))
* **engine:** solve the racing line as a box-constrained QP (Phase T task t05) ([a0db8c6](https://github.com/glandais/vcyclist/commit/a0db8c6c2690bdd0cf0ef322acb86f2cdfe14638))
* **gpx:** carry road width from GPX into the Path (Phase T task t02) ([0d04fa6](https://github.com/glandais/vcyclist/commit/0d04fa6af462d182d8e38b4af981d801f7ec41b9))
* **gpx:** infer road width from the OSM highway class (Phase T task t08) ([07b5904](https://github.com/glandais/vcyclist/commit/07b5904dc2b7dffbcdd38d9a255f014500136ca1))
* **gpx:** NaN-default point fields and a trajectory curvature slot (Phase T task t01) ([34e8377](https://github.com/glandais/vcyclist/commit/34e83779e8160957087e7f2554c941dab3022f20))

# [4.1.0](https://github.com/glandais/vcyclist/compare/v4.0.0...v4.1.0) (2026-08-18)


### Bug Fixes

* **demo:** follow R17's rename and repair the broken power model (task 40) ([9200ee7](https://github.com/glandais/vcyclist/commit/9200ee718dee7c4efb52cba5d1fb66df7f089bec))


### Features

* **demo:** surface the ledger's rider models in the UI (task 42) ([cfd9dee](https://github.com/glandais/vcyclist/commit/cfd9deee156fa5a0f4bd9a8d91c58df10e32b7da))
* **engine:** reach R9, R15, R16, R18 and R19 from JavaScript (task 41) ([413a8c0](https://github.com/glandais/vcyclist/commit/413a8c0f3c50c64cfe9af2c38cb1cb82351beafa))
* **engine:** share one power catalog across the four surfaces (task 43) ([6ff3b94](https://github.com/glandais/vcyclist/commit/6ff3b94667ae5ce436fcf78a9901a3dd9160728b))

# [4.0.0](https://github.com/glandais/vcyclist/compare/v3.1.1...v4.0.0) (2026-08-17)


* feat(engine)!: fade power with supra-CP work, drop the elapsed-time decay (ledger R17) ([f51c5ed](https://github.com/glandais/vcyclist/commit/f51c5ed995309f7206dbd9d0076f426d5a780286))


### Features

* **engine:** add a rider who spends a W' reserve and settles at CP (ledger R16) ([0784cf3](https://github.com/glandais/vcyclist/commit/0784cf3197158b4732dc6f23d00d7c8a7dceb879))
* **engine:** add a wet/dry road-condition preset (ledger R9) ([edb3a87](https://github.com/glandais/vcyclist/commit/edb3a878801de408931cc54761813fadf32732e9))
* **engine:** annotate the W' balance of the Critical Power model (ledger R15) ([cff2263](https://github.com/glandais/vcyclist/commit/cff2263d53569d2323479e5b366f731a1aca8f10)), closes [#37](https://github.com/glandais/vcyclist/issues/37)
* **engine:** rate-limit how fast cyclist power may change (ledger R18) ([7132fe7](https://github.com/glandais/vcyclist/commit/7132fe71b6f2a14088f3c883c754c0df376a780e))
* **engine:** record braking power instead of discarding it (ledger R12) ([51183b0](https://github.com/glandais/vcyclist/commit/51183b0a688ce97ca50e391be21f3dfc1ca359bc)), closes [#38](https://github.com/glandais/vcyclist/issues/38)
* **engine:** ride harder uphill and easier downhill, as a heuristic (ledger R19) ([b2c7073](https://github.com/glandais/vcyclist/commit/b2c70731d4174b74846f754fe3be68144b75039b))
* **engine:** spend one friction budget on braking and cornering (ledger R11) ([63aa84e](https://github.com/glandais/vcyclist/commit/63aa84e337335c6c8777646c60bcf227243e4cd4))
* **engine:** stop pedalling past the pedal-strike lean angle (ledger R10) ([903e4cd](https://github.com/glandais/vcyclist/commit/903e4cd3fe81d8d54ed88f45537829edcc8296cb))


### BREAKING CHANGES

* PowerProviderConstantWithTiring is removed. The JS façade's
power.type = "constant_tiring" becomes "durability", and its "tiringDuration" field becomes
"criticalPower" (watts); the WASI JSON ABI takes the same rename. Callers wanting the old
elapsed-time fade have no replacement -- it was never sourced. The frozen WIT spike under
tools/wasi-component/ still describes constant-tiring: it is a replay artifact of the w13
experiment, not built by Gradle, and is left as it was.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>

## [3.1.1](https://github.com/glandais/vcyclist/compare/v3.1.0...v3.1.1) (2026-08-17)


### Bug Fixes

* **build:** give the browser tests the same 30 s mocha timeout as Node ([#47](https://github.com/glandais/vcyclist/issues/47)) ([cfe1035](https://github.com/glandais/vcyclist/commit/cfe10357e5590e848d2c7caae2af243a6329ae87))
* **engine:** correct physics constants from the research review ([#45](https://github.com/glandais/vcyclist/issues/45)) ([dc27bcf](https://github.com/glandais/vcyclist/commit/dc27bcfb93fab280eac3af28f0ef02bbf41ce14e)), closes [#2](https://github.com/glandais/vcyclist/issues/2)

# [3.1.0](https://github.com/glandais/vcyclist/compare/v3.0.0...v3.1.0) (2026-07-31)


### Bug Fixes

* **cli:** --fix-elevation now really corrects elevations (gpx2web task g34) ([6c3ec03](https://github.com/glandais/vcyclist/commit/6c3ec03d24d70e1b916475dbe17555a0141ebad2))


### Features

* **build:** add the WASI reference host harness (Phase WASI tâche w09) ([e9ddf50](https://github.com/glandais/vcyclist/commit/e9ddf50d8ef4c2ee44759702a5aacc5a34bc070a))
* **build:** cache wasmtime and upload test reports in CI (Phase WASI tâche w02) ([1f3c21e](https://github.com/glandais/vcyclist/commit/1f3c21ed1216eaacf0917acf6f325543fc1375cd))
* **build:** extend the wasmWasi target to :fit and :engine (Phase WASI tâche w01) ([0ae2dba](https://github.com/glandais/vcyclist/commit/0ae2dba511c6109954938d075dd3a1e17e268902))
* **build:** produce the standalone .wasm at a stable path, with a checksum (Phase WASI tâche w06) ([5a27960](https://github.com/glandais/vcyclist/commit/5a279609bb204d45a7a51430d87e7b7fffabbf5b))
* **build:** ship the standalone .wasm to Maven Central and the GitHub release (Phase WASI tâche w07) ([c7bbbf9](https://github.com/glandais/vcyclist/commit/c7bbbf9daaf10c63ca0878c1a5eb9dc4688565d0))
* **elevation:** decode lossless WebP in pure Kotlin (Phase WASI tâche w11) ([16ad07d](https://github.com/glandais/vcyclist/commit/16ad07d0d627ac44dd956b79267f69eaa5bc5aa2))
* **elevation:** serve DEM tiles from the host under WASI (Phase WASI tâche w05) ([5bd17dc](https://github.com/glandais/vcyclist/commit/5bd17dcc74ef184944b8b4dc012d0e133c08834c))
* **engine:** bring the WASI façade up to EngineJsApi parity (Phase WASI tâche w04) ([9cbd3cc](https://github.com/glandais/vcyclist/commit/9cbd3cc87710ddcd1e2ccd1951e9224497570471))
* **engine:** freeze the WASI ABI v1 in EngineWasiApi (Phase WASI tâche w03) ([9d1056e](https://github.com/glandais/vcyclist/commit/9d1056eceda174e0d7aa69810e612618e124838e))
* **fit:** encode FIT in commonMain over fit-kotlin-sdk (Phase WASI tâche w12) ([86a37f6](https://github.com/glandais/vcyclist/commit/86a37f6530261e4a7e7ca52be9a59333ab050fa3))

# [3.0.0](https://github.com/glandais/vcyclist/compare/v2.0.0...v3.0.0) (2026-07-28)


* feat(build)!: drop the Kotlin/Wasm (wasmJs) target ([5fc5a98](https://github.com/glandais/vcyclist/commit/5fc5a985de33b946e5b9b04443e8a993744c2676))
* feat(fit)!: rebase timestamps and write several paths per file (gpx2web task g25) ([696ab30](https://github.com/glandais/vcyclist/commit/696ab30f64e7c4f891c3685c8d087a4f32e4bcca))


### Bug Fixes

* **cli:** write every track to CSV and JSON, not just the first (gpx2web task g28) ([ef1ab40](https://github.com/glandais/vcyclist/commit/ef1ab408b0ea0239289af5d6f378d79d9f1ce7ab))


### Features

* **elevation:** accept a Java tile fetcher, so a disk cache can plug in (gpx2web task g32) ([36b869e](https://github.com/glandais/vcyclist/commit/36b869e96e3179c284a61c64b8122a3a7443cea6))
* **elevation:** expose attribution in the Java config factory (gpx2web task g32) ([f570d77](https://github.com/glandais/vcyclist/commit/f570d777b516439a09ca3d17f09a0b55eb538f6e))
* **elevation:** split tile fetching from tile decoding (gpx2web task g21) ([f03b227](https://github.com/glandais/vcyclist/commit/f03b22789fa8acedc56c4c29c8dbbbb952a13b46))
* **engine:** add JVM blocking and future bridges for the suspend API (gpx2web task g22) ([67f1759](https://github.com/glandais/vcyclist/commit/67f17595fc07697267b94a5da500408dfcab40a2))
* **engine:** catch the JS facade up with g23, g24 and g25 (gpx2web task g29) ([65ec0be](https://github.com/glandais/vcyclist/commit/65ec0befb73633c0cd943ac7235bcf6dad1ef174))
* **engine:** export the worst-case wind direction to JavaScript (gpx2web task g31) ([50ea666](https://github.com/glandais/vcyclist/commit/50ea6661671ad30f086d996abca59f891f1fd21f))
* **engine:** port getWind as dominantHeadwindDirection (gpx2web task g26) ([d9b3153](https://github.com/glandais/vcyclist/commit/d9b3153756e215b232f0d9eac03f9585e4850860))
* **gpx:** add a writeExtensions flag to the GPX writer (gpx2web task g23) ([e6d0c2c](https://github.com/glandais/vcyclist/commit/e6d0c2c90842d5250c69fd6c43d8bb670103148d))
* **gpx:** add JVM facades so Java can use the default arguments (gpx2web task g27) ([1211767](https://github.com/glandais/vcyclist/commit/121176765d93871dfc6cb63966a69b3e557e3792))
* **gpx:** close the four JVM facade gaps the Quarkus migration surfaced (gpx2web task g33) ([15119bb](https://github.com/glandais/vcyclist/commit/15119bbc14ed526cd06ca0f75c0b96b5dd52c19a))
* **gpx:** let the caller choose which power the GPX carries (gpx2web task g30) ([33e5765](https://github.com/glandais/vcyclist/commit/33e5765b415a646a04b72381b7f3189c4dcc0ed5))
* **gpx:** read and write GPX routes (gpx2web task g24) ([74a3262](https://github.com/glandais/vcyclist/commit/74a3262c2a8aea6fe6beed5404b3b11ad8e1730e))


### BREAKING CHANGES

* FitCourse.copy(records = ...) and copy(lap = ...) no
longer exist -- records and lap are now derived accessors over segments.
Reading course.records and course.lap is unchanged for a single-segment
course, and the previous constructor still resolves through a companion
invoke; only copy() is affected.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
* the npm packages @glandais/vcyclist-engine-wasm, @glandais/vcyclist-elevation-wasm
and @glandais/vcyclist-fit-wasm are no longer built or published, and the wasmJs Gradle target and
its Maven artifacts are gone. Consumers must switch to the Kotlin/JS packages
(@glandais/vcyclist-engine, @glandais/vcyclist-elevation) or the JVM artifacts.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>

# [2.0.0](https://github.com/glandais/vcyclist/compare/v1.2.1...v2.0.0) (2026-07-28)


* feat(cli)!: remove EngineCli in favour of the :cli module (gpx2web task g18) ([91a5dd2](https://github.com/glandais/vcyclist/commit/91a5dd2e4a32ebf6dd4fbe55cbca359b902f2ea7))


### Bug Fixes

* **engine:** honour a genuine 0 °C reading in RhoProviderEstimate ([b74096e](https://github.com/glandais/vcyclist/commit/b74096e4b8e545ddf922707a18016bf9b37481a5))
* **fit:** keep a genuine zero sensor reading instead of dropping it ([1d0d3e4](https://github.com/glandais/vcyclist/commit/1d0d3e41149d8fb61001954a10c47a1f3b6f2423))
* **gpx:** keep predefined XML entities when reading element text ([47b1512](https://github.com/glandais/vcyclist/commit/47b1512f59a5c256cc61f1449112c6a4a3e673e6))
* **gpx:** write NaN for an absent GPX sensor instead of leaving it 0.0 ([f3eec22](https://github.com/glandais/vcyclist/commit/f3eec22b614b6028b9e3fdf73c7dcfaa5aeb6b86))


### Features

* **build:** wire publication for the new modules (gpx2web task g19) ([27271b1](https://github.com/glandais/vcyclist/commit/27271b1ccebaf2bf70b763f797a58d50974369e4))
* **cli:** add the enhance and export subcommands (gpx2web task g17) ([f8e497f](https://github.com/glandais/vcyclist/commit/f8e497feff171c3c41b7caa75ae51ea1dd6b936d))
* **cli:** bootstrap the picocli CLI module with shared mixins (gpx2web task g16) ([340117c](https://github.com/glandais/vcyclist/commit/340117c819451268620e6f061814d27f35c07964))
* **engine:** expose climb detection to JS/Wasm and show it in the demo (gpx2web task g12) ([a1a074a](https://github.com/glandais/vcyclist/commit/a1a074a4fdfe54f6cb99f80cfd410fb3f01acf09))
* **engine:** port gpx2web's climb detector (gpx2web task g11) ([5fdb257](https://github.com/glandais/vcyclist/commit/5fdb25789e55c0d5956e0898046f40b0539a2a05))
* **fit:** bootstrap the :fit module with its JVM encoder (gpx2web task g08) ([9247dd4](https://github.com/glandais/vcyclist/commit/9247dd43b51ffea76761982bd55bc8bea849db1b)), closes [hi#level](https://github.com/hi/issues/level)
* **fit:** convert Path to FitCourse and validate the round-trip (gpx2web task g10) ([f3bce12](https://github.com/glandais/vcyclist/commit/f3bce1298db90e0ae5bfc7d9ecf2fbdfda454a44))
* **fit:** implement the JS and Wasm encoders on @garmin/fitsdk (gpx2web task g09) ([f7813d6](https://github.com/glandais/vcyclist/commit/f7813d68daa5a2c435c89cf8e566f4c96fe8a449))
* **gpx:** add a CSV writer for the 36 PointField columns (gpx2web task g06) ([3848e99](https://github.com/glandais/vcyclist/commit/3848e99fbc72c1e1336353532d87bdff1d99f21a))
* **gpx:** add a JSON writer for Path data (gpx2web task g07) ([37790ee](https://github.com/glandais/vcyclist/commit/37790eeb59fa745c5b6093de0f4d763f9d106307))
* **gpx:** add an explicit startTime for absolute timestamps (gpx2web task g05) ([90ea8e0](https://github.com/glandais/vcyclist/commit/90ea8e0b1d922b959c1acf947ad99d9a6ae6a22a))
* **gpx:** parse, enhance and write multi-track / multi-segment GPX (gpx2web task g02) ([9e0ff77](https://github.com/glandais/vcyclist/commit/9e0ff7787d467d5873e21d08ec636d37e8584f12))
* **gpx:** parse, preserve and write GPX waypoints (gpx2web task g03) ([2ee494c](https://github.com/glandais/vcyclist/commit/2ee494c2ea4cb477881e38c6772347337ebcd9e4))
* **gpx:** repair malformed GPX XML before parsing (gpx2web task g04) ([8993d58](https://github.com/glandais/vcyclist/commit/8993d58cf9f8921f9120d4c2decca625e19dc602))
* **map:** add the JVM-only :map module with projection and framing (gpx2web task g13) ([2735e6a](https://github.com/glandais/vcyclist/commit/2735e6aa08508f0a68ba54660be8b61b01bc1e7a))
* **map:** download, cache and render tile backgrounds (gpx2web task g14) ([9105cce](https://github.com/glandais/vcyclist/commit/9105cce52577ab0f1d51ad1103f8761e00c9946f))
* **map:** render hypsometric terrain maps from DEM data (gpx2web task g15) ([eed2456](https://github.com/glandais/vcyclist/commit/eed245652027f5690977ee64a2c9ed08630aae41))


### BREAKING CHANGES

* `io.github.glandais.engine.EngineCli` is removed from the published
`vcyclist-engine` JVM artefact, along with the `:engine:run` Gradle task. The replacement is the
`:cli` module: `vcyclist enhance <in> --gpx <out>` covers what it did, with identical exit codes
(64/66/70) so existing scripts keep working.

The coverage table came first, and it earned its keep. Enumerating every gpxtools-cli option
turned up two with no vcyclist equivalent, which a straight deletion would have silently lost:

- `process --gpx-power` — replay the recorded power instead of the constant. vcyclist already had
  PowerProviderFromData; the CLI just never exposed it. Now `enhance --gpx-power`, same name.
- `export --gpx` — re-write the GPX. Now `export --gpx`, same name.

One mapping is inverted and worth knowing: `process --gpx-elevation` asserts the file's elevation
is already good, i.e. do not correct it. That is vcyclist's default, and the explicit form is
`--no-fix-elevation`.

Four options were renamed, each for a reason recorded in cli/README.md: `--start` to
`--start-time` (symmetry with export), `--map-srtm` to `--elevation-map` ("srtm" named a data
source, not the hypsometric output it actually produces), and `--map-tile-url` / `--map-width` /
`--map-height` shortened since they are unambiguous inside `export`.

FullPipelineSmokeTest is kept, so :engine retains full-pipeline coverage without the CLI. The
only remaining EngineCli mentions in code are three comments explaining the replacement.

CI now smokes the packaged jar: build it, run --help and --version, then run a full enhance from
a different working directory and check all four outputs are non-empty and the FIT carries its
".FIT" marker. The CLI is the only consumer that crosses :gpx -> :engine -> :fit -> :map in one
go, so it doubles as the repo's best integration test, and a fat jar that builds but cannot run
is the classic failure this catches. The step was replayed locally before committing.

On the version: removing a public class from a published artefact is formally breaking, even
though real usage is almost certainly nil — "almost certainly nil" is not a semver argument. The
`!` will make semantic-release cut a major on merge to develop. Nothing fires from this branch,
so the commit type can still be amended if the version decision should be grouped with g19.

check + ktlintCheck green.

Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>

## [1.2.1](https://github.com/glandais/vcyclist/compare/v1.2.0...v1.2.1) (2026-07-20)


### Bug Fixes

* **build:** unblock release check step from setup-node npmrc auth token ([#32](https://github.com/glandais/vcyclist/issues/32)) ([7337de5](https://github.com/glandais/vcyclist/commit/7337de58949e8827014cbc5327cb497afa7b36b7))

# [1.2.0](https://github.com/glandais/vcyclist/compare/v1.1.1...v1.2.0) (2026-05-11)


### Features

* **demo:** add GitHub Pages deploy workflow for the Vue demo (Phase 9 task 39, optional) ([8b976b1](https://github.com/glandais/vcyclist/commit/8b976b1dd8a0188b9142b162a4b64adf530380cf)), closes [#pages](https://github.com/glandais/vcyclist/issues/pages) [#pages](https://github.com/glandais/vcyclist/issues/pages)
* **demo:** bootstrap Vue 3 + Vite shell consuming Kotlin/JS engine (Phase 9 task 35) ([03aeaf6](https://github.com/glandais/vcyclist/commit/03aeaf62463e65c86a09e0b21bd22be1cdbf50ca))
* **demo:** integrate :demo into Gradle build with samples + docs (Phase 9 task 38) ([3416e9b](https://github.com/glandais/vcyclist/commit/3416e9b15424da65786254c403ddf9bc33ae16bf))
* **demo:** port full UI — chart, map, tabs, sidebar (Phase 9 task 37) ([bdd7c16](https://github.com/glandais/vcyclist/commit/bdd7c16beaedcc0405dff4a92108108980cfafb0))
* **demo:** wire engine into Vue demo with config persistence (Phase 9 task 36) ([e2fa8ac](https://github.com/glandais/vcyclist/commit/e2fa8ac0445ec51a340be14c919dbdf58b137c09))
* **engine:** expand JS façade with course-aware enhance + generic field accessors (Phase 9 task 34) ([c3f330d](https://github.com/glandais/vcyclist/commit/c3f330d04411913e24f9d71dc34a97954f4083b0))

## [1.1.1](https://github.com/glandais/vcyclist/compare/v1.1.0...v1.1.1) (2026-05-11)


### Bug Fixes

* **ci:** grant pull-requests:write to release workflow ([abc6201](https://github.com/glandais/vcyclist/commit/abc62012fd938e73ff7c4b9fc34564a626c2e472))

# [1.1.0](https://github.com/glandais/vcyclist/compare/v1.0.0...v1.1.0) (2026-05-11)


### Bug Fixes

* **elevation:** correctly init @jsquash/webp WASM in Node tile fetcher ([4871067](https://github.com/glandais/vcyclist/commit/48710677376310132e181637149a1a93c47c315b))


### Features

* **elevation:** tile fetcher Node.js / Bun (Phase 3 task 32) ([99de2cd](https://github.com/glandais/vcyclist/commit/99de2cd73cd7d760f1ae6186b3b33d124e081210))
* **engine,elevation:** Node integration tests with elevation enabled (Phase 3 task 33) ([75ae69f](https://github.com/glandais/vcyclist/commit/75ae69ff4978dbd54d4218c9c37de50c9cac4952))

# 1.0.0 (2026-05-11)


### Bug Fixes

* **build:** drop assemble prepareCmd and order webpack after library compile ([c98adad](https://github.com/glandais/vcyclist/commit/c98adad802b145f2bc11c3472eeb1c8e59c1c819))
* **elevation/js:** browser E2E — namespace shim + fetch RequestInit bypass ([0bb2a34](https://github.com/glandais/vcyclist/commit/0bb2a347d4a83010c37c53b58d1beef7e7881fdf))
* **engine:** VirtualizeService simulates last point (was leaking source epoch) (Phase 2bis task 29) ([d80d56d](https://github.com/glandais/vcyclist/commit/d80d56dbd6c67bb52f322245b09bd3db305c2853)), closes [7/#12](https://github.com/glandais/vcyclist/issues/12)


### Features

* **elevation:** add WASM browser demo + JS-exported façade ([a095ff8](https://github.com/glandais/vcyclist/commit/a095ff8ddbfbb1db54481227ad275bdbdfc39942))
* **elevation:** BatchCalculator + SmoothingOptions/FilterOptions ([78a93b9](https://github.com/glandais/vcyclist/commit/78a93b948bb2bb76dc50b431bb3964094d884614))
* **elevation:** ElevationProvider public API + config — end of Phase 1 ([325add2](https://github.com/glandais/vcyclist/commit/325add29b07d360db6b0e71f63e082fc03018aab))
* **elevation:** Flux + ElevationCalculator with true bilinear interpolation ([409ed40](https://github.com/glandais/vcyclist/commit/409ed40122182234302e9ae00bbf71afdd7b47b7))
* **elevation:** Kotlin/JS browser demo (sibling of WASM) ([3090acf](https://github.com/glandais/vcyclist/commit/3090acfb44457c35f7152f47be773c06de1d6265))
* **elevation:** LruCache (KMP, suspend-aware) + TileManager ([6edbb5c](https://github.com/glandais/vcyclist/commit/6edbb5cdff9cf3b01c77da257892ca5025ea316d))
* **elevation:** multi-target tile fetcher (JVM HTTP+ImageIO, Wasm browser fetch+canvas) ([3a78987](https://github.com/glandais/vcyclist/commit/3a789872795cded6475440f3ac29e3ab71cb401b))
* **elevation:** port Coordinates, Constants and Vector3D to KMP ([f3b5897](https://github.com/glandais/vcyclist/commit/f3b5897a9161c38d85626c50fb1c4d8f43707559))
* **elevation:** port Distance and EcefConverter to KMP ([8ffac1a](https://github.com/glandais/vcyclist/commit/8ffac1ada05f543c3c60d1e24240922b0422a093))
* **elevation:** port DouglasPeucker 3D simplification to KMP ([edd17be](https://github.com/glandais/vcyclist/commit/edd17bef7b96409cba0df3a74c510531e026facd))
* **elevation:** port ElevationSmoother (triangular kernel) to KMP ([b30c6a0](https://github.com/glandais/vcyclist/commit/b30c6a005a74d688a408577deb4bc35614453a48))
* **elevation:** port tile types, ElevationFunctions, Tile decoding to KMP ([48eb97f](https://github.com/glandais/vcyclist/commit/48eb97fa321a659b1c33374219ec60c56b53b136))
* **engine:** @JsExport facade for JS Node + Wasm browser (Phase 2 task 28) ([a930f1c](https://github.com/glandais/vcyclist/commit/a930f1c61a2a66bf93e6a98422b9de0eef102efb))
* **engine:** add PointField/PointFieldCategory enums (Phase 2 task 10) ([2d20d4a](https://github.com/glandais/vcyclist/commit/2d20d4a579c140ca9babd2b906f49e9c60f7976c))
* **engine:** Cyclist, Bike, Course models + constants (Phase 2 task 13) ([a266111](https://github.com/glandais/vcyclist/commit/a2661119e3af9d2fbf64a73bbd870277b7d88c55))
* **engine:** CyclistPowerProvider + 4 impls + MuscularPowerProvider (Phase 2 task 18) ([a4bb1ec](https://github.com/glandais/vcyclist/commit/a4bb1ecf9b7ea4215ebce9214bc3e12adf0a7c89))
* **engine:** ElevationStep bridge to :elevation (Phase 2 task 24) ([00c92e3](https://github.com/glandais/vcyclist/commit/00c92e3b96930bd0012353f6908a0216871b5e7e))
* **engine:** EngineCli JVM entry point + Gradle run task (Phase 2 task 27) ([4dbebf9](https://github.com/glandais/vcyclist/commit/4dbebf9e0b355ef9fb65148d6d4847df48aadb1f))
* **engine:** Enhancer pipeline orchestrator (Phase 2 task 25) ([fad5e96](https://github.com/glandais/vcyclist/commit/fad5e967de2bdf0079fdfff99287fdd21c32c8d7))
* **engine:** generate Path accessors via :codegen subproject (Phase 2 task 11) ([97ed1d9](https://github.com/glandais/vcyclist/commit/97ed1d9d7c1330cd6f6246770354d30a5357d8ed))
* **engine:** GPX parser with xmlutil + Path bridge (Phase 2 task 14) ([eb58cea](https://github.com/glandais/vcyclist/commit/eb58cea71a84103654a1954a5f0cef468e28f5f6))
* **engine:** GPX writer with namespace handling + round-trip (Phase 2 task 15) ([8c409ca](https://github.com/glandais/vcyclist/commit/8c409ca1e4d0070eb9817ab9e8aeaf226f6f0962))
* **engine:** integrate PointPerDistance into Enhancer pipeline (Phase 2bis task 31) ([6b3cca7](https://github.com/glandais/vcyclist/commit/6b3cca7af1942ed37cfc297540e53db4dd19d096)), closes [#12](https://github.com/glandais/vcyclist/issues/12)
* **engine:** MaxSpeedComputer with cornering + braking (Phase 2 task 20) ([eea84a5](https://github.com/glandais/vcyclist/commit/eea84a55e64872e2e9775fc3a5dcefac6fa1e385))
* **engine:** Path with stats, helpers and :elevation bridge (Phase 2 task 12) ([efc9d17](https://github.com/glandais/vcyclist/commit/efc9d17cc9270c4c4447249c9192b0e6598b137f))
* **engine:** PathSimplifier wrapping :elevation DouglasPeucker (Phase 2 task 23) ([563a86d](https://github.com/glandais/vcyclist/commit/563a86d03257b8f2f9912a48488f8684d1e5896f))
* **engine:** PointPerDistance distance-based resampler (Phase 2bis task 30) ([b9d9360](https://github.com/glandais/vcyclist/commit/b9d9360e5ab95957f72c0abb6fefe1e1de39373a))
* **engine:** PointPerSecond 1Hz resampler (Phase 2 task 22) ([45a5eff](https://github.com/glandais/vcyclist/commit/45a5eff21c24431125b244b4281cc64330111021)), closes [hi#freq](https://github.com/hi/issues/freq)
* **engine:** PowerComputer + energy equation (Phase 2 task 19) ([dd28f9e](https://github.com/glandais/vcyclist/commit/dd28f9e897cf104b7435ea1093283e1427611c1d))
* **engine:** PowerProvider + 4 physics impls + AeroProvider (Phase 2 task 17) ([9f53121](https://github.com/glandais/vcyclist/commit/9f53121a381e4c5cff0024987dc8b03ed468874a))
* **engine:** RhoProvider + WindProvider with ISA model (Phase 2 task 16) ([cf6b908](https://github.com/glandais/vcyclist/commit/cf6b9085dea34a69d73301ee7107995ddd0dc9bc))
* **engine:** VirtualizeService time-stepping simulation (Phase 2 task 21) ([1d19c04](https://github.com/glandais/vcyclist/commit/1d19c0420b4deb3436c3bf0813b0e89a2b066ce5))
* release ([76e5874](https://github.com/glandais/vcyclist/commit/76e587497443ac64a74e4882a6c32f9b85ddc757))
