<script setup lang="ts">
import { computed } from 'vue';
import { PowerParams, PowerSourceType, SLEW_W_PER_S } from '~/types';
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
        value: PowerSourceType.critical_power,
        label: 'Critical Power',
        description: 'Spends a W′ reserve, then settles back to CP as it empties',
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

/** The two models that consume a critical power. */
const usesCp = computed(
    () =>
        props.modelValue.type === PowerSourceType.durability ||
        props.modelValue.type === PowerSourceType.critical_power
);
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

            <div v-if="usesCp" class="mt-8 pt-6 border-t-2 border-gray-200">
                <SliderInput
                    :model-value="modelValue.criticalPower"
                    @update:model-value="updateField('criticalPower', $event)"
                    label="Critical Power"
                    unit="W"
                    :min="100"
                    :max="450"
                    :step="5"
                    tooltip="Power the rider can hold indefinitely. Only work above it counts."
                />

                <SliderInput
                    v-if="modelValue.type === PowerSourceType.critical_power"
                    :model-value="modelValue.wPrime / 1000"
                    @update:model-value="updateField('wPrime', $event * 1000)"
                    label="W′ Reserve"
                    unit="kJ"
                    :min="5"
                    :max="40"
                    :step="1"
                    tooltip="Anaerobic work capacity: the finite energy spendable above CP before the rider must settle back to it."
                />

                <div class="mt-6 p-4 bg-amber-50 rounded-lg border-l-4 border-amber-500">
                    <p
                        v-if="modelValue.type === PowerSourceType.durability"
                        class="text-gray-800 mb-3 m-0"
                    >
                        <strong>Durability model:</strong> fatigue is driven by the work accumulated
                        <em>above</em> critical power, not by elapsed time — riding at or below CP
                        costs nothing. The default fade reaches 10&nbsp;% at 15&nbsp;kJ/kg of
                        supra-CP work.
                    </p>
                    <p v-else class="text-gray-800 mb-3 m-0">
                        <strong>Critical-power model:</strong> the rider holds the target while the
                        W′ reserve lasts, then tapers back toward CP as it empties — asymptotically,
                        so power approaches CP without ever dipping below it. This is the largest
                        behavioural change of the set: long rides above CP slow down a lot, short
                        ones barely move.
                    </p>
                    <p v-if="modelValue.power <= modelValue.criticalPower" class="m-0 text-sm">
                        At {{ modelValue.power }}W against a {{ modelValue.criticalPower }}W CP the
                        rider never goes above CP, so nothing fades and nothing is spent.
                    </p>
                    <p v-else class="m-0 text-sm">
                        {{ modelValue.power - modelValue.criticalPower }}W above CP.
                        <template v-if="modelValue.type === PowerSourceType.critical_power">
                            The {{ (modelValue.wPrime / 1000).toFixed(0) }}&nbsp;kJ reserve empties
                            in about
                            {{
                                Math.round(
                                    modelValue.wPrime /
                                        (modelValue.power - modelValue.criticalPower)
                                )
                            }}&nbsp;s at that rate, after which the rider settles near CP.
                        </template>
                        <template v-else>Fade builds up the longer that is held.</template>
                    </p>
                </div>
            </div>

            <div class="mt-8 pt-6 border-t-2 border-gray-200">
                <label class="block font-medium text-gray-800 text-base mb-1">Rider habits</label>
                <p class="text-gray-600 text-sm mb-4">
                    Applied on top of whichever model is selected above.
                </p>

                <label class="flex items-start gap-3 cursor-pointer mb-4">
                    <UCheckbox
                        :modelValue="modelValue.pacing"
                        @update:modelValue="updateField('pacing', !modelValue.pacing)"
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Adapt the effort to the terrain</strong>
                        <small class="text-gray-600 text-sm">
                            Harder uphill and into a headwind, easier downhill — with an energy
                            account, so the effort is redistributed rather than simply added. A
                            heuristic with no lookahead: the rider reacts to the road it is on.
                        </small>
                    </span>
                </label>

                <label class="flex items-start gap-3 cursor-pointer">
                    <UCheckbox
                        :modelValue="modelValue.slew"
                        @update:modelValue="updateField('slew', !modelValue.slew)"
                        class="mt-1"
                    />
                    <span class="flex flex-col gap-1">
                        <strong class="text-gray-800">Smooth the power changes</strong>
                        <small class="text-gray-600 text-sm">
                            Caps how fast power may move at {{ SLEW_W_PER_S }}&nbsp;W/s, so the
                            rider ramps up from a standstill instead of appearing at full power.
                        </small>
                    </span>
                </label>

                <p
                    v-if="modelValue.pacing"
                    class="mt-4 p-3 bg-blue-50 rounded-md border-l-4 border-blue-500 text-sm text-gray-800"
                >
                    Worth about 1–3&nbsp;% on rolling terrain, which is where the literature puts
                    the entire pacing prize. On a pure climb it has nothing to redistribute
                    <em>to</em>, so a large gain there means the rider simply rode harder — not that
                    it paced better.
                </p>
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
