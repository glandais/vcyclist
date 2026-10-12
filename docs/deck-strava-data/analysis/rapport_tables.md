# Altitude des itinéraires Strava vs IGN LiDAR HD (ponts et tunnels)

Méthode : GPX rééchantillonné à 5 m (lat/lon et altitude Strava interpolés linéairement) ; MNT et MNS LiDAR HD (WMS Géoplateforme, BIL float32, pavage de 0,008° × 256 px ≈ 3,5 × 2,4 m/px, interpolation bilinéaire) ; MNH = MNS − MNT (vérifié identique à la couche MNH à 1e-5 m près). Offset Strava − MNT : médiane sur MNH < 0,5 m. Ponts : runs MNH > 5 m sur ≥ 3 points, fusion des trous < 300 m, MNS localement lisse (RMS résidu/médiane 25 m < 0,8 m), pente du MNS filtré (P95, 25 m) ≤ 10 %, MNT sous l'ouvrage > 3 m sous les deux bouts ; bornes étendues jusqu'à MNH ≤ 1 m. Chaussée estimée = MNS médiane 25 m sur les tabliers (MNH > 1 m), interpolation linéaire portail-à-portail dans les tunnels, MNT ailleurs. Tunnels : MNT > Strava + 15 m, ou |pente Strava sur 100 m| > 15 % ; bornes recalées sur les portails OSM (Overpass, requêtes bbox) quand un tunnel OSM chevauche. Noms : OSM (way `bridge`/`tunnel` à < 10 m de la trace). D+ : hystérésis 0/2/3 m, brut ou après moyenne mobile 45 m.

Figures : `fig/<fichier>_profil.png` (profil complet + écart Strava − chaussée), `fig/<fichier>_pontN.png`, `fig/<fichier>_tunnelN.png`.


## test1 — 10.0 km, 219 points GPX (pas médian 43 m, max 100 m)

- Offset datum Strava − MNT : **-0.52 m** (IQR -0.87 / -0.22, n = 1185 points dégagés)
- Hors ouvrages (après offset) : biais +0.04 m, RMSE 0.85 m, |écart| P95 0.91 m ; sur terrain dégagé seul : biais -0.02, RMSE 0.43 m
- Pente max sur 50 m, à > 100 m de tout ouvrage : Strava 1.3 % (P99 1.0) ; MNT lissé 45 m 8.6 % (P99 6.9). Sur tout le parcours : Strava 7.7 %, chaussée lissée 8.6 %

| D+ (m) | h0 brut | h2 brut | h3 brut | h0 MM45 | h2 MM45 | h3 MM45 |
|---|---|---|---|---|---|---|
| Strava | 68 | 56 | 56 | 67 | 58 | 56 |
| MNT brut | 236 | 167 | 140 | 86 | 49 | 40 |
| MNS brut | 227 | 162 | 143 | 108 | 76 | 70 |
| Chaussée estimée | 123 | 93 | 80 | 84 | 69 | 62 |

**Ponts détectés**

| km | L (m) | nom (OSM) | canyon max (m) | tablier max (m) | Strava max (m) | P95 pente tablier | écart max | RMS | RMS vs interp. lin. | RMS vs MNT | Strava suit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1.03–4.67 | 3645 | D 213, Pont de Saint-Nazaire | 65.8 | 63.7 | 62.1 | 5.9 % | -2.19 | 1.10 | 30.16 | 35.1 | tablier |
| 5.88–5.96 | 85 | way 27013264 | 5.3 | 6.9 | 6.4 | 0.5 % | -0.46 | 0.41 | 0.32 | 4.1 | interpolation lineaire (tablier ~ rectiligne : tablier et interpolation indiscernables) |
| 9.95–9.98 | 35 | Pont écluse sud aval | 6.1 | 5.0 | 5.5 | 4.6 % | +1.55 | 0.94 | 1.28 | 6.1 | tablier (tablier ~ rectiligne : tablier et interpolation indiscernables) |

*Ponts OSM non retenus par la détection LiDAR* (petits ouvrages, tablier bas, ou franchissements au-dessus de la trace) :

- km 5.52–5.54 (30 m) D 213 : MNH max 4.1 m, Strava − MNT max -0.1 m
- km 8.79–8.85 (70 m) Avenue de la Prise-d'Eau : MNH max 12.7 m, Strava − MNT max +13.1 m
- km 9.26–9.29 (35 m) Pont Tournant : MNH max 1.9 m, Strava − MNT max +2.2 m
- km 9.66–9.71 (55 m) Pont basculant : MNH max 13.7 m, Strava − MNT max +6.4 m

*Autres runs MNH > 5 m lisses mais rejetés* (passages sous un ouvrage, surplombs) : km 9.22 (35 m)

*Canopée / bâti (MNS rugueux)* : 3 tronçons, 95 m au total — exclus des ponts, chaussée = MNT.

## test2 — 24.6 km, 689 points GPX (pas médian 26 m, max 100 m)

- Offset datum Strava − MNT : **+0.29 m** (IQR -0.24 / +0.97, n = 3268 points dégagés)
- Hors ouvrages (après offset) : biais +0.10 m, RMSE 0.80 m, |écart| P95 1.42 m ; sur terrain dégagé seul : biais +0.04, RMSE 0.78 m
- Pente max sur 50 m, à > 100 m de tout ouvrage : Strava 8.2 % (P99 2.7) ; MNT lissé 45 m 6.8 % (P99 3.3). Sur tout le parcours : Strava 8.2 %, chaussée lissée 7.5 %

| D+ (m) | h0 brut | h2 brut | h3 brut | h0 MM45 | h2 MM45 | h3 MM45 |
|---|---|---|---|---|---|---|
| Strava | 105 | 54 | 40 | 98 | 47 | 31 |
| MNT brut | 257 | 135 | 112 | 192 | 105 | 82 |
| MNS brut | 1920 | 1479 | 1267 | 471 | 258 | 199 |
| Chaussée estimée | 178 | 79 | 50 | 125 | 58 | 37 |

**Ponts détectés**

| km | L (m) | nom (OSM) | canyon max (m) | tablier max (m) | Strava max (m) | P95 pente tablier | écart max | RMS | RMS vs interp. lin. | RMS vs MNT | Strava suit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.62–1.01 | 390 | Pont de Bellevue | 10.5 | 13.8 | 12.9 | 8.6 % | -1.82 | 0.96 | 0.73 | 8.2 | interpolation lineaire |
| 1.43–1.54 | 125 | way 362043805 | 7.3 | 9.6 | 9.1 | 8.2 % | +1.51 | 0.92 | 0.55 | 5.8 | interpolation lineaire (tablier ~ rectiligne : tablier et interpolation indiscernables) |
| 6.93–7.22 | 295 | Pont Léopold-Sédar-Senghor | 10.5 | 12.1 | 11.1 | 4.0 % | -1.27 | 0.99 | 1.73 | 8.0 | tablier |
| 8.41–8.62 | 210 | Pont Éric Tabarly | 9.8 | 11.4 | 11.2 | 3.0 % | -0.54 | 0.35 | 0.75 | 8.6 | tablier |
| 9.30–9.51 | 205 | Pont Willy-Brandt | 10.6 | 12.1 | 11.1 | 4.1 % | -1.11 | 0.96 | 0.95 | 8.5 | interpolation lineaire |
| 10.73–11.06 | 335 | Pont Georges Clemenceau | 11.5 | 13.0 | 13.7 | 1.0 % | +0.84 | 0.64 | 1.28 | 11.1 | tablier (tablier ~ rectiligne : tablier et interpolation indiscernables) |
| 12.06–12.30 | 240 | Pont de Pirmil | 11.5 | 12.8 | 12.1 | 3.6 % | -0.72 | 0.49 | 1.32 | 9.9 | tablier |
| 14.03–14.26 | 235 | Pont Aristide Briand | 11.4 | 12.9 | 13.6 | 1.5 % | +0.79 | 0.59 | 1.30 | 10.2 | tablier |
| 15.23–15.38 | 155 | Pont Général Audibert | 10.3 | 11.6 | 11.4 | 5.4 % | +0.62 | 0.33 | 1.65 | 8.7 | tablier |
| 16.91–17.16 | 255 | way 30807706 | 9.0 | 10.8 | 10.1 | 0.9 % | -0.84 | 0.22 | 0.21 | 8.4 | interpolation lineaire (tablier ~ rectiligne : tablier et interpolation indiscernables) |
| 18.33–18.61 | 290 | Boulevard Victor Schœlcher, Pont des Trois Continents | 10.9 | 13.3 | 12.0 | 5.2 % | -1.52 | 1.26 | 2.04 | 7.6 | tablier |
| 19.89–20.05 | 155 | way 1287936957, way 1287936958, way 1418371535 | 9.1 | 11.4 | 10.9 | 6.2 % | -1.00 | 0.57 | 1.43 | 6.9 | tablier |
| 20.95–21.14 | 200 | Pont Haudaudine | 10.1 | 12.5 | 12.1 | 6.0 % | -1.00 | 0.61 | 2.17 | 8.0 | tablier |
| 22.16–22.30 | 150 | Pont Général Audibert | 8.0 | 9.3 | 9.4 | 2.4 % | +0.77 | 0.40 | 1.03 | 7.4 | tablier |
| 23.57–23.61 | 55 | Pont de la Rotonde | 6.3 | 14.2 | 14.0 | 4.5 % | -1.19 | 0.70 | 0.53 | 5.1 | interpolation lineaire (tablier ~ rectiligne : tablier et interpolation indiscernables) |
| 24.29–24.41 | 120 | way 410671587 | 8.1 | 12.9 | 11.7 | 4.7 % | -1.20 | 0.94 | 0.70 | 5.5 | interpolation lineaire (tablier ~ rectiligne : tablier et interpolation indiscernables) |

*Ponts OSM non retenus par la détection LiDAR* (petits ouvrages, tablier bas, ou franchissements au-dessus de la trace) :

- km 1.22–1.25 (40 m) Pont de Bellevue, Périphérique Extérieur - Pont de Bellevue, Périphérique Intérieur - Pont de Bellevue : MNH max 5.9 m, Strava − MNT max -0.7 m
- km 3.15–3.19 (50 m) way 715090406 : MNH max 5.4 m, Strava − MNT max +4.2 m
- km 6.17–6.18 (15 m) Passerelle Porthcawl : MNH max 0.0 m, Strava − MNT max -0.2 m
- km 11.14–11.22 (80 m) Boulevard Émile Gabory, way 1360125047, way 735179993 : MNH max 1.4 m, Strava − MNT max +1.7 m
- km 14.55–14.59 (40 m) Busway Ligne 4, Pont Aristide Briand, way 1489503872, way 1489503873 : MNH max 5.6 m, Strava − MNT max +0.3 m
- km 15.12–15.13 (15 m) way 1512061831 : MNH max 0.0 m, Strava − MNT max +0.5 m

*Autres runs MNH > 5 m lisses mais rejetés* (passages sous un ouvrage, surplombs) : km 14.56 (25 m, Busway Ligne 4)

*Canopée / bâti (MNS rugueux)* : 21 tronçons, 2200 m au total — exclus des ponts, chaussée = MNT.

## test3 — 10.1 km, 185 points GPX (pas médian 60 m, max 99 m)

- Offset datum Strava − MNT : **+0.52 m** (IQR -0.13 / +0.88, n = 1012 points dégagés)
- Hors ouvrages (après offset) : biais +0.05 m, RMSE 0.75 m, |écart| P95 1.61 m ; sur terrain dégagé seul : biais -0.13, RMSE 0.77 m
- Pente max sur 50 m, à > 100 m de tout ouvrage : Strava 5.1 % (P99 3.6) ; MNT lissé 45 m 5.4 % (P99 4.2). Sur tout le parcours : Strava 5.1 %, chaussée lissée 5.4 %

| D+ (m) | h0 brut | h2 brut | h3 brut | h0 MM45 | h2 MM45 | h3 MM45 |
|---|---|---|---|---|---|---|
| Strava | 32 | 12 | 12 | 30 | 12 | 9 |
| MNT brut | 225 | 29 | 29 | 62 | 21 | 16 |
| MNS brut | 1142 | 758 | 647 | 207 | 87 | 73 |
| Chaussée estimée | 216 | 25 | 12 | 54 | 15 | 9 |

**Ponts détectés**

| km | L (m) | nom (OSM) | canyon max (m) | tablier max (m) | Strava max (m) | P95 pente tablier | écart max | RMS | RMS vs interp. lin. | RMS vs MNT | Strava suit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 0.42–1.26 | 840 | Pont de Mauves sur Loire, Pont de Mauves-sur-Loire | 9.0 | 13.6 | 11.4 | 2.7 % | -2.28 | 1.77 | 1.41 | 4.8 | interpolation lineaire |

*Ponts OSM non retenus par la détection LiDAR* (petits ouvrages, tablier bas, ou franchissements au-dessus de la trace) :

- km 1.61–1.65 (35 m) Pont de Mauves-sur-Loire : MNH max 6.1 m, Strava − MNT max +0.2 m

*Canopée / bâti (MNS rugueux)* : 6 tronçons, 1405 m au total — exclus des ponts, chaussée = MNT.

## test4 — 111.2 km, 2924 points GPX (pas médian 31 m, max 100 m)

- Offset datum Strava − MNT : **+1.48 m** (IQR +0.21 / +2.90, n = 17750 points dégagés)
- Hors ouvrages (après offset) : biais -0.12 m, RMSE 2.31 m, |écart| P95 4.58 m ; sur terrain dégagé seul : biais -0.07, RMSE 2.28 m
- Pente max sur 50 m, à > 100 m de tout ouvrage : Strava 13.9 % (P99 10.4) ; MNT lissé 45 m 11.2 % (P99 8.9). Sur tout le parcours : Strava 220.5 %, chaussée lissée 11.2 %

| D+ (m) | h0 brut | h2 brut | h3 brut | h0 MM45 | h2 MM45 | h3 MM45 |
|---|---|---|---|---|---|---|
| Strava | 2551 | 2503 | 2483 | 2543 | 2493 | 2479 |
| MNT brut | 3639 | 3399 | 3338 | 2950 | 2865 | 2837 |
| MNS brut | 10196 | 8705 | 7862 | 3984 | 3440 | 3303 |
| Chaussée estimée | 2662 | 2468 | 2414 | 2452 | 2384 | 2362 |

**Ponts détectés**

| km | L (m) | nom (OSM) | canyon max (m) | tablier max (m) | Strava max (m) | P95 pente tablier | écart max | RMS | RMS vs interp. lin. | RMS vs MNT | Strava suit |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 7.66–7.68 | 25 | Le Pont d'Echevis | 8.7 | 311.0 | 316.0 | n/a (trop court) | +5.09 | 5.06 | 5.03 | 11.3 | interpolation lineaire (tablier ~ rectiligne : tablier et interpolation indiscernables) |

*Ponts OSM non retenus par la détection LiDAR* (petits ouvrages, tablier bas, ou franchissements au-dessus de la trace) :

- km 15.57–15.60 (25 m) D 518 : MNH max 4.1 m, Strava − MNT max +11.5 m
- km 29.67–29.70 (40 m) D 518 : MNH max 2.7 m, Strava − MNT max +3.5 m
- km 33.02–33.06 (40 m) D 518 : MNH max 11.8 m, Strava − MNT max +9.2 m
- km 78.66–78.70 (50 m) D 70 : MNH max 15.8 m, Strava − MNT max +8.0 m

*Autres runs MNH > 5 m lisses mais rejetés* (passages sous un ouvrage, surplombs) : km 14.06 (100 m); km 21.88 (15 m); km 42.95 (50 m); km 65.75 (15 m); km 68.52 (215 m); km 94.25 (25 m); km 109.39 (15 m)

*Canopée / bâti (MNS rugueux)* : 45 tronçons, 14160 m au total — exclus des ponts, chaussée = MNT.

**Tunnels**

| km | L (m) | nom (OSM) | MNT au-dessus de la chaussée (max) | pente portail-portail | écart Strava max | RMS | pente Strava max (50 m) | D+ Strava en trop | verdict |
|---|---|---|---|---|---|---|---|---|---|
| 5.11–5.58 | 475 | Route des Petits Goulets | 70 m | +0.9 % | +4.9 m | 3.4 m | 4 % | +2 m | traité (Strava passe dessous) |
| 13.60–15.36 | 1765 | Tunnel des Grands Goulets | 223 m | +4.6 % | +190.1 m | 120.3 m | 220 % | +112 m | **NON détecté par Strava** |
| 65.74–65.94 | 210 | Tunnel des Moines | 62 m | -7.8 % | -1.1 m | 0.5 m | 10 % | +0 m | traité (Strava passe dessous) |
| 68.15–68.64 | 490 | Cirque de Combe Laval | 76 m | +0.5 % | -1.8 m | 1.0 m | 1 % | -1 m | traité (Strava passe dessous) |

*Encorbellement / falaise* (pente transversale du MNT > 100 % sur ±10 m, hors tunnels) : 11 tronçons, 720 m ; |MNT − Strava| RMS 2.1 m contre 2.3 m ailleurs ; les plus marqués : km 68.64–68.67 (9 m); km 67.98–68.14 (7 m); km 66.05–66.19 (5 m); km 7.54–7.58 (4 m)
