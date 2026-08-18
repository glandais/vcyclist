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

- [`docs/kotlin-wasm-wasi.md`](../../guides/kotlin-wasm-wasi.md) — **à lire avant de commencer** : DSL,
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
- **Non prévu par la fiche** : `fit/src/encodingTest/kotlin/…/EncoderBackedTest.kt` (même
  découpage que `decodingTest`, cf. Notes) + `fit/src/wasmWasiTest/…/FitEncoderStubTest.kt`.

## Validation

- [x] `./gradlew check` vert — y compris `:fit:wasmWasiWasmtimeTest`, `:engine:wasmWasiWasmtimeTest`.
- [x] Aucun test perdu : le total avant/après est identique par cible (JVM, JS Node, JS browser).
- [x] `./gradlew ktlintCheck` vert, arbre de travail propre.

## Done when

Les quatre modules du cœur compilent et testent sous wasmtime, sans échec ni test désactivé.

## Notes

Ne pas ajouter `nodejs()` à côté de `wasmtime()` : le POC a montré que wasmtime suffit et
s'auto-provisionne. L'avertissement `⚠️ JS Environment Not Selected` de la Beta2 est inoffensif
(cf. `kotlin-wasm-wasi.md` §1) — le re-vérifier en w08, pas ici.

### Ce qui s'est passé

`:engine` n'avait effectivement aucun `expect` à `actual`iser : la cible ajoutée, il compile et
passe ses 236 tests `commonTest` sous wasmtime sans une ligne de code. `:fit` a demandé le stub
prévu — **et une deuxième application du découpage de l'étape 3**, que la fiche n'avait pas vue :
trois cas de `commonTest` appellent `FitEncoder.encode`, donc échouaient exactement comme les 7
tests WebP. Ils sont partis dans `fit/src/encodingTest/kotlin`, câblé dans `jvmTest` + `jsTest`,
avec le même raisonnement (option A) que `:elevation`. Les fichiers d'origine gardent tout ce qui
s'arrête à `FitCourse` — c'est-à-dire la quasi-totalité — et un commentaire dit où le cas est
parti.

Comptes de tests, mesurés avant / après sur l'arbre stashé :

| Module | JVM | JS Node | wasmWasi |
|---|---|---|---|
| `:gpx` | 254 (=) | 240 (=) | 240 |
| `:elevation` | 225 (=) | 204 (=) | 194 |
| `:fit` | 70 → **71** | 51 → **52** | 42 |
| `:engine` | 242 (=) | 264 (=) | 236 |

Rien n'a été perdu : le déplacement de `TileDecodeSplitTest` est neutre pour JVM/JS (le fichier
est recompilé dans les deux compilations de test), et `:fit` gagne un test — `EncoderBackedTest`
en regroupe trois là où `commonTest` en cédait deux, le cas `case 03` de `PathToFitTimestampTest`
restant sur place pour ses assertions d'horodatage et cédant seulement sa comparaison d'octets.

Les deux stubs sont testés côté wasmWasi (`TileFetcherStubTest`, `FitEncoderStubTest`) : ils
vérifient le *message*, seule documentation qu'un hôte lira, et que tout ce qui entoure le stub
(conversion `Path` → `FitCourse`, `ElevationProvider` alimenté par un fetcher injecté) fonctionne
bien sous WASI. C'est ce qui rend le stub acceptable.

### Vérifications transverses (étape 4)

- `wasmWasiWasmtimeTest` est bien de type `KotlinJsTest` (`./gradlew :elevation:help --task
  wasmWasiWasmtimeTest`), donc les blocs `tasks.withType<KotlinJsTest>` de `:elevation` et
  `:engine` (propagation `INTEGRATION`) s'y appliquent sans modification — vérifié, pas supposé.
- wasmtime était déjà provisionné : `~/.gradle/wasmtime/wasmtime-v46.0.1-x86_64-linux`, **64 Mo**.
  C'est le dossier à cacher en w02 ; la durée de premier téléchargement reste à mesurer sur un
  runner froid.
- `:fit` n'a **pas** `binaries.executable()` : un seul binaire dans le projet, celui de `:engine`
  (w06). `:engine` l'a, et se linke sans `fun main()` — forme réacteur, cf. `kotlin-wasm-wasi.md`
  §5. La façade exportée arrive en w03.
