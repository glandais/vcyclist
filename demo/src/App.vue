<script setup lang="ts">
import { onMounted } from 'vue';
import { fieldDefinitions } from '~/engine-shim';

onMounted(() => {
    const defs = fieldDefinitions();
    console.log(`vcyclist-engine loaded: ${defs.length} fields available`);
});
</script>

<template>
    <div
        id="app"
        class="h-screen flex flex-col bg-white/95 mx-auto w-full shadow-2xl overflow-hidden"
    >
        <header
            class="bg-gradient-to-r from-slate-700 to-blue-500 text-white p-6 text-center shadow-md flex-shrink-0"
        >
            <h1 class="text-4xl mb-2 font-light">vcyclist — Kotlin/JS Demo</h1>
            <p class="text-lg opacity-90">
                Vue + Vite demo consuming the Kotlin/JS engine bundle (Phase 9).
            </p>
        </header>
        <main class="flex-1 p-4">
            <p>Shell ready. Engine integration coming in task 36.</p>
        </main>
    </div>
</template>
