// Handing a generated file to the browser. The only place in the demo that touches the
// anchor-click trick, so the object-URL lifetime is managed in exactly one spot.

/** MIME types of what the engine can emit. */
export const GPX_MIME = 'application/gpx+xml';
export const FIT_MIME = 'application/vnd.ant.fit';
export const CSV_MIME = 'text/csv';
export const JSON_MIME = 'application/json';

export function downloadBlob(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    // Load-bearing, not defensive padding: revoking in the same tick as the click cancels the
    // save in Firefox and Safari, which start reading the blob only after click() returns.
    setTimeout(() => URL.revokeObjectURL(url), 0);
}

export function downloadText(text: string, filename: string, mime: string): void {
    downloadBlob(new Blob([text], { type: `${mime};charset=utf-8` }), filename);
}

export function downloadBytes(bytes: Int8Array, filename: string, mime: string): void {
    // A Kotlin/JS ByteArray IS an Int8Array, but it may be a view onto a larger buffer, so
    // `new Blob([bytes.buffer])` would write the whole backing store. Copying through the
    // Uint8Array constructor takes exactly this view's bytes (the signed→unsigned conversion
    // wraps mod 256, so the byte values are preserved) and gives Blob the ArrayBuffer-backed
    // type it insists on.
    downloadBlob(new Blob([new Uint8Array(bytes)], { type: mime }), filename);
}
