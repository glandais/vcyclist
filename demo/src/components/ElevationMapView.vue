<script setup lang="ts">
import { onMounted, ref, toRef } from 'vue';
import type { ExplorerMode } from '~/composables/useElevationExplorer';
import { useElevationMap, type ReliefMode } from '~/composables/useElevationMap';
import type { CoordinatesDto } from '~/elevation-shim';

const props = defineProps<{
    mode: ExplorerMode;
    points: CoordinatesDto[];
}>();

const emit = defineEmits<{
    pointClick: [latitude: number, longitude: number];
}>();

const mapContainerRef = ref<HTMLElement | null>(null);

const { createMap, fitBounds, applyRelief, invalidateSize } = useElevationMap(
    mapContainerRef,
    toRef(props, 'mode'),
    toRef(props, 'points'),
    (latitude: number, longitude: number) => emit('pointClick', latitude, longitude)
);

const setRelief = (relief: ReliefMode) => applyRelief(relief);

defineExpose({ fitBounds, setRelief, invalidateSize });

onMounted(() => {
    createMap();
});
</script>

<template>
    <section class="flex-1 bg-white relative min-h-[300px]">
        <div ref="mapContainerRef" class="w-full h-full leaflet-map"></div>
    </section>
</template>
