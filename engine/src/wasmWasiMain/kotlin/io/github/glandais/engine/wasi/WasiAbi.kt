package io.github.glandais.engine.wasi

import io.github.glandais.engine.path.Path

/**
 * Everything of the WASI ABI that does **not** touch a host import: the version, the error
 * codes, the handle table and the last-error slot.
 *
 * The split from [EngineWasiApi] is not cosmetic. `wasmWasiWasmtimeTest` runs the module through
 * the KGP test runner, which knows nothing of the custom `vcyclist` imports and cannot supply
 * them; a test that reached an export using `read_input` would fail to instantiate (see
 * `docs/kotlin-wasm-wasi.md` §5). Keeping the state machine here means the parts worth unit
 * testing — handle lifecycle, error mapping — are testable, while the thin `@WasmExport` layer
 * that marshals bytes stays for the reference host of task w09.
 */
internal object WasiAbi {
    /**
     * ABI version, monotone. Bumped on **any** breaking change to an export's signature or
     * meaning; a host reads it first and refuses what it does not know.
     *
     * - `1` — task w03: handles, callback protocol, error codes below.
     */
    const val VERSION: Int = 1

    /** Generic failure. The message is in [lastError], readable through `vcLastError`. */
    const val ERR_GENERIC: Int = -1

    /** The handle passed in is not (or no longer) in the table. */
    const val ERR_UNKNOWN_HANDLE: Int = -2

    /** An argument is out of range — a negative length, an empty payload. */
    const val ERR_INVALID_ARGUMENT: Int = -3

    private val handles = HashMap<Int, Path>()
    private var nextHandle = 1

    /**
     * Message of the last failed export, or `""`. Deliberately *not* cleared by a successful
     * call: a host that ignores a sentinel and asks later still gets the truth about what it
     * ignored, and one that follows the protocol reads it immediately anyway.
     */
    var lastError: String = ""
        private set

    /** Number of live handles. Exposed for tests and for `vcReleaseAll`'s return value. */
    val liveHandles: Int get() = handles.size

    /** Store [path] and return its fresh positive handle. */
    fun register(path: Path): Int {
        val handle = nextHandle++
        handles[handle] = path
        return handle
    }

    /** The path behind [handle], or `null` if the host is holding a stale or invented key. */
    fun pathOrNull(handle: Int): Path? = handles[handle]

    /** Drop [handle]. Returns 1 if it existed, 0 otherwise — never an error code. */
    fun release(handle: Int): Int = if (handles.remove(handle) != null) 1 else 0

    /**
     * Drop every handle and return how many were dropped. What a host reusing one instance
     * across several traces calls between runs; without it the table grows for the lifetime of
     * the module, and nothing else can reclaim a handle the host has forgotten.
     */
    fun releaseAll(): Int {
        val count = handles.size
        handles.clear()
        return count
    }

    /**
     * Record [t] and return [ERR_GENERIC]. Every export funnels its `catch` through here: an
     * exception cannot cross a Wasm boundary, so the only thing that can is the sentinel.
     */
    fun fail(t: Throwable): Int {
        lastError = t.message ?: t::class.simpleName ?: "unknown error"
        return ERR_GENERIC
    }

    /** Record [message] and return [code]. The typed-error counterpart of [fail]. */
    fun fail(
        code: Int,
        message: String,
    ): Int {
        lastError = message
        return code
    }

    /**
     * `null` if [byteLen] is a usable payload length, otherwise the error code to return — after
     * recording why.
     *
     * It lives here rather than inline in the export for a reason worth keeping: an export must
     * reject a bad length **before** calling `read_input`, or a host that passes 0 gets a trap
     * on a callback instead of a code it can read. Inline, that ordering could only be tested
     * from a real host (w09); here it is one assertion.
     */
    fun invalidLengthOrNull(byteLen: Int): Int? =
        if (byteLen > 0) {
            null
        } else {
            fail(ERR_INVALID_ARGUMENT, "byteLen must be positive, was $byteLen")
        }

    /** Test seam: forget every handle *and* the last error, so cases cannot leak into each other. */
    internal fun resetForTests() {
        handles.clear()
        nextHandle = 1
        lastError = ""
    }
}
