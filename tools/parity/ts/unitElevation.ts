/**
 * TypeScript side of the unit-level parity sweep for the `elevation` library.
 *
 * Reads `cases/units.json`, evaluates each sentinel input, and writes a flat
 * `{ "key": number }` map to stdout. `compare-units.py` diffs it against the Kotlin
 * runner's map. Keys must match `UnitDump.kt` exactly.
 *
 * Usage (cwd MUST be the elevation/ reference repo):
 *   npx tsx <this> --cases <units.json> --out <results.json>
 */
import './bootstrap';

import { readFileSync, writeFileSync } from 'node:fs';

import { Distance } from '../../../../elevation/src/utils/Distance';
import { EcefConverter } from '../../../../elevation/src/utils/EcefConverter';
import { Vector3D } from '../../../../elevation/src/utils/Vector3D';
import { DouglasPeucker } from '../../../../elevation/src/utils/DouglasPeucker';
import { ElevationSmoother } from '../../../../elevation/src/utils/ElevationSmoother';
import {
    toPixel,
    toTileCoordinates,
    toTileCoordinatesFloat,
} from '../../../../elevation/src/calculator/ElevationFunctions';

interface Coord {
    id?: string;
    lat: number;
    lon: number;
    ele: number;
}

function arg(name: string): string {
    const i = process.argv.indexOf(`--${name}`);
    if (i < 0) throw new Error(`missing --${name}`);
    return process.argv[i + 1];
}

const cases = JSON.parse(readFileSync(arg('cases'), 'utf-8'));
const out: Record<string, number> = {};
const co = (c: Coord) => ({ latitude: c.lat, longitude: c.lon, elevation: c.ele });

// --- Distance.haversine, over the declared pairs ---------------------------
const byId = new Map<string, Coord>(
    (cases.coordinates as Coord[]).map(c => [c.id as string, c])
);
for (const [a, b] of cases.coordinatePairs as [string, string][]) {
    out[`haversine.${a}|${b}`] = Distance.haversine(co(byId.get(a)!), co(byId.get(b)!));
}

// --- EcefConverter + Vector3D ---------------------------------------------
for (const c of cases.coordinates as Coord[]) {
    for (const z of [1, 3]) {
        const v = EcefConverter.toEcef(co(c), z);
        out[`ecef.${c.id}.z${z}.x`] = v.x;
        out[`ecef.${c.id}.z${z}.y`] = v.y;
        out[`ecef.${c.id}.z${z}.z`] = v.z;
        out[`ecef.${c.id}.z${z}.magnitude`] = v.magnitude();
    }
}
{
    const pts = (cases.coordinates as Coord[]).map(c => EcefConverter.toEcef(co(c), 3));
    for (let i = 0; i + 2 < pts.length; i++) {
        out[`vec.distanceTo.${i}`] = pts[i].distanceTo(pts[i + 1]);
        out[`vec.dot.${i}`] = pts[i].dot(pts[i + 1]);
        out[`vec.cross.${i}.x`] = pts[i].cross(pts[i + 1]).x;
        out[`vec.distanceToSegment.${i}`] = pts[i + 1].distanceToSegment(pts[i], pts[i + 2]);
        out[`dist.pointToSegment3D.${i}`] = Distance.pointToSegment3D(
            pts[i + 1], pts[i], pts[i + 2]
        );
    }
    const zero = new Vector3D(0, 0, 0);
    out['vec.degenerateSegment'] = zero.distanceToSegment(zero, zero);
    out['vec.zeroMagnitude'] = zero.magnitude();
}

// --- cumulativeDistances ---------------------------------------------------
{
    const pts = (cases.coordinates as Coord[]).map(co);
    const cum = Distance.cumulativeDistances(pts);
    cum.forEach((d, i) => {
        out[`cumulative.${i}`] = d;
    });
}

// --- ElevationSmoother -----------------------------------------------------
for (const sc of cases.smootherCases as { id: string; windowSize: number; points: Coord[] }[]) {
    const res = ElevationSmoother.smooth(sc.points.map(co), sc.windowSize);
    res.forEach((p, i) => {
        out[`smooth.${sc.id}.${i}`] = p.elevation;
    });
}

// --- DouglasPeucker (3D) ---------------------------------------------------
for (const dc of cases.douglasPeuckerCases as {
    id: string; tolerance: number; zExaggeration: number; points: Coord[];
}[]) {
    const res = DouglasPeucker.simplify(dc.points.map(co), dc.tolerance, dc.zExaggeration);
    out[`dp.${dc.id}.count`] = res.length;
    res.forEach((p, i) => {
        out[`dp.${dc.id}.${i}.lat`] = p.latitude;
        out[`dp.${dc.id}.${i}.lon`] = p.longitude;
        out[`dp.${dc.id}.${i}.ele`] = p.elevation ?? 0;
    });
}

// --- ElevationFunctions: lat/lon -> tile / pixel ---------------------------
// Keyed by case index, not by the coordinate values: JS and Kotlin disagree on the
// decimal rendering of a double near 1e-6 ("-0.000001" vs "-1.0E-6"), which would show up
// as a spurious key mismatch rather than as the numeric comparison we actually want.
(cases.tileCases as {
    lat: number; lon: number; zoom: number; tileSize: number;
}[]).forEach((tc, ti) => {
    const key = `tile.${ti}.z${tc.zoom}`;
    const c = { latitude: tc.lat, longitude: tc.lon };
    const f = toTileCoordinatesFloat(c, tc.zoom);
    out[`${key}.xFloat`] = f.xFloat;
    out[`${key}.yFloat`] = f.yFloat;
    const t = toTileCoordinates(c, tc.zoom);
    out[`${key}.x`] = t.x;
    out[`${key}.y`] = t.y;
    const p = toPixel(c, tc.zoom, tc.tileSize);
    out[`${key}.px`] = p.x;
    out[`${key}.py`] = p.y;
});

// --- Terrarium decode ------------------------------------------------------
// Formula lives inside the tile decoders on both sides; replicated here from the
// documented encoding so the two ports are compared on the same arithmetic.
for (const [r, g, b] of cases.terrariumCases as number[][]) {
    out[`terrarium.${r}_${g}_${b}`] = r * 256 + g + b / 256 - 32768;
}

writeFileSync(arg('out'), `${JSON.stringify(out, null, 1)}\n`);
process.stderr.write(`[ts/elevation] ${Object.keys(out).length} values\n`);
