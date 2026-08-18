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

- [x] Module `:map` créé, JVM-only, publiable en Maven Central
- [x] Recouvrement avec `:elevation` évalué et conclusion documentée
- [x] `MapSpace` porté
- [x] `MapImage` porté, entrée `List<Path>`
- [x] 23 tests verts
- [x] Comportement antiméridien figé et documenté
- [x] `./gradlew check` vert sur tous les modules, `ktlintCheck` vert

## Résultat

**Recouvrement avec `:elevation` : c'est la même projection, démontré et verrouillé.** La
comparaison a été faite algébriquement, pas supposée. gpx2web calcule
`0,5 − ln((1+sin φ)/(1−sin φ)) / 4π` ; `:elevation` calcule `(1 − ln(tan φ + sec φ)/π) / 2`.
Comme `ln((1+sin φ)/(1−sin φ)) = 2·ln(tan φ + sec φ)`, les deux expressions sont identiques —
vérifié numériquement à 1e-11 près avant d'écrire une ligne de Kotlin.

**Malgré cela, `MapSpace` est une implémentation distincte, pour trois raisons de convention :**

| | `:elevation` | `:map` |
|---|---|---|
| Unités | coordonnées de **tuile** | **pixels** (facteur `tileSize`) |
| Plage de zoom | 0–15 (limite de la source DEM) | 0–22 (le rendu va couramment à 16–18) |
| Hors bornes | **lève** une exception | **borne** (clamp) |
| Inverse pixel → lat/lon | absent | requis pour le cadrage |

Le point 3 est le plus structurant : lever est correct pour une interrogation d'altitude, mais
un rendu ne doit pas échouer parce qu'un point traîne à 86° — il produit un pixel de bord.

Le risque que la fiche pointe (« deux implémentations de Web Mercator finiront par diverger »)
est traité par `MapSpaceCrossCheckTest`, qui compare les deux sur 8 coordonnées × 16 niveaux de
zoom à 1e-9 près. Sans ce test, « même projection » serait un commentaire invérifiable.

**Antiméridien : figé, non corrigé.** Les bornes sont un min/max naïf sur les longitudes, donc
une trace passant de +179,9° à −179,9° donne une étendue de 359,8° au lieu de 0,2°. L'image
reste valide, simplement dézoomée au monde entier. C'est le comportement de la référence ; le
corriger demanderait de détecter l'enroulement et de travailler dans un espace de longitude
décalé, ce qui change le sens de tous les accesseurs. Le cas 11 le verrouille pour qu'une
correction future soit un choix et non un accident.

**Trois comportements de bord découverts par les tests, pas par la relecture :**

1. **Le clamp à l'est casse l'aller-retour de longitude.** `lonToX` borne à `maxPixels − 1`
   (repris de la référence), donc les longitudes situées dans le dernier pixel s'y écrasent. À
   zoom 0 ce pixel fait **1,4° de large** : 179,9° revient à 178,59°. Documenté, et le test
   d'agrément croisé s'arrête à 170° pour cette raison.
2. **`MAX_LAT` projette sur y ≈ −2e-10, pas exactement 0.** C'est la latitude qui *définit* le
   bord supérieur ; le flottant laisse un cheveu négatif. L'assertion est donc une tolérance.
3. **Les bornes ne contenaient pas tout à fait la trace.** gpx2web tronque les deux coins vers
   zéro, ce qui rétrécit la boîte d'une fraction de pixel — environ 2 m au zoom de travail — et
   laissait le point extrême *hors* des bornes. **Écart délibéré assumé :** les coins sont
   arrondis vers l'extérieur (`floor` pour le min, `ceil` pour le max). Contenir la trace est la
   raison d'être de la classe ; 2 m d'écart sont invisibles sur une carte mais rendaient
   l'invariant faux.

**Module JVM-only : l'invariant tient.** `:map` utilise `kotlin-jvm`, n'a pas de `commonMain`, et
**rien ne dépend de lui** (vérifié par recherche : aucune autre `build.gradle.kts` ne référence
`project(":map")`). `./gradlew check` reste vert sur les quatre cibles du cœur.

**Publication non-KMP : le bloc `mavenPublishing` fonctionne tel quel.** Le POM généré porte
`io.github.glandais:vcyclist-map` et résout correctement les variantes `-jvm` de ses dépendances
KMP (`vcyclist-gpx-jvm`, `vcyclist-elevation-jvm`). `publishAndReleaseToMavenCentral` existe.
**Rien n'a encore été ajouté au `publishCmd` de `.releaserc.json`** — à faire en g19, quand
`:map` aura du contenu utile (g14/g15).

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:map` = 23 tests (10 `MapSpaceTest`,
11 `MapImageTest`, 2 `MapSpaceCrossCheckTest`).

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
