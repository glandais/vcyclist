# g07 — Writer JSON

## Goal

Porter `JsonFileWriter` (93 l.) de gpx2web : sérialiser un `Path` en JSON, format directement
consommable par un graphique ou un traitement aval.

C'est le format qu'utilise la webapp gpx2web pour alimenter Chart.js (`VirtualizationResponse.jsonData`).

## Depends on

- `g01` (module `:gpx`)
- `g06` (formatage numérique multiplateforme — à réutiliser, pas à réécrire)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/write/JsonFileWriter.java` (canonique)
- `gpx/src/commonMain/…/io/CsvWriter.kt` (formatage des `Double`)
- `gpx/src/commonMain/…/path/PointField.kt`

## Steps

### 1. Choisir la forme du document

Lire d'abord ce que produit `JsonFileWriter` côté Java, puis décider. Deux formes possibles :

**(a) Orientée lignes** — un objet par point :

```json
{"fields":["distance","elevation","speed"],
 "points":[{"distance":0.0,"elevation":120.4,"speed":0.0}, …]}
```

**(b) Orientée colonnes** — un tableau par champ :

```json
{"size":1018,
 "fields":{"distance":[0.0, 12.4, …],"elevation":[120.4, …]}}
```

Retenir **(b)**. Un graphique consomme des séries, pas des points ; la forme colonnes évite de
répéter 36 noms de clés par point (facteur ~3 sur la taille) et se branche directement sur
Chart.js. Si la lecture du Java montre que gpx2web produit (a), le noter dans les Notes et
dans la matrice g20 — c'est une divergence assumée.

Inclure un bloc de métadonnées : `size`, `totalDistance`, `durationMs`, `elevationGain`,
`elevationLoss`, plus l'unité de chaque champ.

### 2. API

```kotlin
/**
 * Sérialisation JSON d'un [Path], orientée colonnes (une série par champ).
 *
 * Inspiré de `io.github.glandais.gpx.io.write.JsonFileWriter` (gpx2web) ; la forme du
 * document diffère — voir la fiche g07.
 */
object JsonWriter {
    fun write(path: Path, options: JsonOptions = JsonOptions()): String
}

data class JsonOptions(
    val fields: List<PointField>? = null,
    /** Indentation lisible. `false` = compact, défaut, pour la taille de transfert. */
    val pretty: Boolean = false,
    val decimals: Int? = null,
    /** Inclure le bloc `meta` (agrégats + unités). */
    val includeMeta: Boolean = true,
)
```

### 3. Sérialisation

**Ne pas** ajouter `kotlinx.serialization` pour ça. Le module dépend déjà de
`xmlutil-serialization`, mais tirer `kotlinx-serialization-json` pour produire un document de
forme fixe est disproportionné. Construire la chaîne à la main avec un `StringBuilder`, en
réutilisant le formatage numérique de g06.

Contraintes JSON à respecter :

- `NaN` et les infinis **ne sont pas du JSON valide** → sérialiser en `null`. C'est cohérent
  avec « cellule vide » en CSV, et Chart.js interprète `null` comme une rupture de série,
  ce qui est exactement la sémantique voulue.
- Échapper les chaînes (noms de champs) : guillemets, antislashs, caractères de contrôle.

### 4. Façade JS

```kotlin
@JsExport fun pathToJson(path: Path, pretty: Boolean): String
```

Dans la démo, `JSON.parse` sur cette sortie doit donner un objet directement exploitable —
c'est le test d'acceptation le plus parlant.

## Outputs

Créés :

- `gpx/src/commonMain/…/io/JsonWriter.kt`
- `gpx/src/commonTest/…/io/JsonWriterTest.kt`

Modifiés :

- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt`

## Validation

```bash
./gradlew :gpx:allTests
./gradlew ktlintCheck
```

Cas de test (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `Path(0)` | `size: 0`, séries vides, JSON valide |
| 2 | `Path(3)` tous champs | 36 séries de 3 valeurs |
| 3 | `fields` restreint | seules les séries demandées |
| 4 | `NaN` | `null` dans la série |
| 5 | `Infinity` | `null` |
| 6 | `includeMeta = false` | pas de bloc `meta` |
| 7 | `pretty = true` | indenté, sémantiquement identique au compact |
| 8 | Bloc `meta` | agrégats égaux à ceux de `Path` |
| 9 | Sortie parsable | `JSON.parse` réussit (test jsTest/wasmJsTest) |
| 10 | Formatage identique sur les 4 cibles | mêmes chaînes exactes |

Le cas 9 doit tourner **sur les cibles JS et Wasm** : c'est le seul endroit où l'on peut
vraiment vérifier que la sortie est du JSON valide, sans embarquer un parser en commonTest.

## Done when

- [x] Forme du document décidée après lecture du Java, et documentée
- [x] `JsonWriter` + `JsonOptions` en commonMain, sans nouvelle dépendance
- [x] `NaN` / infinis → `null`
- [x] Échappement des chaînes
- [x] `pathToJson` exporté, `.d.ts` régénérés
- [x] ≥ 10 tests verts × 4 cibles, dont un `JSON.parse` en jsTest et wasmJsTest
- [x] `ktlintCheck` vert

## Résultat

**Forme colonnes confirmée après lecture du Java.** `JsonFileWriter` (gpx2web) produit en fait un
document orienté **lignes** : `{"keys": [...], "points": [{...}, {...}, ...]}`, un objet par
point avec les 36 (ou moins, seulement les champs non-`null`) noms de propriétés répétés à chaque
point. C'est bien la divergence anticipée par la fiche — actée ici plutôt que reportée à g20 vu
qu'elle est déjà tranchée dans les Steps : `JsonWriter.write` produit
`{"size":N,"meta":{...},"fields":{"distance":[...],"elevation":[...],...}}`, une série par champ.

**`JsonWriter.kt` et `JsonOptions` en `commonMain`, réutilisant `CsvNumberFormat` sans le
dupliquer.** `CsvNumberFormat.format` gère déjà `NaN` (→ `""`) et l'infini (→ `"Infinity"`/
`"-Infinity"`), deux rendus invalides en JSON : `JsonWriter` ne délègue donc le formatage qu'aux
valeurs finies, et intercepte `NaN`/infini en amont pour émettre le littéral `null` — voir
`numberOrNull()`. Aucune dépendance nouvelle (pas de `kotlinx-serialization-json`) : le document a
une forme fixe, assemblée à la main dans un `StringBuilder`, à l'image de `CsvWriter`.

**Document produit :**

```json
{"size":2,"meta":{"totalDistance":123.4,"durationMs":5000,"elevationGain":12,"elevationLoss":-3,
 "units":{"distance":"meters","elevation":"meters"}},
 "fields":{"distance":[0,123.4],"elevation":[100,105]}}
```

- `meta.units` couvre uniquement les champs demandés via `JsonOptions.fields` (pas les 36 par
  défaut si l'appelant restreint la sélection) — cohérent avec le fait que `fields` ne contient,
  lui aussi, que les séries demandées.
- `options.decimals` s'applique de façon uniforme à `meta` (agrégats) et aux séries de `fields` :
  un seul réglage pour tout le document, pas de sous-option distincte pour les agrégats (la fiche
  ne le demandait pas explicitement, choisi pour la cohérence — un document où `totalDistance`
  aurait un nombre de décimales différent des séries aurait été surprenant).
- `elevationLoss` est déjà négatif ou nul chez `Path` (`Path.kt` : "Always <= 0 (sum of negative
  deltas)") ; `JsonWriter` ne le retraite pas, il transmet tel quel.

**Échappement JSON.** `escapeJsonString` gère guillemet, antislash, `\n`/`\r`/`\t` et tout
caractère de contrôle `< 0x20` restant (`\uXXXX`). Rendue `internal` (pas `private`), comme
`CsvNumberFormat` pour g06 : aucun `PointField.prop`/`unit` actuel ne contient de caractère à
échapper, donc le test dédié (`string_escaping_handles_quotes_backslashes_and_control_chars`)
appelle la fonction directement plutôt que de la déclencher indirectement via `write()`.

**Façade JS/Wasm : `pathToJson(path, pretty)`.** Miroir exact du pattern `pathToCsv` de g06:
`path: Path` direct côté Kotlin/JS, `handle: JsReference<Path>` côté Wasm. Un seul paramètre
utile à exposer au-delà de `pretty` (pas de sélection de champs ni de `decimals` à la frontière
JS pour l'instant — un appelant qui a besoin de plus fin peut toujours appeler `JsonWriter.write`
depuis du code Kotlin/JS partagé ; la façade reste un raccourci pour le cas d'usage "bouton export
JSON" documenté dans la fiche).

**Test d'acceptation `JSON.parse`, sur les deux cibles.** Ajouté dans
`engine/src/jsTest/…/EngineJsApiTest.kt` (`kotlin.js.JSON.parse<dynamic>(json)`, puis lecture de
`.size` / `.fields.elevation.length` / `.meta.totalDistance`) et dans
`engine/src/wasmJsTest/…/EngineJsApiTest.kt` (deux fonctions `@JsFun` externes —
`jsonParsesToObjectOfSize` et `jsonElevationSeriesLength` — qui appellent `JSON.parse` côté JS et
renvoient un `Boolean`/`Int`, seul canal disponible à la frontière Wasm/JS pour ce genre de
vérification). Les deux variantes couvrent `pretty = false` et `pretty = true`.

**Vérifications.** `./gradlew :gpx:allTests :engine:allTests` vert sur JVM + JS Node + JS browser
+ Wasm browser. `JsonWriterTest` : 11 tests (les 10 cas de la table plus le test d'échappement),
`:gpx` JVM passe de 196 à 207 tests (chiffre après le module g06). `EngineJsApiTest` (jsTest et
wasmJsTest) : 2 tests neufs chacun (`pathToJson` compact + pretty via `JSON.parse`), portant
`:engine` jsTest/wasmJsTest de 7 à 9 tests chacun. `ktlintFormat` puis `ktlintCheck` verts ;
`ktlintFormat` a reformaté `JsonWriter.kt` (chaînage `appendKeyRaw` sur plusieurs lignes, style
habituel du projet pour les appels enchaînés) — pas d'autre fichier touché au-delà du diff
additif attendu sur les deux `EngineJsApi.kt`.

## Notes

- **Forme colonnes plutôt que lignes** : divergence possible avec gpx2web, à acter dans g20
  après lecture du Java.
- **Pas de `kotlinx-serialization-json`** : document de forme fixe, `StringBuilder` suffit, et
  ça évite une dépendance de plus sur 4 cibles.
- **`NaN` → `null`** : `NaN` littéral est du JSON invalide, que `JSON.parse` refuse. Certains
  sérialiseurs le produisent quand même — ne pas les imiter.
- Réutiliser le formatage numérique de g06 : si les deux writers divergent sur la
  représentation de `1.0`, c'est un bug en attente.
