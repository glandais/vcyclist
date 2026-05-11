<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useGPXDemo } from '~/composables/useGPXDemo';
import { loadConfig, useConfigPersistence } from '~/composables/useConfigPersistence';
import {
    pathDurationMs,
    pathElevationGain,
    pathElevationLoss,
    pathSize,
    pathTotalDistance,
} from '~/engine-shim';

const config = ref(loadConfig());
useConfigPersistence(config);

const {
    currentPath,
    isProcessing,
    statusText,
    fileName,
    loadGPXFile,
    handleFileUpload,
    enhancePath,
} = useGPXDemo(config);

const stats = computed(() => {
    const p = currentPath.value;
    if (!p) {
        return null;
    }
    return {
        points: pathSize(p),
        distanceKm: (pathTotalDistance(p) / 1000).toFixed(2),
        durationMin: (pathDurationMs(p) / 60_000).toFixed(1),
        elevationGain: pathElevationGain(p).toFixed(0),
        elevationLoss: pathElevationLoss(p).toFixed(0),
    };
});

const onFile = async (e: Event) => {
    const target = e.target as HTMLInputElement;
    const f = target.files?.[0];
    if (f) {
        await handleFileUpload(f);
    }
};

onMounted(() => {
    console.log('Demo ready');
});
</script>

<template>
    <div id="app" class="h-screen flex flex-col bg-white/95">
        <header
            class="bg-gradient-to-r from-slate-700 to-blue-500 text-white p-6 text-center shadow-md"
        >
            <h1 class="text-4xl mb-2 font-light">vcyclist — Kotlin/JS Demo</h1>
            <p class="text-lg opacity-90">
                Vue + Vite demo consuming the Kotlin/JS engine bundle (Phase 9, task 36).
            </p>
        </header>
        <main class="flex-1 p-4 space-y-4 overflow-auto">
            <div class="space-x-2 flex flex-wrap items-center gap-2">
                <input type="file" accept=".gpx" :disabled="isProcessing" @change="onFile" />
                <button
                    class="px-4 py-2 bg-blue-500 text-white rounded disabled:opacity-50"
                    :disabled="isProcessing"
                    @click="loadGPXFile('./gpx/stelvio.gpx')"
                >
                    Load stelvio.gpx (sample)
                </button>
                <button
                    class="px-4 py-2 bg-green-500 text-white rounded disabled:opacity-50"
                    :disabled="isProcessing || !currentPath"
                    @click="enhancePath"
                >
                    Enhance
                </button>
            </div>
            <p v-if="isProcessing" class="text-orange-600">{{ statusText }}</p>
            <p v-if="fileName" class="text-sm text-gray-600">File: {{ fileName }}</p>
            <pre v-if="stats" class="bg-gray-100 p-4 rounded">{{
                JSON.stringify(stats, null, 2)
            }}</pre>
        </main>
    </div>
</template>
