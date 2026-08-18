# Component Model / WASI 0.2 : le verdict

> **Archive.** Gelé à sa date, ne décrit pas le code d'aujourd'hui. État courant :
> [`docs/guides/`](../../guides/) et [`docs/ledgers/`](../../ledgers/). Voir
> [`docs/archive/README.md`](../README.md).

Réponse mesurée à la phrase que `PLAN-WASM-WASI.md` posait par présomption — « Kotlin n'émet pas
de composant ». Spike w13, timeboxé, exploratoire : **aucune ligne de production n'a bougé**,
l'ABI v1 reste figée à `vcAbiVersion() == 1`.

**Verdict, en une phrase : ça marche, et ça se génère.** [`Kotlin/wit-bindgen`](https://github.com/Kotlin/wit-bindgen)
produit toute la glue Canonical ABI à partir du `.wit` de l'ABI v1 ; il ne reste que **53 lignes de
stubs** à remplir, et le composant obtenu rend la bonne distance sur une vraie trace. Ce qui
subsiste n'est plus un mur technique mais un pari : dépendre d'un **fork non publié** d'un
prototype, et accepter que les lectures en vrac (`list<f64>`, `list<u8>`) deviennent des listes
**boxées**. Recommandation : **pas maintenant** — phase F cadrée en §7, à déclencher le jour où le
générateur est livré, pas avant.

> **Cette page a été corrigée.** Sa première version concluait « il n'existe pas de générateur de
> bindings Canonical ABI pour Kotlin ». C'était faux : le fork existe, il est actif (dernier
> commit 21/07/2026), il cible `wasm-wasi`, et le spike l'a fait tourner sur cette ABI-ci. Le §2
> est la mesure ; la conclusion en a changé de nature.

Tout ce qui suit est reproductible : [`tools/wasi-component/reproduce.sh`](../../../tools/wasi-component/README.md).

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
| Combien de glue **à la main** pour ce seul GET ? | **~110 lignes** de Kotlin, statut seulement — ni corps, ni en-têtes, ni cache, ni retry |
| Quelque chose génère-t-il cette glue ? | **Oui** : `Kotlin/wit-bindgen`, branche `kotlin`, cible `wasm-wasi` |
| Génère-t-il **notre** ABI ? | **Oui** : 2 544 lignes depuis `vcyclist-engine.wit`, dont **53 de stubs** à écrire |
| Ça compile sur le Kotlin du projet ? | **Oui**, 2.4.20-Beta2, contre le vrai `:engine` |
| Le composant généré marche-t-il ? | **Oui** : `parse-gpx(str)` → une *resource* WIT, 259 points, 3573,8048648177737 m |
| Le générateur est-il livré ? | **Non** : fork, branche `kotlin`, rien sur crates.io, pas de plugin Gradle, README amont inchangé |

### Versions exactes

| Outil | Version | Rôle |
|---|---|---|
| `wasm-tools` | 1.255.0 (`cargo install`) | `component new`, `component embed`, `validate`, `component wit` |
| adapter Preview 1 | `wasi_snapshot_preview1.reactor.wasm` de wasmtime **47.0.1** | traduit `wasi_snapshot_preview1` vers WASI 0.2 |
| wasmtime (Python) | **47.0.1**, `wasmtime.component` | l'hôte ; API composant **dynamique**, sans génération de code |
| WIT `wasi:http` | **0.2.7** (dernier tag publié) | wasmtime 47 l'accepte comme compatible sémantiquement avec les 0.2.12 de son adapter |
| Kotlin | **2.4.20-Beta2** | ⚠️ w08 (2.4.20 final) n'est pas faite ; voir §9 |
| `Kotlin/wit-bindgen` | branche `kotlin`, commit `efcd80ba8` (21/07/2026), s'annonce `wit-bindgen-cli 0.57.1` | génère les bindings Kotlin depuis WIT ; testé chez lui avec kotlinc-wasm **2.4.20-Beta1**, `-Xwasm-target=wasm-wasi` |

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

**Ce contournement n'est pas une astuce de spike** : le générateur de JetBrains ouvre *chacun* de
ses exports par exactement ces deux lignes, dans cet ordre (§2). Le mur est donc réel, connu, et
traité systématiquement par l'outil — mais traité en pariant la même chose.

### 1.4 Et pourtant, le bout en bout est juste

```
parse-gpx      : handle 1, 259 points, 3573.8048648177737 m
ABI v1 says    : 259 points, 3573.8048648177737 m
```

Même moteur, même trace, même valeur au dernier chiffre — d'un côté le composant appelé avec une
`str` Python, de l'autre `tools/wasi/host.py` sur le module core. Le critère de la fiche (« un
`total-distance` **juste**, pas une instanciation sans erreur ») est atteint.

## 2. Le générateur existe — et il génère *cette* ABI

C'est la correction annoncée en tête. [`Kotlin/wit-bindgen`](https://github.com/Kotlin/wit-bindgen),
dans l'organisation JetBrains, est un fork du `wit-bindgen` de la Bytecode Alliance avec un
`crates/kotlin` de 2 955 lignes de Rust. Branche par défaut `kotlin`, dernier commit **21 juillet
2026** (dix jours avant ce spike), tests menés contre kotlinc-wasm 2.4.20-Beta1 avec
`-Xwasm-target=wasm-wasi` : la cible de ce projet, pas une cible voisine.

Lancé sur [`vcyclist-engine.wit`](../../../tools/wasi-component/vcyclist-engine.wit) — la traduction de
l'ABI v1, écrite avant de connaître le générateur :

```bash
wit-bindgen kotlin wit --world engine-hosted-tiles --generate-stubs   --kotlin-package-name io.github.glandais.engine.wit --out-dir …
```

| Fichier généré | Lignes | Ce que c'est |
|---|---|---|
| `InternalEngineHostedTiles.kt` | 2 001 | l'adaptateur Canonical ABI : lifting/lowering, zones de retour, `RepTable` des resources |
| `EngineHostedTiles.kt` | 372 | l'API typée : `interface PathApi`, `abstract class Path`, les `record`/`variant` en `data class`/`sealed` |
| `runtime/ComponentSupport.kt` | 118 | `cabi_realloc`, `ResourceHandle`, les helpers mémoire |
| **`EngineHostedTilesImpl.kt`** | **53** | **les stubs — le seul fichier qu'un humain écrit** |

Les 53 lignes sont 27 signatures `TODO()`, une par fonction du `.wit`, à remplir en déléguant au
moteur exactement comme `EngineJsApi` le fait pour JavaScript. À comparer aux **791 lignes** de
`EngineWasiApi.kt` — et les 807 lignes de `MiniJson`/`WasiOptions`/`WasiAbi`/`WasiJsonOutput`
n'existent plus du tout.

Trois vérifications, parce que « ça génère » n'est pas « ça marche » :

1. **Ça compile** sur le Kotlin du projet (2.4.20-Beta2, pas la Beta1 des tests amont), contre le
   vrai `:engine` pris dans mavenLocal.
2. **Ça produit un composant valide** (157 454 o) via `component embed` + `component new`.
3. **Ça répond juste** : trois stubs remplis (`parse-gpx`, `path.size`, `path.total-distance`), et
   `run_bindgen_component.py` obtient 259 points et 3573,8048648177737 m — la valeur de l'ABI v1,
   au dernier chiffre. `parse-gpx` rend une `ResourceAny` wasmtime : la table de handles de
   `WasiAbi.kt` est devenue celle du runtime.

Le `cabi_realloc` généré est, au caractère près, celui que ce spike avait écrit à la main —
argument `align` compris, et jeté de la même façon. La convergence est rassurante sur la mesure et
inquiétante sur l'API : deux implémentations indépendantes ont dû ignorer le même paramètre.

### Ce que le générateur ne fait pas encore

- **Il n'est pas livré.** Fork, branche `kotlin`, aucune publication crates.io, pas de plugin
  Gradle : le sample officiel *clone et compile le fork* dans son `make setup`, et annonce des
  bindings *« subject to change and certainly not final »*. Le README amont ne mentionne même pas
  Kotlin dans ses langages supportés.
- **Il refuse l'arbre WIT de `wasi:http`** : `Duplicate interface names found in generation plan
  (most likely due to multiple versions of the package)` — `wasi:filesystem/preopens` y apparaît en
  0.2.6 *et* 0.2.7. Le monde `engine-hosted-tiles` passe, le monde `engine` non. C'est-à-dire que
  le seul monde qui apporte le gain fonctionnel est précisément celui qui ne se génère pas
  aujourd'hui.
- **Il boxe les lectures en vrac.** `list<f64>` devient `List<Double>` et `list<u8>` devient
  `List<UByte>`, élément par élément :
  - en sortie (`path.field`, le `vcPathFieldBytes` d'aujourd'hui), le guest doit construire une
    `List<Double>` boxée de *n* éléments puis la stocker en boucle, là où l'ABI v1 copie une
    `DoubleArray` d'un bloc. Le fil est identique, le coût côté guest ne l'est pas ;
  - en entrée (`tile-source.fetch-tile`), une tuile d'1 Mio devient **1 048 576 `UByte` boxés**
    construits un par un dans une `ArrayList`. C'est le chemin chaud de `fixElevation`.
  - `ComponentSupport.kt` contient encore `fun MALLOC(size: Int, align: Int): Int = TODO()`. Il
    n'est appelé nulle part dans *notre* génération — mais il est là.

## 3. Ce que ça supprime pour un intégrateur

`tools/wasi/host.py`, l'étalon, fait **392 lignes**. Découpage mesuré :

| Rôle | Lignes | Devient |
|---|---|---|
| `Linker`, les 3 imports, le tampon de staging, `_call` / `_with_input` / `_text`, `WasiCallFailed` | ~118 | **rien** — 15 lignes de `Config` + `Linker.instantiate` |
| Une méthode par export, avec son JSON et ses sentinelles | ~163 | des appels typés, générés ailleurs (ici : `instance.get_func`) |
| Les deux `tile_source` (HTTP + Pillow) | ~60 | **rien**, seulement dans le monde `wasi:http` |

Côté guest, le `.wit` de [`tools/wasi-component/vcyclist-engine.wit`](../../../tools/wasi-component/vcyclist-engine.wit)
traduit **les 33 exports** de l'ABI (§7 de `wasm-wasi-abi.md`) : 27 fonctions en sortie, **6 supprimées** plutôt que traduites
(`vcAbiVersion`, `vcLastError`, `vcRelease`, `vcReleaseAll`, `vcListSize`, `vcListGet`), et **aucun
qui ne se traduise pas**. Disparaîtraient aussi : `MiniJson.kt` (282 l.), `WasiOptions.kt` (279 l.),
la table de handles de `WasiAbi.kt` (142 l.), les arguments de longueur d'octets de 14 exports, la
convention NaN de `vcDominantHeadwindAzimuth`, et les deux règles de §4 qui « corrompent l'état
plutôt que d'échouer ».

C'est réel, et c'est de l'ergonomie. Le seul gain **fonctionnel** est ailleurs.

## 4. `wasi:http` : chiffré, cache et retries compris

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
- **Cette glue-là se génère aussi** — sur le principe : c'est le même Canonical ABI, côté import.
  Sauf qu'aujourd'hui elle ne se génère justement pas, faute pour le générateur de digérer l'arbre
  WIT de `wasi:http` (§2). Les ~110 lignes sont donc à la fois « du travail que l'outil fera » et
  « du travail que l'outil ne fait pas encore ».

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

## 5. Ce que ça coûterait

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

## 6. Verdict

**Pas maintenant — mais la raison a changé, et elle est datée.**

La première version de cette page refusait pour un motif technique : « il n'existe pas de
générateur ». Ce motif est mort. Ce qui reste est un arbitrage, et il tient en trois lignes :

1. **Le générateur n'est pas un produit.** Une branche d'un fork, sans release, sans publication,
   sans intégration Gradle, dont le sample officiel dit que les bindings changeront. Adosser une
   ABI publiée — versionnée, documentée sur 466 lignes, jouée en CI à chaque PR — à un `git clone`
   d'une branche, c'est déplacer le risque de notre code vers un dépôt tiers que nous ne
   contrôlons pas.
2. **Le seul monde qui rapporte quelque chose est celui qui ne se génère pas.** `wasi:http` est
   l'unique gain fonctionnel, et c'est précisément l'arbre WIT que le générateur refuse
   aujourd'hui (§2). Faire l'ABI v2 pour le monde `engine-hosted-tiles`, c'est payer une refonte
   complète pour de l'ergonomie d'intégrateur, en gardant `fetch_tile`.
3. **Les lectures en vrac régressent.** `vcPathFieldBytes` existe parce qu'« un appel d'export par
   point et par champ est une traversée de frontière par valeur, ce qu'aucune trace de 50 000
   points ne supporte » (ABI §7). Le binding généré n'y ramène pas la traversée par valeur, mais
   il y ramène une `List<Double>` boxée — et une tuile d'1 Mio devient un million d'`UByte` boxés
   sur le chemin chaud de `fixElevation`.

Aucun des trois n'est définitif. Aucun des trois n'est de notre ressort. C'est exactement la
situation où l'on cadre le travail et où l'on attend.

## 7. Phase F, cadrée et en attente

À déclencher quand les conditions de §8 sont réunies, pas avant. Les coûts sont ceux mesurés
ci-dessus, pas des estimations à vue.

| Tâche | Contenu | Appui mesuré |
|---|---|---|
| f01 | Figer le `.wit` comme contrat : reprendre `vcyclist-engine.wit`, arbitrer les 6 exports supprimés, décider `engine` vs `engine-hosted-tiles` | le `.wit` existe et résout |
| f02 | Intégrer le générateur au build (tâche Gradle, version épinglée, sortie en `build/generated`, jamais commitée) | aujourd'hui : `generate-bindings.sh` + un `git clone` |
| f03 | Remplir les 27 stubs en déléguant au moteur, sur le modèle de `EngineJsApi` | 53 lignes de stubs, 3 déjà remplies dans le spike |
| f04 | Traiter les listes en vrac : mesurer `path.field` et `fetch-tile` sur une vraie trace, et arbitrer (garder un export d'octets hors WIT ? attendre le générateur ?) | la régression est identifiée, pas chiffrée |
| f05 | `wasi:http` : `TileFetcher.wasmWasi.kt` par-dessus `outgoing-handler`, **avec** retries, timeouts, `User-Agent` et respect des conditions du serveur — ce que l'hôte faisait | ~110 lignes à la main pour un GET sans corps ; à générer |
| f06 | Refaire `wasm-wasi-abi.md` (466 l.), `host.py` (392 l.), `test_engine.py` (472 l.), la CI, le `.wasm` publié et son plafond de taille ; `vcAbiVersion` → 2 | inventaire de §5 |

Ordre imposé : **f01 → f02 → f03 → f06**, f04 en parallèle de f03, f05 seulement si le monde
`engine` se génère (sinon la phase F ne livre que de l'ergonomie et doit être re-arbitrée à ce
moment-là).

## 8. Conditions de déclenchement

Réouvrir **dès que** :

1. le générateur Kotlin est **livré** — release taguée, ou publication, ou intégration Gradle
   officielle, ou absorption dans le `wit-bindgen` amont ; le fait que
   [KT-64569](https://youtrack.jetbrains.com/issue/KT-64569) soit résolue vaut aussi ;
2. **et** qu'il génère le monde `wasi:http` (le bug de doublons d'interfaces est corrigé, ou
   l'arbre WIT amont l'est).

À surveiller en plus, parce que chacun change l'arbitrage sans le déclencher :

- `componentModelRealloc` quitte `@ComponentModelInternalApi` et récupère son paramètre
  d'alignement — le générateur ne serait plus obligé de le jeter ;
- les listes de scalaires cessent d'être boxées côté Kotlin (`DoubleArray`/`ByteArray`) ;
- une liaison composant apparaît côté hôtes JVM ou Go, ce qui étendrait le gain au-delà de Python
  et Rust.

Le sample [sample-wasi-http-kotlin](https://github.com/Kotlin/sample-wasi-http-kotlin/) reste le
signal le plus lisible : le jour où il n'a plus besoin de cloner un fork dans son `make setup`,
la condition n°1 est remplie.

### Ce que ce verdict ne dit pas

- Que l'ABI v1 est parfaite. Le `.wit` liste ce qu'elle paie : `vcLastError`, la table de handles,
  les longueurs d'octets, le NaN. C'est de l'élégance, et l'élégance ne justifie pas seule une
  ABI v2 — mais elle en justifierait une partie le jour où le reste est réuni.
- Que le Component Model est immature. Il ne l'est pas : `wasm-tools`, l'adapter et wasmtime ont
  fait exactement ce qu'ils promettent, sur un module WASM-GC qu'ils n'ont pas été écrits pour
  digérer, et le générateur JetBrains a produit 2 544 lignes correctes du premier coup sur une
  ABI qu'il n'avait jamais vue. **C'est la livraison de l'outillage Kotlin qui manque, pas sa
  faisabilité.**

## 9. Réserves de méthode

- **Le spike tourne sur Kotlin 2.4.20-Beta2, pas 2.4.20 final** : w08 n'est pas faite et w07 est
  🟡. La fiche demandait l'inverse, pour ne pas faire porter le doute sur le compilateur. Aucun
  résultat ci-dessus ne dépend pourtant d'un correctif de compilateur : l'absence de générateur,
  le statut interne de `componentModelRealloc` et l'exclusion mutuelle des deux allocateurs sont
  des faits d'API, pas des bugs. `./reproduce.sh` les rejouera tels quels sur la finale.
- `tools/wasi-component/spike-guest` consomme `:engine` via **mavenLocal**, pas par référence de
  projet : le build vcyclist n'est pas touché, conformément aux non-buts de la fiche.
- La chirurgie sur le `.wat` de §1.2 (renommage des exports en kebab) n'a servi qu'à isoler la
  question « les exports scalaires se liftent-ils ? ». Le bout en bout de §1.4 et §3 n'en utilise
  rien : il passe par de vrais `@WasmExport("nom-kebab")`.

## 10. Voir aussi

| Question | Où |
|---|---|
| Rejouer toutes les mesures | [`tools/wasi-component/README.md`](../../../tools/wasi-component/README.md) |
| Les bindings générés, lisibles sans cargo | `tools/wasi-component/spike-bindgen/src/wasmWasiMain/kotlin/` |
| L'ABI v1 traduite en WIT | [`tools/wasi-component/vcyclist-engine.wit`](../../../tools/wasi-component/vcyclist-engine.wit) |
| L'ABI v1 elle-même | [`wasm-wasi-abi.md`](../../guides/wasm-wasi-abi.md) |
| Les notes d'ingénierie de la cible | [`kotlin-wasm-wasi.md`](../../guides/kotlin-wasm-wasi.md) |
| L'hôte de référence, l'étalon des chiffres de §2 | [`tools/wasi/host.py`](../../../tools/wasi/host.py) |
| La fiche de tâche | [`tasks/w13-spike-component-model.md`](../tasks/w13-spike-component-model.md) |
