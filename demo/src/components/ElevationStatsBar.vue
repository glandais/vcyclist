<script setup lang="ts">
import { computed } from 'vue';
import type { ElevationStats } from '~/composables/useElevationExplorer';

const props = defineProps<{ stats: ElevationStats | null }>();

const formatDistance = (meters: number) =>
    meters >= 1000 ? `${(meters / 1000).toFixed(2)} km` : `${meters.toFixed(0)} m`;

const tiles = computed(() => {
    const s = props.stats;
    if (!s) {
        return [];
    }
    return [
        { label: 'Distance', value: formatDistance(s.totalDistance) },
        { label: 'Points', value: String(s.pointCount) },
        { label: 'Min elevation', value: `${s.minElevation.toFixed(0)} m` },
        { label: 'Max elevation', value: `${s.maxElevation.toFixed(0)} m` },
        { label: 'Total ascent', value: `${s.totalAscent.toFixed(0)} m` },
        { label: 'Total descent', value: `${s.totalDescent.toFixed(0)} m` },
    ];
});
</script>

<template>
    <div v-if="tiles.length" class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-2">
        <div
            v-for="tile in tiles"
            :key="tile.label"
            class="rounded-lg border border-gray-200 bg-white px-3 py-2 text-center"
        >
            <div class="text-xs uppercase tracking-wide text-gray-500">{{ tile.label }}</div>
            <div class="text-lg font-semibold text-gray-800">{{ tile.value }}</div>
        </div>
    </div>
</template>
