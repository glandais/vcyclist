<script setup lang="ts">
import { PowerParams, PowerSourceType } from '~/types';
import SliderInput from './SliderInput.vue';

const powerSourceItems = [
    {
        value: PowerSourceType.constant,
        label: 'Constant Power',
        description: 'Steady power output throughout the ride',
    },
    {
        value: PowerSourceType.durability,
        label: 'Durability',
        description: 'Power fades with the work accumulated above critical power',
    },
    {
        value: PowerSourceType.from_data,
        label: 'From GPX Data',
        description: 'Use power data from GPX file (if available)',
    },
];

const props = defineProps<{
    modelValue: PowerParams;
}>();

const emit = defineEmits<{
    'update:modelValue': [value: PowerParams];
}>();

const updateField = <K extends keyof PowerParams>(field: K, value: PowerParams[K]) => {
    emit('update:modelValue', { ...props.modelValue, [field]: value });
};
</script>

<template>
    <div class="p-4">
        <h3 class="text-xl font-semibold text-gray-800 mb-6">⚡ Power Configuration</h3>

        <div class="mb-8">
            <label class="block font-medium text-gray-800 text-base mb-4">Power Source:</label>

            <URadioGroup
                name="powerSource"
                variant="card"
                :items="powerSourceItems"
                :modelValue="modelValue.type"
                @update:modelValue="updateField('type', $event as PowerSourceType)"
            />
        </div>

        <div v-if="modelValue.type !== PowerSourceType.from_data" class="mt-8">
            <SliderInput
                :model-value="modelValue.power"
                @update:model-value="updateField('power', $event)"
                label="Power Output"
                unit="W"
                :min="100"
                :max="500"
                :step="10"
                tooltip="Sustained power output in watts"
            />

            <div class="my-6">
                <label class="flex items-start gap-3 cursor-pointer">
                    <UCheckbox
                        :modelValue="modelValue.useHarmonics"
                        @update:modelValue="updateField('useHarmonics', !modelValue.useHarmonics)"
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Use power harmonics</strong>
                        <small class="text-gray-600 text-sm"
                            >Adds realistic power variations to simulate natural pedaling</small
                        >
                    </span>
                </label>
            </div>

            <div
                v-if="modelValue.type === PowerSourceType.durability"
                class="mt-8 pt-6 border-t-2 border-gray-200"
            >
                <SliderInput
                    :model-value="modelValue.criticalPower"
                    @update:model-value="updateField('criticalPower', $event)"
                    label="Critical Power"
                    unit="W"
                    :min="100"
                    :max="450"
                    :step="5"
                    tooltip="Power the rider can hold indefinitely. Only work above it counts toward fatigue."
                />

                <div class="mt-6 p-4 bg-amber-50 rounded-lg border-l-4 border-amber-500">
                    <p class="text-gray-800 mb-3 m-0">
                        <strong>Durability model:</strong> fatigue is driven by the work accumulated
                        <em>above</em> critical power, not by elapsed time — riding at or below CP
                        costs nothing. The default fade reaches 10&nbsp;% at 15&nbsp;kJ/kg of
                        supra-CP work.
                    </p>
                    <p v-if="modelValue.power <= modelValue.criticalPower" class="m-0 text-sm">
                        At {{ modelValue.power }}W against a {{ modelValue.criticalPower }}W CP the
                        rider never goes above CP, so power will not fade at all.
                    </p>
                    <p v-else class="m-0 text-sm">
                        {{ modelValue.power - modelValue.criticalPower }}W above CP — fade builds up
                        the longer that is held.
                    </p>
                </div>
            </div>
        </div>

        <div v-else class="mt-8 p-4 bg-blue-50 rounded-lg border-l-4 border-blue-500">
            <p class="text-gray-800 text-sm m-0">
                Power data will be read from the GPX file's power extension data. If no power data
                is available, a default constant power will be used.
            </p>
        </div>
    </div>
</template>
