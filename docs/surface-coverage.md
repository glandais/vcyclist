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

Les deux champs de sortie (R12, R15) traversent sans travail de façade : `fieldDefinitions()` les
publie et la démo reconstruit sa liste à l'exécution. **Les champs de sortie ne dérivent pas ; les
options d'entrée, si.**

## Ce qui empêche la prochaine dérive

- **Le catalogue partagé** — `PowerModel` + `CyclistPowerSpec` dans `commonMain`. Le `when` qui
  fait modèle → provider, l'ordre de composition `base → pacing → slew` et les défauts existent
  **une fois**. Ajouter un modèle casse la compilation de `commonMain` tant qu'il n'est pas
  traité, donc sur les trois cibles à la fois, avant tout test.
- **Le contrôle strict des clés** — JS et WASI refusent une clé qu'ils ne lisent pas. C'est ce qui
  transforme un `tiringDuration` oublié en erreur plutôt qu'en réglage silencieusement ignoré.

Aucun des deux ne couvre une capacité qui **n'est pas** un modèle de puissance : R9 vit sur
`Cyclist`, R15 sur `EnhanceOptions`. Pour celles-là, ce tableau est la garantie, et elle vaut ce
que vaut une relecture.

## À faire en ajoutant une capacité

1. **Cœur** — `commonMain`, avec ses tests.
2. **CLI** — une option dans le mixin, dont le défaut vient d'`EngineConstants` (jamais un
   littéral : c'est ainsi que JS et WASI ont défendu 250 W pendant que le CLI défendait 280 W).
3. **JS** — le champ sur le DTO **et** dans l'allowlist de `requireOnlyKeys`.
4. **WASI** — le champ et sa clé dans `requireOnly`, plus [`wasm-wasi-abi.md`](wasm-wasi-abi.md).
5. **Démo** — `engine-shim.ts` (à la main), puis l'UI si la capacité a un sens pour un humain.
6. **Ledger** — la ligne `Surfaces` de l'entrée, si c'en est une.

Un modèle de puissance saute les étapes 2 à 4 : le catalogue les fait.
