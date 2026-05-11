# 28 — Engine : façade `@JsExport` (JS Node + Wasm browser)

## Goal

Exposer le pipeline `:engine` à JavaScript via `@JsExport`, sur **2 cibles** :

- **`wasmJsMain`** : façade Kotlin/Wasm (DTOs `external interface : JsAny`, `Promise<JsAny>`, `JsReference<T>` handle pattern).
- **`jsMain`** : façade Kotlin/JS Node (DTOs `external interface`, `Promise<T>`, classes directes).

Le code suit **exactement** le pattern validé sur le module `:elevation` (commits `a095ff8` côté Wasm + impl Kotlin/JS sœur dans `:elevation`). Lire ces sources comme **référence d'architecture** :

- `vcyclist/elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt`
- `vcyclist/elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt`
- `vcyclist/docs/tasks/bonus-elevation-js-demo.md` — recette détaillée du portage Wasm→JS (mêmes adaptations à reproduire pour `:engine`)

API exposée :

- `parseGpx(xml: String): PathHandle` — parse une chaîne GPX, retourne un handle sur le `Path` du 1er track.
- `enhance(path: PathHandle, options: EnhanceOptionsDto?): Promise<PathHandle>` — lance `Enhancer.enhanceCourseDefault` avec `fixElevation=false`, `computeOnePointPerSecond=false`, `simplifyPath.enabled=false` par défaut (cf. tâche 27 — évite les bugs timestamp).
- `writeGpx(path: PathHandle): String` — sérialise en GPX.
- Accesseurs sur `PathHandle` : `pathSize(h)`, `pathTotalDistance(h)`, `pathDurationMs(h)`, `pathElevationGain(h)`, `pathElevationLoss(h)`, `pointAt(h, i): PointDto`.

`generateTypeScriptDefinitions()` est déjà activé pour les 2 targets (cf. `engine/build.gradle.kts`, à activer si pas déjà fait — vérifier).

**Hors scope** : pas de demo browser HTML/CSS/JS dans cette tâche (cf. demos `:elevation` qui ont demandé ~1500 lignes de JS UI). Si une demo est souhaitée, créer une tâche `29-engine-demo` séparée.

## Depends on

- `25-engine-enhancer` (`Enhancer.enhanceCourseDefault`)
- `14-engine-gpx-parser`, `15-engine-gpx-writer`
- Module `:elevation` (référence d'architecture)

## Inputs

- `vcyclist/elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt` (pattern Wasm)
- `vcyclist/elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt` (pattern Kotlin/JS)
- `vcyclist/docs/kotlin-wasm-jvm-webp.md` §1, §3, §4 (interop conventions)
- Commit `a095ff8` (historique du choix de design `JsReference` handle)

## Steps

### 1. Activation cibles JS/Wasm dans `engine/build.gradle.kts`

Vérifier (et compléter) que le module `:engine` configure bien :

```kotlin
js(IR) {
    nodejs()
    browser {
        commonWebpackConfig { outputFileName = "engine.js" }
        testTask { useKarma { useChromeHeadless() } }
    }
    binaries.executable()
    generateTypeScriptDefinitions()
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
wasmJs {
    browser {
        testTask { useKarma { useChromeHeadless() } }
    }
    binaries.executable()
    generateTypeScriptDefinitions()
}

sourceSets {
    // ... existant ...
    jsMain.dependencies { implementation(libs.kotlinx.browser) }
    wasmJsMain.dependencies { implementation(libs.kotlinx.browser) }
}
```

C'est le même bloc que `elevation/build.gradle.kts` (cf. bonus `:elevation` JS demo), avec `outputFileName = "engine.js"` au lieu de `elevation.js`. `kotlinx-browser 0.5.0` publie bien des artefacts pour `js` ET `wasmJs` — confirmé par le bonus.

### 2. DTOs partagés (commonMain ou wasmJs/jsMain) : structure

Approche : déclarer les DTOs **dans chaque target** (pas en commonMain), car `external interface` n'est pas valide en common. C'est ce que fait le module `:elevation`.

Les deux fichiers `EngineJsApi.kt` (wasmJs et js) partagent **mot pour mot** :
- Les DTOs (`PointDto`, `EnhanceOptionsDto`, etc.)
- La signature `@JsExport` des fonctions
- L'implémentation interne

Différences ciblées :
- **wasmJs** : `external interface : JsAny`, `JsReference<Path>`, `JsArray<…>`, `JsNumber`, `@JsFun` pour construire les objets JS de sortie, `kotlin.js.ExperimentalWasmJsInterop`.
- **jsMain** : `external interface` (no JsAny), retour direct de classes Kotlin (pas de handle), `Array<T>`, `Double`, `js("({})")` pour construire les outputs.

### 3. `EngineJsApi.kt` (wasmJsMain)

`engine/src/wasmJsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt` :

```kotlin
@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
    kotlin.js.ExperimentalWasmJsInterop::class,
)

package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise

/** JS-side view of one path point. */
external interface PointDto : JsAny {
    val latitudeDeg: Double
    val longitudeDeg: Double
    val elevation: Double
    val timeMs: Double
    val speed: Double
    val pComputedPower: Double
    val distance: Double
    val grade: Double
}

/** Subset of [EnhanceOptions] visible to JS. */
external interface EnhanceOptionsDto : JsAny {
    val fixElevation: Boolean?
    val computeMaxSpeeds: Boolean?
    val virtualizeTrack: Boolean?
    val computeOnePointPerSecond: Boolean?
    val simplifyEnabled: Boolean?
    val simplifyToleranceM: Double?
    val simplifyZExaggeration: Double?
}

@JsFun("""(latitudeDeg, longitudeDeg, elevation, timeMs, speed, pComputedPower, distance, grade) =>
    ({ latitudeDeg, longitudeDeg, elevation, timeMs, speed, pComputedPower, distance, grade })""")
private external fun pointObj(
    latitudeDeg: Double, longitudeDeg: Double, elevation: Double, timeMs: Double,
    speed: Double, pComputedPower: Double, distance: Double, grade: Double,
): PointDto

@JsExport
fun parseGpx(xml: String): JsReference<Path> =
    GpxParser.parse(xml).firstTrackAsPath().toJsReference()

@JsExport
fun pathSize(handle: JsReference<Path>): Int = handle.get().size

@JsExport
fun pathTotalDistance(handle: JsReference<Path>): Double = handle.get().totalDistance

@JsExport
fun pathDurationMs(handle: JsReference<Path>): Double = handle.get().durationMs

@JsExport
fun pathElevationGain(handle: JsReference<Path>): Double = handle.get().elevationGain

@JsExport
fun pathElevationLoss(handle: JsReference<Path>): Double = handle.get().elevationLoss

@JsExport
fun pointAt(handle: JsReference<Path>, i: Int): PointDto {
    val p = handle.get()
    return pointObj(
        latitudeDeg = p.latitudeDeg(i),
        longitudeDeg = p.longitudeDeg(i),
        elevation = p.elevation(i),
        timeMs = p.time(i),
        speed = p.speed(i),
        pComputedPower = p.pComputedPower(i),
        distance = p.distance(i),
        grade = p.grade(i),
    )
}

@JsExport
fun writeGpx(handle: JsReference<Path>): String =
    GpxWriter.write(handle.get().toGpxDocument(trackName = "virtualized"))

@JsExport
fun enhance(
    handle: JsReference<Path>,
    options: EnhanceOptionsDto?,
): Promise<JsReference<Path>> = GlobalScope.promise {
    val opts = options.toEnhanceOptions()
    val out = Enhancer.enhanceCourseDefault(handle.get(), elevationProvider = null, options = opts)
    out.toJsReference()
}

private fun EnhanceOptionsDto?.toEnhanceOptions(): EnhanceOptions {
    if (this == null) return defaultJsOptions()
    return EnhanceOptions(
        fixElevation = fixElevation ?: false,
        computeMaxSpeeds = computeMaxSpeeds ?: true,
        virtualizeTrack = virtualizeTrack ?: true,
        computeOnePointPerSecond = computeOnePointPerSecond ?: false,
        simplifyPath = SimplifyPathOptions(
            enabled = simplifyEnabled ?: false,
            toleranceM = simplifyToleranceM ?: 10.0,
            zExaggeration = simplifyZExaggeration ?: 3.0,
        ),
    )
}

/** Safe defaults for browser/Node calls : skip elevation fetch + skip 1Hz resample + skip simplify
 *  (cf. task 27 — timestamps 2024 break PointPerSecond). */
private fun defaultJsOptions(): EnhanceOptions = EnhanceOptions(
    fixElevation = false,
    computeMaxSpeeds = true,
    virtualizeTrack = true,
    computeOnePointPerSecond = false,
    simplifyPath = SimplifyPathOptions(enabled = false),
)
```

### 4. `EngineJsApi.kt` (jsMain) — mirroir

`engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt` :

```kotlin
@file:OptIn(
    DelicateCoroutinesApi::class,
    kotlin.js.ExperimentalJsExport::class,
)

package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument
import io.github.glandais.engine.path.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport
import kotlin.js.Promise

external interface PointDto {
    val latitudeDeg: Double
    val longitudeDeg: Double
    val elevation: Double
    val timeMs: Double
    val speed: Double
    val pComputedPower: Double
    val distance: Double
    val grade: Double
}

external interface EnhanceOptionsDto {
    val fixElevation: Boolean?
    val computeMaxSpeeds: Boolean?
    val virtualizeTrack: Boolean?
    val computeOnePointPerSecond: Boolean?
    val simplifyEnabled: Boolean?
    val simplifyToleranceM: Double?
    val simplifyZExaggeration: Double?
}

private fun pointObj(
    latitudeDeg: Double, longitudeDeg: Double, elevation: Double, timeMs: Double,
    speed: Double, pComputedPower: Double, distance: Double, grade: Double,
): PointDto {
    val o = js("({})")
    o.latitudeDeg = latitudeDeg
    o.longitudeDeg = longitudeDeg
    o.elevation = elevation
    o.timeMs = timeMs
    o.speed = speed
    o.pComputedPower = pComputedPower
    o.distance = distance
    o.grade = grade
    return o.unsafeCast<PointDto>()
}

@JsExport fun parseGpx(xml: String): Path = GpxParser.parse(xml).firstTrackAsPath()
@JsExport fun pathSize(path: Path): Int = path.size
@JsExport fun pathTotalDistance(path: Path): Double = path.totalDistance
@JsExport fun pathDurationMs(path: Path): Double = path.durationMs
@JsExport fun pathElevationGain(path: Path): Double = path.elevationGain
@JsExport fun pathElevationLoss(path: Path): Double = path.elevationLoss

@JsExport
fun pointAt(path: Path, i: Int): PointDto = pointObj(
    latitudeDeg = path.latitudeDeg(i),
    longitudeDeg = path.longitudeDeg(i),
    elevation = path.elevation(i),
    timeMs = path.time(i),
    speed = path.speed(i),
    pComputedPower = path.pComputedPower(i),
    distance = path.distance(i),
    grade = path.grade(i),
)

@JsExport
fun writeGpx(path: Path): String =
    GpxWriter.write(path.toGpxDocument(trackName = "virtualized"))

@JsExport
fun enhance(path: Path, options: EnhanceOptionsDto?): Promise<Path> = GlobalScope.promise {
    val opts = options.toEnhanceOptions()
    Enhancer.enhanceCourseDefault(path, elevationProvider = null, options = opts)
}

private fun EnhanceOptionsDto?.toEnhanceOptions(): EnhanceOptions {
    if (this == null) return defaultJsOptions()
    return EnhanceOptions(
        fixElevation = fixElevation ?: false,
        computeMaxSpeeds = computeMaxSpeeds ?: true,
        virtualizeTrack = virtualizeTrack ?: true,
        computeOnePointPerSecond = computeOnePointPerSecond ?: false,
        simplifyPath = SimplifyPathOptions(
            enabled = simplifyEnabled ?: false,
            toleranceM = simplifyToleranceM ?: 10.0,
            zExaggeration = simplifyZExaggeration ?: 3.0,
        ),
    )
}

private fun defaultJsOptions(): EnhanceOptions = EnhanceOptions(
    fixElevation = false,
    computeMaxSpeeds = true,
    virtualizeTrack = true,
    computeOnePointPerSecond = false,
    simplifyPath = SimplifyPathOptions(enabled = false),
)
```

### 5. Tests

Cibles : **smoke tests** uniquement (la logique métier est testée en `commonTest`). Vérifient seulement que la chaîne `@JsExport` compile et que l'invocation depuis JS marche.

#### `engine/src/wasmJsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt`

```kotlin
package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxFixtures
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class EngineJsApiTest {
    @Test fun `parseGpx + enhance + writeGpx pipeline runs end-to-end via the wasm bridge`() = runTest {
        val handle = parseGpx(GpxFixtures.SAMPLE_GPX_XML)
        assertTrue(pathSize(handle) > 0)
        val enhanced = enhance(handle, options = null).await()
        assertTrue(pathSize(enhanced) > 0)
        val xml = writeGpx(enhanced)
        assertTrue(xml.startsWith("<?xml"))
    }

    @Test fun `pointAt returns DTO with expected fields`() {
        val handle = parseGpx(GpxFixtures.SAMPLE_GPX_XML)
        val pt = pointAt(handle, 0)
        // Just verify the DTO doesn't throw on field access
        assertTrue(pt.latitudeDeg in -90.0..90.0)
        assertTrue(pt.longitudeDeg in -180.0..180.0)
    }
}
```

(Adapter `await()` selon l'API Wasm Promise → coroutine — cf. tests `:elevation` wasm.)

#### `engine/src/jsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt`

Similaire mais sans handle (`parseGpx` retourne `Path` directement).

### 6. Vérification ktlint + `.d.ts` généré

```bash
./gradlew :engine:build
ls engine/build/dist/wasmJs/productionExecutable/*.d.* 2>/dev/null
ls engine/build/dist/js/productionExecutable/*.d.* 2>/dev/null
```

Le `.d.ts` doit contenir au minimum :
- `export function parseGpx(xml: string): …`
- `export function enhance(path: …, options: …): Promise<…>`
- `export function writeGpx(path: …): string`
- `export function pointAt(path: …, i: number): PointDto`
- `export interface PointDto { latitudeDeg: number ; … }`

### 7. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés :

- `engine/src/wasmJsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt`
- `engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt`
- `engine/src/wasmJsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt`
- `engine/src/jsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt`

Modifiés (si nécessaire) :

- `engine/build.gradle.kts` (activation `generateTypeScriptDefinitions` + `binaries.executable()` sur les 2 targets si pas déjà fait)

## Validation

```bash
./gradlew :engine:build
./gradlew :engine:wasmJsBrowserTest
./gradlew :engine:jsNodeTest
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
```

Critères :

- Le module `:engine` compile sur les 4 targets (jvm/jsNode/wasmJs + commonTest).
- ≥ 2 tests par target (wasmJsTest + jsNodeTest) verts.
- `.d.ts` produit pour chaque target.
- `:elevation:allTests` toujours vert.
- Non-régression complète tâches 10-27.

## Done when

- [x] `EngineJsApi.kt` créé dans `wasmJsMain` ET dans `jsMain`
- [x] DTOs `external interface` cohérents entre les 2 cibles
- [x] Tests smoke verts sur wasmJs + jsNode
- [x] `.d.ts` produit pour wasmJs et jsMain
- [x] `:engine:allTests` vert sur les 3 targets (JVM + JS Node + Wasm Browser)
- [x] `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **Conventions exactes** : suivre le pattern `:elevation` (mêmes opt-in, mêmes signatures, même `js("({})")` vs `@JsFun`). C'est le code de référence.
- **Defaults JS-safe** : `defaultJsOptions()` désactive `fixElevation`, `computeOnePointPerSecond` et `simplifyPath` car :
  - `fixElevation` nécessiterait un `ElevationProvider` HTTP côté JS — non trivial sans une vraie integration browser (cf. `:elevation` demo).
  - `computeOnePointPerSecond` + timestamps GPX en 2024 → OOM (cf. tâche 27).
  - `simplifyPath` enabled change agressivement le nombre de points → tests less reproducibles.
  - Si le caller JS sait ce qu'il fait, il peut passer `options = { fixElevation: true, computeOnePointPerSecond: true, simplifyEnabled: true }` et fournir un provider via une API séparée.
- **Pas de demo browser** : intentionnel pour limiter la portée. Demo possible dans une tâche 29 dédiée (cf. les ~1500 lignes HTML/CSS/JS du demo `:elevation`).
- **`Promise<JsReference<Path>>`** vs **`Promise<Path>`** : différence d'idiom entre Wasm et JS. La signature dans le `.d.ts` reflètera : `Promise<unknown>` vs `Promise<engine.Path>`.
- **`generateTypeScriptDefinitions()`** : statut expérimental. Peut générer des fichiers `.d.mts` avec des limitations (génériques, JsAny). Documenter les limitations rencontrées si non-trivial.
- **`PathHandle` opaque côté JS** : le JS ne peut pas inspecter `handle.get().data` (DoubleArray opaque). Il doit passer par `pointAt(handle, i)` pour lire un point. Inefficace pour des paths longs (allocation d'1 DTO par point) — acceptable car les chunks JS itèrent rarement plus de 1000 points.
- **Préparation future** : si Wasm browser demo désirée, créer une tâche 29 qui copie les `resources/` du module `:elevation` et ajoute un shim JS spécifique au pipeline cycliste (chart speed/power vs distance, etc.).
