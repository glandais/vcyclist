# g15 — `:map` : `SRTMMapProducer` (profil d'élévation PNG)

## Goal

Porter `SRTMMapProducer` (142 l.) : produire une image PNG représentant le relief autour de la
trace, à partir des données d'élévation, sans fond de carte téléchargé.

Contrairement à `TileMapProducer` (g14) qui superpose la trace à des tuiles cartographiques,
celui-ci **génère** le fond à partir du modèle numérique de terrain fourni par `:elevation`.

## Depends on

- `g13` (`MapSpace`, `MapImage`)
- `g14` (tracé de la trace sur un `BufferedImage` — à réutiliser)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/map/SRTMMapProducer.java` (canonique)
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/srtm/GpxElevationProvider.java`
  (source d'élévation côté gpx2web — remplacée par `:elevation`)
- `elevation/src/commonMain/…/{ElevationProvider,BatchCalculator}.kt`

## Steps

### 1. Lire ce que produit réellement la version Java

Le nom « SRTMMapProducer » est ambigu : ombrage de relief, carte hypsométrique (couleur par
altitude), ou courbes de niveau ? **Lire les 142 lignes avant de concevoir quoi que ce soit**,
et décrire le rendu obtenu dans le KDoc du port.

Le rendu attendu détermine tout le reste : un ombrage demande le gradient d'altitude, une carte
hypsométrique une simple échelle de couleurs.

### 2. Source d'élévation

gpx2web passe par son `GpxElevationProvider`. Ici, utiliser l'`ElevationProvider` de
`:elevation`, déjà porté et testé.

Le rendu demande une altitude **par pixel**, soit potentiellement des centaines de milliers de
lookups. Utiliser `BatchCalculator` plutôt qu'un appel par pixel, et vérifier que le cache LRU
de tuiles est dimensionné en conséquence — sinon on retélécharge la même tuile en boucle.

Mesurer le temps de rendu sur une image 1000×1000 et le consigner. Si c'est prohibitif,
échantillonner à une résolution plus grossière puis interpoler, et documenter le compromis.

### 3. API

```kotlin
class SrtmMapProducer(private val elevationProvider: ElevationProvider) {
    /**
     * Rend le relief autour de [paths] et y superpose la trace.
     *
     * @param maxSize plus grande dimension de l'image en pixels
     * @param margin marge relative autour des bornes de la trace (0,1 = 10 %)
     */
    fun createSrtmMap(file: File, paths: List<Path>, maxSize: Int, margin: Double)
}
```

`ElevationProvider.getElevation` est `suspend` : encapsuler l'appel dans un `runBlocking` au
niveau du producteur (module JVM-only, c'est acceptable) plutôt que de propager `suspend` dans
toute l'API de rendu.

### 4. Rendu

Réutiliser `MapImage` pour le cadrage et le tracé de la trace. Seul le remplissage du fond
diffère de g14.

Gérer les altitudes manquantes : `ElevationProvider` renvoie `NaN` hors couverture. Choisir une
couleur neutre explicite (et non noir, qui se confond avec une altitude nulle) et la documenter.

### 5. Tests

Même approche qu'en g14 : un `ElevationProvider` factice pour les tests unitaires (relief
synthétique — plan incliné, cône, plateau), et un test d'intégration gaté par `INTEGRATION=1`
sur des données réelles.

## Outputs

Créés :

- `map/src/main/kotlin/io/github/glandais/map/SrtmMapProducer.kt`
- `map/src/test/kotlin/io/github/glandais/map/SrtmMapProducerTest.kt`
- test d'intégration gaté

## Validation

```bash
./gradlew :map:test
INTEGRATION=1 ./gradlew :map:test --tests '*Integration*'
./gradlew ktlintCheck
```

Cas de test (≥ 8) :

| # | Cas | Attendu |
|---|---|---|
| 1 | Relief plan incliné | dégradé monotone dans l'image |
| 2 | Relief plat | fond uniforme |
| 3 | Altitudes `NaN` | couleur neutre documentée, pas de crash |
| 4 | `maxSize` respecté | dimensions conformes |
| 5 | Marge appliquée | bornes élargies |
| 6 | Trace visible par-dessus le relief | pixels de la couleur de trace présents |
| 7 | Multi-track | toutes les traces dessinées |
| 8 | PNG écrit | relisible, dimensions correctes |
| 9 | Nombre de lookups d'élévation | borné, pas un appel par pixel non batché |
| 10 | *(intégration)* trace réelle en montagne | image non uniforme |

Le cas 9 se vérifie en comptant les appels sur le provider factice — c'est ce qui empêche une
régression de performance silencieuse.

## Done when

- [ ] Nature exacte du rendu établie par lecture du Java, documentée en KDoc
- [ ] `SrtmMapProducer` porté, adossé à `:elevation`
- [ ] Lookups d'élévation batchés, nombre borné et testé
- [ ] Temps de rendu mesuré sur 1000×1000 et consigné
- [ ] Altitudes manquantes gérées avec une couleur documentée
- [ ] ≥ 8 tests unitaires sans réseau + test d'intégration gaté
- [ ] `ktlintCheck` vert

## Notes

- **Ne pas concevoir avant d'avoir lu.** Le nom de la classe ne dit pas ce qu'elle dessine.
- **Batcher les lookups** : c'est le vrai risque de cette tâche. Un appel `getElevation` par
  pixel sur une image 1000×1000 fait un million d'appels et, sans cache correctement
  dimensionné, autant de calculs de tuile.
- **`runBlocking` acceptable ici** : module JVM-only, pas de contrainte multiplateforme. Ne pas
  propager `suspend` dans une API de rendu synchrone par nature.
- **`NaN` ≠ altitude 0** : une zone hors couverture DEM doit être visuellement distincte du
  niveau de la mer.
- Dernière tâche de la phase F. Après elle, `:map` remplace complètement le paquet `map/` de
  gpx2web.
