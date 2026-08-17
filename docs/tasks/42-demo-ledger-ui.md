# 42 — Démo : UI des modèles issus du ledger recherche

## Goal

Une fois `41` livrée, la démo peut atteindre R9, R15, R16, R18, R19 — et R10, déjà exposé par la
façade depuis `903e4cd` mais absent du shim et de l'onglet Bike. Cette fiche les met dans l'UI.

C'est la fiche qui rend visible le travail des neuf entrées du ledger : aujourd'hui la démo simule
un coureur d'avant R9, sur route toujours sèche, à puissance constante, qui pédale à fond dans les
épingles.

## Depends on

- `40` (forme réparée de `PowerParams`)
- `41` (la façade expose R9/R15/R16/R18/R19)
- R10 (livrée, `BikeDto.maxPedalingLeanAngleDeg` déjà exposé — rien à attendre côté moteur)

## Inputs

- `demo/src/types.ts` — `PowerParams`, `Config`, `PRESETS`
- `demo/src/engine-shim.ts` — types DTO (à compléter pour R10 : `maxPedalingLeanAngleDeg` manque
  alors que le moteur l'accepte déjà)
- `demo/src/composables/{useGPXDemo,useConfigPersistence}.ts`
- `demo/src/components/{PowerTab,CyclistTab,BikeTab,EnhanceOptionsTab}.vue`
- `demo/src/config/fieldConfig.ts` — construit ses catégories depuis `fieldDefinitions()`, donc
  `wPrimeBalance` (#37) et `pBrake` (#38) sont **déjà** sélectionnables : rien à faire pour eux
- `docs/research/improvements-ledger.md`

## Steps

### 1. Onglet Cyclist — R9

Un sélecteur sec / mouillé. À placer **au-dessus** de « Max Lean Angle », dont il écrase la
valeur : l'ordre visuel doit dire lequel gagne.

Le ledger vaut l'infobulle : mouillé, c'est 40 % de l'adhérence, et le freinage baisse en même
temps que le virage — la démo ne doit pas laisser croire que c'est un curseur de virage.

Question ouverte à trancher à l'implémentation : re-exprimer « Max Lean Angle » en µ, comme le
ledger le recommande (« every source uses that form »). Un angle de carre parle davantage à un
cycliste qu'un coefficient de friction ; garder les degrés et mettre µ dans l'infobulle est
probablement le bon compromis pour une démo.

### 2. Onglet Bike — R10

Un slider `maxPedalingLeanAngleDeg` (défaut 20°, `90` désactive). Ajouter le champ à
`BikeDto` dans `engine-shim.ts` — il manque, alors que le moteur le lit déjà.

À dire dans l'aide : ça ne coûte que 0,25-0,35 % de temps, mais ça change la **trace de
puissance** — c'est une correction du tracé, pas du chrono. Sans cette phrase, un utilisateur qui
regarde le temps total conclura que l'option ne sert à rien.

### 3. Onglet Power — R16, R18, R19

Le sélecteur passe de trois à quatre modèles : `constant`, `durability`, `critical-power`,
`from_data`. `critical-power` ouvre deux sliders, CP (W) et W′ (kJ — l'unité que lisent les
cyclistes ; convertir en J au bord du DTO).

R18 et R19 sont des décorateurs : deux cases à cocher **hors** du sélecteur, applicables à
n'importe quel modèle. Les grouper visuellement et les nommer par leur effet, pas par leur classe
(« Lisser les changements de puissance », « Adapter l'effort au terrain »).

Pour l'allure terrain, la mesure honnête du ledger mérite d'être reprise : le gain est réel et de
l'ordre de 1-3 % sur du vallonné, mais sur une montée pure comme `stelvio.gpx` la règle n'a rien à
redistribuer et se contente de « rouler plus fort ». Une démo qui affiche −7,8 % sur Stelvio sans
le dire ment par omission.

### 4. Onglet Enhance — R15

CP et W′ du champ `wPrimeBalance`, plus l'interrupteur. Point à traiter, pas à ignorer : ce CP est
distinct de celui du modèle de puissance. Soit les lier par défaut avec possibilité de délier,
soit afficher un avertissement quand ils divergent — mais deux sliders indépendants et muets
produiraient une trace W′bal qui ne décrit pas le coureur affiché à côté.

### 5. Presets

Les trois presets gagnent CP et W′. Ne pas leur donner de condition de route : c'est une propriété
de la sortie du jour, pas du cycliste.

### 6. Persistance

Chaque champ nouveau doit avoir un défaut à la relecture — `40` a posé la passe de migration, il
suffit de la nourrir. Un `undefined` qui traverse jusqu'au DTO se lit comme « valeur par défaut du
moteur » et non comme une erreur, ce qui rend l'incohérence silencieuse.

## Outputs

Modifiés :

- `demo/src/types.ts`, `demo/src/engine-shim.ts`
- `demo/src/composables/{useGPXDemo,useConfigPersistence}.ts`
- `demo/src/components/{PowerTab,CyclistTab,BikeTab,EnhanceOptionsTab}.vue`
- `demo/README.md`

## Validation

```bash
cd demo && npm run typecheck && npm run lint && npx vite build
```

Puis, dans le navigateur, sur `stelvio.gpx` et `strava.gpx` :

| # | Cas | Attendu |
|---|---|---|
| 1 | Config par défaut | temps identique à pré-42 |
| 2 | Sec → mouillé, `stelvio.gpx` | nettement plus lent (~+7 %) |
| 3 | `critical-power`, trace longue | plus lent que `constant` ; `wPrimeBalance` descend et se stabilise |
| 4 | Angle de pédalage 20° → 90° | `pCyclistProvidedMuscular` cesse de tomber à zéro dans les virages |
| 5 | Allure terrain, `strava.gpx` | plus rapide à puissance moyenne plus basse |
| 6 | Slew, courbe `pComputedPower` | plus de marche verticale au départ ni en sortie de virage |
| 7 | Config sauvegardée puis rechargée | tous les champs nouveaux relus |

Les cas 3, 4 et 6 se lisent sur le graphe : c'est le seul endroit où l'on voit que ces modèles
font quelque chose, puisque plusieurs ne bougent presque pas le chrono. Sélectionner
`wPrimeBalance`, `pBrake` et `pCyclistProvidedMuscular` dans la sidebar pour les observer.

## Done when

- [ ] Sec/mouillé dans l'onglet Cyclist, au-dessus de l'angle de carre
- [ ] Angle de pédalage dans l'onglet Bike, `BikeDto` du shim complété
- [ ] Quatre modèles de puissance + deux décorateurs indépendants
- [ ] CP/W′ du champ W′bal, divergence avec le modèle de puissance traitée explicitement
- [ ] Presets et persistance à jour
- [ ] Cas 1 vérifié : la config par défaut ne bouge pas
- [ ] typecheck + lint + build verts, 7 cas passés dans le navigateur

## Notes

- **Ce qui ne demande rien.** `fieldConfig.ts` reconstruit ses catégories depuis
  `fieldDefinitions()` à l'exécution : `wPrimeBalance` et `pBrake` apparaissent déjà dans la
  sidebar et se tracent. C'est le seul endroit de la démo qui n'a pas décroché du moteur, et c'est
  parce qu'il ne recopie rien.
- **Ne pas exposer les magnitudes** de R18/R19 (50 W/s, gains de pente, fenêtre de dispersion) :
  `41` explique pourquoi elles ne sont pas dans l'API.
- **R14 et R20 restent absents** — ils ne sont pas implémentés. Le ledger les diffère sur les
  preuves, pas sur l'effort.
