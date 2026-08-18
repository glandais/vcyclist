<script setup lang="ts">
import { ref } from 'vue';
import { type ClimbTuning, GRADE_COLORS, gradeColor } from '~/composables/useClimbs';
import type { ClimbDto } from '~/engine-shim';

const props = defineProps<{
    climbs: ClimbDto[];
    selectedIndex: number | null;
    tuning: ClimbTuning;
    isTuned: boolean;
}>();

const emit = defineEmits<{
    select: [index: number];
    resetTuning: [];
    // A child does not mutate a prop (vue/no-mutating-props): the composable owns the state, the
    // panel only says which knob moved and to what.
    tune: [key: keyof ClimbTuning, value: number];
}>();

const tuningOpen = ref(false);

/**
 * The six knobs of `detectClimbsWithOptions`, in the engine's declaration order.
 *
 * They existed on every wire door and reached no control here, which is what the coverage ledger
 * means by a capability not crossing the demo surface — a shim binding is not a surface crossing.
 */
const TUNING_FIELDS: { key: keyof ClimbTuning; label: string; step: number; hint: string }[] = [
    {
        key: 'minMinClimbElevationM',
        label: 'Min threshold',
        step: 1,
        hint: 'Floor for the dynamic elevation threshold (m).',
    },
    {
        key: 'maxMinClimbElevationM',
        label: 'Max threshold',
        step: 1,
        hint: 'Ceiling for the dynamic elevation threshold (m).',
    },
    {
        key: 'minClimbElevationRatio',
        label: 'Elevation ratio',
        step: 5,
        hint: 'Total ascent is divided by this to size the threshold.',
    },
    {
        key: 'minGradePercent',
        label: 'Min grade',
        step: 0.5,
        hint: 'Minimum average grade for a candidate, in %.',
    },
    {
        key: 'maxDiffRealGrade',
        label: 'Max grade ratio',
        step: 0.1,
        hint: 'Rejects candidates that only average out by mixing ramps with descents.',
    },
    {
        key: 'booster',
        label: 'Steepness bias',
        step: 0.1,
        hint: 'Exponent on grade when scoring: above 1 favours steep over merely long.',
    },
];

const km = (meters: number) => (meters / 1000).toFixed(2);
const percent = (grade: number) => (grade * 100).toFixed(1);

const maxPartGrade = (climb: ClimbDto) =>
    climb.parts.reduce((max, part) => Math.max(max, part.grade), 0);
</script>

<template>
    <section class="bg-white border border-gray-200 rounded-lg p-4">
        <header class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <h2 class="text-lg font-semibold text-slate-700">
                ⛰️ Climbs
                <span v-if="isTuned" class="ml-1 align-middle text-xs font-normal text-amber-700">
                    (tuned)
                </span>
            </h2>
            <div class="flex items-center gap-3 text-xs text-slate-600">
                <span
                    v-for="band in GRADE_COLORS"
                    :key="band.label"
                    class="flex items-center gap-1"
                >
                    <span
                        class="inline-block w-3 h-3 rounded-sm"
                        :style="{ backgroundColor: band.color }"
                    />
                    {{ band.label }}
                </span>
            </div>
        </header>

        <p v-if="props.climbs.length === 0" class="text-sm text-slate-500 py-2">
            No climb detected on this route.
        </p>

        <div v-else class="overflow-x-auto">
            <table class="w-full text-sm">
                <thead>
                    <tr class="text-left text-slate-500 border-b border-gray-200">
                        <th class="py-2 pr-3 font-medium">#</th>
                        <th class="py-2 pr-3 font-medium">Start (km)</th>
                        <th class="py-2 pr-3 font-medium">Length (km)</th>
                        <th class="py-2 pr-3 font-medium">Gain (m)</th>
                        <th class="py-2 pr-3 font-medium">Avg grade</th>
                        <th class="py-2 pr-3 font-medium">Max grade</th>
                        <th class="py-2 font-medium">Profile</th>
                    </tr>
                </thead>
                <tbody>
                    <tr
                        v-for="(climb, i) in props.climbs"
                        :key="climb.startIndex"
                        class="border-b border-gray-100 cursor-pointer hover:bg-blue-50 transition-colors"
                        :class="{ 'bg-blue-100': props.selectedIndex === i }"
                        @click="emit('select', i)"
                    >
                        <td class="py-2 pr-3 font-medium text-slate-700">{{ i + 1 }}</td>
                        <td class="py-2 pr-3">{{ km(climb.startDistanceM) }}</td>
                        <td class="py-2 pr-3">{{ km(climb.lengthM) }}</td>
                        <td class="py-2 pr-3">{{ climb.elevationGainM.toFixed(0) }}</td>
                        <td class="py-2 pr-3">
                            <span
                                class="px-1.5 py-0.5 rounded text-white text-xs font-medium"
                                :style="{ backgroundColor: gradeColor(climb.averageGrade) }"
                            >
                                {{ percent(climb.averageGrade) }} %
                            </span>
                        </td>
                        <td class="py-2 pr-3">
                            <span
                                class="px-1.5 py-0.5 rounded text-white text-xs font-medium"
                                :style="{ backgroundColor: gradeColor(maxPartGrade(climb)) }"
                            >
                                {{ percent(maxPartGrade(climb)) }} %
                            </span>
                        </td>
                        <td class="py-2">
                            <!-- Each part drawn proportionally to its length, coloured by grade. -->
                            <span
                                class="flex h-3 w-32 rounded-sm overflow-hidden"
                                :title="`${climb.parts.length} parts`"
                            >
                                <span
                                    v-for="(part, p) in climb.parts"
                                    :key="p"
                                    :style="{
                                        backgroundColor: gradeColor(part.grade),
                                        width: `${(part.lengthM / climb.lengthM) * 100}%`,
                                    }"
                                />
                            </span>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- Detection tuning. Collapsed by default: the defaults are the engine's, and a reader
             who has not asked to tune should see climbs, not knobs. -->
        <div class="mt-4 border-t border-gray-200 pt-3">
            <button
                type="button"
                class="text-xs text-slate-600 hover:text-slate-900"
                @click="tuningOpen = !tuningOpen"
            >
                {{ tuningOpen ? '▾' : '▸' }} Detection tuning
            </button>

            <div v-if="tuningOpen" class="mt-3">
                <div class="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                    <label v-for="field in TUNING_FIELDS" :key="field.key" class="block text-xs">
                        <span class="mb-1 block font-medium text-slate-700" :title="field.hint">
                            {{ field.label }}
                        </span>
                        <input
                            type="number"
                            class="w-full rounded border border-gray-300 px-2 py-1"
                            :step="field.step"
                            :value="tuning[field.key]"
                            @change="
                                emit(
                                    'tune',
                                    field.key,
                                    Number(($event.target as HTMLInputElement).value)
                                )
                            "
                        />
                    </label>
                </div>
                <button
                    type="button"
                    class="mt-3 text-xs text-slate-600 underline hover:text-slate-900"
                    :disabled="!isTuned"
                    @click="emit('resetTuning')"
                >
                    Reset to engine defaults
                </button>
            </div>
        </div>
    </section>
</template>
