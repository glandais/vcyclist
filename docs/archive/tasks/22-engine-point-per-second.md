# 22 — Engine : `PointPerSecond` (resampling 1 Hz)

## Goal

Re-échantillonner un `Path` pour qu'il contienne **exactement un point par seconde epoch** (`time(i)` multiple de 1000 ms). Interpolation **linéaire** entre les paires de points GPX successifs qui chevauchent une frontière de seconde.

**Algorithme** :
1. Pour chaque point `i ∈ [0, n-1]` du path source :
   - Calculer `epochSec(i) = floor(time(i) / 1000)`.
   - Si `i = 0` et `time(0) % 1000 != 0` : ajouter un point « copie » à `epochSec(0)`.
   - Si `i = n-1` et `time(n-1) % 1000 != 0` : ajouter un point « copie » à `epochSec(n-1) + 1`.
   - Sinon, si `epochSec(i) != epochSec(i+1)` : pour chaque seconde `e ∈ [epochSec(i)+1 si non-aligné, epochSec(i+1)]`, ajouter un point « interpolé » entre `i` et `i+1` avec coefficient `coef = (e×1000 - time(i)) / (time(i+1) - time(i))`.
2. Construire un nouveau `Path` en triant les secondes et en :
   - **Copiant** verbatim les slots de l'index source pour type `copy` (puis écraser `time` à `epoch × 1000`).
   - **Interpolant linéairement** chaque slot entre les deux index source pour type `interpolate` (puis écraser `time`).

## Depends on

- `12-engine-path` (`Path` fixed-size + accesseurs nommés/génériques)
- `10-engine-field-definitions` (`PointField.entries` pour itération)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/processing/PointPerSecond.ts` (canonique)

## Steps

### 1. `PointPerSecond.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointPerSecond.kt` :

```kotlin
package io.github.glandais.engine.path

import kotlin.math.floor

/**
 * Resamples a [Path] to one point per epoch second (1 Hz uniform sampling).
 *
 * Linear interpolation between consecutive source points whenever they straddle a second
 * boundary. Source paths whose first/last points fall mid-second get a "copy" point at the
 * surrounding epoch boundary so the resampled path covers `[floor(start), ceil(end))` seconds.
 *
 * Port of `processing/PointPerSecond.ts`. Returns a fresh [Path] ; the source is unchanged.
 */
object PointPerSecond {

    /** Resample [source] to 1 Hz. Empty source → empty path. */
    fun computeOnePointPerSecond(source: Path): Path {
        if (source.size == 0) return Path(0)
        val plan = buildPlan(source)
        return materialize(source, plan)
    }

    private sealed interface InterpolationData {
        data class Copy(val sourceIndex: Int) : InterpolationData
        data class Interpolate(val from: Int, val to: Int, val coef: Double) : InterpolationData
    }

    private fun buildPlan(source: Path): Map<Long, InterpolationData> {
        // LinkedHashMap keeps insertion order, then we sort by epoch at materialization time.
        val plan = LinkedHashMap<Long, InterpolationData>()
        val n = source.size

        for (i in 0 until n) {
            val time1 = source.time(i)
            val epoch1 = floor(time1 / 1000.0).toLong()
            val msInSec1 = (time1.toLong() % 1000L)

            if (i == 0 && msInSec1 != 0L) {
                plan[epoch1] = InterpolationData.Copy(i)
            }
            if (i == n - 1) {
                if (msInSec1 != 0L) {
                    plan[epoch1 + 1L] = InterpolationData.Copy(i)
                }
                continue
            }

            val time2 = source.time(i + 1)
            val epoch2 = floor(time2 / 1000.0).toLong()
            if (epoch1 == epoch2) continue

            val duration12 = time2 - time1
            val epochStart = if (msInSec1 == 0L) epoch1 else epoch1 + 1L
            val epochEnd = epoch2
            for (e in epochStart..epochEnd) {
                val epochTime = e * 1000.0
                val coef = (epochTime - time1) / duration12
                plan[e] = InterpolationData.Interpolate(i, i + 1, coef)
            }
        }
        return plan
    }

    private fun materialize(source: Path, plan: Map<Long, InterpolationData>): Path {
        val sortedEpochs = plan.keys.sorted()
        val out = Path(sortedEpochs.size)
        for ((idx, epoch) in sortedEpochs.withIndex()) {
            val data = plan[epoch] ?: continue
            when (data) {
                is InterpolationData.Copy -> copyFields(source, data.sourceIndex, out, idx)
                is InterpolationData.Interpolate ->
                    interpolateFields(source, data.from, data.to, data.coef, out, idx)
            }
            // Time slot is always set to the epoch boundary (overwrites copied/interpolated time).
            out.setTime(idx, (epoch * 1000L).toDouble())
        }
        out.computeDerivedData()
        return out
    }

    private fun copyFields(src: Path, srcIdx: Int, dst: Path, dstIdx: Int) {
        for (field in PointField.entries) {
            dst.set(dstIdx, field, src.get(srcIdx, field))
        }
    }

    private fun interpolateFields(
        src: Path,
        i1: Int,
        i2: Int,
        coef: Double,
        dst: Path,
        dstIdx: Int,
    ) {
        for (field in PointField.entries) {
            val v1 = src.get(i1, field)
            val v2 = src.get(i2, field)
            // Strict NaN handling : either side NaN → result NaN (mirrors TS).
            val v = if (v1.isNaN() || v2.isNaN()) Double.NaN else v1 + (v2 - v1) * coef
            dst.set(dstIdx, field, v)
        }
    }
}
```

### 2. Tests `PointPerSecondTest.kt`

Cas à couvrir (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | Path vide → returned path size 0 | exact |
| 2 | Path 1 point au temps `1234 ms` → output a 2 points (epoch 1, epoch 2) avec time = 1000 ms et 2000 ms | sentinel |
| 3 | Path 2 points exactement à 0 ms et 1000 ms → output 1 point ? (les deux ont mêmes epochs ? non, ils sont à epochs 0 et 1 → output 1 point au boundary epoch 1) | propriété |
| 4 | Path 2 points à 0 et 5000 ms → output 5 points aux secondes 1, 2, 3, 4, 5 (interpolation) | sentinel |
| 5 | Interpolation linéaire : si source[0] elevation=100 et source[1] elevation=200 à coef=0.5 → resampled elevation=150 | sentinel |
| 6 | `time(i)` du résultat est strictement croissant et multiple de 1000 ms | propriété |
| 7 | Resample d'un path déjà à 1 Hz (times 0, 1000, 2000, 3000) → output identique en contenu et taille | idempotence |
| 8 | Side-effect : `computeDerivedData()` appelée à la fin → `distance(i) > 0` cohérent avec lat/lon | propriété |
| 9 | NaN handling : si `temperature(0) = NaN`, l'interpolation produit NaN dans le résultat (slot temperature) | sémantique stricte |
| 10 | Path à 30 fps (33 ms intervals, 30 points sur ~1 s) → output 1-2 points (commençant et finissant en bord de seconde) | propriété |
| 11 | Source taille n → preserve les lat/lon/elevation interpolés ; time forcément aligné epoch | propriété structurelle |
| 12 | Path avec time(0) = 0 (aligné) et time(1) = 1500 ms (mid-second) → output [0, 1000] (2 points, source[0] copié et interpolation à epoch 1) | sentinel |

**Helper setup** :
```kotlin
private fun pathWithTimes(times: LongArray): Path {
    val p = Path(times.size)
    for (i in times.indices) {
        p.setLatitude(i, 0.0)
        p.setLongitude(i, i * 1e-5)  // tiny lon shift
        p.setElevation(i, 100.0 + i * 10.0)
        p.setTime(i, times[i].toDouble())
    }
    return p
}
```

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointPerSecond.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/path/PointPerSecondTest.kt` (≥ 10 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 10 tests verts × 3 targets.
- Non-régression `:engine` et `:elevation`.
- `time(i)` toujours multiple de 1000 ms en sortie.
- Idempotence sur un path déjà à 1 Hz.

## Done when

- [x] `PointPerSecond.kt` créé
- [x] `PointPerSecondTest.kt` ≥ 10 tests verts × 3 targets
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **`sealed interface InterpolationData`** : pattern idiomatique Kotlin pour ADT (algebraic data type). Plus type-safe que le `type X = A | B` TS.
- **`epoch * 1000L` puis `.toDouble()`** : on stocke `time` en `Double` mais le calcul du facteur d'interpolation reste exact (epoch est `Long`).
- **`LinkedHashMap` → `sorted()`** : on accepte le coût `O(n log n)` du tri. Pour n < 100k points, négligeable. Alternatif : `TreeMap` mais pas dispo en common KMP de la même façon.
- **NaN propagation** : le TS fait `if (isNaN(v1) || isNaN(v2)) NaN else v1 + (v2-v1) × coef`. Identique. Important pour les slots non-renseignés.
- **`computeDerivedData()` final** : recalcule `distance/bearing/elapsed/dx/dt/speed/grade` depuis `lat/lon/elevation/time`. Cohérent puisque ces 4 champs ont été interpolés ou copiés.
- **Path 1-point au mid-second** (test #2) : Le TS produit 2 points (start-of-second et end-of-second). Étrange mais on reproduit fidèlement.
- **`floor(time(i) / 1000.0).toLong()`** : on utilise `floor` puis `toLong` pour gérer les temps potentiellement très grands (epoch 2024 ≈ 1.7e12 ms). `time(i) / 1000` direct fonctionnerait pour ces valeurs aussi mais `floor` est explicite et résistant aux négatifs.
- **Préparation tâche 23** : `DouglasPeucker` 3D consomme un Path resampled.
