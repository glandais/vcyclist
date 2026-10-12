import os, json
HERE = os.path.dirname(os.path.abspath(__file__))
R = json.load(open(os.path.join(HERE, 'results.json')))
L = []
w = L.append
w('# Altitude des itinéraires Strava vs IGN LiDAR HD (ponts et tunnels)\n')
w('Méthode : GPX rééchantillonné à 5 m (lat/lon et altitude Strava interpolés linéairement) ; MNT et MNS LiDAR HD '
  '(WMS Géoplateforme, BIL float32, pavage de 0,008° × 256 px ≈ 3,5 × 2,4 m/px, interpolation bilinéaire) ; '
  'MNH = MNS − MNT (vérifié identique à la couche MNH à 1e-5 m près). Offset Strava − MNT : médiane sur MNH < 0,5 m. '
  'Ponts : runs MNH > 5 m sur ≥ 3 points, fusion des trous < 300 m, MNS localement lisse (RMS résidu/médiane 25 m < 0,8 m), '
  'pente du MNS filtré (P95, 25 m) ≤ 10 %, MNT sous l\'ouvrage > 3 m sous les deux bouts ; bornes étendues jusqu\'à MNH ≤ 1 m. '
  'Chaussée estimée = MNS médiane 25 m sur les tabliers (MNH > 1 m), interpolation linéaire portail-à-portail dans les tunnels, MNT ailleurs. '
  'Tunnels : MNT > Strava + 15 m, ou |pente Strava sur 100 m| > 15 % ; bornes recalées sur les portails OSM (Overpass, requêtes bbox) quand un tunnel OSM chevauche. '
  'Noms : OSM (way `bridge`/`tunnel` à < 10 m de la trace). D+ : hystérésis 0/2/3 m, brut ou après moyenne mobile 45 m.\n')
w('Figures : `fig/<fichier>_profil.png` (profil complet + écart Strava − chaussée), `fig/<fichier>_pontN.png`, `fig/<fichier>_tunnelN.png`.\n')

for k, r in R.items():
    w(f'\n## {k} — {r["length_km"]:.1f} km, {r["n_gpx"]} points GPX (pas médian {r["gpx_gap_median"]:.0f} m, max {r["gpx_gap_max"]:.0f} m)\n')
    w(f'- Offset datum Strava − MNT : **{r["offset"]:+.2f} m** (IQR {r["offset_iqr"][0]:+.2f} / {r["offset_iqr"][1]:+.2f}, n = {r["offset_n"]} points dégagés)')
    so, sp = r['stats_off_struct'], r['stats_open']
    w(f'- Hors ouvrages (après offset) : biais {so["bias"]:+.2f} m, RMSE {so["rmse"]:.2f} m, |écart| P95 {so["p95abs"]:.2f} m ; '
      f'sur terrain dégagé seul : biais {sp["bias"]:+.2f}, RMSE {sp["rmse"]:.2f} m')
    s = r['slopes']
    w(f'- Pente max sur 50 m, à > 100 m de tout ouvrage : Strava {s["strava_offstruct"][0]:.1f} % (P99 {s["strava_offstruct"][1]:.1f}) ; '
      f'MNT lissé 45 m {s["mnt_ma45_offstruct"][0]:.1f} % (P99 {s["mnt_ma45_offstruct"][1]:.1f}). '
      f'Sur tout le parcours : Strava {s["strava_all"][0]:.1f} %, chaussée lissée {s["road_ma45_all"][0]:.1f} %')
    w('\n| D+ (m) | h0 brut | h2 brut | h3 brut | h0 MM45 | h2 MM45 | h3 MM45 |\n|---|---|---|---|---|---|---|')
    for lbl, nm in [('strava', 'Strava'), ('mnt', 'MNT brut'), ('mns', 'MNS brut'), ('chaussee', 'Chaussée estimée')]:
        d = r['dplus'][lbl]
        w(f'| {nm} | {d["raw_h0"]:.0f} | {d["raw_h2"]:.0f} | {d["raw_h3"]:.0f} | {d["ma45_h0"]:.0f} | {d["ma45_h2"]:.0f} | {d["ma45_h3"]:.0f} |')
    if r['bridges']:
        w('\n**Ponts détectés**\n')
        w('| km | L (m) | nom (OSM) | canyon max (m) | tablier max (m) | Strava max (m) | P95 pente tablier | écart max | RMS | RMS vs interp. lin. | RMS vs MNT | Strava suit |')
        w('|---|---|---|---|---|---|---|---|---|---|---|---|')
        for b in r['bridges']:
            w(f'| {b["km0"]:.2f}–{b["km1"]:.2f} | {b["L"]:.0f} | {", ".join(b["osm"]) or "?"} | {b["canyon"]:.1f} | {b["road_max"]:.1f} | {b["strava_max"]:.1f} | '
              f'{b["slope95"]:.1f} % | {b["err_max"]:+.2f} | {b["err_rms"]:.2f} | {b["rms_vs_lin"]:.2f} | {b["rms_vs_mnt"]:.1f} | {b["behaviour"]} |')
    if r['osm_missed']:
        w('\n*Ponts OSM non retenus par la détection LiDAR* (petits ouvrages, tablier bas, ou franchissements au-dessus de la trace) :\n')
        for b in r['osm_missed']:
            w(f'- km {b["km0"]:.2f}–{b["km1"]:.2f} ({b["L"]:.0f} m) {", ".join(b["name"])} : MNH max {b["mnh_max"]:.1f} m, Strava − MNT max {b["strava_minus_mnt_max"]:+.1f} m')
    if r['others']:
        w('\n*Autres runs MNH > 5 m lisses mais rejetés* (passages sous un ouvrage, surplombs) : ' +
          '; '.join(f'km {o["km0"]:.2f} ({o["L"]:.0f} m{", " + o["osm"][0] if o["osm"] else ""})' for o in r['others']))
    w(f'\n*Canopée / bâti (MNS rugueux)* : {r["rough"]["n"]} tronçons, {r["rough"]["length"]:.0f} m au total — exclus des ponts, chaussée = MNT.')
    if r['tunnels']:
        w('\n**Tunnels**\n')
        w('| km | L (m) | nom (OSM) | MNT au-dessus de la chaussée (max) | pente portail-portail | écart Strava max | RMS | pente Strava max (50 m) | D+ Strava en trop | verdict |')
        w('|---|---|---|---|---|---|---|---|---|---|')
        for t in r['tunnels']:
            w(f'| {t["km0"]:.2f}–{t["km1"]:.2f} | {t["L"]:.0f} | {", ".join(t["osm"]) or "?"} | {t["mnt_above_max"]:.0f} m | {t["portal_slope"]:+.1f} % | '
              f'{t["err_max"]:+.1f} m | {t["err_rms"]:.1f} m | {t["strava_slope50_max"]:.0f} % | {t["strava_dplus_extra"]:+.0f} m | '
              f'{"**NON détecté par Strava**" if t["undetected"] else "traité (Strava passe dessous)"} |')
    if r.get('balcony'):
        b = r['balcony']
        w(f'\n*Encorbellement / falaise* (pente transversale du MNT > 100 % sur ±10 m, hors tunnels) : {b["n_runs"]} tronçons, {b["length"]:.0f} m ; '
          f'|MNT − Strava| RMS {b["mnt_vs_strava_rms_cliff"]:.1f} m contre {b["mnt_vs_strava_rms_rest"]:.1f} m ailleurs ; '
          'les plus marqués : ' + '; '.join(f'km {x["km0"]:.2f}–{x["km1"]:.2f} ({x["dmax"]:.0f} m)' for x in b['top'][:4]))
open(os.path.join(HERE, 'rapport_tables.md'), 'w').write('\n'.join(L) + '\n')
print('\n'.join(L))
