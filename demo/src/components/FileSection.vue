<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { gpxSamples } from '~/config/gpxSamples';
import {
    getField,
    pathElevationCorrections,
    pathElevationGain,
    pathElevationLoss,
    pathReportedElevationGain,
    pathReportedElevationLoss,
    pathSize,
    pathTotalDistance,
    type Path,
} from '@glandais/vcyclist-engine';

const props = defineProps<{
    fileName: string;
    currentPath: Path | null;
    isProcessing: boolean;
}>();

const emit = defineEmits<{
    gpxSelect: [url: string];
    fileUpload: [file: File];
}>();

const selectedGPX = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

const onGPXChange = (value: string | null) => {
    if (value) {
        emit('gpxSelect', value);
        if (fileInput.value) {
            fileInput.value.value = '';
        }
    }
};

const onFileChange = (event: Event) => {
    const target = event.target as HTMLInputElement;
    if (target.files && target.files[0]) {
        emit('fileUpload', target.files[0]);
        selectedGPX.value = null;
    }
};

const fileInfo = computed(() => {
    const path = props.currentPath;
    if (!path) {
        return null;
    }

    const n = pathSize(path);
    let minElevation = Number.POSITIVE_INFINITY;
    let maxElevation = Number.NEGATIVE_INFINITY;
    for (let i = 0; i < n; i++) {
        const e = getField(path, i, 'elevation');
        if (Number.isFinite(e)) {
            if (e < minElevation) {
                minElevation = e;
            }
            if (e > maxElevation) {
                maxElevation = e;
            }
        }
    }
    if (!Number.isFinite(minElevation)) {
        minElevation = 0;
        maxElevation = 0;
    }

    return {
        pointCount: n,
        distance: pathTotalDistance(path) / 1000,
        // The dead-banded figures once the pipeline has run, the raw sums before it. The raw
        // pair stays visible as a tooltip: on a noisy trace they differ by 40 % and hiding that
        // makes the headline look arbitrary.
        elevationGain: pathReportedElevationGain(path),
        elevationLoss: pathReportedElevationLoss(path),
        rawElevationGain: pathElevationGain(path),
        rawElevationLoss: pathElevationLoss(path),
        minElevation,
        maxElevation,
    };
});

/** A status id as a human reads it: `none-found` → `none found`. */
const statusLabel = (id: string, applied: number): string =>
    id === 'applied' ? `applied (${applied})` : id.replace(/-/g, ' ');

/**
 * What the bridge-deck and tunnel corrections did, once the pipeline has run. Hidden on a parsed
 * file (no report) and when neither step ran (no DEM lookup): a line saying "not run" twice for
 * every ride without `fixElevation` would be noise.
 */
const elevationCorrections = computed(() => {
    const path = props.currentPath;
    const report = path ? pathElevationCorrections(path) : null;
    if (!report || (report.bridgeDecks === 'not-run' && report.tunnels === 'not-run')) {
        return null;
    }
    return {
        line:
            `Bridge decks: ${statusLabel(report.bridgeDecks, report.bridgeDecksApplied)}` +
            ` · Tunnels: ${statusLabel(report.tunnels, report.tunnelsApplied)}`,
        warnings: report.warnings,
    };
});

watch(
    () => props.currentPath,
    () => {
        // Reset file input when path changes
    }
);
</script>

<template>
    <section class="p-6 bg-gray-50 border-b border-gray-200">
        <div class="grid grid-cols-1 md:grid-cols-2 gap-8 mb-4">
            <div class="flex flex-col gap-2">
                <label for="gpx-select" class="font-semibold text-gray-700"
                    >Choose sample GPX:</label
                >
                <USelect
                    id="gpx-select"
                    v-model="selectedGPX"
                    :items="gpxSamples"
                    label-key="label"
                    value-key="value"
                    placeholder="Select a sample file..."
                    :disabled="isProcessing"
                    @update:modelValue="onGPXChange"
                    class="w-full"
                />
            </div>
            <div class="flex flex-col gap-2">
                <label for="gpx-upload" class="font-semibold text-gray-700"
                    >Or upload your own:</label
                >
                <input
                    id="gpx-upload"
                    ref="fileInput"
                    type="file"
                    accept=".gpx"
                    :disabled="isProcessing"
                    @change="onFileChange"
                    class="px-3 py-2 border-2 border-gray-300 rounded-lg text-base transition-colors focus:outline-none focus:border-blue-500 focus:ring-4 focus:ring-blue-500/10"
                />
            </div>
        </div>
        <UCollapsible
            v-if="fileInfo"
            id="file-info"
            class="mt-4 rounded-lg border border-blue-200 bg-blue-50"
        >
            <template #default="{ open }">
                <button
                    type="button"
                    class="flex w-full items-center justify-between p-4 text-blue-900 cursor-pointer"
                >
                    <span class="font-semibold">📄 {{ fileName }}</span>
                    <span class="text-xs">{{ open ? '▲' : '▼' }}</span>
                </button>
            </template>
            <template #content>
                <div class="grid grid-cols-1 md:grid-cols-2 gap-4 p-4 pt-0">
                    <div class="bg-white p-2 rounded text-center">
                        <div class="text-sm text-gray-600">Info</div>
                        <div class="text-base font-semibold text-blue-900">
                            {{ fileInfo.pointCount.toLocaleString() }} pts /
                            {{ fileInfo.distance.toFixed(1) }} km
                        </div>
                    </div>
                    <div class="bg-white p-2 rounded text-center">
                        <div class="text-sm text-gray-600">Elevation</div>
                        <div
                            class="text-base font-semibold text-blue-900"
                            :title="`unfiltered sum: +${fileInfo.rawElevationGain.toFixed(0)}m / -${(-fileInfo.rawElevationLoss).toFixed(0)}m`"
                        >
                            +{{ fileInfo.elevationGain.toFixed(0) }}m / -{{
                                (-fileInfo.elevationLoss).toFixed(0)
                            }}m [{{ fileInfo.minElevation.toFixed(0) }},
                            {{ fileInfo.maxElevation.toFixed(0) }}]m
                        </div>
                    </div>
                    <div
                        v-if="elevationCorrections"
                        class="bg-white p-2 rounded text-center md:col-span-2"
                    >
                        <div class="text-sm text-gray-600">Elevation corrections</div>
                        <div class="text-base font-semibold text-blue-900">
                            {{ elevationCorrections.line }}
                        </div>
                    </div>
                </div>
            </template>
        </UCollapsible>
        <UAlert
            v-if="elevationCorrections && elevationCorrections.warnings.length > 0"
            class="mt-4"
            color="warning"
            variant="subtle"
            title="Elevation corrections"
        >
            <template #description>
                <ul class="list-disc pl-4">
                    <li v-for="warning in elevationCorrections.warnings" :key="warning">
                        {{ warning }}
                    </li>
                </ul>
            </template>
        </UAlert>
    </section>
</template>
