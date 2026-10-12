import os, json
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
STEP = 5.0


def load(name):
    z = dict(np.load(os.path.join(HERE, name + '.npz')))
    z['mnh'] = z['mns'] - z['mnt']
    return z


def rmed(x, w):
    """centred rolling median, w points (odd)"""
    h = w // 2
    xp = np.pad(x, h, mode='edge')
    v = np.lib.stride_tricks.sliding_window_view(xp, w)
    return np.nanmedian(v, axis=1)


def rmean(x, w):
    h = w // 2
    xp = np.pad(x, h, mode='edge')
    v = np.lib.stride_tricks.sliding_window_view(xp, 2 * h + 1)
    return v.mean(axis=1)


def runs(mask):
    """list of (i0, i1) inclusive index runs where mask True"""
    m = np.concatenate([[False], mask, [False]]).astype(int)
    d = np.diff(m)
    st = np.where(d == 1)[0]
    en = np.where(d == -1)[0] - 1
    return list(zip(st.tolist(), en.tolist()))


def merge(rs, gap_pts):
    out = []
    for a, b in rs:
        if out and a - out[-1][1] - 1 < gap_pts:
            out[-1] = (out[-1][0], b)
        else:
            out.append((a, b))
    return out


def slope(x, L):
    """slope over a centred window of L metres, in %"""
    h = max(1, int(round(L / STEP / 2)))
    xp = np.pad(x, h, mode='edge')
    return (xp[2 * h:] - xp[:-2 * h]) / (2 * h * STEP) * 100


def dplus(x, th):
    ref = x[0]; g = 0.0
    for v in x[1:]:
        if v > ref + th:
            g += v - ref; ref = v
        elif v < ref - th:
            ref = v
    return g


def load_osm(name):
    p = os.path.join(HERE, 'osm', name + '.json')
    if not os.path.exists(p):
        return None
    try:
        return json.load(open(p))['elements']
    except Exception:
        return None


def osm_match(z, elements, key, maxd=12.0):
    """per resampled point: name/ref of the nearest OSM way with tag key (bridge/tunnel) within maxd m"""
    lat, lon = z['lat'], z['lon']
    k = np.cos(np.radians(lat.mean())) * 111320.0
    X = lon * k; Y = lat * 110540.0
    best = np.full(len(lat), np.inf); lab = np.array([''] * len(lat), dtype=object)
    for el in elements or []:
        t = el.get('tags', {})
        if t.get(key, 'no') in ('no',):
            continue
        g = el.get('geometry') or []
        if len(g) < 2:
            continue
        gx = np.array([p['lon'] for p in g]) * k; gy = np.array([p['lat'] for p in g]) * 110540.0
        if (X.max() < gx.min() - 50) or (X.min() > gx.max() + 50):
            pass
        for j in range(len(g) - 1):
            ax, ay, bx, by = gx[j], gy[j], gx[j + 1], gy[j + 1]
            dx, dy = bx - ax, by - ay
            L2 = dx * dx + dy * dy or 1e-9
            u = np.clip(((X - ax) * dx + (Y - ay) * dy) / L2, 0, 1)
            d = np.hypot(X - ax - u * dx, Y - ay - u * dy)
            m = d < best
            best[m] = d[m]
            name = t.get('name') or t.get('bridge:name') or t.get('tunnel:name') or t.get('ref') or f'way {el["id"]}'
            lab[m] = f'{name} [{t.get("highway")}, {key}={t.get(key)}, layer={t.get("layer","")}]'
    lab[best > maxd] = ''
    return lab
