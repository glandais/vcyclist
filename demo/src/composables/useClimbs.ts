import { computed, type Ref, shallowRef, watch } from 'vue';
import { type ClimbDto, detectClimbs, type Path } from '~/engine-shim';

/**
 * Cycling's conventional grade colours. Thresholds are on the dimensionless grade
 * (0.05 = 5 %), matching `ClimbPartDto.grade`.
 */
export const GRADE_COLORS = [
    { maxGrade: 0.05, color: '#22c55e', label: '< 5 %' },
    { maxGrade: 0.08, color: '#eab308', label: '5–8 %' },
    { maxGrade: 0.11, color: '#f97316', label: '8–11 %' },
    { maxGrade: Infinity, color: '#ef4444', label: '> 11 %' },
] as const;

export function gradeColor(grade: number): string {
    return (
        GRADE_COLORS.find(band => grade < band.maxGrade) ?? GRADE_COLORS[GRADE_COLORS.length - 1]
    ).color;
}

/**
 * Climb detection over the current path.
 *
 * Detection is a pure computation on the enhanced `Path`, so it runs **once per path** — the
 * watcher below — rather than on every render. `shallowRef` keeps Vue from deep-proxying the
 * DTOs coming out of the Kotlin bundle, which would be wasted work on immutable data.
 */
export function useClimbs(currentPath: Ref<Path | null>) {
    const climbs = shallowRef<ClimbDto[]>([]);
    const error = shallowRef<string | null>(null);

    const recompute = () => {
        if (!currentPath.value) {
            climbs.value = [];
            error.value = null;
            return;
        }
        try {
            climbs.value = detectClimbs(currentPath.value);
            error.value = null;
        } catch (e) {
            climbs.value = [];
            error.value = (e as Error).message;
        }
    };

    watch(currentPath, recompute, { immediate: true });

    const hasClimbs = computed(() => climbs.value.length > 0);

    /** Steepest part of a climb, dimensionless. Used for the "max grade" table column. */
    const maxPartGrade = (climb: ClimbDto): number =>
        climb.parts.reduce((max, part) => Math.max(max, part.grade), 0);

    return { climbs, hasClimbs, error, maxPartGrade, recompute };
}
