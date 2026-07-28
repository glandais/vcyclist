<script setup lang="ts">
import { GRADE_COLORS, gradeColor } from '~/composables/useClimbs';
import type { ClimbDto } from '~/engine-shim';

const props = defineProps<{
    climbs: ClimbDto[];
    selectedIndex: number | null;
}>();

const emit = defineEmits<{
    select: [index: number];
}>();

const km = (meters: number) => (meters / 1000).toFixed(2);
const percent = (grade: number) => (grade * 100).toFixed(1);

const maxPartGrade = (climb: ClimbDto) =>
    climb.parts.reduce((max, part) => Math.max(max, part.grade), 0);
</script>

<template>
    <section class="bg-white border border-gray-200 rounded-lg p-4">
        <header class="flex items-center justify-between mb-3 flex-wrap gap-2">
            <h2 class="text-lg font-semibold text-slate-700">⛰️ Climbs</h2>
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
    </section>
</template>
