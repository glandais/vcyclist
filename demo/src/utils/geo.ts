const EARTH_RADIUS_M = 6371008.8;

/**
 * Great-circle distance in metres.
 *
 * The elevation demo this replaced used `sqrt(dlat² + dlng²) * 111000`, which ignores the
 * `cos(latitude)` factor on the longitude term and under-reports by ~30 % at 45° N.
 */
export function haversineMeters(lat1: number, lon1: number, lat2: number, lon2: number): number {
    const toRad = Math.PI / 180;
    const dLat = (lat2 - lat1) * toRad;
    const dLon = (lon2 - lon1) * toRad;
    const a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2) +
        Math.cos(lat1 * toRad) * Math.cos(lat2 * toRad) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/** Cumulative distance (m) along a coordinate list, starting at 0. */
export function cumulativeDistances(
    points: ReadonlyArray<{ latitude: number; longitude: number }>
): number[] {
    const out: number[] = new Array(points.length);
    let total = 0;
    for (let i = 0; i < points.length; i++) {
        if (i > 0) {
            const prev = points[i - 1];
            const cur = points[i];
            total += haversineMeters(prev.latitude, prev.longitude, cur.latitude, cur.longitude);
        }
        out[i] = total;
    }
    return out;
}

/** Debounce a void-returning function. No VueUse in this project, so this is the local helper. */
export function debounce<A extends unknown[]>(fn: (...args: A) => void, waitMs: number) {
    let timer: ReturnType<typeof setTimeout> | null = null;
    return (...args: A) => {
        if (timer !== null) {
            clearTimeout(timer);
        }
        timer = setTimeout(() => {
            timer = null;
            fn(...args);
        }, waitMs);
    };
}
