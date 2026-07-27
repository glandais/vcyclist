# g08 — Module `:fit` : bootstrap + implémentation JVM

## Goal

Créer le module KMP `:fit` et son implémentation JVM, adossée au SDK Java officiel
(`com.garmin:fit:21.205.0`, disponible sur Maven Central — gpx2web l'utilise sans dépôt
supplémentaire).

Cette tâche pose **l'interface `expect`**, qui est la décision structurante du module : les
SDK Java et JavaScript de Garmin n'ont aucune API commune, donc le point de découpe doit être
haut niveau.

## Depends on

- `g01` (module `:gpx`, pour `Path`)
- `g05` (`startTime` : le format FIT exige des timestamps absolus)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/io/write/FitFileWriter.java` (canonique, 138 l.)
- `../gpx2web/pom.xml` (coordonnées et version du SDK)
- `elevation/build.gradle.kts` (modèle de module KMP publié avec `expect`/`actual` par cible)
- `docs/kotlin-wasm-jvm-webp.md` (pattern `expect`/`actual` multi-cibles, §6)

## Steps

### 1. Créer le module

`settings.gradle.kts` : ajouter `":fit"`.

`gradle/libs.versions.toml` :

```toml
[versions]
garmin-fit = "21.205.0"

[libraries]
garmin-fit = { module = "com.garmin:fit", version.ref = "garmin-fit" }
```

`fit/build.gradle.kts` : calqué sur `elevation/build.gradle.kts` (4 cibles, publication Maven
Central + npm `@glandais/vcyclist-fit` / `-wasm`), avec :

```kotlin
commonMain.dependencies { api(project(":gpx")) }
jvmMain.dependencies { implementation(libs.garmin.fit) }
```

### 2. L'interface `expect` — décision structurante

Le SDK Java expose `FileEncoder`, `RecordMesg`, `LapMesg`, `CourseMesg`, `FileIdMesg`… Le SDK
JavaScript expose un `Encoder` qui consomme des objets JS de la même forme que la sortie de son
`Decoder`. **Aucun wrapper fin ne se factorise.** Le point de découpe est donc le plus haut
possible :

```kotlin
package io.github.glandais.fit

/**
 * Encodeur FIT. La granularité de l'`expect` est volontairement grossière : les SDK Garmin
 * Java et JavaScript n'exposent aucune abstraction commune, donc seul le contrat
 * « un [FitCourse] entre, un fichier FIT sort » est partagé.
 */
expect object FitEncoder {
    fun encode(course: FitCourse): ByteArray
}
```

Et un modèle intermédiaire **entièrement en commonMain**, qui porte toute la logique de
conversion depuis un `Path` :

```kotlin
/** Représentation neutre d'un fichier FIT de type Course, prête à encoder. */
data class FitCourse(
    val name: String,
    /** Instant du premier record. Obligatoire : le format FIT n'a pas de temps relatif. */
    val startTime: Instant,
    val records: List<FitRecord>,
    val lap: FitLap,
    val sport: FitSport = FitSport.CYCLING,
)

data class FitRecord(
    val timestamp: Instant,
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeM: Double?,
    val distanceM: Double,
    val speedMs: Double?,
    val powerW: Int?,
    val heartRate: Int?,
    val cadence: Int?,
    val temperatureC: Double?,
)

data class FitLap(
    val startTime: Instant,
    val totalElapsedTimeS: Double,
    val totalTimerTimeS: Double,
    val totalDistanceM: Double,
    val totalAscentM: Int,
    val totalDescentM: Int,
)
```

Conséquence : la conversion `Path` → `FitCourse` est écrite **une seule fois**, testée sur les
4 cibles, et chaque `actual` ne fait plus que de la traduction mécanique vers son SDK. C'est
l'objet de g10.

### 3. `actual` JVM

`fit/src/jvmMain/kotlin/io/github/glandais/fit/FitEncoder.jvm.kt`.

Suivre `FitFileWriter.java` pour l'ordre des messages, qui n'est pas libre dans le format FIT :
`FileIdMesg` d'abord, puis `CourseMesg`, `LapMesg`, puis les `RecordMesg`.

Le SDK Java écrit dans un `File` via `FileEncoder`. Comme l'`expect` rend un `ByteArray`,
encoder vers un fichier temporaire puis relire, **ou** utiliser une sortie mémoire si le SDK
l'autorise. Vérifier à la lecture du SDK ; si un `File` est imposé, isoler ce détail et le
documenter.

### 4. Conversions d'unités

Le format FIT a ses propres unités, et c'est la source d'erreur principale :

| Grandeur | Unité FIT |
|---|---|
| Latitude / longitude | **semicercles** : `deg × 2³¹ / 180` |
| Altitude | m, offset +500, échelle 5 (`(m + 500) × 5`) |
| Distance | m, échelle 100 |
| Vitesse | m/s, échelle 1000 |
| Timestamp | secondes depuis **1989-12-31T00:00:00Z** (époque FIT, pas Unix) |

Vérifier si le SDK applique ces échelles lui-même (les setters typés le font en général) ou
s'il faut les appliquer à la main. gpx2web utilise `SemiCirclesConverter` — donc au moins la
conversion en semicercles est explicite de son côté.

Centraliser ces constantes dans `fit/src/commonMain/…/FitUnits.kt`, testé en commonTest : les
deux `actual` doivent produire les mêmes valeurs brutes.

### 5. Test JVM minimal

Encoder un `FitCourse` de 3 records, ré-ouvrir le résultat avec le `Decode` du SDK, vérifier
que les valeurs relues correspondent. Le round-trip complet est en g10 ; ici on valide
seulement que le module s'assemble et produit un fichier lisible.

## Outputs

Créés :

- `fit/build.gradle.kts`
- `fit/src/commonMain/…/fit/{FitEncoder,FitCourse,FitUnits}.kt`
- `fit/src/jvmMain/…/fit/FitEncoder.jvm.kt`
- `fit/src/commonTest/…/fit/FitUnitsTest.kt`
- `fit/src/jvmTest/…/fit/FitEncoderJvmTest.kt`

Modifiés :

- `settings.gradle.kts`, `gradle/libs.versions.toml`
- `README.md` (tableau des modules)

## Validation

```bash
./gradlew :fit:jvmTest
./gradlew :fit:compileKotlinJs :fit:compileKotlinWasmJs   # doivent échouer proprement : actual manquant
./gradlew ktlintCheck
```

À ce stade, seule la cible JVM est fonctionnelle : les `actual` JS et Wasm arrivent en g09.
Pour que `./gradlew check` reste vert entre les deux tâches, fournir des `actual` JS/Wasm qui
lèvent `NotImplementedError` avec un message explicite, et un test qui vérifie cette levée.

## Done when

- [x] Module `:fit` créé, inclus, publiable
- [x] `FitCourse` / `FitRecord` / `FitLap` / `FitUnits` en commonMain
- [x] `expect object FitEncoder` posé au bon niveau de granularité
- [x] `actual` JVM adossé à `com.garmin:fit:21.205.0`
- [x] `actual` JS/Wasm provisoires levant `NotImplementedError`
- [x] Test JVM de round-trip minimal (encode → décode avec le SDK)
- [x] Tests d'unités verts × 4 cibles
- [x] `./gradlew check` et `ktlintCheck` verts

## Résultat

**Le découpage haut niveau tient.** `expect object FitEncoder { fun encode(course: FitCourse):
ByteArray }` a été implémenté côté JVM sans jamais avoir besoin de descendre en granularité : le
`actual` n'est que de la traduction mécanique vers les `Mesg` du SDK. La note « s'arrêter et
rediscuter si ça ne tient pas » n'a pas eu à jouer.

**Pas de fichier temporaire.** La fiche anticipait un encodage via `File` parce que
`FileEncoder` écrit sur disque. Inutile : le SDK expose aussi `BufferEncoder`, dont `close()`
rend directement le `byte[]`. La signature `ByteArray` de l'`expect` est donc honnête sur JVM —
aucun accès au système de fichiers, ce qui compte pour un module qui devra tourner en navigateur.

**Échelles : le SDK Java en fait la moitié.** Vérification faite dans les sources du SDK
(`RecordMesg.java`) : les setters typés prennent des **unités réelles** et appliquent
échelle + offset eux-mêmes (`setAltitude` en m, `setDistance` en m, `setSpeed` en m/s,
`setPower` en W). Seule exception, la position, documentée `Units: semicircles` — d'où le
`SemiCirclesConverter` de gpx2web, et d'où le fait que le `actual` JVM n'appelle que
`FitUnits.degreesToSemicircles`. Les autres constantes sont quand même définies et testées dans
`FitUnits` : le SDK JavaScript (g09) travaille plus près du fil, et un lecteur de dump FIT en a
besoin.

**Époque FIT.** `FitUnits.FIT_EPOCH_OFFSET_MS = 631_065_600_000` correspond exactement à
`com.garmin.fit.DateTime.OFFSET` (constaté dans les sources). Le `actual` JVM passe par
`DateTime(java.util.Date)`, qui applique l'offset lui-même, plutôt que de pré-convertir — les
deux chemins sont comparés dans les tests.

**Encodage déterministe.** `FileIdMesg.timeCreated` est dérivé de `course.startTime` et
`number` du hash du nom, au lieu du `new Date()` de gpx2web. Deux encodages du même `FitCourse`
produisent des octets identiques, sans quoi aucune assertion au niveau octet ne serait possible
en g10. Test dédié.

**Cibles JS/Wasm.** `actual` provisoires levant `NotImplementedError` avec un message qui nomme
g09, plus un test par cible qui vérifie ce message. Ces deux tests échoueront volontairement dès
que g09 rendra l'encodage fonctionnel — c'est le rappel de les supprimer.

**Validation :** `./gradlew check` + `ktlintCheck` verts. `:fit` = 18 tests JVM (11 `FitUnitsTest`
+ 7 `FitEncoderJvmTest`, dont un round-trip complet encode → `FitDecoder` du SDK) et 11 tests sur
chacune des 3 cibles web. Le SDK résout bien depuis Maven Central (`fit-21.205.0.jar` → HTTP 200),
donc aucun dépôt supplémentaire n'est nécessaire.

**Erreur attrapée par les tests.** La première version de `FitUnitsTest` affirmait
`45.680697° → 544892337` semicercles ; la vraie valeur est `544991944`. C'est la constante écrite
à la main qui était fausse, pas le code. Les valeurs de référence sont désormais calculées hors
de ce dépôt et la dérivation est notée en commentaire.

**Reste ouvert pour g19 :** la licence du SDK Garmin et ses conditions de redistribution. `:fit`
déclare bien la publication npm et Maven Central, mais n'est **pas** ajouté au `publishCmd` de
`.releaserc.json` — la question doit être tranchée avant.

## Notes

- **Le niveau de l'`expect` est LA décision de cette tâche.** Un `expect` fin (un wrapper par
  message FIT) obligerait à réimplémenter deux fois la logique de conversion et doublerait la
  surface de bugs. Si l'implémentation révèle que le découpage haut niveau ne tient pas,
  s'arrêter et rediscuter plutôt que de descendre en granularité par défaut.
- **Époque FIT ≠ époque Unix** : 1989-12-31T00:00:00Z, soit 631065600 s d'écart. Une erreur ici
  produit un fichier qui s'importe mais affiche une date en 1989 ou en 2050.
- **Licence du SDK Garmin** : vérifier ses conditions de redistribution avant de publier
  `:fit` sur Maven Central. gpx2web l'utilise déjà, mais gpx2web n'est pas publié sur Maven
  Central — c'est une différence qui peut compter. **À vérifier avant g19.**
- Le SDK JS (`@garmin/fitsdk`) sert en g09 ; ne rien anticiper ici au-delà de la forme de
  l'`expect`.
