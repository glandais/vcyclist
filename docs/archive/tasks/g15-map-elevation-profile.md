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

- [x] Nature exacte du rendu établie par lecture du Java, documentée en KDoc
- [x] `SrtmMapProducer` porté, adossé à `:elevation`
- [x] Lookups d'élévation batchés, nombre borné et testé
- [x] Temps de rendu mesuré sur ~1000×1400 et consigné
- [x] Altitudes manquantes gérées avec une couleur documentée
- [x] 12 tests unitaires sans réseau + test d'intégration gaté (exécuté une fois pour de vrai)
- [x] `ktlintCheck` vert

## Résultat

**Ce que dessine réellement la classe : une carte hypsométrique** (couleur par altitude). Ni
ombrage de relief, ni courbes de niveau — établi en lisant les 142 lignes, comme la fiche
l'exigeait, et non déduit du nom. **Deux rampes distinctes, avec deux normalisations
différentes :**

| | 0,0 | 0,5 | 1,0 | normalisée sur |
|---|---|---|---|---|
| Fond | cyan `(0,255,255)` | jaune `(255,255,0)` | magenta `(255,0,255)` | le min/max de **l'image** |
| Trace | bleu `(0,0,255)` | vert `(0,255,0)` | rouge `(255,0,0)` | le min/max de **la trace** |

Conséquence à connaître : le même terrain ne rend pas pareil selon le cadrage, puisque la
normalisation dépend de ce qui est visible. C'est le comportement de la référence, et c'est ce
qui rend le relief lisible aussi bien en plaine qu'en montagne.

**Échantillonnage : le vrai sujet de la tâche.** La référence fait un `getElevation` **par
pixel** — un million d'appels pour une image 1000×1000. Remplacé par une grille plus grossière,
plafonnée par `maxSamples` (65 536 par défaut), récupérée en **un seul appel batché**
(`setElevations` groupe par tuile), puis interpolée bilinéairement.

Mesuré sur un rendu 1001×1396 (1 397 396 pixels), DEM synthétique en mémoire :

| | échantillons DEM | temps |
|---|---|---|
| plafonné (défaut) | 56 762 | 123 ms |
| un par pixel | 1 397 396 | 147 ms |

**Ce que ce tableau ne dit pas, et qu'il faut lire honnêtement :** face à un DEM *gratuit* en
mémoire, l'écart de temps est faible — l'interpolation reprend une partie de ce que les lookups
économisent. Le gain réel est le **facteur 25 sur le nombre d'échantillons**, qui est ce qui
compte face à un vrai provider, où chaque échantillon signifie une recherche de tuile et, à
froid, un téléchargement plus un décodage WebP. Les deux images sont visuellement identiques :
à ~30 m de résolution DEM, les échantillons supplémentaires retombent sur la même donnée.

*(Le premier jet de ce KDoc annonçait « 0,3 s contre 40 s ». C'était écrit avant de mesurer, et
c'était faux. Remplacé par les chiffres ci-dessus.)*

**Altitudes manquantes :** gris neutre `(128,128,128)`, explicitement **pas** noir — le noir se
lirait comme « altitude la plus basse » sur la rampe. Une case dont un seul des quatre coins
d'interpolation est `NaN` est déclarée manquante plutôt que d'inventer une altitude plausible.

**Relief plat :** la référence divise par zéro (`(ele-min)/(max-min)`) et atterrit sur jaune via
l'arrondi de `NaN`. Même couleur ici, mais choisie explicitement.

**Quatre erreurs de ma part attrapées par les tests :**

1. J'avais asserté que le canal **bleu** varie de façon monotone sur un plan incliné. Faux : sur
   la rampe cyan→jaune→magenta le bleu fait 255→0→255. C'est le **rouge** qui est monotone.
2. La grille d'échantillonnage demandait **plus de points que de pixels** sur les petites images
   (`width/step + 2` avec `step = 1`). Corrigé en bornant la grille aux dimensions de l'image.
3. La détection de « pixels de trace » par seuils de canaux ratait une trace **rouge sur fond
   jaune** — elles ne diffèrent que par le vert. Remplacé par une comparaison avec la couleur de
   fond réelle, lue dans un coin.
4. Le test multi-trace comparait des comptages entre **deux rendus différents** ; or ajouter une
   trace change les bornes, donc tout le cadrage. Réécrit pour vérifier la présence de pixels de
   trace près de *chaque* trace dans un **seul** rendu.

**Test d'intégration exécuté pour de vrai, une fois**, contre la source DEM que `:elevation`
configure et attribue déjà (et que ses propres tests d'intégration utilisent) : le relief rendu
est non uniforme, donc les vraies altitudes sont bien arrivées. Contrairement à g14, je ne me
suis pas rabattu sur un serveur local : il s'agit ici de la source du projet, quelques tuiles,
une fois — proportionné.

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:map` = 54 tests (42 de g13/g14, 12
nouveaux), dont 0 accès réseau par défaut.

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
