# 31 — Engine : intégrer `PointPerDistance` dans `Enhancer`

## Goal

Brancher `PointPerDistance` (tâche 30) dans `Enhancer.enhanceCourse` pour reproduire fidèlement le pipeline TS, qui appelle `PointPerDistance.compute` **avant et après** `fixElevation` :

- **Pré-fix** : `compute(path, -1.0, 30.0)` — densifie les paths sparse à au plus 30 m entre points pour donner à `fixElevation` suffisamment de points où interroger la résolution DEM (~30 m). Le `minDist=-1` désactive le filtre bas → on ne perd aucun waypoint source.
- **Post-fix** : `compute(path, 1.0, 2.0)` — raffine à 1-2 m entre points (post-altitudes corrigées) pour que les passes suivantes (`MaxSpeedComputer`, `VirtualizeService`) opèrent sur une trace dense et régulière.

Note `:elevation` : sans `fixElevation`, le pré-fix `PointPerDistance(-1, 30)` reste utile (donne `VirtualizeService` plus de waypoints à virtualiser et améliore la précision de `MaxSpeedComputer` sur les virages). Le post-fix `PointPerDistance(1, 2)` est skippé si pas de fix elevation (puisque le smoother garde les mêmes points). À discuter : faut-il vraiment skipper ?

**Plan TS exact** :
```
path = PointPerDistance.compute(path, -1.0, 30.0)
if (fixElevation) path = Elevation.fixElevation(path)
path = PointPerDistance.compute(path, 1.0, 2.0)
path = Elevation.smoothElevation(path)
... reste du pipeline (MaxSpeed, Virtualize, PointPerSecond, Simplify)
```

On porte cette séquence exactement.

## Depends on

- `25-engine-enhancer` (impl actuelle)
- `30-engine-point-per-distance` (`PointPerDistance.compute`)

## Inputs

- `engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/EnhancerTest.kt`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/enhancer/Enhancer.ts` (référence TS — lignes ~64-92)

## Steps

### 1. Modifier `Enhancer.kt`

Ajouter les 2 appels `PointPerDistance.compute` dans la séquence "Step 1" (elevation) :

```kotlin
import io.github.glandais.engine.path.PointPerDistance

// ... dans enhanceCourse() ...

// Step 1a : densify before fixElevation so DEM lookups have ~30 m granularity.
path = PointPerDistance.compute(path, minDistanceM = -1.0, maxDistanceM = 30.0)

// Step 1b : fix elevation (optional).
if (options.fixElevation && elevationProvider != null) {
    path = ElevationStep.fixElevation(path, elevationProvider)
}

// Step 1c : refine to 1-2 m spacing before downstream physics.
path = PointPerDistance.compute(path, minDistanceM = 1.0, maxDistanceM = 2.0)

// Step 1d : smooth elevations (always).
path = ElevationStep.smoothElevation(path)
```

⚠ Cela peut **multiplier la taille du path** : un sample.gpx de 3569 points avec gaps moyens ~35 m, après `PointPerDistance(1, 2)`, devient ~110 000 points (gain ×30). Le pipeline aval doit tenir.
- `MaxSpeedComputer` : O(n) → 110k itérations, ~100 ms.
- `VirtualizeService` : O(n × binary search dt) → 110k × ~20 itérations = 2.2M opérations, ~1-2 s.
- `PointPerSecond` : produit ~ durée_secondes points (e.g. 13000 s = 13k points). OK depuis fix tâche 29.
- `PathSimplifier` : O(n) avec Douglas-Peucker 3D → 110k → quelques 100 ms.

Total raisonnable pour un sample 130 km. À vérifier en smoke E2E.

### 2. Smoke E2E : valider que le pipeline complet termine en temps raisonnable

Mettre à jour `engine/src/jvmTest/kotlin/io/github/glandais/engine/FullPipelineSmokeTest.kt` :

```kotlin
@Test
fun `sample-gpx full pipeline incl PointPerDistance completes within budget`() = runTest {
    val gpxPath = pathToSample() ?: return@runTest
    val xml = java.io.File(gpxPath).readText()
    val path = GpxParser.parse(xml).firstTrackAsPath()
    val wallStart = kotlin.system.measureTimeMillis {
        val out = Enhancer.enhanceCourseDefault(
            path,
            elevationProvider = null,
            options = EnhanceOptions.DEFAULT.copy(fixElevation = false),
        )
        assertTrue(out.size > 0)
        assertTrue(out.totalDistance > 0)
    }
    // Budget : < 10 s on a modern JVM. Adjust if too tight on slow CI runners.
    assertTrue(wallStart < 10_000, "pipeline took ${wallStart} ms, expected < 10000")
}
```

### 3. Mettre à jour les tests existants `EnhancerTest`

Les tests qui asseraient une taille spécifique (e.g. test #1 "all options off → smoothed path size == input size") peuvent maintenant échouer car `PointPerDistance` peut densifier silencieusement même quand toutes les options sont off (les 2 appels sont **toujours** exécutés, non-toggleable).

Décision : Faut-il ajouter une option `EnhanceOptions.densifyByDistance: Boolean = true` ?

**Choix retenu** : non. `PointPerDistance` est partie intégrante du pipeline TS, pas une étape configurable. L'absence d'option simplifie l'API. Les tests qui asseraient `output.size == input.size` doivent être ajustés en `output.size >= input.size` (densification peut ajouter, jamais retirer significativement).

Cas attendus à ajuster dans `EnhancerTest.kt` :
- Si test "all steps disabled → returns smoothed path" → le path peut être densifié, taille ≠ input. Ajuster en "totalDistance ≈ input.totalDistance" ou "first/last lat/lon ≈ input".
- Test parity (`ParityFixtures`) : les valeurs `pointCount` vont changer. Les recalculer.

### 4. Mettre à jour `ParityFixtures.kt` et `EnhancerParityTest`

Comme la densification change radicalement la sortie, les valeurs baseline `ParityFixtures.SAMPLE` et `GARMIN` doivent être recalculées. Approche pragmatique :

1. Exécuter le pipeline une fois (test temporaire qui imprime les nouvelles valeurs).
2. Copier-coller les nouvelles valeurs dans `ParityFixtures.kt` avec un commentaire `// updated for Phase 2bis: PointPerDistance integrated`.
3. Tolérance 0.5% reste valide pour la régression.

Documenter le changement dans `docs/parity.md`.

### 5. Vérification ktlint + non-régression complète

```bash
./gradlew :engine:allTests :elevation:allTests
./gradlew ktlintCheck
```

Tous les tests doivent passer (avec ajustements ParityFixtures + éventuels EnhancerTest).

### 6. Smoke manuel : vérifier la sortie GPX

```bash
./gradlew :engine:run -Pargs="enhance ../virtual-cyclist/gpx/sample.gpx -o /tmp/out-phase2bis.gpx"
```

Comparer (visuellement / `wc -l` / taille fichier) avec la sortie Phase 2 (sans `PointPerDistance`). Le nouveau fichier doit avoir :
- Plus de points (densification)
- Distance totale similaire (légère variation due à l'interpolation linéaire vs Haversine sur petits segments)
- Durée similaire (le profil de speeds reste cohérent)

## Outputs

Modifiés :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt` (intégration `PointPerDistance`)
- `engine/src/commonTest/kotlin/io/github/glandais/engine/EnhancerTest.kt` (ajustements si tailles asserérées)
- `engine/src/commonTest/kotlin/io/github/glandais/engine/parity/ParityFixtures.kt` (valeurs régénérées)
- `engine/src/commonTest/kotlin/io/github/glandais/engine/parity/EnhancerParityTest.kt` (éventuels ajustements de tolérance)
- `docs/parity.md` (note Phase 2bis)
- `engine/src/jvmTest/kotlin/io/github/glandais/engine/FullPipelineSmokeTest.kt` (budget de durée explicite)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
./gradlew :engine:run -Pargs="enhance .../sample.gpx -o /tmp/out.gpx"  # smoke manuel
```

Critères :

- Tous tests `:engine` et `:elevation` verts.
- Smoke E2E sample.gpx : pipeline complet termine en < 10 s sur JVM.
- Fichier de sortie GPX produit, parseable, distance et durée plausibles.
- `ktlintCheck` vert.

## Done when

- [x] `Enhancer.kt` : `PointPerDistance.compute(path, -1.0, 30.0)` ajouté avant `fixElevation`
- [x] `Enhancer.kt` : `PointPerDistance.compute(path, 1.0, 2.0)` ajouté avant `smoothElevation`
- [x] `EnhancerTest` tests existants ajustés (tailles, propriétés) et verts
- [x] `ParityFixtures` valeurs régénérées et commentées
- [x] `EnhancerParityTest` toujours vert
- [x] `FullPipelineSmokeTest` budget de durée vérifié
- [x] Smoke manuel `:engine:run` réussit avec pipeline complet
- [x] `:engine:allTests` + `:elevation:allTests` verts ; `ktlintCheck` vert
- [x] Phase 2bis complète (tâches 29-31) — annoter dans PLAN.md
- [x] Toutes les checkboxes cochées

## Notes

- **Pas d'option opt-out** : `PointPerDistance` fait partie intégrante du pipeline. Si un appelant veut un path "raw" non-densifié, il doit appeler les étapes individuelles sans passer par `Enhancer`.
- **Coût mémoire** : 110k points × 36 slots × 8 bytes ≈ 32 Mo. Acceptable pour JVM, à surveiller pour Wasm (limite mémoire ~2-4 GB en pratique, mais browsers limitent souvent à 512 Mo). Si problème, la tâche `engine-cli` peut documenter l'usage mémoire et l'utilisateur peut downsizer le path source.
- **Densification linéaire vs Haversine** : `PointPerDistance` interpole lat/lon linéairement. Sur des segments < 100 m, l'erreur Haversine est négligeable (< 1 cm). Pas de problème.
- **Préparation post-Phase 2bis** : avec ces 3 corrections, le pipeline Kotlin reproduit fidèlement le pipeline TS. La parité numérique (tâche 26) reste auto-baseline (le TS n'a pas été exécuté en parallèle), mais peut être consolidée par un script manuel hors-CI plus tard.
- **Phase 3 envisageable** : (a) demo Compose Multiplatform Web+Desktop, (b) publication npm package, (c) parité numérique stricte vs TS via fixtures JSON régénérées par un run TS hors-CI.
