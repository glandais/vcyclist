<script setup lang="ts">
import { ref } from 'vue';
import SliderInput from '~/components/SliderInput.vue';
import { gpxSamples } from '~/config/gpxSamples';
import type { ExplorerMode } from '~/composables/useElevationExplorer';
import type { ReliefMode } from '~/composables/useElevationMap';

defineProps<{
    mode: ExplorerMode;
    relief: ReliefMode;
    hasPath: boolean;
}>();

const smoothing = defineModel<{ enabled: boolean; windowSize: number }>('smoothing', {
    required: true,
});
const filter = defineModel<{ enabled: boolean; tolerance: number; zExaggeration: number }>(
    'filter',
    { required: true }
);

const emit = defineEmits<{
    modeChange: [mode: ExplorerMode];
    reliefChange: [relief: ReliefMode];
    clearPath: [];
    sampleSelect: [url: string];
    fileUpload: [file: File];
}>();

const selectedSample = ref<string | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

const onSampleChange = (value: string | null) => {
    if (value) {
        emit('sampleSelect', value);
        if (fileInput.value) {
            fileInput.value.value = '';
        }
    }
};

const onFileChange = (event: Event) => {
    const target = event.target as HTMLInputElement;
    if (target.files && target.files[0]) {
        emit('fileUpload', target.files[0]);
        selectedSample.value = null;
    }
};
</script>

<template>
    <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-4 p-4 border-b border-gray-200">
        <div>
            <h3 class="font-semibold text-gray-700 mb-2">Tools</h3>
            <div class="flex flex-wrap gap-2">
                <UButton
                    :color="mode === 'point' ? 'primary' : 'neutral'"
                    :variant="mode === 'point' ? 'solid' : 'outline'"
                    @click="emit('modeChange', 'point')"
                >
                    Point
                </UButton>
                <UButton
                    :color="mode === 'path' ? 'primary' : 'neutral'"
                    :variant="mode === 'path' ? 'solid' : 'outline'"
                    @click="emit('modeChange', 'path')"
                >
                    Path
                </UButton>
                <UButton
                    color="neutral"
                    variant="outline"
                    :disabled="!hasPath"
                    @click="emit('clearPath')"
                >
                    Clear
                </UButton>
            </div>
            <p class="mt-2 text-sm text-gray-500">
                {{
                    mode === 'point'
                        ? 'Click anywhere to read the elevation at that location.'
                        : 'Click to append points; the profile appears from two points on.'
                }}
            </p>
        </div>

        <div>
            <h3 class="font-semibold text-gray-700 mb-2">Relief</h3>
            <div class="flex flex-wrap gap-2">
                <UButton
                    v-for="option in ['off', 'hillshade', 'slope'] as const"
                    :key="option"
                    :color="relief === option ? 'primary' : 'neutral'"
                    :variant="relief === option ? 'solid' : 'outline'"
                    class="capitalize"
                    @click="emit('reliefChange', option)"
                >
                    {{ option }}
                </UButton>
            </div>

            <h3 class="font-semibold text-gray-700 mt-4 mb-2">GPX</h3>
            <div class="flex flex-col gap-2">
                <USelect
                    v-model="selectedSample"
                    :items="gpxSamples"
                    label-key="label"
                    value-key="value"
                    placeholder="Load a sample track..."
                    @update:modelValue="onSampleChange"
                />
                <input
                    ref="fileInput"
                    type="file"
                    accept=".gpx"
                    class="text-sm"
                    @change="onFileChange"
                />
            </div>
        </div>

        <div>
            <label class="flex items-center gap-2 font-semibold text-gray-700 mb-2">
                <UCheckbox v-model="smoothing.enabled" />
                Smoothing
            </label>
            <SliderInput
                v-model="smoothing.windowSize"
                label="Window"
                unit="m"
                :min="10"
                :max="200"
                :step="10"
                tooltip="Distance-based triangular-kernel window."
            />
        </div>

        <div>
            <label class="flex items-center gap-2 font-semibold text-gray-700 mb-2">
                <UCheckbox v-model="filter.enabled" />
                Filtering
            </label>
            <SliderInput
                v-model="filter.tolerance"
                label="Tolerance"
                unit="m"
                :min="1"
                :max="100"
                :step="1"
                tooltip="Douglas-Peucker 3D tolerance."
            />
            <SliderInput
                v-model="filter.zExaggeration"
                label="Z exaggeration"
                :min="1"
                :max="10"
                :step="0.5"
                tooltip="Vertical exaggeration applied before the 3D distance test."
            />
        </div>
    </div>
</template>
