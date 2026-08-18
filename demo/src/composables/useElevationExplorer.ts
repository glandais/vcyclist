import { computed, reactive, ref, watch } from 'vue';
import {
    getElevationsAlong,
    type CoordinatesDto,
    type CoordinatesElevationDto,
} from '~/elevation-shim';
import { parseGpx, pathLatitudeDeg, pathLongitudeDeg, pathSize } from '~/engine-shim';
import { useElevationProvider } from './useElevationProvider';
import { cumulativeDistances, debounce } from '~/utils/geo';

export type ExplorerMode = 'point' | 'path';
export type StatusKind = 'idle' | 'loading' | 'success' | 'error';

/** Profile sampling step (m). Matches the standalone demo this view replaced. */
const PROFILE_STEP_M = 25;
/** Sliders fire per pixel; only recompute once the drag settles. */
const DEBOUNCE_MS = 300;

export interface ElevationStats {
    pointCount: number;
    totalDistance: number;
    minElevation: number;
    maxElevation: number;
    totalAscent: number;
    totalDescent: number;
}

export function useElevationExplorer() {
    const provider = useElevationProvider();

    const mode = ref<ExplorerMode>('path');
    const points = ref<CoordinatesDto[]>([]);
    const profile = ref<CoordinatesElevationDto[]>([]);
    const status = ref('Click the map to start.');
    const statusKind = ref<StatusKind>('idle');

    const smoothing = reactive({ enabled: true, windowSize: 150 });
    const filter = reactive({ enabled: false, tolerance: 10, zExaggeration: 3 });

    // A slow request for an earlier option set must not paint over a newer profile.
    let requestId = 0;

    const setStatus = (text: string, kind: StatusKind) => {
        status.value = text;
        statusKind.value = kind;
    };

    const updateProfile = async () => {
        if (points.value.length < 2) {
            profile.value = [];
            return;
        }

        const id = ++requestId;
        setStatus('Computing elevation profile…', 'loading');

        try {
            const result = await getElevationsAlong(provider, points.value, {
                step: PROFILE_STEP_M,
                interpolation: true,
                smoothingOptions: smoothing.enabled ? { ...smoothing } : undefined,
                filterOptions: filter.enabled ? { ...filter } : undefined,
            });
            if (id !== requestId) {
                return;
            }
            profile.value = result;
            setStatus(`Elevation profile: ${result.length} points`, 'success');
        } catch (error) {
            if (id !== requestId) {
                return;
            }
            profile.value = [];
            setStatus(`Error: ${(error as Error).message}`, 'error');
        }
    };

    const debouncedUpdate = debounce(() => void updateProfile(), DEBOUNCE_MS);

    // Checkbox toggles are a single deliberate click — recompute at once, like the old demo.
    watch([() => smoothing.enabled, () => filter.enabled], () => void updateProfile());
    // The numeric controls are dragged, so they go through the debounce.
    watch([() => smoothing.windowSize, () => filter.tolerance, () => filter.zExaggeration], () =>
        debouncedUpdate()
    );

    const addPoint = (latitude: number, longitude: number) => {
        points.value = [...points.value, { latitude, longitude }];
        if (points.value.length < 2) {
            setStatus('Point added — add one more to draw a profile.', 'success');
            return;
        }
        void updateProfile();
    };

    const clearPath = () => {
        requestId++;
        points.value = [];
        profile.value = [];
        setStatus('Click the map to start.', 'idle');
    };

    const setMode = (next: ExplorerMode) => {
        mode.value = next;
        if (next === 'point') {
            clearPath();
        }
    };

    const loadTrack = (track: CoordinatesDto[]) => {
        mode.value = 'path';
        requestId++;
        points.value = track;
        profile.value = [];
        void updateProfile();
    };

    /** GPX → coordinates via the engine's own parser; no third-party GPX library needed. */
    const trackFromGpx = (xml: string): CoordinatesDto[] => {
        const path = parseGpx(xml);
        const n = pathSize(path);
        const track: CoordinatesDto[] = new Array(n);
        for (let i = 0; i < n; i++) {
            track[i] = { latitude: pathLatitudeDeg(path, i), longitude: pathLongitudeDeg(path, i) };
        }
        return track;
    };

    const loadSample = async (url: string) => {
        setStatus('Loading GPX…', 'loading');
        const response = await fetch(url);
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        loadTrack(trackFromGpx(await response.text()));
    };

    const loadFile = async (file: File) => {
        setStatus('Loading GPX…', 'loading');
        loadTrack(trackFromGpx(await file.text()));
    };

    const stats = computed<ElevationStats | null>(() => {
        const p = profile.value;
        if (p.length === 0) {
            return null;
        }

        let minElevation = Number.POSITIVE_INFINITY;
        let maxElevation = Number.NEGATIVE_INFINITY;
        let totalAscent = 0;
        let totalDescent = 0;

        for (let i = 0; i < p.length; i++) {
            const e = p[i].elevation;
            minElevation = Math.min(minElevation, e);
            maxElevation = Math.max(maxElevation, e);
            if (i > 0) {
                const diff = e - p[i - 1].elevation;
                if (diff > 0) {
                    totalAscent += diff;
                } else {
                    totalDescent -= diff;
                }
            }
        }

        const distances = cumulativeDistances(p);
        return {
            pointCount: p.length,
            totalDistance: distances[distances.length - 1],
            minElevation,
            maxElevation,
            totalAscent,
            totalDescent,
        };
    });

    return {
        mode,
        points,
        profile,
        status,
        statusKind,
        smoothing,
        filter,
        stats,
        setMode,
        addPoint,
        clearPath,
        loadTrack,
        loadSample,
        loadFile,
    };
}
