"""Stage 1: parse GPX, resample at 5 m, sample IGN LiDAR HD MNT/MNS (bilinear), save npz."""
import sys, os, re, math, time, threading, urllib.request, urllib.error
import numpy as np
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
CACHE = os.path.join(HERE, 'cache')
GPXDIR = os.path.dirname(HERE)
D = 0.008          # tile size in degrees
N = 256
PX = D / N
STEP = 5.0
LAYERS = {
    'mnt': 'IGNF_LIDAR-HD_MNT_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G',
    'mns': 'IGNF_LIDAR-HD_MNS_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G',
    'mnh': 'IGNF_LIDAR-HD_MNH_ELEVATION.ELEVATIONGRIDCOVERAGE.WGS84G',
}
URL = ('https://data.geopf.fr/wms-r/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap&LAYERS={L}'
       '&STYLES=normal&CRS=EPSG:4326&BBOX={s},{w},{n},{e}&WIDTH=256&HEIGHT=256'
       '&FORMAT=image%2Fx-bil%3Bbits%3D32')

_lock = threading.Lock()
_last = [0.0]
RATE = 12.0


def throttle():
    with _lock:
        now = time.time()
        wait = _last[0] + 1.0 / RATE - now
        if wait > 0:
            time.sleep(wait)
        _last[0] = time.time()


def tile_path(layer, ti, tj):
    return os.path.join(CACHE, f'{layer}_{ti}_{tj}.bil')


def fetch_tile(layer, ti, tj):
    p = tile_path(layer, ti, tj)
    if os.path.exists(p) and os.path.getsize(p) == N * N * 4:
        return p
    s, n = ti * D, (ti + 1) * D
    w, e = tj * D, (tj + 1) * D
    url = URL.format(L=LAYERS[layer], s=f'{s:.6f}', w=f'{w:.6f}', n=f'{n:.6f}', e=f'{e:.6f}')
    delay = 1.0
    for attempt in range(8):
        throttle()
        try:
            with urllib.request.urlopen(url, timeout=60) as r:
                data = r.read()
                ct = r.headers.get('Content-Type', '')
            if len(data) != N * N * 4 or 'bil' not in ct:
                raise RuntimeError(f'bad payload {ct} {len(data)} {data[:200]!r}')
            with open(p + '.tmp', 'wb') as f:
                f.write(data)
            os.replace(p + '.tmp', p)
            return p
        except urllib.error.HTTPError as ex:
            if ex.code in (429, 502, 503, 504):
                time.sleep(delay); delay *= 2; continue
            raise
        except (urllib.error.URLError, TimeoutError, RuntimeError) as ex:
            print('retry', layer, ti, tj, ex, file=sys.stderr)
            time.sleep(delay); delay *= 2
    raise RuntimeError(f'failed {layer} {ti} {tj}')


_tiles = {}


def load_tile(layer, ti, tj):
    k = (layer, ti, tj)
    if k not in _tiles:
        a = np.fromfile(tile_path(layer, ti, tj), '<f4').reshape(N, N).astype(float)
        a[a <= -9998] = np.nan
        _tiles[k] = a
    return _tiles[k]


def needed_tiles(lat, lon):
    fk = -lat / PX - 0.5
    fc = lon / PX - 0.5
    k0 = np.floor(fk).astype(np.int64)
    c0 = np.floor(fc).astype(np.int64)
    s = set()
    for dk in (0, 1):
        for dc in (0, 1):
            k = k0 + dk; c = c0 + dc
            ti = -((k // N) + 1)   # rows k in [-(ti+1)N, -ti N -1]
            tj = c // N
            s.update(zip(ti.tolist(), tj.tolist()))
    return s


def pix(layer, k, c):
    ti = -((k // N) + 1)
    tj = c // N
    row = k + (ti + 1) * N
    col = c - tj * N
    out = np.empty(len(k))
    for key in set(zip(ti.tolist(), tj.tolist())):
        m = (ti == key[0]) & (tj == key[1])
        out[m] = load_tile(layer, *key)[row[m], col[m]]
    return out


def sample(layer, lat, lon):
    fk = -lat / PX - 0.5
    fc = lon / PX - 0.5
    k0 = np.floor(fk).astype(np.int64)
    c0 = np.floor(fc).astype(np.int64)
    a = fk - k0; b = fc - c0
    v00 = pix(layer, k0, c0); v01 = pix(layer, k0, c0 + 1)
    v10 = pix(layer, k0 + 1, c0); v11 = pix(layer, k0 + 1, c0 + 1)
    return (v00 * (1 - a) * (1 - b) + v01 * (1 - a) * b + v10 * a * (1 - b) + v11 * a * b)


def parse_gpx(path):
    txt = open(path, encoding='utf-8').read()
    pts = re.findall(r'<trkpt lat="([-\d.]+)" lon="([-\d.]+)">\s*<ele>([-\d.]+)</ele>', txt)
    a = np.array(pts, float)
    return a[:, 0], a[:, 1], a[:, 2]


def hav(lat1, lon1, lat2, lon2):
    R = 6371008.8
    p1, p2 = np.radians(lat1), np.radians(lat2)
    dp = p2 - p1; dl = np.radians(lon2 - lon1)
    h = np.sin(dp / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * R * np.arcsin(np.sqrt(h))


def resample(lat, lon, ele):
    d = np.concatenate([[0], np.cumsum(hav(lat[:-1], lon[:-1], lat[1:], lon[1:]))])
    s = np.arange(0, d[-1], STEP)
    return s, np.interp(s, d, lat), np.interp(s, d, lon), np.interp(s, d, ele), d


def main(names):
    os.makedirs(CACHE, exist_ok=True)
    layers = ['mnt', 'mns']
    for name in names:
        lat0, lon0, ele0 = parse_gpx(os.path.join(GPXDIR, name + '.gpx'))
        s, lat, lon, ele, d0 = resample(lat0, lon0, ele0)
        tiles = needed_tiles(lat, lon)
        jobs = [(L, ti, tj) for L in layers for (ti, tj) in tiles]
        todo = [j for j in jobs if not os.path.exists(tile_path(*j))]
        print(name, len(s), 'pts', len(tiles), 'tiles', len(todo), 'to fetch', flush=True)
        with ThreadPoolExecutor(6) as ex:
            list(ex.map(lambda j: fetch_tile(*j), todo))
        mnt = sample('mnt', lat, lon)
        mns = sample('mns', lat, lon)
        np.savez(os.path.join(HERE, name + '.npz'), s=s, lat=lat, lon=lon, strava=ele, mnt=mnt,
                 mns=mns, lat0=lat0, lon0=lon0, ele0=ele0, d0=d0)
        print(name, 'nan mnt', np.isnan(mnt).sum(), 'nan mns', np.isnan(mns).sum(), flush=True)


if __name__ == '__main__':
    main(sys.argv[1:] or ['test1', 'test2', 'test3', 'test4'])
