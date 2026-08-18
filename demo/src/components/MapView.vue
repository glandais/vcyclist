<script setup lang="ts">
import { onMounted, ref, toRef } from 'vue';
import type { HoverInfo } from '~/composables/useHoverSync';
import { useMap } from '~/composables/useMap';
import type { ClimbDto, Path, RacingLineReportDto } from '@glandais/vcyclist-engine';

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

const { createMap, fitBounds, focusOnClimb, invalidateSize, racingLineDrift } = useMap(
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
    invalidateSize,
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

            <!-- The solver hit its iteration cap. The line drawn is still corridor-feasible — that
                 is enforced at every step — but it is not the optimum, and saying nothing would
                 present a half-solved trajectory as a finished one. `converged` was in the report
                 from the start and nothing read it until S11. -->
            <div
                v-if="racingLineReport && !racingLineReport.converged"
                class="mt-2 flex items-start gap-2 border-t border-amber-300 pt-2 text-amber-700"
            >
                <span aria-hidden="true">⚠️</span>
                <span>
                    Solver did not converge ({{ racingLineReport.newtonIterations }} iterations).
                    The line stays inside the corridor but is not optimal.
                </span>
            </div>
        </div>
    </section>
</template>
