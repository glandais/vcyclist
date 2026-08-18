# 33 — Tests d'intégration Node : elevation réelle + pipeline `enhance` avec `fixElevation`

## Goal

Valider le bon fonctionnement de `:elevation` et `:engine` en environnement **Node.js**, **avec elevation activée** (fetch réel de tuiles WebP via `globalThis.fetch` + décodage par `@jsquash/webp`). Cette tâche est la suite directe de task 32 et **dépend de son implémentation fonctionnelle**.

Trois fichiers de test, **gated par `INTEGRATION=1`** (mirroir du `ElevationProviderIntegrationTest` JVM existant) — pour ne pas hammeriser `tiles.mapterhorn.com` à chaque build CI.

Trois niveaux de couverture :

1. **Décodeur Node WebP** : `TileFetcherNodeIntegrationTest` — fetch + décodage d'une tuile, vérifie dimensions et pixels.
2. **ElevationProvider end-to-end Node** : `ElevationProviderNodeIntegrationTest` — Mont Blanc, Dead Sea, Death Valley, getElevationsAlong.
3. **Pipeline `enhance` complet Node avec `fixElevation=true`** : `EnhanceWithElevationNodeTest` — c'est *le* test demandé par l'utilisateur (vérifier que le mode Node avec elevation activé fonctionne en bout-en-bout).

## Depends on

- Task 32 (Node tile fetcher) — doit être fonctionnel.
- Task 09 (référence des coordonnées et tolérances : Mont Blanc 4805 ± 50 m, Dead Sea −430 ± 50 m, Death Valley −85 ± 50 m).

## Inputs

- `elevation/src/jvmTest/kotlin/io/github/glandais/elevation/ElevationProviderIntegrationTest.kt` — référence JVM à porter sur Node.
- `engine/src/commonTest/kotlin/io/github/glandais/engine/gpx/GpxFixtures.kt` — `SAMPLE_GPX` réutilisable.
- `engine/src/jsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt` — exemple de pattern `jsTest` actuel avec runTest.
- `elevation/build.gradle.kts`, `engine/build.gradle.kts` — ajouter la propagation env `INTEGRATION`.

## Steps

### 1. Helper Node `integrationEnabled()`

Créer `elevation/src/jsTest/kotlin/io/github/glandais/elevation/NodeIntegrationGate.kt` :

```kotlin
package io.github.glandais.elevation

/**
 * Skip-gate for live network tests in JS Node target. Mirrors the JVM
 * `ElevationProviderIntegrationTest` gate (env `INTEGRATION=1` or system prop
 * `integration=true`). Returns false on non-Node runtimes (browser test pages),
 * so this helper is safe to call from `jsBrowserTest` too — it will simply skip.
 */
internal fun integrationEnabled(): Boolean = js(
    "typeof process !== 'undefined' && process.env && process.env.INTEGRATION === '1'",
) as Boolean
```

### 2. Test décodeur Node — `TileFetcherNodeIntegrationTest.kt`

Créer `elevation/src/jsTest/kotlin/io/github/glandais/elevation/TileFetcherNodeIntegrationTest.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileFetcherNodeIntegrationTest {
    @Test
    fun montBlancTileDecodes() = runTest {
        if (!integrationEnabled()) return@runTest
        // Mont Blanc tile at zoom 12 : x=2138, y=1466 (Web Mercator).
        val url = "https://tiles.mapterhorn.com/12/2138/1466.webp"
        val tile = fetchAndDecodeTile(url)
        assertEquals(256, tile.width, "tile width should be 256")
        assertEquals(256, tile.height, "tile height should be 256")
        assertEquals(256 * 256 * 4, tile.rgba.size, "tile rgba should be 4 bytes per pixel")
        // Decode center pixel as Terrarium altitude : R*256 + G + B/256 - 32768.
        val ofs = (128 * 256 + 128) * 4
        val r = tile.rgba[ofs].toInt() and 0xFF
        val g = tile.rgba[ofs + 1].toInt() and 0xFF
        val b = tile.rgba[ofs + 2].toInt() and 0xFF
        val altitude = r * 256.0 + g + b / 256.0 - 32768.0
        assertTrue(altitude > 1500.0, "Mont Blanc center pixel altitude > 1500 m (got $altitude)")
        assertTrue(altitude < 5000.0, "Mont Blanc center pixel altitude < 5000 m (got $altitude)")
    }
}
```

### 3. Test ElevationProvider Node — `ElevationProviderNodeIntegrationTest.kt`

Créer `elevation/src/jsTest/kotlin/io/github/glandais/elevation/ElevationProviderNodeIntegrationTest.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ElevationProviderNodeIntegrationTest {
    @Test
    fun montBlancAltitudeIsCloseTo4805m() = runTest {
        if (!integrationEnabled()) return@runTest
        val provider = ElevationProvider()
        val alt = provider.getElevation(45.8326, 6.8652, interpolation = true)
        assertTrue(abs(alt - 4805.0) < 50.0, "Mont Blanc altitude $alt should be 4805 ± 50 m")
    }

    @Test
    fun deadSeaAltitudeIsCloseToMinus430m() = runTest {
        if (!integrationEnabled()) return@runTest
        val provider = ElevationProvider()
        val alt = provider.getElevation(31.5, 35.5, interpolation = true)
        assertTrue(abs(alt - (-430.0)) < 50.0, "Dead Sea altitude $alt should be -430 ± 50 m")
    }

    @Test
    fun deathValleyAltitudeIsCloseToMinus85m() = runTest {
        if (!integrationEnabled()) return@runTest
        val provider = ElevationProvider()
        val alt = provider.getElevation(36.250, -116.832, interpolation = true)
        assertTrue(abs(alt - (-85.0)) < 50.0, "Death Valley altitude $alt should be -85 ± 50 m")
    }

    @Test
    fun getElevationsAlongMontBlancPathReturnsDensifiedProfile() = runTest {
        if (!integrationEnabled()) return@runTest
        val provider = ElevationProvider()
        val path = listOf(
            Coordinates(45.83, 6.86, null),
            Coordinates(45.84, 6.87, null),
            Coordinates(45.85, 6.88, null),
            Coordinates(45.83, 6.88, null),
        )
        val profile = provider.getElevationsAlong(path, step = 100.0)
        assertTrue(profile.size >= 10, "Expected ≥ 10 densified points, got ${profile.size}")
        val altitudes = profile.map { it.elevation }
        val maxAlt = altitudes.max()
        val minAlt = altitudes.min()
        assertTrue(minAlt > 1000.0, "min altitude $minAlt should be > 1000 m around Mont Blanc")
        assertTrue(maxAlt < 5500.0, "max altitude $maxAlt should be < 5500 m around Mont Blanc")
    }
}
```

⚠ **Vérifier que le constructeur `ElevationProvider()` est public sans arguments** dans le module. Si ce n'est pas le cas (e.g. il prend un `ElevationProviderConfig`), adapter :

```kotlin
val provider = ElevationProvider(ElevationProviderConfig())
```

### 4. Test pipeline `enhance` Node avec fixElevation — `EnhanceWithElevationNodeTest.kt`

Créer `engine/src/jsTest/kotlin/io/github/glandais/engine/EnhanceWithElevationNodeTest.kt` :

```kotlin
package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class EnhanceWithElevationNodeTest {
    @Test
    fun enhanceWithFixElevationOnAlpinePathProducesCorrectedElevations() = runTest {
        if (!integrationEnabled()) return@runTest

        // Tiny Alpine GPX near Mont Blanc — 3 waypoints, no elevation set, so
        // fixElevation will fill them all from the DEM.
        val gpx = """<?xml version="1.0" encoding="UTF-8"?>
            |<gpx version="1.1" creator="vcyclist-test"
            |     xmlns="http://www.topografix.com/GPX/1/1">
            |  <trk><trkseg>
            |    <trkpt lat="45.8326" lon="6.8652"><time>2024-01-01T00:00:00Z</time></trkpt>
            |    <trkpt lat="45.8350" lon="6.8700"><time>2024-01-01T00:00:30Z</time></trkpt>
            |    <trkpt lat="45.8380" lon="6.8750"><time>2024-01-01T00:01:00Z</time></trkpt>
            |  </trkseg></trk>
            |</gpx>
        """.trimMargin()

        val path = parseGpx(gpx)
        val options = js("({ fixElevation: true })").unsafeCast<EnhanceOptionsDto>()
        val out = enhance(path, options).await()

        assertTrue(pathSize(out) > 0, "enhanced path should not be empty")
        // After fixElevation against Mont Blanc DEM, all elevations must be plausible:
        // the path crosses 4000-4800 m range. Spot-check first/middle/last.
        val first = pointAt(out, 0)
        val last = pointAt(out, pathSize(out) - 1)
        assertTrue(first.elevation > 1500.0, "first elevation ${first.elevation} > 1500 m")
        assertTrue(first.elevation < 5000.0, "first elevation ${first.elevation} < 5000 m")
        assertTrue(last.elevation > 1500.0, "last elevation ${last.elevation} > 1500 m")
        assertTrue(last.elevation < 5000.0, "last elevation ${last.elevation} < 5000 m")
    }
}

private fun integrationEnabled(): Boolean = js(
    "typeof process !== 'undefined' && process.env && process.env.INTEGRATION === '1'",
) as Boolean
```

Note : on duplique le helper `integrationEnabled()` localement plutôt que d'exposer celui de `:elevation:jsTest` (les test source sets ne se voient pas entre modules). Garder le source local trivial.

### 5. Propager `INTEGRATION` à `jsNodeTest`

Gradle ne propage **pas** automatiquement les env vars du shell aux Node test runs. Ajouter à `elevation/build.gradle.kts` et `engine/build.gradle.kts`, après le bloc `kotlin { ... }` :

```kotlin
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment(
        "INTEGRATION",
        providers.environmentVariable("INTEGRATION").orElse("").get(),
    )
}
```

Cela rend `process.env.INTEGRATION` accessible dans le runtime Node de Kotlin/JS test.

### 6. Vérification sans `INTEGRATION` (CI standard)

```bash
./gradlew :elevation:jsNodeTest :engine:jsNodeTest
```

Les 6 nouveaux tests doivent **skip silencieusement** (`runTest { if (!integrationEnabled()) return@runTest }`). Cela vérifie au moins que :

- La compilation passe (imports OK, signatures OK).
- Le runtime Node ne crashe pas en chargeant `@jsquash/webp` (lazy require, non chargé tant que pas appelé).
- Les autres tests (`EngineJsApiTest`) restent verts.

### 7. Vérification avec `INTEGRATION=1` (network requis)

```bash
INTEGRATION=1 ./gradlew :elevation:jsNodeTest --rerun-tasks
INTEGRATION=1 ./gradlew :engine:jsNodeTest --rerun-tasks
```

Les 6 tests doivent passer (4 ElevationProvider + 1 TileFetcher + 1 EnhanceWithElevation).

Si le réseau n'est pas disponible (e.g. CI offline) :

- Documenter dans le commit que la vérification `INTEGRATION=1` n'a pas pu être exécutée localement.
- Les tests skip silencieusement par défaut, donc CI standard n'est pas cassé.

### 8. Régression navigateur

```bash
./gradlew :elevation:jsBrowserTest :engine:jsBrowserTest
./gradlew :elevation:wasmJsBrowserTest :engine:wasmJsBrowserTest
```

Doit rester vert. `integrationEnabled()` retourne `false` en environnement navigateur (pas de `process`), donc les tests skip aussi côté `jsBrowserTest`.

## Outputs

Créés :

- `elevation/src/jsTest/kotlin/io/github/glandais/elevation/NodeIntegrationGate.kt`
- `elevation/src/jsTest/kotlin/io/github/glandais/elevation/TileFetcherNodeIntegrationTest.kt`
- `elevation/src/jsTest/kotlin/io/github/glandais/elevation/ElevationProviderNodeIntegrationTest.kt`
- `engine/src/jsTest/kotlin/io/github/glandais/engine/EnhanceWithElevationNodeTest.kt`

Modifiés :

- `elevation/build.gradle.kts` (env propagation `INTEGRATION` aux KotlinJsTest tasks)
- `engine/build.gradle.kts` (idem)

## Validation

```bash
./gradlew :elevation:jsNodeTest :engine:jsNodeTest
./gradlew :elevation:jsBrowserTest :engine:jsBrowserTest
./gradlew :elevation:wasmJsBrowserTest :engine:wasmJsBrowserTest
./gradlew :elevation:jvmTest :engine:jvmTest
./gradlew ktlintCheck
```

Critères :

- Sans `INTEGRATION=1` : **tous les tests verts**, les 6 nouveaux skip silencieusement.
- Avec `INTEGRATION=1` (si réseau dispo) : les 6 nouveaux tests Node passent (Mont Blanc 4805 ± 50, Dead Sea −430 ± 50, etc.).
- Navigateur (jsBrowserTest, wasmJsBrowserTest) reste vert (pas de régression de task 32).
- JVM reste vert (non touché).
- `ktlintCheck` vert.

## Done when

- [x] `NodeIntegrationGate.kt` créé dans `elevation/src/jsTest`
- [x] `TileFetcherNodeIntegrationTest.kt` créé (1 test, gated) — vérifie la chaîne `globalThis.fetch` → `@jsquash/webp` → RGBA jusqu'à un pixel Terrarium plausible
- [x] `ElevationProviderNodeIntegrationTest.kt` créé (4 tests, gated) — Mont Blanc 4805±50 m, Dead Sea −430±50 m, Death Valley −85±50 m, `getElevationsAlong` autour du Mont Blanc
- [x] `EnhanceWithElevationNodeTest.kt` créé dans `engine/src/jsTest` (1 test, gated) — pipeline complet `parseGpx` → `enhance(fixElevation=true)` → assert altitudes Alpines
- [x] Env `INTEGRATION` propagée dans les `KotlinJsTest` tasks de `:elevation` et `:engine`
- [x] Mocha `timeout = "30s"` configuré sur `jsNodeTest` de `:elevation` et `:engine` (la default 2 s tuait le premier fetch + compile WASM à froid)
- [x] **Bug task 32 corrigé** : `TileFetcher.js.kt` Node path utilisait `'@jsquash/webp/decode/index.js'` (qui n'existe pas) ; corrigé en `'@jsquash/webp/decode.js'`
- [x] **Bug task 32 corrigé** : l'init Emscripten de `@jsquash/webp` essaie de fetcher son `.wasm` via `file://` (non supporté par Node fetch). Ajout d'un init manuel `fs.readFileSync(wasmPath) → WebAssembly.compile → decodeMod.init(wasmModule)` cachée derrière un `Promise<dynamic> by lazy` pour amortir le coût WASM-compile au premier appel et caché aux appels suivants.
- [x] **Bug task 27/28 corrigé** : `EngineJsApi.enhance` (JS + Wasm) hardcodait `elevationProvider = null` — quand l'appelant demandait `fixElevation: true`, le step était silencieusement skip. Désormais : auto-instancie un `ElevationProvider()` par défaut quand `opts.fixElevation` est true, sinon `null`. Commentaire KDoc de `defaultJsOptions` mis à jour.
- [x] `:elevation:jsNodeTest` + `:engine:jsNodeTest` verts sans `INTEGRATION` (skips silencieux)
- [x] `:elevation:jsNodeTest` + `:engine:jsNodeTest` verts **avec `INTEGRATION=1`** (les 6 tests passent — Mont Blanc 4805±50, Dead Sea −430±50, Death Valley −85±50, getElevationsAlong densifié, TileFetcher Mont Blanc tile décodée à 512×512 RGBA, EnhanceWithElevation Alpine path corrigée)
- [x] `:elevation:jsBrowserTest` + `:engine:jsBrowserTest` verts (régression OK)
- [x] `:elevation:wasmJsBrowserTest` + `:engine:wasmJsBrowserTest` verts (régression OK)
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **Skip silencieux vs `assumeTrue` / `Ignore`** : `kotlin.test` n'a pas d'équivalent JUnit `Assume.assumeTrue` portable. Le pattern `if (!integrationEnabled()) return@runTest` est utilisé partout dans le module (cf. JVM `ElevationProviderIntegrationTest`). Le test apparaît "passed" dans le rapport, ce qui est cohérent avec la convention du projet.
- **Coordonnées tuiles Mont Blanc** : zoom 12, x=2138, y=1466 → URL `https://tiles.mapterhorn.com/12/2138/1466.webp`. Si l'URL change ou que mapterhorn passe sur un autre CDN, mettre à jour la constante.
- **Tolérance ±50 m** : conservatrice. La résolution DEM Terrarium au zoom 12 est ~10 m/pixel à 45° de latitude ; mais l'altitude Mont Blanc varie entre les sources (4805 IGN, 4807 alpins, 4810 hiver). Tolérance large pour stabilité multi-source.
- **Pourquoi pas de test parity numérique JVM↔Node** : trop fragile sur les bord ULP (`createImageBitmap` browser vs `@jsquash/webp` WASM décodent les mêmes pixels mais les conversions ARGB/RGBA peuvent varier d'1 LSB sur la chaîne R/G/B → ±0.004 m sur l'altitude reconstituée). Le test "± 50 m vs valeur connue" est plus robuste et c'est ce que fait le JVM aussi.
- **Bun** : Bun supporte `process.env`, `globalThis.fetch`, et `require('@jsquash/webp')`. Les tests Node sont compatibles Bun "as-is". Vérification manuelle hors-CI : `INTEGRATION=1 bun run engine/build/.../productionExecutable/.../EngineCli.mjs` — hors scope formel mais documenté.
