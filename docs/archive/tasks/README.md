# Task specs

One file per task: *Goal / Depends on / Inputs / Steps / Outputs / Validation / Done when / Notes*.

**These are frozen.** A spec says what was intended on the day it was written, and several were
overtaken by what the implementation measured — the racing-line series most of all. When a spec and
the code disagree, the code is right and [`../../ledgers/`](../../ledgers/) explains why.

Four ID namespaces share this directory, one per plan:

| Prefix | Plan | Subject |
|---|---|---|
| `NN` | [`../plans/PLAN.md`](../plans/PLAN.md) | Port of the TypeScript references, the demo, the research catch-up |
| `gNN` | [`../plans/PLAN-GPX2WEB.md`](../plans/PLAN-GPX2WEB.md) | Port of what only existed in the Java gpx2web reference |
| `wNN` | [`../plans/PLAN-WASM-WASI.md`](../plans/PLAN-WASM-WASI.md) | The standalone WASI module |
| `tNN` | [`../plans/racing-line-design.md`](../plans/racing-line-design.md) (§11) | The racing line — no plan file, its status lives in the design's header |

Unless a row says otherwise, the task shipped and the owning plan carries its commit hash.

## Port, demo and catch-up (`NN`)

| ID | Subject | Note |
|---|---|---|
| [`00`](00-bootstrap.md) | Bootstrap projet vcyclist |  |
| [`01`](01-elevation-coords-vector.md) | Elevation : Coordinates, Constants & Vector3D |  |
| [`02`](02-elevation-distance-ecef.md) | Elevation : Distance & EcefConverter |  |
| [`03`](03-elevation-douglas-peucker.md) | Elevation : DouglasPeucker (simplification 3D) |  |
| [`04`](04-elevation-smoother.md) | Elevation : ElevationSmoother (lissage par noyau triangulaire) |  |
| [`05`](05-elevation-tile-types-decoding.md) | Elevation : types tuile, ElevationFunctions, Tile (décodage Terrarium) |  |
| [`06`](06-elevation-tile-fetcher.md) | Elevation : tile fetcher (HTTP + décodage image multi-target) |  |
| [`07`](07-elevation-tile-cache.md) | Elevation : LRU cache (KMP) + TileManager |  |
| [`08`](08-elevation-provider-batch.md) | Elevation : ElevationCalculator + Flux + BatchCalculator + ElevationProvider |  |
| [`09`](09-elevation-integration.md) | Elevation : tests d'intégration HTTP réels (tuiles mapterhorn) |  |
| [`10`](10-engine-field-definitions.md) | Engine : `PointField` (source de vérité des 36 champs) |  |
| [`11`](11-engine-codegen-strategy.md) | Engine : codegen `GeneratedPath` (DoubleArray plat + accesseurs typés) |  |
| [`12`](12-engine-path.md) | Engine : `Path` (stats + helpers + bridge `:elevation`) |  |
| [`13`](13-engine-cyclist-bike.md) | Engine : `Cyclist`, `Bike`, `Course`, constantes physiques |  |
| [`14`](14-engine-gpx-parser.md) | Engine : GPX parser (XML → `Path`) |  |
| [`15`](15-engine-gpx-writer.md) | Engine : GPX writer (`Path`/`GpxDocument` → XML) |  |
| [`16`](16-engine-rho-wind-providers.md) | Engine : `RhoProvider` (air density) + `WindProvider` |  |
| [`17`](17-engine-power-providers.md) | Engine : `PowerProvider` + 4 implémentations physiques + `AeroProvider` + `CoursePhysics` |  |
| [`18`](18-engine-cyclist-power-providers.md) | Engine : `CyclistPowerProvider` + 4 impls + `MuscularPowerProvider` |  |
| [`19`](19-engine-power-computer.md) | Engine : `PowerComputer` (équation cinétique + masse équivalente) |  |
| [`20`](20-engine-max-speed-computer.md) | Engine : `MaxSpeedComputer` (cornering + braking) |  |
| [`21`](21-engine-virtualize-service.md) | Engine : `VirtualizeService` (simulation time-stepping) |  |
| [`22`](22-engine-point-per-second.md) | Engine : `PointPerSecond` (resampling 1 Hz) |  |
| [`23`](23-engine-douglas-peucker-3d.md) | Engine : `PathSimplifier` (Douglas-Peucker 3D sur `Path`) |  |
| [`24`](24-engine-elevation-fix-step.md) | Engine : `ElevationStep` (fix + smooth elevation via `:elevation`) |  |
| [`25`](25-engine-enhancer.md) | Engine : `Enhancer` (pipeline orchestrateur) |  |
| [`26`](26-engine-parity-fixtures.md) | Engine : parité numérique vs TS (fixtures end-to-end) |  |
| [`27`](27-engine-cli-smoke.md) | Engine : `EngineCli` (JVM smoke entry point) |  |
| [`28`](28-engine-js-wasm-public-api.md) | Engine : façade `@JsExport` (JS Node + Wasm browser) |  |
| [`29`](29-engine-virtualize-time-fix.md) | Engine : `VirtualizeService` — bug timestamps absolus du dernier point |  |
| [`30`](30-engine-point-per-distance.md) | Engine : `PointPerDistance` (resampling à distance constante) |  |
| [`31`](31-engine-enhancer-densify.md) | Engine : intégrer `PointPerDistance` dans `Enhancer` |  |
| [`32`](32-node-tile-fetcher.md) | Elevation : tile fetcher Node.js / Bun (runtime detection + @jsquash/webp) |  |
| [`33`](33-node-integration-tests.md) | Tests d'intégration Node : elevation réelle + pipeline `enhance` avec `fixElevation` |  |
| [`34`](34-engine-js-api-expansion.md) | Engine `@JsExport` façade : expansion pour la démo Vue |  |
| [`35`](35-demo-bootstrap.md) | Démo Vue/Vite : bootstrap |  |
| [`36`](36-demo-engine-integration.md) | Démo Vue/Vite : intégration moteur (upload → enhance) |  |
| [`37`](37-demo-ui-port.md) | Démo Vue/Vite : portage UI complète (chart + map + tabs + sidebar) |  |
| [`38`](38-demo-build-gradle.md) | Démo Vue/Vite : intégration Gradle + GPX samples + docs |  |
| [`39`](39-demo-gh-pages.md) | Démo Vue/Vite : déploiement GitHub Pages (optionnel) | Closed on 2026-08-18; the workflow ships. |
| [`40`](40-demo-r17-breakage.md) | Démo : réparer la rupture R17 (`constant_tiring` → `durability`) |  |
| [`41`](41-js-facade-ledger-catchup.md) | Façade JS : rattrapage sur R9, R15, R16, R18 et R19 |  |
| [`42`](42-demo-ledger-ui.md) | Démo : UI des modèles issus du ledger recherche |  |
| [`43`](43-facade-parity-guard.md) | Garde-fou : empêcher la façade JS et la démo de décrocher du cœur |  |
| [`44`](44-racing-line-merge.md) | Réconcilier `feat/demo-update` et `feat/racing-line` | Two cosmetic boxes left open on purpose — see its own closing note. |
| [`45`](45-elapsed-dt-units.md) | `elapsed` et `dt` : une seule unité, la seconde |  |

## gpx2web port (`gNN`)

| ID | Subject | Note |
|---|---|---|
| [`g01`](g01-gpx-module-extraction.md) | Extraction du module `:gpx` |  |
| [`g02`](g02-gpx-multi-track.md) | Multi-track / multi-segment de bout en bout |  |
| [`g03`](g03-gpx-waypoints.md) | Waypoints `<wpt>` |  |
| [`g04`](g04-gpx-xml-repair.md) | `GpxXmlRepair` : réparation des GPX malformés |  |
| [`g05`](g05-gpx-start-time.md) | `startTime` : horodatage absolu à l'écriture |  |
| [`g06`](g06-export-csv.md) | Writer CSV |  |
| [`g07`](g07-export-json.md) | Writer JSON |  |
| [`g08`](g08-fit-module-jvm.md) | Module `:fit` : bootstrap + implémentation JVM |  |
| [`g09`](g09-fit-js-wasm.md) | `:fit` : implémentation JS et Wasm (`@garmin/fitsdk`) |  |
| [`g10`](g10-fit-course-encoder.md) | `:fit` : conversion `Path` → `FitCourse` et round-trip |  |
| [`g11`](g11-climb-detector.md) | Port de `ClimbDetector` |  |
| [`g12`](g12-climb-js-demo.md) | Cols : façade `@JsExport` et intégration démo |  |
| [`g13`](g13-map-projection.md) | Module `:map` : projection et `MapImage` |  |
| [`g14`](g14-map-tiles.md) | `:map` : `TileMapProducer` (tuiles + cache) |  |
| [`g15`](g15-map-elevation-profile.md) | `:map` : `SRTMMapProducer` (profil d'élévation PNG) |  |
| [`g16`](g16-cli-bootstrap.md) | Module `:cli` : bootstrap picocli et mixins |  |
| [`g17`](g17-cli-subcommands.md) | `:cli` : sous-commandes `process`, `virtualize`, `export` |  |
| [`g18`](g18-cli-migration.md) | Retrait d'`EngineCli` et documentation du CLI |  |
| [`g19`](g19-publishing.md) | Publication des nouveaux artefacts |  |
| [`g20`](g20-coverage-matrix.md) | Matrice de correspondance gpx2web → vcyclist |  |
| [`g21`](g21-tile-fetch-decode-split.md) | `TileFetcher` : séparer le téléchargement du décodage |  |
| [`g22`](g22-jvm-blocking-bridges.md) | Ponts JVM pour les API `suspend` |  |
| [`g23`](g23-gpx-extensions-option.md) | Option d'écriture des `<extensions>` GPX |  |
| [`g24`](g24-gpx-routes.md) | Lecture et écriture des GPX `<rte>` / `<rtept>` |  |
| [`g25`](g25-fit-multi-path.md) | FIT : multi-`Path` et contrat de timestamp |  |
| [`g26`](g26-path-wind.md) | Port de `GPXDataComputer.getWind` |  |
| [`g27`](g27-jvm-overloads.md) | `@JvmOverloads` sur l'API publique |  |
| [`g28`](g28-cli-multi-track-exports.md) | CSV et JSON : arrêter de perdre les pistes 2..n |  |
| [`g29`](g29-js-facade-catchup.md) | Rattrapage de la façade JS sur g23, g24 et g25 |  |
| [`g30`](g30-gpx-power-export.md) | Quelle puissance le GPX exporte-t-il ? |  |
| [`g31`](g31-wind-js-facade.md) | Façade JS pour `dominantHeadwindDirection` |  |
| [`g32`](g32-elevation-jvm-fetcher.md) | Fabrique JVM acceptant un fetcher de tuiles |  |
| [`g33`](g33-jvm-facade-gaps.md) | Les quatre trous que g27 a laissés, trouvés en migrant |  |
| [`g34`](g34-cli-fix-elevation-noop.md) | `--fix-elevation` du CLI ne corrige aucune élévation |  |

## WASI module (`wNN`)

| ID | Subject | Note |
|---|---|---|
| [`w01`](w01-wasmwasi-target-fit-engine.md) | Étendre la cible `wasmWasi` à `:fit` et `:engine` |  |
| [`w02`](w02-ci-wasmtime.md) | CI : exécuter les tests wasmtime et mettre wasmtime en cache |  |
| [`w03`](w03-engine-wasi-abi-v1.md) | `EngineWasiApi` : figer l'ABI v1 et absorber le POC `GpxWasiApi` |  |
| [`w04`](w04-wasi-facade-parity.md) | Parité fonctionnelle de la façade WASI avec `EngineJsApi` |  |
| [`w05`](w05-elevation-host-injected.md) | Élévation host-injectée : import `fetch_tile` et pont `suspend` → synchrone |  |
| [`w06`](w06-wasm-distribution-task.md) | Tâche Gradle de distribution du `.wasm` |  |
| [`w07`](w07-publish-wasm-artifact.md) | Publier le `.wasm` : Maven Central et release GitHub | **Open** — blocked on `w08`. |
| [`w08`](w08-kotlin-2420-final.md) | Passage à Kotlin 2.4.20 final et re-vérifications | **Open** — waiting for Kotlin 2.4.20 final on Maven Central. |
| [`w09`](w09-host-harness.md) | Harnais d'hôtes de référence (`tools/wasi/`) |  |
| [`w10`](w10-documentation.md) | Documentation utilisateur de l'ABI WASI |  |
| [`w11`](w11-vp8l-decoder.md) | Décodeur WebP / VP8L pur Kotlin (optionnel) |  |
| [`w12`](w12-fit-encoder-kotlin.md) | Encodeur FIT pur Kotlin |  |
| [`w13`](w13-spike-component-model.md) | Spike : Component Model / WASI 0.2 (exploratoire, timeboxé) |  |

## Racing line (`tNN`)

| ID | Subject | Note |
|---|---|---|
| [`t01`](t01-nan-default-curvature-field.md) | `nanDefault` in codegen + `TRAJECTORY_CURVATURE` field |  |
| [`t02`](t02-road-width.md) | `ROAD_WIDTH` field + GPX width plumbing |  |
| [`t03`](t03-curvature-estimator.md) | Planar frame + heading-regression curvature estimator |  |
| [`t04`](t04-corner-detector-corridor.md) | Corner detector + corridor |  |
| [`t05`](t05-offset-qp.md) | Offset QP: banded solver, energy, projected Newton |  |
| [`t06`](t06-time-weighting.md) | Time weighting: IRLS toward `∫√κ ds`, and the saturation mask | Implemented in full, then **reverted**: measured worse. Ledger R25. |
| [`t07`](t07-enhancer-integration.md) | Enhancer integration and the option surfaces |  |
| [`t11`](t11-racing-line-inspection.md) | Make the racing line inspectable |  |
| [`t14`](t14-osm-highway-width.md) | Road width from the OSM `highway` class | Shipped, and measured worth ~0.005 %. Ledger R26. |

## Out of series

| ID | Subject | Note |
|---|---|---|
| [`bonus`](bonus-elevation-js-demo.md) | Bonus — Démo browser Kotlin/JS pour `:elevation` |  |

## Specs that were never written

`racing-line-design.md` §11 planned five more tasks that do not exist here, and the reason is
recorded rather than left to inference:

| ID | Subject | Why not |
|---|---|---|
| `t08` | Roundabout Shape A | Five conjunctive gates with no real-world corpus to validate against; the feasibility study rated the payoff below the risk |
| `t09` | Junction reconstruction | *"The most dangerous thing this design can do"* — it fabricates road geometry that is not in the input |
| `t10` | Lattice-DP fallback | A second trajectory producer to keep consistent with the first, for a case the QP already handles |
| `t12` | `LEAN_ANGLE` field + Zignoli's roll gate | Superseded: pedal-strike clearance shipped as ledger R10 |
| `t13` | Friction-ellipse coupling in `MaxSpeedComputer` | Already shipped as ledger R11 (`63aa84e`) before the design was written |

`t08`–`t10` were also gated on `t14` (OSM ingestion), which shipped and measured ~0 effect — so the
dependency they were waiting on turned out not to be the blocker the design took it for.
