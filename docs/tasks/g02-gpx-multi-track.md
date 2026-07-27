# g02 — Multi-track / multi-segment de bout en bout

## Goal

`GpxDocument` expose déjà `List<GpxTrack>`, mais **tout l'aval suppose le premier track** :
`firstTrackAsPath()`, `EngineJsApi.parseGpx`, `EngineCli`, `Enhancer`. Un GPX à plusieurs
tracks (ou à plusieurs `<trkseg>` dans un track) perd silencieusement des données.

gpx2web gère nativement `GPX` → `List<GPXPath>` avec un `GPXPathType`. Porter cette
sémantique.

## Depends on

- `g01` (module `:gpx`)

## Inputs

- `gpx/src/commonMain/…/gpx/{Gpx,GpxParser,GpxToPath,GpxFromPath,GpxWriter}.kt`
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/data/{GPX,GPXPath,GPXPathType}.java`
- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt`

## Steps

### 1. Segments dans le modèle

`GpxTrack` ne connaît que `points: List<GpxTrackPoint>` : les `<trkseg>` sont aujourd'hui
aplatis. Deux options :

- **(a)** `GpxTrack.segments: List<GpxSegment>`, `GpxSegment.points: List<GpxTrackPoint>`.
  Fidèle au format, mais casse `GpxTrack.points`.
- **(b)** Garder `points` aplati et ajouter `segmentBoundaries: List<Int>` (index de début de
  chaque segment).

Retenir **(a)** avec une propriété de compat `GpxTrack.points get() = segments.flatMap { it.points }`.
Un saut de segment est une discontinuité physique (pause, téléport) : l'aplatir sans le
signaler produit un segment de distance aberrante dans `Path.computeDerivedData`.

### 2. Conversion vers `Path`

Dans `GpxToPath.kt` :

```kotlin
/** Un Path par track. Les segments d'un même track sont concaténés. */
fun GpxDocument.tracksAsPaths(): List<Path>

/** Un Path par segment, tous tracks confondus. */
fun GpxDocument.segmentsAsPaths(): List<Path>

/** Conservé pour compat : premier track, segments concaténés. */
fun GpxDocument.firstTrackAsPath(): Path
```

`firstTrackAsPath()` n'est **pas** dépréciée : elle reste le raccourci légitime du cas courant.

### 3. Écriture

`GpxFromPath.kt` / `GpxWriter.kt` : accepter `List<Path>` et écrire un `<trk>` par `Path`,
avec les métadonnées (nom de track) portées par un paramètre.

```kotlin
fun pathsToGpxDocument(paths: List<Path>, name: String, trackNames: List<String>? = null): GpxDocument
```

### 4. Façade JS/Wasm

Ajouter **sans retirer** l'existant :

```kotlin
@JsExport fun parseGpx(xml: String): Path                    // inchangé — premier track
@JsExport fun parseGpxTracks(xml: String): Array<Path>       // nouveau
@JsExport fun parseGpxSegments(xml: String): Array<Path>     // nouveau
@JsExport fun writeGpxTracks(paths: Array<Path>): String     // nouveau
```

C'est le point de rupture potentiel signalé dans le plan : en gardant `parseGpx` intacte, la
démo continue de fonctionner sans modification.

### 5. `Enhancer`

Ajouter `enhanceCourses(paths: List<Path>, …): List<Path>` qui applique le pipeline à chaque
`Path` indépendamment. Ne **pas** paralléliser : le `ElevationProvider` a un cache partagé et
la contention n'a pas été mesurée.

### 6. CLI

`EngineCli` : traiter tous les tracks au lieu du premier, et le refléter dans la sortie
console (`-> 3 tracks, 1234 points au total`).

## Outputs

Modifiés :

- `gpx/src/commonMain/…/gpx/{Gpx,GpxParser,GpxToPath,GpxFromPath,GpxWriter}.kt`
- `engine/src/commonMain/…/Enhancer.kt`
- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt`
- `engine/src/jvmMain/…/EngineCli.kt`

Créés :

- Fixtures multi-track dans `gpx/src/commonTest/…/gpx/GpxFixtures.kt`
- Tests dans `GpxParserTest`, `GpxWriterTest`, `EnhancerTest`

## Validation

```bash
./gradlew :gpx:allTests :engine:allTests
./gradlew ktlintCheck
./gradlew :engine:run -Pargs="enhance <multi-track.gpx> -o /tmp/out.gpx"
```

Cas de test (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | GPX 1 track / 1 segment | `tracksAsPaths().size == 1` |
| 2 | GPX 2 tracks | `tracksAsPaths().size == 2`, points corrects par track |
| 3 | GPX 1 track / 3 segments | `tracksAsPaths().size == 1`, `segmentsAsPaths().size == 3` |
| 4 | `firstTrackAsPath()` sur (2) | identique au comportement pré-g02 |
| 5 | `<trkseg>` vide | segment ignoré, pas de `Path(0)` parasite |
| 6 | Track sans point | track ignoré |
| 7 | Round-trip 2 tracks : parse → write → parse | même nombre de tracks et de points |
| 8 | Round-trip préserve les noms de track | égalité |
| 9 | `enhanceCourses` sur 2 tracks | 2 Paths, chacun virtualisé |
| 10 | Segments concaténés : la distance totale n'inclut pas le saut inter-segment | assertion numérique |

Le cas 10 est le vrai piège : décider explicitement si la distance saute d'un segment à
l'autre. **Décision : oui, elle saute** (concaténation naïve, comme aujourd'hui), mais
`segmentsAsPaths()` existe pour qui veut éviter l'artefact. Documenter dans le KDoc.

## Done when

- [ ] `GpxSegment` introduit, `GpxTrack.points` conservé en compat
- [ ] `tracksAsPaths` / `segmentsAsPaths` / `firstTrackAsPath` implémentées
- [ ] Écriture multi-track
- [ ] Façade JS étendue sans rupture, `.d.ts` régénérés
- [ ] `Enhancer.enhanceCourses`
- [ ] `EngineCli` traite tous les tracks
- [ ] ≥ 10 tests verts × 4 cibles
- [ ] `ktlintCheck` vert, démo toujours fonctionnelle

## Notes

- **Ne pas paralléliser `enhanceCourses`** : `ElevationProvider` a un `LruCache` dont la
  thread-safety en JVM n'a pas été auditée, et les cibles JS/Wasm sont mono-thread de toute
  façon.
- **`<rte>` (routes) reste non supporté.** gpx2web ne les gère pas non plus. Si le besoin
  émerge, c'est une tâche à part.
- **Distance inter-segment** : gpx2web produit un `GPXPath` par `<trkseg>` (donc pas
  d'artefact). Notre défaut diffère volontairement pour préserver la compat de
  `firstTrackAsPath()`. À signaler dans la matrice de correspondance (g20).
