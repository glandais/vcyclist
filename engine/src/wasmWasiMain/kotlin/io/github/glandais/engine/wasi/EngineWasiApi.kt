@file:OptIn(UnsafeWasmMemoryApi::class)

package io.github.glandais.engine.wasi

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import kotlin.wasm.WasmExport
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * The vcyclist WASI ABI, **version 1** — the single entry point of the standalone `.wasm`
 * module. Functional sibling of `EngineJsApi` (`engine/src/jsMain`), for hosts that have no
 * JavaScript: wasmtime CLI, wasmtime-py, a Go/Rust/JVM embedding.
 *
 * There is exactly one façade, and it lives in `:engine` because that module sits on top of
 * `:gpx`, `:elevation` and `:fit` — two façades would mean two binaries and two ABIs to keep in
 * step. The `:gpx` POC this replaces (`GpxWasiApi`) is gone.
 *
 * ## The protocol, in full
 *
 * A host needs this section and nothing else — no Kotlin is required to implement it. Task w10
 * turns it into `docs/wasm-wasi-abi.md`; the two must not drift.
 *
 * **Module shape.** No `fun main()`, so the module is a *reactor*: the Wasm `start` section runs
 * the global initialisers at instantiation and there is no `_start` (nor `_initialize`) to call.
 * Instantiate, then call exports.
 *
 * **Imports the host must provide**, in module `"vcyclist"`:
 *
 * | Import | Signature | Contract |
 * |---|---|---|
 * | `read_input` | `(ptr: i32, cap: i32) -> i32` | write up to `cap` bytes of the staged payload at `ptr` in linear memory, return how many were written |
 * | `write_output` | `(ptr: i32, len: i32) -> ()` | copy `len` bytes from `ptr`; the memory is gone when the call returns |
 *
 * Both are **mandatory at instantiation**, even for a host that only calls [vcAbiVersion].
 *
 * **Strings.** UTF-8, always, and never held across a call. Inbound: the host stages the bytes,
 * calls e.g. `vcParseGpx(byteLen)`, and the guest allocates `byteLen` bytes in a scoped arena
 * and pulls them through `read_input`. Outbound: the guest writes into a scoped arena and calls
 * `write_output`, returning the byte length.
 *
 * **Objects** never cross the boundary. They live in a guest-side table and cross as positive
 * `Int` handles, released by [vcRelease] or [vcReleaseAll]. A host that drops a handle without
 * releasing it leaks a `Path` for the lifetime of the instance.
 *
 * **Errors.** An exception cannot cross a Wasm boundary. Every export catches, and returns a
 * negative sentinel — [WasiAbi.ERR_GENERIC] (-1) with a message in [vcLastError],
 * [WasiAbi.ERR_UNKNOWN_HANDLE] (-2), [WasiAbi.ERR_INVALID_ARGUMENT] (-3). Exports returning a
 * `Double` return the same codes as a `Double` (`-1.0`, `-2.0`, …), which is unambiguous because
 * every quantity they carry is non-negative.
 *
 * **Two things a host must not do**, both of which corrupt state rather than fail cleanly:
 *
 * - call any export while inside `read_input` or `write_output` — the scoped allocator forbids
 *   nested scopes and throws;
 * - keep a pointer past the end of the callback that handed it out.
 *
 * ## Versioning
 *
 * [vcAbiVersion] is the first export any host should call, and the only one that is guaranteed
 * never to touch an import — so it answers even before `read_input` is wired to anything real.
 * A host that reads a version it does not know must refuse the module rather than guess.
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
 * The ABI version this module implements — currently 1. Call it first; it never fails and never
 * touches a host import.
 */
@WasmExport
fun vcAbiVersion(): Int = WasiAbi.VERSION

/**
 * Parse a GPX document of [byteLen] UTF-8 bytes (pulled through `read_input`) and build a path
 * from its first track. Returns a positive handle, or a negative error code.
 */
@WasmExport
fun vcParseGpx(byteLen: Int): Int =
    try {
        WasiAbi.invalidLengthOrNull(byteLen) ?: run {
            val doc = GpxParser.parse(readBytesFromHost(byteLen).decodeToString())
            WasiAbi.register(doc.firstTrackAsPath())
        }
    } catch (t: Throwable) {
        WasiAbi.fail(t)
    }

/** Number of points of the path behind [handle], or a negative error code. */
@WasmExport
fun vcPathSize(handle: Int): Int =
    WasiAbi.pathOrNull(handle)?.size
        ?: WasiAbi.fail(WasiAbi.ERR_UNKNOWN_HANDLE, "unknown handle $handle")

/** Total distance in meters of the path behind [handle], or a negative error code as a Double. */
@WasmExport
fun vcPathTotalDistance(handle: Int): Double =
    WasiAbi.pathOrNull(handle)?.totalDistance
        ?: WasiAbi.fail(WasiAbi.ERR_UNKNOWN_HANDLE, "unknown handle $handle").toDouble()

/**
 * Serialise the path behind [handle] to GPX 1.1 XML and push it through `write_output`. Returns
 * the UTF-8 byte length, or a negative error code.
 */
@WasmExport
fun vcWriteGpx(handle: Int): Int =
    try {
        val path = WasiAbi.pathOrNull(handle)
        if (path == null) {
            WasiAbi.fail(WasiAbi.ERR_UNKNOWN_HANDLE, "unknown handle $handle")
        } else {
            writeTextToHost(GpxWriter.write(path))
        }
    } catch (t: Throwable) {
        WasiAbi.fail(t)
    }

/**
 * Push the last error message through `write_output` and return its byte length. Never fails;
 * an empty message means "no error recorded since instantiation".
 */
@WasmExport
fun vcLastError(): Int = writeTextToHost(WasiAbi.lastError)

/** Drop the path behind [handle]. Returns 1 if it existed, 0 otherwise — never an error code. */
@WasmExport
fun vcRelease(handle: Int): Int = WasiAbi.release(handle)

/** Drop every handle and return how many were dropped. For a host reusing one instance. */
@WasmExport
fun vcReleaseAll(): Int = WasiAbi.releaseAll()
