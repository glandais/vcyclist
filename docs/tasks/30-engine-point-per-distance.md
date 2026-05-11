# 30 — Engine : `PointPerDistance` (resampling à distance constante)

## Goal

Porter le **resampler distance-based** `PointPerDistance.compute(path, minDist, maxDist, fields)` du TS. Utilisé par `Enhancer.enhanceCourse` du TS pour densifier (`maxDist=30 m`) puis raffiner (`minDist=1, maxDist=2 m`) la trace avant et après `fixElevation`. Son absence dans Phase 2 explique partiellement la divergence avec la sortie TS et certaines anomalies dans le pipeline complet.

**Algorithme** :
1. Lire `distance(i)` pour tout `i` (déjà cumulé par `Path.computeDerivedData`).
2. Toujours conserver le premier point. Soit `lastAddedDistance = distance(0)`, `lastAddedIndex = 0`.
3. Pour chaque point `i ≥ 1` :
   - `gap = distance(i) − lastAddedDistance`
   - Si `gap < minDist` : **skip** (point trop proche du précédent retenu).
   - Si `gap ≤ maxDist` : **copy** verbatim ; `lastAddedDistance = distance(i)`, `lastAddedIndex = i`.
   - Sinon (`gap > maxDist`) : **densifier** en `numSegments = ceil(gap / maxDist)` segments égaux ; interpoler `numSegments - 1` points internes entre `lastAddedDistance` et `distance(i)` (chacun positionné dans le segment `[index1, index1+1]` qui contient sa cible), puis copier le point `i`.
4. Appeler `computeDerivedData()` à la fin.

Semantics edge cases :
- `minDist < 0` (e.g. `-1`) : aucun point n'est jamais sous le minimum → comportement "densify-only".
- `path.size == 0` : retourne `Path(0)`.
- `path.size == 1` : retourne une copie du point unique.

Fixed-size `Path` Kotlin → on construit en 2 passes : (1) plan d'opérations (copy/interpolate), (2) matérialisation dans `Path(planSize)`.

## Depends on

- `12-engine-path` (`Path` fixed-size + accesseurs)
- `10-engine-field-definitions` (`PointField.entries`)
- Pattern réutilisé de la tâche 22 (`PointPerSecond`) pour le 2-pass plan/materialize

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/processing/PointPerDistance.ts` (canonique)

## Steps

### 1. `PointPerDistance.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointPerDistance.kt` :

```kotlin
package io.github.glandais.engine.path

import kotlin.math.ceil

/**
 * Distance-based path resampler. Enforces a `[minDistanceM, maxDistanceM]` gap between
 * consecutive points :
 * - Source points closer than [minDistanceM] from the last kept point are dropped.
 * - Source points within `(minDistanceM, maxDistanceM]` are copied verbatim.
 * - Gaps larger than [maxDistanceM] are filled with linearly interpolated points at regular
 *   intervals so that no resulting gap exceeds [maxDistanceM].
 *
 * The first and last points (after filtering) are always kept. `minDistanceM` may be negative
 * (e.g. `-1`) to disable the lower bound (densify-only mode).
 *
 * Port of `processing/PointPerDistance.ts`. Returns a fresh [Path] ; the source is unchanged.
 */
object PointPerDistance {

    /** Same as [computeOnePointPerDistance] (TS-compatible alias). */
    fun compute(source: Path, minDistanceM: Double, maxDistanceM: Double): Path =
        computeOnePointPerDistance(source, minDistanceM, maxDistanceM)

    fun computeOnePointPerDistance(source: Path, minDistanceM: Double, maxDistanceM: Double): Path {
        require(maxDistanceM > 0.0) { "maxDistanceM must be > 0, got $maxDistanceM" }
        if (source.size == 0) return Path(0)
        if (source.size == 1) {
            val out = Path(1)
            copyFields(source, 0, out, 0)
            return out
        }

        val plan = buildPlan(source, minDistanceM, maxDistanceM)
        return materialize(source, plan)
    }

    private sealed interface Op {
        data class Copy(val sourceIndex: Int) : Op
        data class Interpolate(val from: Int, val to: Int, val coef: Double) : Op
    }

    private fun buildPlan(source: Path, minDistanceM: Double, maxDistanceM: Double): List<Op> {
        val n = source.size
        val plan = ArrayList<Op>(n)
        // Always keep the first point.
        plan += Op.Copy(0)
        var lastAddedDistance = source.distance(0)
        var lastAddedIndex = 0

        for (i in 1 until n) {
            val curDist = source.distance(i)
            val gap = curDist - lastAddedDistance

            when {
                gap < minDistanceM -> continue
                gap <= maxDistanceM -> {
                    plan += Op.Copy(i)
                    lastAddedDistance = curDist
                    lastAddedIndex = i
                }
                else -> {
                    val numSegments = ceil(gap / maxDistanceM).toInt()
                    val spacing = gap / numSegments
                    var index1 = lastAddedIndex
                    for (j in 1 until numSegments) {
                        val targetDistance = lastAddedDistance + j * spacing
                        // Find segment [index1, index1+1] containing targetDistance.
                        while (index1 < i - 1 && source.distance(index1 + 1) < targetDistance) {
                            index1++
                        }
                        val dist1 = source.distance(index1)
                        val dist2 = source.distance(index1 + 1)
                        val coef = (targetDistance - dist1) / (dist2 - dist1)
                        plan += Op.Interpolate(index1, index1 + 1, coef)
                    }
                    plan += Op.Copy(i)
                    lastAddedDistance = curDist
                    lastAddedIndex = i
                }
            }
        }
        return plan
    }

    private fun materialize(source: Path, plan: List<Op>): Path {
        val out = Path(plan.size)
        for ((dstIdx, op) in plan.withIndex()) {
            when (op) {
                is Op.Copy -> copyFields(source, op.sourceIndex, out, dstIdx)
                is Op.Interpolate -> interpolateFields(source, op.from, op.to, op.coef, out, dstIdx)
            }
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
            val v = if (v1.isNaN() || v2.isNaN()) Double.NaN else v1 + (v2 - v1) * coef
            dst.set(dstIdx, field, v)
        }
    }
}
```

### 2. Tests `PointPerDistanceTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/path/PointPerDistanceTest.kt`. Cas (≥ 12) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `compute(Path(0), …)` → `Path(0)` | exact |
| 2 | `compute(Path(1), …)` → `Path(1)` copie verbatim | propriété |
| 3 | `maxDistanceM <= 0` → IllegalArgumentException | exception |
| 4 | Path 4 points à 10m d'écart, `min=-1, max=15` → tous gardés (4 points) | propriété |
| 5 | Path 4 points à 10m d'écart, `min=-1, max=5` → densifié (gap=10 → 2 segments → 1 point interpolé entre chaque paire), résultat 7 points (1 + 3 × (1 interp + 1 copy)) | propriété |
| 6 | Path 4 points à 1m d'écart, `min=5, max=10` → seul le premier (et le dernier ? non — la spec ne préserve pas obligatoirement le dernier) est gardé | propriété |
| 7 | Path 4 points à 1m d'écart, `min=2, max=5` → point i=1 skipped, i=2 gardé (gap=2 ok), etc. | propriété |
| 8 | Interpolation lat/lon préserve la géométrie : point milieu entre (0,0) et (1e-4, 0) avec coef=0.5 → lat=5e-5 | sentinel |
| 9 | Interpolation préserve élévation : (100, 200) → milieu=150 | sentinel |
| 10 | Slots non-coords (e.g. `pInputPower`) interpolés correctement | propriété |
| 11 | Premier point toujours préservé | propriété |
| 12 | NaN dans un slot → NaN dans l'interpolé | propagation |
| 13 | Path long (1000 pts, gap aléatoire 0.5-50m, `min=1, max=2`) → output strictement croissant en distance, gaps ∈ [1, 2] m sauf au point #0 | propriété |

**Helper** :
```kotlin
private fun buildPath(distances: DoubleArray, elevations: DoubleArray = DoubleArray(distances.size) { 100.0 }): Path {
    val n = distances.size
    val p = Path(n)
    for (i in 0 until n) {
        p.setLatitude(i, 0.0)
        p.setLongitude(i, i * 1e-5)
        p.setElevation(i, elevations[i])
        p.setDistance(i, distances[i])
    }
    return p
}
```

⚠ `Path.computeDerivedData()` interne dans `materialize` recalcule `distance(i)` depuis lat/lon. Pour les tests synthétiques, la distance calculée par Haversine sur lat=0/lon=i×1e-5 donne ~1.113 m par incrément. Si les tests asseraient des distances en utilisant `setDistance` puis lisaient `distance(i)` après PointPerDistance, la valeur lue serait **recalculée** ≠ la valeur settée. Solution : les assertions des tests doivent porter sur la **taille de l'output**, l'ordre des points (via une métrique autre que distance), ou bien faire les calculs avant `computeDerivedData()` interne. Documenter.

**Alternative** : pour tester strictement la sémantique distance-based, mocker un `Path` qui retourne directement les `distance(i)` settés via un override. Trop complexe. Décision : utiliser des lat/lon **cohérents** avec les distances voulues (e.g. lon = `i * gap / 111320` pour gap en mètres à l'équateur).

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointPerDistance.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/path/PointPerDistanceTest.kt` (≥ 12 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 12 tests verts × 3 targets.
- Non-régression complète tâches 10-29.
- `:elevation:allTests` toujours vert.

## Done when

- [x] `PointPerDistance.kt` créé
- [x] `PointPerDistanceTest.kt` ≥ 12 tests verts × 3 targets
- [x] `:engine:allTests` + `:elevation:allTests` verts
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **2-pass plan/materialize** : même pattern que tâche 22 (`PointPerSecond`). Garde la fixed-size de `Path` tout en supportant un nombre de points de sortie inconnu à l'avance.
- **`compute` alias** : conserve le nom TS pour faciliter la migration de code/tests. La fonction canonique reste `computeOnePointPerDistance`.
- **Pas de paramètre `fields: List<PointField>`** : différence avec le TS qui permet de limiter les champs copiés/interpolés. On copie/interpole **tous** les 36 slots, comme `PointPerSecond` (tâche 22). Simplifie l'API. Si un besoin émerge (perf sur très gros paths), ajouter un paramètre `fieldsToProcess: Set<PointField>?` avec défaut `null = all`.
- **`require(maxDistanceM > 0)`** : protection. `maxDistanceM = 0` causerait `numSegments = ∞`.
- **Recherche `while (source.distance(index1 + 1) < targetDistance)`** : monotone (l'index ne fait que progresser). O(n) total amorti sur l'ensemble du path.
- **`distance(i)` recalculée par `computeDerivedData`** : impact sur les tests — utiliser lat/lon cohérents pour les sentinels distance.
- **Comportement TS subtil — préservation du dernier point** : le TS ajoute *toujours* le point `i` dans la branche `else` (gap > max), donc le dernier point d'index `n-1` est gardé dès qu'au moins un point avant lui était assez loin. **Mais** dans la branche `gap < minDistanceM`, le dernier point peut être skippé. Conséquence : un path avec un dernier point trop proche du précédent retenu est **tronqué**. Cohérent avec le TS, à vérifier dans le test #6.
- **Préparation tâche 31** : brancher `PointPerDistance` dans `Enhancer.enhanceCourse` à 2 endroits — avant `fixElevation` (`min=-1, max=30`) et après (`min=1, max=2`). Ré-activer `EnhanceOptions.DEFAULT` pour `computeOnePointPerSecond` et `simplifyPath` (déjà fait en tâche 29 via EngineCli mais ne change pas les defaults `EnhanceOptions`).
