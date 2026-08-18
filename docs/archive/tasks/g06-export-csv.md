# g06 — Writer CSV

## Goal

Porter l'export CSV de gpx2web (`CSVFileWriter` + `TabularFileWriter`) : sérialiser les 36
champs d'un `Path` en CSV, une ligne par point.

gpx2web s'appuie sur son framework `PropertyKey` / `Unit` / `Converter` pour produire en-têtes
et valeurs formatées. vcyclist a déjà l'équivalent dans `PointField` (nom, unité, catégorie) —
on s'en sert, on ne porte pas le framework.

## Depends on

- `g01` (module `:gpx`)
- `g02` (multi-track : un CSV par `Path`)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/write/tabular/{CSVFileWriter,TabularFileWriter,TabularCellWriter,TabularHeadersInit,TabularRowInit}.java`
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/data/values/{PropertyKey,PropertyKeys}.java` (référence, non portée)
- `gpx/src/commonMain/…/path/{PointField,PointFieldCategory}.kt`

## Steps

### 1. API

commonMain, aucune I/O fichier (non portable) : le writer produit une `String`. C'est
l'appelant — CLI en JVM, `Blob` + `URL.createObjectURL` dans le navigateur — qui écrit.

```kotlin
/**
 * Sérialisation CSV d'un [Path]. Une ligne d'en-tête, puis une ligne par point.
 *
 * Port de `io.github.glandais.gpx.io.write.tabular.CSVFileWriter` (gpx2web), sans son
 * framework `PropertyKey`/`Unit` : les en-têtes et unités viennent de [PointField].
 */
object CsvWriter {
    fun write(path: Path, options: CsvOptions = CsvOptions()): String
}

data class CsvOptions(
    /** Champs exportés, dans l'ordre. `null` = tous, dans l'ordre de `PointField.entries`. */
    val fields: List<PointField>? = null,
    val separator: Char = ',',
    /** Ajoute l'unité entre parenthèses dans l'en-tête : `elevation (m)`. */
    val unitsInHeader: Boolean = true,
    /** Chiffres après la virgule. `null` = représentation la plus courte qui round-trip. */
    val decimals: Int? = null,
    /** Fin de ligne. `\n` par défaut ; `\r\n` pour Excel sous Windows. */
    val lineSeparator: String = "\n",
)
```

### 2. Formatage des nombres

C'est le point délicat en multiplateforme : `Double.toString()` ne donne **pas** le même
résultat en JVM, en JS et en Wasm (`1.0` vs `1`, notation exponentielle à des seuils
différents).

Implémenter un formatage explicite et testé sur les 4 cibles :

- `decimals = null` → format canonique choisi par nous, pas la sortie native de la plateforme.
- `NaN` → cellule **vide** (et non `NaN`) : c'est ce qu'attend un tableur, et vcyclist utilise
  `NaN` comme marqueur d'absence.
- Pas de notation exponentielle : un CSV lu par un humain ou un tableur ne doit pas contenir
  `1.234E-5`.

Le séparateur décimal est **toujours** le point. Un utilisateur en locale française devra
importer en précisant le format — c'est le comportement de gpx2web, et une locale implicite
serait pire.

### 3. Échappement

Les en-têtes contiennent des espaces et des parenthèses, jamais le séparateur. Implémenter
tout de même l'échappement RFC 4180 (guillemets doublés, champ entre guillemets s'il contient
séparateur, guillemet ou saut de ligne) : le coût est faible et un `separator = ';'` avec un
nom de champ contenant un `;` casserait le fichier.

### 4. Façade JS

```kotlin
@JsExport fun pathToCsv(path: Path, separator: String, unitsInHeader: Boolean): String
```

Permet un bouton « Export CSV » dans la démo sans passer par le serveur.

## Outputs

Créés :

- `gpx/src/commonMain/…/io/CsvWriter.kt`
- `gpx/src/commonMain/…/io/CsvOptions.kt` (ou dans le même fichier)
- `gpx/src/commonTest/…/io/CsvWriterTest.kt`

Modifiés :

- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt`

## Validation

```bash
./gradlew :gpx:allTests
./gradlew ktlintCheck
```

Cas de test (≥ 12) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `Path(0)` | en-tête seule, pas de ligne de données |
| 2 | `Path(3)` tous champs | 1 + 3 lignes, 36 colonnes |
| 3 | `fields` restreint à 3 champs | 3 colonnes, dans l'ordre demandé |
| 4 | `unitsInHeader = true` | en-tête `elevation (m)` |
| 5 | `unitsInHeader = false` | en-tête `elevation` |
| 6 | `NaN` dans un champ | cellule vide |
| 7 | `separator = ';'` | séparateur respecté |
| 8 | `decimals = 2` | exactement 2 décimales |
| 9 | `decimals = null` sur 1.0 | même sortie sur les 4 cibles |
| 10 | Valeur très petite (1e-9) | pas de notation exponentielle |
| 11 | Valeur très grande (1e12) | pas de notation exponentielle |
| 12 | `lineSeparator = "\r\n"` | CRLF partout, y compris après l'en-tête |
| 13 | En-tête contenant le séparateur | champ entre guillemets |

Les cas 9-11 sont les vrais tests de cette tâche : ils échouent naturellement si on s'en remet
à `Double.toString()`.

## Done when

- [x] `CsvWriter` + `CsvOptions` en commonMain
- [x] Formatage numérique explicite, identique sur les 4 cibles
- [x] `NaN` → cellule vide
- [x] Échappement RFC 4180
- [x] `pathToCsv` exporté, `.d.ts` régénérés
- [x] ≥ 12 tests verts × 4 cibles
- [x] `ktlintCheck` vert

## Résultat

**`CsvNumberFormat` est un objet à part, `internal`, pas une méthode privée de `CsvWriter`.**
Comme demandé pour g07 : `gpx/src/commonMain/…/io/CsvNumberFormat.kt` porte tout le formatage
`Double → String`, `CsvWriter.kt` ne fait plus qu'assembler en-têtes + cellules + échappement.
Les deux fichiers vivent dans un nouveau package `io.github.glandais.engine.io` (à côté de
`path` et `gpx`), cohérent avec l'emplacement attendu par la fiche g07.

**Formatage "shortest round-trip" implémenté sans dépendre du `toString()` de la plateforme.**
`Double.toString()` diverge entre JVM/JS/Wasm (`"1.0"` vs `"1"`, seuils d'exponentielle
différents) — c'est justement le problème que la fiche pointe. La stratégie retenue :

1. `formatFixed(value, decimals)` : multiplie la valeur absolue par `10^decimals`, arrondit en
   `Long` (`kotlin.math.round` puis `.toLong()`), puis reconstruit la chaîne à la main à partir
   des parties entière/décimale de ce `Long`. Formater un entier (`Long.toString()`) est,
   contrairement à `Double.toString()`, identique sur les 4 cibles — c'est le socle qui rend tout
   le reste stable.
2. Quand `decimals == null`, on essaie `formatFixed` avec 0, 1, 2, … jusqu'à
   `MAX_AUTO_DECIMALS = 12` décimales, et on garde la **première** chaîne qui, reparsée via
   `String.toDouble()`, redonne exactement la valeur d'origine (`==` bit-à-bit sur les `Double`
   IEEE 754, pas de tolérance). C'est l'algorithme "le plus court qui round-trip" demandé par
   `CsvOptions.decimals` — approximatif (un vrai Grisu/Ryu ferait mieux dans les cas extrêmes),
   mais suffisant pour des grandeurs physiques (mètres, degrés, watts, m/s) et déterministe sur
   les 4 cibles puisqu'il ne s'appuie que sur `round`, l'arithmétique `Long` et le parsing
   `String → Double`, tous IEEE-754-conformes.
3. Le plafond `MAX_AUTO_DECIMALS = 12` évite un dépassement de `Long` pour des valeurs élevées
   (l'inertie de roue, la puissance, etc. restent < 10¹² à cette échelle) ; `decimals` explicite
   au-delà lève une `IllegalArgumentException` plutôt que de silencieusement tronquer.
4. `NaN` → `""`, infinis → `"Infinity"`/`"-Infinity"` (cas non couvert par la table de tests mais
   qui aurait autrement produit une exception d'arrondi/`Long` invalide).
5. `-0.0` formate `"0"` : `(-0.0) < 0.0` est `false` en IEEE 754, donc le signe n'est pas
   émis — testé explicitement (`negative_zero_does_not_render_a_minus_sign`).

**En-têtes : `prop (unit)` littéral, pas d'abréviation d'unité.** La fiche donne
`elevation (m)` comme illustration, mais `PointField.unit` pour `ELEVATION` vaut `"meters"` (pas
`"m"`) — c'est la source de vérité déjà en place, et la tâche interdit explicitement de porter
le framework `PropertyKey`/`Unit`/`Converter` de gpx2web qui ferait cette abréviation. Choix
assumé : l'en-tête produite est `elevation (meters)`, testée telle quelle
(`units_in_header_true_appends_unit_in_parentheses`). Si un jour un besoin d'abréviation émerge,
ce sera une option de `CsvOptions`, comme le note déjà la fiche.

**Échappement RFC 4180 systématique, jamais court-circuité par une hypothèse "ça n'arrive
jamais".** `escapeCsvCell` s'applique à toutes les cellules (en-têtes compris) et vérifie
séparateur / guillemet / `\n` / `\r` à chaque fois. Testé avec `separator = ' '` (un choix
inhabituel mais légal de `CsvOptions`) sur l'en-tête `"elevation (meters)"`, qui contient un
espace : la sortie est bien `"\"elevation (meters)\""`.

**Façade JS/Wasm : `pathToCsv(path, separator, unitsInHeader)`.** `separator` est un `String`
(pas de `Char` à la frontière JS), on ne garde que le premier caractère
(`separator.firstOrNull() ?: ','`). Ajoutée en miroir strict dans les deux façades : `path: Path`
directement côté Kotlin/JS, `handle: JsReference<Path>` côté Wasm (même pattern que `pathSize`,
`writeGpx`, etc.). Aucune signature existante modifiée.

**Vérifications.** `./gradlew :gpx:allTests :engine:allTests` vert sur JVM + JS Node + JS
browser + Wasm browser. 20 tests neufs (`CsvWriterTest` : 14, couvrant les 13 cas de la table
plus un cas de signe négatif ; `CsvNumberFormatTest` : 6, tests unitaires du formateur seul,
y compris `-0.0` et les cas 1e-9/1e12 sans notation exponentielle) ; total `:gpx` JVM passé de
176 à 196 tests. `ktlintFormat` puis `ktlintCheck` verts, aucun fichier reformaté au-delà des
lignes ajoutées (diff purement additif sur les deux `EngineJsApi.kt`).

## Notes

- **XLSX n'est pas porté** (acté dans `PLAN-GPX2WEB.md`) : Apache POI est JVM-only et lourd, le
  CSV couvre le besoin tableur.
- **Pas d'I/O fichier en commonMain** : le writer rend une `String`. L'écriture disque est du
  ressort de `:cli` (g17).
- **Le framework `PropertyKey`/`Unit`/`Converter` de gpx2web n'est pas porté** : `PointField`
  porte déjà nom, unité et catégorie. Si un besoin de conversion d'unité à l'export émerge
  (km/h vs m/s), ce sera une option de `CsvOptions`, pas un framework.
- Sur un path d'un million de points, construire une `String` unique consomme de la mémoire.
  Acceptable pour l'usage visé (traces de quelques dizaines de milliers de points) ; si ça
  devient un problème, ajouter une variante prenant un `Appendable`.
