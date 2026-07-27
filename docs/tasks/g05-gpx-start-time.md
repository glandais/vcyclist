# g05 — `startTime` : horodatage absolu à l'écriture

## Goal

`VirtualizeService` produit un temps **relatif** : `time(0) = 0`, et `PointField.TIME` est en
millisecondes depuis le départ. C'est un invariant du moteur (cf. tâche 29 dans `PLAN.md`), et
il ne change pas.

Mais un GPX ou un FIT exploitables par un appareil ou une plateforme (Garmin Connect, Strava)
exigent des **timestamps absolus**. gpx2web résout ça via `StartTimeProvider` (fuseau déduit
de la position, départ à « demain 8 h locale ») et une option CLI `--start-date`.

Décision : on porte le paramètre explicite, **pas** la résolution automatique de fuseau.

## Depends on

- `g01` (module `:gpx`)
- `g02` (signature de `pathsToGpxDocument` déjà retouchée)

## Inputs

- `gpx/src/commonMain/…/gpx/{GpxFromPath,GpxWriter}.kt`
- `gpx/src/commonMain/…/path/PointField.kt` (champ `TIME`)
- `engine/src/commonMain/…/physics/VirtualizeService.kt` (KDoc sur `time(0) = 0`)
- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/virtual/StartTimeProvider.java` (référence, non portée)

## Steps

### 1. API

`kotlin.time.Instant` est déjà utilisé par `GpxParser` — pas de nouvelle dépendance.

```kotlin
fun pathsToGpxDocument(
    paths: List<Path>,
    name: String,
    trackNames: List<String>? = null,
    waypoints: List<GpxWaypoint> = emptyList(),
    /**
     * Instant du premier point. Quand il est fourni, `<time>` est écrit pour chaque point,
     * à `startTime + time(i)` millisecondes. Quand il vaut `null`, aucune balise `<time>`
     * n'est écrite — un GPX sans horodatage reste valide et sans ambiguïté.
     */
    startTime: Instant? = null,
): GpxDocument
```

Le défaut `null` préserve strictement le comportement actuel.

### 2. Conserver l'instant source

Un aller-retour « GPX horodaté → enhance → GPX » devrait pouvoir réutiliser l'heure de départ
d'origine. `GpxToPath` la perd aujourd'hui (normalisation à 0).

Exposer sur `GpxDocument` (ou en retour de conversion) le `startTime` d'origine :

```kotlin
/** Instant du premier `<trkpt>` horodaté du document, `null` si aucun. */
val GpxDocument.startTime: Instant?
```

Ainsi le CLI et la façade JS peuvent proposer « réutiliser l'heure de départ du fichier ».

### 3. Façade JS

```kotlin
@JsExport fun writeGpxAt(path: Path, startTimeEpochMs: Double): String
```

`Double` et non `Long` : en Kotlin/JS `Long` devient `BigInt`, pénible côté appelant. Les
millisecondes d'époque tiennent exactement dans un `Double` jusqu'en l'an 287396 — c'est ce
que fait déjà `pathDurationMs`.

### 4. CLI

`EngineCli` (puis `:cli` en g17) : option `--start-time <ISO8601>`.

Comportement quand l'option est absente : pas de `<time>` en sortie. **Pas** de défaut
implicite « maintenant » — un défaut implicite rend la sortie non reproductible, ce qui casse
les tests de round-trip et les comparaisons de fixtures.

## Outputs

Modifiés :

- `gpx/src/commonMain/…/gpx/{GpxFromPath,GpxWriter,GpxToPath,Gpx}.kt`
- `engine/src/{jsMain,wasmJsMain}/…/EngineJsApi.kt`
- `engine/src/jvmMain/…/EngineCli.kt`

Créés :

- Tests dans `GpxWriterTest`, `GpxToPathTest`

## Validation

```bash
./gradlew :gpx:allTests :engine:allTests
./gradlew ktlintCheck
```

Cas de test (≥ 8) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `startTime = null` | aucune balise `<time>`, sortie identique à pré-g05 |
| 2 | `startTime = T` | `<time>` de chaque point = `T + time(i)` ms |
| 3 | Point 0 | `<time>` exactement `T` |
| 4 | Format ISO 8601 UTC (`2026-07-27T08:00:00Z`) | conforme au schéma GPX |
| 5 | `time(i)` non entier (ex. 1500,7 ms) | arrondi documenté, pas de dérive cumulée |
| 6 | Round-trip : parse GPX horodaté → `startTime` récupéré → write | timestamps identiques à la ms près |
| 7 | GPX sans `<time>` en entrée | `doc.startTime == null` |
| 8 | Monotonie stricte des `<time>` en sortie | assertion sur tout le path |

## Done when

- [ ] Paramètre `startTime: Instant?` sur l'écriture, défaut `null` neutre
- [ ] `GpxDocument.startTime` exposé en lecture
- [ ] `writeGpxAt` exporté en JS/Wasm, `.d.ts` régénérés
- [ ] Option CLI `--start-time`
- [ ] ≥ 8 tests verts × 4 cibles
- [ ] `ktlintCheck` vert

## Notes

- **`StartTimeProvider` n'est pas porté** : la dépendance `timeshape` pèse ~50 Mo de données de
  fuseaux pour produire un défaut « demain 8 h locale ». Hors de proportion, et JVM-only, donc
  inutilisable dans la démo. Acté dans `PLAN-GPX2WEB.md`.
- **Pas de défaut « maintenant »** : la reproductibilité prime. Un appelant qui veut l'heure
  courante la passe explicitement.
- **Prérequis de la phase D** : le format FIT exige des timestamps absolus (époque FIT :
  1989-12-31T00:00:00Z, cf. g10). g08-g10 dépendent de cette tâche.
- Vérifier que `VirtualizeService` garde bien `time(0) = 0` — cette tâche n'y touche pas, elle
  n'affecte que la sérialisation.
