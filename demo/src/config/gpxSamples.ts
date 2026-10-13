// The bundled sample tracks under `public/gpx/`. Shared by the GPX-analysis file picker and the
// elevation explorer so the two views never drift apart on which samples exist.
export interface GpxSample {
    label: string;
    value: string;
    /** One line shown under the label in the picker. */
    description?: string;
    /**
     * The `<id>.ref.json` the bridge/tunnel bench judges this route against — station positions,
     * the structure spans and, on the four Strava routes, the estimated roadway (NGF-IGN69).
     * Written by `docs/deck-strava-data/analysis/export_demo.py`, never by hand.
     */
    reference?: string;
}

export const gpxSamples: GpxSample[] = [
    { label: 'Sample Route', value: './gpx/sample.gpx' },
    { label: 'Stelvio descent', value: './gpx/stelvio.gpx' },
    { label: 'Amazfit Track', value: './gpx/amazfit.gpx' },
    { label: 'Garmin Track', value: './gpx/garmin.gpx' },
    { label: 'Movescount Track', value: './gpx/movescount.gpx' },
    { label: 'Sports Tracker', value: './gpx/sports-tracker.gpx' },
    { label: 'Strava Track', value: './gpx/strava.gpx' },
    // The only bundled file with more than one <trk> or any <wpt> — which is why the demo could
    // discard both for so long without anyone noticing. Keep it in the list.
    { label: 'Two tracks + waypoints', value: './gpx/two-tracks.gpx' },
];

const v = (id: string, label: string, description: string): GpxSample => ({
    label,
    description,
    value: `./gpx/validation/${id}.gpx`,
    reference: `./gpx/validation/${id}.ref.json`,
});

/**
 * The routes `elevation-bridge-decks` was measured on (`docs/deck-strava-data/`), copied by
 * `export_demo.py`. The four Strava routes carry the estimated roadway; the controls are OSM
 * routes (no `<ele>`, © OpenStreetMap contributors, ODbL) whose reference is the structure spans
 * only. Positives must change, negatives must not: a correction under the Millau viaduct, on the
 * Alpe d'Huez hairpins or over the Galibier col is a false positive.
 */
export const validationSamples: GpxSample[] = [
    v('test1', 'Saint-Nazaire bridge (Strava, 10 km)', 'Bridge deck 63.7 m above the Loire'),
    v('test2', 'Nantes, Loire bridges (Strava, 25 km)', '16 bridges, decks 10–14 m'),
    v('test3', 'Mauves-sur-Loire (Strava, 10 km)', 'One cambered bridge'),
    v(
        'test4',
        'Vercors, Grands Goulets (Strava, 111 km)',
        'Four tunnels, balcony roads, the Échevis bridge'
    ),
    v('p07-millau-a75', 'Viaduc de Millau (deck)', 'Positive: 2.5 km deck, 270 m above the Tarn'),
    v('p06-normandie', 'Pont de Normandie (deck)', 'Positive: cable-stayed, 2.1 km deck'),
    v('p01-chevire', 'Pont de Cheviré (deck)', 'Positive: motorway bridge, Nantes'),
    v('n03-millau-d992', 'Under the Millau viaduct', 'Negative: valley road, nothing to correct'),
    v('n02-nantes-chevire-under', 'Under Cheviré approach', 'Negative: local road under a viaduct'),
    v('tt03-lioran', 'Tunnel du Lioran', 'Tunnel: 1.5 km under the Col de Font de Cère'),
    v('tt01-galibier', 'Tunnel du Galibier', 'Tunnel under the col'),
    v('tn03-galibier-col', 'Col du Galibier (over the top)', 'Negative: the road above the tunnel'),
    v('tn01-alpe-huez', "Alpe d'Huez hairpins", 'Negative: steep hairpins, not a tunnel'),
];

/** Every bundled sample by URL — how the view tells a validation track apart from the rest. */
export const sampleByUrl = new Map<string, GpxSample>(
    [...gpxSamples, ...validationSamples].map(s => [s.value, s])
);

/**
 * Both groups as a grouped `USelect` item list. The group label is French on purpose: it names the
 * bench (`ponts & tunnels`) the way its task doc and report do.
 */
export const gpxSampleItems = [
    gpxSamples,
    [{ type: 'label' as const, label: 'Ponts & tunnels (validation)' }, ...validationSamples],
];
