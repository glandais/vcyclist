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

/** One `<wpt>` of the source document. Everything but the coordinates is optional in GPX. */
export interface WaypointDto {
    readonly latitudeDeg: number;
    readonly longitudeDeg: number;
    readonly elevationM: number | null;
    readonly name: string | null;
    readonly description: string | null;
    readonly symbol: string | null;
    readonly type: string | null;
    readonly timeEpochMs: number | null;
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
    /**
     * Scale the reported climbing is measured at. 'raw' is the unfiltered sum, which over-reports;
     * 'barometric' and 'gps' are Strava's documented 2 m and 10 m thresholds; 'dem' (the default)
     * is sized for DEM rather than device noise.
     */
    readonly elevationGainPreset?: 'raw' | 'barometric' | 'dem' | 'gps';
    /** Override the preset's hysteresis dead band, in metres. 0 disables it. */
    readonly elevationGainThresholdM?: number;
    /** Whether to measure the dead-banded climbing at all. Defaults to true. */
    readonly elevationGainEnabled?: boolean;
    /**
     * Triangular-kernel half-width for the elevation smoother, in metres (default 150).
     *
     * The number that decides both the reported climbing and the gradients the simulation rides:
     * it costs a clean route ~1.5 % and a noisy GPS trace ~48 %.
     */
    readonly elevationSmoothWindowM?: number;
    /** Web-Mercator zoom for the DEM lookup, 0..15 (default 12). Only used with fixElevation. */
    readonly demZoom?: number;
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
 * Neither writer takes waypoints: a round trip through them destroys every `<wpt>` of the source
 * file. Use `writeGpxTracks` for that, which is what this demo's download does.
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
/** Dead-banded ascent, or NaN when the elevationGain stage did not run. */
export const pathElevationGainFiltered: (path: Path) => number = ns.pathElevationGainFiltered;
/** Dead-banded descent. NEGATIVE by convention, or NaN. */
export const pathElevationLossFiltered: (path: Path) => number = ns.pathElevationLossFiltered;
/** What to show a human: dead-banded when measured, the raw sum otherwise. */
export const pathReportedElevationGain: (path: Path) => number = ns.pathReportedElevationGain;
/** Counterpart of pathReportedElevationGain. NEGATIVE by convention. */
export const pathReportedElevationLoss: (path: Path) => number = ns.pathReportedElevationLoss;
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
/**
 * `maxAnalysisPoints` bounds the O(n²) candidate search (default 3000); above it the path is
 * decimated for the analysis only. It is the seventh `ClimbOptions` field and reached no door at
 * all until S4 of the surface-alignment work.
 */
export const detectClimbsWithOptions: (
    path: Path,
    minMinClimbElevationM: number,
    maxMinClimbElevationM: number,
    minClimbElevationRatio: number,
    minGradePercent: number,
    maxDiffRealGrade: number,
    booster: number,
    maxAnalysisPoints?: number | null
) => ClimbDto[] = ns.detectClimbsWithOptions;

// ── Multi-track parsing (g24, g29) ───────────────────────────────────────────────────────────

/** Every `<trk>` **and** `<rte>` of the document, in order. `parseGpx` returns only the first. */
export const parseGpxTracks: (xml: string) => Path[] = ns.parseGpxTracks;
/** One Path per `<trkseg>`, across all tracks. Every returned Path is continuous. */
export const parseGpxSegments: (xml: string) => Path[] = ns.parseGpxSegments;
/** Recorded tracks only — `<rte>` routes left out. */
export const parseGpxTracksOnly: (xml: string) => Path[] = ns.parseGpxTracksOnly;
/** Planned routes only — `<trk>` recordings left out. */
export const parseGpxRoutesOnly: (xml: string) => Path[] = ns.parseGpxRoutesOnly;
/**
 * The document's `<wpt>` entries. A `Path` carries none, so these have to be kept beside it and
 * handed back to `writeGpxTracks` — otherwise a load → enhance → download round trip destroys them.
 */
export const parseGpxWaypoints: (xml: string) => WaypointDto[] = ns.parseGpxWaypoints;

// ── Multi-track and tabular output ───────────────────────────────────────────────────────────

/**
 * One `<trk>` per path, with the document's waypoints written before them.
 *
 * `trackNames` names the tracks positionally; a shorter list leaves the rest unnamed, which is
 * NOT the `"virtualized"` default `writeGpx` puts on its single track. `startTimeEpochMs` does
 * what `writeGpxAt` does, to every track at once. Both arrived in S1 of the surface-alignment
 * work — before that this was the one GPX writer that could neither name nor date its output.
 */
export const writeGpxTracks: (
    paths: Path[],
    waypoints?: WaypointDto[],
    writeExtensions?: boolean,
    powerSource?: GpxPowerSource,
    trackNames?: string[],
    startTimeEpochMs?: number
) => string = ns.writeGpxTracks;
/**
 * Every field as CSV, one row per point. `separator` is a single character; `decimals` rounds every
 * value and `lineSeparator` overrides the `\n` line ending. Omit either to keep the engine default.
 */
export const pathToCsv: (
    path: Path,
    separator: string,
    unitsInHeader: boolean,
    decimals?: number | null,
    lineSeparator?: string | null
) => string = ns.pathToCsv;
/**
 * Column-oriented JSON: one array per field, plus `size` and a `fields` map. `includeMeta: false`
 * drops that preamble; `decimals` rounds every value.
 */
export const pathToJson: (
    path: Path,
    pretty: boolean,
    decimals?: number | null,
    includeMeta?: boolean | null
) => string = ns.pathToJson;
/** Every path as one multi-lap FIT course. `interPathGapMs` spaces the laps; 0 butt-joins them. */
export const pathsToFit: (
    paths: Path[],
    name: string,
    startTimeEpochMs: number,
    interPathGapMs?: number
) => Int8Array = ns.pathsToFit;
/** `dominantHeadwindAzimuth` over several paths at once. */
export const dominantHeadwindAzimuthOfTracks: (paths: Path[]) => number =
    ns.dominantHeadwindAzimuthOfTracks;

// ── Declared but not reached by any UI control ───────────────────────────────────────────────
//
// A re-export is NOT a surface crossing: `docs/ledgers/surface-coverage.md`'s Démo column means
// "reachable by a human in the UI", and `writeGpx` sat here unused from g29 until g35 precisely
// because nobody checked. These are bound and typed so a component can use them without touching
// this file, and listed here so nobody mistakes a binding for coverage:
//
//   enhance                       — the view uses `enhanceWithCourse`, which is a superset
//   writeGpx, writeGpxAt          — neither takes waypoints; the download moved to
//                                   `writeGpxTracks` in S2 so the source's `<wpt>` survive
//   pathsToFit                    — the FIT export is single-path; multi-lap has no control
//   parseGpxSegments,
//   parseGpxTracksOnly,
//   parseGpxRoutesOnly            — the track picker uses `parseGpxTracks`, the superset
//   pointAt                       — the chart reads fields in bulk through `getField`
//   dominantHeadwindAzimuth,
//   dominantHeadwindAzimuthOfTracks
//   detectClimbs                  — superseded by `detectClimbsWithOptions`, which the climbs
//                                   panel now drives; kept bound for a caller wanting the
//                                   defaults without naming six numbers
//   pathElevationGainFiltered,
//   pathElevationLossFiltered     — the raw primitives, which return NaN when the elevationGain
//                                   stage did not run. The UI shows
//                                   `pathReportedElevationGain`, which is the same figure with
//                                   the raw sum as a fallback, so a panel would have to render
//                                   "—" for a state the fallback already resolves. A library
//                                   caller that needs to tell "not measured" from "measured"
//                                   apart does want them, which is why they stay exported
