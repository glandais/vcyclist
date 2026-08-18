# g21 — `TileFetcher` : séparer le téléchargement du décodage

## Goal

`expect suspend fun fetchAndDecodeTile(url: String): RawTile` fait deux choses, et le décodeur
JVM `decodeBytes(bytes, sourceUrl)` est `private` (`TileFetcher.jvm.kt:38`). Un appelant qui
veut son propre cache — disque, S3, `OkHttp` avec `Cache-Control`, tuiles pré-téléchargées,
tests hors-ligne — doit **réécrire le décodage**, conversion ARGB → RGBA et messages d'erreur
compris.

Le point d'injection existant (`fetcher: suspend (String) -> RawTile` sur `TileManager` et
`ElevationProvider`) ne suffit pas : il permet de remplacer le téléchargement, mais oblige à
fournir *aussi* un décodeur. C'est exactement l'inverse du besoin — le téléchargement est ce que
l'appelant veut maîtriser, le décodage est ce qu'il veut réutiliser.

Découper l'`expect` en trois pour que « mon cache + votre décodeur » devienne possible.

## Depends on

Rien. Premier chantier de la série `g21`-`g27` ; indépendant des six autres.

## Inputs

- `elevation/src/commonMain/kotlin/io/github/glandais/elevation/TileFetcher.kt`
- `elevation/src/jvmMain/…/TileFetcher.jvm.kt` (`decodeBytes` à rendre public)
- `elevation/src/jsMain/…/TileFetcher.js.kt` (deux branches : `isNode` → `@jsquash/webp`, navigateur → canvas)
- `elevation/src/commonMain/…/{TileManager,ElevationProvider}.kt` (consommateurs du `fetcher`)
- `elevation/src/commonTest/…/ReferenceTileDigestTest.kt` (le garde-fou à ne pas faire bouger)
- [`docs/kotlin-js-jvm-webp.md`](../../guides/kotlin-js-jvm-webp.md) — **à lire avant** de toucher `jsMain`

## Steps

### 1. API commune

Dans `TileFetcher.kt` :

```kotlin
/** Télécharge les octets bruts de la tuile. Lève si le statut HTTP n'est pas 2xx. */
expect suspend fun fetchTileBytes(url: String): ByteArray

/**
 * Décode une image de tuile (PNG / WebP selon la cible) en RGBA non compressé.
 * [sourceUrl] n'est utilisé que pour les messages d'erreur.
 */
expect suspend fun decodeTileBytes(bytes: ByteArray, sourceUrl: String = ""): RawTile

/** Inchangé : équivaut à `decodeTileBytes(fetchTileBytes(url), url)`. */
expect suspend fun fetchAndDecodeTile(url: String): RawTile
```

`decodeTileBytes` est `suspend` **par nécessité, pas par symétrie** : la voie navigateur passe
par `createImageBitmap`, qui est asynchrone. Un `decodeTileBytes` non-`suspend` serait
impossible à implémenter sur Kotlin/JS.

Le défaut `sourceUrl = ""` évite d'imposer une URL fictive à qui décode des octets venus d'un
fichier local — voir g27 pour le `@JvmOverloads` correspondant.

### 2. Implémentations par cible

- **JVM** — `decodeBytes` devient `actual suspend fun decodeTileBytes`, corps inchangé, wrappé
  dans `withContext(Dispatchers.IO)` : `ImageIO.read` est bloquant. `fetchTileBytes` = le bloc
  `httpClient.send(…, BodyHandlers.ofByteArray())` + le `check(statusCode in 200..299)` actuel.
- **JS** — deux branches conservées. Node : `@jsquash/webp` `decode(buffer)` est déjà orienté
  octets, le découpage y est trivial. Navigateur : `fetchTileBytes` = `fetchUrlBrowser(url)` →
  `res.arrayBuffer()` → `ByteArray` (la raison de ce contournement du `fetch` de kotlinx-browser
  est commentée dans le fichier, la conserver) ; `decodeTileBytes` = `ByteArray` → `Blob` →
  `createImageBitmap` → canvas → `getImageData`, c'est-à-dire le bloc déjà écrit après le
  `res.blob()` actuel, extrait tel quel.

### 3. Recomposition

`fetchAndDecodeTile` reste un `actual` par cible plutôt qu'une fonction commune : sur JVM la
composition naïve ferait deux `withContext(Dispatchers.IO)` imbriqués, et sur JS le
`res.blob()` → `createImageBitmap` évite un aller-retour `ArrayBuffer` → `ByteArray` → `Blob`
que la composition réintroduirait. Garder les chemins directs, et faire porter la garantie
d'équivalence par un test plutôt que par la structure du code.

C'est le point d'attention principal de la tâche : **`fetchAndDecodeTile` doit rester
strictement équivalent à la composition des deux**, à l'octet près.

### 4. Documenter le point d'extension

Ajouter dans `elevation/README.md` (ou le KDoc de `ElevationProviderConfig`) l'exemple canonique
« cache disque maison » : un `fetcher` qui consulte un cache, appelle `fetchTileBytes` en cas de
défaut, stocke les octets, et délègue à `decodeTileBytes`. Une API de confort que personne ne
trouve ne sert à rien.

## Outputs

Modifiés :

- `elevation/src/commonMain/…/TileFetcher.kt`
- `elevation/src/{jvmMain,jsMain}/…/TileFetcher.*.kt`
- `elevation/README.md`

Créés :

- `elevation/src/commonTest/…/TileDecodeSplitTest.kt`

## Validation

```bash
./gradlew :elevation:allTests
./gradlew ktlintCheck
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `decodeTileBytes(fetchTileBytes(u), u)` vs `fetchAndDecodeTile(u)` | `RawTile.rgba` identique octet à octet, mêmes dimensions |
| 2 | `ReferenceTileDigestTest` | SHA-256 de la tuile de référence **inchangé** |
| 3 | `ElevationProvider` sur un `Map<String, ByteArray>` en mémoire, sans réseau | élévations correctes, zéro requête HTTP |
| 4 | `decodeTileBytes` sur des octets corrompus | erreur explicite citant `sourceUrl` |
| 5 | `decodeTileBytes` sans `sourceUrl` | fonctionne ; message d'erreur dégradé mais lisible |
| 6 | `fetchTileBytes` sur un 404 / 500 (serveur HTTP local) | lève, message avec le code |
| 7 | Fixture WebP inline (`InlineWebpFixture`) décodée par `decodeTileBytes` | même RGBA qu'avant sur les deux voies JS |

## Done when

- [x] `fetchTileBytes` et `decodeTileBytes` publics, implémentés sur les 3 cibles
- [x] `fetchAndDecodeTile` inchangé pour l'appelant, digest de référence identique
- [x] Test « cache maison sans réseau » vert
- [x] Exemple d'extension documenté
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### API

- `expect suspend fun fetchTileBytes(url: String): ByteArray` et
  `expect suspend fun decodeTileBytes(bytes: ByteArray, sourceUrl: String = ""): RawTile`,
  publics en `commonMain`, implémentés sur les 3 cibles. `fetchAndDecodeTile` garde exactement sa
  signature et son comportement.
- `fetchAndDecodeTile` **reste un `actual` par cible** plutôt que la composition littérale des
  deux moitiés. Chaque cible a un chemin plus court quand elle possède les deux bouts : la JVM
  fait un seul saut `Dispatchers.IO` au lieu de deux, et les navigateurs passent le `Response`
  directement à `createImageBitmap` sous forme de `Blob`, sans aller-retour `ByteArray` → `Blob`.
  L'équivalence est donc garantie par un test (cas 1, sur la tuile de référence réelle) et non par
  la structure du code — c'est le point qui méritait le plus d'être verrouillé.
- Défaut `sourceUrl = ""` porté par l'`expect` (les `actual` ne le répètent pas, Kotlin l'interdit).

### Implémentations

- **JVM** — `decodeBytes` privée devient `decodeTileBytes`, `withContext(Dispatchers.IO)` car
  `ImageIO.read` est bloquant ; le `httpGetBytes` privé est partagé par `fetchTileBytes` et
  `fetchAndDecodeTile`. Messages d'erreur HTTP identiques au mot près (deux tests existants les
  comparent littéralement).
- **JS** — le bloc `createImageBitmap` → canvas est extrait dans `decodeBlob`, partagé par
  `decodeTileBytes` et `fetchAndDecodeTile`. Côté Node, `@jsquash/webp` prend un `ArrayBuffer`,
  obtenu par `view.buffer.slice(byteOffset, byteOffset + byteLength)` : le `.buffer` nu aurait été
  faux pour un `ByteArray` qui est une sous-vue — cas réel, `fetchTileBytes` en produit une.

### Décisions de conception

- **Messages d'erreur de décodage non unifiés.** La JVM conserve `No ImageIO decoder for tile at
  …` (un test existant compare ce préfixe et il nomme la vraie cause), les cibles web lèvent
  `Cannot decode tile at …: <message natif>`. Les deux citent `sourceUrl`, ce que le cas 4 vérifie
  ; uniformiser aurait effacé de l'information sans rien apporter à l'appelant.
- **`sourceUrl` vide → `<unnamed bytes>`** dans les messages, plutôt qu'une chaîne vide qui
  produirait « decoder for tile at  : … ».
- **Fixtures en hexadécimal.** Nouvelle fonction de test `hexToBytes` : un `byteArrayOf(…)` de 70
  octets est illisible et ktlint impose alors un argument par ligne. Le format hexadécimal tient
  en deux lignes par fixture et reste diffable.
- **Nouvelle fixture `InlineTerrariumTileFixture`** : WebP 4 × 4 sans perte, généré avec Pillow
  (même méthode que `InlineWebpFixture`), encodant `elevation(px, py) = px * 100 + py` —
  c'est-à-dire exactement ce que produit le `syntheticFetcher` de `ElevationProviderTest`. Cette
  correspondance est ce qui rend le test « cache maison » assertif : il compare un
  `ElevationProvider` piloté par les octets à un `ElevationProvider` piloté par un `RawTile` en
  mémoire, élévation par élévation, au lieu de vérifier une vague plausibilité.

### Vérification

- `./gradlew check --rerun-tasks` → 256 tâches exécutées, vert. `ktlintCheck` vert.
- `TileDecodeSplitTest` : 8 tests × 3 cibles (JVM, JS Node, JS navigateur).
- Les 7 premiers tournent **hors ligne sur les 3 cibles** — ce que les tests de décodage
  antérieurs ne pouvaient pas faire : ils passaient par une URL `data:`, que le `HttpClient` de la
  JVM ne sait pas résoudre (d'où un `JsDecodePathTest` cantonné à `jsTest`, sans équivalent JVM).
  Le décodage par octets n'a pas ce problème : effet de bord agréable du découpage, la couverture
  du décodeur devient commune.
- Le 8ᵉ (équivalence sur la tuile de référence réelle) est sous `INTEGRATION=1`, et a été passé au
  vert sur les **3 cibles** : `INTEGRATION=1 ./gradlew :elevation:jvmTest :elevation:jsNodeTest
  :elevation:jsBrowserTest` → `tests="8" skipped="0" failures="0"` partout. Le digest SHA-256 gelé
  de `ReferenceTileDigestTest` est inchangé.
- Aucun test existant modifié ni supprimé.

## Notes

- **Pourquoi `ByteArray` et pas un flux.** Une tuile fait quelques dizaines de Ko et est décodée
  d'un bloc par les trois décodeurs sous-jacents. Un `Source`/`InputStream` n'apporterait rien et
  n'existe pas en commonMain sans dépendance supplémentaire.
- **Le cache n'est pas fourni.** vcyclist reste sans I/O disque en commonMain ; cette tâche donne
  la prise, pas l'implémentation. Un cache disque JVM éventuel serait une tâche distincte, et
  probablement du ressort de l'appelant.
- `TileManager` garde son `LruCache` en mémoire de `Tile` **décodées** : les deux niveaux de
  cache sont complémentaires (octets côté appelant, tuiles décodées côté bibliothèque) et ne se
  recouvrent pas.
