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

- [x] `ClimbDetector.java` lu intégralement, algorithme documenté dans le KDoc
- [x] Modèle `Climb` / `ClimbPart` / `ClimbOptions` en commonMain
- [x] Découpage en portions via le `DouglasPeucker` de `:elevation`, écart avec `Simplifier`
      documenté
- [x] Défauts identiques à `getClimbs(gpxPath)` de gpx2web
- [x] 14 tests verts × 4 cibles
- [x] Comportement du cas 6 figé et documenté — **et vérifié contre le Java**
- [x] `ktlintCheck` vert

## Résultat

**Le `DouglasPeucker` de `:elevation` n'était pas réutilisable tel quel.** La fiche disait
« l'utiliser avec la troisième coordonnée à zéro » : impossible, `simplify()` prend des
`CoordinatesElevation` et les projette en ECEF, donc il attend une latitude et une longitude.
Un profil `(distance, altitude)` n'en a pas.

Plutôt que d'écrire un second Douglas-Peucker — ce que la fiche interdit à juste titre — la
fonction a été **généralisée** : `DouglasPeucker.simplifyIndices(points: List<Vector3D>,
tolerance): List<Int>` est désormais le cœur géométrique, et `simplify()` géographique
l'appelle après projection ECEF. Une seule implémentation, deux appelants. Les fixtures de
parité de `:engine` passent inchangées, ce qui confirme que le refactor est neutre.

`Vector` de gpx2web n'a pas été porté non plus : `Vector3D` de `:elevation` a déjà
`distanceToSegment`, avec la même sémantique (distance perpendiculaire, repli sur l'extrémité
quand la projection sort du segment — gpx2web teste le signe des produits scalaires, `Vector3D`
borne le paramètre de projection ; c'est le même prédicat écrit autrement).

**Convention de pente.** Tout ce qui s'appelle `…Percent` est un pourcentage, tout ce qui
s'appelle `…Grade` est sans unité (`0.08` = 8 %). L'algorithme travaille en pourcentage en
interne, comme le Java, pour que `score` et les seuils restent directement comparables.

**Cas 6 figé — et l'inverse de ce qui était supposé.** La première rédaction du test pariait
que deux montées séparées par une courte descente resteraient séparées. C'est faux. Le
comportement réel, mesuré puis figé :

```
creux  30 m -> ratio climbingGrade/averageGrade = 1,14 -> 1 col
creux  60 m -> ratio 1,29                              -> 1 col
creux  90 m -> ratio > 1,3                             -> 2 cols
```

C'est exactement `maxDiffRealGradeRatio` (1,3) qui décide : la montée reste entière tant que le
creux ne déforme pas trop la moyenne. Lecture sensée — une courte descente dans un col reste le
même col — et c'est le rôle que le commentaire du Java donne à ce paramètre.

**Validation croisée contre le Java — faite.** La fiche la disait facultative mais « le seul
moyen de valider vraiment le port ». Les 9 fichiers nécessaires (`climb/*`, `Simplifier`,
`Vector`) ont été **copiés** dans un bac à sable, les annotations Spring/Jakarta retirées et
`GPXPath` remplacé par une doublure exposant les quatre accesseurs réellement utilisés
(`getDists`, `getEles`, `getPoints`, `getTotalElevation`). Le dépôt gpx2web n'a pas été touché.
Sorties sur les mêmes profils :

| Profil | Java | Kotlin |
|---|---|---|
| plat | 0 col | 0 ✓ |
| descente pure | 0 col | 0 ✓ |
| montée 500 m / 10 km | 1 col, +500 m, 5,000 % | 1 col, +500 m, 5,0 % ✓ |
| pente 1 % | 0 col | 0 ✓ |
| deux montées / plat long | 2 cols, +245 m chacun | 2 cols, +245 m ✓ |
| creux 60 m | **1 col**, +430 m, 3,874 %, 3 portions | **1 col**, 3 portions ✓ |
| creux 90 m | **2 cols**, +245 m chacun | **2 cols** ✓ |
| Stelvio (21 km à 7,3 %) | 1 col, +1533 m, 7,300 % | 1 col, +1533 m, 7,3 % ✓ |

Concordance sur tous les cas, y compris la frontière du cas 6. Les distances diffèrent de
quelques mètres (11 088 m contre 11 100 m) parce que les fixtures Kotlin passent par de vraies
distances géodésiques là où le harnais Java utilise des pas de 100 m exacts — sans incidence.

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:engine` = 218 tests JVM (contre 204),
225 JS Node, 220 Wasm navigateur.

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
