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

- [ ] `CsvWriter` + `CsvOptions` en commonMain
- [ ] Formatage numérique explicite, identique sur les 4 cibles
- [ ] `NaN` → cellule vide
- [ ] Échappement RFC 4180
- [ ] `pathToCsv` exporté, `.d.ts` régénérés
- [ ] ≥ 12 tests verts × 4 cibles
- [ ] `ktlintCheck` vert

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
