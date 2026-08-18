# 10 — Engine : `PointField` (source de vérité des 36 champs)

## Goal

Démarrer la **Phase 2** (module `:engine`) en posant la *single source of truth* du modèle de données du `Path` :

- Un `enum class PointFieldCategory` (14 catégories) regroupant les champs par thème (coordinates, temporal, elevation, power physics, power cyclist, etc.).
- Un `enum class PointField` (**36 champs**) décrivant pour chaque champ : son nom Kotlin (camelCase, ex. `latitude`), son unité (`meters`, `radians`, `watts`, …), sa courte description, sa catégorie, et des flags (`getDegrees`, `notSelectable`).
- `PointField.ordinal` sert d'index dans le `DoubleArray` plat que la tâche 11 fera émerger via codegen ; pas besoin de stocker `index` manuellement.
- Un helper `byProp(prop: String): PointField?` (lookup O(1) via `Map`) pour préparer la sérialisation JSON et le mapping GPX (tâches 14/15).

À noter : le plan parle de "37 champs", mais l'inventaire exhaustif du TS (`fieldDefinitions.ts`) ne contient en réalité **36 champs** (la `CLAUDE.md` du projet TS dit aussi "37 across 12 categories" mais liste 14 catégories — incohérence côté TS). Cette tâche **se cale sur le code TS source**, pas sur sa doc. On corrigera la mention "37" dans `PLAN.md` une fois la tâche commitée.

## Depends on

- `00-bootstrap` (module `:engine` initialisé, source set `commonMain` prêt)
- `08-elevation-provider-batch` — fin de Phase 1, le module `:elevation` est prêt à être déclaré comme dépendance dans `engine/build.gradle.kts` (à faire en début de cette tâche)

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/src/types/path/fieldDefinitions.ts` — **canonical** (lignes 44-389, structure `FIELD_DEFINITIONS: FieldCategory[]`)
- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/CLAUDE.md` — section "Field Categories" pour contexte

Inventaire exhaustif des 36 champs (à reproduire exactement) :

| # | Catégorie (id) | Nom (TS) | Prop Kotlin | Unité | Notes |
|--:|---|---|---|---|---|
| 0 | coordinates | LATITUDE | latitude | radians | `getDegrees=true`, `notSelectable` |
| 1 | coordinates | LONGITUDE | longitude | radians | `getDegrees=true`, `notSelectable` |
| 2 | coordinates | DISTANCE | distance | meters | |
| 3 | coordinates | DX | dx | meters | |
| 4 | temporal | TIME | time | ms | `setSpecial=date`, `getSpecial=date`, `notSelectable` |
| 5 | temporal | ELAPSED | elapsed | ms | |
| 6 | temporal | DT | dt | ms | |
| 7 | angles | BEARING | bearing | radians | |
| 8 | elevation | ELEVATION | elevation | meters | |
| 9 | grade | GRADE | grade | % | |
| 10 | radius | RADIUS | radius | meters | |
| 11 | aero_coef | AERO_COEF | aeroCoef | aero | |
| 12 | cyclist_wind | WIND_BEARING | windBearing | radians | |
| 13 | cyclist_wind | WIND_ALPHA | windAlpha | radians | |
| 14 | power_physics | P_AERO | pAero | watts | |
| 15 | power_physics | P_GRAVITY | pGravity | watts | |
| 16 | power_physics | P_ROLLING_RESISTANCE | pRollingResistance | watts | |
| 17 | power_physics | P_WHEEL_BEARINGS | pWheelBearings | watts | |
| 18 | power_cyclist | P_INPUT_POWER | pInputPower | watts | |
| 19 | power_cyclist | P_CYCLIST_PROVIDED_OPTIMAL_POWER | pCyclistProvidedOptimalPower | watts | |
| 20 | power_cyclist | P_CYCLIST_PROVIDED_OPTIMAL_POWER_HARMONICS | pCyclistProvidedOptimalPowerWithHarmonics | watts | |
| 21 | power_cyclist | P_CYCLIST_PROVIDED_POWER_NEEDED | pCyclistPowerNeeded | watts | |
| 22 | power_cyclist | P_CYCLIST_PROVIDED_MUSCULAR | pCyclistProvidedMuscular | watts | |
| 23 | power_cyclist | P_CYCLIST_PROVIDED_WHEEL | pCyclistProvidedWheel | watts | TS prop `PCyclistProvidedWheel` (P majuscule) — typo TS, normalisée |
| 24 | power_post | P_COMPUTED_TOTAL_POWER | pComputedTotalPower | watts | |
| 25 | power_post | P_COMPUTED_WHEEL_POWER | pComputedWheelPower | watts | |
| 26 | power_post | POWER | pComputedPower | watts | nom ENUM `POWER` vs prop `pComputedPower` |
| 27 | speed | SPEED | speed | m/s | |
| 28 | speed | SPEED_MAX | speedMax | m/s | |
| 29 | speed | SPEED_MAX_INCLINE | speedMaxIncline | m/s | |
| 30 | speed | VIRT_SPEED_CURRENT | virtSpeedCurrent | m/s | |
| 31 | environmental | TEMPERATURE | temperature | celsius | |
| 32 | environmental | WIND_SPEED | windSpeed | m/s | |
| 33 | environmental | WIND_DIRECTION | windDirection | radians | |
| 34 | physiological | HEART_RATE | heartRate | bpm | |
| 35 | physiological | CADENCE | cadence | rpm | |

**Total : 36 fields, 14 catégories.**

## Steps

### 1. Activer la dépendance `:elevation` côté `:engine`

`vcyclist/engine/build.gradle.kts` — décommenter la ligne posée en bootstrap (tâche 00) :

```kotlin
sourceSets {
    commonMain.dependencies {
        implementation(libs.kotlinx.coroutines.core)
        api(project(":elevation"))   // ← décommenter
    }
    // ...
}
```

Vérification : `./gradlew :engine:build` compile sans erreur (et `:elevation` est ajouté au graphe Gradle).

Si cette activation casse quelque chose (collisions de packages, dépendances transitives indésirables), traiter ici plutôt que de la repousser à une tâche ultérieure — c'est le moment naturel.

### 2. `PointFieldCategory.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointFieldCategory.kt` :

```kotlin
package io.github.glandais.engine.path

/**
 * Logical grouping of [PointField]s. Order is canonical (used by the UI to render
 * groups in a stable order) and matches the source TS `fieldDefinitions.ts`.
 *
 * @property id stable identifier matching the TS `id` (e.g. `"power_physics"`)
 * @property displayName human-readable label, may contain emojis (matches TS)
 */
enum class PointFieldCategory(val id: String, val displayName: String) {
    COORDINATES("coordinates", "Coordinates"),
    TEMPORAL("temporal", "Temporal"),
    ANGLES("angles", "Angles"),
    ELEVATION("elevation", "🏔️ Elevation"),  // 🏔️
    GRADE("grade", "📐 Grade"),                     // 📐
    RADIUS("radius", "Radius"),
    AERO_COEF("aero_coef", "Aero coef"),
    CYCLIST_WIND("cyclist_wind", "Cyclist wind"),
    POWER_PHYSICS("power_physics", "⚡ Power Physics"),   // ⚡
    POWER_CYCLIST("power_cyclist", "⚡ Power Cyclist"),
    POWER_POST("power_post", "⚡ Power Post processed"),
    SPEED("speed", "Speed & Motion"),
    ENVIRONMENTAL("environmental", "Environmental"),
    PHYSIOLOGICAL("physiological", "Physiological"),
}
```

Note : `displayName` utilise les escapes Unicode pour garantir une compilation propre sur toutes targets (Wasm/JS échappent parfois les emojis dans les sources non-UTF8). Lisible mais robuste.

### 3. `PointField.kt`

`engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointField.kt` :

```kotlin
package io.github.glandais.engine.path

/**
 * Canonical enumeration of every numeric slot stored per `Path` point.
 *
 * The ordinal of each entry is the field's index into the underlying `DoubleArray`
 * (laid out in task 11). The order **must not** change : it is part of the file format
 * (GPX extensions) and the wire format (future JS/Wasm DTOs).
 *
 * @property prop camelCase property name used in JSON serialization and code generation
 * @property unit physical unit (free-form string, matches TS `unit` field)
 * @property shortDescription one-line human-readable label
 * @property category logical group (for UI / docs)
 * @property notSelectable hidden from generic per-field selection UI (e.g. latitude/time)
 * @property anglesInRadians true if the value is stored in radians and exposing a degrees getter
 *   is recommended (deferred to task 12)
 */
enum class PointField(
    val prop: String,
    val unit: String,
    val shortDescription: String,
    val category: PointFieldCategory,
    val notSelectable: Boolean = false,
    val anglesInRadians: Boolean = false,
) {
    // --- Coordinates ---------------------------------------------------------
    LATITUDE("latitude", "radians", "Latitude (radians)", PointFieldCategory.COORDINATES,
        notSelectable = true, anglesInRadians = true),
    LONGITUDE("longitude", "radians", "Longitude (radians)", PointFieldCategory.COORDINATES,
        notSelectable = true, anglesInRadians = true),
    DISTANCE("distance", "meters", "Distance (meters)", PointFieldCategory.COORDINATES),
    DX("dx", "meters", "dx (meters)", PointFieldCategory.COORDINATES),

    // --- Temporal ------------------------------------------------------------
    TIME("time", "ms", "Timestamp (ms since epoch)", PointFieldCategory.TEMPORAL,
        notSelectable = true),
    ELAPSED("elapsed", "ms", "Elapsed duration (ms)", PointFieldCategory.TEMPORAL),
    DT("dt", "ms", "dt (ms)", PointFieldCategory.TEMPORAL),

    // --- Angles --------------------------------------------------------------
    BEARING("bearing", "radians", "Direction bearing (radians)", PointFieldCategory.ANGLES,
        anglesInRadians = true),

    // --- Elevation -----------------------------------------------------------
    ELEVATION("elevation", "meters", "Elevation (meters)", PointFieldCategory.ELEVATION),

    // --- Grade ---------------------------------------------------------------
    GRADE("grade", "%", "Road grade/slope (%)", PointFieldCategory.GRADE),

    // --- Radius --------------------------------------------------------------
    RADIUS("radius", "meters", "Turn radius (meters)", PointFieldCategory.RADIUS),

    // --- Aero coef -----------------------------------------------------------
    AERO_COEF("aeroCoef", "aero", "Aerodynamic coefficient", PointFieldCategory.AERO_COEF),

    // --- Cyclist wind --------------------------------------------------------
    WIND_BEARING("windBearing", "radians", "Wind bearing (radians)",
        PointFieldCategory.CYCLIST_WIND, anglesInRadians = true),
    WIND_ALPHA("windAlpha", "radians", "Wind angle (radians)",
        PointFieldCategory.CYCLIST_WIND, anglesInRadians = true),

    // --- Power Physics -------------------------------------------------------
    P_AERO("pAero", "watts", "Aerodynamic power", PointFieldCategory.POWER_PHYSICS),
    P_GRAVITY("pGravity", "watts", "Gravitational power", PointFieldCategory.POWER_PHYSICS),
    P_ROLLING_RESISTANCE("pRollingResistance", "watts", "Rolling resistance power",
        PointFieldCategory.POWER_PHYSICS),
    P_WHEEL_BEARINGS("pWheelBearings", "watts", "Wheel bearings power",
        PointFieldCategory.POWER_PHYSICS),

    // --- Power Cyclist -------------------------------------------------------
    P_INPUT_POWER("pInputPower", "watts", "GPX input power", PointFieldCategory.POWER_CYCLIST),
    P_CYCLIST_PROVIDED_OPTIMAL_POWER("pCyclistProvidedOptimalPower", "watts", "Optimal power",
        PointFieldCategory.POWER_CYCLIST),
    P_CYCLIST_PROVIDED_OPTIMAL_POWER_HARMONICS(
        "pCyclistProvidedOptimalPowerWithHarmonics", "watts", "Optimal power with harmonics",
        PointFieldCategory.POWER_CYCLIST,
    ),
    P_CYCLIST_PROVIDED_POWER_NEEDED("pCyclistPowerNeeded", "watts", "Power needed",
        PointFieldCategory.POWER_CYCLIST),
    P_CYCLIST_PROVIDED_MUSCULAR("pCyclistProvidedMuscular", "watts", "Raw cyclist power",
        PointFieldCategory.POWER_CYCLIST),
    P_CYCLIST_PROVIDED_WHEEL("pCyclistProvidedWheel", "watts",
        "Cyclist power transmitted to ground", PointFieldCategory.POWER_CYCLIST),

    // --- Power Post-processed ------------------------------------------------
    P_COMPUTED_TOTAL_POWER("pComputedTotalPower", "watts",
        "Power from kinetic energy change", PointFieldCategory.POWER_POST),
    P_COMPUTED_WHEEL_POWER("pComputedWheelPower", "watts",
        "Wheel power from kinetic energy change", PointFieldCategory.POWER_POST),
    POWER("pComputedPower", "watts", "Total power (watts)", PointFieldCategory.POWER_POST),

    // --- Speed & Motion ------------------------------------------------------
    SPEED("speed", "m/s", "Current speed (m/s)", PointFieldCategory.SPEED),
    SPEED_MAX("speedMax", "m/s", "Maximum speed (m/s)", PointFieldCategory.SPEED),
    SPEED_MAX_INCLINE("speedMaxIncline", "m/s", "Max speed on incline (m/s)",
        PointFieldCategory.SPEED),
    VIRT_SPEED_CURRENT("virtSpeedCurrent", "m/s", "Virtual current speed (m/s)",
        PointFieldCategory.SPEED),

    // --- Environmental -------------------------------------------------------
    TEMPERATURE("temperature", "celsius", "Temperature (celsius)",
        PointFieldCategory.ENVIRONMENTAL),
    WIND_SPEED("windSpeed", "m/s", "Wind speed (m/s)", PointFieldCategory.ENVIRONMENTAL),
    WIND_DIRECTION("windDirection", "radians", "Wind direction (radians)",
        PointFieldCategory.ENVIRONMENTAL, anglesInRadians = true),

    // --- Physiological -------------------------------------------------------
    HEART_RATE("heartRate", "bpm", "Heart rate (bpm)", PointFieldCategory.PHYSIOLOGICAL),
    CADENCE("cadence", "rpm", "Pedaling cadence (rpm)", PointFieldCategory.PHYSIOLOGICAL),
    ;

    /** Field index in the per-point `DoubleArray` slot (== [ordinal]). */
    val index: Int get() = ordinal

    companion object {
        /** Number of fields per point. Single source of truth for codegen (task 11). */
        const val COUNT: Int = 36

        private val byProp: Map<String, PointField> = entries.associateBy { it.prop }

        /** Lookup by camelCase property name (e.g. `"latitude"` → [LATITUDE]). */
        fun byProp(prop: String): PointField? = byProp[prop]

        /** All fields belonging to [category], in declaration order. */
        fun byCategory(category: PointFieldCategory): List<PointField> =
            entries.filter { it.category == category }
    }
}
```

**Notes design** :
- `enum.entries` (Kotlin 2.0+) plutôt que `values()` déprécié.
- `index = ordinal` : implicite, économise un paramètre par entrée et garantit la cohérence ordre/index.
- `anglesInRadians` remplace le `getDegrees` du TS — sémantique plus claire (flag de stockage, pas de génération). La génération du getter `*InDegrees(i)` sera traitée en tâche 12.
- `setSpecial=date` / `getSpecial=date` du TS (champ `TIME`) ne sont pas portés ici : ils décrivent un getter/setter spécial (`Date` ↔ `Long`). En Kotlin, `kotlinx-datetime` offre `Instant` ; on traitera cette ergonomie en tâche 12 (`Path` classe).
- `byProp` map construite une seule fois (lazy via `entries.associateBy` capturé en `val`).
- Pas de `description` longue (le TS l'avait souvent vide). Si besoin pour la doc, ajouter plus tard.
- `POWER` (ordinal 26) garde son nom enum surprenant — c'est l'historique du TS et l'index est gravé dans la pierre par la spec « ordre = format ».

### 4. Tests `PointFieldTest.kt`

`engine/src/commonTest/kotlin/io/github/glandais/engine/path/PointFieldTest.kt` :

Cas à couvrir :

| # | Cas | Attendu |
|---|---|---|
| 1 | `PointField.entries.size == 36` | exact |
| 2 | `PointField.COUNT == 36` | exact |
| 3 | Tous les `ordinal` sont uniques | (trivial, propriété enum — vérifier `entries.map { it.ordinal }.toSet().size == 36`) |
| 4 | Tous les `prop` sont uniques et non vides | aucune collision |
| 5 | `index` == `ordinal` pour chaque entrée | propriété |
| 6 | `PointField.LATITUDE.ordinal == 0` | sentinel |
| 7 | `PointField.CADENCE.ordinal == 35` | sentinel — dernier champ |
| 8 | `byProp("latitude") == LATITUDE` | lookup |
| 9 | `byProp("notARealField") == null` | lookup miss |
| 10 | `byCategory(POWER_PHYSICS).size == 4` | groupement |
| 11 | `byCategory(POWER_CYCLIST).size == 6` | groupement |
| 12 | `byCategory(POWER_POST).size == 3` | groupement |
| 13 | `byCategory(SPEED).size == 4` | groupement |
| 14 | `byCategory(COORDINATES).map { it.prop } == [latitude, longitude, distance, dx]` | ordre |
| 15 | Champs `anglesInRadians=true` : exactement 5 (LATITUDE, LONGITUDE, BEARING, WIND_BEARING, WIND_ALPHA, WIND_DIRECTION) | en fait 6 — recompter ci-dessus |
| 16 | Champs `notSelectable=true` : exactement 3 (LATITUDE, LONGITUDE, TIME) | |
| 17 | Toutes les unités sont parmi un set fermé (radians, meters, ms, %, m/s, watts, celsius, bpm, rpm, aero) | propriété de cohérence |
| 18 | Toutes les `shortDescription` sont non vides | sanity |
| 19 | `PointFieldCategory.entries.size == 14` | sentinel |
| 20 | `PointFieldCategory.entries.map { it.id }.toSet().size == 14` | unicité ids |

Squelette :

```kotlin
package io.github.glandais.engine.path

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PointFieldTest {

    @Test fun `36 fields exactly`() {
        assertEquals(36, PointField.entries.size)
        assertEquals(36, PointField.COUNT)
    }

    @Test fun `ordinals are unique`() {
        val ordinals = PointField.entries.map { it.ordinal }
        assertEquals(36, ordinals.toSet().size)
    }

    @Test fun `props are unique and non-blank`() {
        val props = PointField.entries.map { it.prop }
        assertEquals(36, props.toSet().size)
        assertTrue(props.all { it.isNotBlank() })
    }

    @Test fun `index equals ordinal`() {
        for (f in PointField.entries) assertEquals(f.ordinal, f.index)
    }

    @Test fun `latitude is first`() {
        assertEquals(0, PointField.LATITUDE.ordinal)
    }

    @Test fun `cadence is last`() {
        assertEquals(35, PointField.CADENCE.ordinal)
    }

    @Test fun `byProp round-trip`() {
        for (f in PointField.entries) {
            assertEquals(f, PointField.byProp(f.prop))
        }
        assertNull(PointField.byProp("noSuchField"))
    }

    @Test fun `category groupings match TS structure`() {
        assertEquals(4, PointField.byCategory(PointFieldCategory.COORDINATES).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.TEMPORAL).size)
        assertEquals(1, PointField.byCategory(PointFieldCategory.ANGLES).size)
        assertEquals(4, PointField.byCategory(PointFieldCategory.POWER_PHYSICS).size)
        assertEquals(6, PointField.byCategory(PointFieldCategory.POWER_CYCLIST).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.POWER_POST).size)
        assertEquals(4, PointField.byCategory(PointFieldCategory.SPEED).size)
        assertEquals(3, PointField.byCategory(PointFieldCategory.ENVIRONMENTAL).size)
        assertEquals(2, PointField.byCategory(PointFieldCategory.PHYSIOLOGICAL).size)
    }

    @Test fun `category coordinates order matches TS`() {
        assertEquals(
            listOf("latitude", "longitude", "distance", "dx"),
            PointField.byCategory(PointFieldCategory.COORDINATES).map { it.prop },
        )
    }

    @Test fun `angle fields exposed in radians`() {
        val expected = setOf(
            PointField.LATITUDE,
            PointField.LONGITUDE,
            PointField.BEARING,
            PointField.WIND_BEARING,
            PointField.WIND_ALPHA,
            PointField.WIND_DIRECTION,
        )
        assertEquals(expected, PointField.entries.filter { it.anglesInRadians }.toSet())
    }

    @Test fun `notSelectable fields are latitude, longitude, time`() {
        val expected = setOf(PointField.LATITUDE, PointField.LONGITUDE, PointField.TIME)
        assertEquals(expected, PointField.entries.filter { it.notSelectable }.toSet())
    }

    @Test fun `units belong to a closed set`() {
        val allowed = setOf("radians", "meters", "ms", "%", "m/s", "watts", "celsius", "bpm", "rpm", "aero")
        for (f in PointField.entries) {
            assertTrue(f.unit in allowed, "Unexpected unit '${f.unit}' for $f")
        }
    }

    @Test fun `all shortDescriptions are non-blank`() {
        for (f in PointField.entries) assertTrue(f.shortDescription.isNotBlank())
    }

    @Test fun `14 categories`() {
        assertEquals(14, PointFieldCategory.entries.size)
        assertEquals(14, PointFieldCategory.entries.map { it.id }.toSet().size)
    }
}
```

Supprimer le `SmokeTest.kt` posé en bootstrap (`engine/src/commonTest/kotlin/io/github/glandais/engine/SmokeTest.kt`) — il devient redondant.

### 5. `.gitkeep` à supprimer

`engine/src/commonMain/kotlin/io/github/glandais/engine/.gitkeep` → supprimer car le dossier contiendra désormais du code (via le sous-package `path/`).

### 6. Vérification ktlint

L'ordre des paramètres nommés sur plusieurs lignes peut nécessiter `./gradlew :engine:ktlintFormat`.

## Outputs (fichiers attendus)

Créés :

- `vcyclist/engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointFieldCategory.kt`
- `vcyclist/engine/src/commonMain/kotlin/io/github/glandais/engine/path/PointField.kt`
- `vcyclist/engine/src/commonTest/kotlin/io/github/glandais/engine/path/PointFieldTest.kt`

Modifiés :

- `vcyclist/engine/build.gradle.kts` — activation de `api(project(":elevation"))`

Supprimés :

- `vcyclist/engine/src/commonTest/kotlin/io/github/glandais/engine/SmokeTest.kt` (bootstrap)
- `vcyclist/engine/src/commonMain/kotlin/io/github/glandais/engine/.gitkeep`

## Validation

```bash
./gradlew :engine:allTests
./gradlew :engine:build
./gradlew :elevation:allTests        # non-régression
./gradlew ktlintCheck
```

Critères :

- `PointFieldTest` : ≥ 14 tests verts par target (JVM + JS Node + Wasm browser).
- `:engine:build` compile en activant la dépendance `:elevation` (pas de classpath conflict).
- `ktlintCheck` vert.
- Le smoke test bootstrap est supprimé ; le rapport `:engine:jvmTest` n'affiche plus `SmokeTest` mais `PointFieldTest`.

## Done when

- [x] `api(project(":elevation"))` activé et `:engine:build` vert
- [x] `PointFieldCategory.kt` créé (14 catégories)
- [x] `PointField.kt` créé (36 entrées, ordre exact du TS)
- [x] `PointField.COUNT == 36` (constante exposée pour codegen tâche 11)
- [x] `byProp(prop)` et `byCategory(cat)` opérationnels
- [x] `PointFieldTest.kt` créé (16 tests) — tous verts sur les 3 targets (JVM + JS Node + Wasm browser = 48 exécutions)
- [x] `SmokeTest.kt` du bootstrap supprimé côté `:engine`
- [x] `:elevation:allTests` toujours vert (non-régression)
- [x] `ktlintCheck` vert
- [x] `PLAN.md` mis à jour : "37 champs" → "36 champs" + 14 catégories (commit séparé `docs:` recommandé)
- [x] Toutes les checkboxes cochées

## Notes

- **Pourquoi enum plutôt que codegen** : un `enum class` Kotlin est déjà la *single source of truth* type-safe. Pour la phase suivante (tâche 11), on dérive du code à partir de ce enum (KSP, script gradle, ou inline `inline class`/extension functions). Pas besoin d'un fichier généré séparé pour les **définitions** elles-mêmes — seules les fonctions accesseurs typées (`Path.latitude(i): Double`) sont à générer.
- **`ordinal` comme contrat de format** : tout réordonnancement du enum est un **breaking change** pour les sérialisations GPX-extension et JS DTO. Test #6/#7 le verrouille (`LATITUDE.ordinal == 0`, `CADENCE.ordinal == 35`).
- **Typo TS `PCyclistProvidedWheel`** (P majuscule dans `prop`, ligne 274 du TS) : on normalise en `pCyclistProvidedWheel` côté Kotlin. C'est un bug TS visible-from-JS (l'accesseur `point.PCyclistProvidedWheel` est laid). Si une parité stricte des DTO JS est requise plus tard (tâche 28), exposer un alias via `@JsName`.
- **Pas de `longDescription`** : la majorité des champs TS l'ont vide (`''`). Si on en a besoin pour la doc utilisateur, on l'ajoutera plus tard avec des valeurs réelles plutôt que de copier des `""`.
- **Unicode escapes pour emojis** : choix défensif. La compilation Kotlin/Wasm a déjà eu des soucis de codepoints dans les sources non-UTF8 strictes. `🏔` (surrogate pair) est plus portable que `🏔️` direct.
- **Discrépance "37 vs 36"** : à corriger dans `PLAN.md` dans un commit `docs:` séparé après ce port. Origine probable : doc TS pré-existante qui inclut une catégorie supprimée. Le code TS actuel a 36.
- **Pas de `index: Int` paramètre explicite** : économise 36 entiers de boilerplate sans rien changer fonctionnellement. Si plus tard on veut "réserver" des indices (pour ajouter des champs sans tout décaler), il faudra revenir à un paramètre explicite — mais ce n'est pas le besoin actuel.
- **Préparation tâche 11** : les éléments suivants exposés par cette tâche seront consommés par la prochaine :
  - `PointField.COUNT` → taille du `DoubleArray` par point
  - `PointField.entries` → itération pour générer les getters/setters
  - `PointField.prop` → nom de la fonction membre (`fun latitude(i): Double`)
  - `PointField.anglesInRadians` → décider si on génère un alias `*InDegrees`
- **Tests cross-target** : aucun spécifique nécessaire — enum est natif KMP, les `entries` et `ordinal` ont la même sémantique sur JVM/JS/Wasm.
