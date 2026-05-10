# 01 — Elevation : Coordinates, Constants & Vector3D

## Goal

Porter en Kotlin Multiplatform les fondations du module `:elevation` :

- les types géographiques (`Coordinates`, `CoordinatesElevation`),
- les constantes Terre/maths/algorithme (`EARTH_CONSTANTS`, `MATH_CONSTANTS`, `ALGORITHM_CONSTANTS`),
- la classe utilitaire `Vector3D` (ECEF) avec opérations vectorielles.

Tous portent **0 dépendance** au-delà de la stdlib Kotlin et sont accessibles aux trois targets (JVM, JS Node, Wasm browser). Le smoke test bootstrap est remplacé par les tests réels de cette tâche.

## Depends on

- `00-bootstrap` (squelette Gradle KMP, source sets en place)

## Inputs

Sources de référence à porter (chemins absolus) :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/types.ts` — interfaces `Coordinates`, `CoordinatesElevation`, helper `asCoordinatesElevation` (lignes 1-16)
- `/home/glandais/code/perso/vcyclist-all/elevation/src/utils/Constants.ts` — trois objets de constantes
- `/home/glandais/code/perso/vcyclist-all/elevation/src/utils/Vector3D.ts` — classe complète avec 10 méthodes
- `/home/glandais/code/perso/vcyclist-all/elevation/test/utils/Constants.test.ts` — cas de test à porter mot-à-mot

Note : il n'existe **pas** de `Vector3D.test.ts` côté TS (couverture assurée indirectement). On écrit donc des tests Kotlin neufs et exhaustifs.

## Steps

### 1. `Coordinates.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Coordinates.kt` :

```kotlin
package io.github.glandais.elevation

interface Coordinates {
    val latitude: Double
    val longitude: Double
    val elevation: Double?
}

interface CoordinatesElevation : Coordinates {
    override val elevation: Double
}

data class LatLon(
    override val latitude: Double,
    override val longitude: Double,
    override val elevation: Double? = null,
) : Coordinates

data class LatLonElevation(
    override val latitude: Double,
    override val longitude: Double,
    override val elevation: Double,
) : CoordinatesElevation

fun Coordinates.toCoordinatesElevation(): CoordinatesElevation =
    LatLonElevation(latitude, longitude, elevation ?: 0.0)
```

**Notes design** :
- Kotlin n'autorise pas un champ `val elevation: Double?` covarié en `val elevation: Double` sur une simple `data class`. On sépare donc en deux interfaces + deux data classes concrètes (`LatLon`, `LatLonElevation`).
- Le helper `toCoordinatesElevation()` remplace `asCoordinatesElevation` (extension idiomatique Kotlin).

### 2. `Constants.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Constants.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.PI

object EarthConstants {
    /** Semi-major axis in meters (WGS84 ellipsoid) */
    const val SEMI_MAJOR_AXIS: Double = 6_378_137.0

    /** Mean radius in meters (used for distance calculations) */
    const val MEAN_RADIUS: Double = 6_371_000.0

    /** First eccentricity squared (WGS84 ellipsoid) */
    const val FIRST_ECCENTRICITY_SQUARED: Double = 0.006_694_379_990_14

    /** Web Mercator latitude bound (north/south) in degrees */
    const val WEB_MERCATOR_MAX_LAT: Double = 85.051_128_779_806_59
}

object MathConstants {
    /** Degrees → radians factor */
    val DEG_TO_RAD: Double = PI / 180.0

    /** Radians → degrees factor */
    val RAD_TO_DEG: Double = 180.0 / PI
}

object AlgorithmConstants {
    /** Minimum points needed for smoothing operations */
    const val MIN_SMOOTHING_POINTS: Int = 3
}
```

**Note** : `DEG_TO_RAD`/`RAD_TO_DEG` ne peuvent pas être `const val` car `kotlin.math.PI` n'est pas une compile-time constant. Utilisation de `val` (initialisation à l'`object` init).

### 3. `Vector3D.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Vector3D.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.hypot

data class Vector3D(val x: Double, val y: Double, val z: Double) {

    fun distanceTo(other: Vector3D): Double =
        hypot(hypot(x - other.x, y - other.y), z - other.z)

    operator fun minus(other: Vector3D): Vector3D =
        Vector3D(x - other.x, y - other.y, z - other.z)

    operator fun plus(other: Vector3D): Vector3D =
        Vector3D(x + other.x, y + other.y, z + other.z)

    operator fun times(scalar: Double): Vector3D =
        Vector3D(x * scalar, y * scalar, z * scalar)

    fun dot(other: Vector3D): Double = x * other.x + y * other.y + z * other.z

    fun cross(other: Vector3D): Vector3D = Vector3D(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun magnitude(): Double = hypot(hypot(x, y), z)

    fun normalize(): Vector3D {
        val mag = magnitude()
        if (mag == 0.0) return ZERO
        return this * (1.0 / mag)
    }

    fun distanceToSegment(segmentStart: Vector3D, segmentEnd: Vector3D): Double {
        val segmentVector = segmentEnd - segmentStart
        val segmentLengthSq = segmentVector.dot(segmentVector)

        if (segmentLengthSq == 0.0) return distanceTo(segmentStart)

        val pointVector = this - segmentStart
        val projection = pointVector.dot(segmentVector) / segmentLengthSq
        val clamped = projection.coerceIn(0.0, 1.0)
        val closest = segmentStart + segmentVector * clamped
        return distanceTo(closest)
    }

    companion object {
        val ZERO: Vector3D = Vector3D(0.0, 0.0, 0.0)
    }
}
```

**Notes design** :
- Kotlin stdlib n'expose pas `hypot(a, b, c)` à 3 args : on chaîne `hypot(hypot(x, y), z)`.
- `multiply(scalar)` → opérateur idiomatique `times`.
- `subtract`/`add` → `minus`/`plus` (operator overload).
- Garde la sémantique TS exacte : `normalize()` d'un vecteur nul retourne `ZERO` ; `distanceToSegment` clampe la projection à `[0, 1]`.
- Optimisation mineure vs TS : on travaille avec `segmentLengthSq` au lieu de `length * length` (économise une `sqrt`).

### 4. Tests

**`ConstantsTest.kt`** (port direct du TS) :

```kotlin
package io.github.glandais.elevation

import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-12

class ConstantsTest {
    @Test fun `earth - WGS84 semi-major axis`() {
        assertEquals(6_378_137.0, EarthConstants.SEMI_MAJOR_AXIS)
    }

    @Test fun `earth - mean radius`() {
        assertEquals(6_371_000.0, EarthConstants.MEAN_RADIUS)
    }

    @Test fun `earth - WGS84 first eccentricity squared`() {
        assertTrue((EarthConstants.FIRST_ECCENTRICITY_SQUARED - 0.006_694_379_990_14).absoluteValue < EPS)
    }

    @Test fun `earth - mean radius lesser than semi-major axis`() {
        assertTrue(EarthConstants.MEAN_RADIUS < EarthConstants.SEMI_MAJOR_AXIS)
    }

    @Test fun `math - DEG_TO_RAD`() {
        assertTrue((MathConstants.DEG_TO_RAD - PI / 180.0).absoluteValue < EPS)
    }

    @Test fun `math - RAD_TO_DEG`() {
        assertTrue((MathConstants.RAD_TO_DEG - 180.0 / PI).absoluteValue < EPS)
    }

    @Test fun `math - reciprocal conversion`() {
        assertTrue((MathConstants.DEG_TO_RAD * MathConstants.RAD_TO_DEG - 1.0).absoluteValue < EPS)
    }

    @Test fun `algorithm - min smoothing points`() {
        assertEquals(3, AlgorithmConstants.MIN_SMOOTHING_POINTS)
    }
}
```

**`Vector3DTest.kt`** (couverture exhaustive, nouvelle) :

Cas à couvrir :

| Cas | Méthode | Attendu |
|---|---|---|
| Vecteurs `(1,2,3)` et `(4,6,8)` | `distanceTo` | `√(9+16+25) ≈ 7.0710678…` |
| Soustraction `(5,7,9) - (1,2,3)` | `minus` | `(4,5,6)` |
| Addition `(1,1,1) + (2,3,4)` | `plus` | `(3,4,5)` |
| Multiplication `(1,2,3) * 2.5` | `times` | `(2.5,5.0,7.5)` |
| Dot `(1,2,3) · (4,5,6)` | `dot` | `32.0` |
| Cross `(1,0,0) × (0,1,0)` | `cross` | `(0,0,1)` |
| Cross anti-commutatif `a×b == -(b×a)` | `cross` | propriété |
| Magnitude `(3,4,12)` | `magnitude` | `13.0` |
| Magnitude vecteur nul | `magnitude` | `0.0` |
| Normalize `(3,4,0)` | `normalize` | `(0.6, 0.8, 0)` et `magnitude≈1` |
| Normalize vecteur nul | `normalize` | `ZERO` |
| Distance to segment, point sur segment | `distanceToSegment` | `0.0` |
| Distance to segment, point avant start (projection<0) | `distanceToSegment` | `distance(point, start)` |
| Distance to segment, point après end (projection>1) | `distanceToSegment` | `distance(point, end)` |
| Distance to segment, point au milieu perpendiculaire | `distanceToSegment` | distance perpendiculaire |
| Distance to segment, segment dégénéré (start=end) | `distanceToSegment` | `distance(point, start)` |
| Data class equality | `equals` / `hashCode` | identique pour mêmes coords |
| `ZERO` constant | `companion` | `(0,0,0)` |

Squelette :

```kotlin
package io.github.glandais.elevation

import kotlin.math.absoluteValue
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val EPS = 1e-9

private fun assertVectorClose(expected: Vector3D, actual: Vector3D, eps: Double = EPS) {
    assertTrue((expected.x - actual.x).absoluteValue < eps, "x: expected $expected got $actual")
    assertTrue((expected.y - actual.y).absoluteValue < eps, "y: expected $expected got $actual")
    assertTrue((expected.z - actual.z).absoluteValue < eps, "z: expected $expected got $actual")
}

class Vector3DTest {
    // … tests listés ci-dessus, un @Test par ligne du tableau
}
```

**`CoordinatesTest.kt`** :

```kotlin
package io.github.glandais.elevation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoordinatesTest {
    @Test fun `LatLon defaults elevation to null`() {
        val p = LatLon(48.8566, 2.3522)
        assertNull(p.elevation)
    }

    @Test fun `LatLon can carry elevation`() {
        val p = LatLon(48.8566, 2.3522, 35.0)
        assertEquals(35.0, p.elevation)
    }

    @Test fun `toCoordinatesElevation defaults missing elevation to zero`() {
        val p: Coordinates = LatLon(0.0, 0.0)
        val withEle = p.toCoordinatesElevation()
        assertEquals(0.0, withEle.elevation)
    }

    @Test fun `toCoordinatesElevation preserves existing elevation`() {
        val p: Coordinates = LatLon(0.0, 0.0, 42.5)
        val withEle = p.toCoordinatesElevation()
        assertEquals(42.5, withEle.elevation)
    }

    @Test fun `LatLonElevation always exposes a non-null elevation`() {
        val p = LatLonElevation(45.0, 6.0, 1800.0)
        val asBase: Coordinates = p
        assertEquals(1800.0, asBase.elevation)
    }
}
```

### 5. Nettoyage

- Supprimer `elevation/src/commonTest/kotlin/io/github/glandais/elevation/SmokeTest.kt` (remplacé par les trois fichiers ci-dessus).
- Supprimer `elevation/src/commonMain/kotlin/io/github/glandais/elevation/.gitkeep` (ce dossier contient désormais du code).

### 6. Vérification ktlint

Code conforme aux règles ktlint par défaut (imports triés, indentation 4 espaces, pas de trailing whitespace). Si besoin, lancer `./gradlew ktlintFormat` une fois.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Coordinates.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Constants.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Vector3D.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/CoordinatesTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/ConstantsTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/Vector3DTest.kt`

Supprimés :

- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/SmokeTest.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/.gitkeep`

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:jvmTest                 # tests JVM
./gradlew :elevation:jsNodeTest              # tests JS Node
./gradlew :elevation:wasmJsBrowserTest       # tests Wasm browser
./gradlew :elevation:allTests                # raccourci équivalent
./gradlew ktlintCheck                        # style
./gradlew :elevation:build                   # build complet
```

Critères :

- Sur chaque target : **3 classes de test**, **5 tests Coordinates**, **8 tests Constants**, **≥ 17 tests Vector3D**, **0 failure, 0 error**.
- `ktlintCheck` vert.
- `:engine:allTests` continue de passer (smoke test du bootstrap intact côté `:engine`).
- Parité numérique : les assertions sur `EARTH_CONSTANTS.SEMI_MAJOR_AXIS`, `MEAN_RADIUS`, `FIRST_ECCENTRICITY_SQUARED`, `MIN_SMOOTHING_POINTS` reprennent **exactement** les valeurs des tests TS (`Constants.test.ts`).

## Done when

- [x] 3 fichiers source `commonMain` créés et compilent sur JVM/JS/Wasm
- [x] 3 fichiers tests créés, ≥ 30 tests au total
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert (régression)
- [x] `./gradlew ktlintCheck` sans violation
- [x] `SmokeTest.kt` de `:elevation` supprimé
- [x] `.gitkeep` de `commonMain/.../elevation/` supprimé
- [x] Toutes les checkboxes ci-dessus cochées dans le fichier

## Notes

- **Pourquoi pas `data class Coordinates(...)` unique** : Kotlin refuse de redéfinir une propriété en passant de `Double?` à `Double` non-null dans une hiérarchie de `data class`. La solution `interface + 2 data classes` reste type-safe et idiomatique. Les consommateurs reçoivent une `Coordinates` (interface) et peuvent tester `is CoordinatesElevation` si besoin.
- **`asCoordinatesElevation` → extension `toCoordinatesElevation`** : convention Kotlin (`to` pour les conversions vers un autre type).
- **Pas de `Logger` porté ici** : la stratégie de logging avec dead-code elimination du TS (`__DEV__`) ne s'applique pas en Kotlin. On reviendra dessus si nécessaire avec un wrapper léger autour de `println`/SLF4J, mais ce n'est pas requis pour les algorithmes purs des prochaines tâches.
- **Tolérance numérique** : `1e-12` pour les constantes (identités triviales), `1e-9` pour les opérations sur `Vector3D` (compositions de `sqrt`). Ces tolérances serviront de référence pour les tâches suivantes (`Distance`, `EcefConverter`, etc.).
- **`Vector3D` mutable ?** : non, on garde l'immutabilité du TS. Les opérations renvoient toujours un nouveau `Vector3D`. Le coût d'allocation sera mesuré en Phase 8 (parité) ; si excessif sur les boucles serrées de Douglas-Peucker, une variante mutable interne pourra être introduite dans une tâche dédiée.
- **`companion object ZERO`** : ajout par rapport au TS qui crée `new Vector3D(0, 0, 0)` à chaque appel. Économie négligeable mais lecture plus claire dans `normalize()`.
- **Opérateurs Kotlin (`+`, `-`, `*`)** : préférés à des méthodes nommées pour les expressions vectorielles à venir dans Douglas-Peucker et EcefConverter (`segmentVector * clamped + segmentStart` se lit mieux que `segmentStart.add(segmentVector.multiply(clamped))`).
- **Limite Web Mercator** : `WEB_MERCATOR_MAX_LAT = 85.05112877980659` ajouté ici (au lieu d'attendre la tâche tile-decoding) car c'est une constante Terre, pas un détail de tuile. Pas utilisé dans cette tâche ; servira en `05-elevation-tile-types-decoding`.
- **Pas de Vector3D operator div** : pas requis par le TS, pas ajouté pour éviter du code mort. À ajouter dans une tâche ultérieure si un appelant en a besoin.
