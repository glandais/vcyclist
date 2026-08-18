<script setup lang="ts">
import { onMounted, ref, toRef } from 'vue';
import { useElevationChart } from '~/composables/useElevationChart';
import type { CoordinatesElevationDto } from '@glandais/vcyclist-elevation';

const props = defineProps<{ profile: CoordinatesElevationDto[] }>();

const canvasRef = ref<HTMLCanvasElement | null>(null);

const { render, resize } = useElevationChart(canvasRef, toRef(props, 'profile'));

defineExpose({ resize });

onMounted(() => {
    render();
});
</script>

<template>
    <div class="relative flex-1 min-h-[300px] p-2">
        <canvas ref="canvasRef"></canvas>
        <div
            v-if="profile.length === 0"
            class="absolute inset-0 flex items-center justify-center text-gray-400"
        >
            Click two or more points on the map, or load a GPX file.
        </div>
    </div>
</template>
