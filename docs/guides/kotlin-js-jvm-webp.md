# Kotlin/JS ↔ JavaScript : interop, types et fetch/decode WebP

Synthèse des points techniques sur l'interop Kotlin/JS, la génération de `.d.ts`, et le partage de code avec une cible JVM.

---

## 1. Exposer du Kotlin vers JavaScript

Annotation `@JsExport` sur fonctions top-level ou classes :

```kotlin
@JsExport
fun greet(name: String): String = "Hello, $name"

@JsExport
class Counter(private var value: Int = 0) {
    fun increment() { value++ }
    fun get(): Int = value
}
```

Contrairement à Kotlin/Wasm, Kotlin/JS n'impose pas de rester au niveau top-level : une classe
`@JsExport` reste un vrai objet JavaScript, utilisable en `new Counter()` côté JS et renvoyable
telle quelle depuis une fonction exportée (voir `newElevationProvider` dans
`elevation/src/jsMain/.../ElevationJsApi.kt`, qui retourne directement l'instance
`ElevationProvider` — pas de handle opaque nécessaire).

### Types autorisés dans la signature d'un `@JsExport`

- Primitifs : `Int`, `Double`, `Float`, `Boolean`, `String`, `Char` (et nullable)
- `Long` → mappé sur `BigInt` côté JS
- `Unit` en retour
- Tableaux : `Array<T>`
- Classes elles-mêmes `@JsExport`, et `external interface` (voir §4)
- Types fonction (callbacks)

### Restrictions classiques

- Pas de `List`/`Map` Kotlin directement (utiliser `Array`)
- Pas de `data class` arbitraire non-`@JsExport`
- Pas de génériques côté API exposée
- Pas de `suspend` (il faut renvoyer une `Promise<T>`)

---

## 2. Callbacks et accès à des API JS arbitraires

### Kotlin → JS (exposer une fonction qui prend un callback)

```kotlin
@JsExport
fun onTick(cb: (Int) -> Unit) {
    cb(42)
}
```

Côté JS :

```js
import { onTick } from './app.mjs';
onTick((n) => console.log("tick", n));
```

Le runtime emballe automatiquement la fonction JS en lambda Kotlin appelable.

### JS → Kotlin (appeler du JS depuis Kotlin)

Kotlin/JS accepte le mot-clé `js(...)` pour embarquer un snippet JavaScript arbitraire, typé
`dynamic` sauf `unsafeCast` explicite :

```kotlin
external fun setTimeout(handler: () -> Unit, ms: Int): Int

fun add(x: Int, y: Int): Int = js("x + y") as Int

external val window: dynamic

// Snippet inline, y compris multi-lignes
private fun fetchUrlBrowser(url: String): Promise<Response> =
    js("fetch(url)").unsafeCast<Promise<Response>>()
```

`js("...")` accepte du texte arbitraire (y compris des IIFE multi-lignes), ce qui permet aussi
de cacher un `require()` à la résolution statique de webpack (voir §5, décodage Node) — utile
pour garder le bundle navigateur exempt d'une dépendance qui ne doit charger que côté Node.

---

## 3. Async / Promise

Pour exposer du `suspend`, on wrap en `Promise` via `GlobalScope.promise` :

```kotlin
@JsExport
fun fetchData(): Promise<String> = GlobalScope.promise {
    delay(1000)
    "done"
}
```

Inversement côté Kotlin, on `.await()` une `Promise` JS (`kotlinx.coroutines.await`). C'est le
patron utilisé partout dans les façades `elevation/src/jsMain/.../ElevationJsApi.kt` et
`engine/src/jsMain/.../EngineJsApi.kt` : chaque fonction `suspend` interne est exposée en
`Promise<T>` via `GlobalScope.promise { ... }` (`@OptIn(DelicateCoroutinesApi::class)`).

---

## 4. DTOs typés avec `external interface`

Le meilleur moyen de partager une forme d'objet "JSON-like" typée entre Kotlin et JS/TS est
l'`external interface` :

```kotlin
/** JS-side output shape `{ latitude, longitude, elevation }`. */
external interface CoordinatesElevationDto {
    val latitude: Double
    val longitude: Double
    val elevation: Double
}
```

Côté JS, on passe un objet littéral qui respecte cette forme — pas besoin de constructeur ni
de classe Kotlin instanciée.

Pour construire un tel DTO côté Kotlin (retour de fonction), le patron est `js("({})")` suivi
d'affectations de champs puis d'un `unsafeCast` :

```kotlin
private fun coordsEle(
    latitude: Double,
    longitude: Double,
    elevation: Double,
): CoordinatesElevationDto {
    val o = js("({})")
    o.latitude = latitude
    o.longitude = longitude
    o.elevation = elevation
    return o.unsafeCast<CoordinatesElevationDto>()
}
```

C'est la même technique utilisée pour construire les résultats des façades `ElevationJsApi.kt`
et `EngineJsApi.kt` — voir ces deux fichiers pour les DTOs complets (`CoordinatesDto`,
`SmoothingOptionsDto`, `FilterOptionsDto`, `GetElevationsAlongOptionsDto`,
`ElevationProviderConfigDto`, etc.).

### Génération de `.d.ts`

Dans `build.gradle.kts`, bloc `js {}` :

```kotlin
kotlin {
    js(IR) {
        binaries.executable()
        browser { }
        generateTypeScriptDefinitions()
    }
}
```

Le compilateur scanne les `@JsExport` top-level et produit un `.d.ts` à côté du `.mjs`.

**Il n'émet aucun corps pour une `external interface`.** C'est la conséquence directe du mot-clé :
`external` déclare une forme qui *existe déjà* en JavaScript, donc le compilateur n'a rien à
engendrer pour elle. Le `.d.ts` la **référence** sans jamais la déclarer, et `tsc` le rejette hors
`skipLibCheck` — dix-huit `TS2304` sur `vcyclist-engine.d.ts` avant que ce soit corrigé. Les
interfaces qui *ont* un corps dans le `.d.ts` sont les interfaces Kotlin ordinaires annotées
`@JsExport`, et elles portent une marque `__doNotUseOrImplementIt` qu'aucun littéral d'objet ne
satisfait : elles sont donc inutilisables pour un DTO d'**entrée**.

Les deux contraintes ensemble n'ont qu'une issue : garder l'`external interface` côté Kotlin, et
**engendrer** le `.d.ts` depuis elle. C'est ce que fait `./gradlew :codegen:generateTsFacade` pour
les deux paquets npm ; voir [`using-from-javascript.md`](using-from-javascript.md).

Le compilateur écrit par ailleurs `any` pour tout type non exporté qui traverse la signature —
`Path`, `ElevationProvider` — ce que le fichier engendré remplace par un type opaque marqué.

### Le piège du JSON sérialisé

```kotlin
@JsExport
fun getUser(): String = Json.encodeToString(user)
```

→ `.d.ts` produit : `getUser(): string`. Aucune info sur la forme.

### Pratique recommandée pour partager un "JSON" typé

1. Définir la forme avec `external interface` (typage `.d.ts` propre).
2. Définir une `@Serializable data class` Kotlin parallèle pour la logique interne.
3. Un mapper Kotlin entre les deux (`toKotlin()` / le constructeur `js("({})")` ci-dessus).

Verbeux, mais c'est la seule façon d'avoir à la fois `kotlinx.serialization` propre côté Kotlin
et un `.d.ts` qui décrit vraiment la forme côté TS.

### Cas JSON brut

Taper en `string` est honnête, et on génère les types TS de son côté (Zod,
json-schema-to-typescript) à partir de la source de vérité (schéma JSON, OpenAPI).

---

## 5. Fetch + décodage WebP depuis Kotlin/JS

Deux runtimes cibles pour le même `expect fun fetchAndDecodeTile(url: String): RawTile` :
navigateur (DOM natif) et Node.js/Bun (pas de DOM). Le fichier `TileFetcher.js.kt` de
`:elevation` détecte le runtime au démarrage et bascule :

```kotlin
private val isNode: Boolean =
    js(
        "typeof window === 'undefined' && typeof process !== 'undefined' " +
            "&& process.versions != null && process.versions.node != null",
    ) as Boolean

actual suspend fun fetchAndDecodeTile(url: String): RawTile = if (isNode) decodeNode(url) else decodeBrowser(url)
```

### Cas 1 : décodage navigateur (Canvas)

Le navigateur sait décoder le WebP nativement (Safari 14+, Chrome, Firefox, Edge). On
déclenche ce décodeur via les API DOM : `fetch` → `Blob` → `createImageBitmap` → `Canvas` →
`getImageData`.

```kotlin
private suspend fun decodeBrowser(url: String): RawTile {
    val res: Response = fetchUrlBrowser(url).await()
    check(res.ok) { "Tile fetch failed for $url: HTTP ${res.status}" }
    val blob: Blob = res.blob().await()

    val bitmap: ImageBitmap = window.createImageBitmap(blob).await()
    try {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = bitmap.width
        canvas.height = bitmap.height
        val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
        ctx.drawImage(bitmap, 0.0, 0.0)
        val data = ctx.getImageData(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
        val src = data.data
        // Reinterpret Uint8ClampedArray as Int8Array — ByteArray at Kotlin/JS runtime IS
        // Int8Array, so unsafeCast is zero-copy.
        val int8 = Int8Array(src.buffer, src.byteOffset, src.byteLength)
        val rgba: ByteArray = int8.unsafeCast<ByteArray>()
        return RawTile(bitmap.width, bitmap.height, rgba)
    } finally {
        bitmap.close()
    }
}
```

`ImageData.data` (`Uint8ClampedArray`) se réinterprète directement en `ByteArray` via
`Int8Array(...).unsafeCast<ByteArray>()` — pas de copie : un `ByteArray` Kotlin/JS **est** un
`Int8Array` à l'exécution.

### Cas 2 : décodage Node/Bun (`@jsquash/webp`)

Sans DOM, pas de `createImageBitmap` ni de `Canvas`. `:elevation` charge le décodeur WASM
tiers **`@jsquash/webp`** (un codec WebP tiers, distinct de la cible de compilation
Kotlin/Wasm — voir la note ci-dessous) via `require()`, en le cachant derrière `eval()` pour
que webpack ne le résolve pas statiquement lors du bundling navigateur :

```kotlin
// Load @jsquash/webp lazily so webpack does NOT resolve it at bundle time for the browser
// target. The `require()` is hidden behind `eval()` to defeat webpack's static resolver —
// combined with `webpack.config.d/externals.js`, the browser bundle stays jsquash-free.
//
// `@jsquash/webp` is an Emscripten module whose auto-init fetches its own `.wasm` from a
// `file://` URL, which Node's `fetch` does not support ("not implemented... yet..."). We must
// load and compile the WASM ourselves, then call `init(module)` before the first `decode(buf)`.
private val nodeWebpDecoderPromise: Promise<dynamic> by lazy {
    js(
        """
        (function () {
            var path = eval('require')('path');
            var fs = eval('require')('fs');
            var req = eval('require');
            var decodeMod = req('@jsquash/webp/decode.js');
            var wasmDir = path.dirname(req.resolve('@jsquash/webp/decode.js'));
            var wasmPath = path.join(wasmDir, 'codec/dec/webp_dec.wasm');
            var wasmBytes = fs.readFileSync(wasmPath);
            return WebAssembly.compile(wasmBytes).then(function (wasmModule) {
                return decodeMod.init(wasmModule).then(function () {
                    return decodeMod.default;
                });
            });
        })()
        """,
    ).unsafeCast<Promise<dynamic>>()
}
```

> **Note sur le WASM ici** : `@jsquash/webp` embarque son propre binaire `webp_dec.wasm`
> (`WebAssembly.compile`) — c'est un détail d'implémentation du décodeur JS tiers, complètement
> indépendant du choix de compiler *Kotlin* vers Wasm ou vers JS. vcyclist compile
> exclusivement vers Kotlin/JS ; ce module continue d'utiliser WebAssembly en interne, chargé
> et piloté depuis du code Kotlin/JS.

### Pièges classiques

- **CORS** : sans `Access-Control-Allow-Origin`, `createImageBitmap` réussit mais le canvas
  devient *tainted* et `getImageData` jette `SecurityError`.
- **Animations WebP** : `createImageBitmap` ne donne que la première frame. Pour les suivantes,
  il faudrait un décodeur bas niveau (`ImageDecoder` WebCodecs, ou `@jsquash/webp` côté Node).
- **`RequestInit` par défaut** : la déclaration `fetch(input, init)` de `kotlinx-browser-js`
  n'a pas de valeur par défaut pour `init`, et sérialiserait un `RequestInit()` vide avec des
  champs `null` (`cache`, `mode`, …) — que Chrome rejette sur des champs typés enum. On
  contourne en appelant `fetch(url)` via un snippet `js("fetch(url)")` à un seul argument
  plutôt que de laisser le binding Kotlin générer l'appel à deux arguments.
- **`fetch` sous Node 18+/Bun** : natif dans `globalThis`, retourne le même type `Response`
  Web standard qu'en navigateur — le code de parsing de la réponse (`res.ok`, `res.blob()` /
  `res.arrayBuffer()`) est donc partagé entre les deux branches.

---

## 6. Partager le code avec une cible JVM (Kotlin Multiplatform)

### Setup

```kotlin
// build.gradle.kts
kotlin {
    jvm()
    js(IR) { browser { }; nodejs {}; binaries.executable() }

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        jsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.5.0")
        }
        jvmMain.dependencies {
            implementation("com.twelvemonkeys.imageio:imageio-webp:3.13.1")
        }
    }
}
```

### commonMain — API et logique métier

```kotlin
data class RawImage(val width: Int, val height: Int, val rgba: ByteArray)

expect suspend fun fetchAndDecodeWebp(url: String): RawImage

// 100% partagé : traitement de pixels
fun RawImage.toGrayscale(): RawImage { /* ... */ }
```

### jsMain — version navigateur/Node (voir §5 pour la branche complète)

```kotlin
actual suspend fun fetchAndDecodeWebp(url: String): RawImage = fetchAndDecodeTile(url).let {
    RawImage(it.width, it.height, it.rgba)
}
```

### jvmMain — version JVM

```kotlin
actual suspend fun fetchAndDecodeWebp(url: String): RawImage =
    withContext(Dispatchers.IO) {
        val bytes = HttpClient.newHttpClient()
            .send(HttpRequest.newBuilder(URI.create(url)).build(),
                  HttpResponse.BodyHandlers.ofByteArray())
            .body()

        // TwelveMonkeys s'enregistre via SPI, ImageIO.read le prend
        val img = ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("Aucun décodeur WebP enregistré")

        val w = img.width; val h = img.height
        val argb = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
        val rgba = ByteArray(w * h * 4)
        for (i in argb.indices) {
            val p = argb[i]
            rgba[i*4]   = (p shr 16).toByte()  // R
            rgba[i*4+1] = (p shr 8).toByte()   // G
            rgba[i*4+2] = p.toByte()           // B
            rgba[i*4+3] = (p shr 24).toByte()  // A
        }
        RawImage(w, h, rgba)
    }
```

### Choix de la lib WebP côté JVM

| Lib | Compromis |
|-----|-----------|
| **TwelveMonkeys ImageIO** (`imageio-webp`) | Pur Java, pas de natif, déploiement trivial (jar+SPI), WebP lossy/lossless. Suffisant dans la majorité des cas — c'est le choix retenu pour vcyclist. |
| **Scrimage** (`scrimage-webp`) | Wrappe libwebp natif, plus rapide, embarque des binaires par OS. Préférable pour du décodage massif côté serveur. |
| **JNI direct sur libwebp** | Seulement si on a déjà du natif dans le projet. Sinon overkill. |

### Trois scénarios pratiques

1. **Tester la logique de traitement** : pas besoin de KMP. Code la logique pure en Kotlin
   commun, main JVM qui charge un WebP local avec ImageIO + TwelveMonkeys. Test rapide, debug
   confort IDE, logique réutilisée ensuite en JS.

2. **Un binaire qui tourne des deux côtés** (moteur de rendu CLI JVM + navigateur/Node
   Kotlin/JS) : KMP avec `expect`/`actual` est exactement la bonne forme — c'est le patron
   utilisé par `:elevation` et `:engine` dans vcyclist.

3. **Partager du code entre un backend JVM et un front web appelant du Kotlin/JS** : DTOs et
   validation en commun dans un module KMP, deux artefacts publiés (jar pour le backend,
   `.mjs`+`.d.ts` pour le front). Élégant mais alourdit le build — à peser selon la taille du
   code partagé.

---

## Références

- Façades réelles du projet : `elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt`
  et `engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt`.
- Décodage WebP navigateur/Node : `elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt`.
