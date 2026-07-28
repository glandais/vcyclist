<script setup lang="ts">
import { onMounted, ref, toRef } from 'vue';
import type { HoverInfo } from '~/composables/useHoverSync';
import { useMap } from '~/composables/useMap';
import type { ClimbDto, Path } from '~/engine-shim';

const props = defineProps<{
    currentPath: Path | null;
    hoveredInfo: HoverInfo | null;
    climbs: ClimbDto[];
}>();

const emit = defineEmits<{
    hoverChange: [index: number | null];
}>();

const mapContainerRef = ref<HTMLElement | null>(null);

const { createMap, fitBounds, focusOnClimb } = useMap(
    mapContainerRef,
    toRef(props, 'currentPath'),
    toRef(props, 'hoveredInfo'),
    (index: number | null) => emit('hoverChange', index),
    toRef(props, 'climbs')
);

defineExpose({
    fitBounds,
    focusOnClimb,
});

onMounted(() => {
    createMap();
});
</script>

<template>
    <section class="flex-1 bg-white">
        <div ref="mapContainerRef" class="w-full h-full leaflet-map"></div>
    </section>
</template>
