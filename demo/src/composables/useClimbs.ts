import { computed, reactive, type Ref, shallowRef, watch } from 'vue';
import { type ClimbDto, detectClimbsWithOptions, type Path } from '~/engine-shim';

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
/**
 * The six knobs `detectClimbsWithOptions` takes, at the engine's own defaults.
 *
 * Spelled out here because the JS door is a positional function rather than an options object, so
 * there is nothing to read them off. They match `ClimbOptions`' declaration; the seventh field,
 * `maxAnalysisPoints`, is the O(n²) guard and is left at its default — a UI control for a
 * performance backstop would invite users to make the tab freeze.
 */
export const CLIMB_DEFAULTS = {
    minMinClimbElevationM: 10,
    maxMinClimbElevationM: 35,
    minClimbElevationRatio: 100,
    minGradePercent: 3,
    maxDiffRealGrade: 1.3,
    booster: 1.3,
} as const;

export type ClimbTuning = { -readonly [K in keyof typeof CLIMB_DEFAULTS]: number };

export function useClimbs(currentPath: Ref<Path | null>) {
    const climbs = shallowRef<ClimbDto[]>([]);
    const error = shallowRef<string | null>(null);
    /** Reactive copy of {@link CLIMB_DEFAULTS}, driven by the controls in `ClimbsPanel`. */
    const tuning = reactive<ClimbTuning>({ ...CLIMB_DEFAULTS });

    const recompute = () => {
        if (!currentPath.value) {
            climbs.value = [];
            error.value = null;
            return;
        }
        try {
            // `detectClimbsWithOptions`, not `detectClimbs`: the six knobs were bound in the shim
            // and reachable from no control at all, which the coverage ledger counts as not
            // crossing the demo surface. Same defaults, so an untouched UI detects what it did.
            climbs.value = detectClimbsWithOptions(
                currentPath.value,
                tuning.minMinClimbElevationM,
                tuning.maxMinClimbElevationM,
                tuning.minClimbElevationRatio,
                tuning.minGradePercent,
                tuning.maxDiffRealGrade,
                tuning.booster
            );
            error.value = null;
        } catch (e) {
            climbs.value = [];
            error.value = (e as Error).message;
        }
    };

    watch([currentPath, tuning], recompute, { immediate: true, deep: true });

    const hasClimbs = computed(() => climbs.value.length > 0);

    /** Steepest part of a climb, dimensionless. Used for the "max grade" table column. */
    const maxPartGrade = (climb: ClimbDto): number =>
        climb.parts.reduce((max, part) => Math.max(max, part.grade), 0);

    const isTuned = computed(() =>
        Object.entries(CLIMB_DEFAULTS).some(([k, v]) => tuning[k as keyof ClimbTuning] !== v)
    );

    const resetTuning = () => Object.assign(tuning, CLIMB_DEFAULTS);

    return { climbs, hasClimbs, error, maxPartGrade, recompute, tuning, isTuned, resetTuning };
}
