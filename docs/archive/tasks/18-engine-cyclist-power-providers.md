# 18 — Engine : `CyclistPowerProvider` + 4 impls + `MuscularPowerProvider`

## Goal

Compléter le panel de providers physiques avec **la source de puissance du cycliste** :

1. **`Harmonic`** data class (freq, phase, amp) — modélisation de variations sinusoïdales.
2. **`CyclistPowerProvider`** interface (sous-type de `PowerProvider`).
3. **`CyclistPowerProviderBase`** (`abstract class`) : pipeline commun `getOptimalPower → harmonics → speed-based adjustment`. Note importante : la branche « speed-based adjustment » (`getRealOptimalPower`) est **présente mais inactive** côté TS (le `return optimalPower` la court-circuite). On porte fidèlement.
4. **4 impls** :
   - **`PowerProviderConstant(power, useHarmonics)`** : retourne `power` constant.
   - **`PowerProviderConstantWithTiring(power, useHarmonics, durationS)`** : fatigue linéaire `c = max(0.5, 1 - 0.6 × elapsed/duration)`.
   - **`PowerProviderFromData`** (`object`) : lit `path.pInputPower(i)`. Pas d'`harmonics`, pas de pipeline.
   - **`MuscularPowerProvider`** (`object`) : pont muscular → wheel via `bike.efficiency`. Lit la puissance via `course.cyclistPowerProvider`.
5. **`CoursePhysics` mis à jour** : ajout du 4ᵉ champ `cyclistPowerProvider: CyclistPowerProvider`.

Side-effects path à écrire (cf. TS) :
- `pCyclistProvidedOptimalPower(i)` : valeur retournée par `getOptimalPower`
- `pCyclistProvidedOptimalPowerWithHarmonics(i)` : après harmoniques
- `pCyclistProvidedMuscular(i)` : valeur entrée du `MuscularPowerProvider`
- `pCyclistProvidedWheel(i)` : valeur sortie (après `× efficiency`)
- `pCyclistPowerNeeded(i)` : **différé en tâche 19** (nécessite `PowerComputer`, créé alors). Stub `0.0` ou simplement non-écrit ici.

## Depends on

- `17-engine-power-providers` (`CoursePhysics`, `PowerProvider`)
- `13-engine-cyclist-bike` (`Bike.efficiency`, `EngineConstants.G`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/CyclistPowerProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/CyclistPowerProviderBase.ts` (canonique pour le pipeline harmoniques + speed-adjust)
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/Harmonic.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/PowerProviderConstant.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/PowerProviderConstantWithTiring.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/PowerProviderFromData.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/cyclist/MuscularPowerProvider.ts`

## Steps

### 1. `Harmonic.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/Harmonic.kt` :

```kotlin
package io.github.glandais.engine.physics

/**
 * Harmonic oscillation component for power variation modeling.
 *
 * Applied as : `P' = P + amp × P × cos(freq × t − phase)`.
 *
 * @param freqRadS oscillation frequency in radians per second (typically 1.0–10.0)
 * @param phaseRad phase offset in radians (0 to π)
 * @param amp amplitude factor (dimensionless, typically 0–0.01 → up to 1 % variation)
 */
data class Harmonic(val freqRadS: Double, val phaseRad: Double, val amp: Double)
```

### 2. `CyclistPowerProvider.kt` + base

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/CyclistPowerProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

/**
 * Marker subtype of [PowerProvider] for cyclist input power (positive values, before
 * drivetrain losses).
 */
fun interface CyclistPowerProvider : PowerProvider
```

### 3. `CyclistPowerProviderBase.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/CyclistPowerProviderBase.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Abstract base for cyclist power providers : applies optional harmonic variations on top of
 * a subclass-defined optimal power.
 *
 * Speed-based adjustment ([getRealOptimalPower]) is implemented but **not called** at this
 * stage — the TS reference comments it out and returns `optimalPower` directly. Will be
 * reactivated in task 19 once [PowerComputer] is available.
 *
 * Harmonics : when [useHarmonics] is true, 20 random harmonics are generated at construction
 * with frequencies 1–10 rad/s, phases 0–π, amplitudes 0–0.01.
 *
 * @param useHarmonics enable harmonic variations
 * @param random RNG used for harmonic generation (injectable for deterministic tests)
 */
abstract class CyclistPowerProviderBase(
    val useHarmonics: Boolean,
    random: Random = Random.Default,
) : CyclistPowerProvider {

    private val harmonics: List<Harmonic> = if (useHarmonics) {
        List(20) {
            Harmonic(
                freqRadS = 1.0 + random.nextDouble() * 9.0,
                phaseRad = random.nextDouble() * PI,
                amp = random.nextDouble() * 0.01,
            )
        }
    } else {
        emptyList()
    }

    /** Baseline power before harmonics. Subclasses choose : constant, tiring, from-data, etc. */
    protected abstract fun optimalPower(course: CoursePhysics, path: Path, pointIndex: Int): Double

    final override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        var power = optimalPower(course, path, pointIndex)
        path.setPCyclistProvidedOptimalPower(pointIndex, power)

        if (useHarmonics) {
            val x = path.time(pointIndex) / 10000.0
            for (h in harmonics) {
                power += h.amp * power * cos(h.freqRadS * x - h.phaseRad)
            }
        }
        path.setPCyclistProvidedOptimalPowerWithHarmonics(pointIndex, power)

        // Note: speed-based adjustment (getRealOptimalPower) intentionally skipped to mirror TS.
        // Will be re-enabled in task 19 with PowerComputer access for `powerNeeded`.
        return power
    }

    /**
     * Speed-based adjustment (not currently active). Reproduces the TS algorithm verbatim :
     * within ±5 % of optimal → use as-is ; too slow → up to 3× boost (linear) ; too fast →
     * decrease to 0 (linear).
     *
     * Will be called from [powerAt] in task 19 once `PowerComputer.getNewPower` exists.
     */
    protected fun getRealOptimalPower(optimalPower: Double, powerNeeded: Double): Double {
        val min = optimalPower * (1.0 - TOLERANCE)
        val max = optimalPower * (1.0 + TOLERANCE)
        return when {
            powerNeeded in min..max -> optimalPower
            powerNeeded < min -> optimalPower * MAX_MULTIPLIER -
                (powerNeeded / min) * optimalPower * (MAX_MULTIPLIER - 1.0)
            else -> {
                val diff = powerNeeded - max
                val coef = (diff / max).coerceIn(0.0, 1.0)
                optimalPower - coef * optimalPower
            }
        }
    }

    companion object {
        private const val TOLERANCE = 0.05
        private const val MAX_MULTIPLIER = 3.0
    }
}
```

### 4. Quatre impls

`PowerProviderConstant.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.random.Random

class PowerProviderConstant(
    val power: Double,
    useHarmonics: Boolean = false,
    random: Random = Random.Default,
) : CyclistPowerProviderBase(useHarmonics, random) {
    override fun optimalPower(course: CoursePhysics, path: Path, pointIndex: Int): Double = power
}
```

`PowerProviderConstantWithTiring.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.max
import kotlin.random.Random

/** Fatigue : power linearly decays to 50 % after [durationSeconds]. */
class PowerProviderConstantWithTiring(
    val power: Double,
    useHarmonics: Boolean = false,
    val durationSeconds: Double,
    random: Random = Random.Default,
) : CyclistPowerProviderBase(useHarmonics, random) {
    init { require(durationSeconds > 0.0) { "durationSeconds must be > 0, got $durationSeconds" } }

    override fun optimalPower(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val elapsedS = path.elapsed(pointIndex) / 1000.0
        val c = max(0.5, 1.0 - 0.6 * elapsedS / durationSeconds)
        return power * c
    }
}
```

`PowerProviderFromData.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/** Reads `path.pInputPower(i)` verbatim. No harmonics, no pipeline. */
object PowerProviderFromData : CyclistPowerProvider {
    override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double =
        path.pInputPower(pointIndex)
}
```

`MuscularPowerProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/** Bridge `muscular → wheel` : applies drivetrain efficiency. Reads cyclist power from
 *  `course.cyclistPowerProvider`. */
object MuscularPowerProvider : PowerProvider {
    override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val muscular = course.cyclistPowerProvider.powerAt(course, path, pointIndex)
        path.setPCyclistProvidedMuscular(pointIndex, muscular)

        val wheel = muscular * course.bike.efficiency
        path.setPCyclistProvidedWheel(pointIndex, wheel)
        return wheel
    }
}
```

### 5. `CoursePhysics` enrichi

Modifier `engine/src/commonMain/kotlin/io/github/glandais/engine/CoursePhysics.kt` pour ajouter le 4ᵉ champ :

```kotlin
data class CoursePhysics(
    val course: Course,
    val rhoProvider: RhoProvider = RhoProviderDefault,
    val aeroProvider: AeroProvider = AeroProviderConstant,
    val windProvider: WindProvider = WindProviderNone,
    val cyclistPowerProvider: CyclistPowerProvider = PowerProviderConstant(
        EngineConstants.DEFAULT_CYCLIST_POWER_W,
    ),
) { ... existing delegates ... }
```

**Note** : `PowerProviderConstant` est une `class` (state harmonique) — instancier comme default n'est pas idéal (alloue à chaque appel). Acceptable car défaut rarement utilisé. Alternative : un singleton `PowerProviderConstantDefault280W` ; pas nécessaire pour cette tâche.

### 6. Tests

Structure : un fichier par provider + 1 fichier pour `CyclistPowerProviderBase` (vérifier le pipeline harmoniques + side-effects).

#### `PowerProviderConstantTest.kt` (≥ 5 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | `PowerProviderConstant(250).powerAt(...)` → 250.0 (sans harmoniques) | exact |
| 2 | Side-effect `path.pCyclistProvidedOptimalPower == 250` | identité |
| 3 | Side-effect `path.pCyclistProvidedOptimalPowerWithHarmonics == 250` (sans harmoniques) | identité |
| 4 | Avec `useHarmonics=true`, RNG fixé (`Random(42)`) → résultat déterministe ≠ 250 | propriété |
| 5 | Avec harmoniques, déviation < 25 % du base power (20 harm × 0.01 amp max) | propriété |

#### `PowerProviderConstantWithTiringTest.kt` (≥ 6 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | Au temps elapsed=0 → power = base | exact |
| 2 | Au temps elapsed = duration/2 → power = base × 0.7 | 1e-12 |
| 3 | Au temps elapsed = duration → power = base × 0.4 ? Non : `c = max(0.5, 1 - 0.6 × 1) = 0.4` → max donne 0.5. **Attention** : c = max(0.5, 1-0.6) = max(0.5, 0.4) = 0.5 | 1e-12 |
| 4 | Au temps elapsed > duration → power = base × 0.5 (plancher) | exact |
| 5 | `durationSeconds=0` → IllegalArgumentException | requires |
| 6 | Side-effect `pCyclistProvidedOptimalPower` matche | identité |

#### `PowerProviderFromDataTest.kt` (≥ 3 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | `path.setPInputPower(0, 150.0)` → `powerAt(0) == 150.0` | exact |
| 2 | Aucun side-effect optimal/harmonics écrit (Object ne passe pas par le pipeline) | propriété |
| 3 | Power inputé 0 → retour 0 | exact |

#### `MuscularPowerProviderTest.kt` (≥ 4 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | Cyclist provider retourne 200 W, bike.efficiency=0.976 → wheel = 195.2 W | 1e-12 |
| 2 | Side-effect `pCyclistProvidedMuscular == 200` | identité |
| 3 | Side-effect `pCyclistProvidedWheel == 195.2` | identité |
| 4 | Identité avec `bike.efficiency=1.0` → muscular == wheel | propriété |

#### `CyclistPowerProviderBaseTest.kt` (≥ 4 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | `getRealOptimalPower(optimal=200, powerNeeded=190)` (5 % below → in tolerance) → 200 | exact |
| 2 | `getRealOptimalPower(optimal=200, powerNeeded=100)` (50 % below) → boost > 200 | propriété |
| 3 | `getRealOptimalPower(optimal=200, powerNeeded=300)` (50 % above) → power réduit | propriété |
| 4 | RNG injectable : 2 instances avec `Random(seed)` identique → mêmes harmoniques | déterminisme |

### 7. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/Harmonic.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/CyclistPowerProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/CyclistPowerProviderBase.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerProviderConstant.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerProviderConstantWithTiring.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerProviderFromData.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/MuscularPowerProvider.kt`

Modifiés :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/CoursePhysics.kt` (ajout `cyclistPowerProvider` field + import)

Tests (commonTest) :

- 5 fichiers de tests (~ 22 tests cumulés)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 22 tests verts cumulés × 3 targets.
- Side-effects path vérifiés.
- Harmoniques déterministes (RNG injectable).
- `CoursePhysics` rétro-compatible : tests existants de tâche 17 toujours verts (ils utilisent `CoursePhysics(course)` avec defaults, donc le nouveau champ a un default).
- `:elevation:allTests` toujours vert.

## Done when

- [x] 7 sources commonMain créés
- [x] `CoursePhysics` mis à jour avec 4ᵉ champ
- [x] 5 fichiers tests (≥ 22 tests)
- [x] `:engine:allTests` vert × 3 targets ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] RNG injectable testé (déterminisme)
- [x] Tests existants tâche 17 toujours verts (non-régression)
- [x] Toutes les checkboxes cochées

## Notes

- **Random KMP-safe** : `kotlin.random.Random` est disponible sur les 3 targets. `Random(seed)` est déterministe.
- **`PowerProviderFromData` ne passe pas par `CyclistPowerProviderBase`** : c'est un `object` simple qui implémente `CyclistPowerProvider` directement. Pas de side-effects sur les slots `optimal/withHarmonics` — c'est volontaire (le power est déjà dans le path).
- **Branche `getRealOptimalPower` morte** : le TS la garde commentée. On la porte (pour parité de code visuel) mais on ne l'appelle pas. Test #1-#3 de `CyclistPowerProviderBaseTest` vérifient sa logique en isolation (méthode `protected` → exposer via une sous-classe de test).
- **Pour exposer `getRealOptimalPower` en test** : créer un `class TestableBase(useHarmonics = false) : CyclistPowerProviderBase(false) { fun exposeRealOptimal(o, p) = getRealOptimalPower(o, p) ; override fun optimalPower(...) = 0.0 }` dans le fichier de test.
- **`pCyclistPowerNeeded` non écrit** : différé en tâche 19. Ne PAS écrire `path.setPCyclistPowerNeeded(i, 0.0)` à la place — laisse le slot à sa valeur d'init (0.0). Documenter.
- **`CoursePhysics` 4ᵉ champ** : ajouté avec default `PowerProviderConstant(280)`. Tests tâche 17 utilisent `CoursePhysics(Course(path))` → ne breakent pas.
- **Préparation tâche 19** : `PowerComputer` agrège (resistive + muscular) et calcule `getNewPower` (le `powerNeeded` que le rider doit produire pour atteindre une vitesse cible). Sera consommé par `CyclistPowerProviderBase.getPowerW` (à ré-activer alors).
