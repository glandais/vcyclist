# api — une API HTTP Ktor, septième porte du moteur

## Goal

Exposer **toutes les capacités atteignables du moteur** par HTTP, dans un module Gradle neuf
`:api` (JVM seul, Ktor), avec un contrat **OpenAPI 3.1 engendré depuis le code**, versionné dans le
dépôt et vérifié par le build.

L'API est une **porte** au sens de [`surface-coverage.md`](../ledgers/surface-coverage.md), au même
titre que le CLI, JS, WASI, Java et la démo. Elle hérite donc de toutes leurs règles :

- **mêmes noms de clés JSON que les DTO JS/WASI**, pour que les portes restent alignées ;
- **les défauts sont lus**, jamais écrits : `EngineConstants`, les objets d'options des étapes
  (`SimplifyPathOptions()`, `CurvatureOptions.DEFAULT`, `RacingLineOptions.DEFAULT`,
  `ClimbOptions.DEFAULT`, `CsvOptions()`, `JsonOptions()`, `ElevationDefaults`…) ;
- **une clé inconnue est une erreur**, renvoyée avant tout calcul ;
- **une clé acceptée doit être une clé utilisée** : chaque option a un test comportemental qui la
  voit arriver dans l'objet du cœur ;
- **un fournisseur de puissance avec état par requête**.

L'API est **sans état** : chaque requête porte son GPX, rien n'est stocké côté serveur. C'est le
choix qui évite d'avoir à concevoir un cycle de vie, une éviction et une authentification pour des
ressources dont personne n'a demandé la persistance.

## Depends on

- `:engine` (qui apporte `:gpx`, `:elevation` et `:fit` par `api`) et `:map`.
  `:map` dit « rien ne peut dépendre de moi » ; la règle vise les **bibliothèques**. `:cli`, une
  application, en dépend déjà ; `:api` est une application au même titre. **Rien ne peut dépendre
  de `:api`** non plus : c'est un exécutable, pas une bibliothèque, et il n'est pas publié sur Maven
  Central.
- `:codegen` en **`testImplementation` uniquement** : les tests de parité de `:api` lisent
  `OptionCatalog` par réflexion (étape A7). `:codegen` ne dépend pas de `:api`, il n'y a pas de cycle.
- [`docs/tasks/surface-alignment.md`](surface-alignment.md) — `OptionCatalog`, `DoorParityTest`,
  `SurfaceLedger`, l'idiome des tests de parité.
- `RoadCondition?.applyTo(cyclist)` (S8 livré) — **le préréglage gagne**, partout. Le
  paragraphe de CLAUDE.md qui parle d'une préséance divergente « jusqu'à S8 » est périmé.

## Inputs

### Faits du dépôt dont la conception dépend

| Fait | Valeur |
|---|---|
| Gradle | wrapper 9.8.0 |
| Kotlin | 2.4.20 (final) dans `gradle/libs.versions.toml` ; `jvmToolchain(21)` partout |
| kotlinx-serialization | **absent** du catalogue de versions (seul `xmlutil` l'utilise, via sa propre dépendance) |
| Ktor | **absent** |
| Gabarit d'application JVM | `cli/build.gradle.kts` : `kotlin.jvm`, pas de plugin `application`, une tâche `JavaExec` `run` (`-Pargs`), `generateVersionProperties`, `executableJar` |
| ktlint | appliqué à tout projet sauf `demo` par `allprojects` — `:api` est linté d'office |
| Fixtures GPX | `gpx/src/commonTestFixtures/kotlin/io/github/glandais/engine/gpx/GpxFixtures.kt` (`SAMPLE_GPX`, `MULTI_TRACK_GPX`, `MULTI_SEGMENT_GPX`, `WAYPOINTS_GPX`, `NOT_XML_AT_ALL`, …), un `object` Kotlin pur : on l'ajoute comme `srcDir` du test JVM de `:api`, comme le fait `:engine` |
| `Enhancer` | sans état, sûr en concurrence ; fonctions `suspend` appelables directement d'un handler Ktor |
| `ElevationProvider` | cache LRU partagé **dont la sûreté en concurrence n'a pas été auditée** (`Enhancer.kt:61-64`) |
| Fournisseurs à état | `Durability`, `CriticalPower`, `SlewLimited` : `CyclistPowerSpec.toProvider()` **par requête** |
| `Path` | non sérialisable ; on n'en sort que par `CsvWriter`, `JsonWriter`, `GpxWriter`, FIT, ou champ par champ |
| `TileMapProducer` / `SrtmMapProducer` | écrivent un **fichier** PNG ; `TileMapProducer` n'a **aucune** URL de tuiles par défaut |
| `SrtmMapProducer.createSrtmMap(maxSize = 1024)` | littéral dans la signature, **sans constante** : l'étape A6 l'extrait en `DEFAULT_MAX_SIZE` |

### Les défauts de porte de l'API

Chaque porte fil choisit ses propres défauts de pipeline et les écrit **en un seul endroit**. JS et
WASI s'écartent du cœur sur trois champs (`fixElevation=false`, `computeOnePointPerSecond=false`,
`simplifyPath.enabled=false`) ; le CLI sur un seul (`fixElevation=false`).

**L'API prend exactement les défauts de JS et WASI.** Raisons : un appelant qui passe de la façade
JS à l'API doit obtenir la même trace pour le même JSON ; `fixElevation=false` garde la réponse
déterministe et sans réseau tant qu'on ne demande pas le MNT. Le site unique est
`HttpDefaults.enhance` :

```kotlin
object HttpDefaults {
    val enhance: EnhanceOptions =
        EnhanceOptions.DEFAULT.copy(
            fixElevation = false,
            computeOnePointPerSecond = false,
            simplifyPath = EnhanceOptions.DEFAULT.simplifyPath.copy(enabled = false),
        )
    val cyclist: Cyclist get() = Cyclist()
    val bike: Bike get() = Bike()
    val power: CyclistPowerSpec get() = CyclistPowerSpec()
    val climb: ClimbOptions get() = ClimbOptions.DEFAULT
    val csv: CsvOptions get() = CsvOptions()
    val json: JsonOptions get() = JsonOptions()
    val gpxPowerSource: GpxPowerSource get() = GpxPowerSource.DEFAULT
    val elevationSource: ElevationSource get() = ElevationSource.DEFAULT
    /** Le seul défaut que le cœur ne porte pas : JS et WASI l'écrivent chacun en littéral. */
    const val TRACK_NAME: String = "virtualized"
}
```

Tous les lecteurs (`toEnhanceOptions`, `toCyclist`, …) se lient à **ce** site
(`val d = HttpDefaults.enhance`), et `GET /api/v1/defaults` le publie par les mappeurs inverses.

### Bibliothèques (versions exactes)

| Bibliothèque | Coordonnées | Version | Pourquoi |
|---|---|---|---|
| Ktor serveur | `io.ktor:ktor-server-core`, `-cio`, `-content-negotiation`, `-status-pages`, `-cors`, `-call-logging`, `-call-id` | **3.6.0** | Moteur **CIO** plutôt que Netty : moins de dépendances dans le jar autonome, pas de natif, HTTP/1.1 suffit |
| Sérialisation Ktor | `io.ktor:ktor-serialization-kotlinx-json` | 3.6.0 | |
| OpenAPI (exécution) | `io.ktor:ktor-server-routing-openapi` | 3.6.0 | DSL `Route.describe {}` / `hide()`, schémas inférés des descripteurs kotlinx ; **sans** plugin de compilation |
| Swagger UI | `io.ktor:ktor-server-swagger` | 3.6.0 | `swaggerUI(path) { source = OpenApiDocSource.Routing(…) }` |
| Tests | `io.ktor:ktor-server-test-host`, `io.ktor:ktor-client-content-negotiation` | 3.6.0 | `testApplication {}` |
| JSON | `org.jetbrains.kotlinx:kotlinx-serialization-json` | **1.11.0** | la version contre laquelle Ktor 3.6.0 est construit |
| Plugin | `org.jetbrains.kotlin.plugin.serialization` | `version.ref = "kotlin"` (2.4.20) | |
| Journalisation | `ch.qos.logback:logback-classic` | **1.6.5** | `runtimeOnly` |

**Écartés, avec la raison :**

- **Le plugin Gradle Ktor (`io.ktor.plugin`) et son extension de compilateur** :
  `ktor-compiler-plugin:3.6.0` est compilé contre `kotlin-compiler:2.4.0`, et une extension de
  compilateur n'a pas d'ABI stable d'une version `.20` à l'autre. Le gain (des `describe` déduits du
  KDoc) ne paie pas ce risque.
- **`ktor-server-openapi`** : il tire `swagger-codegen` et `swagger-parser` à l'exécution pour
  rendre une page statique.
- **`io.github.smiley4:ktor-openapi` 5.7.0** : mature, DSL plus riche, mais construit contre Ktor
  3.4.2, en retard, et lourd (jackson, swagger-parser, réflexion). C'est le **repli** si le DSL natif
  s'avère trop pauvre — à décider à l'étape A1, pas plus tard.

Les versions ont été relevées sur Maven Central le 2026-10-13. Si l'une ne se résout pas à
l'étape A1, prendre le correctif publié le plus proche **et corriger ce tableau dans le même
commit**.

`describe {}` et `hide()` sont `@ExperimentalKtorApi` : opt-in au niveau du module
(`compilerOptions.optIn.add("io.ktor.utils.io.ExperimentalKtorApi")`, nom à vérifier dans le jar).
Une montée de Ktor peut casser ce DSL ; le test de dérive le verra.

### Disposition du module

```
api/
  build.gradle.kts
  README.md
  contract/openapi.yaml                       ← ENGENDRÉ (./gradlew :api:generateOpenApi)
  src/main/resources/logback.xml
  src/main/kotlin/io/github/glandais/api/
    Main.kt                 fun main(args) — lit ApiConfig, démarre CIO
    ApiConfig.kt            data class ApiConfig + fromEnvironment(args, env)
    ApiDependencies.kt      ce que les tests remplacent : ElevationGateway, TileFetcher
    Application.kt          fun Application.module(config, deps) — plugins + routes
    ApiVersion.kt           const API_VERSION = "1.0.0" ; version du moteur lue en ressource
    http/
      Problem.kt            @Serializable Problem (RFC 9457) + ApiProblem (exception typée)
      StatusPagesConfig.kt  exception → problem+json
      StrictJson.kt         décodage strict : clés inconnues listées avec leur pointeur JSON
      QueryParams.kt        paramètres de requête autorisés par route ; inconnu → 400
      GpxInput.kt           multipart (parts « gpx » + « request ») ou corps application/gpx+xml brut
      BodyLimit.kt          lecture bornée du corps → 413
      ComputeGate.kt        sémaphore + délai + Dispatchers.Default pour le travail CPU
      MediaTypes.kt         application/gpx+xml, application/vnd.ant.fit, text/csv, image/png, problem+json
    dto/                    tous @Serializable, @SerialName explicites sur les classes
      MetaDtos.kt  GpxDtos.kt  EnhanceDtos.kt  ExportDtos.kt  AnalysisDtos.kt
      ElevationDtos.kt  MapDtos.kt  DefaultsDtos.kt
    mapping/
      HttpDefaults.kt       LE site de défauts de la porte (voir plus haut)
      EnhanceMapping.kt     EnhanceOptionsDto?.toEnhanceOptions(), CyclistDto?.toCyclist(), … et les inverses toDto()
      ExportMapping.kt      CsvOptionsDto?.toCsvOptions(), JsonOptionsDto?, GpxWriteOptionsDto?, FitOptionsDto?
      AnalysisMapping.kt    ClimbOptionsDto?.toClimbOptions(), Climb.toDto(), RacingLineReport.toDto()
      Summaries.kt          Path.toSummary(), Path.toPoints()
    service/
      ElevationGateway.kt   un ElevationProvider par (source, zoom), accès sérialisé par Mutex
      MapService.kt         rend dans un fichier temporaire, renvoie les octets, supprime
    routes/
      MetaRoutes.kt  GpxRoutes.kt  EnhanceRoutes.kt  ExportRoutes.kt
      AnalysisRoutes.kt  ElevationRoutes.kt  MapRoutes.kt
    openapi/
      OpenApiContract.kt    un seul site de rendu : serveur, générateur et test
      GenerateOpenApi.kt    fun main() — écrit contract/openapi.yaml
  src/test/kotlin/io/github/glandais/api/
    ApiTestSupport.kt       testApplication préconfiguré, FakeTiles (MNT plat), helpers multipart
    …RoutesTest.kt par fichier de routes, OpenApiContractTest, HttpDefaultsTest,
    HttpDoorParityTest, HttpForwardingTest, FullPipelineSmokeTest
```

Paquet racine `io.github.glandais.api`.

### Configuration (`ApiConfig`)

Ce sont des réglages **du serveur**, pas des défauts du moteur : ils s'écrivent ici en littéraux, et
nulle part ailleurs.

| Champ | Variable d'env. / argument | Défaut |
|---|---|---|
| `host` | `VCYCLIST_API_HOST` / `--host` | `0.0.0.0` |
| `port` | `VCYCLIST_API_PORT` / `--port` | `8080` |
| `maxBodyBytes` | `VCYCLIST_API_MAX_BODY_BYTES` | 20 MiB |
| `maxTrackPoints` | `VCYCLIST_API_MAX_TRACK_POINTS` | 200 000 (somme sur toutes les traces, après parsing) |
| `maxElevationPoints` | `VCYCLIST_API_MAX_ELEVATION_POINTS` | 10 000 |
| `requestTimeoutMs` | `VCYCLIST_API_REQUEST_TIMEOUT_MS` | 120 000 |
| `computeConcurrency` | `VCYCLIST_API_COMPUTE_CONCURRENCY` | `Runtime.availableProcessors()` |
| `corsOrigins` | `VCYCLIST_API_CORS_ORIGINS` (liste séparée par des virgules, `*` permis) | vide = pas de CORS |
| `mapTileUrlTemplate` | `VCYCLIST_API_MAP_TILE_URL` | `null` = rendu de carte tuilée désactivé (503) |
| `cacheDir` | `VCYCLIST_API_CACHE_DIR` | `${java.io.tmpdir}/vcyclist-api` |

## Conception

### Ressources (toutes sous `/api/v1`)

| Méthode | Chemin | Entrée | Sortie | `operationId` |
|---|---|---|---|---|
| GET | `/health` | — | `HealthDto` | `getHealth` |
| GET | `/version` | — | `VersionDto` | `getVersion` |
| GET | `/catalog` | — | `CatalogDto` (valeurs fil de chaque enum) | `getCatalog` |
| GET | `/fields` | — | `List<FieldDefinitionDto>` (les 44 `PointField`) | `listFields` |
| GET | `/defaults` | — | `DefaultsDto` | `getDefaults` |
| POST | `/gpx/parse` | GPX | `ParsedGpxDto` | `parseGpx` |
| POST | `/enhance` | GPX + `EnhanceRequest` | résumé JSON, GPX, FIT, CSV ou JSON colonnes | `enhance` |
| POST | `/export` | GPX + `ExportRequest` | GPX, FIT, CSV ou JSON colonnes (sans simulation) | `export` |
| POST | `/analysis/climbs` | GPX + `ClimbRequest` | `ClimbsResponse` | `detectClimbs` |
| POST | `/analysis/racing-line` | GPX + `RacingLineRequest` | `RacingLineResponse` | `analyzeRacingLine` |
| POST | `/analysis/headwind` | GPX | `HeadwindResponse` | `dominantHeadwind` |
| GET | `/elevation/sources` | — | `List<ElevationSourceDto>` | `listElevationSources` |
| GET | `/elevation/point` | `lat`, `lon`, `source?`, `zoomLevel?`, `interpolation?` | `ElevationPointResponse` | `getElevation` |
| POST | `/elevation/points` | `ElevationPointsRequest` (JSON) | `ElevationPointsResponse` | `getElevations` |
| POST | `/elevation/along` | `ElevationAlongRequest` (JSON) | `ElevationPointsResponse` | `getElevationsAlong` |
| POST | `/map/tiles` | GPX + `TileMapRequest` | `image/png` | `renderTileMap` |
| POST | `/map/terrain` | GPX + `TerrainMapRequest` | `image/png` | `renderTerrainMap` |

Hors contrat (`hide()`) : `GET /api/v1/openapi.yaml`, `GET /api/v1/openapi.json`, `GET /swagger`.

Soit **17 opérations** à la fin de l'étape A6 ; le test de dérive le vérifie (voir plus bas).

### Entrée GPX (commune à toute route marquée « GPX »)

Deux formes, négociées par `Content-Type` :

1. **`multipart/form-data`** — part `gpx` (fichier, **obligatoire**) et part `request`
   (`application/json`, facultative, le DTO de la route). Toute autre part → 400 `unknown-key`.
2. **`application/gpx+xml`** (ou `application/xml`, `text/xml`) — le corps est le GPX, la requête
   prend ses défauts. Les options passent alors par… rien : qui veut des options utilise le
   multipart. Une seule façon de dire chaque chose.

Tout autre type → 415 `unsupported-media-type`.

Paramètres de requête communs, **et les seuls permis** sur ces routes (plus ceux propres à la route,
ex. `format`) ; un paramètre inconnu → 400 `unknown-key` :

| Paramètre | Valeurs | Défaut | Effet |
|---|---|---|---|
| `kinds` | `all`, `tracks`, `routes` | `all` | filtre `GpxPathKind` |
| `split` | `tracks`, `segments` | `tracks` | `tracksAsPaths` ou `segmentsAsPaths` |
| `trackIndex` | entier ≥ 0 | absent = toutes les traces | n'en garder qu'une ; hors bornes → 422 |
| `repair` | `true`, `false` | `true` | `GpxParser.parse(xml, repairOnFailure)` ; les réparations sont renvoyées dans `warnings` (via `GpxXmlRepair.repairVerbose`) |

Aucune trace avec au moins un point → 422 `empty-gpx`. Plus de `maxTrackPoints` points au total →
413 `too-many-points`.

### DTO d'entrée — mêmes clés que JS/WASI

Tous les champs sont **nullables avec `null` par défaut** (absent = défaut de `HttpDefaults`), comme
WASI et contrairement à JS dont les champs numériques de `CyclistDto` sont obligatoires.

```kotlin
@Serializable @SerialName("EnhanceOptions")
data class EnhanceOptionsDto(            // 20 clés, = ENHANCE_OPTIONS_KEYS (JS) = ENHANCE_KEYS (WASI)
    val fixElevation: Boolean? = null, val computeMaxSpeeds: Boolean? = null,
    val virtualizeTrack: Boolean? = null, val computeOnePointPerSecond: Boolean? = null,
    val simplifyEnabled: Boolean? = null, val simplifyToleranceM: Double? = null,
    val simplifyZExaggeration: Double? = null,
    val wPrimeBalanceEnabled: Boolean? = null, val wPrimeBalanceCriticalPower: Double? = null,
    val wPrimeBalanceWPrime: Double? = null,
    val curvatureEnabled: Boolean? = null, val racingLineEnabled: Boolean? = null,
    val racingLineCorridor: String? = null,          // CorridorMode.byId
    val racingLineRoadWidthM: Double? = null,
    val elevationGainPreset: String? = null,         // ElevationGainPreset.byId ; prend son seuil…
    val elevationGainThresholdM: Double? = null,     // …sauf seuil explicite
    val elevationGainEnabled: Boolean? = null, val elevationSmoothWindowM: Double? = null,
    val demZoom: Int? = null,                        // 0..15, configure le fournisseur, pas EnhanceOptions
    val demSource: String? = null,                   // ElevationSource.byId
)
@Serializable @SerialName("Cyclist")       data class CyclistDto(massKg, cd, frontalAreaM2, maxLeanAngleDeg, maxBrakeG, maxSpeedKmH: Double?, roadCondition: String?)
@Serializable @SerialName("Bike")          data class BikeDto(crr, inertiaFront, inertiaRear, wheelRadiusM, efficiency, maxPedalingLeanAngleDeg: Double?)
@Serializable @SerialName("Wind")          data class WindDto(windSpeed: Double?, windDirection: Double?)   // m/s ; degrés, 0 = N, direction VERS laquelle souffle le vent
@Serializable @SerialName("PowerProvider") data class PowerProviderDto(type: String?, power: Double?, useHarmonics: Boolean?, criticalPower: Double?, wPrime: Double?, pacing: Boolean?, maxSlewWPerS: Double?)

@Serializable @SerialName("EnhanceRequest")
data class EnhanceRequest(                // l'enveloppe de vcEnhanceWithCourse, plus la sortie
    val options: EnhanceOptionsDto? = null, val cyclist: CyclistDto? = null, val bike: BikeDto? = null,
    val wind: WindDto? = null, val power: PowerProviderDto? = null,
    val roadWidthOverrideM: Double? = null,   // comme --road-width du CLI : écrit sur CHAQUE point, bat OSM et <roadwidth>
    val output: OutputDto? = null,
)
@Serializable @SerialName("Output")
data class OutputDto(val gpx: GpxWriteOptionsDto? = null, val csv: CsvOptionsDto? = null,
                     val json: JsonOptionsDto? = null, val fit: FitOptionsDto? = null)
@Serializable @SerialName("ExportRequest") data class ExportRequest(val output: OutputDto? = null)

@Serializable @SerialName("GpxWriteOptions") data class GpxWriteOptionsDto(writeExtensions: Boolean?, startTimeEpochMs: Long?, trackName: String?, trackNames: List<String>?, powerSource: String?, includeWaypoints: Boolean?)
@Serializable @SerialName("CsvOptions")  data class CsvOptionsDto(separator: String?, unitsInHeader: Boolean?, decimals: Int?, lineSeparator: String?, fields: List<String>?)
@Serializable @SerialName("JsonOptions") data class JsonOptionsDto(pretty: Boolean?, decimals: Int?, includeMeta: Boolean?, fields: List<String>?)
@Serializable @SerialName("FitOptions")  data class FitOptionsDto(name: String?, startTimeEpochMs: Long?, interPathGapMs: Long?)
@Serializable @SerialName("ClimbOptions") data class ClimbOptionsDto(minMinClimbElevationM, maxMinClimbElevationM, minClimbElevationRatio, minGradePercent, maxDiffRealGrade, booster: Double?, maxAnalysisPoints: Int?)
@Serializable @SerialName("ClimbRequest") data class ClimbRequest(val options: ClimbOptionsDto? = null)
@Serializable @SerialName("RacingLineRequest") data class RacingLineRequest(val options: EnhanceOptionsDto? = null)  // ne lit que racingLine* — comme analyzeRacingLine en JS
```

Règles de lecture (toutes reprises des portes existantes, **pas réinventées**) :

- **Préséance du préréglage** : `RoadCondition.fromWire(roadCondition)` (valeur inconnue → 400
  `invalid-option`), puis `applyTo(cyclist)`. Quand `roadCondition` **et** `maxLeanAngleDeg` ou
  `maxBrakeG` sont donnés, la réponse porte un avertissement (`warnings`), sur le modèle de
  `roadConditionWarning` du CLI. Jamais de règle « l'explicite gagne ».
- **Préréglage de dénivelé** : nommer un préréglage prend son seuil, un `elevationGainThresholdM`
  explicite l'emporte (`EngineJsApi.kt:622-633`).
- **Vent** : degrés → radians (`· π / 180`) ; objet absent → `WindProviderNone`.
- **Puissance** : `type` absent → `HttpDefaults.power.model` ; `PowerModel.fromIdOrNull`, inconnu → 400.
  `from_data` rejoue `pInputPower` (comme `--gpx-power`).
- **Physique** : `CoursePhysics(Course(path, cyclist, bike), RhoProviderEstimate,
  AeroProviderConstant, wind, spec.toProvider())` puis `Enhancer.enhanceCourse(course, options,
  provider)`, **une fois par trace**, avec **un `toProvider()` neuf par trace** (les décorateurs
  ont un état indexé par `pointIndex`).
- **MNT** : un `ElevationProvider` n'est construit que si `fixElevation` est vrai, par
  `ElevationGateway.provider(demSource, demZoom)`.
- `CsvOptionsDto.separator` : chaîne de **longueur 1** exactement, sinon 400 (WASI prend le premier
  caractère en silence ; ici on refuse).
- `fields` : noms `PointField.prop` résolus par `PointField.byProp` ; inconnu → 400. C'est la
  première porte où `CsvOptions.fields` / `JsonOptions.fields` passent le fil (l'entrée `CoreOnly`
  du catalogue l'appelait de ses vœux : « en ajoutant une liste de noms aux lecteurs »).
- `FitOptionsDto.startTimeEpochMs` **obligatoire** pour `format=fit` (FIT n'a pas d'horloge
  relative) → sinon 422 `missing-start-time`.
- `GpxWriteOptionsDto` : `trackName` donne `trackName`, `trackName-2`… quand il y a plusieurs traces
  et pas de `trackNames` ; `trackNames` doit avoir la taille du nombre de traces (sinon 422) ;
  `includeWaypoints` (défaut `true`) recopie les `<wpt>` du document d'entrée — la leçon F2 ;
  `powerSource` par `GpxPowerSource.fromWire(lowercase)`. Écriture via `pathsToGpxDocument` +
  `GpxWriter.write(document, writeExtensions)`, seule voie qui laisse choisir `powerSource`.

### Sortie de `/enhance` et `/export` : négociation du format

Le format se résout ainsi : paramètre de requête `format` s'il est présent, sinon l'en-tête
`Accept`, sinon `summary` (et `summary` n'est pas permis sur `/export`, dont le défaut est `gpx`).

| `format` | `Accept` | Media type de réponse | Producteur |
|---|---|---|---|
| `summary` | `application/json` | `application/json` | `EnhanceResponse` |
| `gpx` | `application/gpx+xml` | `application/gpx+xml` | `pathsToGpxDocument` + `GpxWriter` |
| `fit` | `application/vnd.ant.fit` | `application/vnd.ant.fit` | `List<Path>.toFitBytes(name, startTime, interPathGap)` |
| `csv` | `text/csv` | `text/csv; charset=utf-8` | `CsvWriter.write` |
| `json` | `application/vnd.vcyclist.columns+json` | `application/json` | `JsonWriter.write` |

Les sorties binaires et textuelles portent `Content-Disposition: attachment; filename="<nom>.<ext>"`.
`Accept` sans format reconnu → 406 `not-acceptable`.

**CSV et JSON colonnes ne portent qu'une trace** : avec plusieurs traces et sans `trackIndex`, la
réponse est 422 `multiple-tracks` — jamais la première trace en silence (la leçon F3).

Seul le sous-objet de `output` qui correspond au format est lu ; en fournir un autre est une clé
acceptée et pas utilisée, donc 400 `invalid-option` (« `output.csv` given but format is `gpx` »).

`EnhanceResponse` (format `summary`) :

```kotlin
@Serializable @SerialName("EnhanceResponse")
data class EnhanceResponse(val tracks: List<TrackResultDto>, val warnings: List<String>)
@Serializable @SerialName("TrackResult")
data class TrackResultDto(val name: String?, val summary: PathSummaryDto,
                          val dominantHeadwindAzimuth: Double?, val points: List<PointDto>?)  // points si ?points=true
@Serializable @SerialName("PathSummary")
data class PathSummaryDto(size: Int, totalDistance: Double, durationMs: Double?, minElevation: Double?,
    maxElevation: Double?, elevationGain: Double, elevationLoss: Double, elevationGainFiltered: Double?,
    elevationLossFiltered: Double?, reportedElevationGain: Double, reportedElevationLoss: Double,
    bounds: BoundsDto?)                                        // BoundsDto en DEGRÉS
@Serializable @SerialName("Point")
data class PointDto(latitudeDeg, longitudeDeg, elevation, timeMs, speed, pComputedPower, distance, grade: Double?)  // = WasiJsonOutput:22-34
```

Tout `NaN` devient `null` (`Double.finiteOrNull()`) : kotlinx refuse `NaN`, et c'est la convention
de l'ABI WASI.

### Autres réponses

- `ParsedGpxDto(name, startTimeEpochMs?, waypoints: List<WaypointDto>, paths: List<ParsedPathDto(name?, kind, summary, points?)>, warnings)`.
- `ClimbsResponse(tracks: List<List<ClimbDto>>)` — `ClimbDto` / `ClimbPartDto` = `WasiJsonOutput.kt:77-103`.
- `RacingLineResponse(tracks: List<RacingLineReportDto?>)` — `null` quand `RacingLine.analyze` rend
  `null` ; = `WasiJsonOutput.kt:111-137` ; `converged` est **dans** le DTO (la leçon F4).
- `HeadwindResponse(overall: Double?, tracks: List<Double?>)` — `dominantHeadwindAzimuthDeg()`.
- `ElevationPointResponse(latitude, longitude, elevation, source, zoomLevel, attribution: List<AttributionDto>)` ;
  **l'attribution accompagne toute réponse qui contient une altitude du MNT** (licence Etalab /
  Mapterhorn), y compris celle du repli.
- `ElevationPointsRequest(points: List<LatLonDto>, source?, zoomLevel?, interpolation?)` ;
  `ElevationAlongRequest(points, source?, zoomLevel?, step?, minDistance?, interpolation?, smoothingOptions?: {enabled?, windowSize?}, filterOptions?: {enabled?, tolerance?, zExaggeration?})`
  — mêmes clés que `getElevationsAlong` en JS. `tileUrlTemplate`, `tileSize`, `cacheSize` ne
  passent **pas** : un serveur qui va chercher l'URL que le client lui donne est une SSRF. Écart
  assumé, écrit dans le registre.
- `TileMapRequest(margin?, maxSize?, width?, height?, zoom?, colors?: List<String> "#rrggbb")` ;
  un seul mode de cadrage, comme `ExportCommand` ; l'URL de tuiles est **celle du serveur**
  (`mapTileUrlTemplate`), jamais celle du client. `TerrainMapRequest(maxSize?, margin?, source?, zoomLevel?)`.
- `DefaultsDto(enhance, cyclist, bike, power, climb, csv, json, gpx, fit, elevation, map, limits)` —
  chaque membre est **rempli par le mappeur inverse** (`HttpDefaults.enhance.toDto()`…), jamais à la
  main. `CatalogDto` est rempli par `PowerModel.ids`, `RoadCondition.wireNames`,
  `CorridorMode.entries`, `ElevationGainPreset.entries`, `GpxPowerSource.wireNames`,
  `ElevationSource.ids`, `CornerKind.entries`.

Les schémas OpenAPI des DTO n'ont **pas** de `default:` : les champs sont nullables pour distinguer
« absent » de « donné », et le descripteur kotlinx ne porte pas les valeurs par défaut. Les défauts
sont publiés par `GET /api/v1/defaults`, dont le schéma, lui, est dans le contrat. Chaque description
de champ renvoie à ce point d'entrée.

### Modèle d'erreur — RFC 9457

Toute erreur est `application/problem+json` :

```json
{ "type": "urn:vcyclist:problem:unknown-key", "title": "Unknown key", "status": 400,
  "detail": "Unknown key(s) in request body", "instance": "/api/v1/enhance",
  "unknownKeys": ["/options/simplifyTolerance"], "errors": [] }
```

`@Serializable data class Problem(type, title, status, detail?, instance?, unknownKeys: List<String>? , errors: List<FieldError>?)`,
`FieldError(pointer, message)`. Une exception `ApiProblem(kind, detail, …)` porte le cas ; un seul
`StatusPages` la traduit.

| `type` (slug) | Statut | Quand |
|---|---|---|
| `invalid-body` | 400 | JSON mal formé, type JSON faux |
| `unknown-key` | 400 | clé JSON, part multipart ou paramètre de requête inconnus — **toutes** listées par pointeur, avant tout calcul |
| `invalid-option` | 400 | valeur hors domaine : enum fil inconnu, `require` d'un `init` du cœur (`IllegalArgumentException` levée par la construction des options), `separator` de longueur ≠ 1, sous-objet `output` qui ne correspond pas au format |
| `invalid-gpx` | 400 | `GpxParser.parse` lève |
| `not-found` / `method-not-allowed` | 404 / 405 | |
| `not-acceptable` | 406 | `Accept` sans format servi |
| `payload-too-large` | 413 | corps > `maxBodyBytes` (Content-Length **ou** flux lu borné) |
| `too-many-points` | 413 | > `maxTrackPoints` / `maxElevationPoints` |
| `unsupported-media-type` | 415 | |
| `empty-gpx`, `multiple-tracks`, `missing-start-time`, `track-index-out-of-range`, `track-names-mismatch` | 422 | requête bien formée, sémantiquement impossible |
| `dem-unavailable` | 502 | `TileFetchException` sans `retryAfterSeconds` |
| `dem-rate-limited` | 503 + `Retry-After` | `TileFetchException` avec `retryAfterSeconds` |
| `busy` / `timeout` | 503 | `ComputeGate` : file pleine / délai dépassé |
| `map-tiles-unconfigured` | 503 | `/map/tiles` sans `mapTileUrlTemplate` |
| `internal` | 500 | tout le reste ; le détail est journalisé, **pas** renvoyé |

**Les clés sont validées hors du calcul.** `StrictJson` décode d'abord en `JsonElement`, parcourt
le descripteur kotlinx récursivement (objets, listes, nullables) et liste **toutes** les clés
inconnues avec leur pointeur JSON ; puis décode avec un `Json` strict (`ignoreUnknownKeys = false`,
`isLenient = false`, `coerceInputValues = false`). C'est l'équivalent HTTP de « valider les clés
hors de `GlobalScope.promise` » : la 400 part avant que la `ComputeGate` ne soit prise.

### Limites, délais, concurrence

- **Taille** : `Content-Length` > `maxBodyBytes` → 413 immédiat ; sinon le corps est lu par
  `receiveChannel()` avec un plafond `maxBodyBytes + 1` (un corps *chunked* ne passe pas à côté).
  En multipart, `receiveMultipart(formFieldLimit = maxBodyBytes)`.
- **`ComputeGate`** : un `Semaphore(computeConcurrency)` + `withTimeout(requestTimeoutMs)` +
  `withContext(Dispatchers.Default)` autour de tout travail CPU (pipeline, montées, ligne de course,
  cartes). **Honnêteté** : le code du moteur ne consulte pas l'annulation ; le délai borne *la
  réponse*, pas le calcul, qui finit sur son fil. La vraie protection est `maxTrackPoints` (le
  pipeline est linéaire, `ClimbDetector` est O(n²) borné par `maxAnalysisPoints`).
- **MNT** : `ElevationGateway` garde un `ElevationProvider` par `(source, zoomLevel)`, chacun derrière
  un `Mutex` — le cache n'est pas audité pour la concurrence ; on le sérialise plutôt que de le
  dupliquer par requête (ce qui perdrait le cache). Les tests injectent `fetcherFor` (tuile Terrarium
  plate : R=128, G=100, B=0 ⇒ 100 m).
- **Un fournisseur de puissance par trace et par requête**, jamais en champ d'objet.

### OpenAPI engendré par le code

- Chaque route porte un `describe { operationId; summary; description; tag(…); parameters {…};
  requestBody {…}; responses {…} }`. Les corps GPX/FIT/PNG sont décrits par leur `ContentType` sans
  schéma ; les erreurs référencent le schéma `Problem` sous `application/problem+json`.
- `OpenApiContract` est **le seul site de rendu** :
  `OpenApiDocSource.Routing(contentType = …).read(application, OpenApiDoc(info = OpenApiInfo(title =
  "vcyclist API", version = API_VERSION)))`. `API_VERSION` est une **constante** (`"1.0.0"`), pas
  `project.version` : semantic-release change la version à chaque push et rendrait le fichier
  committé perpétuellement périmé. `GET /api/v1/version` donne, lui, la version du moteur.
- Servi par `GET /api/v1/openapi.yaml` / `.json` (cachés), et `swaggerUI("swagger")` dont la source
  est **la même** (`OpenApiDocSource.Routing`, pas le `FirstOf(File, Routing)` par défaut). Swagger UI
  charge ses ressources depuis unpkg : acceptable pour un point de développement, noté.
- `:api:generateOpenApi` (`JavaExec`, `workingDir = rootDir`) démarre un serveur CIO sur le port 0,
  rend, écrit `api/contract/openapi.yaml`, arrête le serveur.
- `OpenApiContractTest` (idiome `TsFacadeTest`) :
  1. rendu `testApplication` == fichier committé, message « stale. Run `./gradlew
     :api:generateOpenApi` — never edit by hand » ;
  2. **contrôle de taille** : `assertEquals(EXPECTED_OPERATIONS, operations.size)` — une constante
     qui augmente à chaque étape ; et chaque route non cachée de l'arbre de routage a une opération
     avec `operationId` et `summary` (Ktor émet un chemin nu pour une route sans `describe`) ;
  3. `GET /api/v1/openapi.yaml` == le même rendu (le servi est le committé) ;
  4. deux rendus successifs sont identiques (ordre déterministe).
  La tâche `test` déclare `inputs.dir("contract")` — **un répertoire**, la règle de CLAUDE.md.

### Parité avec les autres portes (étape A7)

- `OptionCatalog.Door` gagne `HTTP`. `ALL_WIRE` et `WIRE_NO_CLI` l'incluent ; tout ensemble explicite
  est revu. `OptionGroup` gagne `httpDto: String?` — le `@SerialName` du DTO de `:api` qui en est la
  porte. `CsvOptions.fields` et `JsonOptions.fields` passent de `CoreOnly` à
  `Opt("fields", doors = setOf(Door.HTTP))`.
- `DoorParityTest` (`:codegen`) : « un groupe qui revendique HTTP nomme un `httpDto` ».
- `HttpDoorParityTest` (`:api`, `testImplementation(project(":codegen"))`) : pour chaque groupe,
  les `elementNames` du descripteur du DTO nommé == `group.wireNames(Door.HTTP)` ; registre
  `serialName → KSerializer` avec **contrôle de taille** (`assertEquals(OptionCatalog.groups.size, …)`)
  ; et chaque DTO du registre est référencé par un groupe. Par réflexion — pas de regex, aucun
  fichier lu comme texte.
- `HttpForwardingTest` : pour **chaque** option HTTP du catalogue, un JSON qui ne porte que cette
  clé avec une valeur sentinelle (booléen inversé, numérique `défaut + 1` ou valeur valide choisie,
  enum = une autre valeur fil) ; décodage, mappage, puis `OptionGroup.defaultOf`-style résolution du
  `path` sur l'objet produit : la valeur a changé. C'est le test « une clé acceptée est une clé
  utilisée » que CLAUDE.md réclame et qu'aucun contrôle statique ne fait.
- `HttpDefaultsTest` : DTO vide → objet == `HttpDefaults.*` ; `HttpDefaults.enhance` ne diffère de
  `EnhanceOptions.DEFAULT` **que** sur les trois champs nommés ; `GET /defaults` décodé puis remappé
  == `HttpDefaults.*` (le publié est l'appliqué).
- `SurfaceLedger` gagne une colonne **HTTP** ; `:codegen:generateSurfaceLedger` régénère la table.

## Steps

Chaque étape laisse `./gradlew :api:check` vert (et `./gradlew check` à A1 et A7), régénère
`api/contract/openapi.yaml`, met à jour `EXPECTED_OPERATIONS`, et fait **un commit**.

### A1 — Module, configuration, méta, plomberie OpenAPI (4 opérations)

Catalogue de versions, `settings.gradle.kts`, `api/build.gradle.kts` (`run`, `executableJar`
→ `vcyclist-api-<v>-all.jar`, `generateVersionProperties`, `generateOpenApi`, entrées de test),
`ApiConfig`, `ApiDependencies`, `Application.module`, `StatusPages` + `Problem`, `StrictJson`,
`QueryParams`, `BodyLimit`, `ComputeGate`, CORS, journalisation ; `health`, `version`, `catalog`,
`fields` ; `OpenApiContract`, Swagger, générateur, contrat committé, `OpenApiContractTest`.

### A2 — Entrée GPX et `/gpx/parse` (5)

`GpxInput` (multipart / brut / 415 / 413 / parts inconnues), sélection (`kinds`, `split`,
`trackIndex`, `repair`), `Summaries`, `ParsedGpxDto`, `?points=true`.

### A3 — `/enhance` en résumé, `/defaults` (7)

DTO d'entrée, `HttpDefaults`, mappeurs aller et retour, câblage physique par trace,
`ElevationGateway` (pour `fixElevation`), avertissements, `ComputeGate`, fumée pleine chaîne.

### A4 — Formats de sortie et `/export` (8)

Négociation `format`/`Accept`, GPX / FIT / CSV / JSON colonnes, `output.*`, `Content-Disposition`,
règle `multiple-tracks`, `fields`.

### A5 — Analyses et élévation (15)

`/analysis/climbs`, `/analysis/racing-line`, `/analysis/headwind`, `/elevation/sources|point|points|along`,
erreurs MNT → 502/503.

### A6 — Cartes (17)

`SrtmMapProducer.DEFAULT_MAX_SIZE` dans `:map`, `MapService`, `/map/tiles`, `/map/terrain`.

### A7 — Parité, registre, documentation

`Door.HTTP`, `httpDto`, tests de parité/transmission/défauts, colonne du registre, README du
module, CLAUDE.md, `docs/README.md`, `surface-coverage.md`, archivage de cette spec.

## Outputs

- Module `:api` (JVM, Ktor 3.6.0), `./gradlew :api:run`, `./gradlew :api:executableJar`.
- `api/contract/openapi.yaml` engendré, servi, vérifié.
- `api/README.md` ; lignes dans CLAUDE.md (table des modules, commandes) et `docs/README.md`.
- `surface-coverage.md` : septième porte, colonne HTTP dans les deux tables.
- `OptionCatalog.Door.HTTP`, `OptionGroup.httpDto`.

## Validation

```bash
./gradlew :api:check                       # routes, contrat, parité, défauts, transmission
./gradlew :codegen:test                    # DoorParityTest, SurfaceLedgerTest avec la colonne HTTP
./gradlew check                            # tout, toutes cibles
./gradlew :api:generateOpenApi && git diff --exit-code api/contract   # rien à régénérer
./gradlew :api:run -Pargs="--port 8081" &  # puis :
curl -s localhost:8081/api/v1/health
curl -s -X POST -H 'Content-Type: application/gpx+xml' --data-binary @in.gpx 'localhost:8081/api/v1/enhance?format=gpx'
```

## Done when

- Les 17 opérations répondent, chacune avec au moins un test `testApplication` de succès et un
  d'erreur.
- Le contrat committé est égal au rendu, et le test le dit quand il ne l'est plus.
- Chaque option HTTP du catalogue est vue arriver dans l'objet du cœur (`HttpForwardingTest`).
- `GET /defaults` == ce que la porte applique ; aucun défaut du moteur écrit en littéral dans `:api`.
- La fumée pleine chaîne sur `GpxFixtures.SAMPLE_GPX` donne, à 0,5 % près, le même
  `totalDistance` / `durationMs` qu'un appel direct à `Enhancer.enhanceCourse` avec `HttpDefaults`.
- Le registre a sa colonne HTTP et dit honnêtement ce qui ne passe pas.

## Notes

- **Pourquoi sans état.** Un `POST /tracks` + `GET /tracks/{id}/…` serait plus REST, mais impose
  stockage, éviction, quotas et identité. Personne ne l'a demandé ; le coût d'un re-téléversement
  d'un GPX de quelques centaines de Ko est négligeable devant une simulation. Le préfixe `/api/v1`
  laisse la porte ouverte.
- **Pourquoi des DTO dans `:api` plutôt que `@Serializable` sur les options du cœur.** Ajouter
  kotlinx-serialization à `commonMain` toucherait le budget de taille wasm, et les noms fil ne sont
  pas les noms des propriétés (`maxDiffRealGrade` → `maxDiffRealGradeRatio`, `power` → `powerW`).
- **Pourquoi les défauts de JS/WASI** et pas ceux du CLI : voir *Inputs*. Le choix est réversible en
  un seul endroit, `HttpDefaults`.
- **Pas de `tileUrlTemplate` client** (MNT et carte) : SSRF. Le serveur choisit ses sources.
- **Pas de cache disque MNT** au départ : `diskCachedElevationProvider` est `internal` à `:cli`.
  Le factoriser dans `:elevation` (`jvmMain`) est une étape ultérieure si le trafic le justifie.
- **Le délai ne tue pas le calcul** (voir *Limites*). L'interruption coopérative du pipeline serait
  un changement de `:engine`, hors périmètre.
- **`"virtualized"`** est le seul défaut de porte sans constante dans le cœur ; JS et WASI l'écrivent
  chacun en littéral. `HttpDefaults.TRACK_NAME` en est la seule écriture dans `:api` ; une
  constante dans le cœur supprimerait les trois.
- **Constaté à A1.** Le DSL natif suffit : pas de repli smiley4. Le document de base est construit
  par `OpenApiDoc.build { info = … }` et non par le constructeur, parce que Swagger UI construit le
  sien par ce `Builder`, qui émet un `webhooks: {}` vide : c'est la seule façon que `/swagger/openapi.json`
  et `/api/v1/openapi.json` soient identiques octet pour octet. Le contrat committé porte donc
  `webhooks: {}`.
- **`ComputeGate` lance le travail dans sa propre portée et ne fait qu'*attendre* le résultat** sous
  `withTimeout`. Un `withContext(Dispatchers.Default)` autour d'une boucle qui ne suspend jamais ne
  rendrait pas la main avant la fin de la boucle, délai ou pas. Un calcul encore en attente de son
  permis est réellement annulé ; un calcul démarré finit et garde son permis. `busy` reste au
  catalogue des problèmes mais la porte ne le lève pas encore : la file attend, bornée par le délai.
- **`executableJar` fusionne les `META-INF/services`** des dépendances au lieu de garder le premier
  (`DuplicatesStrategy.EXCLUDE` seul perdrait des fournisseurs `ServiceLoader` de Ktor).
- **Constaté à A2.** `GpxInput` porte aussi `kinds: List<GpxPathKind>`, aligné sur `paths` et
  `names` : `tracksAsPaths` / `segmentsAsPaths` perdent le conteneur. La sélection est faite sur le
  `GpxDocument` *avant* conversion (`trackIndex` ne convertit qu'un chemin, et `maxTrackPoints` se
  vérifie sans allouer) ; un segment est converti par `segmentsAsPaths` sur une copie à un seul
  segment, seule voie publique qui applique la largeur de route de la trace. `trackIndex < 0` est
  400 `invalid-option` (hors domaine), au-delà de la sélection 422. Un multipart *chunked* au-delà de
  `formFieldLimit` échoue dans la coroutine du parseur de Ktor, qui annule l'appel par une
  `IOException` nue : aucun `catch` du handler ne la voit, c'est `StatusPages` qui la traduit en 413
  (`isBodyLimitExceeded`, reconnue à son message). Un champ DTO nullable de type objet (`bounds`)
  est rendu par Ktor en `$ref` simple, sans `null` : limite du DSL.
- **Constaté à A3.** `receiveGpxInput` est coupé en deux : `receiveGpxUpload` (toutes les
  vérifications sans calcul, décodage strict de `request` compris) puis `GpxUpload.parse` (sous la
  `ComputeGate`). `/enhance` mappe ses options **entre les deux** : un `invalid-option` — enum fil
  inconnu, `require` d'un `init` du cœur, `demZoom` hors `ElevationFunctions.isValidZoomLevel`,
  `roadWidthOverrideM` non fini ou ≤ 0 — part avant même le parsing du GPX. `ComputeGate.admitted`
  compte les calculs admis ; les tests s'en servent pour prouver qu'une 400 n'a rien calculé.
  L'erreur nomme son pointeur dans `errors` (`/options/racingLineCorridor`). Les lecteurs partent de
  `HttpDefaults.enhance` par `copy` des sous-objets, pour que les champs que le fil ne porte pas
  (fenêtres de courbure, réglages de la ligne de course) gardent la valeur du défaut ; nommer un
  préréglage de dénivelé prend son seuil **et** sa fenêtre (`smoothWindowM`), un seuil explicite
  l'emporte. `EnhanceRequest` n'a pas encore `output` : il arrive avec A4 et ses DTO. `DefaultsDto`
  n'a que `enhance`, `cyclist`, `bike`, `power`, `limits` à ce stade ; ses quatre premiers membres
  sont **déclarés nullables** quoique toujours remplis, sans quoi Ktor émet une seconde copie non
  nulle de chaque schéma (`EnhanceOptions2`…) ; `demZoom` y est publié `null` (chaque source garde
  son zoom par défaut — publier 12 l'appliquerait aussi à `ign-lidar-hd`). Le champ de
  `TrackResult` est `dominantHeadwindAzimuth` (degrés). La bibliothèque `:elevation` enveloppe la
  `TileFetchException` dans une `IllegalStateException` : `StatusPages` la cherche dans la chaîne
  des causes. Le verrou de l'`ElevationGateway` est tenu pendant **tout** le pipeline du chemin
  (`Enhancer` ne sépare pas l'étape MNT) : deux `/enhance` avec `fixElevation` sur la même source
  et le même zoom se suivent. Les descriptions de champs passent par `@JsonSchema.Description`.
- Le DSL OpenAPI natif de Ktor est `@ExperimentalKtorApi` ; si une montée de Ktor le casse, le repli
  est smiley4 `ktor-openapi` (voir *Bibliothèques*), sans changer le contrat committé autrement que
  par la régénération.
