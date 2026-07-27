# g11 — Port de `ClimbDetector`

## Goal

Porter la détection de cols de gpx2web (`climb/`, 335 l. au total) en commonMain : à partir
d'un `Path`, produire la liste des ascensions avec leurs caractéristiques (départ, arrivée,
dénivelé, pente moyenne, découpage en portions homogènes).

C'est la brique gpx2web au meilleur rapport valeur/coût : calcul pur, aucune dépendance de
plateforme, et une fonctionnalité que ni la référence TS ni vcyclist n'ont.

## Depends on

- `g01` (module `:gpx`, pour `Path`)

## Inputs

Dans `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/` :

- `climb/ClimbDetector.java` (245 l., canonique)
- `climb/{Climb,ClimbPart,ClimbParts,ClimbPoint,Climbs,DetectedClimb}.java`
- `util/Simplifier.java` (le `Simplifier<ClimbPoint>` utilisé par le détecteur)
- `util/Vector.java`

Côté vcyclist :

- `elevation/src/commonMain/…/DouglasPeucker.kt` (remplace `Simplifier`)
- `gpx/src/commonMain/…/path/Path.kt`

## Steps

### 1. Modèle

`engine/src/commonMain/kotlin/io/github/glandais/engine/climb/` :

```kotlin
/** Une ascension détectée sur un [Path]. */
data class Climb(
    val startIndex: Int,
    val endIndex: Int,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElevationM: Double,
    val endElevationM: Double,
    /** Découpage en portions de pente homogène. */
    val parts: List<ClimbPart>,
) {
    val lengthM: Double get() = endDistanceM - startDistanceM
    val elevationGainM: Double get() = endElevationM - startElevationM
    /** Pente moyenne, sans unité (0,08 = 8 %). */
    val averageGrade: Double get() = elevationGainM / lengthM
}

/** Portion de pente homogène à l'intérieur d'un [Climb]. */
data class ClimbPart(
    val startDistanceM: Double,
    val endDistanceM: Double,
    val startElevationM: Double,
    val endElevationM: Double,
) { /* lengthM, elevationGainM, grade */ }
```

Ne pas porter `ClimbPoint` / `DetectedClimb` / `Climbs` tels quels : ce sont des structures
internes ou des enveloppes de liste. `Climbs` devient `List<Climb>`, `ClimbPoint` un détail
d'implémentation, `DetectedClimb` un type privé de l'algorithme.

### 2. Algorithme

```kotlin
object ClimbDetector {
    fun detect(path: Path, options: ClimbOptions = ClimbOptions.DEFAULT): List<Climb>
}

/** Paramètres du détecteur. Les défauts reprennent ceux de `ClimbDetector.getClimbs()`. */
data class ClimbOptions(
    val minMinClimbElevationM: Double = 10.0,
    val maxMinClimbElevationM: Double = 35.0,
    val minClimbElevationRatio: Double = 100.0,
    val minGradePercent: Double = 3.0,
    val maxDiffRealGrade: Double = 1.3,
    val booster: Double = 1.3,
) { companion object { val DEFAULT = ClimbOptions() } }
```

Les six constantes viennent directement de la surcharge `getClimbs(gpxPath)` de gpx2web —
les reprendre à l'identique pour pouvoir comparer les sorties.

Le seuil effectif est calculé dynamiquement :

```
minClimbElevation = max(minMinClimbElevation,
                        min(maxMinClimbElevation, totalElevation / minClimbElevationRatio))
```

Puis, pour chaque point, recherche du meilleur candidat d'ascension, tri, et déduplication.
**Lire les 245 lignes intégralement avant d'écrire** : la sélection du meilleur candidat et
le rôle du `booster` ne se devinent pas depuis la signature.

### 3. Découpage en portions

Le `Simplifier<ClimbPoint>` de gpx2web est un Douglas-Peucker sur `(distance, elevation, 0)`.
`:elevation` fournit déjà un Douglas-Peucker 3D — l'utiliser avec la troisième coordonnée à
zéro plutôt que de porter `Simplifier` et `Vector`.

Vérifier que la tolérance et la sémantique correspondent : si le `Simplifier` de gpx2web
diffère du `DouglasPeucker` de `:elevation` (critère perpendiculaire vs vertical, gestion des
extrémités), le documenter et choisir explicitement.

### 4. Où le placer

Dans `:engine`, pas dans `:gpx`. La détection de cols est une analyse, pas de l'I/O, et
`:engine` dépend déjà de `:elevation` pour le Douglas-Peucker.

### 5. Tests

Fixtures synthétiques (montée triangulaire, plateau, descente, profil en dents de scie) plus
les traces réelles — `stelvio.gpx` est le cas de référence évident : un col unique, long,
régulier.

## Outputs

Créés :

- `engine/src/commonMain/…/climb/{Climb,ClimbPart,ClimbOptions,ClimbDetector}.kt`
- `engine/src/commonTest/…/climb/ClimbDetectorTest.kt`

## Validation

```bash
./gradlew :engine:allTests
./gradlew ktlintCheck
```

Cas de test (≥ 12) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `Path(0)` | liste vide |
| 2 | Path plat | liste vide |
| 3 | Descente pure | liste vide |
| 4 | Montée unique de 500 m D+ sur 10 km | 1 col, pente ≈ 5 % |
| 5 | Deux montées séparées par un plat long | 2 cols |
| 6 | Deux montées séparées par une descente courte | comportement à figer après lecture du Java |
| 7 | Montée sous `minMinClimbElevationM` | ignorée |
| 8 | Montée à pente < `minGradePercent` | ignorée |
| 9 | Cols non chevauchants | assertion sur tous les couples |
| 10 | Indices croissants et dans les bornes | propriété |
| 11 | Somme des `parts` = longueur du col | propriété |
| 12 | `stelvio.gpx` | 1 col détecté, dénivelé cohérent avec la réalité (~1500 m) |
| 13 | Options personnalisées | seuils respectés |

Le cas 6 est le seul qui demande une décision : le noter explicitement dans la fiche une fois
le comportement Java établi.

## Done when

- [ ] `ClimbDetector.java` lu intégralement, algorithme documenté dans le KDoc
- [ ] Modèle `Climb` / `ClimbPart` / `ClimbOptions` en commonMain
- [ ] Découpage en portions via le `DouglasPeucker` de `:elevation`, écart avec `Simplifier`
      documenté
- [ ] Défauts identiques à `getClimbs(gpxPath)` de gpx2web
- [ ] ≥ 12 tests verts × 4 cibles
- [ ] Comportement du cas 6 figé et documenté
- [ ] `ktlintCheck` vert

## Notes

- **Ne pas porter `Simplifier` ni `Vector`** : `:elevation` a déjà l'équivalent. Porter du code
  redondant crée deux implémentations à maintenir en phase.
- **Comparaison avec gpx2web** : si l'occasion se présente, faire tourner `ClimbDetector` en
  Java sur `stelvio.gpx` et comparer les cols détectés. Ce n'est pas requis pour clore la
  tâche, mais c'est le seul moyen de valider vraiment le port.
- **Pente en pourcentage ou sans unité** : gpx2web mélange les deux (`minGrade = 3.0` est un
  pourcentage, `getGrade()` renvoie une valeur sans unité). Choisir une convention unique côté
  Kotlin, la nommer dans les identifiants (`minGradePercent` vs `averageGrade`), et convertir
  aux frontières.
- Cette tâche est indépendante des phases B, C, D : elle peut être menée en parallèle dès g01
  terminée.
