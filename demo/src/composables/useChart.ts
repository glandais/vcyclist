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
import { computed, type Ref, shallowRef, watch } from 'vue';
import { fieldConfig } from '~/config/fieldConfig';
import { gradeColor } from './useClimbs';
import { type ClimbDto, getField, pathSize, type Path } from '~/engine-shim';
import type { HoverInfo } from './useHoverSync';

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

export function useChart(
    canvasRef: Ref<HTMLCanvasElement | null>,
    currentPath: Ref<Path | null>,
    selectedFields: Ref<Set<string>>,
    hoveredInfo: Ref<HoverInfo | null>,
    onHoverChange: (index: number | null) => void,
    climbs: Ref<ClimbDto[]>
) {
    const chartInstance = shallowRef<Chart | null>(null);

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
                    },
                    tooltip: {
                        enabled: true,
                    },
                    zoom: {
                        zoom: {
                            wheel: {
                                enabled: true,
                            },
                            pinch: {
                                enabled: true,
                            },
                            mode: 'x',
                        },
                        pan: {
                            enabled: true,
                            mode: 'x',
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
    };

    const updateChart = () => {
        if (!chartInstance.value || !currentPath.value) {
            return;
        }

        const path = currentPath.value;
        const pointCount = pathSize(path);

        // Create distance labels (km)
        const labels: number[] = [];
        for (let i = 0; i < pointCount; i++) {
            const distance = getField(path, i, 'distance');
            labels.push(distance / 1000);
        }

        const datasets: ChartDataset<'line'>[] = [];
        const scales: Record<string, ScaleOptions> = {
            x: {
                type: 'linear',
                title: {
                    display: true,
                    text: 'Distance (km)',
                },
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

        chartInstance.value.data.labels = labels;
        chartInstance.value.data.datasets = datasets;
        chartInstance.value.options.scales = scales;
        chartInstance.value.update('none');
    };

    const resetZoom = () => {
        if (chartInstance.value) {
            chartInstance.value.resetZoom();
        }
    };

    const resize = () => {
        if (chartInstance.value) {
            chartInstance.value.resize();
        }
    };

    const hasData = computed(() => currentPath.value !== null);

    watch([currentPath, selectedFields], updateChart, { deep: true });

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
            'default'
        );
    };

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
