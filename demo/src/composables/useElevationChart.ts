import {
    CategoryScale,
    Chart,
    Filler,
    Legend,
    LinearScale,
    LineController,
    LineElement,
    PointElement,
    Title,
    Tooltip,
} from 'chart.js';
import { onUnmounted, type Ref, shallowRef, watch } from 'vue';
import type { CoordinatesElevationDto } from '@glandais/vcyclist-elevation';
import { cumulativeDistances } from '~/utils/geo';

// Idempotent, and the GPX view registers the same set plus the zoom plugin — this view does not
// need zoom, so it registers only what it draws.
Chart.register(
    LineController,
    CategoryScale,
    LinearScale,
    PointElement,
    LineElement,
    Title,
    Tooltip,
    Legend,
    Filler
);

const formatDistance = (meters: number) =>
    meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${meters.toFixed(0)} m`;

export function useElevationChart(
    canvasRef: Ref<HTMLCanvasElement | null>,
    profile: Ref<CoordinatesElevationDto[]>
) {
    const chart = shallowRef<Chart | null>(null);

    const render = () => {
        const canvas = canvasRef.value;
        if (!canvas) {
            return;
        }

        const data = profile.value;
        if (data.length === 0) {
            chart.value?.destroy();
            chart.value = null;
            return;
        }

        const distances = cumulativeDistances(data);
        const labels = distances.map(formatDistance);
        const elevations = data.map(p => p.elevation);

        if (chart.value) {
            chart.value.data.labels = labels;
            chart.value.data.datasets[0].data = elevations;
            chart.value.update('none');
            return;
        }

        chart.value = new Chart(canvas, {
            type: 'line',
            data: {
                labels,
                datasets: [
                    {
                        label: 'Elevation (m)',
                        data: elevations,
                        borderColor: '#2c5aa0',
                        backgroundColor: 'rgba(44, 90, 160, 0.15)',
                        borderWidth: 2,
                        pointRadius: 0,
                        fill: true,
                        tension: 0.1,
                    },
                ],
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            label: context =>
                                `Elevation: ${(context.parsed.y as number).toFixed(1)} m`,
                        },
                    },
                },
                scales: {
                    x: {
                        title: { display: true, text: 'Distance' },
                        ticks: { maxTicksLimit: 10, autoSkip: true },
                    },
                    y: {
                        title: { display: true, text: 'Elevation (m)' },
                        ticks: { callback: value => `${Number(value).toFixed(0)} m` },
                    },
                },
            },
        });
    };

    const resize = () => {
        chart.value?.resize();
    };

    watch(profile, render);
    onUnmounted(() => {
        chart.value?.destroy();
        chart.value = null;
    });

    return { chart, render, resize };
}
