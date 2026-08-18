# Couverture des surfaces : cœur / CLI / JS / WASI / JVM / démo

vcyclist expose le même moteur par **six portes**, et une capacité ajoutée au cœur n'en franchit
aucune toute seule :

| Surface | Où | Vérifie ses entrées ? |
|---|---|---|
| **Cœur** | `:engine` `commonMain` | — |
| **CLI** | `cli/…/mixin/*.kt` | picocli refuse une option inconnue |
| **JS** | `engine/src/jsMain/…/EngineJsApi.kt` | oui depuis `43` (`requireOnlyKeys`) |
| **WASI** | `engine/src/wasmWasiMain/…/WasiOptions.kt` | oui (`requireOnly`) |
| **JVM/Java** | `*/src/jvmMain/…/*Jvm.kt` | le compilateur Java |
| **Démo** | `demo/src/engine-shim.ts` + un contrôle dans l'UI | non (TypeScript à la main) |

La colonne **JVM/Java** est neuve (audit d'août 2026) et c'était l'angle mort structurel du tableau :
une capacité peut être ✅ sur les quatre portes fil et rester **inatteignable depuis Java**, parce
que `copy()`, les paramètres nommés et les valeurs par défaut sont réservés à Kotlin. C'est le cas
aujourd'hui de `CyclistPowerSpec` — la classe unique dans laquelle le CLI, JS et WASI parsent tous
les trois — qui n'a aucune fabrique dans `EngineModelJvm`.

`DemoReachabilityTest` (S9) vérifie ce qu'un scan de texte peut honnêtement vérifier : que le shim
lie les trente `@JsExport`, et que chaque symbole lié est soit **appelé** quelque part dans
`demo/src`, soit nommé dans le bloc « declared but not reached » du shim — dans les deux sens, pour
que la liste reste vraie. Ce qu'il ne peut pas vérifier, c'est l'existence d'un **contrôle** :
« atteignable par un humain » est une affirmation sur l'UI, qu'aucune analyse statique ne fait. C'est
pourquoi les cellules Démo de ce tableau restent écrites à la main.

La **démo** consomme JS via `demo/src/engine-shim.ts`, dont les types TypeScript sont écrits à la
main (Kotlin/JS n'émet aucun corps pour un `external interface`, donc il n'y a rien à importer ni à
comparer — vérifié : le `.d.ts` engendré référence `EnhanceOptionsDto` sans jamais le déclarer, et
les interfaces qui *ont* un corps portent une marque `__doNotUseOrImplementIt` qu'aucun littéral
d'objet ne satisfait).

**La colonne Démo veut dire « atteignable par un humain dans l'UI », pas « réexporté par
`engine-shim.ts` ».** La distinction n'était pas théorique : `writeGpx` était réexporté par le shim
depuis `g29` et **aucun composant ne l'appelait**. Sous l'autre lecture, la ligne aurait affiché ✅
pendant tout ce temps. Le piège est toujours actif : `detectClimbsWithOptions` est déclaré dans le
shim et n'est appelé nulle part.

Depuis que la démo autonome de `:elevation` a été repliée dans `demo/` (route `#/elevation`), la
démo consomme **deux** façades : `engine-shim.ts` et `demo/src/elevation-shim.ts`
(`ElevationJsApi.kt`). Même contrainte, même piège : un renommage côté Kotlin reste silencieux
jusqu'à l'exécution. `:elevation` n'a ni porte CLI ni porte WASI pour ces trois fonctions
(`newElevationProvider`, `getElevation`, `getElevationsAlong`) — le tableau d'état ci-dessous ne
concerne que les capacités du cœur `:engine`.

**Toutes les portes JS contrôlent désormais leurs clés.** `ElevationJsApi` n'avait *aucun*
`requireOnlyKeys` jusqu'à S7 : un `step` mal orthographié dans un `GetElevationsAlongOptionsDto`
était **silencieusement ignoré**, alors que la même faute était une erreur dure sur chaque DTO gardé
d'`EngineJsApi` et sur chaque lecteur WASI — WASI validait même la config de fournisseur d'élévation
que cette façade ne validait pas. Les quatre DTO d'élévation et le `WaypointDto` du moteur, dernier
DTO d'entrée sans garde, sont couverts.

La validation de `getElevationsAlong` se fait **hors** du `GlobalScope.promise` : une clé mal
orthographiée est une erreur de programmation au site d'appel, et lever là est ce sur quoi un
appelant peut agir. À l'intérieur, elle serait devenue une promesse rejetée qu'un appelant sans
`await` laisse tomber en rejet non géré.

## Pourquoi ce tableau existe

Trois fois de suite, une capacité a atterri dans le cœur et le CLI sans atteindre les autres :

| Constat | Fiche | Portée |
|---|---|---|
| g23, g24, g25 livrées sans toucher `EngineJsApi` | `g29` | 3 tâches |
| g26, g27 (partiel) | `g31`, `g33` | 2 tâches |
| R9, R15, R16, R18, R19 livrées sans toucher JS **ni** WASI | `41`, `43` | 5 entrées du ledger |
| La démo **cassée** par le renommage R17 | `40` | 9 entrées de retard |
| 23 défauts d'alignement sur six portes, dont un export WASI qui parse deux clés et les jette | audit 08/2026 | voir [État](#état) |

Le ✅ du ledger voulait dire « livré dans le cœur et le CLI » et se lisait « livré ».

## État

Légende : ✅ atteignable · ⚠️ partiel (la note dit en quoi) · ❌ absent · — sans objet.

| Capacité | Cœur | CLI | JS | WASI | JVM/Java | Démo |
|---|---|---|---|---|---|---|
| Puissance constante | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R17 durabilité (fade sur travail > CP) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R16 critical-power (réserve W′) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Rejeu `from_data` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R19 allure terrain | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R18 limite de pente de puissance | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R9 condition de route sèche/mouillée | ✅ | ✅ [^prec] | ✅ [^prec] | ✅ [^prec] | ✅ | ✅ |
| R10 angle de garde au sol des pédales | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R15 CP/W′ du champ W′bal | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R12 `pBrake` (champ de sortie) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R24 ligne de course (`racingLine*`, opt-in) | ✅ | ✅ [^rl] | ✅ [^rl] | ✅ [^rl] | ✅ [^rl] | ✅ [^rl] |
| R26 largeur de route (`racingLineRoadWidthM`, OSM `highway`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Rapport de ligne de course (`analyzeRacingLine`) | ✅ | ⚠️ table texte, pas de JSON | ✅ | ✅ | ✅ | ⚠️ 4 champs sur 13 [^conv] |
| Export GPX (`writeGpx`, `writeGpxAt`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Export FIT (`pathToFit`, `pathsToFit`) | ✅ | ⚠️ pas d'`interPathGap` | ✅ | ✅ | ✅ | ⚠️ mono-trace (`pathsToFit` non câblé) |
| `--gpx-power-source` (input / computed / computed-or-input) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `trackName` (mono-trace `writeGpx` / `writeGpxAt`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `trackName` + heure de départ absolue (multi-traces) | ✅ | ✅ | ✅ [^multi] | ✅ [^multi] | ✅ | ✅ |
| Lecture multi-traces (`parseGpxTracks`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ [^pick] |
| `tracksAsPaths(kinds)` (traces / itinéraires) | ✅ | ❌ | ✅ | ✅ | ✅ | ❌ |
| Waypoints GPX (`<wpt>`) | ✅ | ✅ | ✅ (`writeGpxTracks`) | ❌ refus documenté | ✅ | ✅ [^wpt] |
| `repairOnFailure` du parseur GPX + `GpxXmlRepair` | ✅ | ❌ | ❌ | ❌ | ✅ | ❌ |
| Détection de cols (`ClimbDetector` / `ClimbOptions`) | ✅ | ❌ aucune option | ✅ 7 sur 7 | ✅ 7 sur 7 | ✅ | ✅ 6 réglages sur 7 [^climbui] |
| Export CSV (`CsvOptions`) | ✅ | ⚠️ aucune option | ✅ 4 de 5 [^fields] | ✅ 4 de 5 [^fields] | ✅ | ⚠️ défauts seulement |
| Export JSON (`JsonOptions`) | ✅ | ⚠️ aucune option | ✅ 3 de 4 [^fields] | ✅ 3 de 4 [^fields] | ✅ | ⚠️ défauts seulement |
| Configuration DEM (`ElevationProviderConfig`) | ✅ | ❌ | ✅ | ⚠️ sans `tileUrlTemplate` | ✅ | ❌ |
| `getElevationsAlong` (échantillonnage DEM le long d'un tracé) | ✅ | ❌ | ✅ | ❌ | ✅ [^bridge] | ✅ |
| `nanDefault` dans le catalogue de champs publié | ✅ | — | ❌ | ❌ | ✅ | ❌ |
| R27 D+/D− à seuil (`elevationGainPreset`, `elevationGainThresholdM`, `elevationGainEnabled`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R28 fenêtre de lissage d'altitude (`elevationSmoothWindowM`) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| R30 zoom du MNT pour le pipeline (`--dem-zoom`, `demZoom`, `vcSetElevationConfig`) | ✅ | ✅ | ✅ | ✅ | ⚠️ [^demzoom] | ❌ [^demzoom] |

[^prec]: **Refermé en S8, avec un changement de comportement du CLI.** Les trois portes résolvaient
    le préréglage elles-mêmes et ne s'accordaient pas : l'explicite gagnait au CLI, le préréglage
    gagnait sur JS et WASI, donc `maxLeanAngleDeg = 42` avec `roadCondition = "wet"` donnait 42° d'un
    côté et 15,6° de l'autre — la même configuration, deux physiques de virage.

    **Règle retenue : le préréglage a le dernier mot**, partout. Décidée, pas héritée. C'est celle
    autour de laquelle l'UI de la démo est construite et qu'elle affiche en toutes lettres, elle
    garde les deux limites solidaires — ce qui est tout l'objet de R9 — et c'est la seule que
    **toutes** les portes savent exprimer : les champs de `CyclistDto` ne sont pas nullables, donc un
    appelant JS fournit toujours les six et la façade ne peut pas distinguer « absent » de « fourni ».

    La règle vit une seule fois, dans `RoadCondition.applyTo` (`commonMain`), avec le catalogue fil
    `wireName` / `fromWire` / `wireNames` / `DEFAULT` sur le modèle de `GpxPowerSource`.

    Le prix est payé par le CLI : `--cyclist-max-angle 42 --road-condition wet` ne donne plus 42°.
    Un drapeau qui perd en silence contre un préréglage étant contraire aux usages en ligne de
    commande, `enhance` **avertit** sur `stderr` quand les deux sont passés. Et `--road-condition`
    est devenu **nullable** : `null` veut dire « aucun préréglage demandé », ce qui n'est pas la même
    chose que demander `dry` — avec `DRY` comme valeur par défaut, le préréglage sec écrasait un
    `--cyclist-max-angle 40` sur une ligne de commande qui ne parlait pas de route. C'est ce qu'a
    fait la première version, et le cas 05 de `MixinParsingTest` l'a attrapée.
[^rl]: Les portes n'exposent que trois des vingt-trois champs de `RacingLineOptions` — `enabled`,
    `corridor` et `defaultRoadWidthM`. Les vingt autres, dont la `CurvatureOptions` **imbriquée**
    (distincte d'`EnhanceOptions.curvature`, que vise le `curvatureEnabled` des portes) et
    `simplifyToleranceCapM`, restent Kotlin : ils ont été réglés par la mesure, et l'arité est
    épinglée à 3 par `EngineModelJvmCoverageTest.kt:77` pour que l'élargir reste une décision et non
    un accident. Voir `EngineModelJvm.kt:97-104` et [`racing-line.md`](../guides/racing-line.md).
[^multi]: **Refermé en S1.** `toWriteGpxOptions()` parsait `trackName` et `startTimeEpochMs`,
    `requireOnly(WRITE_GPX_KEYS)` les acceptait, et `vcWriteGpxTracks` ne transmettait que
    `powerSource` et `writeExtensions` : l'hôte recevait un compte d'octets positif et un document
    aux traces sans nom et aux temps relatifs. Le corps vit désormais dans un
    `internal fun writeGpxTracksText` avec un `wasmWasiTest` dessous. Les formes diffèrent
    volontairement : JS prend un tableau `trackNames` positionnel, WASI une seule chaîne appliquée à
    **toutes** les traces — des noms par trace seraient un ajout au format fil, pas un correctif.
[^pick]: **Refermé en S2.** La démo appelait `parseGpx`, c'est-à-dire `firstTrackAsPath` : un
    fichier à deux traces perdait la seconde au chargement, sans un mot. Elle parse désormais avec
    `parseGpxTracks` et un sélecteur « 🛤️ Track n/N » apparaît dans la barre d'outils dès qu'il y a
    plus d'une trace. Changer de trace repart de la version parsée : l'amélioration avait été
    calculée pour une autre route.
[^wpt]: **Refermé en S2.** `parseGpx` ne porte pas les waypoints et `writeGpxAt` n'a pas de
    paramètre `waypoints`, donc un aller-retour chargement → simulation → téléchargement détruisait
    chaque `<wpt>` du fichier source. La démo les garde à côté du `Path` et exporte par
    `writeGpxTracks` — le seul écrivain qui les prend, et qui n'était utilisable ici que depuis S1,
    quand il a gagné `trackNames` et `startTimeEpochMs`. Vérifié dans le navigateur sur
    `demo/public/gpx/two-tracks.gpx`, le seul échantillon livré qui ait plus d'une trace ou le
    moindre waypoint — ce qui explique que personne ne l'ait vu.
[^fields]: `CsvOptions.fields` / `JsonOptions.fields` sélectionnent les colonnes et ne franchissent
    **aucune** porte, y compris après S4 : ce sont des `List<PointField>`, et aucune porte ne sait
    encore épeler un `PointField` sur le fil — `fieldDefinitions()` publie les noms, rien ne les
    relit. C'est une entrée `CoreOnly` explicite du catalogue, avec sa raison écrite, pas un oubli :
    le test échoue si quelqu'un la supprime sans donner une porte au champ.
[^conv]: **Refermé en partie en S11.** La démo lit désormais `converged` et `newtonIterations` et
    affiche un avertissement sur la carte quand le solveur a atteint son plafond d'itérations — une
    résolution non convergée n'est plus tracée comme une ligne finie. Les neuf champs restants
    (`corners`, les courbures, `lateralOffsetM`, `relativeGradient`, `activeConstraints`) ne sont
    toujours lus par aucun composant.
[^climbui]: **Refermé en S11.** Le panneau Cols pilote `detectClimbsWithOptions` avec six contrôles,
    aux défauts du moteur, repliés par défaut. Le septième champ, `maxAnalysisPoints`, reste au
    défaut à dessein : un contrôle d'UI pour un garde-fou de performance inviterait l'utilisateur à
    faire geler son onglet.
[^demzoom]: Le zoom du MNT que le *pipeline* utilise. `EnhanceOptions` ne le porte pas — il
    configure le fournisseur, pas le pipeline — donc chaque porte le lit là où elle construit son
    `ElevationProvider` : `--dem-zoom`, la clé `demZoom` du DTO JS, `vcSetElevationConfig` côté WASI.
    Java a déjà `ElevationProviderJvm.elevationProviderConfig(zoomLevel)` mais rien ne relaie ce
    fournisseur jusqu'à `enhanceCourse` : ⚠️, pas ✅. La démo n'a pas de contrôle, et **une
    ré-exportation de shim n'est pas une traversée** : `elevation-shim.ts` expose `zoomLevel` depuis
    toujours pour l'explorateur d'altitude, mais rien dans l'UI d'analyse GPX ne le règle. R30 est
    mesuré et rejeté (zoom 12 = résolution native), donc la case reste ❌ sans être une dette.

[^bridge]: **Refermé en S5.** `ElevationProvider` a trois membres `suspend` et seuls deux avaient
    des ponts `Blocking`/`Async` ; celui qui manquait était justement le seul à cinq paramètres par
    défaut, donc littéralement inappelable depuis Java sans écrire une `Continuation` à la main, et
    ses deux types d'options n'avaient pas de fabrique non plus. `getElevationsAlongBlocking` /
    `…Async`, `smoothingOptions` et `filterOptions` existent désormais, épinglés depuis Java.
    (La ligne reste ❌ côté CLI et WASI : ces portes n'exposent pas la fonction.)

R23 (courbure par régression de cap) n'a pas de ligne : c'est un changement d'estimateur, sans
option d'entrée, donc rien à relayer.

R29 (recalage latéral du MNT) n'a pas de ligne : mesuré, rejeté, rien livré. Voir le ledger.

Les deux champs de sortie (R12, R15) traversent sans travail de façade : `fieldDefinitions()` les
publie et la démo reconstruit sa liste à l'exécution. **Les champs de sortie ne dérivent pas ; les
options d'entrée, si.** Nuance apportée par l'audit : ce qui ne dérive pas, c'est la *liste* des
champs, pas leurs *attributs*. `PointField` a sept propriétés de constructeur, six franchissent les
deux portes fil, et `nanDefault` — la seule qui distingue « NaN par conception » d'un NaN accidentel
— ne les franchit pas. La démo code donc cette connaissance en dur pour `sourceLatitude` /
`sourceLongitude` dans `useMap.ts`.

L'**export FIT** a longtemps été la seule ligne incomplète, et le premier cas d'une *fonction de
sortie* plutôt que d'une option d'entrée : `pathToFit` / `pathsToFit` existaient dans le cœur
(`:fit`), dans le CLI (`--fit`, qui exige `--start-time`), sur JS (`EngineJsApi`) et sur WASI
(`vcPathToFit`, `vcPathsToFit`), mais la démo n'offrait aucun téléchargement. Livré en `g10`,
jamais relayé jusqu'à la cinquième surface — exactement la dérive que ce tableau existe pour
attraper, et restée invisible parce que FIT n'y figurait pas.

**Refermé** : la démo télécharge désormais GPX et FIT depuis la vue `#/`. En le câblant, deux
choses sont apparues, et elles valent plus que la ligne elle-même :

1. **Un réexport de shim n'est pas une traversée de surface.** `writeGpx` attendait dans
   `engine-shim.ts` depuis `g29`, appelé par personne — d'où la définition explicite de la colonne
   Démo plus haut, et la ligne « Export GPX » qui manquait au tableau.
2. **`--gpx-power-source` n'avait jamais franchi la porte JS.** Le CLI choisit quelle puissance
   part dans le `<power>` du GPX écrit ; `writeGpx` / `writeGpxAt` étaient figés sur le défaut
   `INPUT`. Conséquence concrète : le GPX exporté par la démo ne contenait **pas** la puissance
   simulée — un fichier issu d'un simulateur de physique sans sa physique. Le FIT, lui, lit
   `pComputedPower` et la portait déjà. Trouvé en câblant la ligne précédente, **refermé dans la
   foulée** : `powerSource` (et `trackName`, que WASI acceptait déjà et pas JS) sont désormais des
   paramètres de `writeGpx` / `writeGpxAt` / `writeGpxTracks` et des clés de `WriteGpxOptions`, la
   démo exporte en `computed-or-input`, et les trois orthographes vivent une seule fois dans
   `GpxPowerSource.fromWire` — le CLI y passe aussi, à la place de son `when` privé.

   Le défaut, lui, **ne bouge pas** : `INPUT` sur toutes les portes. Écrire du simulé dans un
   format que l'écosystème lit comme un enregistrement reste une décision d'appelant. Une
   orthographe inconnue lève, elle ne retombe pas sur le défaut : la même règle que
   `requireOnlyKeys`, pour la même raison.

   Une seule exception assumée : `export --gpx` n'a pas l'option, ses chemins ne sont jamais
   simulés, donc `computed` y serait toujours vide.

## Options par porte

<!-- BEGIN GENERATED: options-par-porte -->
<!-- Engendré par `./gradlew :codegen:generateSurfaceLedger` depuis OptionCatalog. -->
<!-- Ne pas éditer à la main : `SurfaceLedgerTest` compare cette section à ce que le catalogue rend. -->

Une ligne par **option d'entrée** cataloguée, une colonne par porte fil. C'est la moitié
dérivable du tableau ci-dessus : chaque cellule vient d'`OptionCatalog` et est vérifiée contre
les sources par `DoorParityTest` et `CliSurfaceTest`. Les colonnes JVM/Java et Démo n'y sont
pas — elles ne se dérivent pas, et restent écrites à la main dans l'État.

### `ClimbOptions`

> CLI : The CLI has no climb command at all — `grep -ri climb cli/src/main` is empty.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `booster` | `booster` | ❌ | ✅ | ✅ |
| `maxAnalysisPoints` | `maxAnalysisPoints` | ❌ | ✅ | ✅ |
| `maxDiffRealGrade` | `maxDiffRealGradeRatio` | ❌ | ✅ | ✅ |
| `maxMinClimbElevationM` | `maxMinClimbElevationM` | ❌ | ✅ | ✅ |
| `minClimbElevationRatio` | `minClimbElevationRatio` | ❌ | ✅ | ✅ |
| `minGradePercent` | `minGradePercent` | ❌ | ✅ | ✅ |
| `minMinClimbElevationM` | `minMinClimbElevationM` | ❌ | ✅ | ✅ |

### `CsvOptions`

> CLI : `enhance --csv` and `export --csv` pass no CsvOptions at all.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `decimals` | `decimals` | ❌ | ✅ | ✅ |
| `lineSeparator` | `lineSeparator` | ❌ | ✅ | ✅ |
| `separator` | `separator` | ❌ | ✅ | ✅ |
| `unitsInHeader` | `unitsInHeader` | ❌ | ✅ | ✅ |

Sans porte CLI, avec la raison :

- `lineSeparator` — Neither --csv command passes CsvOptions; a gap, recorded.

**Cœur seulement** (1 champ) : `fields`. Column selection is a List<PointField>, and no door has a way to spell a PointField on the wire yet — fieldDefinitions() publishes names, but nothing parses one back. Give it a door by adding a name list to the readers, not by widening this entry.

### `EnhanceOptions`

> CLI : Checked from S9 on; the CLI spells these as --simplify, --no-fix-elevation, etc.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `computeMaxSpeeds` | `computeMaxSpeeds` | ❌ | ✅ | ✅ |
| `computeOnePointPerSecond` | `computeOnePointPerSecond` | ✅ `--one-point-per-second` | ✅ | ✅ |
| `curvatureEnabled` | `curvature.enabled` | ✅ `--curvature` | ✅ | ✅ |
| `elevationGainEnabled` | `elevationGain.enabled` | ❌ | ✅ | ✅ |
| `elevationGainPreset` | `elevationGain.preset` | ✅ `--elevation-gain-preset` | ✅ | ✅ |
| `elevationGainThresholdM` | `elevationGain.thresholdM` | ✅ `--elevation-gain-threshold` | ✅ | ✅ |
| `elevationSmoothWindowM` | `elevationSmoothWindowM` | ✅ `--elevation-smooth-window` | ✅ | ✅ |
| `fixElevation` | `fixElevation` | ✅ `--fix-elevation` | ✅ | ✅ |
| `racingLineCorridor` | `racingLine.corridor` | ✅ `--corridor` | ✅ | ✅ |
| `racingLineEnabled` | `racingLine.enabled` | ✅ `--racing-line` | ✅ | ✅ |
| `racingLineRoadWidthM` | `racingLine.defaultRoadWidthM` | ✅ `--road-width` | ✅ | ✅ |
| `simplifyEnabled` | `simplifyPath.enabled` | ✅ `--simplify` | ✅ | ✅ |
| `simplifyToleranceM` | `simplifyPath.toleranceM` | ✅ `--simplify-tolerance` | ✅ | ✅ |
| `simplifyZExaggeration` | `simplifyPath.zExaggeration` | ❌ | ✅ | ✅ |
| `virtualizeTrack` | `virtualizeTrack` | ✅ `--virtualize` | ✅ | ✅ |
| `wPrimeBalanceCriticalPower` | `wPrimeBalance.criticalPowerW` | ❌ | ✅ | ✅ |
| `wPrimeBalanceEnabled` | `wPrimeBalance.enabled` | ❌ | ✅ | ✅ |
| `wPrimeBalanceWPrime` | `wPrimeBalance.wPrimeJ` | ❌ | ✅ | ✅ |
| `demZoom` | *(préréglage, aucun champ)* | ✅ | ✅ | ✅ |

Sans porte CLI, avec la raison :

- `computeMaxSpeeds` — EnhanceCommand.pipelineOptions() hardcodes it to true; turning the speed ceiling off from a CLI has no use case anybody has asked for.
- `elevationGainEnabled` — The CLI turns the stage off by asking for the `raw` preset, which reports the same unfiltered sum the stage would otherwise be skipped for. A second spelling of one outcome is worth less than the flag it costs.
- `simplifyZExaggeration` — The CLI exposes --simplify-tolerance and not the z exaggeration. A gap, not a decision — recorded so it is visible.
- `wPrimeBalanceCriticalPower` — As wPrimeBalanceEnabled. Note --cyclist-cp configures the POWER MODEL, not this output field.
- `wPrimeBalanceEnabled` — The CLI has no W-prime-balance flags at all; the field is written with the engine defaults. A gap, recorded.
- `wPrimeBalanceWPrime` — As wPrimeBalanceEnabled. Note --cyclist-wprime configures the power model, not this output field.

**Cœur seulement** (25 champs) : `curvature.curvatureSmoothWindowM`, `curvature.curvatureWindowsM`, `curvature.geometrySmoothWindowM`, `curvature.headingNoiseRad`, `elevationGain.smoothWindowM`, `racingLine.boundEpsilonM`, `racingLine.centeringLengthM`, `racingLine.cornerEnterRadiusM`, `racingLine.cornerExitRadiusM`, `racingLine.curvature`, `racingLine.edgeMarginM`, `racingLine.gentleRadiusM`, `racingLine.gradientTolerance`, `racingLine.hairpinTurnDeg`, `racingLine.maxNewtonIterations`, `racingLine.minCornerLengthM`, `racingLine.minCornerTurnDeg`, `racingLine.objectiveRadiusM`, `racingLine.regularityFactor`, `racingLine.selfProximityGapM`, `racingLine.simplifyToleranceCapM`, `racingLine.steeringLengthM`, `racingLine.straightRadiusM`, `racingLine.straightRunM`, `racingLine.widthSmoothWindowM`. The curvature estimator's tuning. Only `enabled` crosses; the four windows were measured (ledger R23) and no door names them.

### `Cyclist`

> CLI : Checked from S9 on; --cyclist-weight, --cyclist-cd and friends.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `cd` | `cd` | ✅ `--cyclist-cd` | ✅ | ✅ |
| `frontalAreaM2` | `frontalAreaM2` | ✅ `--cyclist-a` | ✅ | ✅ |
| `massKg` | `massKg` | ✅ `--cyclist-weight` | ✅ | ✅ |
| `maxBrakeG` | `maxBrakeG` | ✅ `--cyclist-max-brake` | ✅ | ✅ |
| `maxLeanAngleDeg` | `maxLeanAngleDeg` | ✅ `--cyclist-max-angle` | ✅ | ✅ |
| `maxSpeedKmH` | `maxSpeedKmH` | ✅ `--cyclist-max-speed` | ✅ | ✅ |
| `roadCondition` | *(préréglage, aucun champ)* | ✅ | ✅ | ✅ |

### `Bike`

> CLI : Checked from S9 on; --bike-crr and friends.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `crr` | `crr` | ✅ `--bike-crr` | ✅ | ✅ |
| `efficiency` | `efficiency` | ✅ `--bike-efficiency` | ✅ | ✅ |
| `inertiaFront` | `inertiaFront` | ✅ `--bike-inertia-front` | ✅ | ✅ |
| `inertiaRear` | `inertiaRear` | ✅ `--bike-inertia-rear` | ✅ | ✅ |
| `maxPedalingLeanAngleDeg` | `maxPedalingLeanAngleDeg` | ✅ `--bike-max-pedal-angle` | ✅ | ✅ |
| `wheelRadiusM` | `wheelRadiusM` | ✅ `--bike-wheel-radius` | ✅ | ✅ |

### `CyclistPowerSpec`

> CLI : Checked from S9 on; --cyclist-model, --cyclist-power and friends.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `criticalPower` | `criticalPowerW` | ✅ `--cyclist-cp` | ✅ | ✅ |
| `maxSlewWPerS` | `maxSlewWPerS` | ✅ `--cyclist-slew` | ✅ | ✅ |
| `pacing` | `pacing` | ✅ `--cyclist-pacing` | ✅ | ✅ |
| `power` | `powerW` | ✅ `--cyclist-power` | ✅ | ✅ |
| `type` | `model` | ✅ `--cyclist-model` | ✅ | ✅ |
| `useHarmonics` | `useHarmonics` | ✅ `--cyclist-harmonics` | ✅ | ✅ |
| `wPrime` | `wPrimeJ` | ✅ `--cyclist-wprime` | ✅ | ✅ |

### `JsonOptions`

> CLI : `--json` passes no JsonOptions at all.

| Option (nom fil) | Champ du cœur | CLI | JS | WASI |
|---|---|---|---|---|
| `decimals` | `decimals` | ❌ | ✅ | ✅ |
| `includeMeta` | `includeMeta` | ❌ | ✅ | ✅ |
| `pretty` | `pretty` | ❌ | ✅ | ✅ |

**Cœur seulement** (1 champ) : `fields`. Same as CsvOptions.fields.

<!-- END GENERATED: options-par-porte -->

## Ce qui empêche la prochaine dérive

- **Le catalogue partagé** — `PowerModel` + `CyclistPowerSpec` dans `commonMain`. Le `when` qui
  fait modèle → provider, l'ordre de composition `base → pacing → slew` et les défauts existent
  **une fois**. Ajouter un modèle casse la compilation de `commonMain` tant qu'il n'est pas
  traité, donc sur les trois cibles à la fois, avant tout test.
- **Le contrôle strict des clés** — JS et WASI refusent une clé qu'ils ne lisent pas. C'est ce qui
  transforme un `tiringDuration` oublié en erreur plutôt qu'en réglage silencieusement ignoré.
- **La couverture de la porte Java** (S5) — `EngineModelJvmCoverageTest` ne couvrait que
  `EngineModelJvm` et ne comparait que des **arités**. Il couvre maintenant chaque fabrique de
  chaque `*Jvm.kt` — `ClimbDetectorJvm`, `TabularWritersJvm`, `ElevationProviderJvm` n'avaient
  aucune garde — et ajoute la comparaison de **valeurs** `fabrique() == ClasseDeDonnées()`, que
  l'arité ne peut pas voir : une fabrique qui réécrit `10.0` là où la classe dit désormais `12.0`
  a la bonne arité et la mauvaise réponse.

  Une subtilité de conception est apparue en écrivant les tests Java : `@JvmOverloads` tronque
  **par la droite**, donc l'**ordre** des paramètres décide de ce qu'un appelant Java peut omettre.
  `coursePhysics` réordonne délibérément — mettre `cyclistPowerProvider` en dernier, comme la
  classe de données, aurait rendu le seul fournisseur que l'on surcharge accessible seulement en
  nommant les trois auxquels personne ne touche. Le test vérifie l'arité, pas l'ordre, exactement
  pour qu'une façade puisse faire ce choix.

- **Le catalogue d'options** (S4) — `codegen/…/surface/OptionCatalog.kt`. Une déclaration unique
  qui tient des `KClass` et des **chemins d'accès** dans les vraies classes, jamais des chaînes :
  `defaultOf` résout chaque chemin par réflexion sur une instance par défaut, donc une propriété
  renommée fait lever le catalogue en la nommant. Surtout, **la complétude est dérivée** —
  `primaryConstructor.parameters` énumère le vrai jeu de champs et chacun doit apparaître comme
  `Opt` ou comme `CoreOnly(reason)`. Un champ ajouté à `CsvOptions` sans entrée casse le build ;
  il ne peut pas être discrètement laissé hors des portes. C'est exactement ce que
  `GeneratePath.FIELDS` ne fait pas, et la raison pour laquelle le catalogue ne recopie pas de noms.

  `DoorParityTest` et `DoorDefaultsTest` le comparent aux portes. Le premier a trouvé, du premier
  coup, les cinq écarts que l'audit avait prédits : `maxAnalysisPoints` absent de JS **et** de WASI,
  `decimals` absent de `pathToCsv`, `decimals` et `includeMeta` absents de `pathToJson`,
  `lineSeparator` absent de `CSV_KEYS`. Tous refermés dans la foulée. Le second vérifie qu'un
  lecteur WASI lie bien `val d = <Options>()` et retombe sur `d.<champ>` au lieu de réécrire le
  nombre — la classe de dérive « 250 W contre 280 W ».

  **Étendu en S9** à `EnhanceOptions`, `Cyclist`, `Bike` et `CyclistPowerSpec`, et la complétude
  est devenue **récursive** : à chaque niveau de l'arbre des chemins, les paramètres du constructeur
  doivent égaler ce que le catalogue déclare en dessous. C'est ce qui transforme « les portes
  exposent 3 des 23 champs de `RacingLineOptions` » d'une phrase de ledger en vingt entrées
  `CoreOnly` que le build exige — dont `racingLine.curvature`, la `CurvatureOptions` **imbriquée**
  que le `curvatureEnabled` des portes ne vise pas.

  La porte CLI est **vérifiée depuis S9** (`CliSurfaceTest`). Les noms de drapeaux sont déclarés et
  non dérivés — `simplifyEnabled` est `--simplify`, `type` est `--cyclist-model` : toute règle qui
  produirait ceux-là serait une table de correspondance déguisée. Le test vérifie que le drapeau
  déclaré **existe** (c'est la moitié qui dérive, un renommage n'atterrissant que dans le mixin) et
  qu'une option sans porte CLI porte une **raison écrite**. Quatre options d'`EnhanceOptions` sont
  dans ce cas et aucune n'était consignée nulle part : `computeMaxSpeeds` est câblé en dur à `true`,
  `simplifyZExaggeration` et les trois `wPrimeBalance*` n'ont aucun drapeau. Deux décisions, deux
  manques ; les raisons disent lesquels.

  Une troisième catégorie a dû apparaître : `WireOnly`. `roadCondition` **n'est pas un champ** de
  `Cyclist`, c'est un préréglage qui se résout en deux champs. En faire un `Opt` aurait demandé
  d'inventer un chemin inexistant ; l'omettre faisait passer le lecteur WASI pour acceptant une clé
  que rien ne lit — exactement l'alarme que ce catalogue doit lever pour une vraie clé morte.

- **Les tests de parité de portes** (S3) — `DoorKeyParityTest` compare, pour les cinq DTO partagés,
  les propriétés de l'`external interface` Kotlin, `ENHANCE_OPTIONS_KEYS`, `ENHANCE_KEYS` et le
  miroir TypeScript de la démo. C'est la seule mécanique du dépôt qui voie deux source sets à la
  fois — un `external interface` n'a pas de corps engendré, les deux `Set<String>` sont `private`
  dans leur propre source set, et rien sur la JVM ne réfléchit sur du JS ni du wasm. L'idiome vient
  de `WasiParityTableTest` : un test JVM qui lit le dépôt **comme du texte**.

  Deux obligations vont avec, et elles ne sont pas décoratives. **Un auto-contrôle de taille par
  extracteur**, sinon une regex cassée transforme le test en `assertEquals(vide, vide)` qui passe
  pour toujours. Et **chaque fichier lu doit être un `inputs.files` de la tâche de test** : la
  première version de la liste (S0) oubliait `WasiOptions.kt` et `engine-shim.ts`, et `DoorKeyParityTest`
  n'a donc pas vu une clé supprimée du lecteur WASI — la garde était bonne, la tâche n'a simplement
  jamais été relancée. Vérifié en cassant les quatre portes une par une.

Aucun des deux ne couvre une capacité qui **n'est pas** un modèle de puissance : R9 vit sur
`Cyclist`, R15 sur `EnhanceOptions`. Et surtout, aucun des deux ne couvre une **fonction de
sortie** : `pathToFit` et `writeGpxAt` ne prennent pas d'objet d'options, donc `requireOnlyKeys`
ne se déclenche jamais dessus. Les deux dérives ci-dessus (FIT jamais câblé, `--gpx-power-source`
jamais exporté) sont passées exactement par ce trou.

- **Le catalogue `RoadCondition`** (S8) — `wireName`, `fromWire`, `wireNames`, `DEFAULT`, **et la
  règle de préséance** dans `applyTo`. C'était le dernier enum inter-portes sans catalogue, et le
  seul endroit où deux portes donnaient des réponses différentes à la même question. Épinglé par
  `RoadConditionWireTest` en `commonTest`, donc sur les quatre cibles : la règle ne peut pas tenir
  sur l'une et pas sur l'autre.
- **Le catalogue `GpxPowerSource`** — depuis, les trois orthographes et le défaut vivent dans
  `commonMain` (`wireName`, `fromWire`, `DEFAULT`), et les trois portes parsent à travers. Ajouter
  une constante casse le `when` de `wireName` dans `commonMain`, donc sur les trois cibles d'un
  coup. C'est la garde « catalogue partagé » appliquée à une fonction de sortie.

### Les trois trous que l'audit a nommés

- **Une clé acceptée n'est pas une clé utilisée.** `requireOnly` / `requireOnlyKeys` prouvent qu'un
  *lecteur* accepte la clé, jamais qu'un *export* la transmet. `vcWriteGpxTracks` avait la bonne
  liste de clés, le bon parseur, et un corps d'export qui en jetait deux. **Aucune** vérification
  statique ne voit ça : il faut un test de comportement sous l'export. Refermé en S1 pour cet
  export ; la règle vaut pour tous les autres, et vit dans `CLAUDE.md`.
- **L'allowlist JS n'est couplée à rien.** `ENHANCE_OPTIONS_KEYS` est un `Set<String>` écrit à la
  main, sans lien compilateur avec `EnhanceOptionsDto`. Ajouter une propriété à l'`external
  interface` sans toucher au set compile proprement et produit une façade qui refuse sa propre
  option documentée — le commentaire du fichier lui-même enregistre que c'est déjà arrivé, à la
  fusion des fiches `43`/`44`. **Couvert depuis S3** par `DoorKeyParityTest` : le lien reste absent
  du compilateur, mais le build échoue désormais.
- **Les défauts réécrits en littéraux — refermé (S5, S6).** `EngineJsApi` réécrivait `10.0` /
  `3.0` / `true` au lieu de lire `SimplifyPathOptions()` / `CurvatureOptions()`, et pire, il avait
  **deux sites de défauts** — `toEnhanceOptions` et `defaultJsOptions()` — qui les épelaient
  séparément, donc un défaut changé dans l'un n'atteignait pas `enhance(path, null)`. Il n'y en a
  plus qu'un, et `EngineJsApiDefaultsTest` l'épingle de l'extérieur : passez un DTO partiel, les
  champs non nommés doivent valoir ceux du moteur. WASI avait cet épinglage depuis `w09`, JS non —
  c'est exactement par là que la dérive est passée. `ClimbDetectorJvm` et `EngineModelJvm` sont
  partis avec S5, et les défauts d'échantillonnage DEM vivent maintenant dans
  `ElevationDefaults` (`commonMain`) au lieu d'être écrits dans la signature **et** dans la façade.

Le plan pour refermer tout ça est [`docs/tasks/surface-alignment.md`](../tasks/surface-alignment.md).

## À faire en ajoutant une capacité

1. **Cœur** — `commonMain`, avec ses tests.
2. **CLI** — une option dans le mixin, dont le défaut vient d'`EngineConstants` pour la physique et
   de l'objet d'options de l'étape pour le pipeline (`SimplifyPathOptions()`,
   `CurvatureOptions.DEFAULT`, `RacingLineOptions.DEFAULT`, `ClimbOptions.DEFAULT`) — jamais un
   littéral : c'est ainsi que JS et WASI ont défendu 250 W pendant que le CLI défendait 280 W.
3. **JS** — le champ sur le DTO **et** dans l'allowlist de `requireOnlyKeys` **et** dans
   `defaultJsOptions()` : un défaut posé au seul `toEnhanceOptions` ne s'applique pas à
   `enhance(path, null)`.
4. **WASI** — le champ, sa clé dans `requireOnly`, **le corps de l'export qui la transmet vraiment**,
   plus [`wasm-wasi-abi.md`](../guides/wasm-wasi-abi.md).
5. **JVM/Java** — une fabrique dans le `*Jvm.kt` du module, et un test Java dans `src/jvmTest/java/`
   qui épingle la forme courte. Rien d'autre ne prouve qu'un appelant Java y arrive.
6. **Démo** — `engine-shim.ts` (à la main), **puis un contrôle dans l'UI** : le réexport seul ne
   compte pas.
7. **Ledger** — la ligne `Surfaces` de l'entrée, si c'en est une, et la ligne de ce tableau.

Un modèle de puissance saute les étapes 2 à 4 : le catalogue les fait.
