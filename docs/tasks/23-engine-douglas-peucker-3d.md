# 23 — Engine : `PathSimplifier` (Douglas-Peucker 3D sur `Path`)

## Goal

Adapter le `DouglasPeucker` du module `:elevation` (tâche 03 — opère sur `List<CoordinatesElevation>`) pour qu'il travaille directement sur un `Path` du module `:engine`. Évite la conversion `Path → List → Path` et préserve **tous les slots** des points retenus (pas seulement lat/lon/elevation).

**Stratégie** :
1. Construire une `List<CoordinatesElevation>` depuis le path (en degrés via `path.coordinatesElevationSequence()`).
2. Appeler `io.github.glandais.elevation.DouglasPeucker.simplify(coords, tolerance, zExaggeration)` qui retourne la liste simplifiée.
3. **Mapper la liste simplifiée vers les index source** : pour chaque point retenu, trouver l'index correspondant dans le path source via une recherche linéaire (les coordonnées doivent matcher exactement).
4. Construire un nouveau `Path(retainedSize)` et copier **tous** les slots depuis les index sources retenus.
5. Appeler `computeDerivedData()` à la fin.

## Depends on

- `12-engine-path` (`Path`, `coordinatesElevationSequence`)
- `:elevation.DouglasPeucker` (déjà disponible via `api(project(":elevation"))`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/processing/DouglasPeucker.ts` (référence — fait essentiellement la même chose mais réécrit l'algo)

## Steps

### 1. `PathSimplifier.kt`

Nommage : `PathSimplifier` plutôt que `DouglasPeucker3D` ou `DouglasPeucker` pour éviter la collision de nom avec `io.github.glandais.elevation.DouglasPeucker`.

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/PathSimplifier.kt` :

```kotlin
package io.github.glandais.engine.path

import io.github.glandais.elevation.CoordinatesElevation
import io.github.glandais.elevation.DouglasPeucker
import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.MathConstants

/**
 * Path simplification via 3D Douglas-Peucker (ECEF) — wraps the elevation module's
 * [DouglasPeucker] to preserve every [PointField] slot of retained points.
 *
 * Algorithm :
 * 1. Build a `List<CoordinatesElevation>` from the path (degrees).
 * 2. Call [DouglasPeucker.simplify] to get the simplified subset (in order).
 * 3. Walk the source path with a single pointer ; copy every retained point's full slot
 *    set into the output path.
 * 4. Run [Path.computeDerivedData] on the output.
 *
 * Mirrors `processing/DouglasPeucker.ts` semantics while reusing the elevation module's
 * implementation.
 */
object PathSimplifier {

    /** Simplify [path] in 3D space. */
    fun simplify(path: Path, toleranceM: Double, zExaggeration: Double = 3.0): Path {
        if (path.size <= 2) return path.copy()

        // Build the coordinate list (degrees, with elevation).
        val coords = ArrayList<CoordinatesElevation>(path.size)
        for (i in 0 until path.size) {
            coords += LatLonElevation(
                latitude = path.latitude(i) * MathConstants.RAD_TO_DEG,
                longitude = path.longitude(i) * MathConstants.RAD_TO_DEG,
                elevation = path.elevation(i),
            )
        }

        val simplified = DouglasPeucker.simplify(coords, toleranceM, zExaggeration)

        // Map retained coords back to source indices (single-pass match by reference).
        val retainedIndices = ArrayList<Int>(simplified.size)
        var cursor = 0
        for (kept in simplified) {
            // Linear scan from cursor — DouglasPeucker preserves order.
            while (cursor < coords.size && coords[cursor] !== kept) cursor++
            if (cursor == coords.size) error("PathSimplifier: retained point not found in source")
            retainedIndices += cursor
            cursor++  // monotonic progress
        }

        // Materialize a new Path with the retained slots.
        val out = Path(retainedIndices.size)
        for ((dstIdx, srcIdx) in retainedIndices.withIndex()) {
            for (field in PointField.entries) {
                out.set(dstIdx, field, path.get(srcIdx, field))
            }
        }
        out.computeDerivedData()
        return out
    }
}
```

**Note `===` reference equality** : on s'appuie sur le fait que `DouglasPeucker.simplify` renvoie les **mêmes** instances `CoordinatesElevation` que celles passées en entrée (cf. tâche 03 — l'algo n'alloue pas de nouvelles instances pour les points retenus). Si ce contrat venait à changer (e.g. introduction d'un `.copy()` interne), basculer sur `==` avec tolérance numérique.

### 2. Tests `PathSimplifierTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/path/PathSimplifierTest.kt`. Cas (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | Path size 0 → output size 0 | exact |
| 2 | Path size 1 → output size 1 (copie défensive, `path.copy()` retourne fresh) | propriété |
| 3 | Path size 2 → output size 2 (premier+dernier toujours préservés) | propriété |
| 4 | Path 4 points colinéaires, tolerance 1 m → output size 2 (intermediates supprimés) | sentinel |
| 5 | Path 4 points avec déviation 100 m, tolerance 1 m → output size 4 (tous gardés) | propriété |
| 6 | Path 4 points avec déviation 100 m, tolerance 1000 m → output size 2 (tous intermediates filtrés) | propriété |
| 7 | Tolerance extrême (1e9) → output size 2 exact | sentinel |
| 8 | Premier et dernier points toujours préservés (toutes tolerances) | propriété |
| 9 | Slots préservés : tester avec un path qui a `pInputPower(i) = 100+i`, vérifier que les retained ont le power correct | sémantique slots |
| 10 | `computeDerivedData()` appelée à la fin : `distance(i) > 0` cohérent | propriété |
| 11 | `zExaggeration` plus grand → plus de points retenus (variation altitude amplifiée) | propriété |
| 12 | `simplify(path).simplify(path)` (idempotence) → résultat identique | propriété |

**Setup helper** :
```kotlin
private fun buildSyntheticPath(latDeg: DoubleArray, lonDeg: DoubleArray, eleM: DoubleArray): Path {
    require(latDeg.size == lonDeg.size && latDeg.size == eleM.size)
    val n = latDeg.size
    val p = Path(n)
    for (i in 0 until n) {
        p.setLatitude(i, latDeg[i] * MathConstants.DEG_TO_RAD)
        p.setLongitude(i, lonDeg[i] * MathConstants.DEG_TO_RAD)
        p.setElevation(i, eleM[i])
    }
    return p
}
```

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PathSimplifier.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/path/PathSimplifierTest.kt` (≥ 10 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 10 tests verts × 3 targets.
- Slots non-coordonnées préservés (test #9).
- Premier/dernier toujours présents.
- `:elevation:allTests` toujours vert.

## Done when

- [x] `PathSimplifier.kt` créé
- [x] `PathSimplifierTest.kt` ≥ 10 tests verts × 3 targets
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Test slots non-coords préservés vert (test #9)
- [x] Toutes les checkboxes cochées

## Notes

- **Nom `PathSimplifier`** : évite la confusion avec `io.github.glandais.elevation.DouglasPeucker`. Le wrapper exprime sa fonction (`simplify`) sans réimplémenter l'algorithme.
- **`===` reference equality** : repose sur le fait que `DouglasPeucker.simplify` du module elevation ne clone pas les `CoordinatesElevation` retenus. Si cette contrainte venait à se rompre, on basculerait sur une comparaison structurelle. Test #4 et #6 valident implicitement.
- **`path.copy()` pour size ≤ 2** : produit une instance distincte (pas d'aliasing). Important pour cohérence avec les autres tâches qui retournent un nouveau `Path`.
- **Slots préservés** : différence essentielle avec le TS — on évite la perte des slots `pInputPower/heartRate/etc.` qui auraient été remplis avant la simplification (e.g. après `VirtualizeService`).
- **`computeDerivedData()` final** : recalcule `distance/bearing/dx/dt/speed/elapsed/grade` depuis lat/lon/elevation/time. Cohérent puisque ces 4 champs sont préservés des index sources retenus.
- **Préparation tâche 24/25** : `PathSimplifier` consommé par `Enhancer` (tâche 25, étape "simplify") via `EnhanceOptions.simplifyPath`.
