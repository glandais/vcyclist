# 08 — Elevation : ElevationCalculator + Flux + BatchCalculator + ElevationProvider

## Goal

Finaliser le module `:elevation` en exposant son **API publique** :

1. **`ElevationCalculator`** (déplacé depuis la tâche 05) : récupère l'altitude d'un point WGS84 via `TileManager`. Deux modes : pixel-le-plus-proche (`interpolation = false`) ou **interpolation bilinéaire** à partir de 4 pixels voisins (avec gestion des débordements de tuile via `normalizePixel`).
2. **`Flux.forEachParallel`** : limite de concurrence pour les fetchs (équivalent du `Flux` TS basé sur `Promise.race` → en Kotlin, `Semaphore` + `coroutineScope { async + awaitAll }`).
3. **`BatchCalculator`** : pipeline en deux passes :
   - `setElevations(coords, zoom, interpolation)` : groupe les points par tuile, fetch en parallèle (max 10), renvoie une nouvelle liste avec `elevation` renseigné.
   - `getElevationsAlong(path, zoom, step, minDistance, interpolation, smoothing?, filtering?)` : génère des waypoints intermédiaires (haversine + interpolation lat/lon), `setElevations`, lissage optionnel (tâche 04), filtrage Douglas-Peucker optionnel (tâche 03).
4. **`ElevationProviderConfig`** : data class avec defaults (zoom 12, cacheSize 100, urlTemplate mapterhorn, tileSize 512, attribution).
5. **`ElevationProvider`** : façade publique — `getElevation`, `setElevations`, `getElevationsAlong`. Validation des paramètres dans `init`.
6. Helper `toPixelFloat` ajouté à `ElevationFunctions` pour exposer la position sous-pixel (corrige une dégénérescence de l'impl TS où `dx = dy = 0` toujours, cf. note ci-dessous).

C'est la tâche la **plus volumineuse** du module — prévoir 2 ou 3 commits intermédiaires (`feat(elevation): ElevationCalculator + Flux`, `feat(elevation): BatchCalculator`, `feat(elevation): ElevationProvider public API`).

## Depends on

- `02-elevation-distance-ecef` (`Distance.haversine`)
- `03-elevation-douglas-peucker` (`DouglasPeucker.simplify`)
- `04-elevation-smoother` (`ElevationSmoother.smooth`)
- `05-elevation-tile-types-decoding` (`Pixel`, `Tile`, `ElevationFunctions.toPixel`, `normalizePixel`)
- `07-elevation-tile-cache` (`TileManager.getTile`)

## Inputs

Sources à porter (signatures et logique fidèles, **sauf le bug d'interpolation** documenté ci-dessous) :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/calculator/ElevationCalculator.ts`
- `/home/glandais/code/perso/vcyclist-all/elevation/src/calculator/Reactive.ts` (`Flux.forEach`)
- `/home/glandais/code/perso/vcyclist-all/elevation/src/calculator/BatchCalculator.ts`
- `/home/glandais/code/perso/vcyclist-all/elevation/src/ElevationProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/elevation/test/calculator/ElevationCalculator.test.ts` (port partiel — voir §5)
- `/home/glandais/code/perso/vcyclist-all/elevation/test/calculator/BatchCalculator.test.ts` (port partiel)
- `/home/glandais/code/perso/vcyclist-all/elevation/test/ElevationProvider.test.ts` (port partiel — focus validation config + smoke fetch)

## Steps

### 1. Ajouter `toPixelFloat` à `ElevationFunctions.kt`

Le port TS de `ElevationCalculator` souffre d'une **dégénérescence** : il appelle `toPixel(...)` qui retourne `x/y` en `Int`, puis fait `dx = x - floor(x)` → toujours `0`. L'interpolation bilinéaire est donc équivalente à un nearest-neighbor en pratique. **On corrige côté Kotlin.**

Ajouter à `ElevationFunctions.kt` (extension du fichier existant) :

```kotlin
import kotlin.math.floor

/** Pixel with sub-pixel resolution, used for bilinear interpolation. */
data class PixelFloat(val tile: TileCoordinates, val x: Double, val y: Double)

object ElevationFunctions {
    // ... existant inchangé ...

    /** Like [toPixel] but keeps the fractional pixel position for bilinear interpolation. */
    fun toPixelFloat(coords: Coordinates, z: Int, tileSize: Int): PixelFloat {
        val tile = toTileCoordinatesFloat(coords, z)
        val px = (tile.xFloat - tile.x) * tileSize
        val py = (tile.yFloat - tile.y) * tileSize
        return PixelFloat(TileCoordinates(tile.x, tile.y, z), px, py)
    }
}
```

`PixelFloat` peut vivre dans `Tiles.kt` ou `ElevationFunctions.kt` — choix : `Tiles.kt` (avec les autres types de tuile).

### 2. `Flux.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Flux.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

object Flux {

    /**
     * Apply [action] to each item in [items], with at most [maxParallel] concurrent invocations.
     * If any invocation throws, the scope is cancelled and the first error is rethrown.
     *
     * Defaults : [maxParallel] = 1 (sequential).
     */
    suspend fun <T> forEachParallel(
        items: Iterable<T>,
        maxParallel: Int = 1,
        action: suspend (T) -> Unit,
    ) {
        require(maxParallel >= 1) { "maxParallel must be >= 1, got $maxParallel" }
        coroutineScope {
            val semaphore = Semaphore(maxParallel)
            items.map { item ->
                async {
                    semaphore.withPermit { action(item) }
                }
            }.awaitAll()
        }
    }
}
```

**Note** : remplace `Flux.forEach` du TS qui utilisait `Promise.race`. Le `Semaphore` Kotlin est strictement plus simple et plus performant (pas de course en `O(n)` à chaque itération).

### 3. `ElevationCalculator.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationCalculator.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.floor

class ElevationCalculator(
    private val tileManager: TileManager,
    private val tileSize: Int = 256,
) {

    /**
     * Get elevation at [coords] at the given [zoomLevel]. If [interpolation] is true,
     * uses bilinear interpolation from 4 neighbour pixels ; otherwise, returns the elevation
     * of the closest pixel.
     */
    suspend fun getElevation(
        coords: Coordinates,
        zoomLevel: Int,
        interpolation: Boolean = true,
    ): Double = try {
        if (interpolation) {
            getInterpolatedElevation(coords, zoomLevel)
        } else {
            val pixel = ElevationFunctions.toPixel(coords, zoomLevel, tileSize)
            elevationFromPixel(pixel)
        }
    } catch (t: Throwable) {
        throw IllegalStateException("Failed to get elevation: ${t.message}", t)
    }

    private suspend fun getInterpolatedElevation(coords: Coordinates, zoomLevel: Int): Double {
        val pf = ElevationFunctions.toPixelFloat(coords, zoomLevel, tileSize)
        val x0i = floor(pf.x).toInt()
        val y0i = floor(pf.y).toInt()
        val x1i = x0i + 1
        val y1i = y0i + 1
        val dx = pf.x - x0i
        val dy = pf.y - y0i

        val p00 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x0i, y0i), tileSize))
        val p10 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x1i, y0i), tileSize))
        val p01 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x0i, y1i), tileSize))
        val p11 = elevationFromPixel(ElevationFunctions.normalizePixel(Pixel(pf.tile, x1i, y1i), tileSize))

        val top = p00 * (1.0 - dx) + p10 * dx
        val bottom = p01 * (1.0 - dx) + p11 * dx
        return top * (1.0 - dy) + bottom * dy
    }

    private suspend fun elevationFromPixel(pixel: Pixel): Double =
        tileManager.getTile(pixel.tile).getElevation(pixel)
}
```

**Note bilinéaire** : on utilise `PixelFloat` (sous-pixel) pour calculer `dx, dy` réels. La sortie est bien une moyenne pondérée des 4 pixels voisins. Tests #3-4 de §5 verrouillent ce comportement.

### 4. `BatchCalculator.kt`

Plus volumineux. Voici le squelette (à compléter avec les commentaires d'origine TS) :

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/BatchCalculator.kt` :

```kotlin
package io.github.glandais.elevation

class BatchCalculator(private val calculator: ElevationCalculator) {

    /**
     * Compute elevations for all [coordinates] in parallel, grouped by tile to maximize cache reuse.
     * Returns a new list of [CoordinatesElevation] preserving the original order.
     */
    suspend fun setElevations(
        coordinates: List<Coordinates>,
        zoomLevel: Int,
        interpolation: Boolean,
        maxParallelTiles: Int = 10,
    ): List<CoordinatesElevation> {
        val results = arrayOfNulls<CoordinatesElevation>(coordinates.size)

        // Group indices by tile to keep tile fetches grouped (cache-friendly)
        val byTile: MutableMap<String, MutableList<Int>> = LinkedHashMap()
        for ((i, p) in coordinates.withIndex()) {
            val tile = ElevationFunctions.toTileCoordinates(p, zoomLevel)
            val key = "${tile.z}/${tile.x}/${tile.y}"
            byTile.getOrPut(key) { mutableListOf() }.add(i)
        }

        Flux.forEachParallel(byTile.entries, maxParallelTiles) { (_, indices) ->
            for (i in indices) {
                val coord = coordinates[i]
                val ele = calculator.getElevation(coord, zoomLevel, interpolation)
                results[i] = LatLonElevation(coord.latitude, coord.longitude, ele)
            }
        }

        @Suppress("UNCHECKED_CAST")
        return results.toList() as List<CoordinatesElevation>
    }

    /**
     * Compute elevations along a path defined by [path] waypoints.
     *
     * - Densifies the path with intermediate points every [step] meters (linear lat/lon interpolation).
     * - Skips path segments shorter than [minDistance] meters.
     * - Throws if [path.size] < 2 or [step] <= 1.
     *
     * Optional post-processing :
     * - if [smoothingOptions]?.enabled, applies [ElevationSmoother.smooth].
     * - if [filterOptions]?.enabled and >= 3 points, applies [DouglasPeucker.simplify].
     */
    suspend fun getElevationsAlong(
        path: List<Coordinates>,
        zoomLevel: Int,
        step: Double = 10.0,
        minDistance: Double = 1.0,
        interpolation: Boolean = true,
        smoothingOptions: SmoothingOptions? = null,
        filterOptions: FilterOptions? = null,
    ): List<CoordinatesElevation> {
        require(path.size >= 2) { "Path must contain at least 2 coordinates" }
        require(step > 1.0) { "Step is too small: $step meters" }

        val densified = generateCoordinatesAlong(path, step, minDistance)
        var withElevation = setElevations(densified, zoomLevel, interpolation)

        if (smoothingOptions?.enabled == true && withElevation.size >= 3) {
            withElevation = ElevationSmoother.smooth(withElevation, smoothingOptions.windowSize ?: 50.0)
        }
        if (filterOptions?.enabled == true && withElevation.size > 2) {
            withElevation = DouglasPeucker.simplify(
                withElevation,
                filterOptions.tolerance ?: 10.0,
                filterOptions.zExaggeration ?: 3.0,
            )
        }
        return withElevation
    }

    // ----- internal helpers ----------------------------------------------------

    private fun generateCoordinatesAlong(
        path: List<Coordinates>,
        step: Double,
        minDistance: Double,
    ): List<Coordinates> {
        if (path.isEmpty()) return emptyList()
        val out = ArrayList<Coordinates>(path.size * 2)
        out += path[0]

        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val d = Distance.haversine(a, b)
            if (d < minDistance) continue
            val between = generateCoordinatesBetween(a, b, step, d)
            // skip first of `between` (duplicate of previous segment's end)
            for (j in 1 until between.size) out += between[j]
        }
        return out
    }

    private fun generateCoordinatesBetween(
        a: Coordinates,
        b: Coordinates,
        step: Double,
        distance: Double,
    ): List<Coordinates> {
        if (distance <= step) return listOf(a, b)
        val numSteps = (distance / step).toInt()
        val out = ArrayList<Coordinates>(numSteps + 2)
        out += a
        val latDiff = b.latitude - a.latitude
        val lonDiff = b.longitude - a.longitude
        for (i in 1..numSteps) {
            val f = (i * step) / distance
            out += LatLon(a.latitude + latDiff * f, a.longitude + lonDiff * f)
        }
        out += b
        return out
    }
}

// Data classes for options (mirrors the TS interfaces FilterOptions / SmoothingOptions)
data class SmoothingOptions(
    val windowSize: Double? = 50.0,
    val enabled: Boolean = false,
)

data class FilterOptions(
    val tolerance: Double? = 10.0,
    val zExaggeration: Double? = 3.0,
    val enabled: Boolean = false,
)
```

**Notes** :
- `setElevations` mutait l'input côté TS ; ici on **renvoie** une nouvelle liste (immutabilité Kotlin). Les appelants doivent capturer le résultat.
- `LinkedHashMap` pour `byTile` préserve l'ordre de première rencontre (≈ ordre des tuiles dans le path), ce qui aide le cache.
- `SmoothingOptions`/`FilterOptions` sont des data classes simples (équivalents idiomatiques des interfaces TS).

### 5. `ElevationProviderConfig.kt` et `Attribution.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationProviderConfig.kt` :

```kotlin
package io.github.glandais.elevation

data class Attribution(val text: String, val url: String? = null)

data class ElevationProviderConfig(
    val zoomLevel: Int = 12,
    val cacheSize: Int = 100,
    val tileUrlTemplate: String = "https://tiles.mapterhorn.com/{z}/{x}/{y}.webp",
    val tileSize: Int = 512,
    val attribution: Attribution = Attribution(
        text = "Mapterhorn elevation data. See mapterhorn.com/attribution/ for details.",
        url = "https://mapterhorn.com/attribution/",
    ),
)
```

### 6. `ElevationProvider.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationProvider.kt` :

```kotlin
package io.github.glandais.elevation

class ElevationProvider(
    val config: ElevationProviderConfig = ElevationProviderConfig(),
    fetcher: suspend (String) -> RawTile = ::fetchAndDecodeTile,
) {
    init {
        require(config.zoomLevel in 0..15) {
            "Invalid zoom level: ${config.zoomLevel}. Must be an integer between 0 and 15"
        }
        require(config.cacheSize > 0) {
            "Invalid cache size: ${config.cacheSize}. Must be a positive integer"
        }
        require(config.tileSize > 0 && (config.tileSize and (config.tileSize - 1)) == 0) {
            "Invalid tile size: ${config.tileSize}. Must be a positive power of 2"
        }
    }

    private val tileManager = TileManager(config.tileUrlTemplate, config.cacheSize, fetcher)
    private val calculator = ElevationCalculator(tileManager, config.tileSize)
    private val batchCalculator = BatchCalculator(calculator)

    val attribution: Attribution get() = config.attribution

    suspend fun getElevation(latitude: Double, longitude: Double, interpolation: Boolean = true): Double =
        calculator.getElevation(LatLon(latitude, longitude), config.zoomLevel, interpolation)

    suspend fun setElevations(
        coordinates: List<Coordinates>,
        interpolation: Boolean = true,
    ): List<CoordinatesElevation> =
        batchCalculator.setElevations(coordinates, config.zoomLevel, interpolation)

    suspend fun getElevationsAlong(
        path: List<Coordinates>,
        step: Double = 10.0,
        minDistance: Double = 1.0,
        interpolation: Boolean = true,
        smoothingOptions: SmoothingOptions? = null,
        filterOptions: FilterOptions? = null,
    ): List<CoordinatesElevation> = batchCalculator.getElevationsAlong(
        path, config.zoomLevel, step, minDistance, interpolation, smoothingOptions, filterOptions,
    )
}
```

**Note `fetcher` injectable** : `ElevationProvider` accepte un `fetcher` custom. Pour la production : `::fetchAndDecodeTile` (par défaut). Pour les tests : un fetcher in-memory.

### 7. Tests

#### `FluxTest.kt`

- `forEachParallel` avec maxParallel=1 : items traités séquentiellement, vérifier ordre temporel.
- maxParallel=3 + 9 items qui s'attendent sur un gate : exactement 3 en parallèle au début.
- Exception dans une action propage et annule.
- `maxParallel = 0` → `IllegalArgumentException`.

#### `ElevationCalculatorTest.kt`

Fournir un `TileManager` mocké via fetcher injectable qui retourne une `RawTile` 256×256 avec des élévations connues.

Cas :
- `getElevation(coords, z, interpolation=false)` → pixel-closest correct.
- `getElevation(coords, z, interpolation=true)` → moyenne bilinéaire (4 pixels neighbors).
- Cas où `dx=0.5, dy=0.5` → moyenne arithmétique des 4 pixels.
- Cas d'overflow `x1 >= tileSize` → `normalizePixel` redirige vers la tuile voisine.
- Exception interne wrappée en `IllegalStateException` avec message `"Failed to get elevation: ..."`.

#### `BatchCalculatorTest.kt`

Cas :
- `setElevations` sur 5 points dans la même tuile : 1 fetch tuile, 5 élévations.
- `setElevations` sur 5 points dans 5 tuiles différentes : 5 fetches.
- `setElevations` préserve l'ordre.
- `getElevationsAlong` rejette `path.size < 2` (message exact `"Path must contain at least 2 coordinates"`).
- `getElevationsAlong` rejette `step <= 1` (message `"Step is too small: 1.0 meters"`).
- `getElevationsAlong` skip les segments < `minDistance`.
- `getElevationsAlong` applique smoothing si `enabled=true`.
- `getElevationsAlong` applique filtering si `enabled=true`.
- `getElevationsAlong` chaîne smoothing + filtering.
- `generateCoordinatesBetween(a, b, step)` avec `distance < step` → `[a, b]`.
- `generateCoordinatesBetween(a, b, step=10, distance=25)` → 4 points (`a`, 1×step, 2×step, `b`).

#### `ElevationProviderTest.kt`

Cas :
- Validation construction : zoom -1, zoom 16, cacheSize 0, tileSize 7 (non power of 2) → exceptions messages exacts.
- Construction par défaut : `attribution.text` contient "mapterhorn".
- Smoke test : `getElevation(0, 0)` avec fetcher mocké → valeur attendue.
- Smoke test `setElevations` (3 points).
- `getElevationsAlong` end-to-end avec smoothing + filtering.

### 8. Vérification ktlint

Lourd en code — lancer `./gradlew ktlintFormat` après la rédaction.

## Outputs (fichiers attendus)

Créés (commonMain) :

- `Flux.kt`
- `ElevationCalculator.kt`
- `BatchCalculator.kt`
- `ElevationProviderConfig.kt` (contient aussi `Attribution`)
- `ElevationProvider.kt`

Modifiés :

- `Tiles.kt` ou `ElevationFunctions.kt` : ajout `PixelFloat` + `toPixelFloat`.

Tests (commonTest) :

- `FluxTest.kt`
- `ElevationCalculatorTest.kt`
- `BatchCalculatorTest.kt`
- `ElevationProviderTest.kt`

## Validation

```bash
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :elevation:build
./gradlew :engine:allTests       # non-régression
```

Critères :

- ≥ 4 tests `FluxTest`, ≥ 5 `ElevationCalculatorTest`, ≥ 11 `BatchCalculatorTest`, ≥ 5 `ElevationProviderTest`.
- Cumul `:elevation` : ≥ 19 classes, ≥ 180 tests par target.
- `ktlintCheck` vert.
- Pas d'appel HTTP réel pendant `:elevation:allTests` (le fetcher injectable garantit le local).
- Aucune dépendance circulaire entre modules : `ElevationProvider` est le point d'entrée, tout le reste est privé/internal côté API.

## Done when

- [ ] `Flux.kt` créé (+ FluxTest avec déduplication, exception, validation maxParallel)
- [ ] `ElevationCalculator.kt` créé avec **vraie interpolation bilinéaire** (corrige le bug TS où `dx=dy=0`)
- [ ] `toPixelFloat` + `PixelFloat` ajoutés à `ElevationFunctions/Tiles`
- [ ] `BatchCalculator.kt` créé (setElevations + getElevationsAlong + helpers privés)
- [ ] `ElevationProviderConfig.kt` + `Attribution`
- [ ] `ElevationProvider.kt` (façade publique + validations dans `init`)
- [ ] Tests verts sur les 3 targets : `FluxTest` (≥ 4), `ElevationCalculatorTest` (≥ 5), `BatchCalculatorTest` (≥ 11), `ElevationProviderTest` (≥ 5)
- [ ] Messages d'exception conformes : `"Path must contain at least 2 coordinates"`, `"Step is too small: ... meters"`, `"Invalid zoom level: ..."`, `"Invalid cache size: ..."`, `"Invalid tile size: ..."`
- [ ] `ktlintCheck` vert ; `:engine:allTests` toujours vert
- [ ] **Critère de fin Phase 1** : `:elevation:allTests` vert sur JVM + JS + Wasm, coverage ≥ 80 %, le module est utilisable comme dépendance
- [ ] Toutes les checkboxes cochées

## Notes

- **Bug TS d'interpolation** : `ElevationCalculator.ts` utilise `toPixel` qui retourne `Int x, y`, puis fait `dx = x - floor(x) = 0`. La bilinéaire dégénère en nearest-neighbor du pixel 00. **On corrige côté Kotlin** via `toPixelFloat`. Cela peut entraîner de légers écarts entre la sortie Kotlin et la sortie TS sur les tests d'intégration (tâche 09). Décision documentée — si la parité TS doit être exacte, on ajoutera un mode `legacyInterpolation: Boolean` plus tard.
- **`Flux.forEachParallel` vs `flatMapMerge`** : on aurait pu utiliser `kotlinx.coroutines.flow.flatMapMerge(concurrency = N)`. Inconvénient : nécessite un `Flow<T>` plutôt qu'`Iterable<T>` (overhead pour les use-cases simples). Le `Semaphore` reste plus direct.
- **`setElevations` retourne une nouvelle liste** : changement par rapport au TS (qui mutait `point.elevation`). Imposé par l'immutabilité des `data class` Kotlin. Les appelants chaînent désormais : `val withElev = batchCalc.setElevations(coords, z, true)`.
- **`maxParallelTiles = 10`** : valeur du TS, donné en paramètre par défaut, surchargeable. Aligné avec la `cacheSize` par défaut de 100 pour ne pas saturer.
- **Tests `ElevationCalculator` avec fetcher in-memory** : créer un `TileManager(urlTemplate = "test://{z}/{x}/{y}", cacheSize = 16, fetcher = { url -> tileFromUrl(url) })` où `tileFromUrl` renvoie une `RawTile` synthétique. Plus simple qu'un mock framework.
- **`ElevationFunctions.toPixelFloat`** : la position sous-pixel peut être négative ou ≥ tileSize si lat/lon est sur la frontière d'une tuile. `normalizePixel` couvre ce cas (déjà testé en tâche 05).
- **`ElevationProvider` smoke test sans HTTP** : injecter un fetcher qui retourne une `RawTile` triviale avec des pixels Terrarium décodant à des altitudes connues. Le test vérifie l'orchestration complète, pas le réseau.
- **Validation `tileSize` puissance de 2** : `(n & (n - 1)) == 0` est le test idiomatique. Couvert par un test paramétré sur quelques valeurs (`128`, `256`, `512` valides ; `7`, `0`, `-1` invalides).
- **Pas de `Reactive` exposée publiquement** : `Flux` est `object` dans le package mais pas (encore) exporté via une API publique. À introduire dans la tâche 28 (JS/Wasm API) si un consommateur en a besoin.
- **Coverage 80 %** : le code reste linéaire (pas de branches complexes hors validations). Coverage devrait dépasser 90 % naturellement après les tests proposés.
- **Cette tâche conclut la Phase 1**. Pour la Phase 2 (`:engine`), `ElevationProvider` sera consommé comme dépendance via `api(project(":elevation"))` (activation du commentaire dans `engine/build.gradle.kts` — cf. tâche 00).
- **Décomposition des commits** : recommandation :
  1. `feat(elevation): Flux + ElevationCalculator (bilinear)` (sources + tests Flux + ElevationCalculator)
  2. `feat(elevation): BatchCalculator + path densification` (sources + tests)
  3. `feat(elevation): ElevationProvider public API + config` (façade + validations + smoke)
