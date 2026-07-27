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

- [ ] Module `:fit` créé, inclus, publiable
- [ ] `FitCourse` / `FitRecord` / `FitLap` / `FitUnits` en commonMain
- [ ] `expect object FitEncoder` posé au bon niveau de granularité
- [ ] `actual` JVM adossé à `com.garmin:fit:21.205.0`
- [ ] `actual` JS/Wasm provisoires levant `NotImplementedError`
- [ ] Test JVM de round-trip minimal (encode → décode avec le SDK)
- [ ] Tests d'unités verts × 4 cibles
- [ ] `./gradlew check` et `ktlintCheck` verts

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
