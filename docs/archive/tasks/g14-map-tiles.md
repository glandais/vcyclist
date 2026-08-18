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

- [x] `TileFetcher` en interface injectable
- [x] User-agent explicite propre à vcyclist
- [x] URL de tuiles obligatoire, sans défaut
- [x] Cache disque `{cache}/{host}/{z}/{x}/{y}.png`
- [x] Trois modes de cadrage portés
- [x] Tuile manquante tolérée
- [x] 19 tests unitaires sans réseau + test d'intégration gaté
- [x] `ktlintCheck` vert

## Résultat

**Politique d'usage : c'est le point traité le plus sérieusement de la tâche.**

- User-agent `vcyclist (https://github.com/glandais/vcyclist)`, adapté depuis celui de gpx2web
  qui nommait l'autre projet. Il est **vérifié réellement envoyé** par un test qui lance un
  `com.sun.net.httpserver` local et lit l'en-tête reçu — le vérifier via un faux fetcher n'aurait
  rien prouvé, puisque l'en-tête est posé à l'intérieur de l'implémentation HTTP.
- Un user-agent vide est **refusé à la construction** : échouer bruyamment vaut mieux que se
  faire bannir silencieusement.
- **Aucune URL par défaut**, ni dans `createTileMap`, ni dans `HttpTileFetcher`, ni même dans le
  test d'intégration (qui lit `VCYCLIST_TILE_URL` et se saute si absent). C'est précisément
  l'absence de défaut qui empêche un appelant de taper le serveur public d'OSM sans le savoir.
- Politique documentée dans `map/README.md`, avec lien vers la Tile Usage Policy.

**Aucun test unitaire ne touche le réseau.** Deux niveaux, comme demandé : un faux `TileFetcher`
pour l'assemblage et le cache, et un serveur HTTP **local** pour la couche HTTP elle-même
(en-têtes, codes de statut, corps vide, hôte injoignable). Le test d'intégration est gaté par
`INTEGRATION=1`.

**Trois écarts délibérés par rapport à la référence :**

1. **Le code de statut est vérifié.** gpx2web envoie le corps de la réponse directement dans le
   fichier de cache (`BodyHandlers.ofFile`), donc une 404 ou une page de rate-limit est
   **enregistrée en tant que tuile** puis dessinée. Ici une réponse non-2xx signifie simplement
   « pas de tuile ».
2. **Les échecs ne sont pas mis en cache.** gpx2web crée un fichier vide (`FileUtils.touch`), ce
   qui rend la panne définitive : une coupure réseau passagère efface cette tuile pour toujours.
   Ici l'échec n'écrit rien et le rendu suivant réessaie. Les tuiles réussies restent en cache
   indéfiniment, donc la reproductibilité demandée par la fiche est intacte.
3. **Cache lisible par un humain** : `{cache}/{host}/{z}/{x}/{y}.png` au lieu du
   `hex(urlPattern.hashCode())` de gpx2web, opaque quand on cherche pourquoi un rendu est faux.

**`TileMapImage` n'a pas été porté comme classe.** Dans la référence il étend `MapImage` juste
pour transporter `cache` et `urlPattern` ; côté Kotlin `MapImage` est final et ces deux valeurs
appartiennent au producteur. Un `MapImage.ofZoom(...)` a été ajouté pour le troisième mode de
cadrage.

**Vérification du chemin d'intégration sans déranger personne.** Le test gaté a été exécuté
contre un **serveur de tuiles local** (`VCYCLIST_TILE_URL=http://127.0.0.1:8299/{z}/{x}/{y}.png`)
plutôt que contre un service public : cela valide toute la chaîne — requête HTTP réelle,
assemblage, tracé, écriture du PNG, cache, puis second rendu à **zéro requête** — sans mettre en
charge un serveur tiers. Un vrai test contre une source publique reste possible pour qui en a le
droit, en fournissant l'URL.

Au passage, l'assertion « beaucoup de couleurs distinctes » du premier jet a été remplacée : elle
encodait une hypothèse sur la source (une vraie carte de rue), alors que la fiche laisse
justement la source au choix de l'appelant. L'assertion porte désormais sur ce qui doit être vrai
pour **toute** source : le fond est dessiné et la trace est par-dessus.

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:map` = 42 tests (23 de g13, 19
nouveaux), dont 0 accès réseau par défaut.

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


## Vérification visuelle — 2026-07-28

**Faite.** Rendu d'un parcours Albertville → Beaufortain → Bourg-Saint-Maurice sur des tuiles
`tile.openstreetmap.org` (9 tuiles en zoom 11, cadrage `--max-size 900`) : la trace se superpose
aux routes du fond de carte, lacets compris.

Ce point méritait un œil humain. Les tests de `:map` vérifient des propriétés géométriques —
bornes, dimensions, index de tuiles, présence de pixels de trace — et resteraient tous verts si
`MapSpace` et le fetch de tuiles divergeaient d'un décalage systématique sur la projection. La
superposition ne se démontre qu'en regardant. Elle est correcte.

Rappel : cette commande n'a pas de valeur par défaut pour `--tile-url`, et l'URL ci-dessus a été
choisie explicitement par l'auteur du dépôt pour ce contrôle ponctuel. Le user-agent
`vcyclist (https://github.com/glandais/vcyclist)` est envoyé, et le cache évite tout re-téléchargement.
