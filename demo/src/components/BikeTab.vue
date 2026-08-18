<script setup lang="ts">
import type { BikeDto } from '@glandais/vcyclist-engine';
import { PRESETS } from '~/types';
import SliderInput from './SliderInput.vue';

type BikeProperties = BikeDto;

const props = defineProps<{
    modelValue: BikeProperties;
}>();

const emit = defineEmits<{
    'update:modelValue': [value: BikeProperties];
}>();

const updateField = <K extends keyof BikeProperties>(field: K, value: BikeProperties[K]) => {
    emit('update:modelValue', { ...props.modelValue, [field]: value });
};

// Wheel preset values are diameters in meters; `wheelRadiusM` is the radius, so /2.
const wheelPresets = {
    '650b': 0.65,
    '700c': 0.7,
    '29er': 0.735,
};

const applyWheelPreset = (preset: keyof typeof wheelPresets) => {
    updateField('wheelRadiusM', wheelPresets[preset] / 2);
};

const resetToDefault = () => {
    emit('update:modelValue', structuredClone(PRESETS.recreational.bike));
};
</script>

<template>
    <div class="p-4">
        <h3 class="text-xl font-semibold text-gray-800 mb-6">🚴 Bike Parameters</h3>

        <div
            class="mb-8 p-5 bg-gradient-to-br from-gray-50 to-gray-100 rounded-lg border-2 border-gray-200"
        >
            <label class="block font-semibold text-gray-800 mb-3 text-base">🚲 Wheel Size</label>
            <div class="flex flex-wrap gap-3">
                <UButton
                    @click="applyWheelPreset('650b')"
                    variant="outline"
                    color="neutral"
                    block
                    class="flex-1 min-w-[90px]"
                >
                    650b
                </UButton>
                <UButton
                    @click="applyWheelPreset('700c')"
                    variant="outline"
                    color="neutral"
                    block
                    class="flex-1 min-w-[90px]"
                >
                    700c
                </UButton>
                <UButton
                    @click="applyWheelPreset('29er')"
                    variant="outline"
                    color="neutral"
                    block
                    class="flex-1 min-w-[90px]"
                >
                    29er
                </UButton>
            </div>
        </div>

        <SliderInput
            :model-value="modelValue.crr"
            @update:model-value="updateField('crr', $event)"
            label="Rolling Resistance"
            :min="0.002"
            :max="0.008"
            :step="0.0001"
            tooltip="Rolling resistance coefficient (lower = faster)"
        />

        <SliderInput
            :model-value="modelValue.maxPedalingLeanAngleDeg ?? 20"
            @update:model-value="updateField('maxPedalingLeanAngleDeg', $event)"
            label="Pedal Clearance Angle"
            unit="°"
            :min="10"
            :max="90"
            :step="1"
            tooltip="Lean angle past which the inside pedal would strike the ground, so the rider stops pedalling. Set 90 to disable."
        />

        <div class="mb-6 p-4 bg-amber-50 rounded-lg border-l-4 border-amber-500 text-sm">
            <p class="text-gray-800 m-0">
                <template v-if="(modelValue.maxPedalingLeanAngleDeg ?? 20) >= 90">
                    The cut-off is <strong>disabled</strong>: the rider pedals at full power through
                    every hairpin.
                </template>
                <template v-else>
                    The rider stops pedalling past
                    <strong>{{ modelValue.maxPedalingLeanAngleDeg ?? 20 }}°</strong> of lean. This
                    barely moves the clock — about 0.3 % — because it fires exactly where cornering
                    or braking was throwing the power away anyway. Watch
                    <code>pCyclistProvidedMuscular</code> against
                    <code>pCyclistProvidedOptimalPower</code> in the chart: the gap between them
                    <em>is</em> the cut-off.
                </template>
            </p>
        </div>

        <UCollapsible class="mt-6 rounded-lg border border-gray-200">
            <template #default="{ open }">
                <button
                    type="button"
                    class="flex w-full items-center justify-between p-4 cursor-pointer"
                >
                    <span class="font-semibold text-gray-700">⚙️ Advanced Settings</span>
                    <span class="text-xs text-gray-500">{{ open ? '▲' : '▼' }}</span>
                </button>
            </template>
            <template #content>
                <div class="p-4 pt-0">
                    <SliderInput
                        :model-value="modelValue.wheelRadiusM"
                        @update:model-value="updateField('wheelRadiusM', $event)"
                        label="Wheel Radius"
                        unit="m"
                        :min="0.3"
                        :max="0.9"
                        :step="0.005"
                        tooltip="Wheel radius in meters"
                    />

                    <SliderInput
                        :model-value="modelValue.inertiaFront"
                        @update:model-value="updateField('inertiaFront', $event)"
                        label="Front Wheel Inertia"
                        unit="kg⋅m²"
                        :min="0.03"
                        :max="0.1"
                        :step="0.005"
                        tooltip="Rotational inertia of front wheel"
                    />

                    <SliderInput
                        :model-value="modelValue.inertiaRear"
                        @update:model-value="updateField('inertiaRear', $event)"
                        label="Rear Wheel Inertia"
                        unit="kg⋅m²"
                        :min="0.03"
                        :max="0.1"
                        :step="0.005"
                        tooltip="Rotational inertia of rear wheel"
                    />

                    <SliderInput
                        :model-value="modelValue.efficiency * 100"
                        @update:model-value="updateField('efficiency', $event / 100)"
                        label="Drivetrain Efficiency"
                        unit="%"
                        :min="90"
                        :max="100"
                        :step="0.1"
                        tooltip="Power transmission efficiency"
                    />

                    <UButton
                        @click="resetToDefault"
                        color="error"
                        variant="outline"
                        class="w-full mt-6"
                    >
                        🔄 Reset to Defaults
                    </UButton>
                </div>
            </template>
        </UCollapsible>
    </div>
</template>
