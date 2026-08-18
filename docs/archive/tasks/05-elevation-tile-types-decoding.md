# 05 — Elevation : types tuile, ElevationFunctions, Tile (décodage Terrarium)

## Goal

Poser toute la **logique pure** liée aux tuiles, sans encore toucher au fetch HTTP :

- Types `TileCoordinates`, `TileCoordinatesFloat`, `Pixel`, `RGBColor`, `RawTile` (descripteur de tuile brute).
- `ElevationFunctions` : projection Web Mercator (lat/lon ↔ tile/pixel), validation (lat/lon/zoom), `normalizePixel` (gestion des débordements + clamp tuile).
- `Tile` : enveloppe d'un buffer RGBA + décodage Terrarium pixel-par-pixel avec cache mémoire interne (`DoubleArray` de hauteur×largeur).

L'`ElevationCalculator` (interpolation bilinéaire) **est déplacé en tâche 08** : il dépend de `TileManager` (tâche 07) qui n'existe pas encore, et tester l'interpolation sans tile fetching introduit un test-double `TileSource` que la tâche 08 fournira naturellement.

## Depends on

- `01-elevation-coords-vector` (`EarthConstants.WEB_MERCATOR_MAX_LAT`, `MathConstants.DEG_TO_RAD`)

## Inputs

Sources à porter :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/types.ts` — `TileCoordinates`, `TileCoordinatesFloat`, `Pixel`, `RGBColor` (lignes 21-48)
- `/home/glandais/code/perso/vcyclist-all/elevation/src/calculator/ElevationFunctions.ts` — toutes les fonctions exportées
- `/home/glandais/code/perso/vcyclist-all/elevation/src/tile/Tile.ts` — classe abstraite (à simplifier en classe concrète Kotlin) — `decodeElevation`, `getElevation(pixel)`
- `/home/glandais/code/perso/vcyclist-all/elevation/test/calculator/ElevationFunctions.test.ts` — 9 cas `normalizePixel`

Sources non encore testées côté TS (à couvrir avec des tests Kotlin neufs) :

- `toTileCoordinates`, `toTileCoordinatesFloat`, `toPixel` (formules Web Mercator + validations)
- `degToRad`, `isValidLatitude`, `isValidLongitude`, `isValidZoomLevel`
- Décodage Terrarium et cache de `Tile`

## Steps

### 1. Types tuile : `Tiles.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Tiles.kt` :

```kotlin
package io.github.glandais.elevation

/** Integer tile coordinates in the Web Mercator pyramid. */
data class TileCoordinates(val x: Int, val y: Int, val z: Int)

/** Tile coordinates with sub-pixel resolution (used internally for projection math). */
data class TileCoordinatesFloat(
    val x: Int,
    val y: Int,
    val xFloat: Double,
    val yFloat: Double,
    val z: Int,
)

/** A pixel inside a specific tile, with integer pixel coordinates `(x, y)`. */
data class Pixel(val tile: TileCoordinates, val x: Int, val y: Int)

/** Single RGB triplet, components in `0..255`. */
data class RGBColor(val red: Int, val green: Int, val blue: Int)
```

### 2. `RawTile.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/RawTile.kt` :

```kotlin
package io.github.glandais.elevation

/**
 * Raw pixel buffer returned by a [fetchAndDecodeTile][TileFetcher] implementation (task 06).
 *
 * - [rgba] is a packed RGBA byte array, length `width * height * 4`.
 * - Byte ordering: R, G, B, A per pixel, rows top-to-bottom.
 */
data class RawTile(val width: Int, val height: Int, val rgba: ByteArray) {
    init {
        require(rgba.size == width * height * 4) {
            "rgba size ${rgba.size} does not match width*height*4 (${width * height * 4})"
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawTile) return false
        return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + rgba.contentHashCode()
        return result
    }
}
```

Note : `ByteArray` dans une `data class` génère un `equals/hashCode` basé sur identité ; on les override explicitement avec `contentEquals`/`contentHashCode`.

### 3. `ElevationFunctions.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationFunctions.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

object ElevationFunctions {

    fun degToRad(degrees: Double): Double = degrees * MathConstants.DEG_TO_RAD

    fun isValidLatitude(lat: Double): Boolean =
        lat >= -EarthConstants.WEB_MERCATOR_MAX_LAT_TEST && lat <= EarthConstants.WEB_MERCATOR_MAX_LAT_TEST

    fun isValidLongitude(lon: Double): Boolean = lon in -180.0..180.0

    fun isValidZoomLevel(zoom: Int): Boolean = zoom in 0..15

    fun normalizePixel(pixel: Pixel, tileSize: Int): Pixel {
        var x = pixel.x
        var y = pixel.y
        var tileX = pixel.tile.x
        var tileY = pixel.tile.y
        val z = pixel.tile.z

        if (x < 0) { x += tileSize; tileX -= 1 }
        if (x >= tileSize) { x -= tileSize; tileX += 1 }
        if (y < 0) { y += tileSize; tileY -= 1 }
        if (y >= tileSize) { y -= tileSize; tileY += 1 }

        val maxTile = (1 shl z) - 1
        tileX = tileX.coerceIn(0, maxTile)
        tileY = tileY.coerceIn(0, maxTile)

        return Pixel(TileCoordinates(tileX, tileY, z), x, y)
    }

    fun toTileCoordinatesFloat(coords: Coordinates, z: Int): TileCoordinatesFloat {
        require(isValidLatitude(coords.latitude)) {
            "Invalid latitude: ${coords.latitude}. Must be between -85.0511 and 85.0511"
        }
        require(isValidLongitude(coords.longitude)) {
            "Invalid longitude: ${coords.longitude}. Must be between -180 and 180"
        }
        require(isValidZoomLevel(z)) {
            "Invalid zoom level: $z. Must be between 0 and 15"
        }

        val lat = degToRad(coords.latitude)
        val n = (1 shl z).toDouble()
        val xFloat = ((coords.longitude + 180.0) / 360.0) * n
        val yFloat = ((1.0 - ln(tan(lat) + 1.0 / cos(lat)) / kotlin.math.PI) / 2.0) * n

        val maxTile = (1 shl z) - 1
        val x = floor(xFloat).toInt().coerceIn(0, maxTile)
        val y = floor(yFloat).toInt().coerceIn(0, maxTile)

        return TileCoordinatesFloat(x = x, y = y, xFloat = xFloat, yFloat = yFloat, z = z)
    }

    fun toTileCoordinates(coords: Coordinates, z: Int): TileCoordinates {
        val tile = toTileCoordinatesFloat(coords, z)
        return TileCoordinates(tile.x, tile.y, tile.z)
    }

    fun toPixel(coords: Coordinates, z: Int, tileSize: Int): Pixel {
        val tile = toTileCoordinatesFloat(coords, z)
        val px = floor((tile.xFloat - tile.x) * tileSize).toInt().coerceIn(0, tileSize - 1)
        val py = floor((tile.yFloat - tile.y) * tileSize).toInt().coerceIn(0, tileSize - 1)
        return Pixel(TileCoordinates(tile.x, tile.y, z), px, py)
    }
}
```

**Note constante** : le TS valide `lat ∈ [-85.0511, 85.0511]` (5 décimales, troncature). On reproduit **exactement** cette borne. La constante `WEB_MERCATOR_MAX_LAT` introduite en tâche 01 (`85.05112877980659`) est la valeur géodésique précise utilisée pour la projection ; la borne **de validation** est plus laxe (`85.0511`). Pour éviter la confusion, on ajoute en tâche 01 ou ici une seconde constante `WEB_MERCATOR_MAX_LAT_TEST = 85.0511` réservée à `isValidLatitude`. Détail :

```kotlin
// dans Constants.kt (à ajouter dans cette tâche si pas déjà présent)
object EarthConstants {
    // ... existant
    const val WEB_MERCATOR_MAX_LAT_TEST: Double = 85.0511
}
```

Si la dérive sémantique n'est pas tolérée, on peut aussi inliner `85.0511` directement dans `isValidLatitude`. Choix recommandé : **constante nommée** pour clarifier l'intention.

### 4. `Tile.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/Tile.kt` :

```kotlin
package io.github.glandais.elevation

import kotlin.math.round

/**
 * In-memory tile : RGBA pixel buffer with lazy per-pixel Terrarium decoding and a memoization cache.
 *
 * Unlike the TS abstract `Tile`, this class is **concrete** and platform-agnostic — it reads bytes
 * directly from the [rgba] buffer instead of delegating to a `getRGBFromImageData` hook.
 * The fetcher (task 06) produces a [RawTile] that is wrapped into a [Tile].
 */
class Tile(val width: Int, val height: Int, private val rgba: ByteArray) {

    constructor(raw: RawTile) : this(raw.width, raw.height, raw.rgba)

    private val cache: DoubleArray = DoubleArray(width * height) { Double.NaN }

    fun getElevation(pixel: Pixel): Double {
        require(pixel.x in 0 until width) {
            "Invalid x position: ${pixel.x}. Must be between 0 and ${width - 1}"
        }
        require(pixel.y in 0 until height) {
            "Invalid y position: ${pixel.y}. Must be between 0 and ${height - 1}"
        }

        val idx = pixel.y * width + pixel.x
        val cached = cache[idx]
        if (!cached.isNaN()) return cached

        val byteOffset = idx * 4
        val r = rgba[byteOffset].toInt() and 0xFF
        val g = rgba[byteOffset + 1].toInt() and 0xFF
        val b = rgba[byteOffset + 2].toInt() and 0xFF
        val elevation = decodeTerrariumElevation(r, g, b)
        cache[idx] = elevation
        return elevation
    }

    companion object {
        /**
         * Terrarium RGB → elevation decoder.
         *
         * Formula: `(r * 256 + g + b / 256) - 32768`, rounded to 2 decimals to match the TS port.
         *
         * @param r red channel in 0..255
         * @param g green channel in 0..255
         * @param b blue channel in 0..255
         * @return elevation in meters
         */
        fun decodeTerrariumElevation(r: Int, g: Int, b: Int): Double {
            val raw = r * 256.0 + g + b / 256.0 - 32768.0
            return round(raw * 100.0) / 100.0
        }
    }
}
```

**Notes design** :
- Pas d'`abstract` Kotlin ici : la fetch + décodage WebP produit un `ByteArray RGBA`, on n'a pas besoin d'abstraire `getRGBFromImageData` comme côté TS. Le constructeur secondaire `Tile(raw: RawTile)` rend l'usage idiomatique.
- Bytes signés Kotlin → entiers non signés via `and 0xFF`.
- Cache `DoubleArray` initialisé à `NaN` (sentinel) car aucune altitude valide ≈ `NaN` (Terrarium ne produit jamais `NaN`, le plus négatif possible est `-32768`).
- Pas d'API `close()` (l'API TS l'expose pour libérer un canvas Node ; on n'a pas cette ressource native côté Kotlin).
- `round(x * 100) / 100` reproduit exactement l'arrondi à 2 décimales du TS — important pour la parité.

### 5. Tests

#### `TilesTest.kt`

3 tests data class equality/hashCode :
- `TileCoordinates(1, 2, 3) == TileCoordinates(1, 2, 3)`
- `Pixel(tile, 100, 200)` equality
- `RGBColor(255, 128, 0)` equality

#### `RawTileTest.kt`

- Construction valide (256×256×4 bytes) → OK
- `require` rejette une taille incohérente → `IllegalArgumentException`
- `equals` true entre deux RawTile au contenu identique (test critique pour les caches LRU à venir)
- `hashCode` identique pour les mêmes données

#### `ElevationFunctionsTest.kt`

**Port direct des 9 cas TS de `normalizePixel`** :

| # | Cas | Attendu |
|---|---|---|
| 1 | `(z=12, x=100, y=200), px=(128, 64)`, tileSize=256 | inchangé |
| 2 | x=-1, y=128 | x=255, tile.x=99, y/tile.y inchangés |
| 3 | x=128, y=-1 | x/tile.x inchangés, y=255, tile.y=199 |
| 4 | x=256, y=128 | x=0, tile.x=101 |
| 5 | x=128, y=256 | y=0, tile.y=201 |
| 6 | tile=(z=2, x=0, y=0), px=(-10, -10) | tile clampé à (0,0,2) |
| 7 | tile=(z=2, x=3, y=3), px=(300, 300) | tile clampé à (3,3,2) |
| 8 | tile=(z=4, x=8, y=8), px=(-10, 300) | x=246, tile.x=7 / y=44, tile.y=9 |
| 9 | z=0 et z=15 bornes | clampé à 0 / 32767 |

**Tests neufs `toTileCoordinatesFloat` / `toTileCoordinates` / `toPixel`** :

| Cas | Entrée | Attendu |
|---|---|---|
| Validation lat hors bornes | lat=86 | `IllegalArgumentException` message `"Invalid latitude: 86.0. Must be between -85.0511 and 85.0511"` |
| Validation lat hors bornes négatif | lat=-86 | exception |
| Validation lon | lon=181 / lon=-181 | exception messages conformes |
| Validation zoom | z=-1 / z=16 / z=12.5 (n.a. en Kotlin Int) | exception (z=12.5 impossible en Kotlin → skipper) |
| `(lat=0, lon=0, z=0)` | — | tile.x=0, tile.y=0, xFloat=0.5, yFloat=0.5 |
| `(lat=0, lon=0, z=12)` | — | tile coordonnées du centre du monde |
| `(lat=85.0511, lon=180, z=0)` bornes valides | — | aucune exception |
| `toPixel(0, 0, 12, 256)` | — | pixel au milieu de la tuile centrale (128, 128) approximativement |
| `degToRad(180)` | — | `PI` ± 1e-12 |
| Cohérence : `toTileCoordinates == toTileCoordinatesFloat.{x, y, z}` | qq cas | propriété structurelle |

#### `TileTest.kt`

Tests neufs :

| Cas | Attendu |
|---|---|
| `decodeTerrariumElevation(128, 0, 0)` (sea level encoded) | `0.0` |
| `decodeTerrariumElevation(131, 232, 0)` (≈ 1000 m) | `1000.0` |
| `decodeTerrariumElevation(255, 255, 255)` (max) | `≈ 32767.996` |
| `decodeTerrariumElevation(0, 0, 0)` (min) | `-32768.0` |
| `decodeTerrariumElevation` retourne arrondi à 2 décimales | input crafted pour donner `123.4567` → `123.46` |
| `Tile(RawTile)` constructeur secondaire OK | wraps without copy |
| `Tile.getElevation(pixel)` retourne la valeur décodée | RawTile crafted à la main avec 1000 m au pixel (0,0) |
| `Tile.getElevation` cache (2e appel) | second call ne re-lit pas le buffer (test via byte mutation) |
| `Tile.getElevation` rejette `pixel.x < 0` | exception |
| `Tile.getElevation` rejette `pixel.x >= width` | exception message conforme `"Invalid x position: 256. Must be between 0 and 255"` |
| Idem `pixel.y` | exceptions |

Helper pour les tests :

```kotlin
private fun makeRawTileWithElevationAt(
    width: Int, height: Int, pixel: Pair<Int, Int>, elevation: Double,
): RawTile {
    val rgba = ByteArray(width * height * 4)
    // Fill with sea-level encoding (128, 0, 0, 255)
    for (i in 0 until width * height) {
        rgba[i * 4] = 128.toByte()
        rgba[i * 4 + 3] = 255.toByte()
    }
    // Encode target elevation at (pixel.first, pixel.second)
    val raw = (elevation + 32768.0).toInt()  // assume integer meters
    val r = raw shr 8 and 0xFF
    val g = raw and 0xFF
    val offset = (pixel.second * width + pixel.first) * 4
    rgba[offset] = r.toByte()
    rgba[offset + 1] = g.toByte()
    rgba[offset + 2] = 0
    rgba[offset + 3] = 255.toByte()
    return RawTile(width, height, rgba)
}
```

### 6. Vérification ktlint

Le `var x = ... ; if (...) { ... }` à plusieurs lignes peut nécessiter de l'éclatement. Lancer `./gradlew ktlintFormat` après écriture si besoin.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Tiles.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/RawTile.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationFunctions.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Tile.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/TilesTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/RawTileTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/ElevationFunctionsTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/TileTest.kt`

Modifié :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/Constants.kt` — ajout de `WEB_MERCATOR_MAX_LAT_TEST = 85.0511`

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :elevation:build
./gradlew :engine:allTests
```

Critères :

- **`TilesTest`** : ≥ 3 tests verts.
- **`RawTileTest`** : ≥ 4 tests verts.
- **`ElevationFunctionsTest`** : ≥ 9 tests `normalizePixel` (port) + ≥ 10 tests neufs (projections, validators) = **≥ 19** tests.
- **`TileTest`** : ≥ 10 tests.
- Cumul `:elevation` : ≥ 12 classes de test, ≥ 122 tests par target.
- `ktlintCheck` vert.
- `:engine:allTests` toujours vert.
- Messages d'exception `IllegalArgumentException` au caractère près :
  - `"Invalid latitude: <lat>. Must be between -85.0511 and 85.0511"`
  - `"Invalid longitude: <lon>. Must be between -180 and 180"`
  - `"Invalid zoom level: <z>. Must be between 0 and 15"`
  - `"Invalid x position: <x>. Must be between 0 and <width-1>"`
  - `"Invalid y position: <y>. Must be between 0 and <height-1>"`

## Done when

- [x] 4 fichiers source `commonMain` créés et compilent sur les 3 targets
- [x] 4 fichiers tests créés (≥ 36 tests cumulés)
- [x] `EarthConstants.WEB_MERCATOR_MAX_LAT_TEST` ajouté dans `Constants.kt`
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Messages d'exception conformes au TS (vérifiés `assertEquals` sur 5 patterns)
- [x] Décodage Terrarium : test sentinel sea-level (128, 0, 0) → 0.0 vert
- [x] Cache `Tile` : 2e appel sur même pixel ne re-lit pas `rgba` (test via mutation post-décodage)
- [x] Toutes les checkboxes cochées dans le fichier

## Notes

- **Re-scoping** : `ElevationCalculator` (interpolation bilinéaire) est explicitement **différé en tâche 08**. Justification : il nécessite un `TileSource` ou `TileManager` qui n'arrive qu'en tâche 07 ; introduire un test-double ad-hoc en tâche 05 ajouterait de la complexité sans bénéfice (le test-double devient inutile dès la tâche suivante). La tâche 08 absorbe naturellement l'interpolation aux côtés de `ElevationProvider`.
- **Constante de validation lat** : le TS utilise `85.0511` (5 décimales) comme borne, distincte de la borne Web Mercator géodésique `85.0511287798…`. Pour clarifier l'intention, on ajoute `WEB_MERCATOR_MAX_LAT_TEST` (la borne de validation lâche) à côté de `WEB_MERCATOR_MAX_LAT` (la borne géodésique précise). Si l'écart pose problème plus tard, on pourra unifier.
- **`(1 shl z)` vs `Math.pow(2, z)`** : `1 shl z` est un calcul entier exact et plus rapide. Valide pour `z ∈ [0, 15]` (≤ 32768 ≤ Int.MAX_VALUE).
- **`Int` vs `Double` pour le zoom** : le TS accepte `number` mais valide via `Number.isInteger`. Kotlin force `Int` à la compile, donc on supprime ce check de validation : impossible d'avoir un zoom non-entier. Test correspondant retiré.
- **`Tile` non-abstract** : différence structurelle avec le TS. Le TS sépare `Tile` (abstract) de `BrowserTile`/`NodejsTile` parce que `ImageData` est platform-specific. En Kotlin/KMP, on normalise très tôt en `ByteArray` (cf. `kotlin-wasm-jvm-webp.md`), donc `Tile` peut être concret.
- **Cache `DoubleArray` initialisé à `NaN`** : sentinel pour « pas encore décodé ». Vérification `!cached.isNaN()` au lieu de `Double?` (économie de boxing).
- **`(elevation + 32768).toInt()` dans le helper de test** : limite l'encodage de test aux altitudes entières (0, 100, 1000 m), ce qui simplifie la fabrique. Pour tester des altitudes fractionnaires, on utilisera `decodeTerrariumElevation(r, g, b)` directement.
- **Pas de `close()`** : aucune ressource native côté Kotlin (le `ByteArray` est géré par le GC). Si une cible Wasm a besoin de libérer un `ImageBitmap`, ce sera fait dans la couche fetch (tâche 06), pas ici.
- **`toTileCoordinatesFloat` et erreurs lat/lon** : on lève systématiquement `IllegalArgumentException` (via `require`), équivalent du `throw new Error` TS. Messages identiques au caractère près.
- **Test du cache via mutation** : après un premier `getElevation` qui peuple le cache, on mute le `ByteArray` interne (accessible via reflection seulement — alternative : exposer un constructeur de test ou un `internal` helper). Plus simple : on construit le RawTile avec une valeur A, on lit, on construit un autre RawTile B avec la même réf de bytes mais modifié, on lit le pixel — si le cache fonctionne, on récupère A. **Mais** `Tile` prend une copie de référence, donc la mutation externe est observable. Solution : tester deux appels successifs identiques et vérifier que la valeur est identique (faible signal de cache). Solution forte : ajouter un compteur d'accès interne `internal val decodeCount: Int` accessible aux tests. Décision : compteur `internal` pour avoir un vrai test de cache.
