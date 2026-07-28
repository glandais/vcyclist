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

- [x] `Path.toFitCourse` en commonMain, correspondance des champs documentée
- [x] Champs `NaN` omis et non encodés à zéro
- [x] Agrégats du lap repris de `Path`, pas recalculés
- [x] `pathToFit` exporté en JS et Wasm, comportement du `ByteArray` documenté par cible
- [x] ≥ 10 tests de round-trip verts
- [x] **Fichier `.fit` relu par un décodeur tiers indépendant**, résultat consigné — voir la
      réserve ci-dessous : ce n'est pas un import sur appareil ni sur Garmin Connect
- [x] `./gradlew check` et `ktlintCheck` verts

## Résultat

**Champ de puissance retenu : `POWER` (`pComputedPower`).** C'est la puissance que
`VirtualizeService` calcule pour la sortie simulée. `P_INPUT_POWER` est la puissance lue dans le
GPX d'entrée : pour une trace virtualisée elle est soit absente, soit relative à une autre
sortie. Documenté dans le KDoc de `toFitCourse`.

**Deux bugs attrapés par les tests, pas par la relecture :**

1. **Signe de la descente.** `Path.elevationLoss` accumule les deltas négatifs et vaut donc
   -1,6 m ; `total_descent` du format FIT est une magnitude positive. Le premier jet produisait
   un descente négative. gpx2web fait la même négation
   (`setTotalDescent((int) -path.getTotalElevationNegative())`) — cette lecture-là avait été
   faite, mais la conséquence sur le signe pas transposée.
2. Les agrégats `totalAscent`/`totalDescent` sont des entiers : arrondi (`roundToInt`) plutôt
   que troncature, pour qu'un dénivelé de 1499,6 m ne soit pas annoncé à 1499.

**Valeurs absentes.** `Path` est un `DoubleArray` initialisé à `0.0` : « pas de cardio » et
« 0 bpm » sont indistinguables. `NaN` **et** `0.0` exact sont donc traités comme absents pour
les champs capteurs et omis du record — même convention que `GpxFromPath` en écriture GPX. La
position, l'altitude et la distance ne sont pas optionnelles : seul `NaN` les retire.

**Façade JS/Wasm : les deux cibles ne rendent pas le même type**, et c'est documenté par cible.

| Cible | Signature générée | Pourquoi |
|---|---|---|
| Kotlin/JS | `pathToFit(path, name, startTimeEpochMs): Int8Array` | `ByteArray` est exportable et se présente comme un `Int8Array`. |
| Kotlin/Wasm | `pathToFit(handle, name, startTimeEpochMs): NonNullable<unknown>` | `ByteArray` n'est pas un `JsAny`, et `org.khronos.webgl.Uint8Array` est refusé à l'export (« Can't export not-primary constructor »). La valeur rendue **est** un `Uint8Array`, typée `JsAny` faute de mieux. |

**La façade vit dans `:engine`, pas dans `:fit`** — et c'est forcé par la décision g01. `:gpx`
n'est pas un paquet npm séparé (il est inliné dans `@glandais/vcyclist-engine`), donc un `Path`
passé à un `@glandais/vcyclist-fit` bundlé séparément ne serait pas la même classe JS. Un seul
bundle garde les types identiques. **Conséquence à connaître : `@garmin/fitsdk` devient une
dépendance de `@glandais/vcyclist-engine`** (vérifié dans le `package.json` généré).

**Validation externe — faite, avec une réserve à lire.**

Fichier produit : `stelvio.gpx` → pipeline complet `enhance` → `toFitBytes` → 43 records,
1258 octets. Relu avec **`fitdecode` 0.11.0**, une implémentation Python du format FIT
**indépendante de Garmin** (ce n'est pas le SDK), en mode `CrcCheck.RAISE` :

```
clean   : PASSED strict CRC verification, 56 frames
bad CRC : REJECTED -> FitCRCError (un seul bit du CRC inversé)
```

Le contrôle n'est donc pas vide de sens. Contenu relu :

```
messages    : file_id 1, course 1, lap 1, event 2, record 43
file_id.type: course     | manufacturer: dynastream
course      : name='Stelvio descent', sport='cycling'
1er record  : 46.531802 / 10.443940, alt 2626.4 m, dist 0.0, 2026-08-01T08:00:00Z
dernier     : 46.531974 / 10.459061, alt 2584.6 m, dist 3465.7 m, 2026-08-01T08:09:34Z
lap         : dist 3465.7 m, elapsed 574 s, ascent 133 m, descent 174 m
```

Les coordonnées correspondent **exactement** aux premier et dernier points du `stelvio.gpx`
source (46.531802 / 10.44394 et 46.531974 / 10.459061), et la distance et la durée
correspondent à ce que le CLI annonce sur le même fichier (3465,7 m / 574,0 s).

**Réserve, à ne pas masquer :** la fiche demandait un import « dans un outil tiers (Garmin
Connect, un décodeur FIT en ligne…) ». Ce qui a été fait est plus fort qu'un round-trip via le
SDK Garmin — un décodeur écrit indépendamment valide le CRC, l'en-tête, les définitions de
messages et le profil — mais ce **n'est pas** un import sur Garmin Connect ni sur un appareil.
Les deux impliquaient soit la création d'un compte, soit l'envoi du fichier à un service
externe. Le risque résiduel est donc celui que la fiche pointe elle-même : une plateforme peut
exiger des messages ou des champs qu'aucun décodeur ne réclame. **À confirmer par un import
manuel avant de considérer l'export FIT comme livré à un utilisateur final.**

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:fit` = 47 tests JVM (dont 12
`PathToFitTest` et 12 `FitRoundTripTest`), plus les round-trips JS et Wasm.

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


## Validation sur appareil — 2026-07-28

**Fait, et concluant.** Un FIT produit par la CLI (`enhance … --fit`, 1018 points, `sport=cycling`,
type Course) a été chargé sur un appareil Garmin réel et s'y comporte comme un parcours à suivre.

C'est la seule vérification que le dépôt ne peut pas automatiser. Les tests couvrent la
structure — le SDK Garmin décode sur JVM, JS et Wasm, les octets de référence sont committés et
reproduits sur les trois cibles, le round-trip préserve positions et champs capteurs, et un
décodeur tiers indépendant (`fitdecode`, CRC strict) relit le fichier. Aucun de ces chemins ne dit
si l'appareil *navigue* dessus. Maintenant si.

Contrôle de cohérence interne relevé au passage sur le parcours de test : +4467 m / −2848 m pour
un trajet de 350 m à 1969 m, soit exactement les 1619 m d'écart attendus.
