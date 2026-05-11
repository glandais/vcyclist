# 25 — Engine : `Enhancer` (pipeline orchestrateur)

## Goal

Assembler les briques des tâches 17-24 en un pipeline d'enrichissement public :

1. **`fixElevation`** (optionnel) — corrige les altitudes via `:elevation.ElevationProvider`.
2. **`smoothElevation`** — applique un lissage 150 m systématiquement après fix (cf. tâche 24).
3. **`computeMaxSpeeds`** — calcule les vitesses max par cornering + braking (tâche 20).
4. **`virtualizeTrack`** — simulation time-stepping (tâche 21). Forcera `computeMaxSpeeds=true`.
5. **`computeOnePointPerSecond`** — resample à 1 Hz (tâche 22).
6. **`simplifyPath`** — Douglas-Peucker 3D via `PathSimplifier` (tâche 23).

API publique :

- `suspend fun enhanceCourseDefault(path: Path, elevationProvider: ElevationProvider?): Path`
- `suspend fun enhanceCourse(course: CoursePhysics, options: EnhanceOptions = EnhanceOptions.DEFAULT, elevationProvider: ElevationProvider? = null): Path`
- `fun getDefaultCourse(path: Path): CoursePhysics` (Cyclist/Bike défaut + `RhoProviderEstimate` + `AeroProviderConstant` + `WindProviderNone` + `PowerProviderConstant(280)`)

⚠ **Hors scope** : le TS utilise aussi `PointPerDistance.compute(...)` avant et après `fixElevation`. **Non porté** dans ce projet. Conséquence : la trace n'est pas redensifiée à pas constant. Si la parité numérique de la tâche 26 le requiert, on ajoutera `PointPerDistance` dans une sous-tâche dédiée.

## Depends on

- `20-engine-max-speed-computer` (`MaxSpeedComputer`)
- `21-engine-virtualize-service` (`VirtualizeService`)
- `22-engine-point-per-second` (`PointPerSecond`)
- `23-engine-douglas-peucker-3d` (`PathSimplifier`)
- `24-engine-elevation-fix-step` (`ElevationStep`)
- `13-engine-cyclist-bike` (`Course`, `Cyclist`, `Bike`, `EnhanceOptions`)
- `17-engine-power-providers` (`CoursePhysics`)
- `18-engine-cyclist-power-providers` (`PowerProviderConstant`)
- `16-engine-rho-wind-providers` (`RhoProviderEstimate`, `WindProviderNone`)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/enhancer/Enhancer.ts` (canonique — adapter sans porter `PointPerDistance`)

## Steps

### 1. `Enhancer.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt` :

```kotlin
package io.github.glandais.engine

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.engine.path.ElevationStep
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PathSimplifier
import io.github.glandais.engine.path.PointPerSecond
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.MaxSpeedComputer
import io.github.glandais.engine.physics.PowerProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.physics.VirtualizeService
import io.github.glandais.engine.physics.WindProviderNone

/**
 * Top-level enhancement pipeline : transforms a raw GPS [Path] into a physics-aware
 * virtualized ride. Ordering matches the TS `Enhancer.enhanceCourse` minus `PointPerDistance`
 * (not ported).
 *
 * Steps (each optional via [EnhanceOptions]) :
 * 1. fix elevation (Terrarium tiles via [ElevationProvider]) + 150 m smoother
 * 2. compute max speeds (cornering + braking)
 * 3. virtualize track (time-stepping simulation)
 * 4. resample to 1 Hz
 * 5. simplify with Douglas-Peucker 3D
 */
object Enhancer {

    /** Build a [CoursePhysics] from [path] using all default-physics providers (ISA rho, no wind). */
    fun getDefaultCourse(path: Path): CoursePhysics =
        CoursePhysics(
            course = Course(path = path),
            rhoProvider = RhoProviderEstimate,
            aeroProvider = AeroProviderConstant,
            windProvider = WindProviderNone,
            cyclistPowerProvider = PowerProviderConstant(EngineConstants.DEFAULT_CYCLIST_POWER_W),
        )

    /** Convenience : enhance [path] with all defaults and a single optional provider. */
    suspend fun enhanceCourseDefault(
        path: Path,
        elevationProvider: ElevationProvider? = null,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
    ): Path = enhanceCourse(getDefaultCourse(path), options, elevationProvider)

    /**
     * Run the enhancement pipeline.
     *
     * - If [elevationProvider] is `null`, [EnhanceOptions.fixElevation] is forced off (no
     *   provider → can't pull elevations). The smoother runs regardless.
     * - If [EnhanceOptions.virtualizeTrack] is `true`, max-speed computation is always run
     *   (the simulation needs `speedMax`).
     */
    suspend fun enhanceCourse(
        course: CoursePhysics,
        options: EnhanceOptions = EnhanceOptions.DEFAULT,
        elevationProvider: ElevationProvider? = null,
    ): Path {
        var path = course.path

        // Step 1 : elevation fix + smooth.
        if (options.fixElevation && elevationProvider != null) {
            path = ElevationStep.fixElevation(path, elevationProvider)
        }
        path = ElevationStep.smoothElevation(path)

        // Wrap the updated path into a fresh CoursePhysics carrying the new path.
        var working = course.copy(course = course.course.copy(path = path))

        // Step 2 : max speeds (always if virtualize, otherwise optional).
        if (options.computeMaxSpeeds || options.virtualizeTrack) {
            MaxSpeedComputer.computeMaxSpeeds(working.course)
        }

        // Step 3 : virtualize.
        if (options.virtualizeTrack) {
            path = VirtualizeService.virtualizeTrack(working)
            working = working.copy(course = working.course.copy(path = path))
        }

        // Step 4 : 1 Hz resample.
        if (options.computeOnePointPerSecond) {
            path = PointPerSecond.computeOnePointPerSecond(path)
        }

        // Step 5 : simplify.
        if (options.simplifyPath.enabled) {
            path = PathSimplifier.simplify(
                path,
                options.simplifyPath.toleranceM,
                options.simplifyPath.zExaggeration,
            )
        }

        return path
    }
}
```

**Notes design** :

- `CoursePhysics.copy(course = course.course.copy(path = path))` — chaque étape qui change le path doit produire un nouveau `CoursePhysics`. Les providers/cyclist/bike sont preservés via `copy`.
- `elevationProvider: ElevationProvider?` : volontaire. Permet de tester sans HTTP réel (passer `null`, et `fixElevation` est skipped). En prod, l'appelant fournit un `ElevationProvider()` standard.

### 2. Tests `EnhancerTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/EnhancerTest.kt`. Cas (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `enhanceCourse` avec `EnhanceOptions(fixElevation=false, computeMaxSpeeds=false, virtualizeTrack=false, computeOnePointPerSecond=false, simplifyPath.enabled=false)` → retourne path lissé (smoothElevation seul) | propriété |
| 2 | `enhanceCourse` avec `EnhanceOptions.DEFAULT` + provider mock → path simulé final (non-vide, time monotone) | propriété |
| 3 | `enhanceCourseDefault(path)` produit un résultat équivalent à `enhanceCourse(getDefaultCourse(path))` | équivalence |
| 4 | `getDefaultCourse(path).cyclistPowerProvider` est `PowerProviderConstant(280)` | sentinel |
| 5 | `getDefaultCourse(path).rhoProvider` est `RhoProviderEstimate` | sentinel |
| 6 | `getDefaultCourse(path).windProvider` est `WindProviderNone` | sentinel |
| 7 | Pipeline avec `virtualizeTrack=true, computeMaxSpeeds=false` → maxSpeeds toujours exécuté (implication) | propriété |
| 8 | Pipeline avec `elevationProvider=null` → `fixElevation` skipped silencieusement, smoothElevation toujours appelé | propriété |
| 9 | Pipeline complet sur path de 5 points → résultat a une taille raisonnable (entre 5 et 100 points typiquement) | propriété de bon sens |
| 10 | Échec de provider (provider qui throw) → exception propagée | propriété d'erreur |
| 11 | Pipeline sans simplification → résultat = sortie de PointPerSecond | propriété |
| 12 | Pipeline avec seulement `simplifyPath.enabled=true` → tout sauf simplify skipped, simplify appelé | propriété |

Setup helper : créer un `ElevationProvider` mock (cf. tâche 24) avec fetcher constant. Path d'entrée : 5-10 points avec lat/lon/time/elevation valides.

### 3. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt`

Tests :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/EnhancerTest.kt` (≥ 10 tests)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 10 tests verts × 3 targets.
- Non-régression : toutes les tâches 10-24 toujours vertes.
- `:elevation:allTests` toujours vert.

## Done when

- [x] `Enhancer.kt` créé
- [x] `EnhancerTest.kt` ≥ 10 tests verts × 3 targets
- [x] `getDefaultCourse` produit un `CoursePhysics` avec les providers attendus
- [x] Pipeline complet runs sur path d'exemple
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **`PointPerDistance` non porté** : décision documentée. Si la tâche 26 (parité fixtures) montre des écarts > 5 % entre output Kotlin et TS, c'est probablement la cause (la trace n'est pas redensifiée). Ajouter `PointPerDistance.compute` dans une tâche 24-bis ou ici plus tard.
- **`CoursePhysics.copy(course = ...)`** : la chaîne `working.copy(course = course.course.copy(path = path))` nécessite que `Course` soit `data class` (oui, cf. tâche 13). Si la copie devient verbeuse, ajouter une extension `CoursePhysics.withPath(p: Path): CoursePhysics`.
- **`virtualizeTrack` implies `computeMaxSpeeds`** : le TS le fait (`if (computeMaxSpeeds || virtualizeTrack)`). Préservé.
- **`smoothElevation` toujours appelée** : même si `fixElevation` est false. C'est le comportement TS. Garantit que la trace est lissée pour les calculs de grade.
- **`enhanceCourse` est `suspend`** : nécessaire pour `fixElevation`. Si l'appelant ne fournit pas de provider, le `suspend` n'a aucun coût.
- **Phase 2 conclusion** : avec cette tâche, le pipeline complet du moteur est en place. Reste les tâches 26 (parité), 27 (CLI smoke), 28 (API JS/Wasm) pour conclure le projet.
