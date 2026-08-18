# 45 — `elapsed` et `dt` : une seule unité, la seconde

## Goal

`PointField.ELAPSED` et `PointField.DT` déclarent `"ms"`. Selon le moment du pipeline, ils
portent des millisecondes **ou** des secondes :

| Écrivain | Moment | Unité écrite |
|---|---|---|
| `VirtualizeService` (`:83`, `:108`, `:110`) | étape 6 | **ms** |
| `Path.computeDerivedData` (`:218`, `:233`) | dernière, après chaque mutation de cardinalité | **s** |

`computeDerivedData` court en dernier : un chemin fini porte donc des **secondes** sous deux
champs étiquetés ms. Ce n'est pas seulement une gêne interne — `CsvWriter.headerFor`,
le bloc `meta.units` de `JsonWriter` et le `fieldDefinitions` d'`EngineJsApi` publient tous
la chaîne `"ms"` à côté d'un nombre de secondes. **L'unité déclarée est fausse sur la surface
de sortie.**

Le coût s'est déjà payé une fois : `WPrimeBalanceComputer` a lu `dt` et sous-intégré tout le
bilan W′ d'un facteur 1000 (ledger R16), jusqu'à ce qu'une mesure le rattrape. Rien dans le
type ne pouvait l'attraper — un `Double` est un `Double`.

Le sens retenu est la **seconde**, parce que c'est ce que la valeur *survivante* vaut déjà :
corriger l'étiquette ne change aucun nombre exporté. L'inverse (réécrire `computeDerivedData`
en ms) changerait la sortie de tous les consommateurs, démo et fichiers CSV/JSON archivés
compris, et divergerait de la référence TS (`Path.ts:219`) sans rien gagner.

## Depends on

- rien

## Inputs

- `gpx/src/commonMain/…/path/PointField.kt` (`:60-61`)
- `gpx/src/commonMain/…/path/Path.kt` (`:218`, `:233`) — déjà en secondes, ne bouge pas
- `engine/src/commonMain/…/physics/VirtualizeService.kt` (`:83`, `:85`, `:108`, `:110`)
- Les **cinq** lecteurs, tous compensant déjà par `/ 1000.0` : `PowerComputer.kt:164`,
  `PowerProviderDurability.kt:102`, `PowerProviderSlewLimited.kt:73`,
  `PowerProviderTerrainPacing.kt:139`, `PowerProviderCriticalPower.kt:104`
- `CLAUDE.md` § *`elapsed` et `dt` sont des secondes après le pipeline, pas des ms* — documente
  aujourd'hui le bug comme une règle
- `engine/src/commonMain/…/physiology/WPrimeBalanceComputer.kt:86-92` — le commentaire qui
  raconte R16 ; à réécrire une fois la cause disparue

## Steps

### 1. Déclarer la seconde

`PointField.kt` : unité `"s"`, descriptions `"Elapsed duration (s)"` / `"dt (s)"`. Les
**ordinaux ne bougent pas** (format de fil), les `prop` non plus (clés des façades) : aucune
régénération `:codegen`, aucun `COUNT` à resynchroniser.

Ajouter à la description de `DT` le fait que sa **fenêtre** change, ce que l'unité seule ne dit
pas : intervalle arrière `t(i) − t(i−1)` pendant la simulation, demi-intervalle **centré**
`(t(i+1) − t(i−1))/2` après `computeDerivedData`. `dx` fait pareil et `speed = dx/dt` tient dans
les deux cas — c'est cohérent, mais ça se lit comme une seule chose alors que c'en est deux.

### 2. Écrire des secondes dans `VirtualizeService`

- `:83` et `:108` → `(timeMs − startTimeMs) / 1000.0`
- `:110` → `out.setDt(i, dt)` : `dt` y est **déjà** en secondes, le `* 1000.0` est du dégât pur
- `:85` → `0.0` inchangé

### 3. Supprimer les cinq `/ 1000.0`

Aux quatre sites de lecture ci-dessus. Ils tournent *pendant* la simulation, là où les ms
étaient encore vraies ; une fois la seconde écrite à la source, la conversion est en trop.

### 4. Corriger les fixtures de test qui fabriquent des chemins en ms à la main

`PowerProviderDurabilityTest.kt:39`, `PowerProviderSlewLimitedTest.kt:37`,
`PowerProviderCriticalPowerTest.kt:39`, `PowerProviderTerrainPacingTest.kt:150`,
`PowerComputerTest.kt:224`, plus le commentaire `PowerProviderSlewLimitedTest.kt:182` qui
constate l'incohérence. `PointFieldTest` tient un ensemble fermé d'unités : `"s"` s'y ajoute,
`"ms"` y reste pour `TIME` seul.

### 5. Le garde-fou

Ce qui a manqué, c'est un point de rupture. Épingler que les deux champs **s'accordent avec
`time`**, qui n'a qu'un sens partout (ms) :

- après `Enhancer` : `elapsed(i) ≈ (time(i) − time(0)) / 1000` et
  `dt(i) ≈ (time(i+1) − time(i−1)) / 2000` sur tous les points ;
- après `VirtualizeService` seul, la même assertion mi-pipeline avec la fenêtre arrière —
  pour que les deux moments soient tenus par la même convention.

### 6. Documentation

`CLAUDE.md` : remplacer la section qui documente le bug par la règle (seconde partout, `time`
en ms) et la note sur la fenêtre de `dt`. `WPrimeBalanceComputer` : garder le *pourquoi* de
R16 (lire `time` reste le plus sûr) sans la justification devenue fausse.

## Outputs

- `PointField` déclare `"s"` pour `ELAPSED` et `DT`
- Un seul sens par champ sur tout le pipeline
- Deux assertions de garde, une mi-pipeline, une en sortie
- CSV / JSON / `fieldDefinitions` publient l'unité qu'ils portent réellement

## Validation

- [x] `./gradlew check` vert
- [x] `./gradlew ktlintCheck` vert
- [x] Les métriques de sortie sont **inchangées** — voir *Done when*

## Done when

- [x] Aucune multiplication ni division par 1000 n'entoure plus `elapsed` / `dt`
- [x] Les résultats physiques sont inchangés à l'ULP près : chaque lecteur convertissait déjà en
      secondes, on retire une paire multiplication-division, pas une valeur. Attention, ce n'est
      **pas** bit-identique : `×1000` puis `÷1000` n'est pas l'identité en binaire, donc retirer
      l'aller-retour retire aussi un arrondi. Mesuré sur `sample.gpx` (1028 points) : 2 lignes
      diffèrent d'**un ULP** sur `pComputedWheelPower` / `pBrake`
      (`−2634.951416066705` → `…704`), distance et durée identiques au chiffre près
- [x] `CLAUDE.md` ne documente plus le bug comme une règle

## Notes

Aucune des quatre surfaces (cœur, CLI, JS, WASI) ne gagne ni ne perd d'option : c'est un
changement d'unité déclarée, pas de capacité. La chaîne `"ms"` publiée par `fieldDefinitions`
devient `"s"` — un consommateur qui l'affichait montrait déjà la mauvaise unité.

La référence TS porte la même erreur (`fieldDefinitions.ts` déclare `unit: 'ms'` pour les deux,
`Path.ts:219` écrit des secondes). Divergence assumée : elle corrige, elle ne dérive pas.
