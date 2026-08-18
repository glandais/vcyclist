<script setup lang="ts">
import { useToast } from '@nuxt/ui/composables';
import { computed, nextTick, onActivated, onMounted, ref, watch } from 'vue';
import ClimbsPanel from '~/components/ClimbsPanel.vue';
import ConfigModal from '~/components/ConfigModal.vue';
import DataChart from '~/components/DataChart.vue';
import FieldsSidebar from '~/components/FieldsSidebar.vue';
import FileSection from '~/components/FileSection.vue';
import MapView from '~/components/MapView.vue';
import Toolbar from '~/components/Toolbar.vue';
import { loadConfig, useConfigPersistence } from '~/composables/useConfigPersistence';
import { useClimbs } from '~/composables/useClimbs';
import { useGPXDemo } from '~/composables/useGPXDemo';
import { useHoverSync } from '~/composables/useHoverSync';
import { getField, pathToCsv, pathToJson, pathToFit, writeGpxTracks } from '~/engine-shim';
import {
    CSV_MIME,
    downloadBytes,
    downloadText,
    FIT_MIME,
    GPX_MIME,
    JSON_MIME,
} from '~/utils/download';

const toast = useToast();

// Load config from localStorage or use defaults
const config = ref(loadConfig());

// Set up auto-save with debouncing
useConfigPersistence(config);

const {
    currentPath,
    originalPath,
    tracks,
    selectedTrackIndex,
    waypoints,
    selectTrack,
    racingLineReport,
    isProcessing,
    statusText,
    fileName,
    loadGPXFile,
    handleFileUpload,
    enhancePath,
} = useGPXDemo(config);

// --- Export -----------------------------------------------------------------------------------

/**
 * The absolute instant of the first point.
 *
 * It cannot come from `currentPath`: `VirtualizeService` pins `time(0) = 0`, so the enhanced
 * path's clock is relative. `originalPath` still holds what the file said — `GpxToPath` maps a
 * missing `<time>` to `0.0` (not NaN), so a zero means the source carried no timestamps at all.
 * Reusing the file's own start rather than asking the user to retype it is the same decision
 * `GpxDocument.startTime` records on the Kotlin side (task g05).
 */
const resolveStartTimeMs = (): number => {
    const source = originalPath.value;
    if (source !== null) {
        const t0 = getField(source, 0, 'time');
        if (Number.isFinite(t0) && t0 > 0) {
            return t0;
        }
    }
    return Date.now();
};

// `fileName` always carries its .gpx extension; a course called "stelvio.gpx" on a head unit, or
// a file named "stelvio.gpx.fit", both read as a bug. It starts as '', hence the fallback.
const exportBaseName = computed(() => fileName.value.replace(/\.gpx$/i, '') || 'route');

/**
 * Exporting a path that was never enhanced is allowed — exporting what you loaded is legitimate —
 * but it degrades in two ways that are invisible in the file: `pathToFit` treats an exact 0.0
 * power as absent, so there is no power channel, and a timestamp-less source leaves every point
 * on the same instant, which is monotonic enough to encode without error and yields a
 * zero-duration course. Warn rather than fail, and rather than disable the button.
 */
const warnIfNotEnhanced = () => {
    if (currentPath.value !== null && currentPath.value === originalPath.value) {
        toast.add({
            color: 'warning',
            title: 'Exported without enhancing',
            description:
                'No power data, and no clock if the source GPX had no timestamps. Run 🚀 Enhance first.',
            duration: 6000,
        });
    }
};

// Both encoders are synchronous, unlike every other handler in this file.
const onDownloadGpx = () => {
    if (currentPath.value === null) {
        return;
    }
    try {
        // 'computed-or-input': the point of this app is the simulated power, so exporting the
        // engine default ('input' — only what the file already said) would hand back a GPX with
        // the physics stripped out. Falls back to the recorded power wherever the simulation
        // produced none.
        // `writeGpxTracks` rather than `writeGpxAt`: it is the only writer that takes waypoints,
        // and the source document's `<wpt>` entries would otherwise be destroyed by a
        // load → enhance → download round trip. It gained `trackNames` and `startTimeEpochMs` in
        // S1 of the surface-alignment work, which is what makes it a drop-in here.
        const xml = writeGpxTracks(
            [currentPath.value],
            waypoints.value,
            true,
            'computed-or-input',
            [exportBaseName.value],
            resolveStartTimeMs()
        );
        downloadText(xml, `${exportBaseName.value}-virtualized.gpx`, GPX_MIME);
        toast.add({
            color: 'success',
            title: 'GPX Exported',
            description: `${exportBaseName.value}-virtualized.gpx`,
            duration: 3000,
        });
        warnIfNotEnhanced();
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'GPX Export Failed',
            description: 'Failed to write GPX: ' + (error as Error).message,
            duration: 5000,
        });
    }
};

const onDownloadFit = () => {
    if (currentPath.value === null) {
        return;
    }
    try {
        const bytes = pathToFit(currentPath.value, exportBaseName.value, resolveStartTimeMs());
        downloadBytes(bytes, `${exportBaseName.value}.fit`, FIT_MIME);
        toast.add({
            color: 'success',
            title: 'FIT Course Exported',
            description: `${exportBaseName.value}.fit`,
            duration: 3000,
        });
        warnIfNotEnhanced();
    } catch (error) {
        // The encoder's `require` failures carry engine-internal wording; say something the user
        // can act on instead.
        const message = (error as Error).message;
        let description = 'Failed to write FIT: ' + message;
        if (message.includes('monotonic')) {
            description =
                "The path's timestamps go backwards. Run 🚀 Enhance first — the virtualized path is always monotonic.";
        } else if (message.includes('empty')) {
            description = 'There is nothing to export.';
        }
        toast.add({ color: 'error', title: 'FIT Export Failed', description, duration: 5000 });
    }
};

/**
 * Tabular exports. Both writers are synchronous and emit every one of the path's fields — column
 * selection exists on `CsvOptions` but reaches no door yet, so there is nothing to choose here.
 */
const onDownloadCsv = () => {
    if (currentPath.value === null) {
        return;
    }
    try {
        downloadText(
            pathToCsv(currentPath.value, ',', true),
            `${exportBaseName.value}.csv`,
            CSV_MIME
        );
        warnIfNotEnhanced();
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'CSV Export Failed',
            description: (error as Error).message,
            duration: 5000,
        });
    }
};

const onDownloadJson = () => {
    if (currentPath.value === null) {
        return;
    }
    try {
        downloadText(
            pathToJson(currentPath.value, true),
            `${exportBaseName.value}.json`,
            JSON_MIME
        );
        warnIfNotEnhanced();
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'JSON Export Failed',
            description: (error as Error).message,
            duration: 5000,
        });
    }
};

// Climb detection : recomputed once per enhanced path, never per render.
const {
    climbs,
    tuning: climbTuning,
    isTuned: climbsTuned,
    resetTuning: resetClimbTuning,
} = useClimbs(currentPath);
const selectedClimbIndex = ref<number | null>(null);

// Clicking a climb row recentres the map on it and zooms the chart to its distance range.
const onClimbSelect = (index: number) => {
    const climb = climbs.value[index];
    if (!climb) {
        return;
    }
    selectedClimbIndex.value = index;
    mapViewRef.value?.focusOnClimb(climb);
    dataChartRef.value?.zoomToDistanceRange(climb.startDistanceM, climb.endDistanceM);
};

// Set up hover sync between chart and map
const { hoveredInfo, setHoveredIndex } = useHoverSync(currentPath);

const handleHoverChange = (index: number | null) => {
    setHoveredIndex(index);
};

// Refs to components for reset zoom functionality
const dataChartRef = ref<InstanceType<typeof DataChart> | null>(null);
const mapViewRef = ref<InstanceType<typeof MapView> | null>(null);

const hasData = computed(() => currentPath.value !== null);

// UI visibility toggles with localStorage persistence
const UI_STATE_KEY = 'vcyclist-demo-ui-state';

interface UIState {
    filesSectionVisible: boolean;
    configVisible: boolean;
    fieldsSidebarVisible: boolean;
}

const loadUIState = (): UIState => {
    try {
        const saved = window.localStorage.getItem(UI_STATE_KEY);
        if (saved) {
            return JSON.parse(saved) as UIState;
        }
    } catch {
        // Ignore errors
    }
    return {
        filesSectionVisible: false,
        configVisible: false,
        fieldsSidebarVisible: true,
    };
};

const saveUIState = () => {
    try {
        window.localStorage.setItem(
            UI_STATE_KEY,
            JSON.stringify({
                filesSectionVisible: filesSectionVisible.value,
                configVisible: configVisible.value,
                fieldsSidebarVisible: fieldsSidebarVisible.value,
            })
        );
    } catch {
        // Ignore errors
    }
};

const initialUIState = loadUIState();
const filesSectionVisible = ref(initialUIState.filesSectionVisible);
const configVisible = ref(initialUIState.configVisible);
const fieldsSidebarVisible = ref(initialUIState.fieldsSidebarVisible);

// Watch UI state changes and persist them
watch([filesSectionVisible, configVisible, fieldsSidebarVisible], saveUIState);

// Watch for sidebar toggle and resize chart after DOM update
watch(fieldsSidebarVisible, async () => {
    await nextTick();
    dataChartRef.value?.resize();
});

const handleResetZoom = () => {
    selectedClimbIndex.value = null;
    dataChartRef.value?.resetZoom();
    mapViewRef.value?.fitBounds();
};

const onGPXSelect = async (url: string) => {
    try {
        await loadGPXFile(url);
        toast.add({
            color: 'success',
            title: 'GPX Loaded',
            description: 'GPX file loaded successfully',
            duration: 3000,
        });
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
        await handleFileUpload(file);
        toast.add({
            color: 'success',
            title: 'File Uploaded',
            description: 'File uploaded successfully',
            duration: 3000,
        });
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'Upload Failed',
            description: 'Failed to upload file: ' + (error as Error).message,
            duration: 5000,
        });
    }
};

const onEnhancePath = async () => {
    try {
        await enhancePath();
        toast.add({
            color: 'success',
            title: 'Path Enhanced',
            description: 'Path enhanced successfully',
            duration: 3000,
        });
    } catch (error) {
        toast.add({
            color: 'error',
            title: 'Enhancement Failed',
            description: 'Failed to enhance path: ' + (error as Error).message,
            duration: 5000,
        });
    }
};

onMounted(() => {
    console.log('vcyclist demo initialized');
    loadGPXFile('./gpx/stelvio.gpx').then(() => enhancePath());
});

// The view is kept alive across tab switches, so its DOM-measured widgets (Chart.js canvas,
// Leaflet map) must re-measure on the way back in — both render as blank/grey otherwise.
const resizeAll = () => {
    dataChartRef.value?.resize();
    mapViewRef.value?.invalidateSize();
};

onActivated(async () => {
    await nextTick();
    resizeAll();
});

defineExpose({ resizeAll });
</script>

<template>
    <div class="flex-1 min-h-0 flex flex-col">
        <!-- Toolbar -->
        <Toolbar
            :has-data="hasData"
            :is-processing="isProcessing"
            :status-text="statusText"
            :files-section-visible="filesSectionVisible"
            :config-visible="configVisible"
            :fields-sidebar-visible="fieldsSidebarVisible"
            :track-count="tracks.length"
            :selected-track-index="selectedTrackIndex"
            @toggle-files-section="filesSectionVisible = !filesSectionVisible"
            @toggle-config="configVisible = !configVisible"
            @toggle-fields-sidebar="fieldsSidebarVisible = !fieldsSidebarVisible"
            @enhance-path="onEnhancePath"
            @reset-zoom="handleResetZoom"
            @download-gpx="onDownloadGpx"
            @download-fit="onDownloadFit"
            @download-csv="onDownloadCsv"
            @download-json="onDownloadJson"
            @select-track="selectTrack"
        />
        <FieldsSidebar v-model="config.selectedFields" v-model:visible="fieldsSidebarVisible" />

        <!-- Scrollable Content Area -->
        <div class="flex-1 min-h-0 overflow-y-auto flex flex-col">
            <!-- File Selection Section (Toggleable) -->
            <FileSection
                v-if="filesSectionVisible"
                :file-name="fileName"
                :current-path="currentPath"
                :is-processing="isProcessing"
                @gpx-select="onGPXSelect"
                @file-upload="onFileUpload"
            />

            <!-- Configuration Panel (Toggleable) -->
            <ConfigModal v-if="configVisible" v-model="config" />

            <!-- Chart and Map Section with Sidebar -->
            <div class="grid grid-cols-1 xl:grid-cols-2 gap-4 p-4 flex-1 min-h-0">
                <!-- Chart with Fields Sidebar -->
                <div class="flex h-full border border-gray-200 rounded-lg overflow-hidden bg-white">
                    <DataChart
                        ref="dataChartRef"
                        :current-path="currentPath"
                        :selected-fields="config.selectedFields"
                        :is-processing="isProcessing"
                        :hovered-info="hoveredInfo"
                        :climbs="climbs"
                        @hover-change="handleHoverChange"
                        class="flex-1"
                    />
                </div>

                <!-- Map -->
                <MapView
                    ref="mapViewRef"
                    :current-path="currentPath"
                    :hovered-info="hoveredInfo"
                    :climbs="climbs"
                    :racing-line-report="racingLineReport"
                    :original-path="originalPath"
                    @hover-change="handleHoverChange"
                />
            </div>

            <!-- Climbs -->
            <div v-if="hasData" class="px-4 pb-4">
                <ClimbsPanel
                    :climbs="climbs"
                    :selected-index="selectedClimbIndex"
                    :tuning="climbTuning"
                    :is-tuned="climbsTuned"
                    @select="onClimbSelect"
                    @reset-tuning="resetClimbTuning"
                    @tune="(key, value) => (climbTuning[key] = value)"
                />
            </div>
        </div>
    </div>
</template>
