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
├─ :fit         KMP   com.garmin:fit (JVM) / @garmin/fitsdk (JS + Wasm)
├─ :map         JVM   cartes statiques (java.awt / ImageIO)
├─ :cli         JVM   picocli — remplace gpxtools-cli
├─ :codegen     JVM   génère désormais dans :gpx
└─ :demo              Vue/Vite — ajout de l'onglet cols
```

Les briques JVM-only (`:map`, `:cli`) sont **isolées dans leurs propres modules** pour que le
cœur (`:elevation`, `:gpx`, `:engine`, `:fit`) reste compilable sur les 4 cibles.

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
| g11 | Port de `ClimbDetector` | `:engine` | ⬜ |
| g12 | Façade `@JsExport` + intégration démo | `:engine` `:demo` | ⬜ |
| **— Phase F : cartes —** | | | |
| g13 | Projection + `MapImage` | `:map` | ⬜ |
| g14 | `TileMapProducer` — tuiles + cache | `:map` | ⬜ |
| g15 | `SRTMMapProducer` — profil d'élévation PNG | `:map` | ⬜ |
| **— Phase G : CLI —** | | | |
| g16 | Bootstrap picocli + mixins | `:cli` | ⬜ |
| g17 | Sous-commandes `process` / `virtualize` / `export` | `:cli` | ⬜ |
| g18 | Retrait d'`EngineCli` + documentation | `:cli` `:engine` | ⬜ |
| **— Phase H : clôture —** | | | |
| g19 | Publication des nouveaux artefacts (npm + Maven Central) | tous | ⬜ |
| g20 | Matrice de correspondance gpx2web → vcyclist | docs | ⬜ |

## Décisions actées

| Sujet | Décision |
|---|---|
| Écriture FIT | Module KMP `:fit`. JVM → `com.garmin:fit:21.205.0` (Maven Central). JS + Wasm → `@garmin/fitsdk` (classe `Encoder`). Interface `expect` **haut niveau** (`Path` → `ByteArray`), les deux SDK n'ayant aucune API commune. |
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
| `util/GPXDataComputer` (`isCrossing`, `getWind`) | Aucun consommateur identifié hors webapp. |
| `data/FastTimeIndex` | Index de recherche par temps, utilisé uniquement côté webapp. |
| `virtual/StartTimeProvider` + dépendance `timeshape` | Remplacé par un `Instant` explicite (g05) ; ~50 Mo de données de fuseaux pour un défaut « demain 8 h ». |
| `data/values/**` (`PropertyKey`, `PropertyKeys`, `Unit`, `Converters`) | Remplacé par `PointField` (nom + unité + catégorie déjà portés). |
| `srtm/**`, `srtm/mapterhorn/**` | Déjà couvert par `:elevation`. |
| **`gpx-web` (webapp Quarkus)** + `PowerCurvePowerProvider` | **Hors périmètre.** |

## Conséquence sur la dépréciation

**À la fin de ce plan, gpx2web ne peut pas être archivé.** Les modules `gpx` et
`gpxtools-cli` auront un remplaçant complet, mais `gpx-web` — la webapp Quarkus déployée —
reste sans équivalent. Son sort fera l'objet d'une décision séparée.

## Risques identifiés

- ~~**g01 / packaging npm**~~ — **tranché** : `:gpx` n'est pas publié en npm, son code JS est
  inliné dans le bundle `@glandais/vcyclist-engine` (`vcyclist-gpx.js`). Il l'est en revanche
  sur Maven Central, puisque le POM de `:engine` le référence via `api(project(":gpx"))`.
  `.d.ts` et `package.json` générés vérifiés identiques avant / après. Cf.
  [`docs/publishing.md`](publishing.md#why-gpx-ships-to-maven-central-but-not-to-npm).
- ~~**g02 / rupture de la façade JS**~~ — **évité** : `parseGpx` est restée intacte et
  `parseGpxTracks` / `parseGpxSegments` / `writeGpxTracks` ont été ajoutées à côté. Le diff des
  `.d.ts` contre g01 est purement additif, la démo n'a pas bougé d'une ligne.
- ~~**g09 / `@garmin/fitsdk` en Wasm**~~ — **levé** : le paquet se charge et tourne en Karma
  headless Chrome sur les deux cibles web. Pas d'`externals.js` : contrairement à
  `@jsquash/webp` c'est du JavaScript pur, donc il est bundlé normalement et ressort en
  dépendance npm épinglée des paquets publiés.
- **Phase F / `java.awt`** — première dépendance non-multiplateforme du dépôt. D'où le module
  isolé : `./gradlew check` doit rester multi-cibles sur le cœur.
- **g14 / politique d'usage des tuiles** — gpx2web déclare un `USER_AGENT` explicite. Toute
  source de tuiles OSM impose une politique d'usage ; la reprendre telle quelle.

## Ordre d'exécution

`g01` est bloquante. Ensuite :

- **B et C sont séquentielles** (C écrit ce que B modélise).
- **D dépend de C** (g05 : les FIT exigent des timestamps absolus).
- **E est indépendante** — peut être menée en parallèle dès g01 terminée.
- **F est indépendante** — ne dépend que de `Path`, donc de g01.
- **G dépend de C, D et F** (le CLI expose tous les formats de sortie).
- **H clôture.**

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
