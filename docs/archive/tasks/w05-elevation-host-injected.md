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

- [x] `./gradlew check` vert.
- [x] Un test WASI prouve que la suspension réelle échoue avec le message explicite.
- [x] `INTEGRATION=1` : profil altimétrique WASI vs JVM à ±1 m sur `stelvio.gpx`.
- [x] Le module s'instancie toujours si `fetch_tile` retourne `0` (chemin « pas de DEM »).

## Done when

`vcEnhance(..., fixElevation: true)` produit la même trace corrigée que la JVM, avec les tuiles
fournies par l'hôte.

## Notes

Ne pas rappeler un export du module depuis `fetch_tile` : la règle de non-réentrance de l'ABI
(w03) s'y applique comme aux autres callbacks, et l'allocateur scopé jetterait.

### Ce qui s'est passé

**Le contrat de l'import**, tel que livré :

```
fetch_tile(zoom: i32, x: i32, y: i32, ptr: i32, cap: i32) -> i32
```

`cap` = tuile écrite, `0` = pas de tuile ici, autre = erreur hôte. Des pixels **RGBA** (4 octets),
pas RGB comme le supposait la fiche : `RawTile` impose `width × height × 4` et refuse le reste,
donc coller à son invariant évite au guest de réinterpréter quoi que ce soit. Terrarium n'utilise
que R, G et B ; l'octet alpha est ignoré.

Le « pas de tuile » ne remplit **pas** de zéros : `r=128, g=0, b=0` décode exactement 0 m
(`128 × 256 − 32768`), là où un buffer à zéro vaudrait −32768 m — pas « inconnu », mais une
altitude catastrophiquement fausse que le lisseur étalerait ensuite sur les voisins.

**Géométrie** : ni constante figée ni `vcSetTileGeometry`, mais les deux exports
`vcSetElevationConfig` (zoom / tileSize / cacheSize, collant, validé immédiatement en construisant
un `ElevationProvider`) et `vcTileGeometryJson` (ce que l'hôte doit être prêt à écrire). `cap`
reste de toute façon passé à chaque appel.

**Deux pièges de DCE**, tous deux mesurés :

1. Câbler l'import dans `fetchAndDecodeTile` — ce que demandait l'étape 3 — le rend joignable
   depuis *toute* construction de provider, puisque c'est la valeur par défaut du paramètre
   `fetcher`. Résultat : l'import survit au DCE dans le binaire de **test** de `:elevation`, que
   le runner KGP ne sait pas instancier, et les 194 tests de la cible tombent d'un coup. Le
   fetcher hôte reste donc explicite (`ElevationProvider(config, hostTileFetcher())`), ce qui est
   exactement le seam de g21 ; l'`actual` par défaut lève en le disant.
2. Un `object` Kotlin garde ses membres joignables **en bloc** : tant que `fetcher()` était une
   méthode de `HostTileSource`, lire `HostTileSource.tileSize` dans un test suffisait à
   ré-embarquer l'import. D'où la séparation : données dans l'objet, `hostTileFetcher()`
   top-level.

**Le pont `suspend` → synchrone existait depuis w04, il a fallu lui donner un dispatcher.**
`Enhancer` n'est pas une chaîne d'appels `suspend` : `Flux.kt` passe par
`coroutineScope { async { … } }` derrière un `Semaphore`, et le cache de tuiles est protégé par
un `Mutex`. Sans dispatcher dans le contexte, ces enfants partent sur une boucle d'événements que
rien ne pompe sous WASI — et le garde-fou a fait exactement son travail au premier
`fixElevation: true` réel :

```
enhance = -1 | the operation suspended, which this target cannot resume: nothing drives
               a continuation under WASI (see RunSynchronously and task w05)
```

`Dispatchers.Unconfined` exécute chaque enfant sur la pile appelante : un `Mutex` non contendu,
un `Semaphore` avec des permis, un `async` qui n'appelle que l'import synchrone se terminent sans
jamais suspendre. On perd le parallélisme des tuiles — sans importance, l'hôte est de toute façon
mono-thread à travers la frontière et n'a pas le droit de réentrer pendant un callback.

### La mesure ±1 m, et la fausse piste qui l'a précédée

Premier verdict : **8,94 m d'écart max** contre le JVM, budget 1 m. Trois vérifications ont
suivi, dans cet ordre, et aucune n'a incriminé le guest :

1. les deux décodeurs WebP rendent des octets **identiques** sur la tuile 12/2166/1448 (tuile
   VP8L, donc sans perte) — `138,109,88,255,…` des deux côtés ;
2. une **troisième implémentation** des formules Terrarium (Python, portée depuis
   `ElevationCalculator`) donne 2626,2695 m au premier point, comme la JVM ;
3. la même trace réduite à **un seul point** rend, sous WASI, `2626.269547963089` — bit pour bit
   la valeur du provider JVM.

La référence était fausse : `:cli`'s `enhanceOne` appelle `Enhancer.enhanceCourse(physics,
options, elevationProvider = null)`, donc **`--fix-elevation` du CLI est un no-op silencieux**
(`Enhancer` saute l'étape quand le provider est nul). Je comparais WASI *avec* DEM à une JVM
*sans*. Bug réel, hors périmètre de cette fiche, à traiter à part.

Avec une vraie référence JVM (même pipeline, `ElevationProvider()`, mêmes options) :

| | valeur |
|---|---|
| Écart max sur 1 929 points | **0,000435 m** |
| Écart moyen | 0,000031 m |
| Tuiles téléchargées par l'hôte | 2 |

Soit du bruit d'ULP sur les trigonométriques, à 2 000 fois sous le budget.

Chemin « pas de DEM » (`fetch_tile` → `0` systématique) : le module s'instancie, `vcEnhance`
réussit, le profil vaut 0 m partout — et non −32768.

### Un test qui reste

`ElevationBridgeTest` (wasmWasi) vérifie que chaque point reçoit l'altitude de **sa** tuile à
travers `runSynchronously`, sans hôte, via un fetcher injecté. Sa première version affirmait des
tuiles uniformes et échouait à juste titre : l'interpolation bilinéaire traverse légitimement les
bords de tuile. La fixture est donc une **rampe linéaire** en coordonnées pixel globales — la
bilinéaire d'une fonction linéaire est cette même fonction, donc l'attendu est une forme close.
