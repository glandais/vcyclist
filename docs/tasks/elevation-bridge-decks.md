# elevation-bridge-decks — rendre aux ponts leur tablier sur les zones LiDAR HD

## Goal

Sur les zones couvertes par l'IGN LiDAR HD, donner aux tracés l'altitude **de la chaussée
réellement parcourue**, et non celle du terrain nu sous les ouvrages. Concrètement :

1. supprimer le « canyon » qu'un MNT creuse sous chaque pont (6 à 66 m mesurés sur la Loire) ;
2. le remplacer par le **profil du tablier** lu dans le MNS LiDAR HD, là où il existe ;
3. ensuite seulement, réévaluer le lissage de 150 m et le preset de D+, dimensionnés pour un MNT à
   30 m et non pour du LiDAR.

Ce document est l'**analyse** qui précède le code. Elle croise la vérification empirique faite dans
le projet voisin `openelevation` (franchissements de la Loire, Nantes → Saint-Nazaire, 2026-10-12)
avec l'état du module `:elevation` après `cf0798e` (IGN LiDAR HD comme source de MNT).

## Depends on

- `cf0798e feat(elevation): add IGN LiDAR HD as a DEM source` — catalogue `ElevationSource`,
  décodeur `decodeBil32Tile`, requêtes WMS `GetMap` par tuile XYZ, cadence à 20 req/s, repli
  Mapterhorn sur le *no-data*. Tout ce qui suit le réutilise.
- [`docs/guides/elevation.md`](../guides/elevation.md) — l'état actuel du pipeline d'altitude et du
  D+, lignes R27–R30 du ledger.
- `openelevation/research/verification/loire-nantes-saint-nazaire.md` (hors dépôt) — la source des
  chiffres de la section *Inputs*. Les traces qui l'ont produite sont personnelles et ne sortent
  pas de ce projet ; seuls les **profils de référence le long des axes OSM** sont réutilisables ici.

## Inputs

### 1. Ce que fait vcyclist aujourd'hui

| Étape | Comportement | Où |
|---|---|---|
| Source | `mapterhorn` (défaut, z12 × 512 px, ≈ 13,5 m) ou `ign-lidar-hd` (MNT, z15 × 256 px, ≈ 3,4 m), repli Mapterhorn sur `-9999` | `ElevationSource.kt` |
| Correction | `fixElevation = true` par défaut : **toutes** les altitudes sont remplacées par le MNT, en bilinéaire, les `<ele>` du GPX sont jetés | `ElevationStep.fixElevation` |
| Ouvrages | **aucun traitement** — ni pont, ni viaduc, ni tunnel ; le KDoc de `IGN_LIDAR_HD` le dit explicitement | — |
| Lissage physique | noyau triangulaire de 150 m de demi-largeur (longueur effective ≈ 61 m), toujours | `ElevationStep.DEFAULT_SMOOTH_WINDOW_M` |
| D+ rapporté | lu sur `sourceElevation` (**avant** le lissage), preset `dem` = seuil 3 m sur 30 m | `ElevationGain`, `ElevationGainPreset.DEM` |

Conséquence directe : sous un pont, le canyon du MNT entre **en entier** dans le D+ rapporté (le
lissage de 30 m du preset ne l'efface pas), et à moitié dans la physique (le noyau de 150 m
l'atténue sans le supprimer — le cycliste virtuel plonge vers l'eau et remonte).

### 2. Ce que la vérification Loire a mesuré

Vérité terrain de l'étude : la **« chaussée vraie »** = MNS LiDAR HD filtré par une médiane
glissante sur 25 m, là où MNS − MNT > 1 m sur l'ouvrage, MNT ailleurs ; trous de MNS < 300 m
comblés linéairement. Exacte à ±0,5 m sur un tablier simple, ±2 m sur un ouvrage à superstructure.

**Le canyon (A1, confirmé).** Écart max chaussée − MNT, en m :

| Pont | Canyon | Interp. linéaire aux nœuds OSM : max / RMS | Interp. entre culées **exactes** : max / RMS |
|---|---|---|---|
| Anne de Bretagne | 6,0 | 4,3 / 2,9 | 4,3 / 2,9 |
| Pirmil | 11,1 | 4,4 / 3,5 | 2,5 / 1,8 |
| Trois Continents | 10,9 | 6,8 / 5,3 | 4,5 / 3,2 |
| Thouaré | 13,1 | 6,7 / 2,8 | 6,7 / 2,8 |
| **Cheviré** | **58,7** | 41,6 / 27,6 | **40,0 / 26,3** |
| **Saint-Nazaire** | **65,8** | 64,5 / 40,5 | **22,2 / 11,9** |

- Le MNT LiDAR remplace le tablier par l'eau interpolée **et garde les piles** : pics de +33 et
  +55 m à Saint-Nazaire. Le profil MNT d'un pont n'est ni plat ni monotone ; il faut **remplacer**
  tout le profil entre les culées, pas le lisser.
- L'interpolation linéaire, même entre culées parfaites, ne représente pas un tablier bombé :
  1 à 4,5 m à Nantes, 22 à 40 m sur les grands ponts haubanés ou suspendus.
- Les tags OSM ne sont pas fiables : le viaduc nord de Saint-Nazaire (≈ 1 050 m) n'est pas tagué
  `bridge`, d'où 53 m d'erreur à la « culée ». vcyclist n'a de toute façon pas les tags.

**Le D+ (§3.6).** Rapport médian D+ calculé / D+ vrai sur une fenêtre de ±250 m :

| Méthode | Saint-Nazaire (vrai ≈ 54 m) | Cheviré (≈ 47 m) | Petits ponts (≈ 5,4 m) |
|---|---|---|---|
| MNT z17 (canyon), seuil 0 | **2,21** | 0,75 | **2,01** |
| MNT z17, seuil 10 m | 1,13 (par hasard) | 0,19 | 0,00 |
| Interp. linéaire aux nœuds OSM | 0,22 | 0,29 | 0,83 |
| Baro, MA 50 m + seuil 2 m | 0,96 | 1,02 | 0,67 |
| Baro, MA 150 m + seuil 2 m | 0,95 | 0,98 | **0,50** |

- Le canyon **double** le D+ d'un petit pont ; les piles le gonflent ×2,2 à Saint-Nazaire.
- Un lissage de 150 m efface la moitié des rampes des petits ponts ; 50 m les préserve.
- Un seuil de 10 m efface tout pont de moins de 10 m.

**Mapterhorn en France (A13, A14).** Contrairement à ce qu'affirment `elevation.md` (lignes 224 et
312) et la ligne R30 :

| Zoom | Pixel (47° N) | Pas vertical | Source mesurée |
|---|---|---|---|
| z12 | 13,0 m | 0,5 m | sous-échantillonnage du **LiDAR HD** (0,46 m RMS du MNT), **pas** GLO-30 (2,8 m RMS) |
| z14 | 3,2 m | 0,125 m | LiDAR HD |
| z17 | 0,41 m | 1/64 m | LiDAR HD, écart médian **0,00 m** |

z12 n'est donc pas « la résolution native » en France, et z17 existe (le validateur `0..15` de
`ElevationProvider` l'interdit). Cela explique la mesure de `cf0798e` sur `sample.gpx` : Mapterhorn
et IGN s'accordent à 2,0 m en moyenne parce que **les deux sont du LiDAR HD**. Monter en zoom ne
corrige pas les ponts — le canyon est identique dans toutes les sources testées, GLO-30 compris.

**Datum (A19).** IGN69 (LiDAR) contre EGM2008 (GLO-30, base mondiale de Mapterhorn) : +0,76 m dans
l'estuaire. Marche visible seulement à la frontière de couverture LiDAR. Négligeable pour la
physique du vélo, à ne pas oublier dans un futur raccord de sources.

### 3. Ce que l'IGN expose, vérifié le 2026-10-12

La Géoplateforme WMS-R (`data.geopf.fr/wms-r/wms`) publie, en plus du MNT déjà utilisé :

| Couche | Contenu | BIL float32 |
|---|---|---|
| `IGNF_LIDAR-HD_MNS_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G` | MNS : sommet de ce qui est au sol (tabliers, arbres, toits) | ✅ |
| `IGNF_LIDAR-HD_MNH_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G` | MNH : hauteur du sursol = MNS − MNT | ✅ |
| `IGNF_LIDAR-HD_MNT_ELEVATION.MIXED.WGS84G` | MNT « mixte » (non étudié) | — |

Sonde sur une emprise de 64 × 64 px autour de Cheviré : MNT −0,6 → 27,3 m, MNS 1,0 → 51,9 m,
MNH 0,0 → 49,6 m. Les trois couches répondent **au même format** que le MNT : la requête
(`ignWmsTemplate`), la cadence et `decodeBil32Tile` se réutilisent sans changement. Une seule
couche MNH suffit au **détecteur** ; le MNS sert au **profil** du tablier.

### 4. Le piège : le MNS hors des ouvrages

Le MNS est la cime des arbres et le toit des bâtiments. Sur une route forestière, MNS − MNT dépasse
lui aussi 5 m sur des centaines de mètres. Un remplacement « MNS là où MNH > seuil » transformerait
chaque route sous canopée en montagnes russes de 20 m. C'est la raison pour laquelle `cf0798e` a
écarté le MNS, et elle reste valable : **le MNS ne doit jamais être lu hors d'un ouvrage détecté**.

Ce qui distingue un tablier d'une canopée, à vérifier par la mesure :

| Critère | Tablier | Canopée / bâti |
|---|---|---|
| Rugosité du MNS le long de l'axe (dérivée seconde, écart à la médiane 25 m) | faible, surface continue | forte, houppiers et trouées |
| Pente du MNS filtré | ≤ 6–8 % (Cheviré 6,3 %, Saint-Nazaire 5,9 %) | quelconque, souvent > 15 % localement |
| Raccord aux extrémités | le MNS filtré rejoint le MNT à ≤ 1–2 m aux deux bouts (culées) | pas de raccord propre |
| Ce qu'il y a dessous | MNT bas et plat (eau aplanie), ou vallée | MNT continu avec la route avant/après |

### 5. Quatre itinéraires Strava contre l'IGN (2026-10-12)

Quatre itinéraires tracés à la main dans Strava (altitude = fond Strava, pas un capteur), dans
[`docs/deck-strava-data/`](../deck-strava-data/), analyse complète, figures et scripts dans
[`analysis/`](../deck-strava-data/analysis/rapport.md). Méthode : rééchantillonnage à 5 m, MNT et
MNS IGN en bilinéaire, MNH = MNS − MNT, offset Strava − MNT en médiane sur terrain dégagé.

| Fichier | Zone | Offset Strava − MNT | Hors ouvrages, RMSE | Ouvrages |
|---|---|---|---|---|
| `test1` | Saint-Nazaire, 10 km | −0,52 m | 0,85 m | pont de Saint-Nazaire + 2 petits |
| `test2` | Nantes, 25 km | +0,29 m | 0,80 m | 16 ponts de Loire |
| `test3` | Mauves, 10 km | +0,52 m | 0,75 m | pont de Mauves |
| `test4` | Vercors, 111 km | +1,48 m (−5,5 à +3,5 par secteur) | 2,3 m | pont d'Échevis, 4 tunnels |

**Le détecteur fonctionne.** MNH > 5 m sur ≥ 3 points, trous < 300 m fusionnés, rugosité du MNS
(RMS du résidu à la médiane 25 m) < 0,8 m, pente P95 ≤ 10 %, MNT dessous > 3 m sous les deux bouts :
tous les grands ponts sont trouvés, et 14 km de canopée et de bâti sont écartés sur `test4`
(2,2 km sur `test2`). Deux réserves :

- le seuil de rugosité a dû passer de 0,35 à 0,8 m pour le pont de Bellevue, dont la piste cyclable
  longe le bord du tablier (le MNS alterne tablier et vide) ;
- quatre petits ouvrages sont manqués (avenue de la Prise-d'Eau et pont basculant à Saint-Nazaire,
  D518 au km 15,6 et D70 au km 78,7 de `test4`), où Strava est 6 à 13 m au-dessus du MNT.

**Les ponts, Strava les traite bien.** Saint-Nazaire : tablier LiDAR à 63,7 m au plus haut, pente
P95 5,9 %, canyon MNT de 66 m ; Strava suit le tablier à 1,1 m RMS (point haut 1,6 m trop bas).
Ponts nantais : écart max ≤ 1,8 m. Mauves : interpolation linéaire, 2,3 m sous le bombé.

**Les tunnels sont le vrai défaut — et le LiDAR ne voit pas dedans.** Sur `test4`, trois tunnels
sont bien traités (Petits Goulets, tunnel des Moines, galeries de Combe Laval : Strava passe 55 à
76 m sous le MNT, pente régulière). Le **tunnel des Grands Goulets** (km 13,60–15,36, 1,77 km) ne
l'est pas : l'altitude Strava monte en escalier (paliers à 630, 816 puis 711 m, marches de +186 m
sur ≈ 85 m et −105 m sur ≈ 65 m) quand la chaussée monte régulièrement de 600 à 681 m (+4,6 %).
Erreur jusqu'à +190 m, **≈ 112 m de D+ fictif** (2 479 m contre 2 362 m sur l'itinéraire). Le
palier haut est le relief au-dessus du tunnel. vcyclist, qui lit le MNT au point, ferait pire :
il suivrait le relief en continu.

**Les routes en balcon lisent la falaise ou la gorge.** Le MNT au point de la trace donne +76 m
au-dessus de la route à Combe Laval, −22 m dans la gorge de la Bourne aux Petits Goulets. Strava y
reste juste. Le critère de pente transversale (> 100 % sur ±10 m) les distingue mal ; presque tous
ces cas tombent dans l'emprise des tunnels.

## Steps

### E1 — Banc de mesure (avant toute correction)

Un test de mesure `INTEGRATION=1`, à l'image de `RoadSnapProbeTest`, qui fait passer de vrais
itinéraires dans le vrai pipeline. La base est faite des quatre itinéraires de l'*Input 5* :

- **ponts** : Saint-Nazaire (`test1`), les 16 ponts nantais (`test2`), Mauves (`test3`, tablier
  bombé), Échevis (`test4`) ;
- **tunnels** : Petits Goulets, Grands Goulets, tunnel des Moines, galeries de Combe Laval (`test4`) ;
- **routes en balcon** : Combe Laval et gorges de la Bourne (`test4`) ;
- **à ajouter** : des témoins négatifs (une route forestière sous canopée dense, une rue bordée de
  bâtiments, une route en déblai), Cheviré, et Thouaré (cas à treillis, MNS surestimé).

La vérité est la **chaussée estimée** de l'analyse : MNS médiane 25 m sur les tabliers détectés,
interpolation linéaire entre portails dans les tunnels, Strava recalé sur le MNT sur les tronçons
en balcon, MNT ailleurs. Les emprises des ouvrages (km de début et de fin, portails des tunnels)
sont figées dans le test à partir de `analysis/results.json`, pour que le banc ne dépende pas
d'Overpass. Garde-fous : Saint-Nazaire,
point haut 63,7 m NGF-IGN69 et pente max 5,9 % (cohérente avec `incline=6%`) ; Grands Goulets,
600 → 681 m entre portails.

Métriques par cas : erreur max et RMSE sur le tablier, rapport D+ / D+ vrai (preset courant et
seuil 0), durée simulée, nombre de points dont la pente dépasse 15 %. Colonnes : `mapterhorn`,
`ign-lidar-hd`, puis chaque variante des étapes suivantes.

#### Mesures de référence (E1)

Banc : `ElevationBenchProbeTest` (`engine/src/jvmTest/.../path/`). La vérité est figée par
`analysis/export_truth.py` dans `analysis/truth/` : un CSV par itinéraire au pas de 5 m, plus
`structures.csv` (21 ponts, 4 tunnels, 6 tronçons en balcon, 15 ponts OSM non détectés dont la
vérité reste au MNT). Sur les balcons, la vérité est Strava recalé sur le MNT (GPX − `strava_offset_m`,
type `S`) : le MNT au point de la trace y lit la falaise ou la gorge, c'est-à-dire l'erreur même
qu'on mesure, alors que Strava y reste juste (`analysis/rapport.md`). Un second test, non
conditionné, relit ces fichiers à chaque `jvmTest` et vérifie leurs tailles et les garde-fous
(Saint-Nazaire 63,68 m et P95 de pente 5,9 %, calculée comme `analyze.py` : fenêtre de 20 m sur le
tablier ; Grands Goulets 599,75 → 680,46 m).

```bash
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*ElevationBenchProbeTest*' --rerun-tasks -i
```

Tuiles en cache disque dans `build/dem-cache` (ou `$VCYCLIST_DEM_CACHE`, même disposition que
`--cache` de la CLI). Premier passage : 209 tuiles téléchargées (51,3 MB : ≈ 190 IGN, ≈ 20
Mapterhorn) ; passage suivant : 0.

Méthode. Chaque point du profil dense (étapes 1 à 4 du pipeline, pas de 1 à 2 m) est apparié à la
station de vérité la plus proche par un curseur monotone (rejet au-delà de 30 m : aucun rejet sur
les quatre itinéraires). `src` est `sourceElevation` (ce que lit `ElevationGain`), `ele` le profil
lissé à 150 m (ce que roule la physique). Erreur = vcyclist − vérité ; « max » est l'écart signé le
plus grand en valeur absolue. **Aucun décalage n'est soustrait**, ni à `ign-lidar-hd` ni à
`mapterhorn` : en France les deux servent du LiDAR HD en NGF-IGN69 (A13). La colonne « Méd. ouvert »
(médiane de l'écart en terrain ouvert, hors toute emprise) n'est qu'un diagnostic de biais local.
« D+ rep. » est le D+ rapporté par un `EnhanceOptions.DEFAULT` complet ; « D+ 1 Hz » le même run
avec `simplifyPath` désactivé, ce qui isole `PathSimplifier` ; « D+ dem » et « D+ raw » sont
calculés sur `src` rééchantillonné à 5 m, comme la vérité, avec le preset `dem` et le seuil 0.
Pentes : nombre de stations à 5 m où la pente sur 50 m dépasse 15 %.

**Par itinéraire**

| Itinéraire | Source | Méd. ouvert (m) | D+ rep. | /vrai | D+ 1 Hz | /vrai | D+ dem | /vrai | D+ raw | /vrai | Durée | RMS terrain ouvert (m) | > 15 % src | > 15 % ele |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| test1 (vrai : 72 / 123 m, 4) | mapterhorn | +0,03 | 47 | 0,66 | 62 | 0,86 | 62 | 0,86 | 117 | 0,95 | 0:17:34 | 0,29 | 19 | 0 |
| | ign-lidar-hd | −0,01 | 12 | 0,17 | 58 | 0,81 | 59 | 0,82 | 137 | 1,11 | 0:17:35 | 0,24 | 30 | 0 |
| test2 (vrai : 89 / 178 m, 0) | mapterhorn | +0,03 | 71 | 0,80 | 133 | 1,49 | 133 | 1,49 | 227 | 1,27 | 0:44:05 | 0,63 | 24 | 0 |
| | ign-lidar-hd | +0,00 | 85 | 0,96 | 135 | 1,52 | 138 | 1,56 | 220 | 1,23 | 0:44:06 | 0,29 | 48 | 0 |
| test3 (vrai : 18 / 216 m, 0) | mapterhorn | −1,53 | 7 | 0,39 | 20 | 1,13 | 20 | 1,13 | 65 | 0,30 | 0:17:08 | 1,68 | 0 | 0 |
| | ign-lidar-hd | −0,18 | 12 | 0,65 | 26 | 1,43 | 29 | 1,61 | 92 | 0,43 | 0:17:10 | 0,59 | 0 | 0 |
| test4 (vrai : 2 414 / 2 626 m, 2) | mapterhorn | −0,25 | 2 708 | 1,12 | 2 899 | 1,20 | 2 909 | 1,21 | 3 322 | 1,26 | 3:46:55 | 2,63 | 483 | 238 |
| | ign-lidar-hd | +0,01 | 2 722 | 1,13 | 2 866 | 1,19 | 2 870 | 1,19 | 3 136 | 1,19 | 3:46:39 | 0,46 | 398 | 243 |

Entre parenthèses : D+ vrai au preset `dem` / au seuil 0, et stations > 15 % de la vérité. Le MNT
LiDAR seul (colonne `mnt` du CSV) donne au preset `dem` 68 / 144 / 26 / 2 917 m et 26 / 76 / 0 /
388 stations > 15 %.

**Par ouvrage** (m ; `src` max / RMS, puis `ele` max / RMS)

| Ouvrage | km | L (m) | Vrai max | mapterhorn src | mapterhorn ele | ign-lidar-hd src | ign-lidar-hd ele |
|---|---|---|---|---|---|---|---|
| test1 Pont de Saint-Nazaire (D 213) | 1,03–4,67 | 3 645 | 63,7 | −65,7 / 36,0 | −65,7 / 35,9 | −65,8 / 36,0 | −65,7 / 35,9 |
| test1 way 27013264 | 5,89–5,97 | 85 | 6,9 | −5,3 / 4,1 | −2,3 / 2,2 | −5,2 / 4,2 | −2,4 / 2,3 |
| test1 Pont écluse sud aval | 9,95–9,98 | 35 | 5,0 | −5,7 / 3,8 | −1,2 / 0,9 | −6,0 / 4,1 | −1,3 / 1,0 |
| test2 Pont de Bellevue | 0,63–1,01 | 390 | 13,8 | −10,3 / 8,2 | −9,4 / 7,4 | −10,5 / 8,5 | −9,8 / 7,7 |
| test2 way 362043805 | 1,43–1,55 | 125 | 9,6 | −7,3 / 5,9 | −4,6 / 3,9 | −7,3 / 5,9 | −4,5 / 3,8 |
| test2 Pont Léopold-Sédar-Senghor | 6,94–7,23 | 295 | 12,1 | −10,6 / 9,0 | −10,5 / 8,3 | −10,5 / 8,9 | −10,4 / 8,1 |
| test2 Pont Éric Tabarly | 8,42–8,62 | 210 | 11,4 | −9,9 / 8,8 | −8,8 / 7,3 | −9,8 / 8,7 | −8,8 / 7,2 |
| test2 Pont Willy-Brandt | 9,31–9,51 | 205 | 12,1 | −10,6 / 9,4 | −9,7 / 8,1 | −10,5 / 9,2 | −9,4 / 7,8 |
| test2 Pont Georges Clemenceau | 10,73–11,06 | 335 | 13,0 | −11,5 / 10,4 | −11,4 / 9,4 | −11,5 / 10,4 | −11,4 / 9,3 |
| test2 Pont de Pirmil | 12,07–12,30 | 240 | 12,8 | −11,5 / 10,3 | −11,0 / 8,9 | −11,4 / 10,1 | −10,9 / 8,8 |
| test2 Pont Aristide Briand | 14,03–14,26 | 235 | 12,9 | −11,4 / 9,6 | −10,0 / 8,3 | −11,3 / 9,6 | −9,9 / 8,1 |
| test2 Pont Général Audibert | 15,23–15,38 | 155 | 11,6 | −10,1 / 8,5 | −8,3 / 6,9 | −10,2 / 8,6 | −8,2 / 6,8 |
| test2 way 30807706 | 16,91–17,16 | 255 | 10,8 | −9,2 / 8,7 | −8,7 / 7,4 | −9,0 / 8,3 | −8,4 / 7,0 |
| test2 Pont des Trois Continents | 18,33–18,62 | 290 | 13,3 | −10,9 / 8,7 | −10,5 / 8,2 | −10,9 / 8,8 | −10,5 / 8,2 |
| test2 way 1287936957 | 19,90–20,05 | 155 | 11,4 | −8,9 / 7,2 | −7,4 / 6,0 | −9,0 / 7,2 | −7,3 / 5,9 |
| test2 Pont Haudaudine | 20,95–21,15 | 200 | 12,5 | −10,2 / 8,5 | −9,2 / 7,4 | −10,1 / 8,4 | −9,2 / 7,3 |
| test2 Pont Général Audibert (2ᵉ) | 22,16–22,31 | 150 | 9,3 | −7,8 / 6,8 | −6,1 / 5,1 | −7,9 / 6,9 | −6,0 / 5,0 |
| test2 Pont de la Rotonde | 23,57–23,62 | 55 | 14,2 | −5,9 / 4,4 | −4,4 / 4,1 | −6,1 / 4,8 | −4,2 / 3,9 |
| test2 way 410671587 | 24,29–24,41 | 120 | 12,9 | −6,9 / 5,9 | −5,7 / 5,1 | −7,7 / 6,1 | −5,0 / 4,5 |
| test3 Pont de Mauves-sur-Loire | 0,43–1,26 | 840 | 13,6 | −9,1 / 6,4 | −9,0 / 6,1 | −9,0 / 6,4 | −8,9 / 5,9 |
| test4 Pont d'Échevis | 7,66–7,68 | 25 | 311,0 | −5,9 / 5,0 | +0,4 / 0,2 | −6,3 / 4,3 | +0,7 / 0,4 |
| test4 tunnel des Petits Goulets | 5,11–5,58 | 475 | 325,8 | +66,2 / 26,8 | +32,3 / 21,8 | +68,0 / 21,5 | +21,8 / 13,3 |
| test4 **tunnel des Grands Goulets** | 13,60–15,36 | 1 765 | 680,5 | +214,5 / 135,4 | +201,6 / 133,4 | +220,2 / 136,0 | +205,5 / 133,8 |
| test4 tunnel des Moines | 65,74–65,95 | 210 | 1 000,0 | +51,9 / 36,2 | +33,5 / 27,0 | +61,3 / 40,8 | +38,0 / 30,9 |
| test4 galeries de Combe Laval | 68,15–68,64 | 490 | 910,3 | +27,9 / 10,7 | +9,1 / 5,1 | +38,9 / 12,3 | +14,5 / 7,4 |
| test4 balcon km 68,64 (Combe Laval) | 68,64–68,68 | 40 | 908,6 | +7,6 / 5,0 | +4,2 / 4,0 | +11,7 / 6,2 | +11,7 / 9,8 |
| test4 balcon km 67,98 (Combe Laval) | 67,98–68,14 | 165 | 908,5 | −5,7 / 4,2 | −3,7 / 3,4 | −0,9 / 0,5 | +1,0 / 0,5 |
| test4 balcon km 66,05 | 66,05–66,19 | 140 | 984,4 | −10,8 / 8,2 | −7,2 / 6,8 | −6,7 / 2,7 | −1,9 / 1,6 |
| test4 balcon km 7,54 (Bourne) | 7,54–7,58 | 45 | 316,8 | −2,3 / 1,6 | −3,8 / 3,3 | −4,2 / 4,0 | −4,4 / 4,0 |
| test4 balcon km 92,99 | 92,99–93,01 | 25 | 881,4 | −6,0 / 5,6 | −6,0 / 5,8 | −0,8 / 0,6 | −1,9 / 1,8 |
| test4 balcon km 67,08 | 67,08–67,14 | 65 | 968,6 | −4,1 / 2,9 | −2,7 / 1,4 | +2,9 / 2,8 | +2,6 / 2,3 |

Les 15 ponts OSM non détectés (vérité = MNT, donc sans valeur pour juger un tablier) restent
imprimés par le test ; leurs écarts max vont de −6,6 à +10,5 m.

**Constats**

- **Les deux sources voient les mêmes canyons.** Sur les 21 ponts, `mapterhorn` et
  `ign-lidar-hd` diffèrent d'au plus 0,8 m sur l'écart max (way 410671587 ; Mauves : 0,1 m) :
  Mapterhorn sert déjà du LiDAR en France (voir E6). Les sources ne divergent qu'en terrain ouvert
  — montagne (RMS 2,63 m contre 0,46 m sur `test4`) et `test3`, où les pixels de 13 m de
  Mapterhorn portent un biais local (médiane −1,53 m, RMS 1,68 m contre −0,18 / 0,59 m) — et dans
  les balcons.
- **Saint-Nazaire** : vcyclist passe 65,7 m sous le tablier au point haut, RMS 36 m sur 3,6 km ; le
  lissage à 150 m n'y change rien (−65,7 m). **Ponts nantais** : 5,9 à 11,5 m sous le tablier avant
  lissage, encore 4,2 à 11,4 m après — le noyau de 150 m n'efface pas un canyon de 200–300 m.
- **Grands Goulets** : jusqu'à +220 m au-dessus de la chaussée (RMS 136 m), +205 m encore après
  lissage — pire que Strava (+190 m), comme l'annonçait l'*Input 5*. Les trois tunnels « bien
  traités » par Strava coûtent à vcyclist +28 à +68 m.
- **Balcons** (vérité Strava) : jusqu'à +11,7 m (`ign-lidar-hd`, km 68,64 à Combe Laval, intact
  après lissage) et −10,8 m (`mapterhorn`, km 66,05). Le cas spectaculaire de l'*Input 5* (+76 m
  à Combe Laval) tombe dans l'emprise des galeries, donc dans la ligne entre portails, pas dans
  ces six tronçons, où MNT et Strava ne s'écartent que de 3,2 à 8,8 m (`dmax`).
- **D+ gonflé là où il y a du relief, masqué en plaine par `PathSimplifier`.** `test4` : ×1,12–1,13
  en D+ rapporté, ×1,19–1,21 sur le profil dense. `test2` : ×1,49–1,56 sur le profil dense (les
  canyons sous les ponts) et encore ×1,49–1,52 au D+ 1 Hz, mais ×0,80–0,96 une fois
  `PathSimplifier` passé ; sur `test1` (`ign-lidar-hd`), la simplification seule fait passer le D+
  de 58 m (1 Hz) à 12 m, pour 72 m vrais. La simulation et le rééchantillonnage à 1 Hz ne bougent
  presque rien (D+ 1 Hz ≈ D+ dem). Le D+ rapporté ne suffit donc pas à juger E3 : comparer aussi
  la colonne « D+ dem ».
- **Pentes > 15 %** : 398–483 stations sur `test4` avant lissage, 238–243 après, contre 2 pour la
  vérité ; en plaine, 19 à 48 stations avant lissage et aucune après.
- **Limites** : D+ au seuil 0 peu fiable sur `test3` : le MNT lui-même gigote de 0,5 à 1,5 m d'une
  station à l'autre hors ouvrage (210 m des 216 m bruts sur le terrain `T`, 225 m pour le MNT
  seul) ; seul le preset `dem` y est comparable. Aux bords des balcons, la vérité saute du MNT à
  Strava d'au plus `dmax`. Échevis reste douteux (voir `analysis/rapport.md` : 25 m, tablier et
  interpolation indiscernables) ; aucun témoin négatif ni Cheviré / Thouaré dans ce banc.

### E2 — Détecteur d'ouvrage sur le MNH

Échantillonner le MNH le long de la trace à pas de 5 m, et marquer comme **candidat** toute suite
de points où MNH > 5 m sur au moins 3 points consécutifs (seuil de l'étude, §6). Fusionner les
candidats séparés de moins de 300 m (trous de MNS, piles). Classer ensuite chaque candidat avec les
critères de l'*Input 4* ; ceux qui échouent restent au MNT.

Points à trancher par la mesure E1 : seuils de rugosité et de pente, et tolérance de raccord aux
culées. Le taux de faux positifs sur les témoins forestiers est le critère d'acceptation, avant le
gain sur les ponts.

#### Mesures (E2)

Livré : `IgnLidarHdLayer` (`:elevation`, couches brutes MNT / MNS / MNH du même WMS, sans id de
catalogue ni repli : le hors-données reste `NaN`) et `DeckDetector` + `DeckDetectorOptions`
(`:gpx`, `commonMain`, port d'`analyze.py`, seuils dans `DeckDetectorOptions.DEFAULT`). Aucun
changement de pipeline, de porte ni de `PointField`. `ElevationProvider.withFallback` refuse un
fournisseur de couche brute : « pas de repli » est un contrat, plus une convention.

Écarts voulus avec Python, tous sans effet sur ses 21 ponts (KDoc de `DeckDetector`) :

- **culée bornée** : l'extension le long de `MNH > 1` s'arrête à `maxAbutmentM` = 100 m par côté,
  au-delà le candidat retombe sur son cœur haut (champ, haie, remblai : la route n'y est pas un
  tablier). Mesuré sur les 21 ponts de Python : 7 stations (35 m) au plus. Seul effet sur ses
  échantillons : Combe Laval (km 68,5 de `test4`, route en falaise) s'étendait de 29 stations ; il
  passe de `STEEP` à `NO_CANYON`, toujours rejeté (`BOUNDED_ABUTMENT` dans le test de parité) ;
- **trous de données** : un îlot `NaN` d'au plus 2 stations (demi-fenêtre de la médiane) est
  enjambé par l'extension, et un trou `NaN` ou bas d'au plus 2 stations est refermé dans le masque
  de tablier là où la médiane est finie. Avec `fromSurface`, un seul pixel MNS sans donnée rend le
  MNH `NaN` ; avant, il arrêtait l'extension au fond du lit (`NO_CANYON`) ou trouait le tablier
  jusqu'au MNT de la rivière. Un `NaN` plus large qui arrête l'extension donne `NO_DATA`, et des
  suites hautes que seule une rugosité `NaN` (MNS sans donnée) fait échouer aussi.

Limites gardées de Python, pour E3 (tests `known limit` de `DeckDetectorTest`) :

- les suites lisses fusionnent à moins de 300 m *avant* les tests de canyon et de pente : un toit
  lisse sans canyon à moins de 300 m d'un pont entre dans son masque de tablier, et deux ponts aussi
  proches font un seul candidat. Un test de canyon par suite casserait Saint-Nazaire et Mauves,
  qui portent chacun 51 stations de `MNH ≤ 1` (MNS troué au-dessus de l'eau), en tout point
  semblables à une route au sol ;
- des arbres collés au tablier font une seule suite haute avec lui. Couper les stations rugueuses
  aux bouts d'une suite, ou y arrêter l'extension, perd deux ponts de Python (`test2` 285..309 et
  4858..4881) : leurs culées lisent 0,86 m et 1,07 m de rugosité, autant qu'une lisière.

Banc : deux tests ajoutés à `ElevationBenchProbeTest`.

- `deck_detector_reproduces_python_on_its_own_samples`, **non conditionné** : le détecteur sur les
  échantillons mêmes de `fetch.py` (colonne `mnt` de la vérité, et `truth/<route>_mns.csv` que
  `export_truth.py` écrit désormais, au centimètre) doit retrouver exactement `results.json`.
- `deckDetector`, `INTEGRATION=1` : MNT, MNS et MNH échantillonnés par vcyclist aux stations de la
  vérité (`setElevations`, bilinéaire, `NaN` conservé), une couche après l'autre (cadence IGN),
  tuiles dans `build/dem-cache`. Variantes : (py) échantillons Python ; (a) MNS − MNT z15 ;
  (a½) idem au centre des pixels ; (b, c, d) couche MNH z15 / z14 / z13 ; (e14, e13) deux phases,
  MNS et MNT lus dans les seules `surfaceWindows(MNH z14 / z13)` ; (f) MNS z15 − MNT de la source
  du pipeline (`ign-lidar-hd`, `mapterhorn`) ; (f-c) MNH = MNS − MNT LiDAR, mais canyon lu sur le
  MNT `mapterhorn`, pour isoler le canyon.

```bash
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*ElevationBenchProbeTest*' --rerun-tasks -i
```

Premier passage à froid : 512 tuiles BIL de plus que E1 (MNS z15 189, MNH z15 189, z14 93, z13 41,
≈ 134 MB) ; passage suivant : 0. Détecteur : 1,4 / 1,1 / 0,2 / 1,9 ms par itinéraire.

**Le port est exact, à un écart voulu près.** Sur les échantillons de Python : les 21 ponts avec les mêmes `i0`/`i1`, les
9 `others` avec le même verdict (8 `NO_CANYON`, km 68,5 de `test4` en `STEEP` chez Python et en
`NO_CANYON` ici, culée bornée — le seul écart, voulu), les 75 groupes de
canopée (17,86 km) à l'identique, pente P95, rugosité et canyon à 0,03 près (entrées arrondies au
cm), masque de tablier conforme aux types B/b de la vérité sur 1 609 / 1 609 stations.

**Par variante** (rappel sur les 21 ponts, par itinéraire `test1/2/3/4`, au moindre recouvrement ;
manqué OSM retrouvé seulement si la détection est au moins pour moitié sur lui ; FP = détection
qui ne touche aucun pont ni pont OSM manqué ; « hors vérité » = stations des détections, puis du
masque de tablier, hors de tout pont et de tout pont manqué — ce que le seul compte de FP ne voit
pas)

| Variante | Ponts | Manqués OSM | FP | Hors vérité (span / tablier) | NO_CANYON / STEEP / SHORT_ROUGH / NO_DATA | ROUGH (n / km) |
|---|---|---|---|---|---|---|
| (py) échantillons Python | 21 (3/16/1/1) | 0 / 15 | 0 | 0 / 0 | 9 / 0 / 0 / 0 | 75 / 17,86 |
| (a) MNS − MNT z15 | 16 (2/13/1/0) | 0 / 15 | 0 | 8 / 8 | 13 / 1 / 0 / 0 | 83 / 25,18 |
| (a½) idem, centre des pixels | 14 (2/11/1/0) | 0 / 15 | 0 | 5 / 5 | 11 / 3 / 0 / 0 | 66 / 21,80 |
| (b) MNH z15 | 16 (2/13/1/0) | 0 / 15 | 0 | 8 / 8 | 13 / 1 / 0 / 0 | 83 / 25,18 |
| (c) MNH z14 | 13 (2/9/1/1) | 0 / 15 | 0 | 19 / 19 | 53 / 6 / 0 / 0 | 101 / 42,18 |
| (d) MNH z13 | 9 (1/8/0/0) | 0 / 15 | **2** | **866 / 729** | 88 / 4 / 0 / 0 | 94 / 46,41 |
| (e14) deux phases, MNH z14 | = (c) | = (c) | = (c) | = (c) | = (c) | = (c) |
| (e13) deux phases, MNH z13 | = (d) | = (d) | = (d) | = (d) | = (d) | = (d) |
| (f) MNS − MNT `ign-lidar-hd` | 16 (2/13/1/0) | 0 / 15 | 0 | 8 / 8 | 13 / 1 / 0 / 0 | 83 / 25,18 |
| (f) MNS − MNT `mapterhorn` | 14 (2/11/1/0) | 0 / 15 | 0 | 42 / 42 | 28 / 3 / 0 / 0 | 76 / 30,13 |
| (f-c) MNH LiDAR, canyon `mapterhorn` | 10 (2/7/1/0) | 0 / 15 | 0 | 4 / 4 | 19 / 1 / 0 / 0 | 83 / 25,18 |

Python (`results.json`) : 8 / 1 / 0 / 0 ; l'écart de (py) est Combe Laval, culée bornée. Les deux
FP de (d), `test4` km 9,09–9,53 (440 m, P95 7,1 %) et km 62,60–64,59 (1 990 m, P95 8,9 %), sont
des routes de montagne lissées par le MNH z13. Avant la culée bornée, des détections z13 aussi
étirées (725 m sur D 70, 845 m sur Willy-Brandt) touchaient un pont manqué ou réel et comptaient
comme retrouvées : la colonne « hors vérité » est la mesure qui tient.

**Détections de (a)**, tous candidats mesurés (les groupes `ROUGH` ne le sont jamais) :

| Itinéraire | km | L (m) | MNH max | Rugosité | P95 % | Canyon | Verdict | Ouvrage (vérité) |
|---|---|---|---|---|---|---|---|---|
| test1 | 0,33–0,35 | 30 | 10,3 | 0,32 | — | 9,8 | NO_CANYON | — |
| test1 | 1,32–4,67 | 3 355 | 65,8 | 0,00 | 6,1 | 65,8 | tablier | Pont de Saint-Nazaire (début +290 m) |
| test1 | 5,89–5,97 | 80 | 5,2 | 0,06 | 0,7 | 5,2 | tablier | way 27013264 |
| test1 | 9,22–9,26 | 45 | 14,6 | 0,22 | 64,7 | 14,5 | NO_CANYON | — (voisin de Pont Tournant, une station commune) |
| test2 | 1,42–1,55 | 130 | 8,3 | 0,20 | 6,9 | 8,0 | tablier | way 362043805 |
| test2 | 3,94–4,00 | 65 | 11,5 | 0,69 | 27,9 | 11,2 | NO_CANYON | — |
| test2 | 6,93–7,23 … 24,29–24,41 | 125–335 | 8,0–11,5 | 0,00–0,47 | 0,9–7,3 | 7,8–11,5 | tablier | 12 ponts nantais, bouts à ±5 m |
| test2 | 14,57–14,59 | 25 | 5,6 | 0,05 | — | 5,5 | NO_CANYON | Busway Ligne 4 (manqué OSM) |
| test2 | 20,96–21,39 | 435 | 17,3 | 0,27 | 52,6 | 16,4 | STEEP | Pont Haudaudine (fin +240 m) |
| test3 | 0,43–1,27 | 845 | 9,2 | 0,04 | 2,8 | 9,0 | tablier | Pont de Mauves |
| test4 | 13,80–13,91 | 110 | 8,3 | 0,62 | 53,3 | 7,6 | NO_CANYON | Grands Goulets (tunnel) |
| test4 | 68,52–68,63 | 110 | 32,4 | 1,06 | 107,1 | 33,5 | NO_CANYON | Combe Laval (tunnel, balcon) |
| test4 | 7 autres, 25–60 m | | 6,1–17,2 | 0,17–0,79 | | 5,2–15,8 | NO_CANYON | — |

Les cinq ponts que (a) perd : Pont écluse sud aval (`test1`, 35 m) et Pont de la Rotonde (`test2`,
55 m), où le MNS z15 ne voit pas le tablier (MNH ≤ 2,9 m, puis 6,1 m sur une seule station) ;
Bellevue (`ROUGH` : la piste longe le bord du tablier, le MNS alterne plus fort qu'au pas de
Python) ; Échevis (`ROUGH`, 3 stations hautes) ; Haudaudine, fusionné à moins de 300 m avec un bloc
lisse de MNH 12 m au-delà de la culée, donc `STEEP` (P95 52,6 %). Rugosité de Pirmil 0,47 m contre
0,00 chez Python, verdict inchangé. Masque de tablier de (a) : 1 464 / 1 609 stations conformes
(91,0 %). Aucun des 15 ponts OSM manqués par Python n'est retrouvé en z15 : 13 n'ont pas de suite
haute de 3 stations (Prise-d'Eau MNH 8,4 m, D518 km 33 11,1 m, D70 6,7 m, toujours sur 2 stations
au plus ; Pont Tournant n'a aucune station haute, MNH 1,8 m), Pont basculant est `ROUGH`, Busway
`NO_CANYON`. Un rejet n'est imputé à un ouvrage que si l'une de ses stations hautes est dessus : le
`NO_CANYON` du km 9,22 ne touche Pont Tournant que par sa culée.

**Coût en tuiles** (distinctes, 256² float32 = 0,262 MB chacune)

| Couche | test1 | test2 | test3 | test4 | Total | MB |
|---|---|---|---|---|---|---|
| MNT z15 (= `ign-lidar-hd`) | 15 | 24 | 18 | 132 | 189 | 49,5 |
| MNS z15, partout | 15 | 24 | 18 | 132 | 189 | 49,5 |
| MNS z15, fenêtres de MNH z14 | 9 | 20 | 13 | 98 | 140 | 36,7 |
| MNS z15, fenêtres de MNH z13 | 13 | 22 | 13 | 120 | 168 | 44,0 |
| MNH z15 | 15 | 24 | 18 | 132 | 189 | 49,5 |
| MNH z14 | 6 | 11 | 10 | 66 | 93 | 24,4 |
| MNH z13 | 3 | 4 | 5 | 29 | 41 | 10,7 |

Les fenêtres couvrent 43 / 43 / 63 / 43 % des stations en z14 (59 / 61 / 64 / 70 % en z13).

**Constats**

- **Le port est juste ; l'écart vient de l'échantillonnage.** Sur les entrées de Python, parité
  exacte hors culée bornée (21/21, mêmes canopées, un seul verdict de rejet changé). Sur la grille
  XYZ z15 de vcyclist, 16/21, aucun faux positif et 8 stations détectées hors de toute vérité : les
  ponts perdus sont étroits ou longés par le bord (Pont écluse, Rotonde,
  Bellevue, Échevis), plus une fusion à 300 m (Haudaudine). Corriger la convention de pixel
  (`ElevationCalculator` interpole entre coins de pixels, le WMS et `fetch.py` entre centres, soit
  ≈ 1,6 m d'écart) ne rattrape rien : 14/21. Les seuils calés sur la grille de Python (pixel de
  3,47 × 2,37 m) sont au bord pour ces cinq ouvrages ; les retoucher sans témoins négatifs serait
  du surapprentissage.
- **La couche MNH n'apporte rien en z15** : elle vaut MNS − MNT à 1 cm près sur toutes les stations
  des quatre itinéraires, et (b) est identique à (a). Le pipeline lit déjà le MNT : E3 n'a besoin
  que du **MNS z15** (189 tuiles, 49,5 MB sur les 4 itinéraires, autant que le MNT).
- **Le schéma en deux phases ne paie pas.** En z14 il coûte 93 tuiles de MNH + 140 de MNS = 233
  tuiles, plus que le seul MNS z15 (189), pour 13/21 : il gagne Échevis mais perd Willy-Brandt,
  Pirmil, Aristide Briand et way 410671587 (`STEEP`) ; en z13, 41 + 168 = 209 tuiles pour 9/21,
  deux faux positifs et 866 stations détectées hors vérité. Le MNS lu dans les seules fenêtres
  donne bien le même résultat que le MNS complet (vérifié sur les 8 couples), mais les fenêtres
  couvrent 43 à 70 % du parcours : la canopée de `test4` les remplit. **Zoom pour E3 : MNS z15
  partout, pas de couche MNH.**
- **Le MNT du pipeline compte, deux fois.** Avec `ign-lidar-hd`, résultat identique à (a) (même
  raster). Avec `mapterhorn` (z12) pour le MNH *et* le canyon, 14/21 : way 410671587 perd son
  canyon (`NO_CANYON`), mais Pirmil passe `STEEP`, une perte du MNH (extension et pente), pas du
  canyon. Le canyon seul sur `mapterhorn` (f-c, MNH LiDAR) fait pire, 10/21 (Senghor, Willy-Brandt,
  les deux Audibert, way 1287936957, way 410671587 en `NO_CANYON`) : les deux erreurs se compensent en
  partie. E3 doit donc lire **le MNH (MNS − MNT) et le canyon sur le MNT LiDAR z15**, même quand le
  DEM du pipeline est `mapterhorn` — ou ne s'activer qu'avec `ign-lidar-hd`.
- **Pas de faux positif en z15 et z14** (a, a½, b, c, f), y compris sur les deux tunnels de `test4`
  (Grands Goulets et Combe Laval, écartés par `NO_CANYON` : le relief au-dessus d'un tunnel n'a pas
  de MNT plus bas que ses bouts) ; **deux en z13** (d, e13). Le compte de FP ne suffit pas : il ne
  voit pas une détection étirée qui touche un pont, d'où la colonne « hors vérité ». Le critère d'acceptation de la spec (zéro détection sur des témoins forestiers et urbains)
  reste **non jugé** : le banc n'a pas encore de témoin négatif dédié (E1, « à ajouter ») ; la
  canopée rejetée passe de 17,9 km (Python) à 25,2 km en z15, sans fuite en tablier.

### E3 — Remplacement par le profil du tablier

Sur chaque ouvrage accepté : MNS filtré par médiane glissante sur 25 m, trous < 300 m comblés
linéairement, raccord continu au MNT aux deux bouts. Superstructures (treillis de Thouaré, haubans,
mobilier) : comparer médiane et quantile bas (P25) sur la fenêtre, et un plafond de pente ; retenir
ce que E1 désigne.

Placement dans le pipeline : **après `fixElevation` (étape 2) et avant `smoothElevation`
(étape 4)**, pour que `sourceElevation` — que lit `ElevationGain` — porte déjà le tablier. C'est un
changement d'ordre du pipeline : docstring d'`Enhancer.kt`, diagramme du `README.md`, liste de
`CLAUDE.md`, et un smoke CLI.

L'étape demande un *second* fournisseur (MNS, MNH) à côté du fournisseur de MNT ; elle n'a de sens
que si la source primaire est `ign-lidar-hd` ou `mapterhorn` sur la France (les deux sont du MNT
LiDAR). Hors couverture, le MNH répond `-9999` : pas d'ouvrage détecté, rien ne change.

### E4 — Tunnels, et canyons sans MNS, sur le seul MNT

**Les tunnels ne sont plus optionnels** (*Input 5*) : le LiDAR ne voit pas dedans, aucune couche
IGN ne les corrige, et un tunnel non traité ajoute plus de 100 m de D+ fictif. Signature : une bosse
du MNT entre deux points où la route tient une pente normale, avec des pentes impossibles pour une
route (> 15 % sur 100 m) des deux côtés ; le MNS ne la distingue pas, il est au-dessus du relief
lui aussi. Correction : interpolation linéaire entre les portails, qui est la bonne réponse pour une
chaussée de tunnel presque droite. Piège à mesurer sur le banc : une vraie route en lacets ou une
route en balcon où le MNT lit la falaise (Combe Laval, +76 m) a une signature voisine.

Le même détecteur, retourné (creux à parois raides, fond plat, bords à la même altitude), sert de
repli pour les ponts sans MNS — Mapterhorn hors France, ou WASI (voir *Notes*). Imparfait,
l'interpolation linéaire perd le bombé, mais l'étude Loire mesure D+ ×0,83 contre ×2,01 pour le MNT
brut sur les petits ponts.

### E5 — Lissage et D+ adaptés au LiDAR (après E3, jamais avant)

Une fois les tabliers en place, mesurer sur le banc E1 et sur `sample.gpx`/`stelvio.gpx` :

- le noyau physique à 50 m contre 150 m (l'étude : 150 m efface la moitié des rampes de pont) ;
- un preset de D+ « LiDAR » vers 1–2 m sur ≈ 50 m, contre `dem` (3 m sur 30 m).

**L'ordre est impératif** : réduire le lissage avant E3 ferait ressortir les canyons plus nets.
La question de la provenance (savoir quels points sont du LiDAR) se pose ici ; avec `ign-lidar-hd`,
le repli Mapterhorn la connaît déjà point par point.

### E6 — Corriger la documentation existante

- `elevation.md`, *What the DEM costs* et *Raising the DEM zoom* : z12 n'est pas la résolution
  native en France ; Mapterhorn y sert le LiDAR HD jusqu'à z17. La conclusion pratique (monter en
  zoom ne sert à rien pour le D+) reste vraie, la raison est fausse.
- Ligne R30 du ledger : même correction.
- KDoc de `ElevationSource.IGN_LIDAR_HD` et section *No bridges* d'`elevation.md` : renvoyer à
  cette tâche une fois E3 livrée.

### E7 — Les six surfaces

Si E3 devient une option (`bridgeDecks` ou équivalent) : cœur → CLI → JS → WASI → JVM/Java →
contrôle dans la démo → ligne de `surface-coverage.md`, selon la checklist de `CLAUDE.md`.
Défaut à décider après E1 : activé avec `ign-lidar-hd`, sans doute ; avec `mapterhorn`, seulement
si le coût en tuiles MNH reste raisonnable (voir *Notes*).

## Outputs

- Un test de mesure du banc E1 et ses tableaux reproduits dans `elevation.md`.
- Une étape de pipeline (E2 + E3) dans `:gpx`/`:engine`, couverte en `commonTest` par des profils
  synthétiques (canyon, tablier bombé, canopée rugueuse, trou de MNS).
- Lignes **R31** (tabliers par MNS LiDAR HD), **R32** (tunnels, et repli canyon sans MNS) et, si
  mesurée, **R33** (lissage et D+ adaptés au LiDAR) dans `improvements-ledger.md`.
- Les corrections de documentation de E6.

## Validation

- Banc E1, ponts : erreur max sur le tablier ≤ 2 m sur les ponts nantais (contre 6–13 m de canyon),
  ≤ 5 m sur Cheviré et Saint-Nazaire (contre 59–66 m) ; rapport D+ dans [0,8 ; 1,2].
- Banc E1, tunnels : Grands Goulets à ≤ 5 m de la chaussée interpolée entre portails (contre
  jusqu'à +190 m chez Strava), sans D+ fictif ; aucune route en lacets prise pour un tunnel.
- Banc E1, témoins : **zéro** ouvrage détecté sur la route forestière et la rue bâtie.
- `sample.gpx` : D+ rapporté et durée simulée inchangés à 0,5 % près hors ouvrages, et l'écart
  expliqué ouvrage par ouvrage s'il y en a.
- `./gradlew check` vert ; smoke CLI `enhance` avec `--dem-source ign-lidar-hd`.

## Done when

E1 à E4 livrés et mesurés, E6 fait, R31 et R32 remplis avec leurs chiffres ; E5 soit livré, soit
consigné dans le ledger comme mesuré et écarté.

## Notes

- **Coût réseau.** `cf0798e` mesure 132 tuiles IGN (33 MB) pour les 130 km de `sample.gpx`. Le MNH
  ajoute au pire autant ; le MNS seulement les tuiles des ouvrages candidats. Pistes : n'interroger
  le MNH qu'à z13–z14 (un tablier fait des dizaines de mètres, le détecteur n'a pas besoin de
  3 m de pixel), puis le MNS à z15 sur les seuls candidats. À mesurer, comme la cadence de 20 req/s.
- **WASI.** `fetch_tile(zoom, x, y)` ne nomme pas de source : l'hôte ne sait servir qu'une couche.
  E3 y est impossible sans changement d'ABI (un argument de couche, ou un second import). E4 n'a
  pas ce problème. À trancher avant E7.
- **Pourquoi pas les tags OSM.** vcyclist ne lit pas OSM, et l'étude montre que les tags `bridge`
  ratent des ouvrages entiers (Saint-Nazaire). Le MNH est une détection *physique*, indépendante de
  la qualité des tags. Un GPX issu d'un routeur peut porter `bridge` dans ses extensions ; c'est un
  indice à considérer, pas une source de vérité.
- **Baromètre.** Pour un GPX *enregistré* avec baromètre, `fixElevation` jette un signal que
  l'étude valide (rampes restituées à 0,91, retard de 3 à 10 s, dérive ≈ 3 m/h). Une fusion
  « baro pour la forme, MNT pour le niveau » (filtre complémentaire, λ 200–500 m) est hors du
  périmètre de cette tâche, centrée sur l'itinéraire planifié, mais mérite sa propre ligne.
- **Ce qui reste peu mesuré** : l'étude Loire n'avait ni tunnel ni route de montagne ; l'*Input 5*
  couvre quatre tunnels et deux routes en balcon, mais toujours pas de route en déblai, de viaduc
  de montagne ni de témoin forestier dédié. Le banc E1 doit les ajouter avant toute conclusion
  générale.
