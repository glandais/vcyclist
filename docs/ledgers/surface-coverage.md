# Couverture des surfaces : cœur / CLI / JS / WASI

vcyclist expose le même moteur par **quatre portes**, et une capacité ajoutée au cœur ne les
franchit pas toute seule :

| Surface | Où | Vérifie ses entrées ? |
|---|---|---|
| **Cœur** | `:engine` `commonMain` | — |
| **CLI** | `cli/…/mixin/*.kt` | picocli refuse une option inconnue |
| **JS** | `engine/src/jsMain/…/EngineJsApi.kt` | oui depuis `43` (`requireOnlyKeys`) |
| **WASI** | `engine/src/wasmWasiMain/…/WasiOptions.kt` | oui (`requireOnly`) |

La **démo** est une cinquième surface, consommatrice de JS via `demo/src/engine-shim.ts`, dont les
types TypeScript sont écrits à la main (Kotlin/JS n'émet aucun corps pour un `external interface`,
donc il n'y a rien à importer ni à comparer).

**La colonne Démo veut dire « atteignable par un humain dans l'UI », pas « réexporté par
`engine-shim.ts` ».** La distinction n'était pas théorique : `writeGpx` était réexporté par le shim
depuis `g29` et **aucun composant ne l'appelait**. Sous l'autre lecture, la ligne aurait affiché ✅
pendant tout ce temps.

Depuis que la démo autonome de `:elevation` a été repliée dans `demo/` (route `#/elevation`), la
démo consomme **deux** façades : `engine-shim.ts` et `demo/src/elevation-shim.ts`
(`ElevationJsApi.kt`). Même contrainte, même piège : un renommage côté Kotlin reste silencieux
jusqu'à l'exécution. `:elevation` n'a ni porte CLI ni porte WASI pour ces trois fonctions
(`newElevationProvider`, `getElevation`, `getElevationsAlong`) — le tableau ci-dessous ne concerne
que les capacités du cœur `:engine`.

## Pourquoi ce tableau existe

Trois fois de suite, une capacité a atterri dans le cœur et le CLI sans atteindre les autres :

| Constat | Fiche | Portée |
|---|---|---|
| g23, g24, g25 livrées sans toucher `EngineJsApi` | `g29` | 3 tâches |
| g26, g27 (partiel) | `g31`, `g33` | 2 tâches |
| R9, R15, R16, R18, R19 livrées sans toucher JS **ni** WASI | `41`, `43` | 5 entrées du ledger |
| La démo **cassée** par le renommage R17 | `40` | 9 entrées de retard |

Le ✅ du ledger voulait dire « livré dans le cœur et le CLI » et se lisait « livré ».

## État

| Capacité | Cœur | CLI | JS | WASI | Démo |
|---|---|---|---|---|---|
| Puissance constante | ✅ | ✅ | ✅ | ✅ | ✅ |
| R17 durabilité (fade sur travail > CP) | ✅ | ✅ | ✅ | ✅ | ✅ |
| R16 critical-power (réserve W′) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Rejeu `from_data` | ✅ | ✅ | ✅ | ✅ | ✅ |
| R19 allure terrain | ✅ | ✅ | ✅ | ✅ | ✅ |
| R18 limite de pente de puissance | ✅ | ✅ | ✅ | ✅ | ✅ |
| R9 condition de route sèche/mouillée | ✅ | ✅ | ✅ | ✅ | ✅ |
| R10 angle de garde au sol des pédales | ✅ | ✅ | ✅ | ✅ | ✅ |
| R15 CP/W′ du champ W′bal | ✅ | ✅ | ✅ | ✅ | ✅ |
| R12 `pBrake` (champ de sortie) | ✅ | ✅ | ✅ | ✅ | ✅ |
| R24 ligne de course (`racingLine*`, opt-in) | ✅ | ✅ | ✅ | ✅ | ✅ |
| R26 largeur de route (`racingLineRoadWidthM`, OSM `highway`) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Export GPX (`writeGpx`, `writeGpxAt`) | ✅ | ✅ | ✅ | ✅ | ✅ |
| Export FIT (`pathToFit`, `pathsToFit`) | ✅ | ✅ | ✅ | ✅ | ✅ |
| `--gpx-power-source` (input / computed / computed-or-input) | ✅ | ✅ | ❌ | ❌ | ❌ |

R23 (courbure par régression de cap) n'a pas de ligne : c'est un changement d'estimateur, sans
option d'entrée, donc rien à relayer.

Les deux champs de sortie (R12, R15) traversent sans travail de façade : `fieldDefinitions()` les
publie et la démo reconstruit sa liste à l'exécution. **Les champs de sortie ne dérivent pas ; les
options d'entrée, si.**

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
2. **`--gpx-power-source` n'a jamais franchi la porte JS.** Le CLI choisit quelle puissance part
   dans le `<power>` du GPX écrit ; `writeGpx` / `writeGpxAt` sont figés sur le défaut `INPUT`.
   Conséquence concrète : le GPX exporté par la démo ne contient **pas** la puissance simulée —
   un fichier issu d'un simulateur de physique sans sa puissance. Le FIT, lui, lit
   `pComputedPower` et la porte. C'est la ligne ❌ ci-dessus, trouvée en câblant la précédente.

## Ce qui empêche la prochaine dérive

- **Le catalogue partagé** — `PowerModel` + `CyclistPowerSpec` dans `commonMain`. Le `when` qui
  fait modèle → provider, l'ordre de composition `base → pacing → slew` et les défauts existent
  **une fois**. Ajouter un modèle casse la compilation de `commonMain` tant qu'il n'est pas
  traité, donc sur les trois cibles à la fois, avant tout test.
- **Le contrôle strict des clés** — JS et WASI refusent une clé qu'ils ne lisent pas. C'est ce qui
  transforme un `tiringDuration` oublié en erreur plutôt qu'en réglage silencieusement ignoré.

Aucun des deux ne couvre une capacité qui **n'est pas** un modèle de puissance : R9 vit sur
`Cyclist`, R15 sur `EnhanceOptions`. Et surtout, aucun des deux ne couvre une **fonction de
sortie** : `pathToFit` et `writeGpxAt` ne prennent pas d'objet d'options, donc `requireOnlyKeys`
ne se déclenche jamais dessus. Les deux dérives ci-dessus (FIT jamais câblé, `--gpx-power-source`
jamais exporté) sont passées exactement par ce trou. Pour celles-là, ce tableau est la garantie, et elle vaut ce
que vaut une relecture.

## À faire en ajoutant une capacité

1. **Cœur** — `commonMain`, avec ses tests.
2. **CLI** — une option dans le mixin, dont le défaut vient d'`EngineConstants` (jamais un
   littéral : c'est ainsi que JS et WASI ont défendu 250 W pendant que le CLI défendait 280 W).
3. **JS** — le champ sur le DTO **et** dans l'allowlist de `requireOnlyKeys`.
4. **WASI** — le champ et sa clé dans `requireOnly`, plus [`wasm-wasi-abi.md`](../guides/wasm-wasi-abi.md).
5. **Démo** — `engine-shim.ts` (à la main), puis l'UI si la capacité a un sens pour un humain.
6. **Ledger** — la ligne `Surfaces` de l'entrée, si c'en est une.

Un modèle de puissance saute les étapes 2 à 4 : le catalogue les fait.
