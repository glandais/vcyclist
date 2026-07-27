/**
 * Shared on-disk dump format for TS <-> Kotlin numeric parity.
 *
 * Two files per pipeline stage:
 *
 *   <stage>.f64   raw IEEE-754 binary64, little-endian, row-major:
 *                 value(point i, field f) is at byte offset (i * fieldCount + f) * 8
 *   <stage>.json  header: stage name, point count, field order, path aggregates
 *
 * Binary (rather than JSON Lines) is deliberate: it round-trips the exact bit pattern of
 * every double with no decimal formatting in the loop, and keeps a 50 k-point stage at
 * ~14 MB instead of ~150 MB. `compare.py` reads both sides with the same struct layout,
 * so a difference in the report is always a difference in the computed value.
 *
 * The Kotlin writer (`DumpFormat.kt`) MUST stay byte-compatible with this file.
 */
import { closeSync, mkdirSync, openSync, writeFileSync, writeSync } from 'node:fs';
import { dirname, join } from 'node:path';

/** Aggregates mirrored from `Path`'s cached statistics (Kotlin `Path` property names). */
export interface DumpHeader {
    stage: string;
    index: number;
    size: number;
    fieldCount: number;
    fields: string[];
    totalDistance: number;
    durationMs: number;
    elevationGain: number;
    elevationLoss: number;
    minElevation: number;
    maxElevation: number;
    /** Epoch offset subtracted from every timestamp at parse time (see runner). */
    timeOrigin: number;
}

/**
 * Write one stage. `read(i, f)` returns the raw stored double for point `i`, field `f`,
 * where `f` indexes `fields` (== PointField ordinal on both sides).
 */
export function writeStage(
    outDir: string,
    header: DumpHeader,
    read: (pointIndex: number, fieldIndex: number) => number
): void {
    mkdirSync(outDir, { recursive: true });

    const stem = `${String(header.index).padStart(2, '0')}-${header.stage}`;
    const binPath = join(outDir, `${stem}.f64`);
    const jsonPath = join(outDir, `${stem}.json`);

    const { size, fieldCount } = header;
    // Chunked write: one 1 MiB-ish buffer reused, so a 100 k-point stage never allocates
    // a 300 MB Buffer.
    const pointsPerChunk = Math.max(1, Math.floor(4096 / fieldCount) * 8);
    const chunk = Buffer.allocUnsafe(pointsPerChunk * fieldCount * 8);

    mkdirSync(dirname(binPath), { recursive: true });
    const fd = openSync(binPath, 'w');
    try {
        for (let start = 0; start < size; start += pointsPerChunk) {
            const count = Math.min(pointsPerChunk, size - start);
            let off = 0;
            for (let i = start; i < start + count; i++) {
                for (let f = 0; f < fieldCount; f++) {
                    chunk.writeDoubleLE(read(i, f), off);
                    off += 8;
                }
            }
            writeSync(fd, chunk, 0, off);
        }
    } finally {
        closeSync(fd);
    }

    writeFileSync(jsonPath, `${JSON.stringify(header, null, 2)}\n`);
}
