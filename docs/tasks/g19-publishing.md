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

- [x] Inventaire des artefacts figé
- [x] Non-rupture vérifiée **en pratique**, côté Maven et côté npm
- [x] `mavenPublishing` factorisé, `:map` (non-KMP) inclus
- [x] Licence du SDK Garmin tranchée avant publication de `:fit`
- [x] Workflow de release couvrant tous les modules
- [x] `docs/publishing.md` à jour
- [x] Badges README à jour

## Résultat

### Non-rupture — vérifiée, les deux côtés passent

**Maven.** Projet Gradle jetable hors du dépôt, `mavenLocal()`, dépendance unique
`io.github.glandais:vcyclist-engine:1.2.1`, code écrit avec les imports d'avant g01
(`io.github.glandais.engine.path.Path`, `…gpx.GpxParser`, `…gpx.GpxWriter`, `Enhancer`).
Compile et s'exécute sans modification : `3 in -> 2 out, 636 chars of GPX`. `vcyclist-gpx`
arrive bien en transitif sans être nommé.

**npm.** `3 in -> 1021 out, 2001.5 m`, GPX valide. Deux pièges, tous deux consignés dans
`docs/publishing.md` parce qu'ils invalident la vérification sans la faire échouer franchement :

1. **Installer un tarball, pas un lien.** `npm install <build-dir>` crée un lien symbolique et
   Node résout les `require` du paquet depuis le *realpath*, donc dans `engine/build/` où les
   dépendances ne sont pas. Échec en `Cannot find module '@garmin/fitsdk'` qui ne dit rien du
   vrai paquet. `npm pack` puis installation du `.tgz` reproduit fidèlement une install registre.
2. **Le bundle UMD n'a qu'un seul export de premier niveau, `io`.** Les imports nommés ne
   fonctionnent pas — et le README en documentait quatre. Corrigés en g19 (voir plus bas).

### Licence Garmin — tranchée : non bloquante

vcyclist ne redistribue **aucun octet** de Garmin, sur aucune cible. Garmin publie le SDK
lui-même sur les deux registres (`com.garmin:fit`, `@garmin/fitsdk`) ; vcyclist déclare une
*coordonnée* que le résolveur du consommateur va chercher chez Garmin. Le §2.c du FIT Protocol
License Agreement vise la redistribution, pas la déclaration de dépendance sur un paquet que le
concédant a lui-même publié.

Deux faits que la note précédente de `fit/build.gradle.kts` donnait faux, corrigés : la
dépendance n'est **pas** jvmMain-only (`npm(...)` pour JS et Wasm), et comme `:engine` fait
`api(project(":fit"))`, **tout `npm install @glandais/vcyclist-engine` tire `@garmin/fitsdk` en
transitif**, que le consommateur écrive du FIT ou non. Vérifié en installant le tarball dans un
projet vide. Conclusion inchangée, portée plus large — d'où la documentation explicite.

Ce n'est pas un avis juridique, et Maven Central est irréversible.

### `:fit` sur npm — suppression recommandée, pas appliquée

`:fit` ne déclare **aucun `@JsExport`** : `@glandais/vcyclist-fit` et `-fit-wasm` publieraient
un bundle sans API publique atteignable. La façade `pathToFit` vit dans `:engine` (un handle
`Path` ne traverse pas une frontière de bundle), et le JS de `:fit` voyage déjà *dans*
`@glandais/vcyclist-engine`. `:fit:npmPublishJs`/`npmPublishWasm` restent dans `publishCmd` —
les retirer est une édition d'une ligne et ne casse aucun chemin d'import documenté.

### Corrections de documentation trouvées par la vérification

- **README, 4 extraits JS faux** : imports nommés qui n'ont jamais fonctionné. Remplacés par la
  déviation de namespace unique (`engineRaw.io.github.glandais.engine`), celle que
  `demo/src/engine-shim.ts` fait déjà.
- **Nom du jar CLI** : le README annonçait `vcyclist-cli-*-all.jar`, Gradle produisait
  `cli-1.2.1-all.jar`. Corrigé du côté Gradle (`archiveBaseName`), puisque ce jar est désormais
  attaché à la release GitHub et téléchargé seul.

### Wiring

- `mavenPublishing` factorisé dans le `build.gradle.kts` racine (5 copies supprimées). Signature
  conditionnée à la présence d'une clé, sinon `publishToMavenLocal` échoue hors CI. Piège
  rencontré : dans `pom { }`, `name` désigne le POM, pas le projet — capturer `val moduleName =
  name` **avant** le bloc, faute de quoi l'artefact s'appelle `vcyclist-property 'name'`.
- `:map` ajouté à `publishCmd` ; le bloc racine s'applique tel quel à un module `kotlin-jvm`
  (risque relevé en g13 : levé, `jar` + `pom` + `module` + sources + javadoc générés).
- `:cli:executableJar` construit pendant `publishCmd` et attaché à la release GitHub via les
  `assets` de `@semantic-release/github`.
- Tous les modules publient en `1.2.1`, aucun en `unspecified`.

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
