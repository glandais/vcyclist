# g22 — Ponts JVM pour les API `suspend`

## Goal

`ElevationStep.fixElevation(source, provider)` est `suspend`. Depuis Java, l'appeler signifie
instancier une `Continuation` à la main — inutilisable en pratique. Le problème n'est pas propre
à `fixElevation` : il touche toute la chaîne asynchrone de la bibliothèque
(`Enhancer.enhanceCourse*`, `ElevationProvider.getElevation` / `setElevations`).

Un consommateur Java doit pouvoir appeler ces trois surfaces sans écrire une ligne de Kotlin.

## Depends on

- `g21` recommandé mais non bloquant (les ponts d'`ElevationProvider` sont indépendants du
  découpage `fetch`/`decode`).

## Inputs

- `gpx/src/commonMain/…/path/ElevationStep.kt` (`fixElevation` est `suspend`, `smoothElevation` non)
- `engine/src/commonMain/…/Enhancer.kt` (`enhanceCourses`, `enhanceCourseDefault`, `enhanceCourse`)
- `elevation/src/commonMain/…/ElevationProvider.kt` (`getElevation`, `setElevations`)
- `cli/src/main/kotlin/…/command/EnhanceCommand.kt:242` — `runBlocking` déjà en place, même dépendance

## Steps

### 1. Convention de nommage

Deux formes par fonction, appliquées **identiquement partout** — une convention qu'on devine
vaut mieux qu'une API qu'on doit relire :

| Forme | Suffixe | Retour | Pour qui |
|---|---|---|---|
| Bloquante | `Blocking` | `T` | Batch, CLI, tests |
| Asynchrone | `Async` | `CompletableFuture<T>` | Serveur, UI |

### 2. Surface à couvrir

Trois nouveaux fichiers, un par module, tous en `jvmMain` :

```kotlin
// gpx/src/jvmMain/…/path/ElevationStepJvm.kt
fun ElevationStep.fixElevationBlocking(source: Path, provider: ElevationProvider): Path
fun ElevationStep.fixElevationAsync(
    source: Path,
    provider: ElevationProvider,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
): CompletableFuture<Path>
```

Idem pour `EnhancerJvm.kt` (`enhanceCourseBlocking`, `enhanceCourseDefaultBlocking`,
`enhanceCoursesBlocking` + variantes `Async`) et `ElevationProviderJvm.kt`
(`getElevationBlocking`, `setElevationsBlocking` + variantes `Async`).

Ce sont des **fonctions d'extension** dans le même package, pas des méthodes ajoutées aux
objets : la surface commune reste inchangée, et rien de JVM-only n'entre dans `commonMain`.

### 3. Points de vigilance

- **Dépendances.** `runBlocking` vient de `kotlinx-coroutines-core`, déjà présent. Pour la
  variante `Async`, vérifier que `kotlinx.coroutines.future.future` est bien fourni par la
  version 1.11 du core (le module `jdk8` y est fusionné depuis 1.7). Sinon, ajouter
  `kotlinx-coroutines-jdk8` en dépendance **`jvmMain` du seul module concerné**, jamais en
  `commonMain`.
- **Pas de `GlobalScope`.** Un appel réseau lancé sur un scope non annulable est un piège côté
  serveur. Utiliser un `CoroutineScope(dispatcher + SupervisorJob())` local, et **propager
  l'annulation** : annuler la `CompletableFuture` doit annuler la coroutine, ce qui n'est pas
  automatique — le brancher explicitement via `whenComplete` / `invokeOnCompletion`.
- **KDoc.** Écrire noir sur blanc qu'appeler une variante `Blocking` sur un thread d'UI ou dans
  une coroutine est une faute. C'est le seul garde-fou possible : aucune vérification statique
  ne l'attrapera.
- **Exceptions.** La variante `Blocking` propage telle quelle ; la variante `Async` complète la
  future exceptionnellement (`CompletionException` enveloppante) — le documenter, c'est la
  première question que se posera l'appelant.

### 4. Documentation

Ajouter une section « Utilisation depuis Java » au `README.md` racine : un exemple complet
parse → `fixElevationBlocking` → `enhanceCourseBlocking` → écriture GPX, en Java. Sans cette
section, les ponts resteront invisibles.

## Outputs

Créés :

- `gpx/src/jvmMain/kotlin/io/github/glandais/engine/path/ElevationStepJvm.kt`
- `engine/src/jvmMain/kotlin/io/github/glandais/engine/EnhancerJvm.kt`
- `elevation/src/jvmMain/kotlin/io/github/glandais/elevation/ElevationProviderJvm.kt`
- `gpx/src/jvmTest/java/io/github/glandais/engine/path/ElevationStepJavaTest.java` (**source Java**)

Modifiés :

- `README.md` — section « Utilisation depuis Java »
- `gpx/build.gradle.kts`, `engine/build.gradle.kts`, `elevation/build.gradle.kts` si une
  dépendance `jvmMain` s'avère nécessaire

## Validation

```bash
./gradlew :gpx:jvmTest :engine:jvmTest :elevation:jvmTest
./gradlew check          # les 3 cibles : rien ne doit avoir fui dans commonMain
./gradlew ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `fixElevationBlocking` depuis Java, provider bouchonné | path identique à la version `suspend` |
| 2 | `enhanceCourseBlocking` depuis Java | mêmes métriques que le test Kotlin équivalent |
| 3 | `setElevationsBlocking` sur 500 points | résultat identique, pas de deadlock |
| 4 | `enhanceCourseAsync(…).cancel(true)` | coroutine annulée, aucun thread résiduel |
| 5 | Provider qui lève | `Blocking` propage l'exception ; `Async` complète exceptionnellement |
| 6 | Appel `Async` avec dispatcher explicite | s'exécute bien dessus (assertion sur le nom du thread) |
| 7 | `./gradlew :engine:jsNodeTest` et `:engine:jsBrowserTest` | inchangés — rien de JVM n'a fui en commonMain |

## Done when

- [x] `Blocking` + `Async` sur `fixElevation`, `Enhancer` (×3) et `ElevationProvider` (×2)
- [x] Annulation de la `CompletableFuture` propagée à la coroutine, testée
- [x] Aucune dépendance JVM ajoutée à un `commonMain`
- [x] Tests écrits **en Java**, verts
- [x] Section « Utilisation depuis Java » dans le `README.md`
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### API

Trois fichiers `jvmMain`, 11 fonctions :

- `ElevationProviderJvm` (`:elevation`) — `getElevationBlocking`, `setElevationsBlocking`,
  `getElevationAsync`, `setElevationsAsync`. Fonctions d'**extension** sur `ElevationProvider` :
  la classe est instanciable, le receveur se lit naturellement depuis Java
  (`ElevationProviderJvm.getElevationBlocking(provider, lat, lon)`).
- `ElevationStepJvm` (`:gpx`) — `fixElevationBlocking`, `fixElevationAsync`.
- `EnhancerJvm` (`:engine`) — `enhanceCourseBlocking`, `enhanceCourseDefaultBlocking`,
  `enhanceCoursesBlocking` + les trois `Async`.

### Écarts assumés par rapport au spec

- **Top-level + `@file:JvmName`, pas des extensions, pour `ElevationStep` et `Enhancer`.** Ce sont
  des `object`, et une extension sur un `object` s'appelle
  `ElevationStepJvmKt.fixElevationBlocking(ElevationStep.INSTANCE, …)` depuis Java — exactement la
  friction que cette tâche existe pour supprimer. Java voit maintenant une classe utilitaire
  statique : `ElevationStepJvm.fixElevationBlocking(path, provider)`. Les extensions sont
  conservées là où le receveur est une vraie instance (`ElevationProvider`).
- **`Executor?` plutôt que `CoroutineDispatcher`** dans les signatures `Async`. Deux raisons : un
  appelant Java a des `Executor`, pas des dispatchers ; et `kotlinx-coroutines-core` est déclaré
  en `implementation` dans les `commonMain`, donc l'exposer en signature publique aurait imposé la
  dépendance au classpath de compilation des consommateurs. `null` (défaut) = `Dispatchers.IO`.
- **`@JvmOverloads` posé dès maintenant** sur ces 11 fonctions, alors que g27 traite le sujet
  globalement : un pont dont les paramètres optionnels ne sont pas appelables depuis Java ne sert
  à rien. g27 n'aura pas à y revenir.

### Décisions de conception

- **`kotlinx.coroutines.future.future` est bien dans le core 1.11** — vérifié dans le jar
  (`kotlinx/coroutines/future/FutureKt.class`, le module `jdk8` y est fusionné depuis 1.7).
  Aucune dépendance ajoutée, ni en `jvmMain` ni ailleurs.
- **Pas de `GlobalScope`** : chaque `Async` crée un `CoroutineScope(dispatcher + SupervisorJob())`
  local et passe par `scope.future { … }`, qui câble l'annulation dans les deux sens — annuler la
  future annule la coroutine. C'est testé, pas supposé (cas 4).
- **Helper `jvmFuture` privé, dupliqué trois fois.** Six lignes par module ; le partager aurait
  demandé soit une fonction publique existant uniquement pour être partagée, soit une dépendance
  inter-modules pour rien.
- **Tests JUnit 4, pas JUnit 5.** Les modules KMP n'appellent pas `useJUnitPlatform()`, donc
  `kotlin("test")` y résout `kotlin-test-junit` : les sources Java doivent utiliser `org.junit`.
  Noté dans `CLAUDE.md` § *Testing* pour la prochaine fois.
- **Les fixtures des tests Java sont en Kotlin** (`JvmBridgeFixtures`) : le `fetcher` d'un
  `ElevationProvider` est un `suspend (String) -> RawTile`, qui n'a pas de littéral Java. Le fait
  même que la fixture doive être écrite en Kotlin est une illustration du problème que la tâche
  traite.

### Vérification

- 15 tests, tous en **source Java** : `ElevationProviderJavaTest` (7),
  `ElevationStepJavaTest` (3), `EnhancerJavaTest` (4), `ReadmeJavaSnippetTest` (1).
- Le test d'annulation attend que la coroutine soit *entrée* dans le fetch (`CompletableDeferred`)
  avant d'annuler, puis vérifie que le `catch (CancellationException)` a bien été traversé — pas
  de `sleep` arbitraire, pas de faux vert possible.
- `ReadmeJavaSnippetTest` compile et exécute l'exemple Java du `README.md`. Un exemple qui ne
  compile pas est pire que pas d'exemple, et c'est le seul moyen de le savoir : il a d'ailleurs
  fallu corriger le nombre d'arguments de `GpxWriter.write` en l'écrivant.
- `./gradlew check` + `ktlintCheck` verts. Les cibles JS/Node et JS/navigateur sont inchangées :
  rien de JVM n'a fui dans un `commonMain` (cas 7).

### Bénéfice inattendu

Écrire les tests en Java a immédiatement produit deux illustrations concrètes pour **g27** :
`GpxParser.parse(xml)` et `new LatLon(lat, lon)` ne compilent pas depuis Java faute de
`@JvmOverloads`, et les tests doivent épeler `parse(xml, true)` / `new LatLon(lat, lon, null)`.
Ces appels sont commentés comme tels dans les sources — ils deviendront le premier diff de g27.

## Notes

- **Pourquoi ne pas dé-suspendre l'API.** `fixElevation` fait des I/O réseau ; la rendre
  bloquante en commonMain serait un recul pour les cibles JS, où il n'existe pas de
  `runBlocking`. Le pont JVM est la seule réponse correcte.
- **Périmètre.** `smoothElevation`, `PathSimplifier`, les resamplers et tout `:fit` sont déjà
  synchrones : rien à faire pour eux.
- **Kotlin coroutines depuis Java** reste possible directement pour qui le veut ; ces ponts ne
  ferment aucune porte, ils en ouvrent une plus courte.
