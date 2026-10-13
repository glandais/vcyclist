"""E2 bis: negative controls and held-out positives for the deck bench.

Builds, from OpenStreetMap only, one routed path per site of SITES and a per-station kind:

  G ground | O overhead structure | L parallel elevated bridge within 20 m
  X own structure on a negative, or a tunnel/culvert crossing under the route (excluded)
  x 35 m abutment margin (excluded) | D own bridge deck on a held-out positive (must be found)

Outputs (committed): ../controls/<id>.gpx, truth/controls/<id>.csv, truth/controls/index.csv,
truth/controls/spans.csv. Raw Overpass answers are cached in osm/controls/, which is gitignored: a
rerun with that local cache is offline and deterministic, but a fresh clone queries current OSM and
may not reproduce the committed truth (ElevationBenchProbeTest's CONTROL_GUARDS is the reference).
The script never calls data.geopf.fr.

  python3 -I controls.py            # every site, writes everything
  python3 -I controls.py n04 p08    # dry run on a few sites (prefix match), writes nothing
"""
import sys, os, re, json, math, time, heapq, urllib.request, urllib.error, urllib.parse
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OSM_DIR = os.path.join(HERE, 'osm', 'controls')
GPX_DIR = os.path.join(ROOT, 'controls')
TRUTH_DIR = os.path.join(HERE, 'truth', 'controls')
STEP = 5.0
R_EARTH = 6371008.8
ENDPOINTS = ['https://overpass-api.de/api/interpreter', 'https://overpass.private.coffee/api/interpreter']
UA = 'vcyclist-bench/1.0'

ABUTMENT_STATIONS = 7      # x: 35 m around D/X (E2: abutment extension <= 7 stations on Python's 21 bridges)
LATERAL_M = 20.0           # L: pixel + bilinear + OSM centreline + half a parallel deck
LATERAL_HEADING_DEG = 25.0
LATERAL_MIN_STATIONS = 5   # 25 m
OVERHEAD_SLACK_M = 10.0    # O: +-(w/2 + 10 m) around a crossing
TOLL_M = 25.0
SNAP_M = 60.0
END_MARGIN_M = 150.0       # held-out D spans: ground before and after, so the route ends never clamp the canyon
APPROACH_M = 400.0         # holdout2: road kept beyond each end of the deck (E2 quinquies, rule 4)
POSITIVE_ROLES = {'held-out-positive', 'holdout2'}   # holdout2: judged only by deckHoldoutJudge (DECK_JUDGE=1)
# E4 tunnel series (--tunnels): its own output directories, so the deck bench's readers never see it.
TUNNEL_GPX_DIR = os.path.join(ROOT, 'controls', 'tunnels')
TUNNEL_TRUTH_DIR = os.path.join(HERE, 'truth', 'tunnels')
TUNNEL_POSITIVE_ROLES = {'tunnel-tuning', 'tunnel-holdout'}   # tunnel-holdout*: judged only by a *TunnelJudge* test (TUNNEL_JUDGE=1)
TUNNEL_ROLES = TUNNEL_POSITIVE_ROLES | {'tunnel-negative', 'tunnel-holdout-negative'}
GROUND_M = 150.0           # tunnel series: ground beyond every group of counted N spans, and between two groups
UTURN_DEG = 120.0          # no heading change above this over 2 x 15 m
UTURN_NEAR_M = 15.0        # no station within this of another one more than UTURN_ALONG_M away along the route
UTURN_ALONG_M = 60.0

MOTOR = {'motorway', 'trunk'}
MOTOR_LINKS = {'motorway_link', 'trunk_link'}
MAJOR = {'primary', 'primary_link', 'secondary', 'secondary_link', 'tertiary', 'tertiary_link'}
MINOR = {'unclassified', 'residential', 'living_street'}
STREETS = MAJOR | MINOR                       # residential ... primary
CARS = MOTOR | MOTOR_LINKS | STREETS | {'service'}
CYCLE = {'cycleway', 'track', 'path'}
RAILWAYS = {'rail', 'subway', 'light_rail', 'tram', 'narrow_gauge', 'monorail', 'funicular', 'preserved', 'disused'}
DEFAULT_WIDTH = [({'motorway', 'motorway_link'}, 30.0), ({'trunk', 'trunk_link'}, 25.0), ({'primary', 'primary_link'}, 20.0),
                 ({'secondary', 'secondary_link', 'tertiary', 'tertiary_link'}, 15.0)]

# ── Sites ────────────────────────────────────────────────────────────────────────────────────
# checks: (kind, args...) evaluated by run_checks; a failed check moves the waypoint, never loosens it.

SITES = [
    dict(id='n01-paris-metro6', role='negative', category='elevated-rail-beside',
         place='Paris 15e bd de Grenelle -> bd Garibaldi beside the aerial Metro 6',
         waypoints=[(48.85388, 2.28917), (48.84985, 2.29501), (48.84792, 2.30120), (48.84646, 2.30609), (48.84537, 2.31098)],
         classes=MAJOR, selector=dict(name=r'Grenelle|Garibaldi'),
         checks=[('name_frac', r'Grenelle|Garibaldi', 0.7), ('min_len', 1000), ('near_rail', r'^6$|[Ll]igne 6|M[ée]tro 6', 30.0)]),
    dict(id='n02-nantes-chevire-under', role='negative', category='under-viaduct-approach',
         place='Nantes / Reze local road under the Pont de Chevire approach (N 844)',
         derived='chevire_under', bbox=(47.17, -1.66, 47.22, -1.58), osm_key='chevire',
         classes=STREETS | {'cycleway'},
         checks=[('overhead', r'N 844', 1)]),
    dict(id='n03-millau-d992', role='negative', category='valley-floor-under-high-viaduct',
         place='Millau D 992 along the Tarn under the Viaduc de Millau (A 75)',
         waypoints=[(44.08128, 3.04007), (44.07981, 3.02684), (44.07856, 3.01078)],
         classes=CARS, selector=dict(ref=r'\bD ?992\b'),
         checks=[('overhead', r'A ?75', 1)]),
    dict(id='n04-nantes-periph-nord', role='negative', category='motorway-cutting-under-overpasses',
         place='Nantes peripherique nord (N 844) Porte de la Chapelle -> Porte de Carquefou one carriageway',
         waypoints=[(47.25310, -1.51503), (47.25642, -1.54012), (47.26559, -1.55968)],
         classes=MOTOR, selector=dict(ref=r'N 844'),
         checks=[('o_spans', 3), ('x_spans', 1)]),
    dict(id='n05-canal-midi-platanes', role='negative', category='plane-tree-towpath',
         place='Canal du Midi towpath Ramonville -> Castanet (Toulouse)',
         waypoints=[(43.54460, 1.49006), (43.52407, 1.51876)],
         classes=CYCLE | {'service'},
         checks=[('near_water_frac', r'Canal du Midi', 'canal', 30.0, 0.7)]),
    dict(id='n06-fontainebleau-route-ronde', role='negative', category='forest-road',
         place='Foret de Fontainebleau Route Ronde (D 301)',
         waypoints=[(48.42158, 2.65269), (48.39656, 2.63187)],
         classes=CARS, selector=dict(ref=r'\bD ?301\b', name=r'Route Ronde'),
         checks=[('selector_frac', 0.8)]),
    dict(id='n07-landes-velodyssee', role='negative', category='pine-forest-cycleway',
         place='Piste de la Cote cycleway north of Saint-Girons-Plage (Landes)',
         waypoints=[(43.97173, -1.34558), (43.98550, -1.33827)],
         classes=CYCLE,
         checks=[('class_frac', {'cycleway', 'track'}, 0.8)]),
    dict(id='n08-nantes-centre', role='negative', category='urban-street-covered-carriageway',
         place='Nantes centre rue de Strasbourg and cours John Kennedy then quai Andre Morice westbound through its tunnel and covered section',
         # Entered from the east: Quai Magellan reaches the westbound quai only by a U-turn.
         waypoints=[(47.22001, -1.55467), (47.21455, -1.55007), (47.21034, -1.54418), (47.20920, -1.54757),
                    (47.20896, -1.55451), (47.20948, -1.55879)],
         bbox=(47.20503, -1.56408, 47.22363, -1.54000),
         classes=STREETS,
         checks=[('o_spans', 1), ('class_frac', STREETS - {'living_street'}, 0.6)]),
    dict(id='n09-peage-lancon', role='negative', category='toll-plaza-canopy',
         place='A7 barriere de peage de Lancon-Provence one carriageway',
         # past the covered way 184840286 and the service-area footbridge (way 1409740514)
         waypoints=[(43.59650, 5.16426), (43.59401, 5.17245), (43.58964, 5.18991), (43.58875, 5.19448)],
         classes={'motorway'},
         checks=[('o_spans', 1)]),
    dict(id='n10-loire-levee', role='negative', category='road-on-levee',
         place='Levee de la Loire D 952 west of Saint-Martin-de-la-Place',
         waypoints=[(47.31663, -0.15268), (47.33296, -0.18947)],
         classes=CARS, selector=dict(ref=r'\bD ?952\b'),
         checks=[('selector_frac', 0.8)]),
    dict(id='n11-morlaix-viaduc', role='negative', category='town-under-stone-rail-viaduct',
         place='Morlaix centre streets under and beside the railway viaduct',
         # third waypoint on the west branch of Place des Otages: the link road doubled back (130 deg)
         waypoints=[(48.58424, -3.83505), (48.58133, -3.83198), (48.57811, -3.82909), (48.57727, -3.82659), (48.57550, -3.82170)],
         bbox=(48.57188, -3.84048, 48.58786, -3.81627),
         classes=STREETS,
         checks=[('overhead_rail', 1)]),
    dict(id='n12-toulouse-brienne', role='negative', category='urban-plane-tree-alley',
         place='Toulouse allees de Brienne along the canal de Brienne',
         waypoints=[(43.61047, 1.41823), (43.60756, 1.42658), (43.60474, 1.43410)],
         classes=STREETS | {'cycleway'}, selector=dict(name=r'Brienne'),
         checks=[('name_frac', r'Brienne', 0.7)]),
    # E2 quater: the one wooded ravine found by a scan of 34 towns (~3 800 km of OSM ways, vcyclist's own
    # MNS/MNT z15 sampling; scratch, not committed) where DEFAULT merges two kept tall runs across an
    # MNT canyon off any OSM bridge. The route starts where the scanned way chain starts: started 120 m
    # earlier (stations shifted by 0.4 m), the same canopy is one ROUGH group and nothing is measured.
    dict(id='n13-morlaix-pennele', role='negative', category='ravine-merged-tall-runs',
         place='Morlaix path along the wooded Vallee de la Pennele',
         waypoints=[(48.58182, -3.84616), (48.58308, -3.84581), (48.58314, -3.84531), (48.58480, -3.84199),
                    (48.58536, -3.84111), (48.58649, -3.83803)],
         # one 15 m flight of steps (way 183631737) joins the two named path sections
         classes=CYCLE | STREETS | {'service', 'footway', 'steps'},
         checks=[('name_frac', r'Penn[ée]l[ée]', 0.6)]),
    # E2 quinquies negatives, same date and same rule-before-measure: they join deckControls' acceptance.
    # The urban A 35 through Strasbourg is mapped M 35 since its transfer to the Eurometropole.
    # The road dipping under a rail bridge is not built: Dijon has no LiDAR HD MNT, and Reims, its
    # same-day replacement by the same rule, fails the U-turn check (task doc, E2 quinquies amendments).
    dict(id='n14-strasbourg-under-a35', role='negative', category='road-under-motorway',
         place='Strasbourg (Bas-Rhin) longest named street passing under the urban A 35 (now M 35)',
         derived='under', pick=dict(over_ref=r'^[AM] ?35$'), bbox=(48.560, 7.700, 48.600, 7.745),
         classes=STREETS, checks=[('overhead', r'^[AM] ?35$', 1)]),
    dict(id='n15-lyon-berges-rhone', role='negative', category='quay-under-bridges',
         place='Lyon left-bank lower quay of the Rhone (Voie Verte des Berges du Rhone) under Pont de la Guillotiere Pont Wilson and Pont Lafayette',
         waypoints=[(45.75266, 4.83677), (45.75958, 4.84061), (45.76564, 4.84101)],
         classes=CYCLE | {'pedestrian', 'footway'}, selector=dict(name=r'Berges du Rh[oô]ne|Karen Blixen'),
         checks=[('o_spans', 2), ('selector_frac', 0.7)]),
    dict(id='n16-cousin-d427', role='negative', category='wooded-valley-road',
         place='Vallee du Cousin D 427 west of Avallon (Yonne)',
         waypoints=[(47.48397, 3.88437), (47.48554, 3.87961), (47.48993, 3.86537)],
         classes=CARS, selector=dict(ref=r'\bD ?427\b'),
         checks=[('selector_frac', 0.8)]),
    dict(id='p01-chevire', role='held-out-positive', category='long-high-motorway-bridge',
         place='Pont de Chevire Nantes N 844',
         derived='chevire_bridge', bbox=(47.17, -1.66, 47.22, -1.58), osm_key='chevire',
         classes=MOTOR,
         checks=[('d_span_min', 1000), ('d_name', r'Chevir')]),
    dict(id='p02-thouare', role='held-out-positive', category='truss-bridge',
         place='Pont de Thouare-sur-Loire to Saint-Julien-de-Concelles',
         waypoints=[(47.26706, -1.44130), (47.26228, -1.43545), (47.25733, -1.42634), (47.25686, -1.42356)],
         bbox=(47.25371, -1.44659, 47.27068, -1.42105),
         classes=STREETS | {'cycleway'},
         checks=[('d_span_min', 200), ('d_over_water', r'Loire')]),
    dict(id='p03-ancenis', role='held-out-positive', category='suspension-bridge',
         place='Pont d Ancenis D 723',
         waypoints=[(47.36720, -1.17346), (47.35545, -1.17256)],
         classes=STREETS | {'cycleway'},
         checks=[('d_span_min', 300)]),
    dict(id='p04-langeais', role='held-out-positive', category='narrow-suspension-bridge',
         place='Pont de Langeais D 57',
         # from the D 952 on the levee: the cycleway ramp at the north end spirals (teardrop)
         waypoints=[(47.32161, 0.40550), (47.31497, 0.40824)],
         bbox=(47.31135, 0.39724, 47.32536, 0.41354),
         classes=STREETS | {'cycleway'},
         checks=[('d_span_min', 200)]),
    dict(id='p05-saumur', role='held-out-positive', category='stone-arches-across-island',
         place='Saumur Pont Cessart and Pont des Cadets',
         waypoints=[(47.25928, -0.07278), (47.26137, -0.07661), (47.26444, -0.07312), (47.26715, -0.06996), (47.26875, -0.06813)],
         bbox=(47.25566, -0.08190, 47.27077, -0.06467),
         classes=STREETS | {'cycleway'},
         checks=[('d_spans', 2)]),
    dict(id='p06-normandie', role='held-out-positive', category='long-cable-stayed-bridge',
         place='Pont de Normandie (N 1029) Le Havre to Honfleur one carriageway',
         waypoints=[(49.45352, 0.27149), (49.41377, 0.27083)],
         classes=MOTOR, selector=dict(ref=r'N 1029'),
         checks=[('d_span_min', 1500)]),
    dict(id='p07-millau-a75', role='held-out-positive', category='very-high-valley-viaduct',
         place='Viaduc de Millau A 75 one carriageway',
         waypoints=[(44.10002, 3.02209), (44.06847, 3.02464), (44.06171, 3.02779)],
         classes=MOTOR, selector=dict(ref=r'A 75'),
         checks=[('d_span_min', 2000)]),
    dict(id='p08-periph-overpass', role='held-out-positive', category='narrow-short-overpass',
         place='Nantes local road over the peripherique nord (selected from n04)',
         derived='periph_overpass', osm_key='n04-nantes-periph-nord',
         classes=STREETS | {'cycleway'},
         checks=[('d_span_range', 30, 80)]),
    # ── E2 quinquies (2026-10-13): second held-out series, chosen by the rule written in
    # elevation-bridge-decks.md *before* any detector run; role holdout2 keeps them out of every
    # calibration test (only ElevationBenchProbeTest.deckHoldoutJudge reads them, DECK_JUDGE=1).
    # A deck is picked by name or by a "longest bridge that ..." rule, never by hand, and routed
    # with ~APPROACH_M of road on ground beyond each end.
    dict(id='p09-cubzac', role='holdout2', category='long-truss-bridge',
         place='Pont de Cubzac D 1010 over the Dordogne (Gironde)',
         derived='bridge', pick=dict(name=r'^Pont de Cubzac$'), bbox=(44.950, -0.481, 44.973, -0.443),
         classes=STREETS, checks=[('d_span_min', 900), ('d_name', r'Cubzac')]),
    dict(id='p10-pont-saint-esprit', role='holdout2', category='long-masonry-arch-bridge',
         place='Pont du Saint-Esprit D 994 over the Rhone (Gard / Vaucluse)',
         derived='bridge', pick=dict(name=r'^Pont du Saint-Esprit$'), bbox=(44.250, 4.638, 44.270, 4.672),
         classes=STREETS, checks=[('d_span_min', 700), ('d_name', r'Saint-Esprit')]),
    dict(id='p11-sully', role='holdout2', category='long-suspension-bridge',
         place='Pont de Sully-sur-Loire D 948 (Loiret)',
         derived='bridge', pick=dict(name=r'^Pont de Sully-sur-Loire$'), bbox=(47.760, 2.362, 47.780, 2.385),
         classes=STREETS, checks=[('d_span_min', 350), ('d_name', r'Sully')]),
    dict(id='p12-tonnay-charente', role='holdout2', category='long-suspension-cycle-bridge',
         place='Pont suspendu de Tonnay-Charente over the Charente (Charente-Maritime) cycle and foot bridge',
         derived='bridge', pick=dict(name=r'^Pont Suspendu de Tonnay-Charente$'), bbox=(45.929, -0.898, 45.948, -0.876),
         classes=STREETS | CYCLE | {'footway'}, checks=[('d_span_min', 500), ('d_name', r'Tonnay')]),
    dict(id='p13-chalon-bourgogne', role='holdout2', category='long-cable-stayed-bridge',
         place='Pont de Bourgogne D 5A over the Saone Chalon-sur-Saone (Saone-et-Loire)',
         derived='bridge', pick=dict(name=r'^Pont de Bourgogne$'), bbox=(46.777, 4.861, 46.792, 4.879),
         classes=STREETS, checks=[('d_span_min', 300), ('d_name', r'Bourgogne')]),
    dict(id='p14-saint-cloud', role='holdout2', category='medium-girder-bridge',
         place='Pont de Saint-Cloud D 907 over the Seine (Hauts-de-Seine) one carriageway',
         derived='bridge', pick=dict(name=r'^Pont de Saint-Cloud$'), bbox=(48.835, 2.210, 48.848, 2.238),
         classes=STREETS, checks=[('d_span_min', 140), ('d_name', r'Saint-Cloud')]),
    dict(id='p15-blois', role='holdout2', category='medium-masonry-arch-bridge',
         place='Pont Jacques-Gabriel D 956B over the Loire Blois (Loir-et-Cher)',
         derived='bridge', pick=dict(name=r'^Pont Jacques[- ]Gabriel$'), bbox=(47.577, 1.326, 47.593, 1.349),
         classes=STREETS, checks=[('d_span_min', 250), ('d_name', r'Gabriel')]),
    # The rail-yard overpass cell stays empty: Le Mans has no street bridge over railway=rail in its
    # bbox, Lille (same-day replacement) no LiDAR HD MNT, Saint-Denis La Plaine no 25-150 m street
    # bridge over the rails either (task doc, E2 quinquies amendments).
    dict(id='p16-reims-a4', role='holdout2', category='short-overpass-over-motorway',
         place='Reims (Marne) longest street or cycleway bridge of 25-80 m over the A 4',
         derived='bridge', pick=dict(over_ref=r'^A ?4$', length=(25.0, 80.0)), bbox=(49.205, 4.040, 49.240, 4.110),
         classes=(STREETS - {'primary', 'primary_link'}) | {'cycleway'}, checks=[('d_span_range', 25, 80)]),
    dict(id='p17-strasbourg-ill', role='holdout2', category='short-river-bridge',
         place='Strasbourg centre (Bas-Rhin) longest street bridge of 20-60 m over the Ill',
         derived='bridge', pick=dict(over_water=r"^L'Ill( Canalis[ée])?$", length=(20.0, 60.0)), bbox=(48.574, 7.735, 48.588, 7.760),
         classes=STREETS, checks=[('d_span_range', 20, 60)]),
]


# ── Geometry ─────────────────────────────────────────────────────────────────────────────────

def hav(lat1, lon1, lat2, lon2):
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp = p2 - p1
    dl = np.radians(np.asarray(lon2) - np.asarray(lon1))
    h = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * R_EARTH * np.arcsin(np.sqrt(h))


class Proj:
    """Local equirectangular metres, as common.py."""

    def __init__(self, lat0):
        self.kx = math.cos(math.radians(lat0)) * 111320.0
        self.ky = 110540.0

    def xy(self, lat, lon):
        return np.asarray(lon, float) * self.kx, np.asarray(lat, float) * self.ky


def seg_dist(px, py, ax, ay, bx, by):
    """distance of points (px, py) to every segment; returns (dist [P, S], u [P, S])"""
    dx = bx - ax
    dy = by - ay
    l2 = np.maximum(dx * dx + dy * dy, 1e-9)
    u = np.clip(((px[:, None] - ax[None]) * dx[None] + (py[:, None] - ay[None]) * dy[None]) / l2[None], 0, 1)
    d = np.hypot(ax[None] + u * dx[None] - px[:, None], ay[None] + u * dy[None] - py[:, None])
    return d, u


def intersect(a, b, c, d):
    """proper intersection of segments ab and cd (2D tuples); returns (t on ab, u on cd) or None"""
    r = (b[0] - a[0], b[1] - a[1])
    s = (d[0] - c[0], d[1] - c[1])
    den = r[0] * s[1] - r[1] * s[0]
    if abs(den) < 1e-12:
        return None
    q = (c[0] - a[0], c[1] - a[1])
    t = (q[0] * s[1] - q[1] * s[0]) / den
    u = (q[0] * r[1] - q[1] * r[0]) / den
    if 1e-9 < t < 1 - 1e-9 and 1e-9 < u < 1 - 1e-9:
        return t, u
    return None


def in_polygon(px, py, gx, gy):
    inside = np.zeros(len(px), bool)
    n = len(gx)
    for i in range(n - 1):
        x1, y1, x2, y2 = gx[i], gy[i], gx[i + 1], gy[i + 1]
        cond = (y1 > py) != (y2 > py)
        with np.errstate(divide='ignore', invalid='ignore'):
            xi = x1 + (py - y1) * (x2 - x1) / (y2 - y1)
        inside ^= cond & (px < xi)
    return inside


# ── Overpass ─────────────────────────────────────────────────────────────────────────────────

_last = [0.0]


def overpass(query):
    for ep in ENDPOINTS:
        delay = 4.0
        for attempt in range(8):
            wait = _last[0] + 2.0 - time.time()
            if wait > 0:
                time.sleep(wait)
            try:
                req = urllib.request.Request(ep, data=urllib.parse.urlencode({'data': query}).encode(),
                                             headers={'User-Agent': UA})
                with urllib.request.urlopen(req, timeout=200) as r:
                    body = r.read()
                _last[0] = time.time()
                js = json.loads(body)
                remark = js.get('remark', '')
                if 'runtime error' in remark or 'timed out' in remark:
                    raise RuntimeError(remark)
                return js
            except urllib.error.HTTPError as ex:
                _last[0] = time.time()
                if ex.code not in (429, 500, 502, 503, 504):
                    raise
                print(f'  overpass {ep} HTTP {ex.code}, retry in {delay:.0f} s', flush=True)
            except (urllib.error.URLError, TimeoutError, ConnectionError, json.JSONDecodeError, RuntimeError) as ex:
                _last[0] = time.time()
                print(f'  overpass {ep} {type(ex).__name__}: {str(ex)[:120]}, retry in {delay:.0f} s', flush=True)
            time.sleep(delay)
            delay = min(delay * 2, 120)
    raise RuntimeError('every Overpass endpoint failed')


def bbox_of(points, margin_m):
    lats = [p[0] for p in points]
    lons = [p[1] for p in points]
    dlat = margin_m / 110540.0
    dlon = margin_m / (math.cos(math.radians(sum(lats) / len(lats))) * 111320.0)
    return (min(lats) - dlat, min(lons) - dlon, max(lats) + dlat, max(lons) + dlon)


def osm_for(site):
    key = site.get('osm_key', site['id'])
    path = os.path.join(OSM_DIR, key + '.json')
    owner = next((s for s in SITES + flat_tunnel_sites() if s['id'] == key), site)
    bb = owner.get('bbox') or bbox_of(owner['waypoints'], 400.0)
    b = ','.join(f'{v:.5f}' for v in bb)
    if os.path.exists(path):
        js = json.load(open(path))
        if js.get('vcyclist_bbox') == b:
            return Osm(js)
        print(f'  cached {key} has another bbox, refetching', flush=True)
    q = (f'[out:json][timeout:120];\n( way[highway]({b}); way[railway]({b}); way[bridge]({b}); '
         f'way[man_made~"^(bridge|canopy)$"]({b});\n  way[building=roof]({b}); way[covered=yes]({b}); '
         f'way[waterway~"^(river|canal)$"]({b}); node[barrier=toll_booth]({b}); );\nout body geom;')
    print(f'  overpass query for {key} bbox {b}', flush=True)
    js = overpass(q)
    js['vcyclist_bbox'] = b
    os.makedirs(OSM_DIR, exist_ok=True)
    with open(path + '.tmp', 'w') as f:
        json.dump(js, f)
    os.replace(path + '.tmp', path)
    return Osm(json.load(open(path)))


class Osm:
    def __init__(self, js):
        self.base = js['osm3s']['timestamp_osm_base']
        self.ways = {}
        self.node = {}
        self.tolls = []
        for el in js['elements']:
            if el['type'] == 'way' and el.get('geometry') and None not in el['geometry']:
                if el['id'] in self.ways:
                    continue
                self.ways[el['id']] = el
                for nid, g in zip(el['nodes'], el['geometry']):
                    self.node[nid] = (round(g['lat'], 7), round(g['lon'], 7))
            elif el['type'] == 'node' and el.get('tags', {}).get('barrier') == 'toll_booth':
                self.tolls.append((el['lat'], el['lon']))

    def tags(self, wid):
        return self.ways[wid].get('tags', {})


def label_of(tags, wid):
    return (tags.get('name') or tags.get('bridge:name') or tags.get('ref') or f'way {wid}').replace(',', ' ')


def layer_of(tags):
    m = re.match(r'\s*(-?\d+)', tags.get('layer', '0'))
    return int(m.group(1)) if m else 0


def is_elevated(tags):
    """an elevated structure: bridge (not 'no') or layer >= 1, on a highway or an existing railway"""
    if 'highway' not in tags and tags.get('railway') not in RAILWAYS:
        return False
    if tags.get('highway') in ('proposed', 'construction', 'abandoned', 'razed'):
        return False
    if tags.get('tunnel', 'no') not in ('no',):
        return False
    return tags.get('bridge', 'no') != 'no' or layer_of(tags) >= 1


def is_lateral(tags):
    """L: an elevated bridge or viaduct alongside (a staircase or footway at layer >= 1 is not one);
    railways keep layer >= 1, as a viaduct is often mapped by layer alone."""
    if not is_elevated(tags):
        return False
    return own_bridge(tags) or tags.get('railway') in RAILWAYS


def is_under(tags):
    """a way under the route: a highway/railway/waterway in a tunnel or culvert (building passages
    are O), or a waterway at layer < 0; never one at layer <= -2 (metro). The route above it may ride
    a deck that OSM did not tag (n03 over Route du Larzac, n04 over a path: MNT 4 to 6 m down)."""
    if 'highway' not in tags and tags.get('railway') not in RAILWAYS and 'waterway' not in tags:
        return False
    if tags.get('highway') in ('proposed', 'construction', 'abandoned', 'razed'):
        return False
    if layer_of(tags) <= -2:
        return False                        # a deep (bored) tunnel: the surface above is ground
    if tags.get('tunnel', 'no') not in ('no', 'building_passage'):
        return True
    return 'waterway' in tags and layer_of(tags) < 0


def own_bridge(tags):
    return tags.get('bridge', 'no') != 'no' or tags.get('man_made') == 'bridge'


def own_tunnel(tags):
    """N on a tunnel positive: a bored or cut-and-cover tunnel of the route (never a building passage,
    a culvert or an avalanche gallery, which stay X)"""
    return tags.get('tunnel', 'no') not in ('no', 'building_passage', 'culvert', 'avalanche_protector')


def structure_kind(tags):
    """the structure column of spans.csv for a D/X/N station"""
    if own_bridge(tags):
        return 'bridge'
    if tags.get('tunnel') == 'avalanche_protector':
        return 'gallery'
    return 'tunnel' if tags.get('tunnel', 'no') != 'no' else 'layer'


def own_structure(tags):
    """X on a negative: bridge, tunnel (building passages are O, not X), or layer > 0 without either"""
    if own_bridge(tags):
        return True
    if tags.get('tunnel', 'no') not in ('no', 'building_passage'):
        return True
    return layer_of(tags) > 0


def width_of(tags):
    m = re.match(r'\s*([\d.]+)', tags.get('width', ''))
    if m:
        try:
            return float(m.group(1))
        except ValueError:
            pass
    if 'railway' in tags and 'highway' not in tags:
        return 12.0
    hw = tags.get('highway')
    for kinds, w in DEFAULT_WIDTH:
        if hw in kinds:
            return w
    return 10.0


# ── Routing ──────────────────────────────────────────────────────────────────────────────────

class Graph:
    def __init__(self, osm, classes, select=None, oneway=True):
        self.osm = osm
        self.adj = {}
        self.ways = set()
        for wid, el in osm.ways.items():
            t = el.get('tags', {})
            hw = t.get('highway')
            if hw not in classes or t.get('area') == 'yes':
                continue
            if select is not None and not select(t):
                continue
            self.ways.add(wid)
            ow = t.get('oneway', 'no')
            fwd, back = True, True
            if oneway and hw in MOTOR | MOTOR_LINKS:
                implicit = hw in ('motorway', 'motorway_link') and ow != 'no'
                if ow in ('yes', 'true', '1') or (implicit and ow != '-1'):
                    back = False
                elif ow == '-1':
                    fwd = False
            ns = el['nodes']
            for a, b in zip(ns[:-1], ns[1:]):
                if a == b:
                    continue
                pa, pb = osm.node[a], osm.node[b]
                w = float(hav(pa[0], pa[1], pb[0], pb[1]))
                if fwd:
                    self.adj.setdefault(a, []).append((b, w, wid))
                    self.adj.setdefault(b, [])
                if back:
                    self.adj.setdefault(b, []).append((a, w, wid))
                    self.adj.setdefault(a, [])

    def snap(self, lat, lon, max_m=SNAP_M):
        ids = list(self.adj.keys())
        if not ids:
            return None, math.inf
        la = np.array([self.osm.node[i][0] for i in ids])
        lo = np.array([self.osm.node[i][1] for i in ids])
        d = hav(lat, lon, la, lo)
        k = int(np.argmin(d))
        return (ids[k], float(d[k])) if d[k] <= max_m else (None, float(d[k]))

    def dijkstra(self, src, dst=None, limit=math.inf, reverse=False):
        adj = self.adj
        if reverse:
            adj = {}
            for a, es in self.adj.items():
                adj.setdefault(a, [])
                for b, w, wid in es:
                    adj.setdefault(b, []).append((a, w, wid))
        dist = {src: 0.0}
        prev = {}
        pq = [(0.0, src)]
        while pq:
            d, u = heapq.heappop(pq)
            if d > dist.get(u, math.inf):
                continue
            if u == dst:
                break
            for v, w, wid in sorted(adj.get(u, []), key=lambda e: (e[0], e[2])):
                nd = d + w
                if nd <= limit and nd < dist.get(v, math.inf) - 1e-9:
                    dist[v] = nd
                    prev[v] = (u, wid)
                    heapq.heappush(pq, (nd, v))
        return dist, prev


def path_to(prev, src, dst):
    nodes, ways = [dst], []
    while nodes[-1] != src:
        u, wid = prev[nodes[-1]]
        nodes.append(u)
        ways.append(wid)
    return nodes[::-1], ways[::-1]


def selector_fn(sel):
    if not sel:
        return None
    pats = {k: re.compile(v) for k, v in sel.items()}

    def f(t):
        return any(t.get(k) and p.search(t[k]) for k, p in pats.items())
    return f


def route(osm, waypoints, classes, sel=None, oneway=True, max_detour=1.3, snap_m=SNAP_M):
    """Dijkstra between consecutive waypoints; selector-restricted, classes-only fallback."""
    graphs = []
    if sel:
        graphs.append(('selector', Graph(osm, classes, selector_fn(sel), oneway)))
    graphs.append(('classes', Graph(osm, classes, None, oneway)))
    last_err = None
    for gname, g in graphs:
        try:
            snapped = []
            for lat, lon in waypoints:
                nid, d = g.snap(lat, lon, snap_m)
                if nid is None:
                    raise RuntimeError(f'waypoint {lat:.5f},{lon:.5f} not within {snap_m:.0f} m of the {gname} graph (nearest {d:.0f} m)')
                snapped.append(nid)
            nodes, ways = [snapped[0]], []
            for a, b in zip(snapped[:-1], snapped[1:]):
                if a == b:
                    continue
                dist, prev = g.dijkstra(a, b)
                if b not in dist:
                    raise RuntimeError(f'{gname} graph disconnected between waypoints')
                ns, ws = path_to(prev, a, b)
                nodes += ns[1:]
                ways += ws
            if gname == 'classes' and sel:
                print(f'  NOTE: selector graph failed ({last_err}); routed on classes only', flush=True)
            break
        except RuntimeError as ex:
            last_err = ex
            if gname == 'classes':
                raise
    pts = [osm.node[n] for n in nodes]
    lat = np.array([p[0] for p in pts])
    lon = np.array([p[1] for p in pts])
    seg = hav(lat[:-1], lon[:-1], lat[1:], lon[1:])
    keep = np.concatenate([[True], seg > 0])
    nodes = [n for n, k in zip(nodes, keep) if k]
    ways = [w for w, k in zip(ways, keep[1:]) if k]
    if len(set(nodes)) != len(nodes):
        raise RuntimeError('route visits a node twice')
    lat, lon = lat[keep], lon[keep]
    length = float(hav(lat[:-1], lon[:-1], lat[1:], lon[1:]).sum())
    wl = np.array(waypoints)
    straight = float(hav(wl[:-1, 0], wl[:-1, 1], wl[1:, 0], wl[1:, 1]).sum())
    if length > max_detour * straight:
        raise RuntimeError(f'route {length:.0f} m > {max_detour} x straight {straight:.0f} m')
    if not 400 <= length <= 6000:
        raise RuntimeError(f'route length {length:.0f} m outside 0.4-6 km')
    return nodes, ways, lat, lon, length


# ── Derived waypoints ────────────────────────────────────────────────────────────────────────

def way_length(osm, wid):
    g = osm.ways[wid]['geometry']
    la = np.array([p['lat'] for p in g])
    lo = np.array([p['lon'] for p in g])
    return float(hav(la[:-1], lo[:-1], la[1:], lo[1:]).sum())


def crossings(osm, proj, wid_a, wid_b):
    """crossing points (lat, lon) between two ways, sorted"""
    out = []
    ga = osm.ways[wid_a]['geometry']
    gb = osm.ways[wid_b]['geometry']
    ax, ay = proj.xy([p['lat'] for p in ga], [p['lon'] for p in ga])
    bx, by = proj.xy([p['lat'] for p in gb], [p['lon'] for p in gb])
    for i in range(len(ax) - 1):
        for j in range(len(bx) - 1):
            r = intersect((ax[i], ay[i]), (ax[i + 1], ay[i + 1]), (bx[j], by[j]), (bx[j + 1], by[j + 1]))
            if r:
                t = r[0]
                out.append((ga[i]['lat'] + t * (ga[i + 1]['lat'] - ga[i]['lat']),
                            ga[i]['lon'] + t * (ga[i + 1]['lon'] - ga[i]['lon'])))
    return sorted(out)


def along(osm, classes, sel, point, half):
    """[A, X, B]: the two ends of a ~2*half path along `sel` through the node nearest `point`."""
    g = Graph(osm, classes, selector_fn(sel), oneway=False)
    x, d = g.snap(*point, max_m=30.0)
    if x is None:
        raise RuntimeError(f'no {sel} node within 30 m of {point}')
    dx, _ = g.dijkstra(x, limit=half)
    a = max(sorted(dx), key=lambda n: (dx[n], -n))
    da, _ = g.dijkstra(a, limit=3 * half)
    b = max(sorted(n for n in dx if n in da), key=lambda n: (da[n], -n))
    return [osm.node[a], osm.node[x], osm.node[b]]


def chevire_ways(osm):
    out = [wid for wid, el in sorted(osm.ways.items())
           if el.get('tags', {}).get('bridge', 'no') != 'no'
           and el['tags'].get('highway') in MOTOR
           and re.search(r'Chevir', ' '.join(el['tags'].get(k, '') for k in ('name', 'alt_name', 'bridge:name')))]
    if not out:
        raise RuntimeError('no Chevire bridge way')
    return out


def derive_chevire_under(site, osm):
    bridge = chevire_ways(osm)
    proj = Proj(47.19)
    cands = []
    for wid, el in sorted(osm.ways.items()):
        t = el.get('tags', {})
        if t.get('highway') not in site['classes'] or not t.get('name'):
            continue
        if t.get('bridge', 'no') != 'no' or t.get('tunnel', 'no') != 'no' or layer_of(t) != 0:
            continue
        pts = [p for b in bridge for p in crossings(osm, proj, wid, b)]
        if pts:
            cands.append((-way_length(osm, wid), wid, pts[0]))
    if not cands:
        raise RuntimeError('no road crosses under the Chevire bridge')
    cands.sort()
    _, wid, pt = cands[0]
    name = osm.tags(wid)['name']
    print(f'  derived: crossing way {wid} "{name}" at {pt[0]:.6f},{pt[1]:.6f}', flush=True)
    sel = dict(name='^' + re.escape(name) + '$')
    return along(osm, site['classes'], sel, pt, 800.0), sel, f'way {wid} crossing under ways {bridge}'


def derive_chevire_bridge(site, osm):
    bridge = set(chevire_ways(osm))
    g = Graph(osm, MOTOR, selector_fn(dict(ref=r'N 844')), oneway=True)
    start = min(bridge)
    el = osm.ways[start]
    first, last = el['nodes'][0], el['nodes'][-1]
    if el['tags'].get('oneway') == '-1':
        first, last = last, first

    def follow(node, rev):
        seen = set()
        while True:
            nxt = None
            if rev:
                for a, es in g.adj.items():
                    for b, w, wid in es:
                        if b == node and wid in bridge and a not in seen:
                            nxt = a
            else:
                for b, w, wid in g.adj.get(node, []):
                    if wid in bridge and b not in seen:
                        nxt = b
            if nxt is None:
                return node
            seen.add(node)
            node = nxt
    s = follow(first, True)
    e = follow(last, False)
    df, pf = g.dijkstra(e, limit=1200)
    db, pb = g.dijkstra(s, limit=1200, reverse=True)

    def clean_end(n, dist, prev):
        """no bridge way within END_MARGIN_M of the route end n (walking back towards the deck)"""
        walked = 0.0
        while n in prev and walked < END_MARGIN_M:
            u, wid = prev[n]
            if own_bridge(osm.tags(wid)):
                return False
            walked += dist[n] - dist[u]
            n = u
        return walked >= END_MARGIN_M
    # ~700 m of approach on each side, ending on ground: the clamped outside MNT of a route end must
    # not fall on a bridge (the first generation started on a 60 m neighbour bridge).
    b_end = min((n for n in sorted(df) if clean_end(n, df, pf)), key=lambda n: (abs(df[n] - 700), n))
    a_end = min((n for n in sorted(db) if clean_end(n, db, pb)), key=lambda n: (abs(db[n] - 700), n))
    print(f'  derived: Chevire carriageway of way {start}, deck nodes {s} -> {e}', flush=True)
    wps = [osm.node[a_end], osm.node[s], osm.node[e], osm.node[b_end]]
    return wps, dict(ref=r'N 844'), f'carriageway of way {start}'


def derive_periph_overpass(site, osm):
    n04 = next(s for s in SITES if s['id'].startswith('n04'))
    res = build(n04)
    cands = []
    for wid, s_cross in res['crossings']:
        t = osm.tags(wid)
        if t.get('highway') not in STREETS - {'primary', 'primary_link'} | {'cycleway'}:
            continue
        if t.get('bridge', 'no') == 'no':
            continue
        L = way_length(osm, wid)
        if 30 <= L <= 80:
            cands.append((-L, wid, s_cross))
    if not cands:
        raise RuntimeError('no 30-80 m street/cycleway overpass on n04')
    cands.sort()
    _, wid, s_cross = cands[0]
    t = osm.tags(wid)
    k = int(np.argmin(np.abs(res['s'] - s_cross)))
    pt = (res['lat'][k], res['lon'][k])
    # The crossing point on n04 lies under the overpass; snap on the overpass itself.
    g = osm.ways[wid]['geometry']
    mid = g[len(g) // 2]
    pt = (mid['lat'], mid['lon'])
    sel = dict(name='^' + re.escape(t['name']) + '$') if t.get('name') else None
    print(f'  derived: overpass way {wid} "{label_of(t, wid)}" {-cands[0][0]:.0f} m', flush=True)
    if sel is None:
        raise RuntimeError('overpass without a name')
    wps = along(osm, site['classes'], sel, pt, 300.0)
    named = sum(float(hav(a[0], a[1], b[0], b[1])) for a, b in zip(wps[:-1], wps[1:]))
    if named < 400.0:
        # The named street ends within 300 m on a side: continue on any street class, same rule.
        print(f'  derived: "{t["name"]}" gives {named:.0f} m, continuing on street classes', flush=True)
        wps, sel = along(osm, site['classes'], None, pt, 300.0), None
    return wps, sel, f'overpass way {wid}'


def bridge_chain(osm, start, bridges):
    """nodes of the deck through way `start`: `start` extended at both ends by the other ways of
    `bridges` that share an end node (way-level chaining, so a two-way graph never walks back)."""
    nodes = list(osm.ways[start]['nodes'])
    used = {start}
    for _ in range(2):
        while True:
            nxt = None
            for wid in sorted(bridges - used):
                ns = osm.ways[wid]['nodes']
                if ns[0] == nodes[-1]:
                    nxt = (wid, list(ns))
                elif ns[-1] == nodes[-1]:
                    nxt = (wid, list(ns[::-1]))
                if nxt:
                    break
            if nxt is None:
                break
            used.add(nxt[0])
            nodes += nxt[1][1:]
        nodes.reverse()
    return nodes, used


def chain_length(osm, nodes):
    la = np.array([osm.node[n][0] for n in nodes])
    lo = np.array([osm.node[n][1] for n in nodes])
    return float(hav(la[:-1], lo[:-1], la[1:], lo[1:]).sum())


def crosses(osm, proj, wid, targets):
    return any(o != wid and crossings(osm, proj, wid, o) for o in targets)


def over_pred(pick):
    """what a picked bridge must cross (or a picked street pass under): railway, road ref, river name"""
    if pick.get('over') == 'rail':
        return lambda t: t.get('railway') == 'rail'
    if 'over_ref' in pick:
        r = re.compile(pick['over_ref'])
        return lambda t: 'highway' in t and bool(r.search(t.get('ref', '')))
    if 'over_water' in pick:
        r = re.compile(pick['over_water'])
        return lambda t: t.get('waterway') == 'river' and bool(r.search(t.get('name', '')))
    raise ValueError(pick)


def approach(osm, classes, core, nodes, t, need_clean, dirty=own_bridge, back=0.5):
    """[A, S, E, B]: the core ways' node chain S..E plus ~APPROACH_M of road beyond each end, first on
    the core's own ref or name, else on any road of the classes; each end is the straightest
    continuation (largest straight-line / along-route ratio, then farthest from the other end; `back`
    sets how much farther from the other end than from this one, in core lengths) and,
    with need_clean, has END_MARGIN_M of route without any bridge way before it."""
    s_node, e_node = nodes[0], nodes[-1]
    L = chain_length(osm, nodes)
    sel = dict(ref='^' + re.escape(t['ref']) + '$') if t.get('ref') else (
        dict(name='^' + re.escape(t['name']) + '$') if t.get('name') else None)
    oneway = bool(classes & (MOTOR | MOTOR_LINKS))

    def ends(sel_):
        g = Graph(osm, classes, selector_fn(sel_), oneway)
        for a, es in g.adj.items():
            g.adj[a] = [e for e in es if e[2] not in core]
        out = []
        for here, there, rev in ((e_node, s_node, False), (s_node, e_node, True)):
            if here not in g.adj:
                return None
            dist, prev = g.dijkstra(here, limit=APPROACH_M + 400, reverse=rev and oneway)
            ph, pt = osm.node[here], osm.node[there]

            def clean(n):
                walked, m = 0.0, n
                while m in prev and walked < END_MARGIN_M:
                    u, wid = prev[m]
                    if dirty(osm.tags(wid)):
                        return False
                    walked += dist[m] - dist[u]
                    m = u
                if walked < END_MARGIN_M:
                    return False
                if dirty is own_bridge:
                    return True
                # E4 rule 4: the first GROUND_M out of the core are ground too (no structure at all)
                m = n
                while m in prev:
                    u, wid = prev[m]
                    if dist[u] < GROUND_M and dirty(osm.tags(wid)):
                        return False
                    m = u
                return True
            pool = []
            for n in sorted(dist):
                p = osm.node[n]
                if dist[n] < APPROACH_M - 100 or dist[n] > APPROACH_M + 150 or (need_clean and not clean(n)):
                    continue
                away = float(hav(p[0], p[1], pt[0], pt[1]))
                if away <= float(hav(p[0], p[1], ph[0], ph[1])) + back * L:
                    continue                 # back towards the other end of the core
                straight = float(hav(p[0], p[1], ph[0], ph[1])) / dist[n]
                pool.append((-round(straight, 2), -away, n))
            if not pool:
                return None
            out.append(min(pool)[2])
        return out
    found = ends(sel) if sel else None
    if found is None:
        if sel:
            print(f'  derived: approach on {sel} too short, any road of the classes', flush=True)
        sel = None
        found = ends(None)
    if found is None:
        raise RuntimeError('no approach of ~400 m on both sides')
    b_end, a_end = found
    return [osm.node[a_end], osm.node[s_node], osm.node[e_node], osm.node[b_end]], sel


def derive_bridge(site, osm):
    """E2 quinquies: the deck named by pick['name'], or the longest deck (chained bridge ways of the
    site's classes, length in pick['length']) crossing pick's target; then ~APPROACH_M of road on
    ground beyond each end, continuing as straight as the graph allows."""
    pick = site['pick']
    classes = site['classes']
    bridges = {wid for wid, el in osm.ways.items()
               if own_bridge(el.get('tags', {})) and el['tags'].get('highway') in classes
               and el['tags'].get('area') != 'yes'}
    proj = Proj(float(np.mean(site['bbox'][0::2])))
    if 'name' in pick:
        r = re.compile(pick['name'])
        named = sorted(w for w in bridges if any(r.search(osm.tags(w).get(k, '')) for k in ('name', 'bridge:name')))
        if not named:
            raise RuntimeError(f'no bridge way named ~ {pick["name"]}')
        start = named[0]
        nodes, used = bridge_chain(osm, start, set(named))
    else:
        pred = over_pred(pick)
        targets = [o for o in sorted(osm.ways) if pred(osm.tags(o))]
        lo, hi = pick['length']
        seen, cands = set(), []
        for wid in sorted(bridges):
            if not crosses(osm, proj, wid, targets):
                continue
            ns, used = bridge_chain(osm, wid, bridges)
            key = frozenset(used)
            if key in seen:
                continue
            seen.add(key)
            L = chain_length(osm, ns)
            if lo <= L <= hi:
                cands.append((-L, min(used), ns, used))
        if not cands:
            raise RuntimeError(f'no {lo:.0f}-{hi:.0f} m bridge of {sorted(classes)} crossing {pick}')
        cands.sort(key=lambda c: (c[0], c[1]))
        _, start, nodes, used = cands[0]
    L = chain_length(osm, nodes)
    t = osm.tags(start)
    print(f'  derived: deck ways {sorted(used)} "{label_of(t, start)}" {L:.0f} m', flush=True)
    wps, sel = approach(osm, classes, set(used), nodes, t, need_clean=True)
    return wps, sel, f'deck ways {sorted(used)}'


def derive_under(site, osm):
    """E2 quinquies negatives: the longest named street way (no bridge, tunnel or layer) of the site's
    classes crossing under an elevated way matching pick, with ~APPROACH_M of road beyond each end."""
    pred = over_pred(site['pick'])
    proj = Proj(float(np.mean(site['bbox'][0::2])))
    over = [w for w in sorted(osm.ways) if is_elevated(osm.tags(w)) and pred(osm.tags(w))]
    if not over:
        raise RuntimeError(f'no elevated way matching {site["pick"]}')
    cands = []
    for wid, el in sorted(osm.ways.items()):
        t = el.get('tags', {})
        if t.get('highway') not in site['classes'] or not t.get('name'):
            continue
        if t.get('bridge', 'no') != 'no' or t.get('tunnel', 'no') != 'no' or layer_of(t) != 0:
            continue
        pts = [p for o in over for p in crossings(osm, proj, wid, o)]
        if pts:
            cands.append((-way_length(osm, wid), wid, pts[0]))
    if not cands:
        raise RuntimeError(f'no named street crosses under {site["pick"]}')
    cands.sort()
    _, wid, pt = cands[0]
    t = osm.tags(wid)
    print(f'  derived: way {wid} "{t["name"]}" {-cands[0][0]:.0f} m crossing under at {pt[0]:.6f},{pt[1]:.6f}', flush=True)
    wps, sel = approach(osm, site['classes'], {wid}, list(osm.ways[wid]['nodes']), t, need_clean=False)
    return wps, sel, f'way {wid} crossing under {site["pick"]}'


def tunnel_ok(t, classes, strict):
    """a tunnel way a pick may count: rule 1 of the E4 series when strict (tunnel=yes only), any
    own_tunnel otherwise (tuning sites)"""
    if t.get('highway') not in classes or t.get('area') == 'yes':
        return False
    if not strict:
        return own_tunnel(t)                # tuning: a hand-drawn route may ride a tunnel closed to bikes
    if t.get('bicycle') == 'no' or t.get('access') in ('no', 'private'):
        return False
    return t.get('tunnel') == 'yes'


def tunnel_candidates(osm, site, ways=None):
    """[(sort key, start way, nodes, used ways, length, chord / length)] for site['pick'], best first:
    chained tunnel ways (sharing an end node), filtered by ref / name / length, sorted by the pick's
    order ('longest', or 'curved': smallest chord / length)"""
    pick = site['pick']
    classes = site['classes']
    strict = site['role'] in ('tunnel-holdout', 'tunnel-holdout-negative')
    pool = {w for w in (ways if ways is not None else osm.ways) if tunnel_ok(osm.tags(w), classes, strict)
            and osm.tags(w).get('highway') in pick.get('highway', classes)}
    ref = re.compile(pick['ref']) if 'ref' in pick else None
    name = re.compile(pick['name']) if 'name' in pick else None
    lo, hi = pick.get('length', (50.0, 2500.0))
    seen, cands = set(), []
    for wid in sorted(pool):
        ns, used = bridge_chain(osm, wid, pool)
        key = frozenset(used)
        if key in seen:
            continue
        seen.add(key)
        tags = [osm.tags(w) for w in used]
        if ref and not any(ref.search(x.get('ref', '')) for x in tags):
            continue
        if name and not any(name.search(x.get(k, '')) for x in tags for k in ('name', 'tunnel:name', 'alt_name')):
            continue
        L = chain_length(osm, ns)
        if not lo <= L <= hi:
            continue
        a, b = osm.node[ns[0]], osm.node[ns[-1]]
        ratio = float(hav(a[0], a[1], b[0], b[1])) / L
        order = round(ratio, 4) if pick.get('order') == 'curved' else -L
        cands.append((order, min(used), ns, used, L, ratio))
    cands.sort(key=lambda c: (c[0], c[1]))
    return cands


def resolve_area_pick(site):
    """E4 picks over an administrative area (department or commune by its INSEE code): one light
    Overpass query for the area's tunnel ways (cached as osm/controls/<id>-pick.json), the pick made
    on it, then the site's bbox set around the chosen tunnel so osm_for fetches the full site."""
    pick = site['pick']
    path = os.path.join(OSM_DIR, site['id'] + '-pick.json')
    if os.path.exists(path):
        js = json.load(open(path))
    else:
        level = 6 if len(pick['insee']) <= 3 else 8
        what = ''.join(f'["{k}"="{v}"]' for k, v in pick['tags'].items()) if 'tags' in pick else '[tunnel]'
        q = (f'[out:json][timeout:180];\narea["ref:INSEE"="{pick["insee"]}"]["admin_level"="{level}"]->.a;\n'
             f'way(area.a)[highway]{what};\nout body geom;')
        print(f'  overpass area pick for {site["id"]} INSEE {pick["insee"]}', flush=True)
        js = overpass(q)
        os.makedirs(OSM_DIR, exist_ok=True)
        with open(path + '.tmp', 'w') as f:
            json.dump(js, f)
        os.replace(path + '.tmp', path)
    osm = Osm(js)
    if 'tags' in pick:
        cands = [c + (chain_length(osm, c[2]), 1.0) for c in tagged_candidates(osm, site)]
    else:
        cands = tunnel_candidates(osm, site)
    if not cands:
        raise RuntimeError(f'no match in INSEE {pick["insee"]} for {pick}')
    _, start, nodes, used, L, ratio = cands[0]
    print(f'  area pick: {len(cands)} candidate(s); chosen ways {sorted(used)} "{label_of(osm.tags(start), start)}" '
          f'{L:.0f} m, chord/length {ratio:.3f}', flush=True)
    pts = [osm.node[x] for x in nodes]
    site['bbox'] = tuple(round(v, 5) for v in bbox_of(pts, APPROACH_M + 500.0))
    site['picked_ways'] = sorted(used)


def derive_tunnel(site, osm):
    """E4: the tunnel picked by site['pick'] (ref / name / length / order inside the site's bbox, or
    an area pick resolved before the fetch), then ~APPROACH_M of road beyond each end, with
    END_MARGIN_M of route free of any bridge, tunnel or layer before each end."""
    ways = None
    if 'picked_ways' in site:
        ways = set(site['picked_ways'])
    cands = tunnel_candidates(osm, site, ways)
    if not cands:
        raise RuntimeError(f'no tunnel of {sorted(site["classes"])} for {site["pick"]}')
    _, start, nodes, used, L, ratio = cands[0]
    t = osm.tags(start)
    others = [(sorted(c[3]), round(c[4]), round(c[5], 3)) for c in cands[1:4]]
    print(f'  derived: tunnel ways {sorted(used)} "{label_of(t, start)}" {L:.0f} m, chord/length {ratio:.3f}; next {others}', flush=True)
    dirty = (lambda tags: False) if site.get('x_near_ok') else own_structure
    # back=0: a mountain road bends; the bridges' 0.5 core length rejected any ~60 deg turn in 400 m
    wps, sel = approach(osm, site['classes'], set(used), nodes, t, need_clean=True, dirty=dirty, back=0.0)
    return wps, sel, f'tunnel ways {sorted(used)}'


def derive_along(site, osm):
    """E4 negatives: ~2 x site['half'] of road along site['selector'] (or any road of the classes)
    through the node nearest site['center'] (within 400 m), each end the farthest reachable node."""
    sel = site.get('selector')
    g = Graph(osm, site['classes'], selector_fn(sel), oneway=False)
    x, d = g.snap(*site['center'], max_m=site.get('snap_m', 400.0))
    if x is None:
        raise RuntimeError(f'no {sel} node within {site.get("snap_m", 400.0):.0f} m of {site["center"]} (nearest {d:.0f} m)')
    half = site['half']
    dx, _ = g.dijkstra(x, limit=half)
    a = max(sorted(dx), key=lambda n: (dx[n], -n))
    da, _ = g.dijkstra(a, limit=3 * half)
    b = max(sorted(n for n in dx if n in da), key=lambda n: (da[n], -n))
    print(f'  derived: along {sel} through node {x} ({d:.0f} m from the centre), {da[b]:.0f} m', flush=True)
    return [osm.node[a], osm.node[b]], sel, f'along {sel} through node {x}'


def derive_tagged(site, osm):
    """E4 negatives: the longest chain of ways of the classes carrying every tag of site['pick']['tags']
    (e.g. cutting=yes) in the site's bbox, length in pick['length'], then ~APPROACH_M beyond each end."""
    cands = tagged_candidates(osm, site, set(site['picked_ways']) if 'picked_ways' in site else None)
    pick = site['pick']
    lo, hi = pick.get('length', (100.0, 3000.0))
    if not cands:
        raise RuntimeError(f'no {lo:.0f}-{hi:.0f} m way chain tagged {pick["tags"]}')
    _, start, nodes, used = cands[0]
    t = osm.tags(start)
    print(f'  derived: {pick["tags"]} ways {sorted(used)} "{label_of(t, start)}" {-cands[0][0]:.0f} m; {len(cands)} candidate(s)', flush=True)
    wps, sel = approach(osm, site['classes'], set(used), nodes, t, need_clean=False, back=0.0)
    return wps, sel, f'{"_".join(f"{k}={v}" for k, v in pick["tags"].items())} ways {sorted(used)}'


def tagged_candidates(osm, site, ways=None):
    """chains of ways of the classes carrying every tag of site['pick']['tags'], longest first"""
    pick = site['pick']
    pool = {w for w in (ways if ways is not None else osm.ways) if osm.tags(w).get('highway') in site['classes']
            and all(osm.tags(w).get(k) == v for k, v in pick['tags'].items())}
    lo, hi = pick.get('length', (100.0, 3000.0))
    seen, cands = set(), []
    for wid in sorted(pool):
        ns, used = bridge_chain(osm, wid, pool)
        key = frozenset(used)
        if key in seen:
            continue
        seen.add(key)
        L = chain_length(osm, ns)
        if lo <= L <= hi:
            cands.append((-L, min(used), ns, used))
    cands.sort(key=lambda c: (c[0], c[1]))
    return cands


DERIVED = dict(chevire_under=derive_chevire_under, chevire_bridge=derive_chevire_bridge,
               periph_overpass=derive_periph_overpass, bridge=derive_bridge, under=derive_under,
               tunnel=derive_tunnel, along=derive_along, tagged=derive_tagged)


# ── E4 tunnel series (python3 -I controls.py --tunnels) ──────────────────────────────────────
# Written to controls/tunnels/ and truth/tunnels/. Roles: tunnel-tuning and tunnel-negative are the
# calibration set (any department of the bench's exclusion list); tunnel-holdout and
# tunnel-holdout-negative are frozen by the rule of elevation-bridge-decks.md (E4, *Témoins tunnels*),
# written before any OSM query for them, and read only by a *TunnelJudge* test (TUNNEL_JUDGE=1).

def around(lat, lon, m):
    return tuple(round(v, 5) for v in bbox_of([(lat, lon)], m))


TUNNEL_CLASSES = STREETS | {'cycleway'}          # rule 1 of the held-out series


def area_tunnel(sid, dept, insee, category, place, length, order=None, highway=None):
    """a held-out tunnel candidate picked over an administrative area (amendment 1 of the E4 rule)"""
    pick = dict(insee=insee, length=length)
    if order:
        pick['order'] = order
    if highway:
        pick['highway'] = highway
    return dict(id=sid, depts={dept}, role='tunnel-holdout', category=category, place=place,
                derived='tunnel', pick=pick, classes=TUNNEL_CLASSES)

TUNNEL_SITES = [
    # ── tuning tunnels (truth = line between portals; the four of test4 stay in truth/test4.csv)
    dict(id='tt01-galibier', role='tunnel-tuning', category='mountain-tunnel-under-col',
         place='Tunnel du Galibier D 902 under the Col du Galibier (Savoie / Hautes-Alpes)',
         derived='tunnel', pick=dict(name=r'Galibier'), bbox=around(45.0630, 6.4070, 1500),
         classes=CARS, max_detour=2.5, hairpins=True),
    dict(id='tt02-croix-rousse', role='tunnel-tuning', category='urban-bored-tunnel-under-hill',
         place='Lyon Tunnel de la Croix-Rousse bus / foot / cycle tube under the Croix-Rousse hill',
         derived='tunnel', pick=dict(name=r'Croix-Rousse', length=(1500.0, 2000.0)), bbox=(45.764, 4.805, 45.784, 4.850),
         classes=STREETS | {'busway', 'path', 'cycleway', 'service'}),
    dict(id='tt03-lioran', role='tunnel-tuning', category='long-mountain-tunnel-under-col',
         place='Tunnel du Lioran N 122 under the Col de Font de Cere (Cantal)',
         derived='tunnel', pick=dict(ref=r'^N 122$', length=(1000.0, 2000.0)), bbox=around(45.0790, 2.7400, 1800),
         classes=CARS),
    dict(id='tt04-tuileries', role='tunnel-tuning', category='urban-cut-and-cover-flat-above',
         place='Paris Tunnel des Tuileries (former Voie Georges-Pompidou) under the Tuileries quay and garden',
         derived='tunnel', pick=dict(name=r'Tuileries'), bbox=(48.853, 2.312, 48.868, 2.345),
         classes=STREETS | {'service', 'cycleway', 'pedestrian', 'footway'}),
    dict(id='tt05-rousset', role='tunnel-tuning', category='mountain-tunnel-under-col',
         place='Tunnel du Col de Rousset D 518 (Drome)',
         derived='tunnel', pick=dict(ref=r'^D 518$'), bbox=around(44.8390, 5.4020, 1500),
         classes=CARS),
    dict(id='tt06-nesque', role='tunnel-tuning', category='short-gorge-tunnels',
         place='Gorges de la Nesque D 942 tunnels (Vaucluse)',
         derived='tunnel', pick=dict(ref=r'^D 942$', length=(80.0, 300.0)), bbox=around(44.0500, 5.3300, 1500),
         classes=CARS),
    dict(id='tt07-petit-frou', role='tunnel-tuning', category='balcony-gorge-tunnel',
         place='Gorges du Guiers Vif D 520C Tunnel du Petit Frou (Isere / Savoie)',
         derived='tunnel', pick=dict(ref=r'^D 520C$'), bbox=around(45.4200, 5.8300, 1800),
         classes=CARS),
    dict(id='tt08-arly-cliets', role='tunnel-tuning', category='gorge-tunnel-among-avalanche-galleries',
         place='Gorges de l Arly D 1212 Tunnel des Cliets between avalanche galleries (Savoie)',
         derived='tunnel', pick=dict(ref=r'^D 1212$', length=(150.0, 400.0)), bbox=around(45.7800, 6.4850, 1500),
         classes=CARS, x_near_ok=True),
    # ── tuning negatives: real roads, all on the ground (X = own structure, excluded)
    dict(id='tn01-alpe-huez', role='tunnel-negative', category='hairpin-climb',
         place='Alpe d Huez D 211 lacets above Le Bourg-d Oisans (Isere)',
         derived='along', selector=dict(ref=r'^D 211$'), center=(45.0700, 6.0450), half=1500.0,
         bbox=around(45.0700, 6.0450, 2500), classes=CARS, hairpins=True),
    dict(id='tn02-montvernier', role='tunnel-negative', category='hairpin-climb-on-cliff',
         place='Lacets de Montvernier (Savoie)',
         derived='along', center=(45.3150, 6.3400), half=1200.0,
         bbox=around(45.3150, 6.3400, 2000), classes=CARS, hairpins=True),
    dict(id='tn03-galibier-col', role='tunnel-negative', category='road-over-col-above-tunnel',
         place='Col du Galibier D 902 over the col, above the Tunnel du Galibier (Savoie / Hautes-Alpes)',
         derived='along', selector=dict(ref=r'^D 902$'), center=(45.0641, 6.4078), half=1200.0,
         bbox=around(45.0641, 6.4078, 2000), classes=CARS, hairpins=True),
    dict(id='tn04-ventoux', role='tunnel-negative', category='steep-summit-climb',
         place='Mont Ventoux D 974 from below Chalet Reynard to the summit (Vaucluse)',
         derived='along', selector=dict(ref=r'^D 974$'), center=(44.1700, 5.2950), half=1500.0,
         bbox=around(44.1700, 5.2950, 2500), classes=CARS, hairpins=True),
    dict(id='tn05-montmartre', role='tunnel-negative', category='steep-urban-ramp-over-crest',
         place='Paris Montmartre rue Lepic from Place Blanche over the butte',
         waypoints=[(48.88365, 2.33270), (48.88705, 2.33650)],
         classes=STREETS, max_detour=1.8, hairpins=True),
    dict(id='tn06-lyon-boucle', role='tunnel-negative', category='steep-urban-hairpin-ramp',
         place='Lyon Montee de la Boucle from the Rhone to the Croix-Rousse plateau',
         derived='along', selector=dict(name=r'Mont[ée]e de la Boucle'), center=(45.7770, 4.8440), half=500.0,
         bbox=around(45.7770, 4.8440, 1200), classes=STREETS, hairpins=True),
    dict(id='tn07-lyon-st-sebastien', role='tunnel-negative', category='steep-urban-ramp',
         place='Lyon from Place Croix-Paquet to the Croix-Rousse plateau by the quays and Montee Bonafous',
         waypoints=[(45.7697, 4.8362), (45.7745, 4.8330)], bbox=around(45.7720, 4.8346, 900),
         classes=STREETS, max_detour=3.0, hairpins=True),
    dict(id='tn08-bourne', role='tunnel-negative', category='balcony-under-cliff',
         place='Gorges de la Bourne D 531 balcony road (Isere)',
         derived='along', selector=dict(ref=r'^D 531$'), center=(45.0700, 5.4600), half=1500.0, snap_m=2000.0,
         bbox=around(45.0700, 5.4600, 3500), classes=CARS),
    dict(id='tn09-verdon-cretes', role='tunnel-negative', category='balcony-over-gorge',
         place='Gorges du Verdon Route des Cretes D 23 (Alpes-de-Haute-Provence)',
         derived='along', selector=dict(ref=r'^D 23$'), center=(43.7720, 6.3550), half=1500.0, snap_m=2500.0,
         bbox=around(43.7720, 6.3550, 2500), classes=CARS, hairpins=True),
    dict(id='tn10-roselend', role='tunnel-negative', category='dam-crest-road',
         place='Barrage de Roselend Route du Barrage over the dam (Savoie)',
         derived='along', selector=dict(name=r'^Route du Barrage$'), center=(45.6825, 6.6165), half=700.0,
         bbox=around(45.6830, 6.6200, 1500), classes=CARS, hairpins=True),
    dict(id='tn11-serre-poncon', role='tunnel-negative', category='dam-crest-road',
         place='Barrage de Serre-Poncon Route du Barrage (Hautes-Alpes)',
         derived='along', selector=dict(name=r'^Route du Barrage$'), center=(44.4815, 6.2695), half=700.0,
         bbox=around(44.4710, 6.2740, 1500), classes=CARS),
    dict(id='tn12-arly-galleries', role='tunnel-negative', category='avalanche-galleries',
         place='Gorges de l Arly D 1212 south of the Tunnel des Cliets under avalanche galleries (Savoie)',
         derived='along', selector=dict(ref=r'^D 1212$'), center=(45.7521, 6.4449), half=700.0,
         bbox=around(45.7521, 6.4449, 1500), classes=CARS),
    dict(id='tn13-cutting-isere', role='tunnel-negative', category='road-in-cutting',
         place='Longest cutting=yes road chain of the classes around Grenoble (Isere)',
         derived='tagged', pick=dict(tags={'cutting': 'yes'}, length=(100.0, 2000.0)), bbox=(45.10, 5.60, 45.30, 5.85),
         classes=STREETS),
    # ── held-out series (rule of E4, frozen 2026-10-13 18:06 before any OSM query for it). One cell
    # per row of the rule's tables: its candidates are tried in the rule's order, the first that
    # builds and passes every check is kept, a candidate whose department a kept site already holds
    # is skipped (rule 3), and a cell with none left stays empty. Failures are printed, and written in
    # the task doc (E4, *Témoins tunnels*, amendments).
    dict(cell='T1 short gorge (Alps)', candidates=[
        dict(id='th01-cians', depts={'06'}, role='tunnel-holdout', category='short-gorge-tunnel',
             place='Gorges du Cians D 28 longest tunnel between Touet-sur-Var and Beuil (Alpes-Maritimes)',
             derived='tunnel', pick=dict(ref=r'^D ?28$'), bbox=(43.93, 6.94, 44.10, 7.06), classes=TUNNEL_CLASSES),
        dict(id='th01-daluis', depts={'06'}, role='tunnel-holdout', category='short-gorge-tunnel',
             place='Gorges de Daluis D 2202 longest tunnel (Alpes-Maritimes)',
             derived='tunnel', pick=dict(ref=r'^D ?2202$'), bbox=(43.98, 6.78, 44.05, 6.86), classes=TUNNEL_CLASSES)]),
    dict(cell='T2 short gorge (Pyrenees)', candidates=[
        dict(id='th02-luz', depts={'65'}, role='tunnel-holdout', category='short-gorge-tunnel',
             place='Gorges de Luz D 921 longest tunnel between Pierrefitte-Nestalas and Luz-Saint-Sauveur (Hautes-Pyrenees)',
             derived='tunnel', pick=dict(ref=r'^D ?921$'), bbox=(42.86, -0.09, 42.97, 0.01), classes=TUNNEL_CLASSES),
        dict(id='th02-pierre-lys', depts={'11'}, role='tunnel-holdout', category='short-gorge-tunnel',
             place='Defile de Pierre-Lys D 117 longest tunnel (Aude)',
             derived='tunnel', pick=dict(ref=r'^D ?117$'), bbox=(42.80, 2.17, 42.88, 2.27), classes=TUNNEL_CLASSES)]),
    dict(cell='T3 medium under a col', candidates=[
        dict(id='th03-bussang', depts={'88', '68'}, role='tunnel-holdout', category='medium-tunnel-under-col',
             place='Col de Bussang N 66 longest tunnel (Vosges / Haut-Rhin)',
             derived='tunnel', pick=dict(ref=r'^N ?66$'), bbox=(47.86, 6.82, 47.91, 6.89), classes=TUNNEL_CLASSES),
        dict(id='th03-tunnel-66', depts={'66'}, role='tunnel-holdout', category='medium-tunnel',
             place='Pyrenees-Orientales the longest 200-700 m tunnel',
             derived='tunnel', pick=dict(insee='66', length=(200.0, 700.0)), classes=TUNNEL_CLASSES),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th03-tunnel-74', '74', '74', 'medium-tunnel', 'Haute-Savoie the longest 200-700 m tunnel', (200.0, 700.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th03-tunnel-01', '01', '01', 'medium-tunnel', 'Ain the longest 200-700 m tunnel', (200.0, 700.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th03-tunnel-09', '09', '09', 'medium-tunnel', 'Ariege the longest 200-700 m tunnel', (200.0, 700.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th03-tunnel-63', '63', '63', 'medium-tunnel', 'Puy-de-Dome the longest 200-700 m tunnel', (200.0, 700.0))]),
    dict(cell='T4 medium curved', candidates=[
        dict(id='th04-curved-66', depts={'66'}, role='tunnel-holdout', category='medium-curved-tunnel',
             place='Pyrenees-Orientales the 100-700 m tunnel with the smallest chord / length',
             derived='tunnel', pick=dict(insee='66', order='curved', length=(100.0, 700.0)), classes=TUNNEL_CLASSES),
        dict(id='th04-curved-06', depts={'06'}, role='tunnel-holdout', category='medium-curved-tunnel',
             place='Alpes-Maritimes the 100-700 m tunnel with the smallest chord / length',
             derived='tunnel', pick=dict(insee='06', order='curved', length=(100.0, 700.0)), classes=TUNNEL_CLASSES),
        dict(id='th04-curved-2b', depts={'2B'}, role='tunnel-holdout', category='medium-curved-tunnel',
             place='Haute-Corse the 100-700 m tunnel with the smallest chord / length',
             derived='tunnel', pick=dict(insee='2B', order='curved', length=(100.0, 700.0)), classes=TUNNEL_CLASSES),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th04-curved-07', '07', '07', 'medium-curved-tunnel', 'Ardeche the 100-700 m tunnel with the smallest chord / length', (100.0, 700.0), order='curved'),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th04-curved-09', '09', '09', 'medium-curved-tunnel', 'Ariege the 100-700 m tunnel with the smallest chord / length', (100.0, 700.0), order='curved'),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th04-curved-63', '63', '63', 'medium-curved-tunnel', 'Puy-de-Dome the 100-700 m tunnel with the smallest chord / length', (100.0, 700.0), order='curved')]),
    dict(cell='T5 long', candidates=[
        dict(id='th05-long-01', depts={'01'}, role='tunnel-holdout', category='long-tunnel',
             place='Ain the longest 700-2500 m tunnel',
             derived='tunnel', pick=dict(insee='01', length=(700.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th05-long-74', depts={'74'}, role='tunnel-holdout', category='long-tunnel',
             place='Haute-Savoie the longest 700-2500 m tunnel',
             derived='tunnel', pick=dict(insee='74', length=(700.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th05-long-25', depts={'25'}, role='tunnel-holdout', category='long-tunnel',
             place='Doubs the longest 700-2500 m tunnel',
             derived='tunnel', pick=dict(insee='25', length=(700.0, 2500.0)), classes=TUNNEL_CLASSES),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th05-long-09', '09', '09', 'long-tunnel', 'Ariege the longest 700-2500 m tunnel', (700.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th05-long-63', '63', '63', 'long-tunnel', 'Puy-de-Dome the longest 700-2500 m tunnel', (700.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th05-long-2b', '2B', '2B', 'long-tunnel', 'Haute-Corse the longest 700-2500 m tunnel', (700.0, 2500.0))]),
    dict(cell='T6 urban bored under relief', candidates=[
        dict(id='th06-angouleme', depts={'16'}, role='tunnel-holdout', category='urban-bored-tunnel-under-relief',
             place='Angouleme (Charente) the longest 150-2500 m tunnel of the commune',
             derived='tunnel', pick=dict(insee='16015', length=(150.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th06-saint-etienne', depts={'42'}, role='tunnel-holdout', category='urban-bored-tunnel-under-relief',
             place='Saint-Etienne (Loire) the longest 150-2500 m tunnel of the commune',
             derived='tunnel', pick=dict(insee='42218', length=(150.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th06-besancon', depts={'25'}, role='tunnel-holdout', category='urban-bored-tunnel-under-relief',
             place='Besancon (Doubs) the longest 150-2500 m tunnel of the commune',
             derived='tunnel', pick=dict(insee='25056', length=(150.0, 2500.0)), classes=TUNNEL_CLASSES),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th06-le-puy', '43', '43157', 'urban-bored-tunnel-under-relief', 'Le Puy-en-Velay (Haute-Loire) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th06-clermont', '63', '63113', 'urban-bored-tunnel-under-relief', 'Clermont-Ferrand (Puy-de-Dome) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th06-annecy', '74', '74010', 'urban-bored-tunnel-under-relief', 'Annecy (Haute-Savoie) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0))]),
    dict(cell='T7 urban cut-and-cover', candidates=[
        dict(id='th07-montpellier', depts={'34'}, role='tunnel-holdout', category='urban-cut-and-cover',
             place='Montpellier (Herault) the longest 150-2500 m tunnel of the commune',
             derived='tunnel', pick=dict(insee='34172', length=(150.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th07-metz', depts={'57'}, role='tunnel-holdout', category='urban-cut-and-cover',
             place='Metz (Moselle) the longest 150-2500 m tunnel of the commune',
             derived='tunnel', pick=dict(insee='57463', length=(150.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th07-nancy', depts={'54'}, role='tunnel-holdout', category='urban-cut-and-cover',
             place='Nancy (Meurthe-et-Moselle) the longest 150-2500 m tunnel of the commune',
             derived='tunnel', pick=dict(insee='54395', length=(150.0, 2500.0)), classes=TUNNEL_CLASSES),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th07-rennes', '35', '35238', 'urban-cut-and-cover', 'Rennes (Ille-et-Vilaine) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th07-amiens', '80', '80021', 'urban-cut-and-cover', 'Amiens (Somme) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th07-limoges', '87', '87085', 'urban-cut-and-cover', 'Limoges (Haute-Vienne) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0)),
        # amendment 1 (2026-10-13 19:36)
        area_tunnel('th07-poitiers', '86', '86194', 'urban-cut-and-cover', 'Poitiers (Vienne) the longest 150-2500 m tunnel of the commune', (150.0, 2500.0))]),
    dict(cell='T8 greenway in a rail tunnel', candidates=[
        dict(id='th08-voie-verte-07', depts={'07'}, role='tunnel-holdout', category='greenway-in-rail-tunnel',
             place='Ardeche the longest 100-2500 m highway=cycleway tunnel',
             derived='tunnel', pick=dict(insee='07', highway={'cycleway'}, length=(100.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th08-voie-verte-43', depts={'43'}, role='tunnel-holdout', category='greenway-in-rail-tunnel',
             place='Haute-Loire the longest 100-2500 m highway=cycleway tunnel',
             derived='tunnel', pick=dict(insee='43', highway={'cycleway'}, length=(100.0, 2500.0)), classes=TUNNEL_CLASSES),
        dict(id='th08-voie-verte-24', depts={'24'}, role='tunnel-holdout', category='greenway-in-rail-tunnel',
             place='Dordogne the longest 100-2500 m highway=cycleway tunnel',
             derived='tunnel', pick=dict(insee='24', highway={'cycleway'}, length=(100.0, 2500.0)), classes=TUNNEL_CLASSES)]),
    # held-out negatives: centres and waypoints coded from OSM geometry only (rule 6)
    dict(cell='H1 hairpins', candidates=[
        dict(id='hn01-aubisque', depts={'64'}, role='tunnel-holdout-negative', category='hairpin-climb',
             place='Col d Aubisque D 918 lacets below Gourette on the Eaux-Bonnes side (Pyrenees-Atlantiques)',
             derived='along', selector=dict(ref=r'^D ?918$'), center=(42.9600, -0.3520), half=1200.0,
             bbox=around(42.9600, -0.3520, 2000), classes=CARS, hairpins=True)]),
    dict(cell='H2 balcony under a cliff', candidates=[
        dict(id='hn02-aveyron', depts={'82'}, role='tunnel-holdout-negative', category='balcony-under-cliff',
             place='Gorges de l Aveyron D 115 between Saint-Antonin-Noble-Val and Cazals (Tarn-et-Garonne)',
             derived='along', selector=dict(ref=r'^D ?115$'), center=(44.1400, 1.7350), half=1200.0, snap_m=2000.0,
             bbox=around(44.1400, 1.7350, 3000), classes=CARS)]),
    dict(cell='H3 steep urban ramp', candidates=[
        dict(id='hn03-laon', depts={'02'}, role='tunnel-holdout-negative', category='steep-urban-ramp',
             place='Laon (Aisne) streets from the lower town (station) to the upper town (cathedral)',
             waypoints=[(49.5706, 3.6207), (49.5641, 3.6247)], classes=STREETS, max_detour=2.5, snap_m=200.0,
             coverage_failed='IGN MNT z15 NaN at 381 / 381 stations (no LiDAR HD MNT served)'),
        dict(id='hn03-boulogne', depts={'62'}, role='tunnel-holdout-negative', category='steep-urban-ramp',
             place='Boulogne-sur-Mer (Pas-de-Calais) streets from the lower town to the upper town',
             waypoints=[(50.7247, 1.6062), (50.7262, 1.6125)], classes=STREETS, max_detour=2.5, snap_m=200.0)]),
    dict(cell='H4 dam crest', candidates=[
        dict(id='hn04-vouglans', depts={'39'}, role='tunnel-holdout-negative', category='dam-crest-road',
             place='Barrage de Vouglans the road over the crest (Jura)',
             derived='along', center=(46.3975, 5.6650), half=700.0,
             bbox=around(46.3975, 5.6650, 1500), classes=CARS),
        dict(id='hn04-panneciere', depts={'58'}, role='tunnel-holdout-negative', category='dam-crest-road',
             place='Barrage de Panneciere-Chaumard D 303 over the dam (Nievre)',
             derived='along', selector=dict(ref=r'^D ?303$'), center=(47.1640, 3.8743), half=600.0,
             bbox=around(47.1640, 3.8743, 1400), classes=CARS,
             coverage_failed='IGN MNT z15 NaN at 131 / 131 stations (no LiDAR HD MNT served)')]),
    dict(cell='H5 cutting', candidates=[
        dict(id='hn05-cutting-46', depts={'46'}, role='tunnel-holdout-negative', category='road-in-cutting',
             place='Lot the longest cutting=yes road chain of the classes (>= 100 m)',
             derived='tagged', pick=dict(insee='46', tags={'cutting': 'yes'}, length=(100.0, 3000.0)), classes=STREETS),
        dict(id='hn05-cutting-19', depts={'19'}, role='tunnel-holdout-negative', category='road-in-cutting',
             place='Correze the longest cutting=yes road chain of the classes (>= 100 m)',
             derived='tagged', pick=dict(insee='19', tags={'cutting': 'yes'}, length=(100.0, 3000.0)), classes=STREETS)]),
    dict(cell='H6 col', candidates=[
        dict(id='hn06-bavella', depts={'2A'}, role='tunnel-holdout-negative', category='road-over-col',
             place='Col de Bavella D 268 on both sides of the col (Corse-du-Sud)',
             derived='along', selector=dict(ref=r'^D ?268$'), center=(41.7950, 9.2240), half=1500.0,
             bbox=around(41.7950, 9.2240, 2500), classes=CARS)]),
    # amendment 2 (2026-10-13 20:02): complement cell, no stratum, up to four more sites
    dict(cell='T9 complement', keep=4, candidates=[
        area_tunnel(f'th09-tunnel-{d.lower()}', d, d, 'tunnel', f'{name} the longest 50-2500 m tunnel', (50.0, 2500.0))
        for d, name in [('34', 'Herault'), ('07', 'Ardeche'), ('2B', 'Haute-Corse'), ('43', 'Haute-Loire'),
                        ('46', 'Lot'), ('19', 'Correze'), ('81', 'Tarn'), ('65', 'Hautes-Pyrenees')]]),
]


# ── Station kinds ────────────────────────────────────────────────────────────────────────────

def runs_of(mask):
    m = np.concatenate([[False], mask, [False]]).astype(int)
    d = np.diff(m)
    return list(zip(np.where(d == 1)[0].tolist(), (np.where(d == -1)[0] - 1).tolist()))


def build(site):
    if site.get('pick', {}).get('insee') and 'picked_ways' not in site:
        resolve_area_pick(site)
    osm = osm_for(site)
    if site.get('derived'):
        wps, sel, how = DERIVED[site['derived']](site, osm)
    else:
        wps, sel, how = site['waypoints'], site.get('selector'), 'waypoints'
    oneway = bool(site['classes'] & (MOTOR | MOTOR_LINKS))
    nodes, ways, lat0, lon0, length = route(osm, wps, site['classes'], sel, oneway,
                                            site.get('max_detour', 12.0 if site.get('derived') == 'along' else 1.3),
                                            site.get('snap_m', SNAP_M) if not site.get('derived') else SNAP_M)

    # Resample exactly like fetch.py.
    d = np.concatenate([[0], np.cumsum(hav(lat0[:-1], lon0[:-1], lat0[1:], lon0[1:]))])
    s = np.arange(0, d[-1], STEP)
    lat = np.interp(s, d, lat0)
    lon = np.interp(s, d, lon0)
    seg = np.clip(np.searchsorted(d, s, side='right') - 1, 0, len(ways) - 1)
    way = np.array([ways[j] for j in seg], dtype=np.int64)
    n = len(s)

    proj = Proj(float(lat.mean()))
    X, Y = proj.xy(lat, lon)
    PX, PY = proj.xy(lat0, lon0)
    heading = np.degrees(np.arctan2(PY[1:] - PY[:-1], PX[1:] - PX[:-1]))[seg]
    positive = site['role'] in POSITIVE_ROLES
    tpositive = site['role'] in TUNNEL_POSITIVE_ROLES
    own = set(ways)

    D = np.zeros(n, bool)
    N = np.zeros(n, bool)
    Xs = np.zeros(n, bool)
    O = np.zeros(n, bool)
    L = np.zeros(n, bool)
    src = [''] * n
    o_src = []
    # structure behind each D/X/N station: (bridge|tunnel|gallery|layer|underpass, way id, label)
    struct = [None] * n
    for k in range(n):
        t = osm.tags(int(way[k]))
        if tpositive and own_tunnel(t):
            N[k] = True
        elif positive and own_bridge(t):
            D[k] = True
        elif own_structure(t):
            Xs[k] = True
        if D[k] or Xs[k] or N[k]:
            struct[k] = (structure_kind(t), int(way[k]), label_of(t, int(way[k])))
        if t.get('tunnel') == 'building_passage' or t.get('covered') == 'yes':
            O[k] = True
            src[k] = src[k] or label_of(t, int(way[k]))
            o_src.append(int(way[k]))

    path_nodes = list(zip(nodes[:-1], nodes[1:]))

    def crossings_of(wid):
        """(s on the route, half-window of the way's width along the route) of each proper crossing"""
        el = osm.ways[wid]
        g = el['geometry']
        gx, gy = proj.xy([p['lat'] for p in g], [p['lon'] for p in g])
        wn = el['nodes']
        out = []
        if gx.max() < PX.min() - 50 or gx.min() > PX.max() + 50 or gy.max() < PY.min() - 50 or gy.min() > PY.max() + 50:
            return out
        for i in range(len(gx) - 1):
            for j, (na, nb) in enumerate(path_nodes):
                if {na, nb} & {wn[i], wn[i + 1]}:
                    continue
                r = intersect((PX[j], PY[j]), (PX[j + 1], PY[j + 1]), (gx[i], gy[i]), (gx[i + 1], gy[i + 1]))
                if r is None:
                    continue
                sx = d[j] + r[0] * (d[j + 1] - d[j])
                ang = abs(math.atan2(PX[j + 1] - PX[j], PY[j + 1] - PY[j]) - math.atan2(gx[i + 1] - gx[i], gy[i + 1] - gy[i]))
                sin = max(abs(math.sin(ang)), 0.3)
                out.append((float(sx), width_of(osm.tags(wid)) / 2 / sin))
        return out

    # (u) tunnels and culverts crossing under the route: the route rides an untagged deck there.
    # X with its x margin, on negatives and positives alike (a D station keeps D).
    unders = []
    for wid in sorted(osm.ways):
        if wid in own or not is_under(osm.tags(wid)):
            continue
        for sx, half in crossings_of(wid):
            m = np.abs(s - sx) <= max(half, STEP / 2)
            for k in np.where(m & ~D & ~N)[0]:
                Xs[k] = True
                struct[k] = struct[k] or ('underpass', wid, 'under ' + label_of(osm.tags(wid), wid))
            unders.append((wid, sx))

    # (a) elevated ways crossing the path.
    xings = []
    elevated = [wid for wid in sorted(osm.ways) if wid not in own and is_elevated(osm.tags(wid))]
    for wid in elevated:
        for sx, half in crossings_of(wid):
            m = np.abs(s - sx) <= half + OVERHEAD_SLACK_M
            O |= m
            for k in np.where(m)[0]:
                src[k] = src[k] or label_of(osm.tags(wid), wid)
            xings.append((wid, sx))
            o_src.append(wid)

    # (c) canopy / roof polygons and toll booths.
    for wid, el in sorted(osm.ways.items()):
        t = el.get('tags', {})
        if not (t.get('building') == 'roof' or t.get('man_made') == 'canopy'):
            continue
        if el['nodes'][0] != el['nodes'][-1]:
            continue
        g = el['geometry']
        gx, gy = proj.xy([p['lat'] for p in g], [p['lon'] for p in g])
        if gx.max() < X.min() or gx.min() > X.max() or gy.max() < Y.min() or gy.min() > Y.max():
            continue
        m = in_polygon(X, Y, gx, gy)
        if m.any():
            O |= m
            for k in np.where(m)[0]:
                src[k] = src[k] or ('canopy' if t.get('man_made') == 'canopy' else 'roof') + f' way {wid}'
            o_src.append(wid)
    for la, lo in osm.tolls:
        m = hav(lat, lon, la, lo) <= TOLL_M
        if m.any():
            O |= m
            for k in np.where(m)[0]:
                src[k] = src[k] or 'toll booth'

    # L: elevated bridges alongside. A way that also crosses the path (Metro 6 runs beside bd de
    # Grenelle, then over bd Garibaldi) is L where it runs beside and O at its crossing: O wins.
    lsrc = [''] * n
    best = np.full(n, np.inf)
    for wid in [w for w in elevated if is_lateral(osm.tags(w))]:
        g = osm.ways[wid]['geometry']
        gx, gy = proj.xy([p['lat'] for p in g], [p['lon'] for p in g])
        if gx.max() < X.min() - LATERAL_M or gx.min() > X.max() + LATERAL_M or gy.max() < Y.min() - LATERAL_M or gy.min() > Y.max() + LATERAL_M:
            continue
        dd, _ = seg_dist(X, Y, gx[:-1], gy[:-1], gx[1:], gy[1:])
        j = np.argmin(dd, axis=1)
        dmin = dd[np.arange(n), j]
        wh = np.degrees(np.arctan2(gy[1:] - gy[:-1], gx[1:] - gx[:-1]))[j]
        diff = np.abs((heading - wh + 90) % 180 - 90)
        ok = (dmin <= LATERAL_M) & (diff <= LATERAL_HEADING_DEG)
        upd = ok & (dmin < best)
        best[upd] = dmin[upd]
        for k in np.where(upd)[0]:
            lsrc[k] = label_of(osm.tags(wid), wid)
    for a, b in runs_of(np.isfinite(best)):
        if b - a + 1 >= LATERAL_MIN_STATIONS:
            L[a:b + 1] = True

    hard = D | Xs | N
    margin = np.zeros(n, bool)
    for k in np.where(hard)[0]:
        margin[max(0, k - ABUTMENT_STATIONS):k + ABUTMENT_STATIONS + 1] = True
    kind = np.full(n, 'G')
    kind[L] = 'L'
    kind[O] = 'O'
    kind[margin] = 'x'
    kind[Xs] = 'X'
    kind[N] = 'N'
    kind[D] = 'D'
    names = []
    for k in range(n):
        c = kind[k]
        if c in 'DXN':
            names.append(struct[k][2])
        elif c == 'O':
            names.append(src[k])
        elif c == 'L':
            names.append(lsrc[k])
        else:
            names.append('')
    spans = []
    for c in 'DXOLN':
        for a, b in runs_of(kind == c):
            nm = '; '.join(dict.fromkeys(x for x in names[a:b + 1] if x)).replace(',', ' ')
            st, sw = (struct[a][0], struct[a][1]) if c in 'DXN' else ('', 0)
            spans.append((c, a, b, nm, st, sw))
    spans.sort(key=lambda r: (r[1], r[0]))
    return dict(site=site, osm=osm, how=how, sel=sel, nodes=nodes, ways=ways, lat0=lat0, lon0=lon0, length=length,
                s=s, lat=lat, lon=lon, way=way, kind=kind, spans=spans, crossings=xings, o_src=o_src, names=names,
                unders=unders)


# ── Checks ───────────────────────────────────────────────────────────────────────────────────

def frac_where(res, pred):
    ws = res['way']
    return float(np.mean([bool(pred(res['osm'].tags(int(w)))) for w in ws]))


def geometry_checks(res):
    """every site: no U-turn and no doubling back (a hairpin puts rolling windows across itself and
    samples the same ground twice); held-out D spans of 20 m or more keep END_MARGIN_M of route
    beyond them on both sides (DeckDetector clamps the canyon's outside MNT at the route ends)."""
    out = []
    proj = Proj(float(res['lat'].mean()))
    X, Y = proj.xy(res['lat'], res['lon'])
    n = len(X)
    if res['site'].get('hairpins'):
        # E4 hairpin negatives: the U-turns are the point of the site (rule 4 of the tunnel series)
        out.append((True, 'hairpin site: U-turn and doubling-back checks waived'))
        return out + tunnel_ground_checks(res)
    h = 3                                   # 15 m each side
    worst = (0.0, -1)
    for k in range(h, n - h):
        a = math.atan2(Y[k] - Y[k - h], X[k] - X[k - h])
        b = math.atan2(Y[k + h] - Y[k], X[k + h] - X[k])
        turn = abs((math.degrees(b - a) + 180) % 360 - 180)
        if turn > worst[0]:
            worst = (turn, k)
    out.append((worst[0] <= UTURN_DEG, f'max heading change over 2 x 15 m {worst[0]:.0f} deg at s={worst[1] * STEP:.0f} (<= {UTURN_DEG:.0f})'))
    dd = np.hypot(X[:, None] - X[None], Y[:, None] - Y[None])
    far = np.abs(np.arange(n)[:, None] - np.arange(n)[None]) * STEP > UTURN_ALONG_M
    bad = np.argwhere(far & (dd < UTURN_NEAR_M))
    msg = f'no station within {UTURN_NEAR_M:.0f} m of one > {UTURN_ALONG_M:.0f} m along'
    if len(bad):
        i, j = bad[0]
        msg += f': s={i * STEP:.0f} and s={j * STEP:.0f} are {dd[i, j]:.1f} m apart ({len(bad) // 2} pairs)'
    out.append((len(bad) == 0, msg))
    if res['site']['role'] in POSITIVE_ROLES:
        last = (n - 1) * STEP
        short = [(a * STEP, last - b * STEP) for c, a, b, *_ in res['spans'] if c == 'D' and (b - a + 1) * STEP >= 20
                 and min(a * STEP, last - b * STEP) < END_MARGIN_M]
        out.append((not short, f'D spans >= 20 m with < {END_MARGIN_M:.0f} m of route before/after: {short}'))
    return out + tunnel_ground_checks(res)


def n_groups(spans):
    """counted N spans (>= 20 m) of a tunnel positive, merged when less than GROUND_M apart"""
    groups = []
    for c, a, b, *_ in sorted(spans, key=lambda r: r[1]):
        if c != 'N' or (b - a + 1) * STEP < 20:
            continue
        if groups and (a - groups[-1][1] - 1) * STEP < GROUND_M:
            groups[-1][1] = b
        else:
            groups.append([a, b])
    return groups


def tunnel_ground_checks(res):
    """tunnel positives (rule 4 of the E4 series): GROUND_M of route beyond every group of counted N
    spans, both sides, without any N or X station in it, so groups are also GROUND_M apart."""
    if res['site']['role'] not in TUNNEL_POSITIVE_ROLES:
        return []
    kind = res['kind']
    n = len(kind)
    w = int(round(GROUND_M / STEP))
    groups = n_groups(res['spans'])
    if res['site'].get('x_near_ok'):
        # tuning only: a tunnel among avalanche galleries (tt08); the galleries stay X, excluded
        return [(bool(groups), f'{len(groups)} counted N group(s) (>= 1)'),
                (True, 'X within the ground margin allowed for this tuning site (x_near_ok)')]
    bad = []
    for a, b in groups:
        before = kind[max(0, a - w):a]
        after = kind[b + 1:b + 1 + w]
        if a < w or n - 1 - b < w or any(c in 'NX' for c in before) or any(c in 'NX' for c in after):
            bad.append((a * STEP, b * STEP, ''.join(sorted(set(before))), ''.join(sorted(set(after)))))
    return [(bool(groups), f'{len(groups)} counted N group(s) (>= 1)'),
            (not bad, f'N groups without {GROUND_M:.0f} m of ground (no N/X) on both sides: {bad}')]


def run_checks(res):
    out = geometry_checks(res)
    kind = res['kind']
    spans = res['spans']
    osm = res['osm']

    def span_len(a, b):
        return (b - a + 1) * STEP
    for chk in res['site'].get('checks', []):
        name = chk[0]
        if name == 'name_frac':
            f = frac_where(res, lambda t: re.search(chk[1], t.get('name', '')))
            out.append((f >= chk[2], f'{f:.0%} of stations on name ~ {chk[1]} (>= {chk[2]:.0%})'))
        elif name == 'selector_frac':
            fn = selector_fn(res['sel'])
            f = frac_where(res, fn) if fn else 0.0
            out.append((f >= chk[1], f'{f:.0%} of stations on the selector (>= {chk[1]:.0%})'))
        elif name == 'class_frac':
            f = frac_where(res, lambda t: t.get('highway') in chk[1])
            out.append((f >= chk[2], f'{f:.0%} of stations on {sorted(chk[1])} (>= {chk[2]:.0%})'))
        elif name == 'min_len':
            out.append((res['length'] >= chk[1], f'length {res["length"]:.0f} m (>= {chk[1]})'))
        elif name == 'near_rail':
            proj = Proj(float(res['lat'].mean()))
            X, Y = proj.xy(res['lat'], res['lon'])
            hit = []
            for wid, el in osm.ways.items():
                t = el.get('tags', {})
                if t.get('railway') not in RAILWAYS or not is_elevated(t):
                    continue
                if not (re.search(chk[1], t.get('ref', '')) or re.search(chk[1], t.get('name', ''))):
                    continue
                g = el['geometry']
                gx, gy = proj.xy([p['lat'] for p in g], [p['lon'] for p in g])
                dd, _ = seg_dist(X, Y, gx[:-1], gy[:-1], gx[1:], gy[1:])
                if dd.min() <= chk[2]:
                    hit.append(wid)
            out.append((bool(hit), f'elevated railway ~ {chk[1]} within {chk[2]:.0f} m: {hit[:5]}'))
        elif name == 'overhead':
            hit = sorted({w for w in res['o_src'] if w in osm.ways and re.search(chk[1], osm.tags(w).get('ref', ''))})
            out.append((len(hit) >= chk[2], f'overhead ways with ref ~ {chk[1]}: {hit}'))
        elif name == 'overhead_rail':
            hit = sorted({w for w in res['o_src'] if w in osm.ways and osm.tags(w).get('railway') in RAILWAYS
                          and osm.tags(w).get('bridge', 'no') != 'no'})
            out.append((len(hit) >= chk[1], f'overhead railway bridges: {hit}'))
        elif name == 'o_spans':
            c = sum(1 for sp in spans if sp[0] == 'O')
            out.append((c >= chk[1], f'{c} O spans (>= {chk[1]})'))
        elif name == 'x_spans':
            c = sum(1 for sp in spans if sp[0] == 'X')
            out.append((c >= chk[1], f'{c} X spans (>= {chk[1]})'))
        elif name == 'near_water_frac':
            proj = Proj(float(res['lat'].mean()))
            X, Y = proj.xy(res['lat'], res['lon'])
            best = np.full(len(X), np.inf)
            for wid, el in osm.ways.items():
                t = el.get('tags', {})
                if t.get('waterway') != chk[2] or not re.search(chk[1], t.get('name', '')):
                    continue
                g = el['geometry']
                gx, gy = proj.xy([p['lat'] for p in g], [p['lon'] for p in g])
                dd, _ = seg_dist(X, Y, gx[:-1], gy[:-1], gx[1:], gy[1:])
                best = np.minimum(best, dd.min(axis=1))
            f = float(np.mean(best <= chk[3]))
            out.append((f >= chk[4], f'{f:.0%} of stations within {chk[3]:.0f} m of {chk[2]} ~ {chk[1]} (>= {chk[4]:.0%})'))
        elif name == 'd_span_min':
            ls = [span_len(a, b) for c, a, b, *_ in spans if c == 'D']
            out.append((bool(ls) and max(ls) >= chk[1], f'longest D span {max(ls) if ls else 0:.0f} m (>= {chk[1]})'))
        elif name == 'd_span_range':
            ls = [span_len(a, b) for c, a, b, *_ in spans if c == 'D']
            ok = any(chk[1] <= v <= chk[2] for v in ls)
            out.append((ok, f'D spans {ls} m (one in {chk[1]}-{chk[2]})'))
        elif name == 'd_spans':
            ls = [span_len(a, b) for c, a, b, *_ in spans if c == 'D' and span_len(a, b) >= 20]
            out.append((len(ls) >= chk[1], f'{len(ls)} D spans >= 20 m {ls} (>= {chk[1]})'))
        elif name == 'd_name':
            ok = any(c == 'D' and re.search(chk[1], nm) for c, a, b, nm, *_ in spans)
            if not ok:
                ok = any(kind[k] == 'D' and re.search(chk[1], ' '.join(osm.tags(int(res['way'][k])).get(x, '') for x in ('name', 'alt_name')))
                         for k in range(len(kind)))
            out.append((ok, f'a D span named ~ {chk[1]}'))
        elif name == 'd_over_water':
            proj = Proj(float(res['lat'].mean()))
            hit = False
            for wid, el in osm.ways.items():
                t = el.get('tags', {})
                if t.get('waterway') != 'river' or not re.search(chk[1], t.get('name', '')):
                    continue
                for c, a, b, *_ in spans:
                    if c != 'D':
                        continue
                    PX, PY = proj.xy(res['lat'][a:b + 1], res['lon'][a:b + 1])
                    g = el['geometry']
                    gx, gy = proj.xy([p['lat'] for p in g], [p['lon'] for p in g])
                    for i in range(len(gx) - 1):
                        for j in range(len(PX) - 1):
                            if intersect((PX[j], PY[j]), (PX[j + 1], PY[j + 1]), (gx[i], gy[i]), (gx[i + 1], gy[i + 1])):
                                hit = True
            out.append((hit, f'a D span crosses waterway=river ~ {chk[1]}'))
        else:
            raise ValueError(name)
    return out


# ── Output ───────────────────────────────────────────────────────────────────────────────────

KIND_LEGEND = ('# kind: G ground | O overhead structure | L parallel bridge or rail viaduct within 20 m | '
               'X own structure or underpass (excluded) | x 35 m abutment margin (excluded) | D own bridge deck (held-out positive)')


TUNNEL_KIND_LEGEND = ('# kind: G ground | O overhead structure | L parallel bridge or rail viaduct within 20 m | '
                      'X other structure, gallery or underpass (excluded) | x 35 m margin (excluded) | '
                      'N own tunnel (tunnel positive; truth = line between the MNT at the stations just outside it)')


class Series:
    """where a series writes, and its own-structure letter (D decks, N tunnels)"""
    def __init__(self, gpx_dir, truth_dir, kinds, legend):
        self.gpx_dir, self.truth_dir, self.kinds, self.legend = gpx_dir, truth_dir, kinds, legend


DECKS = Series(GPX_DIR, TRUTH_DIR, 'GOLXxD', KIND_LEGEND)
TUNNELS = Series(TUNNEL_GPX_DIR, TUNNEL_TRUTH_DIR, 'GOLXxN', TUNNEL_KIND_LEGEND)


def counts(kind, kinds='GOLXxD'):
    return {c: int(np.sum(kind == c)) for c in kinds}


def write_site(res, series=DECKS):
    site = res['site']
    sid = site['id']
    os.makedirs(series.gpx_dir, exist_ok=True)
    os.makedirs(series.truth_dir, exist_ok=True)
    with open(os.path.join(series.gpx_dir, sid + '.gpx'), 'w', encoding='utf-8', newline='\n') as f:
        f.write('<?xml version="1.0" encoding="UTF-8"?>\n')
        f.write('<gpx creator="vcyclist controls.py" version="1.1" xmlns="http://www.topografix.com/GPX/1/1">\n')
        f.write(' <metadata>\n')
        f.write(f'  <name>{sid}</name>\n')
        f.write('  <copyright author="OpenStreetMap contributors">\n')
        f.write('   <license>https://www.openstreetmap.org/copyright</license>\n')
        f.write('  </copyright>\n')
        f.write(' </metadata>\n')
        f.write(f' <trk>\n  <name>{sid}</name>\n  <trkseg>\n')
        for la, lo in zip(res['lat0'], res['lon0']):
            f.write(f'   <trkpt lat="{la:.7f}" lon="{lo:.7f}"></trkpt>\n')
        f.write('  </trkseg>\n </trk>\n</gpx>\n')
    n = len(res['s'])
    with open(os.path.join(series.truth_dir, sid + '.csv'), 'w', newline='\n') as f:
        f.write(f'# control={sid} role={site["role"]} category={site["category"]} step_m=5 n={n} '
                f'osm_base={res["osm"].base} length_m={res["length"]:.1f} built_from={res["how"].replace(" ", "_")}\n')
        f.write(series.legend + '\n')
        f.write('s,lat,lon,kind,way\n')
        for k in range(n):
            f.write(f'{int(round(res["s"][k]))},{res["lat"][k]:.6f},{res["lon"][k]:.6f},{res["kind"][k]},{int(res["way"][k])}\n')


def write_tables(results, series=DECKS):
    with open(os.path.join(series.truth_dir, 'index.csv'), 'w', newline='\n') as f:
        f.write('id,role,category,n,length_m,' + ','.join(series.kinds) + ',place\n')
        for res in results:
            site = res['site']
            c = counts(res['kind'], series.kinds)
            f.write(f'{site["id"]},{site["role"]},{site["category"]},{len(res["s"])},{res["length"]:.1f},'
                    + ''.join(f'{c[k]},' for k in series.kinds) + f'{site["place"].replace(",", " ")}\n')
    with open(os.path.join(series.truth_dir, 'spans.csv'), 'w', newline='\n') as f:
        f.write('id,kind,i0,i1,s0,s1,way,structure,name\n')
        for res in results:
            for c, a, b, nm, st, w in res['spans']:
                f.write(f'{res["site"]["id"]},{c},{a},{b},{a * 5},{b * 5},{w},{st},{nm}\n')


def main(args):
    series = DECKS
    pool = SITES
    if args and args[0] == '--tunnels':
        series, pool, args = TUNNELS, TUNNEL_SITES, args[1:]
    results, failed, taken = [], [], set()
    for entry in pool:
        cands = entry.get('candidates', [entry])
        if args and not any(c['id'].startswith(a) for c in cands for a in args):
            continue
        res = None
        kept = []
        for site in cands:
            print(f'== {site["id"]} ({site["role"]}, {site["category"]})' + (f' [cell {entry["cell"]}]' if 'cell' in entry else ''), flush=True)
            if site.get('depts', set()) & taken:
                print(f'  SKIPPED (rule 3): department {sorted(site["depts"] & taken)} already held by a kept held-out site', flush=True)
                continue
            res = try_site(site, series)
            if res is not None:
                taken |= site.get('depts', set())
                kept.append(res)
                if len(kept) >= entry.get('keep', 1):
                    break
                continue
            if 'cell' not in entry:
                failed.append(site['id'])
        results.extend(kept)
        if not kept and 'cell' in entry:
            print(f'  CELL EMPTY: {entry["cell"]} (no candidate left)', flush=True)
    if failed:
        print('FAILED:', failed)
        sys.exit(1)
    if args:
        print('dry run (site subset): nothing written')
        return
    for res in results:
        write_site(res, series)
    write_tables(results, series)
    print(f'wrote {len(results)} controls')


def flat_tunnel_sites():
    return [c for e in TUNNEL_SITES for c in e.get('candidates', [e])]


def try_site(site, series):
    """build one site and run its checks: the result, or None (printed) when it fails"""
    if site.get('coverage_failed'):
        print(f'  FAILED coverage (rule 5): {site["coverage_failed"]}', flush=True)
        return None
    try:
        res = build(site)
    except RuntimeError as ex:
        print(f'  FAILED to build: {ex}', flush=True)
        return None
    c = counts(res['kind'], series.kinds)
    used = []
    for w in res['ways']:
        if not used or used[-1] != w:
            used.append(w)
    names = list(dict.fromkeys(label_of(res['osm'].tags(w), w) for w in used))
    print(f'  {res["how"]}; length {res["length"]:.0f} m, {len(res["s"])} stations, {len(set(used))} ways: {"; ".join(names)[:300]}')
    print('  kinds ' + ' '.join(f'{k}={v}' for k, v in c.items()))
    for cc, a, b, nm, st, _ in res['spans']:
        print(f'    {cc} {a:4d}..{b:4d}  {a * 5:5d}-{b * 5:5d} m  {(b - a + 1) * 5:5d} m  {st:9s} {nm[:90]}')
    ok = True
    for passed, msg in run_checks(res):
        print(f'  check {"ok  " if passed else "FAIL"} {msg}')
        ok &= passed
    if not ok:
        print('  FAILED checks', flush=True)
    return res if ok else None


if __name__ == '__main__':
    main(sys.argv[1:])
