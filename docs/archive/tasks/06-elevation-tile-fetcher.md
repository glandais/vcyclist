# 06 — Elevation : tile fetcher (HTTP + décodage image multi-target)

## Goal

Introduire la fonction `expect suspend fun fetchAndDecodeTile(url: String): RawTile` qui télécharge une tuile (WebP ou PNG) depuis une URL et la décode en `ByteArray` RGBA. Trois implémentations `actual` :

- **JVM** : `java.net.http.HttpClient` + `ImageIO.read` via TwelveMonkeys (WebP + PNG natifs).
- **Wasm/browser** : `window.fetch` + `createImageBitmap` + canvas 2D + `getImageData`.
- **JS (Node)** — *optionnelle* : `fetch` global (Node 18+) + `sharp` pour décoder. Stub `TODO` documenté si `sharp` indisponible.

Référence d'implémentation : `vcyclist/docs/kotlin-wasm-jvm-webp.md` §5 (fetch WebP browser) et §6 (pattern KMP complet jvmMain + wasmJsMain).

## Depends on

- `05-elevation-tile-types-decoding` (`RawTile` data class avec `equals/hashCode` corrects pour `ByteArray`)
- Dépendances bootstrap : `kotlinx-coroutines-core` (commonMain), `kotlinx-browser` (wasmJsMain), TwelveMonkeys `imageio-webp` (jvmMain).

## Inputs

- `vcyclist/docs/kotlin-wasm-jvm-webp.md` — guide complet, patterns prêts à coller (§5 cas 2 et §6).
- `/home/glandais/code/perso/vcyclist-all/elevation/src/tile/fetcher/` (TS, référence non-portée littéralement) — pour comprendre l'attente du contrat (`fetch`, decode, error handling).
- `/home/glandais/code/perso/vcyclist-all/elevation/src/tile/fetcher/browser/BrowserTileFetcher.ts` — pattern fetch + ImageBitmap côté browser.

## Steps

### 1. `commonMain` : déclaration `expect`

`elevation/src/commonMain/kotlin/io/github/glandais/elevation/TileFetcher.kt` :

```kotlin
package io.github.glandais.elevation

/**
 * Download the tile at [url] and decode it into a [RawTile] (RGBA bytes).
 *
 * Supports any image format that the target's image decoder supports — typically WebP and PNG
 * for Terrarium tiles. Throws if the URL cannot be reached or the response cannot be decoded.
 *
 * Each target provides its own implementation:
 * - **JVM** : `java.net.http.HttpClient` + `ImageIO` (TwelveMonkeys WebP).
 * - **Wasm/browser** : `fetch` + `createImageBitmap` + canvas.
 * - **JS Node** : `fetch` (Node 18+) + `sharp` (optional).
 *
 * @param url absolute URL of the tile (`https://...` typically)
 * @return decoded raw tile
 * @throws IllegalStateException if HTTP status is not 2xx or decoding fails
 */
expect suspend fun fetchAndDecodeTile(url: String): RawTile
```

### 2. `jvmMain` : implémentation Java HTTP + ImageIO

`elevation/src/jvmMain/kotlin/io/github/glandais/elevation/TileFetcher.jvm.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO

private val httpClient: HttpClient by lazy {
    HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
}

actual suspend fun fetchAndDecodeTile(url: String): RawTile = withContext(Dispatchers.IO) {
    val response = httpClient.send(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )
    check(response.statusCode() in 200..299) {
        "Tile fetch failed for $url: HTTP ${response.statusCode()}"
    }
    decodeBytes(response.body(), url)
}

private fun decodeBytes(bytes: ByteArray, sourceUrl: String): RawTile {
    val img = ImageIO.read(ByteArrayInputStream(bytes))
        ?: error("No ImageIO decoder for tile at $sourceUrl")
    val w = img.width
    val h = img.height
    val argb = IntArray(w * h).also { img.getRGB(0, 0, w, h, it, 0, w) }
    val rgba = ByteArray(w * h * 4)
    for (i in argb.indices) {
        val p = argb[i]
        rgba[i * 4] = (p shr 16).toByte()     // R
        rgba[i * 4 + 1] = (p shr 8).toByte()  // G
        rgba[i * 4 + 2] = p.toByte()          // B
        rgba[i * 4 + 3] = (p shr 24).toByte() // A
    }
    return RawTile(w, h, rgba)
}
```

### 3. `wasmJsMain` : implémentation browser fetch + canvas

`elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/TileFetcher.wasmJs.kt` :

```kotlin
package io.github.glandais.elevation

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.ImageBitmap
import org.w3c.fetch.Response
import org.w3c.files.Blob

actual suspend fun fetchAndDecodeTile(url: String): RawTile {
    val res: Response = window.fetch(url).await()
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
        val rgba = data.data.toByteArray()
        return RawTile(bitmap.width, bitmap.height, rgba)
    } finally {
        bitmap.close()
    }
}
```

**Notes** :
- `Uint8ClampedArray.toByteArray()` est fourni par `kotlinx-browser` (vérifier avec la version 0.3 ; si l'extension n'existe pas, écrire une mini-fonction qui itère `data.data.length` et appelle `data.data.get(i).toByte()`).
- `ImageBitmap.close()` libère les ressources GPU/canvas (important pour Safari).
- `CORS` : si la tuile n'a pas `Access-Control-Allow-Origin: *`, `getImageData` jette `SecurityError`. Pour mapterhorn.com, vérifier l'en-tête en début de Phase 1 si tests d'intégration échouent.

### 4. `jsMain` (Node) : implémentation optionnelle

`elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt` :

```kotlin
package io.github.glandais.elevation

// NOTE: jsMain (Node) target requires `sharp` for WebP/PNG decoding.
// If the project is built without sharp (no npm dependency added), this stub throws.
// Real implementation pattern (à activer une fois sharp ajouté) :
//
//   actual suspend fun fetchAndDecodeTile(url: String): RawTile =
//       suspendCancellableCoroutine { cont ->
//           js(\"""
//               (async () => {
//                   const res = await fetch(url);
//                   if (!res.ok) throw new Error('HTTP ' + res.status);
//                   const buf = Buffer.from(await res.arrayBuffer());
//                   const sharp = require('sharp');
//                   const { data, info } = await sharp(buf).raw().ensureAlpha().toBuffer({ resolveWithObject: true });
//                   return { width: info.width, height: info.height, rgba: data };
//               })().then(resolve, reject);
//           \"""
//           )
//       }
//
actual suspend fun fetchAndDecodeTile(url: String): RawTile {
    throw NotImplementedError(
        "fetchAndDecodeTile is not implemented for the JS (Node) target. " +
            "Add `sharp` as an npm dependency and uncomment the implementation in TileFetcher.js.kt.",
    )
}
```

**Décision** : on garde la target Node configurée (smoke test du bootstrap continue de passer) mais on stub la fonction. L'activation réelle se fera dans une tâche dédiée si besoin (consommateur Node identifié).

### 5. Mise à jour `build.gradle.kts` (module `:elevation`)

Pas de nouvelle dépendance — `imageio-webp` (jvmMain), `kotlinx-browser` (wasmJsMain) et `kotlinx-coroutines-core` (commonMain) sont déjà présentes depuis le bootstrap. Ajouter seulement :

```kotlin
sourceSets {
    // ...
    jvmTest.dependencies {
        // serveur HTTP de test : aucune dep externe, on utilise com.sun.net.httpserver
        // (inclus dans le JDK) — pas besoin d'ajouter Ktor
    }
    wasmJsTest.dependencies {
        // ...
    }
}
```

Aucune modification requise.

### 6. Tests JVM : serveur HTTP embarqué

`elevation/src/jvmTest/kotlin/io/github/glandais/elevation/TileFetcherJvmTest.kt` :

Stratégie :
1. Générer une PNG `2×2` en mémoire avec des couleurs RGB précises (encode 4 altitudes Terrarium distinctes).
2. Démarrer un `com.sun.net.httpserver.HttpServer` sur un port aléatoire (port 0).
3. Servir les bytes à l'URL `/tile.png`.
4. Appeler `fetchAndDecodeTile("http://localhost:$port/tile.png")`.
5. Vérifier `RawTile(2, 2, ...)` avec bytes RGBA attendus.

Cas à couvrir :

| Cas | Attendu |
|---|---|
| Fetch PNG 2×2 → RawTile correct | width=2, height=2, rgba.size=16, pixels décodés conformes |
| Fetch WebP 2×2 → RawTile correct | idem (test que TwelveMonkeys est bien chargé via SPI) |
| HTTP 404 | `IllegalStateException` avec message `"Tile fetch failed for ...: HTTP 404"` |
| HTTP 500 | `IllegalStateException` |
| Body vide / corrompu | `IllegalStateException` avec message `"No ImageIO decoder for tile at ..."` |
| Connexion refusée (port fermé) | exception propagée (`ConnectException`) |

Squelette :

```kotlin
package io.github.glandais.elevation

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TileFetcherJvmTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeTest fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also {
            port = it.address.port
            it.createContext("/tile.png") { ex -> sendImage(ex, format = "png") }
            it.createContext("/tile.webp") { ex -> sendImage(ex, format = "webp") }
            it.createContext("/404") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
            it.createContext("/corrupt") { ex ->
                val bogus = "not an image".toByteArray()
                ex.sendResponseHeaders(200, bogus.size.toLong())
                ex.responseBody.use { it.write(bogus) }
            }
            it.start()
        }
    }

    @AfterTest fun stopServer() { server.stop(0) }

    private fun sendImage(ex: HttpExchange, format: String) {
        val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB).apply {
            // Encode 4 distinct elevations via Terrarium (pixel 0,0 = sea level)
            setRGB(0, 0, 0xFF800000.toInt()) // R=128 G=0 B=0 → 0 m
            setRGB(1, 0, 0xFF83E800.toInt()) // ≈ 1000 m
            setRGB(0, 1, 0xFFFFFFFF.toInt())
            setRGB(1, 1, 0xFF000000.toInt())
        }
        val baos = ByteArrayOutputStream().apply { ImageIO.write(img, format, this) }
        ex.responseHeaders.add("Content-Type", "image/$format")
        ex.sendResponseHeaders(200, baos.size().toLong())
        ex.responseBody.use { it.write(baos.toByteArray()) }
    }

    @Test fun `decodes PNG tile`() = runTest {
        val tile = fetchAndDecodeTile("http://127.0.0.1:$port/tile.png")
        assertEquals(2, tile.width)
        assertEquals(2, tile.height)
        assertEquals(16, tile.rgba.size)
        // pixel (0,0) = (128, 0, 0, 255) → sea level via decodeTerrariumElevation
        assertEquals(0.0, Tile.decodeTerrariumElevation(
            tile.rgba[0].toInt() and 0xFF,
            tile.rgba[1].toInt() and 0xFF,
            tile.rgba[2].toInt() and 0xFF,
        ))
    }

    @Test fun `decodes WebP tile via TwelveMonkeys SPI`() = runTest {
        val tile = fetchAndDecodeTile("http://127.0.0.1:$port/tile.webp")
        assertEquals(2, tile.width)
        assertEquals(2, tile.height)
    }

    @Test fun `throws on 404`() = runTest {
        val ex = assertFailsWith<IllegalStateException> {
            fetchAndDecodeTile("http://127.0.0.1:$port/404")
        }
        assertEquals("Tile fetch failed for http://127.0.0.1:$port/404: HTTP 404", ex.message)
    }

    @Test fun `throws on corrupt body`() = runTest {
        assertFailsWith<IllegalStateException> {
            fetchAndDecodeTile("http://127.0.0.1:$port/corrupt")
        }
    }
}
```

Dépendance test JVM : `kotlinx-coroutines-test` (pour `runTest`). À ajouter :

```kotlin
// gradle/libs.versions.toml — ajout
[libraries]
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }

// elevation/build.gradle.kts
commonTest.dependencies {
    implementation(kotlin("test"))
    implementation(libs.kotlinx.coroutines.test)
}
```

### 7. Tests Wasm — deferred

Le test browser exige un serveur de fixtures (karma config ou test-task customizé). On **diffère** ces tests à la **tâche 09** (intégration HTTP réelle multi-target) plutôt que de bricoler ici.

Pour la tâche 06 : la target `wasmJsMain` doit **compiler** sans erreur (le squelette `actual` est valide) ; le smoke test du bootstrap reste vert.

### 8. Tests Node (jsTest) — skip

Vu que l'`actual` Node lève `NotImplementedError`, on n'écrit pas de test ici. Si plus tard sharp est ajouté, un test similaire au JVM (mais avec un serveur HTTP Node temporaire ou directement file://) sera ajouté.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/elevation/src/commonMain/kotlin/io/github/glandais/elevation/TileFetcher.kt` (déclaration `expect`)
- `vcyclist/elevation/src/jvmMain/kotlin/io/github/glandais/elevation/TileFetcher.jvm.kt`
- `vcyclist/elevation/src/wasmJsMain/kotlin/io/github/glandais/elevation/TileFetcher.wasmJs.kt`
- `vcyclist/elevation/src/jsMain/kotlin/io/github/glandais/elevation/TileFetcher.js.kt`
- `vcyclist/elevation/src/jvmTest/kotlin/io/github/glandais/elevation/TileFetcherJvmTest.kt`

Supprimés (au passage) :

- `vcyclist/elevation/src/jvmMain/kotlin/.gitkeep`
- `vcyclist/elevation/src/jsMain/kotlin/.gitkeep`
- `vcyclist/elevation/src/wasmJsMain/kotlin/.gitkeep`

Modifiés :

- `vcyclist/gradle/libs.versions.toml` — ajout `kotlinx-coroutines-test`
- `vcyclist/elevation/build.gradle.kts` — ajout `kotlinx-coroutines-test` en `commonTest.dependencies`

## Validation

Depuis `vcyclist/` :

```bash
./gradlew :elevation:compileKotlinJvm
./gradlew :elevation:compileKotlinWasmJs
./gradlew :elevation:compileKotlinJs        # vérifie que le stub Node compile
./gradlew :elevation:jvmTest                # tests serveur HTTP embarqué
./gradlew :elevation:jsNodeTest             # commonTests, smoke (le stub n'est pas appelé)
./gradlew :elevation:wasmJsBrowserTest      # commonTests, smoke (l'actual n'est pas appelé)
./gradlew :elevation:allTests
./gradlew ktlintCheck
./gradlew :engine:allTests                  # non-régression
```

Critères :

- Les 3 targets compilent sans erreur.
- `:elevation:jvmTest` ajoute ≥ 4 tests verts dans `TileFetcherJvmTest`.
- `:elevation:jsNodeTest` et `:elevation:wasmJsBrowserTest` continuent de passer (les commonTests existants ne changent pas).
- `ktlintCheck` vert (attention aux imports `java.*` JVM-only — bien dans `jvmMain/`, ktlint global passera car le dossier `jvmMain/` est compilé seulement par la target JVM).
- `:engine:allTests` toujours vert.

## Done when

- [x] `expect fun fetchAndDecodeTile` déclaré dans `commonMain`
- [x] 3 `actual` impl créés (JVM, Wasm, JS Node-stub)
- [x] `TileFetcherJvmTest` créé (≥ 4 tests)
- [x] `kotlinx-coroutines-test` ajouté dans le catalogue + build.gradle
- [x] `./gradlew :elevation:allTests` vert (3 targets)
- [x] `./gradlew :engine:allTests` toujours vert
- [x] `./gradlew ktlintCheck` sans violation
- [x] Test "404 HTTP" vérifie le message d'exception exact
- [x] Test "corrupt body" passe (le code `error()` est bien atteint)
- [x] Toutes les checkboxes cochées dans le fichier

## Notes

- **Pourquoi pas Ktor client** : `java.net.http.HttpClient` est inclus dans le JDK 21 (toolchain bootstrap), pas de dépendance externe. Plus tard, si l'API publique évolue vers une cible iOS/macOS native, un wrapper Ktor pourra être introduit dans une tâche dédiée.
- **TwelveMonkeys SPI** : `imageio-webp` s'enregistre auto via `META-INF/services/javax.imageio.spi.ImageReaderSpi`. Aucune init manuelle. Vérifier au premier lancement de tests qu'un `BufferedImage` est bien retourné pour un fichier `.webp` (test #2).
- **Test « WebP via TwelveMonkeys »** : `ImageIO.write(img, "webp", out)` exige aussi un writer WebP. TwelveMonkeys 3.12 fournit lecteur ET writer. Si l'écriture échoue (writer absent), on peut basculer ce test sur une **fixture binaire** pré-générée et stockée dans `commonTest/resources` (ou `jvmTest/resources`).
- **CORS browser** : non-bloquant pour `localhost`, mais pour les tuiles `mapterhorn.com` en prod, l'en-tête doit autoriser le domaine (à vérifier ; la tâche 09 d'intégration réelle le verrouille).
- **Pourquoi pas d'`expect class TileFetcher`** : la fonction top-level `expect/actual` est plus simple à tester (pas d'instanciation) et suffit. On introduira un `fun interface TileFetcher` dans la tâche 07/08 pour DI (mock dans `BatchCalculator` et `ElevationProvider`).
- **`runTest` (kotlinx-coroutines-test 1.10.2)** : remplace `runBlocking` dans les tests, contrôle la coroutine context, intercepte les exceptions proprement. Le serveur HTTP tourne dans un autre thread (JVM), donc `runTest` ne pose pas de problème de virtual time.
- **Allocation `IntArray + ByteArray`** pour la conversion ARGB → RGBA : pour une tuile 512×512, ~1 Mo alloué temporairement. Acceptable. Si profilage en Phase 1 montre que c'est un hotspot, on pourra écrire directement dans le `ByteArray` final via `Raster.getDataBuffer()` (mais le format `DataBuffer` varie selon `BufferedImage.TYPE_*`, donc plus de code conditionnel).
- **Sentinel HTTP timeout** : 30 s par tile par défaut. Pour CI avec réseau lent, configurable plus tard via `ElevationProviderConfig`.
- **Pas de retry automatique** : volontaire. Une politique de retry sera traitée plus tard dans `TileManager` (tâche 07) ou en wrapper externe.
- **`bitmap.close()`** dans wasmJsMain : `try/finally` strict. Si `drawImage` ou `getImageData` lèvent (CORS taint), on libère quand même.
- **Stub Node `NotImplementedError`** : décision pragmatique. Activer la vraie impl quand un consommateur Node existe. Documenté en commentaire pour rendre l'activation triviale.
