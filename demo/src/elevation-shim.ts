// Re-exports the Kotlin/JS elevation façade under flat, ergonomic names, and declares
// TypeScript types matching its `external interface` DTOs (which Kotlin/JS does not emit as
// TS interface bodies — only as referenced names).
//
// Sibling of `engine-shim.ts`, deliberately a separate file: it unwraps a different package
// namespace and mirrors a different Kotlin source. `:engine` declares `api(project(":elevation"))`,
// so the single `@glandais/vcyclist-engine` bundle carries both façades — no extra build wiring.
//
// Source of truth: elevation/src/jsMain/kotlin/io/github/glandais/elevation/ElevationJsApi.kt.
// A rename there is silent until runtime, exactly as for `engine-shim.ts`.
//
// Note the façade does NOT validate its option keys (`EngineJsApi` does, via `requireOnlyKeys`),
// so an unknown key is ignored rather than rejected — these types are the only guard.

import * as engineRaw from '@glandais/vcyclist-engine';

// eslint-disable-next-line @typescript-eslint/no-explicit-any
const ns: any = (engineRaw as any).io?.github?.glandais?.elevation ?? engineRaw;

export interface CoordinatesDto {
    readonly latitude: number;
    readonly longitude: number;
}

export interface CoordinatesElevationDto {
    readonly latitude: number;
    readonly longitude: number;
    readonly elevation: number;
}

/** Distance-based triangular-kernel smoothing of the elevation profile. */
export interface SmoothingOptionsDto {
    readonly enabled: boolean;
    /** Kernel window in metres. */
    readonly windowSize: number;
}

/** Douglas-Peucker 3D simplification of the elevation profile. */
export interface FilterOptionsDto {
    readonly enabled: boolean;
    /** Tolerance in metres. */
    readonly tolerance: number;
    /** Vertical exaggeration applied before the 3D distance test. */
    readonly zExaggeration: number;
}

/**
 * Every field is optional; omit rather than restate a default, they live in `ElevationJsApi.kt`
 * (`step` 10 m, `minDistance` 1 m, `interpolation` true, no smoothing, no filtering).
 */
export interface GetElevationsAlongOptionsDto {
    readonly step?: number;
    readonly minDistance?: number;
    readonly interpolation?: boolean;
    readonly smoothingOptions?: SmoothingOptionsDto;
    readonly filterOptions?: FilterOptionsDto;
}

/** Mirrors a subset of `ElevationProviderConfig`; `attribution` is not overridable from JS. */
export interface ElevationProviderConfigDto {
    readonly zoomLevel?: number;
    readonly cacheSize?: number;
    readonly tileUrlTemplate?: string;
    readonly tileSize?: number;
}

/** Opaque handle — a Kotlin/JS `ElevationProvider` instance, with no TypeScript surface. */
export type ElevationProvider = object;

export const newElevationProvider: (
    config: ElevationProviderConfigDto | null
) => ElevationProvider = ns.newElevationProvider;

/** `interpolation` has no Kotlin-side default — it must always be passed. */
export const getElevation: (
    provider: ElevationProvider,
    latitude: number,
    longitude: number,
    interpolation: boolean
) => Promise<number> = ns.getElevation;

export const getElevationsAlong: (
    provider: ElevationProvider,
    path: CoordinatesDto[],
    options: GetElevationsAlongOptionsDto | null
) => Promise<CoordinatesElevationDto[]> = ns.getElevationsAlong;
