# 03 — Elevation : DouglasPeucker (simplification 3D)

## Goal

Porter en Kotlin l'algorithme de Douglas-Peucker en 3D :

- Simplifie un chemin `List<CoordinatesElevation>` en supprimant les points dont la distance perpendiculaire au segment courant (en coordonnées ECEF) est inférieure à une `tolerance` exprimée en mètres.
- Préserve **toujours** le premier et le dernier point.
- Utilise `EcefConverter` (tâche 02) pour passer en repère cartésien, et `Vector3D.distanceToSegment` (tâche 01) pour le calcul des distances.
- Paramètre `zExaggeration` qui amplifie verticalement les écarts d'altitude (défaut 3) pour favoriser la rétention de points sur les variations de relief.

## Depends on

- `01-elevation-coords-vector` (`Vector3D.distanceToSegment`, `CoordinatesElevation`)
- `02-elevation-distance-ecef` (`EcefConverter.toEcef`)

## Inputs

Sources de référence à porter :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/utils/DouglasPeucker.ts` — classe complète, méthode publique `simplify` + privée `simplifyRecursive`
- `/home/glandais/code/perso/vcyclist-all/elevation/test/filtering.test.ts` — bloc `describe('DouglasPeucker')` lignes 153-216 (5 cas) + bloc `describe('Integration Tests')` lignes 218-239 (1 cas)

## Steps

### 1. `DouglasPeucker.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/DouglasPeucker.kt` :

```kotlin
package io.github.glandais.elevation

object DouglasPeucker {

    /**
     * Simplify a 3D elevation profile using the Douglas-Peucker algorithm.
     *
     * Converts every candidate point to ECEF (with [zExaggeration] applied) and removes any
     * intermediate point whose perpendicular distance to the current segment is below [tolerance]
     * (meters). The first and last points are always preserved.
     *
     * @param points input path (must have an explicit elevation per point)
     * @param tolerance maximum allowed perpendicular distance in meters
     * @param zExaggeration elevation exaggeration factor applied during ECEF conversion (default 3)
     * @return a new list containing the retained points, in original order
     */
    fun simplify(
        points: List<CoordinatesElevation>,
        tolerance: Double,
        zExaggeration: Double = 3.0,
    ): List<CoordinatesElevation> {
        if (points.size <= 2) return points.toList()

        val lastIndex = points.lastIndex
        return buildList(points.size) {
            add(points[0])
            simplifyRecursive(points, 0, lastIndex, tolerance, zExaggeration, this)
            add(points[lastIndex])
        }
    }

    private fun simplifyRecursive(
        points: List<CoordinatesElevation>,
        firstIndex: Int,
        lastIndex: Int,
        tolerance: Double,
        zExaggeration: Double,
        out: MutableList<CoordinatesElevation>,
    ) {
        val firstEcef = EcefConverter.toEcef(points[firstIndex], zExaggeration)
        val lastEcef = EcefConverter.toEcef(points[lastIndex], zExaggeration)

        var maxDistance = 0.0
        var maxIndex = -1

        for (i in (firstIndex + 1) until lastIndex) {
            val d = EcefConverter.toEcef(points[i], zExaggeration)
                .distanceToSegment(firstEcef, lastEcef)
            if (d > maxDistance) {
                maxDistance = d
                maxIndex = i
            }
        }

        if (maxDistance > tolerance && maxIndex != -1) {
            if (maxIndex - firstIndex > 1) {
                simplifyRecursive(points, firstIndex, maxIndex, tolerance, zExaggeration, out)
            }
            out.add(points[maxIndex])
            if (lastIndex - maxIndex > 1) {
                simplifyRecursive(points, maxIndex, lastIndex, tolerance, zExaggeration, out)
            }
        }
    }
}
```

**Notes design** :
- API publique reçoit `List<CoordinatesElevation>` et renvoie `List<CoordinatesElevation>` (immutable). Le helper récursif écrit dans un `MutableList` passé en paramètre — évite la création de listes intermédiaires à chaque récursion (différence d'implémentation vs TS qui faisait `result.push(...leftSegment)`, mais résultat strictement équivalent).
- `points.toList()` pour le cas `size <= 2` retourne une **copie défensive** comme `[...points]` côté TS.
- Pas de logger porté (cf. tâche 01, note logger). Si un jour on veut tracer, on ajoutera un wrapper léger dédié au module.
- `firstEcef` et `lastEcef` sont recalculés à chaque récursion — exact même comportement que le TS. Optimisation possible (mémorisation) mais non-prioritaire ; à mesurer en Phase 8 si besoin.

### 2. Tests `DouglasPeuckerTest.kt`

`elevation/src/commonTest/kotlin/io/github/glandais/elevation/DouglasPeuckerTest.kt`.

Port direct des 5 cas de `filtering.test.ts` + 1 cas intégration. La factory `createTestPath()` peut être une `private fun` du fichier de test.

Cas à couvrir :

| Cas | Entrée | Attendu |
|---|---|---|
| Tolerance très basse (0.1) préserve toute la trace | 4 points de `createTestPath()` | `size == 4`, premier == input[0], dernier == input[3] |
| Tolerance très haute (1000) simplifie | 4 points de `createTestPath()` | `size < 4`, premier et dernier inchangés |
| Path de 2 points ignore la simplification | 2 points | `size == 2`, contenu identique (copie) |
| Path de 1 point | 1 point | `size == 1`, contenu identique (copie défensive) |
| Path vide | `emptyList()` | `emptyList()` retourné (sémantique `points.size <= 2`) |
| Altitudes identiques | 3 points, même elevation | `size >= 2` (au moins start + end) |
| Mountain path (Zermatt 5 pts) avec tolerance=20 m | 5 points alpins | `size >= 2`, premier et dernier inchangés |
| Premier et dernier préservés sous tolerance extrême | path de 10 points, tolerance=`1e9` | `size == 2`, exactement premier + dernier |
| Copie défensive | input mutable, simplify, mutation d'input | sortie inchangée (immutabilité de `LatLonElevation` → automatique côté Kotlin) |

### 3. Test d'intégration `DouglasPeuckerIntegrationTest.kt`

Reproduit le bloc `describe('Integration Tests')` ligne 218-239 du TS : combine `EcefConverter.convertBatch` + `DouglasPeucker.simplify` sur un mini-path alpin.

```kotlin
package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DouglasPeuckerIntegrationTest {
    @Test fun `ECEF batch conversion and Douglas-Peucker work together`() {
        val testPath = listOf(
            LatLonElevation(46.5197, 9.8544, 1000.0),
            LatLonElevation(46.5198, 9.8545, 1001.0),  // proche, filtré
            LatLonElevation(46.5199, 9.8546, 1200.0),  // saut significatif
            LatLonElevation(46.5200, 9.8547, 1500.0),
        )

        val ecefVectors = EcefConverter.convertBatch(testPath, 3.0)
        assertEquals(4, ecefVectors.size)

        val simplified = DouglasPeucker.simplify(testPath, 10.0, 3.0)
        assertTrue(simplified.size <= testPath.size)
        assertEquals(testPath.first(), simplified.first())
        assertEquals(testPath.last(), simplified.last())
    }
}
```

### 4. Vérification ktlint

Le `buildList(size)` avec lambda multi-statement passe ktlint par défaut. Pas de formatage spécifique requis.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/DouglasPeucker.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/DouglasPeuckerTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/DouglasPeuckerIntegrationTest.kt`

Aucune modification/suppression.

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :elevation:build
./gradlew :engine:allTests       # non-régression
```

Critères :

- **`DouglasPeuckerTest`** : ≥ 9 tests verts par target.
- **`DouglasPeuckerIntegrationTest`** : 1 test vert par target.
- Cumulé `:elevation` : ≥ 6 classes de test, ≥ 75 tests par target.
- `ktlintCheck` vert.
- `:engine:allTests` toujours vert.

## Done when

- [x] `DouglasPeucker.kt` créé et compile sur les 3 targets
- [x] `DouglasPeuckerTest.kt` (≥ 9 tests) créé
- [x] `DouglasPeuckerIntegrationTest.kt` (1 test) créé
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Cas « tolerance extrême → 2 points exact » prouve que la coupe radicale fonctionne
- [x] Cas « path vide » et « path 1 point » prouvent la robustesse du fast-path `<= 2`
- [x] Toutes les checkboxes ci-dessus cochées dans le fichier

## Notes

- **Différence implémentation vs TS** : le TS construit récursivement des sous-listes (`result.push(...leftSegment)`) avec allocation à chaque étage. La version Kotlin passe un `MutableList` accumulateur, ce qui économise des allocations et préserve l'ordre. Le résultat est strictement le même (même ordre, mêmes points) car la récursion est en pré-ordre gauche → point pivot → droite.
- **Cas `points.size == 0` et `1`** : non couverts par le TS (qui traitait implicitement via `<= 2`). On les rend explicites côté Kotlin pour figer le contrat (la sémantique reste « renvoyer une copie de l'input »).
- **`points.toList()` sur une `List<CoordinatesElevation>`** : alloue une nouvelle `ArrayList`. Pour des paths longs en fast-path, c'est suffisant ; si plus tard `Path` (Phase 2) utilise un `DoubleArray` plat, on adaptera la signature ici via une surcharge dédiée.
- **Pas de variante mutable** : `LatLonElevation` est une `data class` immutable → la copie défensive du TS n'a en réalité aucun effet observable. On la garde pour parité.
- **Tolérance "très haute" (1000 m)** : sur un path de 4 points couvrant ~470 m (0.003° lat ≈ 333 m + alt), la simplification retire **tous** les points intermédiaires → résultat = [first, last]. Vérifié indirectement par le test « tolerance extrême → 2 points exact ».
- **Performance** : la conversion ECEF est appelée plusieurs fois par point (1 pour le firstEcef de la branche parent, 1 pour le lastEcef, 1 pour le point lui-même, parfois plusieurs si re-visité). Identique au TS. Optimisation possible : convertir une fois en début de `simplify` dans un `Array<Vector3D>` et passer les indices à la récursive. À traiter dans une tâche de perf séparée si le profilage le justifie en Phase 8.
- **`buildList(size)` capacity hint** : on passe `points.size` comme capacité initiale pour éviter les redimensionnements. Pour `points.size = 1_000_000`, économie non négligeable.
- **Référence Wikipedia** : algorithme classique, voir https://en.wikipedia.org/wiki/Ramer%E2%80%93Douglas%E2%80%93Peucker_algorithm — la version 3D ici diffère de la version 2D usuelle uniquement par la projection ECEF qui transforme lat/lon/alt en coordonnées cartésiennes métriques où la distance Euclidienne est physiquement significative.
