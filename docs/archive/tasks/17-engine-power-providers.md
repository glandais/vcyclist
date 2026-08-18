# 17 — Engine : `PowerProvider` + 4 implémentations physiques + `AeroProvider` + `CoursePhysics`

## Goal

Introduit la pierre angulaire du moteur physique :

1. **`CoursePhysics`** — extension de `Course` avec **3 providers** : `rhoProvider`, `aeroProvider`, `windProvider`. Le 4ᵉ provider (`cyclistPowerProvider`) sera ajouté en tâche 18.
2. **`AeroProvider`** interface + `AeroProviderConstant` : calcule `aeroCoef = (Cd × A × ρ) / 2` à partir de la config cycliste et de la densité de l'air.
3. **`PowerProvider`** interface : `powerAt(coursePhysics, path, i): Double`.
4. **4 impls physiques** (toutes mutent le `Path` pour stocker leur résultat dans le slot dédié) :
   - **`WheelBearingsPowerProvider`** : `P = -v × (91 + 8.7 × v) / 1000` ; écrit `pWheelBearings(i)`.
   - **`RollingResistancePowerProvider`** : `P = -cos(atan(grade)) × m × g × v × crr` ; écrit `pRollingResistance(i)`.
   - **`GravPowerProvider`** : `P = -m × g × v × sin(atan(grade))` ; écrit `pGravity(i)`.
   - **`AeroPowerProvider`** (modèle Isvan) :
     - sans vent : `P = -aeroCoef × v³` ; écrit `aeroCoef(i)`, `pAero(i)`.
     - avec vent : modèle complet Sheldon Brown / Isvan (lambda + mu=1.2) ; écrit aussi `windSpeed`, `windDirection`, `windBearing`, `windAlpha`.

## Depends on

- `13-engine-cyclist-bike` (`Course`, `EngineConstants.G`)
- `16-engine-rho-wind-providers` (`RhoProvider`, `WindProvider`, `Wind`)
- `12-engine-path` (`Path` + accesseurs `setPAero`, `setPGravity`, etc.)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/PowerProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/aero/AeroProvider.ts` (interface)
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/aero/AeroProviderConstant.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/AeroPowerProvider.ts` (Isvan model — canonique)
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/grav/GravPowerProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/rolling/RollingResistancePowerProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/rolling/WheelBearingsPowerProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/course/Course.ts` (interface `CoursePhysics`)

## Steps

### 1. `CoursePhysics.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/CoursePhysics.kt` :

```kotlin
package io.github.glandais.engine

import io.github.glandais.engine.path.Path
import io.github.glandais.engine.physics.AeroProvider
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProvider
import io.github.glandais.engine.physics.RhoProviderDefault
import io.github.glandais.engine.physics.WindProvider
import io.github.glandais.engine.physics.WindProviderNone

/**
 * Course augmented with the physics providers needed to compute resistive powers
 * (aerodynamic, gravity, rolling, bearings).
 *
 * The 4ᵗʰ provider (`cyclistPowerProvider` — input power from the rider) will be added
 * in task 18 when [CyclistPowerProvider][io.github.glandais.engine.physics.CyclistPowerProvider]
 * is ported.
 */
data class CoursePhysics(
    val course: Course,
    val rhoProvider: RhoProvider = RhoProviderDefault,
    val aeroProvider: AeroProvider = AeroProviderConstant,
    val windProvider: WindProvider = WindProviderNone,
) {
    val path: Path get() = course.path
    val cyclist: Cyclist get() = course.cyclist
    val bike: Bike get() = course.bike
}
```

**Décision** : `CoursePhysics` agrège `Course` plutôt que d'en hériter (`data class` Kotlin n'autorise pas l'héritage cross-`data class`). Les delegate properties exposent `path`, `cyclist`, `bike` directement pour ergonomie.

### 2. `AeroProvider.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/AeroProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * Aerodynamic coefficient `aeroCoef = (Cd × A × ρ) / 2` (kg/m).
 * Consumed by [AeroPowerProvider] to compute drag `P = -aeroCoef × v³` (or its
 * wind-aware Isvan variant).
 */
fun interface AeroProvider {
    fun aeroCoef(course: CoursePhysics, path: Path, pointIndex: Int): Double
}

/**
 * Combines [cyclist.cd][io.github.glandais.engine.Cyclist.cd] ×
 * [cyclist.frontalAreaM2][io.github.glandais.engine.Cyclist.frontalAreaM2] × `ρ` (from
 * [CoursePhysics.rhoProvider]) at each point. Recomputed every call — `ρ` may vary with
 * altitude/temperature (see [RhoProviderEstimate]).
 */
object AeroProviderConstant : AeroProvider {
    override fun aeroCoef(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val rho = course.rhoProvider.rho(course.course, path, pointIndex)
        return (course.cyclist.cd * course.cyclist.frontalAreaM2 * rho) / 2.0
    }
}
```

**Note signature** : `RhoProvider.rho(course: Course, path, i)` — pas `CoursePhysics`. On extrait `course.course`.

### 3. `PowerProvider.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * A source of power (positive or negative) at a point.
 *
 * Conventions :
 * - **Resistive forces** (drag, rolling, gravity climbing, bearings) return **negative** values.
 * - **Assistive forces** (gravity descending) return **positive** values.
 * - **Cyclist input power** (task 18) returns positive values.
 *
 * Implementations may write debug/intermediate values into the [Path] at [pointIndex] as a
 * side-effect (e.g. `path.setPAero(i, p)`). This is intentional — the engine consumes these
 * stored values during virtualization.
 */
fun interface PowerProvider {
    fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double
}
```

### 4. `WheelBearingsPowerProvider.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/WheelBearingsPowerProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path

/**
 * Empirical wheel-bearings friction. `P = -v × (91 + 8.7 × v) / 1000` (W).
 *
 * Always negative (resistive). At 10 m/s (~36 km/h) : ~1.78 W ; at 15 m/s : ~3.32 W. Small
 * compared to drag/rolling but non-negligible at low speed.
 */
object WheelBearingsPowerProvider : PowerProvider {
    override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val speed = path.speed(pointIndex)
        val p = -speed * (91.0 + 8.7 * speed) / 1000.0
        path.setPWheelBearings(pointIndex, p)
        return p
    }
}
```

### 5. `RollingResistancePowerProvider.kt`

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.atan
import kotlin.math.cos

/** `P_rolling = -cos(atan(grade)) × m × g × v × Crr` (W, always ≤ 0). */
object RollingResistancePowerProvider : PowerProvider {
    override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val m = course.cyclist.massKg
        val crr = course.bike.crr
        val grade = path.grade(pointIndex)
        val speed = path.speed(pointIndex)
        val coef = cos(atan(grade))
        val p = -coef * m * EngineConstants.G * speed * crr
        path.setPRollingResistance(pointIndex, p)
        return p
    }
}
```

### 6. `GravPowerProvider.kt`

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import kotlin.math.atan
import kotlin.math.sin

/** `P_gravity = -m × g × v × sin(atan(grade))` (W ; <0 climbing, >0 descending). */
object GravPowerProvider : PowerProvider {
    override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val m = course.cyclist.massKg
        val grade = path.grade(pointIndex)
        val speed = path.speed(pointIndex)
        val p = -m * EngineConstants.G * speed * sin(atan(grade))
        path.setPGravity(pointIndex, p)
        return p
    }
}
```

### 7. `AeroPowerProvider.kt`

Le plus complexe — port direct du modèle Isvan TS. Variante sans vent : `-aeroCoef × v³`. Variante avec vent : voir TS pour la formule complète.

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Aerodynamic drag power. Two regimes :
 *
 * - **No wind** (`wind.speedMS == 0.0`) : `P = -aeroCoef × v³`.
 * - **With wind** : Isvan's lightweight-vehicle power model — accounts for cyclist bearing,
 *   wind direction, combined velocity vector, and turbulence factor `μ = 1.2`.
 *
 * Reference : Isvan, O. (2011) "Power Optimization for the Propulsion of Lightweight Vehicles."
 * https://www.sheldonbrown.com/isvan/Power%20Management%20for%20Lightweight%20Vehicles.pdf
 *
 * Side-effects on the path : `aeroCoef`, `pAero`, and (if windy) `windSpeed`, `windDirection`,
 * `windBearing`, `windAlpha`.
 */
object AeroPowerProvider : PowerProvider {

    private const val MU = 1.2

    override fun powerAt(course: CoursePhysics, path: Path, pointIndex: Int): Double {
        val aeroCoef = course.aeroProvider.aeroCoef(course, path, pointIndex)
        path.setAeroCoef(pointIndex, aeroCoef)

        val wind = course.windProvider.wind(course.course, path, pointIndex)
        val pAir = if (wind.speedMS == 0.0) {
            val v = path.speed(pointIndex)
            -aeroCoef * v * v * v
        } else {
            computeWithWind(path, pointIndex, aeroCoef, wind)
        }
        path.setPAero(pointIndex, pAir)
        return pAir
    }

    private fun computeWithWind(
        path: Path,
        i: Int,
        aeroCoef: Double,
        wind: io.github.glandais.engine.physics.Wind,
    ): Double {
        val speed = path.speed(i)
        val bearing = path.bearing(i)

        path.setWindSpeed(i, wind.speedMS)
        path.setWindDirection(i, wind.directionRad)

        // Wind direction (N=0, E=π/2) → bearing convention (E=0, N=π/2).
        val windDirAsBearing = PI / 2.0 - wind.directionRad
        path.setWindBearing(i, windDirAsBearing)

        val alpha = windDirAsBearing - bearing
        path.setWindAlpha(i, alpha)

        val v = wind.speedMS
        val l1 = speed + v * cos(alpha)
        val l2 = l1 * l1
        val l3 = speed * speed + v * v + 2.0 * speed * v * cos(alpha)
        val l4 = l2 / l3
        val lambda = l4 + MU * (1.0 - l4)
        return -aeroCoef * lambda * sqrt(l3) * l1 * speed
    }
}
```

### 8. Tests

Chaque provider a son test. Structure des tests : créer un `Path(1)` (ou 2), set `speed`, `grade`, `elevation` etc. manuellement, créer un `CoursePhysics(Course(path))` avec defaults, invoquer `provider.powerAt(...)`, vérifier valeur + side-effects.

#### `WheelBearingsPowerProviderTest.kt` (≥ 5 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | v=0 → P=0 | exact |
| 2 | v=10 m/s → `P = -10 × (91 + 87)/1000 = -1.78` | 1e-12 |
| 3 | v=15 m/s → P = `-15 × (91 + 130.5)/1000 = -3.3225` | 1e-12 |
| 4 | P toujours ≤ 0 sur un grid de v ∈ [0, 30] | propriété |
| 5 | side-effect : `path.pWheelBearings(i) == returned` | identité |

#### `RollingResistancePowerProviderTest.kt` (≥ 6 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | v=0 → P=0 | exact |
| 2 | v=10, grade=0, m=80 kg, crr=0.004 → P = `-1 × 80 × 9.8 × 10 × 0.004 = -3.136` | 1e-12 |
| 3 | v=10, grade=0.1 (10% montée) → P < base car cos(atan(0.1)) < 1 | propriété |
| 4 | v=10, grade=−0.1 (descente) → même magnitude que +0.1 | symétrie cos pair |
| 5 | side-effect : `path.pRollingResistance(i) == returned` | identité |
| 6 | v=5, m=85 kg, crr=0.005 → calcul exact | 1e-12 |

#### `GravPowerProviderTest.kt` (≥ 6 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | grade=0 → P=0 | exact |
| 2 | grade=0.05, v=5, m=80 → P = `-80 × 9.8 × 5 × sin(atan(0.05)) ≈ -195.62` | 1e-3 |
| 3 | grade=0.10, v=5, m=80 → P ≈ -390.05 (montée plus raide) | 1e-3 |
| 4 | grade=-0.05 (descente) → P > 0 (gravité aide) | propriété |
| 5 | side-effect : `path.pGravity(i) == returned` | identité |
| 6 | Sentinel : grade=0.05, v=5, m=80 → magnitude attendue ~195 W | 1e-3 |

#### `AeroPowerProviderTest.kt` (≥ 10 tests)

| # | Cas | Attendu |
|---|---|---|
| 1 | v=0, no wind → P=0 | exact |
| 2 | v=10, no wind, Cd=0.7, A=0.5, ρ=1.225 → aeroCoef=0.214375 ; P = `-0.214375 × 1000 = -214.375` | 1e-9 |
| 3 | v=10, no wind → `path.aeroCoef(i) == 0.214375` (side-effect) | 1e-12 |
| 4 | v=10, no wind → `path.pAero(i) == returned` | identité |
| 5 | Tailwind direct (v=10, wind=5 m/s direction parallèle au bearing) → P moins négatif que sans vent | propriété |
| 6 | Headwind (v=10, wind=5 m/s direction opposée) → P plus négatif | propriété |
| 7 | Crosswind (wind perpendiculaire) → P entre les deux extrêmes | propriété |
| 8 | Side-effect avec vent : `windSpeed`, `windDirection`, `windBearing`, `windAlpha` écrits | propriété |
| 9 | Modèle Isvan vs no-wind à wind=0 (`speedMS=0.0`) → emprunte la branche no-wind (test explicite : `Wind(0, π/4)` reste no-wind à cause de `if speedMS == 0`) | branche |
| 10 | Parité numérique TS : `v=10, bearing=0, wind=Wind(5, 0)` (vent du Nord, donc dans le dos en bearing=π/2) — calculer la valeur attendue analytiquement et comparer à 1e-9 | 1e-9 |

### 9. Vérification ktlint

`./gradlew :engine:ktlintFormat` au besoin.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/CoursePhysics.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/AeroProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/PowerProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/WheelBearingsPowerProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/RollingResistancePowerProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/GravPowerProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/AeroPowerProvider.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/{WheelBearingsPowerProviderTest, RollingResistancePowerProviderTest, GravPowerProviderTest, AeroPowerProviderTest}.kt`
- (Optionnel) `CoursePhysicsTest.kt` (≥ 3 tests : defaults, delegate properties, equality)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 30 tests verts cumulés (WheelBearings ≥ 5, Rolling ≥ 6, Grav ≥ 6, Aero ≥ 10, CoursePhysics ≥ 3).
- Parité numérique : valeurs exactes du tableau (tolérance 1e-12 pour les calculs simples, 1e-3 pour les compositions trigonométriques, 1e-9 pour l'aero sans vent).
- Side-effects sur `Path` : tous les `setPAero/setPGravity/setPRollingResistance/setPWheelBearings/setAeroCoef` (+ wind* pour aero) sont vérifiés au moins une fois.
- `:elevation:allTests` toujours vert.

## Done when

- [x] 7 sources commonMain créés
- [x] 4-5 fichiers de tests (≥ 30 tests cumulés)
- [x] `:engine:allTests` vert sur les 3 targets
- [x] `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Side-effects path vérifiés
- [x] Toutes les checkboxes cochées

## Notes

- **`CoursePhysics` data class à 3 champs** : tâche 18 ajoutera `cyclistPowerProvider` comme 4ᵉ champ. Avec named args, pas de breakage.
- **Modèle Isvan avec vent** : formule littéralement portée du TS. La référence (Sheldon Brown PDF) est citée. Tests de parité numérique avec valeurs analytiques pré-calculées.
- **Side-effects sur `Path`** : volontaires — le pipeline TS attend que ces slots soient peuplés au moment de la virtualisation (tâche 21). Documenter dans la doc des providers.
- **`PowerProvider.fun interface`** : SAM. Permet `PowerProvider { _, _, _ -> 0.0 }` en tests / mocks.
- **`PI` from `kotlin.math.PI`** : disponible en common.
- **`AeroPowerProvider` test #10** : calculer la valeur attendue à la main avec la formule, puis hardcoder dans le test. Évite la circularité (tester l'impl avec elle-même).
- **Pas de `MuscularPowerProvider` etc. ici** : ce sont les cyclist power providers (input du rider), pas les resistive forces. Traités en tâche 18.
- **Préparation tâche 18** : `CyclistPowerProvider` interface + 4 impls (Constant, ConstantWithTiring, FromData, Muscular).
- **Préparation tâche 19** : `PowerComputer` agrège tous les `PowerProvider` (4 resistive + 1 cyclist input).
