# 19 — Engine : `PowerComputer` (équation cinétique + masse équivalente)

## Goal

Centraliser les calculs d'**énergie cinétique** et de **balance de puissance** :

1. **`PowerComputer`** (`object`) — stateless utility.
2. **`getNewPower(course, path, i, withCyclist)`** : somme des 4 powers résistifs (WheelBearings + Rolling + Aero + Grav) ± `MuscularPowerProvider` (cyclist input). Side-effect : tous les sub-providers écrivent leurs slots respectifs sur le path.
3. **`getDx(pSum, mEq, currentSpeed, dt)`** : intègre une vitesse cinématique sur `dt` secondes. `v_new = √(v_old² + 2·dt·P / mEq)`, clampé à `MINIMAL_SPEED` ; retourne `Δx = (v_old + v_new) × dt / 2`.
4. **`getTotPower(mEq, s1, s2, dt)`** : `P = 0.5 × mEq × (s2² - s1²) / dt` (inverse de getDx).
5. **`getDt(pSum, mEq, currentSpeed, dx)`** : recherche dichotomique du `dt` qui produit `dx` cible. Utilisé par `VirtualizeService` (tâche 21) pour l'alignement GPS.
6. **`computeCyclistPower(course, path, mEq, i)`** : problème inverse — étant donné un Δv mesuré, calculer la puissance cycliste qu'il a fallu fournir. Side-effects sur `pComputedTotalPower`, `pComputedWheelPower`, `pComputedPower`.
7. **`equivalentMass(course)`** : `m_eq = m_kg + (I_front + I_rear) / r²`.

## Depends on

- `17-engine-power-providers` (`WheelBearings/Rolling/Grav/AeroPowerProvider`)
- `18-engine-cyclist-power-providers` (`MuscularPowerProvider`)
- `13-engine-cyclist-bike` (`EngineConstants.MINIMAL_SPEED`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/PowerComputer.ts` (canonique, port verbatim)

## Steps

### 1. `PowerComputer.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerComputer.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stateless power-energy bridge. Sums [PowerProvider] outputs into a total power balance,
 * integrates it into a kinematic speed (`v_new² = v_old² + 2·dt·P / m_eq`), and provides
 * the inverse (`P` from a measured Δv).
 *
 * Singleton-style `object` since there's no state. All methods take an explicit `Path`.
 */
object PowerComputer {

    /**
     * Sum of resistive powers (always 4 providers) ± cyclist muscular power.
     *
     * Side-effects : every sub-provider mutates the path at [pointIndex] with its own
     * intermediate value (cf. tasks 17/18).
     */
    fun getNewPower(
        course: CoursePhysics,
        path: Path,
        pointIndex: Int,
        withCyclist: Boolean,
    ): Double {
        var pSum = 0.0
        pSum += WheelBearingsPowerProvider.powerAt(course, path, pointIndex)
        pSum += RollingResistancePowerProvider.powerAt(course, path, pointIndex)
        pSum += AeroPowerProvider.powerAt(course, path, pointIndex)
        pSum += GravPowerProvider.powerAt(course, path, pointIndex)
        if (withCyclist) {
            pSum += MuscularPowerProvider.powerAt(course, path, pointIndex)
        }
        return pSum
    }

    /**
     * Energy-conservation integrator. Computes the distance travelled during [dt] s given
     * the current power balance [pSum] and current [currentSpeed].
     *
     * `v_new = max(√(v_old² + 2·dt·P / m_eq), MINIMAL_SPEED)`, `Δx = (v_old + v_new)·dt/2`.
     */
    fun getDx(pSum: Double, equivalentMass: Double, currentSpeed: Double, dt: Double): Double {
        val newSpeed = max(
            sqrt((dt * pSum) / (0.5 * equivalentMass) + currentSpeed * currentSpeed),
            EngineConstants.MINIMAL_SPEED,
        )
        return (currentSpeed + newSpeed) * dt / 2.0
    }

    /** `P = 0.5 × m_eq × (s2² − s1²) / dt`. Inverse of [getDx]. */
    fun getTotPower(equivalentMass: Double, s1: Double, s2: Double, dt: Double): Double =
        (0.5 * equivalentMass * (s2 * s2 - s1 * s1)) / dt

    /**
     * Find the time step that produces [dx] meters given the current power balance.
     *
     * Two-step search :
     * 1. Exponential walk : start `dt = 0.1 s`, increment by `0.1` until `getDx(dt) > dx`.
     * 2. Binary search between `[dt − 0.1, dt]` until `dt2 − dt1 < dx / 10_000_000`.
     *
     * Used by `VirtualizeService` (task 21) to align computed waypoints with GPS source.
     */
    fun getDt(pSum: Double, equivalentMass: Double, currentSpeed: Double, dx: Double): Double {
        var dt = 0.1
        while (getDx(pSum, equivalentMass, currentSpeed, dt) <= dx) {
            dt += 0.1
        }
        return getDtInner(pSum, equivalentMass, currentSpeed, dx, dt - 0.1, dt)
    }

    private fun getDtInner(
        pSum: Double,
        equivalentMass: Double,
        currentSpeed: Double,
        dx: Double,
        dt1Init: Double,
        dt2Init: Double,
    ): Double {
        var dt1 = dt1Init
        var dt2 = dt2Init
        val tol = dx / 10_000_000.0
        while (dt2 - dt1 >= tol) {
            val dtMid = (dt1 + dt2) / 2.0
            val dxMid = getDx(pSum, equivalentMass, currentSpeed, dtMid)
            if (dxMid < dx) dt1 = dtMid else dt2 = dtMid
        }
        return (dt1 + dt2) / 2.0
    }

    /**
     * Inverse problem : compute the cyclist power that explains the measured Δv between
     * point i-1 and i. Writes `pComputedTotalPower`, `pComputedWheelPower`, `pComputedPower`.
     *
     * For `i == 0`, sets `pComputedPower(0) = 0.0` and returns.
     */
    fun computeCyclistPower(course: CoursePhysics, path: Path, equivalentMass: Double, i: Int) {
        if (i == 0) {
            path.setPComputedPower(i, 0.0)
            return
        }
        val resistive = getNewPower(course, path, i - 1, withCyclist = false)
        val s1 = path.speed(i - 1)
        val s2 = path.speed(i)
        val dtSeconds = path.dt(i) / 1000.0
        val totPower = getTotPower(equivalentMass, s1, s2, dtSeconds)
        path.setPComputedTotalPower(i, totPower)

        val powerWheel = totPower - resistive
        path.setPComputedWheelPower(i, powerWheel)

        val computed = max(0.0, powerWheel) / course.bike.efficiency
        path.setPComputedPower(i, computed)
    }

    /** `m_eq = m_kg + (I_front + I_rear) / r²`. Accounts for rotational inertia of wheels. */
    fun equivalentMass(course: Course): Double {
        val m = course.cyclist.massKg
        val totalInertia = course.bike.inertiaFront + course.bike.inertiaRear
        return m + totalInertia / (course.bike.wheelRadiusM * course.bike.wheelRadiusM)
    }

    /** Convenience overload — accepts [CoursePhysics] for symmetry. */
    fun equivalentMass(course: CoursePhysics): Double = equivalentMass(course.course)
}
```

### 2. Reactivation `CyclistPowerProviderBase.pCyclistPowerNeeded`

À ce stade, **tâche 18 différait l'écriture** de `pCyclistPowerNeeded`. Le TS la calcule via `PowerComputer.getNewPower(..., withCyclist=false)` et la nie (`powerNeeded = -resistive`). On l'ajoute maintenant à `CyclistPowerProviderBase.powerAt` :

```kotlin
// dans CyclistPowerProviderBase.powerAt, juste avant `return power`:
val powerNeeded = -PowerComputer.getNewPower(course, path, pointIndex, withCyclist = false)
path.setPCyclistPowerNeeded(pointIndex, powerNeeded)
```

⚠ **Attention** : ce side-effect appelle les 4 resistive providers, qui eux-mêmes écrivent leurs slots. C'est cohérent avec le TS — la séquence est :
1. Cyclist `optimalPower` → set `pCyclistProvidedOptimalPower`
2. Harmoniques → set `pCyclistProvidedOptimalPowerWithHarmonics`
3. `getNewPower(..., false)` → écrit `pWheelBearings`, `pRolling`, `pAero`, `pGravity`, `aeroCoef`
4. Set `pCyclistPowerNeeded`
5. Retourne power

### 3. Tests `PowerComputerTest.kt`

Cas à couvrir (≥ 15) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `equivalentMass(Course(...))` defaults → `80 + 0.12 / 0.49 ≈ 80.2449` | 1e-9 |
| 2 | `equivalentMass(custom)` : `m=70, IF=0.04, IR=0.06, r=0.5` → `70 + 0.10/0.25 = 70.4` | exact |
| 3 | `getDx(pSum=0, mEq=80, v=10, dt=1)` → `(10+10) × 1/2 = 10` m (vitesse constante) | 1e-9 |
| 4 | `getDx(pSum=100, mEq=80, v=10, dt=1)` → accélère légèrement, dx ≈ 10.06 m | 1e-3 |
| 5 | `getDx(pSum=-1000, mEq=80, v=10, dt=1)` → décélère, v_new < 10 | propriété |
| 6 | `getDx(pSum=-1e6, mEq=80, v=10, dt=1)` → v_new clampé à MINIMAL_SPEED | propriété |
| 7 | `getTotPower(mEq=80, s1=10, s2=11, dt=1)` → `0.5 × 80 × (121 − 100) / 1 = 840` W | exact |
| 8 | `getTotPower` symétrie : `getTotPower(s2, s1)` négatif de `getTotPower(s1, s2)` | propriété |
| 9 | `getDt` round-trip : `getDx(getDt(...), ...)` ≈ dx cible à 1e-6 | propriété |
| 10 | `getDt(pSum=0, mEq=80, v=10, dx=100)` → `dt = 10` s (à v constante) | 1e-6 |
| 11 | `getNewPower(course, path, 0, withCyclist=false)` à v=10, grade=0 : somme négative (résistances) | propriété |
| 12 | `getNewPower(course, path, 0, withCyclist=true)` : ajoute MuscularPowerProvider | propriété |
| 13 | Side-effects path après `getNewPower` : `pAero`, `pGravity`, `pRolling`, `pWheelBearings`, `aeroCoef` tous remplis | propriété |
| 14 | `computeCyclistPower(i=0)` : `pComputedPower(0) == 0.0`, pas d'autres mutations | exact |
| 15 | `computeCyclistPower(i=1)` avec scénario simple : vérifier `pComputedPower(1) ≥ 0` (clamp), valeurs cohérentes | propriété |

Test #15 — scénario reproductible :
- `path = Path(2)`, `setSpeed(0, 5.0)`, `setSpeed(1, 5.5)`, `setDt(1, 1000.0)` (1 s)
- `course = CoursePhysics(Course(path))`
- Appeler `computeCyclistPower(course, path, mEq, 1)`
- Vérifier `pComputedTotalPower(1)` ≈ `getTotPower(mEq, 5, 5.5, 1)` à 1e-9
- Vérifier `pComputedPower(1) ≥ 0` et `≤ pComputedWheelPower(1) / 0.976 + ε`

### 4. Test régression `CyclistPowerProviderBaseTest`

Ajouter un test : après `powerAt`, `path.pCyclistPowerNeeded(i)` est non-nul (sauf si résistances totales nulles).

### 5. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerComputer.kt`

Modifiés :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/CyclistPowerProviderBase.kt` (ajout 2 lignes pour `pCyclistPowerNeeded`)

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/PowerComputerTest.kt` (≥ 15 tests)
- Régression : `CyclistPowerProviderBaseTest` enrichi d'1 test `pCyclistPowerNeeded`

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 15 tests `PowerComputerTest` verts × 3 targets.
- Tâche 18 toujours verte (régression `pCyclistPowerNeeded` ajoutée mais sémantique étendue).
- `:elevation:allTests` toujours vert.

## Done when

- [x] `PowerComputer.kt` créé
- [x] `CyclistPowerProviderBase` enrichi de l'écriture `pCyclistPowerNeeded`
- [x] `PowerComputerTest.kt` ≥ 15 tests verts × 3 targets
- [x] Tâche 18 non-régression : tous tests existants + 1 test régression `pCyclistPowerNeeded` verts
- [x] `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **`object PowerComputer`** : pas de singleton instance — `object` Kotlin est singleton natif.
- **`getDt` binary search** : convergence `dx / 10_000_000` (très serré). Pour `dx ≈ 10 m`, tolérance ≈ 1e-6 s. Suffisant pour la précision GPS.
- **`max(0.0, powerWheel) / efficiency`** : clamp positif (cyclist input ne peut pas être négatif). Le TS fait pareil.
- **`getNewPower` mute le path** : c'est attendu — les sub-providers écrivent leurs slots. Le test #13 le verrouille.
- **Régression tâche 18** : `pCyclistPowerNeeded` était à 0.0 par défaut. Désormais écrit. Si un test de tâche 18 vérifie explicitement `pCyclistPowerNeeded == 0.0`, il faut le mettre à jour. Probable que ce ne soit pas le cas.
- **Préparation tâche 20** : `MaxSpeedComputer` consomme `PowerComputer.getDt` indirectement pour le calcul des distances de freinage.
- **Préparation tâche 21** : `VirtualizeService` utilise `PowerComputer.getNewPower` + `getDt` à chaque pas de simulation.
- **`Test #15 mEq` calculation** : `mEq = 80 + 0.12/0.49 ≈ 80.2449`. Le test utilise `equivalentMass(course)` pour calculer puis passe au `computeCyclistPower`.
