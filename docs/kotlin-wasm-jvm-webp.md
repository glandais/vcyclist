# Kotlin/Wasm ↔ JavaScript : interop, types et fetch/decode WebP

Synthèse des points techniques sur l'interop Kotlin/Wasm, la génération de `.d.ts`, et le partage de code avec une cible JVM.

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

### Types autorisés dans la signature d'un `@JsExport`

- Primitifs : `Int`, `Double`, `Float`, `Boolean`, `String`, `Char` (et nullable)
- `Long` → mappé sur `BigInt` côté JS
- `Unit` en retour
- Types `JsAny` et sous-types : `JsString`, `JsNumber`, `JsBoolean`, `JsArray<T>`, `JsReference<T>` (objet Kotlin opaque)
- Classes elles-mêmes `@JsExport`
- Types fonction (callbacks)

### Restrictions classiques

- Pas de `List`/`Map` Kotlin directement (utiliser `JsArray`)
- Pas de `data class` arbitraire non-`@JsExport`
- Pas de génériques côté API exposée
- Pas de `suspend` (il faut renvoyer une `Promise<JsAny?>`)

---

## 2. Callbacks

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

```kotlin
external fun setTimeout(handler: () -> Unit, ms: Int): Int

@JsFun("(x, y) => x + y")
external fun add(x: Int, y: Int): Int

external val window: JsAny

// Snippet inline
@JsFun("""
    (cb) => { 
        document.addEventListener('click', e => cb(e.clientX, e.clientY)); 
    }
""")
external fun onClick(cb: (Int, Int) -> Unit)
```

---

## 3. Async / Promise

Pour exposer du `suspend`, on wrap en `Promise` :

```kotlin
@JsExport
fun fetchData(): Promise<JsString> = GlobalScope.promise {
    delay(1000)
    "done".toJsString()
}
```

Inversement côté Kotlin, on peut `.await()` une `Promise` JS.

---

## 4. Génération de `.d.ts`

### Activation

Dans `build.gradle.kts`, bloc `wasmJs {}` :

```kotlin
kotlin {
    wasmJs {
        binaries.executable()
        browser { }
        generateTypeScriptDefinitions()
    }
}
```

> Statut **expérimental** côté JetBrains, susceptible de changer. Le compilateur scanne tous les `@JsExport` top-level et produit un `.d.ts` à côté du `.mjs`.

### Le piège du JSON sérialisé

```kotlin
@JsExport
fun getUser(): String = Json.encodeToString(user)
```

→ `.d.ts` produit : `getUser(): string`. Aucune info sur la forme.

### Pour typer la forme d'un objet : deux approches

#### Approche A : Classe `@JsExport`

```kotlin
@JsExport
class User(val id: Int, val name: String, val email: String?)

@JsExport
fun getUser(): User = User(1, "Gaby", null)
```

Produit en `.d.ts` :

```ts
export class User {
    constructor(id: number, name: string, email: Nullable<string>);
    readonly id: number;
    readonly name: string;
    readonly email: Nullable<string>;
}
export function getUser(): User;
```

**Limite** : c'est une classe, l'instance reste un objet Kotlin opaque côté JS. On ne peut pas la fabriquer en JS avec un littéral `{ id: 1, name: "..." }`.

#### Approche B : `external interface` (descripteur côté JS)

```kotlin
external interface UserDto : JsAny {
    val id: Int
    val name: String
    val email: String?
}

@JsExport
fun processUser(u: UserDto): UserDto = u
```

Génère une vraie `interface` TS, et côté JS on passe un objet littéral. Voie propre pour du JSON-like bidirectionnel typé.

### Pratique recommandée pour partager un "JSON" typé

1. Définir la forme avec `external interface` (typage `.d.ts`)
2. Définir une `@Serializable data class` Kotlin parallèle pour la logique interne
3. Un mapper Kotlin entre les deux

Verbeux mais seule façon d'avoir à la fois `kotlinx.serialization` propre côté Kotlin et un `.d.ts` qui décrit vraiment la forme côté TS.

### Cas JSON brut

Taper en `string` est honnête, et on génère les types TS de son côté (Zod, json-schema-to-typescript) à partir de la source de vérité (schéma JSON, OpenAPI).

---

## 5. Fetch + décodage WebP depuis Kotlin/Wasm

Le navigateur sait décoder le WebP nativement (Safari 14+, Chrome, Firefox, Edge). On déclenche ce décodeur via les API DOM.

### Dépendances

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
```

### Cas 1 : juste afficher l'image (blob URL)

```kotlin
suspend fun loadAsImageSrc(url: String): String {
    val res = window.fetch(url).await<Response>()
    val blob = res.blob().await<Blob>()
    return URL.createObjectURL(blob)
}
```

### Cas 2 : récupérer les pixels RGBA

```kotlin
suspend fun decodeWebp(url: String): ImageData {
    val res = window.fetch(url).await<Response>()
    val blob = res.blob().await<Blob>()
    
    val bitmap = window.createImageBitmap(blob).await<ImageBitmap>()
    
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = bitmap.width
    canvas.height = bitmap.height
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.drawImage(bitmap, 0.0, 0.0)
    
    return ctx.getImageData(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
}
```

`ImageData.data` est un `Uint8ClampedArray` (R,G,B,A,...) :

```kotlin
val pixels: Uint8ClampedArray = imageData.data
val byteArray: ByteArray = pixels.toByteArray()  // copie heap JS → mémoire linéaire Wasm
```

**Compromis copie vs accès direct** : `toByteArray()` copie tout (~64 Mo pour une 4K). Pour beaucoup de calcul : copier une fois. Pour peu d'accès : laisser côté JS et indexer (chaque accès traverse la frontière JS/Wasm).

### Cas 3 : décodage bas niveau via WebCodecs

```kotlin
external class ImageDecoder(init: JsAny) : JsAny {
    fun decode(): Promise<JsAny>
    val tracks: JsAny
}

val decoder = ImageDecoder(js("({ data: blob.stream(), type: 'image/webp' })"))
val result = decoder.decode().await<JsAny>()
// result.image est une VideoFrame, copyTo() donne accès aux bytes
```

Support : Chrome/Edge/Safari récent OK, Firefox historiquement derrière un flag.

### Pièges classiques

- **CORS** : sans `Access-Control-Allow-Origin`, `createImageBitmap` réussit mais le canvas devient *tainted* et `getImageData` jette `SecurityError`.
- **Animations WebP** : `createImageBitmap` ne donne que la première frame. Pour les suivantes : `ImageDecoder`.
- **Performance** : `OffscreenCanvas` dans un Web Worker pour ne pas bloquer le thread principal.

### Hors navigateur (wasmWasi)

Pas de DOM, pas de fetch, pas de Canvas. Il faut soit libwebp en Wasm via Component Model, soit porter un décodeur.

---

## 6. Partager le code avec une cible JVM (Kotlin Multiplatform)

### Setup

```kotlin
// build.gradle.kts
kotlin {
    jvm()
    wasmJs { browser { } ; binaries.executable() }
    
    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
        }
        jvmMain.dependencies {
            implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
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

### wasmJsMain — version navigateur

```kotlin
actual suspend fun fetchAndDecodeWebp(url: String): RawImage {
    val res = window.fetch(url).await<Response>()
    val blob = res.blob().await<Blob>()
    val bitmap = window.createImageBitmap(blob).await<ImageBitmap>()
    
    val canvas = document.createElement("canvas") as HTMLCanvasElement
    canvas.width = bitmap.width
    canvas.height = bitmap.height
    val ctx = canvas.getContext("2d") as CanvasRenderingContext2D
    ctx.drawImage(bitmap, 0.0, 0.0)
    
    val data = ctx.getImageData(0.0, 0.0, bitmap.width.toDouble(), bitmap.height.toDouble())
    return RawImage(bitmap.width, bitmap.height, data.data.toByteArray())
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
| **TwelveMonkeys ImageIO** (`imageio-webp`) | Pur Java, pas de natif, déploiement trivial (jar+SPI), WebP lossy/lossless. Suffisant dans la majorité des cas. |
| **Scrimage** (`scrimage-webp`) | Wrappe libwebp natif, plus rapide, embarque des binaires par OS. Préférable pour du décodage massif côté serveur. |
| **JNI direct sur libwebp** | Seulement si on a déjà du natif dans le projet. Sinon overkill. |

### Trois scénarios pratiques

1. **Tester la logique de traitement** : pas besoin de KMP. Code la logique pure en Kotlin commun, main JVM qui charge un WebP local avec ImageIO + TwelveMonkeys. Test rapide, debug confort IDE, logique réutilisée ensuite en Wasm.

2. **Un binaire qui tourne des deux côtés** (moteur de rendu CLI JVM + navigateur Wasm) : KMP avec `expect`/`actual` est exactement la bonne forme.

3. **Partager du code entre Quarkus (JVM) et un front Vue appelant Wasm** : DTOs et validation en commun dans un module KMP, deux artefacts publiés (jar pour Quarkus, `.mjs`+`.wasm`+`.d.ts` pour Vue). Élégant mais alourdit le build — à peser selon la taille du code partagé.
