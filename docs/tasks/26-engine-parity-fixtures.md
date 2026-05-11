# 26 — Engine : parité numérique vs TS (fixtures end-to-end)

## Goal

Vérifier qu'à structure d'entrée comparable, le pipeline Kotlin produit des résultats **numériquement proches** de la référence TypeScript. Ce n'est pas du bit-exact (deux implémentations indépendantes ne convergent pas au dernier ULP), mais des tolérances physiquement raisonnables :

- Distance totale du parcours : **±0.5 %**
- Vitesse moyenne : **±0.5 km/h**
- Durée totale : **±0.5 %**
- Gain/perte d'élévation : **±1 m** (résolution Terrarium tile)

**Stratégie** :
1. **Générer** côté TS (one-shot, hors CI) la sortie `Enhancer.enhanceCourseDefault(parsed)` pour les fixtures `sample.gpx`, `garmin.gpx` et un GPX alpin (e.g. fragment de col).
2. **Sérialiser** ces sorties en JSON compact (key fields seulement : `latitude`, `longitude`, `elevation`, `time`, `speed`, `pComputedPower`, `distance`) dans `engine/src/commonTest/resources/parity/`.
3. **Lire** ces JSON depuis Kotlin (en *inline string* dans `ParityFixtures.kt` puisque les ressources `commonTest` ne sont pas portables KMP — cf. pattern tâche 14).
4. **Comparer** : pour chaque fixture, lancer le pipeline Kotlin sur la même GPX d'entrée, et comparer point-à-point (ou métrique globale) avec la fixture TS.

Tolérances spéciales :
- Comparaison **point-à-point** difficile car les pipelines peuvent produire des nombres de points différents (resample 1Hz puis simplify). Solution : comparer des **métriques globales** (somme distance, durée, gain alt) plutôt que point-à-point.
- Si on veut du point-à-point : ré-interpoler les deux sorties à des temps communs (e.g. 0, 1, 2, ... s) et comparer.

## Depends on

- `25-engine-enhancer` (pipeline complet `Enhancer.enhanceCourseDefault`)
- `14-engine-gpx-parser` (parser GPX) et fixtures (`sample.gpx` etc.)

## Inputs

- TS reference : `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/` (lancer `npm run dev` ou un script ad-hoc pour produire la sortie de référence)
- Fixtures GPX d'entrée : `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/gpx/{sample,garmin,amazfit}.gpx` (déjà copiées dans `engine/src/commonTest/resources/`)

## Steps

### 1. Génération des fixtures de référence TS (hors-CI)

⚠ Cette étape est **manuelle** et faite une seule fois. Le résultat est commité comme une fixture pseudo-immutable.

Créer (hors-arbre Kotlin) un mini script Node `scripts/generate-parity-fixtures.ts` (à exécuter dans le projet TS `virtual-cyclist`) :

```typescript
// Pseudo-code — adapter à l'API TS exacte
import { GPXParser, Enhancer } from '../../../virtual-cyclist/dist/index.node.mjs';
import { readFileSync, writeFileSync } from 'fs';

const fixtures = ['sample', 'garmin', 'amazfit'];
for (const name of fixtures) {
    const xml = readFileSync(`../virtual-cyclist/gpx/${name}.gpx`, 'utf-8');
    const paths = GPXParser.parse(xml);
    const enhanced = await Enhancer.enhanceCourseDefault(paths.tracks[0]);
    
    // Extract metrics + downsampled time series
    const out = {
        totalDistance: enhanced.getTotalDistance(),
        totalElevationGain: enhanced.getTotalElevationGain(),
        totalElevationLoss: enhanced.getTotalElevationLoss(),
        pointCount: enhanced.length,
        durationMs: enhanced.getTime(enhanced.length - 1) - enhanced.getTime(0),
    };
    writeFileSync(`engine/src/commonTest/resources/parity/${name}.json`, JSON.stringify(out, null, 2));
}
```

Le format JSON sert de "vérité terrain". Limité à des **métriques globales** pour éviter les divergences point-à-point inhérentes aux deux implémentations.

### 2. Inline des fixtures dans `ParityFixtures.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/parity/ParityFixtures.kt` :

```kotlin
package io.github.glandais.engine.parity

/**
 * Reference metrics produced by the TS Enhancer.enhanceCourseDefault pipeline on each
 * input GPX. Generated once via `scripts/generate-parity-fixtures.ts` (hors-CI).
 */
data class ParityMetrics(
    val totalDistance: Double,
    val totalElevationGain: Double,
    val totalElevationLoss: Double,
    val pointCount: Int,
    val durationMs: Double,
)

object ParityFixtures {
    val SAMPLE = ParityMetrics(
        totalDistance = /* valeur exacte produite par le TS */,
        totalElevationGain = ...,
        totalElevationLoss = ...,
        pointCount = ...,
        durationMs = ...,
    )
    // GARMIN, AMAZFIT idem
}
```

⚠ Les valeurs exactes doivent être **collées depuis la sortie JSON du script TS**. Si le TS n'est pas exécutable au moment de l'implémentation (build cassé, dépendances manquantes), placer des valeurs *placeholder* (e.g. `1000.0`) et marquer les tests `@Ignore` jusqu'à la prochaine validation manuelle. La fixture peut être mise à jour ultérieurement.

### 3. Tests `EnhancerParityTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/parity/EnhancerParityTest.kt` :

```kotlin
package io.github.glandais.engine.parity

import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.gpx.GpxFixtures
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class EnhancerParityTest {

    @Test fun `sample-gpx total distance within 0_5 percent of TS reference`() = runTest {
        val path = GpxParser.parse(GpxFixtures.SAMPLE_GPX_XML).firstTrackAsPath()
        val out = Enhancer.enhanceCourseDefault(path, options = EnhanceOptions.DEFAULT.copy(fixElevation = false))
        val ref = ParityFixtures.SAMPLE
        val rel = abs(out.totalDistance - ref.totalDistance) / ref.totalDistance
        assertTrue(rel < 0.005, "totalDistance drift ${rel * 100}% (kt=${out.totalDistance}, ts=${ref.totalDistance})")
    }

    @Test fun `sample-gpx elevation gain within 1m of TS reference`() = runTest {
        val path = GpxParser.parse(GpxFixtures.SAMPLE_GPX_XML).firstTrackAsPath()
        val out = Enhancer.enhanceCourseDefault(path, options = EnhanceOptions.DEFAULT.copy(fixElevation = false))
        assertTrue(abs(out.elevationGain - ParityFixtures.SAMPLE.totalElevationGain) < 1.0)
    }

    @Test fun `sample-gpx duration within 0_5 percent of TS reference`() = runTest {
        val path = GpxParser.parse(GpxFixtures.SAMPLE_GPX_XML).firstTrackAsPath()
        val out = Enhancer.enhanceCourseDefault(path, options = EnhanceOptions.DEFAULT.copy(fixElevation = false))
        val rel = abs(out.durationMs - ParityFixtures.SAMPLE.durationMs) / ParityFixtures.SAMPLE.durationMs
        assertTrue(rel < 0.005)
    }
    // ... idem pour garmin et amazfit
}
```

`options = EnhanceOptions.DEFAULT.copy(fixElevation = false)` car le pipeline TS de référence a aussi `fixElevation` désactivé en l'absence de provider (ou avec un provider mock). Eviter les fetches HTTP réels dans les tests de parité.

### 4. Documentation des écarts attendus

Documenter dans `docs/parity.md` :
- Pourquoi le bit-exact est impossible (`atan2`, `sin`, `cos`, `pow` ULP différences)
- Tolérances choisies et leur justification
- Lien vers `scripts/generate-parity-fixtures.ts` pour future regénération
- État courant des écarts mesurés (à remplir après run)

### 5. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire.

## Outputs

Créés :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/parity/ParityFixtures.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/parity/EnhancerParityTest.kt`
- `docs/parity.md`
- (Optionnel) `scripts/generate-parity-fixtures.ts` (hors-CI, dans le repo `virtual-cyclist`)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
```

Critères :

- `EnhancerParityTest` ≥ 6 tests verts × 3 targets (2 fixtures × 3 métriques minimum).
- Tolérances respectées :
  - distance/durée : ±0.5 %
  - élévation : ±1 m
- Si valeurs TS de référence absentes : tests `@Ignore` documenté. Build continue de passer.
- `:elevation:allTests` toujours vert.

## Done when

- [x] `ParityFixtures.kt` créé (valeurs réelles OU placeholders + `@Ignore`)
- [x] `EnhancerParityTest.kt` créé avec ≥ 6 tests
- [x] `docs/parity.md` créé avec tolérances et écarts mesurés
- [x] `:engine:allTests` vert (tests `@Ignore` comptés comme passés)
- [x] Toutes les checkboxes cochées

## Notes

- **Cas pragmatique sans accès au TS** : si l'agent ne peut pas lancer le TS (Node manquant, dépendances cassées), il peut :
  1. Décider que les tests de parité sont **différés** (créer le squelette + `@Ignore` + note explicite).
  2. Ou utiliser des fixtures *self-referential* : calculer la sortie une fois en Kotlin, la commiter, puis vérifier la stabilité dans le temps (régression test plutôt que parité).
- **Self-referential parity** : approche plus pragmatique. On commit la sortie Kotlin actuelle comme "ground truth", et tout changement futur du pipeline qui dévie de >0.5% fait échouer le test. C'est une protection anti-régression au lieu d'une parité TS stricte. Documenter le choix.
- **`PointPerDistance` non porté** : sera la source principale d'écart avec le TS (densification source de la trace). Tolérance 0.5% peut être insuffisante. Si tests échouent durablement, élargir à 2-3 % pour distance/durée et documenter.
- **`fixElevation` désactivé** : car le TS et le Kotlin utilisent peut-être des fetchers différents (le TS utilise le real provider mapterhorn par défaut). En désactivant, on isole le pipeline physique.
- **Préparation tâche 27 (CLI)** : la même mécanique (`Enhancer.enhanceCourseDefault`) est exposée en CLI.
- **Préparation tâche 28 (JS/Wasm)** : `Enhancer` exposé via `@JsExport` (cf. recette de la tâche bonus `:elevation`).
