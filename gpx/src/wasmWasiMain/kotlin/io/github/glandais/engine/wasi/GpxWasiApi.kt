@file:OptIn(UnsafeWasmMemoryApi::class)

package io.github.glandais.engine.wasi

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.path.Path
import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * POC numeric façade for the standalone WASI reactor module (see docs/tasks/w01 for the full
 * design this is a proof-of-concept slice of).
 *
 * ABI shape — the **callback protocol**, which needs no allocator that outlives an export call
 * (and therefore no `@ComponentModelInternalApi`):
 *
 * - Strings **in**: the host calls `vcParseGpx(byteLen)`; the guest allocates `byteLen` bytes in
 *   a scoped arena and calls the `vcyclist.read_input(ptr, cap)` import, during which the host
 *   writes the UTF-8 payload into linear memory at `ptr`. The bytes are copied onto the WasmGC
 *   heap before the scope closes.
 * - Strings **out**: the guest writes UTF-8 into a scoped arena and calls
 *   `vcyclist.write_output(ptr, len)`, during which the host must copy them out.
 * - The host must NOT call back into any module export while inside either callback: the scoped
 *   allocator forbids nested scopes.
 * - Objects never cross the boundary: they live in [handles] and cross as `Int` keys.
 * - Exceptions cannot cross a Wasm boundary, so every export catches and returns a negative
 *   sentinel; the message is retrievable via [vcLastError].
 */

@WasmImport("vcyclist", "read_input")
private external fun readInput(
    ptr: Int,
    cap: Int,
): Int

@WasmImport("vcyclist", "write_output")
private external fun writeOutput(
    ptr: Int,
    len: Int,
)

private val handles = HashMap<Int, Path>()
private var nextHandle = 1
private var lastError = ""

/** Reads [byteLen] bytes from the host via `read_input` into a fresh [ByteArray]. */
private fun readBytesFromHost(byteLen: Int): ByteArray {
    val bytes = ByteArray(byteLen)
    withScopedMemoryAllocator { allocator ->
        val buf = allocator.allocate(byteLen)
        val got = readInput(buf.address.toInt(), byteLen)
        check(got == byteLen) { "host wrote $got bytes, expected $byteLen" }
        for (i in 0 until byteLen) {
            bytes[i] = (buf + i).loadByte()
        }
    }
    return bytes
}

/** Sends [text] to the host as UTF-8 via `write_output`; returns the byte length. */
private fun writeTextToHost(text: String): Int {
    val bytes = text.encodeToByteArray()
    withScopedMemoryAllocator { allocator ->
        val buf = allocator.allocate(bytes.size)
        for (i in bytes.indices) {
            (buf + i).storeByte(bytes[i])
        }
        writeOutput(buf.address.toInt(), bytes.size)
    }
    return bytes.size
}

/**
 * Parse a GPX document of [byteLen] UTF-8 bytes (pulled via `read_input`) and build a [Path]
 * from its first track. Returns a positive path handle, or -1 on failure (see [vcLastError]).
 */
@WasmExport
fun vcParseGpx(byteLen: Int): Int =
    try {
        val doc = GpxParser.parse(readBytesFromHost(byteLen).decodeToString())
        val path = doc.firstTrackAsPath()
        val handle = nextHandle++
        handles[handle] = path
        handle
    } catch (t: Throwable) {
        lastError = t.message ?: t::class.simpleName ?: "unknown error"
        -1
    }

/** Number of points of the path behind [handle], or -1 for an unknown handle. */
@WasmExport
fun vcPathSize(handle: Int): Int = handles[handle]?.size ?: -1

/** Total distance in meters of the path behind [handle], or NaN for an unknown handle. */
@WasmExport
fun vcPathTotalDistance(handle: Int): Double = handles[handle]?.totalDistance ?: Double.NaN

/**
 * Serialise the path behind [handle] back to GPX 1.1 XML and push it to the host via
 * `write_output`. Returns the UTF-8 byte length, or -1 on failure.
 */
@WasmExport
fun vcWriteGpx(handle: Int): Int =
    try {
        val path = handles[handle] ?: error("unknown handle $handle")
        writeTextToHost(GpxWriter.write(path))
    } catch (t: Throwable) {
        lastError = t.message ?: t::class.simpleName ?: "unknown error"
        -1
    }

/** Push the last error message to the host via `write_output`; returns its byte length. */
@WasmExport
fun vcLastError(): Int = writeTextToHost(lastError)

/** Drop the path behind [handle]. Returns 1 if it existed, 0 otherwise. */
@WasmExport
fun vcRelease(handle: Int): Int = if (handles.remove(handle) != null) 1 else 0
