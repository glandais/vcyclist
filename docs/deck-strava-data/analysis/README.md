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
```
