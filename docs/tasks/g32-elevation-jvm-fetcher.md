# g32 — Fabrique JVM acceptant un fetcher de tuiles

## Goal

`ElevationProvider(config, fetcher)` prend un `suspend (String) -> RawTile`. Le KDoc de
`ElevationProviderJvm.kt` (g27) tranche la question ainsi :

> Les factories donnent les deux formes qu'un appelant Java peut vouloir — tous les défauts, ou
> une configuration personnalisée — et **s'arrêtent délibérément là : injecter un fetcher revient à
> écrire une lambda suspendue, ce qui est le travail de Kotlin.**

C'était juste au moment de g27, faute d'appelant. Ça ne l'est plus : la migration du backend
Quarkus (Java pur) de gpx2web vers vcyclist a besoin d'exactement ça, et pour une raison qui n'a
rien de marginal — **c'est là que se branche le cache disque des tuiles DEM**. gpx2web en avait un,
via un SPI `CacheFolderProvider` ; sans point d'injection Java, le consommateur perd son cache et
retélécharge chaque tuile à chaque redémarrage, contre un service tiers gratuit.

g21 a fourni la moitié du chemin (`fetchTileBytes` / `decodeTileBytes` publics, précisément pour
que « mon cache + votre décodeur » soit possible) et sa note de clôture le disait déjà :

> **Le cache n'est pas fourni.** vcyclist reste sans I/O disque en commonMain ; cette tâche donne
> la prise, pas l'implémentation.

Cette fiche ne fournit toujours pas le cache. Elle rend la prise **atteignable depuis Java**.

Même motif que les cinq fiches d'appelabilité de la phase I : l'API est juste, elle n'est pas
utilisable sans écrire du Kotlin qu'un consommateur Java n'a aucune raison d'introduire dans son
build.

## Depends on

- `g21` (livrée) — `fetchTileBytes` / `decodeTileBytes` publics : c'est ce qui rend le corps du
  fetcher côté appelant trivial (consulter le cache, sinon `fetchTileBytesBlocking`, puis
  `decodeTileBytesBlocking`).
- `g22` (livrée) — `TileFetcherJvm` : les ponts bloquants que ce fetcher appellera.
- `g27` (livrée) — `ElevationProviderJvm.kt` et ses conventions de fabrique. Cette fiche **révise
  une décision de g27**, il faut donc en corriger le KDoc, pas seulement ajouter du code.

Indépendante de tout le reste.

## Inputs

- `elevation/src/jvmMain/kotlin/io/github/glandais/elevation/ElevationProviderJvm.kt` — les
  fabriques existantes et le KDoc à réviser
- `elevation/src/commonMain/kotlin/io/github/glandais/elevation/ElevationProvider.kt` — la
  signature du paramètre `fetcher`, et `TileManager` qui l'appelle
- `elevation/src/commonMain/kotlin/io/github/glandais/elevation/RawTile.kt`
- `elevation/src/jvmMain/kotlin/io/github/glandais/elevation/TileFetcherJvm.kt` — ce que le
  fetcher de l'appelant utilisera
- `elevation/README.md` — l'exemple canonique « cache disque maison » (g21), aujourd'hui en Kotlin
- `README.md` racine, section *« Use from Java »* — verrouillée par `ReadmeJavaSnippetTest`
- `elevation/src/jvmTest/java/` — les tests Java existants (JUnit **4**, cf. `CLAUDE.md`)

## Steps

### 1. Deux surcharges explicites, pas `@JvmOverloads`

```kotlin
fun newElevationProvider(fetcher: Function<String, RawTile>): ElevationProvider

fun newElevationProvider(
    config: ElevationProviderConfig,
    fetcher: Function<String, RawTile>,
): ElevationProvider
```

**`@JvmOverloads` est un piège ici et il faut le dire dans le code.** Il génère les surcharges en
retirant les paramètres *de fin* : sur un `newElevationProvider(config = défaut, fetcher)` il
produirait un `newElevationProvider(config)` qui perd le paramètre requis **et** entre en collision
avec la fabrique déjà existante. Le paramètre à défaut est en tête, le paramètre requis en queue :
c'est exactement la forme que `@JvmOverloads` ne sait pas traiter. Deux surcharges écrites à la
main, donc.

Le type est `java.util.function.Function<String, RawTile>` et non une interface maison : un
consommateur Java a déjà des lambdas et des références de méthode, il n'a pas besoin d'un nom de
type supplémentaire à apprendre. `RawTile` est déjà public et constructible depuis Java
(`RawTile(width, height, rgba)`, tous paramètres requis).

### 2. Le fetcher de l'appelant est bloquant — l'isoler

C'est le point d'attention de la tâche. Un `Function<String, RawTile>` fait des I/O (lecture
disque, HTTP) et n'a aucun moyen de suspendre. L'adapter naïvement en

```kotlin
ElevationProvider(config) { url -> fetcher.apply(url) }
```

le ferait tourner **sur le thread appelant de la coroutine**. Or `BatchCalculator` parallélise par
`maxParallelTiles = 10` : dix appels bloquants sur un dispatcher non prévu pour ça, c'est au mieux
une sérialisation silencieuse, au pire une famine. L'adaptation doit être

```kotlin
ElevationProvider(config) { url -> withContext(Dispatchers.IO) { fetcher.apply(url) } }
```

À documenter dans le KDoc de la fabrique, côté contrat pour l'appelant : *le fetcher peut bloquer,
il est appelé sur `Dispatchers.IO`, et il peut l'être depuis plusieurs threads à la fois — donc il
doit être thread-safe.* La concurrence est une vraie contrainte, pas une précaution de style : un
cache disque naïf qui écrirait directement dans le fichier de destination corromprait des tuiles.
L'exemple du README doit donc montrer l'écriture atomique (fichier temporaire puis `Files.move`),
et pas seulement le chemin heureux.

### 3. Réviser le KDoc de g27

Le paragraphe cité en *Goal* devient faux. Le remplacer par ce que la fabrique couvre désormais, et
par ce qui reste hors de portée : **un fetcher *suspendu* reste du ressort de Kotlin** (un
consommateur Kotlin qui a déjà un client HTTP non bloquant n'a aucune raison de passer par
`Dispatchers.IO`). La ligne de partage se déplace de « fetcher » à « fetcher suspendu », elle ne
disparaît pas.

Corriger aussi la ligne correspondante de la section *« Ce qui n'est pas couvert, et pourquoi »* de
[`g27`](g27-jvm-overloads.md) — une fiche livrée qui décrit un état révisé par une fiche ultérieure
doit pointer vers elle, sinon c'est le genre de trace qu'on relit dans six mois en la croyant à jour.

### 4. Documentation

- `elevation/README.md` — l'exemple « cache disque maison » de g21 est en Kotlin. Lui adjoindre la
  variante Java, qui est le cas d'usage qui a motivé la fiche : `Function<String, RawTile>` +
  `TileFetcherJvm.fetchTileBytesBlocking` + `TileFetcherJvm.decodeTileBytesBlocking` + écriture
  atomique.
- `README.md` racine, section *« Use from Java »* — ajouter la fabrique à la liste des jumelles
  `…Jvm`. Attention : le snippet est compilé et exécuté par `ReadmeJavaSnippetTest`, donc tout ce
  qu'on y écrit doit tenir debout sans réseau.

## Outputs

Modifiés :

- `elevation/src/jvmMain/…/ElevationProviderJvm.kt`
- `elevation/README.md`
- `README.md` (section *Use from Java*)
- `docs/tasks/g27-jvm-overloads.md` (renvoi depuis la section *Ce qui n'est pas couvert*)
- `docs/PLAN-GPX2WEB.md` (colonne `État`)

Créés :

- `elevation/src/jvmTest/java/…/ElevationProviderFetcherJavaTest.java`

## Validation

```bash
./gradlew :elevation:jvmTest
./gradlew check
./gradlew ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `newElevationProvider(fetcher)` depuis Java, fetcher servant des tuiles en mémoire | élévations correctes, **zéro requête réseau** |
| 2 | `newElevationProvider(config, fetcher)` avec un `tileUrlTemplate` maison | l'URL reçue par le fetcher suit le gabarit configuré |
| 3 | Le fetcher n'est appelé qu'une fois pour deux points de la même tuile | le `LruCache` de `TileManager` reste devant — les deux niveaux ne se recouvrent pas |
| 4 | Fetcher qui lève | l'exception remonte inchangée à travers `getElevationBlocking` |
| 5 | Fetcher qui dort ~50 ms, `setElevationsBlocking` sur des points couvrant ≥ 4 tuiles | durée totale nettement inférieure à la somme — preuve que `Dispatchers.IO` parallélise (étape 2) |
| 6 | Fetcher comptant ses appels concurrents | maximum observé > 1, et cohérent avec `maxParallelTiles` |
| 7 | Compilation Java du snippet README | `ReadmeJavaSnippetTest` vert |

Les cas 5 et 6 sont ceux qui distinguent l'implémentation correcte de l'adaptation naïve — sans
eux, un `fetcher.apply(url)` nu passerait la validation.

## Done when

- [x] Les deux surcharges `newElevationProvider(…, Function<String, RawTile>)` compilent et sont
      appelées depuis un test **en source Java**
- [x] Le fetcher est invoqué sur `Dispatchers.IO`, la parallélisation est mesurée (cas 5 et 6)
- [x] Contrat de thread-safety documenté dans le KDoc de la fabrique
- [x] KDoc de g27 révisé, fiche g27 pointant vers celle-ci
- [x] Exemple Java dans `elevation/README.md`, avec écriture atomique
- [x] `README.md` racine à jour, `ReadmeJavaSnippetTest` vert
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### API

```kotlin
fun newElevationProvider(fetcher: Function<String, RawTile>): ElevationProvider
fun newElevationProvider(config: ElevationProviderConfig, fetcher: Function<String, RawTile>): ElevationProvider
```

Deux surcharges écrites à la main, pour la raison que la fiche donnait : `@JvmOverloads` retire
les paramètres **de fin**, or ici le paramètre à défaut (`config`) est en tête et le paramètre
requis (`fetcher`) en queue. Il aurait produit un `newElevationProvider(config)` amputé du fetcher
et en collision avec la fabrique de g27. C'est écrit dans le KDoc, à l'endroit où quelqu'un serait
tenté de « simplifier ».

### Le point qui comptait : l'isolement du blocage

L'adaptation est `withContext(Dispatchers.IO) { fetcher.apply(url) }`, pas `fetcher.apply(url)`.

Ce n'est pas une précaution théorique, et le test le prouve dans les deux sens. Avec la version
naïve, temporairement mise en place pour vérifier que le test mord :

```
expected overlapping fetches, peak was 1
```

Quatre tuiles, un fetcher qui dort 200 ms, `maxParallelTiles = 10` : la concurrence observée
tombe à **1** et la durée totale devient la somme au lieu du maximum. Un test qui ne vérifierait
que le résultat des élévations passerait sans rien remarquer.

Le contrat correspondant est documenté côté appelant : le fetcher **peut bloquer** (il tourne sur
`Dispatchers.IO`), et il **doit être thread-safe** — d'où l'écriture atomique (fichier temporaire
puis `Files.move`) dans l'exemple du README, plutôt que le chemin heureux.

### La ligne de partage s'est déplacée, elle n'a pas disparu

Le KDoc de g27 disait « injecter un fetcher revient à écrire une lambda suspendue, ce qui est le
travail de Kotlin ». Il dit maintenant : un fetcher **bloquant** est un `Function` et se branche
depuis Java ; un fetcher **suspendu** reste du ressort de Kotlin — un appelant qui a déjà un client
HTTP non bloquant n'a aucune raison d'être routé par `Dispatchers.IO`. La section « Ce qui n'est
pas couvert » de la fiche g27 est barrée et pointe ici.

### Vérification

- 6 cas dans `ElevationProviderFetcherJavaTest`, **en source Java**, aucun réseau : le gabarit
  d'URL pointe sur un schéma que rien ne sait résoudre, donc un appel réseau serait un échec et
  non un test lent.
- Le cas de parallélisme a été validé **par contre-épreuve** : implémentation naïve mise en place,
  test rouge, implémentation correcte remise, test vert.
- Le cas 3 vérifie que le `LruCache` de `TileManager` reste devant : deux points d'une même tuile
  n'appellent le fetcher qu'une fois. Les deux niveaux de cache sont complémentaires, pas
  redondants.
- `./gradlew check` + `ktlintCheck` verts, `ReadmeJavaSnippetTest` compris.

## Notes

- **Pourquoi pas un cache disque livré par la bibliothèque.** La position de g21 tient toujours :
  vcyclist n'a pas d'I/O disque en commonMain, et un cache est fait de choix que la bibliothèque
  n'a pas à faire à la place de l'appelant (emplacement, éviction, partage entre processus,
  disposition des répertoires). Le consommateur qui a motivé cette fiche veut d'ailleurs
  précisément **réutiliser la disposition héritée de gpx2web** (`{cache}/mapterhorn/{z}/{x}/{y}.webp`)
  pour ne pas jeter son cache de production — ce qu'un cache imposé lui interdirait.
- **Alternative écartée : laisser l'appelant implémenter `Function2<String, Continuation<? super RawTile>, Object>`.**
  Ça fonctionne (une fonction suspendue qui ne suspend jamais peut rendre sa valeur directement),
  et ça ne demande que `kotlin-stdlib`, déjà présent. Mais ça fait reposer le code d'un
  consommateur Java sur un détail de la convention d'appel du compilateur Kotlin, et ça ne
  résoudrait pas l'étape 2 : rien ne l'amènerait à découvrir qu'il doit lui-même se placer sur un
  pool d'I/O.
- **`elevationProviderConfig` n'expose toujours pas `attribution`** (5ᵉ champ de
  `ElevationProviderConfig`). Hors périmètre ici, mais c'est le prochain trou de la même famille si
  quelqu'un le rencontre.
