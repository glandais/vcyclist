# g20 — Matrice de correspondance gpx2web → vcyclist

## Goal

Produire le document qui dit, classe par classe, ce que gpx2web fait et où se trouve
l'équivalent vcyclist — ou pourquoi il n'y en a pas.

C'est le livrable qui permet de décider en connaissance de cause du sort de gpx2web, et le
document vers lequel pointera son README quand il sera déprécié.

## Depends on

- g01 à g19 (le document constate un état de fait, il ne l'anticipe pas)

## Inputs

- `../gpx2web/gpx/src/main/java/io/github/glandais/gpx/**` (89 classes)
- `../gpx2web/gpxtools-cli/src/main/java/io/github/glandais/**` (8 classes)
- `../gpx2web/gpx-web/src/main/java/io/github/glandais/gpx/web/**` (10 classes)
- `docs/PLAN-GPX2WEB.md` (décisions et liste du non-porté)
- `cli/README.md` (table de correspondance des commandes, écrite en g18)

## Steps

### 1. Inventorier exhaustivement

Lister **toutes** les classes Java de gpx2web, pas seulement celles qu'on a portées. Une classe
oubliée dans l'inventaire est une fonctionnalité perdue sans que personne s'en aperçoive.

```bash
find ../gpx2web -name "*.java" -path "*/src/main/*" | sort
```

### 2. Renseigner le tableau

`docs/gpx2web-coverage.md` :

| Classe gpx2web | Statut | Équivalent vcyclist | Note |
|---|---|---|---|
| `gpx.data.GPXPath` | porté | `io.github.glandais.engine.path.Path` (`:gpx`) | 36 champs vs propriétés dynamiques |
| `gpx.io.write.FitFileWriter` | porté | `io.github.glandais.fit.FitEncoder` (`:fit`) | 4 cibles, SDK Java + JS |
| `gpx.climb.ClimbDetector` | porté | `…engine.climb.ClimbDetector` (`:engine`) | |
| `gpx.map.TileMapProducer` | porté | `io.github.glandais.map.TileMapProducer` (`:map`) | JVM-only |
| `gpx.virtual.power.cyclist.OptimalSpeedService` | **non porté** | — | décision produit |
| `gpx.io.write.tabular.*` (XLSX) | **non porté** | — | CSV uniquement |
| `gpx.util.GPXDataComputer` | **non porté** | — | aucun consommateur hors webapp |
| `gpx.data.FastTimeIndex` | **non porté** | — | usage webapp uniquement |
| `gpx.virtual.StartTimeProvider` | **remplacé** | paramètre `startTime: Instant?` | dépendance `timeshape` écartée |
| `gpx.data.values.**` | **remplacé** | `PointField` | |
| `gpx.srtm.**` | **déjà couvert** | `:elevation` | antérieur à ce plan |
| `gpx.web.**` | **hors périmètre** | — | webapp Quarkus |
| … | | | |

Statuts autorisés : **porté**, **remplacé** (équivalent de conception différente),
**déjà couvert** (avant ce plan), **non porté** (avec la raison), **hors périmètre**.

### 3. Consigner les divergences de comportement

Les tâches précédentes ont produit des écarts assumés. Les rassembler ici, c'est ce qui évite
qu'un utilisateur les découvre en production :

- **g02** — segments concaténés par défaut : la distance saute entre segments, alors que
  gpx2web produit un `GPXPath` par `<trkseg>`. `segmentsAsPaths()` reproduit le comportement
  gpx2web.
- **g03** — les waypoints ne sont pas recalés en altitude par `fixElevation`.
- **g07** — JSON orienté colonnes (à confirmer selon la forme produite par `JsonFileWriter`).
- **g13** — comportement à l'antiméridien.
- **g17** — `process` / `virtualize` fusionnés en `enhance` ; `--start-date` renommé
  `--start-time` ; `--xlsx` sans équivalent.
- Compléter au fil de la relecture des fiches.

### 4. Conclure sur gpx2web

Section explicite : **ce qui reste sans équivalent**.

À la clôture de ce plan, c'est `gpx-web` — la webapp Quarkus, ses deux endpoints, son
`app.html` et son `PowerCurvePowerProvider`. Donc :

- Les modules `gpx` et `gpxtools-cli` de gpx2web peuvent être considérés comme remplacés.
- Le dépôt **ne peut pas être archivé** tant que la webapp tourne.
- Options à documenter sans trancher (ce n'est pas l'objet de ce plan) : reporter la webapp sur
  `vcyclist-engine` JVM, la remplacer par la démo statique, ou l'arrêter.

### 5. Référencer le document

Le lier depuis `README.md`, `docs/PLAN-GPX2WEB.md` et `CLAUDE.md` (section « Where to find
things »).

## Outputs

Créés :

- `docs/gpx2web-coverage.md`

Modifiés :

- `README.md`, `CLAUDE.md`, `docs/PLAN-GPX2WEB.md`

## Validation

Aucune commande — c'est un livrable documentaire. Critères de relecture :

- Chaque classe Java de `gpx2web/gpx` et `gpx2web/gpxtools-cli` a une ligne. Le compte de
  lignes du tableau égale le compte de fichiers `.java` de ces deux modules.
- Chaque statut « non porté » a une raison, pas une case vide.
- Les divergences de comportement de l'étape 3 sont toutes reprises depuis les fiches g01-g19.
- La conclusion nomme explicitement ce qui bloque l'archivage.

## Done when

- [x] Inventaire exhaustif, compte de lignes vérifié contre `find`
- [x] Statut et équivalent renseignés pour chaque classe
- [x] Toutes les raisons de non-port explicitées
- [x] Divergences de comportement rassemblées
- [x] Conclusion sur l'archivabilité de gpx2web
- [x] Document référencé depuis `README.md`, `CLAUDE.md` et `PLAN-GPX2WEB.md`

## Résultat

[`docs/gpx2web-coverage.md`](../gpx2web-coverage.md).

### Compte

**104 classes, 104 lignes**, correspondance bijective vérifiée par script dans les deux sens
(aucune ligne sans fichier, aucun fichier sans ligne) — pas à l'œil.

La fiche annonçait 89 classes pour le module `gpx` ; il y en a **96**. C'est précisément ce que
le garde-fou mécanique sert à attraper, et la raison pour laquelle une matrice partielle est pire
qu'aucune matrice.

Répartition des statuts :

| Statut | Nombre |
|---|---|
| porté | 48 |
| remplacé | 34 |
| déjà couvert (`:elevation`, antérieur au plan) | 13 |
| non porté | 8 |
| partiellement porté | 1 |

Le statut **partiellement porté** ne figurait pas dans la liste autorisée par la fiche. Il a été
ajouté pour `gpx.util.SmoothService` : `smoothEle` est porté, `smoothPower` / `smoothAeroCoef` /
`smoothSpeed` ne le sont pas. Le classer « porté » aurait été faux, « non porté » aussi.

### Les 8 non portés, et pourquoi

- `data.FastTimeIndex` — index temps → point, consommé uniquement par la webapp.
- `util.GPXDataComputer` — détection de croisements, idem, aucun consommateur dans le périmètre.
- `power.cyclist.OptimalSpeedService` + `OptimalSpeeds` — décision produit : conseiller une
  allure n'est pas simuler une sortie.
- `io.write.tabular.*` (4 classes) — l'abstraction CSV/XLSX perd son objet sans XLSX, écarté
  parce qu'Apache POI est JVM-only et pèse plusieurs Mo dans un module qui doit compiler en JS et
  en Wasm.

### Divergences rassemblées

Sept, reprises depuis les fiches g02, g03, g07, g12, g13, g14 et g17, plus une huitième trouvée
en écrivant le document : le lissage n'est porté que pour l'altitude (voir ci-dessus). La fiche
demandait de « compléter au fil de la relecture » — c'est ce qu'a produit la relecture.

Le point g07 était marqué « à confirmer » : confirmé en lisant les deux writers. gpx2web écrit
`{"keys": [...], "points": [{...}]}`, un objet par point ; vcyclist écrit un tableau par champ.

### Conclusion

`gpx` et `gpxtools-cli` sont remplacés. **`gpx-web` bloque l'archivage** : ses deux endpoints
REST, sa page Qute et surtout `PowerCurvePowerProvider`, seule logique métier de la webapp sans
équivalent vcyclist (ce serait un `CyclistPowerProvider` de plus, tâche courte si le besoin
venait). Trois options documentées sans être tranchées, comme demandé.

## Notes

- **Exhaustif ou inutile.** Une matrice partielle donne une fausse assurance : on croit avoir
  tout couvert parce que tout ce qui est listé est vert.
- **Le compte de lignes est le garde-fou** : `find … -name "*.java" | wc -l` doit correspondre
  au nombre de lignes du tableau. C'est mécanique et ça attrape les oublis.
- Ce document sert deux publics : l'auteur, pour décider du sort de gpx2web, et un utilisateur
  de gpx2web qui migre. Écrire pour le second — le premier connaît déjà le contexte.
- Ne pas y traiter la dépréciation de `elevation` et `virtual-cyclist` : ces deux dépôts sont
  couverts par `PLAN.md` et le harnais de parité, pas par ce plan.
