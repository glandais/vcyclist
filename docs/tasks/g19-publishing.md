# g19 — Publication des nouveaux artefacts

## Goal

Publier `:gpx`, `:fit`, `:map` et `:cli` : coordonnées Maven Central, packages npm,
configuration semantic-release, et vérification qu'un consommateur existant de
`vcyclist-engine` n'a rien à changer.

## Depends on

- Toutes les tâches précédentes (g01 à g18)

## Inputs

- `docs/publishing.md` (flux de release actuel)
- `.releaserc.json` (semantic-release, `prepareCmd` qui réécrit `gradle.properties`)
- `engine/build.gradle.kts` et `elevation/build.gradle.kts` (blocs `mavenPublishing`, tâches
  `npmPublishJs` / `npmPublishWasm`)
- `.github/workflows/**`

## Steps

### 1. Inventaire des artefacts

| Module | Maven Central | npm | Note |
|---|---|---|---|
| `:elevation` | `vcyclist-elevation` | `@glandais/vcyclist-elevation` (+ `-wasm`) | existant |
| `:engine` | `vcyclist-engine` | `@glandais/vcyclist-engine` (+ `-wasm`) | existant |
| `:gpx` | `vcyclist-gpx` | selon le choix de g01 | **voir étape 2** |
| `:fit` | `vcyclist-fit` | `@glandais/vcyclist-fit` (+ `-wasm`) | nouveau |
| `:map` | `vcyclist-map` | — | JVM-only |
| `:cli` | — | — | jar exécutable en release GitHub |

### 2. Vérifier la promesse de non-rupture

C'est le critère central de cette tâche. Après publication, un projet qui dépend de
`io.github.glandais:vcyclist-engine` et importe `io.github.glandais.engine.path.Path` doit
compiler **sans modification**.

Vérifier concrètement, pas par raisonnement : créer un projet Gradle jetable hors du dépôt,
dépendre de la version publiée (ou d'un `publishToMavenLocal`), compiler du code utilisant
`Path`, `GpxParser`, `GpxWriter`, `Enhancer`. Consigner le résultat.

Faire l'équivalent côté npm : un projet Node important `@glandais/vcyclist-engine` et appelant
`parseGpx` / `enhance` / `writeGpx`.

### 3. Publication Maven Central

Le bloc `mavenPublishing` d'`engine/build.gradle.kts` est écrit pour un projet KMP. `:map` est
un projet Kotlin/JVM simple — vérifier que la configuration s'y applique (point déjà relevé en
g13) et l'adapter au besoin.

Uniformiser : factoriser le bloc commun dans un script de convention plutôt que de le recopier
dans six modules. Six copies dérivantes du même bloc de POM est un problème qui se manifestera
au pire moment.

### 4. Licence du SDK Garmin — bloquant potentiel

Point signalé en g08 : vérifier les conditions de redistribution de `com.garmin:fit` avant de
publier `:fit` sur Maven Central. gpx2web l'utilise mais **n'est pas publié sur Maven
Central**, donc le précédent ne vaut pas.

Si la licence interdit la redistribution transitive, options : publier `:fit` en marquant la
dépendance `compileOnly` et documenter que l'utilisateur doit l'ajouter lui-même, ou ne pas
publier `:fit` du tout. **Trancher avant de publier, pas après.**

### 5. semantic-release

`.releaserc.json` réécrit `gradle.properties` via `prepareCmd`. Vérifier que tous les modules
héritent bien de la version racine — ils utilisent `project.version`, donc a priori oui, mais
un nouveau module mal configuré publierait en `unspecified`.

Ajouter les tâches de publication des nouveaux modules au workflow de release.

### 6. Documentation

Mettre à jour `docs/publishing.md` : liste des artefacts, ordre de publication, procédure de
vérification post-publication, mode de distribution du jar `:cli`.

## Outputs

Créés :

- éventuellement `buildSrc/` ou un script de convention pour factoriser `mavenPublishing`

Modifiés :

- `{gpx,fit,map,cli}/build.gradle.kts`
- `docs/publishing.md`
- `.github/workflows/**`
- `README.md` (badges des nouveaux artefacts)

## Validation

```bash
./gradlew publishToMavenLocal
./gradlew :fit:jsBrowserProductionLibraryDistribution
./gradlew check
```

Puis, dans un projet jetable hors du dépôt :

```bash
# Maven : compile avec Path / GpxParser / Enhancer importés depuis vcyclist-engine
# npm   : importe @glandais/vcyclist-engine et appelle parseGpx / enhance / writeGpx
```

Critères :

- Tous les modules publient sous la version racine, aucun en `unspecified`.
- Le consommateur Maven existant compile sans modification.
- Le consommateur npm existant fonctionne sans modification.
- Question de licence du SDK Garmin tranchée et documentée.

## Done when

- [ ] Inventaire des artefacts figé
- [ ] Non-rupture vérifiée **en pratique**, côté Maven et côté npm
- [ ] `mavenPublishing` factorisé, `:map` (non-KMP) inclus
- [ ] Licence du SDK Garmin tranchée avant publication de `:fit`
- [ ] Workflow de release couvrant tous les modules
- [ ] `docs/publishing.md` à jour
- [ ] Badges README à jour

## Notes

- **La vérification de non-rupture est le cœur de la tâche.** L'engagement pris en g01
  (`:engine` réexporte `:gpx`, packages inchangés) ne vaut que s'il est testé sur un vrai
  consommateur. Le raisonnement ne suffit pas : `api` vs `implementation`, portée des
  dépendances transitives et packaging npm réservent des surprises.
- **La licence Garmin peut bloquer la publication de `:fit`.** Le découvrir après publication
  sur Maven Central est irréversible — les artefacts n'y sont pas supprimables.
- **Factoriser avant de dupliquer une sixième fois** : le bloc `mavenPublishing` fait ~30
  lignes de POM identiques par module.
- Ne pas publier tant que g20 n'est pas écrite : la matrice de correspondance fait partie de ce
  que verront les utilisateurs qui migrent.
