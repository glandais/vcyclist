# w01 — Étendre la cible `wasmWasi` à `:fit` et `:engine`

## Goal

La cible `wasmWasi { wasmtime() }` n'existe que sur `:gpx` et `:elevation`. Le `.wasm` visé par
ce plan est celui de `:engine`, qui dépend de `:gpx`, `:elevation` **et** `:fit` via `api(...)` :
tant que `:fit` et `:engine` ne compilent pas pour WASI, il n'y a pas de binaire à produire.

À la fin de cette fiche, `./gradlew check` compile et teste les quatre modules du cœur sous
wasmtime, sans échec.

## Depends on

- Le POC de la branche (Kotlin 2.4.20-Beta2, cible sur `:gpx` / `:elevation`).
- Rien d'autre — c'est la première fiche du plan.

## Inputs

- [`docs/kotlin-wasm-wasi.md`](../kotlin-wasm-wasi.md) — **à lire avant de commencer** : DSL,
  dépendances, `actual` déjà écrits, rugosités de la Beta.
- `gpx/build.gradle.kts` et `elevation/build.gradle.kts` — la forme exacte du bloc à recopier.
- `fit/src/commonMain/kotlin/io/github/glandais/fit/FitEncoder.kt:22` — le seul `expect` de `:fit`.
- `fit/src/jsMain/…/FitEncoder.js.kt` — la forme de l'`actual` à imiter.
- `elevation/src/wasmWasiMain/…/TileFetcher.wasmWasi.kt` — le modèle de stub déjà en place.
- `elevation/src/commonTest/…/TileDecodeSplitTest.kt` — les 7 tests qui échouent.

## Steps

### 1. `:fit`

Ajouter le bloc `wasmWasi { wasmtime() }` (sans `binaries.executable()` : c'est une
bibliothèque) et écrire `fit/src/wasmWasiMain/…/FitEncoder.wasmWasi.kt`.

L'`actual` lève `UnsupportedOperationException` avec un message qui **nomme la raison et la
sortie** : pas de SDK Garmin sous WASI, voir w12. Ne pas retourner un `ByteArray` vide — un
fichier FIT tronqué silencieusement est pire qu'une exception.

### 2. `:engine`

Même bloc, **plus** `binaries.executable()` : c'est ce module qui produit le `.wasm` final
(w06). Vérifier qu'aucun `expect` de `commonMain` n'est laissé sans `actual` — a priori aucun,
`:engine` n'en déclare pas, mais le compilateur tranchera.

### 3. Sort des 7 tests `TileDecodeSplitTest`

Ils exercent `decodeTileBytes`, stubbé sous WASI. Deux options :

| Option | Effet | Coût |
|---|---|---|
| **A. Déplacer** ces tests de `commonTest` vers `jvmTest` + `jsTest` | wasmWasi vert, couverture identique sur les cibles qui décodent | duplication du fichier de test, ou source-set partagé |
| B. Les garder et attendre w11 | 7 rouges permanents jusqu'au décodeur pur Kotlin | inacceptable pour la CI de w02 |

**Retenir A.** Créer un source-set de test partagé (`elevation/src/decodingTest/kotlin`, ajouté
en `srcDir` par `jvmTest` et `jsTest`, à la manière de `commonTestFixtures` dans
`gpx/build.gradle.kts`) plutôt que de dupliquer le fichier. Documenter en tête du fichier
pourquoi il n'est pas dans `commonTest`.

Ajouter en contrepartie **un test wasmWasi** qui affirme le contrat du stub : `decodeTileBytes`
lève, et le message cite le seam d'injection. Un stub non testé est un stub qui dérive.

### 4. Vérifications transverses

- `tasks.withType<KotlinJsTest>` (propagation `INTEGRATION`, timeouts) s'applique aussi à
  `wasmWasiWasmtimeTest` : le vérifier, pas le supposer.
- Le premier `check` télécharge wasmtime dans `~/.gradle/wasmtime/` — noter la durée pour w02.

## Outputs

- `fit/build.gradle.kts`, `engine/build.gradle.kts` — bloc `wasmWasi`.
- `fit/src/wasmWasiMain/…/FitEncoder.wasmWasi.kt`.
- `elevation/src/decodingTest/kotlin/…/TileDecodeSplitTest.kt` (déplacé) + wiring Gradle.
- `elevation/src/wasmWasiTest/…/TileFetcherStubTest.kt`.

## Validation

- [ ] `./gradlew check` vert — y compris `:fit:wasmWasiWasmtimeTest`, `:engine:wasmWasiWasmtimeTest`.
- [ ] Aucun test perdu : le total avant/après est identique par cible (JVM, JS Node, JS browser).
- [ ] `./gradlew ktlintCheck` vert, arbre de travail propre.

## Done when

Les quatre modules du cœur compilent et testent sous wasmtime, sans échec ni test désactivé.

## Notes

Ne pas ajouter `nodejs()` à côté de `wasmtime()` : le POC a montré que wasmtime suffit et
s'auto-provisionne. L'avertissement `⚠️ JS Environment Not Selected` de la Beta2 est inoffensif
(cf. `kotlin-wasm-wasi.md` §1) — le re-vérifier en w08, pas ici.
