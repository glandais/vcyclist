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

- [x] Paramètre `startTime: Instant?` sur l'écriture, défaut `null` neutre
- [x] `GpxDocument.startTime` exposé en lecture
- [x] `writeGpxAt` exporté en JS/Wasm, `.d.ts` régénérés
- [x] Option CLI `--start-time`
- [x] ≥ 8 tests verts × 4 cibles
- [x] `ktlintCheck` vert

## Resultat

### API

- `Path.toGpxTrack(name, type, startTime: Instant? = null)` (`GpxFromPath.kt`) : nouveau
  paramètre `startTime`. Quand il est fourni, **tous** les points (y compris l'index 0) reçoivent
  `timeEpochMs = startTime.toEpochMilliseconds() + time(i).roundToLong()`. Quand il vaut `null`
  (défaut), le code emprunte exactement l'ancien chemin (`time(i).toLong().takeIf { it > 0L }`) —
  comportement byte-for-byte identique à avant g05, y compris le test 25 qui compare littéralement
  la sortie avec/sans argument explicite.
- `Path.toGpxDocument(name, trackName, startTime = null)` et
  `pathsToGpxDocument(paths, name, trackNames, waypoints, type, startTime = null)` relaient le
  paramètre à chaque piste (même `startTime` partagé par toutes les pistes d'un document
  multi-piste — cohérent avec un unique horodatage de départ).
- `GpxWriter.write(path, name, trackName, startTime = null)` et
  `GpxWriter.write(paths, name, trackNames, waypoints, startTime = null)` : mêmes surcharges de
  confort, même défaut neutre.
- `GpxDocument.startTime: Instant?` (nouvelle propriété d'extension, `GpxToPath.kt`) : instant du
  **premier** `<trkpt>` horodaté du document, tous tracks/segments confondus, dans l'ordre du
  document (`null` si aucun point n'a de `<time>`). Volontairement tolérant aux points non
  horodatés en tête (cas réel : device qui perd le fix GPS puis le retrouve avec l'heure).
- `writeGpxAt` exporté en `@JsExport` côté `jsMain` et `wasmJsMain` (signature `(Path,
  startTimeEpochMs: Double): String` / `(JsReference<Path>, Double): String`), en miroir strict
  des deux façades existantes — `Double` plutôt que `Long` pour éviter le `BigInt` côté Kotlin/JS,
  même raisonnement que `pathDurationMs` déjà en place (epoch ms tient exactement dans un `Double`
  jusqu'en l'an 287396).
- `EngineCli` : option `--start-time <ISO-8601>` sur la sous-commande `enhance`. Absente par
  défaut → aucun `<time>` en sortie (pas de défaut implicite « maintenant », voir Notes). Une
  valeur invalide (non ISO-8601) retourne `EXIT_USAGE` (64) avec un message explicite plutôt que de
  laisser remonter l'exception `Instant.parse`.

### Décisions de conception

- **Arrondi par point, pas par accumulation** : `time(i).roundToLong()` est appliqué
  indépendamment à chaque point plutôt que d'accumuler un delta arrondi. Le test 29 vérifie
  explicitement l'absence de dérive cumulée sur une série de temps non entiers
  (`0.3, 1500.7, 3000.2` ms).
- **`GpxDocument.startTime` ignore les rte** : non concerné, `<rte>` reste non supporté depuis g02.
- **Pas de `.d.ts` committé à régénérer** : le projet ne committe aucun `.d.ts` généré (ils sortent
  dans `build/js/…`/`build/wasmJs/…`, gitignorés). La case "`.d.ts` régénérés" est donc satisfaite
  de facto — `writeGpxAt` apparaîtra dans le `.d.ts` généré au prochain build JS/Wasm, sans action
  manuelle. Vérifié en inspectant l'arbre : aucun `*.d.ts` suivi par git en dehors de
  `demo/src/vite-env.d.ts` (sans rapport).
- **Round-trip réel = via `enhance`, pas via un `Path` brut parsé** : `GpxToPath.pointsToPath`
  copie déjà `timeEpochMs` tel quel (absolu) dans `Path.time(i)` — ce n'est que
  `VirtualizeService` qui produit un temps *relatif* (`time(0) == 0`). Le cas de test 6 du
  tableau (round-trip startTime) a donc été écrit en rebasant manuellement le path parsé sur
  l'instant recouvré (`time(i) -= startTimeMs`) pour simuler ce que produirait `enhance`, plutôt
  que d'appeler `enhance` directement (coûteux, non déterministe sans provider d'élévation). Ce
  point est documenté dans le commentaire du test `GpxToPathTest.kt` case 06.
- **CLI, pas de `-t` court** : cohérent avec `-o` existant mais le spec nomme explicitement
  `--start-time`, gardé tel quel plutôt que d'ajouter un alias non demandé.

### Vérification

- `./gradlew :gpx:allTests :engine:allTests` → vert sur les 4 cibles (JVM, JS Node, JS Browser,
  Wasm Browser). Nouveaux tests : 6 dans `GpxWriterTest.kt` (cases 25-30) + 4 dans le nouveau
  `GpxToPathTest.kt` (cases 06, 07 du tableau spec, plus 2 cas complémentaires sur
  `GpxDocument.startTime`) = 10 nouveaux tests, au-delà du minimum de 8 demandé.
- `./gradlew ktlintCheck` → vert, aucune reformulation nécessaire par `ktlintFormat`.
- Aucun test existant modifié ni supprimé.

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
