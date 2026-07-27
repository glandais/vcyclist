/**
 * TypeScript side of the elevation (DEM) parity sweep — **needs network**.
 *
 * Mirrors `ElevationDump.kt`: same coordinates, same tiles, same zoom. Decodes WebP with
 * node-canvas (the `elevation` library's Node path) so the comparison isolates the two
 * decoder stacks.
 *
 * Usage (cwd MUST be the elevation/ reference repo):
 *   npx tsx <this> --out <results.json>
 */
import './bootstrap';

import { writeFileSync } from 'node:fs';
import { ElevationProvider } from '../../../../elevation/src/ElevationProvider';

// Keep in lockstep with ELEVATION_COORDS in ElevationDump.kt.
const COORDS: [number, number][] = [
    [45.8326, 6.8652],
    [31.5, 35.5],
    [46.52847, 10.45213],
    [46.5285, 10.45215],
    [46.529, 10.453],
    [46.53, 10.455],
    [0.0, 0.0],
    [-33.9249, 18.4241],
    [64.1466, -21.9426],
    [27.9881, 86.925],
];

function arg(name: string): string {
    const i = process.argv.indexOf(`--${name}`);
    if (i < 0) throw new Error(`missing --${name}`);
    return process.argv[i + 1];
}

async function main(): Promise<void> {
    const provider = new ElevationProvider({ cacheSize: 64 });
    const out: Record<string, number> = {};
    for (let i = 0; i < COORDS.length; i++) {
        const [lat, lon] = COORDS[i];
        out[`elevation.${i}`] = await provider.getElevation(lat, lon);
    }
    writeFileSync(arg('out'), `${JSON.stringify(out, null, 1)}\n`);
    process.stderr.write(`[ts/elevation-live] ${Object.keys(out).length} values\n`);
}

main().catch(err => {
    process.stderr.write(`${err instanceof Error ? err.stack : String(err)}\n`);
    process.exit(1);
});
