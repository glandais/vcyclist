# 04 — Elevation : ElevationSmoother (lissage par noyau triangulaire)

## Goal

Porter en Kotlin le lissage d'altitude par fenêtre de distance avec noyau triangulaire :

- Pour chaque point, moyenne pondérée des altitudes voisines dont la distance cumulée au point courant est `≤ windowSize` mètres.
- Poids triangulaire : `w = 1 − d / windowSize` (linéaire, max au centre, 0 aux bornes).
- Fast-path : moins de 3 points → retour de l'input tel quel.
- Garde-fou : `windowSize ≤ 0` → exception avec message conforme au TS.

Utilise `Distance.cumulativeDistances` (tâche 02) et `AlgorithmConstants.MIN_SMOOTHING_POINTS` (tâche 01).

## Depends on

- `01-elevation-coords-vector` (`CoordinatesElevation`, `LatLonElevation`, `AlgorithmConstants.MIN_SMOOTHING_POINTS`)
- `02-elevation-distance-ecef` (`Distance.cumulativeDistances`)

## Inputs

Sources à porter :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/utils/ElevationSmoother.ts` — classe complète (`smooth` public, `computeSmoothedValue` privé)
- `/home/glandais/code/perso/vcyclist-all/elevation/test/utils/ElevationSmoother.test.ts` — 11 cas de test à porter

## Steps

### 1. `ElevationSmoother.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationSmoother.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.absoluteValue

object ElevationSmoother {

    /**
     * Apply distance-based elevation smoothing using a triangular kernel.
     *
     * For each point, average its elevation with all points within [windowSize] meters
     * (along the cumulative path distance), weighted by `1 − d / windowSize`.
     *
     * Returns the input unchanged if the path has fewer than [AlgorithmConstants.MIN_SMOOTHING_POINTS]
     * points. Throws if [windowSize] is not strictly positive.
     *
     * @param points input path (must have an explicit elevation per point)
     * @param windowSize smoothing window in meters (default 50)
     * @return a new list of smoothed points (lat/lon preserved, elevation replaced)
     */
    fun smooth(
        points: List<CoordinatesElevation>,
        windowSize: Double = 50.0,
    ): List<CoordinatesElevation> {
        if (points.size < AlgorithmConstants.MIN_SMOOTHING_POINTS) return points
        require(windowSize > 0.0) { "Invalid window size: ${formatWindow(windowSize)}. Must be positive" }

        val distances = Distance.cumulativeDistances(points)
        return List(points.size) { i ->
            val smoothed = computeSmoothedValue(i, points, distances, windowSize)
            LatLonElevation(points[i].latitude, points[i].longitude, smoothed)
        }
    }

    private fun computeSmoothedValue(
        index: Int,
        points: List<CoordinatesElevation>,
        distances: DoubleArray,
        windowSize: Double,
    ): Double {
        val current = distances[index]

        var startIndex = index
        while (startIndex > 0 && current - distances[startIndex - 1] <= windowSize) {
            startIndex--
        }

        var endIndex = index
        while (endIndex < points.size - 1 && distances[endIndex + 1] - current <= windowSize) {
            endIndex++
        }

        var totalWeight = 0.0
        var weightedSum = 0.0
        for (j in startIndex..endIndex) {
            val d = (distances[j] - current).absoluteValue
            val weight = 1.0 - d / windowSize
            totalWeight += weight
            weightedSum += points[j].elevation * weight
        }

        return if (totalWeight > 0.0) weightedSum / totalWeight else points[index].elevation
    }

    /** Formats `windowSize` like the TS message (`0` not `0.0`, `-50` not `-50.0`). */
    private fun formatWindow(w: Double): String {
        val asLong = w.toLong()
        return if (asLong.toDouble() == w) asLong.toString() else w.toString()
    }
}
```

**Notes design** :
- API publique : `List<CoordinatesElevation>` in/out. Lat/lon préservés, on émet de nouveaux `LatLonElevation` plutôt que `.copy()` (l'input est typé `CoordinatesElevation` interface, pas forcément `LatLonElevation`).
- `require(windowSize > 0.0)` lève `IllegalArgumentException` avec le **même** message que le TS. Le helper `formatWindow` garantit que `0` et `-50` (entiers) ne s'affichent pas `0.0` / `-50.0`, ce qui matchait la chaîne TS exacte testée.
- `DoubleArray distances` : retour direct de `Distance.cumulativeDistances` (tâche 02), pas de boxing.
- Complexité : `O(n × k)` où `k` est le nombre de points dans la fenêtre (identique au TS — le scan `startIndex/endIndex` n'est pas amorti à `O(1)` car re-parti de `index` à chaque appel). Optimisation possible (sliding window O(n)) reportée à une tâche perf si profilage l'exige.
- `currentDistance - distances[startIndex - 1] <= windowSize` : on inclut les voisins **strictement à la borne** comme le TS (inégalité large). Important pour la parité.
- Le fallback `if (totalWeight > 0)` est une sécurité défensive ; en pratique le point courant lui-même a un poids de 1.0 → `totalWeight >= 1.0`. On le garde pour parité.

### 2. Tests `ElevationSmootherTest.kt`

`elevation/src/commonTest/kotlin/io/github/glandais/elevation/ElevationSmootherTest.kt`.

Port direct des 11 cas TS.

Cas à couvrir :

| # | Cas TS | Entrée | Attendu |
|---|---|---|---|
| 1 | `return original data for less than 3 points` | 2 points | `result == points` (référence ou contenu identique) |
| 2 | `throw error for invalid window size` | 3 points + windowSize 0 et -50 | `IllegalArgumentException` avec messages exacts `"Invalid window size: 0. Must be positive"` et `"Invalid window size: -50. Must be positive"` |
| 3 | `smooth with default window size 50` | 5 points ~11 m d'écart, alt {100, 200, 120, 180, 150} | `result[2] != 120`, `100 < result[1] < 200`, `result[0] > 100`, `result[4] < 180` |
| 4 | `different smoothing with different window sizes` | 5 points avec spike (alt 100, 300, 100, 100, 100) | `largeWindow[1] < smallWindow[1] < 300` |
| 5 | `preserve elevation at edges with appropriate weights` | 5 points (500, 100, 100, 100, 600) windowSize=30 | `200 < result[0] < 500`, `200 < result[4] < 600`, `100 < result[2] < 400` |
| 6 | `handle points far apart correctly` | 3 points ~1110 m d'écart, windowSize=50 | `result == points` (rien à lisser) |
| 7 | `handle dense points correctly` | 5 points ~1.1 m d'écart, alt {100, 200, 150, 250, 120}, windowSize=10 | `result[2] != 150` et `result[2] > 150` |
| 8 | `triangular kernel weighting correctly` | 3 points (0, 100, 0) windowSize=25 | `0 < result[1] < 100`, `result[0] > 0`, `result[2] > 0` |
| 9 | `edge case where no points are within window` | 3 points ~110 km, windowSize=10 | `result == points` (chaque point isolé garde alt exacte) |
| 10 | `preserve coordinates while smoothing elevations` | 3 points proches (45.123, -122.456) | latitudes/longitudes inchangées, elevations modifiées |
| 11 | `smooth realistic elevation profile` | 11 points colline + bruit, windowSize=40 | bruit réduit (`result[3] > 108`, `result[7] < 130`), max ≤ max original, variation moyenne adjacente < 10 |

Quelques squelettes :

```kotlin
package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElevationSmootherTest {

    @Test fun `returns original data for less than 3 points`() {
        val pts = listOf(
            LatLonElevation(45.0, 0.0, 100.0),
            LatLonElevation(45.001, 0.0, 150.0),
        )
        val result = ElevationSmoother.smooth(pts)
        assertEquals(pts, result)
    }

    @Test fun `throws on zero window size with TS-compatible message`() {
        val pts = listOf(
            LatLonElevation(45.0, 0.0, 100.0),
            LatLonElevation(45.001, 0.0, 150.0),
            LatLonElevation(45.002, 0.0, 200.0),
        )
        val ex0 = assertFailsWith<IllegalArgumentException> { ElevationSmoother.smooth(pts, 0.0) }
        assertEquals("Invalid window size: 0. Must be positive", ex0.message)

        val exNeg = assertFailsWith<IllegalArgumentException> { ElevationSmoother.smooth(pts, -50.0) }
        assertEquals("Invalid window size: -50. Must be positive", exNeg.message)
    }

    // … cas 3-11 sur le même modèle
}
```

Pour le cas 11, helper `avgAbsAdjacentDelta`:
```kotlin
private fun avgAbsAdjacentDelta(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    var sum = 0.0
    for (i in 1 until values.size) sum += (values[i] - values[i - 1]).absoluteValue
    return sum / (values.size - 1)
}
```

### 3. Vérification ktlint

Aucun pattern atypique. Le `require { … }` est idiomatique Kotlin.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationSmoother.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/ElevationSmootherTest.kt`

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :elevation:build
./gradlew :engine:allTests       # non-régression
```

Critères :

- **`ElevationSmootherTest`** : ≥ 11 tests verts par target.
- Cumul `:elevation` : ≥ 8 classes de test, ≥ 86 tests par target.
- `ktlintCheck` vert.
- `:engine:allTests` toujours vert.
- Messages d'exception **exactement** identiques au TS (`"Invalid window size: 0. Must be positive"`, `"Invalid window size: -50. Must be positive"`).

## Done when

- [x] `ElevationSmoother.kt` créé et compile sur les 3 targets
- [x] `ElevationSmootherTest.kt` (≥ 11 tests) créé
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Messages d'exception conformes (vérifiés `assertEquals`)
- [x] Toutes les checkboxes ci-dessus cochées dans le fichier

## Notes

- **Format du message d'erreur** : le TS produit `Invalid window size: 0. Must be positive` (pas `0.0`). En Kotlin, `Double.toString()` de `0.0` retournerait `"0.0"`. Le helper `formatWindow` détecte le cas « entier représentable » et renvoie la forme entière. Test 2 du tableau verrouille ce comportement.
- **Pourquoi `LatLonElevation` plutôt que `.copy()`** : l'input est typé `CoordinatesElevation` (interface). On ne peut pas garantir que c'est une `data class` avec `.copy()`. La construction directe d'un `LatLonElevation` reste la solution la plus simple et la plus rapide. Si plus tard on a un type concret commun (issu de la Phase 2 `Path`), on adaptera.
- **`require` vs `throw IllegalArgumentException`** : `require` est idiomatique et expose le même type d'exception ; le message lazy n'est évalué que si la condition échoue.
- **Inégalités larges (`<=`) dans le scan** : un point exactement à `windowSize` mètres est inclus (poids = 0 → contribue 0 mais reste dans la boucle). Identique au TS.
- **Cas dégénéré totalWeight = 0** : impossible en pratique (le point courant lui-même contribue avec poids 1.0 puisque sa distance à lui-même est 0). Le fallback `points[index].elevation` est défensif ; couvert implicitement par chaque test (tous les retours sont des moyennes valides).
- **Pas de variance reduction explicite** : le plan mentionnait « variance reduction » mais le test TS « realistic elevation profile » mesure plutôt la variation moyenne entre points adjacents. On porte cette même métrique (`avgAbsAdjacentDelta < 10`), qui capture l'objectif de lissage de manière plus stable que la variance globale.
- **Pas de `enabled=false` ici** : la `SmoothingOptions { enabled: boolean }` du TS est gérée à un niveau supérieur (dans `BatchCalculator`, tâche 08). À ce stade `ElevationSmoother.smooth` est inconditionnel ; l'appelant décide d'appeler ou non.
- **Algorithme alternatif O(n)** : on pourrait maintenir une fenêtre glissante `[start, end]` qui ne reviendrait jamais en arrière (les deux indices ne font que progresser), réduisant la complexité totale à `O(n)`. Différence négligeable sur `n < 10⁴`, à benchmarker en Phase 8.
