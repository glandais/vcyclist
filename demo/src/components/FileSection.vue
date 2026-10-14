<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { gpxSampleItems, type GpxSample } from '~/config/gpxSamples';
import type { ValidationReference } from '~/composables/useValidationReference';
import {
    getField,
    pathElevationCorrections,
    pathElevationGain,
    pathDurationMs,
    pathElevationLoss,
    pathReportedElevationGain,
    pathReportedElevationLoss,
    pathSize,
    pathTotalDistance,
    type Path,
    type TerrainProvenanceDto,
} from '@glandais/vcyclist-engine';

const props = defineProps<{
    fileName: string;
    currentPath: Path | null;
    isProcessing: boolean;
    /** The bundled validation sample loaded, if any, with its bench reference once fetched. */
    validationSample: GpxSample | null;
    reference: ValidationReference | null;
    /** The same ride with both elevation corrections off, once that run has landed. */
    comparisonPath: Path | null;
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
    const terrain = report.terrain ? terrainLabel(report.terrain) : '';
    return {
        line:
            `Bridge decks: ${statusLabel(report.bridgeDecks, report.bridgeDecksApplied)}` +
            ` · Tunnels: ${statusLabel(report.tunnels, report.tunnelsApplied)}` +
            (report.tunnelsOutOfScope > 0
                ? ` (${report.tunnelsOutOfScope} left on unmeasured terrain)`
                : '') +
            (terrain ? ` · Terrain: ${terrain}` : ''),
        warnings: report.warnings,
    };
});

/** `IGN LiDAR HD 98.2 km, Mapterhorn (France) 1.3 km`: each source read, longest first. */
function terrainLabel(t: TerrainProvenanceDto): string {
    const parts: Array<[string, number]> = [
        ['IGN LiDAR HD', t.ignLidarHdKm],
        ['Mapterhorn (France)', t.mapterhornMeasuredKm],
        ['Mapterhorn (abroad, unmeasured)', t.mapterhornKm],
        ['other source', t.otherKm],
        ['no data', t.noDataKm],
    ];
    return parts
        .filter(([, km]) => km >= 0.05)
        .sort((a, b) => b[1] - a[1])
        .map(([name, km]) => `${name} ${km.toFixed(1)} km`)
        .join(', ');
}

/**
 * What the corrections are worth on this ride: the reported climbing and the ride time with and
 * without them. Only once the comparison run exists, which it does only when something was
 * corrected.
 */
const withoutCorrections = computed(() => {
    const path = props.currentPath;
    const other = props.comparisonPath;
    if (!path || !other) {
        return null;
    }
    // Positive: the uncorrected ride is slower. NaN-safe: a path without a clock says nothing.
    const minutes = (pathDurationMs(other) - pathDurationMs(path)) / 60000;
    return {
        gain: pathReportedElevationGain(other),
        loss: pathReportedElevationLoss(other),
        time: Number.isFinite(minutes)
            ? `${minutes >= 0 ? '+' : '−'}${Math.abs(minutes).toFixed(1)} min`
            : '',
    };
});

/** The bench's own words for the loaded validation track: role, category, place. */
const validationInfo = computed(() => {
    const sample = props.validationSample;
    if (!sample) {
        return null;
    }
    const meta = props.reference?.meta ?? {};
    const role = String(meta.role ?? (meta.route ? 'strava-route' : ''));
    const roleLabel: Record<string, string> = {
        'strava-route': 'Strava route — dashed line: the bench reference (IGN LiDAR HD)',
        'held-out-positive': 'Positive control — the deck must be corrected',
        holdout2: 'Positive control — the deck must be corrected',
        negative: 'Negative control — nothing on this route may move',
        'tunnel-tuning': 'Tunnel — the profile must go straight between the portals',
        'tunnel-negative': 'Negative control — a steep real road, not a tunnel',
    };
    // What the orange line is, or why there is none. Nothing while the pipeline has not run or
    // the second run is still in flight — the chart's spinner covers that.
    const report = props.currentPath ? pathElevationCorrections(props.currentPath) : null;
    const corrected = report ? report.bridgeDecksApplied + report.tunnelsApplied : 0;
    let comparison = '';
    if (props.comparisonPath) {
        comparison = 'Orange line: the same ride with both corrections off.';
    } else if (report && corrected === 0) {
        comparison = 'Nothing was corrected on this run, so there is no second profile.';
    }
    return {
        label: sample.label,
        description: sample.description ?? '',
        role: roleLabel[role] ?? role,
        comparison,
        place: String(meta.place ?? ''),
        osm: meta.osm_base ? `Route © OpenStreetMap contributors (ODbL), ${meta.osm_base}` : '',
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
                    :items="gpxSampleItems"
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
        <!-- Kept compact: every line here comes out of the chart's height. -->
        <div
            v-if="validationInfo"
            class="mt-3 rounded-lg border border-violet-200 bg-violet-50 px-3 py-2 text-sm text-violet-950"
        >
            <div>
                <span class="font-semibold">🌉 {{ validationInfo.label }}</span>
                <span v-if="validationInfo.description"> — {{ validationInfo.description }}</span>
            </div>
            <div class="text-violet-800">
                {{ validationInfo.role }}<template v-if="validationInfo.role">. </template>Bands
                mark the bench's structures.
                <template v-if="validationInfo.comparison">{{
                    validationInfo.comparison
                }}</template>
            </div>
            <div v-if="validationInfo.place || validationInfo.osm" class="text-xs text-violet-700">
                {{ validationInfo.place
                }}<template v-if="validationInfo.place && validationInfo.osm"> · </template
                >{{ validationInfo.osm }}
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
                        <div v-if="withoutCorrections" class="mt-1 text-sm text-orange-800">
                            Without them: +{{ withoutCorrections.gain.toFixed(0) }}m / -{{
                                (-withoutCorrections.loss).toFixed(0)
                            }}m<template v-if="withoutCorrections.time"
                                >, ride time {{ withoutCorrections.time }}</template
                            >
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
