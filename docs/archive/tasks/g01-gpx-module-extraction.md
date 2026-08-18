# g01 — Extraction du module `:gpx`

## Goal

Sortir le modèle de données (`Path`, `PointField`, resamplers, simplifier) et l'I/O GPX de
`:engine` vers un nouveau module KMP `:gpx`, sans **aucune** rupture pour les consommateurs
existants (`@glandais/vcyclist-engine` en npm, `io.github.glandais:vcyclist-engine` en Maven
Central, la démo Vue).

Tâche bloquante : toute la phase B, C et D en dépend.

**Décision structurante — les noms de packages ne changent pas.** `Path` reste
`io.github.glandais.engine.path.Path`, `GpxParser` reste `io.github.glandais.engine.gpx.GpxParser`.
Seul le module Gradle qui les héberge change. En Kotlin, package ≠ module : c'est la seule
façon d'avoir zéro `import` cassé. Un renommage éventuel vers `io.github.glandais.gpx.*` sera
un breaking change à part entière, avec `typealias` de transition, dans une version majeure.

## Depends on

Rien. Première tâche du plan.

## Inputs

- `engine/build.gradle.kts` (modèle à dupliquer pour `:gpx`)
- `elevation/build.gradle.kts` (module KMP publié le plus simple)
- `settings.gradle.kts`
- `codegen/` (sa sortie doit être redirigée)

## Steps

### 1. Créer le module

`settings.gradle.kts` :

```kotlin
include(":elevation", ":gpx", ":engine", ":codegen", ":demo")
```

`gpx/build.gradle.kts` : copie de `engine/build.gradle.kts` en adaptant :

- `outputFileName = "gpx.js"`
- `customField("name", "@glandais/vcyclist-gpx")` / `"@glandais/vcyclist-gpx-wasm"`
- `commonMain.dependencies` : `kotlinx-coroutines-core`, `xmlutil-core`, `xmlutil-serialization`,
  `api(project(":elevation"))` — `Path` utilise `Coordinates`, `DouglasPeucker` et
  `ElevationSmoother` de `:elevation`.
- **Pas** de tâche `run` (elle reste dans `:engine` jusqu'à g18).

### 2. Déplacer les sources

Avec `git mv`, en préservant l'arborescence de packages :

| Depuis `engine/src/…` | Vers `gpx/src/…` |
|---|---|
| `commonMain/…/engine/path/**` | idem |
| `commonMain/…/engine/gpx/**` | idem |
| `commonTest/…/engine/path/**` | idem |
| `commonTest/…/engine/gpx/**` | idem |

Soit : `Path.kt`, `GeneratedPath.kt`, `PointField.kt`, `PointFieldAccessors.kt`,
`PointFieldCategory.kt`, `PointPerDistance.kt`, `PointPerSecond.kt`, `PathSimplifier.kt`,
`ElevationStep.kt`, et les 5 fichiers de `gpx/`.

⚠ `ElevationStep.kt` est à la frontière : il orchestre `fixElevation`/`smoothElevation` sur un
`Path` via un `ElevationProvider`. Il ne dépend d'aucune physique → il part dans `:gpx`.

Restent dans `:engine` : `Bike`, `Course`, `CoursePhysics`, `Cyclist`, `EngineConstants`,
`EnhanceOptions`, `Enhancer`, tout `physics/`, `EngineCli`, les façades JS/Wasm.

### 3. Rebrancher `:engine`

`engine/build.gradle.kts`, `commonMain.dependencies` :

```kotlin
api(project(":gpx"))   // réexporte Path + GPX I/O : compat source pour les consommateurs
// `api(project(":elevation"))` devient transitif via :gpx, mais on le garde explicite
// puisque `physics/` et `Enhancer` utilisent directement Coordinates.
api(project(":elevation"))
```

### 4. Rediriger `:codegen`

`codegen/` écrit `GeneratedPath.kt` et `PointFieldAccessors.kt`. Mettre à jour les chemins de
sortie vers `gpx/src/commonMain/kotlin/io/github/glandais/engine/path/`, ainsi que
l'en-tête de régénération dans `GeneratedPath.kt` et `codegen/README.md`.

Vérifier que `./gradlew :codegen:run` régénère à l'identique (diff vide).

### 5. Trancher le packaging npm

Deux options, à évaluer en construisant réellement les bundles :

**(a) `:gpx` non publié en npm.** Retirer `binaries.library()` / `packageJson` / `npmPublish*`
de `gpx/build.gradle.kts`. Le code de `:gpx` est inliné dans le bundle `engine.js`. Un seul
package npm, aucun changement pour la démo. C'est **l'option par défaut**.

**(b) `:gpx` publié.** `@glandais/vcyclist-engine` déclare `@glandais/vcyclist-gpx` en
dépendance. Bundles plus petits pour qui ne veut que le parsing, mais deux packages à
versionner en phase.

Retenir (a) sauf si le bundle `engine.js` grossit de façon inacceptable. Documenter le choix
dans les Notes de cette fiche **et** dans `docs/publishing.md`.

### 6. Vérifier la non-régression de la façade JS

`EngineJsApi.kt` (jsMain et wasmJsMain) exporte `parseGpx`, `writeGpx`, `pathSize`,
`pathTotalDistance`, `pointAt`, `getField`, `fieldDefinitions`… Ces fonctions doivent rester
exportées **depuis `:engine`** et garder des signatures identiques, même si les types
manipulés viennent maintenant de `:gpx`.

Régénérer les `.d.ts` et les differ contre la version précédente : le diff doit être vide.

## Outputs

Créés :

- `gpx/build.gradle.kts`
- `gpx/src/{commonMain,commonTest}/kotlin/io/github/glandais/engine/{path,gpx}/**` (déplacés)

Modifiés :

- `settings.gradle.kts`, `engine/build.gradle.kts`
- `codegen/` (chemins de sortie) + `codegen/README.md`
- `docs/publishing.md` (nouveau module, choix de packaging)
- `README.md` (tableau des modules)
- `CLAUDE.md` (section « Project overview » : décrire `:gpx`)

## Validation

```bash
./gradlew check
./gradlew :gpx:allTests :engine:allTests :elevation:allTests
./gradlew :codegen:run && git diff --exit-code   # régénération idempotente
./gradlew ktlintCheck
./gradlew :engine:jsBrowserProductionLibraryDistribution
./gradlew :demo:assemble
```

Critères :

- Tous les tests existants passent, **sans modification d'un seul test** hors déplacement de
  fichier. Si un test doit changer, c'est que l'extraction n'est pas neutre.
- Le diff des `.d.ts` générés est vide.
- `:demo:assemble` produit un site fonctionnel — charger un GPX, cliquer Enhance.

## Done when

- [x] Module `:gpx` créé et inclus dans `settings.gradle.kts`
- [x] Sources déplacées par `git mv`, packages inchangés
- [x] `:engine` fait `api(project(":gpx"))`
- [x] `:codegen` régénère dans `:gpx`, sortie idempotente
- [x] Choix de packaging npm tranché et documenté
- [x] Diff des `.d.ts` vide
- [x] `./gradlew check` vert, `ktlintCheck` vert
- [x] `:demo:assemble` vert et démo fonctionnelle manuellement
- [x] `README.md`, `CLAUDE.md`, `docs/publishing.md` à jour

## Résultat

**Packaging npm : option (a) retenue.** `:gpx` n'a ni `binaries.library()`, ni `packageJson`,
ni tâche `npmPublish*`. Son code JS est émis dans le bundle `:engine` sous
`engine/build/dist/js/productionLibrary/vcyclist-gpx.js` — un seul package npm, aucun
changement pour la démo. `:gpx` **est** publié sur Maven Central (`vcyclist-gpx`), obligatoire
puisque le POM de `:engine` le référence via `api(project(":gpx"))` (vérifié :
`engine/build/publications/jvm/pom-default.xml` contient `vcyclist-gpx-jvm` en scope
`compile`). Documenté dans `docs/publishing.md`.

**`GpxFixtures.kt` : répertoire de sources partagé.** Les tests de parité et de façade JS de
`:engine` utilisent les mêmes chaînes GPX que les tests de `:gpx`, or KMP n'a pas d'équivalent
à `java-test-fixtures`. Le fichier vit donc dans `gpx/src/commonTestFixtures/kotlin/` et **les
deux** `build.gradle.kts` ajoutent ce répertoire à leur source set `commonTest` via
`kotlin.srcDir(…)`. Un seul fichier sur le disque, deux compilations de test. C'est le seul
écart au plan initial de la fiche.

**Non-régression vérifiée :**

| Contrôle | Résultat |
|---|---|
| `.d.ts` JS + `.d.mts` Wasm avant / après | **identiques octet pour octet** |
| `package.json` npm généré (JS et Wasm) | **identiques octet pour octet** |
| Tests JVM `:gpx` + `:engine` | 131 + 201 = **332**, soit exactement le total d'avant |
| `./gradlew check` (4 cibles) + `ktlintCheck` | verts |
| `./gradlew :codegen:run` | diff vide (régénération idempotente) |
| `:demo:assemble` + chargement de `stelvio.gpx` dans le navigateur | profil altitude + vitesse simulée affichés |

Aucun test n'a été modifié — uniquement déplacé. `:tools:parity` n'existe pas dans
`settings.gradle.kts` (la note ci-dessous est un reliquat de la rédaction du plan).

## Notes

- **Faire l'extraction en un seul commit `refactor:`**, sans changement de comportement. Un
  `refactor:` ne déclenche pas de release chez semantic-release, ce qui est correct ici : rien
  ne change pour les consommateurs.
- **Ne pas en profiter pour nettoyer.** Toute amélioration de code repérée pendant le
  déplacement va dans une tâche séparée. Un refactor de déplacement doit rester diffable.
- **`:tools:parity`** dépend de `:engine` — vérifier qu'il compile toujours (il est inclus dans
  `settings.gradle.kts` et sert au harnais de parité TS).
- **Ordre de `git mv`** : déplacer d'abord les tests, lancer la compilation pour voir les
  erreurs, puis les sources. Ça donne un signal clair sur les dépendances oubliées.
