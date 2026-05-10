# 07 — Elevation : LRU cache (KMP) + TileManager

## Goal

Introduire deux briques de concurrence côté `commonMain` :

- **`LruCache<K, V>`** : cache LRU générique avec loader `suspend`, **déduplication par clé** (deux coroutines qui demandent la même clé partagent un seul `loader`), éviction LRU O(1) basée sur `LinkedHashMap` (stdlib KMP), hook optionnel `onEvict`.
- **`TileManager`** : assemble `urlTemplate`, `fetchAndDecodeTile` (tâche 06) et un `LruCache<TileCoordinates, Tile>` ; expose `suspend fun getTile(tc): Tile`.

Tests communs aux 3 targets (JVM, JS Node, Wasm browser) en utilisant `kotlinx-coroutines-test` (déjà introduit en tâche 06).

## Depends on

- `05-elevation-tile-types-decoding` (`TileCoordinates`, `Tile`, `RawTile`)
- `06-elevation-tile-fetcher` (`fetchAndDecodeTile`, `kotlinx-coroutines-test`)

## Inputs

Sources de référence (NON portées littéralement — Kotlin coroutines idioms sont très différents de l'event-loop JS) :

- `/home/glandais/code/perso/vcyclist-all/elevation/src/tile/cache/Cache.ts` — sémantique LRU et déduplication
- `/home/glandais/code/perso/vcyclist-all/elevation/src/tile/cache/ReentrantLock.ts` — pattern dédup + semaphore
- `/home/glandais/code/perso/vcyclist-all/elevation/src/tile/TileManager.ts` — wiring `urlTemplate` + cache + loader

On **n'inclut pas** le semaphore "max concurrent slots" du `ReentrantLock` TS : c'est une responsabilité de `Reactive` (tâche 08). `LruCache` se contente de la déduplication par clé.

## Steps

### 1. `LruCache.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/LruCache.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Suspend-aware LRU cache with per-key load deduplication.
 *
 * Properties:
 * - `O(1)` get/put using `LinkedHashMap` insertion order (re-insertion = move-to-back).
 * - When two coroutines call [get] for the same missing key, only one [loader] invocation runs ;
 *   the second awaits the same `CompletableDeferred`.
 * - When [size] exceeds [maxSize], the least-recently-used entry is evicted ; if [onEvict] is set,
 *   it is invoked with the evicted value (intended for releasing resources, e.g. `bitmap.close()`).
 * - Thread-safe across coroutine dispatchers via a single [Mutex] guarding mutable state.
 *
 * Not safe for concurrent JVM threads outside coroutines — coroutine dispatchers guarantee
 * happens-before via the suspension/resumption machinery.
 *
 * @param maxSize must be > 0
 * @param loader called on miss to produce the value
 * @param onEvict optional cleanup invoked when an entry is evicted (or [clear]ed)
 */
class LruCache<K : Any, V : Any>(
    private val maxSize: Int,
    private val loader: suspend (K) -> V,
    private val onEvict: ((V) -> Unit)? = null,
) {
    init {
        require(maxSize > 0) { "Cache size must be greater than 0" }
    }

    private val mutex = Mutex()
    private val entries: LinkedHashMap<K, V> = LinkedHashMap()
    private val inFlight: MutableMap<K, CompletableDeferred<V>> = mutableMapOf()

    /** Look up [key] in the cache, loading via [loader] if absent. */
    suspend fun get(key: K): V {
        // Fast path: cache hit — move to end (most-recently used) and return.
        mutex.withLock {
            entries.remove(key)?.let { v ->
                entries[key] = v
                return v
            }
            // Existing load in progress?
            inFlight[key]?.let { return@withLock /* fall through */ Unit }
        }

        // If another coroutine is already loading this key, await its result.
        inFlight[key]?.let { existing ->
            return existing.await()
        }

        // Register our own deferred (re-check inside lock against a TOCTOU race).
        val ours = CompletableDeferred<V>()
        mutex.withLock {
            entries.remove(key)?.let { v ->
                entries[key] = v
                ours.complete(v)
                return v
            }
            inFlight[key]?.let { existing ->
                return existing.await()
            }
            inFlight[key] = ours
        }

        // Call loader outside the lock to avoid blocking other operations.
        val value = try {
            loader(key)
        } catch (t: Throwable) {
            mutex.withLock { inFlight.remove(key) }
            ours.completeExceptionally(t)
            throw t
        }

        mutex.withLock {
            inFlight.remove(key)
            if (entries.size >= maxSize && key !in entries) {
                val it = entries.entries.iterator()
                val oldest = it.next()
                it.remove()
                onEvict?.invoke(oldest.value)
            }
            entries[key] = value
        }
        ours.complete(value)
        return value
    }

    /** Evict all entries (calling [onEvict] on each). */
    suspend fun clear() = mutex.withLock {
        if (onEvict != null) entries.values.forEach { onEvict.invoke(it) }
        entries.clear()
    }

    // ---- Test inspection (internal so commonTest can reach it) -------------

    internal suspend fun snapshotKeys(): List<K> = mutex.withLock { entries.keys.toList() }
    internal suspend fun snapshotSize(): Int = mutex.withLock { entries.size }
    internal suspend fun lruKey(): K? = mutex.withLock { entries.keys.firstOrNull() }
}
```

**Notes design** :
- Pas de `getDirect` synchrone (`getDirect` du TS) : en KMP on n'a pas de `synchronized` cross-platform. Le `get` suspend est déjà non-bloquant côté coroutines ; le surcoût d'un `mutex.withLock` sur cache hit est négligeable (microseconde).
- **TOCTOU race** : entre la vérification initiale (`entries.remove(key)?.let { ... }`) et l'enregistrement de `ours` dans `inFlight`, une autre coroutine peut compléter ; on re-vérifie dans la deuxième `withLock`.
- **`inFlight` map** : déduplication par clé. Un `CompletableDeferred<V>` est partagé entre toutes les coroutines qui attendent. Si la 1ère échoue, toutes reçoivent l'exception.
- **`onEvict` synchrone** : on n'autorise pas `suspend` ici. Pour un Tile Kotlin, il n'y a rien à libérer (le `ByteArray` est GC-géré). Pour un futur Wasm `ImageBitmap`, on devra exposer une variante `suspend onEvict` — refactor pour plus tard si besoin.
- **Pas de semaphore "max concurrent"** : la limite de concurrence (équivalent du TS `ReentrantLock.maxConcurrent`) sera implémentée dans `Reactive` (tâche 08).

### 2. `TileManager.kt`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/TileManager.kt` :

```kotlin
package io.github.glandais.elevation

/**
 * Caches decoded [Tile]s keyed by [TileCoordinates], using [fetcher] to download missing tiles
 * via [urlTemplate].
 *
 * The [urlTemplate] follows the convention `https://host/{z}/{x}/{y}.webp` — the `{z}`, `{x}` and
 * `{y}` placeholders are substituted with the integer tile coordinates.
 *
 * @param urlTemplate template URL with `{z}`, `{x}`, `{y}` placeholders
 * @param cacheSize maximum number of tiles kept in memory (LRU eviction beyond this)
 * @param fetcher pluggable for tests ; defaults to the platform-specific [fetchAndDecodeTile]
 */
class TileManager(
    val urlTemplate: String,
    cacheSize: Int,
    private val fetcher: suspend (String) -> RawTile = ::fetchAndDecodeTile,
) {
    private val cache: LruCache<TileCoordinates, Tile> = LruCache(
        maxSize = cacheSize,
        loader = { tc -> Tile(fetcher(buildUrl(tc))) },
    )

    suspend fun getTile(tileCoords: TileCoordinates): Tile = cache.get(tileCoords)

    suspend fun clear() = cache.clear()

    private fun buildUrl(tc: TileCoordinates): String =
        urlTemplate
            .replace("{z}", tc.z.toString())
            .replace("{x}", tc.x.toString())
            .replace("{y}", tc.y.toString())

    // Visible-for-test
    internal suspend fun cachedKeys(): List<TileCoordinates> = cache.snapshotKeys()
}
```

**Notes design** :
- `fetcher` injectable : pratique pour les tests (fournir un fetcher in-memory) et pour de futures intégrations (Ktor client, mock-server, etc.).
- Pas de lazy init du cache (TS avait `initCache` async parce que le `TileFetcher` était dynamiquement importé selon l'env). Côté KMP c'est résolu à la compile via `expect/actual`.
- Pas de `getTileDirect` (cf. note `LruCache`).

### 3. Tests `LruCacheTest.kt`

`elevation/src/commonTest/kotlin/io/github/glandais/elevation/LruCacheTest.kt`. Tous les tests utilisent `runTest` (kotlinx-coroutines-test).

Cas à couvrir :

| # | Cas | Attendu |
|---|---|---|
| 1 | `maxSize <= 0` rejeté à la construction | `IllegalArgumentException` message `"Cache size must be greater than 0"` |
| 2 | `get` miss → appelle loader 1 fois | loader counter == 1, valeur correcte |
| 3 | `get` hit → ne réappelle pas loader | loader counter == 1 après 2 appels |
| 4 | Éviction LRU au-delà de `maxSize` | size=2, get(A), get(B), get(C) → `A` évicté (snapshot keys = [B, C]) |
| 5 | `onEvict` appelé sur éviction | counter incrémenté à 1 après le scénario #4 |
| 6 | Accès rafraîchit la position LRU | size=2, get(A), get(B), get(A), get(C) → `B` évicté (snapshot keys = [A, C]) |
| 7 | Déduplication concurrente | 2 `launch { cache.get(key) }` parallèles → loader appelé 1 fois |
| 8 | Loader qui jette propage l'exception aux waiters | `assertFailsWith` sur les 2 awaits ; loader appelé 1 fois |
| 9 | Loader qui jette → permet une nouvelle tentative | après échec, get(key) appelle loader à nouveau (pas de poisoning) |
| 10 | `clear()` vide le cache et appelle `onEvict` | size=0, `onEvict` reçu N fois |
| 11 | `get` après `clear()` recharge | loader rappelé |
| 12 | Évictions multiples préservent l'ordre LRU correct | scénario complexe avec 5 inserts dans cache size=3 |

Pattern pour le test de déduplication :

```kotlin
@Test fun `deduplicates concurrent get on same key`() = runTest {
    val gate = CompletableDeferred<Unit>()
    var loaderCalls = 0
    val cache = LruCache<String, String>(maxSize = 4, loader = { k ->
        loaderCalls++
        gate.await()
        "value-of-$k"
    })

    val a = async { cache.get("foo") }
    val b = async { cache.get("foo") }
    runCurrent()  // let both coroutines reach inFlight registration
    assertEquals(1, loaderCalls)
    gate.complete(Unit)
    assertEquals("value-of-foo", a.await())
    assertEquals("value-of-foo", b.await())
    assertEquals(1, loaderCalls)
}
```

Pour le test #12 (LRU ordering), construire :
```
cache.size = 3
get(A) get(B) get(C) → keys = [A, B, C]
get(A)                → keys = [B, C, A]
get(D)                → evict B → keys = [C, A, D]
get(C)                → keys = [A, D, C]
get(E)                → evict A → keys = [D, C, E]
```
Vérifier `cache.snapshotKeys()` à chaque étape.

### 4. Tests `TileManagerTest.kt`

`elevation/src/commonTest/kotlin/io/github/glandais/elevation/TileManagerTest.kt`.

Cas à couvrir :

| # | Cas | Attendu |
|---|---|---|
| 1 | `urlTemplate` substitution `{z}/{x}/{y}` | fetcher reçoit `"https://host/12/100/200.webp"` |
| 2 | Cache hit ne re-fetch pas | fetcher counter == 1 après 2 `getTile(tc)` |
| 3 | Tiles distinctes → fetches distincts | `(z=12,x=1,y=1)` et `(z=12,x=2,y=1)` → 2 calls |
| 4 | Eviction à `cacheSize=2` après 3 tuiles distinctes | 1ère tuile évincée (`cachedKeys().size == 2`) |
| 5 | Fetcher exception propagée | `assertFailsWith` |
| 6 | `clear()` vide le manager | `cachedKeys().isEmpty()` |
| 7 | URL n'a pas de `{z}/{x}/{y}` (URL fixe) → toujours appelée telle quelle | fetcher reçoit `urlTemplate` brut |

Helper test :

```kotlin
private fun makeRawTile(width: Int = 2, height: Int = 2): RawTile =
    RawTile(width, height, ByteArray(width * height * 4) { i -> 
        if (i % 4 == 0) 128.toByte()       // R = 128 (sea level)
        else if (i % 4 == 3) 255.toByte()  // A = 255
        else 0
    })
```

Et un mock fetcher :

```kotlin
class RecordingFetcher(private val responder: suspend (String) -> RawTile) {
    val calls = mutableListOf<String>()
    suspend fun fetch(url: String): RawTile {
        calls += url
        return responder(url)
    }
}
```

### 5. Vérification ktlint

Le pattern `mutex.withLock { ... }` avec `return@withLock /* fall through */` est non-idiomatique mais explicite. Si ktlint râle, remplacer par une variable booléenne intermédiaire.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/LruCache.kt`
- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/TileManager.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/LruCacheTest.kt`
- `vcyclist/elevation/src/commonTest/kotlin/io/github/glandais/elevation/TileManagerTest.kt`

Aucune modification de build (`kotlinx-coroutines-test` déjà présent depuis tâche 06).

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :elevation:build
./gradlew :engine:allTests       # non-régression
```

Critères :

- **`LruCacheTest`** : ≥ 12 tests verts par target.
- **`TileManagerTest`** : ≥ 7 tests verts par target.
- Cumul `:elevation` (avant tâche 08) : ≥ 15 classes de test, ≥ 153 tests par target.
- `ktlintCheck` vert.
- `:engine:allTests` toujours vert.
- Test #7 (déduplication concurrente) prouve qu'un seul appel `loader` est fait pour 2 `get` parallèles — vérifie le `inFlight` map.

## Done when

- [x] `LruCache.kt` et `TileManager.kt` créés et compilent sur les 3 targets
- [x] `LruCacheTest.kt` (≥ 12 tests) et `TileManagerTest.kt` (≥ 7 tests) créés
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Test de déduplication concurrente passe sur les 3 targets (JVM thread, JS event-loop, Wasm event-loop ont des comportements différents — la sémantique `runTest` est identique)
- [x] Test d'éviction prouve l'ordre LRU correct (scénario complexe en 5 étapes)
- [x] Toutes les checkboxes cochées

## Notes

- **Pas de port littéral de `ReentrantLock`** : la version TS combine déduplication par clé + semaphore "max concurrent slots". On scinde ces deux responsabilités : `LruCache` fait la dédup, `Reactive` (tâche 08) fait la limitation de concurrence. C'est plus modulaire et chacun est testable indépendamment.
- **`runCurrent()` dans le test #7** : `runTest` utilise un `StandardTestDispatcher` virtuel ; il faut explicitement faire avancer le planificateur pour que les `async { cache.get(key) }` atteignent leur `inFlight[key] = ours`. Sans `runCurrent()`, les deux `loader` n'ont rien d'exécuté.
- **Pourquoi `K : Any, V : Any`** : exclut `null` comme valeur cachée ; simplifie la sémantique (`entries[k] == null` ⇔ absence). Si un cas requiert `null`, on encapsulera en `Optional<V>` ou un wrapper.
- **Allocation `CompletableDeferred` en cache hit** : non — on alloue `ours` seulement après le fast-path. Mesurer en Phase 8 si profiling montre un coût.
- **`onEvict` sur `clear()`** : on appelle pour symétrie avec l'éviction. Si plus tard une variante "clear sans notification" est requise, on ajoutera un paramètre.
- **Concurrence JVM hors coroutines** : un appel `cache.get` depuis un thread Java non-coroutine est interdit (Mutex coroutine n'est pas atteignable). Si besoin, fournir un wrapper `runBlocking` côté `jvmMain` plus tard.
- **Implémentation `LinkedHashMap` KMP** : disponible nativement (`kotlin.collections.LinkedHashMap`). Sur JS et Wasm, la stdlib Kotlin réimplémente l'ordre d'insertion en interne. Garanti par contrat.
- **Pas de "max concurrent slots" intégré** : volontaire. La granularité est `Reactive` (tâche 08), qui peut être plug-and-play en wrappant le `loader` :
  ```kotlin
  val rateLimited: suspend (K) -> V = { reactive.acquire { realLoader(it) } }
  LruCache(maxSize, rateLimited, onEvict)
  ```
- **TileManager.fetcher injectable** : permet l'écriture de tests sans HTTP. Pour le wiring de production, le défaut `::fetchAndDecodeTile` cible directement la fonction `expect`.
- **Pas de `Tile.close()` à propager via `onEvict`** : actuellement Kotlin `Tile` n'a pas de ressource à libérer. Si plus tard on garde un `ImageBitmap` côté Wasm dans `Tile` plutôt qu'un `ByteArray` immutable, on activera `onEvict = { it.close() }`.
- **Tests #7 et #8 (déduplication)** : nécessitent un `CompletableDeferred` "gate" pour contrôler le timing. Documenter le pattern dans le commentaire en haut du fichier de test.
