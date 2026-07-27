# g04 — `GpxXmlRepair` : réparation des GPX malformés

## Goal

Porter `GpxXmlRepair` (131 l.) de gpx2web : une passe de nettoyage appliquée au XML brut
**avant** parsing, qui rattrape les fichiers produits par des appareils ou des exports
approximatifs.

Aujourd'hui, vcyclist lève `IllegalArgumentException("Invalid GPX XML: …")` et s'arrête. Avec
la démo navigateur, ça se traduit par un fichier utilisateur simplement refusé.

## Depends on

- `g01` (module `:gpx`)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/read/GpxXmlRepair.java` (canonique)
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/read/GPXFileReader.java` (point d'appel)
- `gpx/src/commonMain/…/gpx/GpxParser.kt`

## Steps

### 1. Inventorier ce que répare la version Java

**À faire avant d'écrire une ligne de Kotlin** : lire les 131 lignes et lister exhaustivement
les réparations effectuées, avec pour chacune un exemple d'entrée cassée. Cette liste est le
contrat de la tâche et alimente directement la table de tests.

Attendus typiques (à confirmer par la lecture) : caractères de contrôle interdits en XML 1.0,
esperluettes non échappées, BOM, encodage déclaré ≠ encodage réel, balises non fermées en fin
de fichier (trace tronquée).

### 2. Porter en commonMain

`gpx/src/commonMain/kotlin/io/github/glandais/engine/gpx/GpxXmlRepair.kt` :

```kotlin
/**
 * Nettoyage du XML brut avant parsing. Chaque réparation est indépendante et sans effet sur
 * un document déjà valide : `repair(validXml) == validXml` doit tenir.
 *
 * Port de `io.github.glandais.gpx.io.read.GpxXmlRepair` (gpx2web).
 */
object GpxXmlRepair {
    fun repair(xml: String): String
    /** Variante instrumentée : renvoie aussi la liste des réparations appliquées. */
    fun repairVerbose(xml: String): RepairResult
}

data class RepairResult(val xml: String, val repairs: List<String>)
```

Kotlin pur, aucune API de plateforme : `kotlin.text` suffit. Attention aux `Regex` sur des
fichiers de plusieurs Mo — préférer un balayage `StringBuilder` là où gpx2web utilise des
regex coûteuses, et le noter.

### 3. Brancher sur le parser

Ne **pas** réparer systématiquement : une passe de réparation sur chaque fichier coûte une
copie de la chaîne pour rien dans 99 % des cas.

```kotlin
fun parse(xml: String, repairOnFailure: Boolean = true): GpxDocument
```

Sémantique : tenter le parsing normal ; en cas d'échec **et** si `repairOnFailure`, appliquer
`GpxXmlRepair.repair` puis retenter une fois. Si le second essai échoue, propager l'exception
du **second** essai, en mentionnant qu'une réparation a été tentée.

`GpxXmlRepair.repair` reste public : un appelant peut réparer explicitement.

### 4. Journalisation

Quand une réparation sauve un fichier, le signaler — sinon le comportement est magique et
indébogable. Utiliser le mécanisme de log déjà en place dans le module ; à défaut, exposer
l'information via `RepairResult` et laisser l'appelant décider.

## Outputs

Créés :

- `gpx/src/commonMain/…/gpx/GpxXmlRepair.kt`
- `gpx/src/commonTest/…/gpx/GpxXmlRepairTest.kt`
- Fixtures cassées dans `GpxFixtures.kt` (chaînes brutes Kotlin, une par type de casse)

Modifiés :

- `GpxParser.kt` (paramètre `repairOnFailure`)

## Validation

```bash
./gradlew :gpx:allTests
./gradlew ktlintCheck
```

Cas de test — **un par réparation identifiée à l'étape 1**, plus :

| # | Cas | Attendu |
|---|---|---|
| A | `repair(gpxValide) == gpxValide` | idempotence, aucun effet de bord |
| B | `repair(repair(x)) == repair(x)` | idempotence de la réparation |
| C | GPX cassé → `parse(xml)` réussit | réparation transparente |
| D | GPX cassé → `parse(xml, repairOnFailure = false)` | lève l'exception |
| E | XML irrécupérable (`"pas du xml du tout"`) | lève, message mentionnant la tentative de réparation |
| F | Fichier de 5 Mo valide | pas de réparation déclenchée, pas de coût mesurable |

## Done when

- [ ] Liste exhaustive des réparations de la version Java, documentée dans la fiche
- [ ] `GpxXmlRepair` porté en commonMain
- [ ] `parse(xml, repairOnFailure = true)` en défaut, retry unique
- [ ] Un test par réparation + les cas A-F
- [ ] Tests verts × 4 cibles
- [ ] `ktlintCheck` vert

## Notes

- **Retry unique, jamais de boucle.** Réparer en boucle jusqu'à ce que ça passe est le chemin
  direct vers un hang sur fichier hostile.
- **Ne pas réparer en amont systématiquement** : coût mémoire inutile (copie complète de la
  chaîne) sur la trace valide, qui est le cas courant.
- **Propager l'exception du second essai** : celle du premier essai décrit un fichier qui
  n'existe plus après réparation, donc un message trompeur.
- Cette tâche améliore directement l'expérience de la démo navigateur : c'est le seul endroit
  où l'utilisateur soumet des fichiers arbitraires.
