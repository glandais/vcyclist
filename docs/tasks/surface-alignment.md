# surface-alignment — aligner les six portes sans les recopier à la main

## Goal

Rendre l'alignement des façades **vérifiable par le build** plutôt que par relecture, et refermer
les vingt-trois écarts que l'audit d'août 2026 a confirmés entre le cœur, le CLI, JS, WASI, la
façade JVM/Java et la démo.

L'objectif n'est pas un générateur. C'est **un catalogue d'options unique qui vérifie avant de
générer**, plus une règle de test pour la seule classe de défaut qu'aucun catalogue ne voit : une
clé qu'un lecteur accepte et qu'un export n'utilise pas.

## Depends on

- [`docs/ledgers/surface-coverage.md`](../ledgers/surface-coverage.md) — le tableau d'état, source
  des écarts listés ici
- `engine/src/jvmTest/…/wasi/WasiParityTableTest.kt` — l'idiome de test réutilisé (un test JVM qui
  lit les autres source sets *comme du texte*)
- `:codegen` — le module qui hébergera le catalogue

## Inputs

Les trois familles de défauts confirmés, par ordre d'impact utilisateur.

### Famille 1 — perte silencieuse (résultat *faux*, pas seulement inatteignable)

| # | Défaut | Où |
|---|---|---|
| F1 | `vcWriteGpxTracks` parse `trackName` et `startTimeEpochMs` puis ne les transmet pas ; l'hôte reçoit un succès et un GPX aux traces sans nom, aux temps relatifs à l'époque 0 — et l'ABI les documente comme fonctionnelles | `EngineWasiApi.kt:747` |
| F2 | La démo détruit tous les `<wpt>` sur un aller-retour chargement → simulation → téléchargement (`parseGpx` ne porte pas les waypoints, `writeGpxAt` n'a pas de paramètre `waypoints`) | `GpxAnalysisView.vue:93`, `useGPXDemo.ts:154` |
| F3 | La démo jette tout `<trk>` après le premier (`parseGpx` = `firstTrackAsPath`) | `useGPXDemo.ts:154` |
| F4 | Une résolution de ligne de course **non convergée** est tracée sur la carte sans avertissement : `report.converged` n'est jamais lu | `useMap.ts:289` |
| F5 | Préséance `roadCondition` inversée entre le CLI et JS/WASI : `maxLeanAngleDeg = 42` + `roadCondition = "wet"` donne 42° d'un côté, ~15,6° de l'autre | `CyclistMixin.kt:136,139` vs `WasiOptions.kt:145,148` et `EngineJsApi.kt:565,568` |

### Famille 2 — capacités du cœur inatteignables depuis une porte

- **Java** (l'angle mort structurel) : pas de fabrique pour `CyclistPowerSpec`, `CoursePhysics` ni
  `Course` ; `ElevationProvider.getElevationsAlong` n'a **aucun** pont `Blocking`/`Async`.
- **CLI** : aucune porte pour la détection de cols ; aucune option `CsvOptions` / `JsonOptions` ;
  aucun accès à `ElevationProviderConfig` ; pas d'`interPathGap` sur les écritures FIT ; pas de
  filtre `tracksAsPaths(kinds)` ; pas de `--no-gpx-repair`.
- **JS** : `ClimbOptions.maxAnalysisPoints` absent ; `pathToCsv` a trois paramètres et `pathToJson`
  deux, donc `decimals`, `includeMeta` et `fields` sont hors d'atteinte alors que WASI les accepte.
- **WASI** : `maxAnalysisPoints` **rejeté** comme clé inconnue ; `fields` et `lineSeparator`
  rejetés eux aussi (ce n'est pas un oubli silencieux, c'est un refus dur).
- **Démo** : dix des trente `@JsExport` d'`EngineJsApi` ne sont pas liés dans `engine-shim.ts`, dont
  `parseGpxTracks`, `writeGpxTracks`, `pathToCsv`, `pathToJson`, `pathsToFit`.
- **Hôte de référence** : `vcAnalyzeRacingLineJson` est le seul `@WasmExport` sans aide nommée dans
  `tools/wasi/host.py` ni assertion dans `test_engine.py`.

### Famille 3 — les mécanismes qui laissent passer les familles 1 et 2

- `ENHANCE_OPTIONS_KEYS` (`jsMain`, `private`) n'a aucun lien compilateur avec `EnhanceOptionsDto`.
- Les défauts réécrits en littéraux : `EngineJsApi.kt:508-517`, `ClimbDetectorJvm.kt:23-31`,
  `EngineModelJvm.simplifyPathOptions` / `curvatureOptions`.
- `ElevationJsApi` n'a **aucun** `requireOnlyKeys` : une clé mal orthographiée y est ignorée.
- `RoadCondition` est le seul enum inter-portes sans catalogue fil dans `commonMain` — cause directe
  de F5.

## Steps

Chaque étape est livrable seule. L'ordre est « le moins cher et le plus utile d'abord » ; les
étapes S0 à S2 ne dépendent d'aucun mécanisme neuf.

### S0 — Gratuit, à faire de toute façon ✅ livré

Pointer la regex de `WasiParityTableTest` sur `tools/wasi/host.py` en plus de
`WasiExportCatalog.kt`, pour que tout `@WasmExport` doive avoir une aide dans l'hôte de référence ;
ajouter l'assertion `vcAnalyzeRacingLineJson` dans `tools/wasi/test_engine.py`. Dans le même commit,
déclarer les `inputs.files(…)` / `inputs.dir("demo/src")` sur `:engine:jvmTest` et `:cli:test` —
sans quoi une tâche de test qui lit d'autres source sets reste `UP-TO-DATE` sur exactement les
modifications qu'elle est censée surveiller.

### S1 — Réparer F1 et arrêter de mentir dans la doc ✅ livré

Extraire un `internal fun writeGpxTracksText(paths, options)` hors de `vcWriteGpxTracks`, transmettre
`options.trackName` et `options.startTimeEpochMs`, et l'épingler dans un `wasmWasiTest` sous l'export
(idiome `w03` : le runner KGP ne sait pas fournir `read_input`). Ajouter `trackNames` /
`startTimeEpochMs` au `writeGpxTracks` JS. Corriger `wasm-wasi-abi.md` §9 et §11.

C'est le défaut le plus grave, et **aucun mécanisme statique ne l'aurait jamais attrapé**.

Livré : `writeGpxTracksText` transmet les quatre clés, quatre `wasmWasiTest` l'épinglent (dont deux
qui échouent sur l'ancien corps), `writeGpxTracks` côté JS prend `trackNames` et `startTimeEpochMs`
avec deux `jsNodeTest`, et l'hôte de référence vérifie les deux clés à travers l'ABI. Les formes
divergent volontairement : JS prend un tableau positionnel, WASI une chaîne pour toutes les traces.
Reste ouvert : la démo ne propose toujours pas d'export multi-traces (F3, étape S2).

### S2 — Refermer les deux pertes de données de la démo ✅ livré

Lier les dix `@JsExport` manquants dans `engine-shim.ts` ; parser via `parseGpxTracks` pour qu'un
second `<trk>` survive ; garder les waypoints à côté du `Path` pour que le téléchargement cesse de
les détruire. Ajouter les téléchargements CSV/JSON dans `Toolbar.vue` pendant que le shim est ouvert.
Supprimer ou justifier les cinq réexports morts (`enhance`, `writeGpx`, `pointAt`,
`dominantHeadwindAzimuth`, `detectClimbsWithOptions`) et le `PointDto` mort.

Livré : F2 et F3 sont refermés. Le shim lie les trente `@JsExport` (dix de plus), la démo parse par
`parseGpxTracks` avec un sélecteur de trace dans la barre d'outils, garde les waypoints à côté du
`Path` et exporte par `writeGpxTracks`, et le menu Download gagne CSV et JSON. Les onze bindings
que **aucun contrôle n'atteint** sont listés nommément en bas d'`engine-shim.ts`, chacun avec sa
raison — un réexport n'est pas une traversée de surface, et la liste doit rester lisible comme
telle. Ajouté `demo/public/gpx/two-tracks.gpx` : aucun échantillon livré n'avait plus d'une trace
ni le moindre waypoint, ce qui est précisément pourquoi les deux pertes ont survécu si longtemps.
Vérifié dans le navigateur, pas seulement au typecheck.

Reste ouvert : `pathsToFit` (FIT multi-tours) n'a pas de contrôle, et les options de `CsvOptions` /
`JsonOptions` non plus — elles n'atteignent pas encore la porte JS (étape S4).

### S3 — `DoorKeyParityTest`, **vert au commit** ✅ livré

`engine/src/jvmTest/…/surface/DoorKeyParityTest.kt` : égalité à quatre voies entre les propriétés de
l'`external interface` Kotlin, `ENHANCE_OPTIONS_KEYS`, `ENHANCE_KEYS` et le miroir TypeScript, pour
les cinq DTO partagés (14/7/6/2/7 noms, identiques aujourd'hui). Réutiliser
`DocumentedFieldCountTest.repositoryRoot()`. **Chaque extracteur porte un auto-contrôle**
`assertTrue(found.size > n)`, pour qu'une regex cassée échoue bruyamment au lieu de rétrécir en
silence.

Le livrer vert : le mécanisme est relu pour lui-même avant d'être attaché à une assertion rouge.
C'est le test qui aurait attrapé la fusion `43`/`44`.

Livré, et vert : les quatre portes s'accordent aujourd'hui sur les cinq DTO (14/7/6/2/7 noms).
Quatre assertions — l'allowlist JS contre son propre DTO, les deux portes fil l'une contre l'autre,
le miroir TypeScript contre le Kotlin, plus l'auto-contrôle de taille par extracteur.

**Non vacuité vérifiée en cassant chaque porte à son tour** : une clé ajoutée au seul miroir TS, une
clé retirée du seul lecteur WASI, une propriété ajoutée à la seule `external interface` (le bug
`43`/`44` reproduit), et une taille attendue fausse. Les deux premières **n'ont d'abord rien cassé** :
la liste `inputs.files` de S0 ne couvrait ni `WasiOptions.kt` ni `engine-shim.ts`, donc la tâche
restait `UP-TO-DATE`. Corrigé dans le même commit — et c'est la démonstration la plus nette qui soit
que la déclaration d'entrées n'est pas de l'hygiène de build mais la condition d'existence du test.

### S4 — Catalogue v1 dans `:codegen` + contrôles d'arité et de défauts ✅ livré

`codegen/src/main/kotlin/io/github/glandais/codegen/surface/OptionCatalog.kt` : la déclaration
unique, écrite à la main, qui tient des **symboles réels et non des chaînes**. Chaque entrée nomme
une `KClass` et un **chemin d'accès pointé** dans l'objet instancié —
`Opt("simplifyToleranceM", path = "simplifyPath.toleranceM")` — jamais un littéral, si bien qu'un
vérificateur ne peut épeler que `d.simplifyPath.toleranceM`.

La complétude est **dérivée, pas déclarée** : `EnhanceOptions::class.primaryConstructor!!.parameters`,
récursivement dans les classes d'options imbriquées, énumère le vrai jeu de champs, et chaque
paramètre doit apparaître soit comme `Opt`, soit comme `CoreOnly(reason)`.

> C'est la décision de conception non négociable. Le dépôt a déjà commis l'erreur inverse :
> `GeneratePath.FIELDS` est un miroir recopié à la main de `PointField.kt`, gardé par le seul
> `EXPECTED_COUNT = 43`. Un catalogue qui **redit** les noms de champs sous forme de chaînes est
> `FIELDS` avec plus de surface, et donc négatif.

Le catalogue vit dans `:codegen` et pas dans `commonMain` pour deux raisons portantes :
`engine/build.gradle.kts` enregistre `checkWasmModuleSize`, donc ~40 descripteurs avec leurs textes
d'aide sont du poids mort dans le `.wasm` ; et un catalogue en `commonMain` **ne peut pas réfléchir**.

Commencer par `ClimbOptions`, `CsvOptions` et `JsonOptions` seulement — les trois plus petites, et
les trois qui ont des écarts vivants. Ajouter `DoorArityTest` et `DoorDefaultsTest`. Attendre du
rouge sur `maxAnalysisPoints`, `CsvOptions.decimals/fields/lineSeparator`,
`JsonOptions.decimals/includeMeta/fields`. Le verdir en élargissant JS et WASI (et en donnant au
CLI au moins `--csv-fields` / `--decimals`), ou en écrivant des raisons `CoreOnly`.

**S'arrêter ici et réévaluer avant d'écrire le moindre émetteur** : cette étape est peut-être tout
le livrable qui en vaut la peine.

#### Livré

Le catalogue est bien rouge au premier lancement, et sur exactement les cinq écarts prédits :

```
DoorParityTest > every option the catalog gives a JS door has one() FAILED
  [ClimbOptions.detectClimbsWithOptions is missing [maxAnalysisPoints],
   CsvOptions.pathToCsv is missing [decimals],
   JsonOptions.pathToJson is missing [decimals, includeMeta]]
DoorParityTest > every option the catalog gives a WASI door has one() FAILED
  [CLIMB_KEYS is missing [maxAnalysisPoints], CSV_KEYS is missing [lineSeparator]]
```

Verdi en élargissant les deux portes fil, avec un test par porte, plus l'hôte de référence.
`fields` reste `CoreOnly` avec sa raison : aucune porte ne sait épeler un `PointField` sur le fil.

Trois leçons, dont deux sur les tests eux-mêmes :

1. **Les assertions accumulées valent mieux que les assertions par groupe.** La première version
   levait au premier écart, donc n'en montrait qu'un sur cinq — une liste partielle de ce qui manque
   se lit comme une liste complète.
2. **Un auto-contrôle trop serré est un faux positif, pas une trouvaille.** Le seuil « au moins deux
   paramètres » a signalé `pathToJson`, qui en prend légitimement un seul. De même, la première
   version de `DoorDefaultsTest` accusait les deux lecteurs `decimals` de réécrire leur défaut :
   leur `Double.NaN` est un **sentinelle d'absence**, et le vrai repli `d.decimals` est à la ligne
   suivante. Assertion corrigée pour chercher `d.<champ>` dans tout le corps du lecteur.
3. **La porte CLI n'est pas vérifiée**, et un `Opt` qui revendique `Door.CLI` fait échouer un test
   exprès. Les trois groupes n'ont aucune option CLI (`cliNote` le dit par groupe, dans le code) ;
   leur donner une porte CLI est un ajout de fonctionnalité, pas la mécanique de cette étape.

#### Réévaluation, comme prévu

La moitié « vérification » vaut clairement le catalogue : elle a trouvé cinq écarts réels sur trois
petites classes, et la complétude dérivée est ce qu'aucun test de parité entre portes ne peut faire —
`ClimbOptions` avait sept champs et les deux portes fil en exposaient six, **en parfait accord entre
elles**. La moitié « génération » reste non démontrée. Recommandation : faire S9 (élargir le
catalogue, toujours en vérification seule) avant S10, et ne juger les émetteurs que sur le diff
qu'ils produisent contre les lecteurs écrits à la main.

### S5 — La porte Java ✅ livré

`EngineModelJvm.cyclistPowerSpec` / `coursePhysics` / `course`, et
`ElevationProviderJvm.getElevationsAlongBlocking` / `…Async` (plus des fabriques pour
`SmoothingOptions` et `FilterOptions`), chacun avec un test Java d'épinglage dans
`src/jvmTest/java/`. Étendre `EngineModelJvmCoverageTest` de six fabriques sur un fichier à toutes
les fabriques `*Jvm.kt`, et ajouter l'assertion de valeur `factory() == DataClass()` — qui garde
`ClimbDetectorJvm`, `TabularWritersJvm`, `GpxModelJvm` et `ElevationProviderJvm`, aujourd'hui sans
aucune garde.

Livré : les trois fabriques du moteur, les deux ponts d'élévation et les fabriques
`smoothingOptions` / `filterOptions`, chacun épinglé depuis Java (`PowerSpecJavaTest`,
`ElevationProviderJavaTest`). `EngineModelJvmCoverageTest` passe de six fabriques sur un fichier à
quatorze sur quatre façades, et gagne la comparaison de valeurs `fabrique() == ClasseDeDonnées()`.
Non vacuité vérifiée : un défaut dérivé et une fabrique rétrécie échouent tous les deux.

**La leçon de conception :** `@JvmOverloads` tronque par la droite, donc l'ordre des paramètres
décide de ce qu'un appelant Java peut omettre. `coursePhysics` réordonne exprès — la classe de
données met `cyclistPowerProvider` en dernier, ce qui l'aurait rendu inaccessible sans nommer les
trois fournisseurs auxquels personne ne touche. Découvert en écrivant le test Java, pas en
raisonnant : le premier jet ne compilait pas.

La moitié S6 de `ClimbDetectorJvm` (sept défauts réécrits) est partie avec, puisque la nouvelle
assertion de valeurs l'exigeait.

### S6 — Tuer les défauts réécrits ✅ livré

`EngineJsApi.kt:508/509/517` lit sur un `val d = defaultJsOptions()`, comme `WasiOptions.kt:69` ;
`ClimbDetectorJvm.kt:23-31` et `EngineModelJvm.kt:75-78,89-95` lisent `ClimbOptions.DEFAULT.*` et les
défauts des data classes d'étape. Ajouter l'assertion `jsNodeTest` que `{simplifyEnabled: true}`
donne `SimplifyPathOptions().toleranceM` — l'épinglage que `WasiOptionsTest.kt:57-61` a déjà et que
JS n'a pas. L'assertion de valeur de S5 garde ensuite la moitié Java pour de bon.

Livré : `toEnhanceOptions` lit chaque repli sur `defaultJsOptions()` ou sur l'objet d'options de
l'étape ; plus un seul littéral. Le vrai défaut était pire que « des littéraux » : il y avait **deux
sites de défauts** qui s'épelaient séparément, donc `enhance(path, {})` et `enhance(path, null)`
pouvaient diverger par construction. `EngineJsApiDefaultsTest` les épingle ensemble.

Non vacuité vérifiée de la manière la plus directe possible : déplacer `SimplifyPathOptions.toleranceM`
de 10,0 à 12,0 dans `commonMain` **avec** le littéral JS restauré fait échouer le test — c'est
littéralement le scénario « 250 W contre 280 W », rejoué.

### S7 — `requireOnlyKeys` sur la façade d'élévation ✅ livré

La garde sur les quatre DTO d'`ElevationJsApi` (`GetElevationsAlongOptionsDto`,
`ElevationProviderConfigDto`, `SmoothingOptionsDto`, `FilterOptionsDto`) et sur le `WaypointDto` du
moteur, pour qu'une clé mal orthographiée soit une erreur sur **toutes** les portes JS.

Livré avec S6. Les quatre DTO d'`ElevationJsApi` **et** le `WaypointDto` du moteur — dernier DTO
d'entrée sans garde — refusent une clé inconnue. La validation de `getElevationsAlong` se fait hors
du `GlobalScope.promise`, pour qu'une faute de frappe lève au site d'appel plutôt que de devenir une
promesse rejetée : trouvé en écrivant le test, qui échouait sur la première version.

En chemin, un écart que l'audit n'avait pas listé : `ElevationJsApi` réécrivait aussi `10.0` / `1.0`
/ `true` pour l'échantillonnage, en double avec la signature de `getElevationsAlong`. Les trois
vivent désormais dans `ElevationDefaults` (`commonMain`), lu par le cœur, la façade JS et les ponts
JVM — le même idiome de catalogue partagé que `PowerModel` et `GpxPowerSource`.

### S8 — Catalogue fil de `RoadCondition` et décision de préséance ✅ livré

`wireName` / `fromWire` / `DEFAULT` dans `commonMain`, à côté de ceux de `GpxPowerSource` ; les trois
portes y passent ; la préséance est tranchée **une fois**. Le `JsonObj` de WASI sait distinguer
absent de fourni, donc rien ne l'oblige à copier la contrainte de non-nullité de JS. Quel que soit le
choix, il est écrit à un seul endroit au lieu de cinq KDoc.

**Décision : le préréglage a le dernier mot**, sur toutes les portes. Prise par l'auteur du dépôt,
sur trois options présentées (préréglage gagnant / explicite gagnant / combinaison refusée).

Ce qui a fait pencher : l'UI de la démo est *construite* autour de cette règle et l'affiche
(« a wet road overrides the lean angle and braking sliders below », « Overridden by the wet preset
(15.6°, µ = 0.28) ») ; la règle garde les deux limites solidaires, ce qui est l'objet même de R9 ; et
c'est la seule exprimable partout, `CyclistDto` n'ayant pas de champs nullables.

Livré : `RoadCondition.wireName` / `fromWire` / `wireNames` / `DEFAULT` plus `applyTo`, qui **est**
la règle. Les trois portes y passent. `RoadConditionWireTest` est en `commonTest`, donc vérifié sur
JVM, JS Node, JS navigateur et wasmWasi.

Le CLI change : `--cyclist-max-angle 42 --road-condition wet` donne désormais 15,6°. `enhance`
avertit sur `stderr` quand les deux sont passés — un drapeau qui perd en silence contre un
préréglage est contraire aux usages.

**Un piège trouvé en cours de route, et c'est la partie qui valait le test.** La première version
gardait `--road-condition` non nullable avec `DRY` par défaut : le préréglage sec écrasait alors un
`--cyclist-max-angle 40` sur une ligne de commande qui ne mentionnait jamais la route. Le cas 05 de
`MixinParsingTest` a échoué immédiatement. `null` veut dire « aucun préréglage demandé », ce qui
n'est pas « demander `dry` » — la même distinction que fait déjà `CyclistDto.roadCondition: String?`.

### S9 — Catalogue v2 : `EnhanceOptions` / `Cyclist` / `Bike` / `Wind` / puissance, toujours en vérification seule (~1 j)

36 options, plus les 20 lignes `CoreOnly` de `RacingLineOptions`. Vert sur `develop` d'aujourd'hui
pour le groupe enhance ; la valeur est prospective. Ajouter le contrôle d'atteignabilité de la démo
(sur les **imports nommés** depuis `~/engine-shim`, pas sur des occurrences de mots) et un
`CliSurfaceTest` exigeant que chaque `Opt` ait une option picocli ou un `CliExempt(reason)`.

Livré, vert sur `develop`. Quatre groupes de plus (33 options), la complétude devenue **récursive**,
et deux extracteurs neufs : `CliSurfaceTest` et `DemoReachabilityTest`.

**`Wind` a été écarté du catalogue**, et c'est un vrai constat plutôt qu'un oubli : `WindDto` ne
correspond à aucune classe d'options à défauts. `windDirection` est en **degrés** et se lit dans
`Wind.directionRad` en radians, l'absence de l'objet donne `WindProviderNone` — un autre type — et
non un `Wind()` par défaut. Le modèle « chemin vers un champ + défaut de la classe » ne s'y applique
pas ; l'y forcer aurait produit une entrée fausse.

**Trois catégories, pas deux.** `roadCondition` n'est pas un champ de `Cyclist` : c'est un préréglage
qui se résout en deux champs. `WireOnly` est né de là — l'omettre faisait passer `CYCLIST_KEYS` pour
acceptant une clé morte, ce qui est précisément l'alarme à ne pas banaliser.

**Ce que la porte CLI a révélé.** Quatre options d'`EnhanceOptions` n'ont aucun drapeau et rien ne le
consignait : `computeMaxSpeeds` (câblé en dur à `true`), `simplifyZExaggeration`, et les trois
`wPrimeBalance*`. Deux sont des décisions, deux des manques ; chacune porte désormais sa raison.

**Une dérive réelle trouvée par `DoorDefaultsTest`** en chemin : le lecteur WASI de la puissance
écrivait `string("type", PowerModel.CONSTANT.id)` au lieu de `d.model.id`, donc un changement du
modèle par défaut de `CyclistPowerSpec` l'aurait laissé sur `CONSTANT`.

**Et, pour la troisième fois, le piège `UP-TO-DATE`.** `CliSurfaceTest` et `DemoReachabilityTest` ont
tous deux atterri **verts et aveugles** : la liste `inputs` nommait des fichiers isolés alors que ces
tests parcourent `cli/src/main` et `demo/src` entiers. Renommer `--cyclist-weight` et retirer une
entrée du bloc du shim ne cassaient rien. La règle est corrigée dans `codegen/build.gradle.kts` :
**déclarer le répertoire qu'un extracteur parcourt, pas le fichier auquel on pense** — un répertoire
ne peut pas prendre du retard sur un extracteur neuf, une liste de fichiers si.

### S10 — Les premiers émetteurs ⛔ non entrepris, et c'est la recommandation

**Décision : ne pas écrire d'émetteur pour l'instant.** Ce n'est pas un report faute de temps, c'est
la conclusion de la réévaluation que S4 imposait — et elle s'est confirmée à chaque étape depuis.

Ce que S4 exigeait avant tout émetteur : que la moitié « vérification » ait été verte *un certain
temps*. Elle a une poignée de commits. Ce n'est pas « un certain temps ».

Ce que l'expérience a ajouté depuis :

1. **La vérification a payé, et pas qu'un peu.** Elle a trouvé cinq écarts réels en S4, une dérive de
   défaut en S9 (`PowerModel.CONSTANT.id` au lieu de `d.model.id`), quatre options CLI sans porte ni
   trace écrite, et elle a forcé trois catégories de modèle (`Opt`, `CoreOnly`, `WireOnly`) qui
   disent quelque chose de vrai sur le domaine. L'émetteur n'ajouterait rien à cela.
2. **Le vrai coût n'est pas là où on le croyait.** Le travail répétitif n'est pas d'écrire les
   lecteurs WASI — c'est de *se souvenir* de toutes les portes. La vérification résout exactement
   ça ; la génération résoudrait la frappe, qui n'a jamais été le problème.
3. **Un émetteur déplace le risque, il ne le supprime pas.** Le critère d'acceptation prévu était
   « un diff quasi nul contre les lecteurs écrits à la main ». Un diff nul démontre que l'émetteur
   sait reproduire ce qui existe déjà — c'est-à-dire qu'il n'apporte rien de vérifiable — et un diff
   non nul démontre que le modèle est faux. Le test n'a pas de résultat qui plaide *pour*.
4. **Le dépôt a déjà un fichier engendré à surveiller** (`GeneratedPath.kt`), plus la table de
   `surface-coverage.md` depuis S11. Chacun ajoute une manière de committer du périmé.

**Quand y revenir.** Si `WasiOptions.kt` devient assez gros pour que l'ajout d'une option y coûte
plus qu'une ligne par lecteur, ou si un cinquième groupe de portes apparaît. Le catalogue est prêt :
il tient déjà les noms fil, les chemins, les défauts et les portes, ce qu'un émetteur demanderait.
Le reste est un `StringBuilder`.

**Ce qui a été fait à la place**, et qui était la partie utile de S10 : la table par option de
`surface-coverage.md` est engendrée depuis le catalogue (S11), avec régénération-et-comparaison. Le
mécanisme « engendrer et vérifier » existe donc dans le dépôt et a un utilisateur — simplement pas
celui que le plan avait prévu.

### S11 — La matrice et les derniers manques de la démo ✅ livré

`docs/ledgers/surface-matrix.tsv` (~90-110 lignes, largement transcriptibles depuis les inventaires
par surface de cet audit), avec une colonne JVM/Java ; un `SurfaceMatrixTest` qui **vérifie** chaque
cellule de porte contre les extracteurs de S3/S4/S9 au lieu de lui faire confiance ; et une tâche
`generateSurfaceLedger` qui rend la section « État » de `surface-coverage.md` depuis le TSV — le
truc « engendrer et comparer » de la fiche `w10` pour la table ABI. En parallèle : les contrôles de
réglage des cols dans `ClimbsPanel.vue` et le badge `!report.converged` dans `useMap.ts` /
`MapView.vue`.

Livré, avec **une déviation assumée par rapport au plan**. Le plan demandait un
`surface-matrix.tsv` écrit à la main et un test comparant chaque cellule aux extracteurs. C'est
`GeneratePath.FIELDS` à nouveau : un fichier dont le seul contenu correct est ce que le code sait
déjà, tenu honnête par un contrôle qui ne doit jamais être en désaccord. Si chaque cellule est
vérifiée contre une dérivation, **la dérivation est la source de vérité et le fichier est un cache**.

Donc : pas de TSV. La table par option est **engendrée** depuis `OptionCatalog` par
`./gradlew :codegen:generateSurfaceLedger`, entre deux marqueurs de `surface-coverage.md`, et
`SurfaceLedgerTest` régénère en mémoire et échoue si ce qui est committé diffère — l'idiome de la
fiche `w10`. Le reste du document, dont le tableau État et sa colonne Démo, reste écrit à la main :
« atteignable par un humain dans l'UI » n'est pas dérivable, et prétendre le contraire serait le
✅ non vérifié que ce ledger existe pour empêcher.

Les deux manques de la démo sont refermés. Le panneau Cols a six contrôles de réglage pilotant
`detectClimbsWithOptions` — vérifié dans le navigateur : passer « Min grade » à 20 % fait passer le
Stelvio de un col à zéro et affiche le badge « (tuned) ». La carte affiche un avertissement quand
`report.converged` est faux.

**Ce que je n'ai pas vérifié visuellement** : le badge de non-convergence lui-même. Aucun échantillon
livré ne fait échouer le solveur (le Stelvio converge en 12 itérations), et je n'ai pas réussi à
forcer l'état par l'UI dans un temps raisonnable. Le balisage est typé et la condition triviale, mais
c'est une inspection, pas une observation.

## Outputs

- `codegen/src/main/kotlin/…/surface/OptionCatalog.kt`
- `engine/src/jvmTest/kotlin/…/surface/DoorKeyParityTest.kt`, `DoorArityTest.kt`, `DoorDefaultsTest.kt`,
  `CliSurfaceTest.kt`, `SurfaceMatrixTest.kt`
- `docs/ledgers/surface-matrix.tsv` + la section « État » de `surface-coverage.md` engendrée
- Les correctifs de code des familles 1 et 2, étape par étape

## Validation

- `./gradlew check` vert à chaque étape (sauf S4, délibérément rouge au commit puis verdie).
- `./tools/wasi/run-all.sh` couvre désormais `vcAnalyzeRacingLineJson`.
- Un aller-retour dans la démo sur un GPX à deux traces et cinq waypoints ressort avec deux traces
  et cinq waypoints.
- `--road-condition wet --cyclist-max-angle 42` et le `{roadCondition:"wet", maxLeanAngleDeg:42}`
  équivalent donnent la même physique de virage sur les trois portes.

## Done when

Ajouter une option à une classe d'options du cœur fait **échouer le build** tant que chaque porte ne
l'expose pas ou ne porte pas une raison écrite de ne pas l'exposer — et le tableau d'état de
`surface-coverage.md` n'est plus édité à la main.

## Notes

**Ce qui a été écarté, et pourquoi.**

- **Un catalogue `EnhanceOptionCatalog` d'exécution dans `commonMain`**, replié par chaque porte.
  Séduisant — il supprime trois listes de clés et rend un défaut littéral inépelable — mais : il
  fait payer `checkWasmModuleSize` sur toutes les cibles ; sa prétention à l'exhaustivité est
  fausse, car une `List<OptionDescriptor>` à laquelle il manque une entrée compile partout, à la
  différence d'un `when` sur un ensemble scellé (**`PointField` est la preuve** : il est parcouru
  génériquement par les deux portes fil et ne dérive jamais, et pourtant `nanDefault` manque aux
  deux, parce que c'est un *attribut* listé à la main) ; et il impose des changements de signature
  cassants côté JS pour livrer ses gains CSV/JSON/cols, que le vérificateur livre sans eux. La
  bonne moitié est conservée : les chemins d'accès dans le catalogue, et le catalogue `commonMain`
  étendu à `RoadCondition`.
- **Engendrer l'`external interface` JS.** Vérifié empiriquement plutôt que supposé :
  `generateTypeScriptDefinitions()` est déjà actif et le `.d.ts` émis référence `EnhanceOptionsDto`
  sans jamais le déclarer, tandis que les interfaces qui *ont* un corps portent une marque
  `__doNotUseOrImplementIt` qu'aucun littéral d'objet ne satisfait. L'`external interface` est donc
  forcé pour tout DTO d'entrée, et l'engendrer n'apporte rien que le contrôle de clés n'apporte —
  tout en faisant pointer les traces d'erreur vers un fichier que personne n'a écrit.
- **Engendrer les options picocli.** Les noms de drapeaux ne sont pas dérivables
  (`simplifyEnabled` → `--simplify`, `racingLineRoadWidthM` → `--road-width`), les descriptions sont
  de la prose multiligne avec interpolation `${DEFAULT-VALUE}`, `negatable = true` sur un `Boolean`
  non nul *bascule* la valeur initiale, `--corridor` et `--gpx-power-source` ont une validation à
  timing propre, et `--cyclist-slew` vaut délibérément `0.0` plutôt que le défaut d'`EngineConstants`.
  Un contrôle adossé à `CliExempt(reason)` est le bon instrument ; un générateur, non.
- **Une surface de test vitest/npm dans `demo/`** : une devDependency, une tâche Gradle `npmTest`,
  un branchement dans `check` et une étape CI, pour un contrôle qu'un scan de texte côté JVM fait
  aussi bien depuis un source set existant.
- **Un module `:conformance` d'emblée.** Que le `jvmTest` d'`:engine` lise `demo/src` et `cli/src`
  inverse le sens des dépendances et c'est un vrai défaut d'odeur — mais c'est du texte, Gradle n'y
  voit aucun cycle, et S0-S5 sont légitimement chez eux à côté de `WasiParityTableTest`. À
  promouvoir si et quand le générateur de ledger atterrit et que les lectures inter-modules se
  multiplient.
- **Élargir les 20 autres réglages de `RacingLineOptions`.** `EngineModelJvm.kt:97-104` dit qu'ils
  ont été réglés par la mesure et ne font partie de la surface d'aucune porte, et
  `EngineModelJvmCoverageTest.kt:77` épingle l'arité à 3 « pour que l'élargir devienne une décision
  plutôt qu'un accident ». Le manque, c'est la note de bas de tableau, pas les réglages.

**La limite du tout.** Une clé qu'un lecteur accepte n'est pas une clé qu'un export utilise.
`vcWriteGpxTracks` aurait passé chacune des vérifications ci-dessus : bon jeu de clés, bon parseur,
mauvais corps d'export. C'est pour ça que la règle « tout export WASI qui lit un objet d'options doit
avoir un test de comportement sous l'export » est dans `CLAUDE.md` et pas dans un fichier de test.
