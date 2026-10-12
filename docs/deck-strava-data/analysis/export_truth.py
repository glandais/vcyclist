"""Stage 4: freeze the estimated roadway as the truth of the E1 bench (ElevationBenchProbeTest).

Reads <name>.npz (lat/lon, from fetch.py) and <name>_out.npz (road/mnt/sc, from analyze.py) plus
results.json; writes truth/<name>.csv (one row per 5 m station), truth/<name>_mns.csv (fetch.py's
MNS sample at each station: with the `mnt` column it is the exact input of analyze.py's bridge
detector, which DeckDetector's parity test replays) and truth/structures.csv (bridge, tunnel,
balcony and missed-bridge spans). Altitudes are NGF-IGN69, the MNT frame. Strava is not in
the file: GPX ele - strava_offset_m (in the header) reproduces it.

On balcony spans the MNT at the track point reads the cliff or the gorge, which is the very error
the bench measures there, so their truth is Strava shifted onto the MNT frame (sc = strava -
strava_offset_m, kind S): the analysis found Strava flat and right on those spans.

    python3 -I export_truth.py [SRC_DIR] [OUT_DIR]

SRC_DIR defaults to this directory (where fetch.py/analyze.py leave their npz), OUT_DIR to truth/.
"""
import os, sys, json
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = sys.argv[1] if len(sys.argv) > 1 else HERE
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(HERE, 'truth')
STEP = 5.0


def kinds(n, res, road, mnt, balconies):
    k = np.full(n, 'T', dtype='<U1')                      # T terrain, truth = MNT
    for b in res['bridges']:
        k[b['i0']:b['i1'] + 1] = 'b'                      # b bridge span off deck (MNH <= 1 m), truth = MNT
    k[np.abs(road - mnt) > 1e-9] = 'B'                    # B deck, truth = MNS median 25 m
    for t in res['tunnels']:
        k[t['i0']:t['i1'] + 1] = 'N'                      # N tunnel, truth = linear between portals
    for a, b in balconies:
        assert (k[a:b + 1] == 'T').all(), 'balcony span overlaps a structure'
        k[a:b + 1] = 'S'                                  # S balcony, truth = Strava - strava_offset_m
    return k


def clean(names):
    return '; '.join(names).replace(',', ' ')


def main():
    os.makedirs(OUT, exist_ok=True)
    allres = json.load(open(os.path.join(SRC, 'results.json')))
    srows = ['route,kind,i0,i1,s0,s1,lat0,lon0,lat1,lon1,z0,z1,zmax,name,flag']
    for name, res in allres.items():
        z = np.load(os.path.join(SRC, name + '.npz'))
        o = np.load(os.path.join(SRC, name + '_out.npz'))
        lat, lon, s, road, mnt, sc = z['lat'], z['lon'], o['s'], o['road'], o['mnt'], o['sc']
        assert np.allclose(z['s'], s) and not np.isnan(road).any()
        assert np.allclose(np.diff(s), STEP)
        n = len(s)

        def idx(km):
            return min(n - 1, int(round(km * 1000 / STEP)))

        # the six worst cliff runs of the analysis; flag = max |MNT - Strava|
        balconies = [(idx(x['km0']), idx(x['km1']), x['dmax']) for x in (res.get('balcony') or {}).get('top', [])]
        k = kinds(n, res, road, mnt, [(a, b) for a, b, _ in balconies])
        road = np.where(k == 'S', sc, road)
        with open(os.path.join(OUT, name + '.csv'), 'w', newline='\n') as f:
            f.write(f'# route={name} step_m={STEP:g} n={n} datum=NGF-IGN69 strava_offset_m={res["offset"]:.3f}\n')
            f.write('# kind: T terrain=MNT | B deck=MNS median 25 m | b bridge span off-deck=MNT | N tunnel=linear between portals'
                    ' | S balcony=Strava - strava_offset_m\n')
            f.write('s,lat,lon,truth,mnt,kind\n')
            for i in range(n):
                f.write(f'{s[i]:.0f},{lat[i]:.6f},{lon[i]:.6f},{road[i]:.2f},{mnt[i]:.2f},{k[i]}\n')
        with open(os.path.join(OUT, name + '_mns.csv'), 'w', newline='\n') as f:
            f.write(f'# route={name} step_m={STEP:g} n={n} datum=NGF-IGN69 source=fetch.py IGN LiDAR HD MNS, bilinear,'
                    ' 0.008 deg tiles of 256 px\n')
            f.write('mns\n')
            for v in z['mns']:
                f.write(f'{v:.2f}\n')

        def row(kind, a, b, nm, flag):
            srows.append(f'{name},{kind},{a},{b},{s[a]:.0f},{s[b]:.0f},{lat[a]:.6f},{lon[a]:.6f},{lat[b]:.6f},{lon[b]:.6f},'
                         f'{road[a]:.2f},{road[b]:.2f},{road[a:b + 1].max():.2f},{nm},{flag}')

        for x in res['bridges']:                          # flag = how Strava rides it
            row('bridge', x['i0'], x['i1'], clean(x['osm']), x['behaviour'].split(' (')[0])
        for x in res['tunnels']:                          # flag = whether Strava handled it
            row('tunnel', x['i0'], x['i1'], clean(x['osm']), 'undetected' if x.get('undetected') else 'handled')
        for a, b, dmax in balconies:
            row('balcony', a, b, '', f'dmax={dmax:.1f}')
        for x in res['osm_missed']:                       # OSM bridge, not detected: truth stays on MNT
            row('missed', idx(x['km0']), idx(x['km1']), clean(x['name']), f'mnh={x["mnh_max"]:.1f}')
    with open(os.path.join(OUT, 'structures.csv'), 'w', newline='\n') as f:
        f.write('\n'.join(srows) + '\n')


if __name__ == '__main__':
    main()
