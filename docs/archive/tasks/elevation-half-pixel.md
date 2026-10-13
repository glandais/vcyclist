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

- [`docs/archive/tasks/elevation-bridge-decks.md`](elevation-bridge-decks.md) — *Mesures (E2)* (variantes
  (a) / (a½)), *Ponts pentus (E2 ter)* (diagnostics Cheviré / Millau / Normandie) et le banc
  `ElevationBenchProbeTest`. Ses chiffres sont la base de comparaison ; ce document ne les modifie
  pas.
- `cf0798e feat(elevation): add IGN LiDAR HD as a DEM source` — requête WMS `GetMap` par tuile XYZ.
- [`docs/guides/elevation.md`](../../guides/elevation.md) — décrit la grille comme des « posts » à
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

## Résultats (2026-10-13)

Mesuré sur `2fb3f03` + correctif, tuiles `build/dem-cache` (0 tuile téléchargée par le banc). La
colonne « ancien » est une **remesure du même jour** avec le calculateur d'avant (seul
`ElevationCalculator.kt` restauré), pas une recopie : ses chiffres E2 / E2 ter retombent exactement
sur ceux d'`elevation-bridge-decks.md`.

### H1 / H2 — le défaut, puis la correction

Les deux tests H1 (`ElevationCalculatorTest`) échouaient avant correction d'un demi-pixel exact :
au centre du pixel (1, 2), le bilinéaire rendait **152,5** au lieu de 102 (la moyenne du bloc
2 × 2 au sud-est) ; sur la rampe `7·gx + 3·gy`, **81,77** au lieu de 76,77, soit `(7 + 3) / 2 = 5`.
Correction : `fx = pf.x − ½`, `fy = pf.y − ½` dans `getInterpolatedElevation`, rien d'autre.
Choix 2 des *Notes* (voisin de poids nul et `NaN`) : laissé tel quel.

### H3 — banc des tabliers

**Contrôle de cohérence.** La variante (a½) est devenue (a⌐) « ancienne convention » :
`halfPixelShift` inversé (+½ px sud-est) puis le calculateur corrigé. (a⌐) reproduit **exactement**
l'ancien (a) (16/21, 8 / 8, 13 / 1, 83 / 25,18, flips 5), et le nouveau (a) reproduit **exactement**
l'ancien (a½) (14/21, 5 / 5, 11 / 3, 66 / 21,80, flips 7). La correction est donc bien, et
seulement, le demi-pixel qu'émulait (a½).

Variantes E2 (`PYTHON` sauf `ater`) :

| Variante | Ponts /21 ancien → nouveau | Hors vérité (span / tablier) | NO_CANYON / STEEP | ROUGH (n / km) | FP |
|---|---|---|---|---|---|
| (a) MNS − MNT z15 | 16 → **14** | 8 / 8 → **5 / 5** | 13 / 1 → 11 / 3 | 83 / 25,18 → 66 / 21,80 | 0 → 0 |
| (a⌐) ancienne convention | — → 16 | — → 8 / 8 | — → 13 / 1 | — → 83 / 25,18 | — → 0 |
| (b) MNH z15 | 16 → 14 | 8 / 8 → 5 / 5 | 13 / 1 → 11 / 3 | 83 / 25,18 → 66 / 21,80 | 0 → 0 |
| (c) MNH z14 | 13 → 15 | 19 / 19 → 45 / 30 | 53 / 6 → 46 / 2 | 101 / 42,18 → 99 / 33,29 | 0 → 0 |
| (d) MNH z13 | 9 → 11 | 866 / 729 → 254 / 220 | 88 / 4 → 84 / 3 | 94 / 46,41 → 98 / 43,11 | 2 → 1 |
| (f-ign) MNS − DEM ign | 16 → 14 | 8 / 8 → 5 / 5 | 13 / 1 → 11 / 3 | 83 / 25,18 → 66 / 21,80 | 0 → 0 |
| (f-mth) MNS − DEM mapterhorn | 14 → 14 | 42 / 42 → 48 / 48 | 28 / 3 → 10 / 3 | 76 / 30,13 → 63 / 20,92 | 0 → 0 |
| (f-mthc) canyon sur mapterhorn | 10 → 13 | 4 / 4 → 4 / 4 | 19 / 1 → 12 / 3 | 83 / 25,18 → 66 / 21,80 | 0 → 0 |
| (ater) (a) à DEFAULT | 16 → **14** | 8 / 8 → 5 / 5 | 13 / 1 → 11 / 3 | 83 / 25,18 → 66 / 21,80 | 0 → 0 |

Masque de tablier (a) contre la vérité B/b : 91,0 % → **86,8 %**. Les deux ponts perdus sont dans
`test2`, tous deux `STEEP` : *way 362043805* (fusionné avec Bellevue en un span 0,63–1,55 km,
P95 11,7 %) et *Pont Willy-Brandt* (P95 10,8 %), contre 10 %. Aucun gagné ; Bellevue passe de
`ROUGH` à `STEEP`, toujours manqué ; Échevis de `ROUGH` à « aucun candidat ».

E2 ter, `DEFAULT`, Strava et témoins :

| Critère | Ancien | Nouveau |
|---|---|---|
| Strava, ponts /21 (`PYTHON` = `DEFAULT`, mêmes clés) | 16 | **14** |
| Strava, FP | 0 | 0 |
| Strava, hors vérité (span / tablier) | 8 / 8 | 5 / 5 |
| Négatifs : FP | 0 | 0 |
| Négatifs : stations `G` / `O` / `L` (span et masque) | 0 / 0 / 0 | 0 / 0 / 0 |
| Beaujoire (`n04`, ses propres ponts 1/2) | trouvé, 1,75–1,95 | trouvé, 1,74–1,94 |
| Témoins positifs, spans trouvés | 13/15 | 13/15 (−`p06` Normandie, +`p04` D 57) |
| Témoins positifs, couverture `D` | 1 594 / 1 692 (**94 %**) | 1 240 / 1 692 (**73 %**) |
| `PYTHON` → `DEFAULT` (spans, couverture) | 11/15 → 13/15, 400 → 1 594 | 12/15 → 13/15, 471 → 1 240 |
| Rien de `PYTHON` perdu | oui | **non** : `p06` perd Normandie (que `PYTHON` couvre à 17 %) |
| `LOST_FIVE` toujours perdus à DEFAULT | oui | oui (aucun récupéré) |
| Témoin vallon `n13` : candidat mesuré au-delà du canyon, deux suites hautes | oui (0,30–0,44, `STEEP` 45,6 %) | **non** : plus aucun candidat mesuré (seul `ROUGH` 1 / 0,72 km reste) |

P95 de pente (propre / brut), couverture `D` par témoin positif, `DEFAULT` :

| Témoin | Ancien | Nouveau |
|---|---|---|
| `p01` Cheviré | 9,7 / 24,5 %, `DECK`, 100 % | **7,4 / 13,0 %**, `DECK`, 100 % |
| `p02` Thouaré | 6,2 / 8,0 %, 94 % | 6,4 / 6,3 %, 94 % |
| `p03` Ancenis | 1,7 / 1,7 %, 93 % | 1,7 / 1,7 %, 93 % |
| `p04` Langeais | non mesuré (`ROUGH`), **0 %** | 2,8 / 2,8 %, `DECK`, **94 %** |
| `p05` Saumur | 0,3 / 2,3 et 2,3 %, 100 % | 0,2 / 1,6 et 1,6 %, 100 % |
| `p06` Normandie | 6,5 / 53,5 %, `DECK`, **99 %** | **41,1 / 51,8 %, `STEEP`, 4 %** |
| `p07` Millau | 4,2 / 34,8 %, `DECK`, 100 % | 3,7 / **14,3 %**, `DECK`, 100 % |
| `p08` périph | 8,4 / 8,4 %, 100 % | 7,0 / 7,0 %, 100 % |

Les P95 bruts recoupent le port Python d'E2 ter (Cheviré 24,5 → 13,0, Millau 34,8 → 14,3 : mêmes
chiffres au dixième). Normandie diffère du port (53,5 → 51,8 ici, 35,1 → 50,6 là-bas), mais va dans
le même sens pour la métrique qui compte : **le P95 propre passe de 6,5 à 41,1 %**, 110 stations sur
422 au-dessus de 10 %, toutes au milieu du tablier (0 / 110 / 0), jusqu'à +154 % ; aux pires
stations le MNT est **au-dessus** du MNS (km 1,965 : MNS 64,7, MNT 105,8) ou à 0,7 m d'altitude,
30 à 40 m sous un MNS de 30,5–41,1 m (km 1,835–1,840) : la trace longe le haubanage et le MNS y alterne pylône / vide.

Balayage E2 ter (une variation à la fois depuis `DEFAULT`), lignes qui changent de sens :

| Réglage | Ancien : Strava, témoins | Nouveau : Strava, témoins |
|---|---|---|
| `DEFAULT` | 16, 13/15 94 % | 14, 13/15 73 % |
| `tallMnhM` 4,0 | 13 (−3 `STEEP`), 14/15 95 % | 14, **15/15 83 %** |
| `tallMnhM` 3,0 / 2,5 | 14 / 15, 14/15 95 % | 14 / 14 (2,5 : 1 FP Strava), 15/15 83 % |
| `maxSlopeP95` 0,12 / 0,15 | 16, 13/15 94 % | 17 (Bellevue et les deux `STEEP` perdus), 13/15 73 %, hors vérité 110 / 33 |
| `mergeGapM` 200 | 17 (+Haudaudine), 13/15 94 % | 15 (+Bellevue), 14/15 77 % (Normandie 3/3, 17 %) |
| `surfaceMedianWindowM` 35 | 18, 13/15 88 % | 15, 13/15 73 % |
| `surfaceMedianWindowM` 15 | 14, 12/15 53 % | 12 (−Tabarly, −Pirmil), 9/15 17 % |
| `minSmoothFraction` 0,5 / 0,4 | 17, 14/15 99 %, −Beaujoire aux deux | 14, 13/15 73 %, −Beaujoire à 0,4 seulement |
| `minCanyonDropM` −1 | 16, FP Strava 3, négatifs 2 FP (`n04`, `n09`) / 10 `O` | 14, FP Strava 0, négatifs 2 FP (`n02`, `n04`) / 10 `O` |

**Constat H3.** Les critères d'acceptation des négatifs tiennent tous (0 FP, 0 `G`/`O`/`L`,
Beaujoire trouvé) : **H5 ne se déclenche pas**, aucun seuil n'est touché. Ce qui se perd est côté
positifs : deux ponts Strava (16 → 14, tous deux `STEEP` à 10,8–11,7 % pour un seuil de 10 %), et
Normandie (99 % → 4 %), qui fait tomber la couverture des témoins de 94 à 73 % ; Langeais, jamais
trouvé aux coins, l'est aux centres. Cheviré, le cas le plus exposé (9,7 % pour 10 %), s'éloigne du
seuil (7,4 %). Le témoin vallon `n13` ne mesure plus de candidat à `DEFAULT` : il reste négatif,
mais ne teste plus rien. Le banc est recalé sur ces valeurs, en égalités pour qu'un mouvement dans
un sens comme dans l'autre se voie : ponts trouvés, stations hors vérité (`PYTHON` et `DEFAULT`),
couverture `D` des témoins (1 240 / 1 692), spans perdus (`LOST_AT_CENTRES`, Normandie manquant à
`p06`), `n13` sans témoin (`RAVINES_WITHOUT_WITNESS`) ; seuls restent des planchers les critères
qui l'étaient déjà (0 FP, 0 `G`/`O`/`L`, couverture ≥ 95 % de `p01`/`p07`) ; décision 3 des *Notes* : accepté, pas compensé.

> **Suite (2026-10-13).** Le DEFAULT a ensuite été recalé aux centres, une fois la deuxième série
> de témoins gelée et sur le seul jeu de réglage : `elevation-bridge-decks.md`, *Recalage aux centres
> (E2 quinquies)* (Strava 18/21, témoins 14/15 à 98 %, négatifs 0 FP / 0 `G`/`O`/`L`).

### H4 — pipeline, deux sources

E1 (`bench()`), ancien → nouveau. D+ rapporté (préréglage `dem`) et D+ dem en mètres, RMS terrain
ouvert en mètres, « > 15 % » en stations de `sourceElevation` :

| Trace | Source | med. ouvert | D+ rapporté | D+ dem (/vrai) | D+ brut | RMS ouvert | > 15 % src |
|---|---|---|---|---|---|---|---|
| test1 (vrai 72) | mapterhorn | +0,03 → +0,01 | 47 → 12 | 62 → 52 (0,86 → 0,73) | 117 → 107 | 0,29 → **0,24** | 19 → 26 |
| | ign-lidar-hd | −0,01 → −0,00 | 12 → 12 | 59 → 53 (0,82 → 0,74) | 137 → 125 | 0,24 → 0,20 | 30 → 18 |
| test2 (vrai 89) | mapterhorn | +0,03 → −0,04 | 71 → 80 | 133 → 130 (1,49 → 1,46) | 227 → 223 | 0,63 → **0,43** | 24 → 21 |
| | ign-lidar-hd | +0,00 → −0,01 | 85 → 87 | 138 → 136 (1,56 → 1,52) | 220 → 225 | 0,29 → 0,32 | 48 → 48 |
| test3 (vrai 18) | mapterhorn | −1,53 → **+0,32** | 7 → 7 | 20 → 24 (1,13 → 1,35) | 65 → 76 | 1,68 → **0,66** | 0 → 0 |
| | ign-lidar-hd | −0,18 → +0,26 | 12 → 11 | 29 → 35 (1,61 → 1,94) | 92 → 109 | 0,59 → **0,78** | 0 → 0 |
| test4 (vrai 2 414) | mapterhorn | −0,25 → +0,13 | 2 708 → 2 738 | 2 909 → 2 858 (1,21 → 1,18) | 3 322 → 3 136 | 2,63 → **0,58** | 483 → 395 |
| | ign-lidar-hd | +0,01 → +0,01 | 2 722 → 2 732 | 2 870 → 2 872 (1,19 → 1,19) | 3 136 → 3 100 | 0,46 → 0,24 | 398 → 402 |

La vérité E1 est échantillonnée par `fetch.py`, aux centres : en terrain ouvert, l'ancien RMS
mesurait donc en partie le demi-pixel lui-même. Mapterhorn en profite le plus (test4 2,63 → 0,58 m,
test3 1,68 → 0,66 m, biais de test3 −1,53 → +0,32 m) ; c'est l'effet *Inputs §4* annoncé, et il
n'était pas de « plusieurs décimètres » mais de plus d'un mètre. Une régression : IGN sur test3,
RMS 0,59 → 0,78 m et D+ dem 29 → 35 m (vrai 18). Le D+ dem de test4 en Mapterhorn baisse de 51 m
(1,21 → 1,18 du vrai).

R30 (`--fix-elevation --dem-zoom Z --no-simplify`, CLI, mapterhorn ; D+ rapporté `dem` / durée) :

| Trace | Convention | z12 | z13 | z14 | z15 |
|---|---|---|---|---|---|
| `stelvio` | ancienne | 131 m / 568 s | 130 / 568 | 130 / 568 | 130 / 568 |
| | nouvelle | 132 m / 572 s | 130 / 568 | 130 / 568 | 130 / 568 |
| `strava` | ancienne | 726 m / 2 955 s | 710 / 2 945 | 718 / 2 944 | 724 / 2 944 |
| | nouvelle | 714 m / 2 945 s | 728 / 2 944 | 735 / 2 945 | 736 / 2 945 |

Écart entre zooms : 1,5 % (`stelvio`) et 3,0 % (`strava`) sur le D+, ≤ 0,7 % sur la durée.
**Verdict R30 inchangé** (négligeable). Le 854 m du ledger (2026-08-18) n'est pas reproductible
avec le code et les tuiles d'aujourd'hui, même à l'ancienne convention (726 m le même jour) ; la
cause n'a pas été bissectée (le préréglage de D+ existait déjà en 1f2364e ; 235c889 a changé
depuis le lisseur et l'étape d'ouverture d'`ElevationGain`).

R29 (`RoadSnapProbeTest`, Mapterhorn z12, décalage latéral → D+ / rugosité / max |dh|) :

| Décalage | `stelvio` ancien | `stelvio` nouveau | `strava` ancien | `strava` nouveau |
|---|---|---|---|---|
| −15 m | 189 / 1,499 / 14,0 | 184 / 1,369 / 12,9 | 834 / 1,658 / 15,1 | 830 / 1,643 / 15,6 |
| −5 m | 132 / 0,985 / 5,0 | 131 / 0,808 / 4,1 | 747 / 0,649 / 5,4 | 759 / 0,627 / 5,4 |
| **0 m** | **131 / 0,885** | **132 / 0,681** | **728 / 0,226** | **722 / 0,224** |
| +5 m | 131 / 0,912 / 4,5 | 133 / 0,746 / 5,2 | 720 / 0,620 / 5,4 | 700 / 0,600 / 5,0 |
| +15 m | 180 / 1,288 / 12,5 | 179 / 1,185 / 16,5 | 747 / 1,532 / 14,3 | 732 / 1,536 / 14,2 |
| par station | 153 / 6,692 | 154 / 8,009 | 809 / 8,265 | 802 / 8,092 |

La trace enregistrée reste le minimum de rugosité de son couloir sur les deux traces :
**verdict R29 inchangé**. Deux chiffres d'`elevation.md` bougent : le déplacement latéral change
une altitude isolée de jusqu'à **16,5 m** (15,1 m à l'ancienne convention le même jour ; 14 m
dans la mesure d'origine), et, sur `strava`, `+5 m` et `+10 m` donnent
un D+ un peu *plus bas* que le centre (700 et 708 contre 722) — c'était déjà vrai aux coins le même
jour (720 contre 728) : la phrase « tout déplacement aggrave les deux chiffres » était fausse sur les
tuiles d'aujourd'hui, avant ce correctif. La table d'`elevation.md` (`strava` 861 m au centre)
n'est pas reproductible avec le code et les tuiles d'aujourd'hui (728 m aux coins le même jour) ;
code (235c889) ou tuiles, la cause n'a pas été mesurée.

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
