<script setup lang="ts">
import { computed } from 'vue';
import type { CyclistDto } from '~/engine-shim';
import SliderInput from './SliderInput.vue';

type CyclistProperties = CyclistDto;

const props = defineProps<{
    modelValue: CyclistProperties;
}>();

const emit = defineEmits<{
    'update:modelValue': [value: CyclistProperties];
}>();

const updateField = <K extends keyof CyclistProperties>(field: K, value: CyclistProperties[K]) => {
    emit('update:modelValue', { ...props.modelValue, [field]: value });
};

const roadConditionItems = [
    {
        value: 'dry',
        label: '☀️ Dry',
        description: 'µ = 0.70 — the shipped defaults, reproduced exactly',
    },
    {
        value: 'wet',
        label: '🌧️ Wet',
        description: 'µ = 0.28 — cornering speed cut by 1.58×, braking down to 0.23 g',
    },
];

// The engine derives BOTH grip limits from the preset and they override the two sliders below,
// which is why this control sits above them. µ = tan(lean), so the angle IS a friction
// coefficient — shown here because every source in the literature uses that form.
const mu = computed(() => Math.tan((props.modelValue.maxLeanAngleDeg * Math.PI) / 180));
</script>

<template>
    <div class="p-4">
        <h3 class="text-xl font-semibold text-gray-800 mb-6">👤 Cyclist Parameters</h3>

        <SliderInput
            :model-value="modelValue.massKg"
            @update:model-value="updateField('massKg', $event)"
            label="Body Mass"
            unit="kg"
            :min="50"
            :max="120"
            :step="1"
            tooltip="Total mass of cyclist + bike + gear"
        />

        <div class="mb-6">
            <label class="block font-medium text-gray-800 text-base mb-3">Road Condition:</label>
            <URadioGroup
                name="roadCondition"
                variant="card"
                orientation="horizontal"
                :items="roadConditionItems"
                :modelValue="modelValue.roadCondition ?? 'dry'"
                @update:modelValue="updateField('roadCondition', $event as 'dry' | 'wet')"
            />
            <p
                v-if="(modelValue.roadCondition ?? 'dry') === 'wet'"
                class="mt-3 p-3 bg-blue-50 rounded-md border-l-4 border-blue-500 text-sm text-gray-800"
            >
                A wet road overrides the lean angle and braking sliders below — rain takes grip away
                from cornering <em>and</em> from braking, and moving only one would model a rider
                who cannot corner but can still stop like it is dry. Expect roughly +3 % over a long
                route and more on a technical one.
            </p>
        </div>

        <SliderInput
            :model-value="modelValue.maxLeanAngleDeg"
            @update:model-value="updateField('maxLeanAngleDeg', $event)"
            label="Max Lean Angle"
            unit="°"
            :min="30"
            :max="55"
            :step="1"
            :tooltip="`Maximum cornering lean angle (higher = faster cornering). This is a tyre friction coefficient in disguise: µ = tan(angle) = ${mu.toFixed(2)}, and v_max = √(µ·g·R).`"
        />

        <p
            v-if="(modelValue.roadCondition ?? 'dry') === 'wet'"
            class="-mt-2 mb-4 text-sm text-gray-500 italic"
        >
            Overridden by the wet preset (15.6°, µ = 0.28).
        </p>

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
                        :model-value="modelValue.cd"
                        @update:model-value="updateField('cd', $event)"
                        label="Drag Coefficient"
                        :min="0.5"
                        :max="0.9"
                        :step="0.01"
                        tooltip="Aerodynamic drag coefficient (lower = more aero)"
                    />

                    <SliderInput
                        :model-value="modelValue.frontalAreaM2"
                        @update:model-value="updateField('frontalAreaM2', $event)"
                        label="Frontal Area"
                        unit="m²"
                        :min="0.3"
                        :max="0.6"
                        :step="0.01"
                        tooltip="Frontal area exposed to wind"
                    />

                    <SliderInput
                        :model-value="modelValue.maxBrakeG"
                        @update:model-value="updateField('maxBrakeG', $event)"
                        label="Max Brake Force"
                        unit="G"
                        :min="0.3"
                        :max="0.6"
                        :step="0.05"
                        tooltip="Maximum braking deceleration. Above ~0.63 g the bike pitches over the front wheel, whatever the tyres do — so the slider stops below it."
                    />

                    <p
                        v-if="(modelValue.roadCondition ?? 'dry') === 'wet'"
                        class="-mt-2 mb-4 text-sm text-gray-500 italic"
                    >
                        Overridden by the wet preset (0.23 g).
                    </p>

                    <SliderInput
                        :model-value="modelValue.maxSpeedKmH"
                        @update:model-value="updateField('maxSpeedKmH', $event)"
                        label="Max Speed"
                        unit="km/h"
                        :min="40"
                        :max="130"
                        :step="5"
                        tooltip="Absolute maximum speed limit"
                    />
                </div>
            </template>
        </UCollapsible>
    </div>
</template>
