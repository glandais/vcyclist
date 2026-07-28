# PLAN-GPX2WEB — Port des fonctionnalités gpx2web vers vcyclist

Plan **distinct** de [`PLAN.md`](PLAN.md) (tâches 00-39, port des références TypeScript).
Celui-ci couvre le port des fonctionnalités qui n'existent que dans la référence Java
[`../gpx2web/`](https://github.com/glandais/gpx2web), en vue de déprécier ce dépôt.

Numérotation `g01`…`g20`, fiches dans [`tasks/`](tasks/), pour ne pas collisionner avec la
numérotation existante.

## Pourquoi ce plan

`elevation` et `virtual-cyclist` (TS) sont fonctionnellement portés dans vcyclist. `gpx2web`
(Java) ne l'est que partiellement : sa physique a servi d'inspiration au port TS, mais toute
sa couche outillage (FIT, cols, cartes, exports tabulaires, CLI) n'a jamais eu d'équivalent.

Ce plan porte ce qui a été retenu, et **acte explicitement ce qui ne le sera pas**.

## Architecture cible

```
vcyclist/
├─ :elevation   KMP   inchangé
├─ :gpx         KMP   Path + PointField + GPX I/O + waypoints + repair + exports CSV/JSON
├─ :engine      KMP   physique + Enhancer + détection de cols   →  api(project(":gpx"))
├─ :fit         KMP   com.garmin:fit (JVM) / @garmin/fitsdk (JS)
├─ :map         JVM   cartes statiques (java.awt / ImageIO)
├─ :cli         JVM   picocli — remplace gpxtools-cli
├─ :codegen     JVM   génère désormais dans :gpx
└─ :demo              Vue/Vite — ajout de l'onglet cols
```

Les briques JVM-only (`:map`, `:cli`) sont **isolées dans leurs propres modules** pour que le
cœur (`:elevation`, `:gpx`, `:engine`, `:fit`) reste compilable sur les 3 cibles (à l'époque de
ce plan, 4 cibles avec Kotlin/Wasm ; la cible a depuis été retirée du projet).

## Avancement

| # | Tâche | Module | État |
|---|---|---|---|
| **— Phase A : restructuration (bloquante) —** | | | |
| g01 | Extraction du module `:gpx` (Path + GPX I/O) | `:gpx` | ✅ |
| **— Phase B : modèle GPX —** | | | |
| g02 | Multi-track / multi-segment de bout en bout | `:gpx` | ✅ |
| g03 | Waypoints `<wpt>` | `:gpx` | ✅ |
| g04 | `GpxXmlRepair` — réparation des GPX malformés | `:gpx` | ✅ |
| **— Phase C : exports —** | | | |
| g05 | `startTime: Instant?` — horodatage absolu à l'écriture | `:gpx` | ✅ |
| g06 | Writer CSV (36 champs `PointField`) | `:gpx` | ✅ |
| g07 | Writer JSON | `:gpx` | ✅ |
| **— Phase D : FIT —** | | | |
| g08 | Bootstrap `:fit` + `expect`/`actual` + implémentation JVM | `:fit` | ✅ |
| g09 | Implémentation JS + Wasm (`@garmin/fitsdk`) | `:fit` | ✅ |
| g10 | Encodage Course/Lap/Records + tests round-trip | `:fit` | ✅ |
| **— Phase E : cols —** | | | |
| g11 | Port de `ClimbDetector` | `:engine` | ✅ |
| g12 | Façade `@JsExport` + intégration démo | `:engine` `:demo` | ✅ |
| **— Phase F : cartes —** | | | |
| g13 | Projection + `MapImage` | `:map` | ✅ |
| g14 | `TileMapProducer` — tuiles + cache | `:map` | ✅ |
| g15 | `SRTMMapProducer` — carte hypsométrique PNG | `:map` | ✅ |
| **— Phase G : CLI —** | | | |
| g16 | Bootstrap picocli + mixins | `:cli` | ✅ |
| g17 | Sous-commandes `enhance` / `export` | `:cli` | ✅ |
| g18 | Retrait d'`EngineCli` + documentation | `:cli` `:engine` | ✅ |
| **— Phase H : clôture —** | | | |
| g19 | Publication des nouveaux artefacts (npm + Maven Central) | tous | ✅ |
| g20 | Matrice de correspondance gpx2web → vcyclist | docs | ✅ |
| **— Phase I : retours de migration —** | | | |
| g21 | `TileFetcher` — séparer téléchargement et décodage | `:elevation` | ✅ |
| g22 | Ponts JVM `Blocking` / `Async` pour les API `suspend` | `:gpx` `:engine` `:elevation` | ⬜ |
| g23 | Option d'écriture des `<extensions>` GPX | `:gpx` `:cli` | ⬜ |
| g24 | Lecture / écriture des GPX `<rte>` / `<rtept>` | `:gpx` | ⬜ |
| g25 | FIT multi-`Path` + contrat de timestamp | `:fit` `:cli` | ⬜ |
| g26 | Port de `GPXDataComputer.getWind` | `:engine` | ⬜ |
| g27 | `@JvmOverloads` sur l'API publique | tous | ⬜ |

La **phase I** n'était pas au plan initial : elle rassemble les points bloquants remontés par la
première migration réelle d'un projet consommateur (appelant **Java**) de gpx2web vers vcyclist.
Cinq des sept fiches (g21, g22, g23, g25, g27) ne comblent pas un trou fonctionnel mais un trou
d'**appelabilité** — l'API était juste, elle n'était pas utilisable sans réécrire du code interne
ou du boilerplate. Les deux autres sont des écarts de parité constatés à l'usage : `<rte>`
(g24, lu par gpx2web, silencieusement ignoré ici) et `getWind` (g26, dont le refus de portage
reposait sur un « aucun consommateur » démenti depuis).

> g09 a livré `@garmin/fitsdk` sur JS **et** Kotlin/Wasm. La cible Kotlin/Wasm a depuis été
> retirée du projet (Kotlin/Wasm n'est pas WASI et a de toute façon besoin d'un runtime JS, ce
> que Kotlin/JS couvre déjà) ; `:fit` ne compile plus que pour JVM, JS Node et JS browser.

## Décisions actées

| Sujet | Décision |
|---|---|
| Écriture FIT | Module KMP `:fit`. JVM → `com.garmin:fit:21.205.0` (Maven Central). JS → `@garmin/fitsdk` (classe `Encoder`). Interface `expect` **haut niveau** (`Path` → `ByteArray`), les deux SDK n'ayant aucune API commune. |
| Détection de cols | commonMain, plus façade JS et affichage dans la démo. |
| Cartes statiques | Port JVM-only fidèle (`java.awt`), module `:map` isolé. |
| Exports tabulaires | CSV + JSON en commonMain. **XLSX abandonné.** |
| Modèle GPX | Multi-track/segment + waypoints + `GpxXmlRepair`. |
| Heure de départ | Paramètre `startTime: Instant?` explicite en commonMain. Pas de résolution automatique de fuseau. |
| CLI | Module `:cli` JVM-only sous picocli, remplace `gpxtools-cli`. |
| Compatibilité | `:engine` fait `api(project(":gpx"))` **et les noms de packages ne changent pas** → aucune rupture pour les consommateurs existants. Bump mineur. |

## Explicitement non porté

Ces éléments de gpx2web n'ont **pas** d'équivalent prévu dans vcyclist. La liste est
normative : si l'un d'eux redevient nécessaire, il faut une nouvelle tâche.

| Élément gpx2web | Raison |
|---|---|
| `virtual/power/cyclist/OptimalSpeedService` + `OptimalSpeeds` | Abandonné (décision produit). |
| Export XLSX (`ProcessCommand --xlsx`) | Le CSV couvre le besoin tableur ; Apache POI est JVM-only et lourd. |
| ~~`util/GPXDataComputer` (`isCrossing`, `getWind`)~~ | ~~Aucun consommateur identifié hors webapp.~~ **Réouvert** : `getWind` est porté par **g26** (le consommateur existe, il est apparu à la migration). Le sort d'`isCrossing` est tranché au démarrage de cette fiche. |
| `data/FastTimeIndex` | Index de recherche par temps, utilisé uniquement côté webapp. |
| `virtual/StartTimeProvider` + dépendance `timeshape` | Remplacé par un `Instant` explicite (g05) ; ~50 Mo de données de fuseaux pour un défaut « demain 8 h ». |
| `data/values/**` (`PropertyKey`, `PropertyKeys`, `Unit`, `Converters`) | Remplacé par `PointField` (nom + unité + catégorie déjà portés). |
| `srtm/**`, `srtm/mapterhorn/**` | Déjà couvert par `:elevation`. |
| **`gpx-web` (webapp Quarkus)** + `PowerCurvePowerProvider` | **Hors périmètre.** |

## Conséquence sur la dépréciation

**À la fin de ce plan, gpx2web ne peut pas être archivé.** Les modules `gpx` et
`gpxtools-cli` auront un remplaçant complet, mais `gpx-web` — la webapp Quarkus déployée —
reste sans équivalent. Son sort fera l'objet d'une décision séparée.

La matrice classe par classe qui l'établit est
[`docs/gpx2web-coverage.md`](gpx2web-coverage.md) : 104 classes Java, une ligne chacune, plus
les divergences de comportement assumées et les options pour le sort de la webapp.

## Risques identifiés

- ~~**g01 / packaging npm**~~ — **tranché** : `:gpx` n'est pas publié en npm, son code JS est
  inliné dans le bundle `@glandais/vcyclist-engine` (`vcyclist-gpx.js`). Il l'est en revanche
  sur Maven Central, puisque le POM de `:engine` le référence via `api(project(":gpx"))`.
  `.d.ts` et `package.json` générés vérifiés identiques avant / après. Cf.
  [`docs/publishing.md`](publishing.md#why-gpx-ships-to-maven-central-but-not-to-npm).
- ~~**g02 / rupture de la façade JS**~~ — **évité** : `parseGpx` est restée intacte et
  `parseGpxTracks` / `parseGpxSegments` / `writeGpxTracks` ont été ajoutées à côté. Le diff des
  `.d.ts` contre g01 est purement additif, la démo n'a pas bougé d'une ligne.
- ~~**g09 / `@garmin/fitsdk` en Wasm**~~ — **levé** : le paquet se chargeait et tournait en Karma
  headless Chrome sur les deux cibles web (JS et Kotlin/Wasm). Pas d'`externals.js` : contrairement
  à `@jsquash/webp` c'est du JavaScript pur, donc il est bundlé normalement et ressort en
  dépendance npm épinglée des paquets publiés. La cible Kotlin/Wasm a depuis été retirée du
  projet ; ce risque ne concerne plus que la cible JS restante.
- ~~**Phase F / `java.awt`**~~ — **levé pour g13** : `:map` utilise le plugin `kotlin-jvm`, n'a
  pas de `commonMain`, et rien ne dépend de lui (vérifié). `./gradlew check` reste vert et
  multi-cibles sur le cœur. Le bloc `mavenPublishing` fonctionne tel quel sur un module non-KMP.
- ~~**g19 / licence du SDK Garmin**~~ — **tranché, non bloquant** : vcyclist ne redistribue aucun
  octet de Garmin. Le SDK est publié par Garmin sur les deux registres (`com.garmin:fit`,
  `@garmin/fitsdk`) et vcyclist n'en déclare que la *coordonnée*. Portée plus large qu'annoncé
  jusqu'ici, et documentée comme telle : la dépendance n'est pas jvmMain-only, et comme `:engine`
  fait `api(project(":fit"))`, tout `npm install @glandais/vcyclist-engine` tire `@garmin/fitsdk`
  en transitif. Détail et réserves dans
  [`docs/publishing.md`](publishing.md#fit-and-the-garmin-sdk-licence--the-decision).
- ~~**g14 / politique d'usage des tuiles**~~ — **traité** : user-agent explicite
  `vcyclist (https://github.com/glandais/vcyclist)`, vérifié envoyé par un test contre un serveur
  HTTP local ; **aucune URL de tuiles par défaut**, ni en production ni dans le test
  d'intégration ; cache permanent ; aucun test unitaire ne touche le réseau. Politique documentée
  dans [`map/README.md`](../map/README.md).

## Ordre d'exécution

`g01` est bloquante. Ensuite :

- **B et C sont séquentielles** (C écrit ce que B modélise).
- **D dépend de C** (g05 : les FIT exigent des timestamps absolus).
- **E est indépendante** — peut être menée en parallèle dès g01 terminée.
- **F est indépendante** — ne dépend que de `Path`, donc de g01.
- **G dépend de C, D et F** (le CLI expose tous les formats de sortie).
- **H clôture.**
- **I vient après H** et ses sept fiches sont largement parallélisables, à deux contraintes
  près : **g27 en dernier** (il annote des signatures que g23, g24 et g25 modifient) et **g23
  avant g24** (les deux touchent `GpxWriter.kt`).

## Workflow

Une fiche `tasks/gNN-*.md` par tâche, implémentation, validation (`./gradlew check` +
`ktlintCheck` verts sur toutes les cibles), cases cochées, mise à jour de la colonne `État`
ci-dessus, puis **un seul commit** regroupant code et documentation :

```
feat(<module>): <sujet> (gpx2web task gNN)
```

(`refactor(...)` plutôt que `feat(...)` quand la tâche ne change rien pour les consommateurs
— c'était le cas de g01 : semantic-release n'a alors rien à publier.)

La colonne `État` est l'unique source de vérité de l'avancement : pas de compteur, pas de
hash de commit à recopier (`git log --grep 'gpx2web task'` les retrouve).

## Références gpx2web à lire

Chemins relatifs à `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/` :

| Tâche | Sources de référence |
|---|---|
| g03 | `data/GPXWaypoint.java`, `io/read/GPXFileReader.java`, `io/write/GPXFileWriter.java` |
| g04 | `io/read/GpxXmlRepair.java` |
| g06 | `io/write/tabular/{CSVFileWriter,TabularFileWriter,TabularCellWriter}.java` |
| g07 | `io/write/JsonFileWriter.java` |
| g08-g10 | `io/write/FitFileWriter.java` |
| g11 | `climb/*.java`, `util/Simplifier.java`, `util/Vector.java` |
| g13-g15 | `map/*.java`, `util/MagicPower2MapSpace.java` |
| g16-g18 | `../gpxtools-cli/src/main/java/io/github/glandais/**` |
| g23 | `io/write/GPXFileWriter.java` — paramètre `boolean extensions` (lignes 83, 128, 153) |
| g24 | `io/read/GPXFileReader.java` (lignes 150-175), `data/GPXPathType.java` |
| g25 | `io/write/FitFileWriter.java` — boucle sur `gpx.paths()`, `EventMesg` (lignes 25-70) |
| g26 | `util/GPXDataComputer.java` (`getWind`, `getWindUnscaled`), `util/Vector.java` |
