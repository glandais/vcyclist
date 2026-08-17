# Couverture gpx2web → vcyclist

Ce document dit, **classe par classe**, ce que fait [gpx2web](https://github.com/glandais/gpx2web)
et où se trouve l'équivalent vcyclist — ou pourquoi il n'y en a pas.

Il s'adresse d'abord à quelqu'un qui **migre depuis gpx2web** : si vous cherchez la classe que
vous utilisiez, elle est dans les tableaux ci-dessous. Pour la correspondance des **commandes**
`gpxtools-cli`, allez plutôt dans [`cli/README.md`](../cli/README.md), qui la traite option par
option.

## Comment lire les tableaux

| Statut | Signification |
|---|---|
| **porté** | Même rôle, même découpage. Le nom change parfois, l'intention non. |
| **remplacé** | Le besoin est couvert, par une conception différente. La colonne « Note » dit laquelle. |
| **déjà couvert** | Existait dans vcyclist avant ce plan, via le portage de `@glandais/elevation`. |
| **non porté** | Aucun équivalent, délibérément. La raison est **toujours** donnée. |
| **hors périmètre** | Webapp Quarkus `gpx-web` — voir la conclusion. |

Les modules Gradle vcyclist sont indiqués entre parenthèses : `:gpx`, `:engine`, `:elevation`,
`:fit`, `:map`, `:cli`. Les préfixes de package sont abrégés — `…engine.path.Path` se lit
`io.github.glandais.engine.path.Path`.

## Exhaustivité

Le garde-fou de ce document est mécanique : **une ligne par fichier `.java`** des modules `gpx`
et `gpxtools-cli` de gpx2web.

```bash
find gpx gpxtools-cli -name '*.java' -path '*/src/main/*' | wc -l
# 104
```

Total des tableaux ci-dessous : **104**. Le module `gpx-web` (10 classes de plus) est traité à
part, dans la conclusion, puisqu'il est hors périmètre du plan.

> Note : la fiche `docs/tasks/g20-*.md` annonçait 89 classes pour le module `gpx`. Le compte réel
> est 96. C'est exactement ce que l'étape de comptage sert à attraper.

---

## `gpxtools-cli` — 8 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `RootCommand` | porté | `…cli.RootCommand` (`:cli`) | |
| `FilesMixin` | porté | `…cli.mixin.FilesMixin` (`:cli`) | Le mode dossier de gpx2web est conservé pour plusieurs entrées ; avec une seule entrée la cible est prise littéralement (`OutputNaming`). |
| `BikeMixin` | porté | `…cli.mixin.BikeMixin` (`:cli`) | Valeurs par défaut tirées de `EngineConstants`, jamais recopiées — un test le vérifie. |
| `CyclistMixin` | porté | `…cli.mixin.CyclistMixin` (`:cli`) | idem |
| `CacheFolderProviderImpl` | remplacé | option `--cache` de `FilesMixin` (`:cli`) | gpx2web passe par une interface injectée ; vcyclist n'a qu'un consommateur (les tuiles), donc un chemin explicite suffit. |
| `process.ProcessCommand` | remplacé | `…cli.command.EnhanceCommand` (`:cli`) | `process` et `virtualize` sont fusionnées — voir « Divergences ». |
| `virtualize.VirtualizeCommand` | remplacé | `…cli.command.EnhanceCommand` (`:cli`) | idem |
| `export.ExportCommand` | porté | `…cli.command.ExportCommand` (`:cli`) | |

vcyclist ajoute un `WindMixin` sans équivalent gpx2web, où les options de vent sont dupliquées
dans `ProcessCommand` et `VirtualizeCommand`.

---

## `gpx.climb` — 7 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `Climb` | porté | `…engine.climb.Climb` (`:engine`) | |
| `ClimbPart` | porté | `…engine.climb.ClimbPart` (`:engine`) | |
| `ClimbDetector` | porté | `…engine.climb.ClimbDetector` (`:engine`) | Recherche bornée par `ClimbOptions.maxAnalysisPoints` — voir « Divergences ». |
| `Climbs` | remplacé | `List<Climb>` | Sous-classe de `ArrayList` sans comportement propre. |
| `ClimbParts` | remplacé | `List<ClimbPart>` | idem |
| `ClimbPoint` | remplacé | `ClimbDetector.Profile` (interne) | Le profil décimé porte aussi l'index source ; un couple `(dist, ele)` ne suffisait plus. |
| `DetectedClimb` | remplacé | interne au détecteur | Type intermédiaire, jamais exposé. |

---

## `gpx.data` — 6 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `GPXPath` | porté | `…engine.path.Path` (`:gpx`) | 36 `DoubleArray` à plat, taille fixe, contre une liste de `Point` à propriétés dynamiques. Les opérations qui changent la cardinalité construisent un nouveau `Path`. |
| `Point` | remplacé | index `i` dans un `Path` (`:gpx`) | Il n'y a pas d'objet point : `path.latitude(i)`, `path.speed(i)`. C'est le cœur du modèle — voir `CLAUDE.md`. |
| `GPX` | remplacé | `…engine.gpx.Gpx` + `List<Path>` (`:gpx`) | Le document GPX et les traces sont séparés ; `tracksAsPaths()` / `segmentsAsPaths()` font le pont. |
| `GPXWaypoint` | porté | `…engine.gpx.Gpx` (`Waypoint`) (`:gpx`) | Préservés à la lecture **et** à l'écriture depuis g03. |
| `GPXPathType` | **porté** | `GpxPathKind` + `tracksAsPaths(kinds)` / `segmentsAsPaths(kinds)` (`:gpx`) | L'énumération distinguait `<trk>` / `<trkseg>` / route ; vcyclist sépare les deux axes : `GpxPathKind` porte le conteneur (`TRACK` / `ROUTE`, task g24), et les deux fonctions le découpage segment ou piste. Les routes sont lues **et** réécrites en `<rte>`. |
| `FastTimeIndex` | **non porté** | — | Index binaire temps → point, utilisé **uniquement** par la webapp Quarkus pour répondre à une position de curseur. Aucun consommateur dans le périmètre porté. |

---

## `gpx.data.values` — 16 classes

Tout ce paquet est **remplacé par un seul type**, `…engine.path.PointField` (`:gpx`) : une
énumération de 36 champs portant nom, unité, description et catégorie, plus des accesseurs
générés par `:codegen`. gpx2web modélise la même chose avec des clés typées, des unités objets et
des convertisseurs ; vcyclist obtient la sécurité de type par la génération de code et garde des
`DoubleArray` nus à l'exécution.

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `PropertyKey` | remplacé | `PointField` (`:gpx`) | |
| `PropertyKeys` | remplacé | `PointField.entries` (`:gpx`) | |
| `PropertyKeyEnum` | remplacé | `PointFieldCategory` (`:gpx`) | |
| `unit.Unit` | remplacé | `PointField.unit: String` (`:gpx`) | Une chaîne, pas une hiérarchie : l'unité est de la métadonnée d'export, jamais de l'arithmétique. |
| `unit.DoubleUnit` | remplacé | idem | |
| `unit.AngleUnit` | remplacé | idem | Les angles sont **toujours** en radians dans `Path`. |
| `unit.SpeedUnit` | remplacé | idem | Toujours en m/s. |
| `unit.DurationUnit` | remplacé | idem | |
| `unit.InstantUnit` | remplacé | idem | |
| `converter.Converter` | remplacé | `…engine.io.CsvNumberFormat` (`:gpx`) | Le formatage n'existe qu'au moment d'exporter ; CSV et JSON le partagent pour ne jamais diverger. |
| `converter.Converters` | remplacé | idem | |
| `converter.NoopConverter` | remplacé | idem | |
| `converter.DateConverter` | remplacé | `kotlin.time.Instant` (`:gpx`) | |
| `converter.DegreesConverter` | remplacé | `…elevation.MathConstants.RAD_TO_DEG` | |
| `converter.DurationSecondsConverter` | remplacé | `CsvNumberFormat` (`:gpx`) | |
| `converter.SemiCirclesConverter` | porté | `…fit.FitUnits` (`:fit`) | Le seul convertisseur au contenu métier réel : les semicercles FIT. Testé contre une valeur calculée à la main (g08). |

---

## `gpx.filter` — 3 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `GPXPerDistance` | porté | `…engine.path.PointPerDistance` (`:gpx`) | |
| `GPXPerSecond` | porté | `…engine.path.PointPerSecond` (`:gpx`) | |
| `GPXFilter` | remplacé | `…engine.path.PathSimplifier` (`:gpx`) | Façade statique chez gpx2web ; vcyclist expose directement le simplificateur, appelé par `Enhancer`. |

---

## `gpx.io` — 12 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `GPXField` | remplacé | `PointField` + `GpxFromPath` (`:gpx`) | L'énumération mêlait champ et sérialisation ; vcyclist sépare le modèle du writer. |
| `read.GPXFileReader` | porté | `…engine.gpx.GpxParser` (`:gpx`) | Multi-cibles via xmlutil. |
| `read.GpxXmlRepair` | porté | `…engine.gpx.GpxXmlRepair` (`:gpx`) | |
| `write.GPXFileWriter` | porté | `…engine.gpx.GpxWriter` (`:gpx`) | |
| `write.FitFileWriter` | porté | `…fit.FitEncoder` + `PathToFit` (`:fit`) | 4 cibles depuis w12 : encodeur unique en `commonMain` sur `io.github.glandais:fit-kotlin-sdk` (KMP), donc JVM, JS Node, JS browser **et** wasmWasi, octet pour octet identiques. Auparavant un `expect`/`actual` sur le SDK Java et sur `@garmin/fitsdk`. Produit un **Course**, pas une Activity. Multi-`Path` depuis g25 : un `LapMesg` et une paire d'`EventMesg` `TIMER`/`START`…`STOP` par path, comme la boucle sur `gpx.paths()` du writer Java. (À l'origine 4 cibles avec Kotlin/Wasm ; la cible a été retirée depuis — Kotlin/Wasm n'est pas WASI et a besoin d'un runtime JS de toute façon, ce que Kotlin/JS couvre déjà.) |
| `write.JsonFileWriter` | porté | `…engine.io.JsonWriter` (`:gpx`) | Forme du document différente — voir « Divergences ». |
| `write.FileExporter` | remplacé | — (fonctions `write` directes) | Interface à une méthode pour trois writers ; vcyclist n'a pas de code qui les traite uniformément. |
| `write.tabular.CSVFileWriter` | porté | `…engine.io.CsvWriter` (`:gpx`) | |
| `write.tabular.TabularFileWriter` | **non porté** | — | Abstraction commune CSV / XLSX. Sans XLSX (ci-dessous), il ne reste qu'un writer, donc plus rien à abstraire. |
| `write.tabular.TabularCellWriter` | **non porté** | — | Écriture d'une cellule indépendamment du format de sortie ; sans XLSX, `CsvWriter` écrit directement. |
| `write.tabular.TabularHeadersInit` | **non porté** | — | Callback d'en-têtes de l'abstraction tabulaire, disparue avec elle. |
| `write.tabular.TabularRowInit` | **non porté** | — | Callback de ligne de l'abstraction tabulaire, disparue avec elle. |

**XLSX n'est pas porté.** L'export tabulaire de gpx2web sait écrire du XLSX via Apache POI, qui
est JVM-only et pèse plusieurs mégaoctets — inacceptable dans un module qui doit compiler pour
JS. `--csv` couvre le besoin ; `--xlsx` existe encore dans la CLI, **uniquement** pour
répondre par un message qui explique, plutôt que par « unknown option ».

---

## `gpx.map` — 4 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `MapImage` | porté | `…map.MapImage` (`:map`) | Prend une `List<Path>` au lieu du wrapper `GPX`. |
| `TileMapProducer` | porté | `…map.TileMapProducer` (`:map`) | **Aucune URL de tuiles par défaut** — voir « Divergences ». |
| `SRTMMapProducer` | porté | `…map.SrtmMapProducer` (`:map`) | |
| `TileMapImage` | remplacé | `MapImage` + `TileMapProducer` (`:map`) | gpx2web hérite pour ajouter le fond de carte ; vcyclist compose (le cadrage ne dépend pas des tuiles). |

`:map` est **JVM-only** (`java.awt` / `ImageIO`) et rien d'autre ne dépend de lui, donc
l'invariant quatre-cibles du cœur est intact.

---

## `gpx.srtm` — 11 classes

Ce paquet était **déjà couvert avant ce plan** : le module `:elevation` porte la bibliothèque
TypeScript `@glandais/elevation`, qui résout le même problème que le `gpx.srtm` de gpx2web. Les
deux implémentations sont indépendantes ; la colonne donne l'équivalent fonctionnel, pas une
traduction ligne à ligne.

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `GpxElevationProvider` | déjà couvert | `…elevation.ElevationProvider` (`:elevation`) | |
| `GPXElevationFixer` | déjà couvert | `…engine.path.ElevationStep` (`:gpx`) | Étape `fixElevation` du pipeline. |
| `mapterhorn.MapterhornElevationSource` | déjà couvert | `…elevation.ElevationCalculator` (`:elevation`) | |
| `mapterhorn.MapterhornConfig` | déjà couvert | `…elevation.ElevationProviderConfig` (`:elevation`) | |
| `mapterhorn.TileFetcher` | déjà couvert | `…elevation.TileFetcher` (`:elevation`) | `expect`/`actual` sur 3 cibles (JVM, JS Node, JS browser). |
| `mapterhorn.HttpTileFetcher` | déjà couvert | `TileFetcher.jvm/js` (`:elevation`) | |
| `mapterhorn.TerrariumDecoder` | déjà couvert | `…elevation.RawTile` (`:elevation`) | WebP décodé par TwelveMonkeys (JVM) ou `@jsquash/webp` (JS — un décodeur WASM tiers, pas la cible de compilation Kotlin/Wasm). |
| `mapterhorn.TerrainTile` | déjà couvert | `…elevation.Tile` (`:elevation`) | |
| `mapterhorn.TileCoord` | déjà couvert | `…elevation.Tiles` (`:elevation`) | |
| `mapterhorn.TileLruCache` | déjà couvert | `…elevation.LruCache` (`:elevation`) | |
| `mapterhorn.Projection` | déjà couvert | `…elevation.Coordinates` (`:elevation`) | La projection **carte** est distincte : `…map.MapSpace`. |

---

## `gpx.util` — 7 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `Vector` | déjà couvert | `…elevation.Vector3D` (`:elevation`) | |
| `Simplifier` | déjà couvert | `…elevation.DouglasPeucker` (`:elevation`) + `PathSimplifier` (`:gpx`) | Douglas-Peucker 3D. |
| `MagicPower2MapSpace` | porté | `…map.MapSpace` (`:map`) | Web Mercator, tuiles 256. |
| `Constants` | porté | `…engine.EngineConstants` (`:engine`) + `…elevation.Constants` | Séparées : les constantes physiques n'ont rien à faire dans `:elevation`. |
| `SmoothService` | **partiellement porté** | `…engine.path.ElevationStep.smoothElevation` (`:gpx`) | Seul `smoothEle` est porté (noyau 150 m, toujours actif). `smoothPower`, `smoothAeroCoef` et `smoothSpeed` **ne le sont pas** : le pipeline de référence TypeScript ne les applique pas, et les ajouter changerait les sorties par rapport à `@glandais/virtual-cyclist`, qui est la référence de parité. |
| `CacheFolderProvider` | remplacé | paramètre explicite (`:map`, `:cli`) | `TileMapProducer` reçoit son dossier de cache ; pas d'injection. |
| `GPXDataComputer` | **partiellement porté** | `Path.dominantHeadwindDirection()` (`:engine`) | `getWind` est porté sous un nom qui dit ce qu'il calcule : l'opposé de l'orientation dominante du parcours, soit le vent constant le plus défavorable en moyenne (task g26). `isCrossing` (auto-intersection de la trace) **reste non porté** : aucun consommateur, et un coût en O(n²) sur la trace simplifiée à 50 m. |

---

## `gpx.virtual` — 30 classes

### Cœur du pipeline — 6 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `GPXEnhancer` | porté | `…engine.Enhancer` (`:engine`) | Ordre des étapes aligné sur la référence TypeScript, pas sur gpx2web. |
| `VirtualizeService` | porté | `…engine.physics.VirtualizeService` (`:engine`) | |
| `Course` | porté | `…engine.Course` (`:engine`) | |
| `Cyclist` | porté | `…engine.Cyclist` (`:engine`) | |
| `Bike` | porté | `…engine.Bike` (`:engine`) | |
| `StartTimeProvider` | remplacé | paramètre `startTime: Instant?` (`:gpx`, `:cli`) | gpx2web déduit le fuseau des coordonnées via la dépendance `timeshape` (~50 Mo de géométries). vcyclist demande l'instant, ce que le format FIT exige de toute façon. |

### Vitesse maximale — 1 classe

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `maxspeed.MaxSpeedComputer` | porté | `…engine.physics.MaxSpeedComputer` (`:engine`) | Virages + freinage. |

### Intégration des puissances — 4 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `power.PowerComputer` | porté | `…engine.physics.PowerComputer` (`:engine`) | |
| `power.PowerProvider` | porté | `…engine.physics.PowerProvider` (`:engine`) | `fun interface`, donc constructible en SAM. |
| `power.PowerProviderList` | remplacé | `…engine.CoursePhysics` (`:engine`) | L'agrégation est explicite : quatre champs nommés plutôt qu'une liste. |
| `power.PowerProviderId` | remplacé | `PointField` dédiés (`:gpx`) | Chaque contribution a son champ (`pAero`, `pGravity`, …) au lieu d'être indexée par énumération. |

### Aérodynamique et vent — 7 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `power.aero.AeroPowerProvider` | porté | `…engine.physics.AeroPowerProvider` (`:engine`) | Modèle d'Isvan avec vent. |
| `power.aero.aero.AeroProvider` | porté | `…engine.physics.AeroProvider` (`:engine`) | |
| `power.aero.aero.AeroProviderConstant` | porté | `AeroProviderConstant` (`:engine`) | |
| `power.aero.wind.WindProvider` | porté | `…engine.physics.WindProvider` (`:engine`) | |
| `power.aero.wind.WindProviderConstant` | porté | `WindProvider.constant(...)` (`:engine`) | |
| `power.aero.wind.WindProviderNone` | porté | `WindProvider.none` (`:engine`) | |
| `power.aero.wind.Wind` | remplacé | couple vitesse / cap (`:engine`) | |

vcyclist ajoute `RhoProvider` / `RhoProviderEstimate` (masse volumique de l'air estimée depuis
l'altitude), qui n'a pas d'équivalent gpx2web.

### Puissance du cycliste — 9 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `power.cyclist.CyclistPowerProvider` | porté | `…engine.physics.CyclistPowerProvider` (`:engine`) | |
| `power.cyclist.CyclistPowerProviderBase` | porté | `CyclistPowerProviderBase` (`:engine`) | |
| `power.cyclist.PowerProviderConstant` | porté | `PowerProviderConstant` (`:engine`) | |
| `power.cyclist.PowerProviderConstantWithTiring` | porté puis **remplacé** | `PowerProviderDurability` (`:engine`) | La décroissance en temps écoulé n'avait aucune source ; la durabilité est pondérée par l'intensité (travail au-dessus de CP). Voir `docs/research/improvements-ledger.md` R17. |
| `power.cyclist.PowerProviderFromData` | porté | `PowerProviderFromData` (`:engine`) | CLI : `--gpx-power`. |
| `power.cyclist.MuscularPowerProvider` | porté | `MuscularPowerProvider` (`:engine`) | |
| `power.cyclist.Harmonic` | porté | `…engine.physics.Harmonic` (`:engine`) | |
| `power.cyclist.OptimalSpeedService` | **non porté** | — | Décision produit : résout la vitesse d'équilibre pour une pente et une puissance données, afin de proposer une allure « optimale ». C'est une fonctionnalité de conseil, pas de simulation ; vcyclist simule ce que fait le cycliste décrit, il ne prescrit pas. |
| `power.cyclist.OptimalSpeeds` | **non porté** | — | Cache de `OptimalSpeedService`, sans objet sans lui. |

### Résistances — 3 classes

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `power.grav.GravPowerProvider` | porté | `…engine.physics.GravPowerProvider` (`:engine`) | |
| `power.rolling.RollingResistancePowerProvider` | porté | `RollingResistancePowerProvider` (`:engine`) | |
| `power.rolling.WheelBearingsPowerProvider` | porté | `WheelBearingsPowerProvider` (`:engine`) | |

---

## Divergences de comportement

Ces écarts sont **assumés et testés**. Ils sont réunis ici pour que personne ne les découvre en
production.

### La puissance écrite dans le GPX est celle du fichier source (g30)

`toGpxTrack` écrit `pInputPower` — ce que le `<power>` du fichier d'entrée disait — et non la
puissance reconstruite par la simulation. gpx2web écrit la puissance **simulée**, non par choix
mais par construction : il n'a qu'un seul emplacement `power`, et `VirtualizeService.java:99`
écrase la valeur lue par celle du cycliste simulé. La référence TypeScript, elle, écrit bien
`pInputPower` (`GPXWriter.ts`).

vcyclist suit la référence TS **par défaut** et rend le comportement gpx2web atteignable :
`toGpxTrack(powerSource = GpxPowerSource.COMPUTED)`, ou `--gpx-power-source computed` au CLI.
Écrire une donnée simulée dans un format que tout l'écosystème lit comme un enregistrement est une
décision qui revient à l'appelant. À noter : `<power>` ne porte aucune provenance, donc un
aller-retour transforme une puissance simulée en puissance « mesurée ».

Le FIT, lui, exporte toujours `pComputedPower` : c'est le format d'un parcours simulé, et
`P_INPUT_POWER` y décrirait une autre sortie.

### Segments concaténés par défaut (g02)

`tracksAsPaths()` renvoie **un `Path` par `<trk>`**, segments concaténés. gpx2web produit un
`GPXPath` par `<trkseg>`. Une frontière de segment est une discontinuité physique : en la
concaténant, la pause est repliée dans `totalDistance`. **`segmentsAsPaths()` reproduit le
comportement gpx2web** ; utilisez-la si cet artefact vous importe.

### Les waypoints ne sont pas recalés en altitude (g03)

`fixElevation` corrige les points de trace depuis le MNT, **pas les waypoints**. Un waypoint est
une annotation posée par un humain ; lui réécrire son altitude, c'est corriger une donnée que
personne n'a mesurée. gpx2web ne les préserve pas du tout à l'écriture, donc la question ne s'y
pose pas.

### JSON orienté colonnes (g07)

gpx2web écrit `{"keys": [...], "points": [{...}, {...}]}` — un objet par point, chaque nom de
champ répété. vcyclist écrit **un tableau par champ**. Le document est environ trois fois plus
petit et se branche directement sur un dataset Chart.js. Une valeur absente est `null` (JSON
n'admet ni `NaN` ni les infinis).

### Détection d'ascensions bornée (g12)

`ClimbDetector` est en O(n²). Sur une trace enrichie (~25 000 points) la recherche exhaustive
prenait environ **neuf minutes** et figeait l'onglet du navigateur. vcyclist décime le profil à
`ClimbOptions.maxAnalysisPoints` (3 000 par défaut) avant la recherche, puis remonte aux index
d'origine. Les ascensions détectées peuvent donc différer de gpx2web à la marge sur les traces
très denses. Relever `maxAnalysisPoints` restaure le comportement exhaustif, au prix du temps.

### Antiméridien non géré (g13)

Comme gpx2web : les bornes sont un min/max sur les longitudes, donc une trace qui franchit ±180°
produit une image absurdement dézoomée. C'est **gelé, pas oublié** — `MapImageTest` fixe le
comportement actuel pour qu'une correction future soit un choix délibéré.

Écart mineur et volontaire : vcyclist arrondit les coins du cadre **vers l'extérieur**, là où
gpx2web tronque vers zéro et peut laisser le point extrême une fraction de pixel hors des bornes.

### Aucune URL de tuiles par défaut (g14)

`--map` **exige** `--tile-url`. Livrer une valeur par défaut est ce qui amène les outils à
marteler les serveurs publics d'OpenStreetMap sans que leurs auteurs s'en rendent compte.
vcyclist envoie par ailleurs un user-agent explicite, et ne met **pas** en cache les échecs (un
incident réseau ne condamne pas une tuile définitivement, contrairement à gpx2web qui écrit un
fichier vide).

### `process` et `virtualize` fusionnées (g17)

gpx2web a deux commandes aux options presque identiques. vcyclist n'a qu'un pipeline
`Enhancer` ; reproduire la scission reviendrait à inventer une distinction que le code ne fait
pas. Une seule commande **`enhance`**, avec les étapes exposées en drapeaux négatables
(`--[no-]virtualize`, `--[no-]simplify`, …). `--start-date` devient **`--start-time`**.
`cli/README.md` donne la table option par option.

### Lissage limité à l'altitude

`SmoothService.smoothPower`, `smoothAeroCoef` et `smoothSpeed` ne sont pas portés : la référence
de parité de vcyclist est `@glandais/virtual-cyclist`, dont le pipeline ne les applique pas.

---

## Ce qui reste sans équivalent

À la clôture de ce plan, les modules **`gpx` et `gpxtools-cli` de gpx2web peuvent être considérés
comme remplacés**. Ce qui ne l'est pas :

### `gpx-web` — la webapp Quarkus (10 classes, hors périmètre)

| Classe | Rôle |
|---|---|
| `web.resource.api.GPXAnalysisResource` | endpoint REST d'analyse |
| `web.resource.api.GPXVirtualizationResource` | endpoint REST de virtualisation |
| `web.resource.template.AppPageResource` | rendu de `app.html` (Qute) |
| `web.service.VirtualizationService` | orchestration côté serveur |
| `web.model.VirtualizationRequest` | DTO |
| `web.model.VirtualizationResponse` | DTO |
| `web.model.GPXAnalysisResponse` | DTO |
| `web.model.PowerCurvePoint` | DTO |
| `web.virtual.PowerCurvePowerProvider` | puissance suivant une courbe fournie par l'utilisateur |
| `web.CacheFolderProviderImpl` | cache disque côté serveur |

Deux points méritent d'être distingués :

- **L'enveloppe HTTP** (endpoints, DTO, page Qute) n'a pas d'équivalent et n'en aura pas : le
  périmètre de vcyclist est une bibliothèque plus une CLI, pas un service déployé.
- **`PowerCurvePowerProvider`** est la seule *logique métier* de `gpx-web` sans équivalent
  vcyclist. C'est un `CyclistPowerProvider` de plus ; le porter serait une tâche courte si le
  besoin se présentait.

### Conséquence : le dépôt gpx2web ne peut pas être archivé

**Tant que la webapp tourne**, gpx2web reste nécessaire. Les options, à documenter sans les
trancher — ce n'est pas l'objet de ce plan :

1. **Reporter la webapp sur `vcyclist-engine`** (variante JVM, Maven Central). `gpx-web` garderait
   ses endpoints et perdrait sa copie du moteur. Il faudrait porter `PowerCurvePowerProvider`.
2. **La remplacer par la démo statique** (`demo/`, Vue 3 + `@glandais/vcyclist-engine`), qui
   calcule dans le navigateur et n'a donc pas de serveur à faire tourner. Les deux endpoints REST
   disparaîtraient, ce qui casse tout appel programmatique existant.
3. **L'arrêter**, si personne ne l'utilise.

Rien dans ce plan ne dépend de ce choix.

---

## Voir aussi

- [`cli/README.md`](../cli/README.md) — correspondance des commandes `gpxtools-cli`, option par option
- [`docs/PLAN-GPX2WEB.md`](PLAN-GPX2WEB.md) — les tâches g01–g20 et leurs décisions
- [`docs/publishing.md`](publishing.md) — artefacts publiés et vérification de non-rupture
- [`docs/parity.md`](parity.md) — pourquoi la référence de parité est `@glandais/virtual-cyclist`
