# 34 — Engine `@JsExport` façade : expansion pour la démo Vue

## Goal

Élargir la façade `EngineJsApi.kt` (côté `engine/src/jsMain/`) pour qu'un consommateur JS (la future démo Vue Phase 9) puisse :

1. Construire un `CoursePhysics` complet (cyclist + bike + wind + power provider) via des DTO `external interface` JSON-like.
2. Appeler `enhanceWithCourse(path, cyclistDto?, bikeDto?, windDto?, powerDto?, optionsDto?)` qui renvoie un `Promise<Path>`.
3. Lire **n'importe lequel des 36 champs** d'un `Path` via `getField(path, i, fieldProp: String)` (string-keyed, mirroir du `path.getField(i, pointField)` TS).
4. Énumérer le catalogue de champs via `fieldDefinitions(): Array<FieldDefinitionDto>` (mirroir du TS `FIELD_DEFINITIONS`).

La signature existante `enhance(path, options)` (task 28/33) **reste inchangée** pour préserver la compat npm et les tests d'intégration Node de task 33.

Cible : **Kotlin/JS uniquement** (la démo consomme `jsBrowserProductionLibraryDistribution`). Pas de modification de `wasmJsMain/EngineJsApi.kt` dans cette tâche.

## Depends on

- Task 28 (façade `@JsExport` initiale)
- Task 33 (auto-instanciation `ElevationProvider` quand `fixElevation = true`)
- Task 13 (modèles `Cyclist`, `Bike`, `Course`)
- Task 16-18 (providers `RhoProvider`, `WindProvider`, `AeroProvider`, `CyclistPowerProvider` + variantes)

## Inputs

- `engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt` — façade actuelle à étendre.
- `engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointField.kt` — 36 champs / 14 catégories (`byProp` round-trip déjà disponible).
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/cyclist/*.kt` — `PowerProviderConstant`, `PowerProviderConstantWithTiring`, `PowerProviderFromData`.
- `engine/src/commonMain/kotlin/io/github/glandais/engine/physics/aero/wind/WindProviderConstant.kt`.
- `engine/src/commonMain/kotlin/io/github/glandais/engine/Enhancer.kt` — `enhanceCourse(course, options)` est le point d'entrée à appeler depuis la nouvelle façade.
- `virtual-cyclist/src/types/path/fieldDefinitions.ts` — référence pour la forme du DTO `FieldDefinitionDto`.
- `virtual-cyclist/demo/src/types.ts` (lignes 31-43, 122-144) — référence pour les DTO Cyclist/Bike/Wind/Power et leurs noms de champs.

## Steps

### 1. Ajouter les DTO `external interface`

Dans `engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt`, après `EnhanceOptionsDto` :

```kotlin
external interface CyclistDto {
    val massKg: Double
    val cd: Double
    val frontalAreaM2: Double
    val maxLeanAngleDeg: Double
    val maxBrakeG: Double
    val maxSpeedKmH: Double
}

external interface BikeDto {
    val crr: Double
    val inertiaFront: Double
    val inertiaRear: Double
    val wheelRadiusM: Double
    val efficiency: Double
}

external interface WindDto {
    val windSpeed: Double      // m/s
    val windDirection: Double  // degrees, meteorological convention (0 = North, 90 = East)
}

external interface PowerProviderDto {
    val type: String           // "constant" | "constant_tiring" | "from_data"
    val power: Double?         // W ; required for constant / constant_tiring, ignored for from_data
    val useHarmonics: Boolean?
    val tiringDuration: Double?  // seconds ; only for constant_tiring
}

external interface FieldDefinitionDto {
    val prop: String
    val unit: String
    val shortDescription: String
    val categoryId: String
    val categoryName: String
    val notSelectable: Boolean
    val anglesInRadians: Boolean
}
```

### 2. Helpers de conversion DTO → modèles internes

Toujours dans le même fichier (top-level `private`) :

```kotlin
private fun CyclistDto?.toCyclist(): Cyclist =
    if (this == null) Cyclist()  // defaults (80 kg / 280 W / 0.7 / 0.5 m² / 35° / 100 km/h)
    else Cyclist(
        massKg = massKg,
        powerW = 0.0,  // power vient du PowerProvider, pas du Cyclist (voir notes)
        cd = cd,
        frontalAreaM2 = frontalAreaM2,
        maxLeanAngleDeg = maxLeanAngleDeg,
        maxBrakeG = maxBrakeG,
        maxSpeedKmH = maxSpeedKmH,
    )

private fun BikeDto?.toBike(): Bike =
    if (this == null) Bike()
    else Bike(crr, inertiaFront, inertiaRear, wheelRadiusM, efficiency)

private fun WindDto?.toWindProvider(): WindProvider =
    if (this == null) WindProviderNone
    else WindProviderConstant(windSpeed, windDirection)

private fun PowerProviderDto?.toCyclistPowerProvider(): CyclistPowerProvider {
    if (this == null) return PowerProviderConstant(250.0, useHarmonics = false)
    return when (type) {
        "constant" -> PowerProviderConstant(power ?: 250.0, useHarmonics ?: false)
        "constant_tiring" -> PowerProviderConstantWithTiring(
            power ?: 250.0,
            useHarmonics ?: false,
            tiringDuration ?: 7200.0,
        )
        "from_data" -> PowerProviderFromData
        else -> error("Unknown PowerProviderDto.type: $type")
    }
}
```

⚠ Vérifier les noms exacts des classes (`PowerProviderFromData` vs `powerProviderFromData` singleton — adapter selon ce que `:engine` expose).

### 3. Ajouter les fonctions `@JsExport` neuves

```kotlin
@JsExport
fun enhanceWithCourse(
    path: Path,
    cyclist: CyclistDto?,
    bike: BikeDto?,
    wind: WindDto?,
    power: PowerProviderDto?,
    options: EnhanceOptionsDto?,
): Promise<Path> =
    GlobalScope.promise {
        val opts = options.toEnhanceOptions()
        val provider = if (opts.fixElevation) ElevationProvider() else null
        val course = CoursePhysics(
            course = Course(path, cyclist.toCyclist(), bike.toBike()),
            rhoProvider = IsaRhoProvider,           // confirmer le nom
            aeroProvider = AeroProviderIsvan,        // confirmer
            windProvider = wind.toWindProvider(),
            cyclistPowerProvider = power.toCyclistPowerProvider(),
        )
        Enhancer.enhanceCourse(course, opts, elevationProvider = provider)
    }

@JsExport
fun getField(
    path: Path,
    i: Int,
    fieldProp: String,
): Double {
    val field = PointField.byProp(fieldProp)
        ?: error("Unknown PointField prop: $fieldProp")
    return path.get(i, field)
}

@JsExport
fun fieldDefinitions(): Array<FieldDefinitionDto> =
    PointField.entries.map { f ->
        val o = js("({})")
        o.prop = f.prop
        o.unit = f.unit
        o.shortDescription = f.shortDescription
        o.categoryId = f.category.id            // confirmer : category.id ou category.name.lowercase()
        o.categoryName = f.category.displayName // confirmer
        o.notSelectable = f.notSelectable
        o.anglesInRadians = f.anglesInRadians
        o.unsafeCast<FieldDefinitionDto>()
    }.toTypedArray()
```

⚠ Confirmer les noms exacts dans `PointField.kt` / `PointFieldCategory.kt`. Si une catégorie n'a pas de `displayName`, utiliser le nom de l'enum (`category.name`).

### 4. Tests `jsBrowserTest`

Ajouter à `engine/src/jsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt` (ou créer `EngineJsApiCourseTest.kt` voisin) :

```kotlin
@Test
fun enhanceWithCourseUsesCustomCyclist() = runTest {
    val gpx = SAMPLE_GPX  // fixture commune
    val path = parseGpx(gpx)
    val heavyCyclist = js("({ massKg: 110, cd: 0.9, frontalAreaM2: 0.55, " +
        "maxLeanAngleDeg: 25, maxBrakeG: 0.4, maxSpeedKmH: 60 })")
        .unsafeCast<CyclistDto>()
    val constantPower = js("({ type: 'constant', power: 150, useHarmonics: false })")
        .unsafeCast<PowerProviderDto>()
    val out = enhanceWithCourse(path, heavyCyclist, null, null, constantPower, null).await()
    assertTrue(pathSize(out) > 0)
    // Same path with default cyclist + 280 W should be faster on average.
    val outDefault = enhanceWithCourse(path, null, null, null, null, null).await()
    assertTrue(pathDurationMs(out) > pathDurationMs(outDefault),
        "heavy cyclist with 150 W should be slower than default 250 W")
}

@Test
fun getFieldByPropMatchesPointAt() = runTest {
    val path = parseGpx(SAMPLE_GPX)
    assertEquals(pointAt(path, 5).elevation, getField(path, 5, "elevation"), 1e-9)
    assertEquals(pointAt(path, 5).distance, getField(path, 5, "distance"), 1e-9)
}

@Test
fun fieldDefinitionsExposes36Entries() {
    val defs = fieldDefinitions()
    assertEquals(36, defs.size)
    assertTrue(defs.any { it.prop == "elevation" })
    assertTrue(defs.any { it.prop == "speed" })
}
```

### 5. `.d.ts` à jour

```bash
./gradlew :engine:jsBrowserProductionLibraryDistribution
grep -E "(enhanceWithCourse|getField|fieldDefinitions|CyclistDto|BikeDto|WindDto|PowerProviderDto|FieldDefinitionDto)" \
  engine/build/dist/js/productionLibrary/kotlin/vcyclist-engine.d.ts
```

Doit retourner les types et fonctions ajoutés. Si un type interne (e.g. `Cyclist`) fuit dans la signature `.d.ts`, masquer côté Kotlin (paramètre `CyclistDto?` au lieu de `Cyclist?`).

## Outputs

Modifiés :

- `engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt` (≈ +150 lignes)
- `engine/src/jsTest/kotlin/io/github/glandais/engine/EngineJsApiTest.kt` (+3 tests) **OU** nouveau `EngineJsApiCourseTest.kt`

Inchangés :

- `engine/src/wasmJsMain/...` (la démo n'utilise que la cible JS)
- `engine/src/commonMain/...`
- `wasmJsTest/...`, `jsNodeTest/...`

## Validation

```bash
./gradlew :engine:jsBrowserTest :engine:jsNodeTest :engine:jvmTest
./gradlew :engine:jsBrowserProductionLibraryDistribution
./gradlew ktlintCheck
```

Critères :

- 3 nouveaux tests `jsBrowserTest` verts.
- Régression : tous les tests existants (commonTest × 4 targets, jsTest, wasmJsTest, jvmTest) restent verts.
- `vcyclist-engine.d.ts` contient les 5 nouveaux types DTO et les 3 nouvelles fonctions exportées.
- `ktlintCheck` vert.

## Done when

- [ ] 5 `external interface` (`CyclistDto`, `BikeDto`, `WindDto`, `PowerProviderDto`, `FieldDefinitionDto`) ajoutées
- [ ] 3 `@JsExport fun` (`enhanceWithCourse`, `getField`, `fieldDefinitions`) ajoutées
- [ ] Helpers de conversion DTO → modèles internes implémentés (5 fonctions privées)
- [ ] Tests `jsBrowserTest` : custom cyclist, getField parity vs pointAt, fieldDefinitions count
- [ ] `.d.ts` régénéré et contient les 8 nouveaux symboles
- [ ] `enhance(path, options)` existant inchangé (compat npm/Phase 3)
- [ ] Toutes les checkboxes cochées

## Notes

- **Pourquoi `enhanceWithCourse` vs surcharger `enhance`** : Kotlin/JS ne génère pas de surcharges TypeScript propres pour `@JsExport` ; un nom distinct évite les `.d.ts` ambigus côté consommateur.
- **`Cyclist.powerW` ignoré** : le pipeline TS lit la puissance depuis le `CyclistPowerProvider`, pas depuis `Cyclist.powerW`. On respecte cette séparation : `Cyclist` ne porte que les caractéristiques aéro/mécaniques, et le DTO `PowerProviderDto` porte la stratégie. Passer `powerW = 0.0` côté Kotlin est sans effet (le PowerComputer n'y touche pas) ; si une régression apparaît, ajouter `powerW = power?.power ?: 280.0` dans `toCyclist`.
- **PowerProviderFromData singleton** : si l'engine expose un singleton (`object PowerProviderFromData`), `power?.type == "from_data"` doit le retourner directement. Vérifier la signature.
- **`category.id` vs `category.name`** : `PointFieldCategory` est une enum Kotlin ; `.name` est l'identifiant Kotlin (e.g. `COORDINATES`), souvent différent du `id` TS (`coordinates`). Définir le mapping dans le helper si nécessaire : `val categoryId = category.name.lowercase()`.
- **Pas d'expansion wasmJs dans cette tâche** : la démo cible Kotlin/JS. Si une démo Wasm est désirée plus tard, dupliquer le pattern dans `wasmJsMain/EngineJsApi.kt` avec les contraintes Wasm (`external interface : JsAny`, `@JsFun` pour les littéraux d'objet, `JsReference<Path>` au lieu de Path direct).
