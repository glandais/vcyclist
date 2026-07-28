# w05 — Élévation host-injectée : import `fetch_tile` et pont `suspend` → synchrone

## Goal

`fixElevation` est la seule étape du pipeline qui a besoin du monde extérieur : télécharger des
tuiles DEM et les décoder. WASI n'offre ni l'un ni l'autre. Cette fiche fait passer la
responsabilité à l'hôte, via un import déclaré, sans rien changer aux autres cibles.

Deux obstacles, et c'est le sujet de la fiche : **(a)** l'hôte ne sait pas décoder du WebP plus
que le guest, **(b)** `ElevationProvider` est `suspend` alors qu'un `@WasmExport` est synchrone.

## Depends on

- `w04` (la façade, dont `vcEnhance`).

## Inputs

- `elevation/src/commonMain/…/TileManager.kt` — le seam `fetcher: suspend (String) -> RawTile`
  introduit par g21 : **le point d'injection, déjà en place**.
- `elevation/src/commonMain/…/TileFetcher.kt` — le split fetch/decode (g21).
- `elevation/src/wasmWasiMain/…/TileFetcher.wasmWasi.kt` — les stubs actuels.
- `docs/elevation-integration.md` — format Terrarium, URL template, tailles de tuile.
- `docs/kotlin-wasm-wasi.md` §6 — règles des scopes d'allocation, réentrance interdite.

## Steps

### 1. Contrat de l'import : des tuiles **déjà décodées**

Décision : l'hôte ne fournit pas un WebP mais une tuile **décodée**, c'est-à-dire les octets RGB
Terrarium bruts (`512 × 512 × 3` pour Mapterhorn — à confirmer sur `RawTile`).

Pourquoi : le décodage WebP est justement ce que ni WASI ni un petit hôte n'ont envie de faire,
alors que tout hôte a une bibliothèque d'images (Pillow, `image` en Go, `image` crate en Rust) ;
et cela évite d'attendre w11. Le décodeur pur Kotlin de w11 reste souhaitable — il rendra
l'import optionnel — mais il ne bloque plus rien.

```
@WasmImport("vcyclist", "fetch_tile")
external fun fetchTile(zoom: Int, x: Int, y: Int, ptr: Int, cap: Int): Int
```

Convention : retour = nombre d'octets écrits, `0` = tuile absente (mer, hors couverture, l'hôte
décide), négatif = erreur hôte. Le guest alloue `cap` d'après la taille attendue de la tuile et
lit la mémoire avant de refermer le scope — même discipline que `read_input`.

Un export `vcSetTileGeometry(size, bytesPerPixel)` ou une constante figée : trancher et
documenter. Une constante figée est acceptable si `:elevation` n'utilise qu'un format.

### 2. Le pont `suspend` → synchrone

`Enhancer.enhance` et `ElevationProvider` sont `suspend`, mais un `@WasmExport` doit rendre une
valeur immédiatement. Il n'y a pas de `runBlocking` disponible ici (le dispatcher WASI de
kotlinx-coroutines est à boucle d'événements, comme JS) — **le vérifier en premier, avant de
concevoir le reste : c'est le risque technique de la fiche.**

Approche recommandée si `runBlocking` est bien absent : puisque le callback `fetch_tile` est
synchrone, la coroutine ne suspend jamais réellement. Démarrer via `startCoroutine` avec une
`Continuation` qui capture le résultat, puis **exiger** qu'elle soit complétée au retour :

```kotlin
private fun <T> runSynchronously(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return (result ?: error("pipeline suspended: no async work is possible under WASI"))
        .getOrThrow()
}
```

Le message d'erreur est important : il transforme une régression future (quelqu'un introduit un
`delay` ou un `withContext` dans le pipeline) en échec explicite plutôt qu'en blocage. Ajouter
un test qui le déclenche volontairement.

### 3. Câblage

- `TileFetcher.wasmWasi.kt` : `fetchAndDecodeTile` appelle l'import et construit un `RawTile` ;
  `decodeTileBytes` continue de lever (le décodage reste hôte) avec un message renvoyant à w11.
- Façade : `vcEnhance` accepte `fixElevation: true` dans son JSON d'options et construit le
  `ElevationProvider` sur ce fetcher — même plomberie que `EngineJsApi.enhance` (tâche 33).
- Si l'hôte n'a pas câblé `fetch_tile`, le module ne s'instancie pas du tout (les imports sont
  obligatoires). Le documenter clairement en w10 : **tout hôte doit fournir les trois imports**,
  quitte à ce que `fetch_tile` retourne toujours `0`.

### 4. Hôte de référence

Étendre le script Python de w09 avec une implémentation réelle de `fetch_tile` (`urllib` +
Pillow pour le décodage WebP), et un test `INTEGRATION`-gated qui compare le profil altimétrique
obtenu à celui de la JVM sur la même trace, à ±1 m (tolérance élévation du projet).

## Outputs

- `elevation/src/wasmWasiMain/…/TileFetcher.wasmWasi.kt` réécrit.
- Le helper `runSynchronously` + son test, dans `engine/src/wasmWasiMain/…/wasi/`.
- `vcEnhance` avec `fixElevation` opérationnel.
- Hôte Python enrichi (w09).

## Validation

- [ ] `./gradlew check` vert.
- [ ] Un test WASI prouve que la suspension réelle échoue avec le message explicite.
- [ ] `INTEGRATION=1` : profil altimétrique WASI vs JVM à ±1 m sur `stelvio.gpx`.
- [ ] Le module s'instancie toujours si `fetch_tile` retourne `0` (chemin « pas de DEM »).

## Done when

`vcEnhance(..., fixElevation: true)` produit la même trace corrigée que la JVM, avec les tuiles
fournies par l'hôte.

## Notes

Ne pas rappeler un export du module depuis `fetch_tile` : la règle de non-réentrance de l'ABI
(w03) s'y applique comme aux autres callbacks, et l'allocateur scopé jetterait.
