# g03 — Waypoints `<wpt>`

## Goal

vcyclist ignore les `<wpt>` : ils sont perdus silencieusement au parsing et absents à
l'écriture. gpx2web les modélise (`GPXWaypoint`) et les préserve. Porter le parsing, la
conservation à travers le pipeline et la réécriture.

Les waypoints portent des points d'intérêt (ravitaillement, sommet, départ) qu'un cycliste
tient à retrouver dans le GPX enrichi.

## Depends on

- `g01` (module `:gpx`)
- `g02` (structure du document consolidée)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/data/GPXWaypoint.java`
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/read/GPXFileReader.java`
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/write/GPXFileWriter.java`
- `gpx/src/commonMain/…/gpx/{Gpx,GpxParser,GpxWriter}.kt`

## Steps

### 1. Modèle

```kotlin
/**
 * Un `<wpt>` GPX. Point d'intérêt indépendant de la trace : il n'entre pas dans le [Path]
 * et n'est pas affecté par le rééchantillonnage.
 */
data class GpxWaypoint(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val elevationM: Double? = null,
    val name: String? = null,
    val description: String? = null,
    /** Valeur de `<sym>` — icône suggérée par l'appareil. */
    val symbol: String? = null,
    /** Valeur de `<type>`. */
    val type: String? = null,
    /** Époque en millisecondes, `null` si `<time>` absent ou illisible. */
    val timeEpochMs: Long? = null,
)
```

`GpxDocument` gagne `val waypoints: List<GpxWaypoint> = emptyList()`.

### 2. Parsing

Dans `GpxParser.parseGpxRoot`, traiter `<wpt>` au même niveau que `<trk>` et `<metadata>`.
Même stratégie que l'existant : **matching sur le nom local**, préfixes de namespace ignorés.

`lat`/`lon` obligatoires (mêmes erreurs que `<trkpt>`) ; tout le reste optionnel.

### 3. Écriture

`GpxWriter` : écrire les `<wpt>` **avant** les `<trk>`, comme l'impose le schéma GPX 1.1
(ordre `metadata`, `wpt*`, `rte*`, `trk*`). Un writer qui les place après produit un fichier
que certains parsers stricts rejettent — c'est le piège principal de cette tâche.

### 4. Traversée du pipeline

Les waypoints ne sont pas des points de trace : ils ne passent **pas** par `Path`. Ils sont
portés par `GpxDocument` en entrée et doivent être réinjectés en sortie.

`GpxFromPath.pathsToGpxDocument(…)` gagne un paramètre :

```kotlin
fun pathsToGpxDocument(
    paths: List<Path>,
    name: String,
    trackNames: List<String>? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
): GpxDocument
```

Et `EngineCli` / la façade JS repassent `doc.waypoints` du document source vers le document
de sortie.

⚠ **Ne pas** recaler l'élévation des waypoints via `fixElevation` : leur altitude est souvent
une saisie manuelle intentionnelle. Documenter ce choix.

### 5. Façade JS

```kotlin
@JsExport fun parseGpxWaypoints(xml: String): Array<WaypointDto>
```

DTO plat (`external interface` en JS, `@JsFun` builder en Wasm), cf.
`docs/kotlin-wasm-jvm-webp.md` §4.

## Outputs

Créés :

- `gpx/src/commonMain/…/gpx/GpxWaypoint.kt` (ou ajout dans `Gpx.kt`)
- Tests dans `GpxParserTest`, `GpxWriterTest`

Modifiés :

- `GpxParser.kt`, `GpxWriter.kt`, `GpxFromPath.kt`, `Gpx.kt`
- `EngineJsApi.kt` (js + wasmJs), `EngineCli.kt`
- `GpxFixtures.kt` (fixture avec waypoints)

## Validation

```bash
./gradlew :gpx:allTests :engine:allTests
./gradlew ktlintCheck
```

Cas de test (≥ 8) :

| # | Cas | Attendu |
|---|---|---|
| 1 | GPX avec 3 `<wpt>` | `doc.waypoints.size == 3` |
| 2 | `<wpt>` sans `lat` | `IllegalArgumentException` |
| 3 | `<wpt>` minimal (lat/lon seuls) | champs optionnels à `null` |
| 4 | `<wpt>` complet (ele, name, desc, sym, type, time) | tous les champs peuplés |
| 5 | Namespace préfixé (`gpx:wpt`) | parsé pareil |
| 6 | Round-trip parse → write → parse | waypoints identiques |
| 7 | Ordre d'écriture | `<wpt>` avant `<trk>` dans la sortie |
| 8 | Pipeline complet : GPX avec wpt → enhance → write | waypoints préservés à l'identique |

## Done when

- [x] `GpxWaypoint` modélisé, `GpxDocument.waypoints` ajouté
- [x] Parsing avec matching sur nom local
- [x] Écriture dans l'ordre imposé par le schéma GPX 1.1
- [x] Préservation à travers `enhance` (CLI + façade JS)
- [x] `parseGpxWaypoints` exporté, `.d.ts` régénérés
- [x] ≥ 8 tests verts × 4 cibles
- [x] `ktlintCheck` vert

## Résultat

**Modèle.** `GpxWaypoint` ajouté dans `Gpx.kt` (pas un fichier séparé — cohérent avec la taille
de `GpxTrackPoint`, déjà dans le même fichier). `GpxDocument` gagne `waypoints: List<GpxWaypoint>
= emptyList()` en dernier paramètre : ajout purement additif, aucun site d'appel positionnel
existant (`GpxDocument(name = …, tracks = …)`) n'est cassé.

**Parsing.** `parseGpxRoot` traite `wpt` au même niveau que `metadata` / `trk`, matching sur
`localName` comme le reste du parseur. `parseWaypoint` est la jumelle de `parseTrackPoint` en
plus simple : mêmes erreurs `lat`/`lon` obligatoires, mais **pas** d'`ExtensionsAccumulator` — le
spec dit explicitement que les extensions de waypoint ne sont pas portées (gpx2web ne fait pas
mieux). `name`/`desc`/`sym`/`type` sont lus comme `<trk><name>` (trim + vide → `null`).

**Écriture.** `writeWaypoint` émet les `<wpt>` avant la boucle `for (track in document.tracks)`,
dans l'ordre `ele, time, name, desc, sym, type` (ordre du schéma GPX 1.1 `wptType`). Un commentaire
rappelle explicitement le piège `metadata, wpt*, rte*, trk*` cité dans le spec — `<rte>` reste
non supporté (g02).

**Pas de `fixElevation` sur les waypoints — respecté par construction.** Les waypoints ne
traversent jamais `Path` (ni `GpxToPath`, ni `Enhancer`) : ils voyagent uniquement via
`GpxDocument.waypoints` / le nouveau paramètre `waypoints` de `pathsToGpxDocument` et
`GpxWriter.write(paths, …)`. Il n'y a donc littéralement aucun point d'accroche pour
`fixElevation` — le choix documenté au niveau KDoc de `GpxWaypoint` est une garantie structurelle,
pas une case à cocher dans le pipeline.

**`pathsToGpxDocument` / `GpxWriter.write(paths, …)`.** Nouveau paramètre `waypoints: List<GpxWaypoint>
= emptyList()`, placé avant `type` (qui a lui-même un défaut) : tous les appelants existants
utilisent des arguments nommés, donc zéro rupture. `EngineCli.runEnhance` passe `doc.waypoints`
(le document source, avant enhance) vers l'appel `pathsToGpxDocument(results, …, waypoints =
doc.waypoints)` — c'est le point exact où le spec demandait la ré-injection.

**Façade JS/Wasm : `WaypointDto` + `parseGpxWaypoints`, et `writeGpxTracks` étendu.** Suit le
patron `PointDto` existant (`external interface` + builder `js("({})")` côté JS,
`external interface : JsAny` + `@JsFun` côté Wasm — doc `kotlin-wasm-jvm-webp.md` §4 approche B).
Décision qui dépasse le minimum du spec : `writeGpxTracks` gagne aussi un paramètre `waypoints`
pour que le round-trip complet (`parseGpxTracks` → enhance par `Path` → `writeGpxTracks`) puisse
réinjecter les points d'intérêt côté JS/Wasm exactement comme le fait `EngineCli` côté JVM,
sinon la préservation resterait un point mort de la façade.

- Côté Kotlin/JS, `waypoints: Array<WaypointDto> = emptyArray()` a un défaut : l'appel existant
  dans `EngineJsApiTest` (jsTest) continue de compiler tel quel, sans modification.
- Côté Kotlin/Wasm, `waypoints: JsArray<WaypointDto>` **n'a pas** de défaut — les valeurs par
  défaut sur les fonctions `@JsExport` top-level ne sont pas fiables dans ce compilateur, et le
  reste de la façade Wasm n'en utilise déjà nulle part (`enhance(handle, options: …?)` est
  toujours à deux arguments obligatoires). C'est un **changement de signature cassant** pour
  `writeGpxTracks` côté Wasm ; le seul appelant du dépôt (`EngineJsApiTest.wasmJsTest`, cas
  « writeGpxTracks round-trips… ») a été mis à jour pour passer `JsArray()`. Asymétrie
  documentée ici plutôt que dans le code, dans l'esprit du reste du fichier
  `kotlin-wasm-jvm-webp.md`.

**Tests.** 24 cas dans `GpxParserTest` (+6 : cases 19-24), 24 cas dans `GpxWriterTest` (+4 :
cases 21-24), 1 cas CLI JVM (`EngineCliSmokeTest` case 3b, round-trip complet
parse→enhance→write sur `GpxFixtures.WAYPOINTS_GPX`, comparaison stricte
`source.waypoints == reparsed.waypoints`), 2 cas ajoutés dans chacun des tests de façade
`jsTest`/`wasmJsTest`. `GpxFixtures.WAYPOINTS_GPX` (nouvelle fixture) porte 3 `<wpt>` : un
minimal, un complet (`ele`, `time`, `name`, `desc`, `sym`, `type`), un minimal — plus un `<trk>`
à 3 points pour vérifier que les waypoints ne fuient pas dans `Path` (case 24 du parseur).
Total JVM du dépôt (`gpx` + `engine`) : 357 tests, contre 346 avant g03 (+11, cohérent avec les
6+4+1 cas ajoutés ci-dessus).

**Validation :** `./gradlew :gpx:allTests :engine:allTests` vert sur les 4 cibles (JVM, JS Node,
JS Browser/Karma, Wasm Browser/Karma) ; `./gradlew ktlintCheck` vert après `ktlintFormat`.

## Notes

- **Pas de `fixElevation` sur les waypoints** — décision explicite, à documenter en KDoc et
  reprendre dans la matrice g20.
- **`<rte>` toujours non supporté** (cf. g02).
- **Extensions de waypoint** ignorées : on ne porte que les champs GPX 1.1 standards. gpx2web
  ne fait pas mieux.
- Le round-trip (cas 6) est le test qui compte : c'est lui qui garantit qu'un utilisateur ne
  perd rien en passant sa trace dans vcyclist.
