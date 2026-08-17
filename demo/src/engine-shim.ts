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
}

export interface BikeDto {
    readonly crr: number;
    readonly inertiaFront: number;
    readonly inertiaRear: number;
    readonly wheelRadiusM: number;
    readonly efficiency: number;
}

export interface WindDto {
    readonly windSpeed: number; // m/s
    readonly windDirection: number; // degrees, 0 = N, 90 = E — direction the wind blows toward
}

export interface PowerProviderDto {
    readonly type: 'constant' | 'durability' | 'from_data';
    readonly power?: number; // W
    readonly useHarmonics?: boolean;
    /** Critical power (W), `durability` only — power fades with work accumulated above it. */
    readonly criticalPower?: number;
}

export interface EnhanceOptionsDto {
    readonly fixElevation?: boolean;
    readonly computeMaxSpeeds?: boolean;
    readonly virtualizeTrack?: boolean;
    readonly computeOnePointPerSecond?: boolean;
    readonly simplifyEnabled?: boolean;
    readonly simplifyToleranceM?: number;
    readonly simplifyZExaggeration?: number;
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
export const writeGpx: (path: Path, writeExtensions?: boolean) => string = ns.writeGpx;
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
export const detectClimbsWithOptions: (
    path: Path,
    minMinClimbElevationM: number,
    maxMinClimbElevationM: number,
    minClimbElevationRatio: number,
    minGradePercent: number,
    maxDiffRealGrade: number,
    booster: number
) => ClimbDto[] = ns.detectClimbsWithOptions;
