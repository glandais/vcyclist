# Kotlin/Wasm WASI : cible `wasmWasi`, wasmtime, publication Maven et module `.wasm` autonome

Synthèse des points techniques validés par le POC de la branche `feat/wasm-wasi` (juillet 2026) :
ajout de la cible `wasmWasi` avec Kotlin **2.4.20-Beta2**, tests sous **wasmtime**, publication
des variantes `-wasm-wasi` sur Maven, et production d'un module `.wasm` autonome piloté depuis
Python via **wasmtime-py**. Remplace les hypothèses de l'ancienne fiche
`w01-wasmwasi-numeric-facade.md` (écrite avant la suppression de wasmJs et avant le support
wasmtime de KGP), supprimée depuis.

Ce document est une **note d'ingénierie** : ce qui a été mesuré et pourquoi. La suite de travaux
qu'il alimente est [`PLAN-WASM-WASI.md`](PLAN-WASM-WASI.md).

Si vous cherchez plutôt **comment appeler le module depuis un hôte** — imports à fournir, exports,
codes d'erreur, schémas JSON — c'est [`wasm-wasi-abi.md`](wasm-wasi-abi.md), et l'hôte de référence
exécuté par la CI est [`tools/wasi`](../tools/wasi/README.md). Le présent document explique
*pourquoi* l'ABI a cette forme ; l'autre explique *comment* s'en servir.

---

## 1. DSL Gradle : `wasmWasi { wasmtime() }`

Le support wasmtime est arrivé dans KGP avec 2.4.20-Beta2 (KT-86633, wasmtime 46 via KT-86878).
Plus besoin de Node.js pour exécuter les tests WASI :

```kotlin
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
wasmWasi {
    wasmtime()
    // Uniquement pour produire le .wasm autonome (§5) — inutile pour une pure lib klib.
    binaries.executable()
}
```

- `wasmtime()` accepte un bloc de configuration ; l'unique réglage est
  `wasmtimeRunArgs: ListProperty<String>` (arguments CLI supplémentaires).
- **Tâches créées** : `wasmWasiWasmtimeTest` (exécute `commonTest` dans wasmtime),
  `kotlinWasmWasmtimeSetup` (télécharge wasmtime dans `~/.gradle/wasmtime/`, v46.0.1 constaté —
  rien à installer, ni en local ni en CI ; prévoir juste le cache de ce dossier).
- KGP lance wasmtime avec `-W function-references,gc,exceptions` : ce sont les trois proposals
  Wasm dont tout runtime hôte aura besoin (§6). Kotlin 2.4 émet le *nouveau* exception handling
  (`exnref`), le seul que wasmtime accepte.
- `wasmWasiWasmtimeTest` est un `KotlinJsTest` : les blocs existants
  `tasks.withType<KotlinJsTest>` (propagation `INTEGRATION`, timeouts…) s'y appliquent sans
  modification.
- **Rugosité de la Beta2** : KGP logue `⚠️ JS Environment Not Selected` même quand `wasmtime()`
  est choisi — le check ne connaît que `nodejs()`. Avertissement inoffensif, à surveiller pour
  2.4.20 final.

## 2. Compatibilité des dépendances et `expect`/`actual`

Les deux seules dépendances tierces de `commonMain` publient des variantes `wasm-wasi` :
`kotlinx-coroutines-core:1.11.0` et `xmlutil:1.0.1`. Conséquences mesurées :

- **`:gpx` compile et passe toute sa suite `commonTest` sous wasmtime sans toucher une ligne**
  (240 tests verts) — parsing GPX (xmlutil), modèle `Path`, resamplers, simplifier.
- **`:elevation`** demande exactement 4 `actual` :
  - `TileFetcher.wasmWasi.kt` : `fetchTileBytes` / `decodeTileBytes` / `fetchAndDecodeTile` en
    stubs `UnsupportedOperationException` — WASI n'a ni client HTTP standard ni décodeur
    d'images ; l'hôte doit injecter son fetcher via le seam de `TileManager` (tâche g21).
  - `IntegrationGate.wasmWasi.kt` : `integrationEnabled() = false` (pas de réseau).
  - Bilan : 191/198 tests verts ; les 7 échecs sont tous `TileDecodeSplitTest`, c'est-à-dire
    exactement le décodage WebP stubbé. Pour une intégration réelle : soit porter le décodeur
    VP8L pur Kotlin (tâche w11), soit sortir ces 7 tests de la cible wasmWasi (tâche w01).
- `kotlin("test")` et `kotlinx-coroutines-test` (`runTest`) fonctionnent tels quels.
- La montée 2.4.10 → 2.4.20-Beta2 n'a cassé que `kotlin-js-store/yarn.lock` (tooling JS de KGP) :
  `./gradlew kotlinUpgradeYarnLock`, et `./gradlew check` est vert.

## 3. Publication Maven : un artifactId suffixé, pas un classifier

Le plugin vanniktech (0.37.0) prend la nouvelle cible en charge **sans aucune configuration** :

```text
io/github/glandais/vcyclist-gpx-wasm-wasi/2.0.0/
  vcyclist-gpx-wasm-wasi-2.0.0.klib        ← la bibliothèque (IR Kotlin, pas un .wasm linké)
  vcyclist-gpx-wasm-wasi-2.0.0-sources.jar
  vcyclist-gpx-wasm-wasi-2.0.0-javadoc.jar
  vcyclist-gpx-wasm-wasi-2.0.0.module      ← métadonnées Gradle
  vcyclist-gpx-wasm-wasi-2.0.0.pom
```

Points à retenir :

- C'est le **layout KMP standard** : un module Maven séparé par cible (suffixe d'artifactId
  `-wasm-wasi`), référencé par les variantes `wasmWasiApiElements/RuntimeElements` des
  métadonnées Gradle du module racine `vcyclist-gpx`. Ce n'est **pas** un classifier Maven — et
  ce n'est pas nécessaire : un consommateur Gradle résout la variante automatiquement.
- La tâche `publishWasmWasiPublicationToMavenCentralRepository` existe dès que la cible est
  déclarée ; la publication agrégée existante l'embarque.
- Ce qui est publié est un **`.klib`** (consommable par un autre build Kotlin), pas un `.wasm`
  exécutable. Pour distribuer le `.wasm` autonome du §5 sur Maven Central, il faudrait
  l'attacher explicitement à la publication comme artefact à classifier (petit bloc `artifact()`
  custom — non couvert par KMP).

Consommateur minimal validé (résolution depuis mavenLocal, test exécuté sous wasmtime) :

```kotlin
// build.gradle.kts du consommateur
plugins { kotlin("multiplatform") version "2.4.20-Beta2" }

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi { wasmtime() }
    sourceSets {
        commonMain.dependencies { implementation("io.github.glandais:vcyclist-gpx:2.0.0") }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
```

## 4. Réserve : publier avec un compilateur Beta

2.4.20-Beta2 garde le format de métadonnées 2.4, donc les consommateurs 2.4.x restent
compatibles (y compris pour les artefacts JVM/JS, eux aussi recompilés par la Beta). Préférer
néanmoins attendre 2.4.20 final avant une release Maven Central réelle.

## 5. Module `.wasm` autonome : formes possibles

`binaries.executable()` produit un `.wasm` linké et optimisé (binaryen) dans
`build/compileSync/wasmWasi/main/productionExecutable/optimized/` — **145 Ko** pour `:gpx`
façade comprise. La forme du module dépend de la présence d'un `fun main()` :

| Source | Exports | Initialisation |
|---|---|---|
| avec `fun main()` | `_start`, `memory`, `@WasmExport…` | module *commande* WASI : l'hôte appelle `_start` (initialise puis exécute `main`) |
| sans `main()` | `memory`, `@WasmExport…` seulement | **section `start` Wasm** : les initialiseurs globaux tournent automatiquement à l'instanciation ; rien à appeler |

Contrairement à ce que documentait l'ancienne fiche w01 (Kotlin antérieur), Beta2 n'exporte **pas**
`_initialize` : la forme sans `main` s'initialise toute seule. C'est la forme « bibliothèque »
recommandée : instancier, puis appeler directement les exports.

Le module n'importe que `wasi_snapshot_preview1` + les imports `@WasmImport` déclarés (§6).
Les imports custom sont **obligatoires à l'instanciation** — mais ils sont éliminés par DCE du
binaire de *test* tant qu'aucun test n'appelle la façade, donc `wasmWasiWasmtimeTest` du module
n'en souffre pas. Corollaire : si un `commonTest` exerce un jour la façade, le runner KGP ne
saura pas fournir ces imports — la façade complète devra vivre dans un module dont les tests
peuvent les fournir (ou rester hors `commonTest`).

## 6. ABI : le protocole à callbacks (aucune API interne)

`@WasmExport` n'accepte que des types numériques (`Int`, `Double`…) : les objets deviennent des
handles entiers, les chaînes transitent par la mémoire linéaire. L'ancienne fiche w01 envisageait
l'arène `componentModelRealloc` (`@ComponentModelInternalApi`, avec ses règles piégeuses) ; le POC
montre qu'un **protocole à callbacks** suffit, en n'utilisant que l'API publique
`kotlin.wasm.unsafe.withScopedMemoryAllocator` :

- **Chaîne entrante** : l'hôte appelle `vcParseGpx(byteLen)` ; le *guest* alloue `byteLen`
  octets dans une arène scopée et rappelle l'import `vcyclist.read_input(ptr, cap)`, pendant
  lequel l'hôte écrit l'UTF-8 en mémoire linéaire. Copie sur le tas WasmGC avant la fermeture
  du scope — aucun pointeur ne survit à l'appel d'export.
- **Chaîne sortante** : le guest écrit l'UTF-8 dans une arène scopée et appelle
  `vcyclist.write_output(ptr, len)`, pendant lequel l'hôte copie.
- **Interdit** : que l'hôte rappelle un export du module pendant un callback (les scopes
  imbriqués de l'allocateur jettent).
- **Erreurs** : une exception ne traverse pas la frontière Wasm ; chaque export catch et
  retourne une sentinelle négative, le message est récupérable via `vcLastError`.

Implémentation de référence : `gpx/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/GpxWasiApi.kt`.
L'essentiel :

```kotlin
@file:OptIn(UnsafeWasmMemoryApi::class)

@WasmImport("vcyclist", "read_input")
private external fun readInput(ptr: Int, cap: Int): Int

private val handles = HashMap<Int, Path>()
private var nextHandle = 1
private var lastError = ""

private fun readBytesFromHost(byteLen: Int): ByteArray {
    val bytes = ByteArray(byteLen)
    withScopedMemoryAllocator { allocator ->
        val buf = allocator.allocate(byteLen)
        val got = readInput(buf.address.toInt(), byteLen)
        check(got == byteLen) { "host wrote $got bytes, expected $byteLen" }
        for (i in 0 until byteLen) bytes[i] = (buf + i).loadByte()
    }
    return bytes
}

@WasmExport
fun vcParseGpx(byteLen: Int): Int =
    try {
        val doc = GpxParser.parse(readBytesFromHost(byteLen).decodeToString())
        val handle = nextHandle++
        handles[handle] = doc.firstTrackAsPath()
        handle
    } catch (t: Throwable) {
        lastError = t.message ?: "unknown error"
        -1
    }

@WasmExport
fun vcPathTotalDistance(handle: Int): Double = handles[handle]?.totalDistance ?: Double.NaN
```

## 7. Hôte Python : wasmtime-py

Validé avec wasmtime-py **47.0.1** (`pip install wasmtime`) sur le `.wasm` optimisé, contre
`demo/public/gpx/stelvio.gpx` : 259 points, distance identique à un haversine Python à 10⁻⁶
près, aller-retour XML, chemin d'erreur et cycle de vie des handles.

```python
from wasmtime import (Config, Engine, Func, FuncType, Linker, Module, Store,
                      ValType, WasiConfig)

# Les trois proposals que KGP passe lui-même au CLI wasmtime.
config = Config()
config.wasm_function_references = True
config.wasm_gc = True
config.wasm_exceptions = True          # setters write-only : hasattr() les ment
engine = Engine(config)

module = Module.from_file(engine, "vcyclist-gpx.wasm")
store = Store(engine)
wasi = WasiConfig()
wasi.inherit_stdout()                  # println() du guest → stdout Python
store.set_wasi(wasi)

linker = Linker(engine)
linker.define_wasi()

staged, captured = {"data": b""}, {"data": b""}

def read_input(caller, ptr, cap):      # le guest demande les octets stagés
    mem = caller.get("memory")
    data = staged["data"][:cap]
    mem.write(caller, data, ptr)
    return len(data)

def write_output(caller, ptr, length): # le guest pousse un résultat UTF-8
    mem = caller.get("memory")
    captured["data"] = bytes(mem.read(caller, ptr, ptr + length))

i32 = ValType.i32()
linker.define(store, "vcyclist", "read_input",
              Func(store, FuncType([i32, i32], [i32]), read_input, access_caller=True))
linker.define(store, "vcyclist", "write_output",
              Func(store, FuncType([i32, i32], []), write_output, access_caller=True))

instance = linker.instantiate(store, module)   # section start → initialisation auto
exports = instance.exports(store)

gpx = open("stelvio.gpx", "rb").read()
staged["data"] = gpx
handle = exports["vcParseGpx"](store, len(gpx))          # > 0, ou -1 (voir vcLastError)
points = exports["vcPathSize"](store, handle)
distance = exports["vcPathTotalDistance"](store, handle)
exports["vcWriteGpx"](store, handle)                     # XML dans captured["data"]
exports["vcRelease"](store, handle)
```

Pièges rencontrés :

- Les propriétés `Config.wasm_*` sont des setters *write-only* : `hasattr()` retourne `False`
  alors qu'elles existent — les assigner directement.
- `caller.get("memory")` + `mem.read/write(caller, …)` : le `Caller` sert de contexte de store
  pendant un callback.
- Choisir la version wasmtime-py ≥ celle que KGP utilise (46) — les proposals GC/exnref sont
  récents.

## 8. Récapitulatif des choix validés et points ouverts

Validé :

1. Kotlin 2.4.20-Beta2 : montée sans casse (`check` vert), Beta disponible sur Maven Central.
2. `wasmWasi { wasmtime() }` : tests `commonTest` exécutés dans wasmtime auto-provisionné.
3. Publication : variantes `-wasm-wasi` (klib) produites et résolues par un consommateur réel.
4. `.wasm` autonome de 145 Ko, ABI à callbacks sans API interne, piloté par wasmtime-py.

Ouvert (hors POC) :

- Étendre la cible à `:fit` (1 stub `FitEncoder`) puis `:engine` (ordre imposé par les `api()`),
  et y déplacer la façade complète façon `EngineJsApi` (tâches w01, w03, w04).
- Sort des 7 tests WebP sur wasmWasi : décodeur VP8L pur Kotlin ou gating par cible.
- Attacher le `.wasm` autonome à la publication Maven (classifier custom) si on veut le
  distribuer tel quel.
- Attendre 2.4.20 final pour publier ; re-vérifier alors l'avertissement « JS Environment Not
  Selected » et l'absence de `_initialize`.
