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

- [x] `./gradlew check` vert, `ktlintCheck` vert.
- [x] La table de correspondance couvre 100 % des `@JsExport` de `EngineJsApi`, décision incluse.
- [x] `vcEnhance` sur `stelvio.gpx` depuis wasmtime-py rend distance et durée à 0,5 % des
      valeurs JVM.
- [x] Taille du `.wasm` relevée et comparée à w03.

## Done when

Un hôte WASI peut charger un GPX, lancer la simulation, lire les champs en bloc et ré-écrire un
GPX — sans hôte JavaScript, et sans autre écart avec la façade JS que ceux listés et justifiés.

## Notes

Ne pas exposer d'export dont le nom diffère de son homologue JS sans raison : `vcEnhance` pour
`enhance`, préfixe `vc` systématique (l'espace de noms des exports Wasm est plat).

### Ce qui s'est passé

**La table de correspondance est du code, pas de la prose.** `WasiExportCatalog.kt` porte une
`ParityEntry` par `@JsExport`, avec sa décision (`PORTED` / `RESHAPED` / `NOT_PORTED`) et sa
raison — et `WasiParityTableTest` (jvmTest) **lit les deux fichiers sources en texte** pour
vérifier qu'aucun export JS n'a été oublié, qu'aucune ligne ne survit à un export disparu, et
qu'un `NOT_PORTED` porte bien une justification. Lire du texte est inhabituel ; c'est ici le seul
moyen d'y arriver, `PARITY_TABLE` vivant dans `wasmWasiMain` et `EngineJsApi` dans `jsMain`,
donc invisibles l'un à l'autre et à toute réflexion JVM. Le jour où quelqu'un ajoute un
`@JsExport` sans décider ce qu'il devient sous WASI, le build casse au lieu de laisser un trou
dans la doc de w10.

Bilan : 29 exports JS, 20 exports WASI. Les regroupements (`RESHAPED`) tiennent tous à la même
cause — un export Wasm ne prend que des nombres, donc les variantes se distinguent par un
paramètre plutôt que par un nom : les quatre `parseGpx*` multi-chemins deviennent
`vcParseGpxMulti(byteLen, mode)`, `writeGpx` et `writeGpxAt` se départagent par la présence de
`startTimeEpochMs` dans les options, `detectClimbs` et `detectClimbsWithOptions` par la présence
de l'objet d'options tout court.

**Les listes de chemins.** `vcParseGpxMulti` rend un *handle de liste*, parcouru par
`vcListSize` / `vcListGet`. Les deux tables (chemins, listes) partagent le même compteur, si bien
qu'un handle de liste passé là où un handle de chemin est attendu est simplement inconnu (`-2`)
au lieu d'être réinterprété. `vcListGet` enregistre un handle propre par appel : c'est une
référence, pas une copie, et libérer l'un ne dérange pas l'autre.

**L'accès en masse est la raison d'être de l'ABI.** `vcPathFieldBytes(handle, fieldIndex)` pousse
tout un champ en `f64` little-endian — `8 × vcPathSize` octets, aucune conversion (la mémoire
Wasm est little-endian). Vérifié : 259 points → 2 072 octets, et `numpy`/`struct` relisent la
même valeur que `vcGetField`. Un appel par point serait 50 000 franchissements de frontière sur
une vraie trace.

**Le pont `suspend` → synchrone** (`RunSynchronously.kt`) démarre la coroutine et **exige**
qu'elle soit terminée au retour de `startCoroutine`. Sous WASI rien ne peut reprendre une
continuation : si le bloc suspend réellement, il lève en nommant w05, au lieu de rendre un
chemin à moitié calculé. `runBlocking` n'existe pas sur cette cible, et il n'y aurait de toute
façon aucun thread à bloquer.

**Un bug attrapé par un test de w03.** `guardedDouble` renvoyait `-1` (générique) pour un handle
inconnu au lieu de `-2` : l'ordre des `catch` *est* la taxonomie des erreurs, l'exception la plus
spécifique d'abord. Sans le test de w03 sur `vcPathTotalDistance(404)`, ça passait inaperçu.

### Options : strictes, et nommées comme en JS

Un champ absent vaut son défaut, lu depuis le moteur (`Cyclist()`, `Bike()`, `ClimbOptions()`…)
et jamais recopié. Un champ **inconnu est une erreur `-3`** :

```
vcEnhance avec {"fixElevations": true}
  -> -3   "unknown option(s) fixElevations — expected one of computeMaxSpeeds, …"
```

C'est le seul écart volontaire avec la façade JS, et il va dans le bon sens : une faute de frappe
sur `massKg` ne doit pas simuler silencieusement le cycliste par défaut.

`fixElevation: true` échoue également en `-3` avec un message qui nomme w05, plutôt que de sauter
l'étape en silence — une élévation non corrigée sans le dire est une simulation fausse d'aspect
plausible.

### Mesures

Smoke complet sous wasmtime-py sur `demo/public/gpx/stelvio.gpx` (259 points) :

| | WASI | JVM (`:cli enhance --no-fix-elevation --no-one-point-per-second`) |
|---|---|---|
| Durée simulée | **573,2 s** | **573,2 s** |
| Distance | 3 573,805 m | 3 573,8 m |

Identique à la précision d'affichage, très loin sous les 0,5 % du budget. Le reste du smoke :
36 définitions de champs, 1 col détecté (9,09 % de pente moyenne), vent dominant 278,36°, CSV
803 989 o, JSON colonne 835 710 o, GPX 237 164 o avec `<time>` absolus et sans `<extensions>`,
et les six chemins d'erreur (`-2`, `-3`, `-4`, `NaN`) conformes.

Taille du binaire optimisé :

| Étape | Taille |
|---|---|
| w03 (ABI v1, surface GPX seule) | 148 904 o |
| **w04 (surface complète)** | **240 336 o** |

+91 Ko pour la simulation, les cols, les writers CSV/JSON et le mini-JSON — soit toujours moins
que ce que coûtait kotlinx-serialization sur la surface *minimale* (281 030 o, cf. w03).

`:engine` sous wasmtime passe de 236 à 273 tests.

### Écart assumé, à documenter en w10

`vcWriteGpxTracks` n'écrit pas les `<wpt>` : la version JS les reçoit en argument, et l'ABI n'a
pas de handle de waypoint. Un hôte qui y tient récupère les siens via
`vcParseGpxWaypointsJson` et les fusionne. Ajouter un handle pour ça alourdirait le protocole
pour un besoin que personne n'a encore exprimé.
