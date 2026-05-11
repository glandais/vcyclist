# Réécriture de virtual-cyclist en Kotlin Multiplatform

## Contexte

Le projet TypeScript `virtual-cyclist` (simulateur de cyclisme basé physique avec pipeline 5 étapes : fix elevation → max speeds → virtualize 1Hz → resample → simplify) doit être réécrit en Kotlin Multiplatform dans `/home/glandais/code/perso/vcyclist-all/vcyclist/`. La réécriture s'inspire fortement du projet Java `gpx2web` (architecture `PowerProvider`, modèle de points typés, `MaxSpeedComputer`, `VirtualizeService`) et utilise une réécriture de la lib voisine `elevation` (port complet en Kotlin du fetch de tuiles Terrarium, interpolation bilinéaire, Haversine, Douglas-Peucker 3D, lissage triangulaire).

**Pourquoi ce changement** : Kotlin Multiplatform permet d'avoir un seul codebase moteur compilable en JVM (dev/tests rapides, intégration backend), Wasm (browser pour la demo), JS Node (CLI/scripts), et plus tard natif si besoin. Une demo Compose Multiplatform (Web Wasm/JS + Desktop JVM) remplacera la demo Vue actuelle.

**Décisions clés validées avec l'utilisateur** :
- Cibles engine : **JVM, Wasm, JS (Node)** — pas de natif initialement
- Module **elevation** porté en Kotlin comme module Gradle séparé, dépendance d'engine
- Demo Compose Multiplatform : **Web (Wasm/JS) + Desktop (JVM)**
- Modèle Path : **DoubleArray plat + getters générés** (codegen, comme la version TS)
- Focus initial sur le **module engine** ; demo abordée brièvement en fin de plan

**Choix techniques pour interop & WebP** (cf. `kotlin-wasm-jvm-webp.md` à la racine du repo, source de vérité) :
- **KMP avec `expect`/`actual`** pour tout ce qui touche au runtime (fetch HTTP, décodage tuile, IO fichier).
- **Décodage WebP des tuiles Terrarium** :
  - JVM : **TwelveMonkeys ImageIO** (`com.twelvemonkeys.imageio:imageio-webp:3.12.0`), pur Java, SPI, déploiement trivial.
  - Wasm/browser : `kotlinx-browser:0.3` + `window.createImageBitmap(blob)` + canvas 2D + `getImageData`.
  - JS Node : `node-fetch` + `sharp` (natif npm) — best-effort, peut être limité au PNG Terrarium si `sharp` indisponible. Cible Node pour elevation marquée **optionnelle** (engine reste utilisable sur Node avec un `ElevationProvider` plug-in fourni par l'appelant).
- **Coroutines** : `kotlinx-coroutines-core:1.10.2` (commun à toutes cibles).
- **API publique vers JS/Wasm** (pour la demo et un éventuel npm package) :
  - `@JsExport` sur classes orientées consommateur + `external interface` pour les DTO littéraux JSON-like.
  - `suspend` → wrappé en `Promise<JsAny?>` via `GlobalScope.promise { ... }` côté wasmJsMain.
  - **Pas** d'exposition directe de `Path` (DoubleArray opaque) — passer par des DTO (`PointDto`, `PathDto`) sérialisables.
  - `generateTypeScriptDefinitions()` activé sur la target `wasmJs` pour produire un `.d.ts` (statut expérimental, à valider).

**Suivi** : Chaque tâche correspond à un fichier markdown dans `vcyclist/docs/tasks/NN-slug.md` contenant Goal / Inputs / Steps / Outputs / Validation / Done-when. Les tâches sont **séquentielles** (chaque tâche dépend des précédentes), conçues pour être exécutables dans des sessions Claude indépendantes en lisant uniquement le markdown de la tâche + les outputs des précédentes.

---

## Avancement

| # | Tâche | Statut | Commit | Tests ajoutés (commonTest, par target) |
|---|---|---|---|---|
| 00 | Bootstrap KMP | ✅ | `536a20c` | 1 smoke / module |
| 01 | Coordinates / Constants / Vector3D | ✅ | `f3b5897` | 32 |
| 02 | Distance / EcefConverter | ✅ | `8ffac1a` | 33 |
| 03 | DouglasPeucker 3D | ✅ | `edd17be` | 10 |
| 04 | ElevationSmoother | ✅ | `b30c6a0` | 11 |
| 05 | Tile types / ElevationFunctions / Tile (Terrarium) | ✅ | `48eb97f` | 43 |
| 06 | Tile fetcher (HTTP + WebP, multi-target) | ✅ | `3a78987` | 5 (jvmTest) |
| 07 | LRU cache + TileManager | ✅ | `6edbb5c` | 20 |
| 08 | Flux + ElevationCalculator + BatchCalculator + ElevationProvider | ✅ | `409ed40` + `78a93b9` + `325add2` | 33 |
| **— Phase 1 (module `:elevation`) terminée —** | | | | |
| 09 | Intégration HTTP réelle (tuiles mapterhorn, gated `INTEGRATION=1`) | ✅ | `ad2837b` | 6 (jvmTest, opt-in) |
| ★ | **Bonus** — WASM browser demo + `@JsExport` façade `ElevationJsApi` | ✅ | `a095ff8` | — (smoke E2E Mont Blanc ≈ 4757 m) |
| ★ | **Bonus** — Kotlin/JS browser demo (sibling of WASM, same UI) | ✅ | `3090acf` + `0bb2a34` | — (smoke E2E Chrome : sample.gpx 1053 pts / 64 tuiles, Mont Blanc = 4756.57 m, parité Wasm) |
| 10 | Engine — `PointField` + `PointFieldCategory` (36 champs / 14 catégories) | ✅ | `2d20d4a` | 16 (×3 targets = 48) |
| 11 | Engine — `GeneratedPath` codegen (sous-projet `:codegen`) + `PointFieldAccessors` | ✅ | `97ed1d9` | 9 (×3 targets = 27) |
| 12 | Engine — Path (stats + helpers + bridge `:elevation`) | ✅ | `efc9d17` | 18 (×3 targets = 54) |
| 13 | Engine — Cyclist/Bike/Course models + constants | ✅ | `a266111` | 40 (×3 targets = 120) |
| 14 | Engine — GPX parser (xmlutil + Path bridge) | ✅ | `eb58cea` | 18 (×3 targets = 54) |
| 15 | Engine — GPX writer (Path→Gpx bridge + xmlutil writer + round-trip) | ✅ | `8c409ca` | 20 (×3 targets = 60) |
| 16 | Engine — RhoProvider + WindProvider (ISA + constant/none) | ✅ | `cf6b908` | 22 (×3 targets = 66) |
| 17 | Engine — PowerProvider + 4 physics impls + AeroProvider + CoursePhysics | ✅ | `9f53121` | 38 (×3 targets = 114) |
| 18 | Engine — CyclistPowerProvider + 4 impls + MuscularPowerProvider | ✅ | `a4bb1ec` | 28 (×3 targets = 84) |
| 19 | Engine — PowerComputer + energy equation (`getNewPower`/`getDx`/`getDt`/`getTotPower`/`computeCyclistPower`/`equivalentMass`) | ✅ | `dd28f9e` | 17 + 1 régression (×3 targets = 54) |
| 20 | Engine — `MaxSpeedComputer` (cornering + braking, backward pass) | ✅ | `eea84a5` | 14 (×3 targets = 42) |
| 21 | Engine — `VirtualizeService` (time-stepping simulation) | ✅ | `1d19c04` | 12 (×3 targets = 36) |
| 22 | Engine — `PointPerSecond` (1 Hz resampler) | ✅ | `45a5eff` | 12 (×3 targets = 36) |
| 23 | Engine — `PathSimplifier` (3D Douglas-Peucker wrapper) | ✅ | `563a86d` | 13 (×3 targets = 39) |
| 24 | Engine — `ElevationStep` (fix + smooth elevation bridge to `:elevation`) | ✅ | `00c92e3` | 11 (×3 targets = 33) |
| 25 | Engine — `Enhancer` (pipeline orchestrator) | ✅ | `fad5e96` | 12 (×3 targets = 36) |
| 26 | Engine — parity fixtures (self-referential regression baseline) | ✅ | `c1b06c1` | 9 (×3 targets = 27) |
| 27 | Engine — `EngineCli` JVM smoke entry point + Gradle `run` task | ✅ | — | 5 (jvmTest only) |
| 28 | Engine — `@JsExport` façade (JS Node + Wasm browser) + `.d.ts` | ✅ | — | 3 (jsTest + wasmJsTest + jsBrowserTest, smoke) |
| **— Phase 2 (module `:engine`, tâches 10-28) terminée —** | | | | |
| **— Phase 2bis : correction des bugs résiduels —** | | | | |
| 29 | Engine — `VirtualizeService` : normaliser `time(0)=0` + propager `time(n-1)` cohérent | ✅ | `d80d56d` | 2 (×3 targets = 6) |
| 30 | Engine — `PointPerDistance` : port du resampler à distance constante (utilisé par `Enhancer` TS avant/après `fixElevation`) | ✅ | — | 14 (×4 targets = 56) |
| 31 | Engine — `Enhancer` : intégrer `PointPerDistance` + ré-activer `computeOnePointPerSecond` et `simplifyPath` par défaut une fois le bug timestamp corrigé | ✅ | `6b3cca7` | — |
| **— Phase 2bis terminée (tâches 29-31) —** | | | | |
| **— Phase 3 : support Node.js / Bun —** | | | | |
| 32 | Elevation — tile fetcher Node (runtime detection + `@jsquash/webp` WASM decoder + webpack externals) | ✅ | — | — (aucune nouvelle classe ; tests d'intégration en tâche 33) |
| 33 | Elevation + Engine — tests d'intégration Node (jsNodeTest, gated `INTEGRATION=1`) + plumberie `ElevationProvider` dans `EngineJsApi` | ✅ | — | 6 tests gated (`:elevation` : 1 TileFetcher + 4 ElevationProvider ; `:engine` : 1 EnhanceWithElevation) — skip silencieusement sans INTEGRATION, passent avec INTEGRATION=1 contre tiles.mapterhorn.com |
| **— Phase 9 : démo Vue/Vite sur Kotlin/JS —** | | | | |
| 34 | Engine — `@JsExport` façade étendue : `enhanceWithCourse` + `getField` + `fieldDefinitions` + DTO Cyclist/Bike/Wind/Power | ✅ | `c3f330d` | 4 (jsBrowserTest) |
| 35 | Demo — bootstrap Vue/Vite + alias `@glandais/vcyclist-engine` via `file:` + shell vide | ☐ | | |
| 36 | Demo — intégration moteur : `useGPXDemo` + `types` + persistance config | ☐ | | |
| 37 | Demo — UI complète (16 composants Vue + Chart.js + Leaflet + 6 tabs + FieldsSidebar) | ☐ | | |
| 38 | Demo — intégration Gradle (`:demo:assemble`) + GPX samples + README | ☐ | | |
| 39 | Demo — déploiement GitHub Pages (optionnel, stretch) | ☐ | | |

**Cumul `:elevation` après Phase 1 + extras** : 20 classes de tests, **193 tests** (commonTest 182 + jvmTest 11 dont 6 opt-in) × 3 targets en mode standard = **557 exécutions** vertes (offline).

**Cumul `:engine` après tâches 10-13** : 8 classes de tests, **83 tests commonTest** (16 PointField + 9 GeneratedPath + 18 Path + 16 EngineConstants + 9 Cyclist + 9 Bike + 3 Course + 3 EnhanceOptions) × 3 targets = **249 exécutions** vertes.

**Cumul `:engine` après tâche 14** : 9 classes de tests, **101 tests commonTest** (83 + 18 GpxParser) × 3 targets = **303 exécutions** vertes.

**Cumul `:engine` après tâche 15** : 10 classes de tests, **121 tests commonTest** (101 + 20 GpxWriter) × 3 targets = **363 exécutions** vertes.

**Cumul `:engine` après tâche 16** : 12 classes de tests, **143 tests commonTest** (121 + 15 RhoProvider + 7 WindProvider) × 3 targets = **429 exécutions** vertes.

**Cumul `:engine` après tâche 17** : 17 classes de tests, **181 tests commonTest** (143 + 6 WheelBearings + 7 RollingResistance + 7 Grav + 14 AeroPower + 4 CoursePhysics) × 3 targets = **543 exécutions** vertes.

**Cumul `:engine` après tâche 18** : 22 classes de tests, **209 tests commonTest** (181 + 6 PowerProviderConstant + 6 PowerProviderConstantWithTiring + 4 PowerProviderFromData + 5 MuscularPowerProvider + 7 CyclistPowerProviderBase) × 3 targets = **627 exécutions** vertes.

**Cumul `:engine` après tâche 19** : 23 classes de tests, **227 tests commonTest** (209 + 18 PowerComputer) × 3 targets = **681 exécutions** vertes.

**Cumul `:engine` après tâche 20** : 24 classes de tests, **241 tests commonTest** (227 + 14 MaxSpeedComputer) × 3 targets = **723 exécutions** vertes.

**Cumul `:engine` après tâche 21** : 25 classes de tests, **253 tests commonTest** (241 + 12 VirtualizeService) × 3 targets = **759 exécutions** vertes.

**Cumul `:engine` après tâche 22** : 26 classes de tests, **265 tests commonTest** (253 + 12 PointPerSecond) × 3 targets = **795 exécutions** vertes.

**Cumul `:engine` après tâche 23** : 27 classes de tests, **278 tests commonTest** (265 + 13 PathSimplifier) × 3 targets = **834 exécutions** vertes.

**Cumul `:engine` après tâche 24** : 28 classes de tests, **289 tests commonTest** (278 + 11 ElevationStep) × 3 targets = **867 exécutions** vertes.

**Cumul `:engine` après tâche 25** : 29 classes de tests, **301 tests commonTest** (289 + 12 Enhancer) × 3 targets = **903 exécutions** vertes.

**Cumul `:engine` après tâche 26** : 30 classes de tests, **310 tests commonTest** (301 + 9 EnhancerParity) × 3 targets = **930 exécutions** vertes.

**Cumul `:engine` après tâche 27** : 30 classes de tests commonTest = **310 tests × 3 targets = 930 exécutions** vertes, **plus** 1 classe `EngineCliSmokeTest` en `jvmTest` (5 tests, JVM-only). Total `:engine:allTests` = **935 exécutions** vertes.

**Cumul `:engine` après tâche 28** : 30 classes de tests commonTest = **310 tests × 4 targets** (JVM + JS Node + JS Browser + Wasm Browser) = **1240 exécutions** + 1 classe `EngineCliSmokeTest` jvmTest-only (5 tests) + 1 classe `EngineJsApiTest` par target JS/Wasm/JsBrowser (3 tests × 3 = 9). Total `:engine:allTests` = **1254 exécutions** vertes. Façade `@JsExport` exposée sur 2 surfaces : `engine/src/wasmJsMain/.../EngineJsApi.kt` (`JsReference<Path>` handle pattern + DTOs `external interface : JsAny` + `Promise<JsReference<Path>>`) et `engine/src/jsMain/.../EngineJsApi.kt` (classes directes + DTOs `external interface` + `Promise<Path>`). `.d.ts`/`.d.mts` générés sous `build/compileSync/{js,wasmJs}/main/productionExecutable/kotlin/vcyclist-engine.{d.ts,d.mts}` couvrant `parseGpx`, `enhance`, `writeGpx`, `pointAt`, `pathSize`, `pathTotalDistance`, `pathDurationMs`, `pathElevationGain`, `pathElevationLoss`, `PointDto`, `EnhanceOptionsDto`.

**Cumul `:engine` après tâche 29** : 31 classes de tests commonTest = **312 tests × 4 targets** (310 + 2 VirtualizeServiceTimestamp) = **1248 exécutions** + 1 classe `EngineCliSmokeTest` jvmTest-only (5 tests) + 1 classe `FullPipelineSmokeTest` jvmTest-only (1 test, sibling-GPX-aware) + 1 classe `EngineJsApiTest` par target JS/Wasm/JsBrowser (3 tests × 3 = 9). Total `:engine:allTests` = **1263 exécutions** vertes. Phase 2bis bug #1 corrigé : `VirtualizeService` simule désormais tous les points (boucle `i < n` au lieu de `i < n - 1`), donc `time(n-1)` ne fuit plus l'epoch source — le pipeline complet (1 Hz resample + Douglas-Peucker simplify) tourne sans OOM sur sample.gpx (3569 → 1060 pts, 128.5 km, 3 h 39 simulés, < 200 ms wall). `EngineCli` revient aux options par défaut (sauf `fixElevation`).

**Cumul `:engine` après tâche 30** : 32 classes de tests commonTest = **326 tests × 4 targets** (312 + 14 PointPerDistance) = **1304 exécutions** + 1 classe `EngineCliSmokeTest` jvmTest-only (5 tests) + 1 classe `FullPipelineSmokeTest` jvmTest-only (1 test) + 1 classe `EngineJsApiTest` par target JS/Wasm/JsBrowser (3 tests × 3 = 9). Total `:engine:allTests` = **1319 exécutions** vertes. Phase 2bis bug #2 corrigé : `PointPerDistance` porté sous `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointPerDistance.kt` (object stateless, `compute` + `computeOnePointPerDistance` + plan/materialize 2-pass). Reste à brancher dans `Enhancer` (tâche 31).

**Cumul `:engine` après tâche 31** : 32 classes de tests commonTest = **326 tests × 4 targets** (inchangé, pas de nouvelle classe — tâche 31 ajuste 4 tests existants dans `EnhancerTest` et `EnhancerParityTest` au pipeline densifié) = **1304 exécutions** + 1 classe `EngineCliSmokeTest` jvmTest-only (5 tests) + 1 classe `FullPipelineSmokeTest` jvmTest-only (1 test, désormais avec budget wall-clock < 10 s) + 1 classe `EngineJsApiTest` par target JS/Wasm/JsBrowser (3 tests × 3 = 9). Total `:engine:allTests` = **1319 exécutions** vertes. Phase 2bis bug #3 corrigé : `Enhancer` exécute désormais `PointPerDistance.compute(path, -1.0, 30.0)` avant `fixElevation` puis `PointPerDistance.compute(path, 1.0, 2.0)` avant `smoothElevation`, reproduisant fidèlement le pipeline TS (`virtual-cyclist/src/enhancer/Enhancer.ts` lignes 64-92). Smoke E2E sample.gpx : 3569 → 1018 pts, 128.6 km, 19 157 s simulés, 164 KiB GPX, ~1.7 s wall (très en dessous du budget 10 s). Baseline `ParityFixtures` régénérée : SAMPLE durationMs 52 000 → 49 000 ms, SAMPLE gain 0.268 → 0.219 m, GARMIN loss -0.0086 → -0.0048 m.

**Cumul `:elevation` après tâche 32** : nombre de classes/tests inchangé (aucune nouvelle classe ajoutée — la tâche 33 apportera les tests d'intégration Node). `TileFetcher.js.kt` ajoute une détection runtime `isNode` + une branche `decodeNode` qui appelle `globalThis.fetch` (natif Node 18+ et Bun) puis décode le WebP via `@jsquash/webp` (pur WASM, ~50 KiB, chargé en lazy via `eval('require')` pour ne pas perturber webpack côté navigateur). La branche `decodeBrowser` est byte-pour-byte identique à l'ancienne implémentation. `webpack.config.d/externals.js` marque `@jsquash/webp` en commonjs external — le bundle navigateur `elevation.js` reste à 160 KiB et ne contient que la string littérale `@jsquash/webp/decode.js` à l'intérieur de l'`eval('require')(...)` (le module spec, pas le code du package). `:elevation:allTests` reste à 557 exécutions vertes (JVM + JS Node + JS Browser + Wasm Browser). `:engine:allTests` reste à 1319 exécutions vertes (régression OK). Cible Phase 3 atteignable : un consommateur Node ou Bun qui importe `vcyclist-elevation.mjs` et appelle `getElevationsAlong` exerce désormais le vrai pipeline `globalThis.fetch` → `@jsquash/webp` → `ElevationCalculator`. La tâche 33 ajoute les tests d'intégration qui valident le bout-en-bout avec `INTEGRATION=1`.

**Cumul `:elevation` + `:engine` après tâche 33** : +3 classes de tests `jsTest`-only (ne se déclinent pas en commonTest car spécifiques au runtime Node) gated par `INTEGRATION=1` : `TileFetcherNodeIntegrationTest` (1 test, fetch + décodage WebP de la tuile Mont Blanc zoom 12), `ElevationProviderNodeIntegrationTest` (4 tests, miroir Node du `ElevationProviderIntegrationTest` JVM : Mont Blanc 4805±50 m, Dead Sea −430±50 m, Death Valley −85±50 m, `getElevationsAlong` densifié autour du Mont Blanc), `EnhanceWithElevationNodeTest` (1 test, pipeline complet `parseGpx` → `enhance(fixElevation=true)` → altitudes Alpines plausibles). Sans `INTEGRATION=1`, les 6 tests skippent silencieusement (mêmes nombres d'exécutions qu'avant : `:elevation:allTests` 557, `:engine:allTests` 1319). Avec `INTEGRATION=1`, +6 exécutions (uniquement `:elevation:jsNodeTest` et `:engine:jsNodeTest`, soit 1 cible). La tâche 33 a également corrigé trois bugs résiduels : (1) chemin module `@jsquash/webp/decode/index.js` → `@jsquash/webp/decode.js`, (2) init Emscripten manuelle via `fs.readFileSync` + `WebAssembly.compile` + `decodeMod.init(wasmModule)` (le auto-init tente un fetch `file://` non supporté par Node), (3) `EngineJsApi.enhance` (JS + Wasm) auto-instancie un `ElevationProvider()` par défaut quand `opts.fixElevation` est true — auparavant hardcodé à `null`, ce qui faisait silencieusement échouer le fix elevation côté JS/Wasm. Build env propage `INTEGRATION` aux KotlinJsTest tasks ; Mocha timeout bumpé à 30 s pour absorber le premier compile WASM à froid (~500 ms) + fetch tuile (~200 ms) + décodage (~50 ms).

**Phase 3 — support Node.js / Bun terminé** : le module `:elevation` (et donc `:engine` qui en dépend) fonctionne désormais en bout-en-bout sur Node ≥ 18 et Bun, **avec** elevation activée. Un consommateur Node qui importe les bundles peut désormais :

```javascript
import { parseGpx, enhance, writeGpx } from './vcyclist-engine.mjs'
const path = parseGpx(gpxXml)
const out = await enhance(path, { fixElevation: true })   // tire les tuiles Terrarium via @jsquash/webp
const xml = writeGpx(out)
```

et obtenir un GPX corrigé du DEM. Le navigateur (Wasm + Kotlin/JS) reste fonctionnel à l'identique (la branche `decodeBrowser` est inchangée). Le bundle browser ne pulle pas `@jsquash/webp` (webpack externals + `eval('require')`). Pour valider manuellement sous Bun (hors CI) : compiler le bundle Node puis `INTEGRATION=1 bun run engine/build/.../productionExecutable/.../vcyclist-engine.mjs`.

**— Phase 2 (module `:engine`, tâches 10-28) terminée —** Le moteur Kotlin Multiplatform `:engine` est désormais complet : modèle de données (PointField + GeneratedPath + Path), modèles de domaine (Cyclist/Bike/Course/CoursePhysics), I/O GPX (parser + writer), physique (4 PowerProviders + AeroProvider + RhoProvider + WindProvider + PowerComputer + MaxSpeedComputer), simulation (VirtualizeService + PointPerSecond), post-traitement (PathSimplifier + ElevationStep), orchestration (Enhancer), CLI JVM smoke (EngineCli), et façades `@JsExport` pour JS Node + Wasm browser + JS browser. Prêt pour Phase 3 (demo Compose Multiplatform) ou intégration npm.

**Phase 2bis terminée (tâches 29-31)** : les 3 bugs identifiés à la fin de Phase 2 sont corrigés.

- **Bug #1 — `VirtualizeService` timestamps absolus** ✅ corrigé en tâche 29 (`d80d56d`) : la simulation s'arrêtait à `i < n - 1` et copiait le dernier point verbatim depuis l'input, laissant `time(n - 1)` à l'epoch source (~1.7e12 ms en 2024) tandis que `time(n - 2)` venait d'être écrasé à une valeur simulée proche de 0. Effet visible : `PointPerSecond` recevait `time(n-1) - time(0) ≈ 1.7e12 ms` → tentait d'allouer ~1.7 milliards de points → OOM. Correction : étendre la boucle à `i < n` et supprimer le `copyAllFields` post-boucle ; étendre `computeCyclistPower` à `out.indices`. Le pipeline complet (1 Hz resample + Douglas-Peucker simplify) tourne désormais sans OOM sur la fixture `sample.gpx` (3569 → 1060 pts en < 200 ms). `EngineCli` repasse aux options par défaut (sauf `fixElevation`).
- **Bug #2 — `PointPerDistance` non porté** ✅ corrigé en tâche 30 (`b9d9360`) : le `Enhancer.ts` TS appelle `PointPerDistance.compute(path, ±step, fields)` avant et après `fixElevation` pour densifier la trace à pas constant. Sans cette étape, les paths longs avec des points GPS très espacés sont sous-densifiés, ce qui dégrade la précision de `fixElevation` (moins de mesures DEM) et de `MaxSpeedComputer` (radii calculés sur des fenêtres moins représentatives). Correction : `PointPerDistance` porté en `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointPerDistance.kt` (object stateless, plan/materialize 2-pass, copyFields/interpolateFields privés, NaN-strict comme `PointPerSecond`). 14 tests `PointPerDistanceTest`. Restait à brancher dans `Enhancer` (tâche 31).
- **Bug #3 — `Enhancer` n'invoquait pas `PointPerDistance`** ✅ corrigé en tâche 31 (`6b3cca7`) : `Enhancer.enhanceCourse` exécute désormais `PointPerDistance.compute(path, -1.0, 30.0)` avant `fixElevation` (pré-densification à 30 m max pour donner à `fixElevation` une grille fine de lookup DEM) puis `PointPerDistance.compute(path, 1.0, 2.0)` après `fixElevation` mais avant `smoothElevation` (raffinage à 1-2 m pour les passes physiques aval). Les deux appels sont toujours exécutés (non-toggleable), conformément au pipeline TS. Smoke E2E sample.gpx : 3569 → 1018 pts, 128.6 km, 19 157 s simulés, 164 KiB GPX, ~1.7 s wall (très en dessous du budget 10 s ajouté à `FullPipelineSmokeTest`). 4 tests `EnhancerTest` ajustés (PointPerDistance densifie même quand les options sont off) et 4 valeurs `ParityFixtures.SAMPLE`/`GARMIN` régénérées (cf. `docs/parity.md`).
- **Parité TS** (auto-baseline) : avec ces 3 corrections, le pipeline Kotlin reproduit fidèlement le pipeline TS (séquence `PointPerDistance` → `fixElevation` → `PointPerDistance` → `smoothElevation` → `MaxSpeedComputer` → `VirtualizeService` → `PointPerSecond` → `PathSimplifier`). La tâche 26 a posé un baseline self-referential ; il est désormais possible d'exécuter le TS hors-CI et de comparer numériquement les sorties (Phase 3 envisageable).

**Critère Phase 1** : `./gradlew :elevation:allTests` vert sur JVM + JS Node + Wasm browser. ✅ Module utilisable comme dépendance via `api(project(":elevation"))` à activer en Phase 2.

**Bonus hors plan** : le commit `a095ff8` ajoute un démonstrateur browser Kotlin/Wasm (Leaflet + Chart.js + GPX upload) et la façade `@JsExport` `ElevationJsApi` (top-level functions + `JsReference<ElevationProvider>` handle pattern + DTOs `external interface : JsAny` + `Promise<…>` via `GlobalScope.promise`). Cela **valide les patterns** documentés dans `kotlin-wasm-jvm-webp.md` et **réduit le scope de la tâche 28** (la recette est désormais éprouvée pour `:engine`). E2E vérifié contre `tiles.mapterhorn.com` (Mont Blanc ≈ 4757 m). À noter : pas de tests unitaires pour cette façade, et `GlobalScope.promise` requiert `@OptIn(DelicateCoroutinesApi)` — à traiter si publication npm.

**Bonus hors plan (suite)** : un second démonstrateur browser, en **Kotlin/JS pur** (sans Wasm), est ajouté sous `elevation/src/jsMain/` (cf. `docs/tasks/bonus-elevation-js-demo.md`). Il partage l'UI complète avec la démo Wasm (`demo.css`/`demo.js`/`sample.gpx` strictement identiques) et expose une façade `@JsExport` parallèle adaptée aux conventions Kotlin/JS (pas de `JsAny`/`JsReference<T>`/`JsArray<T>` : DTOs `external interface` simples, `ElevationProvider` passé en référence directe, `Array<T>` natif). Le pipeline fetch/decode WebP utilise le même `createImageBitmap` + canvas 2D que la version Wasm. Bundle de production : ~140 KiB `elevation.js` monolithique (vs ~13 KiB loader + ~135 KiB `.wasm` côté Wasm). Tasks Gradle : `:elevation:jsBrowserDevelopmentRun` (dev server) et `:elevation:jsBrowserDistribution` (dist statique sous `build/dist/js/productionExecutable/`). `:elevation:jsBrowserTest` (Karma + Chrome headless) ajouté en complément de `:jsNodeTest`. Pas de smoke E2E HTTP automatisé — la validation manuelle se fait via `sample.gpx` chargé dans le navigateur (altitude max attendue ≈ 4757 m, même chemin algorithmique que la version Wasm).

---

## Structure cible

```
/home/glandais/code/perso/vcyclist-all/vcyclist/
├── settings.gradle.kts                # multi-module
├── build.gradle.kts                   # root + plugins versions
├── gradle.properties
├── gradle/wrapper/...
├── docs/
│   ├── PLAN.md                        # vue d'ensemble (issue de ce plan)
│   ├── ARCHITECTURE.md                # high-level (rempli au fur et à mesure)
│   └── tasks/                         # un .md par tâche, état progress
│       ├── 00-bootstrap.md
│       ├── 01-elevation-coords-vector.md
│       └── …
├── elevation/                         # module Gradle KMP
│   ├── build.gradle.kts
│   └── src/{commonMain,jvmMain,jsMain,wasmJsMain,commonTest,jvmTest}/...
├── engine/                            # module Gradle KMP (dépend de elevation)
│   ├── build.gradle.kts
│   └── src/{commonMain,jvmMain,jsMain,wasmJsMain,commonTest,jvmTest}/...
└── demo/                              # module Gradle Compose Multiplatform
    ├── build.gradle.kts
    └── src/{commonMain,desktopMain,wasmJsMain}/...
```

Tests : chaque algorithme a son test unitaire en `commonTest` ; intégrations qui dépendent de l'environnement (HTTP, fichiers) sont en `jvmTest` (et `jsTest`/`wasmJsTest` quand pertinent). Sample data partagée via `commonTest/resources` ou ressource embarquée.

---

## Convention de tâche (modèle)

Chaque `docs/tasks/NN-slug.md` suit ce format :

```markdown
# NN — <Titre>

## Goal
<1–3 phrases : ce que produit la tâche>

## Depends on
- NN-1, NN-2 (tâches préalables)

## Inputs
- fichiers/refs spec dans repos voisins (chemins absolus)
- décisions de design héritées

## Steps
1. …
2. …

## Outputs (fichiers attendus)
- elevation/src/commonMain/kotlin/.../X.kt
- elevation/src/commonTest/kotlin/.../XTest.kt

## Validation
- Commande : `./gradlew :elevation:allTests` (ou `:engine:jvmTest`, etc.)
- Critères : <ce qui doit passer/montrer>
- Numerical parity check (si applicable) : tolérance 1e-9 vs sortie TS

## Done when
- [ ] Tests verts sur cibles JVM/JS/Wasm activées
- [ ] Coverage du nouveau code ≥ 80 %
- [ ] Pas de warning compilateur
- [ ] Markdown coché

## Notes
<décisions/non-évidences à propager>
```

---

## Phase 0 — Bootstrap

### 00-bootstrap.md
- Init `vcyclist/` avec Gradle 8.x + Kotlin 2.x.
- `settings.gradle.kts` : modules `:elevation`, `:engine` (demo plus tard).
- Plugin `org.jetbrains.kotlin.multiplatform` configuré pour `jvm()`, `js(IR) { nodejs() }`, `wasmJs { browser(); binaries.executable(); generateTypeScriptDefinitions() }`.
- Dépendances communes par défaut : `kotlinx-coroutines-core:1.10.2`, `kotlin-test`.
- Dépendances par target :
  - `wasmJsMain` : `kotlinx-browser:0.3`
  - `jvmMain` : `imageio-webp:3.12.0` (TwelveMonkeys) — seulement dans `:elevation`
  - `jsMain` : `node-fetch` (via npm) ; `sharp` optionnel
- Targets de test : Kotlin Test (`kotlin("test")`) + assertions communes.
- Lint/format : `ktlint` (recommandation).
- CI minimal : GitHub Actions ou script `make check` qui lance build + tests sur toutes cibles.
- `docs/tasks/` créé, `PLAN.md` copié depuis ce plan, `kotlin-wasm-jvm-webp.md` recopié/lié dans `docs/`.
- **Validation** : `./gradlew build` passe (modules vides) ; `:elevation:allTests` et `:engine:allTests` retournent UP-TO-DATE.

---

## Phase 1 — Module `elevation` (port de la lib TS)

Référence canonique : `/home/glandais/code/perso/vcyclist-all/elevation/src/`. Pour chaque algorithme, le test Kotlin doit reproduire les cas du test TS correspondant (`/elevation/test/...`) avec les mêmes valeurs et tolérances.

### 01-elevation-coords-vector.md
- Types : `Coordinates(latitude, longitude, elevation: Double?)`, `CoordinatesElevation(latitude, longitude, elevation: Double)`.
- Constantes : `EARTH_RADIUS_M = 6_371_000.0`, `WGS84_*`, `WEB_MERCATOR_MAX_LAT`.
- `Vector3D` : data class + ops (add, sub, dot, cross, magnitude, normalize, distanceToSegment).
- Tests : ports directs de `test/utils/Vector3D.test.ts`.
- **Outputs** : `elevation/src/commonMain/kotlin/io/github/glandais/elevation/{Coordinates.kt, Constants.kt, Vector3D.kt}` + tests.

### 02-elevation-distance-ecef.md
- `Distance` : `haversineMeters(a, b)`, `euclidean3DMeters(a, b, zExaggeration)`, `pointToSegmentDistance3D(...)`.
- `EcefConverter` : WGS84 → ECEF avec `zExaggeration` (paramètre Douglas-Peucker).
- Tests : ports de `test/utils/Distance.test.ts` + `EcefConverter.test.ts` (paris/londres ≈ 343 km, etc.).
- **Validation** : parité numérique 1e-9 vs TS.

### 03-elevation-douglas-peucker.md
- `DouglasPeucker` : simplification récursive 3D en ECEF, paramètres `tolerance`, `zExaggeration`.
- Préserve toujours premier/dernier point.
- Tests : ports de `test/utils/DouglasPeucker.test.ts` + `test/filtering.test.ts` (taux de réduction, retention endpoints).

### 04-elevation-smoother.md
- `ElevationSmoother` : noyau triangulaire avec fenêtre par distance ; O(n) via distances cumulées.
- Tests : ports `test/utils/ElevationSmoother.test.ts` (variance reduction, no-op si `enabled=false`).

### 05-elevation-tile-types-decoding.md
- Types : `TileCoordinates`, `TileCoordinatesFloat`, `Pixel`, `RGBColor`, `RawTile` (data class avec `equals`/`hashCode` corrects pour `ByteArray`).
- `ElevationFunctions` (object) : conversion lat/lon ↔ tile/pixel (Web Mercator), validators (`isValidLatitude/Longitude/ZoomLevel`), `normalizePixel` avec clamp tuile.
- `Tile` : classe **concrète** (vs `abstract` TS — normalisation précoce en `ByteArray` côté KMP), décodage Terrarium pixel-par-pixel avec cache `DoubleArray` (sentinel `NaN`).
- Ajout `EarthConstants.WEB_MERCATOR_MAX_LAT_TEST = 85.0511` (borne de validation TS) à côté de `WEB_MERCATOR_MAX_LAT` (borne géodésique précise).
- **Re-scope** : `ElevationCalculator` (interpolation bilinéaire) est déplacé en tâche 08 car il nécessite `TileManager` (tâche 07).
- Tests : ports `test/calculator/ElevationFunctions.test.ts` (9 cas `normalizePixel`) + tests neufs pour projections, validators, décodage Terrarium, cache.

### 06-elevation-tile-fetcher.md
- `commonMain` : `data class RawTile(val width: Int, val height: Int, val rgba: ByteArray)` + `expect suspend fun fetchAndDecodeTile(url: String): RawTile`.
- `actual` **JVM** : `java.net.http.HttpClient` (KMP-friendly, pas de dépendance Ktor obligatoire) + `ImageIO.read(...)` via TwelveMonkeys (SPI auto-enregistré). Décode ARGB → conversion vers `ByteArray` RGBA (pattern exact du `kotlin-wasm-jvm-webp.md` §6 jvmMain).
- `actual` **Wasm/browser** : `window.fetch(url).await<Response>()` → `.blob().await<Blob>()` → `window.createImageBitmap(blob).await<ImageBitmap>()` → canvas 2D + `getImageData()` → `data.data.toByteArray()` (pattern §5 cas 2 et §6 wasmJsMain).
- `actual` **JS (Node)** : `node-fetch` + `sharp` (`sharp(buffer).raw().toBuffer({ resolveWithObject: true })`). Si `sharp` indisponible (build/CI offline), fallback : forcer URL Terrarium PNG et décoder via `pngjs`. Cette target est marquée **optionnelle** ; commenter le bloc `jsMain` si non utilisé.
- Tests :
  - `commonTest` : mocker `fetchAndDecodeTile` via `expect/actual` test fixture qui lit une tuile depuis `commonTest/resources/sample-tile.webp`.
  - `jvmTest` : test réel d'intégration avec un serveur Ktor embarqué (`io.ktor:ktor-server-test-host`, scope `testImplementation`) servant une tuile fixture, vérification que les bytes décodés correspondent à la fixture.
  - `wasmJsTest` : exécution dans `browser { testTask {} }`, mock URL avec un blob créé en JS.

### 07-elevation-tile-cache.md
- `LruCache<K, V>` thread-safe (KMP, utilise `kotlinx.atomicfu` ou simple synchronized JVM-only en attendant — choix : LinkedHashMap+lock simple côté JVM, `kotlinx-collections-immutable` côté commun).
- `TileManager` : pool réentrant, déduplication des fetches concurrents (`Mutex` par clé de tuile).
- Tests : éviction LRU, deduplication concurrente.

### 08-elevation-provider-batch.md
- `ElevationCalculator` (déplacé depuis tâche 05) : interpolation bilinéaire à partir de 4 pixels voisins via `TileManager`. Suspend.
- `ElevationProviderConfig` (zoom, cacheSize, urlTemplate, tileSize, attribution).
- `ElevationProvider` (API publique) : `getElevation(lat, lon)`, `setElevations(coords)`, `getElevationsAlong(path, options)`.
- `BatchCalculator` : pipeline `getElevationsAlong` (génération de waypoints intermédiaires via Haversine, lissage, filtrage Douglas-Peucker).
- `Reactive` : limitation de concurrence (équivalent de `Reactive.ts`).
- Tests : ports `test/calculator/ElevationCalculator.test.ts` (avec `TileManager` mocké), principaux de `test/ElevationProvider.test.ts`, `BatchCalculator.test.ts`.

### 09-elevation-integration.md
- Test d'intégration `jvmTest` qui fetche réellement quelques tuiles `tiles.mapterhorn.com` (équivalent `ElevationProvider.integration.test.ts`).
- Skippable via env var `INTEGRATION=1` pour CI offline.
- Sanity check : altitude Mont Blanc ≈ 4800 m ± 50 m.

**Critère de fin de Phase 1** : `./gradlew :elevation:allTests` vert sur `jvm`, `js`, `wasmJs`. Coverage ≥ 80 %. Le module est utilisable comme dépendance.

---

## Phase 2 — Module `engine` : modèle de données

Référence : `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/path/` (fieldDefinitions, GeneratedPath, Path).

### 10-engine-field-definitions.md
- Définir les **36 champs** du Path (single source of truth) en Kotlin : `enum class PointField(val prop: String, val unit: String, val shortDescription: String, val category: PointFieldCategory, val notSelectable: Boolean = false, val anglesInRadians: Boolean = false)`. `index = ordinal`.
- **14 catégories** (`enum class PointFieldCategory`) : coordinates, temporal, angles, elevation, grade, radius, aero_coef, cyclist_wind, power_physics, power_cyclist, power_post, speed, environmental, physiological.
- Cross-check exhaustif vs `virtual-cyclist/src/types/path/fieldDefinitions.ts` (qui contient bien 36 champs malgré la mention "37" dans la doc TS).
- **Output** : `engine/src/commonMain/kotlin/.../path/{PointField.kt, PointFieldCategory.kt}`.
- Test : `PointField.entries.size == 36`, `PointField.COUNT == 36`, ordinaux uniques, `byProp` round-trip, comptage par catégorie.

### 11-engine-codegen-strategy.md
- **Décision** : pas de KSP/codegen-plugin pour démarrer ; on génère **manuellement à partir de `PointField`** une classe `GeneratedPath` qui expose getters/setters typés.
- Structure : `class GeneratedPath(val size: Int) { protected val data: DoubleArray = DoubleArray(size * 37); var <field>(i: Int): Double get() = data[i * 37 + index] set(v) { ... } }`.
- Si `PointField` change → relancer un script Kotlin (`scripts/generate-path.kts` ou Gradle task) qui régénère `GeneratedPath.kt`.
- Alternative laissée ouverte : KSP plus tard si la liste évolue beaucoup.
- **Outputs** : `engine/src/commonMain/kotlin/.../path/GeneratedPath.kt` + `scripts/generate-path.main.kts`.
- Tests : round-trip set/get sur chaque champ.

### 12-engine-path.md
- `class Path(size) : GeneratedPath(size)` ; ajoute stats calculées paresseusement (`totalDistance`, `elevationGain`, `elevationLoss`, `duration`, etc.).
- API d'itération : `forEachPoint { i -> ... }`, `subPath(i, j)`, `copy()`, `extend(additionalSize)`.
- Tests : construction d'un path court à la main, vérif stats.

---

## Phase 3 — Module `engine` : modèles de domaine

Référence : `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/models/`.

### 13-engine-cyclist-bike.md
- `data class Cyclist(massKg, powerW, cd, frontalAreaM2, maxLeanAngleDeg, maxSpeedKmH, maxBrakeG, ...)` avec defaults (80 kg, 280 W, 0.7, 0.5 m², 35°, 100 km/h).
- `data class Bike(crr=0.004, inertiaFront=0.05, inertiaRear=0.07, wheelRadiusM=0.7, efficiency=0.976)`.
- `data class Course(path: Path, cyclist: Cyclist, bike: Bike)`.
- `class CoursePhysics(course, rhoProvider, aeroProvider, windProvider, powerProvider)`.
- Tests : valeurs par défaut, conversions (km/h ↔ m/s, deg ↔ rad).

---

## Phase 4 — Module `engine` : I/O GPX

### 14-engine-gpx-parser.md
- Parser GPX **KMP-pur** : pas de dépendance JVM-only.
- Option A (recommandée) : `kotlinx.serialization` n'a pas de XML stable multi-plateforme ; utiliser **`xmlutil`** (https://github.com/pdvrieze/xmlutil) qui supporte JVM/JS/Wasm.
- Mapper waypoints + extensions Garmin (power, cadence, hr, atemp).
- **Output** : `engine/src/commonMain/kotlin/.../gpx/GpxParser.kt`.
- Tests : fixtures dans `engine/src/commonTest/resources/` — réutiliser `virtual-cyclist/test/fixtures/*.gpx` (Garmin, Strava, Amazfit).

### 15-engine-gpx-writer.md
- Écriture symétrique avec `xmlutil`.
- Round-trip test : parse → write → parse, comparaison structurelle.

---

## Phase 5 — Module `engine` : physique

Référence cœur : `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/` + `gpx2web/.../virtual/power/`. Utiliser `HOW_IT_WORKS.md` du projet TS comme spec numérique.

### 16-engine-rho-wind-providers.md
- `interface RhoProvider { fun rho(altitudeM: Double, tempC: Double): Double }`.
- `IsaRhoProvider` : modèle ISA barométrique.
- `interface WindProvider { fun wind(point: Path, i: Int): WindVector }` (constant, none, fromData).
- Tests : ISA à 0 m / 1500 m, valeurs connues.

### 17-engine-power-providers.md
- `interface PowerProvider { fun powerAt(course: CoursePhysics, i: Int, speed: Double): Double }`.
- 4 implémentations physiques :
  - `WheelBearingsPowerProvider` : `P = -speed × (91 + 8.7 × speed) / 1000`.
  - `RollingResistancePowerProvider` : `-cos(atan(grade)) × m × g × speed × crr`.
  - `GravPowerProvider` : `-m × g × speed × sin(atan(grade))`.
  - `AeroPowerProvider` (Isvan model) : `-CdA × ρ/2 × v_apparent³` avec contribution vent (formule Sheldon Brown).
- Tests : valeurs numériques précises, parité TS (tolérance 1e-6 W).

### 18-engine-cyclist-power-providers.md
- 4 sources de puissance cycliste :
  - `ConstantPowerProvider`
  - `ConstantWithTiringPowerProvider`
  - `FromDataPowerProvider` (lit le champ power du Path)
  - `MuscularPowerProvider` (harmoniques optionnelles)
- Tests : profils typiques.

### 19-engine-power-computer.md
- Bilan énergétique + équation cinétique :
  ```
  M_eq = m + (I_front + I_rear) / r²
  v_new = sqrt(v_old² + 2 × P_net × Δt / M_eq)
  ```
- `PowerComputer` agrège tous les `PowerProvider`.
- Tests : conservation d'énergie sur plat sans vent, descente libre.

### 20-engine-max-speed-computer.md
- `MaxSpeedComputer` 2 passes :
  - Forward : pour chaque triplet (i-1, i, i+1), centre du cercle circonscrit → rayon → `v_max = √(g × radius × tan(maxLean))`. Sécurité +2 m sur radius.
  - Backward : freinage cinématique `v0² = vf² + 2ad` avec `a = maxBrakeG × g`.
- Tests : virage serré (R=30 m, lean 35° → ≈ 14.4 m/s ≈ 52 km/h), freinage 30→0 km/h sur 7 m.
- Parité numérique vs TS sur sample.gpx.

### 21-engine-virtualize-service.md
- Boucle 1 Hz : à chaque pas, calcule `dt` par binary search sur `dists[]` pour avancer d'1 s à la `v` courante, applique `MaxSpeedComputer.cap(v)`, met à jour position/temps/champs.
- Snap aux waypoints sources pour cohérence GPS.
- Tests : sample.gpx → vérifier durée, distance, distribution des vitesses.

---

## Phase 6 — Module `engine` : post-traitement

### 22-engine-point-per-second.md
- Resampler uniforme à 1 Hz (interpolation linéaire entre samples virtualisés).
- Tests : nombre de points = floor(durée), interpolation cohérente.

### 23-engine-douglas-peucker-3d.md
- Réutilise `DouglasPeucker` du module `elevation` (dépendance déjà déclarée). Fournit wrapper qui opère sur `Path` (transcrit Path → list de coords ECEF avec exagération).
- Tests : taux de réduction sur sample.

---

## Phase 7 — Module `engine` : pipeline orchestrateur

### 24-engine-elevation-fix-step.md
- Step 1 du pipeline : appelle `ElevationProvider.getElevationsAlong(path, options)` pour corriger l'altitude.
- Fallback : si `ElevationProvider` non fourni, utilise altitude GPX brute + smoother local (MA 150 pts, comme TS).
- Tests : avec mock provider, sans provider.

### 25-engine-enhancer.md
- `Enhancer` orchestre les 5 étapes :
  1. `fixElevation`
  2. `computeMaxSpeeds`
  3. `virtualize`
  4. `resample1Hz`
  5. `simplify` (Douglas-Peucker 3D)
- API : `enhance(coursePhysics, options): Path` + helper `enhanceCourseDefault(path)`.
- Tests : pipeline complet sur `sample.gpx` ; comparaison structurelle (durée, distance, profil de vitesse) avec sortie de référence générée depuis l'engine TS.

---

## Phase 8 — Validation end-to-end & parité

### 26-engine-parity-fixtures.md
- Générer une fois (manuellement, dans un sous-dossier `fixtures/expected/`) les sorties du virtual-cyclist TS sur 3 GPX de référence :
  - `sample.gpx` (court, plat)
  - un GPX de col (Ventoux du gpx2web)
  - un GPX urbain avec virages serrés
- Format : JSON ou CSV avec champs clés (time, lat, lon, ele, speed, power_total, grade).
- Test Kotlin : exécute le pipeline Kotlin sur ces GPX, compare à la fixture (tolérance par champ : 0.5 % distance, 0.5 km/h vitesse, 5 W puissance — à affiner).

### 27-engine-cli-smoke.md
- Petit point d'entrée `jvmMain` (`fun main()`) : `engine-cli enhance input.gpx --cyclist-mass 80 --power 280 -o output.gpx`.
- Test smoke : commande tourne sans erreur sur `sample.gpx`.

### 28-engine-js-wasm-public-api.md
- API consommateurs JS/Wasm (utilisée par la future demo et un éventuel npm package).
- Définir DTO `@JsExport` dans `commonMain` (visibles par toutes cibles) **mais** annotations `@JsExport` actives uniquement quand compilées pour `wasmJs`/`js` :
  - `external interface CyclistDto : JsAny { val massKg: Double; val powerW: Double; val cd: Double; val frontalAreaM2: Double; ... }`
  - `external interface BikeDto : JsAny { val crr: Double; ... }`
  - `external interface EnhanceOptionsDto : JsAny { ... }`
  - `@JsExport class PathView` (opaque) avec accessors typés : `length: Int`, `pointAt(i: Int): JsArray<JsNumber>` ou `pointJson(i: Int): String`.
- Façade exposée :
  ```kotlin
  @JsExport
  fun parseGpx(xml: String): PathView
  @JsExport
  fun enhance(path: PathView, cyclist: CyclistDto, bike: BikeDto, options: EnhanceOptionsDto?): Promise<PathView>
  @JsExport
  fun writeGpx(path: PathView): String
  ```
- `suspend fun enhance(...)` interne → wrapper `Promise<JsAny?>` via `GlobalScope.promise { ... }` côté `wasmJsMain`.
- Activation `generateTypeScriptDefinitions()` dans le bloc `wasmJs {}` du build → produit `engine.d.ts` à côté du `.mjs`.
- Validation : fichier `.d.ts` généré et lisible ; smoke test JS dans `wasmJsTest` qui appelle parseGpx → enhance → writeGpx sur un GPX inline.

**Critère de fin** : `./gradlew check` vert sur toutes cibles, parity tests passants à la tolérance définie, `.d.ts` généré.

---

## Phase 9 — Démo Vue/Vite sur sortie Kotlin/JS

**Décision (révisée)** : la démo Compose Multiplatform initialement esquissée
est abandonnée. À la place, on **porte** la démo Vue 3 + Vite existante de
`virtual-cyclist/demo/` dans le module `:demo` du repo vcyclist, en branchant
le moteur **Kotlin/JS** à la place du moteur TypeScript. Avantages : réutilise
un UX déjà mature (PrimeVue, Leaflet, Chart.js), évite l'investissement en
chart/map natif Compose (écosystème encore jeune côté Web), livre la démo en
quelques tâches.

**Cible technique** :

- **Module `:demo`** — Vue 3 + Vite + TypeScript + PrimeVue + Leaflet + Chart.js,
  géré par Gradle via le plugin `com.github.node-gradle.node` (Node téléchargé
  automatiquement, build reproductible).
- **Consommation moteur** — `@glandais/vcyclist-engine` linké via
  `file:../engine/build/dist/js/productionLibrary` (Vite + npm). La cible
  Kotlin/JS est privilégiée pour la maturité d'interop avec Vue ; pas de
  consommation Wasm dans cette phase.
- **API engine étendue** (task 34) — `enhanceWithCourse(path, cyclistDto?,
  bikeDto?, windDto?, powerProviderDto?, optionsDto?)` + `getField(path, i,
  fieldProp: string)` + `fieldDefinitions(): Array<FieldDefinitionDto>` + 5
  DTO `external interface` (Cyclist/Bike/Wind/Power/FieldDefinition). L'API
  `enhance(path, options)` existante reste inchangée (compat npm Phase 3).
- **Build & deploy** — `./gradlew :demo:assemble` produit un site statique
  servable. Task 39 (optionnelle) ajoute la publication automatique sur
  GitHub Pages à chaque push develop.

**Tâches** (cf. `docs/tasks/34-...md` à `39-...md`) :

- **34** — Engine `@JsExport` façade : expansion DTO + helpers
- **35** — Demo bootstrap Vue/Vite (shell vide qui résout le bundle Kotlin/JS)
- **36** — Demo intégration moteur (`useGPXDemo`, types, persistance)
- **37** — Demo UI complète (16 composants Vue, chart, map, tabs, sidebar)
- **38** — Demo intégration Gradle (`:demo:assemble`) + samples + docs
- **39** — Demo déploiement GitHub Pages (optionnel)

**Critère de fin de Phase 9** : `./gradlew :demo:assemble` produit un site
statique fonctionnel ; charger Stelvio + cliquer Enhance affiche durée
virtualisée plausible, profil elevation/speed sur le chart, track sur la carte ;
hover sync chart ↔ map fonctionne ; les 36 champs sont sélectionnables via
FieldsSidebar.

---

## Fichiers critiques de référence à lire avant exécution

À consulter par l'exécutant de chaque tâche :

**`elevation/` (TS)** — spec algorithmes
- `src/ElevationProvider.ts`, `src/types.ts`
- `src/calculator/{ElevationCalculator,BatchCalculator,ElevationFunctions}.ts`
- `src/utils/{Distance,DouglasPeucker,ElevationSmoother,EcefConverter,Vector3D}.ts`
- `src/tile/{TileManager,TileLoader}.ts`
- `test/**/*.test.ts` (cas numériques à porter)

**`virtual-cyclist/` (TS)** — spec engine
- `HOW_IT_WORKS.md` (spec numérique exhaustive — **lecture obligatoire**)
- `src/types/path/{fieldDefinitions,GeneratedPath,Path}.ts`
- `src/types/models/{Cyclist,Bike,Course}.ts`
- `src/physics/{VirtualizeService,MaxSpeedComputer}.ts`
- `src/physics/power/{PowerComputer,aero/AeroPowerProvider,grav/GravPowerProvider,rolling/*}.ts`
- `src/enhancer/Enhancer.ts`
- `src/gpx/{GPXParser,GPXWriter}.ts`

**`gpx2web/` (Java)** — inspiration architecturale
- `gpx/.../virtual/{VirtualizeService,GPXEnhancer}.java`
- `gpx/.../virtual/power/{PowerComputer, aero/AeroPowerProvider, rolling/*, grav/*}.java`
- `gpx/.../virtual/maxspeed/MaxSpeedComputer.java`
- `gpx/.../data/{Point,GPXPath,values/PropertyKeys}.java`

**`kotlin-wasm-jvm-webp.md`** (racine du repo) — guide d'interop & décodage WebP
- §1 — `@JsExport` et types autorisés
- §3 — wrappers `suspend` → `Promise`
- §4 — `generateTypeScriptDefinitions()` et choix DTO (`external interface` vs `@JsExport class`)
- §5 — fetch + `createImageBitmap` + canvas pour décoder WebP côté browser
- §6 — pattern `expect`/`actual` complet (commonMain + wasmJsMain + jvmMain TwelveMonkeys)
- Référence à citer dans chaque tâche qui touche au fetcher de tuiles ou à l'export JS/Wasm.

---

## Verification end-to-end

Une fois toutes les tâches du plan exécutées :

1. `cd vcyclist && ./gradlew check` — toutes targets compilent et passent les tests.
2. `./gradlew :elevation:allTests :engine:allTests` — tests verts en JVM, JS, Wasm.
3. `./gradlew :engine:jvmRun --args="enhance ../virtual-cyclist/sample.gpx -o /tmp/out.gpx"` — smoke CLI.
4. Comparaison fixtures de parité : `./gradlew :engine:jvmTest --tests "*ParityTest"` — vert.
5. Couverture rapportée ≥ 80 % sur les deux modules.

À ce stade, l'engine Kotlin est fonctionnellement équivalent à l'engine TS, compilable en JVM/JS/Wasm, et prêt à servir la Phase 9 (demo Compose).
