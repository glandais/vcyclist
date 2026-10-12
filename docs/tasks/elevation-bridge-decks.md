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
interpolation linéaire entre portails dans les tunnels, MNT ailleurs. Les emprises des ouvrages
(km de début et de fin, portails des tunnels) sont figées dans le test à partir de
`analysis/results.json`, pour que le banc ne dépende pas d'Overpass. Garde-fous : Saint-Nazaire,
point haut 63,7 m NGF-IGN69 et pente max 5,9 % (cohérente avec `incline=6%`) ; Grands Goulets,
600 → 681 m entre portails.

Métriques par cas : erreur max et RMSE sur le tablier, rapport D+ / D+ vrai (preset courant et
seuil 0), durée simulée, nombre de points dont la pente dépasse 15 %. Colonnes : `mapterhorn`,
`ign-lidar-hd`, puis chaque variante des étapes suivantes.

### E2 — Détecteur d'ouvrage sur le MNH

Échantillonner le MNH le long de la trace à pas de 5 m, et marquer comme **candidat** toute suite
de points où MNH > 5 m sur au moins 3 points consécutifs (seuil de l'étude, §6). Fusionner les
candidats séparés de moins de 300 m (trous de MNS, piles). Classer ensuite chaque candidat avec les
critères de l'*Input 4* ; ceux qui échouent restent au MNT.

Points à trancher par la mesure E1 : seuils de rugosité et de pente, et tolérance de raccord aux
culées. Le taux de faux positifs sur les témoins forestiers est le critère d'acceptation, avant le
gain sur les ponts.

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
