# elevation-half-pixel — interpoler entre centres de pixels, pas entre coins

## Goal

Supprimer le décalage d'un **demi-pixel vers le sud-est** que `ElevationCalculator` applique à
chaque lecture bilinéaire de MNT ou de MNS. Le calculateur place la valeur du pixel `(i, j)` sur le
**coin** nord-ouest du pixel ; toutes les sources lues (WMS IGN, Mapterhorn, Terrarium) la
définissent au **centre**. Chaque altitude interpolée est donc celle du terrain situé
`+½ px` à l'est et `+½ px` au sud du point demandé :

| Source (configuration) | Pixel à 44–49° N | Décalage par axe | Décalage diagonal |
|---|---|---|---|
| IGN LiDAR HD, z15, 256 px | 3,1–3,4 m | **1,55–1,72 m** | 2,2–2,4 m |
| Mapterhorn, z12, 512 px (défaut) | 12,4–13,7 m | **6,2–6,9 m** | 8,8–9,7 m |

Le but est une lecture géométriquement juste, **puis** une remesure de tout ce qui a été réglé sur
la lecture fausse. Ce n'est pas un réglage : si un chiffre se dégrade, il est rapporté, pas
compensé.

Décision déjà prise : tâche **séparée** de `elevation-bridge-decks`, qui reste calée sur la
convention des coins jusqu'à ce que celle-ci soit livrée (*Ponts pentus (E2 ter)* : « Corriger la
convention de pixel reste un sujet séparé »).

## Depends on

- [`docs/tasks/elevation-bridge-decks.md`](elevation-bridge-decks.md) — *Mesures (E2)* (variantes
  (a) / (a½)), *Ponts pentus (E2 ter)* (diagnostics Cheviré / Millau / Normandie) et le banc
  `ElevationBenchProbeTest`. Ses chiffres sont la base de comparaison ; ce document ne les modifie
  pas.
- `cf0798e feat(elevation): add IGN LiDAR HD as a DEM source` — requête WMS `GetMap` par tuile XYZ.
- [`docs/guides/elevation.md`](../guides/elevation.md) — décrit la grille comme des « posts » à
  13,5 m, sans dire où un post se trouve dans son pixel.

## Inputs

### 1. Où le décalage naît, précisément

`ElevationFunctions.toPixelFloat` (`elevation/src/commonMain/.../ElevationFunctions.kt`) rend la
position **continue** du point dans la tuile, en unités de pixel : `px = (xFloat − x) · tileSize`.
Le bord ouest de la tuile est `px = 0`, le bord est `px = tileSize`. C'est juste et doit le rester.

`ElevationCalculator.getInterpolatedElevation` (`ElevationCalculator.kt`, l. 56–71) en déduit :

```kotlin
val x0i = floor(pf.x).toInt()   // pixel (x0i, y0i) pris comme situé en (x0i, y0i)
val dx = pf.x - x0i             // poids de x0i + 1
```

Il traite donc l'entier `i` comme l'emplacement de l'échantillon `i`. Or l'échantillon `i` couvre
`[i, i + 1)` et vaut pour son centre, `i + 0,5`. Conséquence : au centre exact du pixel `i`
(`pf.x = i + 0,5`), le calculateur rend `½ p(i) + ½ p(i + 1)` au lieu de `p(i)` ; en général il
rend la surface bilinéaire vraie évaluée en `(pf.x + 0,5, pf.y + 0,5)`. Comme `y` croît vers le
sud dans la grille XYZ, le terrain lu est au **sud-est** du point.

Ce que **ne** touche **pas** le décalage :

- le mode `interpolation = false` (`toPixel` puis `floor`) : il rend le pixel qui **contient** le
  point, ce qui est juste en convention de centres. Aujourd'hui les deux modes sont donc
  incohérents entre eux d'un demi-pixel ;
- `Tile.kt` : il ne fait que décoder `(x, y)` → altitude, sans géométrie ;
- la conversion lat/lon → tuile, et le `wmsBbox4326` de la requête IGN (bords de tuile exacts).

La projection IGN (EPSG:4326, lignes linéaires en latitude dans la tuile) diffère de Mercator de
moins de 0,005 px à z15 (KDoc d'`ignWmsTemplate`) : négligeable devant 0,5 px.

### 2. Toutes les sources lues sont en convention de centres

- **WMS IGN (LiDAR HD MNT / MNS / MNH)**. Le `BBOX` d'un `GetMap` désigne les **bords extérieurs**
  de l'image : le pixel `(i, j)` d'une réponse `WIDTH × HEIGHT` couvre une cellule dont le centre
  est à `ouest + (i + ½)·Δ`. C'est le modèle OGC (WMS 1.3.0, OGC 06-042) tel que MapServer l'a
  documenté pour s'y aligner : [MS RFC 25](https://mapserver.gis.umn.edu/ar/development/rfc/ms-rfc-25.html),
  [MS RFC 107](https://mapserver.org/development/rfc/ms-rfc-107.html), et la lecture du
  §6.5.6 de WMS 1.1.0 dans le [ticket MapServer #2843](https://trac.osgeo.org/mapserver/ticket/2843).
  Le texte normatif de 06-042 n'a pas été relu pour cette spec. Confirmation indépendante, dans le
  dépôt : `docs/deck-strava-data/analysis/fetch.py` interpole les mêmes tuiles BIL avec
  `fk = -lat / PX - 0.5`, `fc = lon / PX - 0.5`, c'est-à-dire entre centres.
- **Mapterhorn** (source par défaut, Terrarium WebP, 512 px). Vérifié dans le code de son
  pipeline (`github.com/mapterhorn/mapterhorn`, commit `775663e`, 2026-10-08) :
  `pipelines/aggregation_reproject.py` reprojette par `gdalwarp -t_srs EPSG:3857 -tr <tuile/512>
  -te <bords mercantile.xy_bounds de la tuile> -r cubicspline`, donc une grille GDAL dont l'origine
  est le **coin** nord-ouest de la tuile et dont chaque valeur est calculée au **centre** de sa
  cellule ([modèle raster GDAL](https://gdal.org/en/stable/user/raster_data_model.html) : le
  `GeoTransform` donne le coin haut-gauche du pixel haut-gauche). Les zooms inférieurs sont
  produits par `pipelines/downsampling_run.py` en moyennant des blocs 2 × 2
  (`reshape((512, 2, 512, 2)).mean(axis=(1, 3))`), ce qui conserve la convention des centres.
- **Terrarium historique (Mapzen / Tilezen, AWS Terrain Tiles)**, pour un appelant qui configure
  son propre `tileUrlTemplate` : `joerd/mercator.py` (`github.com/tilezen/joerd`, commit
  `0b86765`) construit `dst_gt = (bbox.minx, res, 0, bbox.maxy, 0, -res)` sur les bords de la tuile,
  puis `gdal.ReprojectImage` : même modèle, centres.

Le décalage s'applique donc à **toutes** les sources, avec une amplitude proportionnelle au pixel :
**quatre fois plus grand en Mapterhorn z12 qu'en IGN z15**, en mètres.

### 3. Impact mesuré (IGN z15, détecteur de tablier)

Les chiffres qui suivent viennent de `elevation-bridge-decks.md` ; ils ne sont pas refaits ici.

- *Mesures (E2)*, banc Kotlin, variantes (a) MNS − MNT z15 aux coins et (a½) idem aux centres
  (la variante (a½) émule les centres en déplaçant les stations d'un demi-pixel nord-ouest,
  `halfPixelShift` dans `ElevationBenchProbeTest`, sans toucher au calculateur), à
  `DeckDetectorOptions.PYTHON` :

  | Variante | Ponts /21 | FP | Hors vérité (span / tablier) | NO_CANYON / STEEP | ROUGH (n / km) |
  |---|---|---|---|---|---|
  | (a) coins | 16 | 0 | 8 / 8 | 13 / 1 | 83 / 25,18 |
  | (a½) centres | 14 | 0 | 5 / 5 | 11 / 3 | 66 / 21,80 |

  Moins de stations hors vérité, moins de canopée rejetée, mais deux ponts de moins : les seuils
  ont été retouchés depuis (E2 bis, E2 ter) **sur la convention des coins**.
- *Ponts pentus (E2 ter)*, port Python **non commité, non reproductible depuis le dépôt**, P95 brut
  de pente sur le tablier, coins → centres : Cheviré (`p01`) 24,5 → **13,0 %**, Millau (`p07`)
  34,8 → 14,3 %, Normandie (`p06`) 35,1 → **50,6 %**. Le biais dépend du sens de parcours et du côté
  du tablier où passe la trace : à Cheviré la trace longe le bord à ≈ 3 m d'un vide de 40–55 m, et
  le demi-pixel (≈ 1,5 m) pousse l'échantillon vers le vide. La convention seule ne corrige aucun
  des trois.

### 4. Impact attendu, non mesuré (Mapterhorn z12, pipeline)

- L'erreur d'un point vaut `∇z · (Δest, Δsud)`, soit au plus `|∇z| · 9 m` en Mapterhorn z12.
  Sur une pente régulière, la composante **le long** de la route ne fait que décaler l'abscisse
  de quelques mètres (sans effet sur le D+) ; la composante **en travers** lit une ligne parallèle
  plus haut ou plus bas sur le versant. Elle est constante sur une traversée, mais **change de
  signe à chaque lacet** : c'est là que le D+ et les pentes de la physique peuvent bouger.
  `elevation.md` (*Snapping to the road*) a mesuré qu'un déplacement latéral de ±15 m change des
  altitudes isolées jusqu'à 14 m : à 6–9 m, quelques mètres sont plausibles dans un déblai.
- En terrain ouvert, E1 (*Mesures de référence (E1)*) donne un RMS de 0,24–0,59 m en IGN et de
  0,29–2,63 m en Mapterhorn (test3 1,68 m, test4 2,63 m), et une médiane de biais de −1,53 à
  +0,03 m toutes sources confondues. Le demi-pixel y est déjà inclus ; en **IGN**, il ne peut donc
  pas être grand. En Mapterhorn, ces chiffres ne bornent rien : le RMS de test3 / test4 laisse la
  place à un effet de plusieurs décimètres.
- R30 (zoom DEM > 12, « Negligible (measured) ») et R29 (snapping, « None ») ont été mesurés avec
  le décalage. Une lecture juste ne les invalide pas a priori, mais leurs chiffres changeront. Ils
  ne viennent pas du même banc : R30 vient des mesures de D+ (E1 et le ledger), R29 de
  `RoadSnapProbeTest` (sensibilité latérale ±15 m → jusqu'à 14 m, chiffres de *Snapping to the
  road* dans `elevation.md`).

### 5. Ce qui bougera

Tests unitaires (synthétiques, valeur `px · 100 + py` ou rampe) :

- `ElevationCalculatorTest` : `interpolation=true with dx=0 dy=0 equals nearest-pixel` (en
  `(0, 0)`, z0, 4 px, le point est sur un **coin** commun à quatre pixels : il vaudra
  `(101 + 201 + 102 + 202) / 4 = 151,5`, et non plus 202) ; `bilinear interpolation matches weighted
  formula` et `dx=0_5 dy=0_5 averages all four neighbours` (leur formule attendue recopie la
  convention des coins) ; `interpolation overflows tile via normalizePixel` (en `(0, −22,5)`, z1, 4 px :
  `px = 3,5` → `fx = 3,0`, donc `x0 = 3` et `x1 = 4` lit encore la tuile à l'est, avec un poids nul ;
  seul `y` change, `py = 0` → `fy = −0,5`, la ligne `y0` passe à la tuile au **nord**. Le test, qui
  n'affirme que `isFinite`, reste vert ; seul son commentaire est à corriger, et il est déjà faux
  aujourd'hui : il annonce `py = 4,0` quand `py = 0`).
- `FormalModelTileEdgeTest` : son KDoc dit que le bilinéaire lit toujours `x0 + 1` / `y0 + 1` ;
  avec `− ½`, il lit `x0 − 1` dans la moitié ouest d'un pixel, donc le bord **ouest** / **nord** du
  monde (tuile 0, `x = −1`) devient un cas, à couvrir comme le bord est.
- `IgnLidarHdLayerTest` `a single no-data pixel makes its bilinear neighbourhood NaN, and only
  that` : **inchangé**, et pour la même raison qu'aujourd'hui. À z0, 256 px, la longitude −0,7°
  donne `px = 127,502` → `fx = 127,002` : le voisin sans donnée `x1 = 128` pèse `≈ 0,002`, petit mais
  non nul ; +0,7° donne `fx = 127,998`, poids `≈ 0,998`. Les deux restent `NaN` par un voisin de
  poids non nul ; le cas `NaN · 0` n'y est jamais exercé.
- `ElevationBridgeTest` (`engine/src/wasmWasiTest`) : `expectedAt` évalue la rampe en
  `toPixelFloat` ; la rampe est reproduite exactement par le bilinéaire, donc l'attendu devient la
  rampe en `(pf.x − ½, pf.y − ½)`.
- A priori verts, **marge réduite** : `ElevationProviderFetcherJavaTest.blockingFetchersRunInParallel`
  (z4, latitude 0, donc `py = 0`) passe de 4 à 8 tuiles lues : chaque point demande aussi la tuile au
  nord, l'une après l'autre dans son groupe. ≈ 400 ms pour une borne de 800 ms : la marge est divisée
  par deux. Le sortir de la latitude 0 si elle devient instable.
- **Pas** touchés : `ElevationProviderTest` (`interpolation = false`), `ElevationFunctionsTest`
  (`toPixel`), `TileTest`, tout ce qui ne lit pas de DEM (`fixElevation` désactivé : fixtures,
  `tools/wasi`, `EnhanceCommandTest`).

Tests réseau (`INTEGRATION=1`), tolérances larges, a priori verts : `ElevationProviderIntegrationTest`
(±50 m), `IgnLidarHdIntegrationTest` (Puy de Dôme 1 462,8 ± 3 m, IGN vs Mapterhorn < 10 m),
`EnhanceWithElevationNodeTest` (bornes 1 500–5 000 m). Le Puy de Dôme est un sommet : décalé, il
lisait un point plus bas ; corrigé, l'écart doit diminuer.

Banc `ElevationBenchProbeTest` :

- **non conditionnés** (`truth_files_parse_and_hold_their_guards`,
  `deck_detector_reproduces_python_on_its_own_samples`, `deck_controls_parse_and_hold_their_guards`)
  : ils lisent les échantillons de `fetch.py`, déjà aux centres. **Inchangés.**
- Trois tests `INTEGRATION=1`, dont tous les chiffres échantillonnés par vcyclist bougent :
  - `bench()` (E1) : n'affirme que l'intégrité des données (nombres de lignes, de structures) ;
    ce sont ses **tableaux** E1 qui bougent, pas ses assertions ;
  - `deckDetector()` (E2) : `assertEquals(16, …)` / `8` / `8` pour la variante (a) à `PYTHON`,
    `assertEquals(0, fpByVariant["a"])`, variante (ater) à `DEFAULT` : `16`,
    `e2.foundKeys == e2ter.foundKeys`, `0` FP, `≤ 8` / `≤ 8` hors vérité ;
  - `deckControls()` (E2 bis à quater) : Strava à `PYTHON` (`pyStrava` : `16`, `0` FP, `8` / `8`)
    et à `DEFAULT` (`baseStrava` : `16`, `pyStrava.foundKeys == baseStrava.foundKeys`, `0` FP,
    `≤ 8` / `≤ 8`) ; négatifs : `0` FP, `0` station `G` / `O` / `L`, Beaujoire trouvé ; rien de
    `PYTHON` perdu ; témoins positifs : couverture `D` `≥ 90 %`, `13` spans trouvés, `≥ 95 %` sur
    `p01`, `p06`, `p07` ; le témoin vallon `n13` doit garder au `DEFAULT` un candidat mesuré
    au-delà du canyon, à deux suites hautes (fragile : décalé de 0,4 m, il disparaît) ; enfin
    `lostFive.values.forEach { assertTrue(it !in baseStrava.foundKeys) }` (`LOST_FIVE`) —
    **celle-ci casse sur une amélioration** : si la lecture aux centres récupère un des cinq ponts
    perdus, elle passe au rouge, et c'est une bonne nouvelle à écrire, pas un défaut ;
  - la variante (a½) : gardée telle quelle, `halfPixelShift` (½ px nord-ouest) suivi du
    calculateur corrigé lit la surface vraie en `p − ½`, un demi-pixel au **nord-ouest** : l'image
    miroir de l'ancien défaut. Inversée (+½ px sud-est), elle reproduit l'ancienne lecture aux coins
    (exactement en Mercator ; à moins de 0,005 px près sur les tuiles IGN en EPSG:4326, voir §1)
    (voir *Steps*).
- Tableaux E1 (D+, RMS terrain ouvert, pentes > 15 %) pour `ign-lidar-hd` **et** `mapterhorn`.

Seuils de `DeckDetectorOptions` réglés sur la convention des coins (E2 bis, E2 ter) : `tallMnhM`,
`maxRoughnessM` (seuil propre et filtre des suites), `roughnessWindowM`, `surfaceMedianWindowM`,
`minCleanFraction`, `minCleanStationsCount`, `maxSlopeP95` (0,10). Le plus exposé : **Cheviré
accepté à 9,7 % pour un seuil de 10 %** à DEFAULT ; le moindre déplacement du profil le fait
basculer, dans un sens ou dans l'autre.

Documentation : `elevation.md` (« bilinearly interpolated », « posts » de 13,5 m, chiffres de
*Snapping to the road*), KDoc d'`ElevationCalculator` et de `halfPixelShift`, lignes R29 / R30 du
ledger si leurs chiffres changent.

## Steps

### H1 — Épingler le défaut avant correction

Un test `commonTest` qui échoue aujourd'hui : tuile synthétique, point au **centre** exact d'un
pixel (z0, 4 px), `interpolation = true` doit rendre la valeur de ce pixel, comme
`interpolation = false`. Un second : sur une rampe linéaire `a·x + b·y`, l'altitude bilinéaire au
point `(px, py)` vaut `a·(px − ½) + b·(py − ½)` en indices de pixel. Les deux échouent sur
`develop`, d'un demi-pixel exactement.

### H2 — Correction

Dans `ElevationCalculator.getInterpolatedElevation` seulement : `val fx = pf.x - 0.5`,
`val fy = pf.y - 0.5`, puis `floor`, `dx`, `dy` sur `fx` / `fy`. `toPixelFloat` reste la position
géométrique (il sert aussi au découpage en tuiles et à `ElevationBridgeTest`). `x0i = −1` est déjà
géré par `normalizePixel` (passage à la tuile voisine, ou bord du monde clampé). KDoc : énoncer la
convention (« la valeur d'un pixel vaut pour son centre ; WMS, GDAL, Mapterhorn, Terrarium »).

Mettre à jour les tests unitaires listés en *Inputs §5* — attendus **recalculés**, pas recopiés de
la sortie. Ajouter le cas bord ouest / nord du monde à `FormalModelTileEdgeTest`.

### H3 — Remesure du banc, sans retouche de seuil

`INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*ElevationBenchProbeTest*' --rerun-tasks -i`
(tuiles en cache `build/dem-cache` : aucune nouvelle tuile, la même grille est lue autrement).

- variante (a½) : supprimer `halfPixelShift`, ou le garder **inversé** (décaler vers le sud-est) :
  appliqué au calculateur corrigé, il reproduit exactement l'ancienne lecture aux coins, une
  variante « ancienne convention » comparable. Gardé tel quel, il lirait un demi-pixel au
  nord-ouest, l'image miroir de l'ancien défaut ;
- rapporter dans ce document, à côté de l'ancien : tableau des variantes E2, tableau E2 ter
  (couverture `D`, spans positifs, FP, hors vérité, Beaujoire), P95 par témoin positif, balayage
  E2 ter ;
- remplacer les égalités du banc par les nouvelles valeurs **seulement après** avoir écrit le
  constat ; une perte (pont, couverture) est un résultat, pas un bug du correctif.

### H4 — Remesure du pipeline (Mapterhorn et IGN)

Refaire *Mesures de référence (E1)* (D+ rapporté, D+ dem, RMS terrain ouvert, pentes > 15 %) pour
les deux sources, puis `RoadSnapProbeTest` pour R29 et le tableau *Snapping to the road*
d'`elevation.md` :

```bash
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*RoadSnapProbeTest*' --rerun-tasks -i
```

Attendu : variation faible en terrain ouvert, à chercher dans les lacets en Mapterhorn. Si R29 ou
R30 change de verdict, le noter dans le ledger.

### H5 — Seuils du détecteur : décision, pas réglage automatique

Seulement si H3 dégrade un critère d'acceptation (FP ou stations `G`/`O`/`L` sur les négatifs,
Beaujoire perdue). Avant de toucher un seuil, les **témoins E2 ter** de
`elevation-bridge-decks.md` (`p09..`, `n13..`) doivent exister : les sites `p01`, `p06`, `p07`,
`p08` sont déjà des cas de réglage, ils ne prouvent plus rien.

## Outputs

- `ElevationCalculator.kt` corrigé, KDoc de convention.
- Tests unitaires mis à jour + H1 + bord ouest / nord du monde.
- `ElevationBenchProbeTest` : variante de comparaison réécrite, assertions recalées.
- Résultats H3 / H4 dans ce document ; `elevation.md` et, au besoin, le ledger corrigés.
- `docs/README.md` : ligne déplacée vers l'archive une fois livrée.

## Validation

```bash
./gradlew :elevation:allTests :engine:allTests
INTEGRATION=1 ./gradlew :elevation:jvmTest --tests '*Integration*'
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*ElevationBenchProbeTest*' --rerun-tasks -i
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*RoadSnapProbeTest*' --rerun-tasks -i
./gradlew check
```

Tolérances : `1e-12` sur les tuiles synthétiques (rampe, centres exacts), ±1 m en Terrarium réel.
Jamais de `==` sur un `Double`.

## Done when

- Le bilinéaire rend exactement la valeur du pixel en son centre, et une rampe sans décalage, sur
  JVM, JS et wasmWasi.
- Les deux modes (`interpolation` vrai / faux) lisent le même pixel au centre de celui-ci.
- Le banc a été remesuré et ses chiffres écrits ici à côté des anciens, avec le constat, que le
  résultat soit meilleur ou pire.
- `elevation.md` énonce la convention des centres.

## Notes

- **Pourquoi ce n'est pas corrigé dans `elevation-bridge-decks`** : tous les seuils E2 bis / E2 ter
  y ont été réglés et vérifiés aux coins ; changer la lecture au milieu de cette tâche aurait mêlé
  deux causes dans chaque écart de mesure.
- **Le correctif ne promet pas de meilleurs ponts.** (a½) a perdu 2 ponts sur 21 en E2, et le port
  Python montre Normandie qui empire (35,1 → 50,6 %). Il promet une lecture juste ; la qualité du
  détecteur se rejuge après.
- **Voisin de poids nul.** Avec `NaN`, un pixel de poids 0 rend quand même `NaN`. Le cas existe déjà
  aujourd'hui (à toute position entière de `pf`), mais la correction le place au **centre exact**
  d'un pixel (`dx = dy = 0`), où il serait logique de ne lire que celui-ci. `IgnLidarHdLayerTest` ne
  l'exerce pas. C'est un changement de comportement du
  sans-donnée distinct de la convention ; à décider dans H2, pas à glisser dedans.
- **Décisions ouvertes** (une à la fois, avec la réponse par défaut proposée) :
  1. Variante (a½) du banc : supprimer, ou l'inverser en « ancienne convention » ? — *Défaut :
     l'inverser, une remesure veut un point de comparaison.*
  2. Poids nul et `NaN` : ignorer le voisin de poids 0 ? — *Défaut : non dans cette tâche.*
  3. Si H3 perd un pont ou de la couverture : accepter, ou ouvrir H5 ? — *Défaut : accepter et
     écrire le constat ; H5 seulement après les témoins E2 ter.*
  4. Remesurer R29 / R30 (H4) dans cette tâche, ou plus tard ? — *Défaut : dans cette tâche, les
     deux : D+ et RMS (E1, R30) et `RoadSnapProbeTest` (R29, *Snapping to the road*).*
