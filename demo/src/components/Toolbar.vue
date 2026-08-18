<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{
    hasData: boolean;
    isProcessing: boolean;
    statusText: string;
    filesSectionVisible: boolean;
    configVisible: boolean;
    fieldsSidebarVisible: boolean;
    /** How many `<trk>`/`<rte>` the loaded file carried. The selector appears only above one. */
    trackCount: number;
    selectedTrackIndex: number;
}>();

const emit = defineEmits<{
    toggleFilesSection: [];
    toggleConfig: [];
    toggleFieldsSidebar: [];
    enhancePath: [];
    resetZoom: [];
    downloadGpx: [];
    downloadFit: [];
    downloadCsv: [];
    downloadJson: [];
    selectTrack: [index: number];
}>();

// Nuxt UI's dropdown wants its items as data, not markup.
const downloadItems = [
    [
        { label: 'GPX', onSelect: () => emit('downloadGpx') },
        { label: 'FIT course', onSelect: () => emit('downloadFit') },
    ],
    [
        { label: 'CSV', onSelect: () => emit('downloadCsv') },
        { label: 'JSON', onSelect: () => emit('downloadJson') },
    ],
];

/**
 * One entry per track of the loaded file. Before this existed the demo called `parseGpx`, which
 * keeps only the first track, so every other one was discarded at load with nothing said.
 */
const trackItems = computed(() =>
    Array.from({ length: props.trackCount }, (_, i) => ({
        label: `Track ${i + 1}`,
        onSelect: () => emit('selectTrack', i),
    }))
);
const trackButtonLabel = computed(
    () => `🛤️ Track ${props.selectedTrackIndex + 1}/${props.trackCount}`
);

const fileButtonLabel = computed(() =>
    props.filesSectionVisible ? '📁 Hide Files' : '📁 Load File'
);
const configButtonLabel = computed(() => (props.configVisible ? '⚙️ Hide Config' : '⚙️ Config'));
const fieldsButtonLabel = computed(() =>
    props.fieldsSidebarVisible ? '📊 Hide Fields' : '📊 Fields'
);
</script>

<template>
    <div class="flex items-center gap-2 px-4 py-2 bg-white border-b border-gray-200">
        <!-- File Section Toggle -->
        <UButton @click="emit('toggleFilesSection')" color="neutral" variant="outline" size="sm">
            {{ fileButtonLabel }}
        </UButton>

        <!-- Config Toggle -->
        <UButton @click="emit('toggleConfig')" color="neutral" variant="outline" size="sm">
            {{ configButtonLabel }}
        </UButton>

        <!-- Fields Sidebar Toggle -->
        <UButton @click="emit('toggleFieldsSidebar')" color="neutral" variant="outline" size="sm">
            {{ fieldsButtonLabel }}
        </UButton>

        <div class="border-l border-gray-300 h-6 mx-1"></div>

        <!-- Enhance Path -->
        <UButton
            @click="emit('enhancePath')"
            :disabled="!hasData || isProcessing"
            color="primary"
            size="sm"
        >
            🚀 Enhance
        </UButton>

        <!-- Reset Zoom -->
        <UButton
            @click="emit('resetZoom')"
            :disabled="!hasData"
            color="neutral"
            variant="outline"
            size="sm"
        >
            🔍 Reset Zoom
        </UButton>

        <!-- Export the virtualized path. Enabled as soon as there is a path: exporting what you
             loaded is legitimate, and the view warns when it has not been enhanced. -->
        <UDropdownMenu :items="downloadItems">
            <UButton
                :disabled="!hasData || isProcessing"
                color="neutral"
                variant="outline"
                size="sm"
            >
                ⬇️ Download
            </UButton>
        </UDropdownMenu>

        <!-- Track picker, only when the file actually carried more than one. -->
        <UDropdownMenu v-if="trackCount > 1" :items="[trackItems]">
            <UButton :disabled="isProcessing" color="neutral" variant="outline" size="sm">
                {{ trackButtonLabel }}
            </UButton>
        </UDropdownMenu>

        <!-- Processing Status -->
        <div v-if="isProcessing" class="flex items-center gap-2 ml-auto">
            <!-- Nuxt UI has no circular spinner component; a plain CSS one keeps the
                 demo free of an extra icon dependency for a single use. -->
            <span
                class="size-5 rounded-full border-2 border-gray-300 border-t-primary animate-spin"
                role="status"
                aria-label="Processing"
            ></span>
            <span class="text-xs text-gray-600">{{ statusText }}</span>
        </div>
    </div>
</template>
