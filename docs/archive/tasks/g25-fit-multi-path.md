# g25 — FIT : multi-`Path` et contrat de timestamp

## Goal

Deux problèmes distincts dans `fit/src/commonMain/…/PathToFit.kt`, l'un est un bug silencieux,
l'autre une lacune fonctionnelle par rapport à gpx2web.

**a. Le timestamp est faux dès que `time(0) != 0`.** `toFitCourse` écrit
`timestamp = startTime + time(i)` (`PathToFit.kt:58`). Correct pour un path issu de
`VirtualizeService`, dont l'horloge est relative (`time(0) == 0`, invariant documenté). Mais
`GpxToPath.pointsToPath` recopie `timeEpochMs` **verbatim** — un path simplement parsé depuis un
GPX horodaté porte des millisecondes d'époque, et le FIT produit se retrouve daté de
`startTime + ~1,8 × 10¹² ms`, soit environ **57 ans dans le futur**. Aucun test ne le couvre :
toutes les fixtures FIT partent d'un path virtualisé.

**b. Un seul `Path` par fichier.** `toFitBytes` est une extension de `Path`. gpx2web écrit
`gpx.paths()` entier dans un seul FIT : un `LapMesg` par path, et des `EventMesg` `TIMER/START`
… `TIMER/STOP` (`STOP_ALL` sur le dernier seulement) encadrant les records de chacun
(`FitFileWriter.java:25-70`).

## Depends on

- `g08`-`g10` (module `:fit`, encodeurs JVM et JS, round-trip)
- `g05` (`startTime` explicite)

## Inputs

- `fit/src/commonMain/…/{PathToFit,FitCourse,FitEncoder,FitUnits,FitMessageNumbers}.kt`
- `fit/src/{jvmMain,jsMain}/…/FitEncoder.*.kt`
- `../gpx2web/…/io/write/FitFileWriter.java` — référence (boucle sur les paths, `EventMesg`)
- `gpx/src/commonMain/…/gpx/GpxToPath.kt` — la source du `time(i)` absolu
- `engine/src/commonMain/…/physics/VirtualizeService.kt` — KDoc de l'invariant `time(0) = 0`

## Steps

### 1. (a) Rebaser les timestamps sur le premier point

```kotlin
val t0 = time(0)
timestamp = startTime + (time(i) - t0).toLong().milliseconds
```

- Path relatif (`time(0) == 0`) : sortie **strictement inchangée**, les tests g08-g10 restent
  verts sans retouche. C'est ce que vérifie le cas 1.
- Path absolu : `startTime` redevient ce que son nom promet — l'instant du premier point.
- L'opération devient **idempotente**, ce que l'API actuelle n'est pas.

Ajouter une garde sur la monotonie : un FIT à timestamps décroissants est accepté par le SDK et
rejeté par les plateformes. `require` plutôt que correction silencieuse — un path non monotone
signale un problème en amont qu'il vaut mieux voir.

Mettre à jour le KDoc du tableau de correspondance (`PathToFit.kt:20`), qui documente
aujourd'hui la formule fausse.

### 2. (b) Modèle multi-path

Dans `FitCourse.kt`, remplacer `records: List<FitRecord>` + `lap: FitLap` par :

```kotlin
data class FitSegment(val records: List<FitRecord>, val lap: FitLap)

data class FitCourse(
    val name: String,
    val startTime: Instant,
    val segments: List<FitSegment>,
    val sport: FitSport = FitSport.CYCLING,
) {
    /** Tous les records, tous segments confondus. Accesseur de compatibilité pré-g25. */
    val records: List<FitRecord> get() = segments.flatMap { it.records }

    /** Le lap unique. Lève si le course en compte plusieurs. Accesseur de compatibilité. */
    val lap: FitLap get() = segments.single().lap
}
```

Même stratégie que `GpxTrack.points` en g02 : les accesseurs dérivés évitent de casser les
appelants et les tests existants.

### 3. (b) Encodeurs — **le point de risque**

`FitEncoder` doit désormais émettre les `EventMesg`. **Vérifier la faisabilité des deux côtés
avant de figer le modèle** : `com.garmin:fit` expose `EventMesg` (utilisé tel quel par gpx2web),
`@garmin/fitsdk` expose les mêmes messages par nom côté `Encoder`, mais c'est là que la parité
JVM / JS peut se rompre — et c'est cette parité que garantit le test de round-trip de g10.

Si `@garmin/fitsdk` ne permet pas d'écrire les `EventMesg` proprement, deux issues acceptables,
à trancher et documenter : les omettre des deux côtés (y compris JVM, pour préserver la parité), ou
livrer le multi-lap sans les events. **Ne pas** livrer des sorties divergentes selon la cible.

Ordre d'écriture, calqué sur `FitFileWriter.java` : `FileId`, `Course`, tous les `Lap`, puis
pour chaque path `Event(START)` → records → `Event(STOP)` — `STOP_ALL` sur le dernier.

### 4. (b) API

```kotlin
fun List<Path>.toFitCourse(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
    interPathGap: Duration = Duration.ZERO,
): FitCourse

fun List<Path>.toFitBytes(…): ByteArray
```

Chaque path est rebasé sur son **propre** `time(0)` (cf. étape 1), puis posé après le précédent,
séparé par `interPathGap`. Défaut `ZERO` : les paths s'enchaînent sans trou, ce qui correspond
au cas dominant — un même parcours découpé en plusieurs traces.

L'extension mono-`Path` existante reste publique et devient `listOf(this).toFitCourse(…)`.

Le `require(size > 0)` actuel devient : liste non vide **et** aucun path vide.

## Outputs

Modifiés :

- `fit/src/commonMain/…/{PathToFit,FitCourse,FitEncoder}.kt`
- `fit/src/{jvmMain,jsMain}/…/FitEncoder.*.kt`
- `cli/src/main/kotlin/…/command/ExportCommand.kt` — écrire tous les paths dans un seul FIT
- `docs/gpx2web-coverage.md` — ligne `FitFileWriter`

Créés :

- `fit/src/commonTest/…/PathToFitMultiTest.kt`
- `fit/src/commonTest/…/PathToFitTimestampTest.kt`

## Validation

```bash
./gradlew :fit:allTests :cli:test
./gradlew ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | Path virtualisé (`time(0) == 0`) | timestamps FIT **inchangés** vs pré-g25 |
| 2 | Path parsé d'un GPX horodaté (`time(0) ≈ 1,8e12`) | premier record exactement à `startTime` |
| 3 | `toFitCourse` appliqué deux fois au même path rebasé | idempotent |
| 4 | Path à `time(i)` décroissant | `IllegalArgumentException` explicite |
| 5 | 3 paths, `interPathGap = ZERO` | 3 laps, records contigus, timestamps monotones |
| 6 | 3 paths, `interPathGap = 5 min` | trou de 300 s entre chaque bloc |
| 7 | Events | `START` par path, `STOP` sauf le dernier en `STOP_ALL` |
| 8 | Round-trip encode → décode multi-path, JVM + JS | laps, records et events identiques sur les 3 cibles de test |
| 9 | Accesseurs `records` / `lap` de compatibilité | inchangés pour un course mono-segment |
| 10 | `lap` sur un course multi-segment | lève (`single()`), message clair |
| 11 | Liste vide, ou contenant un path vide | `IllegalArgumentException` |
| 12 | CLI `export --fit` sur un GPX multi-track | un seul fichier, un lap par track |

## Done when

- [x] Timestamps rebasés sur `time(0)`, KDoc corrigé, garde de monotonie
- [x] Sortie inchangée pour tous les paths virtualisés (cas 1 vérifié par comparaison exacte)
- [x] `FitSegment` + accesseurs de compatibilité
- [x] `List<Path>.toFitCourse` / `toFitBytes` avec un lap et des events par path
- [x] Faisabilité des `EventMesg` tranchée et **identique sur les deux encodeurs**
- [x] Round-trip vert sur JVM, JS Node et JS navigateur
- [x] CLI `export --fit` **et `enhance --fit`** écrivent tous les paths
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### (a) Le bug de timestamp

`toFitSegment` calcule `startTime + (time(i) - time(0))`. Conséquences mesurées :

- path virtualisé (`time(0) == 0`) : **sortie inchangée**, y compris au niveau des octets — les
  tests g08-g10 et les deux références `FitReferenceBytes` passent sans retouche ;
- path parsé d'un GPX horodaté : le premier record tombe exactement sur `startTime`, au lieu de
  `startTime + 1,8 × 10¹² ms` (≈ 2083). Le cas de test 02 assert explicitement
  `timestamp < 2_000_000_000_000` avec le commentaire qui explique pourquoi.

Garde de monotonie ajoutée : un `time(i) < time(i-1)` lève avec les deux indices et les deux
valeurs. FIT accepte des records qui reculent, les plateformes qui les lisent non.

### (b) Le point de risque `EventMesg` : **il n'existait pas**

La fiche annonçait la faisabilité des `EventMesg` comme le risque principal, à vérifier avant de
figer le modèle. Vérification faite en lisant les deux `actual` : **les deux encodeurs écrivaient
déjà** un `EventMesg` `TIMER`/`START` avant les records et un `TIMER`/`STOP_ALL` après, depuis
g08/g09. Il n'y avait donc qu'à en émettre une paire par segment, et à ajouter la constante
`EVENT_TYPE_STOP` (valeur 1) qui manquait — `EVENT_TYPE_STOP_ALL` (4) existait seule.

Aucune divergence JVM / JS à arbitrer : les deux SDK exposent le message, et le round-trip
décodé côté JVM confirme la séquence `START, STOP, START, STOP, START, STOP_ALL` sur trois paths.

### (c) Modèle

`FitCourse.segments: List<FitSegment>`, avec `FitSegment(records, lap)`. Les accesseurs dérivés
`records` (concaténation) et `lap` (le lap unique, sinon `error`) couvrent la lecture, et un
`operator fun invoke(name, startTime, records, lap, sport)` dans le companion garde le
constructeur d'avant compilable.

**Rupture assumée** : `FitCourse.copy(records = …)` et `copy(lap = …)` n'existent plus — un
`copy()` de data class ne peut pas passer par des accesseurs dérivés. Un test interne l'utilisait
et a été réécrit. C'est le seul point de rupture, il est en source uniquement, et il est signalé
comme `BREAKING CHANGE` dans le commit.

### (d) API et CLI

`List<Path>.toFitCourse(name, startTime, sport, interPathGap = ZERO)` et le `toFitBytes`
correspondant ; l'extension mono-`Path` délègue à `listOf(this)`, ce que le cas 05 vérifie par
égalité de `FitCourse`. `enhance --fit` **et** `export --fit` écrivent désormais toutes les
pistes — ils n'encodaient que `first()` et perdaient silencieusement les autres.

### Vérification

- 16 nouveaux tests : 6 (`PathToFitTimestampTest`) + 10 (`PathToFitMultiTest`) en commonTest × 3
  cibles, plus 3 round-trips décodés par le SDK Java (`FitRoundTripTest` 10-12) et 1 cas CLI.
- Le cas CLI décode le fichier produit et compte les laps et les events. `com.garmin:fit` a été
  ajouté en `testImplementation` de `:cli` pour ça : il était déjà sur le classpath d'exécution
  via `:fit`, la ligne le rend visible à la compilation des tests. Assertion structurelle plutôt
  que sur la taille du fichier.
- `./gradlew check` + `ktlintCheck` verts.

## Notes

- **Type de commit.** Le rebasage change la sortie FIT pour les paths dont `time(0) != 0` — qui
  produisaient jusqu'ici une date aberrante. C'est un `fix:`, pas un `feat!:` : personne ne peut
  dépendre d'un fichier daté de 2083. Le multi-path est un `feat:`. Deux commits séparés.
- **Pourquoi ne pas normaliser `Path` à la source.** Faire que `GpxToPath` produise du temps
  relatif corrigerait le symptôme ici mais casserait `GpxDocument.startTime` (g05) et le
  round-trip GPX horodaté. Le contrat « `Path.time` est absolu si parsé, relatif si virtualisé »
  est bancal mais assumé depuis g05 ; c'est au sérialiseur de s'en protéger, ce que fait le
  rebasage.
- **`interPathGap` n'est pas une pause.** FIT distingue `totalElapsedTime` de `totalTimerTime`
  précisément pour ça. Un vrai support des pauses supposerait des events `TIMER/PAUSE`, hors
  périmètre — le documenter pour couper court.
