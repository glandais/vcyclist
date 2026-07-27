# g14 — `:map` : `TileMapProducer` (tuiles + cache)

## Goal

Porter `TileMapProducer` (239 l.) et `TileMapImage` (37 l.) : produire une image PNG de la
trace superposée à un fond de carte téléchargé depuis un serveur de tuiles.

## Depends on

- `g13` (`MapSpace`, `MapImage`, module `:map`)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/map/{TileMapProducer,TileMapImage}.java`
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/util/CacheFolderProvider.java`
- `elevation/src/jvmMain/…/TileFetcher.jvm.kt` (précédent : fetch HTTP + cache côté JVM)

## Steps

### 1. Récupération des tuiles

`map/src/main/kotlin/io/github/glandais/map/TileFetcher.kt`.

gpx2web utilise `java.net.http.HttpClient` avec un `USER_AGENT` explicite :

```java
public static final String USER_AGENT = "gpx2web (https://github.com/glandais/gpx2web)";
```

**Le user-agent doit être adapté à vcyclist et rester explicite.** La politique d'usage des
tuiles OSM impose une identification et interdit le téléchargement massif ; un user-agent
générique fait bannir l'adresse IP. Reprendre aussi le `ThreadLocalRandom` de gpx2web s'il sert
à répartir les requêtes sur plusieurs sous-domaines.

Rendre l'URL de tuiles **obligatoire et sans défaut** : forcer l'appelant à choisir sa source
et donc à assumer la politique d'usage correspondante.

### 2. Cache disque

`CacheFolderProvider` de gpx2web est une interface implémentée différemment par le CLI et la
webapp. Ici, un paramètre suffit :

```kotlin
class TileMapProducer(
    private val cacheFolder: java.io.File,
    private val userAgent: String,
)
```

Politique de cache : une tuile est immuable en pratique. La conserver indéfiniment, avec une
arborescence `{cache}/{host}/{z}/{x}/{y}.png`. Pas d'expiration — l'utilisateur peut vider le
dossier.

### 3. Assemblage

`TileMapImage` étend `MapImage` : télécharge les tuiles couvrant les bornes, les dessine dans
le `BufferedImage`, puis laisse le producteur tracer la trace par-dessus.

Le tracé de la trace (couleur, épaisseur, éventuel dégradé) suit gpx2web ; le porter tel quel,
puis exposer les paramètres qui existent déjà côté Java.

### 4. Surcharges publiques

gpx2web en expose quatre :

```kotlin
fun createTileMap(file: File, paths: List<Path>, urlPattern: String, margin: Double, width: Int?, height: Int?)
fun createTileMap(file: File, paths: List<Path>, urlPattern: String, margin: Double, maxSize: Int)
fun createTileMap(file: File, paths: List<Path>, urlPattern: String, zoom: Int, margin: Double)
```

Les porter en Kotlin avec des paramètres par défaut plutôt qu'en surcharges multiples, mais
conserver les trois modes de cadrage (dimensions imposées / taille max / zoom imposé).

### 5. Tests sans réseau

Les tests unitaires ne doivent **pas** télécharger de tuiles. Deux niveaux :

- **Tests unitaires** : injecter un fetcher de test qui renvoie des tuiles générées en mémoire
  (damier de couleur). Valide l'assemblage, le cadrage, le tracé, l'écriture du PNG.
- **Test d'intégration** : gaté par `INTEGRATION=1`, comme les tests existants de `:elevation`.
  Télécharge de vraies tuiles et vérifie que l'image produite est non vide et relisible.

Le fetcher doit donc être une interface injectable, pas un appel HTTP en dur.

## Outputs

Créés :

- `map/src/main/kotlin/io/github/glandais/map/{TileFetcher,TileMapImage,TileMapProducer}.kt`
- `map/src/test/kotlin/io/github/glandais/map/TileMapProducerTest.kt` (fetcher factice)
- `map/src/test/kotlin/io/github/glandais/map/TileMapIntegrationTest.kt` (gaté `INTEGRATION=1`)

## Validation

```bash
./gradlew :map:test
INTEGRATION=1 ./gradlew :map:test --tests '*Integration*'
./gradlew ktlintCheck
```

Cas de test (≥ 10) :

| # | Cas | Attendu |
|---|---|---|
| 1 | Cadrage `maxSize` | tuiles couvrant exactement les bornes |
| 2 | Zoom imposé | niveau respecté |
| 3 | Dimensions imposées | image aux dimensions exactes |
| 4 | Tuile absente du cache | fetcher appelé une fois |
| 5 | Tuile présente dans le cache | fetcher **non** appelé |
| 6 | Deuxième rendu identique | zéro appel réseau |
| 7 | Échec de téléchargement d'une tuile | image produite avec une tuile vide, pas d'exception |
| 8 | Trace tracée par-dessus le fond | pixels de la couleur de trace présents |
| 9 | Multi-track | toutes les traces dessinées |
| 10 | PNG écrit | relisible par `ImageIO.read`, dimensions correctes |
| 11 | User-agent | présent dans la requête (vérifié via le fetcher factice) |
| 12 | *(intégration)* vraies tuiles | image non vide |

Le cas 7 est important : une carte partiellement téléchargée vaut mieux qu'une exception.

## Done when

- [ ] `TileFetcher` en interface injectable
- [ ] User-agent explicite propre à vcyclist
- [ ] URL de tuiles obligatoire, sans défaut
- [ ] Cache disque `{cache}/{host}/{z}/{x}/{y}.png`
- [ ] Trois modes de cadrage portés
- [ ] Tuile manquante tolérée
- [ ] ≥ 10 tests unitaires sans réseau + test d'intégration gaté
- [ ] `ktlintCheck` vert

## Notes

- **Politique d'usage des tuiles** : risque identifié dans le plan. Ne pas embarquer d'URL par
  défaut — c'est ce qui pousse les utilisateurs à taper le serveur public d'OSM sans le savoir.
  Documenter dans le README du module que le choix de la source engage l'appelant.
- **Pas de téléchargement dans les tests unitaires** : un test qui dépend du réseau est un test
  qui échouera un jour en CI pour une raison sans rapport.
- **Cache sans expiration** : les tuiles sont immuables en pratique et le rendu doit être
  reproductible. Un rendu qui change parce que le fond de carte a été mis à jour entre deux
  exécutions rend tout test de non-régression impossible.
- La parallélisation du téléchargement n'est pas dans le périmètre. Si le rendu s'avère trop
  lent sur de grandes traces, en faire une tâche séparée avec des mesures à l'appui.
