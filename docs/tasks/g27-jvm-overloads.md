# g27 — `@JvmOverloads` sur l'API publique

## Goal

Le projet ne contient **aucun** `@JvmOverloads` (vérifié : `grep -rn JvmOverloads --include=*.kt`
→ 0 résultat). Tout paramètre à valeur par défaut est donc obligatoire depuis Java.

Exemple : `PathSimplifier.simplify(path, toleranceM, zExaggeration = 3.0)` impose à l'appelant
Java de connaître et de recopier `3.0` — c'est-à-dire de dupliquer, dans son code, une constante
que la bibliothèque possède déjà. Le jour où elle change, les deux divergent silencieusement.

Le problème est systémique : une trentaine de fonctions publiques sont concernées.

## Depends on

- **`g23`, `g24`, `g25`** — obligatoire. Ces trois fiches ajoutent ou modifient des signatures
  (`writeExtensions`, `kind`, `List<Path>.toFitCourse`). Annoter avant reviendrait à repasser.
- `g21`, `g22` recommandés pour la même raison (`decodeTileBytes(bytes, sourceUrl = "")`, ponts
  JVM), mais les conflits y sont mineurs.

Dernière fiche de la série `g21`-`g27`.

## Inputs

- `gpx/src/commonMain/…/path/{PathSimplifier,ElevationStep}.kt`
- `gpx/src/commonMain/…/gpx/{GpxWriter,GpxFromPath,GpxXmlRepair}.kt`
- `gpx/src/commonMain/…/io/{CsvWriter,JsonWriter,CsvNumberFormat}.kt`
- `elevation/src/commonMain/…/{ElevationProvider,TileManager,BatchCalculator,ElevationFunctions,TileFetcher}.kt`
- `engine/src/commonMain/…/{Enhancer,Bike}.kt`, `…/physics/VirtualizeService.kt`, `…/climb/ClimbDetector.kt`
- `fit/src/commonMain/…/PathToFit.kt`
- `map/src/main/kotlin/…/{MapImage,TileMapProducer,SrtmMapProducer}.kt`

## Steps

### 1. ~~Vérifier la faisabilité~~ — **tranché en g23 : `@JvmOverloads` est hors jeu en `commonMain`**

La question de départ était de savoir si `kotlin.jvm.JvmOverloads` est résoluble depuis un source
set commun en Kotlin 2.3.21. **Elle ne l'est pas** — vérifié en g23, où la première écriture de
`GpxWriter.write` la portait :

```
e: GpxWriter.kt:46:6 Unresolved reference 'JvmOverloads'.
```

Contrairement à `@JvmStatic` et `@JvmName`, elle n'a pas de déclaration commune. Toute l'API de
`commonMain` — c'est-à-dire **tout le périmètre P0 et P1** — est donc concernée par le repli, qui
devient le plan principal :

**Des façades `jvmMain` par module, dans la lignée des ponts `…Jvm` de g22.** Pour chaque cible du
périmètre, une fonction `jvmMain` qui appelle la version commune avec les défauts. Deux formes
possibles, à trancher au démarrage :

- **surcharges explicites** (`fun write(path: Path) = write(path, "noname", null, null, true)`) —
  verbeuses mais triviales ;
- **fonctions annotées `@JvmOverloads` dans `jvmMain`**, qui délèguent à la commune — une
  déclaration par cible au lieu d'une ladder complète, l'annotation étant résoluble là.

La seconde est plus courte et se maintient mieux ; la vérifier sur un cas avant de dérouler.

Cela réoriente aussi le sens de la tâche : les fichiers `…Jvm` de g22 (`ElevationProviderJvm`,
`ElevationStepJvm`, `EnhancerJvm`) sont l'endroit naturel où ces façades atterrissent, et g27
devient « compléter la surface JVM », pas « annoter la surface commune ».

**Bénéfice de bord, constaté en g23** : une façade JVM isole aussi Java de l'ajout d'un paramètre
à défaut. Quand g23 a ajouté `writeExtensions` à `GpxWriter.write`, le test Java de g22 a cessé de
compiler (les défauts Kotlin sont positionnels et obligatoires côté Java). Avec la façade, cet
ajout serait passé inaperçu pour l'appelant Java.

Ce qui reste annotable directement : le code **JVM-only** (`:map`, `:cli`) et tout ce qui vit déjà
dans un `jvmMain`. Le P2 `:map` est donc traitable tel quel.

### 2. Périmètre, par priorité

| Priorité | Cible | Exemples |
|---|---|---|
| **P0** — points d'entrée « une ligne » | ce qu'un appelant Java écrit en premier | `PathSimplifier.simplify`, `ElevationStep.smoothElevation`, `GpxWriter.write` (×3), `toGpxTrack` / `toGpxDocument` / `pathsToGpxDocument`, `toFitCourse` / `toFitBytes`, `CsvWriter`, `JsonWriter`, `GpxXmlRepair`, `decodeTileBytes` |
| **P1** — constructeurs de configuration | `ElevationProvider`, `TileManager`, `ElevationProviderConfig`, `Bike`, `EnhanceOptions` |
| **P2** — physique et `:map` | `Enhancer.enhanceCourse*`, `ClimbDetector`, `VirtualizeService`, `MapImage`, `TileMapProducer`, `SrtmMapProducer` |

`GeneratedPath.kt` est **hors périmètre** : c'est du code généré par `:codegen`, et ses 36
fonctions à défaut sont des accesseurs qu'aucun appelant Java n'utilise sous forme courte. Si
l'annotation s'y avérait utile, c'est le générateur qu'il faudrait modifier, pas le fichier.

### 3. Règles et pièges

- Sur un **constructeur** : forme `class X @JvmOverloads constructor(…)` — **uniquement pour les
  classes JVM-only**, cf. étape 1. Pour une classe `commonMain` (`ElevationProvider`,
  `EnhanceOptions`, `Bike`), il faut une fonction de fabrique dans `jvmMain`.
- **`@JvmOverloads` ne génère rien pour le `copy()` d'une `data class`.** Pour `EnhanceOptions`,
  le vrai point de friction côté Java est probablement `copy()`, pas le constructeur. Ne pas
  construire de `Builder` par anticipation : **confirmer d'abord sur le projet consommateur** que
  la gêne est réelle, et n'ajouter le `Builder` que dans ce cas.
- Impossible sur une méthode d'**interface**, sur une `expect fun`, sur une fonction `abstract`.
  Pour les `expect`/`actual`, annoter l'`actual` JVM.
- **Lister les exclusions rencontrées** dans la section « Résultat » : c'est la seule trace qui
  évitera à quelqu'un de refaire la même tentative dans six mois.
- Ajout **rétro-compatible** en source comme en binaire : aucun bump majeur.

### 4. Le garde-fou — un test écrit en Java

`gpx/src/jvmTest/java/…/JavaInteropTest.java`, **source Java et non Kotlin** : appeler chaque
point d'entrée P0 dans sa forme la plus courte, et vérifier que le résultat est identique à la
forme longue explicite.

C'est le cœur de la tâche. Sans un test compilé par `javac`, la régression est structurellement
invisible : depuis Kotlin, tout compile de toute façon. Vérifier que le source set Java est bien
pris en compte par la cible JVM du module KMP (`withJava()` ou source set dédié selon la
configuration de `gpx/build.gradle.kts`).

Un second fichier équivalent dans `:fit` couvre `toFitBytes` (module distinct, cible P0).

## Outputs

Modifiés :

- Les fichiers listés en *Inputs*, annotations seules — **aucun changement de corps de fonction**
- `README.md` — la section « Utilisation depuis Java » de `g22` mentionne les formes courtes

Créés :

- `gpx/src/jvmTest/java/io/github/glandais/engine/JavaInteropTest.java`
- `fit/src/jvmTest/java/io/github/glandais/fit/FitJavaInteropTest.java`

## Validation

```bash
./gradlew check          # les 3 cibles : l'annotation doit être neutre hors JVM
./gradlew ktlintCheck
javap -p build/classes/kotlin/jvm/main/io/github/glandais/engine/path/PathSimplifier.class
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `JavaInteropTest` appelle chaque entrée P0 en forme courte | compile et passe |
| 2 | `PathSimplifier.simplify(path, 5.0)` depuis Java | identique à `simplify(path, 5.0, 3.0)` |
| 3 | `GpxWriter.write(path)` depuis Java | identique à la forme complète |
| 4 | `new ElevationProvider()` depuis Java | config par défaut |
| 5 | `path.toFitBytes(name, startTime)` depuis Java | identique à la forme avec `sport` |
| 6 | `javap` sur 3 classes P0 | les surcharges générées sont présentes |
| 7 | `:engine:jsNodeTest`, `:engine:jsBrowserTest` | inchangés — annotation neutre hors JVM |
| 8 | Aucun test Kotlin existant modifié | diff limité aux annotations et aux nouveaux fichiers |

## Done when

- [ ] Forme de façade `jvmMain` tranchée sur un cas témoin (surcharges explicites vs `@JvmOverloads` délégant)
- [ ] P0 et P1 annotés ; P2 annoté ou explicitement reporté avec motif
- [ ] Exclusions techniques listées dans « Résultat »
- [ ] `JavaInteropTest.java` + `FitJavaInteropTest.java` verts
- [ ] Question du `Builder` d'`EnhanceOptions` tranchée sur la base d'un besoin constaté
- [ ] Aucun corps de fonction modifié
- [ ] `./gradlew check` + `ktlintCheck` verts

## Notes

- **Pourquoi en dernier.** Cette fiche complète des signatures ; trois des six autres les
  modifient. L'inverse imposerait une seconde passe.
- **Le titre est devenu trompeur.** Depuis le constat de g23, la tâche n'annote presque rien :
  elle ajoute une surface JVM. Le renommer au démarrage (`g27-jvm-facade`) si cela aide, mais ne
  pas renuméroter — les commits et le plan référencent `g27`.
- **Ce que cette fiche révèle.** Elle et `g21`, `g22`, `g25` traitent la même cause : l'API était
  juste, mais pas *appelable* depuis un projet Java sans réécrire du code interne ou du
  boilerplate. Le test Java d'ici et le test « cache maison » de `g21` existent pour que la
  prochaine régression de ce type soit détectée par la CI, et non par une migration.
- **Coût de maintenance.** Chaque nouveau point d'entrée public à paramètre par défaut devra
  penser à l'annotation. Le mentionner dans `CLAUDE.md` § *Conventions* est probablement le
  meilleur rappel disponible — à ajouter dans cette tâche.
