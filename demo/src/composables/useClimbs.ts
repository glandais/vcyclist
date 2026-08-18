import { computed, reactive, type Ref, shallowRef, watch } from 'vue';
import {
    type ClimbDto,
    climbDefaults,
    detectClimbsWithOptions,
    type Path,
} from '@glandais/vcyclist-engine';

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
 * The six knobs the tuning panel exposes, at the engine's own defaults.
 *
 * Read off `climbDefaults`, which `:codegen` generates from `ClimbOptions` — these were six
 * literals until the packages started publishing their defaults, and nothing compared them to the
 * engine. The seventh field, `maxAnalysisPoints`, is dropped here rather than absent there: it is
 * the O(n²) guard, and a UI control for a performance backstop would invite users to freeze the
 * tab. `detectClimbsWithOptions` takes it last and optional, so omitting it keeps the default.
 */
const { maxAnalysisPoints, ...CLIMB_DEFAULTS } = climbDefaults;

export { CLIMB_DEFAULTS };

export type ClimbTuning = { -readonly [K in keyof typeof CLIMB_DEFAULTS]: number };

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
    /** Reactive copy of {@link CLIMB_DEFAULTS}, driven by the controls in `ClimbsPanel`. */
    const tuning = reactive<ClimbTuning>({ ...CLIMB_DEFAULTS });

    const recompute = () => {
        if (!currentPath.value) {
            climbs.value = [];
            error.value = null;
            return;
        }
        try {
            // `detectClimbsWithOptions`, not `detectClimbs`: the six knobs were exported by the
            // façade and reachable from no control at all, which the coverage ledger counts as not
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
