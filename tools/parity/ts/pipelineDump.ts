/**
 * TypeScript side of the end-to-end parity cascade.
 *
 * Re-implements `Enhancer.enhanceCourse` step by step (same calls, same order, same
 * arguments) so the `Path` can be dumped *between* stages. Diverging at stage N is only
 * diagnosable if stage N-1 is known to match.
 *
 * Keep in lockstep with:
 *   - virtual-cyclist/src/enhancer/Enhancer.ts  (the behaviour being mirrored)
 *   - tools/parity/src/main/kotlin/.../PipelineDump.kt  (the Kotlin counterpart)
 *
 * Usage (cwd MUST be virtual-cyclist/, so tsx picks up its tsconfig `paths`):
 *   npx tsx <this> --gpx <file.gpx> --out <dir> [--simplify] [--fix-elevation]
 */
import './bootstrap';

import { readFileSync } from 'node:fs';
import { Enhancer } from '@/enhancer/';
import { GPXParser } from '@/gpx/';
import { MaxSpeedComputer, VirtualizeService } from '@/physics/';
import { DouglasPeucker, PointPerDistance, PointPerSecond } from '@/processing/';
import { CoursePhysics } from '@/types/course/';
import { Path, POINT_FIELDS } from '@/types/path/';
import { Elevation } from '@/elevation/';
import { DumpHeader, writeStage } from './dumpFormat';

const FIELD_NAMES = [
    'latitude', 'longitude', 'distance', 'dx',
    'time', 'elapsed', 'dt',
    'bearing',
    'elevation',
    'grade',
    'radius',
    'aeroCoef',
    'windBearing', 'windAlpha',
    'pAero', 'pGravity', 'pRollingResistance', 'pWheelBearings',
    'pInputPower', 'pCyclistProvidedOptimalPower',
    'pCyclistProvidedOptimalPowerWithHarmonics', 'pCyclistPowerNeeded',
    'pCyclistProvidedMuscular', 'pCyclistProvidedWheel',
    'pComputedTotalPower', 'pComputedWheelPower', 'pComputedPower',
    'speed', 'speedMax', 'speedMaxIncline', 'virtSpeedCurrent',
    'temperature', 'windSpeed', 'windDirection',
    'heartRate', 'cadence',
];

function arg(name: string): string | undefined {
    const i = process.argv.indexOf(`--${name}`);
    return i >= 0 ? process.argv[i + 1] : undefined;
}
function flag(name: string): boolean {
    return process.argv.includes(`--${name}`);
}

/**
 * Simulation start timestamp used by `clock-pinned` mode.
 *
 * `VirtualizeService.ts` defaults its simulation clock to `new Date().getTime()`, so the TS
 * time axis starts around 1.785e12 ms, where float64 quantises to 2.44e-4 ms — about 2.1e6
 * times coarser than Kotlin's axis, which starts at 0. That alone shifts `dt`, `speed` and
 * every power field by ~1e-7 relative from the very first point, and gives `PointPerSecond`
 * a different sub-second phase to snap to. Without pinning, the measurement only ever
 * reproduces the TS clock defect (divergence 1 in docs/parity.md).
 *
 * Since `virtual-cyclist` 1.3.0 (`8532ba0`, *feat: allow custom start time for track
 * virtualization*) this is a **supported parameter** — `virtualizeTrack(course, startTime)`,
 * also reachable as `EnhanceOptions.startTime`. The harness used to monkey-patch `globalThis
 * .Date` instead; that is gone. Nothing ambient is overridden any more, and the pinned mode
 * now exercises the shipped API exactly as a caller would.
 */
const PINNED_START_MS = 0;

let stageIndex = 0;
let timeOrigin = 0;

function dump(outDir: string, stage: string, path: Path): void {
    const size = path.getPointCount();
    let durationMs = 0;
    if (size >= 2) {
        durationMs = path.getField(size - 1, 4 /* TIME */) - path.getField(0, 4 /* TIME */);
    }
    const header: DumpHeader = {
        stage,
        index: stageIndex++,
        size,
        fieldCount: POINT_FIELDS.length,
        fields: FIELD_NAMES,
        totalDistance: path.getTotalDistance(),
        durationMs,
        elevationGain: path.getTotalElevationGain(),
        elevationLoss: path.getTotalElevationLoss(),
        minElevation: size === 0 ? 0 : path.getMinElevation(),
        maxElevation: size === 0 ? 0 : path.getMaxElevation(),
        timeOrigin,
    };
    writeStage(outDir, header, (i, f) => path.getField(i, POINT_FIELDS[f]));
    process.stderr.write(`  [ts] ${String(header.index).padStart(2, '0')}-${stage}: ${size} pts\n`);
}

async function main(): Promise<void> {
    const gpxFile = arg('gpx');
    const outDir = arg('out');
    if (!gpxFile || !outDir) {
        throw new Error('usage: --gpx <file.gpx> --out <dir> [--simplify] [--fix-elevation]');
    }
    const doSimplify = flag('simplify');
    const doFixElevation = flag('fix-elevation');
    const zeroClock = flag('zero-clock');

    // ---- parse ----------------------------------------------------------------
    const parsed = GPXParser.parse(readFileSync(gpxFile, 'utf-8'));
    if (parsed.tracks.length === 0) throw new Error(`no track in ${gpxFile}`);
    let path = parsed.tracks[0];

    // Normalise the time axis to t0 = 0, symmetrically with the Kotlin runner. This is a
    // harness decision, not a pipeline one: it keeps the absolute epoch (~1.7e12 ms) out of
    // the comparison, where it would swamp millisecond-level differences in float64
    // resolution. The offset is recorded in every header so nothing is lost.
    if (path.getPointCount() > 0) {
        timeOrigin = path.getField(0, 4 /* TIME */);
        for (let i = 0; i < path.getPointCount(); i++) {
            path.setField(i, 4 /* TIME */, path.getField(i, 4) - timeOrigin);
        }
        path.computeDerivedData();
    }
    dump(outDir, 'parsed', path);

    // ---- pipeline, mirroring Enhancer.enhanceCourse ----------------------------
    const course: CoursePhysics = Enhancer.getDefaultCourse(path);

    path = PointPerDistance.compute(path, -1.0, 30.0, Enhancer.INPUT_GPX_FIELDS);
    dump(outDir, 'ppd-30', path);

    if (doFixElevation) {
        path = await Elevation.fixElevation(path);
        dump(outDir, 'fixelevation', path);
    }

    path = PointPerDistance.compute(path, 1.0, 2.0, Enhancer.INPUT_GPX_FIELDS);
    dump(outDir, 'ppd-2', path);

    path = await Elevation.smoothElevation(path);
    dump(outDir, 'smooth', path);

    const courseWithPath: CoursePhysics = { ...course, path };
    MaxSpeedComputer.computeMaxSpeeds(courseWithPath); // mutates `path` in place
    dump(outDir, 'maxspeed', path);

    // `undefined` lets the library apply its own `new Date()` default, which is exactly what
    // `as-is` mode is meant to measure.
    path = VirtualizeService.virtualizeTrack(courseWithPath, zeroClock ? PINNED_START_MS : undefined);
    dump(outDir, 'virtualize', path);

    path = PointPerSecond.computeOnePointPerSecond(path);
    dump(outDir, 'pointpersecond', path);

    if (doSimplify) {
        path = DouglasPeucker.simplify(path, 10, 3);
        dump(outDir, 'simplify', path);
    }
}

main().catch(err => {
    process.stderr.write(`${err instanceof Error ? err.stack : String(err)}\n`);
    process.exit(1);
});
