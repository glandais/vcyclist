# 29 — Engine : `VirtualizeService` — bug timestamps absolus du dernier point

## Goal

Corriger le **bug #1** identifié à la fin de Phase 2 : la simulation initialise `time(0) = 0.0` (epoch local) mais le **dernier point** `n-1` reçoit une `copyAllFields(input, n-1, out, n-1)` qui copie le `time` source (epoch GPX, typiquement ~1.7e12 ms en 2024). Conséquence : `time(n-1) - time(0) ≈ 1.7e12 ms` au lieu d'une durée plausible (~10⁴ ms), ce qui fait exploser `PointPerSecond` (alloue `floor(durée_ms / 1000)` points → ~1.7 milliards de points → OOM).

**Correction** : après la boucle principale, **réécrire** le `time` (et `elapsed`) du dernier point en extrapolant depuis `time(n-2)` avec un `dt` plausible. Le reste des slots (lat/lon/elevation/speedMax/...) du dernier point reste copié verbatim — c'est seulement le `time` qui doit être recalculé pour cohérence.

**Stratégie retenue** : faire une étape supplémentaire de simulation pour le segment `[n-2, n-1]` :
- Lire `dx = input.distance(n-1) - input.distance(n-2)`
- Calculer `pSum` puis `dt = PowerComputer.getDt(...)`
- Mettre à jour `time(n-1)`, `elapsed(n-1)`, `dx(n-1)`, `dt(n-1)`, `speed(n-1)`, `virtSpeedCurrent(n-1)`

C'est la même logique que la boucle principale, juste étendue d'un cran. Le `pComputedPower(n-1)` est calculé dans la passe inverse existante.

## Depends on

- `21-engine-virtualize-service` (impl actuelle)
- `19-engine-power-computer` (`getDt`, `getNewPower`)

## Inputs

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/VirtualizeService.kt` (impl actuelle)
- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/VirtualizeServiceTest.kt` (tests existants — non-régression)
- Référence TS `virtual-cyclist/src/physics/VirtualizeService.ts` (à relire — peut donner des indices sur la condition d'arrêt `< pathLength - 1`)

## Steps

### 1. Modifier `VirtualizeService.kt`

Remplacer la boucle `while (i < n - 1)` par `while (i < n)` et **supprimer** le `copyAllFields(input, n - 1, out, n - 1)` placé après la boucle (le dernier point est désormais simulé comme les autres).

Diff visuel (sections clés) :

```kotlin
// Avant :
var i = 1
var iter = 0
while (i < n - 1) {
    // ... simulation ...
    i++
    if (iter++ > MAX_ITERATIONS) break
}
// Last point : copy verbatim (no virtualization).
copyAllFields(input, n - 1, out, n - 1)

// Après :
var i = 1
var iter = 0
while (i < n) {
    // ... simulation strictement identique ...
    i++
    if (iter++ > MAX_ITERATIONS) break
}
// (no special-case for the last point — it's simulated like the rest)
```

⚠ Vérifier que le `getNewPower(course, out, i - 1, withCyclist = true)` reste valide quand `i = n - 1` : il lit le slot `i - 1 = n - 2` qui a été simulé à l'itération précédente. ✓ OK.

### 2. Mettre à jour `computeCyclistPower` pour le dernier point

L'invariant courant `for (j in 0 until out.size - 1)` ne calcule `pComputedPower` que pour les indices `[0, n-2]`. Après la correction, le point `n-1` est simulé, donc `pComputedPower(n-1)` devrait aussi être calculé. Changer en `for (j in 0 until out.size)` (équivalent `out.indices`).

Cohérence : `computeCyclistPower(j)` lit `path.dt(j)`. Pour `j = 0`, retourne `pComputedPower = 0` (early return). Pour `j ≥ 1`, lit `path.dt(j)` qui doit être > 0. Avec la boucle modifiée, `dt(j)` est setté pour tout `j ∈ [1, n-1]`. ✓

### 3. Vérifier les tests existants

Lancer `./gradlew :engine:jvmTest --tests '*VirtualizeServiceTest*'` après modification. Les 12 tests existants doivent passer (ou être ajustés si la sémantique change). Cas attendus :

- Test #2 (Path 1 point) : retourne size 1, inchangé.
- Test #3 (line straight 3 pts) : speeds inchangés sur i=0,1. Désormais point 2 simulé aussi.
- Test #4 (time monotone) : doit toujours marcher car le dernier point a maintenant un `time(n-1) = time(n-2) + dt × 1000`.
- Test #11 (iteration cap 110k pts) : doit toujours marcher.

Si un test asserte une propriété spécifique du dernier point comme "verbatim copy", l'ajuster pour refléter la nouvelle sémantique.

### 4. Ajouter un test de régression spécifique au bug

`engine/src/commonTest/kotlin/io/github/glandais/engine/physics/VirtualizeServiceTimestampTest.kt` :

```kotlin
package io.github.glandais.engine.physics

import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.path.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class VirtualizeServiceTimestampTest {

    @Test fun `last point time is not an epoch leak from input`() {
        // Source path with realistic epoch timestamps (2024-ish).
        val n = 5
        val path = Path(n)
        val epoch2024 = 1_700_000_000_000.0
        for (i in 0 until n) {
            path.setLatitude(i, 0.0)
            path.setLongitude(i, i * 1e-5)
            path.setElevation(i, 100.0)
            path.setTime(i, epoch2024 + i * 1_000.0) // 1 s apart
            path.setDistance(i, i * 1.0)             // 1 m apart
            path.setSpeedMax(i, 50.0)
        }
        val course = CoursePhysics(Course(path))
        val out = VirtualizeService.virtualizeTrack(course)

        // The simulated path must NOT carry the 1.7e12 epoch in time(n-1).
        assertTrue(
            out.time(out.size - 1) < 1_000_000.0,
            "time(n-1) = ${out.time(out.size - 1)} leaked the input epoch instead of being simulated",
        )
        // It should be strictly greater than time(0)=0 (monotone) and consistent with dt(n-1).
        assertTrue(out.time(out.size - 1) > out.time(out.size - 2))
        val expected = out.time(out.size - 2) + out.dt(out.size - 1)
        assertTrue(
            kotlin.math.abs(out.time(out.size - 1) - expected) < 1e-6,
            "time(n-1) should equal time(n-2) + dt(n-1) ; got ${out.time(out.size - 1)} vs expected $expected",
        )
    }

    @Test fun `PointPerSecond can run on output without OOM`() {
        // Same setup as above. After the fix, PointPerSecond should not allocate ~10^9 points.
        val n = 5
        val path = Path(n)
        val epoch2024 = 1_700_000_000_000.0
        for (i in 0 until n) {
            path.setLatitude(i, 0.0)
            path.setLongitude(i, i * 1e-5)
            path.setElevation(i, 100.0)
            path.setTime(i, epoch2024 + i * 1_000.0)
            path.setDistance(i, i * 1.0)
            path.setSpeedMax(i, 50.0)
        }
        val virtual = VirtualizeService.virtualizeTrack(CoursePhysics(Course(path)))
        // Should produce at most ~30 epoch seconds, not 10^9.
        val resampled = io.github.glandais.engine.path.PointPerSecond.computeOnePointPerSecond(virtual)
        assertTrue(resampled.size < 100, "PointPerSecond produced ${resampled.size} points (expected < 100)")
    }
}
```

### 5. Mettre à jour les tests `Enhancer` qui désactivaient `computeOnePointPerSecond`

Chercher dans `engine/src/commonTest/.../EnhancerTest.kt` et `EngineCliSmokeTest.kt` les usages :

```kotlin
EnhanceOptions.DEFAULT.copy(computeOnePointPerSecond = false, ...)
```

Vérifier si la désactivation reste nécessaire après le fix. Si oui (e.g. parce que la fixture sample.gpx avec 3569 points × 1 Hz = trop de points pour un test rapide), laisser tel quel. Sinon, ré-activer.

**Conservatisme** : laisser `computeOnePointPerSecond=false` dans les tests existants pour ne pas changer leur comportement. La tâche 31 ré-activera explicitement le défaut.

### 6. Vérification ktlint + non-régression complète

```bash
./gradlew :engine:allTests :elevation:allTests
./gradlew ktlintCheck
```

## Outputs

Modifié :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/VirtualizeService.kt`

Créé :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/physics/VirtualizeServiceTimestampTest.kt` (≥ 2 tests)

Tests existants éventuellement ajustés :

- `VirtualizeServiceTest.kt` (selon les assertions qui dépendaient du "verbatim last point")

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
```

Critères :

- ≥ 2 tests `VirtualizeServiceTimestampTest` verts × 3 targets.
- Tous tests `VirtualizeServiceTest` existants toujours verts (ajustés au besoin).
- `EnhancerTest` toujours vert (les tests utilisent `computeOnePointPerSecond=false` donc indifférent au fix).
- `:elevation:allTests` toujours vert.
- Smoke manuel : `./gradlew :engine:run -Pargs="enhance .../sample.gpx -o /tmp/out.gpx"` avec **`computeOnePointPerSecond=true`** (à activer dans EngineCli.kt pour ce smoke) doit **réussir** (pas d'OOM).

## Done when

- [x] `VirtualizeService.kt` : boucle étendue à `i < n` au lieu de `i < n - 1`
- [x] `copyAllFields(input, n-1, out, n-1)` post-boucle supprimé
- [x] `computeCyclistPower` étendu à `out.size` (inclut le dernier point)
- [x] `VirtualizeServiceTimestampTest` créé (≥ 2 tests)
- [x] Tests `VirtualizeServiceTest` existants ajustés si nécessaire et tous verts
- [x] Smoke manuel avec `computeOnePointPerSecond=true` réussit
- [x] `:engine:allTests` + `:elevation:allTests` verts ; `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **Sémantique TS** : le TS arrête la boucle à `i < pathLength - 1` aussi, et **n'ajoute pas** le dernier point. Notre `Path` Kotlin est fixed-size, on ne peut pas "skipper" le dernier point ; on choisit de le simuler. Légère divergence sémantique avec le TS (le TS produit `n-1` points en sortie, on produit `n`). Acceptable car en pratique le `n-1`ᵉ point reste cohérent (time/speed extrapolés depuis `n-2`).
- **Alternative** : retourner un `Path(n - 1)` au lieu de `Path(n)` (parité stricte TS). Inconvénient : asymétrie size input/output, casse les tests qui asserent `virtual.size == input.size`. Décision : préférer la simulation du dernier point.
- **Test #11 (iteration cap)** : avec la boucle `i < n` au lieu de `< n-1`, il faut une iteration de plus. Le cap MAX_ITERATIONS reste à 100k — pas d'impact mesurable.
- **`out.computeDerivedData()` final** : recalcule `bearing/grade/dx/dt/speed/elapsed` depuis lat/lon/elevation/time. Avec la nouvelle sémantique, `time(n-1)` est désormais cohérent, donc `dx/dt/speed/elapsed` recalculés sont également cohérents. **Plus de risque OOM** dans le `PointPerSecond` aval.
- **Préparation tâche 30** : `PointPerDistance` porté pour densifier les paths avant `Enhancer`.
- **Préparation tâche 31** : ré-activer `computeOnePointPerSecond` et `simplifyPath` dans `EnhanceOptions.DEFAULT`, et brancher `PointPerDistance` dans `Enhancer`.
