# 14 — Engine : GPX parser (XML → `Path`)

## Goal

Porter le **parser GPX** en KMP-pur. Trois livrables :

1. **Modèles de données GPX** intermédiaires (`GpxDocument`, `GpxTrack`, `GpxTrackPoint`) — séparés de `Path` pour préserver les champs *bruts* tels que présents dans le fichier (lat/lon en degrés, time en `Instant`/Long, extensions optionnelles), avant tout calcul de stats ou conversion en radians.
2. **`GpxParser`** : lit une chaîne XML GPX et produit un `GpxDocument`. Multi-namespace (Garmin v3 GpxExtensions, gpxtpx TrackPointExtension v1, Cluetrust gpxdata, Amazfit/Movescount, génériques). Extensions reconnues : **power, cadence, heart rate, temperature**.
3. **Pont vers `Path`** : extension `GpxDocument.firstTrackAsPath(): Path` qui matérialise un `Path` (taille fixe = nb de trackpoints) + appelle `computeDerivedData()`.

Stratégie XML : **`xmlutil`** (https://github.com/pdvrieze/xmlutil) — bibliothèque KMP qui supporte JVM, JS, Wasm-Js. Pas de dépendance JVM-only autorisée.

## Depends on

- `10` (`PointField`), `11` (`GeneratedPath`), `12` (`Path`) — pour pouvoir matérialiser un `Path` à partir d'un `GpxDocument`.
- `:elevation.MathConstants.DEG_TO_RAD` (conversion lat/lon).

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/gpx/GPXParser.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/gpx/ExtensionParser.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/gpx/NamespaceResolver.ts`
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/gpx/types.ts` (constantes `KNOWN_NAMESPACES`, `EXTENSION_FIELD_MAPPINGS`)
- Fixtures GPX réelles (à copier dans `engine/src/commonTest/resources/`) :
  - `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/gpx/sample.gpx` — petit, custom `<power>` non-namespacé
  - `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/gpx/garmin.gpx` — Garmin TPX (hr, cad, atemp)
  - `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/gpx/amazfit.gpx` — Amazfit (heartrate)
  - `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/gpx/movescount.gpx` — Cluetrust (cadence, temp)

## Steps

### 1. Dépendance `xmlutil` dans le build

`gradle/libs.versions.toml` :

```toml
[versions]
xmlutil = "0.91.0"   # vérifier la dernière stable supportant wasmJs (≥ 0.90.x)

[libraries]
xmlutil-core      = { module = "io.github.pdvrieze.xmlutil:core", version.ref = "xmlutil" }
xmlutil-serialization = { module = "io.github.pdvrieze.xmlutil:serialization", version.ref = "xmlutil" }
```

`engine/build.gradle.kts` — ajouter dans `commonMain.dependencies` :

```kotlin
implementation(libs.xmlutil.core)
implementation(libs.xmlutil.serialization)
```

Vérification : `./gradlew :engine:build` télécharge la dépendance et compile.

**Si xmlutil ne supporte pas une target** (e.g. wasmJs sur une vieille version) : escalade vers un parser hand-rolled simple. À ce stade, supposer xmlutil 0.91+ couvre les 3 cibles ; si BUILD FAILED après ajout, c'est le moment de migrer (note à la fin).

### 2. Modèles `Gpx.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/Gpx.kt` :

```kotlin
package io.github.glandais.engine.gpx

/**
 * Raw GPX document, post-parse, pre-conversion. Preserves the file's units verbatim
 * (lat/lon in **degrees**, time in **epoch milliseconds**, optional fields for everything
 * that's not always present).
 */
data class GpxDocument(
    val name: String = "noname",
    val tracks: List<GpxTrack>,
)

data class GpxTrack(
    val name: String? = null,
    val type: String? = null,
    val points: List<GpxTrackPoint>,
)

/**
 * A single `<trkpt>` entry. Required : [latitudeDeg], [longitudeDeg]. Everything else may be
 * absent depending on the source device.
 */
data class GpxTrackPoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double? = null,
    /** Epoch milliseconds. Null if no `<time>` tag. */
    val timeEpochMs: Long? = null,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    /** Ambient temperature in Celsius. */
    val temperatureC: Double? = null,
    /** Power in watts. */
    val powerW: Double? = null,
)
```

### 3. `GpxParser.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxParser.kt`.

Approche : pas de désérialisation `@Serializable` directe (les extensions hétérogènes rendent un mapping statique trop fragile). On parse le DOM avec `nl.adaptivity.xmlutil.dom2` ou l'API DOM Java-compat de xmlutil, puis on parcourt manuellement.

Squelette (compléter selon l'API exacte d'xmlutil 0.91) :

```kotlin
package io.github.glandais.engine.gpx

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming
import nl.adaptivity.xmlutil.newReader

object GpxParser {

    /** Parse a full GPX XML string. Throws [IllegalArgumentException] on malformed input. */
    fun parse(xml: String): GpxDocument {
        val reader = xmlStreaming.newReader(xml)
        return parseDocument(reader)
    }

    private fun parseDocument(reader: XmlReader): GpxDocument {
        var name = "noname"
        val tracks = mutableListOf<GpxTrack>()
        // Navigate to <gpx> root, then iterate <metadata>/<name>, <trk> children.
        // Use reader.next() loop with EventType.START_ELEMENT / END_ELEMENT events.
        // Recognise local names ignoring prefix : reader.localName.
        ...
        return GpxDocument(name = name, tracks = tracks)
    }

    private fun parseTrack(reader: XmlReader): GpxTrack { ... }

    private fun parseTrackSegment(reader: XmlReader): List<GpxTrackPoint> { ... }

    private fun parseTrackPoint(reader: XmlReader): GpxTrackPoint {
        // Read lat/lon from attributes (required).
        // Iterate child elements : <ele>, <time>, <extensions>.
        // Within <extensions>, scan for any of the known leaf names :
        //   - power, hr, cad, cadence, heartrate, heartRate, atemp, temperature, temp
        // Match by local name only (ignore namespace prefix), accept multi-source.
        // For Garmin nested <TrackPointExtension>, recurse one level deeper.
        ...
    }
}
```

**Reconnaissance des extensions par nom local** (un seul `Map<String, (String) -> Unit>` interne) :

| Local name (case-insensitive)            | Cible       | Conversion |
|---|---|---|
| `power`                                  | `powerW`    | parseDouble |
| `hr`, `heartrate`, `heartRate`           | `heartRate` | parseDouble.roundToInt |
| `cad`, `cadence`                         | `cadence`   | parseDouble.roundToInt |
| `atemp`, `temperature`, `temp`           | `temperatureC` | parseDouble |
| `TrackPointExtension`                    | recurse     | descendre dans les enfants |

Pas besoin de gestion fine des namespaces — la stratégie « local name only » couvre 100% des fixtures réelles et matche ce que fait `ExtensionParser.ts` en pratique.

**Parsing du time** : `<time>2024-11-03T14:25:22.000Z</time>`. Utiliser `kotlinx-datetime` (déjà dispo via la stdlib ? sinon ajouter `kotlinx-datetime:0.6.x` à `commonMain`) pour parser un `Instant` puis `.toEpochMilliseconds()`. Si parse fail : laisser `timeEpochMs = null` (la TS swallow l'exception).

### 4. Dépendance `kotlinx-datetime`

Ajouter au catalogue :

```toml
[versions]
kotlinx-datetime = "0.6.1"

[libraries]
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinx-datetime" }
```

`engine/build.gradle.kts` :

```kotlin
implementation(libs.kotlinx.datetime)
```

### 5. Pont vers `Path` : `GpxToPath.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxToPath.kt` :

```kotlin
package io.github.glandais.engine.gpx

import io.github.glandais.elevation.MathConstants
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField

/** Convert the first track of [this] document to a [Path], computing derived data. */
fun GpxDocument.firstTrackAsPath(): Path {
    val track = tracks.firstOrNull() ?: error("GpxDocument has no track")
    return track.toPath()
}

fun GpxTrack.toPath(): Path {
    val n = points.size
    val path = Path(n)
    for ((i, p) in points.withIndex()) {
        path.setLatitude(i, p.latitudeDeg * MathConstants.DEG_TO_RAD)
        path.setLongitude(i, p.longitudeDeg * MathConstants.DEG_TO_RAD)
        path.setElevation(i, p.elevationM ?: 0.0)
        path.setTime(i, (p.timeEpochMs ?: 0L).toDouble())
        p.powerW?.let { path.setPInputPower(i, it) }
        p.heartRate?.let { path.setHeartRate(i, it.toDouble()) }
        p.cadence?.let { path.setCadence(i, it.toDouble()) }
        p.temperatureC?.let { path.setTemperature(i, it) }
    }
    path.computeDerivedData()
    return path
}
```

### 6. Fixtures de test

Copier (au commit) les 4 GPX dans `engine/src/commonTest/resources/` :
- `sample.gpx` (basique, custom `<power>`)
- `garmin.gpx` (Garmin TPX)
- `amazfit.gpx` (Amazfit `<heartrate>`)
- `movescount.gpx` (Cluetrust)

**Problème KMP** : les ressources `commonTest/resources/` ne sont **pas** universellement accessibles (JVM via ClassLoader, JS/Wasm via fetch ou bundling). Solution pragmatique :

1. Copier les fichiers en `commonTest/resources/` (référence visible humainement).
2. Créer `GpxFixtures.kt` dans `commonTest/kotlin/` qui contient le **contenu inline** des fixtures comme `val SAMPLE_GPX_XML: String = "<?xml..."` (multi-line raw strings).
3. Les tests utilisent `GpxFixtures.SAMPLE_GPX_XML` — fonctionne sur les 3 targets sans config de bundling.

Si les fichiers `.gpx` sont gros (> 100 lignes), tronquer dans `GpxFixtures.kt` pour ne garder qu'une dizaine de trackpoints représentatifs.

### 7. Tests `GpxParserTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/gpx/GpxParserTest.kt`. Cas à couvrir :

| # | Cas | Attendu |
|---|---|---|
| 1 | Parse XML invalide | `IllegalArgumentException` |
| 2 | Parse `<gpx>` vide (pas de track) | `tracks.isEmpty()` |
| 3 | Parse `<gpx><trk><trkseg/></trk></gpx>` | 1 track, 0 point |
| 4 | Parse `<trkpt>` sans lat/lon | exception |
| 5 | Parse `<trkpt lat="X" lon="Y">` lat/lon invalides | exception |
| 6 | Parse minimal valide (lat/lon seuls) | `GpxTrackPoint(lat, lon, ele=null, time=null, ...)` |
| 7 | Parse avec `<ele>` | `elevationM == valeur` |
| 8 | Parse avec `<time>` ISO 8601 | `timeEpochMs == correct epoch` |
| 9 | Parse `<time>` invalide | `timeEpochMs == null` (swallow) |
| 10 | sample.gpx : ≥ 6 trackpoints, premier a `powerW == 45.0` | parse + extension |
| 11 | sample.gpx : `name == "L'étape du Tour 2025"` | metadata trk |
| 12 | garmin.gpx : `heartRate`, `cadence`, `temperatureC` peuplés | Garmin TPX |
| 13 | amazfit.gpx : `heartRate` peuplé | Amazfit format |
| 14 | movescount.gpx : `cadence` peuplé via `gpxdata:cadence` | Cluetrust |
| 15 | `GpxDocument.firstTrackAsPath()` produit `Path` de la bonne taille | bridge |
| 16 | `Path` issu de sample.gpx : `path.elevation(0) == 350.1` | bridge |
| 17 | `Path.totalDistance > 0` après `firstTrackAsPath` | computeDerivedData appelé |
| 18 | Multi-track document : seul le premier track est consommé par `firstTrackAsPath` | clarification |

### 8. Vérification ktlint

`./gradlew :engine:ktlintFormat` si nécessaire après ajout des fichiers.

## Outputs (fichiers attendus)

Créés (commonMain) :

- `engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/Gpx.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxParser.kt`
- `engine/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxToPath.kt`

Tests (commonTest) :

- `engine/src/commonTest/kotlin/io/github/glandais/engine/gpx/GpxFixtures.kt`
- `engine/src/commonTest/kotlin/io/github/glandais/engine/gpx/GpxParserTest.kt`

Fixtures (référence humaine) :

- `engine/src/commonTest/resources/sample.gpx` (+ garmin, amazfit, movescount)

Modifiés :

- `gradle/libs.versions.toml` (ajout `xmlutil` + `kotlinx-datetime`)
- `engine/build.gradle.kts` (deps `commonMain`)

## Validation

```bash
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:build
```

Critères :

- ≥ 18 tests verts dans `GpxParserTest` sur JVM/JS/Wasm.
- `xmlutil` compile sur les 3 cibles.
- Parsing de **4 fixtures réelles** (sample, garmin, amazfit, movescount) sans crash.
- Pont vers `Path` : `totalDistance > 0` et premier point d'élévation cohérent avec le fichier.
- `:elevation:allTests` toujours vert.

## Done when

- [x] Dépendances `xmlutil` + `kotlinx-datetime` ajoutées au catalogue + build
- [x] 3 sources `commonMain` créés (`Gpx.kt`, `GpxParser.kt`, `GpxToPath.kt`)
- [x] `GpxFixtures.kt` inline avec ≥ 4 fixtures (sample, garmin, amazfit, movescount)
- [x] `GpxParserTest.kt` ≥ 18 tests verts sur les 3 targets
- [x] Fichiers fixtures `.gpx` recopiés dans `commonTest/resources/` pour référence
- [x] `:engine:allTests` vert, `:elevation:allTests` toujours vert, `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **xmlutil API** : les noms exacts (`xmlStreaming`, `newReader`, `XmlReader`) peuvent varier d'une version à l'autre — vérifier la dernière stable au moment de l'implémentation. Si l'API DOM (`Document`/`Element`) est plus simple à utiliser que le streaming reader, basculer dessus. L'important est la **sémantique** (lecture XML namespace-aware côté JVM/JS/Wasm).
- **Local-name matching** : on ignore les namespaces. Solide en pratique car les vrais fichiers GPX réutilisent les mêmes local names entre dialectes. Si une collision survient (e.g. deux champs `temperature` différents), on prendra le premier — comportement déterministe.
- **`<time>` parsing** : `Instant.parse("2024-11-03T14:25:22.000Z")` — `kotlinx-datetime.Instant` parse ISO-8601 nativement.
- **`Path.setTime(i, epochMs.toDouble())`** : on stocke un `Double` qui représente un `Long` epoch ms. Précision exacte pour 10^15 ms (> 30 000 ans après 1970). Pas de perte.
- **`computeDerivedData()` appelé après population** : fait que `path.totalDistance` etc. sont prêts à l'usage.
- **Fixtures inline** : la duplication file vs string est délibérée — les `.gpx` checkés en git restent lisibles (`git diff`), les `String` en Kotlin servent les tests cross-target. Si le coût de maintenance gêne, on pourra plus tard générer le `.kt` depuis les `.gpx` via une tâche Gradle (mais le bénéfice n'est pas évident).
- **Cas `time invalide`** : swallow comme le TS (`try-catch` puis `null`). Pas de crash sur fichier mal formé.
- **`querySelector("name")` du TS** : retourne le **premier** `<name>` en profondeur — attention à ne pas confondre `<metadata><name>` et `<trk><name>` quand on parse le track. Le TS limite explicitement à `trackElement.querySelector("name")` (descendant du `<trk>`). Côté Kotlin, on traverse manuellement.
- **`firstTrackAsPath` plutôt qu'un `Paths` global** : on garde la sémantique TS « `Paths { tracks: Path[] }` » via `GpxDocument.tracks: List<GpxTrack>`. Le helper `firstTrackAsPath` correspond à l'usage courant (1 track par fichier). Pour multi-track, l'appelant fait `doc.tracks.map { it.toPath() }`.
- **Pas de `NamespaceResolver` séparé** : avec la stratégie « local-name », il devient superflu. Simplification.
- **Si xmlutil casse Wasm** : alternative simple — écrire un mini-pull-parser hand-rolled (~150 lignes) qui ne gère que le subset GPX requis. Pas idéal, mais réalisable. Documenter la décision dans une note de cette tâche si déclenchée.
- **Préparation tâche 15 (writer)** : `GpxDocument` réutilisable pour serialisation symétrique. Il faudra peut-être ajouter `@Serializable` ou un `GpxWriter` manuel.
