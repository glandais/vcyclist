# g24 — Lecture et écriture des GPX `<rte>` / `<rtept>`

## Goal

`GpxParser.kt:107` ne reconnaît que `"trk"`. Un GPX composé uniquement de `<rte>` est parsé en
document **vide, sans erreur** — le pire des échecs, silencieux : l'appelant reçoit un
`GpxDocument(tracks = emptyList())` et croit son fichier vide. Or les routes sont la sortie
normale de plusieurs planificateurs d'itinéraire (Garmin BaseCamp, RideWithGPS en mode route,
Komoot en export « route »).

gpx2web les gère (`GPXFileReader.java:153` et `:164` traitent `rte`/`rtept` au même titre que
`trk`/`trkpt`, et `GPXPathType` porte la distinction). C'est donc une **régression de parité**,
pas une extension de périmètre.

## Depends on

- `g02` (multi-track / multi-segment — la structure `GpxDocument` → `GpxTrack` → `GpxSegment`)
- `g23` : les deux touchent `GpxWriter.kt`. Faire `g23` d'abord.

## Inputs

- `gpx/src/commonMain/…/gpx/Gpx.kt` — `GpxDocument`, `GpxTrack`, `GpxSegment`
- `gpx/src/commonMain/…/gpx/GpxParser.kt:100-180` — dispatch `"trk"` / `"trkseg"` / `"trkpt"`
- `gpx/src/commonMain/…/gpx/{GpxWriter,GpxToPath,GpxFromPath}.kt`
- `../gpx2web/…/io/read/GPXFileReader.java:150-175` et `data/GPXPathType.java` — référence
- `docs/tasks/g02-gpx-multi-track.md:183` — note à rectifier
- `docs/gpx2web-coverage.md:83` — ligne `GPXPathType` à rectifier

## Steps

### 1. Modèle

```kotlin
enum class GpxPathKind { TRACK, ROUTE }
```

Ajouter à `GpxTrack` un champ `kind: GpxPathKind = GpxPathKind.TRACK`, **en dernière position**
du constructeur primaire pour préserver la compatibilité des appels positionnels existants. Le
`companion object invoke(name, type, points)` de g02 doit relayer le nouveau paramètre avec le
même défaut.

Le nom `GpxTrack` couvre désormais aussi les routes. Le renommer serait une rupture de source
pour un gain cosmétique : garder le nom, l'expliquer dans le KDoc de la classe.

### 2. Parser

- `"rte"` → même chemin que `"trk"`, avec `kind = ROUTE` et **un segment unique** : une route n'a
  pas de `<rtept seg>`, la notion de segment n'existe pas dans le schéma.
- `"rtept"` accepté partout où `"trkpt"` l'est, avec **exactement le même contrat** : `lat`/`lon`
  obligatoires (même erreur si absents ou invalides), `<ele>`, `<time>` et `<extensions>`
  optionnels, même tolérance aux valeurs illisibles.
- **Ordre du document préservé** : un fichier mêlant `<trk>` et `<rte>` doit ressortir dans
  l'ordre de lecture, pas trié par type. Cela impose de collecter dans une seule liste au fil du
  parcours plutôt que d'accumuler deux listes et de les concaténer.

### 3. Writer

- `kind == ROUTE` → `<rte>` / `<rtept>`, sans `<trkseg>`.
- Un path `ROUTE` porteur de plusieurs segments est impossible en lecture mais constructible à la
  main : concaténer les points et le documenter dans le KDoc du writer, plutôt que de lever.
- Les `<extensions>` sont autorisées sur `<rtept>` par le schéma GPX 1.1 : même traitement que
  `<trkpt>`, sous le drapeau `writeExtensions` de `g23`.

### 4. Accesseurs `Path`

`tracksAsPaths()` et `segmentsAsPaths()` incluent les routes. Ajouter un paramètre de filtrage
plutôt que deux méthodes de plus :

```kotlin
fun GpxDocument.tracksAsPaths(kinds: Set<GpxPathKind> = GpxPathKind.entries.toSet()): List<Path>
```

`GpxDocument.startTime` (g05) considère aussi les `<rtept>` horodatés : sa note « ignore les
rte » dans `g05-gpx-start-time.md` devient caduque et doit être rectifiée.

### 5. Documentation à rectifier

Deux affirmations aujourd'hui fausses dans le dépôt :

- `docs/tasks/g02-gpx-multi-track.md:183` — « `<rte>` (routes) reste non supporté. gpx2web ne
  les gère pas non plus. » La seconde phrase est fausse ; la première devient obsolète. Réécrire
  en pointant vers cette fiche.
- `docs/gpx2web-coverage.md:83` — « Les routes (`<rte>`) ne sont pas lues. » À mettre à jour, en
  remplaçant la mention de `GPXPathType` « remplacé » par « porté → `GpxPathKind` ».

## Outputs

Modifiés :

- `gpx/src/commonMain/…/gpx/{Gpx,GpxParser,GpxWriter,GpxToPath}.kt`
- `docs/tasks/{g02-gpx-multi-track,g05-gpx-start-time}.md` (notes rectifiées)
- `docs/gpx2web-coverage.md` (ligne 83)

Créés :

- `gpx/src/commonTest/…/gpx/GpxRouteTest.kt`
- Fixtures route dans `gpx/src/commonTestFixtures/…/gpx/GpxFixtures.kt` (chaînes Kotlin inline —
  `commonTest/resources` n'est pas portable, cf. `CLAUDE.md`)

## Validation

```bash
./gradlew :gpx:allTests :engine:allTests
./gradlew ktlintCheck
```

| # | Cas | Attendu |
|---|---|---|
| 1 | GPX 100 % `<rte>` (2 routes) | 2 entrées, `kind == ROUTE`, points corrects |
| 2 | GPX mixte `<trk>` + `<rte>` + `<trk>` | 3 entrées **dans l'ordre du document**, `kind` correct |
| 3 | `<rtept>` sans `lat` | même erreur que `<trkpt>` sans `lat` |
| 4 | `<rtept>` avec `<ele>` et `<time>` | valeurs lues, `GpxDocument.startTime` renseigné |
| 5 | Round-trip route : parse → write | ressort en `<rte>`/`<rtept>`, pas en `<trk>` |
| 6 | Round-trip mixte | types et ordre préservés |
| 7 | `tracksAsPaths(kinds = setOf(TRACK))` sur un mixte | routes exclues |
| 8 | `tracksAsPaths()` sans argument sur un fichier 100 % `<trk>` | identique à pré-g24 |
| 9 | Route à segment unique écrite | aucun `<trkseg>` dans la sortie |
| 10 | `<rte>` vide (sans `<rtept>`) | entrée avec 0 point, pas de crash |
| 11 | `enhance` sur un GPX route (bout en bout) | pipeline complet, sortie cohérente |

## Done when

- [x] `GpxPathKind` exposé, `GpxTrack.kind` en dernière position avec défaut `TRACK`
- [x] `<rte>` / `<rtept>` lus, ordre du document préservé sur les fichiers mixtes
- [x] Écriture en `<rte>` quand `kind == ROUTE`, round-trip vert
- [x] `tracksAsPaths(kinds = …)` pour filtrer
- [x] Notes erronées de `g02`, `g05` et `gpx2web-coverage.md` rectifiées
- [x] `./gradlew check` + `ktlintCheck` verts

## Résultat

### API

- `enum class GpxPathKind { TRACK, ROUTE }` et `GpxTrack.kind`, **en dernière position** du
  constructeur avec défaut `TRACK` : tous les appels positionnels existants compilent inchangés.
  Le `companion object invoke(name, type, points)` de g02 relaie le nouveau paramètre.
- `GpxParser` : `"rte"` → `parseRoute`, `"rtept"` → **le même `parseTrackPoint` que `<trkpt>`**.
  Ce partage n'est pas une commodité : GPX 1.1 déclare `<trkpt>`, `<rtept>` et `<wpt>` comme le
  même `wptType`, donc les attributs obligatoires, les enfants optionnels et les messages
  d'erreur sont identiques par construction (cas 3).
- `GpxWriter` : `writeRoute` émet `<rte>` / `<rtept>` sans `<trkseg>`. `writeTrackPoint` prend un
  `localName` (défaut `"trkpt"`) — un seul chemin de code pour les deux conteneurs, donc aucun
  risque de divergence sur les extensions ou l'horodatage.
- `tracksAsPaths(kinds = …)` et `segmentsAsPaths(kinds = …)` : défaut = les deux conteneurs.
  `setOf(GpxPathKind.TRACK)` redonne la sélection pré-g24.

### Ordre d'écriture : document, pas schéma

Premier jet : routes groupées avant les pistes, pour respecter la séquence `wpt*, rte*, trk*` de
GPX 1.1. **Corrigé sur demande** — routes et pistes sont écrites dans l'ordre du document, y
compris entrelacées.

Le compromis est explicite : réécrire un fichier mixte dans un ordre différent de celui où il a
été lu revient à modifier silencieusement le fichier de l'utilisateur, ce qui pèse plus lourd que
la clause d'ordre du schéma — d'autant que les documents mêlant les deux conteneurs sont rares.
Les `<wpt>` restent écrits en premier : c'est l'ordre que les parseurs stricts rejettent
réellement en pratique. Un test (cas 06b) vérifie que l'écriture est un point fixe, donc que
l'ordre ne peut pas dériver au fil des round-trips.

### Comportement de bout en bout

`enhance` sur un GPX 100 % `<rte>` fonctionne désormais — il sortait en `RUNTIME` (« no track with
any point ») auparavant. La sortie est un `<trk>` : une route virtualisée porte des horodatages et
des données simulées, c'est un enregistrement, plus un plan. Vérifié par le cas CLI 20 et par un
smoke réel.

### Vérification

- 13 cas dans `GpxRouteTest` (commonTest) × 3 cibles, plus le cas CLI 20.
- Cas 8 : un fichier 100 % `<trk>` produit une sortie **identique** à pré-g24 — la tâche est
  purement additive pour l'existant.
- `./gradlew check` + `ktlintCheck` verts.

## Notes

- **Pourquoi un `enum` et pas un `Boolean isRoute`.** Le schéma GPX distingue trois conteneurs
  (`wpt`, `rte`, `trk`) ; les waypoints ont déjà leur type. Un `enum` laisse la place si un
  besoin de distinction plus fine apparaît, sans nouvelle rupture de signature.
- **Une route n'est pas une trace.** Elle n'est pas horodatée en général, ses points sont
  espacés de centaines de mètres, et son élévation est souvent absente. Le pipeline `Enhancer`
  la traite correctement grâce à `PointPerDistance(-1, 30)` en tête, mais le cas mérite un test
  de bout en bout (cas 11) : c'est le scénario réel d'un utilisateur qui virtualise un
  itinéraire planifié.
- **Compatibilité.** Purement additif : aucun fichier aujourd'hui lu correctement ne change de
  résultat. Bump mineur.
