# Component Model / WASI 0.2 : le verdict

Réponse mesurée à la phrase que `PLAN-WASM-WASI.md` posait par présomption — « Kotlin n'émet pas
de composant ». Spike w13, timeboxé, exploratoire : **aucune ligne de production n'a bougé**,
l'ABI v1 reste figée à `vcAbiVersion() == 1`.

**Verdict, en une phrase : c'est mécaniquement possible aujourd'hui — un composant réel, appelé
par des valeurs typées, rend la *bonne* distance sur une vraie trace — mais chaque octet de la
glue Canonical ABI est écrit à la main, sur une API que la stdlib déclare interne, et avec un
contournement d'une incompatibilité d'allocateurs. Donc non : pas de phase F. Réouverture à
[KT-64569](https://youtrack.jetbrains.com/issue/KT-64569).**

Tout ce qui suit est reproductible : [`tools/wasi-component/reproduce.sh`](../tools/wasi-component/README.md).

## 0. Ce qui a été mesuré, en un tableau

| Question | Réponse mesurée |
|---|---|
| `wasm-tools component new` digère-t-il le module WASM-GC + `exnref` publié ? | **Oui.** Composant valide, 526 849 o contre 501 834 o (+5,0 %) |
| Ce composant exporte-t-il quelque chose ? | **Non. Zéro export** — il faut des noms kebab et de la métadonnée `component-type` |
| Kotlin peut-il exporter aux noms canoniques ? | **Oui**, `@WasmExport("parse-gpx")` — le nom est un paramètre de l'annotation |
| Kotlin peut-il exporter `cabi_realloc` ? | **Oui**, via `componentModelRealloc` — `@ComponentModelInternalApi` |
| Une `string` entrante traverse-t-elle ? | **Oui**, 23 694 caractères intacts sans `read_input` |
| Une `string` sortante ? | **Oui**, ~10 lignes de retour indirect écrites à la main |
| Le moteur tourne-t-il derrière ? | **Oui, au bit près** : 259 points et 3573,8048648177737 m, identiques à l'ABI v1 |
| … sans contournement ? | **Non.** `IllegalStateException: Can't create new allocators while realloc-allocated memory is not freed` |
| Un import composant marche-t-il depuis Kotlin ? | **Oui**, `wasi:random/random.get-random-bytes` à la main |
| `wasi:http` sortant, pour de vrai ? | **Oui, HTTP 200** sur `https://tiles.mapterhorn.com/12/2129/1465.webp` |
| Combien de glue pour ce seul GET ? | **~110 lignes** de Kotlin, statut seulement — ni corps, ni en-têtes, ni cache, ni retry |
| Quelque chose génère-t-il cette glue ? | **Non**, pour Kotlin. `wit-bindgen` la génère pour Rust, C, Go, JS, Python |

### Versions exactes

| Outil | Version | Rôle |
|---|---|---|
| `wasm-tools` | 1.255.0 (`cargo install`) | `component new`, `component embed`, `validate`, `component wit` |
| adapter Preview 1 | `wasi_snapshot_preview1.reactor.wasm` de wasmtime **47.0.1** | traduit `wasi_snapshot_preview1` vers WASI 0.2 |
| wasmtime (Python) | **47.0.1**, `wasmtime.component` | l'hôte ; API composant **dynamique**, sans génération de code |
| WIT `wasi:http` | **0.2.7** (dernier tag publié) | wasmtime 47 l'accepte comme compatible sémantiquement avec les 0.2.12 de son adapter |
| Kotlin | **2.4.20-Beta2** | ⚠️ w08 (2.4.20 final) n'est pas faite ; voir §6 |

## 1. Est-ce mécaniquement possible ? Oui, et voici le mur

### 1.1 L'adapter digère le module — ce n'était pas acquis

L'étape qui devait tuer la fiche en une demi-journée ne l'a pas tuée. Le `.wasm` publié, non
modifié, passe :

```bash
wasm-tools component new vcyclist-engine.wasm \
  --adapt wasi_snapshot_preview1=wasi_snapshot_preview1.reactor.wasm \
  --adapt vcyclist=vcyclist-stub.wasm \
  -o vcyclist-engine.component.wasm      # 526 849 o, `wasm-tools validate --features=all` OK
```

L'adapter Preview 1 est écrit pour des modules à mémoire linéaire, et le module exige `gc`,
`function-references` et le **nouveau** handling d'exceptions. Ça marche quand même : l'adapter ne
touche pas au code du guest, il satisfait ses imports.

Le second `--adapt` est le premier enseignement : sans lui, l'encodeur refuse avec

```
failed to resolve import `vcyclist::fetch_tile`
module requires an import interface named `vcyclist`
```

Les trois imports custom de §3 n'ont pas de WIT à quoi se rattacher. Un module de stub les absorbe
— ils **disparaissent** du composant au lieu d'y devenir des imports typés, ce qui est exactement
ce qu'on ne veut pas et suffit pour cette étape-ci.

### 1.2 Le composant obtenu n'exporte rien

```wit
world root {
  import wasi:cli/exit@0.2.12;  // … 11 autres
}                                // et pas un seul export
```

Les 33 exports sont bien dans le module core, mais rien ne les *lifte* : il manque la section
`component-type` que `wit-bindgen` grave dans les binaires Rust/C, et les noms
(`vcAbiVersion`) ne sont pas des noms WIT. Un composant « valide » qui n'expose rien est le
piège que la fiche annonçait — s'instancier n'est pas marcher.

En renommant les exports scalaires en kebab-case (chirurgie sur le `.wat`, hors production) et en
gravant un monde avec `wasm-tools component embed`, on obtient un composant qui, lui, exporte
vraiment `abi-version`, `path-size`, `path-total-distance`. Les exports scalaires se liftent donc
sans rien de spécial. Le mur est ailleurs.

### 1.3 Le mur : les deux allocateurs de Kotlin s'excluent

Un export qui reçoit une `string` reçoit un pointeur vers de la mémoire que l'hôte a allouée en
appelant `cabi_realloc`. Kotlin sait exporter cette fonction :

```kotlin
@WasmExport("cabi_realloc")
fun cabiRealloc(oldPtr: Int, oldSize: Int, align: Int, newSize: Int): Int =
    componentModelRealloc(oldPtr, oldSize, newSize)   // note l'argument `align`, jeté
```

`componentModelRealloc` est `@ComponentModelInternalApi`, dont la KDoc dit : *« Internal APIs for
WebAssembly Component Model integration. Should not be used outside of code generated by the
compiler and toolchain. »* Sa signature en 2.4.20-Beta2 n'a **pas** de paramètre d'alignement, que
la Canonical ABI passe pourtant : le wrapper le jette. Ça marche (l'allocateur aligne sur 8, ce
qui couvre `string`, `list<u8>` et `list<f64>`), rien ne le promet.

Le transfert lui-même est parfait : `str-len` sur les 23 694 caractères de `stelvio.gpx` répond
23 694. Mais dès que le guest fait quoi que ce soit :

```
IllegalStateException: Can't create new allocators while realloc-allocated memory is not freed
```

`withScopedMemoryAllocator` — l'API publique sur laquelle repose **toute** l'ABI v1, et que la
stdlib utilise elle-même pour ses appels WASI — refuse de s'ouvrir tant que la mémoire allouée par
`componentModelRealloc` n'est pas rendue. Un export composant qui reçoit une chaîne ne peut donc
faire aucun appel système, ni rien qui en fasse un, pendant tout l'appel. Le probe est explicite :
`probe-scoped(s: string)` ne fait qu'un `withScopedMemoryAllocator { allocate(8) }` et échoue.

**Le contournement fonctionne** : recopier l'argument sur le tas GC, appeler
`freeAllComponentModelReallocAllocatedMemory()`, puis travailler. Trois parsings successifs dans
la même instance passent. Mais on libère là une mémoire que l'appelant croit posséder jusqu'à
`cabi_post_return` — c'est-à-dire qu'on parie sur ce que fait l'hôte de la sienne. wasmtime 47.0.1
ne s'en plaint pas ; ce n'est pas une garantie, c'est une observation.

### 1.4 Et pourtant, le bout en bout est juste

```
parse-gpx      : handle 1, 259 points, 3573.8048648177737 m
ABI v1 says    : 259 points, 3573.8048648177737 m
```

Même moteur, même trace, même valeur au dernier chiffre — d'un côté le composant appelé avec une
`str` Python, de l'autre `tools/wasi/host.py` sur le module core. Le critère de la fiche (« un
`total-distance` **juste**, pas une instanciation sans erreur ») est atteint.

## 2. Ce que ça supprime pour un intégrateur

`tools/wasi/host.py`, l'étalon, fait **392 lignes**. Découpage mesuré :

| Rôle | Lignes | Devient |
|---|---|---|
| `Linker`, les 3 imports, le tampon de staging, `_call` / `_with_input` / `_text`, `WasiCallFailed` | ~118 | **rien** — 15 lignes de `Config` + `Linker.instantiate` |
| Une méthode par export, avec son JSON et ses sentinelles | ~163 | des appels typés, générés ailleurs (ici : `instance.get_func`) |
| Les deux `tile_source` (HTTP + Pillow) | ~60 | **rien**, seulement dans le monde `wasi:http` |

Côté guest, le `.wit` de [`tools/wasi-component/vcyclist-engine.wit`](../tools/wasi-component/vcyclist-engine.wit)
traduit **les 33 exports** de §7 : 27 fonctions en sortie, **6 supprimées** plutôt que traduites
(`vcAbiVersion`, `vcLastError`, `vcRelease`, `vcReleaseAll`, `vcListSize`, `vcListGet`), et **aucun
qui ne se traduise pas**. Disparaîtraient aussi : `MiniJson.kt` (282 l.), `WasiOptions.kt` (279 l.),
la table de handles de `WasiAbi.kt` (142 l.), les arguments de longueur d'octets de 14 exports, la
convention NaN de `vcDominantHeadwindAzimuth`, et les deux règles de §4 qui « corrompent l'état
plutôt que d'échouer ».

C'est réel, et c'est de l'ergonomie. Le seul gain **fonctionnel** est ailleurs.

## 3. `wasi:http` : chiffré, cache et retries compris

Depuis w11 (WebP en Kotlin) et w12 (FIT en Kotlin), le réseau est la **seule** chose que le module
demande encore à son hôte. C'est donc la seule question qui pouvait changer la nature du produit.

**La requête sortante aboutit.** Pas « compile », pas « s'instancie » : `http-get-status` renvoie
**200** pour `https://tiles.mapterhorn.com/12/2129/1465.webp`, le vrai serveur de tuiles, depuis
un guest Kotlin, à travers `wasi:http/outgoing-handler@0.2.7` servi par wasmtime.

Le prix, mesuré :

- **~110 lignes de Kotlin écrites à la main** pour ce seul GET, qui ne lit **que le code de
  statut** : 10 imports `@WasmImport` déclarés un par un, `option<scheme>` aplati en quatre
  paramètres, des zones de retour dont les offsets ont été **trouvés en sondant la mémoire** (le
  handle de `result<own<future-incoming-response>, error-code>` est à +8, pas à +4 ; celui de
  `option<result<result<own<incoming-response>, error-code>, ()>>` est à +24), et une boucle
  `poll`. Lire le corps de la réponse — c'est-à-dire la tuile — ajoute `incoming-body`, un
  `input-stream`, un `blocking-read` en boucle et leurs `[resource-drop]`.
- **Rien ne génère ça pour Kotlin.** `wit-bindgen` le génère en une commande pour Rust, C, Go, JS
  et Python. C'est tout l'écart, et il est là entièrement.

**La question qui compte, tranchée : non, §8 ne disparaît pas, elle déménage.** Aujourd'hui l'hôte
apporte le réseau *et* ce qui va avec — `TileManager` côté guest a un cache LRU, mais les retries,
la limitation de concurrence et la politique d'erreur réseau sont à l'hôte, qui les a déjà pour
son propre compte (`tools/wasi/host.py` en a 60 lignes, un hôte Go ou Rust a son client HTTP et
ses policies). Dans le monde `wasi:http`, tout ça devient du Kotlin dans `commonMain` ou
`wasmWasiMain` : retries avec backoff, timeouts, `User-Agent`, redirections, et le respect des
conditions d'utilisation du serveur de tuiles — que le README de l'hôte lui délègue explicitement
aujourd'hui. Le gain net n'est pas « §8 s'évapore » mais « §8 change de côté », et le côté qui la
reçoit est celui qui doit être portable JVM + JS + WASI.

Ce que ça achète en échange, et qui est réel : un `.wasm` que n'importe quel hôte WASI 0.2 lance
sans écrire une ligne d'imports, `fixElevation` compris. C'est la différence entre « une
bibliothèque qu'on embarque » et « un binaire qu'on exécute ».

## 4. Ce que ça coûterait

Un monde composant est une **ABI v2**, pas une option de compilation :

| À refaire | Taille actuelle |
|---|---|
| `EngineWasiApi.kt` — 33 exports, réécrits en glue Canonical ABI à la main | 791 lignes |
| `WasiAbi.kt`, `MiniJson.kt`, `WasiOptions.kt`, `WasiJsonOutput.kt` — supprimés, mais leur logique de validation doit atterrir quelque part | 807 lignes |
| `docs/wasm-wasi-abi.md` — §3 à §9 n'ont plus d'objet | 466 lignes |
| `tools/wasi/host.py` + `test_engine.py` — l'étalon et la seule couverture bout en bout de l'ABI | 864 lignes |
| La CI, le `.wasm` publié (w06/w07), son `.sha256`, son plafond de taille | — |

Et le composant est **plus gros** : +5,0 % sur le module publié, +10,4 % sur le guest du spike.

**Runtimes.** Rien à perdre : le module exige déjà `gc` + `function-references` + `exnref`, ce qui
exclut wazero et, en pratique, tout ce qui n'est pas wasmtime récent — l'ABI documente wasmtime 46+
comme la référence. Le Component Model ne rétrécit pas cet ensemble, il le déplace : ce qui change
est l'**API d'embarquement**. wasmtime-py 47.0.1 a une API composant dynamique et typée (mesurée
ici, sans génération de code) ; ce spike n'a **pas** testé wasmtime-go ni wasmtime-java, et un
intégrateur JVM ou Go doit vérifier lui-même que sa liaison expose les composants avant de
compter sur ce verdict.

## 5. Verdict

**Non. Pas de phase F. Refus daté du 2026-07-31.**

Ce n'est pas un « pas mûr » vague : les trois quarts de la mécanique marchent, et la mesure le
montre. C'est un refus pour une raison unique et nommable :

> **Il n'existe pas de générateur de bindings Canonical ABI pour Kotlin.** Tout ce que
> `wit-bindgen` produit ailleurs — lifting des `string`/`list`/`record`/`variant`, `resource`,
> zones de retour, `cabi_post_return`, appels d'imports — est ici du code écrit à la main, sur une
> API que la stdlib marque interne, contre un allocateur qui interdit à la stdlib de fonctionner
> pendant l'appel. ~110 lignes pour un GET qui ne lit pas son corps donnent l'ordre de grandeur
> pour 33 exports.

Échanger une ABI figée, versionnée, documentée sur 466 lignes et jouée en CI contre cette
quantité de glue manuelle serait un mauvais marché — et `wasi:http`, seul gain fonctionnel, ne
supprime pas le travail de §8, il le déplace vers le guest.

### Conditions de réouverture

Réouvrir la question **dès qu'une seule** de ces deux conditions tombe, sans attendre l'autre :

1. **[KT-64569](https://youtrack.jetbrains.com/issue/KT-64569) est résolue**, c'est-à-dire que le
   compilateur Kotlin émet un composant ou qu'un `wit-bindgen` Kotlin existe et est supporté. Le
   critère de recevabilité est précis : que `parse-gpx: func(gpx: string) -> result<path, error>`
   se génère, et que le guest puisse appeler `withScopedMemoryAllocator` — ou n'en ait plus besoin
   — pendant cet appel.
2. **`componentModelRealloc` quitte `@ComponentModelInternalApi`** et gagne son paramètre
   d'alignement, ce qui vaudrait engagement de stabilité.

Le sample [sample-wasi-http-kotlin](https://github.com/Kotlin/sample-wasi-http-kotlin/) reste le
signal à surveiller : il annonce lui-même des bindings *« subject to change and certainly not
final »*, ce que ce spike confirme depuis l'autre bout.

### Ce que ce verdict ne dit pas

- Que l'ABI v1 est parfaite. Le `.wit` de `tools/wasi-component/` liste ce qu'elle paie :
  `vcLastError`, la table de handles, les longueurs d'octets, le NaN. C'est de l'élégance, et
  l'élégance ne justifie pas une ABI v2.
- Que le Component Model est immature. Il ne l'est pas — `wasm-tools`, l'adapter et wasmtime ont
  fait ici exactement ce qu'ils promettent, sur un module WASM-GC qu'ils n'ont pas été écrits pour
  digérer. **C'est le support Kotlin qui manque, pas la spécification.**

## 6. Réserves de méthode

- **Le spike tourne sur Kotlin 2.4.20-Beta2, pas 2.4.20 final** : w08 n'est pas faite et w07 est
  🟡. La fiche demandait l'inverse, pour ne pas faire porter le doute sur le compilateur. Aucun
  résultat ci-dessus ne dépend pourtant d'un correctif de compilateur : l'absence de générateur,
  le statut interne de `componentModelRealloc` et l'exclusion mutuelle des deux allocateurs sont
  des faits d'API, pas des bugs. `./reproduce.sh` les rejouera tels quels sur la finale, et la
  conclusion ne devrait changer que si la 2.4.20 apporte un `wit-bindgen` — auquel cas c'est la
  condition de réouverture n°1 qui est remplie.
- `tools/wasi-component/spike-guest` consomme `:engine` via **mavenLocal**, pas par référence de
  projet : le build vcyclist n'est pas touché, conformément aux non-buts de la fiche.
- La chirurgie sur le `.wat` de §1.2 (renommage des exports en kebab) n'a servi qu'à isoler la
  question « les exports scalaires se liftent-ils ? ». Le bout en bout de §1.4 et §3 n'en utilise
  rien : il passe par de vrais `@WasmExport("nom-kebab")`.

## 7. Voir aussi

| Question | Où |
|---|---|
| Rejouer toutes les mesures | [`tools/wasi-component/README.md`](../tools/wasi-component/README.md) |
| L'ABI v1 traduite en WIT | [`tools/wasi-component/vcyclist-engine.wit`](../tools/wasi-component/vcyclist-engine.wit) |
| L'ABI v1 elle-même | [`wasm-wasi-abi.md`](wasm-wasi-abi.md) |
| Les notes d'ingénierie de la cible | [`kotlin-wasm-wasi.md`](kotlin-wasm-wasi.md) |
| L'hôte de référence, l'étalon des chiffres de §2 | [`tools/wasi/host.py`](../tools/wasi/host.py) |
| La fiche de tâche | [`tasks/w13-spike-component-model.md`](tasks/w13-spike-component-model.md) |
