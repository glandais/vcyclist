# g29 — Rattrapage de la façade JS sur g23, g24 et g25

## Goal

Trois tâches de la phase I ont enrichi le cœur et le CLI sans toucher `EngineJsApi`. La façade
JavaScript est donc en retard sur trois points, et un consommateur JS n'a **aucun moyen**
d'atteindre ces fonctionnalités :

| Manque | Ajouté par | État côté JS |
|---|---|---|
| `writeExtensions` | g23 | `writeGpx(path)` et `writeGpxTracks(paths, waypoints)` écrivent toujours les extensions |
| Filtre `kinds` | g24 | `parseGpxTracks(xml)` lit bien les `<rte>` depuis g24, mais sans moyen de les exclure |
| FIT multi-`Path` | g25 | `pathToFit(path, name, startTimeEpochMs)` reste mono-path |

Le premier est le plus gênant : la démo navigateur propose un téléchargement GPX, et c'est
précisément là qu'un fichier nu a du sens (import sur une plateforme tierce).

## Depends on

- `g23`, `g24`, `g25` (livrées)
- Indépendante de `g27` et de `g31`.

## Inputs

- `engine/src/jsMain/…/EngineJsApi.kt` — `parseGpxTracks:215`, `writeGpxTracks:231`,
  `writeGpx:280`, `pathToFit:545`
- [`docs/kotlin-js-jvm-webp.md`](../kotlin-js-jvm-webp.md) — **à lire avant** de toucher `jsMain`
- `demo/src/engine-shim.ts` — types TypeScript des DTO, à suivre si la surface change
- `docs/tasks/{g23-gpx-extensions-option,g24-gpx-routes,g25-fit-multi-path}.md` — la sémantique
  exacte à refléter

## Steps

### 1. Règle de compatibilité

**Aucune signature existante ne change de sens.** Kotlin/JS ne connaît pas la surcharge : deux
`@JsExport fun writeGpx` ne compilent pas. Donc, pour chaque manque, soit un **paramètre à défaut
neutre** sur la fonction existante, soit une **fonction nouvelle au nom explicite**. Trancher au
cas par cas plutôt que d'appliquer une règle uniforme :

- les paramètres à défaut sortent bien en `.d.ts` (`writeGpx(path, writeExtensions?: boolean)`) et
  restent appelables sous leur forme courte ;
- une fonction séparée se justifie quand la variante change la **forme** du retour ou des
  arguments, comme le FIT multi-path.

### 2. g23 — `writeExtensions`

```kotlin
@JsExport fun writeGpx(path: Path, writeExtensions: Boolean = true): String
@JsExport fun writeGpxTracks(paths: Array<Path>, waypoints: Array<WaypointDto> = emptyArray(), writeExtensions: Boolean = true): String
@JsExport fun writeGpxAt(path: Path, startTimeEpochMs: Double, writeExtensions: Boolean = true): String
```

Défaut `true` : sortie inchangée pour tout appelant existant, y compris la démo.

### 3. g24 — filtre sur le conteneur

`GpxPathKind` est une `enum class` de `commonMain`. Elle **n'est pas exportable** telle quelle
vers JS de façon lisible ; ne pas l'exporter. Exposer plutôt le besoin, pas le type :

```kotlin
/** Un Path par <trk> uniquement — routes exclues. */
@JsExport fun parseGpxTracksOnly(xml: String): Array<Path>

/** Un Path par <rte> uniquement. */
@JsExport fun parseGpxRoutesOnly(xml: String): Array<Path>
```

`parseGpxTracks` garde son comportement actuel (les deux conteneurs), qui est le bon défaut :
c'est ce que veut dire « les traces de ce fichier ». Documenter dans le KDoc que depuis g24 il
inclut les routes — un appelant JS qui comptait sur l'ancien comportement doit le savoir.

Alternative à évaluer : un unique `parseGpxTracks(xml, kinds: String = "all")` avec `"all"` /
`"track"` / `"route"`. Une chaîne magique contre deux fonctions nommées — préférer les deux
fonctions, sauf si le besoin d'un troisième axe apparaît.

### 4. g25 — FIT multi-`Path`

```kotlin
@JsExport
fun pathsToFit(
    paths: Array<Path>,
    name: String,
    startTimeEpochMs: Double,
    interPathGapMs: Double = 0.0,
): ByteArray
```

Fonction **nouvelle**, pas un paramètre : la forme du premier argument change. `pathToFit` reste
tel quel et délègue.

`interPathGapMs` en `Double` plutôt qu'une `Duration` : même raison que `startTimeEpochMs`, un
`Long` deviendrait un `BigInt` à la frontière JS. Convention déjà en place pour `pathDurationMs`.

### 5. Démo

La démo n'est pas obligée d'exposer tout cela. Le minimum utile est la case « GPX sans
extensions » à côté du bouton de téléchargement — c'est la fonctionnalité qui a un sens dans un
navigateur. Le FIT multi-path et le filtre de conteneur peuvent rester des API sans UI.

Si la démo change, mettre à jour `demo/src/engine-shim.ts`.

## Outputs

Modifiés :

- `engine/src/jsMain/…/EngineJsApi.kt`
- `README.md` — section « Use from JavaScript / TypeScript » (les extraits doivent rester exacts)
- `demo/src/…` + `demo/src/engine-shim.ts` (si la case à cocher est retenue)

Créés :

- Tests dans `engine/src/jsTest/…` (façade JS) ; les tests de comportement eux-mêmes vivent déjà
  en commonTest côté g23-g25, il s'agit ici de vérifier le **passage de paramètre**, pas de
  retester l'écriture GPX.

## Validation

```bash
./gradlew :engine:jsNodeTest :engine:jsBrowserTest
./gradlew check ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | `writeGpx(path)` | sortie identique à pré-g29 |
| 2 | `writeGpx(path, false)` | ni `<extensions>` ni `gpxtpx` |
| 3 | `writeGpxTracks(paths, wpts, false)` | idem, waypoints toujours écrits |
| 4 | `writeGpxAt(path, t, false)` | `<time>` présent, extensions absentes |
| 5 | GPX 100 % routes → `parseGpxTracks` | non vide (comportement g24) |
| 6 | Mixte → `parseGpxTracksOnly` / `parseGpxRoutesOnly` | partition exacte de `parseGpxTracks` |
| 7 | `pathsToFit([a, b], …)` décodé | 2 laps, 2 paires d'events |
| 8 | `pathToFit(a, …)` vs `pathsToFit([a], …)` | octets identiques |
| 9 | `.d.ts` généré | les paramètres optionnels y sont bien optionnels |

Le cas 9 se vérifie en lisant le `.d.ts` produit dans `build/dist/js/…`, pas en le supposant :
c'est le contrat réel vu par un consommateur TypeScript.

## Done when

- [x] `writeExtensions` sur les trois fonctions d'écriture GPX, défaut `true`
- [x] Accès JS aux deux conteneurs séparément, sans exporter `GpxPathKind`
- [x] `pathsToFit` exporté, `pathToFit` inchangé
- [x] Extraits du `README.md` vérifiés (pas juste modifiés)
- [x] `.d.ts` inspecté
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### Ce qui est exporté

| Ajout | Forme retenue |
|---|---|
| g23 | paramètre `writeExtensions: Boolean = true` en dernière position de `writeGpx`, `writeGpxAt`, `writeGpxTracks` |
| g24 | deux fonctions nommées, `parseGpxTracksOnly` / `parseGpxRoutesOnly` ; `parseGpxTracks` garde les deux conteneurs |
| g25 | fonction nouvelle `pathsToFit(paths, name, startTimeEpochMs, interPathGapMs = 0.0)` |

Le choix « paramètre » vs « fonction nouvelle » suit la règle posée par la fiche : un paramètre
quand seule une option s'ajoute, une fonction quand la **forme** d'un argument change (tableau au
lieu d'un seul path). `GpxPathKind` n'est pas exporté — une énumération Kotlin traverse vers
JavaScript sous une forme que personne n'a envie d'importer juste pour filtrer une liste.

`interPathGapMs` est un `Double`, comme `startTimeEpochMs` : un `Long` deviendrait un `BigInt` à
la frontière JS.

### `.d.ts` vérifié, pas supposé

Le contrat réel vu par un consommateur TypeScript, extrait du fichier généré :

```ts
function writeGpx(path: any, writeExtensions?: boolean): string;
function writeGpxAt(path: any, startTimeEpochMs: number, writeExtensions?: boolean): string;
function writeGpxTracks(paths: Array<any>, waypoints?: Array<WaypointDto>, writeExtensions?: boolean): string;
function parseGpxTracksOnly(xml: string): Array<any>;
function pathsToFit(paths: Array<any>, name: string, startTimeEpochMs: number, interPathGapMs?: number): Int8Array;
```

Les paramètres optionnels ressortent bien optionnels (`?`). Attention au piège rencontré : le
`.d.ts` de `build/dist/js/productionLibrary/` n'est **pas** régénéré par `jsBrowserDistribution` ;
c'est celui de `compileSync/js/main/productionExecutable/` qui est à jour. Vérifier la date avant
de conclure quoi que ce soit d'une inspection de `.d.ts`.

### Démo : signature typée, pas de case à cocher

La fiche proposait une case « GPX sans extensions » à côté du bouton de téléchargement. **Il n'y a
pas de bouton de téléchargement** : `writeGpx` est exporté par `demo/src/engine-shim.ts` mais
aucun composant ne l'appelle. Ajouter le téléchargement *et* son option serait une fonctionnalité
de démo, pas un rattrapage de façade.

La signature du shim est mise à jour (`writeGpx: (path, writeExtensions?: boolean) => string`),
donc l'option est typée et atteignable le jour où l'UI existe.

### Vérification

- 7 tests dans `EngineJsApiCatchupTest` (jsTest), qui vérifient le **passage de paramètre** et non
  le comportement — celui-ci est déjà couvert en commonTest par g23, g24 et g25.
- Le test des conteneurs assert que `parseGpxTracksOnly` et `parseGpxRoutesOnly` **partitionnent**
  exactement `parseGpxTracks` : impossible qu'un fichier tombe entre les deux.
- `pathsToFit([p])` est comparé octet à octet à `pathToFit(p)`.
- `./gradlew check` + `ktlintCheck` + `:demo:assemble` verts.

## Notes

- **Pourquoi une fiche à part et pas une correction dans g23-g25.** Parce que le manque est
  systémique, pas ponctuel : trois tâches d'affilée ont oublié la même surface. Une fiche unique
  rend le trou visible et évite de le reproduire une quatrième fois.
- **La leçon à retenir** : toute fiche qui ajoute un paramètre à une API de `commonMain` devrait
  se demander si la façade JS le relaie. Envisager une ligne à ce sujet dans `CLAUDE.md` §
  *Codebase touchpoints*, au moment de livrer celle-ci.
- La façade JS de `dominantHeadwindDirection` (g26) est traitée séparément, en `g31` : elle ajoute
  une fonction, elle ne rattrape pas un retard.
