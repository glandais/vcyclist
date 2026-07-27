# g13 — Module `:map` : projection et `MapImage`

## Goal

Créer le module JVM-only `:map` et y porter les fondations du rendu cartographique de gpx2web :
la projection Web Mercator (`MagicPower2MapSpace`, 141 l.) et le cadrage d'image (`MapImage`,
161 l.).

Ces deux classes ne dessinent pas de tuiles : elles calculent le cadrage (zoom, bornes,
marges, taille) et convertissent coordonnées géographiques ↔ pixels. g14 et g15 s'appuient
dessus.

## Depends on

- `g01` (module `:gpx`, pour `Path`)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/util/MagicPower2MapSpace.java`
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/map/MapImage.java`
- `elevation/src/commonMain/…/{Tiles,ElevationFunctions}.kt` — contiennent déjà des conversions
  lat/lon ↔ tuile ; **vérifier le recouvrement avant de porter**

## Steps

### 1. Créer le module

`settings.gradle.kts` : ajouter `":map"`.

`map/build.gradle.kts` : plugin `kotlin-jvm` (pas `kotlin-multiplatform`), `jvmToolchain(21)`,
publication Maven Central sous `vcyclist-map`. **Pas** de publication npm.

```kotlin
dependencies {
    api(project(":gpx"))
    api(project(":elevation"))   // pour g15 (profil d'élévation)
}
```

C'est le premier module JVM-only du dépôt. Vérifier que `./gradlew check` reste vert sur les
autres modules et que la publication Maven Central s'applique correctement à un module non-KMP
(le bloc `mavenPublishing` d'`engine/build.gradle.kts` cible un projet KMP — l'adapter).

### 2. Vérifier le recouvrement avec `:elevation`

`ElevationFunctions` et `Tiles` font déjà des conversions lat/lon ↔ coordonnées de tuile pour
les tuiles Terrarium. `MagicPower2MapSpace` fait la même chose pour les tuiles cartographiques.

**Avant de porter, comparer les deux.** Si `:elevation` couvre déjà le besoin, réutiliser au
lieu de dupliquer. Si les conventions diffèrent (origine, sens de l'axe Y, taille de tuile
configurable), le documenter explicitement et porter la partie manquante.

Consigner la conclusion dans les Notes : c'est l'information utile pour g14.

### 3. Projection

`map/src/main/kotlin/io/github/glandais/map/MapSpace.kt` :

conversions lat/lon ↔ pixels pour un niveau de zoom donné, taille de tuile paramétrable
(256 px par défaut). Port direct de `MagicPower2MapSpace`, en Kotlin idiomatique.

### 4. Cadrage

`map/src/main/kotlin/io/github/glandais/map/MapImage.kt` :

- Constructeurs équivalents à ceux de gpx2web : `(paths, margin, maxSize)` et
  `(paths, margin, width, height)`.
- Calcul des bornes géographiques englobant tous les `Path`, application de la marge,
  choix du niveau de zoom, dimensions finales en pixels.
- Accesseurs `getX(lon)`, `getY(lat)`, `getLon(x)`, `getLat(y)`, `getTileI`, `getTileJ`.
- `saveImage(file)` → PNG via `ImageIO`.

Prendre `List<Path>` en entrée (et non le `GPX` de gpx2web), cohérent avec le multi-track de g02.

### 5. Tests

Le cadrage et la projection sont du calcul pur : entièrement testables sans réseau ni fichier.

## Outputs

Créés :

- `map/build.gradle.kts`
- `map/src/main/kotlin/io/github/glandais/map/{MapSpace,MapImage}.kt`
- `map/src/test/kotlin/io/github/glandais/map/{MapSpaceTest,MapImageTest}.kt`

Modifiés :

- `settings.gradle.kts`, `README.md`, `CLAUDE.md` (nouveau module JVM-only)

## Validation

```bash
./gradlew :map:test
./gradlew check
./gradlew ktlintCheck
```

Cas de test (≥ 12) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `MapSpace` : (0°, 0°) au zoom 0 | centre de la tuile unique |
| 2 | Aller-retour lon → x → lon | égalité à 1e-9 |
| 3 | Aller-retour lat → y → lat | égalité à 1e-9 |
| 4 | Bornes du Web Mercator (±85,0511°) | pas de débordement |
| 5 | Zoom n → zoom n+1 | coordonnées doublées |
| 6 | `MapImage` sur un `Path` d'un seul point | dimensions valides, pas de division par zéro |
| 7 | Bornes d'un `Path` de 3 points | englobe tous les points |
| 8 | Marge de 10 % | bornes élargies dans les deux directions |
| 9 | `maxSize` respecté | ni largeur ni hauteur ne dépasse |
| 10 | `width`/`height` imposés | dimensions exactes |
| 11 | Trace traversant l'antiméridien | comportement figé et documenté |
| 12 | Multi-track | bornes englobant tous les `Path` |
| 13 | `saveImage` | PNG relisible par `ImageIO.read` |

Le cas 11 demande une décision : gpx2web ne gère probablement pas l'antiméridien. Établir le
comportement et le documenter plutôt que de le corriger silencieusement.

## Done when

- [ ] Module `:map` créé, JVM-only, publiable en Maven Central
- [ ] Recouvrement avec `:elevation` évalué et conclusion documentée
- [ ] `MapSpace` porté
- [ ] `MapImage` porté, entrée `List<Path>`
- [ ] ≥ 12 tests verts
- [ ] Comportement antiméridien figé et documenté
- [ ] `./gradlew check` vert sur tous les modules, `ktlintCheck` vert

## Notes

- **Premier module JVM-only du dépôt.** L'invariant « `commonMain` compile sur 4 cibles » n'est
  pas menacé puisque `:map` n'a pas de `commonMain` — mais vérifier que rien dans `:gpx` ou
  `:engine` ne se met à dépendre de `:map`.
- **Ne pas dupliquer les conversions de `:elevation`** sans avoir vérifié. Deux implémentations
  de Web Mercator dans le même dépôt finiront par diverger.
- **Publication Maven Central d'un module non-KMP** : le bloc `mavenPublishing` existant est
  écrit pour des projets KMP. Vérifier qu'il fonctionne tel quel ou l'adapter, et le noter pour
  g19.
- `java.awt.image.BufferedImage` et `ImageIO` sont dans le JDK — aucune dépendance externe pour
  cette tâche. Les tuiles (g14) en ajouteront peut-être.
