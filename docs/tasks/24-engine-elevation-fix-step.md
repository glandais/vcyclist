# 24 — Engine : `ElevationStep` (fix + smooth elevation via `:elevation`)

## Goal

Pont entre le module `:elevation` (`ElevationProvider` + `ElevationSmoother`) et le module `:engine` (`Path`). Deux opérations :

1. **`fixElevation(path, provider)`** : pour chaque point du path, remplacer `elevation(i)` par la valeur fournie par `ElevationProvider.setElevations(...)` (qui fetche des tuiles Terrarium). Async (`suspend`).
2. **`smoothElevation(path)`** : applique `ElevationSmoother.smooth` avec une fenêtre de **150 m** (constante TS). Synchrone.

Les deux retournent un **nouveau** `Path` (avec tous les slots copiés depuis l'input, sauf `elevation` qui est mis à jour), puis appellent `path.computeDerivedData()`.

## Depends on

- `12-engine-path` (`Path`, `coordinatesElevationSequence`, slots)
- `:elevation.ElevationProvider`, `:elevation.ElevationSmoother`, `:elevation.LatLon`, `:elevation.LatLonElevation`

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/elevation/Elevation.ts` (canonique)

## Steps

### 1. `ElevationStep.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/ElevationStep.kt` :

```kotlin
package io.github.glandais.engine.path

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationSmoother
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.MathConstants

/**
 * Two helpers that bridge the `:elevation` module to the engine's [Path] :
 *
 * - [fixElevation] : pulls corrected altitudes from an [ElevationProvider] (Terrarium tiles).
 *   Async — needs network if a real provider is used.
 * - [smoothElevation] : runs the triangular-kernel smoother over the path with a 150 m window.
 *   Synchronous.
 *
 * Both return a fresh [Path] preserving every other slot and call [Path.computeDerivedData] at
 * the end. Mirrors `elevation/Elevation.ts`.
 */
object ElevationStep {

    /** Default smoothing window (meters). Matches `Elevation.ts` (`150`). */
    const val DEFAULT_SMOOTH_WINDOW_M: Double = 150.0

    /** Fetch corrected elevations for every point of [source] and return a fresh path. */
    suspend fun fixElevation(source: Path, provider: ElevationProvider): Path {
        if (source.size == 0) return Path(0)
        val coords = List(source.size) {
            LatLon(
                latitude = source.latitude(it) * MathConstants.RAD_TO_DEG,
                longitude = source.longitude(it) * MathConstants.RAD_TO_DEG,
            )
        }
        val corrected = provider.setElevations(coords)
        val out = copyAllSlots(source)
        for (i in 0 until out.size) {
            out.setElevation(i, corrected[i].elevation)
        }
        out.computeDerivedData()
        return out
    }

    /** Apply the triangular-kernel smoother (window [windowM]) and return a fresh path. */
    fun smoothElevation(source: Path, windowM: Double = DEFAULT_SMOOTH_WINDOW_M): Path {
        if (source.size == 0) return Path(0)
        val coords = List(source.size) { i -> source.coordinatesElevationAt(i) }
        val smoothed = ElevationSmoother.smooth(coords, windowM)
        val out = copyAllSlots(source)
        for (i in 0 until out.size) {
            out.setElevation(i, smoothed[i].elevation)
        }
        out.computeDerivedData()
        return out
    }

    private fun copyAllSlots(source: Path): Path {
        val out = Path(source.size)
        for (i in 0 until source.size) {
            for (field in PointField.entries) {
                out.set(i, field, source.get(i, field))
            }
        }
        return out
    }
}
```

### 2. Tests `ElevationStepTest.kt`

Cas à couvrir (≥ 9) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `fixElevation` sur path vide → output vide | exact |
| 2 | `fixElevation` avec provider mock (renvoie elevation=valeur incrémentale `100 + i`) → elevations remplacées | propriété |
| 3 | `fixElevation` préserve lat/lon/time/autres slots | propriété |
| 4 | `fixElevation` appelle `computeDerivedData` (distance recalculée) | propriété |
| 5 | `smoothElevation` sur path vide → output vide | exact |
| 6 | `smoothElevation` sur path < 3 points → output identique (cf. `ElevationSmoother`) | propriété |
| 7 | `smoothElevation` sur path avec spike (alt = 100, 200, 100) → spike réduit | propriété |
| 8 | `smoothElevation` préserve lat/lon/time | propriété |
| 9 | `smoothElevation` avec windowM customisé | propriété |
| 10 | Default windowM == 150.0 | sentinel |

Mock provider helper :
```kotlin
private fun mockProvider(setElevationsImpl: suspend (List<Coordinates>) -> List<CoordinatesElevation>): ElevationProvider =
    // Soit un object anonyme implémentant ElevationProvider, soit un test double construit via
    // injection du `fetcher` puis stubbing complet. Vérifier la surface API actuelle.
```

⚠ **Important** : `ElevationProvider` est une `class` avec init validations. Pour mocker proprement, le mieux est d'utiliser **un fetcher injectable** qui retourne une `RawTile` synthétique → l'algorithme de bilinéaire produit alors des altitudes prévisibles. Si trop complexe, créer une interface `ElevationLookup` minimale et injecter ça plutôt que `ElevationProvider` directement. **Vérifier l'API actuelle** d'`ElevationProvider` avant de figer le design.

**Alternative simple** : la signature `fixElevation` prend `provider: ElevationProvider`. Les tests créent un vrai `ElevationProvider(config, fetcher = mockFetcher)` où le `mockFetcher` retourne des `RawTile` constants. Plus lourd mais plus réaliste.

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/path/ElevationStep.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/path/ElevationStepTest.kt` (≥ 9 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 9 tests verts × 3 targets.
- Slots non-elevation préservés.
- `:elevation:allTests` toujours vert.

## Done when

- [x] `ElevationStep.kt` créé
- [x] `ElevationStepTest.kt` ≥ 9 tests verts × 3 targets
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **`suspend fun fixElevation`** : nécessite un appelant qui sait gérer les coroutines. C'est le cas dans `Enhancer` (tâche 25) qui sera lui-même `suspend`.
- **`provider: ElevationProvider`** : l'appelant peut injecter un provider configuré (fetcher mock, URL custom, etc.). Si `null` plus tard ajouté comme option : la spec utilise `provider.setElevations(coords)`. Si `null`, `smoothElevation` peut être appelé seul.
- **150 m window** : valeur TS. Au-delà, les écarts d'altitude < 5 m sur 150 m de distance sont lissés. Sentinel test #10.
- **`copyAllSlots`** : tous les 36 slots sont copiés (lat/lon/time/elevation/extensions). Seul `elevation` est ensuite remplacé. Importante pour préserver `pInputPower`, `heartRate`, etc.
- **`computeDerivedData()` final** : recalcule distance/bearing/etc. à partir des nouvelles altitudes (utile pour `grade(i)` qui dépend de `elevation`).
- **Mock provider** : choix d'implémentation laissé à l'agent — peut utiliser un `ElevationProvider(fetcher = mockFetcher)` qui retourne des `RawTile` connus, ou un wrapper plus simple si possible. Documenter le choix.
- **Préparation tâche 25** : `Enhancer.enhanceCourse(...)` orchestre `fixElevation` + `smoothElevation` + les 5 autres étapes.
