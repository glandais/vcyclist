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

- [ ] `GpxWaypoint` modélisé, `GpxDocument.waypoints` ajouté
- [ ] Parsing avec matching sur nom local
- [ ] Écriture dans l'ordre imposé par le schéma GPX 1.1
- [ ] Préservation à travers `enhance` (CLI + façade JS)
- [ ] `parseGpxWaypoints` exporté, `.d.ts` régénérés
- [ ] ≥ 8 tests verts × 4 cibles
- [ ] `ktlintCheck` vert

## Notes

- **Pas de `fixElevation` sur les waypoints** — décision explicite, à documenter en KDoc et
  reprendre dans la matrice g20.
- **`<rte>` toujours non supporté** (cf. g02).
- **Extensions de waypoint** ignorées : on ne porte que les champs GPX 1.1 standards. gpx2web
  ne fait pas mieux.
- Le round-trip (cas 6) est le test qui compte : c'est lui qui garantit qu'un utilisateur ne
  perd rien en passant sa trace dans vcyclist.
