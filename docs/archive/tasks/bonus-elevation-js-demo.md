# Bonus — Démo browser Kotlin/JS pour `:elevation`

## Goal

Ajouter une démo browser Kotlin/JS (cible `js(IR)`, sans Wasm) en parallèle de la démo Kotlin/Wasm existante (commit `a095ff8`). Permet de comparer taille de bundle, latence de chargement et support navigateur des deux backends sur la même UI Leaflet + Chart.js.

## Depends on

- Bonus précédent (démo Wasm `a095ff8`) — la façade `ElevationJsApi.kt` côté `wasmJsMain` et les ressources HTML/JS/CSS dans `elevation/src/wasmJsMain/resources/` servent de source de vérité.
- Phase 1 `:elevation` terminée (tâches 00-09).

## Inputs

- `elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt` — façade `@JsExport` à porter
- `elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/TileFetcher.wasmJs.kt` — pattern fetch + canvas decode WebP
- `elevation/src/wasmJsMain/resources/{index.html,demo.js,demo.css,sample.gpx}` — UI complète
- `docs/kotlin-wasm-jvm-webp.md` §4 (DTO via `external interface`) et §5 (fetch + WebP browser)

## Steps

1. Étendre `elevation/build.gradle.kts` :
   - Bloc `js(IR)` : ajouter `browser { commonWebpackConfig { outputFileName = "elevation.js" } ; testTask { useKarma { useChromeHeadless() } } }`, `binaries.executable()`, `generateTypeScriptDefinitions()` (en gardant `nodejs()` pour ne pas casser les tests Node existants).
   - Ajouter `jsMain.dependencies { implementation(libs.kotlinx.browser) }` — `kotlinx-browser` 0.5.0 supporte JS en plus de Wasm.
2. Remplacer `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt` (actuellement un stub `NotImplementedError`) par l'implémentation browser : `window.fetch` → `blob()` → `createImageBitmap` → canvas 2D → `getImageData` → `ByteArray` RGBA. Le pattern wasm est portable presque tel quel (mêmes imports `kotlinx.browser.*` et `org.w3c.*`), avec deux ajustements Kotlin/JS-spécifiques : indexation directe `Uint8ClampedArray[i].toByte()` (pas de `toByteArray()`/`Int8Array` reinterpret), et `import kotlinx.coroutines.await`.
3. Créer `elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt`, miroir de la version wasm avec adaptations Kotlin/JS :
   - `external interface ... ` (sans parent `JsAny`)
   - `ElevationProvider` direct (pas de `JsReference<T>`)
   - `Array<T>` (pas de `JsArray<T>`)
   - `Promise<Double>` (pas de `Promise<JsNumber>`)
   - `Array<CoordinatesElevationDto>` produit en sortie (les objets DTO sont construits via `js("{}")` + assignations puis `unsafeCast`)
   - Mêmes noms de fonctions exportées (`newElevationProvider`, `getElevation`, `getElevationsAlong`) pour parité avec le shim wasm.
4. Copier les 4 fichiers de `elevation/src/wasmJsMain/resources/` vers `elevation/src/jsMain/resources/` :
   - `demo.css` et `sample.gpx` : copies strictement identiques.
   - `demo.js` : copie strictement identique (consomme la façade via `window.Elevation.ElevationProvider`, agnostique du backend).
   - `index.html` : copie + adaptation des labels "WASM" → "Kotlin/JS" et du shim — `globalThis.elevation` est synchrone en JS (pas une Promise), mais `await x` sur une valeur non-Promise renvoie la valeur, donc le shim wasm fonctionne tel quel.
5. Mettre à jour `elevation/README.md` : ajouter une section "Démo Kotlin/JS" en miroir de la section Wasm, documentant `:elevation:jsBrowserDevelopmentRun` et `:elevation:jsBrowserDistribution`.

## Outputs (fichiers attendus)

- `elevation/build.gradle.kts` (modifié)
- `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt` (réécrit)
- `elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt` (nouveau)
- `elevation/src/jsMain/resources/index.html` (nouveau)
- `elevation/src/jsMain/resources/demo.js` (nouveau, copie)
- `elevation/src/jsMain/resources/demo.css` (nouveau, copie)
- `elevation/src/jsMain/resources/sample.gpx` (nouveau, copie)
- `elevation/README.md` (modifié)

## Validation

- `./gradlew :elevation:jsBrowserProductionWebpack` — bundle généré dans `build/dist/js/productionExecutable/`
- `./gradlew :elevation:jsTest` — tests Node passent (les 182 commonTests, inchangés)
- `./gradlew :elevation:wasmJsBrowserTest` — non-régression de la démo Wasm
- `./gradlew :elevation:allTests` — tous les targets verts (`jvm`, `js`, `wasmJs`)
- Smoke E2E (manuel) : `./gradlew :elevation:jsBrowserDevelopmentRun` → ouvrir l'URL, charger `sample.gpx` via UI → vérifier altitude max ≈ Mont Blanc (~4757 m, identique à la démo Wasm)

## Done when

- [x] `:elevation:jsBrowserProductionWebpack` vert
- [x] `:elevation:jsTest` vert
- [x] `:elevation:wasmJsBrowserTest` vert (non-régression)
- [x] `:elevation:allTests` vert toutes targets
- [x] Bundle `build/dist/js/productionExecutable/elevation.js` produit avec les assets HTML/JS/CSS/GPX
- [x] `.d.ts` généré côté JS (parité Wasm)
- [x] README mis à jour avec la section "Démo Kotlin/JS"
- [x] Markdown coché + PLAN.md tracking ligne ★ ajoutée

## Notes

- **Pas de tests unitaires** pour la façade JS — parité avec la démo Wasm (qui n'en a pas). Le smoke E2E manuel (chargement de `sample.gpx`) reste la validation fonctionnelle.
- **`@OptIn(DelicateCoroutinesApi)`** : nécessaire pour `GlobalScope.promise` (dette technique partagée avec wasm, à traiter si publication d'un npm package).
- **`kotlinx-browser` 0.5.0** : confirmé qu'il publie un artefact `js` en plus de `wasmJs` (cf. `gradle/libs.versions.toml`).
- **Format module** : Kotlin/JS sort un bundle UMD par défaut (comme wasm), `globalThis.elevation` accessible synchroniquement (Wasm = Promise à cause de l'instanciation WebAssembly).
- **Pas d'`useEsModules()`** ajouté — garde UMD pour parité avec wasm et simplicité du shim.
- **`nodejs()` conservé** pour ne pas casser `jsNodeTest` (182 commonTests existants). L'`actual fetchAndDecodeTile` reste browser-only ; les tests Node n'appellent pas cette fonction (logique pure).
