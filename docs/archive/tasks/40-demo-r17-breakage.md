# 40 — Démo : réparer la rupture R17 (`constant_tiring` → `durability`)

## Goal

R17 a supprimé `PowerProviderConstantWithTiring` du moteur — pas déprécié, **supprimé** — et
renommé le type de la façade JS `"constant_tiring"` → `"durability"`, `tiringDuration` →
`criticalPower`. La démo n'a pas suivi. Sélectionner « Constant with Fatigue » dans l'onglet
Power fait donc lever `EngineJsApi` :

```
IllegalStateException: Unknown PowerProviderDto.type: constant_tiring
```

C'est un des trois modèles de puissance proposés par l'UI : un tiers de l'onglet est mort.

La fiche répare **uniquement** la rupture et les valeurs par défaut devenues fausses. Tout ce que
la façade n'expose pas encore (R9, R15, R16, R18, R19) est hors périmètre — voir `41` et `42`.

## Depends on

- R17 (livrée, `f51c5ed`), R2 (livrée, `dc27bcf`)
- Bloque `42` (l'UI des nouveaux modèles part de la forme réparée de `PowerParams`)

## Inputs

- `demo/src/types.ts` — `PowerSourceType`, `PowerParams`, `Preset`, `PRESETS`, `DEFAULT_CONFIG`
- `demo/src/engine-shim.ts` — `PowerProviderDto`
- `demo/src/composables/useGPXDemo.ts` — `buildPowerProviderDto`
- `demo/src/composables/useConfigPersistence.ts` — la config est persistée en `localStorage`
- `demo/src/components/{PowerTab,ConfigModal}.vue`
- `engine/src/jsMain/…/EngineJsApi.kt:457` — `toCyclistPowerProvider`, la liste des types acceptés
- `docs/research/improvements-ledger.md` — R2 et R17

## Steps

### 1. Le type et son paramètre

`PowerSourceType.constant_tiring` → `durability` ; `PowerParams.tiringDuration` (secondes) →
`criticalPower` (W). Ce n'est **pas** un renommage : les deux grandeurs n'ont aucun rapport. La
durée disait « quand la puissance aura fondu de moitié », CP dit « au-dessus de quoi le travail
compte ». Une conversion de l'une vers l'autre n'existe pas et il ne faut pas en inventer une.

### 2. Les presets

`Preset.tiringDuration` → `criticalPower`. Valeurs retenues ≈ 90 % de la puissance cible du
preset, pour que le modèle ait de quoi mordre :

| Preset | puissance | CP |
|---|---|---|
| beginner | 180 W | 160 W |
| recreational | 230 W | 210 W |
| pro | 340 W | 310 W |

C'est une heuristique de démo, pas une donnée physiologique — le défaut moteur reste
`EngineConstants.DEFAULT_CRITICAL_POWER_W = 250`.

### 3. `maxBrakeG` : R2 n'avait pas été répercutée

Les presets portaient 0.4 / **0.6** / **0.7** g alors que R2 a ramené le défaut à 0.4 et que le
ledger situe la limite géométrique de basculement (pitch-over) à **0.63 g**. Le preset « pro »
freinait donc au-delà du physiquement possible. Retenu : 0.35 / 0.4 / 0.5 — la progression
beginner → pro est conservée, le plafond ne l'est plus dépassé.

### 4. La migration `localStorage` — le piège

`useConfigPersistence` sérialise la `Config` entière et la relit telle quelle. **Réparer les
sources ne suffit donc pas** : un visiteur revenant sur la démo relit `constant_tiring` depuis son
navigateur et casse exactement comme avant. Ajouter une passe de migration dans
`deserializeConfig`, qui traite la valeur stockée comme un sac non typé (elle est antérieure à la
forme courante — un `as` vers le type courant ferait croire au compilateur que la comparaison à
`'constant_tiring'` est morte, ce qu'il signale en TS2367).

`tiringDuration` n'a pas d'équivalent : il est abandonné, CP retombe sur le défaut.

## Outputs

Modifiés :

- `demo/src/types.ts`, `demo/src/engine-shim.ts`
- `demo/src/composables/{useGPXDemo,useConfigPersistence}.ts`
- `demo/src/components/{PowerTab,ConfigModal}.vue`

## Validation

```bash
./gradlew :engine:jsBrowserProductionLibraryDistribution
cd demo && npm ci && npm run typecheck && npm run lint && npx vite build
```

Plus un smoke Node contre le bundle produit, qui est le seul à prouver la réparation : le
typecheck ne voit pas `toCyclistPowerProvider`, qui lève à l'exécution sur un `type` inconnu.

| # | Cas | Attendu |
|---|---|---|
| 1 | `enhanceWithCourse(…, {type:'constant'}, …)` | OK |
| 2 | `enhanceWithCourse(…, {type:'durability', criticalPower}, …)` | OK |
| 3 | `enhanceWithCourse(…, {type:'constant_tiring'}, …)` | lève — c'est ce qu'on réparait |
| 4 | `durability` vs `constant` sur une longue trace | durée **différente** (sinon le modèle est accepté mais pas branché) |

Le cas 4 est le seul qui distingue « le moteur ne refuse plus » de « le moteur fait quelque
chose ». Sur une trace courte les deux coïncident, ce qui est la bonne réponse, pas un échec :
R17 ne facture que le travail au-dessus de CP.

## Done when

- [x] `durability` / `criticalPower` dans les types, le shim, le builder de DTO et l'UI
- [x] Encart « Fatigue Model » de `PowerTab.vue` réécrit — il décrivait une décroissance linéaire
      100 % → 50 % qui n'existe plus nulle part dans le code
- [x] Presets `criticalPower` + `maxBrakeG` corrigés
- [x] Migration `localStorage` en place
- [x] typecheck + lint + build verts, smoke Node vert

## Résultat

Smoke Node contre `engine/build/dist/js/productionLibrary/vcyclist-engine.js` (preset
recreational, `fixElevation: false`) :

| `type` | `stelvio.gpx` | verdict |
|---|---|---|
| `constant` | 655 s | OK |
| `durability` | 655 s | OK |
| `constant_tiring` | — | `IllegalStateException: Unknown PowerProviderDto.type` |

Et sur `sample.gpx` (128 km), 230 W contre CP 210 W : `constant` 21 854 s, `durability`
22 128 s, soit **+1,25 %**. Du même ordre que le +1,6 % mesuré par le ledger sur la même trace à
280 W contre CP 250 W — le modèle est bien branché, pas seulement accepté.

L'égalité à 655 s sur `stelvio.gpx` est le comportement attendu : onze minutes de montée
n'accumulent presque pas de travail supra-CP. C'est précisément ce que R17 corrigeait dans
l'ancien modèle, où la décroissance était fonction du temps écoulé et se serait appliquée
pleinement.

Le bundle Kotlin/JS s'importe en CJS-interop depuis Node : la racine utile est `mod.default`, pas
`mod` — `mod.io` est `undefined` et le shim de la démo tombe alors sur sa branche de repli sans
que rien ne le signale. À savoir pour tout futur smoke.

## Notes

- **Pourquoi une fiche pour une réparation.** Parce que la cause n'est pas une étourderie : la
  démo n'a pas été touchée depuis `dc27bcf` (R1-R4) alors que R9 à R19 ont toutes livré depuis.
  C'est le même trou systémique que `g29` avait constaté sur la façade JS, un cran plus loin dans
  la chaîne. La fiche `43` en tire la garde-fou.
- **Ce que la fiche ne fait pas** : elle ne rend pas `durability` configurable au-delà de CP, ne
  touche pas à l'onglet Cyclist et n'expose aucun des modèles R16/R18/R19. La démo reste en
  retard — elle n'est simplement plus cassée.
