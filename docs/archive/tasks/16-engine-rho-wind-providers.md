# 16 — Engine : `RhoProvider` (air density) + `WindProvider`

## Goal

Premier des 6 « providers physiques ». Introduit deux interfaces pluggables et leurs implémentations par défaut :

- **`RhoProvider`** — air density (kg/m³) en fonction de l'altitude et de la température.
  - `RhoProviderDefault` : constant `EngineConstants.DEFAULT_AIR_DENSITY = 1.225`.
  - `RhoProviderEstimate` : modèle barométrique troposphérique (ISA), formule `ρ = p / (R·T)` avec `p = P0 · (1 − L·h/T0)^(g/(R·L))`.
- **`WindProvider`** — vent (vitesse + direction en radians) en fonction du point courant.
  - `Wind(speedMS, directionRad)` data class.
  - `WindProviderNone` : `Wind(0, 0)` partout.
  - `WindProviderConstant(wind)` : retourne la même valeur sur toute la trace.
  - (Pas de `fromData` ici — l'approche TS ne l'implémente pas non plus ; on l'ajoutera si besoin.)

Les deux interfaces ont la même signature : `(course: Course, path: Path, pointIndex: Int) → T`. Le `Course` n'est pas utilisé par les impls actuels mais permet l'évolution future (e.g. provider qui dépend des préférences cycliste).

## Depends on

- `13-engine-cyclist-bike` (`Course`, `EngineConstants.DEFAULT_AIR_DENSITY`)
- `12-engine-path` (`Path` pour lire `elevation(i)` et `temperature(i)`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/rho/RhoProvider.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/rho/RhoProviderDefault.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/rho/RhoProviderEstimate.ts` (canonique — constantes ISA + formules barométriques)
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/physics/power/aero/wind/{Wind,WindProvider,WindProviderConstant,WindProviderNone}.ts`

## Steps

### 1. `RhoProvider.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/RhoProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.EngineConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import kotlin.math.pow

/**
 * Air density provider — returns ρ (kg/m³) at a given point on the course.
 *
 * Two impls : [RhoProviderDefault] (constant 1.225) and [RhoProviderEstimate] (ISA barometric model).
 */
fun interface RhoProvider {
    fun rho(course: Course, path: Path, pointIndex: Int): Double
}

/** Constant `EngineConstants.DEFAULT_AIR_DENSITY = 1.225` regardless of point. */
object RhoProviderDefault : RhoProvider {
    override fun rho(course: Course, path: Path, pointIndex: Int): Double =
        EngineConstants.DEFAULT_AIR_DENSITY
}

/**
 * ISA (International Standard Atmosphere) troposphere model. Reads `elevation(i)` and
 * `temperature(i)` from the path ; falls back to 15 °C and 0 m if either is `NaN`.
 *
 * Formula :
 * ```
 * p = P0 · (1 − L·h / T0)^(g / (R·L))
 * ρ = p / (R · T)
 * ```
 * with `P0 = 101325 Pa`, `T0 = 288.15 K`, `g = 9.80665 m/s²`, `L = 0.0065 K/m`,
 * `R = 287.05 J/(kg·K)`, `T = temperatureC + 273.15`.
 */
object RhoProviderEstimate : RhoProvider {
    // ISA constants
    private const val P0 = 101325.0           // sea level pressure (Pa)
    private const val T0 = 288.15             // sea level temperature (K)
    private const val G_ISA = 9.80665         // gravity (m/s²)
    private const val L = 0.0065              // temperature lapse rate (K/m)
    private const val R = 287.05              // specific gas constant for dry air (J/(kg·K))

    override fun rho(course: Course, path: Path, pointIndex: Int): Double {
        val providedTemp = path.get(pointIndex, PointField.TEMPERATURE)
        val temperatureC = if (providedTemp.isNaN() || providedTemp == 0.0) 15.0 else providedTemp

        val providedElevation = path.elevation(pointIndex)
        val altitude = if (providedElevation.isNaN()) 0.0 else providedElevation

        val tKelvin = temperatureC + 273.15
        val pressure = P0 * (1.0 - L * altitude / T0).pow(G_ISA / (R * L))
        return pressure / (R * tKelvin)
    }
}
```

**Sémantique `temperature == 0.0`** : `GeneratedPath` initialise tous les slots à `0.0`. Pour la température, `0 °C` est une valeur réaliste mais le TS traite `NaN` comme « absent ». En Kotlin, `0.0` est interprété comme « non renseigné » et tombera en fallback 15 °C. Compromis pragmatique : si un cas réel exige `0 °C`, il faut passer par un autre provider (e.g. `RhoProviderConstant(altitude)` à introduire si besoin).

### 2. `WindProvider.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/physics/WindProvider.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.path.Path

/**
 * Wind conditions at a point.
 *
 * @param speedMS wind speed in m/s
 * @param directionRad direction in radians (0 = North, π/2 = East, π = South, 3π/2 = West)
 */
data class Wind(val speedMS: Double, val directionRad: Double) {
    companion object {
        val NONE = Wind(0.0, 0.0)
    }
}

/** Returns wind conditions [Wind] at a given point on the course. */
fun interface WindProvider {
    fun wind(course: Course, path: Path, pointIndex: Int): Wind
}

/** No wind anywhere ; equivalent to perfectly calm conditions. */
object WindProviderNone : WindProvider {
    override fun wind(course: Course, path: Path, pointIndex: Int): Wind = Wind.NONE
}

/** Same [Wind] returned for every point. */
class WindProviderConstant(private val wind: Wind) : WindProvider {
    override fun wind(course: Course, path: Path, pointIndex: Int): Wind = wind
}
```

### 3. Tests `RhoProviderTest.kt`

Cas à couvrir (≥ 14) :

| # | Cas | Attendu (tolérance) |
|---|---|---|
| 1 | `RhoProviderDefault.rho(...)` retourne `1.225` exact pour n'importe quel point | exact |
| 2 | `RhoProviderEstimate.rho` à `altitude=0, temp=15°C` ≈ `1.2249…` | 1e-3 |
| 3 | `RhoProviderEstimate.rho` à `altitude=1500m, temp=15°C` ≈ `1.0581…` | 1e-3 |
| 4 | `RhoProviderEstimate.rho` à `altitude=3000m, temp=15°C` ≈ `0.9091…` | 1e-3 |
| 5 | `RhoProviderEstimate.rho` à `altitude=0, temp=0°C` (densité plus élevée à froid) | > rho(0, 15°C) |
| 6 | `RhoProviderEstimate.rho` à `altitude=0, temp=30°C` (densité plus basse à chaud) | < rho(0, 15°C) |
| 7 | `temperature(i) == NaN` (set explicitement) → fallback 15°C | rho ≈ rho(altitude, 15°C) |
| 8 | `elevation(i) == NaN` → fallback 0 m | rho ≈ rho(0, temp) |
| 9 | Monotonie : rho décroît avec altitude (alt 0 → 1000 → 2000 → 3000) | propriété |
| 10 | Cohérence avec TS : à `altitude=0, temp=15°C`, valeur match TS au bit près (~1.225) | 1e-9 |
| 11 | `RhoProviderEstimate.rho` à altitude négative (-50 m, Mer Morte) | > rho(0) |
| 12 | `path.setTemperature(0, 0.0)` (sentinel "absent") → fallback 15°C | rho == rho(0, 15°C) |
| 13 | `path.setTemperature(0, 20.0)` → rho < rho(0, 15°C) | propriété |
| 14 | Validation course inutilisé : passer `null` n'est pas autorisé (signature) — tests utilisent un `Course` minimal | sentinel |

**Valeurs de référence ISA** (source : tables ISA standards) :
- 0 m, 15 °C → ρ ≈ 1.2250 kg/m³ (en pratique, formule donne ≈ 1.22497…)
- 1500 m, 15 °C **à T0** → la formule TS utilise T sea-level constant donc rho à 1500 m sans correction temp donne ≈ 1.0581 (à confirmer par calcul direct)
- 3000 m, 15 °C → ≈ 0.9091

⚠ Le TS utilise `T` (température fournie + 273.15) **à la fois** dans le calcul de pression ET dans la division finale. C'est légèrement non-standard ISA strict (qui voudrait `T0` dans la pression et `T_altitude = T0 − L·h` dans la division), mais on porte fidèlement le TS pour parité. Les tests #2-#4 ci-dessus utilisent ce modèle TS.

### 4. Tests `WindProviderTest.kt`

Cas (≥ 6) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `Wind.NONE == Wind(0.0, 0.0)` | sentinel |
| 2 | `WindProviderNone.wind(...)` retourne `Wind.NONE` pour tous les indices | exact |
| 3 | `WindProviderConstant(Wind(5.0, PI/2)).wind(...)` retourne 5 m/s East | exact |
| 4 | `Wind` data class equality | reflexive |
| 5 | `WindProviderConstant` retourne la même instance à chaque appel (pas de copie défensive) | identité |
| 6 | Direction π (Sud) bien préservée | sentinel |

### 5. Vérification ktlint

`:engine:ktlintFormat` au besoin.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/RhoProvider.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/WindProvider.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/RhoProviderTest.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/WindProviderTest.kt`

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 14 tests `RhoProviderTest`, ≥ 6 `WindProviderTest` = **≥ 20 tests** verts sur JVM/JS/Wasm.
- Valeurs ISA conformes au TS.
- `:elevation:allTests` toujours vert.

## Done when

- [x] 2 sources `commonMain` créés
- [x] 2 fichiers tests (≥ 20 tests cumulés)
- [x] `:engine:allTests` vert (3 targets) ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Valeurs ISA documentées (commentaire avec source)
- [x] Toutes les checkboxes cochées

## Notes

- **`fun interface`** : Kotlin SAM — permet `RhoProvider { _, _, _ -> 1.225 }` en lambda. Pratique pour tests et pour la future API publique (e.g. un caller JS pourrait fournir un lambda via JS bridge).
- **`object` plutôt que `class` pour les impls par défaut** : `RhoProviderDefault`/`RhoProviderEstimate`/`WindProviderNone` n'ont pas d'état. Pattern singleton idiomatique, économise des allocations.
- **`Wind.NONE` companion** : zero-allocation pour le cas le plus fréquent.
- **`PointField.TEMPERATURE` lookup générique** : démontre l'usage du générique `path.get(i, field)` introduit en tâche 11. Aussi possible : `path.temperature(i)` directement. Le générique est utile dans du code template-driven (Phase 8 parité).
- **Constantes ISA dans `RhoProviderEstimate`** : valeurs gravées (P0, T0, g, L, R) — source : ICAO ISA. Ne pas modifier sans valider la parité TS.
- **Différence subtile avec ISA stricte** : voir la note `⚠` ci-dessus. On porte la formule TS telle quelle. Si la parité numérique avec TS échoue, c'est ce point qu'il faudra ré-examiner.
- **`temperature == 0.0` traité comme absent** : compromis cf. tâche 15. Si on veut un jour distinguer "0 °C réel" vs "absent", il faudra introduire un sentinel `NaN` à la lecture (ou un `PointField` séparé `temperatureSet: Boolean`). Pas urgent.
- **Pas de `fromData` wind provider** : le TS ne l'a pas non plus. Si un cas l'exige (e.g. lecture météo OWM par point), on l'ajoutera comme `WindProviderFromData(data: List<Wind>)`.
- **Préparation tâche 17** : `RhoProvider` consommé par `AeroPowerProvider` (drag aéro = `−CdA · ρ/2 · v³`). `WindProvider` consommé par le même (vitesse apparente du vent).
