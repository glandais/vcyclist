"""Stage 2: structures (bridges / tunnels), estimated roadway, stats, figures, results.json"""
import os, sys, json
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from common import *          # noqa
import fetch                  # noqa

SMOOTH_TH = float(os.environ.get("SMOOTH_TH", "0.8"))
FIG = os.path.join(HERE, 'fig')
C_STRAVA, C_MNT, C_ROAD = '#2a78d6', '#eb6834', '#1baf7a'
C_MNS, C_INK, C_MUTED = '#b9b8b2', '#0b0b0b', '#52514e'
plt.rcParams.update({'font.size': 9, 'axes.edgecolor': C_MUTED, 'axes.labelcolor': C_INK,
                     'xtick.color': C_MUTED, 'ytick.color': C_MUTED, 'axes.grid': True,
                     'grid.color': '#e6e5e0', 'grid.linewidth': 0.6, 'axes.spines.top': False,
                     'axes.spines.right': False, 'figure.facecolor': '#fcfcfb',
                     'axes.facecolor': '#fcfcfb'})


def cross_slope(z, d=10.0):
    """terrain (MNT) slope across the track, in %, sampling +-d m perpendicular"""
    lat, lon = z['lat'], z['lon']
    kx = np.cos(np.radians(lat)) * 111320.0; ky = 110540.0
    dx = np.gradient(lon) * kx; dy = np.gradient(lat) * ky
    n = np.hypot(dx, dy) + 1e-9
    px, py = -dy / n, dx / n
    la1, lo1 = lat + py * d / ky, lon + px * d / kx
    la2, lo2 = lat - py * d / ky, lon - px * d / kx
    tiles = fetch.needed_tiles(np.r_[la1, la2], np.r_[lo1, lo2])
    for t in tiles:
        fetch.fetch_tile('mnt', *t)
    a = fetch.sample('mnt', la1, lo1); b = fetch.sample('mnt', la2, lo2)
    return np.abs(a - b) / (2 * d) * 100, np.maximum(a, b)


def analyse(name):
    z = load(name)
    s, st, mnt, mns, mnh = z['s'], z['strava'], z['mnt'], z['mns'], z['mnh']
    n = len(s)
    open_ = mnh < 0.5
    off = float(np.median((st - mnt)[open_]))
    sc = st - off
    mf = rmed(mns, 5)                              # MNS, rolling median 25 m
    resid = mns - mf
    rough = np.sqrt(rmean(resid ** 2, 7))          # local MNS roughness, ~35 m
    osm = load_osm(name)
    lab_b = osm_match(z, osm, 'bridge', 10); lab_t = osm_match(z, osm, 'tunnel', 10)

    # ---------------- bridges
    tall = mnh > 5
    raw = [r for r in runs(tall) if r[1] - r[0] + 1 >= 3]
    smooth_pt = tall & (rough < SMOOTH_TH)
    bridges, others = [], []
    cand = merge([r for r in raw if smooth_pt[r[0]:r[1] + 1].mean() >= 0.6], 60)
    for a, b in cand:
        a2, b2 = a, b
        while a2 > 0 and mnh[a2 - 1] > 1:
            a2 -= 1
        while b2 < n - 1 and mnh[b2 + 1] > 1:
            b2 += 1
        seg = slice(a2, b2 + 1)
        deck = mnh[seg] > 1
        sl = np.abs(slope(mf, 25))[a2 + 2:b2 - 1] if b2 - a2 > 5 else np.array([99.])
        canyon = float(np.max(mf[seg] - mnt[seg]))
        # MNT under the deck clearly lower than at both ends?
        e0 = mnt[max(a2 - 1, 0)]; e1 = mnt[min(b2 + 1, n - 1)]
        under_low = float(np.min(mnt[seg])) < min(e0, e1) - 3
        rmed_seg = float(np.median(rough[seg][mnh[seg] > 5]))
        short = b2 - a2 <= 5
        is_bridge = under_low and ((np.percentile(sl, 95) <= 10 and s[b2] - s[a2] >= 30) or (short and rmed_seg < 0.4))
        rec = dict(i0=a2, i1=b2, km0=s[a2] / 1000, km1=s[b2] / 1000, L=s[b2] - s[a2] + STEP,
                   canyon=canyon, slope95=float(np.percentile(sl, 95)), slopemax=float(sl.max()),
                   rough=float(np.median(rough[seg][mnh[seg] > 5])), under_low=bool(under_low),
                   deck_max=float(np.max(mf[seg])),
                   osm=sorted(set(x.split(' [')[0] for x in lab_b[seg] if x)))
        (bridges if is_bridge else others).append(rec)
    osm_missed = []
    for ra, rb in merge([r for r in runs(lab_b != '') if r[1] - r[0] >= 2], 2):
        if any(b['i0'] <= rb and b['i1'] >= ra for b in bridges):
            continue
        seg = slice(ra, rb + 1)
        osm_missed.append(dict(km0=s[ra] / 1000, km1=s[rb] / 1000, L=s[rb] - s[ra] + STEP, mnh_max=float(mnh[seg].max()),
                               strava_minus_mnt_max=float(np.max(st[seg] - off - mnt[seg])),
                               name=sorted(set(x.split(' [')[0] for x in lab_b[seg] if x))))
    rough_runs = [r for r in raw if smooth_pt[r[0]:r[1] + 1].mean() < 0.6]
    rough_merged = merge(rough_runs, 60)

    # ---------------- estimated roadway
    road = mnt.copy()
    on_bridge = np.zeros(n, bool)
    for b in bridges:
        seg = slice(b['i0'], b['i1'] + 1)
        m = np.zeros(n, bool)
        for ra, rb in runs(mnh > 1):
            if (rb - ra + 1 >= 8 or b['i1'] - b['i0'] <= 12) and ra <= b['i1'] and rb >= b['i0']:
                m[max(ra, b['i0']):min(rb, b['i1']) + 1] = True
        road[m] = mf[m]; on_bridge[seg] = True

    # ---------------- tunnels / undetected tunnels
    above = (mnt - sc) > 15
    steep = np.abs(slope(sc, 100)) > 15
    tun = []
    for a, b in merge([r for r in runs(above | steep) if r[1] - r[0] >= 2], 30):
        a2, b2 = a, b
        while a2 > 0 and (abs(mnt[a2] - sc[a2]) > 3 or abs(slope(sc, 50)[a2]) > 12):
            a2 -= 1
        while b2 < n - 1 and (abs(mnt[b2] - sc[b2]) > 3 or abs(slope(sc, 50)[b2]) > 12):
            b2 += 1
        tun.append([a2, b2])
    tun = merge([tuple(t) for t in tun], 1)
    # snap detected segments to OSM tunnel spans (portals) when they overlap
    osm_spans = merge([r for r in runs(lab_t != '') if r[1] - r[0] >= 2], 30)
    snapped = []
    for a, b in tun:
        ov = [r for r in osm_spans if r[0] <= b and r[1] >= a]
        if ov:
            a, b = min(r[0] for r in ov) - 1, max(r[1] for r in ov) + 1
        snapped.append((max(a, 0), min(b, n - 1)))
    tun = merge(sorted(set(snapped)), 1)
    tunnels = []
    cs, _ = cross_slope(z) if tun or name == 'test4' else (None, None)
    for a, b in tun:
        seg = slice(a, b + 1)
        lin = np.interp(s[seg], [s[a], s[b]], [mnt[a], mnt[b]])
        road[seg] = lin
        err = sc[seg] - lin
        sl50 = slope(sc, 50)[seg]
        jumps = np.diff(st[seg])
        tunnels.append(dict(i0=a, i1=b, km0=s[a] / 1000, km1=s[b] / 1000, L=s[b] - s[a] + STEP,
                            mnt_above_max=float(np.max(mnt[seg] - lin)),
                            strava_below_mnt_max=float(np.max(mnt[seg] - sc[seg])),
                            err_max=float(err[np.argmax(np.abs(err))]),
                            err_rms=float(np.sqrt(np.mean(err ** 2))),
                            strava_slope50_max=float(np.max(np.abs(sl50))),
                            portal_slope=float((mnt[b] - mnt[a]) / (s[b] - s[a]) * 100),
                            strava_dplus_extra=float(dplus(sc[seg], 0) - max(0, lin[-1] - lin[0])),
                            undetected=bool(np.max(np.abs(sl50)) > 15 and np.max(np.abs(err)) > 15),
                            osm=sorted(set(x.split(' [')[0] for x in lab_t[seg] if x))))
    in_tunnel = np.zeros(n, bool)
    for t in tunnels:
        in_tunnel[t['i0']:t['i1'] + 1] = True

    # balcony / cliff: MNT reads a steep wall next to the road
    balcony = None
    if cs is not None:
        cliff = (cs > 100) & ~in_tunnel & ~on_bridge
        bruns = merge([r for r in runs(cliff) if r[1] - r[0] >= 2], 6)
        dev = np.abs(mnt - rmed(mnt, 9))
        balcony = dict(n_runs=len(bruns), length=float(sum((b - a + 1) * STEP for a, b in bruns)),
                       mnt_vs_strava_rms_cliff=float(np.sqrt(np.mean((mnt - sc)[cliff] ** 2))) if cliff.any() else None,
                       mnt_vs_strava_rms_rest=float(np.sqrt(np.mean((mnt - sc)[~cliff & ~in_tunnel & ~on_bridge] ** 2))),
                       spike_frac_cliff=float(np.mean(dev[cliff] > 3)) if cliff.any() else None,
                       spike_frac_rest=float(np.mean(dev[~cliff & ~in_tunnel & ~on_bridge] > 3)),
                       top=[dict(km0=s[a] / 1000, km1=s[b] / 1000, L=(b - a + 1) * STEP,
                                 dmax=float(np.max(np.abs(mnt - sc)[a:b + 1])))
                            for a, b in sorted(bruns, key=lambda r: -np.max(np.abs(mnt - sc)[r[0]:r[1] + 1]))[:6]])

    # bridge-level Strava behaviour
    for b in bridges:
        a, e = b['i0'], b['i1']
        seg = slice(a, e + 1)
        err = sc[seg] - road[seg]
        lin = np.interp(s[seg], [s[a], s[e]], [road[a], road[e]])
        b.update(err_max=float(err[np.argmax(np.abs(err))]), err_rms=float(np.sqrt(np.mean(err ** 2))),
                 rms_vs_lin=float(np.sqrt(np.mean((sc[seg] - lin) ** 2))),
                 rms_vs_mnt=float(np.sqrt(np.mean((sc[seg] - mnt[seg]) ** 2))),
                 lin_vs_road_max=float(np.max(np.abs(lin - road[seg]))),
                 strava_max=float(np.max(sc[seg])), road_max=float(np.max(road[seg])),
                 road_slope_max=float(np.max(np.abs(slope(road, 50)[seg]))),
                 strava_slope_max=float(np.max(np.abs(slope(sc, 50)[seg]))))
        r = {'tablier': b['err_rms'], 'interpolation lineaire': b['rms_vs_lin'], 'canyon (MNT)': b['rms_vs_mnt']}
        b['behaviour'] = min(r, key=r.get)
        if b['lin_vs_road_max'] < 1.0:
            b['behaviour'] += ' (tablier ~ rectiligne : tablier et interpolation indiscernables)'

    # ---------------- off-structure stats
    struct = on_bridge | in_tunnel
    off_m = ~struct
    ok = off_m & open_
    def rm(m):
        e = (sc - mnt)[m]
        return dict(n=int(m.sum()), bias=float(e.mean()), rmse=float(np.sqrt(np.mean(e ** 2))),
                    p95abs=float(np.percentile(np.abs(e), 95)))
    w = 9  # ~45 m moving average
    far = ~(rmean(struct.astype(float), 41) > 0)   # >100 m from any structure
    mnt_s = rmean(mnt, w)
    def maxslope(x, m, L=50):
        v = np.abs(slope(x, L))[m]
        return float(v.max()), float(np.percentile(v, 99))
    dp = {}
    for lbl, x in [('strava', sc), ('mnt', mnt), ('mns', mns), ('chaussee', road)]:
        dp[lbl] = {f'raw_h{th}': dplus(x, th) for th in (0, 2, 3)}
        xs = rmean(x, w)
        dp[lbl].update({f'ma45_h{th}': dplus(xs, th) for th in (0, 2, 3)})
    dp['strava_gpx_points'] = {f'raw_h{th}': dplus(z['ele0'], th) for th in (0, 2, 3)}
    res = dict(name=name, n=n, length_km=s[-1] / 1000, n_gpx=int(len(z['ele0'])),
               gpx_gap_max=float(np.diff(z['d0']).max()), gpx_gap_median=float(np.median(np.diff(z['d0']))),
               offset=off, offset_n=int(open_.sum()), offset_iqr=[float(np.percentile((st - mnt)[open_], q)) for q in (25, 75)],
               bridges=bridges, others=others, osm_missed=osm_missed, tunnels=tunnels,
               rough=dict(n=len(rough_merged), length=float(sum((b - a + 1) * STEP for a, b in rough_merged))),
               stats_off_struct=rm(off_m), stats_open=rm(ok),
               stats_off_struct_vs_road=dict(rmse=float(np.sqrt(np.mean((sc - road)[off_m] ** 2)))),
               slopes=dict(strava_offstruct=maxslope(sc, far), mnt_ma45_offstruct=maxslope(mnt_s, far), road_ma45_offstruct=maxslope(rmean(road, w), far),
                           strava_all=maxslope(sc, np.ones(n, bool)), road_ma45_all=maxslope(rmean(road, w), np.ones(n, bool))),
               dplus=dp, balcony=balcony)
    arrays = dict(s=s, sc=sc, mnt=mnt, mns=mns, mnh=mnh, mf=mf, road=road)
    return res, arrays


def shade(ax, res, lo=None, hi=None):
    for b in res['bridges']:
        ax.axvspan(b['km0'], b['km1'], color=C_ROAD, alpha=0.12, lw=0)
    for t in res['tunnels']:
        ax.axvspan(t['km0'], t['km1'], color=C_MUTED, alpha=0.15, lw=0)


def plot_full(res, A):
    km = A['s'] / 1000
    fig, (ax, ax2) = plt.subplots(2, 1, figsize=(13, 6.2), sharex=True, gridspec_kw=dict(height_ratios=[3, 1]))
    shade(ax, res)
    ax.plot(km, A['mns'], color=C_MNS, lw=0.8, label='MNS (surface)')
    ax.plot(km, A['mnt'], color=C_MNT, lw=1.2, label='MNT (terrain nu)')
    ax.plot(km, A['road'], color=C_ROAD, lw=2, label='chaussée estimée')
    ax.plot(km, A['sc'], color=C_STRAVA, lw=1.6, label=f'Strava − offset ({res["offset"]:+.2f} m)')
    lo = np.nanpercentile(np.r_[A['mnt'], A['sc']], 0.2); hi = np.nanpercentile(np.r_[A['road'], A['sc']], 99.9)
    ax.set_ylim(lo - 0.08 * (hi - lo), hi + 0.25 * (hi - lo))
    ax.set_ylabel('altitude (m NGF-IGN69)')
    ax.set_title(f'{res["name"]} — profil complet ({res["length_km"]:.1f} km). Vert pâle : ponts détectés ; gris : tunnels',
                 loc='left', color=C_INK)
    ax.legend(loc='upper left', ncol=4, frameon=False)
    ax2.plot(km, A['sc'] - A['road'], color=C_STRAVA, lw=1)
    ax2.axhline(0, color=C_MUTED, lw=0.6)
    ax2.set_ylabel('Strava − chaussée (m)'); ax2.set_xlabel('distance (km)')
    shade(ax2, res)
    fig.tight_layout()
    p = os.path.join(FIG, f'{res["name"]}_profil.png'); fig.savefig(p, dpi=130); plt.close(fig)
    return p


def plot_zoom(res, A, k0, k1, title, fname):
    km = A['s'] / 1000
    m = (km >= k0) & (km <= k1)
    fig, ax = plt.subplots(figsize=(10, 4.2))
    shade(ax, res)
    ax.fill_between(km[m], A['mnt'][m], A['mns'][m], color=C_MNS, alpha=0.5, lw=0, label='MNH (MNS − MNT)')
    ax.plot(km[m], A['mnt'][m], color=C_MNT, lw=1.2, label='MNT')
    ax.plot(km[m], A['road'][m], color=C_ROAD, lw=2.2, label='chaussée estimée')
    ax.plot(km[m], A['sc'][m], color=C_STRAVA, lw=1.8, label='Strava − offset')
    ax.set_xlim(k0, k1)
    ys = np.r_[A['mnt'][m], A['sc'][m], A['road'][m]]
    ax.set_ylim(np.nanmin(ys) - 3, max(np.nanmax(ys), np.nanmax(A['road'][m])) + 0.15 * (np.nanmax(ys) - np.nanmin(ys)) + 3)
    ax.set_title(title, loc='left', color=C_INK); ax.set_xlabel('distance (km)'); ax.set_ylabel('altitude (m)')
    ax.legend(loc='upper left', ncol=4, frameon=False)
    fig.tight_layout()
    p = os.path.join(FIG, fname); fig.savefig(p, dpi=130); plt.close(fig)
    return p


def main():
    allres = {}
    for name in ['test1', 'test2', 'test3', 'test4']:
        res, A = analyse(name)
        figs = [plot_full(res, A)]
        for j, b in enumerate(res['bridges']):
            pad = max(0.15, 0.3 * (b['km1'] - b['km0']))
            nm = ', '.join(b['osm']) or 'pont'
            figs.append(plot_zoom(res, A, b['km0'] - pad, b['km1'] + pad,
                                  f'{name} — {nm} (km {b["km0"]:.2f}–{b["km1"]:.2f}) : Strava suit « {b["behaviour"].split(" (")[0]} »',
                                  f'{name}_pont{j + 1}.png'))
        for j, t in enumerate(res['tunnels']):
            pad = max(0.2, 0.3 * (t['km1'] - t['km0']))
            nm = ', '.join(t['osm']) or 'tunnel ?'
            tag = 'NON DÉTECTÉ par Strava' if t['undetected'] else 'traité par Strava'
            figs.append(plot_zoom(res, A, t['km0'] - pad, t['km1'] + pad,
                                  f'{name} — {nm} (km {t["km0"]:.2f}–{t["km1"]:.2f}) : {tag}',
                                  f'{name}_tunnel{j + 1}.png'))
        res['figures'] = [os.path.relpath(p, HERE) for p in figs]
        allres[name] = res
        np.savez(os.path.join(HERE, name + '_out.npz'), **A)
    json.dump(allres, open(os.path.join(HERE, 'results.json'), 'w'), indent=1, ensure_ascii=False,
              default=lambda o: o.item() if hasattr(o, 'item') else str(o))


if __name__ == '__main__':
    main()
