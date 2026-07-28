# w04 — Parité fonctionnelle de la façade WASI avec `EngineJsApi`

## Goal

Un `.wasm` qui sait seulement parser un GPX et rendre une distance n'a pas d'intérêt : ce que
les hôtes veulent, c'est **`enhance`** — la simulation. Cette fiche porte la surface utile de
`EngineJsApi` sur l'ABI figée en w03.

`fixElevation` est explicitement hors périmètre ici : il demande le réseau, c'est w05.

## Depends on

- `w03` (ABI v1).

## Inputs

- `engine/src/jsMain/…/EngineJsApi.kt` — la référence, ~29 exports et une dizaine de DTO.
- `engine/src/commonMain/…/Enhancer.kt` — l'ordre du pipeline, invariant.
- `engine/src/commonMain/…/climb/ClimbDetector.kt` — cols.
- `gpx/src/commonMain/…/export/` — writers CSV (g06) et JSON (g07).
- `engine/src/commonMain/…/path/PathWind.kt` — `dominantHeadwindAzimuthDeg` (g26/g31).

## Steps

### 1. Table de correspondance

Première étape, avant toute ligne de code : produire la table `EngineJsApi` → `EngineWasiApi`,
une ligne par export JS, avec une décision explicite **porté / porté sous une autre forme / non
porté + raison**. Elle atterrit dans la doc de w10 ; c'est elle qui rend la parité vérifiable.

Attendu (à confirmer lors de la rédaction) :

| Groupe JS | Forme WASI |
|---|---|
| `parseGpx`, `parseGpxTracks`, `parseGpxSegments`, `parseGpxTracksOnly`, `parseGpxRoutesOnly` | `vcParseGpx(byteLen, mode) -> handle` / `vcParseGpxMulti(byteLen, mode) -> handle de liste` |
| `writeGpx`, `writeGpxAt`, `writeGpxTracks` | `vcWriteGpx(handle, optionsJsonLen)` |
| `pathSize/TotalDistance/DurationMs/ElevationGain/ElevationLoss` | scalaires, un export chacun (déjà partiellement fait en w03) |
| `pointAt`, `getField`, `pathLatitudeDeg`, `pathLongitudeDeg` | `vcGetField(handle, fieldIndex, pointIndex) -> Double` + `vcPointJson(handle, i)` |
| `fieldDefinitions` | `vcFieldDefinitionsJson()` |
| `enhance`, `enhanceWithCourse` | `vcEnhance(handle, optionsJsonLen) -> handle` |
| `detectClimbs`, `detectClimbsWithOptions` | `vcDetectClimbsJson(handle, optionsJsonLen)` |
| `pathToCsv`, `pathToJson` | `vcPathToCsv(handle, optionsJsonLen)`, `vcPathToJson(...)` |
| `dominantHeadwindAzimuth(OfTracks)` | scalaires |
| `pathToFit`, `pathsToFit` | **non porté** — stub `:fit` (w01), sentinelle d'erreur, voir w12 |
| `parseGpxWaypoints` | JSON |

### 2. Accès en masse aux points

Un export scalaire par point et par champ, c'est un appel Wasm par valeur : inutilisable sur une
trace de 50 000 points. Prévoir **un export de transfert en bloc** : `vcPathFieldBytes(handle,
fieldIndex)` qui pousse le `DoubleArray` du champ, en little-endian, via `write_output`. L'hôte
reconstruit un tableau natif d'un coup. C'est le seul chemin viable pour tracer un profil.

Documenter l'endianness (Wasm est little-endian, donc pas de conversion) et la taille attendue
(`8 × vcPathSize`).

### 3. Options JSON

Un objet JSON par export à options, schéma aligné sur les DTO JS (décision w03 §3). Les valeurs
par défaut viennent d'`EngineConstants`, **jamais recopiées** — même règle que `:cli`. Un champ
JSON absent = défaut ; un champ inconnu = erreur `-3`, pas un silence (une faute de frappe sur
`cyclistWeight` doit se voir).

### 4. Tests

- `wasmWasiTest` pour la sérialisation des options et la table de correspondance (un test qui
  échoue si un export JS n'a pas de ligne dans la table — le fichier de table peut être une
  simple liste Kotlin, comparée à l'ensemble des noms attendus).
- Le bout en bout (enhance complet sur `stelvio.gpx`, comparaison aux métriques de
  `ParityFixtures`) va dans le harnais d'hôtes de w09, avec la tolérance de 0,5 % du projet.

## Outputs

- `engine/src/wasmWasiMain/…/wasi/EngineWasiApi.kt` étendu.
- Éventuels helpers de sérialisation JSON dans le même package.
- Tests `wasmWasiTest` associés.

## Validation

- [ ] `./gradlew check` vert, `ktlintCheck` vert.
- [ ] La table de correspondance couvre 100 % des `@JsExport` de `EngineJsApi`, décision incluse.
- [ ] `vcEnhance` sur `stelvio.gpx` depuis wasmtime-py rend distance et durée à 0,5 % des
      valeurs JVM.
- [ ] Taille du `.wasm` relevée et comparée à w03.

## Done when

Un hôte WASI peut charger un GPX, lancer la simulation, lire les champs en bloc et ré-écrire un
GPX — sans hôte JavaScript, et sans autre écart avec la façade JS que ceux listés et justifiés.

## Notes

Ne pas exposer d'export dont le nom diffère de son homologue JS sans raison : `vcEnhance` pour
`enhance`, préfixe `vc` systématique (l'espace de noms des exports Wasm est plat).
