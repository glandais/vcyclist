# 21 — Engine : `VirtualizeService` (simulation time-stepping)

## Goal

Cœur du moteur de simulation : transformer un `Path` statique (GPS) en une trace **virtualisée** où chaque waypoint a une vitesse, un temps et une puissance physiquement cohérents avec :

- la puissance du cycliste (`MuscularPowerProvider`),
- les 4 résistances (aero, gravité, roulement, roulements),
- les limites de vitesse `speedMax` (calculées par `MaxSpeedComputer`, tâche 20).

**Algorithme** (boucle séquentielle sur les points GPS) :
1. Initialiser le 1er point à `MINIMAL_SPEED`, temps `t₀ = 0`.
2. À chaque pas de `i = 1` à `n-2` :
   - Lire `dx = distance(i) - distance(i-1)` (depuis le path source).
   - Lire `pSum = PowerComputer.getNewPower(course, path, i-1, withCyclist=true)`.
   - `dt = PowerComputer.getDt(pSum, mEq, speed, dx)` (recherche binaire).
   - `speedNew = 2·dx/dt - speed` (résolu depuis l'identité trapézoïdale `dx = (v_old + v_new) × dt/2`).
   - Si `speedNew > path.speedMax(i)` : clamp à `speedMax`, recalculer `dt = 2·dx / (speedNew + speed)`.
   - Avancer `time += dt × 1000`, ajouter le point virtualisé.
3. Post-process : pour chaque `i ∈ [0, n-2)`, appeler `PowerComputer.computeCyclistPower(...)` (problème inverse → écrit `pComputedPower`).
4. Appeler `path.computeDerivedData()` à la fin (recalcule bearing/grade/dx/dt/speed/elapsed).
5. Retourner le nouveau `Path`.

Particularité : on construit un **nouveau** `Path` (taille `n-1` car la boucle s'arrête à `< n-1`) en copiant les slots du path source point par point puis en réécrivant `time/elapsed/dx/dt/speed/virtSpeedCurrent`.

⚠ Comme `Path` est fixed-size (cf. tâche 12 — pas d'`addPoint` dynamique), on aura besoin d'un **builder pattern** : soit copier tous les points dans un nouveau Path de même taille puis ne pas écraser les non-utilisés, soit construire un `Path` cible au début avec `Path(n-1)` puis remplir.

## Depends on

- `19-engine-power-computer` (`PowerComputer.getNewPower`, `getDt`, `equivalentMass`, `computeCyclistPower`)
- `20-engine-max-speed-computer` (lit `path.speedMax` pour le clamp)
- `12-engine-path` (`Path` fixed-size — toutes les accesseurs)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/VirtualizeService.ts` (canonique)

## Steps

### 1. `VirtualizeService.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/VirtualizeService.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField

/**
 * Transforms a static GPS route into a physics-based virtual ride.
 *
 * Time-stepping loop : for each successive GPS waypoint, compute the time taken to travel
 * the segment given the current power balance and cyclist input. Enforces `speedMax`
 * (cornering + braking) from task 20. Output : a new [Path] with `speed`, `time`, `dx`,
 * `dt`, `virtSpeedCurrent` populated, plus the inverse cyclist-power computation in
 * `pComputedPower`.
 *
 * Reference : virtual-cyclist TS `VirtualizeService.ts`. Iteration cap : 100 000.
 */
object VirtualizeService {

    private const val MAX_ITERATIONS = 100_000

    /** Build a virtualized [Path] from `course.path`. */
    fun virtualizeTrack(course: CoursePhysics): Path {
        val mEq = PowerComputer.equivalentMass(course)
        val input: Path = course.path
        val n = input.size
        if (n < 2) return input.copy()  // nothing to simulate

        // The output path has the same size as input (we keep [0, n-2] simulated and skip n-1).
        // Why n? TS uses dynamic addPoint; we mirror by writing into a fixed-size Path of size n
        // and stopping the simulation at n-1 (last point gets copy of input only).
        val out = Path(n)
        copyAllFields(input, 0, out, 0)

        var speed = EngineConstants.MINIMAL_SPEED
        val startTimeMs = 0.0  // arbitrary epoch start
        var timeMs = startTimeMs
        out.setTime(0, timeMs)
        out.setElapsed(0, 0.0)
        out.setSpeed(0, speed)
        out.setVirtSpeedCurrent(0, speed)

        var i = 1
        var iter = 0
        while (i < n - 1) {
            // Read source distance delta
            val dx = input.distance(i) - input.distance(i - 1)
            // Power balance at i-1 (includes cyclist input via MuscularPowerProvider).
            val pSum = PowerComputer.getNewPower(course, out, i - 1, withCyclist = true)
            var dt = PowerComputer.getDt(pSum, mEq, speed, dx)
            var speedNew = 2.0 * dx / dt - speed

            copyAllFields(input, i, out, i)

            val speedMax = input.speedMax(i)
            if (speedNew > speedMax) {
                speedNew = speedMax
                dt = 2.0 * dx / (speedNew + speed)
            }

            speed = speedNew
            timeMs += dt * 1000.0

            out.setTime(i, timeMs)
            out.setElapsed(i, timeMs - startTimeMs)
            out.setDx(i, dx)
            out.setDt(i, dt * 1000.0)
            out.setSpeed(i, speed)
            out.setVirtSpeedCurrent(i, speed)

            i++
            if (iter++ > MAX_ITERATIONS) break
        }
        // Last point : copy verbatim (no virtualization beyond the second-to-last)
        if (n >= 2) copyAllFields(input, n - 1, out, n - 1)

        // Inverse problem : back-calculate cyclist power from speed changes.
        for (j in 0 until out.size - 1) {
            PowerComputer.computeCyclistPower(course, out, mEq, j)
        }

        out.computeDerivedData()
        return out
    }

    /** Copy every [PointField] slot from `src[i]` to `dst[j]`. Uses the generic `get/set`. */
    private fun copyAllFields(src: Path, i: Int, dst: Path, j: Int) {
        for (field in PointField.entries) {
            dst.set(j, field, src.get(i, field))
        }
    }
}
```

### 2. Tests `VirtualizeServiceTest.kt`

Cas à couvrir (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | Path vide → returned path size 0 | exact |
| 2 | Path 1 point → returned path size 1, copie de l'input | propriété |
| 3 | Path 3 points en ligne droite plate (grade=0), cyclist=defaults → simulation produit `speed(1) > MINIMAL_SPEED` et `speed(1) < cyclist.maxSpeedMS` | propriété |
| 4 | Path simulé : `time(0) == 0`, `time(i) > time(i-1)` monotone strict | propriété |
| 5 | Cyclist power = 0 (custom CoursePhysics avec PowerProviderConstant(0)) → speeds décroissent ou stagnent à MINIMAL_SPEED | propriété |
| 6 | speedMax constraint : si `path.speedMax(i) = 1 m/s` partout, alors `speed(i) ≤ 1 m/s` après virtualization | propriété |
| 7 | Path montée raide (grade=0.1, m=80, v initial bas) → cyclist 280W ne tient pas la vitesse, speed plafonne | propriété |
| 8 | Path descente raide (grade=-0.1) → speed augmente jusqu'à `speedMax(i)` | propriété |
| 9 | Post-process : `pComputedPower(i) ≥ 0` pour tout `i` (clampé positif) | propriété |
| 10 | Round-trip identité : si input path déjà virtualisé et speeds parfaitement cohérents, re-virtualization produit speeds quasi identiques | propriété (tolérance large 1e-3) |
| 11 | Iteration cap : path artificiel de 200 000 points → simulation s'arrête à `MAX_ITERATIONS` sans crash | propriété |
| 12 | Side-effects sur `out` : `dx`, `dt`, `virtSpeedCurrent`, `time`, `elapsed` écrits ; lat/lon/elevation copiés depuis input | propriété |

**Setup helper** :
```kotlin
private fun buildFlatPath(distances: DoubleArray, speedMax: Double = 100.0): Path {
    val n = distances.size
    val p = Path(n)
    for (i in distances.indices) {
        p.setDistance(i, distances[i])
        p.setLatitude(i, 0.0)
        p.setLongitude(i, i * 1e-5)  // tiny lon shift for bearing
        p.setElevation(i, 100.0)
        p.setGrade(i, 0.0)
        p.setSpeedMax(i, speedMax)
    }
    return p
}
```

⚠ Test #11 (iteration cap) : créer 200k points en mémoire prend ~ 200 000 × 36 × 8 octets ≈ 57 Mo. Acceptable pour tests JVM, peut être lourd pour Wasm. Si tests Wasm OOM, baisser à 110 000 points.

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/VirtualizeService.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/VirtualizeServiceTest.kt` (≥ 10 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 10 tests verts × 3 targets.
- Pas de boucle infinie (cap à 100 000 itérations).
- Side-effects path corrects (time/elapsed/dx/dt/speed/virtSpeedCurrent/pComputedPower).
- `:elevation:allTests` toujours vert.

## Done when

- [x] `VirtualizeService.kt` créé
- [x] `VirtualizeServiceTest.kt` ≥ 10 tests verts × 3 targets
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Iteration cap testé
- [x] Toutes les checkboxes cochées

## Notes

- **Builder dynamique TS vs fixed-size Kotlin** : le TS fait `new Path()` puis `addPoint(...)` (capacité dynamique). On copie la sémantique en utilisant `Path(n)` (taille fixée par input) et en remplissant `i = 0` à `n-1`. La boucle s'arrête à `n-1` (exclusif) — le dernier point reçoit donc une copie verbatim de l'input (pas de virtualization). Légère divergence avec le TS qui produit `n-1` points (l'index `n-1` n'est jamais ajouté). Test #2 vérifie taille préservée.
- **`speed = 2·dx/dt - speed`** : démontable depuis `dx = (v_old + v_new) × dt/2` → `v_new = 2·dx/dt - v_old`. Identité trapézoïdale.
- **`PowerComputer.getDt` peut être lent** : binary search avec tolérance `dx/1e7`. Pour `dx=10 m, dt≈1 s`, environ 20 itérations de binary search. Acceptable pour quelques milliers de points.
- **`startTimeMs = 0.0`** : on n'utilise pas `Date().getTime()` du TS (non-déterministe). Origine arbitraire à 0, et `elapsed(i) = time(i)`. Plus testable.
- **`computeCyclistPower` post-process** : exécuté APRÈS la boucle principale. Mute `pComputedPower(i)`, `pComputedTotalPower(i)`, `pComputedWheelPower(i)`.
- **`path.computeDerivedData()` final** : recalcule bearing/grade/distance/elapsed depuis lat/lon/elevation/time. Cela **écrase** les `distance` du path source. Acceptable car la trajectoire spatiale reste identique (lat/lon copiés depuis input).
- **`copyAllFields`** : utilise l'accesseur générique `get(i, field)` / `set(j, field, v)` (tâche 11). Coût : 36 × 2 lookups par point. Négligeable face au binary search de `getDt`.
- **Iteration cap 100k** : protection contre boucle infinie si `dx = 0` ou `getDt` ne converge pas. Devrait être inutile en pratique.
- **Préparation tâche 22** : `PointPerSecond` resample le path virtualisé à 1 Hz. Lit `time(i)` et interpole entre les waypoints.
