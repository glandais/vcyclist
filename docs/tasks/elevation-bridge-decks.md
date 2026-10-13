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
l'acceptation, et ne départage aucun réglage (voir ci-dessous). Les `p09..` restent à faire.

#### Vallons boisés et seuils combinés (E2 quater)

> **Note (2026-10-13).** Chiffres mesurés à l'ancienne convention des coins (lecture bilinéaire
> décalée d'un demi-pixel au sud-est). Aux centres (`elevation-half-pixel`) : Strava 14/21, 5 / 5
> hors vérité, témoins 13/15 à 73 % (1 240 / 1 692), Normandie `STEEP` (P95 propre 41,1 %, 4 %),
> Cheviré P95 7,4 %, `n13` sans candidat mesuré — voir `elevation-half-pixel.md` § Résultats. Les
> tables ci-dessous restent valables pour la convention qu'elles décrivent : la variante (a⌐) du
> banc les reproduit exactement.


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
consigné dans le ledger comme mesuré et écarté.

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
