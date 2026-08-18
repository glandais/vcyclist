<script setup lang="ts">
import { useToast } from '@nuxt/ui/composables';
import { computed, nextTick, onActivated, ref, watch } from 'vue';
import ElevationChart from '~/components/ElevationChart.vue';
import ElevationControls from '~/components/ElevationControls.vue';
import ElevationMapView from '~/components/ElevationMapView.vue';
import ElevationStatsBar from '~/components/ElevationStatsBar.vue';
import { useElevationExplorer, type ExplorerMode } from '~/composables/useElevationExplorer';
import type { ReliefMode } from '~/composables/useElevationMap';

const toast = useToast();

const {
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
    loadSample,
    loadFile,
} = useElevationExplorer();

const mapRef = ref<InstanceType<typeof ElevationMapView> | null>(null);
const chartRef = ref<InstanceType<typeof ElevationChart> | null>(null);

const relief = ref<ReliefMode>('hillshade');
const hasPath = computed(() => points.value.length > 0);

const statusClass = computed(() => {
    switch (statusKind.value) {
        case 'loading':
            return 'text-amber-600';
        case 'success':
            return 'text-emerald-700';
        case 'error':
            return 'text-red-600';
        default:
            return 'text-gray-500';
    }
});

const onReliefChange = (next: ReliefMode) => {
    relief.value = next;
    mapRef.value?.setRelief(next);
};

const onModeChange = (next: ExplorerMode) => setMode(next);

const onSampleSelect = async (url: string) => {
    try {
        await loadSample(url);
        await nextTick();
        mapRef.value?.fitBounds();
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'Load Failed',
            description: 'Failed to load GPX file: ' + (error as Error).message,
            duration: 5000,
        });
    }
};

const onFileUpload = async (file: File) => {
    try {
        await loadFile(file);
        await nextTick();
        mapRef.value?.fitBounds();
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'Upload Failed',
            description: 'Failed to read GPX file: ' + (error as Error).message,
            duration: 5000,
        });
    }
};

// Kept alive across tab switches: re-measure the map and the chart on the way back in.
const resizeAll = () => {
    chartRef.value?.resize();
    mapRef.value?.invalidateSize();
};

onActivated(async () => {
    await nextTick();
    resizeAll();
});

watch(statusKind, kind => {
    if (kind === 'error') {
        toast.add({
            color: 'error',
            title: 'Elevation',
            description: status.value,
            duration: 5000,
        });
    }
});

defineExpose({ resizeAll });
</script>

<template>
    <div class="flex-1 min-h-0 flex flex-col overflow-y-auto">
        <ElevationControls
            v-model:smoothing="smoothing"
            v-model:filter="filter"
            :mode="mode"
            :relief="relief"
            :has-path="hasPath"
            @mode-change="onModeChange"
            @relief-change="onReliefChange"
            @clear-path="clearPath"
            @sample-select="onSampleSelect"
            @file-upload="onFileUpload"
        />

        <div class="px-4 pt-3 text-sm" :class="statusClass">{{ status }}</div>

        <div class="grid grid-cols-1 xl:grid-cols-2 gap-4 p-4 flex-1 min-h-0">
            <div class="flex h-full border border-gray-200 rounded-lg overflow-hidden bg-white">
                <ElevationChart ref="chartRef" :profile="profile" class="flex-1" />
            </div>

            <div class="flex h-full border border-gray-200 rounded-lg overflow-hidden bg-white">
                <ElevationMapView
                    ref="mapRef"
                    :mode="mode"
                    :points="points"
                    @point-click="addPoint"
                />
            </div>
        </div>

        <div v-if="stats" class="px-4 pb-4">
            <ElevationStatsBar :stats="stats" />
        </div>
    </div>
</template>
