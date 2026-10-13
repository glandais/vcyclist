"""Export a few bench routes to the browser demo, with the reference each one is judged against.

Copies the four Strava routes (`../test1..4.gpx`) and a handful of telling controls
(`../controls/*.gpx`, `../controls/tunnels/*.gpx`) into `demo/public/gpx/validation/`, and writes
next to each a compact `<id>.ref.json` built from `truth/` — never edited by hand:

- `lat`, `lon`: station positions, 1e-5 degree integers, delta-encoded (first value absolute);
- `truth` (Strava routes only): the frozen roadway of `truth/<route>.csv` in decimetres, NGF-IGN69,
  delta-encoded. The controls carry no elevation truth (`controls.py` writes kinds only, from OSM);
- `spans`: maximal runs of one kind, `[kind, firstStation, lastStation, name]`, ground and
  excluded kinds dropped, named after the structure of `truth/structures.csv` or
  `truth/{controls,tunnels}/spans.csv` that overlaps the run most;
- `meta`: the CSV header fields (role, category, place, OSM base date) and the kind legend.

Stations are the CSVs' 5 m grid, every second one kept on `test4` (111 km) to hold the bundle
under ~1 MB. The demo matches stations to its own path by position, not by `s` (see
`demo/src/composables/useValidationReference.ts`), so the step need not match anything.

    python3 -I export_demo.py [OUT_DIR]

stdlib only, no network. OUT_DIR defaults to ../../../demo/public/gpx/validation.
"""
import csv, json, os, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.dirname(HERE)
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(DATA, '..', '..', 'demo', 'public', 'gpx', 'validation')

# id, gpx (relative to DATA), truth csv (relative to truth/), station stride
SITES = [
    ('test1', 'test1.gpx', 'test1.csv', 1),
    ('test2', 'test2.gpx', 'test2.csv', 1),
    ('test3', 'test3.gpx', 'test3.csv', 1),
    ('test4', 'test4.gpx', 'test4.csv', 2),
    ('p01-chevire', 'controls/p01-chevire.gpx', 'controls/p01-chevire.csv', 1),
    ('p06-normandie', 'controls/p06-normandie.gpx', 'controls/p06-normandie.csv', 1),
    ('p07-millau-a75', 'controls/p07-millau-a75.gpx', 'controls/p07-millau-a75.csv', 1),
    ('n02-nantes-chevire-under', 'controls/n02-nantes-chevire-under.gpx', 'controls/n02-nantes-chevire-under.csv', 1),
    ('n03-millau-d992', 'controls/n03-millau-d992.gpx', 'controls/n03-millau-d992.csv', 1),
    ('tt01-galibier', 'controls/tunnels/tt01-galibier.gpx', 'tunnels/tt01-galibier.csv', 1),
    ('tn03-galibier-col', 'controls/tunnels/tn03-galibier-col.gpx', 'tunnels/tn03-galibier-col.csv', 1),
    ('tt03-lioran', 'controls/tunnels/tt03-lioran.gpx', 'tunnels/tt03-lioran.csv', 1),
    ('tn01-alpe-huez', 'controls/tunnels/tn01-alpe-huez.gpx', 'tunnels/tn01-alpe-huez.csv', 1),
]

# Kinds drawn as bands. Ground (T, G) is the default; X/x are excluded from scoring and b is a
# bridge span the truth keeps on the terrain, so none of them gets a band.
BANDS = {'B', 'D', 'N', 'S', 'O', 'L'}


def read_truth(path):
    meta, legend = {}, ''
    with open(path, newline='') as f:
        lines = f.read().splitlines()
    head = [ln for ln in lines if ln.startswith('#')]
    for tok in head[0].lstrip('# ').split(' '):
        if '=' in tok:
            k, v = tok.split('=', 1)
            meta[k] = v
    legend = head[1].lstrip('# ').removeprefix('kind: ') if len(head) > 1 else ''
    rows = list(csv.DictReader([ln for ln in lines if not ln.startswith('#')]))
    return meta, legend, rows


# The bench's index files are ASCII-folded; the demo shows these strings to people.
PLACE_FIXES = (
    ('Alpe d Huez', "Alpe d'Huez"),
    ('Bourg-d Oisans', "Bourg-d'Oisans"),
    ('Isere', 'Isère'),
    ('Reze', 'Rezé'),
    ('Chevire', 'Cheviré'),
    ('Font de Cere', 'Font de Cère'),
)


def tidy_place(place):
    for a, b in PLACE_FIXES:
        place = place.replace(a, b)
    return ' '.join(place.split())


def places():
    out = {}
    for sub in ('controls', 'tunnels'):
        with open(os.path.join(HERE, 'truth', sub, 'index.csv'), newline='') as f:
            for r in csv.DictReader(f):
                out[r['id']] = tidy_place(r['place'])
    return out


def deltas(values):
    out, prev = [], 0
    for v in values:
        out.append(v - prev)
        prev = v
    return out


def structures():
    """(i0, i1, name) per route id, in 5 m station indices, from the bench's own span tables."""
    out = {}
    with open(os.path.join(HERE, 'truth', 'structures.csv'), newline='') as f:
        for r in csv.DictReader(f):
            out.setdefault(r['route'], []).append((int(r['i0']), int(r['i1']), r['name']))
    for sub in ('controls', 'tunnels'):
        with open(os.path.join(HERE, 'truth', sub, 'spans.csv'), newline='') as f:
            for r in csv.DictReader(f):
                out.setdefault(r['id'], []).append((int(r['i0']), int(r['i1']), r['name']))
    return out


def spans(kinds, named, stride):
    """Maximal runs of a banded kind on the full 5 m grid, as [kind, i0, i1, name] in kept-station
    indices (i // stride, last index rounded up); name = the structure overlapping the run most."""
    out, i, last = [], 0, (len(kinds) - 1 + stride - 1) // stride
    while i < len(kinds):
        j = i
        while j + 1 < len(kinds) and kinds[j + 1] == kinds[i]:
            j += 1
        if kinds[i] in BANDS:
            best, name = 0, ''
            for a, b, nm in named:
                overlap = min(b, j) - max(a, i) + 1
                if overlap > best:
                    best, name = overlap, nm
            out.append([kinds[i], i // stride, min(last, -(-j // stride)), name])
        i = j + 1
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    where = places()
    named = structures()
    total = 0
    for sid, gpx, truth, stride in SITES:
        meta, legend, rows = read_truth(os.path.join(HERE, 'truth', truth))
        runs = spans([r['kind'] for r in rows], named.get(sid, []), stride)
        kept = rows[::stride]
        if kept[-1] is not rows[-1]:
            kept.append(rows[-1])         # keep the route's last station
        rows = kept
        ref = {
            'id': sid,
            'meta': {**meta, 'place': where.get(sid, ''), 'legend': legend,
                     'stride_m': float(meta.get('step_m', 5)) * stride},
            'lat': deltas([round(float(r['lat']) * 1e5) for r in rows]),
            'lon': deltas([round(float(r['lon']) * 1e5) for r in rows]),
            'spans': runs,
        }
        if 'truth' in rows[0]:
            ref['truth'] = deltas([round(float(r['truth']) * 10) for r in rows])
        dst_gpx = os.path.join(OUT, sid + '.gpx')
        shutil.copyfile(os.path.join(DATA, gpx), dst_gpx)
        dst_ref = os.path.join(OUT, sid + '.ref.json')
        with open(dst_ref, 'w', newline='\n') as f:
            json.dump(ref, f, separators=(',', ':'), ensure_ascii=False)
            f.write('\n')
        size = os.path.getsize(dst_gpx) + os.path.getsize(dst_ref)
        total += size
        print(f'{sid:28s} {len(rows):6d} stations {len(ref["spans"]):3d} spans {size / 1024:7.1f} KiB')
    print(f'total {total / 1024:.1f} KiB')
    assert total < 1024 * 1024, 'bundle over 1 MiB'


if __name__ == '__main__':
    main()
