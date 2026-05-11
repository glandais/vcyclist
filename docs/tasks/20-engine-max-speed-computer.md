# 20 — Engine : `MaxSpeedComputer` (cornering + braking)

## Goal

Calculer la **vitesse maximale sûre** à chaque point d'un `Path` en combinant :

- **Limite cornering** (par point) : `v = √(g × R × tan(θ_lean))` où `R` est le rayon de courbure local (estimé via la variation de bearing sur une fenêtre de ±10 points). Clampé à `cyclist.maxSpeedMS`.
- **Limite braking** (par point, vers le suivant) : `v₀ = √(v_f² + 2 × a × d)` (équation cinématique de freinage).
- **Backward pass** : on parcourt le path de la fin vers le début ; le dernier point a `speedMax = 2 m/s` (sécurité d'arrêt) ; chaque point `i` prend le `min` des deux limites.

Side-effects sur le path : `speedMax`, `speedMaxIncline`, `radius` (rayon de courbure calculé en passant).

Après la passe : appel à `path.computeDerivedData()` (le TS le fait). Note : `Path.computeDerivedData` ne touche **pas** au slot `speedMax` — il recalcule `bearing/grade/elapsed/dx/dt/speed/distance` mais pas speedMax. Sûr d'appeler.

## Depends on

- `12-engine-path` (`Path.bearing/distance` lus, `setSpeedMax/setSpeedMaxIncline/setRadius` écrits)
- `13-engine-cyclist-bike` (`Cyclist.tanMaxLeanAngle`, `maxSpeedMS`, `maxBrakeMS2`, `EngineConstants.G`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/MaxSpeedComputer.ts` (canonique)

## Steps

### 1. `MaxSpeedComputer.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/MaxSpeedComputer.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Computes maximum-safe speeds on a [Path] from cornering geometry and braking limits.
 *
 * Single backward pass : for each point `i` from last → first,
 * `speedMax[i] = min(corneringLimit(i), brakeFrom(i, i+1, speedMax[i+1]))`.
 *
 * - **Cornering** : `v_max = √(g × radius × tan(θ_lean))`. Radius is estimated by accumulating
 *   bearing changes over a window of ±10 points and dividing total distance by total angle.
 *   Clamped to `[5 m, MAX_RADIUS=200 m]`.
 * - **Braking** : kinematic `v₀ = √(v_f² + 2 × a × d)`. Always satisfiable since `a > 0`.
 * - **Last point** is set to `2 m/s` (sentinel speed at end-of-track).
 *
 * Side-effects on the path : `speedMax`, `speedMaxIncline`, `radius`.
 *
 * Port of `MaxSpeedComputer.ts`.
 */
object MaxSpeedComputer {

    private const val MAX_RADIUS_M = 200.0
    private const val MIN_RADIUS_M = 5.0
    private const val END_SPEED_MS = 2.0
    private const val BEARING_THRESHOLD = 0.001
    private const val DEFAULT_WINDOW = 10

    /** Compute `speedMax` for every point on `course.path`. */
    fun computeMaxSpeeds(course: Course) {
        val path = course.path
        val n = path.size

        for (i in n - 1 downTo 0) {
            if (i == n - 1) {
                path.setSpeedMax(i, END_SPEED_MS)
            } else {
                val cornering = computeCorneringLimit(course, i, DEFAULT_WINDOW)
                val braking = computeBrakingLimit(course, i, i + 1)
                path.setSpeedMax(i, min(cornering, braking))
            }
        }
        path.computeDerivedData()
    }

    private fun computeCorneringLimit(course: Course, currentIndex: Int, window: Int): Double {
        val path = course.path
        val radius = computeRadiusWindowed(path, currentIndex, window)
        val vMax = sqrt(EngineConstants.G * radius * course.cyclist.tanMaxLeanAngle)
        val result = min(course.cyclist.maxSpeedMS, vMax)
        path.setSpeedMaxIncline(currentIndex, result)
        return result
    }

    private fun computeRadiusWindowed(path: Path, i: Int, k: Int): Double {
        val mini = max(0, i - k)
        val maxi = min(path.size - 1, i + k)
        val totalBearingChange = normalizeAngleDiff(path.bearing(maxi) - path.bearing(mini))
        val totalDistance = path.distance(maxi) - path.distance(mini)

        if (abs(totalBearingChange) < BEARING_THRESHOLD) {
            path.setRadius(i, MAX_RADIUS_M)
            return MAX_RADIUS_M
        }
        val raw = totalDistance / abs(totalBearingChange)
        val clamped = max(MIN_RADIUS_M, min(MAX_RADIUS_M, raw))
        path.setRadius(i, clamped)
        return clamped
    }

    private fun normalizeAngleDiff(angleIn: Double): Double {
        var a = angleIn
        while (a > PI) a -= 2.0 * PI
        while (a < -PI) a += 2.0 * PI
        return a
    }

    private fun computeBrakingLimit(course: Course, currentIndex: Int, nextIndex: Int): Double {
        val path = course.path
        val vf = path.speedMax(nextIndex)
        val a = course.cyclist.maxBrakeMS2
        val d = path.distance(nextIndex) - path.distance(currentIndex)
        return sqrt(vf * vf + 2.0 * a * d)
    }
}
```

### 2. Tests `MaxSpeedComputerTest.kt`

Cas à couvrir (≥ 12) :

| # | Cas | Attendu |
|---|---|---|
| 1 | Path vide (`Path(0)`) → no crash | propriété |
| 2 | Path 1 point → `speedMax(0) == 2.0` | exact |
| 3 | Path 2 points en ligne droite (bearing=0) → cornering = `cyclist.maxSpeedMS` (≈ 27.78 m/s), point 0 = min(maxSpeedMS, brake-limit) | propriété |
| 4 | Path 3 points avec virage 90° à i=1 (bearing change π/2 sur ~30 m) → radius < MAX_RADIUS, cornering limit < maxSpeedMS | propriété |
| 5 | Virage très serré (radius < 5 m calculé) → clamp à MIN_RADIUS_M = 5 m | sentinel |
| 6 | Pas de virage (bearing constant) → radius = MAX_RADIUS_M = 200 m | sentinel |
| 7 | `normalizeAngleDiff` : 3π/2 → -π/2 | sentinel |
| 8 | `normalizeAngleDiff` : -3π/2 → π/2 | sentinel |
| 9 | `normalizeAngleDiff` : -π/4 → -π/4 (déjà dans [-π, π]) | identité |
| 10 | Backward pass cohérent : `speedMax(i)` ≤ `speedMax(i+1) + √(2×a×d)` (propriété de freinage) | propriété |
| 11 | Side-effects : `radius` et `speedMaxIncline` écrits pour tous les points (sauf le dernier) | propriété |
| 12 | Sentinel cornering : R=30 m, lean=35° → `v_max = √(9.8 × 30 × tan(35°)) ≈ 14.36 m/s ≈ 51.7 km/h` | 1e-3 |
| 13 | Sentinel braking : v_f=0, d=10 m, a=0.6×9.8=5.88 m/s² → `v_0 = √(2×5.88×10) ≈ 10.84 m/s ≈ 39 km/h` | 1e-3 |

**Helper de test** — construire un Path avec une géométrie connue :
```kotlin
private fun buildLinearPath(distances: DoubleArray, bearings: DoubleArray): Path {
    require(distances.size == bearings.size)
    val p = Path(distances.size)
    for (i in distances.indices) {
        p.setDistance(i, distances[i])
        p.setBearing(i, bearings[i])
    }
    return p
}
```

⚠ `MaxSpeedComputer` lit `bearing` et `distance` du path, qui sont **normalement** calculés par `Path.computeDerivedData()` à partir de lat/lon. Pour des tests synthétiques, on peut court-circuiter en settant directement les bearings/distances. Documenter.

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/MaxSpeedComputer.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/MaxSpeedComputerTest.kt` (≥ 12 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 12 tests verts × 3 targets.
- Non-régression : tous les tests existants verts.
- Sentinel cornering R=30m → ~14.36 m/s.
- Sentinel braking 0→0 sur 10m → ~10.84 m/s.

## Done when

- [x] `MaxSpeedComputer.kt` créé
- [x] `MaxSpeedComputerTest.kt` ≥ 12 tests verts × 3 targets
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Side-effects path vérifiés (`radius`, `speedMaxIncline`, `speedMax`)
- [x] Toutes les checkboxes cochées

## Notes

- **`object MaxSpeedComputer`** : stateless, pattern singleton natif Kotlin.
- **`MaxSpeedCourse` interface du TS** : on utilise directement `Course` (qui a `path + cyclist + bike`). Pas besoin d'interface séparée.
- **`path.computeDerivedData()` à la fin** : note du TS — recalcule les champs dérivés *après* avoir setté `speedMax`. Mais ces champs (`bearing`, `grade`, `speed`, etc.) **dépendent** de `lat/lon/elevation/time`, pas de `speedMax`. L'appel est donc redondant ici. On le porte fidèlement (parité), mais on note la potentielle suppression future.
- **`speed(i)` ≠ `speedMax(i)`** : 2 slots distincts du PointField. `speedMax` est calculé par cette tâche ; `speed` est calculé par `Path.computeDerivedData()` (à partir des temps GPX). Les confondre est une source d'erreur fréquente.
- **`MAX_RADIUS=200 m`** : valeur arbitraire qui sature le radius "ligne droite" (pas de virage détectable). En pratique, plus le radius est grand, plus la cornering limit explose, donc clampée à `cyclist.maxSpeedMS`.
- **Window = 10** : ±10 points autour de i, soit 20 segments. À 10 m/s × 1 s = 10 m par segment → fenêtre ≈ 200 m. Approche robuste à la noise GPS.
- **Préparation tâche 21** : `VirtualizeService` lit `speedMax(i)` pour clamper la vitesse simulée à chaque itération.
