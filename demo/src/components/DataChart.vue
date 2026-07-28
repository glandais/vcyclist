<script setup lang="ts">
import { onMounted, ref, toRef } from 'vue';
import { useChart } from '~/composables/useChart';
import type { HoverInfo } from '~/composables/useHoverSync';
import type { ClimbDto, Path } from '~/engine-shim';

const props = defineProps<{
    currentPath: Path | null;
    selectedFields: Set<string>;
    isProcessing: boolean;
    hoveredInfo: HoverInfo | null;
    climbs: ClimbDto[];
}>();

const emit = defineEmits<{
    hoverChange: [index: number | null];
}>();

const canvasRef = ref<HTMLCanvasElement | null>(null);

const { createChart, resetZoom, resize, zoomToDistanceRange } = useChart(
    canvasRef,
    toRef(props, 'currentPath'),
    toRef(props, 'selectedFields'),
    toRef(props, 'hoveredInfo'),
    (index: number | null) => emit('hoverChange', index),
    toRef(props, 'climbs')
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
    <section class="flex-1 bg-white">
        <div class="w-full h-full p-4">
            <canvas ref="canvasRef" id="data-chart" class="w-full h-full"></canvas>
        </div>
    </section>
</template>
