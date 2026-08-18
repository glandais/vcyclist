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

- [x] Sec/mouillé dans l'onglet Cyclist, au-dessus de l'angle de carre
- [x] Angle de pédalage dans l'onglet Bike, `BikeDto` du shim complété
- [x] Quatre modèles de puissance + deux décorateurs indépendants
- [x] CP/W′ du champ W′bal, divergence avec le modèle de puissance traitée explicitement
- [x] Presets et persistance à jour
- [x] Cas 1 vérifié : la config par défaut ne bouge pas
- [x] typecheck + lint + build verts, cas passés dans le navigateur

## Résultat

typecheck + lint + build verts. Validation menée dans Chrome sur `stelvio.gpx` (3,5 km, épingles),
zéro erreur console sur toute la session.

| Cas | Mesure |
|---|---|
| Sec → mouillé | 633 s → **674 s**, soit **+6,5 %** — l'ordre de grandeur du +7,1 % que le ledger mesure sur la même trace |
| `critical-power` + allure + lissage | 583 s, soit −7,9 % contre le `constant` sec |
| Angle de pédalage 20 → 90 | l'encart bascule sur « cut-off désactivé » |
| Champ `wPrimeBalance` tracé | part de 20 kJ, tombe à ~0 au sommet (1,4 km), remonte à ~11 kJ dans la descente |
| Rechargement | `roadCondition`, angle de pédalage, modèle, CP, W′, allure, lissage, champs sélectionnés : tout relu |

La courbe W′bal est la vérification la plus parlante des cinq : sa forme — vidange en montée
au-dessus de CP, recharge exponentielle en descente — est celle que décrit la physiologie, et elle
est calculée avec le CP/W′ du modèle de puissance, ce qui prouve le chaînage complet UI → DTO →
moteur → champ → graphe.

Le −7,9 % est **le cas dont l'encart d'allure prévient** : `stelvio.gpx` est une montée pure, la
règle n'a rien à redistribuer *vers*, elle se contente d'augmenter la puissance. La démo le dit
maintenant à l'écran plutôt que de laisser lire un gain de 8 % comme une meilleure gestion.

### Décisions prises à l'implémentation

- **Le lissage est une case, pas un curseur.** Le moteur prend un taux en W/s (fiche `41`), la démo
  n'expose que le choix marche/arrêt et envoie `SLEW_W_PER_S = 50`. C'est une borne de modélisation
  de Zignoli & Biral, pas une propriété mesurée d'un coureur ; un curseur donnerait à croire le
  contraire. Le taux reste réglable via l'API.
- **Les décorateurs ne s'appliquent pas à `from_data`.** L'UI les masque pour ce modèle ; le
  builder de DTO fait désormais pareil. Sans ça, activer l'allure puis basculer sur « depuis le
  GPX » aurait continué à réécrire silencieusement la puissance enregistrée.
- **`linkToPowerModel`, coché par défaut**, plutôt que deux CP indépendants et muets. La fiche
  laissait le choix entre lier et avertir : deux CP qui divergent en silence produisent une trace
  W′bal qui ne décrit pas le coureur affiché à côté, et c'est le cas surprenant, pas l'utile.
- **`maxBrakeG` plafonné à 0,6** dans le curseur : au-delà de ~0,63 g le vélo bascule par-dessus la
  roue avant, quelles que soient les gommes. Le curseur montait à 0,8.
- **La condition de route n'entre pas dans les presets** mais devait survivre à leur application :
  `applyPreset` la reconduit explicitement, sinon choisir « Pro » séchait la route en silence.
- **La migration de config est devenue générique** : elle complète depuis `DEFAULT_CONFIG` toute
  clé que la version enregistrée ne connaissait pas, au lieu de traiter le seul cas R17. Une clé
  absente qui atteint le DTO en `undefined` se lit côté moteur comme « prends ton défaut », ce qui
  est indiscernable d'un choix délibéré.

## Notes

- **Ce qui ne demande rien.** `fieldConfig.ts` reconstruit ses catégories depuis
  `fieldDefinitions()` à l'exécution : `wPrimeBalance` et `pBrake` apparaissent déjà dans la
  sidebar et se tracent. C'est le seul endroit de la démo qui n'a pas décroché du moteur, et c'est
  parce qu'il ne recopie rien.
- **Ne pas exposer les magnitudes** de R18/R19 (50 W/s, gains de pente, fenêtre de dispersion) :
  `41` explique pourquoi elles ne sont pas dans l'API.
- **R14 et R20 restent absents** — ils ne sont pas implémentés. Le ledger les diffère sur les
  preuves, pas sur l'effort.
