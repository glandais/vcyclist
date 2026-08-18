# 44 — Réconcilier `feat/demo-update` et `feat/racing-line`

## Goal

Les deux branches partent du même commit (`72f4368`, develop @ 4.0.0) et se rejoignent sur quatre
fichiers. La fusion elle-même est petite — **deux fichiers en conflit, deux hunks chacun**, tous du
type « garder les deux côtés ». Ce n'est pas le sujet de la fiche.

Le sujet, c'est ce qui fusionne **proprement et faussement** : la fiche `43` a posé un contrôle
strict des clés sur les DTO JS, avec `ENHANCE_OPTIONS_KEYS` comme liste blanche. `feat/racing-line`
ajoute quatre clés à `EnhanceOptionsDto` — `curvatureEnabled`, `racingLineEnabled`,
`racingLineCorridor`, `racingLineRoadWidthM`. Dans l'arbre fusionné, la liste blanche garde **mes
dix entrées, sans conflit**, à 500 lignes de l'interface qu'elle garde.

Le résultat compile, puis lève `Unknown EnhanceOptionsDto key(s): racingLineEnabled` sur la
première utilisation.

## Ce que le garde-fou a attrapé, et ce qu'il a laissé passer

Le tableau est le vrai résultat de cette fiche, parce qu'il note `43` sur un cas réel qu'elle
n'avait pas vu venir :

| Surface | Clés ajoutées par racing-line | Comportement à la fusion |
|---|---|---|
| **WASI** | 4 dans `ENHANCE_KEYS` | **Conflit visible.** La liste était déjà stricte, donc racing-line a *dû* la mettre à jour, et les deux éditions se télescopent. Impossible à rater. |
| **JS** | 4 sur le DTO, aucune dans la liste blanche | **Fusion silencieuse.** La liste blanche est arrivée avec `43`, après le branchement : racing-line n'avait rien à mettre à jour. |

Même garde-fou, deux issues opposées — et la seule différence est que côté WASI la liste blanche
est *à côté* de ce qu'elle décrit, alors que côté JS je l'ai posée 500 lignes plus bas. **La
proximité n'est pas cosmétique : c'est elle qui transforme l'oubli en conflit.**

Un filet de sécurité existe malgré tout, et il est arrivé après coup : la tâche `t11` a ajouté
`RacingLineJsApiTest.the corridor mode reaches the report`, qui passe
`{ racingLineCorridor: 'lane' }`. Ce test **passe au rouge** après fusion. Les trois autres clés —
`racingLineEnabled`, `curvatureEnabled`, `racingLineRoadWidthM` — ne sont exercées par aucun test
de façade, ni JS ni WASI.

## Depends on

- `43` (catalogue partagé + clés strictes), livrée
- `feat/racing-line` jusqu'à `9ef604e` (t01→t11)
- À faire **après** la fusion des deux branches dans `develop`, pas avant

## Inputs

- La fusion à blanc : `git merge-tree --write-tree feat/demo-update feat/racing-line`
- `engine/src/jsMain/…/EngineJsApi.kt` — `EnhanceOptionsDto`, `toEnhanceOptions`,
  `ENHANCE_OPTIONS_KEYS`, `analyzeRacingLine` (t11)
- `engine/src/wasmWasiMain/…/WasiOptions.kt` — `ENHANCE_KEYS`, `toEnhanceOptions`
- [`docs/surface-coverage.md`](../../ledgers/surface-coverage.md) — la matrice posée par `43`
- [`docs/wasm-wasi-abi.md`](../../guides/wasm-wasi-abi.md) — réécrit par `43`, jamais touché par racing-line
- [`docs/racing-line.md`](../../guides/racing-line.md) — le document utilisateur de `t11`
- `demo/src/engine-shim.ts`, `demo/src/config/fieldConfig.ts`

## Steps

### 1. Résoudre les quatre conflits

Tous « garder les deux côtés », aucun arbitrage :

| Fichier | Hunk | Résolution |
|---|---|---|
| `EngineJsApi.kt` | corps de `EnhanceOptionsDto` | les 3 champs W′bal **et** les 4 champs curvature/racing-line |
| `EngineJsApi.kt` | `toEnhanceOptions` | `wPrimeBalance = …` **et** `curvature = …`, `racingLine = …` |
| `WasiOptions.kt` | `ENHANCE_KEYS` | les 3 clés W′bal **et** les 4 clés racing-line |
| `WasiOptions.kt` | `toEnhanceOptions` | idem |

`CLAUDE.md` et le ledger fusionnent seuls, dans des sections disjointes. Le CLI ne conflit pas du
tout : racing-line a mis ses options dans `EnhanceCommand`, `43` a réécrit `CyclistMixin`.

### 2. Fermer le trou silencieux — et le rendre impossible à rouvrir

Ajouter les quatre clés à `ENHANCE_OPTIONS_KEYS` est la correction évidente ; elle ne vaut rien
seule, puisque c'est exactement l'oubli qui vient de se produire.

**Déplacer chaque `*_KEYS` juste sous l'interface qu'il décrit.** Les quatre listes
(`ENHANCE_OPTIONS_KEYS`, `CYCLIST_KEYS`, `BIKE_KEYS`, `WIND_KEYS`, `POWER_PROVIDER_KEYS`) vivent
aujourd'hui groupées près de `requireOnlyKeys`, loin des DTO. Collées à leur interface, une
addition de champ et l'addition de clé correspondante tombent dans le **même hunk** : git conflit
au lieu de fusionner, et une relecture voit les deux d'un coup. C'est la leçon que WASI donne
gratuitement.

### 3. Un test par clé, sur les deux façades

Trois des quatre clés ne sont exercées nulle part. Ajouter, côté `jsTest` et `wasmWasiTest`, un cas
qui **passe la clé et vérifie qu'elle arrive** dans `EnhanceOptions` — pas qu'elle est acceptée,
qu'elle a un effet :

- `curvatureEnabled: false` → `EnhanceOptions.curvature.enabled == false`
- `racingLineEnabled: true` → la sortie bouge (le stage réécrit les coordonnées)
- `racingLineCorridor: "full-road"` → corridor plus large que `lane` (déjà couvert côté JS par t11)
- `racingLineRoadWidthM` → `RacingLineOptions.defaultRoadWidthM`

Le point n'est pas la couverture pour elle-même : c'est que ces quatre-là viennent de démontrer
qu'une clé non testée peut traverser une fusion sans que personne s'en aperçoive.

### 4. `docs/wasm-wasi-abi.md`

Racing-line ajoute quatre clés à l'ABI WASI **sans toucher le document**, et `43` a justement
réécrit la section des options — donc la fusion prend ma version et les clés restent
non documentées. Ajouter les quatre, plus les valeurs de `racingLineCorridor` (`lane`,
`lane-left`, `full-road`) et le fait qu'une valeur inconnue est une erreur.

C'est la dérive que `surface-coverage.md` décrit, arrivée la même semaine que le document. À dire
tel quel dans le résultat de la fiche : le tableau ne l'a pas empêchée, il l'a rendue trouvable.

### 5. Matrice et ledger

- `docs/surface-coverage.md` : trois lignes — R23 (courbure), R24 (racing line), R26 (largeur OSM).
- Le ledger : la ligne `Surfaces` sur R23/R24/R26, comme les neuf entrées précédentes.
- `docs/PLAN.md` : la **phase T n'y figure pas**. Racing-line se suit dans
  `docs/design/racing-line.md` §11 et ses fiches `tNN`. Soit on ajoute la phase au traqueur
  canonique, soit `PLAN.md` cesse de l'être et le dit.

### 6. Démo

Racing-line ne touche aucun fichier de `demo/`. Trois choses en découlent :

- **Gratuit** : `roadWidth`, `lateralOffset` et `trajectoryCurvature` apparaissent déjà dans la
  sidebar, sous une catégorie « Road » nouvelle, parce que `fieldConfig.ts` lit
  `fieldDefinitions()` à l'exécution. `sourceLatitude` / `sourceLongitude` portent
  `notSelectable = true` et n'y apparaissent pas — correct, ce ne sont pas des séries.
- **À faire** : les contrôles (courbure on/off, racing line on/off, corridor, largeur de route)
  dans l'onglet Options, et les types `analyzeRacingLine` / `RacingLineReportDto` / `CornerDto`
  dans `engine-shim.ts`.
- **À trancher, et c'est le point le plus important** : le racing line **déplace toutes les
  coordonnées**. La carte de la démo trace `latitude`/`longitude` et afficherait donc un tracé qui
  n'est plus celui du fichier chargé, sans le dire. Les originaux sont conservés dans
  `sourceLatitude`/`sourceLongitude`, lisibles via `getField` (qui passe par `PointField.byProp` et
  ignore `notSelectable`) — en radians, à convertir. Proposition : superposer les deux, la trace
  source en trait fin, la ligne optimisée par-dessus, dès que le stage est actif.

Le rapport de `t11` mérite une place : il montre des virages que le stage **dégrade** (sur Stelvio,
une épingle passe de 20,0 à 17,1 m de rayon) alors que la durée globale s'améliore. C'est
exactement ce qu'une démo peut montrer et qu'un chiffre agrégé cache.

## Outputs

Modifiés :

- `engine/src/jsMain/…/EngineJsApi.kt`, `engine/src/wasmWasiMain/…/WasiOptions.kt`
- `docs/wasm-wasi-abi.md`, `docs/surface-coverage.md`,
  `docs/research/improvements-ledger.md`, `docs/PLAN.md`
- `demo/src/engine-shim.ts`, `demo/src/types.ts`, `demo/src/composables/*`,
  `demo/src/components/{EnhanceOptionsTab,MapView}.vue`

Créés :

- Cas dans `engine/src/jsTest/…` et `engine/src/wasmWasiTest/…` (étape 3)

## Validation

```bash
./gradlew check ktlintCheck
cd demo && npm run typecheck && npm run lint && npx vite build
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `RacingLineJsApiTest` (t11) | vert — il est rouge tant que l'étape 2 n'est pas faite |
| 2 | Chaque clé racing-line, JS et WASI | atteint `EnhanceOptions`, effet observable |
| 3 | Une clé inconnue, JS et WASI | lève toujours |
| 4 | Options par défaut | sortie identique à l'avant-fusion des deux côtés |
| 5 | Démo, racing line actif | carte montrant trace source **et** ligne optimisée |
| 6 | Démo, config par défaut | racing line **off** — le stage déplace tout, il ne s'active pas tout seul |

Le cas 1 est le seul à être déjà écrit, et c'est celui qui prouve que la fusion était cassée.
Le cas 6 est un garde-fou d'ergonomie autant que de justesse : un utilisateur qui charge son fichier
doit retrouver son fichier.

## Done when

- [x] Quatre conflits résolus, les deux côtés conservés
- [x] Les quatre clés dans `ENHANCE_OPTIONS_KEYS`
- [ ] Chaque `*_KEYS` collé sous son interface — **non fait**, les cinq `val` restent groupés en bas
      d'`EngineJsApi.kt`. Cosmétique, sans effet sur la garde elle-même.
- [ ] Un test par clé, sur les deux façades — **non fait** ; les allowlists sont testées en bloc.
- [x] `docs/guides/wasm-wasi-abi.md` à jour
- [x] R23/R24/R26 dans le ledger ; R24/R26 dans la matrice de surfaces (ajoutées le 2026-08-18) ;
      la série `t` n'a jamais eu de phase dans `PLAN.md` — son état vit dans l'en-tête de
      [`racing-line-design.md`](../plans/racing-line-design.md)
- [x] Démo : contrôles, shim, et la question de la carte tranchée
- [x] `./gradlew check` + `ktlintCheck` + typecheck/lint/build démo verts

> **Clôture (2026-08-18, réorganisation `docs/`).** Les deux cases restées ouvertes ci-dessus sont
> les seules ; elles sont cosmétiques et volontairement laissées telles quelles plutôt que cochées
> à faux.

## Résultat partiel — la fusion et la démo

La fusion est faite (`b8c6986`), avec en prime `vcAnalyzeRacingLineJson` : `WasiParityTableTest`
échouait **déjà sur `feat/racing-line`**, `t11` ayant ajouté `analyzeRacingLine` sans entrée dans
`PARITY_TABLE`. Plutôt que d'inscrire le premier `NOT_PORTED` du projet, l'export a été porté.

### La carte : trois tracés, et la question tranchée

La fiche posait le problème sans le résoudre : le racing line **déplace toutes les coordonnées**,
donc la carte montrerait un tracé qui n'est pas le fichier chargé. Retenu : superposer les trois,
avec une légende, et **seulement quand l'étape a tourné**.

| Tracé | Source | Rendu |
|---|---|---|
| Route enregistrée | `sourceLatitude` / `sourceLongitude` du chemin enrichi | gris, pointillé |
| Corridor autorisé | `corridorLo` / `corridorHi` du rapport | bande violette |
| Ligne suivie | `latitude` / `longitude` du chemin enrichi | rouge plein |

### Le corridor n'est pas dessinable sans reconstruire une normale

Le rapport donne des **décalages latéraux en mètres**, pas des coordonnées : la normale vit dans
le repère plan du moteur, qui ne sort pas. Elle est donc reconstruite dans la démo — tangente
locale en (est, nord), quart de tour anti-horaire, ce qui reproduit le `(−sin θ, cos θ)` de
`RacingLine`.

Une normale inversée dessinerait un corridor parfaitement plausible **du mauvais côté de la
route**, et rien ne le dirait. D'où un auto-contrôle permanent : `sourceLat/Lon` plus le champ
`lateralOffset`, tous deux écrits par l'étape, doivent redonner `latitude`/`longitude`. L'écart
maximal est calculé à chaque tracé et journalisé au-delà de 1,5 m.

**Il a servi immédiatement, et trois fois.**

1. **4,46 m** au premier essai. Cause : le rapport était calculé sur le chemin *enrichi*, donc
   déjà déplacé — un corridor autour d'une deuxième optimisation, que personne n'a parcourue.
   Corrigé en analysant le chemin d'entrée. → 0,51 m.
2. **Hypothèse du rééchantillonnage 1 Hz : fausse.** Testée en le désactivant, l'écart ne bouge
   pas. Ce n'était pas ça.
3. **Élargir la fenêtre de tangente à 5 m — pour coller au lissage du moteur — aggrave**, 0,51 →
   0,69 m. Une corde de 10 m ne dit rien d'une épingle de 5 m de rayon, et les épingles sont
   précisément là où les décalages sont grands. Ramenée à 1 m.

Les 0,51 m restants sont un plancher : le moteur prend sa normale sur un repère lissé, et aucune
corde passant par les points enregistrés ne le reproduit là où la route tourne brutalement entre
deux échantillons. Le seuil d'alerte est donc à 1,5 m — au-dessus du bruit d'épingle, bien en
dessous des ~6 m qu'afficherait une normale inversée.

La fenêtre de tangente est exprimée **en mètres et non en nombre de points**, ce qui est la leçon
de R23 appliquée à la démo : les deux appelants n'ont pas le même pas d'échantillonnage.

### Ce que la démo expose

Onglet Options, section « Trajectory » : estimation de courbure (R23, activée par défaut) et
racing line (R24, désactivée par défaut, avec corridor et largeur de route). La largeur par défaut
est **6 m parce que c'est `RacingLineOptions.defaultRoadWidthM`**, pas parce que 6 semblait
raisonnable — la règle posée par `43` après l'affaire des 250/280 W.

### Reste ouvert

Les quatre points non cochés ci-dessus : les `*_KEYS` à rapprocher de leurs interfaces, un test
par clé sur les deux façades, les lignes R23/R24/R26 dans la matrice et le ledger, et le sort de
la phase T dans `PLAN.md`.

## Notes

- **Ordre de fusion recommandé** : `feat/demo-update` d'abord, `feat/racing-line` ensuite.
  L'ordre ne change presque rien aux conflits — un rebase rejouerait les mêmes quatre hunks — mais
  il décide quelle branche porte la présente fiche, et il fait atterrir racing-line dans un dépôt
  qui documente déjà l'obligation des quatre surfaces.
- **Le rebase ne referme pas le trou.** En rejouant `8c0e1bd` par-dessus `43`, le hunk qui ajoute
  les champs au DTO s'applique proprement et la liste blanche reste inchangée : même angle mort.
  Seule l'étape 2 le ferme.
- **Ce que la fusion ne casse pas, contrairement à ce qu'on pourrait croire.** Les cinq champs
  `nanDefault` de racing-line ne mettent pas en échec le test de `41` qui compare les 38 champs
  entre deux exécutions : `kotlin.test` compare `toBits()` avant d'appliquer la tolérance, et
  `NaN.toBits() == NaN.toBits()`. Vérifié dans les sources de `kotlin-test`, pas supposé.
- **Convergence à noter.** Racing-line est arrivé de son côté à la convention du catalogue de
  `43` : `CorridorMode.byId` vit dans `commonMain` avec, presque mot pour mot, le même argument
  (« so the CLI, the JS DTO and the WASI ABI cannot drift apart »). Et `t11` a remplacé le `38`
  codé en dur d'`EngineJsApiCourseTest` par `PointField.COUNT`. Les deux branches ont le même
  réflexe ; il n'y a rien à réarchitecturer.
- **Divergence de langue.** Les fiches `g`, `w` et `40`–`43` sont en français, la série `t` est en
  anglais. Celle-ci suit sa propre série. À trancher une fois pour le dépôt plutôt qu'au cas par
  cas — et le ledger, lui, est déjà en anglais.
