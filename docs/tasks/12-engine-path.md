# 12 — Engine : `Path` (stats + helpers + bridge `:elevation`)

## Goal

Faire émerger la classe `Path : GeneratedPath` qui ajoute aux 36 slots numériques :

- **Statistiques cumulées** calculées en une passe : `totalDistance`, `elevationGain`, `elevationLoss`, `minElevation`, `maxElevation`, `durationMs`, `boundsRad` (lat/lon min/max **en radians**, conformes au stockage).
- **`computeDerivedData()`** : recalcule les champs dérivés (`distance` cumulée par Haversine, `elapsed`, `dx`, `dt`, `speed`, `grade`, `bearing`) à partir de `latitude`, `longitude`, `elevation`, `time`. Port direct du `computeDerivedData()` TS (2-pass).
- **Helpers ergonomiques** :
  - `forEachPoint { i -> … }` (inline)
  - `indices: IntRange` (compatible boucle `for (i in path.indices)`)
  - `subPath(from, until): Path` (copie d'une plage)
  - `copy(): Path` (clone complet via `data.copyOf()`)
- **Bridge `:elevation`** (le module est désormais `api`-dépendance, cf. tâche 10) :
  - `latitudeDeg(i)` / `longitudeDeg(i)` : conversion radians → degrés via `MathConstants.RAD_TO_DEG` (du module `elevation`)
  - `coordinatesAt(i): Coordinates` : retourne un `LatLon(latDeg, lonDeg, elevation)` (interface du module `:elevation`)
  - `coordinatesElevationSequence(): Sequence<CoordinatesElevation>` : permet `path.coordinatesElevationSequence().toList()` puis appel à `ElevationProvider.setElevations(...)`.

Pas de mutations « append point » dynamique ici — `GeneratedPath` est fixed-size. Les opérations de construction (resample, simplify) qui créent des paths de taille variable seront traitées dans la tâche **22** (`PointPerSecond`) via un *builder* dédié. C'est volontaire : `Path` est une **vue immutable de structure** (la taille ne change pas) avec mutations *en place* des slots numériques.

## Depends on

- `10-engine-field-definitions` (`PointField`, accesseurs `latitude/longitude/elevation/time/...`)
- `11-engine-codegen-strategy` (`GeneratedPath`, `data: DoubleArray`, accesseurs nommés/génériques)
- `:elevation` (Phase 1 terminée) — utilise `Distance.haversine`, `MathConstants.RAD_TO_DEG`, `LatLon`, `LatLonElevation`, `Coordinates`, `CoordinatesElevation`.

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/path/Path.ts` — référence canonique (port adapté à la sémantique Kotlin)
- `vcyclist/engine/src/commonMain/kotlin/io/github/glandais/engine/path/GeneratedPath.kt` (déjà généré)
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/{Distance,Constants,Coordinates}.kt`

## Steps

### 1. `Path.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/Path.kt` :

```kotlin
package io.github.glandais.engine.path

import io.github.glandais.elevation.Coordinates
import io.github.glandais.elevation.CoordinatesElevation
import io.github.glandais.elevation.Distance
import io.github.glandais.elevation.LatLon
import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.MathConstants
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Concrete [GeneratedPath] augmented with computed statistics and ergonomic helpers.
 *
 * - Storage is **fixed-size** : the `size` is captured at construction and the underlying
 *   `DoubleArray` is never resized. Pipelines that produce paths of unknown length build a
 *   `MutableList<LatLonElevation>` first and pass it to [Path.fromCoordinates] at the end.
 * - **Latitude/longitude are stored in radians** (matches [PointField.LATITUDE].unit ==
 *   "radians"). Use [latitudeDeg]/[longitudeDeg] or [coordinatesAt] for degree-based access.
 */
class Path(size: Int) : GeneratedPath(size) {

    // ---------- Cached statistics (computed by [computeDerivedData]) -----------

    var totalDistance: Double = 0.0
        private set

    var minElevation: Double = 0.0
        private set

    var maxElevation: Double = 0.0
        private set

    var elevationGain: Double = 0.0
        private set

    /** Always ≤ 0 (sum of negative deltas). */
    var elevationLoss: Double = 0.0
        private set

    /** Duration of the track in milliseconds (`time(size-1) - time(0)`), or 0 if size < 2. */
    var durationMs: Double = 0.0
        private set

    /** Bounding box in **radians** (matches storage units). */
    var boundsRad: BoundsRad = BoundsRad.EMPTY
        private set

    /** Bounding box in radians ; empty for size 0. */
    data class BoundsRad(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
        companion object {
            val EMPTY = BoundsRad(0.0, 0.0, 0.0, 0.0)
        }
    }

    // ---------- Iteration helpers ---------------------------------------------

    val indices: IntRange get() = 0 until size

    inline fun forEachPoint(action: (Int) -> Unit) {
        for (i in 0 until size) action(i)
    }

    // ---------- Coordinate helpers --------------------------------------------

    fun latitudeDeg(i: Int): Double = latitude(i) * MathConstants.RAD_TO_DEG
    fun longitudeDeg(i: Int): Double = longitude(i) * MathConstants.RAD_TO_DEG

    fun coordinatesAt(i: Int): Coordinates =
        LatLon(latitudeDeg(i), longitudeDeg(i), elevation(i))

    fun coordinatesElevationAt(i: Int): CoordinatesElevation =
        LatLonElevation(latitudeDeg(i), longitudeDeg(i), elevation(i))

    fun coordinatesElevationSequence(): Sequence<CoordinatesElevation> =
        (0 until size).asSequence().map { coordinatesElevationAt(it) }

    // ---------- Bulk operations -----------------------------------------------

    /** Deep copy of the underlying storage. Stats are re-derived (not copied). */
    fun copy(): Path {
        val out = Path(size)
        data.copyInto(out.data, destinationOffset = 0, startIndex = 0, endIndex = data.size)
        out.copyStatsFrom(this)
        return out
    }

    /** Slice `[from, until)` into a new [Path]. Stats are recomputed lazily — call
     *  [computeDerivedData] on the result if needed. */
    fun subPath(from: Int, until: Int): Path {
        require(from in 0..size) { "from=$from out of [0, $size]" }
        require(until in from..size) { "until=$until out of [$from, $size]" }
        val newSize = until - from
        val out = Path(newSize)
        data.copyInto(
            destination = out.data,
            destinationOffset = 0,
            startIndex = from * PointField.COUNT,
            endIndex = until * PointField.COUNT,
        )
        return out
    }

    private fun copyStatsFrom(other: Path) {
        totalDistance = other.totalDistance
        minElevation = other.minElevation
        maxElevation = other.maxElevation
        elevationGain = other.elevationGain
        elevationLoss = other.elevationLoss
        durationMs = other.durationMs
        boundsRad = other.boundsRad
    }

    // ---------- Derived data --------------------------------------------------

    /**
     * Recompute every derived field from the 4 primary inputs : `latitude`, `longitude`,
     * `elevation`, `time`. Output fields written : `distance`, `elapsed`, `dx`, `dt`, `speed`,
     * `grade`, `bearing`. Stats properties are also refreshed.
     *
     * Port of `Path.ts#computeDerivedData()` (two-pass algorithm).
     */
    fun computeDerivedData() {
        resetStats()
        if (size == 0) return

        val timeStart = time(0)

        // First pass : cumulative distance, elevation gain/loss, geographic bounds.
        var cumDist = 0.0
        var minLat = Double.POSITIVE_INFINITY
        var maxLat = Double.NEGATIVE_INFINITY
        var minLon = Double.POSITIVE_INFINITY
        var maxLon = Double.NEGATIVE_INFINITY
        var minEle = Double.POSITIVE_INFINITY
        var maxEle = Double.NEGATIVE_INFINITY
        var gain = 0.0
        var loss = 0.0

        for (i in 0 until size) {
            val lat = latitude(i)
            val lon = longitude(i)
            val ele = elevation(i)

            minLat = min(minLat, lat); maxLat = max(maxLat, lat)
            minLon = min(minLon, lon); maxLon = max(maxLon, lon)
            minEle = min(minEle, ele); maxEle = max(maxEle, ele)

            if (i > 0) {
                val prevCoord = LatLon(latitudeDeg(i - 1), longitudeDeg(i - 1))
                val curCoord = LatLon(latitudeDeg(i), longitudeDeg(i))
                cumDist += Distance.haversine(prevCoord, curCoord)

                val dEle = ele - elevation(i - 1)
                if (dEle > 0) gain += dEle else loss += dEle
            }
            setDistance(i, cumDist)
        }

        totalDistance = cumDist
        minElevation = if (minEle == Double.POSITIVE_INFINITY) 0.0 else minEle
        maxElevation = if (maxEle == Double.NEGATIVE_INFINITY) 0.0 else maxEle
        elevationGain = gain
        elevationLoss = loss
        boundsRad = BoundsRad(minLat, maxLat, minLon, maxLon)
        durationMs = if (size >= 2) time(size - 1) - timeStart else 0.0

        // Second pass : per-point elapsed, dx, dt, speed, grade, bearing.
        for (i in 0 until size) {
            setElapsed(i, (time(i) - timeStart) / 1000.0)
            if (size <= 1) continue

            val im1 = max(0, i - 1)
            val ip1 = min(size - 1, i + 1)

            setBearing(i, computeBearing(im1, ip1))

            val dDist = (distance(ip1) - distance(im1)) / 2.0
            val dEle = (elevation(ip1) - elevation(im1)) / 2.0
            val grade = if (dDist == 0.0) 0.0 else dEle / dDist
            setGrade(i, grade)

            val dTime = (time(ip1) - time(im1)) / 2000.0
            setDx(i, dDist)
            setDt(i, dTime)
            setSpeed(i, if (dTime == 0.0) 0.0 else dDist / dTime)
        }
    }

    private fun resetStats() {
        totalDistance = 0.0
        minElevation = 0.0
        maxElevation = 0.0
        elevationGain = 0.0
        elevationLoss = 0.0
        durationMs = 0.0
        boundsRad = BoundsRad.EMPTY
    }

    /** Bearing between two points in radians, using simple cylindrical projection (good enough
     *  for short segments at non-polar latitudes). Port of `Path.ts#computeBearing`. */
    private fun computeBearing(from: Int, to: Int): Double {
        val lat1 = latitude(from); val lon1 = longitude(from)
        val lat2 = latitude(to);   val lon2 = longitude(to)
        // Cylindrical projection (x = lon * cos(lat), y = lat)
        val x1 = lon1 * cos(lat1); val y1 = lat1
        val x2 = lon2 * cos(lat2); val y2 = lat2
        val dy = y2 - y1
        val dx = x2 - x1
        return atan2(-dy, dx)
    }

    companion object {
        /**
         * Build a [Path] from a sequence of [LatLonElevation], copying lat/lon (converted to
         * radians) and elevation into the first three slots. Other slots remain 0.0.
         * Call [computeDerivedData] afterwards if you need `distance`, `bearing`, etc.
         */
        fun fromCoordinates(coords: List<CoordinatesElevation>): Path {
            val path = Path(coords.size)
            for ((i, c) in coords.withIndex()) {
                path.setLatitude(i, c.latitude * MathConstants.DEG_TO_RAD)
                path.setLongitude(i, c.longitude * MathConstants.DEG_TO_RAD)
                path.setElevation(i, c.elevation)
            }
            return path
        }
    }
}
```

### 2. Tests `PathTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/path/PathTest.kt` :

Cas à couvrir :

| # | Cas | Attendu |
|---|---|---|
| 1 | `Path(0)` — vide | size=0, `totalDistance=0`, `boundsRad==EMPTY` après `computeDerivedData` |
| 2 | `Path(1)` — point unique | `computeDerivedData` ne crashe pas, `totalDistance=0`, `durationMs=0` |
| 3 | `Path(3)` ligne droite équateur à 1 km de distance entre chaque | `totalDistance ≈ 2000` à ±5 m, `boundsRad` correct |
| 4 | Path montée 100 m → 300 m → 200 m | `elevationGain ≈ 200`, `elevationLoss ≈ -100`, `min=100`, `max=300` |
| 5 | `computeDerivedData` calcule `grade` correctement (montée 5% sur 100 m) | `grade(1) ≈ 0.05` |
| 6 | `computeDerivedData` calcule `speed` correctement (100 m en 10 s = 10 m/s) | `speed(1) ≈ 10.0` à ±1e-6 |
| 7 | `coordinatesAt(i)` retourne degrés | conversion radians ↔ degrés round-trip |
| 8 | `forEachPoint` itère sur tous les indices | counter = size |
| 9 | `indices` est `0 until size` | identité |
| 10 | `copy()` produit une instance distincte avec mêmes données et stats | `data.contentEquals(other.data)`, `totalDistance` identique, mutation de `out.setLatitude(0, ...)` n'affecte pas l'original |
| 11 | `subPath(1, 4)` extrait 3 points | new size=3, latitudes correctes |
| 12 | `subPath(0, 0)` retourne path vide | size=0 |
| 13 | `subPath` rejette indices hors bornes | `IllegalArgumentException` |
| 14 | `fromCoordinates([LatLonElevation(45.0, 6.0, 1000.0), …])` populate lat/lon/elevation | lat radians ≈ 45° × π/180, elevation matches |
| 15 | `coordinatesElevationSequence().toList().size == size` | propriété d'iter |
| 16 | `durationMs` cohérent (time(0)=1000, time(N-1)=11000 → 10000) | sentinel |
| 17 | `boundsRad` non-EMPTY après `computeDerivedData` avec ≥ 1 point | propriété |
| 18 | `subPath(2, 5).copy()` produit le même résultat que `subPath(2, 5)` puis copy | propriété |

Squelette (extrait — 18 cas à dérouler) :

```kotlin
package io.github.glandais.engine.path

import io.github.glandais.elevation.LatLonElevation
import io.github.glandais.elevation.MathConstants
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PathTest {

    private fun pathFromLatLonEle(vararg triples: Triple<Double, Double, Double>): Path {
        val p = Path(triples.size)
        for ((i, t) in triples.withIndex()) {
            p.setLatitude(i, t.first * MathConstants.DEG_TO_RAD)
            p.setLongitude(i, t.second * MathConstants.DEG_TO_RAD)
            p.setElevation(i, t.third)
        }
        return p
    }

    @Test fun `empty path computes zero stats`() {
        val p = Path(0)
        p.computeDerivedData()
        assertEquals(0.0, p.totalDistance)
        assertEquals(Path.BoundsRad.EMPTY, p.boundsRad)
    }

    @Test fun `single point path is safe`() {
        val p = pathFromLatLonEle(Triple(0.0, 0.0, 100.0))
        p.computeDerivedData()
        assertEquals(0.0, p.totalDistance)
        assertEquals(0.0, p.durationMs)
    }

    @Test fun `three points ~1km apart on equator`() {
        // Equator, longitude 0/0.00898/0.01796 ≈ 1km steps
        val p = pathFromLatLonEle(
            Triple(0.0, 0.0,         50.0),
            Triple(0.0, 0.00898315, 50.0),
            Triple(0.0, 0.01796630, 50.0),
        )
        p.computeDerivedData()
        assertTrue(abs(p.totalDistance - 2000.0) < 5.0, "got ${p.totalDistance}")
    }

    @Test fun `elevation gain and loss are correct on a hill profile`() {
        val p = pathFromLatLonEle(
            Triple(0.0, 0.0,    100.0),
            Triple(0.0, 0.0009, 300.0),
            Triple(0.0, 0.0018, 200.0),
        )
        p.computeDerivedData()
        assertEquals(200.0, p.elevationGain)
        assertEquals(-100.0, p.elevationLoss)
        assertEquals(100.0, p.minElevation)
        assertEquals(300.0, p.maxElevation)
    }

    // ... 14 more tests (grade, speed, copy, subPath, fromCoordinates, durationMs, etc.)
}
```

### 3. Vérification ktlint

Imports triés, indentation 4 espaces. Si `ktlintCheck` râle, lancer `:engine:ktlintFormat`.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/engine/src/commonMain/kotlin/io/github/glandais/engine/path/Path.kt`
- `vcyclist/engine/src/commonTest/kotlin/io/github/glandais/engine/path/PathTest.kt`

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests     # non-régression
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- `PathTest` : **≥ 18 tests verts** par target (JVM + JS Node + Wasm browser).
- `:engine:build` compile sans erreur (import du module `:elevation`).
- `ktlintCheck` vert.
- `:elevation:allTests` toujours vert.
- Sentinel numérique : test #3 vérifie `totalDistance ≈ 2000 m ± 5 m` sur l'équateur (validation que la chaîne Haversine + degrés↔radians est correcte).

## Done when

- [x] `Path.kt` créé avec stats, helpers, `computeDerivedData`, bridge `:elevation`
- [x] `Path.fromCoordinates` companion helper
- [x] `PathTest.kt` créé avec ≥ 18 cas
- [x] `./gradlew :engine:allTests` vert (3 targets)
- [x] `./gradlew :elevation:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Sentinel équateur 2 km à ±5 m passe
- [x] Toutes les checkboxes cochées

## Notes

- **Lat/lon en radians dans le storage** : aligné avec `PointField.LATITUDE.unit == "radians"` et le TS. Les API publiques ergonomiques (`latitudeDeg`, `coordinatesAt`, etc.) convertissent à la volée.
- **`Distance.haversine` du module `:elevation`** : prend des `Coordinates` en degrés. On crée des `LatLon(latDeg, lonDeg)` temporaires dans `computeDerivedData`. C'est moins efficace que de réimplémenter Haversine en radians ici, mais évite la duplication. Si profilage le justifie en Phase 8, on factorisera un `Distance.haversineRad(lat1, lon1, lat2, lon2): Double` côté `:elevation`.
- **Pas d'`addInterpolatedFrom` / `addFrom`** : différence par rapport au TS. Notre `GeneratedPath` est fixed-size — pas de capacity dynamique. Les opérations de construction live dans la tâche **22** (`PointPerSecond`) qui prendra la forme :
  ```kotlin
  class PathBuilder { fun add(coords: CoordinatesElevation); fun build(): Path }
  ```
- **`BoundsRad` en radians plutôt qu'en degrés** : la conversion est triviale (`× RAD_TO_DEG`). On exposera un wrapper `boundsDeg` plus tard si la demo UI en a besoin.
- **`durationMs` en `Double`** : cohérent avec le type de stockage (les valeurs `time` sont stockées en `Double` même si elles représentent des `Long` epoch ms). Pas de cast `Long` à ce stade ; ergonomie `Instant` envisagée en tâche 12bis ou plus tard.
- **`copy()` ré-utilise les stats** : optimisation acceptable car `data.copyInto` est consistant avec les stats du parent. Mutation après `copy()` invaliderait les stats (= bug appelant — on l'accepte, c'est documenté).
- **`subPath` ne recopie pas les stats** : volontaire — la sous-trace a ses propres stats. Appelant doit faire `out.computeDerivedData()` avant lecture.
- **`computeBearing` cylindrique** : précision suffisante pour bearings locaux (< 100 km). Au-delà, considérer une projection sphérique stricte. Le TS utilise la même approche.
- **`copyInto`** : `data.copyInto(out.data)` est intrinsique stdlib — efficace cross-target (intrinsic JVM, `set` JS, `memcpy` Wasm).
- **Test #3 équateur** : 0.00898315° de longitude à l'équateur ≈ 1000 m via Haversine avec `MEAN_RADIUS=6_371_000`. Tolérance ±5 m car la formule cumule des trigs.
- **Préparation tâche 13** : `Path` est consommable. La tâche 13 ajoutera `data class Cyclist` et `Bike` + `Course(path, cyclist, bike)`.
