import type { PointFieldProp } from '@glandais/vcyclist-engine';
import {
    CategoryScale,
    Chart,
    type ChartDataset,
    Filler,
    Legend,
    LinearScale,
    LineController,
    LineElement,
    PointElement,
    type ScaleOptions,
    Title,
    Tooltip,
} from 'chart.js';
import zoomPlugin from 'chartjs-plugin-zoom';
import { computed, onUnmounted, type Ref, shallowRef, watch } from 'vue';
import { fieldConfig } from '~/config/fieldConfig';
import { gradeColor } from './useClimbs';
import { type ClimbDto, getField, pathSize, type Path } from '@glandais/vcyclist-engine';
import type { HoverInfo } from './useHoverSync';
import { BAND_STYLES, type ElevationOverlays } from './useValidationReference';

// Register Chart.js components
Chart.register(
    LineController,
    CategoryScale,
    LinearScale,
    PointElement,
    LineElement,
    Title,
    Tooltip,
    Legend,
    Filler,
    zoomPlugin
);

/** The x window the chart shows, as point indices of the current path (inclusive). */
export interface IndexRange {
    start: number;
    end: number;
}

/** The axis the `elevation` field is drawn on — its category's — which the overlays share. */
const ELEVATION_AXIS = Object.values(fieldConfig).find(c => c.fields.elevation)?.axis ?? null;

/** First index whose value is >= v. The labels are sorted: `distance` never decreases. */
const lowerBound = (values: number[], v: number) => {
    let lo = 0;
    let hi = values.length;
    while (lo < hi) {
        const mid = (lo + hi) >> 1;
        if (values[mid] < v) {
            lo = mid + 1;
        } else {
            hi = mid;
        }
    }
    return lo;
};

export function useChart(
    canvasRef: Ref<HTMLCanvasElement | null>,
    currentPath: Ref<Path | null>,
    selectedFields: Ref<Set<PointFieldProp>>,
    hoveredInfo: Ref<HoverInfo | null>,
    onHoverChange: (index: number | null) => void,
    climbs: Ref<ClimbDto[]>,
    overlays: Ref<ElevationOverlays>,
    overviewRef: Ref<HTMLCanvasElement | null>,
    onRangeChange: (range: IndexRange | null) => void,
    onResetRequest: () => void
) {
    const chartInstance = shallowRef<Chart | null>(null);
    /** Distance in km per point, the chart's x — kept here too for the overview strip. */
    let xs: number[] = [];
    /** The path the datasets were last built from: a rebuild for another path drops the zoom. */
    let builtFor: Path | null = null;
    let lastRange: IndexRange | null = null;

    // --- Zoom window ----------------------------------------------------------------------------

    /** The visible x window in km, or null when the whole ride is shown. */
    const visibleWindow = (chart: Chart): { min: number; max: number } | null => {
        const x = chart.scales.x;
        if (!x || xs.length < 2) {
            return null;
        }
        const first = xs[0];
        const last = xs[xs.length - 1];
        const eps = (last - first) * 1e-6;
        if (x.min <= first + eps && x.max >= last - eps) {
            return null;
        }
        return { min: x.min, max: x.max };
    };

    const emitRange = (chart: Chart) => {
        const w = visibleWindow(chart);
        const range =
            w === null
                ? null
                : {
                      start: Math.min(lowerBound(xs, w.min), xs.length - 1),
                      end: Math.max(lowerBound(xs, w.max) - 1, 0),
                  };
        if (range?.start === lastRange?.start && range?.end === lastRange?.end) {
            return;
        }
        lastRange = range;
        onRangeChange(range);
    };

    /**
     * Fit every y axis to what the x window shows, or hand it back to Chart.js's auto-range when
     * the whole ride is shown. Without it a 30 m bridge is a flat line on an axis sized for the
     * 1 000 m the ride climbs elsewhere.
     *
     * The zoom plugin cannot do this itself, and it gets in the way: it records a scale's
     * "original" limits again whenever they changed behind its back — which a y fit does — so its
     * `resetZoom` would restore the fitted range. Clearing the y limits whenever the window is the
     * full ride (and `resetZoom` ends in `onZoomComplete`, which lands here) sidesteps that.
     */
    const fitY = (chart: Chart) => {
        const scales = chart.options.scales ?? {};
        const w = visibleWindow(chart);
        const i0 = w ? lowerBound(xs, w.min) : 0;
        const i1 = w ? lowerBound(xs, w.max) : xs.length;
        for (const [id, options] of Object.entries(scales)) {
            if (id === 'x' || !options) {
                continue;
            }
            const linear = options as { min?: number; max?: number };
            let lo = Infinity;
            let hi = -Infinity;
            if (w !== null) {
                (chart.data.datasets as ChartDataset<'line'>[]).forEach((dataset, d) => {
                    if (dataset.yAxisID !== id || !chart.isDatasetVisible(d)) {
                        return;
                    }
                    const data = dataset.data as (number | null)[];
                    for (let i = Math.max(i0 - 1, 0); i <= i1 && i < data.length; i++) {
                        const v = data[i];
                        if (v !== null && Number.isFinite(v)) {
                            lo = Math.min(lo, v);
                            hi = Math.max(hi, v);
                        }
                    }
                });
            }
            if (!Number.isFinite(lo)) {
                delete linear.min;
                delete linear.max;
                continue;
            }
            const pad = Math.max((hi - lo) * 0.08, 0.5);
            linear.min = lo - pad;
            linear.max = hi + pad;
        }
        chart.update('none');
        emitRange(chart);
        drawOverview();
    };

    // One fit per frame: the zoom plugin fires `onZoom` on every wheel tick and `onPan` on every
    // pointer move.
    let fitFrame = 0;
    const scheduleFit = () => {
        if (fitFrame) {
            return;
        }
        fitFrame = requestAnimationFrame(() => {
            fitFrame = 0;
            if (chartInstance.value) {
                fitY(chartInstance.value);
            }
        });
    };

    const resetZoom = () => {
        const chart = chartInstance.value;
        if (!chart) {
            return;
        }
        chart.resetZoom('none');
        // The plugin restores the x limits it recorded, which are a kept window if the datasets
        // were rebuilt mid-zoom. The whole ride has no x limits at all.
        const x = chart.options.scales?.x as { min?: number; max?: number } | undefined;
        if (x) {
            delete x.min;
            delete x.max;
        }
        fitY(chart);
    };

    // --- Chart ----------------------------------------------------------------------------------

    const createChart = () => {
        if (!canvasRef.value) {
            return;
        }

        const ctx = canvasRef.value.getContext('2d');
        if (!ctx) {
            return;
        }

        chartInstance.value = new Chart(ctx, {
            type: 'line',
            data: {
                labels: [],
                datasets: [],
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                animation: false,
                interaction: {
                    mode: 'index',
                    intersect: false,
                },
                onHover: (_event, activeElements) => {
                    if (activeElements.length > 0) {
                        const index = activeElements[0].index;
                        onHoverChange(index);
                    } else {
                        onHoverChange(null);
                    }
                },
                plugins: {
                    legend: {
                        display: true,
                        position: 'top',
                        // Hiding a series changes what the y fit has to cover.
                        onClick(event, item, legend) {
                            Chart.defaults.plugins.legend.onClick.call(this, event, item, legend);
                            scheduleFit();
                        },
                    },
                    tooltip: {
                        enabled: true,
                    },
                    zoom: {
                        limits: { x: { min: 'original', max: 'original', minRange: 0.05 } },
                        zoom: {
                            wheel: {
                                enabled: true,
                            },
                            pinch: {
                                enabled: true,
                            },
                            // Drag a rectangle to zoom onto it; shift+drag pans, see below.
                            drag: {
                                enabled: true,
                                threshold: 5,
                                backgroundColor: 'rgba(59, 130, 246, 0.12)',
                                borderColor: 'rgba(59, 130, 246, 0.6)',
                                borderWidth: 1,
                            },
                            mode: 'x',
                            onZoom: scheduleFit,
                            onZoomComplete: scheduleFit,
                        },
                        pan: {
                            enabled: true,
                            mode: 'x',
                            // Tells a mouse pan from a drag-zoom. The plugin checks the key for
                            // mouse pointers only, so a finger still pans with no key.
                            modifierKey: 'shift',
                            onPan: scheduleFit,
                            onPanComplete: scheduleFit,
                        },
                    },
                },
                scales: {},
            },
            plugins: [
                {
                    // Shade each ClimbPart across the x-range it covers, in the conventional
                    // cycling grade colours. Drawn in `beforeDatasetsDraw` so the bands sit
                    // behind the series rather than hiding them, and keyed off distance (the
                    // chart's x unit) so it works regardless of which fields are selected.
                    id: 'climbBands',
                    beforeDatasetsDraw: chart => {
                        if (climbs.value.length === 0) {
                            return;
                        }
                        const xAxis = chart.scales.x;
                        const yAxis = chart.scales.y || Object.values(chart.scales)[1];
                        if (!xAxis || !yAxis) {
                            return;
                        }
                        const drawCtx = chart.ctx;
                        drawCtx.save();
                        drawCtx.beginPath();
                        drawCtx.rect(
                            xAxis.left,
                            yAxis.top,
                            xAxis.right - xAxis.left,
                            yAxis.bottom - yAxis.top
                        );
                        drawCtx.clip();
                        for (const climb of climbs.value) {
                            for (const part of climb.parts) {
                                const x0 = xAxis.getPixelForValue(part.startDistanceM / 1000);
                                const x1 = xAxis.getPixelForValue(part.endDistanceM / 1000);
                                if (!Number.isFinite(x0) || !Number.isFinite(x1)) {
                                    continue;
                                }
                                drawCtx.fillStyle = gradeColor(part.grade);
                                drawCtx.globalAlpha = 0.18;
                                drawCtx.fillRect(
                                    x0,
                                    yAxis.top,
                                    Math.max(x1 - x0, 1),
                                    yAxis.bottom - yAxis.top
                                );
                            }
                        }
                        drawCtx.restore();
                    },
                },
                {
                    // The validation bench's structures: a light tint over the plot and a solid
                    // strip along its bottom edge, so they still read over the climb shading.
                    id: 'structureBands',
                    beforeDatasetsDraw: chart => {
                        const bands = overlays.value.bands;
                        const xAxis = chart.scales.x;
                        const area = chart.chartArea;
                        if (bands.length === 0 || !xAxis || !area) {
                            return;
                        }
                        const drawCtx = chart.ctx;
                        drawCtx.save();
                        drawCtx.beginPath();
                        drawCtx.rect(area.left, area.top, area.width, area.height);
                        drawCtx.clip();
                        for (const band of bands) {
                            const x0 = xAxis.getPixelForValue(band.startKm);
                            const x1 = xAxis.getPixelForValue(band.endKm);
                            if (!Number.isFinite(x0) || !Number.isFinite(x1)) {
                                continue;
                            }
                            const width = Math.max(x1 - x0, 2);
                            drawCtx.fillStyle = BAND_STYLES[band.kind].color;
                            drawCtx.globalAlpha = 0.1;
                            drawCtx.fillRect(x0, area.top, width, area.height);
                            drawCtx.globalAlpha = 0.75;
                            drawCtx.fillRect(x0, area.bottom - 6, width, 6);
                        }
                        drawCtx.restore();
                    },
                },
                {
                    id: 'crosshair',
                    afterDraw: chart => {
                        if (hoveredInfo.value === null) {
                            return;
                        }

                        const index = hoveredInfo.value.index;
                        const meta = chart.getDatasetMeta(0);
                        if (!meta || !meta.data[index]) {
                            return;
                        }

                        const x = meta.data[index].x;
                        const yAxis = chart.scales.y || Object.values(chart.scales)[1];
                        if (!yAxis) {
                            return;
                        }

                        const drawCtx = chart.ctx;
                        drawCtx.save();
                        drawCtx.beginPath();
                        drawCtx.moveTo(x, yAxis.top);
                        drawCtx.lineTo(x, yAxis.bottom);
                        drawCtx.lineWidth = 2;
                        drawCtx.strokeStyle = 'rgba(255, 0, 0, 0.8)';
                        drawCtx.setLineDash([5, 5]);
                        drawCtx.stroke();
                        drawCtx.restore();
                    },
                },
            ],
        });

        // Double-click anywhere on the plot: back to the whole ride, chart and map alike.
        canvasRef.value.addEventListener('dblclick', onResetRequest);
    };

    /** The overlays drawn against the elevation field, on its axis. */
    const overlayDatasets = (): ChartDataset<'line'>[] => {
        if (ELEVATION_AXIS === null || !selectedFields.value.has('elevation')) {
            return [];
        }
        const out: ChartDataset<'line'>[] = [];
        const { withoutCorrections, reference } = overlays.value;
        if (withoutCorrections) {
            out.push({
                label: 'Elevation without corrections',
                data: withoutCorrections,
                borderColor: 'rgba(234, 88, 12, 0.9)',
                yAxisID: ELEVATION_AXIS,
                tension: 0,
                pointRadius: 0,
                borderWidth: 1.5,
            });
        }
        if (reference) {
            out.push({
                label: 'Estimated roadway (IGN bench reference)',
                data: reference,
                borderColor: 'rgba(15, 23, 42, 0.85)',
                borderDash: [6, 4],
                yAxisID: ELEVATION_AXIS,
                tension: 0,
                pointRadius: 0,
                borderWidth: 1.5,
            });
        }
        return out;
    };

    const updateChart = () => {
        const chart = chartInstance.value;
        if (!chart || !currentPath.value) {
            return;
        }

        const path = currentPath.value;
        const pointCount = pathSize(path);
        // The overlays land after the ride (the comparison run comes second) and must not throw
        // away the window the user is looking at; a different path must.
        const keep = builtFor === path ? visibleWindow(chart) : null;
        if (builtFor !== path) {
            builtFor = path;
            lastRange = null;
            onRangeChange(null);
        }

        // Create distance labels (km)
        const labels: number[] = [];
        for (let i = 0; i < pointCount; i++) {
            const distance = getField(path, i, 'distance');
            labels.push(distance / 1000);
        }
        xs = labels;

        const datasets: ChartDataset<'line'>[] = [];
        const scales: Record<string, ScaleOptions> = {
            x: {
                type: 'linear',
                title: {
                    display: true,
                    text: 'Distance (km)',
                },
                ...(keep ?? {}),
            },
        };

        const axisPositions: Record<string, 'left' | 'right'> = {};
        let leftAxisCount = 0;
        let rightAxisCount = 0;

        selectedFields.value.forEach(fieldKey => {
            // Find category for this field
            let category = null;
            let fieldDef = null;
            for (const [, cat] of Object.entries(fieldConfig)) {
                if (cat.fields[fieldKey]) {
                    category = cat;
                    fieldDef = cat.fields[fieldKey];
                    break;
                }
            }

            if (!category || !fieldDef) {
                return;
            }

            // Pull data using string-keyed getField from Kotlin/JS engine
            const data: (number | null)[] = [];
            for (let i = 0; i < pointCount; i++) {
                const value = getField(path, i, fieldKey);
                data.push(value);
            }

            const axisId = category.axis;
            if (!axisPositions[axisId]) {
                axisPositions[axisId] = leftAxisCount <= rightAxisCount ? 'left' : 'right';
                if (axisPositions[axisId] === 'left') {
                    leftAxisCount++;
                } else {
                    rightAxisCount++;
                }

                scales[axisId] = {
                    type: 'linear',
                    position: axisPositions[axisId],
                    title: {
                        display: true,
                        text: `${category.name} (${category.unit})`,
                    },
                    grid: {
                        drawOnChartArea: axisPositions[axisId] === 'left',
                    },
                };
            }

            datasets.push({
                label: fieldDef.shortDescription,
                data,
                borderColor: fieldDef.color,
                yAxisID: axisId,
                tension: 0,
                pointRadius: 0,
                borderWidth: 2,
            });
        });

        datasets.push(...overlayDatasets());

        chart.data.labels = labels;
        chart.data.datasets = datasets;
        chart.options.scales = scales;
        chart.update('none');
        buildOverviewProfile();
        fitY(chart);
    };

    const resize = () => {
        chartInstance.value?.resize();
        buildOverviewProfile();
        drawOverview();
    };

    const hasData = computed(() => currentPath.value !== null);

    watch([currentPath, selectedFields, overlays], updateChart, { deep: true });

    watch(hoveredInfo, () => {
        if (chartInstance.value) {
            chartInstance.value.update('none');
        }
    });

    watch(climbs, () => {
        chartInstance.value?.update('none');
    });

    /** Zoom the x axis onto a distance window, in meters. Used when a climb row is clicked. */
    const zoomToDistanceRange = (startM: number, endM: number) => {
        const chart = chartInstance.value;
        if (!chart) {
            return;
        }
        // A little margin either side so the climb is not flush against the plot edges.
        const marginKm = Math.max((endM - startM) / 1000 / 20, 0.1);
        chart.zoomScale(
            'x',
            { min: startM / 1000 - marginKm, max: endM / 1000 + marginKm },
            'none'
        );
        fitY(chart);
    };

    // --- Overview strip -------------------------------------------------------------------------
    //
    // The whole ride's elevation, small, with the zoom window drawn over it. Dragging inside the
    // window moves it, dragging elsewhere brushes a new one, a click recentres it and a
    // double-click resets. Pointer events, so a finger works too — it is the touch way to pan.

    /** The profile, pre-rendered once per path and size; a redraw only blits it. */
    let overviewProfile: HTMLCanvasElement | null = null;

    const overviewSize = () => {
        const canvas = overviewRef.value;
        if (!canvas) {
            return null;
        }
        const dpr = window.devicePixelRatio || 1;
        const width = canvas.clientWidth;
        const height = canvas.clientHeight;
        if (width === 0 || height === 0) {
            return null;
        }
        const pw = Math.round(width * dpr);
        const ph = Math.round(height * dpr);
        if (canvas.width !== pw || canvas.height !== ph) {
            canvas.width = pw;
            canvas.height = ph;
        }
        return { width, height, dpr };
    };

    const buildOverviewProfile = () => {
        overviewProfile = null;
        const size = overviewSize();
        const path = currentPath.value;
        if (!size || !path || xs.length < 2) {
            return;
        }
        const n = xs.length;
        const elevation = new Float64Array(n);
        let lo = Infinity;
        let hi = -Infinity;
        for (let i = 0; i < n; i++) {
            const e = getField(path, i, 'elevation');
            elevation[i] = e;
            if (Number.isFinite(e)) {
                lo = Math.min(lo, e);
                hi = Math.max(hi, e);
            }
        }
        if (!Number.isFinite(lo)) {
            return;
        }
        const off = document.createElement('canvas');
        off.width = Math.round(size.width * size.dpr);
        off.height = Math.round(size.height * size.dpr);
        const g = off.getContext('2d');
        if (!g) {
            return;
        }
        g.scale(size.dpr, size.dpr);
        const x0 = xs[0];
        const kx = size.width / (xs[n - 1] - x0 || 1);
        const ky = (size.height - 4) / Math.max(hi - lo, 1);
        g.beginPath();
        g.moveTo(0, size.height);
        for (let i = 0; i < n; i++) {
            if (Number.isFinite(elevation[i])) {
                g.lineTo((xs[i] - x0) * kx, size.height - 2 - (elevation[i] - lo) * ky);
            }
        }
        g.lineTo(size.width, size.height);
        g.closePath();
        g.fillStyle = 'rgba(100, 116, 139, 0.45)';
        g.fill();
        overviewProfile = off;
    };

    const drawOverview = () => {
        const canvas = overviewRef.value;
        const size = overviewSize();
        const chart = chartInstance.value;
        if (!canvas || !size || !chart) {
            return;
        }
        const g = canvas.getContext('2d');
        if (!g) {
            return;
        }
        // Self-healing: the profile is missing when the path arrived while the strip was still
        // hidden (`v-show` lays it out at width 0), and stale after a resize. Rebuild it here
        // rather than trusting every caller to have done it at the right moment.
        if (
            !overviewProfile ||
            overviewProfile.width !== canvas.width ||
            overviewProfile.height !== canvas.height
        ) {
            buildOverviewProfile();
        }
        g.setTransform(1, 0, 0, 1, 0, 0);
        g.clearRect(0, 0, canvas.width, canvas.height);
        if (!overviewProfile || xs.length < 2) {
            return;
        }
        g.drawImage(overviewProfile, 0, 0);
        const w = visibleWindow(chart);
        if (w === null) {
            return;
        }
        g.scale(size.dpr, size.dpr);
        const x0 = xs[0];
        const kx = size.width / (xs[xs.length - 1] - x0 || 1);
        const a = (w.min - x0) * kx;
        const b = (w.max - x0) * kx;
        g.fillStyle = 'rgba(255, 255, 255, 0.7)';
        g.fillRect(0, 0, Math.max(a, 0), size.height);
        g.fillRect(b, 0, Math.max(size.width - b, 0), size.height);
        g.strokeStyle = 'rgba(37, 99, 235, 0.9)';
        g.lineWidth = 1.5;
        g.strokeRect(a, 0.75, Math.max(b - a, 2), size.height - 1.5);
    };

    /** The km under a pointer over the overview strip. */
    const overviewKm = (event: PointerEvent): number => {
        const rect = (event.currentTarget as HTMLCanvasElement).getBoundingClientRect();
        const t = Math.min(1, Math.max(0, (event.clientX - rect.left) / rect.width));
        return xs[0] + t * (xs[xs.length - 1] - xs[0]);
    };

    let drag: { mode: 'move' | 'brush'; anchorKm: number; startX: number; moved: boolean } | null =
        null;

    const setWindow = (min: number, max: number) => {
        const chart = chartInstance.value;
        if (!chart || xs.length < 2) {
            return;
        }
        const first = xs[0];
        const last = xs[xs.length - 1];
        const width = Math.min(Math.max(max - min, 0.05), last - first);
        let lo = Math.max(first, min);
        let hi = lo + width;
        if (hi > last) {
            hi = last;
            lo = last - width;
        }
        chart.zoomScale('x', { min: lo, max: hi }, 'none');
        scheduleFit();
    };

    const onOverviewDown = (event: PointerEvent) => {
        const chart = chartInstance.value;
        if (!chart || xs.length < 2 || event.button !== 0) {
            return;
        }
        const km = overviewKm(event);
        const w = visibleWindow(chart);
        const inside = w !== null && km >= w.min && km <= w.max;
        drag = {
            mode: inside ? 'move' : 'brush',
            anchorKm: w !== null && inside ? km - w.min : km,
            startX: event.clientX,
            moved: false,
        };
        (event.currentTarget as HTMLCanvasElement).setPointerCapture(event.pointerId);
    };

    const onOverviewMove = (event: PointerEvent) => {
        const chart = chartInstance.value;
        if (!drag || !chart) {
            return;
        }
        if (!drag.moved && Math.abs(event.clientX - drag.startX) < 4) {
            return;
        }
        drag.moved = true;
        const km = overviewKm(event);
        if (drag.mode === 'move') {
            const w = visibleWindow(chart);
            if (w) {
                setWindow(km - drag.anchorKm, km - drag.anchorKm + (w.max - w.min));
            }
        } else {
            setWindow(Math.min(drag.anchorKm, km), Math.max(drag.anchorKm, km));
        }
    };

    /** The browser took the pointer away (system gesture, tab switch): drop the drag. */
    const onOverviewCancel = () => {
        drag = null;
    };

    const onOverviewUp = (event: PointerEvent) => {
        const chart = chartInstance.value;
        const current = drag;
        drag = null;
        if (!current || current.moved || !chart) {
            return;
        }
        // A plain click: recentre the window there, or open a tenth of the ride around it.
        const km = overviewKm(event);
        const w = visibleWindow(chart);
        const width = w ? w.max - w.min : (xs[xs.length - 1] - xs[0]) / 10;
        setWindow(km - width / 2, km + width / 2);
    };

    const attachOverview = (canvas: HTMLCanvasElement | null, old?: HTMLCanvasElement | null) => {
        if (old) {
            old.removeEventListener('pointerdown', onOverviewDown);
            old.removeEventListener('pointermove', onOverviewMove);
            old.removeEventListener('pointerup', onOverviewUp);
            old.removeEventListener('pointercancel', onOverviewCancel);
            old.removeEventListener('lostpointercapture', onOverviewCancel);
            old.removeEventListener('dblclick', onResetRequest);
            overviewObserver?.disconnect();
            overviewObserver = null;
        }
        if (canvas) {
            canvas.addEventListener('pointerdown', onOverviewDown);
            canvas.addEventListener('pointermove', onOverviewMove);
            canvas.addEventListener('pointerup', onOverviewUp);
            canvas.addEventListener('pointercancel', onOverviewCancel);
            canvas.addEventListener('lostpointercapture', onOverviewCancel);
            canvas.addEventListener('dblclick', onResetRequest);
            // Fires when the strip first becomes visible and on every width change (window
            // resize, grid breakpoint, sidebar) — Chart.js resizes itself, this strip does not.
            overviewObserver = new ResizeObserver(() => drawOverview());
            overviewObserver.observe(canvas);
            drawOverview();
        }
    };
    let overviewObserver: ResizeObserver | null = null;
    watch(overviewRef, (canvas, old) => attachOverview(canvas, old), { immediate: true });

    onUnmounted(() => {
        if (fitFrame) {
            cancelAnimationFrame(fitFrame);
        }
        attachOverview(null, overviewRef.value);
        canvasRef.value?.removeEventListener('dblclick', onResetRequest);
        chartInstance.value?.destroy();
    });

    return {
        chartInstance,
        createChart,
        updateChart,
        resetZoom,
        resize,
        hasData,
        zoomToDistanceRange,
    };
}
