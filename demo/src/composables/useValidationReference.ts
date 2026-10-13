import { computed, type Ref, ref } from 'vue';
import {
    getField,
    pathLatitudeDeg,
    pathLongitudeDeg,
    pathSize,
    type Path,
} from '@glandais/vcyclist-engine';

/**
 * A bench route's reference, as `docs/deck-strava-data/analysis/export_demo.py` writes it: station
 * positions (1e-5 degree integers) and truth (decimetres) are delta-encoded, spans are station
 * index runs.
 */
interface ReferenceFile {
    id: string;
    meta: Record<string, string | number>;
    lat: number[];
    lon: number[];
    truth?: number[];
    spans: [string, number, number, string][];
}

export interface ValidationReference {
    id: string;
    /** `role`, `category`, `place`, `datum`… — the truth CSV header, verbatim. */
    meta: Record<string, string | number>;
    lat: Float64Array;
    lon: Float64Array;
    /** Estimated roadway, metres NGF-IGN69 — only on the four Strava routes. */
    truth: Float64Array | null;
    /** Band category per station, `null` on ground and on excluded stations. */
    band: (BandKind | null)[];
    /** Structure name per station, `''` where none. */
    name: string[];
}

/** What a span of the bench is, collapsed to what the chart can say about it. */
export type BandKind = 'deck' | 'tunnel' | 'balcony' | 'overhead';

export const BAND_STYLES: Record<BandKind, { label: string; color: string; hint: string }> = {
    deck: {
        label: 'Bridge deck',
        color: '#2563eb',
        hint: 'The route rides a bridge: the profile should follow the deck, not the valley.',
    },
    tunnel: {
        label: 'Tunnel',
        color: '#475569',
        hint: 'The route runs underground: the profile should go straight between the portals.',
    },
    balcony: {
        label: 'Balcony road',
        color: '#d97706',
        hint: 'A cliff-edge road: the terrain model reads the cliff or the gorge beside it.',
    },
    overhead: {
        label: 'Structure overhead / alongside',
        color: '#7c3aed',
        hint: 'A bridge above or beside the route: nothing here should move.',
    },
};

// The bench's kind letters (truth CSV headers): B/D deck, N own tunnel, S balcony, O overhead,
// L parallel bridge or viaduct. Anything else is ground or excluded from scoring.
const BAND_OF: Record<string, BandKind> = {
    B: 'deck',
    D: 'deck',
    N: 'tunnel',
    S: 'balcony',
    O: 'overhead',
    L: 'overhead',
};

const undelta = (values: number[], scale: number): Float64Array => {
    const out = new Float64Array(values.length);
    let acc = 0;
    for (let i = 0; i < values.length; i++) {
        acc += values[i];
        out[i] = acc / scale;
    }
    return out;
};

const decode = (file: ReferenceFile): ValidationReference => {
    const n = file.lat.length;
    const band: (BandKind | null)[] = new Array(n).fill(null);
    const name: string[] = new Array(n).fill('');
    for (const [kind, i0, i1, label] of file.spans) {
        for (let i = i0; i <= i1 && i < n; i++) {
            band[i] = BAND_OF[kind] ?? null;
            name[i] = label;
        }
    }
    return {
        id: file.id,
        meta: file.meta,
        lat: undelta(file.lat, 1e5),
        lon: undelta(file.lon, 1e5),
        truth: file.truth ? undelta(file.truth, 10) : null,
        band,
        name,
    };
};

/** A polyline in a local metric frame, with its cumulative length. */
interface Track {
    x: Float64Array;
    y: Float64Array;
    s: Float64Array;
}

const METRES_PER_DEGREE = 111_320;

const toTrack = (lat: ArrayLike<number>, lon: ArrayLike<number>, lat0: number): Track => {
    const n = lat.length;
    const k = Math.cos((lat0 * Math.PI) / 180) * METRES_PER_DEGREE;
    const x = new Float64Array(n);
    const y = new Float64Array(n);
    const s = new Float64Array(n);
    for (let i = 0; i < n; i++) {
        x[i] = lon[i] * k;
        y[i] = lat[i] * METRES_PER_DEGREE;
        if (i > 0) {
            s[i] = s[i - 1] + Math.hypot(x[i] - x[i - 1], y[i] - y[i - 1]);
        }
    }
    return { x, y, s };
};

/** Farther than this from the series, a route point has no counterpart. */
const MAX_OFFSET_M = 40;
/** How far back along the series the search may look; covers a racing line's small shortcuts. */
const BACK_M = 60;
/** How far ahead it looks at least; the step between two route points widens it. */
const AHEAD_M = 300;

/**
 * For each point of `route`, the fractional index along `series` of the nearest point of the
 * series, or NaN when it is farther than {@link MAX_OFFSET_M}.
 *
 * ## Why position and not cumulative distance
 *
 * The chart's x is the enhanced path's own `distance`, the reference's is the raw GPX resampled
 * at 5 m by `fetch.py`, and the "without corrections" run is a separate ride at a separate pace.
 * Their cumulative distances drift apart — densification, the racing line, 3D against 2D length —
 * by tens of metres over the 111 km Vercors route, enough to shift a 50 m bridge off its own deck.
 * Matching by position does not drift. The search is **monotone**: it only looks in a window
 * around the previous match, so a hairpin or an out-and-back never snaps onto the other leg.
 * Only the first point, or one following a stretch with no match, searches the whole series.
 */
const matchAlong = (route: Track, series: Track): Float64Array => {
    const n = route.x.length;
    const m = series.x.length;
    const out = new Float64Array(n).fill(NaN);
    if (m < 2) {
        return out;
    }
    let cursor = -1; // segment of the last match, -1 = lost
    for (let i = 0; i < n; i++) {
        let lo = 0;
        let hi = m - 2;
        if (cursor >= 0) {
            const step = i > 0 ? route.s[i] - route.s[i - 1] : 0;
            const sLo = series.s[cursor] - BACK_M;
            const sHi = series.s[cursor + 1] + Math.max(AHEAD_M, 4 * step);
            lo = cursor;
            while (lo > 0 && series.s[lo] > sLo) {
                lo--;
            }
            hi = cursor;
            while (hi < m - 2 && series.s[hi + 1] < sHi) {
                hi++;
            }
        }
        let best = Infinity;
        let bestK = -1;
        let bestT = 0;
        const px = route.x[i];
        const py = route.y[i];
        for (let k = lo; k <= hi; k++) {
            const ax = series.x[k];
            const ay = series.y[k];
            const dx = series.x[k + 1] - ax;
            const dy = series.y[k + 1] - ay;
            const l2 = dx * dx + dy * dy;
            const t = l2 > 0 ? Math.min(1, Math.max(0, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0;
            const d = Math.hypot(px - ax - t * dx, py - ay - t * dy);
            if (d < best) {
                best = d;
                bestK = k;
                bestT = t;
            }
        }
        if (bestK >= 0 && best <= MAX_OFFSET_M) {
            out[i] = bestK + bestT;
            cursor = bestK;
        } else {
            cursor = -1;
        }
    }
    return out;
};

const interpolate = (values: ArrayLike<number>, j: number): number | null => {
    if (!Number.isFinite(j)) {
        return null;
    }
    const k = Math.min(Math.floor(j), values.length - 2);
    const t = j - k;
    const v = values[k] * (1 - t) + values[k + 1] * t;
    return Number.isFinite(v) ? v : null;
};

const pathTrack = (path: Path, lat0: number): Track => {
    const n = pathSize(path);
    const lat = new Float64Array(n);
    const lon = new Float64Array(n);
    for (let i = 0; i < n; i++) {
        lat[i] = pathLatitudeDeg(path, i);
        lon[i] = pathLongitudeDeg(path, i);
    }
    return toTrack(lat, lon, lat0);
};

/** A run of one band kind, in the chart's unit (km). */
export interface Band {
    kind: BandKind;
    startKm: number;
    endKm: number;
    name: string;
}

/** Everything drawn over the elevation profile, aligned on the current path's point indices. */
export interface ElevationOverlays {
    /** The same ride enhanced with both elevation corrections off. */
    withoutCorrections: (number | null)[] | null;
    /** The bench's estimated roadway, dashed. */
    reference: (number | null)[] | null;
    bands: Band[];
}

/**
 * Loads a validation track's reference and maps it, and the "without corrections" run, onto the
 * current path's indices — so the chart's index-mode hover and tooltip read every series at the
 * same point.
 */
export function useValidationReference(
    currentPath: Ref<Path | null>,
    comparisonPath: Ref<Path | null>
) {
    const reference = ref<ValidationReference | null>(null);
    let requested: string | null = null;

    /** Load `url`'s reference, or clear it with `null`. A late answer for a stale URL is dropped. */
    const loadReference = async (url: string | null) => {
        requested = url;
        reference.value = null;
        if (url === null) {
            return;
        }
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`Failed to load ${url}: ${response.status}`);
        }
        const file = (await response.json()) as ReferenceFile;
        if (requested === url) {
            reference.value = decode(file);
        }
    };

    const overlays = computed<ElevationOverlays>(() => {
        const path = currentPath.value;
        const bench = reference.value;
        const other = comparisonPath.value;
        const empty: ElevationOverlays = { withoutCorrections: null, reference: null, bands: [] };
        if (!path || pathSize(path) < 2 || (!bench && !other)) {
            return empty;
        }
        const n = pathSize(path);
        const lat0 = pathLatitudeDeg(path, 0);
        const route = pathTrack(path, lat0);

        let withoutCorrections: (number | null)[] | null = null;
        if (other && pathSize(other) >= 2) {
            const m = pathSize(other);
            const elevation = new Float64Array(m);
            for (let i = 0; i < m; i++) {
                elevation[i] = getField(other, i, 'elevation');
            }
            const j = matchAlong(route, pathTrack(other, lat0));
            withoutCorrections = Array.from(j, x => interpolate(elevation, x));
        }

        let truth: (number | null)[] | null = null;
        const bands: Band[] = [];
        if (bench) {
            const j = matchAlong(route, toTrack(bench.lat, bench.lon, lat0));
            if (bench.truth) {
                const t = bench.truth;
                truth = Array.from(j, x => interpolate(t, x));
            }
            let open: Band | null = null;
            for (let i = 0; i < n; i++) {
                const k = Number.isFinite(j[i]) ? Math.round(j[i]) : -1;
                const kind = k >= 0 ? bench.band[k] : null;
                const km = getField(path, i, 'distance') / 1000;
                if (open && (kind !== open.kind || bench.name[k] !== open.name)) {
                    bands.push(open);
                    open = null;
                }
                if (kind) {
                    if (open) {
                        open.endKm = km;
                    } else {
                        open = { kind, startKm: km, endKm: km, name: bench.name[k] };
                    }
                }
            }
            if (open) {
                bands.push(open);
            }
        }
        return { withoutCorrections, reference: truth, bands };
    });

    return { reference, loadReference, overlays };
}
