# `tools/wasi` — hôte WASI de référence

Un vrai hôte, en Python, qui charge `engine/build/wasm/vcyclist-engine.wasm` et l'utilise comme
le ferait un embarqueur : `wasmtime-py` pour le runtime, les trois imports câblés, les exports
appelés à travers une petite façade.

C'est le **seul** endroit où l'ABI est exercée de bout en bout. `wasmWasiWasmtimeTest`, le runner
de KGP, ne sait pas fournir les imports custom (`vcyclist.read_input`, `write_output`,
`fetch_tile`) : un test qui atteint un export qui en utilise un ne s'instancie même pas — voir
[`docs/kotlin-wasm-wasi.md`](../../docs/kotlin-wasm-wasi.md) §5. Tout ce que le module promet à un
hôte se vérifie donc ici.

C'est aussi la **documentation exécutable** de l'ABI : [`docs/wasm-wasi-abi.md`] pointe sur
`host.py` plutôt que de recopier du Python dans un markdown qui divergerait.

## Lancer

```bash
pip install -r tools/wasi/requirements.txt

./tools/wasi/run-all.sh                 # hors ligne
INTEGRATION=1 ./tools/wasi/run-all.sh   # + télécharge de vraies tuiles DEM
```

`run-all.sh` construit le binaire (`:engine:wasmModule`), le pèse (`:engine:checkWasmModuleSize`)
puis lance la suite. Sans le script :

```bash
./gradlew :engine:wasmModule
cd tools/wasi && python3 -m unittest discover -s . -p 'test_*.py' -v
```

## Les fichiers

| Fichier | Rôle |
|---|---|
| `host.py` | La classe `VcyclistHost` : proposals, `Linker`, les trois imports, une méthode par export |
| `fixtures.py` | Les GPX et les métriques attendues, **extraits des sources Kotlin** (voir plus bas) |
| `test_engine.py` | Les assertions, en `unittest` (stdlib — une dépendance de moins en CI) |
| `requirements.txt` | `wasmtime` ≥ celle que KGP provisionne ; `Pillow` pour les seuls tests réseau |

## Ce qui est couvert

- **Protocole** : `vcAbiVersion`, cycle de vie des handles (`vcRelease`, `vcReleaseAll`, handle
  jamais réémis), les quatre codes d'erreur, `vcLastError` vide avant tout échec.
- **Parsing** : aller-retour GPX (chaque point survit), les quatre modes de `vcParseGpxMulti`,
  handles de liste indépendants, GPX invalide → erreur et non plantage.
- **Champs** : catalogue dense et ordonné (les index *sont* les identifiants),
  `vcPathFieldBytes` d'accord avec `vcGetField` point par point, index hors bornes refusé.
- **Pipeline** : `vcEnhance` comparé aux **mêmes références que `EnhancerParityTest`** sur la
  JVM, à 0,5 % ; `vcEnhanceWithCourse` (400 W sur 65 kg bat 120 W sur 90 kg) ; l'entrée n'est
  jamais modifiée.
- **Exports** : CSV, JSON colonne, cols, vent dominant, waypoints.
- **Élévation** : géométrie de tuile, configuration refusée si absurde, chemin « pas de DEM »
  (0 m, pas −32768), et sous `INTEGRATION=1` la correction par de vraies tuiles Mapterhorn.
- **Compatibilité runtime** : `wasmtime` en ligne de commande doit échouer sur un **import
  manquant** — pas sur « proposal non supporté » ni « module invalide ». C'est ce test qui
  attrapera une montée de Kotlin ou de wasmtime qui casse le format.

## Les références viennent des sources Kotlin

`fixtures.py` lit `GpxFixtures.kt` et `ParityFixtures.kt` **à l'exécution** : les documents GPX
et les cinq métriques attendues ne sont recopiés nulle part. Un harnais de parité dont les
références sont périmées est pire que pas de harnais du tout — il continue de passer. Si une
fixture est renommée, l'extraction échoue en nommant le fichier où elle a cherché.

Les options de `vcEnhance` utilisées pour la comparaison sont celles de
`EnhancerParityTest.runPipeline` (`EnhanceOptions.DEFAULT` sans la correction d'altitude), pas
les défauts WASI — ceux-ci sont les défauts **JS** (ni ré-échantillonnage 1 Hz, ni simplification)
et mesureraient un autre pipeline.

## Les trois imports sont obligatoires

Y compris `fetch_tile`, y compris pour un hôte qui ne corrigera jamais d'altitude : les imports
Wasm sont résolus à l'instanciation, donc il en manque un et le module ne charge pas du tout.
`host.no_tiles` est l'implémentation minimale — elle répond toujours « pas de tuile ».

## wasmtime : lequel ?

Le smoke CLI utilise le binaire que **KGP a provisionné** dans `~/.gradle/wasmtime/`, pas celui du
système. Tester une autre version que celle de la CI donnerait des verts locaux et des rouges en
CI sur les proposals GC / exnref. S'il est absent (aucune tâche `wasmWasi` jamais lancée), le test
est *skippé* plutôt que faux.

[`docs/wasm-wasi-abi.md`]: ../../docs/wasm-wasi-abi.md
