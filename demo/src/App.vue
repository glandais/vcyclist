<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';

const route = useRoute();

const tabs = [
    {
        to: '/',
        label: '🚴‍♂️ GPX analysis',
        subtitle:
            'Upload GPX routes and simulate realistic cycling speeds based on terrain and rider physics',
    },
    {
        to: '/elevation',
        label: '⛰️ Elevation explorer',
        subtitle:
            'Query DEM tiles by point or along a path, with smoothing, simplification and relief shading',
    },
];

const subtitle = computed(
    () => tabs.find(tab => tab.to === route.path)?.subtitle ?? tabs[0].subtitle
);
</script>

<template>
    <UApp>
        <div
            id="app"
            class="h-screen flex flex-col bg-white/95 mx-auto w-full shadow-2xl overflow-hidden"
        >
            <!-- Header Section -->
            <header
                class="bg-gradient-to-r from-slate-700 to-blue-500 text-white p-6 text-center shadow-md flex-shrink-0"
            >
                <h1 class="text-4xl mb-2 font-light">vcyclist — Kotlin/JS demos</h1>
                <p class="text-lg opacity-90">{{ subtitle }}</p>
            </header>

            <!-- View tabs. Plain links rather than UTabs: UTabs owns its own content slot and
                 would fight the RouterView below. -->
            <nav class="flex gap-2 px-4 py-2 border-b border-gray-200 bg-gray-50 flex-shrink-0">
                <ULink
                    v-for="tab in tabs"
                    :key="tab.to"
                    :to="tab.to"
                    class="px-4 py-2 rounded-md font-medium transition-colors"
                    :class="
                        route.path === tab.to
                            ? 'bg-blue-600 text-white'
                            : 'text-gray-600 hover:bg-gray-200'
                    "
                >
                    {{ tab.label }}
                </ULink>
            </nav>

            <!--
                KeepAlive is load-bearing: the GPX view parses and enhances stelvio.gpx on mount,
                and the elevation view holds a clicked path — a plain RouterView would throw both
                away on every tab switch. The cost is that each view must re-measure its Leaflet
                map and Chart.js canvas in `onActivated`.
            -->
            <RouterView v-slot="{ Component }">
                <KeepAlive>
                    <component :is="Component" />
                </KeepAlive>
            </RouterView>
        </div>
    </UApp>
</template>
