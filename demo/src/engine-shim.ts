// Re-exports the Kotlin/JS engine bundle under flat, ergonomic names, and declares
// TypeScript types matching the engine's external interface DTOs (which Kotlin/JS does
// not emit as TS interface bodies — only as referenced names).
//
// The Kotlin/JS bundle preserves the package namespace under the root export
// (`mod.io.github.glandais.engine.*`). We unwrap that here so consumers can
// `import { parseGpx } from '~/engine-shim'`.

import * as engineRaw from '@glandais/vcyclist-engine';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const ns: any = (engineRaw as any).io?.github?.glandais?.engine ?? engineRaw;

export interface PointDto {
    readonly latitudeDeg: number;
    readonly longitudeDeg: number;
    readonly elevation: number;
    readonly timeMs: number;
    readonly speed: number;
    readonly pComputedPower: number;
    readonly distance: number;
    readonly grade: number;
}

export interface CyclistDto {
    readonly massKg: number;
    readonly cd: number;
    readonly frontalAreaM2: number;
    readonly maxLeanAngleDeg: number;
    readonly maxBrakeG: number;
    readonly maxSpeedKmH: number;
    /**
     * Road surface preset: 'dry' (default) or 'wet'. Sets cornering grip and braking together.
     * When present it OVERRIDES maxLeanAngleDeg and maxBrakeG — omit it to keep the raw values.
     */
    readonly roadCondition?: 'dry' | 'wet';
}

export interface BikeDto {
    readonly crr: number;
    readonly inertiaFront: number;
    readonly inertiaRear: number;
    readonly wheelRadiusM: number;
    readonly efficiency: number;
    /** Lean angle (°) past which the rider stops pedalling; 90 disables the cut-off. */
    readonly maxPedalingLeanAngleDeg?: number;
}

export interface WindDto {
    readonly windSpeed: number; // m/s
    readonly windDirection: number; // degrees, 0 = N, 90 = E — direction the wind blows toward
}

export interface PowerProviderDto {
    readonly type: 'constant' | 'durability' | 'critical-power' | 'from_data';
    readonly power?: number; // W
    readonly useHarmonics?: boolean;
    /** Critical power (W) for 'durability' and 'critical-power'. Defaults to 250 W. */
    readonly criticalPower?: number;
    /** W′ (J), 'critical-power' only — the reserve spendable above CP. Defaults to 20 kJ. */
    readonly wPrime?: number;
    /** Terrain pacing: harder uphill and into headwind, easier downhill. Off by default. */
    readonly pacing?: boolean;
    /** Power slew limit in W/s; 0 or omitted disables it. 50 is the literature value. */
    readonly maxSlewWPerS?: number;
}

export interface EnhanceOptionsDto {
    readonly fixElevation?: boolean;
    readonly computeMaxSpeeds?: boolean;
    readonly virtualizeTrack?: boolean;
    readonly computeOnePointPerSecond?: boolean;
    readonly simplifyEnabled?: boolean;
    readonly simplifyToleranceM?: number;
    readonly simplifyZExaggeration?: number;
    /** W′ balance annotation pass. Already on by default — these three make it calibrable. */
    readonly wPrimeBalanceEnabled?: boolean;
    /** CP (W) for the wPrimeBalance FIELD — independent of PowerProviderDto.criticalPower. */
    readonly wPrimeBalanceCriticalPower?: number;
    /** W′ (J) for the wPrimeBalance field. */
    readonly wPrimeBalanceWPrime?: number;
    /** Curvature estimation. On by default; it corrects `radius` and `speedMax`. */
    readonly curvatureEnabled?: boolean;
    /**
     * Optimal-trajectory stage. Off by default: it MOVES EVERY COORDINATE of the result. The
     * originals survive in the `sourceLatitude` / `sourceLongitude` fields.
     */
    readonly racingLineEnabled?: boolean;
    /** 'lane' (default), 'lane-left', or 'full-road' — closed roads and time trials only. */
    readonly racingLineCorridor?: 'lane' | 'lane-left' | 'full-road';
    /** Road width assumed where the GPX supplies none, in metres. */
    readonly racingLineRoadWidthM?: number;
}

/** One detected bend, as the racing-line report describes it. */
export interface CornerDto {
    readonly fromIndex: number;
    readonly untilIndex: number;
    readonly apexIndex: number;
    readonly kind: string;
    /** How far the bend turns, in radians. */
    readonly turnRad: number;
    /** +1 or -1. */
    readonly direction: number;
    readonly radiusQ20M: number;
    readonly radiusMinM: number;
    readonly lengthM: number;
}

/**
 * What the racing-line stage would do, without doing it.
 *
 * The per-point arrays are all `size` long and indexed like the path. `corridorLo`/`corridorHi`
 * and `lateralOffsetM` are signed lateral offsets in metres from the reference line, **positive
 * to the left** of the direction of travel.
 */
export interface RacingLineReportDto {
    readonly size: number;
    readonly corners: CornerDto[];
    readonly centerlineCurvature: Float64Array;
    readonly trajectoryCurvature: Float64Array;
    readonly corridorLo: Float64Array;
    readonly corridorHi: Float64Array;
    readonly roadHalfWidthM: Float64Array;
    readonly lateralOffsetM: Float64Array;
    readonly maxCorridorWidthM: number;
    readonly newtonIterations: number;
    readonly relativeGradient: number;
    readonly converged: boolean;
    readonly activeConstraints: number;
}

export interface FieldDefinitionDto {
    readonly prop: string;
    readonly unit: string;
    readonly shortDescription: string;
    readonly categoryId: string;
    readonly categoryName: string;
    readonly notSelectable: boolean;
    readonly anglesInRadians: boolean;
}

// Opaque Path handle — Kotlin/JS class instance, no TS surface.
/** One homogeneous-grade segment of a climb. `grade` is dimensionless: 0.08 = 8 %. */
export interface ClimbPartDto {
    startDistanceM: number;
    endDistanceM: number;
    startElevationM: number;
    endElevationM: number;
    lengthM: number;
    elevationGainM: number;
    grade: number;
}

/** A climb detected on the enhanced path. */
export interface ClimbDto {
    startIndex: number;
    endIndex: number;
    startDistanceM: number;
    endDistanceM: number;
    startElevationM: number;
    endElevationM: number;
    lengthM: number;
    elevationGainM: number;
    /** Dimensionless: 0.08 = 8 %. */
    averageGrade: number;
    /** Dimensionless, counting only the rising sections. */
    climbingGrade: number;
    positiveElevationM: number;
    negativeElevationM: number;
    parts: ClimbPartDto[];
}

export type Path = object;

export const parseGpx: (xml: string) => Path = ns.parseGpx;
/**
 * Which of the path's two power fields lands in `<power>` — the CLI's `--gpx-power-source`.
 * `input` is what the source file said, `computed` what the simulation produced, and
 * `computed-or-input` prefers the simulation and falls back. Omit to keep the engine default
 * (`input`); an unknown value throws rather than silently writing the wrong one.
 */
export type GpxPowerSource = 'input' | 'computed' | 'computed-or-input';

export const writeGpx: (
    path: Path,
    writeExtensions?: boolean,
    powerSource?: GpxPowerSource,
    trackName?: string
) => string = ns.writeGpx;
/**
 * Like `writeGpx`, but stamps every point with an ABSOLUTE `<time>` = `startTimeEpochMs + time(i)`.
 * `writeGpx` emits the path's own relative clock, which after `enhance` starts at 0 — i.e. 1970.
 * Both writers hardcode `trackName = "virtualized"`; the JS facade exposes no name parameter.
 */
export const writeGpxAt: (
    path: Path,
    startTimeEpochMs: number,
    writeExtensions?: boolean,
    powerSource?: GpxPowerSource,
    trackName?: string
) => string = ns.writeGpxAt;
/**
 * Encode the path as a Garmin FIT **Course**. `startTimeEpochMs` is mandatory — FIT has no
 * relative clock, so somebody has to decide the absolute instant.
 *
 * Returns the Kotlin `ByteArray`, which IS a JS `Int8Array` at Kotlin/JS runtime (zero-copy).
 * Throws when the path is empty or its `time` is not monotonic.
 */
export const pathToFit: (path: Path, name: string, startTimeEpochMs: number) => Int8Array =
    ns.pathToFit;
/**
 * Azimuth in degrees of the constant wind that makes this course hardest, ready for the wind
 * direction field. `NaN` when the course is too short or too symmetric to have one.
 */
export const dominantHeadwindAzimuth: (path: Path) => number = ns.dominantHeadwindAzimuth;
export const enhance: (path: Path, options: EnhanceOptionsDto | null) => Promise<Path> = ns.enhance;
export const enhanceWithCourse: (
    path: Path,
    cyclist: CyclistDto | null,
    bike: BikeDto | null,
    wind: WindDto | null,
    power: PowerProviderDto | null,
    options: EnhanceOptionsDto | null
) => Promise<Path> = ns.enhanceWithCourse;
export const pathSize: (path: Path) => number = ns.pathSize;
export const pathTotalDistance: (path: Path) => number = ns.pathTotalDistance;
export const pathDurationMs: (path: Path) => number = ns.pathDurationMs;
export const pathElevationGain: (path: Path) => number = ns.pathElevationGain;
export const pathElevationLoss: (path: Path) => number = ns.pathElevationLoss;
export const pointAt: (path: Path, i: number) => PointDto = ns.pointAt;
export const getField: (path: Path, i: number, fieldProp: string) => number = ns.getField;
export const fieldDefinitions: () => FieldDefinitionDto[] = ns.fieldDefinitions;
export const pathLatitudeDeg: (path: Path, i: number) => number = ns.pathLatitudeDeg;
export const pathLongitudeDeg: (path: Path, i: number) => number = ns.pathLongitudeDeg;
export const detectClimbs: (path: Path) => ClimbDto[] = ns.detectClimbs;
/**
 * Ask what the racing-line stage would build, without building it — read-only, moves nothing.
 * Returns `null` when the path cannot be projected (too short, or too near a pole).
 */
export const analyzeRacingLine: (
    path: Path,
    options?: EnhanceOptionsDto | null
) => RacingLineReportDto | null = ns.analyzeRacingLine;
export const detectClimbsWithOptions: (
    path: Path,
    minMinClimbElevationM: number,
    maxMinClimbElevationM: number,
    minClimbElevationRatio: number,
    minGradePercent: number,
    maxDiffRealGrade: number,
    booster: number
) => ClimbDto[] = ns.detectClimbsWithOptions;
