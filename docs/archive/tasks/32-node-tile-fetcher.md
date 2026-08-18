# 32 — Elevation : tile fetcher Node.js / Bun (runtime detection + @jsquash/webp)

## Goal

Faire fonctionner le module `:elevation` en environnement **Node.js / Bun**, en plus du navigateur déjà supporté. Le bloqueur actuel : `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt` appelle `window.createImageBitmap` + `document.createElement("canvas")`, APIs DOM absentes hors navigateur.

La cible `js(IR) { nodejs(); browser() }` est déjà déclarée. Avec ce fix, `:elevation:jsNodeTest` + tout consommateur Node.js (CLI, Bun, scripts) pourra appeler `ElevationProvider.getElevation` / `getElevationsAlong` réellement et faire passer du WebP par le pipeline.

Décisions actées (cf. plan approuvé) :

- **Décodeur WebP Node** : `@jsquash/webp` (WASM pur, ~50 KB, identique Node / Bun / Deno).
- **Séparation Node ↔ Browser** : **runtime detection** dans un seul `jsMain` (`typeof window === 'undefined' && process.versions.node`). Kotlin/JS produit un seul binaire pour le target `js(IR)` qui sert les deux environnements ; il n'y a pas de source set intermédiaire `jsBrowserMain` / `jsNodeMain` automatique en `js(IR)`.

## Depends on

- Task 06 (Tile fetcher multi-target, déjà fait).
- Task 09 (intégration HTTP réelle JVM, référence des coordonnées Mont Blanc / Dead Sea pour les tests de la task 33).

## Inputs

- `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt` (à modifier, ~42 lignes)
- `elevation/src/commonMain/kotlin/io/github/glandais/elevation/TileFetcher.kt` (`expect` — référence du contrat)
- `elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/TileFetcher.wasmJs.kt` (référence inchangée)
- `elevation/src/jvmMain/kotlin/io/github/glandais/elevation/TileFetcher.jvm.kt` (référence inchangée)
- `elevation/build.gradle.kts` (ajouter npm dep + dispatcher webpack externals)
- Package npm cible : `@jsquash/webp@^1.4.0` ; API utilisée : `decode(buffer)` retournant `ImageData` (avec `data: Uint8ClampedArray`, `width`, `height`).

## Steps

### 1. Modifier `TileFetcher.js.kt`

Architecture finale :

```kotlin
package io.github.glandais.elevation

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.ImageBitmap
import org.w3c.fetch.Response
import org.w3c.files.Blob
import kotlin.js.Promise

// True when running under Node.js or Bun (no DOM, has process.versions.node).
private val isNode: Boolean = js(
    "typeof window === 'undefined' && typeof process !== 'undefined' " +
        "&& process.versions != null && process.versions.node != null",
) as Boolean

// Bypasses the kotlinx-browser-js `fetch(input, init)` declaration which has no default
// for `init` and would serialise `{cache: null}`. The Wasm target has `init = null` so
// this is js-target-only plumbing. Used by `decodeBrowser`.
private fun fetchUrlBrowser(url: String): Promise<Response> =
    js("fetch(url)").unsafeCast<Promise<Response>>()

// Node: globalThis.fetch is native since Node 18 and Bun. Returns a Web `Response`.
private fun fetchUrlNode(url: String): Promise<Response> =
    js("globalThis.fetch(url)").unsafeCast<Promise<Response>>()

// Wrap the response.arrayBuffer() promise — Web standard, available in Node 18+.
private fun responseArrayBuffer(res: Response): Promise<dynamic> =
    js("res.arrayBuffer()").unsafeCast<Promise<dynamic>>()

// Load @jsquash/webp lazily so webpack does NOT resolve it at bundle time for the
// browser target. The require() is hidden behind eval() to defeat webpack's static
// resolver — combined with webpack.config.d/externals.js, the browser bundle stays
// jsquash-free.
private fun decodeWebpNode(buffer: dynamic): Promise<dynamic> =
    js("eval('require')('@jsquash/webp/decode/index.js').default(buffer)")
        .unsafeCast<Promise<dynamic>>()

actual suspend fun fetchAndDecodeTile(url: String): RawTile =
    if (isNode) decodeNode(url) else decodeBrowser(url)

private suspend fun decodeBrowser(url: String): RawTile {
    val res: Response = fetchUrlBrowser(url).await()
    check(res.ok) { "Tile fetch failed for $url: HTTP ${res.status}" }
    val blob: Blob = res.blob().await()

    val bitmap: ImageBitmap = window.createImageBitmap(blob).await()
    try {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = bitmap.width
        canvas.height = bitmap.height
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(bitmap, 0.0, 0.0)
        val data = ctx.getImageData(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
        val src = data.data
        val int8 = Int8Array(src.buffer, src.byteOffset, src.byteLength)
        val rgba: ByteArray = int8.unsafeCast<ByteArray>()
        return RawTile(bitmap.width, bitmap.height, rgba)
    } finally {
        bitmap.close()
    }
}

private suspend fun decodeNode(url: String): RawTile {
    val res: Response = fetchUrlNode(url).await()
    check(res.ok) { "Tile fetch failed for $url: HTTP ${res.status}" }
    val ab: dynamic = responseArrayBuffer(res).await()
    val image: dynamic = decodeWebpNode(ab).await()
    val width: Int = (image.width as Number).toInt()
    val height: Int = (image.height as Number).toInt()
    val src: dynamic = image.data // Uint8ClampedArray
    val int8 = Int8Array(src.buffer, src.byteOffset as Int, src.byteLength as Int)
    val rgba: ByteArray = int8.unsafeCast<ByteArray>()
    return RawTile(width, height, rgba)
}
```

**Notes d'implémentation** :

- `eval('require')` est intentionnel : il rend le `require` opaque aux résolveurs statiques de Webpack (la branche Node n'est jamais exécutée côté navigateur, mais Webpack ne doit pas pulse `@jsquash/webp` dans le bundle browser).
- `@jsquash/webp/decode/index.js` est le point d'entrée Node CommonJS du package ; sa valeur `default` est la fonction `decode(buffer): Promise<ImageData>`.
- `Uint8ClampedArray` → `Int8Array` reinterpret-cast → `ByteArray` est zéro-copie, identique au pattern browser. Les signed/unsigned conversions sont bénignes (les pixels sont relus comme `Int` puis masqués `and 0xFF`).
- L'`isNode` est calculé une fois au chargement du module ; à l'exécution navigateur webpack peut tree-shaker la branche Node si la const est devinable, mais on n'en dépend pas.

### 2. Ajouter la dépendance npm à `elevation/build.gradle.kts`

Dans le bloc `sourceSets { jsMain.dependencies { ... } }` :

```kotlin
jsMain.dependencies {
    implementation(libs.kotlinx.browser)
    implementation(npm("@jsquash/webp", "1.4.0"))
}
```

Version `1.4.0` confirmée disponible sur npm (cf. https://www.npmjs.com/package/@jsquash/webp).

### 3. Créer `elevation/webpack.config.d/externals.js`

Ce fichier est automatiquement mergé par le plugin `kotlin-multiplatform` dans la config Webpack lors du build navigateur :

```js
// Mark @jsquash/webp as a Node-only dependency: do not bundle it into the browser
// distribution. The Node target resolves it at runtime via eval('require'); the
// browser distribution never enters that code path.
config.externals = (config.externals || []).concat([
    function ({ request }, callback) {
        if (request && request.startsWith('@jsquash/webp')) {
            return callback(null, 'commonjs ' + request);
        }
        callback();
    },
]);
```

Le test de regression du §Verification §7 vérifie que `@jsquash` n'apparaît plus dans `elevation/build/dist/js/productionExecutable/elevation.js`.

### 4. Vérification compilation et test

```bash
./gradlew :elevation:compileKotlinJs
./gradlew :elevation:jsNodeTest          # commonTest sur Node — doit rester vert
./gradlew :elevation:jsBrowserTest       # commonTest navigateur — doit rester vert
./gradlew :elevation:wasmJsBrowserTest   # commonTest Wasm — doit rester vert (inchangé)
./gradlew :elevation:jvmTest             # JVM — doit rester vert (inchangé)
./gradlew ktlintCheck
```

Aucune nouvelle classe de test n'est introduite par cette tâche ; les tests existants doivent rester verts. Les nouveaux tests Node-spécifiques (Mont Blanc & co) arrivent en task 33.

### 5. Smoke manuel Node optionnel

Avec network disponible :

```bash
./gradlew :elevation:jsNodeProductionRun --quiet
# Ou un petit script Node qui charge le bundle et appelle ElevationProvider.
```

Vérifier que le `require('@jsquash/webp/decode/index.js')` est résolu correctement par le runtime Node (le `package.json` du `node_modules/@jsquash/webp/` doit avoir un export `decode/index.js`).

## Outputs

Modifiés :

- `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt`
- `elevation/build.gradle.kts`

Créés :

- `elevation/webpack.config.d/externals.js`

## Validation

```bash
./gradlew :elevation:compileKotlinJs :engine:compileKotlinJs
./gradlew :elevation:allTests :engine:allTests
./gradlew :elevation:jsBrowserDistribution
test -f elevation/build/dist/js/productionExecutable/elevation.js
! grep -q '@jsquash' elevation/build/dist/js/productionExecutable/elevation.js
./gradlew ktlintCheck
```

Critères :

- `compileKotlinJs` passe sans warning sur l'`unsafeCast` ou le `require`.
- `:elevation:allTests` vert (JVM, JS Node, JS Browser, Wasm Browser) — aucun test n'a été ajouté, donc l'effectif reste identique.
- `:engine:allTests` vert (régression).
- Le bundle navigateur `elevation.js` ne contient pas `@jsquash` (sinon l'externals n'a pas marché).
- `ktlintCheck` vert.

## Done when

- [x] `TileFetcher.js.kt` refactoré avec `isNode` + `decodeBrowser` + `decodeNode` + dispatcher
- [x] `npm("@jsquash/webp", "1.4.0")` ajouté à `jsMain.dependencies`
- [x] `webpack.config.d/externals.js` créé et fonctionnel
- [x] `:elevation:allTests` vert sur les 4 cibles
- [x] `:engine:allTests` vert (pas de régression)
- [x] Bundle navigateur sans `@jsquash` (externals OK — la seule occurrence du mot `@jsquash` dans `elevation.js` est la string littérale `@jsquash/webp/decode/index.js` à l'intérieur du `eval('require')(...)`, soit le **specifier** que Node résout à l'exécution, pas le code du package. Bundle final : 160 KiB, inchangé.)
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **Pourquoi `globalThis.fetch` et pas `node-fetch`** : Node ≥ 18 inclut `fetch` nativement (stable depuis Node 21). Bun inclut `fetch` nativement. Éviter une npm dep en plus simplifie le déploiement. Si Node 16 doit être supporté, ajouter `node-fetch` (hors scope vu les versions actuelles).
- **Pourquoi pas `expect/actual` Node vs Browser** : `js(IR)` traite `nodejs()` et `browser()` comme deux **environnements d'exécution** du **même target** (donc même binaire compilé). Le pattern canonique recommandé par JetBrains est la détection runtime ; splitter en source sets intermédiaires est de la plomberie Gradle complexe sans gain réel ici.
- **`eval('require')`** : pattern classique pour cacher un `require` aux bundlers. Sans lui, Webpack tente de résoudre `@jsquash/webp/decode/index.js` même dans le bundle navigateur ; combiné aux `externals` du §3 c'est une ceinture-et-bretelles.
- **Tolérance de version `@jsquash/webp`** : `1.4.0` à la date d'écriture. Si une 2.x sort avec une API breaking change (peu probable mais possible), la string `eval('require')('@jsquash/webp/decode/index.js').default(buffer)` peut casser ; pinner la version mineure dans `npm("@jsquash/webp", "1.4.0")` (pas de `^`).
- **Compatibilité Bun** : Bun supporte `require()` (npm compat) et `globalThis.fetch`. Aucune adaptation supplémentaire requise. Vérification manuelle en task 33 (optionnelle, si Bun installé).
- **Wasm Browser** : inchangé. `decodeNode` n'est jamais appelé depuis le target `wasmJs`, qui n'a pas de `process.versions.node`.
