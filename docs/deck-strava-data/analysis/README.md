# Strava routes vs IGN LiDAR HD

Analysis behind [`docs/tasks/elevation-bridge-decks.md`](../../tasks/elevation-bridge-decks.md):
how Strava's elevation on four planned routes (`../test1.gpx` … `../test4.gpx`, author metadata
removed) compares with the IGN LiDAR HD terrain (MNT), surface (MNS) and height (MNH) models over
bridges and tunnels. Findings are in [`rapport.md`](rapport.md), figures in `fig/`, every figure in
`results.json`.

Not part of the build. To reproduce (Python 3, numpy, matplotlib; network to `data.geopf.fr` and
Overpass, bbox requests only):

```bash
python3 -I fetch.py     # resample GPX at 5 m, sample MNT/MNS — tiles cached in cache/
python3 -I analyze.py   # bridges, tunnels, estimated roadway, figures, results.json
python3 -I report.py    # rapport_tables.md
python3 -I export_truth.py  # truth/*.csv: frozen roadway, MNS samples and structure spans for ElevationBenchProbeTest
python3 -I controls.py      # E2 bis: negative controls and held-out positives, from OSM only
```

`controls.py` (stdlib + numpy, network to Overpass only, bbox queries one at a time; raw answers
cached in `osm/controls/`, so a rerun on that cache is offline and deterministic — but the cache is
gitignored: from a fresh clone the script queries current OSM and may not reproduce the committed
truth, whose reference is `CONTROL_GUARDS` in `ElevationBenchProbeTest`) builds the 20 sites of its
`SITES` table — 12 negative controls `n01..n12` (roads under or beside viaducts and overpasses,
toll canopy, covered carriageway, forest, plane trees, levee) and 8 held-out positives `p01..p08`
(bridges outside the four Strava routes, never used to pick a threshold). Each site is a Dijkstra
route over OSM ways, resampled at 5 m exactly like `fetch.py`, with one kind per station: `G`
ground, `O` structure overhead, `L` bridge or railway viaduct alongside (≤ 20 m), `X` own structure
on a negative, or a tunnel/culvert crossing under the route (an untagged deck), and `x` its 35 m
abutment margin (both excluded from scoring), `D` own bridge deck on a positive. Every site carries
self-checks (expected names, overhead refs, deck lengths), plus no U-turn or doubling back on every
site and 150 m of route beyond every counted `D` span on a positive; a failed check stops the
script without writing. `python3 -I controls.py n04 p08` is a dry run on a subset.

Outputs, read by `ElevationBenchProbeTest` (`deck_controls_parse_and_hold_their_guards`, and
`deckControls` under `INTEGRATION=1`):

- `../controls/<id>.gpx`: the routed OSM nodes, no `<ele>` (© OpenStreetMap contributors, ODbL);
- `truth/controls/<id>.csv`: `s,lat,lon,kind,way` at 5 m, header with role, category, OSM base date;
- `truth/controls/index.csv`: one row per site, station counts per kind;
- `truth/controls/spans.csv`: every maximal run of `D`, `X`, `O`, `L` with, for `D`/`X`, its OSM way
  and structure (`bridge`, `tunnel`, `layer`, `underpass`), and its name.
