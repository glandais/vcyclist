# `tools/wasi-component` — le spike Component Model (tâche w13)

Tout ce qui a produit les chiffres de [`docs/wasm-wasi-component-model.md`](../../docs/wasm-wasi-component-model.md),
pour qu'ils soient vérifiables plutôt que crus.

**Rien ici n'est du code de production.** Ce répertoire n'est pas dans le build Gradle de
vcyclist, pas dans la CI, pas publié. L'ABI v1 (`docs/wasm-wasi-abi.md`) est inchangée, et
`vcAbiVersion` vaut toujours 1.

## Les fichiers

| Fichier | Rôle |
|---|---|
| `vcyclist-engine.wit` | **Le livrable de papier** : les 33 exports de l'ABI v1 traduits en WIT, avec ce que chaque ligne supprime. Ne compile rien |
| `spike.wit` | Le monde jetable du guest ci-dessous — trois exports utiles et une poignée de sondes |
| `spike-guest/` | Un **build Gradle séparé** : un fichier Kotlin qui exporte aux noms canoniques par-dessus le vrai `:engine`, consommé depuis mavenLocal |
| `run_component.py` | L'hôte : ~15 lignes de mise en route, puis des appels typés. À comparer aux 392 lignes de `tools/wasi/host.py` |
| `reproduce.sh` | Rejoue tout, de zéro |
| `build/` | Artefacts jetables, ignorés par git |

## Rejouer

```bash
pip install -r ../wasi/requirements.txt      # wasmtime >= 47.0.1
./reproduce.sh                               # ~2 min à froid (cargo install wasm-tools)
./reproduce.sh --offline                     # sans le test wasi:http
```

Il faut `cargo` (pour `wasm-tools`) et un accès réseau pour l'adapter Preview 1, le WIT de
`wasi:http` et — sauf `--offline` — une vraie tuile DEM.

Sortie attendue :

```
   module    501834 bytes
   component 526849 bytes, valid — and with zero exports:
...
probe-scoped   : -1 | IllegalStateException: Can't create new allocators while realloc-allocated memory is not freed
parse-gpx      : handle 1, 259 points, 3573.8048648177737 m
ABI v1 says    : 259 points, 3573.8048648177737 m
random-sum     : 16 bytes from wasi:random
http-get-status: 200 for https://tiles.mapterhorn.com/12/2129/1465.webp
```

Les deux lignes qui comptent : le composant et le module core donnent **la même distance au
dernier chiffre** (le spike exécute vraiment le moteur), et `probe-scoped` échoue (le mur est
réel, et le reste ne marche qu'avec le contournement documenté dans `SpikeGuest.kt`).

## Ce que le guest jetable contient

`spike-guest/src/wasmWasiMain/kotlin/SpikeGuest.kt`, dans l'ordre :

1. `cabi_realloc` par-dessus `componentModelRealloc` (`@ComponentModelInternalApi`) ;
2. `parse-gpx` / `total-distance` / `path-size` aux noms canoniques, plus `parse-gpx-freeing`, la
   variante qui contourne l'exclusion des deux allocateurs ;
3. `last-error`, une `string` **sortante**, écrite à la main (zone de retour, `cabi_realloc`) ;
4. `probe-scoped`, la sonde qui isole le mur ;
5. `random-sum`, un import composant (`wasi:random`) appelé à la main ;
6. `http-get-status` + `http-debug` : `wasi:http/outgoing-handler` à la main, ~110 lignes, et la
   sonde pas-à-pas qui a servi à *mesurer* les offsets des zones de retour.

## Si `reproduce.sh` échoue

- `already closed` / `unwrap() on a None value` côté Python : il manque `store.set_wasi_http()`,
  sans lequel wasmtime-py 47.0.1 **abort le processus** au premier appel HTTP.
- `package 'wasi:http@0.2.7' not found` : le tarball WIT n'a pas été extrait dans `build/`.
- `failed to resolve import 'vcyclist::fetch_tile'` : c'est l'étape 1 **sans** le stub — le
  message est un résultat du spike, pas une panne.
