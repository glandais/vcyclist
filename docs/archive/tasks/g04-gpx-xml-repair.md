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

- [x] Liste exhaustive des réparations de la version Java, documentée dans la fiche
- [x] `GpxXmlRepair` porté en commonMain
- [x] `parse(xml, repairOnFailure = true)` en défaut, retry unique
- [x] Un test par réparation + les cas A-F
- [x] Tests verts × 4 cibles
- [x] `ktlintCheck` vert

## Résultat

**Étape 1 — inventaire réel de `GpxXmlRepair.java` (131 l.) : seulement 2 réparations, pas 4.**
La lecture donne un contrat plus étroit que les « attendus typiques » devinés dans ce document :

1. **Ré-encodage** : si `raw` n'est pas de l'UTF-8 valide, le décoder en ISO-8859-1 puis
   ré-encoder en UTF-8, et forcer `encoding="UTF-8"` dans la déclaration XML.
2. **Recombinaison de paires de substituts** : un caractère astral écrit comme deux références
   numériques adjacentes à ses code units UTF-16 (`&#55357;&#56888;`) — interdit par XML 1.0,
   qui ne reconnaît qu'une référence numérique unique vers le point de code combiné.

Aucune trace de nettoyage de caractères de contrôle, d'échappement d'esperluette ou de
réparation de balise non fermée côté gpx2web : ces trois idées, mentionnées dans la section
« Attendus typiques » ci-dessus, étaient des suppositions **avant** lecture du source, pas des
réparations réellement portées par la version Java.

**Décision de conception n°1 — la réparation n°1 (encodage) ne se porte pas telle quelle.**
`GpxXmlRepair.java` opère sur `byte[]` : elle peut encore accéder aux octets bruts pour tenter un
second décodage. `GpxParser.parse(xml: String)` reçoit un texte **déjà décodé** par l'appelant
(fetch/FileReader côté démo, lecture fichier côté JVM) — au moment où `GpxXmlRepair` le voit, un
mauvais choix de charset a déjà perdu de l'information (un décodeur strict remplace les
séquences UTF-8 invalides par `U+FFFD`) et ne peut plus être annulé depuis la seule `String`.
Une tentative « au niveau `String` » a été envisagée (vérifier que tous les caractères tiennent
sur un octet, ré-encoder en Latin-1, redécoder en UTF-8) et rejetée : un texte Latin-1
**correctement** décodé peut, par coïncidence, former une séquence UTF-8 syntaxiquement valide
(ex. `"café"` suivi de deux caractères dont les codes tombent dans une plage de octets de
continuation) — le risque de corruption silencieuse d'un fichier déjà correct l'emporte sur le
bénéfice. Documenté dans le KDoc de `GpxXmlRepair.kt`.

**Décision de conception n°2 — trois réparations « attendues » ajoutées à la place, alignées
sur l'intro de la tâche.** Puisque la réparation d'encodage ne transfère pas, et que le
problème réel visé par g04 (« fichiers produits par des appareils ou des exports
approximatifs ») reste entier, `GpxXmlRepair.kt` porte à la place les trois idées de la section
« Attendus typiques » — chacune sûre car elle ne cible qu'une syntaxe **toujours** illégale en
XML 1.0, jamais un caractère légitime :

- BOM (`U+FEFF`) en tête de document, avant `<?xml` — supprimé.
- Caractères de contrôle C0/C1 interdits par XML 1.0 (hors `\t \n \r`) et substituts UTF-16
  isolés — supprimés caractère par caractère ; les vraies paires de substituts (emoji réels dans
  un nom de trace) sont détectées et conservées intactes.
- Esperluette seule (`Café & Croissant` au lieu de `Café &amp; Croissant`) — échappée en
  `&amp;`, sauf si elle amorce déjà une référence de caractère/entité valide.

La recombinaison de paires de substituts (réparation n°2 du Java) est portée sans changement
de sémantique, seulement de représentation (scan par index plutôt que `Matcher` Java).

**Implémentation par boucles indexées, pas par `Regex`.** Comme demandé par la note de la
fiche : un fichier GPX peut peser plusieurs Mo (une ligne par point, sans retour à la ligne), et
un moteur de regex à *backtracking* invoqué caractère par caractère est un coût inutile sur le
cas courant (fichier valide, 0 réparation) et un risque de dégénérescence sur le cas cassé.
Chaque passe est un simple parcours `StringBuilder`, construit paresseusement (`sb` reste `null`
tant qu'aucune réparation n'est nécessaire, donc `repair(xmlValide)` ne recopie jamais la
chaîne).

**Étape 3 — retry unique dans `GpxParser.parse`.** `parse()` devient `parse(xml, repairOnFailure
= true)` : `parseOnce` interne inchangé dans sa logique, appelé une première fois, puis — en cas
d'échec et si `repairOnFailure` — une seconde fois sur `GpxXmlRepair.repair(xml)`. L'exception
propagée est toujours celle du **second** essai (message préfixé « repair attempted »), jamais
celle du premier, conformément à la note de la fiche.

Effet de bord découvert en cours de route : le premier essai attrapait seulement `XmlException`,
mais un texte qui n'est même pas du XML (`"pas du xml du tout"`, cas E) fait lever une
`IllegalStateException` par le lecteur `xmlutil` sous-jacent sur les cibles Kotlin/JS et
Kotlin/Wasm. `parseOnce` attrape maintenant `Exception` en général (en laissant passer telle
quelle notre propre `IllegalArgumentException` de validation `lat`/`lon`), pour que ce cas
échoue bien avec un message exploitable sur les 4 cibles au lieu d'une exception brute non
catchée sur certaines.

**Étape 4 — journalisation : aucun mécanisme de log en commonMain, donc `RepairResult` fait
tout le travail.** `grep` sur `commonMain` de `:gpx` et `:engine` ne trouve ni `Logger` ni
`Napier` ni rien d'équivalent. `parse()` reste donc silencieux en cas de succès après réparation
— l'information n'est pas perdue pour autant : elle est exposée par
`GpxXmlRepair.repairVerbose(xml).repairs`, que l'appelant (la démo navigateur, typiquement) peut
appeler explicitement avant `parse(xml, repairOnFailure = false)` pour obtenir un diagnostic
détaillé sans dupliquer le travail de réparation.

**Découverte inattendue : `xmlutil` est plus permissif que prévu, et pas de façon uniforme
entre cibles.** En testant les cas C/D (retry sur un document cassé) sur les 4 cibles, seule
l'esperluette seule fait échouer le parsing strict sur JVM, Kotlin/JS-Node et Wasm/navigateur —
BOM, caractères de contrôle et substituts non appariés sont acceptés tels quels par le lecteur
`xmlutil` multiplateforme sans jamais lever, sur aucune cible (validations de « caractère légal
XML 1.0 » absentes de son scanner). Sur Kotlin/JS-navigateur, à l'inverse, même l'esperluette
seule ne fait pas échouer le parsing : ce backend délègue au `DOMParser` natif du navigateur, qui
**récupère silencieusement** en tronquant l'arbre au point d'erreur (0 point au lieu de 2) plutôt
que de lever une exception — le même comportement que celui déjà documenté dans le commentaire
du cas 01 de `GpxParserTest` pour les balises fermantes mal appariées. Conséquence pratique :
les cas C et D (qui exigent un document cassé de façon strictement identique sur les 4 cibles)
vivent dans `gpx/src/jvmTest/.../GpxXmlRepairParseTest.kt`, une suite **JVM seulement** — voir
l'en-tête de ce fichier pour le détail. `GpxXmlRepair` lui-même (la transformation de chaîne, y
compris son effet sur le parsing une fois réparé) reste testé sur les 4 cibles dans
`GpxXmlRepairTest`.

**Fixtures ajoutées à `GpxFixtures.kt`** (partagées `:gpx`/`:engine` comme le reste) :
`VALID_MINIMAL_GPX` (référence « déjà valide »), `BOM_GPX`, `CONTROL_CHAR_GPX`,
`BARE_AMPERSAND_GPX`, `SURROGATE_PAIR_GPX`, `NOT_XML_AT_ALL`.

**Vérification.** `./gradlew :gpx:allTests :engine:allTests` verts sur les 4 cibles (156 tests
JVM pour `:gpx`, dont 15 nouveaux : 13 dans `GpxXmlRepairTest` + 2 dans
`GpxXmlRepairParseTest`) ; `./gradlew ktlintFormat && ./gradlew ktlintCheck` verts. Cas F (fichier
de 20 000 points, ~1,7 Mo) : 0 réparation déclenchée, `repairVerbose` s'exécute en quelques
dizaines de ms sur la machine de dev (assertion large `< 10 s` dans le test pour éviter le bruit
CI, garde surtout contre une régression O(n²) accidentelle).

## Notes

- **Retry unique, jamais de boucle.** Réparer en boucle jusqu'à ce que ça passe est le chemin
  direct vers un hang sur fichier hostile.
- **Ne pas réparer en amont systématiquement** : coût mémoire inutile (copie complète de la
  chaîne) sur la trace valide, qui est le cas courant.
- **Propager l'exception du second essai** : celle du premier essai décrit un fichier qui
  n'existe plus après réparation, donc un message trompeur.
- Cette tâche améliore directement l'expérience de la démo navigateur : c'est le seul endroit
  où l'utilisateur soumet des fichiers arbitraires.
