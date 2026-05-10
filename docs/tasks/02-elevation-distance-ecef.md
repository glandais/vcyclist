# 02 — Elevation : Distance & EcefConverter

## Goal

Porter en Kotlin les utilitaires géométriques du module `:elevation` :

- `Distance` : Haversine (great-circle), Euclidean 3D, point-à-segment 3D, distances cumulées le long d'un chemin, distance totale.
- `EcefConverter` : projection WGS84 → ECEF (Earth-Centered, Earth-Fixed) avec facteur d'exagération d'altitude (`zExaggeration`) utilisé par Douglas-Peucker.

Aucune dépendance externe (`kotlin.math` uniquement). Tests communs aux trois targets, parité numérique avec la version TS.

## Depends on

- `01-elevation-coords-vector` (types `Coordinates`, `Vector3D`, constantes `EarthConstants`/`MathConstants`)

## Inputs

Sources de référence à porter (chemins absolus) :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/utils/Distance.ts` — classe complète (5 méthodes statiques)
- `/home/glandais/code/perso/vcyclist-all/elevation/src/utils/EcefConverter.ts` — classe complète (2 méthodes statiques)
- `/home/glandais/code/perso/vcyclist-all/elevation/test/utils/Distance.test.ts` — 14 cas de test à porter

Note : il n'existe **pas** de `EcefConverter.test.ts` côté TS (couvert indirectement par `filtering.test.ts`). On écrit des tests Kotlin neufs basés sur des propriétés mathématiques connues (pôles, équateur, méridien).

## Steps

### 1. `Distance.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Distance.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Distance {

    /**
     * Great-circle distance in meters using the Haversine formula.
     * Uses [EarthConstants.MEAN_RADIUS].
     */
    fun haversine(coord1: Coordinates, coord2: Coordinates): Double {
        val lat1Rad = coord1.latitude * MathConstants.DEG_TO_RAD
        val lat2Rad = coord2.latitude * MathConstants.DEG_TO_RAD
        val deltaLat = (coord2.latitude - coord1.latitude) * MathConstants.DEG_TO_RAD
        val deltaLon = (coord2.longitude - coord1.longitude) * MathConstants.DEG_TO_RAD

        val sinHalfLat = sin(deltaLat / 2.0)
        val sinHalfLon = sin(deltaLon / 2.0)
        val a = sinHalfLat * sinHalfLat + cos(lat1Rad) * cos(lat2Rad) * sinHalfLon * sinHalfLon
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EarthConstants.MEAN_RADIUS * c
    }

    /** Euclidean distance between two 3D points in meters. */
    fun euclidean3D(point1: Vector3D, point2: Vector3D): Double {
        val dx = point1.x - point2.x
        val dy = point1.y - point2.y
        val dz = point1.z - point2.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * Perpendicular distance from [point] to the segment [[segmentStart], [segmentEnd]] in 3D.
     * Returns the distance to the endpoint if the projection falls outside the segment.
     */
    fun pointToSegment3D(
        point: Vector3D,
        segmentStart: Vector3D,
        segmentEnd: Vector3D,
    ): Double {
        val segmentVector = segmentEnd - segmentStart
        val segmentLengthSquared = segmentVector.dot(segmentVector)
        if (segmentLengthSquared == 0.0) return euclidean3D(point, segmentStart)

        val pointVector = point - segmentStart
        val t = (pointVector.dot(segmentVector) / segmentLengthSquared).coerceIn(0.0, 1.0)
        val closest = segmentStart + segmentVector * t
        return euclidean3D(point, closest)
    }

    /**
     * Cumulative haversine distances along the path. Returns `[0]` for an empty or single-point input
     * (matches TS reference for empty input).
     */
    fun cumulativeDistances(points: List<Coordinates>): DoubleArray {
        if (points.isEmpty()) return doubleArrayOf(0.0)
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) {
            out[i] = out[i - 1] + haversine(points[i - 1], points[i])
        }
        return out
    }

    /** Total path length (sum of segment haversine distances). Returns 0 if fewer than 2 points. */
    fun totalPathDistance(points: List<Coordinates>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversine(points[i - 1], points[i])
        }
        return total
    }
}
```

**Notes design** :
- `object Distance` reproduit fidèlement la classe TS à méthodes statiques.
- `cumulativeDistances` retourne un `DoubleArray` (perf-friendly, équivalent au `number[]` TS) ; on conserve la sémantique surprenante `[0]` pour input vide (testé explicitement côté TS, ligne 153).
- Réutilise les opérateurs `+`, `-`, `*` et `.dot()` de `Vector3D` introduits en tâche 01.

### 2. `EcefConverter.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/EcefConverter.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object EcefConverter {

    /**
     * Convert WGS84 [coordinates] to ECEF (Earth-Centered, Earth-Fixed) Cartesian coordinates.
     * Applies [zExaggeration] to the elevation component (default 3, used by Douglas-Peucker
     * to emphasize vertical deviations).
     */
    fun toEcef(coordinates: Coordinates, zExaggeration: Double = 3.0): Vector3D {
        val latRad = coordinates.latitude * MathConstants.DEG_TO_RAD
        val lonRad = coordinates.longitude * MathConstants.DEG_TO_RAD
        val elevationExaggerated = zExaggeration * (coordinates.elevation ?: 0.0)

        val sinLat = sin(latRad)
        val n = EarthConstants.SEMI_MAJOR_AXIS /
            sqrt(1.0 - EarthConstants.FIRST_ECCENTRICITY_SQUARED * sinLat * sinLat)

        val cosLat = cos(latRad)
        val cosLon = cos(lonRad)
        val sinLon = sin(lonRad)

        val x = (n + elevationExaggerated) * cosLat * cosLon
        val y = (n + elevationExaggerated) * cosLat * sinLon
        val z = (n * (1.0 - EarthConstants.FIRST_ECCENTRICITY_SQUARED) + elevationExaggerated) * sinLat

        return Vector3D(x, y, z)
    }

    /** Batch-convert a list of coordinates to ECEF vectors. Order is preserved. */
    fun convertBatch(coordinates: List<Coordinates>, zExaggeration: Double = 3.0): List<Vector3D> =
        coordinates.map { toEcef(it, zExaggeration) }
}
```

**Notes design** :
- `zExaggeration` est `Double` (le TS l'avait `number` mais documenté comme entier ; on garde `Double` pour ne pas perdre de précision si un appelant passe 2.5 par exemple).
- Le `elevation ?: 0.0` remplace `(coordinates.elevation || 0)` du TS et fonctionne correctement pour `null` (mais pas pour `0.0` qui doit être préservé — différence importante avec le `||` JS qui transformait `0` en `0`, comportement identique ici).

### 3. Tests `DistanceTest.kt`

Port direct des 14 cas TS, plus quelques cas de parité fine. Chemin : `elevation/src/commonTest/kotlin/io/github/glandais/elevation/DistanceTest.kt`.

Cas à couvrir :

**haversine** :
| Cas | Entrée | Borne / valeur attendue |
|---|---|---|
| Paris → quartier Paris | (48.8566, 2.3522) ↔ (48.8606, 2.3376) | 1000 < d < 1500 |
| Paris → Londres | (48.8566, 2.3522) ↔ (51.5074, -0.1278) | 340000 < d < 350000 |
| Same point | (48.8566, 2.3522)² | d == 0.0 (exact) |
| Équateur 1° longitude | (0,0) ↔ (0,1) | 110000 < d < 112000 |
| Antipodaux | (0,0) ↔ (0,180) | 19_900_000 < d < 20_100_000 |
| **Parité fine** : équateur 1° lon, valeur précise | (0,0) ↔ (0,1) | d ≈ `π × MEAN_RADIUS / 180` à 1e-9 (~111195.0797343687) |

**euclidean3D** :
| Cas | Attendu |
|---|---|
| (0,0,0) ↔ (3,4,0) | 5.0 (exact) |
| (1,2,3) ↔ idem | 0.0 (exact) |
| (1,1,1) ↔ (4,5,1) | 5.0 (exact) |

**pointToSegment3D** :
| Cas | Attendu |
|---|---|
| Point (0,1,0), segment (-1,0,0)→(1,0,0) | 1.0 (perpendiculaire) |
| Point sur endpoint (1,0,0) | 0.0 |
| Segment dégénéré (0,0,0)→(0,0,0), point (1,1,0) | √2 ≈ 1.4142… |
| Point au-delà de l'endpoint, projection clampée | distance à l'endpoint |
| Point avant le start, projection clampée à 0 | distance au start |

**cumulativeDistances** :
| Cas | Attendu |
|---|---|
| 4 points à ~11 m d'écart sur latitude | size=4 ; [0]==0 ; 10<[1]<12 ; 20<[2]<24 ; 30<[3]<36 |
| Single point | `[0.0]` |
| Empty list | `[0.0]` (sémantique TS conservée) |

**totalPathDistance** :
| Cas | Attendu |
|---|---|
| 3 points en ligne | 20 < d < 24 |
| Single point | 0.0 |
| Empty | 0.0 |

Tolérances :
- Bornes inégalités : reproduction littérale du TS.
- Parité fine : `1e-9` (équateur 1° lon = `PI * 6_371_000 / 180`).

### 4. Tests `EcefConverterTest.kt`

Pas d'équivalent TS — on écrit basé sur propriétés mathématiques connues. Chemin : `elevation/src/commonTest/kotlin/io/github/glandais/elevation/EcefConverterTest.kt`.

Cas à couvrir :

| Cas | Entrée | Attendu (tolérance) |
|---|---|---|
| Origine (lat=0, lon=0, ele=0, zExag=0) | `LatLonElevation(0,0,0)`, zExag=0 | `(SEMI_MAJOR_AXIS, 0, 0)` à 1e-6 |
| Équateur, 90°E (lat=0, lon=90) | `LatLonElevation(0,90,0)`, zExag=0 | `(0, SEMI_MAJOR_AXIS, 0)` à 1e-6 |
| Équateur, 180° (lat=0, lon=180) | `LatLonElevation(0,180,0)`, zExag=0 | `(-SEMI_MAJOR_AXIS, 0, 0)` à 1e-6 |
| Pôle Nord (lat=90, lon=any, ele=0) | `LatLonElevation(90,0,0)`, zExag=0 | `(0, 0, b)` où b = `SEMI_MAJOR_AXIS × √(1 − e²)` à 1e-6 |
| Pôle Sud (lat=-90) | `LatLonElevation(-90,0,0)`, zExag=0 | `(0, 0, -b)` à 1e-6 |
| Magnitude sur ellipsoïde ≈ R_mean | `LatLonElevation(45,0,0)`, zExag=0 | `|v| ∈ [SEMI_MAJOR_AXIS × √(1−e²), SEMI_MAJOR_AXIS]` |
| Default zExaggeration = 3 | `LatLon(0, 0, 1000.0)` (interface base, elevation? = 1000) | x ≈ SEMI_MAJOR_AXIS + 3000.0 à 1e-6 |
| zExaggeration explicite = 0 ignore elevation | `LatLonElevation(0,0,1000)`, zExag=0 | x ≈ SEMI_MAJOR_AXIS exact |
| Elevation null traitée comme 0 | `LatLon(0,0,null)`, zExag=3 | x ≈ SEMI_MAJOR_AXIS (pas de NaN, pas d'erreur) |
| Antipodaux donnent vecteurs opposés en x/y | `(0,0,0)` vs `(0,180,0)` | x₂ == -x₁, y₂ == y₁, z₂ == z₁ |
| `convertBatch` préserve l'ordre et la taille | liste de 5 coords | size=5, identique à `toEcef` un-par-un |
| `convertBatch` empty | `emptyList()` | `emptyList()` |
| `convertBatch` zExag custom propage | liste, zExag=5 | concordance avec `toEcef(_, 5.0)` |

Tolérance par défaut : `1e-6` (les calculs ECEF impliquent des produits de très grands nombres ~6.4e6 et des fonctions trig, donc 1e-6 absolu sur ~10^7 = ~10^-13 relatif, largement suffisant).

Squelette :

```kotlin
package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-6

private fun assertVectorClose(expected: Vector3D, actual: Vector3D, eps: Double = EPS) {
    assertTrue((expected.x - actual.x).absoluteValue < eps, "x: expected $expected got $actual")
    assertTrue((expected.y - actual.y).absoluteValue < eps, "y: expected $expected got $actual")
    assertTrue((expected.z - actual.z).absoluteValue < eps, "z: expected $expected got $actual")
}

class EcefConverterTest {
    // …
}
```

### 5. Nettoyage

Rien à supprimer — les `.gitkeep` de `jvmMain/jsMain/wasmJsMain` restent pertinents tant qu'on n'y écrit pas (toujours pas le cas en tâche 02).

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Distance.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/EcefConverter.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/DistanceTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/EcefConverterTest.kt`

Modifiés/supprimés : aucun.

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:allTests      # 3 targets
./gradlew ktlintCheck
./gradlew :elevation:build
./gradlew :engine:allTests         # non-régression (smoke engine encore en place)
```

Critères :

- **`DistanceTest`** : ≥ 14 tests verts par target (port du TS) + ≥ 1 test parité fine équateur.
- **`EcefConverterTest`** : ≥ 13 tests verts par target.
- Total cumulé `:elevation` : ≥ 5 classes de test, ≥ 60 tests par target.
- `ktlintCheck` vert.
- `:engine:allTests` toujours vert.

## Done when

- [x] 2 fichiers source `commonMain` créés et compilent sur JVM/JS/Wasm
- [x] 2 fichiers tests créés
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Test parité fine équateur (`d ≈ π × R_mean / 180 à 1e-9`) passe sur les 3 targets — vérifie que `sin`/`atan2`/`sqrt` produisent des résultats identiques (à 1e-9 près) entre JVM/JS/Wasm
- [x] Toutes les checkboxes ci-dessus cochées dans le fichier

## Notes

- **Parité numérique multi-target** : la spec Kotlin garantit la même *définition* de `kotlin.math.{sin, cos, atan2, sqrt}` mais pas la précision bit-à-bit (libc Linux vs V8 vs Wasm runtime peuvent différer au dernier bit). C'est pour ça que les tests de parité utilisent une tolérance `1e-9` plutôt qu'une égalité exacte. Si un test échoue uniquement sur Wasm/JS avec un écart > 1e-9, relâcher à `1e-7` et le documenter dans une nouvelle note ici.
- **Pourquoi `DoubleArray` plutôt que `List<Double>` pour `cumulativeDistances`** : retour fortement typé, perf-friendly (pas d'auto-boxing), aligné avec le futur modèle `Path` (Phase 2) qui utilisera des `DoubleArray` partout. La signature TS `number[]` se mappe naturellement.
- **Cas dégénéré `cumulativeDistances([])` → `[0.0]`** : sémantique étrange du TS (un tableau singleton plutôt qu'un tableau vide). Conservée pour parité ; si jugée gênante plus tard, traitée dans une tâche dédiée avec un breaking change.
- **`elevation: null` vs `elevation: 0.0`** : ECEF traite les deux identiquement (pas d'altitude ajoutée). C'est aussi le cas dans le TS (`||` falsy). Test explicite ajouté pour figer ce comportement.
- **Pas de `DoubleArray.contentEquals`** dans les tests : on accède par index (plus lisible quand la sémantique de chaque indice est testée).
- **Inégalités strictes du TS** : `toBeGreaterThan(1000)` → `assertTrue(d > 1000)` (pas d'égalité). Conservé tel quel pour parité comportementale.
- **`Distance` comme `object`** : équivalent strict de la classe TS à méthodes statiques. Si on souhaitait plus tard un mock via DI, on pourrait introduire une `interface DistanceCalculator` ; pas nécessaire à ce stade.
- **Hypot vs sqrt(dx² + dy² + dz²)** : le TS utilise `Math.sqrt(dx² + dy² + dz²)`, on garde la même formulation dans `euclidean3D` (par opposition à `Vector3D.distanceTo` qui utilise `hypot`). Sémantiquement équivalent ; l'écart numérique éventuel est inférieur à `1e-12`.
