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
- **témoins** (ajoutés en E2 bis, *Témoins*) : 12 témoins négatifs (forêt, platanes, rues, voie en
  déblai sous passages supérieurs, rues sous et le long de viaducs, péage, levée) et 8 ponts hors
  réglage, dont Cheviré et Thouaré (cas à treillis, MNS surestimé).

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

> **Note (2026-10-13).** Mesures à l'ancienne convention des coins. Aux centres
> (`elevation-half-pixel`), le RMS terrain ouvert de Mapterhorn tombe de 2,63 à 0,58 m (test4) et
> de 1,68 à 0,66 m (test3) ; IGN test3 monte de 0,59 à 0,78 m — voir `elevation-half-pixel.md` § H4.


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
  interpolation indiscernables). Les témoins négatifs, Cheviré et Thouaré ne sont pas dans les
  mesures E1 : ils jugent le détecteur, en E2 bis.

### E2 — Détecteur d'ouvrage sur le MNH

Échantillonner le MNH le long de la trace à pas de 5 m, et marquer comme **candidat** toute suite
de points où MNH > 5 m sur au moins 3 points consécutifs (seuil de l'étude, §6). Fusionner les
candidats séparés de moins de 300 m (trous de MNS, piles). Classer ensuite chaque candidat avec les
critères de l'*Input 4* ; ceux qui échouent restent au MNT.

Points à trancher par la mesure E1 : seuils de rugosité et de pente, et tolérance de raccord aux
culées. Le taux de faux positifs sur les témoins forestiers est le critère d'acceptation, avant le
gain sur les ponts.

#### Mesures (E2)

> **Note (2026-10-13).** Chiffres mesurés à l'ancienne convention des coins (lecture bilinéaire
> décalée d'un demi-pixel au sud-est). Aux centres (`elevation-half-pixel`) : Strava 14/21, 5 / 5
> hors vérité, témoins 13/15 à 73 % (1 240 / 1 692), Normandie `STEEP` (P95 propre 41,1 %, 4 %),
> Cheviré P95 7,4 %, `n13` sans candidat mesuré — voir `elevation-half-pixel.md` § Résultats. Les
> tables ci-dessous restent valables pour la convention qu'elles décrivent : la variante (a⌐) du
> banc les reproduit exactement.
> Le DEFAULT a depuis été recalé aux centres (Strava 18/21, témoins 14/15 à 98 %) : voir
> *Recalage aux centres (E2 quinquies)*.


Livré : `IgnLidarHdLayer` (`:elevation`, couches brutes MNT / MNS / MNH du même WMS, sans id de
catalogue ni repli : le hors-données reste `NaN`) et `DeckDetector` + `DeckDetectorOptions`
(`:gpx`, `commonMain`, port d'`analyze.py`, seuils dans `DeckDetectorOptions.PYTHON` — le
DEFAULT jusqu'à E2 bis ; E2 ter a changé DEFAULT, voir *Ponts pentus (E2 ter)*). Aucun
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
  voit pas une détection étirée qui touche un pont, d'où la colonne « hors vérité ». Le critère
  d'acceptation de la spec (zéro détection sur des témoins forestiers et urbains) est **jugé en
  E2 bis : 0 FP et 0 station hors vérité (`G`/`O`/`L`) sur 12 témoins** (30,4 km) ; seule
  détection : le pont propre de `n04` (`X`, exclu du score). C'est vrai à `PYTHON` (DEFAULT de
  E2 bis) et dans 28 des 29 variantes du balayage E2 bis (la 29ᵉ coupe le test de canyon, pour
  diagnostic) ; le balayage E2 ter, fait depuis le nouveau DEFAULT, est dans *Ponts pentus (E2
  ter)* ; la canopée rejetée
  passe de 17,9 km (Python) à 25,2 km en z15, sans fuite en tablier.

#### Témoins (E2 bis)

> **Note (2026-10-13).** Chiffres mesurés à l'ancienne convention des coins (lecture bilinéaire
> décalée d'un demi-pixel au sud-est). Aux centres (`elevation-half-pixel`) : Strava 14/21, 5 / 5
> hors vérité, témoins 13/15 à 73 % (1 240 / 1 692), Normandie `STEEP` (P95 propre 41,1 %, 4 %),
> Cheviré P95 7,4 %, `n13` sans candidat mesuré — voir `elevation-half-pixel.md` § Résultats. Les
> tables ci-dessous restent valables pour la convention qu'elles décrivent : la variante (a⌐) du
> banc les reproduit exactement.
> Le DEFAULT a depuis été recalé aux centres (Strava 18/21, témoins 14/15 à 98 %) : voir
> *Recalage aux centres (E2 quinquies)*.


**Pourquoi.** Le critère d'acceptation de E2 (zéro détection sur des témoins forestiers et urbains)
n'était pas jugé, faute de témoin. Sur la grille z15 de vcyclist, cinq ponts sont perdus à des
seuils qui sont au bord (Pont écluse, Rotonde, Bellevue, Échevis, Haudaudine). Retoucher ces seuils
demande deux choses : des **témoins négatifs** dédiés, qui montrent ce que coûte une variation, et
des **ponts que le réglage n'a jamais vus** (témoins positifs), qui montrent si un gain se généralise.
`DeckDetectorOptions.DEFAULT` **n'a pas changé en E2 bis** : le balayage ci-dessous construit ses
variantes par `copy` dans le test. Il a changé en E2 ter (*Ponts pentus*), et le DEFAULT de E2 bis
reste disponible sous le nom `DeckDetectorOptions.PYTHON` : tous les chiffres de cette section sont
ceux de `PYTHON`. Le tableau *Sensibilité* de cette section est la sortie de `deckControls` au commit
23c5420, avant E2 ter. Le banc actuel fait son balayage depuis le DEFAULT E2 ter : seules ses lignes
`PYTHON (E2 bis DEFAULT)` et `PYTHON, canyon off` reproduisent E2 bis.

**Sites.** Il y a 12 témoins négatifs `n01..n12` (30,4 km) et 8 témoins positifs `p01..p08`, tous
hors des quatre itinéraires Strava. Colonnes : stations à 5 m par type. `G` = sol, `O` = ouvrage
au-dessus, `L` = pont ou viaduc le long de la route, `X+x` = ouvrage propre ou passage inférieur, et
leurs marges, exclus, `D` = tablier propre.

| id | catégorie | lieu | km | G | O | L | X+x |
|---|---|---|---|---|---|---|---|
| n01 | rue le long d'un viaduc ferroviaire | Paris 15ᵉ, bd de Grenelle → bd Garibaldi, métro 6 aérien | 1,98 | 12 | 7 | 377 | 0 |
| n02 | rue sous l'approche d'un viaduc | Rezé, rue de l'Île Botty sous le Pont de Cheviré (N 844) | 1,54 | 298 | 11 | 0 | 0 |
| n03 | fond de vallée sous un viaduc très haut | Millau, D 992 sous le Viaduc de Millau | 2,41 | 454 | 12 | 0 | 16 |
| n04 | voie rapide en déblai sous des passages supérieurs | Nantes, périphérique nord extérieur (N 844), avec son pont de la Beaujoire | 4,00 | 634 | 73 | 6 | 88 |
| n05 | chemin de halage sous platanes | Canal du Midi, Ramonville → Castanet | 3,67 | 728 | 7 | 0 | 0 |
| n06 | route forestière | Forêt de Fontainebleau, Route Ronde (D 301) | 3,26 | 652 | 0 | 0 | 0 |
| n07 | piste cyclable en pinède | Landes, Piste de la Côte (nord de Saint-Girons-Plage) | 1,70 | 340 | 0 | 0 | 0 |
| n08 | rue urbaine, chaussée couverte | Nantes, rue de Strasbourg, cours John-Kennedy (Pont de la Rotonde), puis quai André-Morice vers l'ouest | 2,90 | 500 | 16 | 0 | 65 |
| n09 | auvent de péage, passerelle d'aire | A7, barrière de Lançon-Provence puis aire de Lançon | 2,59 | 502 | 16 | 0 | 0 |
| n10 | route sur levée | Levée de la Loire, D 952, Saint-Martin-de-la-Place | 3,35 | 670 | 0 | 0 | 0 |
| n11 | ville sous un viaduc ferroviaire en pierre | Morlaix, quai de Léon → rue de Paris | 1,53 | 251 | 7 | 0 | 49 |
| n12 | allée urbaine sous platanes | Toulouse, allées de Brienne | 1,43 | 265 | 0 | 0 | 22 |

| id | ouvrage | km | spans `D` (m) | statut depuis E2 ter |
|---|---|---|---|---|
| p01 | Pont de Cheviré (N 844), un sens | 3,01 | 1 685 | réglage (E2 ter) |
| p02 | Pont de Thouaré + pont de la D 37 (deux bras de Loire) | 1,84 | 400 ; 235 ; 30 (rue des Ponts) | vu, non réglant |
| p03 | Pont Bretagne-Anjou, Ancenis (suspendu) | 1,49 | 430 | vu, non réglant |
| p04 | Pont de Langeais, D 57 (suspendu, étroit) | 0,96 | 390 | vu, non réglant |
| p05 | Pont Cessart + Pont des Cadets, Saumur (arches, île) | 1,45 | 275 ; 195 ; 30 (place de la Résistance) | vu, non réglant |
| p06 | Pont de Normandie (N 1029), un sens | 4,52 | 2 150 ; 60 ; 35 | réglage (E2 ter) |
| p07 | Viaduc de Millau (A 75), un sens | 4,34 | 2 465 ; 25 | réglage (E2 ter) |
| p08 | Passage supérieur bd Professeur-René-Auvigné sur le périphérique (tiré de n04) | 0,59 | 55 | réglage (E2 ter) |

Les témoins positifs étaient tenus à l'écart du réglage en E2 bis. **Depuis E2 ter, ils ne le sont
plus** : `p01`, `p06`, `p07` et `p08` ont fixé le correctif, et `p02` à `p05` ont été vus dans son
balayage (voir *Ponts pentus (E2 ter)*, *Statut des témoins*). Il ne reste aucun témoin positif
réellement tenu à l'écart pour le test de pente ; c'est l'objet de l'étape *Témoins E2 ter*.

Chaque span `D` compté laisse au moins 150 m d'itinéraire avant et après lui (vérifié par
`controls.py` et par le test non conditionné) : `DeckDetector` prend, au bout d'un itinéraire, le
MNT du span lui-même comme « MNT juste à côté », et le test de canyon mesurerait alors ce bornage.

Couverture LiDAR HD, d'après une sonde MNS 8 × 8 px faite à la conception. Le péage de
Saint-Arnoult (A10) et la forêt de Compiègne sont hors couverture (0/64 pixels) et n'ont pas été
retenus. Sur les 20 sites retenus, il n'y a **aucun** `NaN` de MNT ni de MNS sur les stations. Aucune
chaussée sous une halle de gare n'a pu être identifiée sans ambiguïté. La chaussée couverte ne
compte que **30 m notés** : sur `n08`, le quai André-Morice passe d'abord en tunnel
(`tunnel=yes`, `layer=-1`, 65 m), ouvrage propre exclu en `X` avec ses marges (28 stations), et seule
la section `covered=yes` qui suit (6 stations `O`) est notée. S'y ajoutent l'auvent de péage et la
passerelle couverte de l'aire de Lançon (`n09`, 16 stations `O`).

Sites déplacés par rapport à la conception, les vérifications restant inchangées :

- les points de passage, estimés de mémoire, ont été recalés sur les voies OSM ;
- `n04` est passé sur la portion N 844 effectivement « périphérique nord » (Porte de la Chapelle →
  est de la Beaujoire), sens extérieur ;
- `n07` a été déplacé sur la Piste de la Côte, seule voie `cycleway` continue de la zone, la
  Vélodyssée plus au sud y étant cartographiée en `track`/`path` ;
- `n08` : la sélection automatique de passages sous immeuble ne donnait que des impasses de cour ou
  des voies piétonnes (3 % de rue). Elle est remplacée par un itinéraire fixe. Le quai André-Morice
  vers l'ouest ne se rejoint depuis le centre que par un demi-tour (quai Magellan à l'aller, quai
  André-Morice au retour, 15 m plus loin) : l'itinéraire y entre donc par l'est, après la rue de
  Strasbourg, le cours John-Kennedy et le Pont de la Rotonde (pont propre, `X`) ;
- `n09` : prolongé de 380 m vers l'est, jusqu'au nœud OSM suivant, pour passer sous la voie couverte
  (way 184840286) et la passerelle de l'aire de Lançon (way 1409740514) ;
- `n11` : le troisième point de passage, sur la bretelle de la place des Otages, faisait revenir
  l'itinéraire de 130° ; il est passé sur la branche ouest de la place ;
- `p01` : l'approche s'arrêtait sur un pont voisin de 60 m, sans sol avant lui. Les deux bouts sont
  désormais choisis à ≈ 700 m du tablier, sur une voie sans pont dans leurs 150 derniers mètres ;
- `p02`, `p05`, `p07` : prolongés au-delà du dernier pont (0, 15 et 60 m d'itinéraire après lui
  auparavant ; 225, 160 et 860 m désormais). `p05` y gagne le pont de la place de la Résistance
  (30 m) ;
- `p04` : la rampe cyclable du bout nord tournait en goutte d'eau (stations à moins de 15 m l'une de
  l'autre). L'itinéraire part désormais de la D 952 sur la levée ;
- `p06` : le Pont de Normandie est la N 1029 (`trunk`) dans OSM, pas l'A 29 ;
- `p08` : la rue nommée s'arrête à 224 m. Le tracé se prolonge sur toute voie de desserte, avec la
  même règle des ± 300 m.

**Méthode.** [`analysis/controls.py`](../deck-strava-data/analysis/controls.py) n'interroge
qu'Overpass : une requête par emprise (points de passage + 400 m, ou emprise fixée par site), une à
la fois, réponses en cache dans `osm/controls/`. Il trace l'itinéraire par Dijkstra sur les voies OSM
des classes du site, en respectant le sens unique sur les voies rapides, ce qui garde une seule
chaussée. Il rééchantillonne à 5 m exactement comme `fetch.py`. La relance sur le cache est hors
ligne et déterministe, sorties identiques à l'octet ; mais ce cache est **local et non versionné**
(`osm/` est ignoré par git). Depuis un clone neuf, le script interroge l'OSM du jour et ne
reproduit pas forcément la vérité livrée : la référence est la vérité versionnée, et
`CONTROL_GUARDS` signale toute dérive. Chaque site porte ses propres vérifications : noms attendus,
référence de l'ouvrage au-dessus, longueur des tabliers. Tous les sites en portent trois de plus :
aucun virage de plus de 120° sur 2 × 15 m, aucune station à moins de 15 m d'une autre distante de
plus de 60 m le long de l'itinéraire (pas de demi-tour ni d'aller-retour), et 150 m d'itinéraire
de part et d'autre de chaque span `D` compté. Quand une vérification échoue, on déplace le point de
passage, jamais la vérification.

| type | règle |
|---|---|
| `D` | la voie propre de la station porte `bridge` ou `man_made=bridge`, sur un témoin positif |
| `X` | sur un témoin négatif, la voie propre porte `bridge`, `tunnel` (sauf `building_passage`) ou `layer > 0` ; ou, sur tout témoin, une voie en tunnel ou en buse (`tunnel` ≠ `no`/`building_passage`, ou cours d'eau à `layer < 0`) **passe sous** l'itinéraire, sur ± (l/2 / sin θ) autour du croisement, sauf à `layer ≤ −2` (métro foré) |
| `x` | moins de 35 m (7 stations) d'une station `D`/`X` |
| `O` | une autre voie routière ou ferrée surélevée (`bridge` ou `layer ≥ 1`) **croise** le segment, sur ± (l/2 / sin θ + 10 m) autour du croisement ; ou la voie propre est `building_passage` / `covered=yes` ; ou la station est dans un polygone `building=roof` / `man_made=canopy`, ou à moins de 25 m d'une `barrier=toll_booth` |
| `L` | un pont (`bridge` ≠ `no` ou `man_made=bridge`), ou une voie ferrée à `layer ≥ 1`, passe à ≤ 20 m de la station, cap à ± 25°, sur ≥ 25 m consécutifs ; un escalier ou un trottoir à `layer ≥ 1` sans `bridge` n'en est pas un |
| `G` | tout le reste, voie propre à `layer < 0` comprise : une route en passage inférieur est au sol |

l = `width`, sinon de 30 m pour une autoroute à 10 m pour une voie de desserte, 12 m pour une voie
ferrée ; θ = angle du croisement. Priorité : D/X > x > O > L > G. Les marges se justifient ainsi :

- **35 m** pour `x` : c'est l'extension maximale le long de `MNH > 1` mesurée en E2 sur les 21 ponts
  de Python ;
- **20 m** pour `L` : pixel z15 (≈ 3,3 m à 46° N), plus ± 1 pixel de bilinéaire, plus 3 à 5 m
  d'erreur d'axe OSM, plus une demi-largeur de tablier parallèle ;
- **l/2 + 10 m** pour `O` : la largeur de l'ouvrage, plus la même marge pixel/OSM. Le `/ sin θ`
  ajouté à la conception allonge la fenêtre sous un ouvrage biais.

Les ouvrages qui **passent au-dessus** du témoin ne sont pas exclus. Ce sont précisément les
négatifs difficiles : un tablier lisse et haut au-dessus d'une route au sol. Seuls les ouvrages
propres de l'itinéraire (`X`, `D`) sont connus exactement, puisque le tracé est fait de voies OSM.
Les **passages inférieurs** sont l'exception : une route sur un court ouvrage non tagué au-dessus
d'un chemin en tunnel est un vrai tablier, avec un vrai canyon. Relevé au WMS (≈ 1,5 m/px) : `n03`
station 11, D 992 au-dessus de la Route du Larzac (way 157512472, 17 m), MNS 391,75 m pour un MNT de
385,82 m, voisins MNS = MNT ; `n04` station 763, périphérique au-dessus d'un sentier (way 419403514,
42 m), MNS 10,00 m pour un MNT de 5,84 m. Une relaxation qui y détecterait un tablier aurait raison,
et serait comptée en FP : ces croisements sont donc exclus (`X`, structure `underpass` dans
`spans.csv`), comme les autres passages inférieurs, même sans chute de MNT relevée (buse du Gesvres
sur `n04`, Jarlot, Queffleuth et rivière de Morlaix sur `n11`).

L'échantillonnage suit le chemin de la variante (a) : MNS − MNT z15, bilinéaire, une couche après
l'autre. Les métriques sont celles du code :

- un **FP** est une détection acceptée sans aucune station `D`/`X`/`x` ;
- **hors vérité** compte les stations `G`/`O`/`L` dans les détections acceptées, puis dans leur
  masque de tablier ;
- le **rappel des témoins positifs** compte les spans `D` d'au moins 20 m touchés par une
  détection acceptée. La **couverture** est la part des stations `D` qui sont dans une détection
  acceptée, arrondie au pour cent. Elle voit une détection partielle que le rappel compte comme
  trouvée ;
- les **ponts propres des négatifs** (`X`, structure `bridge`, au moins 20 m : Beaujoire sur `n04`,
  Rotonde sur `n08`) ne sont pas des FP ; ils sont comptés en rappel, pour qu'un réglage qui les
  perd se voie.

Banc : deux tests de plus dans `ElevationBenchProbeTest`.

- `deck_controls_parse_and_hold_their_guards`, **non conditionné**. Il vérifie les tailles :
  12 + 8 rôles, `n=` de l'en-tête, `s = 5k`, comptes par type contre `index.csv`, runs et structures
  contre `spans.csv`, les deux ponts propres des négatifs, et les 150 m de part et d'autre de chaque
  span `D` compté. Il fige les comptes de chaque site (`CONTROL_GUARDS`), et vérifie que chaque GPX
  rééchantillonné redonne la vérité : `n` à ± 1, premières et dernières stations à 1e-6°.
- `deckControls`, `INTEGRATION=1`, qui imprime `[controls]`. Il vérifie que l'extraction du
  score Strava reproduit E2 à `PYTHON` (16/21, FP 0, 8 / 8 stations) et **le critère
  d'acceptation au DEFAULT E2 ter** (en E2 bis, ce DEFAULT était `PYTHON`) : 0 FP et 0 station `G`/`O`/`L` dans une détection ou un masque sur les 12 négatifs, et
  le pont de la Beaujoire trouvé. Le balayage reste imprimé, pas vérifié.

```bash
python3 -I docs/deck-strava-data/analysis/controls.py
INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*ElevationBenchProbeTest*' --rerun-tasks -i
```

Coût : 178 tuiles BIL de plus (46,6 MB) pour les 20 sites (MNT et MNS z15, 89 tuiles distinctes
par couche), puis 0.

**Mesures DEFAULT.** Les négatifs donnent **0 FP et 0 station hors vérité** (0/0/0 en span comme en
masque) sur 12 témoins, soit 30,4 km. La seule détection sur un négatif est le pont de la Beaujoire
de `n04`, son propre ouvrage (`X`), accepté à juste titre (1,75–1,95 km, chute MNT 11,8 m). L'autre
pont propre, la Rotonde sur `n08`, ne donne aucun candidat, comme sur `test2` en E2 (MNS z15 sans
tablier visible) : ponts propres 1/2.

Tous les candidats que le détecteur a mesurés sur les négatifs (les groupes `ROUGH` ne sont jamais
mesurés ; P95 « — » = span court, jugé sur sa seule rugosité) :

| témoin | km | L (m) | MNH max | rugosité | P95 % | chute MNT | verdict | sous/à côté |
|---|---|---|---|---|---|---|---|---|
| n02 | 0,79–0,82 | 35 | 50,2 | 0,10 | 208,8 | 0,0 | NO_CANYON | `O` Pont de Cheviré |
| n04 | 0,02–0,06 | 50 | 7,1 | 0,22 | 16,5 | −0,1 | NO_CANYON | `O` route de Paris |
| n04 | 0,59–0,61 | 20 | 5,9 | 0,15 | — | 0,0 | NO_CANYON | `O` tram ligne 1 |
| n04 | 1,13–1,18 | 50 | 6,5 | 0,04 | 13,8 | 0,1 | NO_CANYON | `O` route de Saint-Joseph |
| n04 | 1,75–1,95 | 205 | 12,5 | 0,06 | 0,5 | 11,8 | tablier | `X` pont de la Beaujoire (propre) |
| n05 | 0,01–0,52 | 520 | 33,0 | 0,56 | 13,8 | 0,1 | NO_CANYON | platanes (`G`) |
| n05 | 1,50–2,98 | 1 485 | 31,3 | 0,36 | 43,0 | 0,3 | NO_CANYON | platanes (`G`) |
| n06 | 1,80–1,91 | 120 | 21,9 | 2,61 | 55,2 | −0,1 | NO_CANYON | forêt (`G`) |
| n08 | 2,58–2,86 | 290 | 18,7 | 0,48 | 67,7 | 0,8 | NO_CANYON | bâti (`G`), section couverte du quai et Pont Haudaudine (`O`) |
| n09 | 0,72–0,73 | 20 | 7,5 | 0,67 | — | −0,1 | NO_CANYON | `O` péage |
| n09 | 2,21–2,23 | 30 | 15,8 | 0,27 | — | 0,0 | NO_CANYON | `O` passerelle de l'aire de Lançon |
| n11 | 0,53–0,65 | 120 | 9,0 | 0,65 | 39,2 | 0,0 | NO_CANYON | bâti (`G`) |

Groupes `ROUGH` : n03 0,04 km, n04 0,02, n05 0,93, n06 2,75, n07 0,20, n08 0,54, n11 0,02, n12 1,40
(5,9 km en tout). n01 (métro 6 sur 377 stations `L`) et n10 (levée) n'ont **aucun** candidat.

Sur les témoins positifs : **11/15 spans `D`** trouvés, **0 FP**, mais seulement **400 / 1 692
stations `D`** (24 %) dans une détection acceptée, dont 385 dans un masque de tablier.

| témoin | détections acceptées (km) | rappel (couv.) | verdict des spans manqués | hors vérité G |
|---|---|---|---|---|
| p01 Cheviré, réglage (E2 ter) | — | 0/1 (0 %) | 1 685 m `STEEP` (P95 24,5 %) | 0 |
| p02 Thouaré | 0,73–1,60 | 2/3 (94 %) | rue des Ponts (30 m) : aucune suite haute | 37 (île entre les bras, hors masque) |
| p03 Ancenis | 0,53–0,93 | 1/1 (93 %) | — | 0 |
| p04 Langeais | — | 0/1 (0 %) | 390 m `ROUGH` (groupe de 0,36 km) | 0 |
| p05 Saumur | 0,38–0,66 ; 1,00–1,29 | 3/3 (100 %) | — | 0 |
| p06 Normandie, réglage (E2 ter) | 3,05–3,35 ; 3,68–3,86 | 3/3 (18 %) | 1,22–2,60 km du grand pont `STEEP` (P95 35,1 %) : seuls les ≈ 300 derniers mètres (côté sud, ≈ 14 % du tablier) sont acceptés | 5 |
| p07 Millau, réglage (E2 ter) | 0,29–0,31 (pont voisin) | 1/2 (1 %) | viaduc 2 465 m `STEEP` (P95 34,8 %) | 0 |
| p08 passage supérieur, réglage (E2 ter) | 0,23–0,31 | 1/1 (100 %) | — | 0 |

Les trois `STEEP` ne viennent pas des culées : aucune station raide dans les 100 premiers ou derniers
mètres des trois tabliers. Elles viennent de sauts du MNS **au milieu du tablier** :

- Cheviré : −56 % au km 1,72, le MNS passe de 57 à 46 m ;
- Normandie : ±150 % aux km 1,81–1,84, le MNS tombe à 18–29 m sur un tablier à ≈ 48 m, MNT 0,6 m ;
- Millau : ±100 % aux km 1,71–1,79, sauts de ≈ 20 m sur un tablier à ≈ 620 m.

Les causes, mesurées depuis, sont trois mécanismes distincts : voir *Ponts pentus (E2 ter)*,
*Diagnostic*.

Langeais (suspendu, étroit) est perdu en `ROUGH`. Un groupe `ROUGH` n'est jamais mesuré, et la
cause n'est pas relevée.

**Sensibilité.** Une seule variation à la fois depuis DEFAULT ; la dernière ligne en cumule cinq.
`mergeGapM` 200 / 100 / 50 resserre la fusion (DEFAULT 300) au lieu de la relâcher. Les deux lignes
`minCanyonDropM` 0,3 et −1 (test de canyon coupé : toutes les chutes mesurées sur les témoins sont
au-dessus de −1 m) ne sont pas des candidats : elles montrent ce que tiennent les autres
garde-fous seuls. Les pertes sont données avec le verdict que le pont prend.

| réglage | ponts /21 | récupérés / perdus (verdict) | rappel témoins pos. (couv. `D`) | FP négatifs | hors vérité nég. G/O/L | ponts propres nég. /2 | FP Strava / hors vérité |
|---|---|---|---|---|---|---|---|
| DEFAULT | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| tallMnhM 4,0 | 13 | — / way 362043805, Willy-Brandt, Trois-Continents (`STEEP`) | 12/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 6 |
| tallMnhM 3,0 | 14 | — / Willy-Brandt, Trois-Continents (`STEEP`) | 12/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 36 |
| tallMnhM 2,5 | 15 | Haudaudine / Willy-Brandt, Trois-Continents (`STEEP`) | 12/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 37 |
| minTallRunM 10 | 16 | — / — | 12/15 (24 %) | 0 | 0/0/0 | 1 | **1** / 10 |
| minTallRunM 5 | 15 | — / Trois-Continents (`STEEP`) | 12/15 (24 %) | 0 | 0/0/0 | 1 | **1** / 10 |
| maxRoughnessM 1,0 | 16 | Bellevue / Pirmil (`STEEP`) | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| maxRoughnessM 1,2 | 16 | Bellevue / Pirmil (`STEEP`) | 12/15 (28 %) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| maxRoughnessM 1,5 | 16 | Bellevue / Pirmil (`STEEP`) | 12/15 (28 %) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| minSmoothFraction 0,5 | 17 | Bellevue / — | 12/15 (28 %) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| minSmoothFraction 0,4 | 17 | Bellevue / — | 12/15 (28 %) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| maxShortRoughnessM 0,6 / 0,8 | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| roughnessWindowM 25 | 16 | — / — | 12/15 (28 %) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 8 |
| roughnessWindowM 45 | 17 | Haudaudine / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| surfaceMedianWindowM 15 | 14 | — / Pirmil, way 410671587 (`STEEP`) | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 7 |
| surfaceMedianWindowM 35 | **18** | Bellevue, Haudaudine / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 10 |
| mergeGapM 200 | 17 | Haudaudine / — | 11/15 (29 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| mergeGapM 100 | 17 | Haudaudine / — | 11/15 (34 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| mergeGapM 50 | 17 | Haudaudine / — | 11/15 (34 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| maxSlopeP95 0,12 / 0,15 | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| minCanyonDropM 2,0 / 1,0 | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| *minCanyonDropM 0,3, diagnostic* | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| *minCanyonDropM −1, canyon coupé, diagnostic* | 16 | — / — | 11/15 (24 %) | **2** | 0/**10**/0 | 1 | **3** / 28 |
| deckMnhM 0,5 | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| deckMnhM 2,0 | 16 | — / — | 11/15 (23 %) | 0 | 0/0/0 | 1 | 0 / 2 |
| minLengthM 20 | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| *stress, pas une proposition* : tallMnhM 3, minTallRunM 10, maxRoughnessM 1,2, maxShortRoughnessM 0,8, mergeGapM 100 | 16 | Bellevue / Willy-Brandt (`STEEP`) | 13/15 (33 %) | 0 | 0/0/0 | 1 | **2** / 15 |

« Trois-Continents » désigne la structure `Boulevard Victor Schœlcher; Pont des Trois Continents`.
Le témoin positif gagné à 12/15 est la rue des Ponts (`p02`) pour `tallMnhM` et `minTallRunM`,
Langeais (`p04`) pour `maxRoughnessM` ≥ 1,2, `minSmoothFraction` et `roughnessWindowM` 25.

Les FP du balayage :

- `minTallRunM` 10 et 5 : `test4` km 5,37, 10 m, MNH 15,4 m, chute MNT 4,0 m, dans l'emprise du
  tunnel des Petits Goulets (route en falaise) ;
- *stress* : `test2` km 3,01–3,03 (25 m, MNH 4,5 m, chute 4,0 m) et `test4` km 61,33 (10 m, MNH
  5,0 m, chute 3,7 m) ;
- canyon coupé : sur les négatifs, deux spans courts sous un ouvrage, `n04` km 0,59–0,61 (tram
  ligne 1, 20 m, rugosité 0,15) et `n09` km 2,21–2,23 (passerelle de l'aire, 30 m, rugosité 0,27) ;
  sur Strava, `test1` km 0,33, `test4` km 23,18 et 42,96 (chutes −0,1 à −0,3 m).

Les détections hors vérité de `tallMnhM` 3,0 et 2,5 (36 et 37 stations) restent dans des spans
étirés, pas dans le masque de tablier (7 et 8 stations).

**Constats**

- **Critère d'acceptation : tenu sur ce banc.** Les 12 témoins négatifs donnent 0 FP et 0 station
  hors vérité (`G`/`O`/`L`) ; seule détection : le pont propre de `n04` (`X`, exclu du score). C'est
  vrai à DEFAULT (vérifié par le test) et dans 28 des 29 variantes du balayage, *stress* compris ;
  seule la ligne de diagnostic « canyon coupé » en produit.
- **Sur les négatifs, le test de canyon rejette tout ce qui est mesuré.** Tablier de Cheviré
  au-dessus de `n02` (MNH 50 m, rugosité 0,10), passages supérieurs de `n04` (rugosité 0,04 à
  0,22), auvent de péage et passerelle de `n09`, et **voûte de platanes du Canal du Midi** (rugosité
  0,36 à 0,56, sous le seuil de 0,8 sur 2 km) : tous sont lisses, et tous écartés par `NO_CANYON`,
  avec une chute de MNT de −0,1 à 0,8 m. La rugosité n'écarte que la forêt et le bâti ordinaires
  (`ROUGH`, 5,9 km en tout). Les ouvrages au-dessus (`O`) donnent sept candidats, tous `NO_CANYON` ;
  l'ouvrage latéral (`L`, métro 6 sur 377 stations) n'en donne aucun.
- **Sans canyon, la pente tient les longs, pas les courts.** Canyon coupé, les candidats longs des
  négatifs tombent en `STEEP` (P95 13,8 à 208,8 %), mais deux spans courts sous un ouvrage, jugés sur
  leur seule rugosité (0,15 et 0,27 < 0,4), passent : 2 FP, 10 stations `O`. Deux marges sont
  minces : les passages supérieurs de `n04` à P95 13,8 et 16,5 % ne sont qu'à peine au-dessus de
  `maxSlopeP95`.
- **Conséquence pour la retouche des seuils.** Comme le canyon rejette tout candidat des négatifs
  avant les autres tests, un 0 dans les colonnes négatives d'une ligne qui ne touche pas au canyon
  (`tallMnhM`, rugosité, fusion, médiane) **ne prouve pas** que la variation est sûre : il était
  presque acquis. Le coût de ces variations ne se voit que sur les **routes Strava** (Petits Goulets
  dès `minTallRunM` 10), en **ponts perdus** (Pirmil, Willy-Brandt, Trois-Continents) et sur le
  **pont propre de `n04`**. Les négatifs ne jugent vraiment que le test de canyon.
- **La marge du canyon est plus mince qu'il n'y paraît.** Chute ≤ 0,8 m sur les négatifs contre
  ≥ 5,2 m sur tous les ponts acceptés des témoins positifs ; mais les FP Strava des variations, qui
  ne sont pas des ponts, ont des chutes de 3,7 à 4,0 m (`test4` km 5,37 et 61,33, `test2` km 3,01),
  dans cet écart et au-dessus du seuil de 3,0 m. Les négatifs ne contiennent **aucun** cas où le sol
  tombe sous ou à côté d'une route qui n'est pas sur un pont : c'est une limite du banc, pas la
  preuve d'une marge de 5 m.
- **Variations sans FP ni pont Strava perdu** (hors vérité Strava +1 à +2) :
  - `surfaceMedianWindowM` 35 récupère **Bellevue et Haudaudine** (18/21, hors vérité 10 / 10) ;
  - `mergeGapM` 200, 100 et 50 (mesurés) et `roughnessWindowM` 45 récupèrent Haudaudine (17/21) ;
  - `minSmoothFraction` 0,5 et 0,4 récupèrent Bellevue (17/21) et Langeais, **mais perdent le pont
    propre de la Beaujoire** (`STEEP`), comme `roughnessWindowM` 25 et `maxRoughnessM` ≥ 1,2. La
    cause de ce `STEEP` n'est pas mesurée.
- **Variations qui coûtent** :
  - `tallMnhM` < 5 perd 2 à 3 ponts nantais, tous en `STEEP` ;
  - `minTallRunM` 10 / 5 crée le FP des Petits Goulets ;
  - `maxRoughnessM` ≥ 1,0 perd Pirmil (`STEEP`), et ≥ 1,2 la Beaujoire ;
  - `surfaceMedianWindowM` 15 perd Pirmil et way 410671587 (`STEEP`).

  **Pont écluse et Rotonde** ne sont rattrapés par aucun réglage : le MNS z15 ne voit pas leur
  tablier (une seule station haute au plus), et la Rotonde de `n08` le confirme. **Échevis** est
  `ROUGH` (3 stations hautes) et n'est pas rattrapé jusqu'à `maxRoughnessM` 1,5 ni à
  `minSmoothFraction` 0,4 ; il l'est en z14 (E2). Leur cas relève du zoom ou de l'échantillonnage,
  pas d'un seuil **dans la plage balayée**.
- **Les témoins positifs montrent une lacune que les quatre routes cachaient.** Les trois
  grands ouvrages (Cheviré 1,7 km, Normandie 2,2 km, Millau 2,5 km) sont `STEEP` à P95 25 à 35 %,
  pour des sauts du MNS en milieu de tablier, et **aucun réglage du balayage ne les rattrape** :
  `maxSlopeP95` 0,15 ne suffit pas. Le rappel par span (11/15) le masque. La couverture des stations
  `D` (24 %) le montre. Traité en E2 ter (*Ponts pentus*), dans le détecteur et non en E3.
- **Limites du banc.** Sur 30,4 km de négatifs, 26,5 km sont `G`. Les cas difficiles sont minces :
  149 stations `O` (≈ 0,75 km en 16 spans, qui ont donné 7 candidats), 383 stations `L`
  dont 377 sur le seul métro 6 de `n01`, 30 m de chaussée couverte notée. La forêt dense tient en
  un site plat (`n06`, 3,3 km), plus une pinède (`n07`, 1,7 km) ; pas de forêt en pente. Les tunnels
  propres sont exclus (`X`) alors que le relief au-dessus d'un tunnel est un risque connu (jugé
  seulement sur `test4`, Grands Goulets et Combe Laval). Manquent : un sol qui tombe sous ou à côté
  de la route hors pont (route en corniche, bord de quai ou de berge, digue, buse sous remblai),
  une forêt en pente, une chaussée sous des immeubles.
- Ces mesures ne choisissent pas de nouveau DEFAULT : la décision revient à l'utilisateur. **E2
  ter a depuis changé DEFAULT** (pontage des stations rugueuses et MNT > MNS pris pour haut), sur la
  demande de l'utilisateur de traiter les ponts pentus ; aucune des variations ci-dessous n'y est
  entrée, et le changement attend sa relecture avant commit. Les
  variations sans FP, sans pont Strava perdu et sans perte de la Beaujoire sont
  `surfaceMedianWindowM` 35, `mergeGapM` 200, 100 et 50, et `roughnessWindowM` 45 (hors vérité
  Strava +1 à +2). Leur combinaison n'a pas été mesurée, hors la ligne *stress*.

#### Ponts pentus (E2 ter)

> **Note (2026-10-13).** Chiffres mesurés à l'ancienne convention des coins (lecture bilinéaire
> décalée d'un demi-pixel au sud-est). Aux centres (`elevation-half-pixel`) : Strava 14/21, 5 / 5
> hors vérité, témoins 13/15 à 73 % (1 240 / 1 692), Normandie `STEEP` (P95 propre 41,1 %, 4 %),
> Cheviré P95 7,4 %, `n13` sans candidat mesuré — voir `elevation-half-pixel.md` § Résultats. Les
> tables ci-dessous restent valables pour la convention qu'elles décrivent : la variante (a⌐) du
> banc les reproduit exactement.
> Le DEFAULT a depuis été recalé aux centres (Strava 18/21, témoins 14/15 à 98 %) : voir
> *Recalage aux centres (E2 quinquies)*.


**Pourquoi.** À DEFAULT E2 bis, trois grands ouvrages tenus à l'écart échouent au test de pente :
Cheviré (`p01`, P95 24,5 %), Normandie (`p06`, travée principale à 35,1 %, seuls les ≈ 300 derniers
mètres acceptés) et Millau (`p07`, 34,8 %). Leurs tabliers réels ne dépassent pas quelques pour
cent. Les stations raides sont toutes en milieu de tablier (aucune dans les 100 m des extrémités).
Le rappel par span (11/15) le masque ; la couverture des stations `D` (400 / 1 692, 24 %) le montre.
Saint-Nazaire (`test1`, 3,4 km, 66 m de haut, haubané) passe à 6,1 % et sert de contraste.

**Diagnostic, trois mécanismes.** Les trois ont la même signature : l'erreur porte sur des stations
**rugueuses** (le MNS y saute), et la vraie pente reste lisible de part et d'autre du trou.
Échantillonnage : variante (a), MNT + MNS z15, bilinéaire aux coins de pixel de vcyclist, stations à
5 m.

| site | mécanisme | origine | sens de l'erreur | z16 corrige ? |
|---|---|---|---|---|
| `p01` Cheviré | la trace est à ≈ 3 m du bord du tablier, avec 40 à 55 m de vide au-delà ; le pixel z15 mélange tablier et vide, et la convention des coins décale l'échantillon d'≈ 1,5 m vers le vide | échantillonnage | vers le bas seulement | oui (6,5–6,7 %) |
| `p07` Millau | une bande de trous du MNS le long du terre-plein central, à −9..−3 m de la trace ; z15 et la convention des coins l'étalent sur la trace | échantillonnage (et trous IGN à z17) | vers le bas seulement | oui (3,7–4,5 %) |
| `p06` Normandie | (A) pylônes et haubans, +5..+45 m sur toute la chaussée ; (B) un vrai trou du MNS à s 1815–1835, présent aussi à z17 ; (D) MNT au-dessus du MNS sur 164 stations `D`, d'où un MNH négatif qui coupe le tablier | données IGN | vers le haut et vers le bas | **non** (86 % à z16, z17 pire) |

Les chiffres de ce tableau, comme tous ceux marqués « port Python » dans cette sous-section,
viennent de scripts de diagnostic **jetables et non commités** (`build/diag-normandie/*.py` sur
des échantillons `*.npz`, ignorés par git, et le bloc-notes de la session) ; ce n'est pas
`analyze.py`, et rien ne les reproduit depuis le dépôt. Seuls les chiffres « banc Kotlin » sont
reproductibles. Les pentes z16 de la dernière colonne sont des mesures sur le MNS z16, pas la pente
de référence : celle de Cheviré est 6,3 % (*Inputs §4*), celle de Millau ≈ 3 %.

**Ce qui ne marche pas.**

- **Convention de pixel** (centres au lieu de coins, port Python) : Cheviré 24,5 → 13,0 %, Millau 34,8 → 14,3 %,
  mais Normandie 35,1 → 50,6 %. Le biais dépend du sens de parcours ; seule, elle ne corrige rien.
  Hors de ce correctif.
- **z16 seul** (port Python) : corrige Cheviré et Millau, pas Normandie (86 % de couverture).
- **Médiane plus large, P80, `maxSlopeP95` 0,15** : aucun ne rattrape les trois (balayage E2 bis).
- **Sous-ensemble lisse** (P95 sur les seules stations propres) : jette les stations raides sans
  rien mesurer à travers. Sur Haudaudine il accepte le span avec ses 240 m au-delà de la culée :
  stations Strava hors vérité 8 → 56 (port Python).
- **MNT > MNS seul** : nécessaire pour Normandie, mais seul il perd un span (`p06` sud) : 10/15, 20
  % (banc Kotlin, ligne `slopeOnCleanDeck false`).

**Correctif.** Dans `DeckDetector`, deux options nouvelles, actives par défaut :

- `slopeOnCleanDeck` : une station est **propre** quand le max de la rugosité sur la fenêtre
  `roughnessWindowM` centrée sur elle est `< maxRoughnessM` (seuil existant, aucun nouveau ; un
  `NaN` dans la fenêtre = non propre). Sur un span long, le MNS est **ponté** : un trou (les
  stations non propres entre deux stations propres consécutives du span) est remplacé par
  l'interpolation linéaire entre ces deux stations propres, **seulement s'il est haut de bout en
  bout** : aucune de ses stations n'a un MNH (effectif, `mntAboveMnsAsTall` appliqué) `≤ tallMnhM`
  ; un MNH inconnu (`NaN`) ne s'y oppose pas. Un trou qui touche le sol (une clairière en lisière,
  une route entre deux ouvrages) reste brut. Deux suites hautes distinctes étant toujours séparées
  par une station basse, le pontage reste donc confiné à l'intérieur d'une suite haute (ou à
  travers du sans-donnée). Les stations non propres de tête et de queue restent brutes (une queue
  rugueuse est mesurée : Haudaudine reste `STEEP`). La médiane glissante et la pente centrée
  s'appliquent ensuite comme avant. Repli sur le profil brut
  quand le span a moins de `minCleanStationsCount` (5) stations propres, ou moins de
  `minCleanFraction` (0,25) de stations propres parmi ses stations intérieures. Les spans courts ne
  changent pas.
- `mntAboveMnsAsTall` : `MNT > MNS + mntAboveMnsToleranceM` (0,5 m) est physiquement impossible ; le
  MNH y est pris pour haut (`+∞`) dans les suites hautes, l'extension, le masque de tablier, la
  rugosité médiane du span (`roughnessM`, verdict des spans courts) et la règle du trou haut
  ci-dessus ; **jamais** dans le test de canyon ni dans la médiane glissante du MNS (`mf` dans le
  KDoc de `DeckDetector`).

Le pontage garde la marche à travers chaque trou, et c'est elle qui porte la vraie pente (port
Python, non commité ; pente réelle : *Inputs §4* pour Cheviré, profils publiés pour les deux
autres) :

| trou | longueur | dénivelé | pente à travers | pente réelle |
|---|---|---|---|---|
| Cheviré | 77 stations | 23,5 m | 6,1 % | 6,3 % |
| Millau | 99 stations | 15,2 m | 3,07 % | 3,03 % |
| Normandie sud | 82 stations | 24,9 m | 6,1 % | ≈ 6 % |

`DeckMetrics` porte désormais `rawSlopeP95` (la statistique d'analyze.py, toujours calculée),
`cleanFraction` et `slopeOnCleanDeck` (le profil ponté a-t-il été lu). `DeckDetectorOptions.PYTHON`
= DEFAULT sans les deux options : c'est le DEFAULT de E2 bis, et la parité exacte avec les
échantillons de Python s'y vérifie.

**Mesures.** Banc Kotlin, variante (a), `INTEGRATION=1 ./gradlew :engine:jvmTest --tests
'*ElevationBenchProbeTest*' --rerun-tasks -i`. Les lignes en italique viennent du port Python non
commité et ne sont pas reproductibles depuis le dépôt ; aucun test ne compare ce port au banc
Kotlin (le test de parité non conditionné compare `analyze.py` à `PYTHON`), et le port n'a pas la
règle du trou haut.

| | couverture `D` | spans pos. | FP nég. / G·O·L | Beaujoire | Strava | hors vérité Strava (span / masque) | canyon coupé : FP / G·O·L nég. |
|---|---|---|---|---|---|---|---|
| `PYTHON` (DEFAULT E2 bis) | 400 / 1 692 (24 %) | 11/15 | 0 / 0 | oui | 16/21 | 8 / 8 | 2 / 10 (`n04`, `n09`) |
| **DEFAULT E2 ter** | **1 594 / 1 692 (94 %)** | **13/15** | 0 / 0 | oui | 16/21, mêmes ponts | 8 / 8 | 2 / 10 (inchangé) |
| pontage seul (`mntAboveMnsAsTall false`) | 89 % | 13/15 | 0 / 0 | oui | 16/21 | 8 / 8 | — |
| MNT > MNS seul (`slopeOnCleanDeck false`) | 20 % | **10/15** | 0 / 0 | oui | 16/21 | 8 / 8 | — |
| *sous-ensemble lisse (port Python)* | 94 % | 13/15 | 0 / 0 | oui | 17/21 | **56** | — |
| *pontage, seuil 0,5, sans plancher (port Python, sans la règle du trou haut)* | 94 % | 13/15 | 0 / 0 | oui | 16/21 | 8 | — (note ¹) |

¹ Dans cette variante, la voûte de `n05` (fraction propre 0,13) passe à 4,6 % pontée : 114 stations
`G` dans un span accepté **une fois le test de canyon coupé** ; avec le canyon, elle reste
`NO_CANYON` (0 / 0 dans la colonne FP / G·O·L). Non remesuré avec la règle du trou haut.

Par témoin positif, `PYTHON` → DEFAULT (P95 lu par le verdict ; « brut » = `rawSlopeP95`) :

| témoin | statut | spans | couverture `D` | P95 % avant → après | brut % | fraction propre |
|---|---|---|---|---|---|---|
| `p01` Cheviré | réglage | 0/1 → 1/1 | 0 → 100 % | 24,5 `STEEP` → **9,7** | 24,5 | 0,64 |
| `p02` Thouaré | vu, non réglant | 2/3 → 2/3 | 94 → 94 % | 8,0 → 6,2 | 8,0 | 0,70 |
| `p03` Ancenis | vu, non réglant | 1/1 → 1/1 | 93 → 93 % | 1,7 → 1,7 | 1,7 | 1,00 |
| `p04` Langeais | vu, non réglant | 0/1 → 0/1 | 0 → 0 % | `ROUGH` (jamais mesuré) | — | — |
| `p05` Saumur | vu, non réglant | 3/3 → 3/3 | 100 → 100 % | 0,3 ; 2,3 (inchangés) | idem | 1,00 |
| `p06` Normandie | réglage | 3/3 → 3/3 | 18 → **99 %** | 35,1 `STEEP` → **6,5** (un seul span 1,22–3,35 km) | 53,5 | 0,71 |
| `p07` Millau | réglage | 1/2 → 2/2 | 1 → **100 %** | 34,8 `STEEP` → **4,2** | 34,8 | 0,60 |
| `p08` passage sup. | réglage | 1/1 → 1/1 | 100 → 100 % | 8,4 → 8,4 (repli brut : 2 stations propres) | 8,4 | 0,17 |

Ailleurs :

- **Strava** : mêmes 16 ponts, 0 FP, 8 / 8 stations hors vérité. Saint-Nazaire 6,1 % (fraction
  propre 0,94, rien à ponter). Haudaudine reste `STEEP` à 52,6 % (fraction propre 0,49) : sa queue
  rugueuse est mesurée brute, et ses trous intérieurs touchent le sol (19,1 % sans la règle du trou
  haut).
- **Négatifs** : 0 FP et 0 station `G`/`O`/`L` (span et masque), Beaujoire trouvé. Les P95 des
  candidats qui bougent restent au-dessus du seuil et sont de toute façon `NO_CANYON` : `n05` 13,8 →
  12,1 % (0,58) et 43,0 → 13,6 % (0,81). `n11` (0,35) reste à 39,2 %, son brut : ses trous
  touchent le sol (21,2 % sans la règle du trou haut). `n06` (0,05) et `n08` (0,15) sont jugés
  bruts (repli).
- **Canyon coupé** (diagnostic) : 2 FP, 0/10/0 stations `G`/`O`/`L` sur les négatifs, exactement
  comme `PYTHON` canyon coupé (les deux spans courts sous ouvrage de `n04` et `n09`, jugés sur la
  rugosité, que E2 ter ne touche pas).
- **Parité sur les échantillons de Python** (test non conditionné) : à `PYTHON`, parité exacte (21
  ponts, 9 autres, groupes `ROUGH`). À DEFAULT, mêmes 21 spans acceptés aux mêmes bornes et mêmes 9
  verdicts de rejet ; un seul P95 change, sur `test2` : 125..202 8,6 → 4,4 % (0,41). 285..309 reste
  à 8,1 % (sans la règle du trou haut : 2,4 %).
- **Coût** : aucune tuile de plus (le même MNS z15 que E2) ; le détecteur reste à 0,3–3,7 ms par
  itinéraire (`test4`, 111 km : 3,7 ms contre 2,8 ms pour `PYTHON` dans la dernière passe, 1,9
  contre 2,5 ms dans la précédente : bruit de mesure).

**Balayage depuis le nouveau DEFAULT.** Une variation à la fois. « Hors vérité Strava » compte les
stations des spans. Les lignes `PYTHON` et `minCanyonDropM −1` sont des diagnostics.

| réglage | ponts /21 | récupérés / perdus (verdict) | rappel témoins pos. (couv. `D`) | FP nég. | hors vérité nég. G/O/L | ponts propres nég. /2 | FP Strava / hors vérité |
|---|---|---|---|---|---|---|---|
| DEFAULT | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| *`PYTHON` (DEFAULT E2 bis)* | 16 | — / — | 11/15 (24 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| slopeOnCleanDeck false | 16 | — / — | 10/15 (20 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| mntAboveMnsAsTall false | 16 | — / — | 13/15 (73 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| minCleanFraction 0 / 0,4 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| minCleanStationsCount 3 / 10 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| maxRoughnessM 0,5 (seuil propre, **et** filtre des suites) | 17 | Haudaudine / — | 12/15 (86 %, perd `p02` D 37) | 0 | 0/0/0 | 1 | 0 / 9 |
| tallMnhM 4,0 | 13 | — / way 362043805, Willy-Brandt, Trois-Continents (`STEEP`) | 14/15 (95 %) | 0 | 0/0/0 | 1 | 0 / 6 |
| tallMnhM 3,0 | 14 | — / Willy-Brandt, Trois-Continents (`STEEP`) | 14/15 (95 %) | 0 | 0/0/0 | 1 | 0 / **98** |
| tallMnhM 2,5 | 15 | Haudaudine / Willy-Brandt, Trois-Continents (`STEEP`) | 14/15 (95 %) | 0 | 0/0/0 | 1 | 0 / **99** |
| minTallRunM 10 | 16 | — / — | 14/15 (95 %) | 0 | 0/0/0 | 1 | **1** / 10 |
| minTallRunM 5 | 15 | — / Trois-Continents (`STEEP`) | 14/15 (95 %) | 0 | 0/0/0 | 1 | **1** / 10 |
| maxRoughnessM 1,0 | 16 | Bellevue / Pirmil (`STEEP`) | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| maxRoughnessM 1,2 | 16 | Bellevue / Pirmil (`STEEP`) | 13/15 (79 %, perd `p01`) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| maxRoughnessM 1,5 | 16 | Bellevue / Pirmil (`STEEP`) | 12/15 (53 %, perd `p01`, `p06`) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| minSmoothFraction 0,5 / 0,4 | 17 | Bellevue / — | 14/15 (99 %) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 9 |
| maxShortRoughnessM 0,6 / 0,8 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| roughnessWindowM 25 | 16 | — / — | 13/15 (79 %, perd `p01`) | 0 | 0/0/0 | **0** (Beaujoire `STEEP`) | 0 / 8 |
| roughnessWindowM 45 | 17 | Haudaudine / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| surfaceMedianWindowM 15 | 14 | — / Pirmil, way 410671587 (`STEEP`) | 12/15 (53 %) | 0 | 0/0/0 | 1 | 0 / 7 |
| surfaceMedianWindowM 35 | **18** | Bellevue, Haudaudine / — | 13/15 (**88 %**) | 0 | 0/0/0 | 1 | 0 / 10 |
| mergeGapM 200 / 100 / 50 | 17 | Haudaudine / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| maxSlopeP95 0,12 / 0,15 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| minCanyonDropM 2,0 / 1,0 / 0,3 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| *minCanyonDropM −1, canyon coupé* | 16 | — / — | 13/15 (94 %) | **2** | 0/**10**/0 | 1 | **3** / 28 |
| *`PYTHON`, canyon coupé* | 16 | — / — | 11/15 (24 %) | **2** | 0/**10**/0 | 1 | **3** / 28 |
| deckMnhM 0,5 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 9 |
| deckMnhM 2,0 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 2 |
| minLengthM 20 | 16 | — / — | 13/15 (94 %) | 0 | 0/0/0 | 1 | 0 / 8 |
| *stress* (comme en E2 bis) | 16 | Bellevue / Willy-Brandt (`STEEP`) | 14/15 (79 %, perd `p01`) | 0 | 0/0/0 | 1 | **2** / 15 |

Constats du balayage :

- **Les options E2 ter sont plates autour de DEFAULT** : `minCleanFraction` 0 à 0,4 et
  `minCleanStationsCount` 3 à 10 ne changent rien sur ce banc. Le plancher de fraction ne servait
  qu'avec un seuil de propreté plus strict (0,5 m : peu de stations propres, pontage plus étendu ;
  voûte de `n05`, port Python, sans la règle du trou haut).
- **`mergeGapM` 200 aide toujours** : Haudaudine récupéré (17/21), hors vérité Strava +1, témoins
  inchangés (94 %), négatifs propres, Beaujoire gardé. En E2 bis il remontait aussi la couverture
  des positifs (24 → 29 %) ; plus maintenant, le pontage l'a déjà fait.
- **`surfaceMedianWindowM` 35 aide sur Strava mais coûte sur les témoins** : 18/21 (Bellevue,
  Haudaudine), hors vérité Strava 10, mais couverture `D` 94 → 88 %. En E2 bis il ne coûtait rien
  sur les positifs (24 % → 24 %).
- **`maxRoughnessM` 1,2 et `roughnessWindowM` 25** : le span de la Beaujoire y fusionne avec un
  passage supérieur voisin (1,75–2,18 km, 435 m). Sans la règle du trou haut, il passait à 8,5 %
  ponté (13,5 % brut, fraction propre 0,87) et mettait 39 stations `G`/`O`/`L` de `n04` dans un
  span accepté : la limite connue « un toit dans l'écart de fusion emprunte le canyon d'un pont »,
  au DEFAULT des autres réglages. Avec la règle, le trou entre les deux ouvrages touche le sol et
  reste brut : 0 station hors vérité, mais le span est `STEEP` et la Beaujoire perdue, comme sous
  `PYTHON`.
- **Relâcher les suites hautes coûte encore à 3 m et moins** : hors vérité Strava 98 et 99
  stations pour `tallMnhM` 3,0 et 2,5 (6 à 37 en E2 bis) ; 6 à 4,0 m, 10 pour `minTallRunM` 10 / 5.
  Abaisser `tallMnhM` rend « hauts » plus de trous, donc plus de pontage : la règle du trou haut
  suit le seuil qu'on relâche. Sans elle, 68 (4,0 m) et 72 (`minTallRunM`).

**Statut des témoins.**

- `p01`, `p06`, `p07` sont devenus des **cas de réglage** : leur rappel et leur couverture ne
  comptent plus comme généralisation.
- `p08` aussi : il a fixé le repli (`minCleanStationsCount`, 2 stations propres ; sans repli et avec
  un plancher, il serait perdu).
- `p02` à `p05` sont **vus, non réglants** : aucun choix n'a dépendu d'eux (même verdict dans toutes
  les variantes), mais ils étaient dans le balayage. Ils ne testent pas non plus le nouvel
  estimateur : `p03` et `p05` n'ont aucune station rugueuse, `p04` est `ROUGH` avant le test de
  pente, `p02` est accepté dans les deux cas.
- Les négatifs `n01..n12` restent des **critères d'acceptation**. `n05` a fixé le plancher de
  couverture (`minCleanFraction`).
- **Conséquence : il ne reste aucun témoin positif tenu à l'écart pour le test de pente.** Voir
  l'étape *Témoins E2 ter*.

**Limites.**

- **Marge de Cheviré : 9,7 %** pour une pente réelle de 6,3 % (*Inputs §4*). Le reste est un creux long et
  **lisse** laissé par le mélange de bord (le plateau de s 1095–1300), que le masque propre ne voit
  pas. Tout resserrement de `maxSlopeP95` ou du masque propre le reperd.
- Le profil E3 de Cheviré reste faux jusqu'à 15 m (le même creux lisse).
- Le pontage ne voit pas une marche cachée entre deux stations propres sur un toit entièrement
  lisse.
- **Un trou ponté n'a pas de longueur maximale** : la pente pontée ne lit que les deux stations
  propres qui l'encadrent. Les ponts acceptés pontent des trous uniques de 385 m (Cheviré), 410 m
  (Normandie) et 495 m (Millau), plus que `mergeGapM` (300 m) — mesures du port Python, non
  commité. Seule la règle du trou haut rattache un trou à un ouvrage : tout trou resté au-dessus
  de `tallMnhM` est aplati. Sans elle, une route de ravin boisé (pente jusqu'à 25 %, creux du MNT
  de 20 m, donc canyon) entre deux ouvrages plats de 8 m à moins de 300 m passait, avec la canopée
  dans le masque de tablier (cas épinglé dans `DeckDetectorTest`, avec ses clairières de lisière).
  Une canopée haute de bout en bout ne forme pas de suite distincte, donc pas de span fusionné ;
  le cas restant est un trou de sans-donnée entre deux suites. Aucun témoin négatif de cette forme
  n'existe sur le banc (voir *Témoins E2 ter*).

**Pour E3.**

- Le profil du tablier = le profil ponté (même masque, mêmes interpolations).
- Variante (a16), MNS z16 lu seulement dans les `surfaceWindows` des candidats z15 : **non mesurée**
  (étape optionnelle de E2 ter, reportée). Attendu : marge de Cheviré vers 6,5 %, régression de
  Normandie à mesurer ; coût ≈ 2× tuiles par span candidat, à ≤ 20 req/s, en cache dans
  `build/dem-cache`.
- Corriger la convention de pixel reste un sujet séparé.

#### Témoins E2 ter (à faire avant tout nouveau réglage)

Choisir 3 à 5 nouveaux sites `p09..`, figés dans `controls.py` **avant** de lancer le détecteur,
couvrant : une trace à une seule chaussée près du bord d'un tablier large, un pont haubané ou
suspendu, et un long viaduc à terre-plein central. Couverture LiDAR HD vérifiée par la sonde 8 × 8
px. Candidats à vérifier : Brotonne, Tancarville, Aquitaine, Térénez, Île de Ré. Un échec y est
**rapporté, jamais réglé**.

Y ajouter un ou deux **témoins négatifs** `n13..`, figés de la même façon : une route de vallée ou
de ravin boisé (vrai canyon du MNT) avec un ouvrage haut et lisse à chaque bout (passage supérieur,
bâtiment, toit plat), à moins de 300 m l'un de l'autre. C'est la forme de faux positif du pontage ;
aucun négatif actuel ne l'a (`n05` n'a pas de canyon, `n06` et `n11` pas de station propre).

État : **le témoin demandé reste à trouver.** E2 quater n'a trouvé aucun site de cette forme et
ajoute seulement `n13`, un vallon boisé dont la canopée passe le test du canyon mais qui est rejeté
par une pente loin du seuil : il ne couvre que le passage du canyon par une canopée, pas
l'acceptation, et ne départage aucun réglage (voir ci-dessous). Les `p09..` sont gelés en E2
quinquies (*Témoins tenus à l'écart, deuxième série*), jugés une fois après calibration (voir
*Jugement sur p09..p17*).

#### Vallons boisés et seuils combinés (E2 quater)

> **Note (2026-10-13).** Chiffres mesurés à l'ancienne convention des coins (lecture bilinéaire
> décalée d'un demi-pixel au sud-est). Aux centres (`elevation-half-pixel`) : Strava 14/21, 5 / 5
> hors vérité, témoins 13/15 à 73 % (1 240 / 1 692), Normandie `STEEP` (P95 propre 41,1 %, 4 %),
> Cheviré P95 7,4 %, `n13` sans candidat mesuré — voir `elevation-half-pixel.md` § Résultats. Les
> tables ci-dessous restent valables pour la convention qu'elles décrivent : la variante (a⌐) du
> banc les reproduit exactement.
> Le DEFAULT a depuis été recalé aux centres (Strava 18/21, témoins 14/15 à 98 %) : voir
> *Recalage aux centres (E2 quinquies)*.


**Pourquoi.** Deux questions laissées ouvertes par E2 ter : le pontage accepte-t-il une canopée de
vallon quand le canyon passe (témoin demandé dans *Témoins E2 ter*) ; et que donnent ensemble
`mergeGapM` 200 et `surfaceMedianWindowM` 35, les deux variations sans coût du balayage E2 bis,
mesurées cette fois depuis le DEFAULT E2 ter. `DeckDetectorOptions.DEFAULT` **n'a pas changé**.

**Recherche du témoin.** Contrairement à la consigne de *Témoins E2 ter* (site figé avant tout
passage du détecteur), le site a été choisi *parce que* le détecteur y forme un candidat fusionné :
un témoin où rien n'est mesuré ne teste pas la règle. Balayage jetable (scripts du bloc-notes, non
commités, non reproductibles depuis le dépôt) :

- 34 villes au relief boisé (Meudon, Saint-Cloud, Bièvre, Lyon ouest, Caluire, Yzeron, Rouen,
  Mont-Saint-Aignan, Le Havre, Besançon, Brest, Morlaix, Quimper, Lannion, Saint-Brieuc, Dinan,
  Saint-Étienne, Tulle, Saint-Claude, Morez, Chambéry, Grenoble, Nancy, Metz, Épinal, Nice,
  Marseille, Toulon, Angoulême, Périgueux, Limoges, Poitiers, Angers, Cherbourg — ce dernier hors
  couverture) ; les voies OSM des classes routières, cyclables et piétonnes, chaînées par nom,
  6 834 chaînes, ≈ 3 800 km ;
- détecteur DEFAULT sur l'échantillonnage de vcyclist (MNS et MNT z15, comme la variante (a)) ;
  retenu : un candidat mesuré contenant au moins deux suites hautes et une chute de MNT > 3 m.

Résultat : **50 candidats, dont 46 touchent (à 50 m près) une voie OSM `bridge` ou `layer ≥ 1`** :
de vrais tabliers, acceptés ou non. Les 4 autres : un trottoir en épingle à Chambéry (demi-tour,
inutilisable), un tablier réel non tagué à Morez (avenue Georges Lissac, MNT 15 à 20 m sous la
route), une boucle d'échangeur à Rouen, et **un seul vallon boisé** : le chemin de la Vallée de la
Pennélé à Morlaix. La forme « un ouvrage lisse de chaque côté d'un vallon, à moins de 300 m »
n'a été trouvée nulle part sur l'échantillonnage de vcyclist. Deux sites qui la donnaient sur la
grille de `fetch.py` (Tulle, chemin du Pont de la Pierre ; Lyon 9ᵉ, chemin de la Vernique entre
deux passages sous le périphérique nord) ne forment en z15 vcyclist **aucun** candidat mesuré (un
seul groupe `ROUGH` chacun) ; dans les deux, le canyon venait du petit pont propre du ruisseau
(25 m et 10 m). Ils n'ont pas été gardés.

| id | catégorie | lieu | km | G | O | L | X+x |
|---|---|---|---|---|---|---|---|
| n13 | `ravine-merged-tall-runs` | Morlaix, chemin de la Vallée de la Pennélé (une volée de marches de 15 m relie ses deux tronçons) | 0,93 | 187 | 0 | 0 | 0 |

**Ce que `n13` mesure.** À DEFAULT, un candidat km 0,30–0,44 (140 m) : deux suites hautes d'au
moins `minTallRunM` dans le candidat (`tall runs 2` dans la sortie du banc), 24 stations hautes,
toutes `G` ; chute de MNT **3,0 m** (le test est `> 3,0`) ; fraction
propre **0,25** (le plancher `minCleanFraction` : le profil ponté est lu) ; P95 45,6 % → `STEEP`.
Les trous entre stations propres touchent le sol : rien n'est ponté, le P95 ponté égale le brut
(la colonne `onClean true` de la sortie dit seulement que la pente est lue sur le tablier propre).
Avec `surfaceMedianWindowM` 35, seul ou combiné : P95 43,0 %, `STEEP`. Aucune ligne du balayage ne
l'accepte, canyon coupé compris (les 2 FP de cette ligne restent ceux de `n04` et `n09`).

> **Note (2026-10-13).** Aux centres de pixel (`elevation-half-pixel`), `n13` ne mesure plus
> aucun candidat à DEFAULT (seul un `ROUGH` 1 / 0,72 km reste) : il reste négatif mais ne témoigne
> plus de rien, et le banc le range dans `RAVINES_WITHOUT_WITNESS`. Le canyon coupé donne toujours
> 2 FP, désormais `n02` et `n04`.

Le candidat est **fragile** : démarré 120 m plus tôt (stations décalées de 0,4 m), le même chemin ne
donne qu'un groupe `ROUGH` de 0,72 km, rien de mesuré. L'itinéraire commence donc exactement au
début de la chaîne balayée (commentaire dans `controls.py`). Deux marges sont nulles à 0,05 près
(chute 3,0 m, fraction propre 0,25) : `n13` fait passer un candidat de canopée par le canyon et par
le profil ponté, mais il est rejeté par une pente (45 %) loin du seuil. Un vallon de pente douce
sous une canopée lisse, ou deux ouvrages lisses autour d'un vallon, restent sans témoin. Rejeté à
4,5 fois `maxSlopeP95` (0,10) et accepté par aucune des 40 lignes du balayage, `n13` est un témoin
de **non-régression faible** : il ne départage aucun réglage et ne répond pas à la demande de
*Témoins E2 ter*.

**Seuils combinés.** Banc Kotlin, variante (a), depuis le DEFAULT E2 ter ; 13 négatifs (`n13`
compris), 8 positifs. Ligne `mergeGapM 200 + median 35` ajoutée au balayage, et bloc `E2 quater`
dans la sortie `[controls]` (couverture par positif, candidats de `n13`, candidats des positifs qui
perdent de la couverture).

| réglage | Strava /21 | récupérés | FP Strava / hors vérité (span / masque) | spans pos. (couv. `D`) | FP nég. | G/O/L nég. | Beaujoire |
|---|---|---|---|---|---|---|---|
| DEFAULT | 16 | — | 0 / 8 / 8 | 13/15 (94 %) | 0 | 0/0/0 | oui |
| `mergeGapM` 200 | 17 | Haudaudine | 0 / 9 / 9 | 13/15 (94 %) | 0 | 0/0/0 | oui |
| `surfaceMedianWindowM` 35 | 18 | Bellevue, Haudaudine | 0 / 10 / 10 | 13/15 (**88 %**) | 0 | 0/0/0 | oui |
| les deux | 18 | Bellevue, Haudaudine | 0 / 10 / 10 | 13/15 (**88 %**) | 0 | 0/0/0 | oui |

Aucun pont Strava perdu dans ces trois lignes. Par positif (spans, couverture `D`) :

| réglage | p01 | p02 | p03 | p04 | p05 | p06 | p07 | p08 |
|---|---|---|---|---|---|---|---|---|
| DEFAULT | 1/1 100 % | 2/3 94 % | 1/1 93 % | 0/1 0 % | 3/3 100 % | 3/3 99 % | 2/2 100 % | 1/1 100 % |
| `mergeGapM` 200 | = | 2/3 **93 %** | = | = | = | = | = | = |
| `surfaceMedianWindowM` 35 | = | = | = | = | = | 3/3 **76 %** | = | = |
| les deux | = | 2/3 **93 %** | = | = | = | 3/3 **76 %** | = | = |

- `mergeGapM` 200 coupe Thouaré (`p02`) en deux spans (km 0,73–1,12 et 1,38–1,60 au lieu de
  0,73–1,60).
- `surfaceMedianWindowM` 35 coupe le grand pont de Normandie (`p06`) en deux spans acceptés
  (km 1,22–1,68 et 2,19–3,35, plus 3,68–3,86) au lieu d'un seul 1,22–3,35 : ≈ 510 m de tablier
  (km 1,68–2,19) sortent de toute détection. La cause de la coupure n'est pas mesurée.
- Sur Strava, la combinaison ne fait rien de plus que `surfaceMedianWindowM` 35 seul ; sur les
  positifs, elle cumule les deux pertes.

**Ce que valent ces gains.** Bellevue et Haudaudine sont deux des cinq ponts Strava perdus que les
balayages E2 bis et E2 ter regardaient : des cas de réglage, pas de la généralisation. Des témoins
positifs, ne restent intacts du réglage que `p02` à `p05`, soit **5 ponts mesurables** (Thouaré,
D 37, Ancenis, Cessart, Cadets ; la rue des Ponts et la place de la Résistance font 30 m, Langeais
est `ROUGH` partout). Sur ces 5 ponts, aucune des deux variations ne gagne rien (même rappel, même
couverture à un point près) ; `mergeGapM` 200 y perd un point sur Thouaré. Le seul coût net tombe
sur `p06`, cas de réglage d'E2 ter, que `surfaceMedianWindowM` 35 recoupe. Les négatifs, `n13`
compris, ne départagent rien : 0 FP et 0 station `G`/`O`/`L` dans les quatre lignes.

**Recommandation : ne pas adopter la combinaison, ni `surfaceMedianWindowM` 35 seul.** Le gain (+2
ponts Strava) porte sur des ponts qui ont guidé le réglage, coûte 2 stations hors vérité sur Strava
et 23 points de couverture sur Normandie, et ne se voit sur aucun des 5 ponts non réglants.
`mergeGapM` 200 seul est **neutre à la marge** (+1 pont Strava de réglage, +1 station hors vérité,
−1 point sur Thouaré) : rien ne justifie de le changer avant les témoins `p09..` de *Témoins E2
ter*. La valeur 200 n'a rien de particulier : `mergeGapM` 200, 100 et 50 donnent exactement la même
ligne du balayage (17 ponts, Haudaudine, 9 / 9, 13/15 à 94 %). La vraie question est donc « faut-il
fusionner des suites hautes séparées de 200 à 300 m (le DEFAULT) ? », pas le choix d'une valeur.

**Tests.** `deck_controls_parse_and_hold_their_guards` porte `n13` (13 + 8 rôles ; `CONTROL_GUARDS`
`n13` = 187 stations, 0 `O`, 0 `L`, 0 `X+x`). `deckControls` (`INTEGRATION=1`) vérifie le critère
d'acceptation au DEFAULT sur les **13** négatifs : 0 FP, 0 station `G`/`O`/`L` en span comme en
masque, Beaujoire trouvé — vert, `n13` compris ; plus la taille du bloc E2 quater (un témoin de
catégorie `ravine-merged-tall-runs`) et, pour chaque ravin, qu'il existe au DEFAULT un candidat
mesuré au-delà du test du canyon (chute de MNT > `minCanyonDropM`) contenant au moins deux suites
hautes : si le candidat fragile de `n13` disparaît, le banc casse au lieu de passer sur rien. Le balayage passe à 40 réglages. Coût de `n13` : 3 tuiles MNT
z15 et leurs MNS.

#### Témoins tenus à l'écart, deuxième série (E2 quinquies)

**Pourquoi.** Depuis E2 ter, `p01`..`p08` ont tous servi au réglage ou été vus dans un balayage, et
la lecture aux centres de pixel (`elevation-half-pixel`) a déplacé des seuils réglés sur l'ancienne
lecture aux coins. Il faut un jeu de ponts **que personne n'a regardé avec le détecteur**, figé par
une règle écrite avant tout passage, et mesuré par un test séparé qu'aucun réglage ne lit.

**Règle de choix (écrite le 2026-10-13, avant toute requête OSM ou tuile pour ces sites).**

1. *Ouvrage.* Un pont routier dont le cycliste **parcourt le tablier** (voie OSM `bridge=*` de classe
   `primary` … `residential` ou `cycleway`, jamais `motorway` ni `trunk`), qui franchit un cours
   d'eau, une vallée, une route ou une voie ferrée **à l'intérieur des terres** (pas de bras de mer :
   le MNT n'y existe pas).
2. *Strates.* Trois classes de longueur du tablier OSM — court < 60 m, moyen 60–300 m, long > 300 m —
   croisées avec quatre types : poutre (`beam`, caisson, treillis), arc ou voûte, suspendu ou haubané,
   passage supérieur (au-dessus d'une autoroute ou d'un faisceau ferré). Le type est celui du tag
   `bridge:structure` quand il existe, sinon celui documenté pour l'ouvrage. Une cellule sans
   candidat évident reste vide plutôt que remplie par un pont choisi au jugé.
3. *Régions.* Aucun département déjà utilisé par le banc, à l'une ou l'autre extrémité du pont :
   Loire-Atlantique, Maine-et-Loire, Indre-et-Loire, Calvados, Seine-Maritime, Aveyron, Paris,
   Haute-Garonne, Landes, Seine-et-Marne, Bouches-du-Rhône, Finistère, Drôme, Isère. **Un seul site
   positif par département.**
4. *Itinéraire.* Construit par `controls.py` à partir d'OSM seul, avec les mêmes contrôles que
   `p01`..`p08` : pas de demi-tour, pas de retour sur soi, **≥ 150 m d'itinéraire au sol de chaque
   côté** de tout span `D` compté. Le site est le pont et ≈ 400 m de route de chaque côté, au sol ;
   une seule chaussée quand la route est à chaussées séparées.
5. *Couverture.* LiDAR HD vérifiée **sans lire de MNS ni de MNH** : la fraction de `NaN` du MNT z15
   aux stations, lue une fois au gel (≤ 1 %, le seuil « hors couverture » du banc). Aucune autre
   valeur d'altitude n'est regardée avant le juge.
6. *Ordre.* Les candidats sont pris **dans l'ordre de la liste ci-dessous**, cellule par cellule. Un
   candidat est remplacé par le suivant de sa cellule seulement si un contrôle de `controls.py` ou
   la couverture échoue, et la raison est écrite ici. **Jamais** à cause d'un passage du détecteur,
   qui n'a lieu qu'une fois la liste gelée.
7. *Jugement.* Le détecteur ne tourne sur `p09..` que dans `deckHoldoutJudge` (`INTEGRATION=1` **et**
   `DECK_JUDGE=1`). Un échec y est **rapporté, jamais réglé** : un réglage qui suivrait un jugement
   rend la série inutilisable, et il faut alors une troisième série.

Candidats, dans l'ordre (département) :

| cellule | 1ᵉʳ choix | remplaçant |
|---|---|---|
| long, poutre / treillis | Pont de Cubzac, D 1010 sur la Dordogne (33) | — |
| long, arc / voûte | Pont de Pont-Saint-Esprit sur le Rhône (30) | Pont de Pierre, Bordeaux (33), si Cubzac tombe |
| long, suspendu / haubané | Pont de Sully-sur-Loire, D 948 (45) | — |
| moyen, poutre | Pont de Saint-Cloud sur la Seine (92) | — |
| moyen, arc / voûte | Pont Jacques-Gabriel, Blois (41) | — |
| moyen, suspendu | Pont suspendu de Tonnay-Charente (17) | — |
| moyen, haubané | Pont de Bourgogne, Chalon-sur-Saône (71) | — |
| passage supérieur, faisceau ferré | Le Mans (72) : le plus long pont de rue de 25 à 150 m qui croise `railway=rail` près de la gare | — |
| court, passage supérieur | est de Reims (51) : le plus long pont de rue ou piste de 25 à 80 m qui croise `ref=A 4` (règle de `p08`) | — |
| court, rivière | Strasbourg (67) : le plus long pont de rue de 20 à 60 m sur l'Ill dans le centre | — |

Une sélection « le plus long pont qui … » est faite par `controls.py` sur les voies OSM, jamais à
la main. Les cellules « long, passage supérieur » et « court, arc » restent vides : aucun pont long
au-dessus d'une autoroute sur une route ouverte aux vélos ne s'impose, et une voûte courte ne se
désigne pas sans tag fiable.

**Négatifs ajoutés à la même date**, de formes faibles dans le banc ; ils rejoignent le critère
d'acceptation normal de `deckControls`, pas le juge : une rue qui **plonge sous une voie ferrée**
(Dijon, 21 : la plus longue rue nommée qui passe sous un pont ferroviaire près de la gare), une rue
qui passe **sous une autoroute** (Strasbourg, A 35 : la plus longue rue nommée qui passe dessous),
un **quai sous des ponts** (Lyon, bas-port de la rive gauche du Rhône, sous les ponts Wilson et de
la Guillotière), un **vallon boisé** à fond plat (vallée du Cousin, D 427, Avallon, 89). Mêmes
contrôles, rôle `negative`.

**Amendements du même jour, avant tout passage du détecteur.** Construction par `controls.py`
(OSM du 2026-10-13), puis couverture par la fraction de `NaN` du MNT z15 aux stations (plus proche
pixel, grille de `fetch.py` ; aucune autre valeur lue) :

- *Cellules réattribuées par la longueur OSM* (règle 2), liste inchangée : le pont suspendu de
  Tonnay-Charente fait 624 m de tablier OSM (trois voies, dont une `footway` de 73 m au milieu,
  admise dans ses classes : c'est une passerelle cyclable et piétonne) et le Pont de Bourgogne 354 m :
  tous deux passent en « long ». Les cellules « moyen, suspendu » et « moyen, haubané » restent donc
  vides.
- *Passage supérieur sur faisceau ferré : cellule vide.* Le Mans n'a, dans son emprise, aucun pont
  de rue qui croise `railway=rail` (ses ponts franchissent la Sarthe) ; remplacé le jour même, par la
  même règle, par Lille (Lille-Flandres / Lille-Europe), dont le MNT est absent (203/203 stations
  `NaN`), puis par Saint-Denis La Plaine (faisceau de Paris-Nord), sans pont de rue de 25 à 150 m sur
  les voies. Trois échecs de règle, pas de quatrième essai.
- *Rue qui plonge sous une voie ferrée : non construite.* Dijon : MNT absent (205/205 `NaN`) ;
  Reims, son remplaçant par la même règle : échec du contrôle de demi-tour (131° sur 2 × 15 m).
- *Corrections de transcription de la règle*, sans changement de choix : l'Ill est nommée
  `L'Ill` dans OSM (l'expression régulière `^Ill$` ne trouvait rien) ; l'A 35 urbaine de Strasbourg
  est cartographiée `M 35` depuis son transfert à l'Eurométropole ; l'emprise de la recherche sous
  l'A 35 a été élargie à l'est (le premier passage trouvé était au bord de l'emprise, sans 400 m
  d'approche).
- *Reconnaissance OSM à la main, non journalisée jusqu'ici.* Avant d'écrire les sites dans
  `controls.py`, des requêtes Overpass à la main (scripts jetables `recon.py`, `recon2.py`,
  `recon3.py` du bloc-notes, 13 h 53 – 14 h 01) ont listé les ponts OSM autour des sept ouvrages
  nommés (Cubzac, Pont-Saint-Esprit, Sully, Saint-Cloud, Blois, Tonnay-Charente, Chalon) et des
  négatifs de Lyon et du Cousin : existence, voies du tablier, longueurs, tags. Elles ont servi à
  coder les voies du tablier et les points de passage, pas à choisir : aucun candidat n'a été
  écarté sur leur vue, et les sélections « le plus long pont qui … » sont restées à `controls.py`.
  La phrase « jamais à la main » ci-dessus vaut donc pour la *sélection*, pas pour la
  reconnaissance. **Ordre** : la règle et la liste des candidats ont été écrites à 13 h 53 min 10 s
  (horodatage de la session), la première requête Overpass est partie à 13 h 53 min 31 s, et le texte
  de la règle, des candidats et des négatifs prévus est resté inchangé depuis (comparé au texte de la
  session). Cet ordre ne repose que sur le journal de la session, les horodatages du cache de tuiles
  et les journaux de test, **pas sur un commit**. Pour une troisième série : committer la règle et la
  liste gelée seules, avant tout recalage et tout passage du juge.
- *Exception à la règle 1, nommée comme telle.* Le tablier compté de `p12` inclut la voie 121636940,
  `highway=footway` (73 m au milieu de la passerelle), hors des classes de la règle 1. Elle a été
  admise avant tout passage du détecteur ; c'est une exception à la règle écrite, pas une lecture
  de celle-ci. Elle n'est pas défaite après le jugement.
- Les approches de 400 m prennent, de chaque côté, le nœud le plus « droit » (rapport distance à
  vol d'oiseau / distance routière), puis le plus éloigné de l'autre culée ; sur la même `ref` ou le
  même nom que le tablier quand c'est possible, sinon sur toute route des classes du site.

**Liste gelée (2026-10-13, OSM du jour, aucune altitude lue hormis le `NaN` du MNT : 0 % partout).**
Rôle `holdout2` dans `index.csv`, mesurés **seulement** par `deckHoldoutJudge`.

| id | ouvrage (dépt.) | cellule | voies OSM du tablier | km | spans `D` (m) | `X+x` |
|---|---|---|---|---|---|---|
| p09 | Pont de Cubzac, D 1010, Dordogne (33) | long, treillis | 37829396, 1202440422, 1202440423 | 1,87 | 1 055 | 14 |
| p10 | Pont du Saint-Esprit, D 994, Rhône (30 / 84) | long, voûtes | 34977420, 615699262 | 1,76 | 785 | 14 |
| p11 | Pont de Sully-sur-Loire, D 948 (45) | long, suspendu | 174751939, 1374227449 | 1,29 | 400 | 14 |
| p12 | Pont suspendu de Tonnay-Charente, passerelle (17) | long, suspendu | 121636940, 121636941, 121636950 | 1,66 | 625 ; 35 (rue de Lattre-de-Tassigny, 121688592) | 38 |
| p13 | Pont de Bourgogne, D 5A, Chalon-sur-Saône (71) | long, haubané | 157176480 | 1,21 | 355 | 46 |
| p14 | Pont de Saint-Cloud, D 907, une chaussée (92) | moyen, poutre | 22063856 | 1,26 | 165 | 69 |
| p15 | Pont Jacques-Gabriel, D 956B, Blois (41) | moyen, voûtes | 19727454, 1044179665, 1063548233 | 1,11 | 285 | 14 |
| p16 | Reims, chemin agricole sans nom (`highway=unclassified`, prolongé de `track`) au-dessus de l'A 4 (51) | court, passage supérieur | 396421656 | 0,96 | 60 | 14 |
| p17 | Strasbourg, Pont Royal sur l'Ill (67) | court, rivière | 26306542 | 0,93 | 55 ; 40 (Pont Saint-Étienne, 14879071) | 28 |

Soit 9 sites, 11 spans `D` comptés (≥ 20 m), 772 stations `D`. Les `X+x` de `p13`, `p14`
sont des passages inférieurs (rues et tram sous les rampes d'accès) et leurs marges, exclus.

Réserves sur la liste gelée, relevées après le jugement et **sans re-choix** (la série est jugée) :

- *Règle 4 plus étroite dans le code que dans le texte.* `controls.py` (`END_MARGIN_M`) et la garde
  Kotlin (`deck_controls_parse_and_hold_their_guards`) ne mesurent que la distance de chaque span
  `D` aux **extrémités de l'itinéraire**, et l'approche n'exige 150 m sans pont qu'avant chaque
  extrémité. Rien ne vérifie le sol **entre** deux spans `D`, ni que l'approche est au sol.
  - `p17` enfreint la règle telle qu'écrite : le Pont Saint-Étienne (`D` s 295–330) finit 145 m
    avant le début du Pont Royal (`D` s 475–525), avec 70 m de `G` entre eux (s 370–435). Et le
    Pont Saint-Étienne franchit le **Fossé du Faux-Rempart** (canal), pas l'Ill. Le juge n'a donné
    aucun candidat sur l'un ni l'autre : l'écart de 145 m est un facteur confondant à garder en
    mémoire, pas une explication établie.
  - `p12` : l'approche porte un second pont (rue de Lattre-de-Tassigny sur la voie ferrée
    Nantes–Saintes, `D` compté, s 275–305), et le tablier principal (s 510–1130) s'enchaîne
    directement sur la rampe cyclable `layer=1` 285257598 (`X` s 460–505), sans sol ni marge `x`
    entre eux.
  - Pour une troisième série : ajouter un contrôle de sol entre spans `D` à `controls.py` et à la
    garde Kotlin, ou réécrire la règle.
- *Types mélangés dans un span compté.* Sur `p09`, seule la voie principale 37829396 (≈ 575 m) est
  `bridge:structure=truss` ; les viaducs d'accès 1202440422 et 1202440423 (≈ 480 m des 1 055 m
  comptés) sont `bridge:structure=arch`. Sur `p12`, l'essentiel des 625 m est du viaduc d'accès
  autour de la travée suspendue. Leurs résultats au juge ne sont donc pas une mesure pure du type de
  leur cellule.
- *Département partagé avec un négatif.* `n14-strasbourg-under-a35` (négatif, lu par le recalage
  puisqu'il est dans le critère normal de `deckControls`) et `p17` sont tous deux dans le Bas-Rhin
  (67), à ≈ 1 km l'un de l'autre (`n14` finit à lon 7,739, `p17` commence à 7,7526), donc sur des
  tuiles z15 voisines, peut-être communes. La règle 3 ne visait que les sites positifs ; la fuite
  est faible (`n14` ne contraint que les faux positifs), mais elle existe. Pour une troisième série :
  appliquer la règle 3 aux négatifs aussi.

Négatifs ajoutés (rôle `negative`, dans le critère d'acceptation normal de `deckControls`) :

| id | catégorie | lieu | km | G | O | L | X+x |
|---|---|---|---|---|---|---|---|
| n14 | `road-under-motorway` | Strasbourg, rue du Bataillon-de-Marche-24 sous la M 35 (voie 25415527), puis la voie ferrée vers Strasbourg-Ville ; pont propre de la Porte de Saverne (20 m, `X`) | 0,99 | 148 | 33 | 0 | 18 |
| n15 | `quay-under-bridges` | Lyon, Voie verte des berges du Rhône, sous les ponts de la Guillotière, Wilson et Lafayette | 1,51 | 272 | 30 | 0 | 0 |
| n16 | `wooded-valley-road` | Vallée du Cousin, D 427, à l'ouest d'Avallon (89) | 1,66 | 333 | 0 | 0 | 0 |

`n15` finit à 8 m au sud de la Passerelle du Collège (voie 133403571, `footway`, `layer=1`, qui
enjambe le bas-port) : ses 2–3 dernières stations sont étiquetées `G` mais tombent dans la fenêtre
`O` (demi-largeur + `OVERHEAD_SLACK_M`) qu'aurait un passage au milieu de l'itinéraire. Sans effet à
une extrémité, où le détecteur ne peut pas former de candidat ; si `n15` est reconstruit, finir
≈ 20 m plus tôt ou franchir la passerelle pour qu'elle soit marquée `O`.

**Branchement du banc.** `ControlRow.holdout2` ; `deckControls` retire ces lignes de l'index
**avant** tout échantillonnage (assertion sur leur nombre) ; `deckHoldoutJudge` est le seul lecteur,
gardé par `INTEGRATION=1` **et** `DECK_JUDGE=1` ; il imprime, par site, à `DEFAULT`, au DEFAULT
E2 ter (reconstruit : `splitSteepMerged = false`, `cleanGapGroundMnhM = 5`, ajouté avant le passage
noté ; voir *Jugement sur p09..p17*), puis à `PYTHON`,
spans trouvés, couverture `D`, FP, stations `G`/`O`/`L`, verdicts des spans manqués, et n'asserte que
l'intégrité des données. `deck_controls_parse_and_hold_their_guards` (sans réseau) porte les
`CONTROL_GUARDS` des 12 nouveaux sites et la règle des 150 m au sol pour `holdout2` comme pour
`p01..p08`. Le juge n'a **pas** été lancé à la livraison de cette étape.

**Mesures sur les nouveaux négatifs** (`deckControls`, `INTEGRATION=1`, sans `DECK_JUDGE`) : vert.
DEFAULT sur les 16 négatifs : 0 FP, 0 station `G`/`O`/`L` en span comme en masque ; Strava 14/21,
5 / 5 ; témoins `p01..p08` inchangés (13/15, 1 240 / 1 692). Aucun candidat accepté sur `n14..n16` ;
candidats mesurés, tous `NO_CANYON` : `n14` km 0,39–0,79 (passage sous la M 35 fusionné avec le pont
de la Porte de Saverne, chute de MNT 1,9 m, P95 35,1 %), `n15` sous chacun des trois ponts (chute
≤ 0,1 m). Le pont propre de `n14` (20 m) n'est pas trouvé (`NO_CANYON`) : propres trouvés 1/3. `n16`
ne donne que deux groupes `ROUGH` (1,17 km). Canyon coupé (`minCanyonDropM` −1) : 3 FP sur les
négatifs (`n02`, `n04` et `n15` sous le pont Wilson, 25 m), 15 stations `O` : le quai sous un pont
est un négatif que seul le test du canyon tient. Coût : 18 tuiles z15 (4,7 MB).
Ces chiffres sont ceux du DEFAULT E2 ter ; le recalage ci-dessous ne change aucun verdict sur
`n14..n16`.

#### Recalage aux centres (E2 quinquies)

**Pourquoi.** Les seuils de `DeckDetectorOptions.DEFAULT` avaient été réglés sur la lecture aux
coins de pixel. Aux centres (`elevation-half-pixel`, H3), Strava passe de 16/21 à 14/21 (*Pont
Willy-Brandt* et *way 362043805* deviennent `STEEP`, à un P95 de 10,8 et 11,7 %), et la couverture
`D` des témoins `p01..p08` tombe de 94 à 73 %, parce que Normandie (`p06`) devient `STEEP` avec un
P95 propre de 41,1 %. Ce recalage n'utilise **que le jeu de réglage** : les 4 itinéraires Strava, `p01..p08`
et les 16 négatifs `n01..n16`. La série `p09..p17` n'a été ni échantillonnée ni jugée : aucun
passage avec `DECK_JUDGE=1`, et `deckControls` retire toujours ces lignes avant de lire la moindre
tuile.

**Méthode.** Les MNS et MNT z15 du jeu de réglage, aux centres et aux coins, ont été extraits du
banc par un test jetable (retiré, non commité). Un port Python jetable du détecteur, lui aussi non
commité, a servi au diagnostic. Avant tout usage, il redonnait les chiffres Kotlin : Strava 14 et
16, P95 41,1 / 51,8, 11,7 et 10,8 %. Chaque chiffre ci-dessous qui n'est pas marqué « port » vient
du banc Kotlin, reproductible avec `INTEGRATION=1 ./gradlew :engine:jvmTest --tests
'*ElevationBenchProbeTest*' --rerun-tasks -i`.

**Diagnostic : deux mécanismes, aucun seuil en cause.**

- **Willy-Brandt et 362043805 : un voisin fusionné.** Aucun des deux tabliers n'est raide. Lu seul,
  Willy-Brandt (km 9,31–9,51) donne un P95 de 4,4 % aux centres, comme aux coins. Aux centres, un
  objet lisse **hors du pont** devient une suite haute : km 9,16–9,23, à 75 m de la culée, au
  sol (chute de MNT −0,0 m), MNH 6,3 m, rugosité 0,11–0,25. Aux coins, son MNH plafonnait à 4,6 m
  et il ne formait pas de suite. Séparé du pont par 15 stations de sol (MNH 0), il est fusionné
  par `mergeGapM`, et les marches de son MNS (+29 % puis −20 %) font monter le P95 du span fusionné
  à 10,8 %. Même schéma pour 362043805 (km 1,43–1,55, 6,6 % seul) : aux centres, Bellevue
  (km 0,63–1,01, `ROUGH` aux coins) est gardé et fusionné avec lui, à travers l'ouvrage du
  périphérique (km 1,21–1,26, chute 0,2 m, marches à 20 %). Le span km 0,63–1,55 lit alors 11,7 %.
  Le pontage d'E2 ter ne peut rien ici : les stations des marches sont propres, et le trou entre les
  ouvrages touche le sol. C'est la limite connue « un ouvrage dans l'écart de fusion », prise à
  l'envers : le voisin ne passe pas grâce au pont, il fait échouer le pont.
- **Normandie : une seule station veto.** Aux centres, le trou ponté du sud (stations 517–600,
  82 stations, le « Normandie sud » d'E2 ter) n'est plus ponté, à cause d'une seule station : la
  559 (km 2,795), où le MNT vaut 51,2 m sous un MNS de 55,6 m, soit un MNH de 4,38 m. Ses voisines
  ont un MNT **au-dessus** du MNS (79–126 m pour un MNS de 55–79 m), et toutes les autres stations du
  trou sont hautes. La règle du trou haut (aucune station `≤ tallMnhM`, 5 m) refuse alors de
  ponter. Le trou reste brut (pylônes et haubans) : le profil lu garde 64 stations sur 422 au-dessus
  de 10 %, et le P95 passe à 41,1 %. Aux coins, le MNH minimal du même trou était de 5,81 m : la règle passait à 0,81 m
  près. La station 559 se trouve dans la zone où le MNT d'IGN est corrompu sous le tablier : son
  MNH n'est pas un sol.

**Changements.** Deux principes, et aucun seuil d'`analyze.py` déplacé. Les deux options sont
coupées dans `PYTHON`, qui reste inchangé, parité comprise.

1. `cleanGapGroundMnhM` = **1,0 m** (nouvelle option) : un trou n'est plus ponté s'il **touche le
   sol**, c'est-à-dire s'il contient une station de MNH `≤ 1 m`, la valeur de `deckMnhM`. C'est le
   seuil auquel l'extension et le masque de tablier décident déjà qu'on est au sol (la culée). Avant,
   le trou n'était pas ponté s'il n'était pas haut de bout en bout (`≤ tallMnhM`, 5 m). Le pontage
   reste ainsi confiné à une structure continue `MNH > 1 m` : une clairière ou une route entre deux
   ouvrages (MNH ≈ 0) l'interrompt toujours. En revanche, un sous-étage de 1 à 5 m n'arrête plus le
   pontage.
2. `splitSteepMerged` = **vrai** (nouvelle option) : quand un span fait de plusieurs suites gardées
   (fusionnées par `mergeGapM` ou réunies par leurs extensions) est `STEEP`, il est rejugé **par
   parties**. Chaque partie est une suite gardée étendue seule, les parties qui se chevauchent étant
   réunies : ce sont les spans que donnerait un écart de fusion nul. Les parties remplacent le span
   dans le résultat, chacune avec son canyon, sa pente et son verdict. Un span fusionné qui passe
   entier n'est jamais coupé : Saint-Nazaire et Mauves, que la fusion porte à travers leurs trous de
   MNS au-dessus de l'eau, ne changent pas. La fusion sert à porter un tablier à travers ses propres
   trous ; quand l'ensemble n'est pas un tablier, chaque morceau est jugé sur ses propres mérites.

`DeckDetectorTest` épingle les deux règles, avec trois tests ajustés et deux nouveaux. Pour le trou :
une station à 4,4 m de MNH dans un trou ponté ne bloque plus le pontage, une station à 0,5 m le
bloque toujours, et la règle d'E2 ter (5 m) bloque les deux. Pour la partition : un tablier plat et
un bâtiment à deux niveaux sur sol plat, fusionnés, donnent un span `STEEP` sous `PYTHON` ; sous
DEFAULT, le tablier est accepté et le bâtiment rejeté `NO_CANYON`. Le toit de la limite connue, qui
passe entier, n'est pas coupé. Les deux témoins canopée (`woodedRavine`, repli à peu de stations
propres) restent sans détection : leurs parties sont `NO_CANYON`.

**Avant / après** (banc Kotlin ; DEFAULT E2 ter puis DEFAULT recalé) :

| Critère | E2 ter aux coins | E2 ter aux centres | **Recalé aux centres** |
|---|---|---|---|
| Strava, ponts /21 | 16 | 14 | **18** |
| Strava, ponts perdus par rapport aux coins | — | Willy-Brandt, 362043805 | **aucun** (16 ⊂ 18, asserté) |
| Strava, gagnés | — | — | Willy-Brandt, 362043805, Bellevue, Haudaudine |
| Strava, FP | 0 | 0 | 0 |
| Strava, hors vérité (span / masque) | 8 / 8 | 5 / 5 | 7 / 7 |
| Négatifs (16) : FP | 0 | 0 | **0** |
| Négatifs : stations `G`/`O`/`L` (span et masque) | 0 / 0 / 0 | 0 / 0 / 0 | **0 / 0 / 0** |
| Beaujoire (`n04`) | trouvé | trouvé | **trouvé** (km 1,74–1,94) |
| Ponts propres des négatifs trouvés | 1/2 | 1/3 | 1/3 |
| Témoins `p01..p08`, spans | 13/15 | 13/15 | **14/15** (seule la rue des Ponts, 30 m, est manquée) |
| Témoins, couverture `D` | 1 594 / 1 692 (94 %) | 1 240 / 1 692 (73 %) | **1 666 / 1 692 (98 %)** |
| Témoins, `D` dans le masque | — | 1 227 | 1 653 |
| Rien de `PYTHON` perdu | oui | non (Normandie) | **oui** |
| `n13` | candidat `STEEP` 45,6 % | aucun candidat mesuré | aucun candidat mesuré (inchangé) |
| Canyon coupé : FP nég. / `G`/`O`/`L` | 2 / 0/10/0 (13 nég.) | 3 / 0/15/0 | 3 / 0/15/0 |
| Canyon coupé : FP Strava / hors vérité | 3 / 28 | 0 / 5 | 1 / 22 |

Par témoin, couverture `D` à DEFAULT : `p01` 100 %, `p02` 94 %, `p03` 93 %, `p04` 94 %, `p05` 100 %,
`p06` 73 % → **99 %** (Normandie km 1,22–3,35, P95 6,4 % ponté, 51,8 % brut), `p07` 100 %, `p08`
100 %. Seul `p06` bouge. Les 7 stations Strava hors vérité sont toutes des stations de culée, une
par span (Léopold-Sédar-Senghor, Tabarly, Willy-Brandt, Haudaudine, Audibert, way 410671587,
Mauves) ; les deux nouvelles sont celles de Willy-Brandt et de Haudaudine. Aux coins, avec le
DEFAULT recalé (port) : Strava 17/21, 0 FP, témoins 13/15 à 94 %, négatifs 0 / 0 ; Langeais y reste
`ROUGH`, comme avant.

**Marges** (banc Kotlin, DEFAULT recalé) :

- **Seuil de sol du trou.** Sur tout le jeu de réglage, seuls trois trous ont une décision qui
  dépend de la règle (MNH minimal entre 1 et 5 m), et ce sont trois vrais tabliers : Saint-Nazaire
  (3,96 m), Thouaré (3,77 m) et Normandie (4,38 m). Aucun trou d'un négatif n'est dans ce cas, donc
  la règle n'est **pas** éprouvée par un négatif. Normandie passe avec 3,4 m de marge sur le seuil,
  et `cleanGapGroundMnhM` 2,5 donne exactement la même ligne de balayage. À 5 m (la règle d'E2
  ter), Normandie retombe : couverture 82 %, la partition récupérant une partie du pont.
- **Pente.** Le plus fort P95 accepté est celui de Cheviré, 7,4 %, pour un seuil de 10 %. Viennent
  ensuite `p08` à 7,0 %, `test2` way 1287936957 à 6,7 % et 362043805 à 6,6 %. `maxSlopeP95` 0,08
  donne exactement la ligne de DEFAULT. À 0,12, les spans fusionnés passent entiers et les stations
  hors vérité montent à 111 / 34.
- **Parties.** Les parties acceptées et leur marge sur le seuil de pente :

  | Partie | Pont | P95 | chute de MNT |
  |---|---|---|---|
  | km 9,31–9,51 | Willy-Brandt | 4,4 % | 7,4 m |
  | km 1,43–1,55 | 362043805 | 6,6 % | 6,0 m |
  | km 0,83–1,01 | Bellevue | 3,6 % | 6,0 m |
  | km 20,96–21,15 | Haudaudine | 5,7 % | 6,0 m |
  | km 0,63–0,80 | Bellevue | 4,4 % (profil brut, fraction propre 0,22) | **3,9 m** |

  La dernière n'a que 0,9 m de marge sur le canyon. Les parties rejetées le sont toutes par le
  canyon, loin du seuil : le voisin de Willy-Brandt (chute −0,0 m, et un P95 de 9,9 %, sous le seuil
  de pente), l'ouvrage du périphérique de Bellevue (0,2 m) et la queue arborée de Haudaudine
  (0,1 m). C'est donc **le test du canyon seul** qui empêche une partie voisine d'être acceptée : canyon coupé,
  la partition laisse passer un FP Strava de plus et 22 stations hors vérité (5 sous E2 ter).
- **Partition et négatifs.** À DEFAULT, aucun négatif n'a de span fusionné `STEEP` : la partition
  ne s'y déclenche jamais, et n'y est **pas** éprouvée non plus. Ses seuls témoins négatifs sont les
  trois parties voisines ci-dessus, sur les itinéraires Strava.

**Balayage** (45 lignes, dont 5 nouvelles : chacune des deux options défaite, les deux à la fois
(= DEFAULT E2 ter), `cleanGapGroundMnhM` 2,5 et `maxSlopeP95` 0,08). Aucune ligne ne crée de FP
ni de station `G`/`O`/`L` sur les négatifs, sauf le canyon coupé. Les lignes qui changent de sens :

| Réglage | Strava (hors vérité) | Témoins | Remarque |
|---|---|---|---|
| `splitSteepMerged` faux | 14 (5 / 5) | 14/15, 98 % | le gain Strava vient tout entier de la partition |
| `cleanGapGroundMnhM` 5 | 18 (7 / 7) | 14/15, 82 % | le gain sur Normandie vient de la règle de sol |
| `tallMnhM` 4,0 / 3,0 | 18 (7 / 7) | **15/15, 83 %** | rue des Ponts trouvée, Normandie à 38 % (port) |
| `minTallRunM` 10 | 18 (7 / 7) | 15/15, 99 % | — |
| `minTallRunM` 5 / `tallMnhM` 2,5 | 18, **1 FP** (10 / 10 ; 39 / 10) | 15/15 | — |
| `maxRoughnessM` 0,5 | 17 (Bellevue `ROUGH`) | 12/15, 89 % | — |
| `surfaceMedianWindowM` 35 | 18 (**81 / 19**) | 14/15, 98 % | — |
| `mergeGapM` 200 / 100 / 50 | 18 (7 / 7) | 14/15, **82 %** | Normandie coupé : km 1,69–2,79 `STEEP` 41,2 % |
| `maxSlopeP95` 0,12 / 0,15 | 18 (**111 / 34**) | 14/15, 98 % | — |
| `deckMnhM` 0,5 / 2,0 | 18 (18 / 18 ; 3 / 3) | 14/15, 99 / 98 % | — |

Depuis E2 quinquies, la règle du trou haut lit `cleanGapGroundMnhM` et non plus `tallMnhM` : les
lignes `tallMnhM` 4,0 / 3,0 / 2,5 ne déplacent plus que le seuil des suites hautes, alors que dans
les balayages d'E2 ter elles déplaçaient aussi la règle du trou. Les deux tableaux ne se comparent
pas ligne à ligne sur ce réglage.

`minTallRunM` 10 est la seule ligne qui gagne sans rien coûter sur ce banc (+ la rue des Ponts,
+1 point). Elle n'est **pas** adoptée : c'est un seuil de Python, et ce gain d'un span de 30 m
porte sur un témoin déjà vu. Le recalage s'en tient aux deux principes.

**Tests et épingles.** `deckControls` et `deckDetector` sont recalés en égalités sur le nouveau
DEFAULT : Strava 18, ponts de `PYTHON` et des coins inclus, 7 / 7 hors vérité, 0 FP ; parmi les
`LOST_FIVE`, Bellevue et Haudaudine trouvés ; couverture `D` 1 666 / 1 692 ; 14/15 spans, la rue des
Ponts seule manquée ; rien de `PYTHON` perdu. `LOST_AT_CENTRES` est retiré. Le critère des négatifs
(0 FP, 0 `G`/`O`/`L`, Beaujoire) est inchangé et vert. `n13` reste dans
`RAVINES_WITHOUT_WITNESS`. La parité sur les échantillons de Python (`PYTHON` exact, DEFAULT avec
les mêmes 21 spans et les mêmes verdicts) reste verte.

**Ce que vaut ce recalage.** Les quatre ponts Strava gagnés et Normandie sont des **cas de
réglage** : c'est sur eux que le diagnostic a été fait. Les cinq ponts non réglants `p02..p05` ne
bougent pas (même rappel, même couverture), et aucune des deux règles n'est éprouvée par un
négatif. La généralisation reste à mesurer, **une seule fois**, sur `p09..p17` par
`deckHoldoutJudge`, à DEFAULT recalé, sans retouche après lecture (règle 7 d'E2 quinquies).

#### Jugement sur p09..p17 (E2 quinquies, après calibration)

**Passé une seule fois, le 2026-10-13, après la fin du recalage et sans retouche ensuite** (règle 7).
`INTEGRATION=1 DECK_JUDGE=1 ./gradlew :engine:jvmTest --tests '*deckHoldoutJudge*' --rerun-tasks -i`
(`--rerun-tasks` est nécessaire : ni `INTEGRATION` ni `DECK_JUDGE` ne sont des entrées déclarées
de `jvmTest`, et Gradle peut sinon tenir la tâche pour à jour et sauter le juge sans rien dire),
lecture aux centres de pixel, trois jeux d'options dans le même passage : DEFAULT recalé, **DEFAULT E2 ter**
(`10faced`, reconstruit comme dans le balayage : `splitSteepMerged = false`,
`cleanGapGroundMnhM = 5`) pour attribuer le gain, et `PYTHON`. **Cinq** tentatives précédentes ont
échoué sur un HTTP 400 transitoire du WMS IGN pendant l'échantillonnage (15 h 11 – 15 h 13, builds
13 à 17 du démon Gradle), **avant toute sortie du détecteur** ; le cache de tuiles s'est complété de
l'une à l'autre, et seul le sixième passage (15 h 13 min 26 s) a produit des verdicts. Deux des cinq
ont un journal dans le bloc-notes (`judge.log`, `judge2.log`) ; les trois autres n'en ont pas, mais
le journal du démon (`~/.gradle/daemon/9.8.0/daemon-1379818.out.log`, éphémère) garde la sortie de
test des six : les échecs n'impriment que les lignes « stations, NaN MNT … » (de 0 à 6 sites, `p09`
à `p14` au plus) avant l'exception, et aucune ligne d'en-tête ni de verdict
`[judge] DEFAULT` / `E2 ter` / `PYTHON`, que le sixième imprime (trois en-têtes, trois totaux). Les 9 sites sont dans la
couverture (0 `NaN` MNT ni MNS), 11 spans `D`, 772 stations `D`.

| Témoin | Span `D` (OSM) | DEFAULT recalé | DEFAULT E2 ter | `PYTHON` |
|---|---|---|---|---|
| `p09` Cubzac (long, treillis) | Pont de Cubzac, 1 055 m | manqué, `ROUGH` | manqué, `ROUGH` | manqué, `ROUGH` |
| `p10` Pont-Saint-Esprit (long, voûte) | Pont du Saint-Esprit, 785 m | manqué, `STEEP` 10,5 % (brut 12,8, propre 0,46), km 0,45–1,23 | manqué, `STEEP` 14,2 % (brut 14,6, propre 0,49), km 0,45–1,28 | manqué, `STEEP` |
| `p11` Sully (long, suspendu) | 1 span | **trouvé, 95 %**, P95 1,6 % | trouvé, 95 %, 1,6 % | trouvé, 95 % |
| `p12` Tonnay-Charente (suspendu) | Pont suspendu, 625 m | manqué, `ROUGH` | manqué, `ROUGH` | manqué, `ROUGH` |
| | rue de Lattre de Tassigny, 35 m | manqué, `ROUGH` | manqué, `ROUGH` | manqué, `ROUGH` |
| `p13` Chalon, pont de Bourgogne (haubané) | 1 span | **trouvé, 99 %**, P95 1,6 % | trouvé, 99 %, 1,6 % | trouvé, 99 % |
| `p14` Saint-Cloud (moyen, poutre) | Pont de Saint-Cloud, 165 m | **trouvé, 100 %**, P95 1,9 %, km 0,54–0,72 | manqué, `STEEP` 42,7 %, km 0,54–0,79 | manqué, `STEEP` |
| `p15` Blois (moyen, voûte) | 1 span | **trouvé, 98 %**, P95 4,7 % | trouvé, 98 %, 4,7 % | trouvé, 98 % |
| `p16` Reims A 4 (court, passage sup.) | way 396421656, 60 m | manqué, aucun candidat | idem | idem |
| `p17` Strasbourg, Ill (court) | Pont Saint-Étienne, 40 m | manqué, aucun candidat | idem | idem |
| | Pont Royal, 55 m | manqué, aucun candidat | idem | idem |
| **Total** | 11 spans, 772 stations `D` | **4/11, 235 / 772 = 30 %** | 3/11, 202 / 772 = 26 % | 3/11, 202 / 772 = 26 % |

FP 0 et stations `G`/`O`/`L` 0/0/0 (masque compris) pour les trois jeux sur les neuf sites.

Après ce passage, `ElevationBenchProbeTest.kt` n'a été modifié que dans des KDoc (le paragraphe de
`deckHoldoutJudge` rebouclé, à 15 h 14, puis la KDoc de `deckControls`) : vérifié sur le journal de
la session, et `ktlintCheck` et la compilation de `jvmTest` sont verts sur le fichier livré. Le
code du juge est celui qui a tourné. Les
spans `ROUGH` sont rejetés avant toute mesure de pente : le juge n'en imprime pas de P95.

**Attribution.** Le seul gain du recalage est Saint-Cloud, et il vient de `splitSteepMerged` : le
span fusionné km 0,54–0,79 (`STEEP` 42,7 %) est rejugé en deux parties, le tablier km 0,54–0,72
(`DECK`, P95 1,9 %, canyon 9,5 m, chute 7,4 m) et un voisin km 0,77–0,79 rejeté par le canyon
(chute −0,1 m) — exactement la forme diagnostiquée sur Willy-Brandt. Sur Pont-Saint-Esprit, le
recalage détache aussi un morceau d'extrémité (km 1,25–1,28, `NO_CANYON`) et la pente du reste
tombe de 14,2 à 10,5 %, encore au-dessus du seuil de 10 % ; ce cas montre que le seuil de pente
n'est plus loin, et c'est exactement pourquoi il **n'est pas** retouché ici. Rien de ce que
trouvent `PYTHON` ou DEFAULT E2 ter n'est perdu.

**Verdict.** La précision généralise (0 FP, 0 `G`/`O`/`L` sur des sites jamais vus), le rappel non :
4 spans sur 11 et 30 % des stations `D`, contre 14/15 et 98 % sur `p01..p08`. Le chiffre de
réglage était donc optimiste, comme la règle 7 le craignait. Les échecs se rangent en trois formes
que le banc de réglage couvrait mal : les **ponts courts** (≤ 60 m, 3/3 sans candidat, la même
forme que la rue des Ponts), les **grands ouvrages à superstructure** (treillis de Cubzac,
suspendu de Tonnay-Charente, `ROUGH` : le MNS y lit vraisemblablement la structure plutôt que le
tablier, non vérifié ici) et une **voûte longue** à fraction propre faible (Pont-Saint-Esprit,
`STEEP`). L'échantillon est petit : n = 11 spans, sur 9 sites et des strates inégalement remplies
(cinq cellules vides), si bien que 4/11 a un intervalle large (Wilson à 95 % : ≈ 15–65 %). Les trois
formes d'échec sont des **hypothèses** pour une troisième série, pas des taux mesurés. Le recalage apporte +1 span et +4 points, sans régression. **Aucun seuil n'est modifié
à la suite de ce jugement** ; `p09..p17` sont désormais consommés, et tout réglage ultérieur visant
ces trois formes exige une troisième série tenue à l'écart.

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

#### Mesures (E3)

**Livré.** `DeckProfileBuilder` (`:gpx`, `commonMain`, pur) construit le profil de chaque tablier
accepté ; `BridgeDeckStep` (`:engine`, `internal`) l'écrit dans le chemin, en **étape 3b** du
pipeline : après `PointPerDistance(1, 2)`, avant `smoothElevation`, donc `sourceElevation` — et le
D+ rapporté — portent le tablier. Aucun seuil nouveau : tout est lu dans `DeckDetectorOptions`.
Aucune option de porte, aucun champ d'`EnhanceOptions`, aucun `PointField` : l'étape tourne quand
`fixElevation` est vrai et que le fournisseur porte un **modèle de surface**
(`ElevationProvider.surfaceModel`, nouveau membre public). Seul
`ElevationSource.IGN_LIDAR_HD.provider()` à son zoom par défaut (15) en attache un — le MNS, par le
même `fetcherFor`, donc le cache disque de la CLI et du banc le couvre. CLI et JS (et la démo, par
son sélecteur de MNT) ont donc les tabliers avec `ign-lidar-hd` ; WASI construit son fournisseur à
la main, sans surface, et garde les ponts au MNT (décision du 2026-10-13) ; `--dem-zoom 14`,
Mapterhorn et tout fournisseur fait main n'en ont pas. Une erreur de **lecture de tuile** rend le
chemin tel quel (`FETCH_FAILED`), jamais un échec de la sortie ; seules les deux lectures (MNT puis
MNS) sont dans le `try` : détection, construction et écriture n'y sont pas, et un défaut chez elles
lève au lieu de se faire passer pour une tuile manquante (test). `Enhancer` jette le statut :
`FETCH_FAILED` n'est signalé à personne — voir *Ce que E7 doit faire*.

L'algorithme est celui du plan : ancres = stations propres (règle du détecteur, extraite en
`DeckDetector.surfaceState`, sans changement de comportement : `DeckDetectorTest` et la parité
Python restent verts), dans le masque de tablier, MNS fini ; linéaire entre ancres ; médiane 25 m ;
plafond de pente à `maxSlopeP95` (10 %) ; rampes linéaires jusqu'à l'altitude du chemin à `a − 1`
et `b + 1`, profil tenu plat jusqu'à un bout de parcours ; deux spans joints quand moins de
`mergeGapM` les séparent et que tout l'écart est à plus de `minCanyonDropM` sous la droite entre
leurs ancres.

**Écarts au plan.**

1. **Le plafond n'est qu'une dilatation.** Avec un cône, la dilatation donne déjà un profil de pente
   ≤ `maxSlopeP95` ; l'érosion qui la suit et toute l'ouverture sont alors des identités. La
   « fermeture puis ouverture » du plan vaut donc le plus petit majorant de pente 10 %, et c'est ce
   qui est codé (`capSlope`, deux passes). Le plafond **ne fait que relever** : il comble une
   cuvette, et garde un pic propre en le changeant en tente de pente 10 % (test `known limit`). Les
   chiffres du sondage (sommet de Normandie inchangé, erreur de Saint-Nazaire remontée de −0,42 à
   −0,35 m) étaient déjà ceux d'un majorant.
2. `build` rend `DeckProfiles` (remplacements **et** régions écartées, faute de deux ancres), et
   `DeckReplacement` est une classe simple (tableau dedans) avec `spanFirst/spanLast`, les `anchors`
   et `withEnds(e0, e1)` qui pose les épingles et remplit les rampes.
3. `copyAllSlots` n'est ni déplacé ni dupliqué : `Path.copy()` suffit.
4. **Annulation.** `ElevationCalculator` enveloppe toute exception d'un fetcher, annulation comprise,
   dans une `IllegalStateException` : l'étape demande donc au job (`ensureActive`) avant de rendre
   `FETCH_FAILED`, sans quoi une sortie annulée irait au bout.
5. Banc : un fournisseur neuf par colonne et par itinéraire — trois caches de 100 tuiles à la fois
   épuisaient le tas de test sur `test4`.
6. **Ce que garde le `catch`.** Il n'enveloppe que les deux `setElevations` ; le reste passe par
   `replaceDecks`, hors garde. `apply` prend ce dernier en paramètre (`replace`), une couture pour le
   seul test qui vérifie qu'une `IllegalStateException` venue de la détection atteint l'appelant.

**Variantes du profil** (`deckProfiles`, `INTEGRATION=1`, échantillonnage (a) aux stations de la
vérité ; le sondage jetable du plan est reproduit par un test qui se relance). Pente : P95 sur 20 m
comme le détecteur, max **entre deux stations** (5 m), là où le plafond s'exprime — le sondage lisait
une autre fenêtre, d'où des max bruts différents (R1, R4), mêmes classements.

| Variante | Saint-Nazaire `B` rms / max | Nantes `B` rms, pire des 14 autres | Bellevue (joint) `B` rms / max | Mauves `B` rms | Cheviré P95 / max | Normandie max / sommet | Millau max | Thouaré P95 / max |
|---|---|---|---|---|---|---|---|---|
| R1 `mf` brut sur le masque | 0,09 / −0,56 | 0,67 | 1,35 / −5,56 | 0,04 | 13,0 / 73,7 % | 269,4 % / 92,98 m | 87,4 % | 7,5 / 23,7 % |
| R4 ancres + médiane | 0,08 / −0,42 | 0,67 | 0,81 / +2,02 | 0,04 | 7,4 / 34,2 % | 26,9 % / 66,84 m | 26,8 % | 7,3 / 24,9 % |
| R5 ancres + P25 | 0,25 / −1,35 | 0,96 | 0,80 / +1,98 | 0,09 | 7,4 / 35,6 % | 23,5 % / 66,84 m | 26,8 % | 8,4 / 23,7 % |
| **R8 code (plafond 10 %)** | **0,08 / −0,35** | 0,68 | 0,81 / +2,02 | 0,04 | 7,9 / **10,0 %** | **10,0 %** / 66,84 m | **10,0 %** | 5,8 / 10,0 % |

Asserté : pente ≤ `maxSlopeP95` entre deux stations du span des ancres sur chaque région (18
Strava, 11 témoins) ; Saint-Nazaire `B` rms 0,082 m ≤ 0,15 ; Cheviré, Normandie et Millau
plafonnés exactement à 10,0 %. Le plafond n'agit que sur 10 des 29 régions (Bellevue, 362043805,
30807706, 1287936957, Saint-Nazaire, Cheviré, Thouaré, Normandie, Millau, périphérique `p08`) ;
ailleurs R8 = R4. « Deck RMS » (profil − `mf` brut sur les stations propres du tablier) va de 0,00
à 0,40 m (R8) : contrôle de cohérence, **pas une vérité** — les témoins n'ont pas de vérité
d'altitude, et sur les itinéraires Strava la vérité `B` est elle-même le `mf` brut de `analyze.py`,
ce qui donne à R1 un avantage de construction : la vérité ne départage médiane et P25 qu'au-delà de
≈ 0,5 m. Couverture des stations `D` par les spans remplacés : de 93 % (Ancenis, 80 / 86) à 100 %.
Marches aux culées (profil à la première/dernière ancre − MNT à l'épingle) : −1,55 à +2,57 m, la
plupart entre +0,0 et +1,3 m.

**Banc E1** (`bench`, colonnes `ign -decks` = l'`ign-lidar-hd` d'E1, MNS détaché, puis
`ign-lidar-hd` avec tabliers). La colonne `ign -decks` est identique, ligne pour ligne, au banc
lancé sur `8bac315` le même jour (même cache) ; asserté : hors des tronçons remplacés, chaque point
garde son altitude et sa `sourceElevation` au bit près.

| Itinéraire | `B` src rms / max (m) | Toutes stations src rms / max (m) | D+ dem / vrai | D+ rep. / vrai | D+ 1 Hz / vrai | D+ raw / vrai | Durée | > 15 % src / ele |
|---|---|---|---|---|---|---|---|---|
| test1 avant | 36,92 / −65,79 | 21,68 / −65,79 | 53 / 0,74 | 12 / 0,17 | 52 / 0,73 | 125 / 1,01 | 0:17:34 | 18 / 0 |
| test1 **après** | **0,48 / −5,99** | **0,45 / −5,99** | **78 / 1,08** | 59 / 0,82 | 78 / 1,08 | 109 / 0,89 | 0:18:21 | 2 / 0 |
| test2 avant | 8,69 / −11,54 | 3,24 / −11,54 | 136 / 1,52 | 87 / 0,98 | 135 / 1,52 | 225 / 1,26 | 0:44:05 | 48 / 0 |
| test2 **après** | **0,77 / −6,19** | **0,43 / −6,19** | **85 / 0,95** | 44 / 0,49 | 84 / 0,95 | 180 / 1,01 | 0:44:33 | 0 / 0 |
| test3 avant | 7,60 / −8,95 | 1,98 / −8,95 | 35 / 1,94 | 11 / 0,60 | 32 / 1,76 | 109 / 0,50 | 0:17:10 | 0 / 0 |
| test3 **après** | **0,04 / +0,23** | **0,77 / +6,29** | **27 / 1,49** | 10 / 0,57 | 26 / 1,48 | 101 / 0,47 | 0:17:11 | 0 / 0 |
| test4 avant = après | 4,44 / −6,61 | 17,29 / +220,26 | 2 872 / 1,19 | 2 732 / 1,13 | 2 868 / 1,19 | 3 100 / 1,18 | 3:46:39 | 402 / 236 |

Les stations `b` (vérité = MNT sous un trou du MNS, non jugées) : `test1` 0,46 / −1,97 → 0,44 /
−1,48 m, `test3` 0,59 / −3,59 → 1,28 / +1,62 m (le profil ponté passe 1,6 m au-dessus de la vérité
MNT, comme le sondage l'annonçait). Le profil lissé (`ele`) sur toutes les stations : `test1` 21,65
→ 0,51 m rms, max **+9,29 m** ; `test2` 2,88 → 0,53 m, max +5,23 m : le noyau de 150 m étale
désormais le tablier sur les rampes d'accès — c'est la question d'E5.

**Par pont** (m, `src` max / RMS sur toute l'emprise de `structures.csv`, culées et stations `b`
comprises ; avant → après) :

| Pont | Avant | Après |
|---|---|---|
| test1 Pont de Saint-Nazaire (D 213) | −65,8 / 36,1 | **−1,5 / 0,1** |
| test1 way 27013264 | −5,2 / 4,2 | −1,1 / 0,2 |
| test1 Pont écluse sud aval *(non détecté)* | −6,0 / 4,6 | −6,0 / 4,6 |
| test2 Pont de Bellevue (2 spans joints) | −10,5 / 8,5 | +1,9 / 0,8 |
| test2 way 362043805 | −7,3 / 5,9 | −1,3 / 0,7 |
| test2 Pont Léopold-Sédar-Senghor | −10,5 / 8,9 | −0,5 / 0,2 |
| test2 Pont Éric Tabarly | −9,8 / 8,7 | −0,6 / 0,1 |
| test2 Pont Willy-Brandt | −10,5 / 9,2 | −0,4 / 0,1 |
| test2 Pont Georges Clemenceau | −11,5 / 10,4 | −0,1 / 0,0 |
| test2 Pont de Pirmil | −11,4 / 10,1 | −4,8 / 0,7 |
| test2 Pont Aristide Briand | −11,3 / 9,5 | −2,8 / 0,3 |
| test2 Pont Général Audibert | −10,2 / 8,7 | −1,2 / 0,2 |
| test2 way 30807706 | −9,0 / 8,3 | +1,0 / 0,6 |
| test2 Boulevard Victor Schœlcher (Trois Continents) | −10,9 / 8,8 | −1,7 / 0,2 |
| test2 way 1287936957 | −9,0 / 7,2 | −3,0 / 0,6 |
| test2 Pont Haudaudine | −10,1 / 8,4 | −1,3 / 0,2 |
| test2 Pont Général Audibert (2ᵉ) | −7,9 / 6,9 | +0,1 / 0,1 |
| test2 Pont de la Rotonde *(non détecté)* | −6,2 / 5,1 | −6,2 / 5,1 |
| test2 way 410671587 | −7,6 / 6,1 | −2,6 / 0,6 |
| test3 Pont de Mauves-sur-Loire | −8,9 / 6,3 | +1,6 / 0,7 |
| test4 Pont d'Échevis *(non détecté)* | −6,6 / 4,4 | −6,6 / 4,4 |

18 régions remplacées (2 / 15 / 1 / 0), 18 ponts sur 21 touchés, Bellevue en **une** région de deux
spans (asserté), aucune région écartée. Stations de vérité remplacées hors de tout pont (pont OSM
manqué compris) : 2 / 27 / 3 / 0, des culées et rampes. La ligne « missed Pont de Bellevue »
(km 1,22–1,26) bouge (+4,4 → +5,4 m) sans être remplacée : l'itinéraire y repasse à 0,4 m de sa
station de km 1,00, et l'appariement du banc — qui n'est **pas** monotone : sa fenêtre part de
20 stations derrière le curseur, qui peut donc reculer — y envoie des points du tablier. Un
artefact du banc, pas de l'étape ; le même va-et-vient peut toucher aussi les `B` rms / max de
Bellevue lui-même, dans les deux colonnes.

**Cibles de la *Validation*** (asserté, les manquées épinglées dans `TARGETS_MISSED`, rien de réglé) :

| Cible | Mesure | Verdict |
|---|---|---|
| Saint-Nazaire, erreur max sur `B` ≤ 5 m | −0,54 m (rms 0,08), point haut 63,67 m pour 63,68 | ✅ |
| Ponts nantais remplacés, erreur max sur `B` ≤ 2 m | 11 sur 15 ; Pirmil −4,83, Aristide Briand −2,83, way 1287936957 −3,04, way 410671587 −2,57 m | ❌ 4 |
| Pont de la Rotonde ≤ 2 m | −6,19 m, non détecté (E2) | ❌ |
| D+ dem / vrai dans [0,8 ; 1,2], test1 / test2 / test3 | 1,08 / 0,95 / **1,49** | ❌ test3 |
| Cheviré ≤ 5 m | pas de vérité d'altitude sur `p01` | non mesurable |

Les quatre ponts nantais manqués le sont **tous à leur dernière station `B`** (à +1 / −3 / +1 / −1 m
du bout) : la dernière ancre propre précède le bout du tablier, et la rampe vers l'épingle `b + 1`
y descend déjà. Le RMS sur `B` reste 0,30 à 0,70 m. Corriger demanderait de prolonger le profil
jusqu'au bout du span avant la rampe : un choix de structure, à décider sur une autre série que
celle-ci. `test3` : le profil de Mauves est juste (`B` 0,04 m rms) et le D+ dem passe de 1,94 à 1,49 ;
le reste vient du MNT lui-même hors du pont (le MNT LiDAR seul donne 26 m pour 18 vrais, E1,
*Limites*) — affaire d'E5.

**Le D+ rapporté de `test2` tombe à 0,49** (44 m pour 89) alors que le profil dense est à 0,95 :
`PathSimplifier` efface les rampes de pont comme E1 l'avait vu sur `test1` (58 → 12 m). C'est E5
(lissage, preset et simplification), qui n'est pas bloqué : l'étape 3b ne lit ni le noyau ni le
preset.

**Smoke CLI** (`enhance docs/deck-strava-data/test1.gpx --fix-elevation --cache build/dem-cache`) :

| `--dem-source` | `<ele>` max | `<ele>` min | Durée | D+ / D− rapportés |
|---|---|---|---|---|
| `ign-lidar-hd` | **63,37 m** (le point haut à 63,68 m, après noyau 150 m et simplification) | 2,84 m | 1 101 s | 59 / 61 m |
| `ign-lidar-hd --dem-zoom 14` | 6,76 m (pas de tablier) | −2,21 m | 1 053 s | 13 / 14 m |
| `mapterhorn` | 6,54 m | −2,00 m | 1 053 s | 12 / 11 m |

**`sample.gpx`** (`deckSampleGpx`, 130,4 km en Savoie, `EnhanceOptions.DEFAULT`, `ign-lidar-hd` sans
→ avec MNS) : D+ rapporté 4 552,6 → 4 554,6 m (**+0,04 %**), durée 19 618 → 19 617 s (**−0,01 %**),
dans la tolérance de 0,5 %. Neuf tronçons remplacés, deux régions écartées (moins de deux ancres),
tous courts — 25 à 60 m : passages au-dessus de ruisseaux ou de routes, relevés de 0,1 à 6,0 m en
moyenne (km 1,55 ; 2,75 ; 2,83 ; 19,03 ; 71,17 ; 87,81 ; 95,12 ; 100,95 ; 112,52 ; coordonnées
imprimées par le test). D+ dense (grille 5 m, preset `dem`) : 4 684,1 → 4 676,8 m. Aucun n'a été
vérifié sur le terrain.

**Coût.** Une tuile de MNS par tuile de MNT : sur les quatre itinéraires, les 189 tuiles d'E2
(49,5 MB) ; sur `sample.gpx`, 132 tuiles de plus à froid (≈ 35 MB ; 264 tuiles, 69,2 MB pour MNT
et MNS ensemble ce jour-là). La relecture du MNT aux stations, en ordre inverse, ne coûte rien sur
les routes de moins de ≈ 100 tuiles. Banc chaud : 1 273 tuiles lues sur disque, 0 téléchargée.

**Série tenue à l'écart.** `deckHoldoutJudge` a gagné l'impression E3 par région, après que E3 a
été figé ; **passé une seule fois**, le 2026-10-13 à 17 h 38, sans retouche ensuite. Les verdicts
du détecteur sont ceux du jugement d'E2 quinquies (DEFAULT 4/11, 235 / 772 = 30 %, 0 FP, 0
`G`/`O`/`L`). Les quatre régions (Sully, Chalon, Saint-Cloud, Blois) : pente max entre deux
stations 2,1 à 6,9 %, le plafond n'agit nulle part, R8 = R4 partout, « deck RMS » 0,00 à 0,10 m,
marches aux culées +0,03 à +1,55 m, aucune région écartée. Sans vérité d'altitude, ce passage ne
juge que la tenue du profil, pas son exactitude.

**Tests.** `DeckProfileBuilderTest` (12 : canyon plat et rampes, tablier bombé, canopée, trous de
MNS, cuvette comblée par le plafond, pic propre en tente — `known limit`, jonction Bellevue /
Saumur / `mergeGapM`, bouts de parcours, moins de deux ancres, toit plat relevé — `known limit`,
jamais deux tronçons qui se chevauchent — adjacents joints, à une station l'épingle partagée —,
`capSlope`) ; `BridgeDeckStepTest` (9, tuiles IGN synthétiques : dont un défaut de détection qui
lève au lieu de `FETCH_FAILED`, et `write` sur un `Path` avec tablier en début et fin de parcours
— tenu plat au-delà de la dernière station — et deux tabliers à une station l'un de l'autre) ;
`EnhancerBridgeDeckTest` (4 : dont une tuile MNS illisible qui rend, au bit près, la sortie sans
modèle de surface) ;
`ElevationSourceTest` (+4) et `ElevationSourceJavaTest` (+1, `getSurfaceModel()` depuis Java) ;
`EnhanceCommandTest` cas 32 (CLI hors ligne par `elevationProviderFactory`). Les tests de parité
des portes ne bougent pas : aucune clé, aucun défaut, aucun export.

**Ce que E7 doit faire.** (1) Décider d'une option `bridgeDecks` (désactivation) ; si oui, toute la
checklist des six surfaces, défaut dérivé de `provider.surfaceModel != null`, jamais un littéral.
(2) Une ligne propre dans `surface-coverage.md` — cœur / CLI / JS / JVM / démo ✅ (automatique avec
`ign-lidar-hd` z15), WASI ❌ ; une note de bas de page l'annonce déjà, et `wasm-wasi-abi.md`
(§ `vcSetElevationConfig`, « No bridge decks on this door either ») écrit déjà l'écart (pas
d'argument de couche à `fetch_tile`, décision du 2026-10-13). (3) Peut-être exposer
`BridgeDeckResult` (statut, tronçons) à la démo, par un drapeau `PointField` ou un scalaire : c'est
aussi le crochet de provenance dont E5 a besoin. (4) **Signaler `FETCH_FAILED` et `dropped`.**
Aujourd'hui `Enhancer` jette le statut : une seule tuile MNS refusée (HTTP 400 / 429, cache froid)
renvoie *tous* les tabliers du parcours au fond de vallée, sans un mot, et la même commande peut
rendre l'un ou l'autre profil selon le réseau — contraire à la règle de `DiskCachedElevation`
(« Errors stay loud »). Au minimum un avertissement de la CLI ; mieux, un statut sur le résultat
d'`enhance`, lu par chaque porte. `EnhancerBridgeDeckTest` épingle déjà la sortie dans ce cas. (5)
`ElevationJsApi.newElevationProvider` construit son fournisseur à la main, **sans** surface (il ne
sert que l'explorateur d'altitude), alors que la fabrique JVM `newElevationProvider(ElevationSource)`
en attache une : si un fournisseur JS doit un jour entrer dans `enhance`, lui attacher le MNS. **E4** réutilise `alongTrackStations` et la
lecture du MNT aux stations, sans surface, donc aussi sous WASI.

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

#### Témoins tunnels (E4, avant tout détecteur)

> *Note du 2026-10-13, après coup.* Cette section est l'état **au gel** (18 h 06 – 20 h 02) et n'est
> pas réécrite : le détecteur existe depuis (*Mesures (E4)*) et le juge a été lancé une fois (*Jugement
> tunnels*). Le « pas lancé » et le « aucun détecteur n'existe encore » ci-dessous sont historiques.

**Pourquoi.** Un correcteur de tunnel lit le seul MNT, donc il tourne sur **toute** source — Mapterhorn
dans le monde entier compris. Un faux « tunnel » aplatit une vraie montée : sur une route réelle en
lacets, en balcon, sur une rampe urbaine, une crête de barrage ou un col, la précision compte plus que
le rappel. Le banc n'a aujourd'hui que les quatre tunnels de `test4` (Petits Goulets, Grands Goulets,
tunnel des Moines, Combe Laval) et aucun négatif raide. Cette étape construit les témoins — **aucun
détecteur n'existe encore, aucun n'est lancé**.

**Forme.** Mêmes outils qu'en E2 bis : `controls.py` (OSM seul, routage Dijkstra, stations à 5 m),
mais une série à part, `python3 -I controls.py --tunnels`, écrite dans `controls/tunnels/<id>.gpx` et
`analysis/truth/tunnels/` (`index.csv`, `spans.csv`, `<id>.csv`), pour que les lecteurs du banc des
ponts ne changent pas. Sortes de station : `G` sol, `O` ouvrage au-dessus, `L` pont ou viaduc le long
(≤ 20 m), `N` **tunnel propre** du site positif (la vérité y est la droite entre les portails, comme
`N` dans `truth/test4.csv` : MNT à la station juste hors du tunnel de chaque côté, lu au moment de la
mesure), `X` autre ouvrage du site (pont, tunnel d'un négatif, galerie paravalanche, passage
inférieur), `x` sa marge de 35 m (exclues). Rôles : `tunnel-tuning` (réglage), `tunnel-negative`
(négatifs de réglage), `tunnel-holdout` et `tunnel-holdout-negative` (série tenue à l'écart).

**Règle de la série tenue à l'écart (écrite le 2026-10-13 à 18 h 06, avant toute requête OSM, toute
tuile et toute valeur d'altitude pour ces sites ; le jeu de réglage n'est pas encore construit non
plus).**

1. *Ouvrage.* Un tunnel routier OSM `tunnel=yes` (jamais `building_passage`, `culvert`,
   `avalanche_protector`), sur une voie des classes `primary` … `residential`, `unclassified` ou
   `cycleway` (jamais `motorway` ni `trunk`), sans `bicycle=no` ni `access=no|private`. Sa longueur
   est celle de la chaîne des voies tunnel qui se touchent par un bout, entre 50 et 2 500 m.
2. *Strates.* Huit cellules, ci-dessous : court (50–200 m) en gorge ×2, moyen (200–700 m) sous un col,
   moyen courbe, long (700–2 500 m), urbain foré sous du relief, urbain en tranchée couverte (un tunnel
   que le MNT peut **ne pas** montrer en bosse : la ville est plate au-dessus), voie verte en ancien
   tunnel ferroviaire. Une cellule sans candidat qui passe les contrôles reste vide.
3. *Régions.* Aucun département déjà lu par le banc ou par le jeu de réglage, à l'une ou l'autre
   extrémité. **Exclus** : 12,
   13, 14, 17, 21, 26, 29, 30, 31, 33, 37, 38, 40, 41, 44, 45, 49, 51, 59, 67, 69, 71, 72, 75, 76, 77,
   84, 89, 92, 93 (ponts, `sample.gpx` en 73) et 04, 05, 15, 48, 73, 83 (réservés au jeu de réglage de
   cette étape, qui ne peut en sortir). **Un seul site par département sur les deux listes tenues à
   l'écart**, négatifs compris (leçon de `n14` / `p17`) ; un candidat dont le département est déjà
   pris par un site gelé avant lui est sauté, et c'est écrit.
4. *Itinéraire.* Construit par `controls.py` : le tunnel et ≈ 400 m de route de chaque côté, sur la
   même `ref` ou le même nom quand c'est possible ; pas de demi-tour, pas de retour sur soi ; **≥ 150 m
   de sol** (aucun pont, tunnel, `layer`, passage inférieur : ni `N`, ni `X`, ni `x`) au-delà de chaque
   groupe de spans `N` comptés (≥ 20 m), des deux côtés, **et entre deux groupes**. Deux tunnels à
   moins de 150 m l'un de l'autre forment un seul groupe ; le sol qui les sépare est `G`, jugé comme
   sol. Un négatif en lacets (cellule *lacets*) est dispensé des deux contrôles de demi-tour, qui
   sont sa raison d'être, et de rien d'autre.
5. *Couverture.* LiDAR HD vérifiée **sans lire d'altitude** : la fraction de `NaN` du MNT IGN z15 aux
   stations (pixel le plus proche, grille de `fetch.py`), lue une fois au gel, ≤ 1 %. Mapterhorn n'est
   pas lu avant le juge. Aucune autre valeur n'est regardée.
6. *Ordre.* Les candidats sont pris dans l'ordre de la liste, cellule par cellule ; un candidat est
   remplacé par le suivant de sa cellule seulement si une règle (1, 3, 4) ou la couverture (5) échoue,
   et la raison est écrite ici. **Jamais** à cause d'une altitude ou d'un détecteur. La reconnaissance
   OSM (existence, noms, `ref`, géométrie, longueurs) sert à coder les points de passage des négatifs ;
   elle ne choisit rien. Les sélections « le plus long / le plus courbe tunnel qui … » sont faites par
   `controls.py`, jamais à la main.
7. *Jugement.* Ces sites ne sont mesurés que par un test dont le nom contient `TunnelJudge`, gardé
   par `INTEGRATION=1` **et** `TUNNEL_JUDGE=1` ; tout autre test qui échantillonne les témoins tunnels
   retire ces lignes de l'index **avant** toute lecture de tuile (assertion sur leur nombre). Le juge
   lit **les deux sources** (MNT IGN LiDAR HD z15 et Mapterhorn z12). Un échec y est **rapporté,
   jamais réglé** ; un réglage qui suivrait un jugement rend la série inutilisable.
8. *Trace.* Cette règle et la liste gelée doivent être committées **seules**, avant tout réglage d'un
   détecteur de tunnel et avant tout passage du juge (consigne tirée d'E2 quinquies ; la présente
   session ne committe pas : à faire par l'utilisateur).

Candidats tunnels, dans l'ordre (département ; sélection faite par `controls.py`) :

| cellule | 1ᵉʳ choix | remplaçants, dans l'ordre |
|---|---|---|
| T1 court, gorge (Alpes) | Gorges du Cians (06) : le plus long tunnel sur `ref=D 28` entre Touët-sur-Var et Beuil | Gorges de Daluis (06), `ref=D 2202` |
| T2 court, gorge (Pyrénées) | Gorges de Luz (65) : le plus long tunnel sur `ref=D 921` entre Pierrefitte-Nestalas et Luz-Saint-Sauveur | Défilé de Pierre-Lys (11), `ref=D 117` |
| T3 moyen, sous un col | Col de Bussang (88 / 68) : le plus long tunnel sur `ref=N 66` près du col | le plus long tunnel 200–700 m des Pyrénées-Orientales (66) |
| T4 moyen, courbe | le tunnel de 100–700 m au plus faible rapport corde / longueur des Pyrénées-Orientales (66) | même règle : Alpes-Maritimes (06), Haute-Corse (2B) |
| T5 long | le plus long tunnel de 700–2 500 m de l'Ain (01) | même règle : Haute-Savoie (74), Doubs (25) |
| T6 urbain, foré sous du relief | le plus long tunnel de 150–2 500 m de la commune d'Angoulême (16) | Saint-Étienne (42), Besançon (25) |
| T7 urbain, tranchée couverte | le plus long tunnel de 150–2 500 m de la commune de Montpellier (34) | Metz (57), Nancy (54) |
| T8 voie verte, ancien tunnel ferroviaire | le plus long tunnel `highway=cycleway` de 100–2 500 m de l'Ardèche (07) | même règle : Haute-Loire (43), Dordogne (24) |

Négatifs tenus à l'écart, dans l'ordre (une vraie route, entièrement au sol hors `X`, dont le relief
ressemble à un tunnel pour un test de pente) :

| cellule | 1ᵉʳ choix | remplaçants |
|---|---|---|
| H1 lacets | Col d'Aubisque (64), `ref=D 918`, les lacets sous Gourette côté Eaux-Bonnes | Col de Marie-Blanque (64), `ref=D 294` |
| H2 balcon sous falaise | Gorges de l'Aveyron (82), `ref=D 115` entre Saint-Antonin-Noble-Val et Cazals | Gorges de Galamus (11), si 11 est libre |
| H3 rampe urbaine raide | Laon (02), la route de la ville basse à la ville haute (rampe d'Ardon) | Boulogne-sur-Mer (62), de la ville basse à la haute ville |
| H4 crête de barrage | Barrage de Vouglans (39), la route sur la crête | Barrage de Pannecière (58) |
| H5 route en déblai | la plus longue voie des classes taguée `cutting=yes` (≥ 100 m) du Lot (46) | même règle : Corrèze (19) |
| H6 col, vraie bosse | Col de Bavella (2A), `ref=D 268` de part et d'autre du col | Col de Vizzavona (2B), si 2B est libre |

**Amendement 1 (2026-10-13, 19 h 36), avant toute lecture d'altitude sur ces sites.** La première
construction (`controls.py --tunnels`, OSM du jour) n'a gardé que 3 tunnels sur 8 cellules : la
plupart des candidats échouent à la règle 1 (pas de tunnel des classes dans OSM, ou `bicycle=no`) ou à
la règle 4 (un pont ou un passage inférieur à moins de 150 m d'un portail). Faute de quoi juger, des
candidats sont **ajoutés à la fin** des cellules vides, par la même règle (sélection par
`controls.py`, départements libres, aucun département des négatifs tenus à l'écart), et jamais en
amont d'un candidat existant :

| cellule | candidats ajoutés, dans l'ordre |
|---|---|
| T3 moyen | le plus long tunnel de 200–700 m : Haute-Savoie (74), Ain (01), Ariège (09), Puy-de-Dôme (63) |
| T4 moyen courbe | le plus courbe de 100–700 m : Ardèche (07), Ariège (09), Puy-de-Dôme (63) |
| T5 long | le plus long de 700–2 500 m : Ariège (09), Puy-de-Dôme (63), Haute-Corse (2B) |
| T6 urbain foré | le plus long de 150–2 500 m de la commune : Le Puy-en-Velay (43), Clermont-Ferrand (63), Annecy (74) |
| T7 urbain, tranchée couverte | le plus long de 150–2 500 m de la commune : Rennes (35), Amiens (80), Limoges (87), Poitiers (86) |
| T8 voie verte | le plus long tunnel `highway=cycleway` de 100–2 500 m : Tarn (81), Haute-Vienne (87), Creuse (23) |

*Note datée (2026-10-13, 21 h 30, revue d'E4).* La ligne T8 ci-dessus figurait dans le texte de 19 h 36
(instantané `spec-amend1-193617.md`), puis avait été retirée sans note ; elle est rétablie telle
qu'écrite. Elle était **en erreur** : à 19 h 35 (`full.log`), T8 était déjà tenu par
`th08-voie-verte-24` (Dordogne), la cellule n'était donc pas vide ; ces candidats n'ont jamais été
essayés, et le Tarn a été pris ensuite par T9 (`th09-tunnel-81`). La sélection n'en change pas.

**Amendement 2 (2026-10-13, 20 h 02), toujours avant toute lecture d'altitude.** Après l'amendement 1,
4 tunnels seulement (T1, T2, T3, T8). Une cellule de complément **T9**, sans strate : dans l'ordre
Hérault (34), Ardèche (07), Haute-Corse (2B), Haute-Loire (43), Lot (46), Corrèze (19), Tarn (81),
Hautes-Pyrénées (65), le plus long tunnel des classes de 50–2 500 m du département ; chaque
département qui passe donne un site (règle 3 tenue), jusqu'à 4 sites de plus, puis la liste
s'arrête. Les cellules vides restent vides.

**Précisions de construction, sans changement de choix** (toutes avant la première valeur d'altitude
lue sur un site tenu à l'écart ; seule la fraction de `NaN` de la règle 5 a été lue, à la fin) :

- *Règle 3, jeu de réglage.* La phrase « 04, 05, 15, 48, 73, 83, réservés au jeu de réglage, qui ne
  peut en sortir » se lit : le jeu de réglage reste **dans la liste exclue**. Il occupe 04, 05, 15,
  26, 38, 69, 73, 75, 84 (aucun site en 48 ni 83 : les tunnels du Tarn sur la D 907B font ≤ 30 m,
  ceux de la Corniche sublime sont des sentiers).
- *Règle 4, approche.* Le contrôle « l'extrémité ne revient pas vers l'autre bout » hérité des ponts
  (marge 0,5 × longueur du tablier) rejetait toute route qui tourne de plus de ≈ 60° en 400 m ; la
  série tunnels le prend à 0 (`back=0.0`), pour tous ses sites. Les « 150 m de sol » sont vérifiés
  aux deux bouts de l'approche **et** sur les 150 premiers mètres hors du portail (`approach`), puis
  sur l'itinéraire écrit (`tunnel_ground_checks`) ; un site de réglage, `tt08`, en est dispensé
  (`x_near_ok` : un tunnel entre deux galeries paravalanches, qui restent `X`).
- *Règle 6, ordre.* Codé dans `controls.py` : chaque ligne des tableaux est une *cellule* de
  `TUNNEL_SITES` dont les candidats sont essayés dans l'ordre ; le premier qui se construit et passe
  les contrôles est gardé, un candidat dont le département est déjà tenu est sauté. Le journal de la
  construction imprime chaque échec et chaque saut.
- *Points de passage des négatifs* : centres et points codés après reconnaissance OSM (noms, `ref`,
  géométrie) ; `hn02` (centre à 794 m de la D 115) et `hn03` (point de la gare à 127 m d'un nœud de
  rue) ont été recodés ainsi, sans changer de site.

**Échecs et remplacements (OSM du 2026-10-13).**

| cellule | candidat | échec |
|---|---|---|
| T2 | Gorges de Luz, D 921 (65) | règle 1 : aucun tunnel routier dans OSM (seulement des galeries hydrauliques `tunnel=flooded`) |
| T3 | Col de Bussang, N 66 (88 / 68) | règle 1 : le « Tunnel de Bussang » est ferroviaire ; la N 66 passe le col à ciel ouvert |
| T3 | 200–700 m des Pyrénées-Orientales (66) ; Ain (01) | règle 1 : aucun tunnel des classes dans la plage |
| T3 | 200–700 m de Haute-Savoie (74) : route de Sommand, 325 m (voie 137554710) | règle 4 : pas d'approche de 400 m propre aux deux bouts |
| T4 | le plus courbe des Pyrénées-Orientales (66), 118 m (1195520320) ; Haute-Corse, tunnel de l'Isolella, 181 m (27118814) ; Ardèche, route des Gorges, 135 m (174971324) | règle 4 (Isolella : un pont à 268 m du portail) |
| T4 | Alpes-Maritimes (06), Ariège (09) | sautés (règle 3 : 06 tenu par T1, 09 par T3) ; Puy-de-Dôme (63) : règle 1 |
| T5 | Ain (01), Haute-Savoie (74), Doubs (25), Puy-de-Dôme (63), Haute-Corse (2B) | règle 1 : aucun tunnel des classes de 700–2 500 m (autoroutes, LHC, `bicycle=no`) ; Ariège (09) sauté |
| T6 | Angoulême (tunnel de la Gâtine, `bicycle=no`), Besançon, Clermont-Ferrand, Annecy | règle 1 |
| T6 | Saint-Étienne, allée du Général-Rullière, 169 m ; Le Puy-en-Velay, voie verte du Velay, 1 259 m (124759294) | règle 4 |
| T7 | Montpellier, galerie de la Comédie, 389 m (1315709014) | règle 4 : un passage inférieur à 90 m du portail |
| T7 | Metz, Nancy, Rennes, Amiens, Limoges, Poitiers | règle 1 : aucun tunnel des classes de 150–2 500 m dans la commune |
| T8 | Ardèche (07) | règle 1 : les tunnels de la Dolce Via sont `highway=path`, pas `cycleway` |
| T8 | Haute-Loire (43), voie verte du Velay | règle 4 |
| T9 | Hérault (Comédie, règle 4) ; Haute-Loire, Lot (D 662, 262 m), Corrèze (D 1089, 384 m), Hautes-Pyrénées (D 920, 116 m) | règle 4 ; la liste s'épuise avec **3** sites gardés (07, 2B, 81) au lieu de 4 |
| H3 | Laon (02) | règle 5 : MNT IGN `NaN` sur 381 / 381 stations |
| H4 | Vouglans (39) : codé sans sélecteur, l'itinéraire prend des voies `service` | règle 4 : demi-tour de 127° |
| H4 | Pannecière (58), D 303 | règle 5 : MNT `NaN` sur 131 / 131 stations (et la crête y est taguée `bridge`, donc `X`) |
| H5 | Lot (46) : chemin du Pech Saint-Cirq, 145 m (1037791655) ; Corrèze (19) | règle 4 ; Corrèze : aucune voie `cutting=yes` des classes ≥ 100 m |

Cellules vides : **T4** (moyen courbe), **T5** (long), **T6** (urbain foré), **T7** (urbain en tranchée
couverte), **H4** (crête de barrage), **H5** (déblai). Les formes T7 et H4 ne sont donc couvertes que
par le jeu de réglage (`tt04`, `tn10`, `tn11`) ; T5 et T6 par `tt02`, `tt03` et, en partie, `th09-tunnel-81`
(940 m).

**Liste gelée (2026-10-13, OSM du jour ; seule la fraction de `NaN` du MNT lue : 0 % partout).** Rôles
`tunnel-holdout` et `tunnel-holdout-negative` dans `truth/tunnels/index.csv`, mesurés **seulement**
par `tunnelHoldoutTunnelJudge`.

| id | cellule | ouvrage (dépt.) | voie(s) OSM du tunnel | km | span `N` |
|---|---|---|---|---|---|
| th01-cians | T1 | Tunnel des Traverses, D 28, gorges du Cians (06) | 173944928 | 1,28 | 440 m |
| th02-pierre-lys | T2 | Défilé de la Pierre-Lys, D 117 (11) | 40885827 | 0,90 | 95 m |
| th03-tunnel-09 | T3 | D 3, Ariège (09) | 25024680 | 1,00 | 215 m |
| th08-voie-verte-24 | T8 | Voie verte, tunnel éclairé, Dordogne (24) | 668765879, 668765888 | 1,50 | 470 m |
| th09-tunnel-07 | T9 | Rue des Défilés, Ardèche (07) | 239917000 | 1,38 | 370 m (+ un pont `X` de 40 m) |
| th09-tunnel-2b | T9 | D 344, Haute-Corse (2B) | 26842205 | 1,37 | 380 m |
| th09-tunnel-81 | T9 | Tunnel de Pech Mergou, Tarn (81) | 81148851 | 1,78 | 940 m |

| id | cellule | lieu (dépt.) | km | `X` (exclus) |
|---|---|---|---|---|
| hn01-aubisque | H1 lacets | D 918 sous Gourette (64) | 2,37 | deux ponts (25, 15 m) |
| hn02-aveyron | H2 balcon | D 115, gorges de l'Aveyron (82) | 2,38 | passage inférieur 10 m |
| hn03-boulogne | H3 rampe | Boulogne-sur-Mer, ville basse → haute ville par la porte des Dunes (62) | 0,69 | passage inférieur 15 m, porte 10 m |
| hn06-bavella | H6 col | D 268 de part et d'autre du col de Bavella (2A) | 2,98 | pont 5 m |

Soit **7 tunnels** (2 910 m de `N`, 582 stations) et **4 négatifs** — en dessous des 8–10 et 5 visés ; la série n'est
pas complétée au-delà de l'amendement 2.

**Jeu de réglage** (`tunnel-tuning`, choisi à la main, comme `p01..p08` ; avec les quatre tunnels de
`test4`) :

| id | ouvrage | voie(s) OSM | span `N` | note |
|---|---|---|---|---|
| tt01-galibier | Tunnel du Galibier, D 902 (73 / 05) | 27415944 | 365 m | sous le col ; `bicycle=no` (admis en réglage) ; lacets à l'approche |
| tt02-croix-rousse | Tube bus / piétons / vélos de la Croix-Rousse, Lyon (69) | 250224690, 1453890251 | 1 770 m | urbain, foré sous la colline |
| tt03-lioran | Tunnel du Lioran, N 122 (15) | 4902725 | 1 500 m | long, sous le col |
| tt04-tuileries | Tunnel des Tuileries (ancienne voie Georges-Pompidou), Paris (75) | 4294224 | 855 m | **tranchée couverte, sol plat au-dessus** |
| tt05-rousset | Tunnel du col de Rousset, D 518 (26) | 56415172 | 700 m | sous le col |
| tt06-nesque | Gorges de la Nesque, D 942 (84) | 135975453, 127459865 | 50 + 105 m | deux tunnels courts, un seul groupe |
| tt07-petit-frou | Tunnel du Petit Frou, D 520C (38 / 73) | 59663343 | 225 m | balcon, gorge |
| tt08-arly-cliets | Tunnel des Cliets, D 1212 (73) | 922716048 | 240 m | entre deux galeries paravalanches (`X`) |

**Négatifs de réglage** (`tunnel-negative`, 13) : `tn01` Alpe d'Huez D 211 (lacets) ; `tn02` lacets de
Montvernier ; `tn03` col du Galibier, D 902 **au-dessus** de `tt01` ; `tn04` sommet du Ventoux, D 974 ;
`tn05` Montmartre, rue Lepic ; `tn06` Lyon, montée de la Boucle ; `tn07` Lyon, quais et montée
Bonafous vers la Croix-Rousse ; `tn08` gorges de la Bourne, D 531 (balcon ; trois tunnels et deux
galeries `X`) ; `tn09` Verdon, route des Crêtes, D 23 (balcon au-dessus du vide) ; `tn10` barrage de
Roselend (la route du barrage, crête taguée `bridge`, 645 m `X` : seules les approches jugent) ;
`tn11` barrage de Serre-Ponçon ; `tn12` gorges de l'Arly sous les galeries (galerie `X`, 165 m) ;
`tn13` la plus longue voie `cutting=yes` autour de Grenoble (Grande Rue, 335 m). Les galeries
paravalanches sont `X` (structure `gallery`) : sous une galerie le LiDAR peut lire le toit ou le
versant, le banc ne juge que le sol autour.

**Mesure de référence, sans détecteur** (`tunnelControls`, `INTEGRATION=1`, réglage et négatifs de
réglage seulement ; 74 tuiles téléchargées). « MNT au-dessus de la droite » : écart max du MNT à la
droite entre portails sur le span `N` ; « raides » : stations `G` à plus de 15 % sur 100 m.

| site | IGN MNT z15 | Mapterhorn z12 |
|---|---|---|
| tt01 Galibier | +85,3 m ; 0 raide | +86,5 m ; 0 |
| tt02 Croix-Rousse | +81,5 m ; 1 | +79,2 m ; 1 |
| tt03 Lioran | +214,8 m ; 4 (max 25 %) | +208,9 m ; 3 |
| tt04 Tuileries | **+5,6 m** ; 0 | **+3,7 m** ; 0 |
| tt05 Rousset | +186,4 m ; 0 | +182,7 m ; 0 |
| tt06 Nesque | +5,2 m (50 m, portails à +23,8 %) et +40,3 m ; 9 (max 38 %) | +6,8 et +29,8 m ; 9 |
| tt07 Petit Frou | +125,0 m ; 3 (max 45 %) | +117,7 m ; 4 (max 57 %) |
| tt08 Cliets | +83,9 m ; 0 | +77,3 m ; 0 |
| négatifs `tn01`..`tn13` | 0 raide sauf `tn08` Bourne **15** (max 43,6 %) et `tn12` 2 ; max 4,1 à 15,0 % ailleurs | `tn08` **29** (max 37 %), `tn13` 9, 0 ailleurs |

Lecture : la tranchée couverte ne fait presque pas de bosse (3,7 à 5,6 m sur 855 m) — le signal
« pentes impossibles des deux côtés » ne la verra pas, et ce n'est pas grave (l'erreur est petite) ;
la route en balcon de la Bourne lit la falaise comme un tunnel, plus fort sur Mapterhorn (29 stations
raides) que sur l'IGN (15) : c'est le négatif qui jugera la précision. Les lacets, le Ventoux, les
rampes urbaines et les crêtes de barrage restent sous 15 % sur 100 m à la lecture au point.

**Branchement.** `TunnelBenchProbeTest` (`engine/src/jvmTest`), indépendant du banc des ponts (il
recopie le cache disque de `ElevationBenchProbeTest`, même répertoire) :
`tunnel_controls_parse_and_hold_their_guards` (sans réseau, chaque `jvmTest`) — en-têtes, nombre de
colonnes, `ROLES` (8 / 13 / 7 / 4, auto-contrôle de taille du lecteur), `GUARDS` par site (stations,
`O`, `L`, `X+x`, spans `N`, stations `N`), runs de `spans.csv` contre les CSV, GPX rééchantillonné
contre la vérité, 150 m de sol autour de chaque groupe `N`, aucun `N` sur un négatif, et les quatre
tunnels de `test4` dans `structures.csv` ; `tunnelControls` (`INTEGRATION=1`) retire les lignes
tenues à l'écart **avant** toute tuile (assertion sur leur nombre) ; `tunnelHoldoutTunnelJudge`
(`INTEGRATION=1` **et** `TUNNEL_JUDGE=1`) seul lecteur de la série, **pas lancé** — il n'imprime
aujourd'hui que la mesure de référence, et le lancer avant le gel du détecteur consommerait la série.
Les entrées Gradle de `jvmTest` couvrent déjà `controls/**` et `analysis/truth/**` (vérifié : une
modification de `controls/tunnels/th01-cians.gpx` relance la tâche). `controls.py` sans `--tunnels`
réécrit la série des ponts **à l'octet près**.

#### Mesures (E4)

**Livré.** `TunnelDetector` et `TunnelDetectorOptions` (`:gpx`, `commonMain`, purs : tableaux en
entrée, spans en sortie) ; `TunnelStep` (`:engine`, `internal`) en **étape 3c** du pipeline : après
3b (les tabliers), avant `smoothElevation`, donc `sourceElevation` et le D+ rapporté perdent la
montée fictive. **Activation** : dès que `fixElevation` est vrai, **sur toute source** (IGN à tout
zoom, Mapterhorn, fournisseur fait main, WASI) — l'étape ne lit que le MNT, elle n'a pas besoin de
modèle de surface ; jamais sur l'altitude propre d'un GPX (baromètre). Aucune option de porte,
aucun champ d'`EnhanceOptions`, aucun `PointField` (E7). Un surchargement `internal` de
`Enhancer.enhanceCourse(…, tunnels)` sert au seul banc. Les seuils vivent dans
`TunnelDetectorOptions.DEFAULT`, aucun littéral ailleurs.

**Placement argumenté.** Après 3b : 3b a déjà lu le MNT aux mêmes stations (`alongTrackStations`,
5 m ; l'écriture d'un tablier ne bouge aucune coordonnée, `computeDerivedData` est en haversine 2D),
3c **réutilise** ces échantillons (`BridgeDeckResult.terrain`) au lieu de relire les tuiles ; sans
modèle de surface il lit lui-même, en ordre inverse comme 3b (cache chaud). Après 3b aussi parce
qu'un tablier détecté doit primer : 3c **n'écrit jamais** dans une plage écrite par 3b, et un
portail tombé dans un tablier est épinglé au tablier (un tunnel qui débouche sur un pont). Un tunnel
dont un tablier tient **entièrement entre les portails** est abandonné (`TunnelResult.dropped`,
ajouté après le juge) : ce sont les piles d'un pont que le MNT a gardées, et écrire la droite autour
du tablier laisserait une marche verticale à chacun de ses bords. Avant le lissage, pour que le D+ lu sur
`sourceElevation` soit juste.

**Correction.** La droite entre les portails, épinglée au **MNT lu à la station du portail** — pas à
l'altitude du chemin à cet endroit : l'étape 2 a lu le MNT tous les ≤ 30 m, et l'interpolation y
mêle le front de taille au pied (sur le monde synthétique des tests, +0,7 m ; sur le banc, Grands
Goulets passe de +4,0 / 2,8 m à **+0,9 / 0,5 m** max / RMS, Moines de +17,8 / 13,2 à −0,6 / 0,3 m
avec ce seul changement). Pour la même raison, les points à moins de 30 m (le pas de l'étape 1,
`PRE_FIX_SPACING_M`) hors de chaque portail reprennent le MNT interpolé sur la grille de 5 m.
Tout autre point garde son altitude au bit près (asserté sur le banc : `TunnelResult.written`).

**Ce que le MNT montre d'un tunnel** (mesures sur le jeu de réglage, `tunnelControls`, puis profils
complets — exploration en Python sur les mêmes échantillons, portage Kotlin vérifié **à l'identique
sur 115 profils × source** : spans égaux station pour station) :

- une bosse de 27 à 216 m (IGN) au-dessus de la droite entre portails ; 3,7–5,6 m seulement sur la
  tranchée couverte des Tuileries ;
- aux portails, un **front de taille** : la pente sur 50 m juste à l'intérieur monte de +34 à
  +177 % à l'entrée et descend de −34 à −249 % à la sortie ; à l'extérieur, la route : ≤ 20 % sur
  20 et sur 50 m ;
- la droite entre portails est une pente de route : −9,6 à +4,6 % ;
- le tracé entre portails est **droit** : corde / longueur 0,90 (Combe Laval) à 1,00 ;
- un tunnel long n'est pas une bosse simple : le Lioran a un replat à mi-montée, les Grands Goulets
  une **terrasse** à 700 m (80 m au-dessus de la droite) entre deux crêtes ; la Croix-Rousse un
  plateau d'un kilomètre ; la descente au-dessus de la Croix-Rousse tient −15 à −20 % sur 20 m.

Et des négatifs : à la lecture au point, les lacets, le Ventoux, les rampes urbaines et les crêtes
de barrage restent ≤ 15 % sur 100 m (tableau de référence ci-dessus) ; la Bourne lit la falaise et
la gorge en **pointes** d'une à trois stations (+20 à +25 m, −19 m) ; la crête de Roselend (`X`) lit
n'importe quoi (±40 m tous les 15 m), comme celle que traverse `sample.gpx` au km 70,6–71,1.

**Ce qui a échoué en route**, et a fixé la forme du détecteur :

1. *Enveloppe à pente bornée* (plus grande fonction ≤ MNT de pente ≤ 12 %, excès = MNT − enveloppe) :
   coupe la Croix-Rousse en deux (le plateau rejoint l'enveloppe), et un creux (crête de Roselend)
   contamine 985 m de sol `G` (excès 108 m).
2. *Première sortie valide après l'entrée* : tronque les tunnels longs de 60 à 190 m (Galibier,
   Lioran, Croix-Rousse) — un pied de pente intérieur passe pour un portail. D'où : un portail exige
   la route **sur 20 et sur 50 m** à l'extérieur, et, parmi les sorties valides d'une entrée, on garde
   celle dont la bosse est **la plus haute** (toute la montagne, pas une épaule).
3. *La plus haute bosse* seule fusionne : sur la Bourne, un tunnel `X` et 650 m de route en descente
   jusqu'au creux suivant (`G` déplacé de 25 m). D'où : les pentes ≥ 30 % doivent être **au portail**
   (dans les 50 m), la bosse ne descend jamais à plus de 3 m sous la droite, et une **vallée
   intérieure** (une sortie puis une entrée) à moins de 15 m au-dessus de la droite fait deux tunnels
   — pas la terrasse des Grands Goulets.
4. *Lacets* : avec des seuils relâchés (30 % → 20 %), Mapterhorn trouve des « tunnels » de 2,4 et
   2,9 km sur la route pyrénéenne `strava.gpx` ; leur corde / longueur vaut 0,67 et 0,43. D'où le
   critère de **rectitude** ≥ 0,8, qui ne coûte aucun tunnel du réglage.

**Le détecteur** (`TunnelDetector`, sur le MNT aux stations de 5 m) :

1. *Portails* : une entrée a la route derrière elle (|pente| ≤ 20 % sur 20 m **et** sur 50 m) et un
   front devant (≥ 25 % sur 20 m) ; une sortie, l'inverse ; parmi les portails d'une même sorte à
   moins de 20 m, le plus net (plus grand changement de pente).
2. *Candidat* : chaque entrée `a` avec chaque sortie `b` à 50–3 000 m ; tunnel si la corde / longueur
   ≥ 0,8, la droite a une pente ≤ 12 %, le MNT monte d'au moins 15 m au-dessus d'elle et ne descend
   jamais de plus de 3 m sous elle, **les deux côtés sont impossibles** (≥ 30 % sur 50 m dans les
   50 m après l'entrée, ≤ −30 % dans les 50 m avant la sortie), et aucune vallée intérieure à moins de
   15 m au-dessus de la droite. *Ajoutés après le juge* (*Revue d'E4*) : le MNT est déjà à 15 m
   au-dessus de la droite **à moins de 100 m de chaque portail** (`faceReachM`, un front de montagne,
   pas la paroi d'un ravin sous un pont), et une station lue à 0 m exactement côté route d'un portail
   (sur 50 m : un trou lu comme le niveau de la mer) rejette.
3. Par entrée, la sortie à la bosse la plus haute ; puis l'entrée suivante après cette sortie.
   Jamais de recouvrement. Un `NaN` lu par la décision rejette.

**Sensibilité** — balayage du **prototype Python** (`py/sweep.py` du bloc-notes de la session), avant
l'épinglage de la droite aux stations des portails et la relecture des 30 m que `TunnelStep` ajoute ;
sa sortie n'a pas été archivée. Sa colonne `test4` n'est donc **pas** le banc Kotlin (qui donne
8,27 / 4,45 m après 3c) : elle compare les seuils entre eux, pas au livré. Le critère de front
(ajouté après le juge, *Revue d'E4*) n'y figure pas (un seuil à la fois, autres au défaut ; « tunnels » = groupes `N` de réglage dont la
bosse > 20 m est ramenée sous le quart, sur 14 groupes × source ; « sol » = stations `G` des 13
négatifs déplacées > 0,5 m ; « parcours » = détections sur `test1..3`, `sample`, `strava`,
`stelvio`, deux sources ; `test4` = RMS `N` IGN /
Mapterhorn, sur les stations de la vérité) :

| Seuil | Valeurs → tunnels / sol / parcours / `test4` |
|---|---|
| défaut | 12 / 13 / 0 / 8,9 – 5,3 m |
| `minSideGrade` 0,20 · 0,25 · **0,30** · 0,40 | 12/47/1 · 12/47/0 · **12/13/0** · 12/0/0 (même `test4`) |
| `minPortalGrade` 0,15 · 0,20 · **0,25** · 0,35 | 12/13/0 ×3 · 11/13/0 |
| `maxRoadGrade` 0,12 · 0,15 · **0,20** · 0,25 | 11/13/1 (10,8 – 6,8 ; 2 `T` de `test4` déplacées) · 12/13/0 (8,9 – 7,0) · **12/13/0** · 13/117/0 |
| `minHeightM` 8 · 10 · **15** · 25 | 12/13/0 ×3 · 12/0/0 |
| `maxChordGrade` 0,08 · 0,10 · **0,12** · 0,15 · 0,20 | 12/13/0 (14,1 – 11,8) · 12/13/0 · **12/13/0** · 12/13/1 · 12/13/1 |
| `maxBelowChordM` 1 · **3** · 6 | 12/13/0 (9,2 – 9,2) · **12/13/0** · 12/13/0 (8,9 – 4,9) |
| `minValleyHeightM` 5 · **15** · 30 | identiques |
| `minLengthM` 30 · **50** · 100 | 12/13/4 (piles de Saint-Nazaire, 40–45 m) · **12/13/0** · 12/0/0 |
| `steepWindowM` 30 · **50** · 100 | 12/47/0 (7,4 – 4,9) · **12/13/0** · 13/104/0 |

Le défaut n'est pas toujours au milieu d'un plateau : `maxChordGrade` 0,15 laisse passer la crête de
Roselend de `sample.gpx` (+13,6 %, un MNT sans valeur) ; `maxRoadGrade` 0,25 et `steepWindowM` 100
font entrer 100+ stations de la Bourne ; `minSideGrade` 0,40 ferait aussi bien sur ce jeu, mais
laisserait 4 points de marge à la Croix-Rousse sur Mapterhorn (+44 %) — 0,30 garde la marge côté
tunnels, la précision étant déjà tenue par les autres critères.

**Jeu de réglage** (`tunnelControls`, `INTEGRATION=1`, Kotlin ; erreur max |MNT − droite| sur le
groupe `N`, avant → après ; asserté : `TUNING_FOUND`, `GROUND_MOVED`) :

| site | IGN MNT z15 | Mapterhorn z12 |
|---|---|---|
| tt01 Galibier | 85,3 → **3,8** m (rms 3,1) | 86,5 → **1,8** (1,0) |
| tt02 Croix-Rousse | 81,5 → **2,5** (2,4) | 79,2 → **5,0** (3,2) |
| tt03 Lioran | 214,8 → **2,5** (1,4) | 208,9 → **3,5** (3,3) |
| tt04 Tuileries (tranchée couverte) | 5,6 → 5,6, non vu | 3,7 → 3,7, non vu |
| tt05 Rousset | 186,4 → **3,5** (2,0) | 182,7 → **5,1** (2,6) |
| tt06 Nesque, 1ᵉʳ tunnel (50 m) | 5,2 → 25,2 (voir ci-dessous) | 6,8 → 11,6 |
| tt06 Nesque, 2ᵉ (105 m) | 40,3 → **0,1** (0,0) | 29,8 → 9,8 (6,6) |
| tt07 Petit Frou | 125,0 → **2,1** (1,2) | 117,7 → **7,2** (4,2) |
| tt08 Cliets | 83,9 → **3,0** (1,6) | 77,3 → 77,3, non vu |

Le « 25,2 m » de Nesque n'est pas une erreur du détecteur : le span OSM de 45 m est au milieu d'une
tranchée rocheuse de ≈ 95 m (le MNT est à +20 % dès 200 m, avant les 35 m de marge `x`), et la
« vérité » de ce groupe est une droite tirée entre deux stations **sur la tranchée** (742,8 →
755,9 m) quand la route passe à 733 → 727 m ; le détecteur prend la tranchée entière (215–310 m) et
pose 733 → 727. Cliets sur Mapterhorn : la galerie paravalanche aval lit 10 m sous la route, aucune
sortie n'a la route derrière elle.

**Négatifs** : aucune station `G` déplacée sur les 13 négatifs à l'IGN ; sur Mapterhorn, **13** à la
Bourne (2 345–2 415 m, max 16,8 m), une pointe de 17 m sur 70 m (pentes +39 / −42 %) que l'IGN ne
voit pas : la correction rapproche Mapterhorn de l'IGN (écart RMS 16,9 → 9,9 m). Les tunnels `X` de
la Bourne (1 525–1 680 m, 2 585–2 640 m) sont trouvés sur les deux sources, et ce sont de vrais
tunnels. Aucun tunnel sur les 16 négatifs ponts d'E2 bis (forêts, platanes, déblais, rues, péage,
levée), ni sur les parcours `test1..3`, `sample.gpx`, `strava`, `movescount`, `sports-tracker`
(Ariège), `stelvio`, `amazfit`, `garmin` (Helsinki, Mapterhorn seul).

**Ponts sans tablier (constat).** Sur les ponts positifs `p06` (Normandie) et `p07` (Millau), le MNT
garde **pylônes et piles** (Normandie : +140 m sur 300 m de chaque côté ; Millau : une pile de 80 m sur
50 m), et le détecteur les prend pour des tunnels. Avec `ign-lidar-hd` z15 le tablier est détecté (E3)
et protégé (sur `p06`, les deux « tunnels » de pylônes tiennent dans le tablier de 1,22–3,35 km :
rien n'est écrit) ; sur Mapterhorn, la bosse est aplatie au fond du canyon : le tablier manque
toujours, mais les montées fictives de 140 m disparaissent. Le cas inverse — un tablier détecté
strictement **entre** deux « portails » — abandonne désormais le tunnel (voir *Placement*). Les piles de Saint-Nazaire (40–45 m de large) sont
sous `minLengthM`.

**Banc E1, `test4`** (`bench`, colonnes `ign -tun` = l'`ign-lidar-hd` d'E3, `map -tun` = le
`mapterhorn` d'E3 ; `src` = `sourceElevation`, `ele` = profil lissé 150 m) :

| `test4` | IGN, sans → avec 3c | Mapterhorn, sans → avec 3c |
|---|---|---|
| Stations `N`, `src` rms / max | 106,58 / +220,26 → **8,27 / +67,85** m | 106,39 / +218,23 → **4,45 / +26,08** m |
| Stations `N`, `ele` rms / max | 104,30 / +206,37 → 4,97 / +19,72 m | 104,37 / +205,26 → 3,39 / +9,31 m |
| Toutes stations, `src` rms | 17,29 → **1,37** m | 17,27 → **0,97** m |
| Balcons `S`, `src` rms / max | 2,19 / +6,05 → 2,07 / −4,36 m | 4,72 / −16,91 → 4,72 / −16,91 m |
| D+ `dem` (grille 5 m) / vrai (2 414 m) | 2 872 / 1,19 → **2 546 / 1,05** | 2 858 / 1,18 → **2 479 / 1,03** |
| D+ rapporté / vrai | 2 732 / 1,13 → **2 460 / 1,02** | 2 738 / 1,13 → **2 425 / 1,00** |
| D+ brut / vrai brut | 3 100 / 1,18 → 2 718 / 1,04 | 3 136 / 1,19 → 2 703 / 1,03 |
| Points > 15 %, `src` / `ele` | 402 / 236 → **96 / 0** | 395 / 238 → **38 / 0** |
| Durée simulée | 3:46:39 → 3:42:22 | 3:46:28 → 3:41:56 |

Par tunnel (`src` max / rms, m) :

| tunnel | IGN avant → après | Mapterhorn avant → après |
|---|---|---|
| Petits Goulets (475 m) | +67,9 / 20,4 → **+67,9 / 20,4** (non vu) | +55,1 / 20,8 → −10,9 / 2,1 (2 spans) |
| Grands Goulets (1 765 m) | +220,3 / 136,4 → **+0,9 / 0,5** | +218,2 / 136,2 → **+3,8 / 3,1** |
| Tunnel des Moines (210 m) | +61,4 / 40,7 → **−0,6 / 0,3** | +59,1 / 39,7 → +5,4 / 3,8 |
| Combe Laval (490 m) | +43,2 / 15,7 → −6,4 / 2,5 (2 spans) | +36,5 / 14,6 → +26,1 / 8,5 (1 span) |

Cible de la *Validation* : Grands Goulets à ≤ 5 m de la droite entre portails, **tenue** sur les
deux sources (0,9 et 3,8 m), sans D+ fictif ; aucune route en lacets prise pour un tunnel (Alpe
d'Huez, Montvernier, Galibier, Ventoux, `sample.gpx`, Ariège, Stelvio). Les Petits Goulets restent
le cas manqué à l'IGN : une suite de tunnels rocheux de 50–80 m séparés par des ouvertures où le
MNT lit la gorge 10 à 19 m **sous** la route ; aucune paire de portails n'y a 50 m de route derrière
elle. Les colonnes `ign -decks` (MNS détaché) et `ign-lidar-hd` sont identiques au bit près sur
`sourceElevation` hors tabliers (asserté) ; sur `test1..3`, aucun tunnel, toutes les colonnes d'E3
inchangées (18 régions, mêmes cibles manquées).

**Smoke CLI** (`enhance docs/deck-strava-data/test4.gpx --fix-elevation --cache build/dem-cache`) :

| `--dem-source` | Durée | D+ / D+ `dem` rapportés | `<ele>` dans les Grands Goulets |
|---|---|---|---|
| `ign-lidar-hd` | 13 342 s | 2 460 / 2 270 m | 608,8 → 680,1 m (vérité 600 → 681 ; 816 m avant) |
| `mapterhorn` | 13 316 s | 2 425 / 2 235 m | 612,6 → 680,8 m |

**`sample.gpx`** (`tunnelRides`, `EnhanceOptions.DEFAULT`, 3c sans → avec) : **aucun tunnel** sur
les deux sources, sortie identique au bit près (altitude et temps de chaque point, asserté) — D+
rapporté 4 554,6 m (IGN, tabliers compris) et 4 545,1 m (Mapterhorn), durée 19 617 et 19 597 s,
inchangés. Ce parcours alpin de 130 km ne passe sous aucun tunnel, et ses lacets, sa crête de
barrage (Roselend, km 70,6–71,1) et ses cols n'en ont pas la signature. Même chose sur les six
autres parcours de la démo. **Mais la marge n'est pas confortable** (revue d'E4, portage Python,
Mapterhorn) : outre Roselend (corde +13,6 % contre 12 %), la D 109 à Héry-sur-Ugine (km 18,82–20,74)
est une vraie colline de 72 m que, avant le critère de front, **seul** le flanc de sortie (−15 % contre
−30 %) refusait — un ravin de 14 m sous un pont au pied tient lieu d'entrée ; `strava.gpx` km
4,93–5,95 (23 m) ne tombait que sur la montée (19 %) ; `sports-tracker.gpx` km 2,19–2,43, une butte
de 25 m sur un sentier ariégeois, ne tombe **que** sur le flanc de sortie (−26,5 %, 3,5 points de
marge) et le critère de front ne la protège pas (fronts 25 / 22 m). Les trois sont épinglés dans
`tunnelRides` (`NEAR_MISSES` : trouvés avec le seuil relâché, refusés au défaut, et — D 109, `strava`
— refusés aussi par le seul critère de front).

**Repli canyon sans MNS : mesuré, différé.** Le détecteur retourné (MNT → −MNT, `minHeightM` 5 m)
sur les ponts de réglage, MNS retenu : **0 des 21 ponts Strava** ; sur `p01..p08`, Millau (493 /
498 stations `D`) et une partie de Normandie (41 et 94 / 449), rien d'autre. Une berge de Loire fait
5 à 10 m : jamais 30 % sur 50 m, et le canyon d'un petit pont n'a pas de « front ». Côté précision,
aucune station `G` déplacée sur les négatifs, mais deux creux de 15–20 m sur 50–100 m dans les
parcours (`sample.gpx` km 23,15, `strava.gpx` km 5,92 : des ravines, sans vérité pour trancher) et
la crête de Roselend (`sample.gpx` km 70,4–71,2). Un repli utile demanderait un autre détecteur (bords bas, fenêtre courte) et
ses propres négatifs ; non codé.

**Limites.** Non vus : tranchée couverte sous un sol plat (erreur ≤ 6 m), tunnels de moins de 50 m,
tunnels courbes ou hélicoïdaux (rectitude < 0,8), suites de tunnels courts entrecoupés de vues sur
la gorge ; **hors échantillon** (juge) : un tunnel sous une colline basse et arrondie dont un flanc
descend moins de 30 % sur 50 m (`th08`, voie verte de Dordogne, sortie ≈ −20 à −24 %), et un tunnel
court en gorge dont un portail tombe sur la paroi (`th02`, Pierre-Lys, ≈ 10 m laissés). Un portail
au fond d'un canyon de pont non détecté (Mapterhorn) y épingle la droite.

Aplatis à tort, possibles : **une vraie butte à ≥ 30 % des deux côtés** — impossible pour une route
revêtue, pas pour un chemin, un sentier VTT ou une rue très raide, que le moteur accepte aussi ; la
montée sort alors du D+ et de la durée simulée, sans option de porte pour couper l'étape (E7). Le cas
mesuré le plus proche : `sports-tracker.gpx` km 2,19 (ci-dessus). La précision n'est **mesurée que
sur des MNT dérivés du LiDAR** (IGN, et Mapterhorn z12 en France et en Finlande, voir A13/A14) :
**aucun site du banc ne lit la base mondiale GLO-30** de Mapterhorn (30 m, un modèle de surface). Un
sondage de la revue sur la route des gorges du Dadès (Maroc, 35,7 km, aucun tunnel dans OSM) y trouve
3 « tunnels » (km 16,53, 18,07, 19,97 ; 94, 22 et 53 m de bosse), probablement des parois de gorge
qu'aplatir améliore, sans vérité pour trancher ; forêt plate (Biélorussie), défrichements
amazoniens (Rondônia) : 0 ; rues de Russian Hill (San Francisco) : 0. Le critère de front ne change
pas ces trois détections. Les régions GLO-30 sont donc **non mesurées** ; à peser en E7 avant de
livrer sans interrupteur.

Sous WASI, où un `-9999` IGN lit 0 m sans repli, un îlot de couverture entre deux trous pouvait
passer pour une montagne : une station à 0 m exactement dans les 50 m côté route d'un portail
rejette désormais le candidat, y compris quand le portail tombe sur le bord interpolé du trou (test
`a_covered_island_between_two_holes_read_as_zero_is_no_tunnel`) ; un trou lu à une autre valeur
reste une limite (`wasm-wasi-abi.md`).

**Tests.** `TunnelDetectorTest` (`:gpx`, 16 : les 14 d'origine, plus la colline de la D 109 entre
deux ravins pontés (profil Mapterhorn réel, miroir) et l'îlot entre deux trous à 0 m, ajoutés après
le juge — tunnel sous une montagne, terrasse des Grands Goulets,
deux tunnels séparés de route, lacets à rampe de 18 %, vrai col à 12 %, bosse urbaine à 25 %, pointes
de balcon de 1 à 3 stations laissées, pointe de 5 stations remise sur la route, gorge entrevue entre
deux tunnels, corde trop raide, `known limit` tranchée couverte et tunnel courbe, `NaN`, options) ;
`TunnelStepTest` (`:engine`, 7 : droite entre portails et marges, échantillons de 3b réutilisés,
canyon de pont sans tunnel, tuile illisible → `FETCH_FAILED`, défaut hors lecture qui lève, portail
sur un tablier épinglé au tablier, tablier entre les portails → tunnel abandonné) ; `EnhancerTunnelTest` (4 : la montagne quitte `sourceElevation` et le D+, porte publique =
surchargement, sans correction DEM rien n'est lu, parcours sans tunnel identique au bit près,
tabliers compris) ; `TunnelBenchProbeTest.tunnelControls` (épingle `TUNING_FOUND`, `GROUND_MOVED`) et
`tunnelRides` (`RIDE_TUNNELS = 0`, identité au bit près, `NEAR_MISSES`) ; `ElevationBenchProbeTest.bench` (colonnes
`map -tun`, `ign -tun`, 3c ne bouge rien hors de `written`).

**Série tenue à l'écart : jugée une fois, après le build (voir *Jugement tunnels* ci-dessous).**
`tunnelHoldoutTunnelJudge` imprime aussi le verdict du détecteur. Ordre prévu (règle 8) : committer **d'abord, seuls**, la
règle, la liste gelée et les témoins (`controls.py --tunnels`, `truth/tunnels/`, `controls/tunnels/`,
cette section *Témoins tunnels* jusqu'au *Branchement*), puis E4 (détecteur, étape, mesures) ; geler
ce commit, puis lancer le juge **une fois** et en rapporter le résultat ici sans retoucher un seuil.
Le réglage de cette session a eu lieu après l'écriture de la règle (18 h 06) et le gel de la liste,
sans aucune lecture d'altitude sur les sites tenus à l'écart (le banc les retire de l'index avant
toute tuile, asserté ; l'exploration Python n'a lu que les sites de réglage et les parcours).

#### Jugement tunnels (E4, une seule fois, après le build)

**Lancé une fois**, le 2026-10-13 à 20 h 56, après la fin du build E4 et sans retouche ensuite :
`TUNNEL_JUDGE=1 INTEGRATION=1 ./gradlew :engine:jvmTest --tests '*TunnelJudge*' --rerun-tasks -i`
(50 tuiles lues sur le réseau : **aucune tuile tenue à l'écart n'était dans le cache du banc
Kotlin**, `build/dem-cache`. Les tuiles IGN MNT z15 de ces sites avaient en revanche été téléchargées
au gel par `coverage.py` via le cache propre de `fetch.py`, pour le seul compte des `NaN` de la règle
5 — aucune valeur n'y a été examinée ; ce même cache a servi à la lecture post-jugement de la voie
verte.)

**Ce que vaut « BUILD SUCCESSFUL ».** Le juge n'avait **aucun critère de réussite ou d'échec** fixé
avant son lancement : la règle 7 parle d'« échec » sans le définir, et le test n'asserte que
l'intégrité des données (nombre de lignes). Un build vert dit que la série a été lue intacte, rien
sur le détecteur ; le *Verdict* ci-dessous est une **lecture faite après coup**, pas un test contre
un seuil gelé. La prochaine série tenue à l'écart écrira ses critères d'acceptation dans sa règle
**avant** d'être construite (par exemple : 0 station `G` déplacée sur les négatifs, rappel ≥ N/M).

**Écart à la règle 8** : rien n'était committé — ni les témoins ni E4 — car cette session ne committe
pas ; le gel est tracé par les empreintes SHA-256 prises juste avant le juge (préfixes) :
`TunnelDetector.kt` `cfc0ac90…`, `TunnelDetectorOptions.kt` `781bd8e1…`, `TunnelStep.kt`
`c4c0e58e…`, `TunnelBenchProbeTest.kt` `a1700b4b…`, `truth/tunnels/index.csv` `4f243240…`,
`spans.csv` `6c86e170…`. Aucun seuil n'a été touché après le juge **sur sa foi**. La KDoc de
`TunnelBenchProbeTest` a été retouchée juste après (« it has not been run » → « it ran once »,
commentaire seul, `6b143d35…`). **Manifeste complet** :
`docs/deck-strava-data/analysis/tunnel-judge.sha256` (`sha256sum -c`), SHA-256 entiers de tout ce que
le juge lit — `controls.py`, les 34 fichiers de `truth/tunnels/`, les 32 GPX de `controls/tunnels/` —
et des sources du détecteur et de l'étape (`TunnelDetector*.kt`, `TunnelStep.kt`, `BridgeDeckStep.kt`,
`Enhancer.kt`) telles qu'au juge ; seule `TunnelBenchProbeTest.kt` y figure dans sa version d'après
la retouche de KDoc (`6b143d35…`), la version jugée étant `a1700b4b…`. Les fichiers de données y sont
vérifiés identiques à ce jour ; les sources du détecteur, de l'étape et du banc ont changé **après**
(*Revue d'E4*), et une copie des versions jugées est dans le bloc-notes de la session
(`judged/judged-sources.tgz`).

Lecture : *portails* = début / fin du span détecté contre la station juste hors du span OSM (la
vérité de `N`) ; *max* = |MNT − droite vraie| max sur `N` avant → après ; *RMS* après. Longueur = span
`N` de la liste gelée (stations × 5 m), département entre parenthèses.

| site | cellule | IGN : portails, max, RMS | Mapterhorn : portails, max, RMS |
|---|---|---|---|
| `th01-cians` (06, 440 m) | T1 gorge | trouvé, 0 / 0 m ; 94,3 → 0,0 ; 0,0 | trouvé, 0 / 0 m ; 88,8 → 0,0 ; 0,0 |
| `th02-pierre-lys` (11, 95 m) | T2 gorge | trouvé, −10 / 0 m ; 28,3 → 10,1 ; 6,1 | trouvé, −10 / +5 m ; 22,8 → 10,1 ; 6,7 |
| `th03-tunnel-09` (09, 215 m) | T3 moyen | trouvé, +15 / −10 m ; 56,1 → 0,8 ; 0,5 | trouvé, +10 / −5 m ; 48,6 → 1,7 ; 0,9 |
| `th08-voie-verte-24` (24, 470 m) | T8 voie verte | **manqué** ; 39,1 → 39,1 ; 28,3 | **manqué** ; 34,5 → 34,5 ; 23,9 |
| `th09-tunnel-07` (07, 370 m) | T9 | trouvé, 0 / −10 m ; 35,0 → 0,5 ; 0,2 | trouvé, −10 / −5 m ; 28,2 → 3,9 ; 2,0 |
| `th09-tunnel-2b` (2B, 380 m) | T9 | trouvé, 0 / 0 m ; 75,0 → 0,0 ; 0,0 | trouvé, 0 / +5 m ; 61,0 → 1,5 ; 0,9 |
| `th09-tunnel-81` (81, 940 m) | T9 | trouvé, 0 / +5 m ; 142,8 → 2,4 ; 1,4 | trouvé, −5 / 0 m ; 137,0 → 2,0 ; 1,2 |
| `hn01-aubisque` (64) | H1 lacets | 0 tunnel, 0 `G` bougée | 0 tunnel, 0 `G` bougée |
| `hn02-aveyron` (82) | H2 balcon | 0 tunnel, 0 `G` bougée | 0 tunnel, 0 `G` bougée |
| `hn03-boulogne` (62) | H3 rampe urbaine | 0 tunnel, 0 `G` bougée | 0 tunnel, 0 `G` bougée |
| `hn06-bavella` (2A) | H6 col | 0 tunnel, 0 `G` bougée | 0 tunnel, 0 `G` bougée |

(Portails en mètres, signe + = vers l'aval de la trace, grille de 5 m ; 0 = le portail détecté est la
station même de la vérité.)

- **Rappel** : 6 tunnels sur 7 sur chaque source (réglage : 7/8 IGN, 5/8 Mapterhorn). Aucun portail à
  plus de 15 m de la vérité ; erreur max après correction ≤ 2,4 m (IGN) et ≤ 3,9 m (Mapterhorn) sur
  les cinq tunnels francs.
- **Pierre-Lys** : trouvé, mais 10,1 m résiduels sur les deux sources. Le span OSM fait 95 m ; le
  détecteur épingle son entrée 10 m plus tôt, sur la paroi de la gorge, et la droite part de là — même
  famille que les tunnels courts en gorge du réglage (Petits Goulets).
- **Voie verte de Dordogne (manqué, deux sources)** : ancien tunnel ferroviaire droit (rectitude
  1,00), colline basse et arrondie (39 m au-dessus de la droite sur 470 m), sol plat de part et
  d'autre. Lecture post-jugement du MNT IGN (bilinéaire, `fetch.py`, sans détecteur) : la face
  d'entrée est franche (+56 % sur 20 m), mais la sortie descend en pente douce et irrégulière (≈ −20
  à −24 % sur 50 m, au plus −53 % sur 20 m juste au portail) ; cause **probable** : le critère « les
  deux flancs ≥ 30 % sur 50 m à moins de 50 m du portail » échoue côté sortie. Non vérifié dans le
  détecteur ; **non réglé**.
- **Négatifs** : **aucun faux tunnel** sur les quatre négatifs tenus à l'écart (lacets d'Aubisque,
  balcon de l'Aveyron, rampe de Boulogne, col de Bavella), sur les deux sources, et aucune station
  `G` déplacée sur aucun des 11 sites (positifs compris). Cellules vides de la liste gelée (rien à
  juger) : T4 courbe, T5 long, T6 et T7 urbains, H4 crête de barrage, H5 déblai — la précision n'est
  donc pas jugée hors échantillon sur une crête de barrage ni sur un déblai, ni le rappel sur un
  tunnel courbe ou en tranchée couverte (limites déjà écrites).

**Verdict** (lecture après coup, voir plus haut). Précision tenue hors échantillon (0 faux tunnel, 0 sol déplacé) ; rappel 6/7 sur les
deux sources, le manqué étant un tunnel sous une colline basse aux flancs doux, en deçà des seuils
de flanc. La série est désormais **dépensée** : tout réglage ultérieur (par exemple pour la voie
verte) demande une nouvelle série tenue à l'écart.

#### Revue d'E4 (après le juge, 2026-10-13 soir)

Une revue du code livré, **sans lecture de la série tenue à l'écart** (dépensée), a changé le
détecteur et l'étape. Ces changements n'ont **pas** été jugés hors échantillon : le résultat ci-dessus
vaut pour la version du manifeste, pas pour le code actuel.

- **Critère de front** (`faceReachM` = 100 m, `TunnelDetectorOptions`). Un MNT n'a pas les ponts : un
  ruisseau franchi sur un pont y est un ravin, dont la remontée passait pour le front d'un portail
  (la pente du portail est lue entre les bouts de la fenêtre, la recherche du flanc ≥ 30 % commence
  25 m avant le portail, et rien n'exigeait que la bosse monte vite près du portail). Sur
  `sample.gpx`, D 109 à Héry-sur-Ugine (Mapterhorn) : un ravin de 14 m au km 18,82 fait une entrée
  valide ; la vraie colline de 72 m qui suit (corde −0,2 %, rectitude 0,89) n'était refusée que par le
  flanc de sortie. Le même demi-profil en miroir — un ravin ponté à chaque pied, forme courante — était
  pris pour un tunnel (320–2 580 m, 70 m aplatis). Désormais le MNT doit être déjà à `minHeightM` au-
  dessus de la droite à moins de 100 m de **chaque** portail. **Mesuré** (portage Python, sur les 77
  profils × 2 sources du bloc-notes : réglage, négatifs, 16 négatifs ponts, positifs ponts, `test1..4`,
  parcours de la démo) : **aucun span ne change** de 75 à 150 m ; à 20 m, le premier tunnel de la
  Nesque (Mapterhorn, bosse 16 m) et une pointe de la Bourne tombent ; à 30 m, la Croix-Rousse et des
  tunnels de `test4`. Banc Kotlin relancé : `TUNING_FOUND`, `GROUND_MOVED`, `test4` et les parcours
  **identiques** à la sortie près. Le miroir de la D 109 est un test (`TunnelDetectorTest`), les trois
  quasi-détections des parcours sont épinglées (`NEAR_MISSES`). Le critère ne protège pas la butte du
  sentier ariégeois (fronts 25 / 22 m) ; une hauteur de front plus grande la refuserait, mais coûte des
  tunnels de réglage dès 20 m — **rejeté**.
- **Trou lu à 0 m** : une station à 0 m exactement dans les 50 m côté route d'un portail rejette le
  candidat (un trou lu comme le niveau de la mer par un fournisseur sans repli, WASI). Aucun effet sur
  le banc.
- **Tablier entre les portails** : le tunnel est abandonné (`TunnelResult.dropped`) au lieu d'écrire
  la droite autour du tablier ; un portail sur un tablier reste épinglé. Aucun effet sur le banc.
- **Une seule constante pour le pas de l'étape 1** : `Enhancer.PRE_FIX_SPACING_M` (`internal`),
  lue par `TunnelStep` (`refreshM` sans défaut) et par les deux bancs ; `BridgeDeckStep.write` lit
  `DeckReplacement.writtenRange`, la même plage que 3c protège.

**Ordre des commits recommandé** (règle 8 tenue autant que possible) : (1) les témoins seuls —
`controls.py`, `analysis/README.md`, `truth/tunnels/`, `controls/tunnels/`, la section *Témoins
tunnels* ; (2) E4 **tel que jugé** — les sources du manifeste (restaurées depuis
`judged-sources.tgz`), vérifiées par `sha256sum -c` ; (3) la revue d'E4 — ce qui précède. Une
prochaine série tenue à l'écart inclura une route sur un éperon avec un ruisseau ponté à chaque pied,
une crête de barrage, un déblai, et au moins un site GLO-30 seul.

**Ce que E7 doit décider pour les tunnels** : une option de désactivation (défaut : activé, sur toute
source) et, si oui, les six surfaces — à peser contre les deux risques de précision non bornés :
les buttes réelles ≥ 30 % des chemins et sentiers, et les régions GLO-30, non mesurées (*Limites*) ;
signaler `FETCH_FAILED` comme pour 3b ; peut-être exposer les spans (`TunnelResult.spans`,
`dropped`) à la démo.

### E5 — Lissage et D+ adaptés au LiDAR (après E3, jamais avant)

Une fois les tabliers en place, mesurer sur le banc E1 et sur `sample.gpx`/`stelvio.gpx` :

- le noyau physique à 50 m contre 150 m (l'étude : 150 m efface la moitié des rampes de pont) ;
- un preset de D+ « LiDAR » vers 1–2 m sur ≈ 50 m, contre `dem` (3 m sur 30 m).

**L'ordre est impératif** : réduire le lissage avant E3 ferait ressortir les canyons plus nets.
La question de la provenance (savoir quels points sont du LiDAR) se pose ici ; avec `ign-lidar-hd`,
le repli Mapterhorn la connaît déjà point par point.

#### Mesures (E5)

Banc E1 en direct (`ign-lidar-hd` avec tabliers et tunnels, tel que livré ; `mapterhorn` avec
tunnels), `sample.gpx` et les parcours de la démo. Les variantes de noyau et de preset ont été
mesurées par une sonde jetable (supprimée) ; le changement retenu, par le banc lui-même.

**Décision.** Un seul changement : `ElevationGain` passe **avant** `PathSimplifier` (étape 10, sur le
tracé 1 Hz, ou dense si le 1 Hz est coupé), et `PathSimplifier` recopie `elevationGainFiltered` /
`elevationLossFiltered` sur le chemin qu'il construit. Le noyau de 150 m et le preset `dem` (3 m sur
30 m) **ne bougent pas**. Le gain est net sur IGN (`test2` 0,49 → 0,95) ; sur Mapterhorn, la source
par défaut, il est mitigé : `test2` passe de 0,90 à 1,46, l'erreur moyenne sur le banc de 0,38 à 0,28
(détail au §3). Aucune option ajoutée, aucun défaut changé, rien pour E7.

**Deux prémisses étaient fausses.**

- Le « +9,29 m près des tabliers » n'est pas le noyau qui étale un tablier : le pire point est sur
  `test1` au km 8,80, le passage supérieur de l'avenue de la Prise-d'Eau, un pont **manqué** dont la
  vérité est le canyon du MNT (−5,6 m). Près des rampes trouvées (stations à ciel ouvert à moins de
  300 m d'un ouvrage), l'écart lissé est de −1,89 m au pire sur `test1` (−1,86 m à 50 m). Le +5,2 m de
  `test2` est l'artefact connu de l'appariement au pont de Bellevue manqué (km 1,23), déjà présent
  avant lissage.
- Le D+ dense de `test3` (1,49 × vérité) n'est pas du bruit réparti : les 9 m d'excès tiennent en
  trois kilomètres (km 2 : 1/0, km 7 : 4/0, km 8 : 4/0, vcyclist/vérité). Aux km 7,98 et 8,91 le MNT
  IGN lu par vcyclist est 3,5 et 5,8 m au-dessus de la vérité, sur 25–75 m de terrain dégagé au bord de
  la Loire — une digue ou un déblai qu'un décalage latéral attrape. Problème de résolution et de
  recalage ; aucun preset de D+ ne retire une bosse de 4–6 m sans retirer les petits ponts (≈ 5,4 m).

**1. Noyau : 150 m conservé.**

| Mesure (`ign-lidar-hd`) | 150 | 100 | 75 | 50 | 30 |
|---|---|---|---|---|---|
| `test2` lissé, terrain dégagé RMS (m) | 0,27 | 0,23 | 0,21 | 0,19 | 0,17 |
| `test2` lissé, rampes RMS | 0,56 | 0,47 | 0,44 | 0,41 | 0,40 |
| `test2` lissé, tabliers RMS / max | 0,86/−4,28 | 0,67/−4,08 | 0,63/−4,25 | 0,65/−4,86 | 0,70/−5,68 |
| `test1` lissé, tabliers RMS | 0,22 | 0,20 | 0,23 | 0,30 | 0,39 |
| `test3` lissé, tabliers RMS | 0,24 | 0,14 | 0,10 | 0,08 | 0,06 |
| `test4` stations > 15 % sur 50 m (vérité : 2) | 0 | 25 | 59 | 81 | 89 |
| `test4` pente max sur 10 m | 16 % | 22 % | 36 % | 76 % | 143 % |
| `test4` tunnels, pire écart lissé (m) | +19,7 | +21,9 | +26,5 | +34,7 | +45,6 |
| `test4` durée | 3:42:22 | +0:39 | +1:08 | +1:18 (+0,6 %) | +1:48 |
| `sample` stations > 15 % sur 50 m | 54 | 85 | 96 | 113 | 153 |
| `sample` durée | 19 617 s | +0,2 % | +0,3 % | +0,4 % | +0,5 % |

La ligne tunnels est grande parce que les Petits Goulets ne sont pas détectés sur IGN : un noyau plus
court expose ce que les corrections ont laissé. Mapterhorn pareil (`test4` stations raides 0/11/15/27/30,
Combe Laval +9,3 → +16,6 m à 50 m). Durées de `test1..3` à 50 m : +1 % au plus (`test1` Mapterhorn +0,95 %, `test2` IGN +0,52 % ; +1,6 % et
+0,64 % à 30 m). Parcours lus sur leur propre
`<ele>`, sans `--fix-elevation`, à 50 m : `stelvio` 594 s +7,1 % (avec MNT : 572 s, +2,1 %), `amazfit`
948 s +4,0 %, `strava` 2 899 s +0,2 %, `sports-tracker` 2 217 s −3,3 % (pentes jusqu'à 839 %). Le noyau
de 150 m n'aplatit pas les vrais tabliers : Saint-Nazaire, pente max sur 10 m 5,7 % contre un p95 vrai
de 5,9 % ; Cheviré 6,4 % contre ≈ 6,3 % ; les tabliers corrigés s'écartent du profil source corrigé de
3,55 m au plus (Langeais), 3,19 m à 50 m.

**2. Preset de D+ : `dem` conservé.** Rapport D+ dense (grille 5 m, profil source) / vérité au même
preset :

| Route (`ign-lidar-hd`) | dem | 2/30 | 2/60 | 1,5/45 | 1/30 | 1/60 | 3/60 |
|---|---|---|---|---|---|---|---|
| `test1` | 1,08 | 1,11 | 1,05 | 1,07 | 1,06 | 1,05 | 1,02 |
| `test2` | 0,95 | 1,06 | 0,97 | 1,02 | 0,99 | 0,98 | 0,97 |
| `test3` | 1,49 | 1,26 | 1,21 | 1,21 | 1,20 | 1,12 | 1,26 |
| `test4` | 1,05 | 1,05 | 1,03 | 1,04 | 1,05 | 1,03 | 1,03 |

Un seuil de 1–1,5 m change ce que mesure le D+ plutôt que sa justesse : la vérité elle-même passe de
18 à 36 m sur `test3` et de 89 à 109 m sur `test2` à 1 m sur 30 m. 2 m sur 60 m donne presque la même
vérité que `dem` (71/88/22/2 411 contre 72/89/18/2 414) et gagne 2–5 points, mais coupe les traces
enregistrées sans rien pour vérifier : `sports-tracker` 1 081 → 855 (−21 %), `stelvio` sans MNT
172 → 152 (−12 %). Aucun preset ne corrige `test3` (voir plus haut).

**3. L'effondrement du D+ rapporté, et le correctif.** `PathSimplifier` fait son Douglas-Peucker sur
l'altitude **lissée** (tolérance 10 m, exagération 3) et garde ≈ 5 % des points (142 sur 2 674 sur
`test2`) ; `ElevationGain` relisait ensuite le profil source aux seuls points gardés, donc sans les
rampes des ponts nantais. Variantes à k = 150 (sonde) :

| Variante | `test2` IGN : D+ (× vérité), points | `test1` IGN | `test3` IGN | `test4` IGN | `test4` Mapterhorn | Mapterhorn `test1` / `test2` / `test3` |
|---|---|---|---|---|---|---|
| Avant : simplifier, puis mesurer | 44 (0,49), 142 | 59 (0,82), 60 | 10 (0,57), 38 | 2 460 (1,02), 773 | 2 425 (1,00), 779 | 12 (0,17) / 80 (0,90) / 7 (0,40) |
| **Mesurer le 1 Hz, garder le chiffre** | **84 (0,95), 142** | **78 (1,08)** | 26 (1,48) | 2 543 (1,05) | 2 476 (1,03) | 51 (0,71) / 130 (1,46) / 24 (1,34) |
| Mesurer le dense | 85 (0,95) | 78 | 27 | 2 546 | 2 479 | 53 / 130 / 24 |
| Exagération 6 | 65 (0,73), 153 | 59 | 14 | 2 474, 788 | 2 436 | 41 / 108 / 14 |
| Exagération 10 | 74 (0,83), 161 | 59 | 18 | 2 474, 814 | 2 443 | 46 / 110 / 14 |
| Tolérance 5 m | 70 (0,79), 239 (+68 %) | 59 | 17 | 2 491, 1 157 | 2 445 | 41 / 108 / 15 |
| Simplifier sur le profil source | 65 (0,73), 157 | 72 | 19 | 2 511 | 2 449 | 42 / 109 / 16 |

Retenu : la deuxième ligne. Les points et la taille du fichier ne changent pas (18 kB contre 337 kB pour
le fichier 1 Hz de `test2` IGN) ; le D+ rapporté ne dépend plus d'un réglage de taille de fichier
(`--no-simplify` le déplaçait de −4 %, `sports-tracker`, à +325 %, `test1` Mapterhorn 12 → 51 m ;
`test2` IGN 44 → 84 m, +91 %). La raison de faire tourner l'étape en dernier — que le
résumé s'accorde avec sa propre trace — ne tenait pas : le `<ele>` écrit est le profil lissé, et sur
`test2` il donnait 36 m au preset `dem` quand 44 m étaient rapportés (`test4` : 2 386 contre 2 460).

**Après le correctif, banc E1** (`ElevationBenchProbeTest.bench`, D+ rapporté × vérité ; les autres
colonnes, durées comprises, sont identiques à l'unité près ; le banc asserte désormais
« D+ rep. » = « D+ 1Hz ») :

| Route | `map -tun` | `mapterhorn` | `ign -decks` | `ign -tun` | `ign-lidar-hd` |
|---|---|---|---|---|---|
| `test1` (vérité 72 m) | 12 (0,17) → 51 (0,71) | 12 → 51 | 12 (0,17) → 52 (0,73) | 59 (0,82) → 78 (1,08) | 59 (0,82) → 78 (1,08) |
| `test2` (89 m) | 80 (0,90) → 130 (1,46) | 80 → 130 | 87 (0,98) → 135 (1,52) | 44 (0,49) → 84 (0,95) | 44 (0,49) → 84 (0,95) |
| `test3` (18 m) | 7 (0,40) → 24 (1,34) | 7 → 24 | 11 (0,60) → 32 (1,76) | 10 (0,57) → 26 (1,48) | 10 (0,57) → 26 (1,48) |
| `test4` (2 414 m) | 2 738 (1,13) → 2 853 (1,18) | 2 425 (1,00) → 2 476 (1,03) | 2 460 (1,02) → 2 543 (1,05) | 2 732 (1,13) → 2 868 (1,19) | 2 460 (1,02) → 2 543 (1,05) |

**Sur la source par défaut, le bilan est mitigé.** Mapterhorn (`ElevationSource.DEFAULT`) n'a pas de
tabliers : le correctif expose les canyons sous les ponts nantais que la simplification masquait, et
`test2` se dégrade, 0,90 → 1,46 (`ign -decks` 0,98 → 1,52) ; `test4` passe de 1,00 à 1,03. `test1`
(0,17 → 0,71) et `test3` (0,40 → 1,34) s'améliorent. L'erreur absolue sur `test1..4` passe de
0,83 / 0,10 / 0,60 / 0,00 à 0,29 / 0,46 / 0,34 / 0,03 : meilleure en moyenne (0,38 → 0,28), pire sur
`test2`, où l'ancien chiffre était juste par accident — le D+ dense donnait déjà 1,46 depuis E1. `TARGETS_MISSED`
inchangé, `test3` y reste.

**Parcours** (D+ rapporté, `dem`, CLI et `TunnelBenchProbeTest`, avant → après ; durées identiques) :

| Parcours | Sans `--fix-elevation` | `ign-lidar-hd` | `mapterhorn` |
|---|---|---|---|
| `sample` | 4 485 → 4 501 (+0,4 %) | 4 555 → 4 652 (+2,1 %) | 4 545 → 4 599 (+1,2 %) |
| `stelvio` | 148 → 172 (+16 %) | — (hors IGN, = Mapterhorn) | 131 → 132 |
| `sports-tracker` | 1 126 → 1 081 (−4,0 %) | 528 → 538 | 527 → 533 |
| `amazfit` | 94 → 100 (+6,4 %) | — | 79 → 85 |
| `movescount` | 514 → 519 | 527 → 537 | 526 → 532 |
| `strava` | 632 → 634 | 672 → 738 (+9,7 %) | 666 → 714 (+7,2 %) |
| `garmin` | 6 → 6 | — | 8 → 17 |

Le chiffre « après » sans MNT est celui que `--no-simplify` donnait déjà, à 3 m près celui de la
colonne « dem / source » du tableau *What the pipeline reports* d'`elevation.md` ; avec Mapterhorn, `strava`
rejoint les 714 m que *What the DEM costs* documentait (mesurés avec `--no-simplify`). Sur
`sample.gpx`, les tabliers coûtent désormais −0,27 % de D+ rapporté (4 665 → 4 652 m) au lieu de
+0,04 % : dans la tolérance de 0,5 % de la *Validation*, durée −0,01 %.

**4. Par source, provenance.** Les conclusions noyau et preset valent pour les deux sources ; `stelvio`
(hors couverture IGN, repli Mapterhorn) sort identique au bit, `sample` est couvert par IGN sur
357 points échantillonnés sur 357. Pas de comportement par source, pas de crochet de provenance : le
statut des tabliers et des tunnels reste E7 (3) et (4).

**Sites modifiés.** `Enhancer.kt` (étapes 10 et 11 échangées, KDoc), `PathSimplifier.kt` (recopie
des deux scalaires après `computeDerivedData`, dont `resetStats` les efface ; les setters restent
`internal` à `:gpx` ; un `PathSimplifier.simplify` appelé seul, `PathSimplifierJvm.simplify`
compris, garde donc lui aussi le D+ filtré de son entrée au lieu de `NaN`), KDoc de `Path.elevationGainFiltered`, `ElevationGain.annotate` et
`EnhanceOptions.elevationGain` ; description de `elevationSmoothWindowM` (KDoc commun et JS,
`index.d.ts` régénéré, aide CLI, `wasm-wasi-abi.md`, `using-from-javascript.md`) qui le disait
déterminant du D+ rapporté ; ordre du pipeline dans `using-from-javascript.md` ; `CLAUDE.md` (liste du pipeline), diagramme du `README.md` ;
`elevation.md` ; ligne R33. Tests : `EnhancerElevationGainTest.simplification_does_not_move_the_reported_gain`
(une bosse de 6 m sur route plate que Douglas-Peucker supprime), `PathSimplifierTest` (recopie,
`NaN` conservé), assertion d'égalité des colonnes du banc.

### E6 — Corriger la documentation existante

- `elevation.md`, *What the DEM costs* et *Raising the DEM zoom* : z12 n'est pas la résolution
  native en France ; Mapterhorn y sert le LiDAR HD jusqu'à z17. La conclusion pratique (monter en
  zoom ne sert à rien pour le D+) reste vraie, la raison est fausse.
- Ligne R30 du ledger : même correction.
- KDoc de `ElevationSource.IGN_LIDAR_HD` et section *No bridges* d'`elevation.md` : renvoyer à
  cette tâche une fois E3 livrée.

**Fait (2026-10-13).** `elevation.md` : *What the DEM costs* — la raison « z12 = résolution native »
remplacée par la mesure de l'*Input 2* (Mapterhorn = LiDAR HD sous-échantillonné en France, z17
servi), conclusion inchangée (1,5–3 % de D+, < 1 % de durée) ; *Raising the DEM zoom* — z17 en
France, GLO-30 seulement hors modèles nationaux ; section IGN — « ses tuiles *sont* du LiDAR HD » ;
pipeline — étapes 3b et 3c ajoutées ; renvois R31/R32/R33 en fin de *No bridges* et de *Tunnels* ;
en-tête R27–R33. Ledger : note datée sous R30, l'historique intact. KDoc de
`ElevationSource.MAPTERHORN` (source fusionnée, LiDAR HD en France) et `IGN_LIDAR_HD` (étapes 3b
et 3c, R31–R33). Laissé en l'état : le commentaire de `MapSpace.kt:30` (« no deeper tiles »), que
le guide et R30 citent déjà comme faux.

### E7 — Les sept surfaces (six à l'écriture de la tâche, HTTP depuis)

Si E3 devient une option (`bridgeDecks` ou équivalent) : cœur → CLI → JS → WASI → JVM/Java →
contrôle dans la démo → ligne de `surface-coverage.md`, selon la checklist de `CLAUDE.md`.
Défaut à décider après E1 : activé avec `ign-lidar-hd`, sans doute ; avec `mapterhorn`, seulement
si le coût en tuiles MNH reste raisonnable (voir *Notes*).

#### Mesures (E7)

**Fait (2026-10-13).** Deux options et un rapport, sur les sept portes (la septième, HTTP, est
`:api`).

*Options.* `ElevationCorrectionOptions(bridgeDecks, tunnels)`, toutes deux `true`, ajoutée en
**dernier** paramètre d'`EnhanceOptions` (`elevationCorrections`) parce que `EngineModelJvm` passe
ses arguments par position. Imbriquée plutôt que deux booléens à plat : même forme que `curvature`
/ `racingLine`, un seul site de défaut par porte (`ElevationCorrectionOptions.DEFAULT`, lu comme
`d.elevationCorrections.*`), et de la place pour un réglage du détecteur sans élargir
`EnhanceOptions`. Clés filaires `bridgeDecksEnabled` / `tunnelsEnabled` (convention
`<étape>Enabled`) ; CLI `--[no-]bridge-decks` / `--[no-]tunnels`. Le `true` des tabliers est une
**permission**, pas une promesse : l'étape 3b ne tourne que si `fixElevation`, l'option et un
`surfaceModel` sont réunis — le défaut effectif est donc dérivé de `provider.surfaceModel != null`
dans `Enhancer`, jamais d'un littéral de porte (point (1)). La désactivation des tabliers sert la
reproductibilité (sans elle, la même commande rend deux profils selon le réseau), le coût (une tuile
MNS de plus par tuile MNT, sous les 20 req/s de l'IGN) et la comparaison. Celle des tunnels sert
les deux risques non bornés de *Limites* (E4) : les buttes réelles ≥ 30 % des chemins (la butte
ariégeoise de `sports-tracker.gpx` n'est protégée que d'un critère) et les régions GLO-30, non
mesurées. Elle remplace le surchargement `internal enhanceCourse(…, tunnels)` des bancs, qui
disparaît.

*Rapport (points (3) et (4)).* `ElevationCorrections` (statut par étape, comptes appliqués et
écartés, avertissements) et `ElevationCorrectionStatus` (`not-run`, `unavailable`, `no-coverage`,
`none-found`, `applied`, `fetch-failed`), dans `:gpx` puisque `Path` les porte. Le rapport est posé
**une fois**, après l'étape 11, sur `Path.elevationCorrections` — même canal que
`elevationGainFiltered`, donc aucun nouveau point d'entrée, aucun type de retour changé ; `copy()` et
`PathSimplifier` le portent, `computeDerivedData` ne l'efface pas. Écarté : un
`enhanceCourseWithReport(): EnhanceResult`, qui coûtait un export JS, une table par handle côté WASI
et quatre ponts JVM. Le texte des avertissements n'existe que dans `ElevationCorrectionReport`
(`engine/…/path/`) ; un avertissement seulement quand la sortie est sciemment fausse ou dépend du
réseau : lecture MNS échouée, tablier détecté laissé au fond (avec son km sur l'axe de l'étape 3),
lecture MNT échouée pendant l'étape tunnels. Rien pour `unavailable` / `not-run` (le cas normal sur
Mapterhorn) ni pour un tunnel écarté (comportement voulu, compté seulement).

*Portes.* CLI : chaque avertissement sur stderr (`warning: <fichier> track <n>: …`), même avec
`--quiet`, code de sortie 0, et une ligne d'état sur stdout avec `--fix-elevation`. JVM :
`getElevationCorrections()` gratuit, fabrique `EngineModelJvm.elevationCorrectionOptions(…)`. JS :
les deux clés (DTO, `ENHANCE_OPTIONS_KEYS`, lues sur `defaultJsOptions()`), et l'export
`pathElevationCorrections(path)` ; `enhance` et `enhanceWithCourse` analysent désormais leurs DTO
et construisent leur fournisseur de MNT **avant** `GlobalScope.promise` (une clé inconnue, un DTO de
cycliste, de vélo, de vent ou de puissance invalide, une `demSource` inconnue ou un `demZoom` hors
bornes lèvent à l'appel, plus en promesse rejetée — changement de comportement public, à signaler
dans le message de commit, documenté dans `using-from-javascript.md`). WASI :
les deux clés acceptées, l'export `vcPathElevationCorrectionsJson(handle)` ; les corps de
`vcEnhance` / `vcEnhanceWithCourse` extraits en `enhanceFromJson` / `enhanceCourseFromJson`
(idiome w03), **sans valeur par défaut** pour le fournisseur — sur Kotlin/Wasm un paramètre par
défaut garde sa valeur atteignable de tout appel, et `hostTileFetcher` amenait l'import `fetch_tile`
dans le binaire de test, que le lanceur KGP ne sait pas instancier. HTTP : les deux clés (22 clés
pour `EnhanceOptions`, `/defaults` les publie), `TrackResult.elevationCorrections` dans le résumé,
chaque avertissement ajouté à `EnhanceResponse.warnings` préfixé `track <n> (<nom>)`, et
`GET /catalog` liste `elevationCorrectionStatuses`. Démo : deux cases sous la source du MNT (le
libellé des tabliers grisé hors `ign-lidar-hd`, sans désactiver la case — c'est une permission),
une ligne d'état sous les statistiques du fichier et une alerte pour les avertissements ; le
libellé IGN de `DEM_SOURCES` ne dit plus « No bridges ».

*WASI (point (2)).* Clé `bridgeDecksEnabled` **acceptée**, rapport `unavailable` dès que l'étape
est permise (`not-run` si `false` ou sans `fixElevation`) : les deux
portes filaires acceptent les mêmes clés, et contrairement à `demSource` rien ne peut y être mal
appliqué. `tunnelsEnabled` y est pleinement effectif (l'étape 3c ne lit que le MNT). Ligne R31 du
ledger : WASI ❌ avec note ; table par option (engendrée) : ✅ pour la clé.

*Fichiers et artefacts.* Catalogue `OptionCatalog` (deux entrées `ALL_WIRE`), `DoorKeyParityTest`
20 → 22, `TsFacade` 12 → 13 DTO et 34 → 35 fonctions, `StringUnions` lie les deux statuts du DTO JS
à `ElevationCorrectionStatus` (union fermée dans `index.d.ts`), `WasiExportCatalog` (une ligne
`RESHAPED`), `tools/wasi/host.py` (`elevation_corrections`) et un test Python. Régénérés :
`index.d.ts` / `.mjs` / `.cjs` (`:codegen:generateTsFacade`), la table par option de
`surface-coverage.md` (`:codegen:generateSurfaceLedger`), `api/contract/openapi.yaml`
(`:api:generateOpenApi`). Tests : `EnhancerElevationCorrectionsTest` (cœur, 18 cas),
`EnhanceCommandTest` 33–36, `ElevationCorrectionsJavaTest`, `EngineJsApiElevationCorrectionsTest`,
`ElevationCorrectionsWasiTest` (passe par `enhanceFromJson`), `WasiOptionsTest`,
`WasiJsonOutputTest`, et trois cas d'`EnhanceRoutesTest` (MNS injoignable → `fetch-failed` et un
avertissement `track 1 …` ; `bridgeDecksEnabled: false` → `not-run`, MNS jamais lu).

*Non fait.* Les tronçons (point (3), et `TunnelResult.spans` côté tunnels) ne sont pas exposés — voir
*Notes*. Point (5) sans objet : aucun fournisseur JS n'entre dans `enhance`, la façade construit le
sien par `ElevationSource.provider()`, qui attache le MNS. Un échec MNT pendant l'étape tunnels n'a
pas pu être provoqué à travers le pipeline (l'étape 2 a déjà mis les tuiles en cache) : il est
épinglé au niveau de `ElevationCorrectionReport.of`, comme le tablier écarté.

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
- Banc E2 bis, témoins négatifs : **0 FP** (hors ouvrage propre `X`) et 0 station hors vérité sur la
  route forestière et la rue bâtie.
- Banc E2 ter (asserté par `deckControls`) : rien de ce que trouve `PYTHON` n'est perdu, couverture
  `D` ≥ 90 %, 13/15 spans, `p01`/`p06`/`p07` couverts à ≥ 95 % chacun ; ces trois-là sont des cas de
  réglage, la généralisation se juge sur les *Témoins E2 ter*.
- `sample.gpx` : D+ rapporté et durée simulée inchangés à 0,5 % près hors ouvrages, et l'écart
  expliqué ouvrage par ouvrage s'il y en a.
- `./gradlew check` vert ; smoke CLI `enhance` avec `--dem-source ign-lidar-hd`.

## Done when

E1 à E4 livrés et mesurés, E6 fait, R31 et R32 remplis avec leurs chiffres ; E5 soit livré, soit
consigné dans le ledger comme mesuré et écarté ; E7 : options et rapport sur les sept portes,
lignes de `surface-coverage.md`.

## Notes

- **Coût réseau.** `cf0798e` mesure 132 tuiles IGN (33 MB) pour les 130 km de `sample.gpx`. Le MNH
  ajoute au pire autant ; le MNS seulement les tuiles des ouvrages candidats. Pistes : n'interroger
  le MNH qu'à z13–z14 (un tablier fait des dizaines de mètres, le détecteur n'a pas besoin de
  3 m de pixel), puis le MNS à z15 sur les seuls candidats. À mesurer, comme la cadence de 20 req/s.
- **WASI.** `fetch_tile(zoom, x, y)` ne nomme pas de source : l'hôte ne sait servir qu'une couche.
  E3 y est impossible sans changement d'ABI (un argument de couche, ou un second import). E4 n'a
  pas ce problème. **Tranché le 2026-10-13 : pas de changement d'ABI.** Sous WASI, les ponts restent
  au MNT (E4 s'applique) ; E7 inscrit ce manque dans `surface-coverage.md` et dans
  `wasm-wasi-abi.md`, au lieu d'ajouter une couche à `fetch_tile`.
- **Tronçons non exposés (E7).** Le rapport donne des comptes et, pour un tablier écarté, son km —
  pas les tronçons corrigés. Raison : leurs distances sont sur l'axe densifié de l'étape 3, que la
  ligne de course, le rééchantillonnage à 1 Hz et la simplification déplacent ; une superposition
  juste demanderait un `PointField` de provenance (ordinal 45, codegen, indices de champs CSV / JSON
  / WASI : un changement de format), et E5 l'a mesuré inutile (« pas de crochet de provenance »).
  Le km d'un tablier écarté, lui, reste fiable : ce décalage de quelques mètres disparaît dans un
  libellé à 0,1 km, pas dans une superposition point par point.
  Côté HTTP, ⚠️ dans le ledger : seul le format `summary` porte le rapport ; les formats fichier
  (GPX, FIT, CSV, JSON en colonnes) n'ont pas de canal d'avertissement.
- **Pourquoi pas les tags OSM.** vcyclist ne lit pas OSM, et l'étude montre que les tags `bridge`
  ratent des ouvrages entiers (Saint-Nazaire). Le MNH est une détection *physique*, indépendante de
  la qualité des tags. Un GPX issu d'un routeur peut porter `bridge` dans ses extensions ; c'est un
  indice à considérer, pas une source de vérité.
- **Baromètre.** Pour un GPX *enregistré* avec baromètre, `fixElevation` jette un signal que
  l'étude valide (rampes restituées à 0,91, retard de 3 à 10 s, dérive ≈ 3 m/h). Une fusion
  « baro pour la forme, MNT pour le niveau » (filtre complémentaire, λ 200–500 m) est hors du
  périmètre de cette tâche, centrée sur l'itinéraire planifié, mais mérite sa propre ligne.
- **Ce qui reste peu mesuré** : l'étude Loire n'avait ni tunnel ni route de montagne ; l'*Input 5*
  couvre quatre tunnels et deux routes en balcon ; les témoins de E2 bis ajoutent une route en
  déblai (`n04`), un viaduc de montagne (`p07`, `n03`) et des témoins forestiers (`n06`, `n07`).
  Manquent toujours un sol qui tombe hors pont, une forêt en pente et une chaussée sous des
  immeubles (*Limites du banc*, E2 bis) : à ajouter avant toute conclusion générale.
