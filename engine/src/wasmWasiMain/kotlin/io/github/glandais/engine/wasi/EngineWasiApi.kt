@file:OptIn(UnsafeWasmMemoryApi::class, ExperimentalWasmInterop::class)

package io.github.glandais.engine.wasi

import io.github.glandais.elevation.ElevationProvider
import io.github.glandais.elevation.ElevationProviderConfig
import io.github.glandais.elevation.HostTileSource
import io.github.glandais.elevation.hostTileFetcher
import io.github.glandais.engine.Course
import io.github.glandais.engine.CoursePhysics
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.Enhancer
import io.github.glandais.engine.climb.ClimbDetector
import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxPathKind
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.segmentsAsPaths
import io.github.glandais.engine.gpx.toGpxDocument
import io.github.glandais.engine.gpx.tracksAsPaths
import io.github.glandais.engine.io.CsvWriter
import io.github.glandais.engine.io.JsonWriter
import io.github.glandais.engine.path.Path
import io.github.glandais.engine.path.PointField
import io.github.glandais.engine.path.dominantHeadwindAzimuthDeg
import io.github.glandais.engine.physics.AeroProviderConstant
import io.github.glandais.engine.physics.RhoProviderEstimate
import io.github.glandais.engine.trajectory.RacingLine
import io.github.glandais.fit.toFitBytes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.wasm.ExperimentalWasmInterop
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
 * turns it into `docs/guides/wasm-wasi-abi.md`; the two must not drift.
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
 * [WasiAbi.ERR_UNKNOWN_HANDLE] (-2), [WasiAbi.ERR_INVALID_ARGUMENT] (-3),
 * [WasiAbi.ERR_UNSUPPORTED] (-4). Exports returning a `Double` return the same codes as a
 * `Double` (`-1.0`, `-2.0`, …), which is unambiguous because every quantity they carry is
 * non-negative — except [vcDominantHeadwindAzimuth], where the whole azimuth circle is a valid
 * answer and the sentinel is `NaN`, exactly as on the JS side.
 *
 * **Options** travel as one UTF-8 JSON object per call, pulled the same way as any other input:
 * pass its byte length, or `0` for "all defaults". The field names are the JS DTOs' field names
 * (see `WasiOptions.kt`), and an **unknown field is an error**, not a shrug — a typo in
 * `massKg` must not silently simulate the default rider.
 *
 * **Bulk reads.** One export call per point per field is unusable on a 50 000-point trace, so
 * [vcPathFieldBytes] pushes a whole field as raw little-endian `f64`s — `8 × vcPathSize` bytes,
 * no conversion needed since Wasm memory is little-endian. That is the only viable way to draw a
 * profile.
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
 *
 * Version 1 is **sealed by the first publication** (task w07), not by the day it was written:
 * w03 fixed its shape, w04 filled in its surface, and `vcWriteGpx` gained its options argument
 * in between. Nothing outside this repository has consumed it yet. After w07, any change of this
 * kind bumps the number.
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
 * Sends [bytes] to the host verbatim via `write_output`; returns the byte length. The raw
 * counterpart of [writeTextToHost], used by [vcPathFieldBytes].
 */
private fun writeBytesToHost(bytes: ByteArray): Int {
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
 * Pull an options object of [jsonLen] bytes from the host, or `null` when [jsonLen] is 0.
 *
 * `0` meaning "defaults" rather than "empty object" is what lets every options-taking export
 * keep a single signature: a host with nothing to say passes 0 and never builds a payload.
 */
private fun readOptions(jsonLen: Int): JsonObj? {
    if (jsonLen == 0) return null
    require(jsonLen > 0) { "options length must not be negative, was $jsonLen" }
    return parseJsonObject(readBytesFromHost(jsonLen).decodeToString())
}

/**
 * Run [block] and map anything it throws to the right sentinel.
 *
 * The ordering of the catches **is** the error taxonomy: an unknown handle and a bad argument
 * are both `IllegalArgumentException`-shaped mistakes by the host, but they carry different
 * codes, so the specific one is caught first. Getting this wrong is not academic — the first
 * version funnelled unknown handles into `ERR_GENERIC` and a w03 test caught it.
 */
private inline fun guarded(block: () -> Int): Int =
    try {
        block()
    } catch (e: UnknownHandleException) {
        WasiAbi.fail(WasiAbi.ERR_UNKNOWN_HANDLE, e.message ?: "unknown handle")
    } catch (e: IllegalArgumentException) {
        WasiAbi.fail(WasiAbi.ERR_INVALID_ARGUMENT, e.message ?: "invalid argument")
    } catch (t: Throwable) {
        WasiAbi.fail(t)
    }

/** [guarded] for the exports that answer with a `Double`. */
private inline fun guardedDouble(block: () -> Double): Double =
    try {
        block()
    } catch (e: UnknownHandleException) {
        WasiAbi.fail(WasiAbi.ERR_UNKNOWN_HANDLE, e.message ?: "unknown handle").toDouble()
    } catch (e: IllegalArgumentException) {
        WasiAbi.fail(WasiAbi.ERR_INVALID_ARGUMENT, e.message ?: "invalid argument").toDouble()
    } catch (t: Throwable) {
        WasiAbi.fail(t).toDouble()
    }

/** The path behind [handle], or an exception carrying the unknown-handle code. */
private fun requirePath(handle: Int): Path = WasiAbi.pathOrNull(handle) ?: throw UnknownHandleException(handle)

/** The path list behind [handle], or an exception carrying the unknown-handle code. */
private fun requireList(handle: Int): List<Path> = WasiAbi.listOrNull(handle) ?: throw UnknownHandleException(handle)

/**
 * Thrown internally so the handle checks read as ordinary code; [guarded] turns it back into
 * [WasiAbi.ERR_UNKNOWN_HANDLE]. It never escapes the module — nothing throws across a Wasm
 * boundary.
 */
private class UnknownHandleException(
    handle: Int,
) : RuntimeException("unknown handle $handle")

private fun unknownHandleOr(t: Throwable): Int =
    if (t is UnknownHandleException) {
        WasiAbi.fail(WasiAbi.ERR_UNKNOWN_HANDLE, t.message ?: "unknown handle")
    } else {
        WasiAbi.fail(t)
    }

// ── Version, errors, handles ─────────────────────────────────────────────────────────────────

/**
 * The ABI version this module implements — currently 1. Call it first; it never fails and never
 * touches a host import.
 */
@WasmExport
fun vcAbiVersion(): Int = WasiAbi.VERSION

/**
 * Push the last error message through `write_output` and return its byte length. Never fails;
 * an empty message means "no error recorded since instantiation".
 */
@WasmExport
fun vcLastError(): Int = writeTextToHost(WasiAbi.lastError)

/** Drop the object behind [handle], path or list. Returns 1 if it existed, 0 otherwise. */
@WasmExport
fun vcRelease(handle: Int): Int = WasiAbi.release(handle)

/** Drop every handle and return how many were dropped. For a host reusing one instance. */
@WasmExport
fun vcReleaseAll(): Int = WasiAbi.releaseAll()

// ── Parsing ──────────────────────────────────────────────────────────────────────────────────

/**
 * Parse a GPX document of [byteLen] UTF-8 bytes (pulled through `read_input`) and build a path
 * from its first track — `parseGpx`. Returns a positive handle, or a negative error code.
 */
@WasmExport
fun vcParseGpx(byteLen: Int): Int =
    guarded {
        WasiAbi.invalidLengthOrNull(byteLen) ?: run {
            val doc = GpxParser.parse(readBytesFromHost(byteLen).decodeToString())
            WasiAbi.register(doc.firstTrackAsPath())
        }
    }

/**
 * Parse a GPX document into **several** paths and return one list handle — the four multi-path
 * `parseGpx*` functions of the JS façade, selected by [mode]:
 *
 * | mode | JS equivalent | meaning |
 * |---|---|---|
 * | 0 | `parseGpxTracks` | one path per `<trk>` **and** per `<rte>` (task g24) |
 * | 1 | `parseGpxSegments` | one path per `<trkseg>`, every path continuous |
 * | 2 | `parseGpxTracksOnly` | `<trk>` only |
 * | 3 | `parseGpxRoutesOnly` | `<rte>` only |
 *
 * Walk the result with [vcListSize] and [vcListGet]. An unknown mode is
 * [WasiAbi.ERR_INVALID_ARGUMENT] rather than a silent fallback to 0.
 */
@WasmExport
fun vcParseGpxMulti(
    byteLen: Int,
    mode: Int,
): Int =
    guarded {
        WasiAbi.invalidLengthOrNull(byteLen) ?: run {
            val doc = GpxParser.parse(readBytesFromHost(byteLen).decodeToString())
            val paths =
                when (mode) {
                    0 -> doc.tracksAsPaths()
                    1 -> doc.segmentsAsPaths()
                    2 -> doc.tracksAsPaths(kinds = setOf(GpxPathKind.TRACK))
                    3 -> doc.tracksAsPaths(kinds = setOf(GpxPathKind.ROUTE))
                    else -> throw IllegalArgumentException("unknown parse mode $mode — expected 0, 1, 2 or 3")
                }
            WasiAbi.registerList(paths)
        }
    }

/** Every `<wpt>` of a GPX document, as a JSON array of `WaypointDto`-shaped objects. */
@WasmExport
fun vcParseGpxWaypointsJson(byteLen: Int): Int =
    guarded {
        WasiAbi.invalidLengthOrNull(byteLen) ?: run {
            val doc = GpxParser.parse(readBytesFromHost(byteLen).decodeToString())
            writeTextToHost(jsonArray(doc.waypoints.map(::waypointJson)))
        }
    }

/** Number of paths behind the list [handle], or a negative error code. */
@WasmExport
fun vcListSize(handle: Int): Int = guarded { requireList(handle).size }

/**
 * Register the path at [index] of the list behind [handle] and return **its own** handle.
 *
 * Calling it twice for the same index yields two handles onto the same path: a handle is a
 * reference, not a copy, and releasing one does not disturb the other.
 */
@WasmExport
fun vcListGet(
    handle: Int,
    index: Int,
): Int =
    guarded {
        val paths = requireList(handle)
        require(index in paths.indices) { "index $index out of bounds for a list of ${paths.size}" }
        WasiAbi.register(paths[index])
    }

// ── Path metrics ─────────────────────────────────────────────────────────────────────────────

/** Number of points of the path behind [handle], or a negative error code. */
@WasmExport
fun vcPathSize(handle: Int): Int = guarded { requirePath(handle).size }

/** Total distance in meters, or a negative error code as a `Double`. */
@WasmExport
fun vcPathTotalDistance(handle: Int): Double = guardedDouble { requirePath(handle).totalDistance }

/** Duration in milliseconds, or a negative error code as a `Double`. */
@WasmExport
fun vcPathDurationMs(handle: Int): Double = guardedDouble { requirePath(handle).durationMs }

/** Cumulated ascent in meters, or a negative error code as a `Double`. */
@WasmExport
fun vcPathElevationGain(handle: Int): Double = guardedDouble { requirePath(handle).elevationGain }

/** Cumulated descent in meters, or a negative error code as a `Double`. */
@WasmExport
fun vcPathElevationLoss(handle: Int): Double = guardedDouble { requirePath(handle).elevationLoss }

/** Latitude of point [i] in **degrees** (the path stores radians). */
@WasmExport
fun vcPathLatitudeDeg(
    handle: Int,
    i: Int,
): Double = guardedDouble { requirePath(handle).latitudeDeg(requireIndex(requirePath(handle), i)) }

/** Longitude of point [i] in **degrees**. */
@WasmExport
fun vcPathLongitudeDeg(
    handle: Int,
    i: Int,
): Double = guardedDouble { requirePath(handle).longitudeDeg(requireIndex(requirePath(handle), i)) }

private fun requireIndex(
    path: Path,
    i: Int,
): Int {
    require(i in 0 until path.size) { "point index $i out of bounds for a path of ${path.size}" }
    return i
}

// ── Fields ───────────────────────────────────────────────────────────────────────────────────

/**
 * The 43-entry `PointField` catalog as JSON — `fieldDefinitions`, plus the `index` that
 * [vcGetField] and [vcPathFieldBytes] take. A host reads this once and never hard-codes the list.
 */
@WasmExport
fun vcFieldDefinitionsJson(): Int = guarded { writeTextToHost(fieldDefinitionsJson()) }

/**
 * Field [fieldIndex] at point [pointIndex] — `getField`, by index rather than by name, since
 * passing a string through linear memory would cost more than the read itself.
 */
@WasmExport
fun vcGetField(
    handle: Int,
    fieldIndex: Int,
    pointIndex: Int,
): Double =
    guardedDouble {
        val path = requirePath(handle)
        path.get(requireIndex(path, pointIndex), requireField(fieldIndex))
    }

/**
 * Push the whole of field [fieldIndex] as raw little-endian `f64`s and return the byte length —
 * `8 × vcPathSize`, or a negative error code.
 *
 * This is the export that makes the ABI usable: reading a 50 000-point profile one
 * [vcGetField] call at a time is 50 000 crossings of the boundary. Wasm memory is little-endian,
 * so a host on any mainstream architecture can reinterpret the bytes as a `f64` array with no
 * conversion at all (`numpy.frombuffer`, `Float64Array`, `binary.LittleEndian`).
 */
@WasmExport
fun vcPathFieldBytes(
    handle: Int,
    fieldIndex: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val field = requireField(fieldIndex)
        val bytes = ByteArray(path.size * Double.SIZE_BYTES)
        var offset = 0
        for (i in 0 until path.size) {
            var bits = path.get(i, field).toRawBits()
            repeat(Double.SIZE_BYTES) {
                bytes[offset++] = (bits and 0xFF).toByte()
                bits = bits ushr 8
            }
        }
        writeBytesToHost(bytes)
    }

private fun requireField(fieldIndex: Int): PointField {
    require(fieldIndex in PointField.entries.indices) {
        "field index $fieldIndex out of bounds — see vcFieldDefinitionsJson (${PointField.entries.size} fields)"
    }
    return PointField.entries[fieldIndex]
}

/** One point as a `PointDto`-shaped JSON object — `pointAt`. */
@WasmExport
fun vcPointJson(
    handle: Int,
    i: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        writeTextToHost(pointJson(path, requireIndex(path, i)))
    }

// ── Simulation ───────────────────────────────────────────────────────────────────────────────

/**
 * Run the enhancement pipeline on the path behind [handle] and return a **new** handle — the
 * input path is left untouched, as on the JS side.
 *
 * [optionsJsonLen] is the byte length of an `EnhanceOptionsDto`-shaped object, or 0 for the
 * defaults (no elevation fetch, no 1 Hz resample, no simplify).
 *
 * `fixElevation: true` currently fails: there is no elevation provider under WASI until a host
 * injects one (task w05). It fails loudly rather than quietly skipping the step, because a
 * silently unfixed elevation is a plausible-looking wrong simulation.
 */
@WasmExport
fun vcEnhance(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val options = readOptions(optionsJsonLen).toEnhanceOptions()
        WasiAbi.register(
            runSynchronously {
                Enhancer.enhanceCourseDefault(
                    path,
                    elevationProvider = elevationProviderFor(options),
                    options = options,
                )
            },
        )
    }

/**
 * [vcEnhance] with a full physics course. The payload is one JSON object with up to five
 * sub-objects — `cyclist`, `bike`, `wind`, `power`, `options` — each shaped like the matching JS
 * DTO and each optional.
 *
 * ```json
 * { "cyclist": {"massKg": 72},
 *   "wind": {"windSpeed": 5, "windDirection": 270},
 *   "power": {"type": "constant", "power": 220},
 *   "options": {"computeOnePointPerSecond": true} }
 * ```
 */
@WasmExport
fun vcEnhanceWithCourse(
    handle: Int,
    payloadJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val payload = readOptions(payloadJsonLen)
        payload?.requireOnly(setOf("cyclist", "bike", "wind", "power", "options"))
        val options = payload?.obj("options").toEnhanceOptions()
        val course =
            CoursePhysics(
                course =
                    Course(
                        path = path,
                        cyclist = payload?.obj("cyclist").toCyclist(),
                        bike = payload?.obj("bike").toBike(),
                    ),
                rhoProvider = RhoProviderEstimate,
                aeroProvider = AeroProviderConstant,
                windProvider = payload?.obj("wind").toWindProvider(),
                cyclistPowerProvider = payload?.obj("power").toCyclistPowerProvider(),
            )
        WasiAbi.register(
            runSynchronously { Enhancer.enhanceCourse(course, options, elevationProviderFor(options)) },
        )
    }

/**
 * The provider `fixElevation` runs on, or `null` when the caller did not ask for it.
 *
 * Built on [HostTileSource] rather than on the library default: under WASI the tiles come from
 * the host through the `fetch_tile` import (task w05), so the URL template is the host one and
 * the fetcher reads through the import. `elevationConfig` is whatever the host last set with
 * [vcSetElevationConfig], defaults included.
 *
 * Allocating a provider only when asked matches the JS façade, and keeps the tile cache out of
 * the way of hosts that never fix elevations.
 */
private fun elevationProviderFor(options: EnhanceOptions): ElevationProvider? =
    if (options.fixElevation) ElevationProvider(elevationConfig, fetcher = hostTileFetcher()) else null

// ── Elevation, served by the host ────────────────────────────────────────────────────────────

/**
 * The tile configuration `fixElevation` uses, replaced wholesale by [vcSetElevationConfig].
 *
 * The URL template is not the library's `https://tiles.mapterhorn.com/…`: on this target the
 * host serves tiles through the `fetch_tile` import, and [HostTileSource.URL_TEMPLATE] is the
 * shape `TileManager` builds and the fetcher immediately parses back.
 */
private var elevationConfig: ElevationProviderConfig =
    ElevationProviderConfig(tileUrlTemplate = HostTileSource.URL_TEMPLATE)

/**
 * Configure DEM tiles: `{"zoomLevel":12,"tileSize":512,"cacheSize":100,"tileFormat":"rgba"}`,
 * any subset, `0` for the defaults. Returns [WasiAbi.VERSION] on success, or a negative code.
 *
 * `tileFormat` says what the host writes in `fetch_tile`. `"rgba"` (the default) is decoded
 * pixels, the original contract. `"webp"` means the **bytes as downloaded**: since task w11 the
 * module carries its own lossless-WebP decoder, so a host in that mode needs an HTTP client and
 * nothing else — no image library. Anything else is [WasiAbi.ERR_INVALID_ARGUMENT].
 *
 * Sticky, and read at the next `fixElevation`. Separate from the enhance options on purpose:
 * those mirror the JS `EnhanceOptionsDto` field for field, and hanging a tile configuration off
 * them would break that correspondence for something a host sets once per session, not per call.
 *
 * Setting `tileSize` also changes the `cap` the guest asks `fetch_tile` for — read the result
 * back with [vcTileGeometryJson] rather than assuming it.
 */
@WasmExport
fun vcSetElevationConfig(jsonLen: Int): Int =
    guarded {
        val json = readOptions(jsonLen)
        json?.requireOnly(setOf("zoomLevel", "tileSize", "cacheSize", "tileFormat"))
        val format =
            when (val requested = json?.string("tileFormat", "rgba") ?: "rgba") {
                "rgba" -> HostTileSource.TileFormat.RGBA
                "webp" -> HostTileSource.TileFormat.WEBP
                else -> throw IllegalArgumentException(
                    "unknown tileFormat '$requested' — expected rgba (decoded pixels) or webp (the file as downloaded)",
                )
            }
        val defaults = ElevationProviderConfig()
        val config =
            ElevationProviderConfig(
                zoomLevel = (json?.double("zoomLevel", defaults.zoomLevel.toDouble()) ?: defaults.zoomLevel.toDouble()).toInt(),
                cacheSize = (json?.double("cacheSize", defaults.cacheSize.toDouble()) ?: defaults.cacheSize.toDouble()).toInt(),
                tileSize = (json?.double("tileSize", defaults.tileSize.toDouble()) ?: defaults.tileSize.toDouble()).toInt(),
                tileUrlTemplate = HostTileSource.URL_TEMPLATE,
            )
        // `ElevationProvider`'s init block is the one place that validates zoom / cache / size,
        // so build one now: a host must learn about a bad configuration here, not three calls
        // later from inside `vcEnhance`.
        ElevationProvider(config, fetcher = hostTileFetcher())
        elevationConfig = config
        HostTileSource.tileSize = config.tileSize
        HostTileSource.tileFormat = format
        WasiAbi.VERSION
    }

/**
 * What the host must be ready to write when `fetch_tile` is called:
 *
 * ```json
 * {"tileSize":512,"bytesPerPixel":4,"expectedBytes":1048576,"layout":"RGBA",
 *  "encoding":"terrarium","zoomLevel":12,"tileFormat":"rgba"}
 * ```
 *
 * `expectedBytes` also arrives as the `cap` argument of every `fetch_tile` call, so a host can
 * simply trust `cap`; this export exists so it can allocate once, up front, and check that it and
 * the guest agree.
 *
 * In `"webp"` mode `expectedBytes` is the size of the *buffer offered*, not of the answer: the
 * host writes however many bytes the compressed file has and returns that count. `layout` and
 * `encoding` then describe what the module will decode the file into, not what the host sends.
 */
@WasmExport
fun vcTileGeometryJson(): Int =
    guarded {
        writeTextToHost(
            jsonObject(
                "tileSize" to HostTileSource.tileSize.toString(),
                "bytesPerPixel" to HostTileSource.BYTES_PER_PIXEL.toString(),
                "expectedBytes" to HostTileSource.tileBytes.toString(),
                "layout" to jsonString("RGBA"),
                "encoding" to jsonString("terrarium"),
                "zoomLevel" to elevationConfig.zoomLevel.toString(),
                "tileFormat" to jsonString(HostTileSource.tileFormat.name.lowercase()),
            ),
        )
    }

// ── Climbs and wind ──────────────────────────────────────────────────────────────────────────

/**
 * Detected climbs as a JSON array of `ClimbDto`-shaped objects — `detectClimbs` and
 * `detectClimbsWithOptions` in one export, the six scalars of the latter becoming the fields of
 * the options object.
 */
@WasmExport
fun vcDetectClimbsJson(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val options = readOptions(optionsJsonLen).toClimbOptions()
        writeTextToHost(climbsJson(ClimbDetector.detect(path, options)))
    }

/**
 * The racing line the stage *would* build, as a `RacingLineReportDto`-shaped JSON object — the
 * WASI counterpart of `analyzeRacingLine`.
 *
 * Read-only: it never moves a coordinate, which is the whole point. The stage rewrites every
 * position in a rider's file, so being able to ask what it would do without applying it is what
 * makes an opt-in feature inspectable rather than merely optional.
 *
 * Options are the `EnhanceOptionsDto` shape, of which only the `racingLine*` and `curvature*`
 * keys matter here; `0` for the defaults. Emits the JSON literal `null` when the path cannot be
 * projected — too short, non-finite coordinates, or too near a pole — mirroring the `null` the JS
 * façade returns. That is a successful call answering "no", not an error, so it returns the
 * length of `null` rather than a negative code.
 */
@WasmExport
fun vcAnalyzeRacingLineJson(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val options = readOptions(optionsJsonLen).toEnhanceOptions().racingLine
        val report = RacingLine.analyze(path, options)
        writeTextToHost(if (report == null) "null" else racingLineReportJson(report))
    }

/**
 * Azimuth in degrees of the constant wind that makes the path hardest on average (task g31).
 *
 * The odd one out for errors: every azimuth including 0 is a valid answer, so this returns
 * `NaN` — for an unknown handle as well as for a course with no answer (fewer than 4 points, a
 * symmetric loop). The reason is in [vcLastError]. Feed the value straight back into a `wind`
 * object's `windDirection`; no flip, no conversion.
 */
@WasmExport
fun vcDominantHeadwindAzimuth(handle: Int): Double =
    try {
        requirePath(handle).dominantHeadwindAzimuthDeg()
    } catch (t: Throwable) {
        unknownHandleOr(t)
        Double.NaN
    }

/** Multi-path form of [vcDominantHeadwindAzimuth], over a list handle. Tracks weigh equally. */
@WasmExport
fun vcDominantHeadwindAzimuthOfTracks(handle: Int): Double =
    try {
        requireList(handle).dominantHeadwindAzimuthDeg()
    } catch (t: Throwable) {
        unknownHandleOr(t)
        Double.NaN
    }

// ── Serialisation ────────────────────────────────────────────────────────────────────────────

/**
 * Serialise the path behind [handle] as a single-track GPX 1.1 document and push it through
 * `write_output`. Returns the UTF-8 byte length, or a negative error code.
 *
 * Options (`0` for defaults): `writeExtensions` (default `true`; `false` drops power, heart
 * rate, cadence, temperature and the `gpxtpx` namespace — task g23), `trackName`, and
 * `startTimeEpochMs`, whose presence turns relative times into absolute ones, which is what
 * `writeGpxAt` does on the JS side.
 */
@WasmExport
fun vcWriteGpx(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val options = readOptions(optionsJsonLen).toWriteGpxOptions()
        val document =
            if (options.startTimeEpochMs == null) {
                path.toGpxDocument(trackName = options.trackName)
            } else {
                path.toGpxDocument(
                    trackName = options.trackName,
                    startTime = Instant.fromEpochMilliseconds(options.startTimeEpochMs.toLong()),
                )
            }
        writeTextToHost(GpxWriter.write(document, writeExtensions = options.writeExtensions))
    }

/**
 * Serialise every path of a list handle as a multi-track GPX — one `<trk>` per path.
 *
 * Waypoints are **not** written: the JS `writeGpxTracks` takes them as an argument, and this ABI
 * has no waypoint handle to pass. A host that needs them keeps the source document's `<wpt>`
 * elements from [vcParseGpxWaypointsJson] and merges them itself.
 */
@WasmExport
fun vcWriteGpxTracks(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val paths = requireList(handle)
        val options = readOptions(optionsJsonLen).toWriteGpxOptions()
        writeTextToHost(GpxWriter.write(paths, writeExtensions = options.writeExtensions))
    }

/** The path as CSV — `pathToCsv`. Options: `separator`, `unitsInHeader`, `decimals`. */
@WasmExport
fun vcPathToCsv(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        writeTextToHost(CsvWriter.write(path, readOptions(optionsJsonLen).toCsvOptions()))
    }

/**
 * The path as column-oriented JSON — `pathToJson`. Options: `pretty`, `decimals`, `includeMeta`.
 * One array per field, which is the cheap way for a host to plot everything at once when it does
 * not want the raw bytes of [vcPathFieldBytes].
 */
@WasmExport
fun vcPathToJson(
    handle: Int,
    optionsJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        writeTextToHost(JsonWriter.write(path, readOptions(optionsJsonLen).toJsonOptions()))
    }

/**
 * Encode the path behind [handle] as a Garmin FIT Course file — `pathToFit`. The **binary** file
 * goes through `write_output` and the return value is its byte length.
 *
 * Payload (mandatory, unlike everywhere else in this ABI): `startTimeEpochMs`, plus an optional
 * `name`. FIT has no relative clock, so the absolute start is not something this export can
 * default — see [FitOptions].
 *
 * This returned [WasiAbi.ERR_UNSUPPORTED] until w12, when the encoder became pure Kotlin over a
 * multiplatform FIT SDK. The signature did not change and no host that handled the old sentinel
 * breaks, so the ABI version stays at 1.
 */
@WasmExport
fun vcPathToFit(
    handle: Int,
    payloadJsonLen: Int,
): Int =
    guarded {
        val path = requirePath(handle)
        val options = readOptions(payloadJsonLen).toFitOptions()
        writeBytesToHost(
            path.toFitBytes(options.name, Instant.fromEpochMilliseconds(options.startTimeEpochMs.toLong())),
        )
    }

/**
 * Encode every path of a list handle into **one** FIT course — `pathsToFit`. One lap and one
 * `TIMER`/`START`…`STOP` event pair per path, the last one closing the file with `STOP_ALL`.
 *
 * Same payload as [vcPathToFit], plus `interPathGapMs`: how far to shift each path after the
 * first, `0` meaning they run straight on from one another. It is not a pause — FIT expresses
 * those with `TIMER`/`PAUSE` events, which this port does not emit.
 */
@WasmExport
fun vcPathsToFit(
    handle: Int,
    payloadJsonLen: Int,
): Int =
    guarded {
        val paths = requireList(handle)
        val options = readOptions(payloadJsonLen).toFitOptions()
        writeBytesToHost(
            paths.toFitBytes(
                options.name,
                Instant.fromEpochMilliseconds(options.startTimeEpochMs.toLong()),
                interPathGap = options.interPathGapMs.milliseconds,
            ),
        )
    }
