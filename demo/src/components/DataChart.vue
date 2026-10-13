<script setup lang="ts">
import type { PointFieldProp } from '@glandais/vcyclist-engine';
import { computed, onMounted, ref, toRef } from 'vue';
import { type IndexRange, useChart } from '~/composables/useChart';
import type { HoverInfo } from '~/composables/useHoverSync';
import {
    BAND_STYLES,
    type BandKind,
    type ElevationOverlays,
} from '~/composables/useValidationReference';
import type { ClimbDto, Path } from '@glandais/vcyclist-engine';

const props = defineProps<{
    currentPath: Path | null;
    selectedFields: Set<PointFieldProp>;
    isProcessing: boolean;
    hoveredInfo: HoverInfo | null;
    climbs: ClimbDto[];
    overlays: ElevationOverlays;
    /** The "without corrections" run is still in flight. */
    comparisonPending: boolean;
}>();

const emit = defineEmits<{
    hoverChange: [index: number | null];
    /** The zoomed x window as point indices, `null` when the whole ride is shown. */
    rangeChange: [range: IndexRange | null];
    /** A double-click asked for the whole ride back. */
    resetZoom: [];
}>();

const canvasRef = ref<HTMLCanvasElement | null>(null);
const overviewRef = ref<HTMLCanvasElement | null>(null);

const { createChart, resetZoom, resize, zoomToDistanceRange } = useChart(
    canvasRef,
    toRef(props, 'currentPath'),
    toRef(props, 'selectedFields'),
    toRef(props, 'hoveredInfo'),
    (index: number | null) => emit('hoverChange', index),
    toRef(props, 'climbs'),
    toRef(props, 'overlays'),
    overviewRef,
    (range: IndexRange | null) => emit('rangeChange', range),
    () => emit('resetZoom')
);

/** One legend entry per band kind present, in a fixed order. */
const bandLegend = computed(() => {
    const present = new Set<BandKind>(props.overlays.bands.map(b => b.kind));
    return (Object.keys(BAND_STYLES) as BandKind[])
        .filter(k => present.has(k))
        .map(k => ({ kind: k, ...BAND_STYLES[k] }));
});

const showOverlayHint = computed(
    () =>
        (props.overlays.reference !== null || props.overlays.withoutCorrections !== null) &&
        !props.selectedFields.has('elevation')
);

defineExpose({
    resetZoom,
    resize,
    zoomToDistanceRange,
});

onMounted(() => {
    createChart();
});
</script>

<template>
    <section class="flex-1 min-w-0 bg-white flex flex-col">
        <div
            v-if="bandLegend.length > 0 || comparisonPending || showOverlayHint"
            class="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 pt-3 text-xs text-gray-600"
        >
            <span
                v-for="band in bandLegend"
                :key="band.kind"
                class="flex items-center gap-1.5"
                :title="band.hint"
            >
                <span
                    class="inline-block h-2 w-4 rounded-sm"
                    :style="{ backgroundColor: band.color }"
                ></span>
                {{ band.label }}
            </span>
            <span v-if="comparisonPending" class="flex items-center gap-1.5 text-orange-700">
                <span
                    class="size-3 rounded-full border-2 border-orange-200 border-t-orange-600 animate-spin"
                    aria-hidden="true"
                ></span>
                Computing the profile without corrections…
            </span>
            <span v-if="showOverlayHint" class="text-amber-700">
                Select the Elevation field to see the reference and “without corrections” profiles.
            </span>
        </div>
        <div class="flex-1 min-h-0 w-full px-4 pt-2">
            <canvas ref="canvasRef" id="data-chart" class="w-full h-full"></canvas>
        </div>
        <div v-show="currentPath !== null" class="px-4 pb-2">
            <canvas
                ref="overviewRef"
                class="block w-full h-10 cursor-pointer touch-none rounded border border-gray-200"
                aria-label="Ride overview: drag to move or draw the zoom window, double-click to reset"
            ></canvas>
            <p class="mt-1 text-[11px] text-gray-500">
                Drag to zoom · wheel or pinch to zoom · Shift+drag to pan · double-click to reset ·
                drag on this strip to move or redraw the window
            </p>
        </div>
    </section>
</template>
