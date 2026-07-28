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

- [x] Forme de façade `jvmMain` tranchée sur un cas témoin (surcharges explicites vs `@JvmOverloads` délégant)
- [x] P0 et P1 couverts ; P2 couvert (`:map`), `Enhancer` déjà fait en g22
- [x] Exclusions techniques listées dans « Résultat »
- [x] `JavaInteropTest.java` + `FitJavaInteropTest.java` verts
- [x] Question du `Builder` d'`EnhanceOptions` tranchée sur la base d'un besoin constaté
- [x] Aucun corps de fonction modifié
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### Forme retenue

Un fichier `…Jvm.kt` par type source, en `jvmMain`, même package, `@file:JvmName`, contenant des
fonctions **top-level** annotées `@JvmOverloads` qui délèguent. Vérifié sur un témoin
(`GpxWriterJvm`) avant de dérouler : `javap` montre bien l'échelle de surcharges attendue, de
`write(Path)` à `write(Path, String, String, Instant, boolean)`.

C'est la forme la plus courte des deux envisagées — une déclaration par cible au lieu d'une
échelle écrite à la main — et elle prolonge la convention posée par g22 (`ElevationProviderJvm`,
`ElevationStepJvm`, `EnhancerJvm`), qui devient donc la convention unique pour toute la surface
Java du projet.

### Ce qui est couvert

| Module | Fichiers |
|---|---|
| `:gpx` | `GpxParserJvm`, `GpxWriterJvm`, `GpxFromPathJvm`, `GpxModelJvm`, `PathSimplifierJvm`, `TabularWritersJvm`, + `smoothElevation` ajouté à `ElevationStepJvm` |
| `:elevation` | `TileFetcherJvm` (bridges + défaut de `sourceUrl`), + factories `newElevationProvider` / `elevationProviderConfig` / `latLon` dans `ElevationProviderJvm` |
| `:engine` | `EngineModelJvm` (`bike`, `cyclist`, `enhanceOptions`, `simplifyPathOptions`), `ClimbDetectorJvm` |
| `:fit` | `PathToFitJvm` |
| `:map` | `@JvmOverloads` **direct** sur `MapImage.ofMaxSize` / `ofZoom` / `ofSize`, `TileMapProducer.createTileMap`, `SrtmMapProducer.createSrtmMap` ; `MapFactoriesJvm` pour les constructeurs |

`Enhancer` n'apparaît pas : g22 l'avait déjà couvert, `@JvmOverloads` compris.

### Trois découvertes, dont deux pièges

1. **Une façade peut masquer l'original.** La première version de `GpxFromPathJvm` exposait
   `pathsToGpxDocument(paths, name, trackNames, waypoints, startTime)` — mêmes nom, package et
   signature que la fonction commune. Résultat : pour tout appelant **Kotlin** compilé sur la
   cible JVM, la résolution choisissait la façade, qui s'appelait elle-même. `GpxWriterTest`
   case 24 est mort sur un `StackOverflowError`. Renommée en `toGpxDocument`, surchargée sur le
   type du receveur. Consigné dans `CLAUDE.md`.
2. **`@JvmOverloads` sur un constructeur coûte cher en diff.** ktlint impose alors de passer le
   constructeur à la ligne, ce qui ré-indente tout le corps de la classe : mesuré à **~1 000
   lignes** de bruit pour quatre annotations dans `:map`. Les fonctions n'ont pas ce problème.
   D'où `MapFactoriesJvm` pour les quatre classes concernées, et l'annotation directe partout
   ailleurs.
3. **`val` dans un `object` est illisible depuis Java** : `MathConstants.INSTANCE.getDEG_TO_RAD()`.
   Trouvé en écrivant `FitJavaInteropTest`, qui ne compilait pas. `DEG_TO_RAD`, `RAD_TO_DEG` et
   `DEFAULT_MAX_LEAN_ANGLE_RAD` sont passés en `const val` — trois `public static final double`
   côté Java, aucun changement côté Kotlin.

### Ce qui n'est pas couvert, et pourquoi

- **`FitRecord` / `FitLap` / `FitCourse`** : constructeurs à défauts, mais un appelant Java qui
  assemble un `FitRecord` à la main court-circuite `PathToFit`, c'est-à-dire toute la logique du
  module. Pas de factory tant que ce cas d'usage n'existe pas.
- ~~**`ElevationProvider(config, fetcher)`**~~ — **révisé par [g32](g32-elevation-jvm-fetcher.md)** :
  le constat (« le second paramètre est un `suspend (String) -> RawTile`, qui n'a pas de littéral
  Java ») était juste, la conclusion trop large. Un consommateur Java est apparu — le backend
  Quarkus, qui y branche son cache disque de tuiles —, et une fabrique prenant un
  `Function<String, RawTile>` bloquant règle son cas. La ligne de partage s'est déplacée de
  « fetcher » à « fetcher **suspendu** ».
- **`copy()` des data classes** : jamais couvert par `@JvmOverloads`, ni par une factory. La fiche
  demandait de trancher la question du `Builder` d'`EnhanceOptions` **sur la base d'un besoin
  constaté** : aucun ne l'est à ce jour, donc pas de `Builder`. La factory `enhanceOptions(...)`
  couvre la construction ; c'est la modification d'une instance existante qui reste verbeuse.
- **`GeneratedPath.kt`** : hors périmètre comme prévu (code généré, accesseurs).

### Vérification

- **13 tests en source Java** : `JavaInteropTest` (9, `:gpx`) et `FitJavaInteropTest` (4, `:fit`),
  plus les 15 de g22 qui continuent de passer. Chaque test compare la **forme courte** à la forme
  longue explicite, de sorte qu'une façade qui passerait un défaut différent échouerait ici.
- `ReadmeJavaSnippetTest` (g22) a été mis à jour : le snippet Java du `README.md` passe par les
  façades et perd ses trois `null` de remplissage — c'est la démonstration la plus directe du
  gain.
- `./gradlew check` + `ktlintCheck` verts. Aucun corps de fonction commune modifié : le diff sur
  `commonMain` se limite à trois `val` → `const val`.

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
