<script setup lang="ts">
import Checkbox from 'primevue/checkbox';
import type { DemoEnhanceOptions } from '~/types';
import SliderInput from './SliderInput.vue';

const props = defineProps<{
    modelValue: DemoEnhanceOptions;
}>();

const emit = defineEmits<{
    'update:modelValue': [value: DemoEnhanceOptions];
}>();

const updateField = <K extends keyof DemoEnhanceOptions>(
    field: K,
    value: DemoEnhanceOptions[K]
) => {
    emit('update:modelValue', { ...props.modelValue, [field]: value });
};

const updateSimplifyField = <K extends keyof DemoEnhanceOptions['simplifyPath']>(
    field: K,
    value: DemoEnhanceOptions['simplifyPath'][K]
) => {
    emit('update:modelValue', {
        ...props.modelValue,
        simplifyPath: {
            ...props.modelValue.simplifyPath,
            [field]: value,
        },
    });
};
</script>

<template>
    <div class="p-4">
        <h3 class="text-xl font-semibold text-gray-800 mb-3">🔧 Enhancement Pipeline Options</h3>

        <p class="text-gray-600 text-sm mb-6">
            Configure which processing steps are applied to the GPX track during enhancement.
        </p>

        <div class="mb-8">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">Processing Steps</h4>

            <div class="flex flex-col gap-3">
                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <Checkbox
                        :binary="true"
                        :modelValue="modelValue.fixElevation"
                        @update:modelValue="updateField('fixElevation', !modelValue.fixElevation)"
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Fix Elevation Data</strong>
                        <small class="text-gray-600 text-sm"
                            >Correct elevation using external elevation service</small
                        >
                    </span>
                </label>

                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <Checkbox
                        :binary="true"
                        :modelValue="modelValue.computeMaxSpeeds"
                        @update:modelValue="
                            updateField('computeMaxSpeeds', !modelValue.computeMaxSpeeds)
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Compute Maximum Safe Speeds</strong>
                        <small class="text-gray-600 text-sm"
                            >Calculate cornering and braking limits (auto-enabled if virtualization
                            is on)</small
                        >
                    </span>
                </label>

                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <Checkbox
                        :binary="true"
                        :modelValue="modelValue.virtualizeTrack"
                        @update:modelValue="
                            updateField('virtualizeTrack', !modelValue.virtualizeTrack)
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Virtualize Track</strong>
                        <small class="text-gray-600 text-sm"
                            >Simulate realistic cycling speeds using physics-based
                            calculations</small
                        >
                    </span>
                </label>

                <label
                    class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all"
                >
                    <Checkbox
                        :binary="true"
                        :modelValue="modelValue.computeOnePointPerSecond"
                        @update:modelValue="
                            updateField(
                                'computeOnePointPerSecond',
                                !modelValue.computeOnePointPerSecond
                            )
                        "
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Resample to 1Hz</strong>
                        <small class="text-gray-600 text-sm"
                            >Standardize track to one point per second for consistent
                            analysis</small
                        >
                    </span>
                </label>
            </div>
        </div>

        <div class="pt-6 border-t-2 border-gray-200">
            <h4 class="text-lg font-semibold text-gray-700 mb-4">Path Simplification</h4>

            <label
                class="flex items-start gap-3 p-4 bg-gray-50 border-2 border-gray-200 rounded-lg cursor-pointer hover:bg-gray-100 hover:border-blue-500 transition-all mb-6"
            >
                <Checkbox
                    :binary="true"
                    :modelValue="modelValue.simplifyPath.enable"
                    @update:modelValue="
                        updateSimplifyField('enable', !modelValue.simplifyPath.enable)
                    "
                    class="mt-1"
                />
                <span class="flex flex-col gap-1">
                    <strong class="text-gray-800">Enable Path Simplification</strong>
                    <small class="text-gray-600 text-sm"
                        >Reduce point count using Douglas-Peucker algorithm</small
                    >
                </span>
            </label>

            <div
                v-if="modelValue.simplifyPath.enable"
                class="p-6 bg-gray-50 rounded-lg border-l-4 border-blue-500"
            >
                <SliderInput
                    :model-value="modelValue.simplifyPath.tolerance"
                    @update:model-value="updateSimplifyField('tolerance', $event)"
                    label="Tolerance"
                    unit="m"
                    :min="1"
                    :max="50"
                    :step="1"
                    tooltip="Maximum allowed distance from simplified line (higher = more aggressive simplification)"
                />

                <SliderInput
                    :model-value="modelValue.simplifyPath.zExaggeration"
                    @update:model-value="updateSimplifyField('zExaggeration', $event)"
                    label="Elevation Exaggeration"
                    :min="1"
                    :max="10"
                    :step="0.5"
                    tooltip="Factor for elevation weighting in 3D distance calculation"
                />

                <div class="mt-6 p-4 bg-green-50 rounded-md">
                    <p class="text-gray-800 text-sm m-0">
                        Current settings will simplify the path using a tolerance of
                        <strong>{{ modelValue.simplifyPath.tolerance }}m</strong> with elevation
                        weighted <strong>{{ modelValue.simplifyPath.zExaggeration }}x</strong>.
                    </p>
                </div>
            </div>
        </div>
    </div>
</template>
