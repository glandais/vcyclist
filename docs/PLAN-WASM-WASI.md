# PLAN-WASM-WASI — Publier un `.wasm` vcyclist utilisable sous wasmtime

Plan **distinct** de [`PLAN.md`](PLAN.md) (port TypeScript, tâches 00-39) et de
[`PLAN-GPX2WEB.md`](PLAN-GPX2WEB.md) (port Java, tâches g01-g32). Numérotation `w01`…, fiches
dans [`tasks/`](tasks/).

## Objectif

**Produire et publier un module `.wasm` autonome exécutant le pipeline vcyclist, instanciable
par n'importe quel runtime WASI — wasmtime au minimum — sans hôte JavaScript.**

« Au minimum wasmtime » veut dire : wasmtime CLI et wasmtime-py sont les cibles de validation ;
rien dans l'ABI ne doit être spécifique à wasmtime, pour que WasmEdge, wasmer, wazero ou un
embarquement Go/Rust/Java fonctionnent aussi.

Le cadrage technique — ce qui est **déjà validé par le POC** de cette branche — est dans
[`docs/kotlin-wasm-wasi.md`](kotlin-wasm-wasi.md). Ce plan n'y revient pas : il enchaîne ce
qu'il reste à faire pour passer d'un POC (`:gpx` seul, 4 exports, piloté à la main depuis
Python) à un artefact publié et documenté.

## Point de départ (état de la branche `feat/wasm-wasi`)

| Acquis | Détail |
|---|---|
| Kotlin 2.4.20-Beta2 | `./gradlew check` vert, `kotlin-js-store/yarn.lock` régénéré |
| Cible `wasmWasi { wasmtime() }` | déclarée sur `:gpx` et `:elevation` uniquement |
| Tests sous wasmtime | `:gpx` 240/240 ; `:elevation` 191/198 (les 7 = décodage WebP stubbé) |
| `actual` WASI | `TileFetcher.wasmWasi.kt`, `IntegrationGate.wasmWasi.kt` (`:elevation`) |
| `.wasm` autonome | 145 Ko, façade POC `GpxWasiApi` (4 exports), piloté par wasmtime-py |
| Publication klib | variantes `-wasm-wasi` produites par vanniktech sans configuration |

Manquent donc : la cible sur `:fit` et `:engine`, une façade digne de ce nom, l'élévation, le
packaging/publication du binaire, la CI, et la documentation utilisateur.

## Architecture cible

```
                    hôte WASI (wasmtime CLI, wasmtime-py, Go, Rust, JVM…)
                              │  imports "vcyclist" : read_input / write_output / fetch_tile
                              ▼
      ┌───────────────────────────────────────────────────────────┐
      │  vcyclist-engine.wasm  (module réacteur, section `start`) │
      │  engine/src/wasmWasiMain/.../wasi/EngineWasiApi.kt        │
      │    exports @WasmExport numériques + handles Int           │
      └───────────────────────────────────────────────────────────┘
        :engine  →  :gpx  →  :elevation        :fit (stub WASI)
```

Modules concernés : `:gpx`, `:elevation`, `:fit`, `:engine`. `:map`, `:cli`, `:codegen` et
`:demo` sont hors périmètre (JVM-only ou JS-only), et l'invariant « rien ne dépend de `:map` »
reste intact.

## Décisions structurantes

1. **Une seule façade, dans `:engine`** (`EngineWasiApi`), miroir fonctionnel de `EngineJsApi`.
   Le POC `GpxWasiApi` de `:gpx` est absorbé (w03) : deux façades signifieraient deux `.wasm`.
2. **ABI à callbacks, API publique uniquement** — `withScopedMemoryAllocator`, jamais
   `@ComponentModelInternalApi`. Protocole figé et versionné en w03.
3. **Structures complexes en JSON UTF-8**, pas en dizaines d'exports numériques. Les writers
   JSON existent déjà côté `:gpx` (g07), et un hôte quelconque sait parser du JSON.
4. **Le réseau et le décodage d'images restent chez l'hôte.** WASI n'a ni client HTTP ni
   décodeur ; le seam `TileManager(fetcher = …)` de g21 est le point d'injection (w05).
5. ~~**FIT n'est pas porté** sur WASI~~ — **levé en w12** : `FitEncoder` est passé en
   `commonMain` sur `io.github.glandais:fit-kotlin-sdk` (SDK FIT multiplateforme). Un seul
   encodeur, sortie identique octet pour octet sur les quatre cibles, et les deux SDK Garmin
   (`com.garmin:fit`, `@garmin/fitsdk`) sortent du périmètre publié. Coût : +183 Ko de `.wasm`.
6. **Publication du binaire séparée de celle des klib.** Les variantes `-wasm-wasi` (klib) sont
   déjà publiées gratuitement ; le `.wasm` exécutable est un artefact supplémentaire (w07).

## Avancement

| # | Tâche | Module | État |
|---|---|---|---|
| **— Phase A : la cible sur tout le cœur —** | | | |
| w01 | Étendre `wasmWasi` à `:fit` et `:engine`, stub FIT, sort des 7 tests WebP | `:fit` `:engine` `:elevation` | ✅ |
| w02 | CI : `wasmWasiWasmtimeTest` dans `check` + cache wasmtime | build | ✅ |
| **— Phase B : ABI et façade —** | | | |
| w03 | `EngineWasiApi` — ABI v1 figée, absorption du POC `GpxWasiApi` | `:engine` `:gpx` | ✅ |
| w04 | Parité fonctionnelle avec `EngineJsApi` (enhance, cols, exports, vent) | `:engine` | ✅ |
| w05 | Élévation host-injectée : import `fetch_tile` + pont `suspend`→synchrone | `:engine` `:elevation` | ✅ |
| **— Phase C : packaging et publication —** | | | |
| w06 | Tâche Gradle de distribution du `.wasm` (nom stable, taille, checksum) | `:engine` build | ✅ |
| w07 | Publier le `.wasm` : Maven Central (classifier) + asset de release GitHub | build docs | 🟡 |
| w08 | Passage à Kotlin 2.4.20 final et re-vérifications | build | ⬜ |
| **— Phase D : hôtes de référence et documentation —** | | | |
| w09 | Harnais d'hôtes `tools/wasi/` : wasmtime CLI + wasmtime-py, joué en CI | tools | ✅ |
| w10 | Documentation : `docs/wasm-wasi-abi.md`, README, `publishing.md` | docs | ✅ |
| **— Phase E : autonomie complète (optionnel) —** | | | |
| w11 | Décodeur WebP/VP8L pur Kotlin — `:elevation` autonome sous WASI | `:elevation` | ✅ |
| w12 | Encodeur FIT pur Kotlin — `pathToFit` sous WASI | `:fit` `:engine` | ✅ |

🟡 = plomberie livrée et vérifiée en local, publication réelle en attente de w08 (Kotlin 2.4.20
n'est pas sortie : Maven Central s'arrête à `2.4.20-Beta2`).

Chemin critique minimal pour « un `.wasm` publié et utilisable sous wasmtime » :
**w01 → w03 → w04 → w06 → w08 → w07**, w02/w09/w10 en accompagnement. w05 est ce qui rend
`fixElevation` utilisable ; w11/w12 ne sont requis que pour supprimer toute dépendance à l'hôte —
faits tous les deux, donc le module est autonome : plus rien n'est délégué à l'hôte hormis le
réseau.

## Ce qui n'est explicitement pas fait

- **Le Component Model / WIT / WASI Preview 2.** Kotlin n'émet pas de composant ; on reste sur
  un module core WASI Preview 1 avec des imports custom. Un wrapper `wit-component` reste
  possible côté hôte, hors périmètre.
- **Un `.wasm` par module.** Un seul binaire, celui de `:engine`, qui contient tout le cœur.
- **La publication npm d'un paquet WASI.** WASI n'est pas un artefact npm ; les paquets
  `@glandais/vcyclist-*` restent Kotlin/JS.
- **Le retour de la cible `wasmJs`** (retirée du projet) : `wasmWasi` ne la remplace pas et ne
  la ressuscite pas.

## Conventions

Identiques au reste du dépôt (voir [`CLAUDE.md`](../CLAUDE.md)) : fiche par tâche dans
`docs/tasks/wNN-slug.md`, deux commits par tâche
(`feat(engine|elevation|fit|build): … (Phase WASI tâche wNN)` puis
`docs(plan): mark task wNN done in PLAN-WASM-WASI.md`), scope `build` pour les tâches purement
Gradle/CI. La colonne État de la table ci-dessus est la source de vérité de l'avancement.
