# w13 — Spike : Component Model / WASI 0.2 (exploratoire, timeboxé)

## Goal

Répondre par la **mesure** à une question aujourd'hui tranchée par présomption. `PLAN-WASM-WASI.md`
écarte le Component Model d'une phrase — « Kotlin n'émet pas de composant » — qui était vraie
quand elle a été écrite et ne l'est plus tout à fait : JetBrains publie un serveur `wasi:http`
écrit en Kotlin ([sample-wasi-http-kotlin](https://github.com/Kotlin/sample-wasi-http-kotlin/)),
prototype précoce mais fonctionnel, et [KT-64569](https://youtrack.jetbrains.com/issue/KT-64569)
suit le support officiel.

Fiche **exploratoire** : elle ne livre pas de fonctionnalité. Elle livre un **verdict écrit et
chiffré** — soit une phase F cadrée et estimée, soit un « non » qui nomme le mur exact et la
condition à laquelle il tomberait. Aucune ligne de code de production n'en sort.

**Timebox : 2 jours.** Dépassement = le verdict est « pas mûr », et c'est un résultat valable.

## Depends on

- `w08` (Kotlin 2.4.20 final). Spiker sur une Beta ferait porter le doute sur le compilateur au
  lieu de l'écosystème.
- `w07` (publication réelle du `.wasm`). Le chemin critique se termine avant qu'on explore à
  côté — c'est la même règle qu'en w11.

## Inputs

- `engine/build/wasm/vcyclist-engine.wasm` — le module à transformer, tel qu'il est publié.
- `engine/src/wasmWasiMain/…/wasi/EngineWasiApi.kt` + `WasiAbi.kt` — l'ABI v1 à traduire en WIT.
- `docs/wasm-wasi-abi.md` — le contrat côté hôte, §3 à §8 : c'est très exactement ce que le
  Component Model prétend rendre inutile.
- `tools/wasi/host.py` — l'hôte de référence, étalon de « ce qu'un intégrateur doit écrire
  aujourd'hui ».
- `docs/kotlin-wasm-wasi.md:142` — les notes sur `componentModelRealloc` et
  `@ComponentModelInternalApi`, écartés en w03 par la décision structurante n°2.
- Le sample JetBrains, en lecture : sa glue est l'état de l'art disponible.

## Steps

### 1. D'abord le mur, ensuite le reste

L'étape qui peut tuer la fiche en une demi-journée, donc **la première** : prendre le `.wasm`
publié, sans le modifier, et tenter

```bash
wasm-tools component new vcyclist-engine.wasm \
  --adapt wasi_snapshot_preview1=wasi_snapshot_preview1.reactor.wasm \
  -o vcyclist-engine.component.wasm
```

Le module exige `gc`, `function-references` et le **nouveau** handling d'exceptions (`exnref`,
cf. §2 de l'ABI). L'adapter Preview 1 est écrit pour des modules à mémoire linéaire ; qu'il
digère un module WASM-GC n'est **pas** acquis. Trois issues possibles, toutes à écrire telles
quelles :

- il refuse → verdict immédiat, avec le message d'erreur et la version de `wasm-tools` ;
- il produit un composant qui ne s'instancie pas → noter à quel stade ;
- il produit un composant valide → continuer, en notant que les trois imports `vcyclist` sont
  alors devenus des imports de composant à typer.

### 2. Le `.wit` de l'API telle qu'elle est

Traduire l'ABI v1 en WIT **sur le papier**, sans l'implémenter. C'est le livrable qui a de la
valeur même si l'étape 1 a échoué : il chiffre l'écart entre le contrat actuel et sa forme
standard.

Correspondances à établir explicitement, une par une :

| ABI v1 | Forme WIT attendue | Ce qui disparaît |
|---|---|---|
| handles `Int` + `vcRelease` / `vcReleaseAll` (§5) | `resource path`, `resource path-list` | la table côté guest, le compteur partagé, les handles fuités |
| sentinelles `-1`…`-4` + `vcLastError` (§6) | `result<T, engine-error>` | la lecture d'erreur différée, le NaN de `vcDominantHeadwindAzimuth` |
| `read_input` / `write_output` + arène scopée (§4) | `string`, `list<u8>`, `list<f64>` | les deux règles qui « corrompent l'état plutôt que d'échouer » |
| options JSON par longueur d'octets (§7) | `record enhance-options` | le parsing JSON des deux côtés |
| `vcPathFieldBytes` (§7) | `list<f64>` | rien : le cas où l'ABI actuelle est déjà optimale |
| `fetch_tile` (§8) | import `wasi:http/outgoing-handler` | **toute la §8** |

Poser ce fichier dans `tools/wasi-component/vcyclist-engine.wit`, hors du build Gradle.

### 3. Chiffrer le gain `wasi:http`

C'est le seul gain **fonctionnel** de l'opération, tout le reste étant de l'ergonomie : depuis
w11 et w12 le module est autonome sauf pour le réseau, et le réseau est précisément ce que
Preview 1 ne peut pas fournir. Vérifier dans le sample que l'`outgoing-handler` marche
réellement depuis Kotlin (une requête sortante aboutie, pas seulement compilée), et estimer ce
que `TileFetcher.wasmWasi.kt` deviendrait.

Question à trancher au passage : un client HTTP côté guest doit-il refaire le cache de tuiles,
la limitation de concurrence et les retries que l'hôte assurait ? Si oui, la §8 ne disparaît pas,
elle déménage — et le gain net est plus faible qu'annoncé. Écrire la réponse.

### 4. Un export de bout en bout, le plus petit possible

Uniquement si 1 et 3 sont concluants. Trois exports, pas trente : `abi-version`, `parse-gpx`
(string entrante), `total-distance` (f64 sortant). Piloté par `wasmtime-py` via les bindings
**générés**, pas à la main — la démonstration porte sur la génération, pas sur le résultat.

Mesurer et noter : taille du composant vs 318 Ko du module, volume de glue manuelle nécessaire,
et si `@ComponentModelInternalApi` a dû être touché (auquel cas la décision n°2 du plan est en
jeu, et le verdict doit le dire).

### 5. Le verdict

`docs/wasm-wasi-component-model.md`, qui répond dans cet ordre :

1. Est-ce **mécaniquement** possible aujourd'hui, avec quelles versions exactes ?
2. Qu'est-ce que ça supprime pour un intégrateur — comparer `host.py` (≈ N lignes) à son
   équivalent généré, chiffres à l'appui ?
3. Qu'est-ce que ça coûte : ABI v2, refonte de `EngineWasiApi`, des 466 lignes de
   `wasm-wasi-abi.md`, de `host.py` et de la CI ; quels runtimes on perd (wazero est déjà hors
   jeu par le GC, WasmEdge à vérifier) ?
4. **Verdict** : phase F cadrée avec ses tâches, ou refus daté avec la condition de réouverture
   (« quand KT-64569 est résolue », « quand l'adapter accepte les modules GC »).

## Outputs

- `docs/wasm-wasi-component-model.md` — le verdict. Le vrai livrable.
- `tools/wasi-component/vcyclist-engine.wit` — l'API v1 en WIT, hors build.
- Éventuellement un composant jetable + son hôte généré, dans le même répertoire, non intégré à
  la CI.
- Une ligne modifiée dans `PLAN-WASM-WASI.md` § « Ce qui n'est explicitement pas fait » : la
  présomption remplacée par un lien vers le verdict.

## Validation

- [x] `wasm-tools component new` tenté sur le binaire publié, résultat consigné avec les versions
      de `wasm-tools`, `wasmtime` et Kotlin. → il **accepte** le module WASM-GC (526 849 o, valide)
      mais produit un composant **sans aucun export**.
- [x] Le `.wit` couvre les **33 exports** de la §7 — ou nomme ceux qui ne se traduisent pas et
      pourquoi. → 27 fonctions, 6 supprimées plutôt que traduites, aucune intraduisible.
- [x] Le gain `wasi:http` est chiffré, cache et retries compris, pas seulement affirmé. → HTTP 200
      réel sur une vraie tuile, ~110 lignes de glue pour le seul statut, et §8 **déménage** vers le
      guest au lieu de disparaître. Le générateur Kotlin, lui, **refuse** l'arbre WIT de
      `wasi:http` : le seul monde qui rapporte est celui qui ne se génère pas.
- [x] `docs/wasm-wasi-component-model.md` tranche explicitement, sans « à voir ». → refus daté,
      condition de réouverture nommée (KT-64569).
- [x] `./gradlew check` reste vert : **rien** de la production n'a bougé.
- [x] Arbre propre — les artefacts jetables sont dans `tools/wasi-component/`, `.gitignore`
      ajusté si besoin. → `build/` était déjà ignoré, rien à ajuster.

## Résultat

**Décision du 2026-07-31 : pas maintenant, phase F cadrée et en attente.** Le verdict est
[`docs/wasm-wasi-component-model.md`](../plans/wasm-wasi-component-model.md), les mesures se rejouent
avec [`tools/wasi-component/reproduce.sh`](../../../tools/wasi-component/README.md).

Trois écarts avec la fiche, assumés :

1. **La dépendance à w08 n'est pas respectée** — le spike tourne sur 2.4.20-Beta2, w08 et w07
   n'étant pas faites. Aucun résultat n'en dépend (voir §6 du verdict), mais c'est à savoir.
2. **L'étape 1 ne s'est pas arrêtée au premier refus** : l'encodeur exige un adapter pour les
   imports `vcyclist` avant de dire quoi que ce soit du reste. Un module de stub lève l'obstacle,
   et c'est ce refus-là qui est consigné.
3. **L'étape 4 va plus loin que ses trois exports** : elle a d'abord fabriqué à la main le
   `cabi_realloc`, une `string` sortante et dix imports `wasi:http`. Puis, une fois
   [`Kotlin/wit-bindgen`](https://github.com/Kotlin/wit-bindgen) trouvé, elle a refait le même
   bout en bout avec des bindings **générés** depuis `vcyclist-engine.wit` — 53 lignes de stubs
   au lieu de 791. La première rédaction du verdict, qui affirmait qu'aucun générateur n'existait,
   a été corrigée ; l'encadré en tête du verdict le dit.

## Done when

Le plan peut remplacer « Kotlin n'émet pas de composant » par une raison **mesurée** — ou ouvrir
une phase F dont le coût est connu avant de la commencer.

## Notes

### Non-buts, à tenir

- **L'ABI v1 ne bouge pas.** Elle est figée, versionnée, documentée et jouée en CI ; un spike ne
  la touche pas, même « juste pour voir ». `vcAbiVersion` reste à 1.
- **Rien n'est publié.** Ni Maven Central, ni asset GitHub, ni entrée dans `publishing.md`.
- **`EngineWasiApi.kt` n'est pas modifié.** Si l'étape 4 exige des exports aux noms canoniques
  (`vcyclist:engine/path#enhance`), ils vivent dans un fichier jetable sous `tools/`, pas dans
  `wasmWasiMain`.
- Pas de dépendance nouvelle dans `build.gradle.kts`. `wasm-tools` est un binaire qu'on invoque,
  pas un plugin qu'on adopte.

### Pourquoi maintenant et pas avant

w11 et w12 ont retiré à l'hôte le décodage WebP puis l'encodage FIT. Il ne lui reste **que** le
réseau — et c'est le seul manque que Preview 1 ne peut pas combler par plus de code Kotlin. La
question du Component Model n'est donc plus une question de confort d'intégration : c'est la
seule voie connue vers un module réellement autonome. Elle mérite une réponse mesurée, pas la
phrase par défaut du plan.

### Pourquoi timeboxé si serré

Le sample JetBrains annonce lui-même des bindings « subject to change and certainly not final ».
Sur un projet dont l'ABI est figée et testée, échanger un contrat stable contre un générateur
non stabilisé est un mauvais marché — sauf si l'étape 3 démontre que `wasi:http` change la nature
du produit. Le spike existe pour trancher ce point-là ; tout le reste (résultats, records, plus
de `vcLastError`) est de l'élégance, et l'élégance ne justifie pas une ABI v2.

### Le piège à éviter

Un composant qui « s'instancie » n'est pas un composant qui marche. Le critère de l'étape 4 est
un `total-distance` **juste** sur une trace réelle, obtenu via des bindings générés — pas une
instanciation sans erreur. Même exigence qu'en w11 sur le test vert qui n'exécutait rien.
