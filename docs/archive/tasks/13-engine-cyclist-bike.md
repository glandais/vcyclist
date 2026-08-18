# 13 — Engine : `Cyclist`, `Bike`, `Course`, constantes physiques

## Goal

Poser les **modèles de domaine** statiques (immutables) du module `:engine` :

- `Cyclist` : masse système, max brake, drag coefficient, frontal area, max lean angle, max speed.
- `Bike` : rolling resistance coefficient, inerties roues, rayon de roue, efficacité transmission.
- `Course` : agrégat `Path + Cyclist + Bike`.
- `EnhanceOptions` + `SimplifyPathOptions` : data classes d'options pour le pipeline (consommées plus tard par `Enhancer` en tâche 25).
- `EngineConstants` : G, MINIMAL_SPEED, constantes physiques par défaut (issues du TS `constants.ts`).

**Hors scope** : `CoursePhysics` (qui agrège Course + 4 providers) — les providers `RhoProvider`/`AeroProvider`/`WindProvider`/`CyclistPowerProvider` sont introduits en tâches 16-19, donc `CoursePhysics` sera assemblé en tâche 19 ou 20.

## Depends on

- `10-engine-field-definitions`, `11-engine-codegen-strategy`, `12-engine-path` (le `Path` est requis par `Course`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/constants/constants.ts` — toutes les constantes (G, defaults Crr/cd/A/mass/etc.)
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/models/Cyclist.ts` — classe + méthodes utilitaires
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/models/Bike.ts` — idem
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/course/Course.ts` — interfaces Course, CoursePhysics, EnhanceOptions, SimplifyPathOptions

## Steps

### 1. `EngineConstants.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/EngineConstants.kt` :

```kotlin
package io.github.glandais.engine

import kotlin.math.PI

/**
 * Physics constants and default cyclist/bike parameters. Ported from the TS
 * `constants.ts`. Values are validated against academic cycling research — do not change
 * lightly without updating the parity tests (task 26).
 */
object EngineConstants {

    // ---- Fundamental physics --------------------------------------------------

    /** Standard gravitational acceleration (m/s²). */
    const val G: Double = 9.8

    /** Below this speed (m/s ~= 0.555 → 2 km/h), some physics calculations become unstable. */
    const val MINIMAL_SPEED: Double = 2.0 / 3.6

    // ---- Bike defaults --------------------------------------------------------

    const val DEFAULT_CRR: Double = 0.004
    const val DEFAULT_INERTIA_FRONT: Double = 0.05
    const val DEFAULT_INERTIA_REAR: Double = 0.07
    const val DEFAULT_WHEEL_RADIUS_M: Double = 0.7
    const val DEFAULT_DRIVETRAIN_EFFICIENCY: Double = 0.976

    // ---- Cyclist defaults -----------------------------------------------------

    const val DEFAULT_CYCLIST_MASS_KG: Double = 80.0
    const val DEFAULT_CYCLIST_POWER_W: Double = 280.0
    const val DEFAULT_MAX_BRAKE_G: Double = 0.6
    const val DEFAULT_MAX_LEAN_ANGLE_DEG: Double = 35.0
    val DEFAULT_MAX_LEAN_ANGLE_RAD: Double = DEFAULT_MAX_LEAN_ANGLE_DEG * PI / 180.0
    const val DEFAULT_MAX_SPEED_KMH: Double = 100.0

    // ---- Aerodynamics ---------------------------------------------------------

    const val DEFAULT_DRAG_COEFFICIENT: Double = 0.7
    const val DEFAULT_FRONTAL_AREA_M2: Double = 0.5
    const val DEFAULT_AIR_DENSITY: Double = 1.225
}
```

### 2. `Cyclist.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/Cyclist.kt` :

```kotlin
package io.github.glandais.engine

import kotlin.math.PI
import kotlin.math.tan

/**
 * Cyclist parameters for virtual cycling simulations.
 *
 * @param massKg Total system mass (cyclist + bike) in kilograms
 * @param maxBrakeG Maximum braking deceleration in g-units (multiplied by [EngineConstants.G] to get m/s²)
 * @param cd Aerodynamic drag coefficient (dimensionless)
 * @param frontalAreaM2 Frontal area for aerodynamic calculations (m²)
 * @param maxLeanAngleDeg Maximum lean angle in degrees for cornering
 * @param maxSpeedKmH Maximum speed capability in km/h
 */
data class Cyclist(
    val massKg: Double = EngineConstants.DEFAULT_CYCLIST_MASS_KG,
    val maxBrakeG: Double = EngineConstants.DEFAULT_MAX_BRAKE_G,
    val cd: Double = EngineConstants.DEFAULT_DRAG_COEFFICIENT,
    val frontalAreaM2: Double = EngineConstants.DEFAULT_FRONTAL_AREA_M2,
    val maxLeanAngleDeg: Double = EngineConstants.DEFAULT_MAX_LEAN_ANGLE_DEG,
    val maxSpeedKmH: Double = EngineConstants.DEFAULT_MAX_SPEED_KMH,
) {
    /** Tangent of the max lean angle — used in cornering physics (`v_max² = g·R·tan(θ)`). */
    val tanMaxLeanAngle: Double get() = tan(maxLeanAngleDeg * PI / 180.0)

    /** Max lean angle in radians. */
    val maxLeanAngleRad: Double get() = maxLeanAngleDeg * PI / 180.0

    /** Max braking deceleration in m/s². */
    val maxBrakeMS2: Double get() = maxBrakeG * EngineConstants.G

    /** Max speed in m/s (km/h → m/s : ÷ 3.6). */
    val maxSpeedMS: Double get() = maxSpeedKmH / 3.6

    /** Aerodynamic drag area `CdA = cd × frontalArea` (m²). */
    val aerodynamicDragArea: Double get() = cd * frontalAreaM2

    companion object {
        /** Default cyclist : 80 kg system, recreational/intermediate parameters. */
        val DEFAULT = Cyclist()
    }
}
```

### 3. `Bike.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/Bike.kt` :

```kotlin
package io.github.glandais.engine

import kotlin.math.PI

/**
 * Bike parameters for virtual cycling simulations.
 *
 * @param crr Rolling resistance coefficient (dimensionless)
 * @param inertiaFront Front wheel rotational inertia (kg·m²)
 * @param inertiaRear Rear wheel rotational inertia (kg·m²)
 * @param wheelRadiusM Wheel radius in meters (default 0.7 = 700c with 25mm tire)
 * @param efficiency Drivetrain efficiency (0..1, dimensionless)
 */
data class Bike(
    val crr: Double = EngineConstants.DEFAULT_CRR,
    val inertiaFront: Double = EngineConstants.DEFAULT_INERTIA_FRONT,
    val inertiaRear: Double = EngineConstants.DEFAULT_INERTIA_REAR,
    val wheelRadiusM: Double = EngineConstants.DEFAULT_WHEEL_RADIUS_M,
    val efficiency: Double = EngineConstants.DEFAULT_DRIVETRAIN_EFFICIENCY,
) {
    /** Sum of front and rear wheel rotational inertias (kg·m²). */
    val totalInertia: Double get() = inertiaFront + inertiaRear

    /** Wheel diameter (m). */
    val wheelDiameterM: Double get() = 2.0 * wheelRadiusM

    /** Wheel circumference `2πr` (m). */
    val wheelCircumferenceM: Double get() = 2.0 * PI * wheelRadiusM

    /** Equivalent linear mass from rotating wheels : `I_total / r²` (kg). */
    val equivalentMass: Double get() = totalInertia / (wheelRadiusM * wheelRadiusM)

    /** `1 - efficiency` — fraction of input power lost in the drivetrain. */
    val powerLossFactor: Double get() = 1.0 - efficiency

    /** Power delivered to the rear wheel for a given input power : `inputPower × efficiency`. */
    fun wheelPower(inputPower: Double): Double = inputPower * efficiency

    /** Rolling resistance force `F = crr × N` for a given normal force `N` (N). */
    fun rollingResistanceForce(normalForce: Double): Double = crr * normalForce

    companion object {
        /** Default bike : modern road bike with high-performance tires (Crr=0.004). */
        val DEFAULT = Bike()
    }
}
```

### 4. `Course.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/Course.kt` :

```kotlin
package io.github.glandais.engine

import io.github.glandais.engine.path.Path

/**
 * A cycling course : a [Path] simulated with a given [Cyclist] on a given [Bike].
 *
 * `CoursePhysics` (to be introduced in task 19/20) extends this with physics providers
 * (rho, aero, wind, cyclistPower) once those are ported.
 */
data class Course(
    val path: Path,
    val cyclist: Cyclist = Cyclist.DEFAULT,
    val bike: Bike = Bike.DEFAULT,
)
```

### 5. `EnhanceOptions.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/EnhanceOptions.kt` :

```kotlin
package io.github.glandais.engine

/**
 * Options for [io.github.glandais.engine.path.Path] simplification via Douglas-Peucker 3D.
 *
 * @param enabled whether simplification is active (default `true`)
 * @param toleranceM maximum allowed perpendicular distance in meters (default 10)
 * @param zExaggeration elevation exaggeration factor for ECEF conversion (default 3)
 */
data class SimplifyPathOptions(
    val enabled: Boolean = true,
    val toleranceM: Double = 10.0,
    val zExaggeration: Double = 3.0,
)

/**
 * Options controlling the [Enhancer] pipeline (introduced in task 25). Defaults match the TS
 * library : every step is enabled and simplification is on with `tolerance=10`, `zExag=3`.
 *
 * @param fixElevation pull elevation from a tile provider (task 24)
 * @param computeMaxSpeeds compute cornering + braking max speeds (task 20)
 * @param virtualizeTrack run power-based virtualization (task 21) — implies [computeMaxSpeeds]
 * @param computeOnePointPerSecond resample to 1 Hz (task 22)
 * @param simplifyPath Douglas-Peucker simplification options (task 23)
 */
data class EnhanceOptions(
    val fixElevation: Boolean = true,
    val computeMaxSpeeds: Boolean = true,
    val virtualizeTrack: Boolean = true,
    val computeOnePointPerSecond: Boolean = true,
    val simplifyPath: SimplifyPathOptions = SimplifyPathOptions(),
) {
    companion object {
        /** All steps enabled with TS-compatible defaults. */
        val DEFAULT = EnhanceOptions()
    }
}
```

### 6. Tests

#### `EngineConstantsTest.kt`

Vérifier les valeurs numériques exactes (parité vs TS) :

| Cas | Attendu |
|---|---|
| `G == 9.8` | exact |
| `MINIMAL_SPEED == 2.0/3.6` | exact |
| `DEFAULT_CRR == 0.004` | exact |
| `DEFAULT_INERTIA_FRONT == 0.05` | exact |
| `DEFAULT_INERTIA_REAR == 0.07` | exact |
| `DEFAULT_WHEEL_RADIUS_M == 0.7` | exact |
| `DEFAULT_DRIVETRAIN_EFFICIENCY == 0.976` | exact |
| `DEFAULT_CYCLIST_MASS_KG == 80.0` | exact |
| `DEFAULT_CYCLIST_POWER_W == 280.0` | exact |
| `DEFAULT_MAX_BRAKE_G == 0.6` | exact |
| `DEFAULT_MAX_LEAN_ANGLE_DEG == 35.0` | exact |
| `DEFAULT_MAX_LEAN_ANGLE_RAD ≈ 35 * π / 180` | tolérance 1e-12 |
| `DEFAULT_MAX_SPEED_KMH == 100.0` | exact |
| `DEFAULT_DRAG_COEFFICIENT == 0.7` | exact |
| `DEFAULT_FRONTAL_AREA_M2 == 0.5` | exact |
| `DEFAULT_AIR_DENSITY == 1.225` | exact |

#### `CyclistTest.kt`

| Cas | Attendu |
|---|---|
| `Cyclist.DEFAULT.massKg == 80.0` | sentinel |
| `Cyclist.DEFAULT.cd == 0.7`, `frontalAreaM2 == 0.5` | sentinel |
| `Cyclist.DEFAULT.aerodynamicDragArea ≈ 0.35` | calcul |
| `Cyclist.DEFAULT.tanMaxLeanAngle ≈ tan(35°)` à 1e-12 | calcul |
| `Cyclist.DEFAULT.maxLeanAngleRad ≈ 35 * π / 180` à 1e-12 | calcul |
| `Cyclist.DEFAULT.maxBrakeMS2 ≈ 0.6 * 9.8 = 5.88` à 1e-12 | calcul |
| `Cyclist.DEFAULT.maxSpeedMS ≈ 100/3.6 ≈ 27.777…` à 1e-12 | calcul |
| `Cyclist(massKg=90.0).massKg == 90.0` autres defaults conservés | copy semantics |
| Data class `copy(maxBrakeG=0.8)` change le brake, conserve le reste | propriété |

#### `BikeTest.kt`

| Cas | Attendu |
|---|---|
| `Bike.DEFAULT.crr == 0.004` | sentinel |
| `Bike.DEFAULT.totalInertia ≈ 0.12` | calcul |
| `Bike.DEFAULT.wheelDiameterM ≈ 1.4` | calcul |
| `Bike.DEFAULT.wheelCircumferenceM ≈ 2 * π * 0.7` à 1e-12 | calcul |
| `Bike.DEFAULT.equivalentMass ≈ 0.12 / 0.49 ≈ 0.244…` à 1e-9 | calcul |
| `Bike.DEFAULT.powerLossFactor ≈ 1 - 0.976 = 0.024` à 1e-12 | calcul |
| `Bike.DEFAULT.wheelPower(100.0) ≈ 97.6` à 1e-12 | calcul |
| `Bike.DEFAULT.rollingResistanceForce(800.0) ≈ 3.2` (crr * 800) | calcul |
| `Bike(crr=0.005).crr == 0.005` autres defaults conservés | copy semantics |

#### `CourseTest.kt`

| Cas | Attendu |
|---|---|
| `Course(path).cyclist == Cyclist.DEFAULT && course.bike == Bike.DEFAULT` | defaults |
| `Course(path, custom, customBike)` retient les overrides | propriété |
| `Course` equality : deux instances avec le même path/cyclist/bike sont égales | data class |

#### `EnhanceOptionsTest.kt`

| Cas | Attendu |
|---|---|
| `EnhanceOptions.DEFAULT` : tous les flags à true, simplifyPath enabled | sentinel |
| `SimplifyPathOptions()` : enabled=true, tolerance=10.0, zExag=3.0 | defaults |
| `EnhanceOptions(fixElevation = false)` : override unique | copy semantics |

### 7. Vérification ktlint

Imports triés, indentation 4 espaces. `:engine:ktlintFormat` au besoin.

## Outputs (fichiers attendus)

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/EngineConstants.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/Cyclist.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/Bike.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/Course.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/EnhanceOptions.kt`

Tests (commonTest) :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/EngineConstantsTest.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/CyclistTest.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/BikeTest.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/CourseTest.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/EnhanceOptionsTest.kt`

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- `EngineConstantsTest` ≥ 16 tests, `CyclistTest` ≥ 9, `BikeTest` ≥ 9, `CourseTest` ≥ 3, `EnhanceOptionsTest` ≥ 3 = **≥ 40 tests** verts par target.
- Parité numérique avec TS : tous les `DEFAULT_*` doivent être identiques au bit près.
- `ktlintCheck` vert ; `:elevation:allTests` toujours vert ; `:engine:build` compile.

## Done when

- [x] 5 sources `commonMain` créées
- [x] 5 fichiers tests créés (≥ 40 tests cumulés)
- [x] `./gradlew :engine:allTests` vert (3 targets)
- [x] `./gradlew :elevation:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Parité numérique vs TS sur tous les `DEFAULT_*`
- [x] Toutes les checkboxes cochées

## Notes

- **`data class` + `companion object DEFAULT`** : pattern Kotlin idiomatique. Bénéfice gratuit de `copy()`, `equals`, `hashCode`, `toString`. Le TS utilisait `static getDefault()` — équivalent fonctionnel.
- **Conversion km/h → m/s via `/ 3.6`** : exactement comme le TS. Pas de précision perdue (3.6 = 18/5).
- **`tanMaxLeanAngle` comme `val get()`** : recomputé à chaque appel mais le coût est négligeable (un `tan` C-intrinsic). Si profilage montre un hot path, on cachera dans une `private val`.
- **`EngineConstants` au lieu de `Constants`** : éviter la collision avec `io.github.glandais.elevation.EarthConstants/MathConstants`. Le package distinct résout déjà l'ambiguïté mais le nom plus parlant aide en imports croisés.
- **`MINIMAL_SPEED = 2.0/3.6`** : laissé en expression plutôt que valeur littérale `0.5555…` — explicite l'intention "2 km/h" et préserve la précision flottante de la même manière que le TS.
- **Pas de `toString()` custom** : on garde le `toString` auto-généré par `data class`. Si on a besoin du format TS pour debug visuel, on l'ajoutera plus tard.
- **`CoursePhysics` non porté** : volontaire — les 4 providers physiques arrivent en tâches 16-19. Une fois disponibles, `CoursePhysics` sera ajouté en tâche 19/20 comme une seconde data class étendant `Course` (ou agrégant).
- **`EnhanceOptions.DEFAULT`** : alignement avec le TS (tout enabled). Le caller peut faire `EnhanceOptions(fixElevation = false)` pour désactiver une étape.
- **Constantes en `Double`** : le TS utilise `number` (double IEEE). Aligné.
- **`PI` depuis `kotlin.math.PI`** : suffisant pour les conversions deg↔rad. Pas besoin d'`Math.PI` ; KMP-safe.
