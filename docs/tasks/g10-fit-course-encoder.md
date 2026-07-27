# g10 — `:fit` : conversion `Path` → `FitCourse` et round-trip

## Goal

Écrire la conversion d'un `Path` vcyclist vers le modèle `FitCourse` (commonMain, donc une
seule fois pour les 4 cibles), et valider l'ensemble par des tests de round-trip sur les
fixtures GPX réelles.

C'est la tâche qui rend l'export FIT utilisable de bout en bout : g08 et g09 ne fournissent
que la plomberie des SDK.

## Depends on

- `g05` (`startTime` absolu)
- `g08` (modèle `FitCourse`, `FitUnits`, `actual` JVM)
- `g09` (`actual` JS et Wasm)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/write/FitFileWriter.java` — en
  particulier `writeLap`, `writeCourse`, `writeFileId`
- `gpx/src/commonMain/…/path/PointField.kt` (champs disponibles)
- `gpx/src/commonTest/…/gpx/GpxFixtures.kt` (traces réelles)

## Steps

### 1. Conversion

`fit/src/commonMain/kotlin/io/github/glandais/fit/PathToFit.kt` :

```kotlin
/**
 * Convertit un [Path] virtualisé en [FitCourse].
 *
 * @param startTime instant du premier point — obligatoire, le format FIT n'ayant pas de
 *   notion de temps relatif (cf. g05).
 */
fun Path.toFitCourse(
    name: String,
    startTime: Instant,
    sport: FitSport = FitSport.CYCLING,
): FitCourse
```

Correspondance des champs, à établir en lisant `FitFileWriter.writeLap` :

| `FitRecord` | Source `PointField` |
|---|---|
| `timestamp` | `startTime + TIME(i)` ms |
| `latitudeDeg` / `longitudeDeg` | `LATITUDE` / `LONGITUDE` (radians → degrés) |
| `altitudeM` | `ELEVATION` |
| `distanceM` | `DISTANCE` |
| `speedMs` | `SPEED` |
| `powerW` | champ de puissance cycliste — **identifier lequel** parmi les 36 |
| `heartRate` / `cadence` / `temperatureC` | champs correspondants s'ils existent |

Les champs absents ou `NaN` doivent être **omis** du record, pas encodés à zéro : un fichier
FIT avec une fréquence cardiaque à 0 affiche une série plate au lieu d'une absence de donnée.

### 2. Agrégats du `FitLap`

`totalElapsedTimeS`, `totalTimerTimeS`, `totalDistanceM`, `totalAscentM`, `totalDescentM`.

`Path` fournit déjà `totalDistance`, `durationMs`, `elevationGain`, `elevationLoss` — les
réutiliser plutôt que de recalculer. `totalAscent`/`totalDescent` sont des entiers en mètres
dans le format FIT : arrondi à documenter.

Distinction `elapsed` vs `timer` : pour une course virtualisée sans pause, les deux sont
égaux. Le noter en KDoc.

### 3. API publique

```kotlin
/** Raccourci : encode directement un [Path] en octets FIT. */
fun Path.toFitBytes(name: String, startTime: Instant): ByteArray =
    FitEncoder.encode(toFitCourse(name, startTime))
```

Façade JS :

```kotlin
@JsExport fun pathToFit(path: Path, name: String, startTimeEpochMs: Double): ByteArray
```

Vérifier comment `ByteArray` traverse la frontière `@JsExport` en Kotlin/JS **et** en
Kotlin/Wasm (cf. `docs/kotlin-wasm-jvm-webp.md` §1 sur les types autorisés). Si `ByteArray`
n'est pas exportable en Wasm, exposer un `Uint8Array` ou un handle opaque, et documenter la
différence entre les deux cibles.

### 4. Tests de round-trip

Le vrai critère de cette tâche : encoder, **relire avec le SDK**, comparer aux valeurs source.

| # | Cas | Attendu |
|---|---|---|
| 1 | `Path` de 3 points | 3 records relus, coordonnées à 1e-5 ° près (précision des semicercles) |
| 2 | Timestamps | `record[i].timestamp - startTime == TIME(i)` à la seconde près |
| 3 | Distance | monotone croissante, égale à `DISTANCE(i)` à 0,01 m près |
| 4 | Altitude | égale à `ELEVATION(i)` à 0,2 m près (échelle 5) |
| 5 | Champ absent (`NaN`) | champ omis dans le record relu, pas à 0 |
| 6 | Agrégats du lap | égaux à ceux de `Path` |
| 7 | `sample.gpx` enhancé complet (~1000 points) | fichier relisible, taille plausible |
| 8 | `stelvio.gpx` enhancé | idem |
| 9 | `Path(0)` | erreur explicite, pas un fichier FIT vide et invalide |
| 10 | Même fixture sur JVM, JS et Wasm | mêmes octets (hors `timeCreated`) |

Les tolérances des cas 1, 3 et 4 découlent des échelles du format FIT documentées en g08 : ce
ne sont pas des marges arbitraires, elles doivent être justifiées en commentaire.

### 5. Validation externe — obligatoire

Générer un `.fit` depuis `stelvio.gpx` et **l'importer réellement** dans un outil tiers (Garmin
Connect, un décodeur FIT en ligne, ou l'outil de vérification fourni par le SDK). Un round-trip
qui passe avec le SDK Garmin ne garantit pas qu'un appareil accepte le fichier.

Consigner le résultat dans les Notes de la fiche. Sans cette vérification, la tâche n'est pas
terminée : c'est le seul test qui valide vraiment l'objectif utilisateur.

## Outputs

Créés :

- `fit/src/commonMain/…/fit/PathToFit.kt`
- `fit/src/commonTest/…/fit/PathToFitTest.kt`
- `fit/src/jvmTest/…/fit/FitRoundTripTest.kt` (round-trip via le SDK Java)
- `fit/src/jsTest/…/fit/FitRoundTripJsTest.kt`

Modifiés :

- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt` (`pathToFit`)
- `README.md` (mention de l'export FIT)

## Validation

```bash
./gradlew :fit:allTests
./gradlew check
./gradlew ktlintCheck
```

Plus la validation externe de l'étape 5.

## Done when

- [ ] `Path.toFitCourse` en commonMain, correspondance des champs documentée
- [ ] Champs `NaN` omis et non encodés à zéro
- [ ] Agrégats du lap repris de `Path`, pas recalculés
- [ ] `pathToFit` exporté en JS et Wasm, comportement du `ByteArray` documenté par cible
- [ ] ≥ 10 tests de round-trip verts
- [ ] **Fichier `.fit` importé avec succès dans un outil tiers**, résultat consigné
- [ ] `./gradlew check` et `ktlintCheck` verts

## Notes

- **La validation externe n'est pas optionnelle.** Le format FIT est plein de contraintes que
  le SDK accepte en écriture mais qu'un appareil refuse (ordre des messages, champs
  obligatoires du `FileIdMesg`, type de fichier).
- **Course vs Activity** : gpx2web écrit un fichier de type Course (parcours à suivre). C'est
  le bon choix pour une trace virtualisée. Un fichier Activity (sortie enregistrée) aurait une
  structure différente et n'est pas dans le périmètre.
- **Précision des semicercles** : `2³¹/180` semicercles par degré, soit ~1,2 cm à l'équateur.
  La tolérance de 1e-5 ° du cas 1 est largement au-dessus — c'est volontaire, elle couvre aussi
  la conversion radians → degrés.
- **`Path(0)`** doit lever, pas produire un fichier vide : un `.fit` sans record est accepté
  par le SDK mais rejeté par les plateformes.
