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

- [ ] Forme du document décidée après lecture du Java, et documentée
- [ ] `JsonWriter` + `JsonOptions` en commonMain, sans nouvelle dépendance
- [ ] `NaN` / infinis → `null`
- [ ] Échappement des chaînes
- [ ] `pathToJson` exporté, `.d.ts` régénérés
- [ ] ≥ 10 tests verts × 4 cibles, dont un `JSON.parse` en jsTest et wasmJsTest
- [ ] `ktlintCheck` vert

## Notes

- **Forme colonnes plutôt que lignes** : divergence possible avec gpx2web, à acter dans g20
  après lecture du Java.
- **Pas de `kotlinx-serialization-json`** : document de forme fixe, `StringBuilder` suffit, et
  ça évite une dépendance de plus sur 4 cibles.
- **`NaN` → `null`** : `NaN` littéral est du JSON invalide, que `JSON.parse` refuse. Certains
  sérialiseurs le produisent quand même — ne pas les imiter.
- Réutiliser le formatage numérique de g06 : si les deux writers divergent sur la
  représentation de `1.0`, c'est un bug en attente.
