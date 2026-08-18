// The bundled sample tracks under `public/gpx/`. Shared by the GPX-analysis file picker and the
// elevation explorer so the two views never drift apart on which samples exist.
export interface GpxSample {
    label: string;
    value: string;
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
