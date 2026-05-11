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
