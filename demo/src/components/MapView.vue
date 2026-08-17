<script setup lang="ts">
import { onMounted, ref, toRef } from 'vue';
import type { HoverInfo } from '~/composables/useHoverSync';
import { useMap } from '~/composables/useMap';
import type { ClimbDto, Path, RacingLineReportDto } from '~/engine-shim';

const props = defineProps<{
    currentPath: Path | null;
    hoveredInfo: HoverInfo | null;
    climbs: ClimbDto[];
    racingLineReport: RacingLineReportDto | null;
    originalPath: Path | null;
}>();

const emit = defineEmits<{
    hoverChange: [index: number | null];
}>();

const mapContainerRef = ref<HTMLElement | null>(null);

const { createMap, fitBounds, focusOnClimb, racingLineDrift } = useMap(
    mapContainerRef,
    toRef(props, 'currentPath'),
    toRef(props, 'hoveredInfo'),
    (index: number | null) => emit('hoverChange', index),
    toRef(props, 'climbs'),
    toRef(props, 'racingLineReport'),
    toRef(props, 'originalPath')
);

defineExpose({
    fitBounds,
    focusOnClimb,
    racingLineDrift,
});

onMounted(() => {
    createMap();
});
</script>

<template>
    <section class="flex-1 bg-white relative">
        <div ref="mapContainerRef" class="w-full h-full leaflet-map"></div>

        <!-- Only shown when the stage ran, because only then is the blue trace not the file. -->
        <div
            v-if="racingLineReport"
            class="absolute bottom-4 left-4 z-[1000] rounded-md bg-white/90 px-3 py-2 text-xs shadow"
        >
            <div class="flex items-center gap-2">
                <span class="inline-block h-0 w-6 border-t-2 border-dashed border-slate-500"></span>
                <span>Road as recorded</span>
            </div>
            <div class="mt-1 flex items-center gap-2">
                <span class="inline-block h-3 w-6 border border-violet-600 bg-violet-600/20"></span>
                <span>Corridor allowed</span>
            </div>
            <div class="mt-1 flex items-center gap-2">
                <span class="inline-block h-0 w-6 border-t-[3px] border-red-600"></span>
                <span>Racing line ridden</span>
            </div>
        </div>
    </section>
</template>
