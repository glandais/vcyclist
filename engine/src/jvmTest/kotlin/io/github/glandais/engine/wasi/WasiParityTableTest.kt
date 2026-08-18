package io.github.glandais.engine.wasi

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The parity table of task w04 must cover **every** `@JsExport` of `EngineJsApi`, and this test
 * is what makes that statement true tomorrow rather than only on the day it was written.
 *
 * The same test now also holds the other end: every `@WasmExport` must have a named helper in
 * `tools/wasi/host.py`, so that `run-all.sh` calls it through the ABI at least once.
 *
 * It reads its sources as text, which is unusual enough to justify:
 *
 * - `PARITY_TABLE` lives in `wasmWasiMain`, invisible to any JVM compilation, so it cannot be
 *   imported here;
 * - `EngineJsApi` lives in `jsMain`, equally invisible;
 * - nothing on the JVM can reflect over either.
 *
 * Comparing the two files' text is therefore the only way to check the invariant at all — and it
 * checks the one that matters: a `@JsExport` added without a decision recorded for the WASI ABI
 * fails the build, instead of quietly leaving a hole in `docs/guides/wasm-wasi-abi.md` (task w10).
 */
class WasiParityTableTest {
    private val jsApi = File("src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt")
    private val catalog = File("src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/WasiExportCatalog.kt")
    private val wasiApi = File("src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/EngineWasiApi.kt")
    private val referenceHost = File("../tools/wasi/host.py")

    /** Names declared right after a `@JsExport`, whatever the declaration spans. */
    private fun jsExportNames(): Set<String> {
        val text = jsApi.readText()
        return Regex("""@JsExport\s+(?:@\S+\s+)*fun\s+([A-Za-z0-9_]+)""")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun tabledNames(): List<String> =
        Regex("""ParityEntry\(\s*"([A-Za-z0-9_]+)"""")
            .findAll(catalog.readText())
            .map { it.groupValues[1] }
            .toList()

    /** Names declared right after a `@WasmExport`. */
    private fun wasmExportNames(): Set<String> =
        Regex("""@WasmExport\s+(?:@\S+\s+)*fun\s+([A-Za-z0-9_]+)""")
            .findAll(wasiApi.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every source is where this test thinks it is`() {
        assertTrue(jsApi.isFile, "not found: ${jsApi.absolutePath} — did the façade move?")
        assertTrue(catalog.isFile, "not found: ${catalog.absolutePath} — did the catalog move?")
        assertTrue(wasiApi.isFile, "not found: ${wasiApi.absolutePath} — did the WASI façade move?")
        assertTrue(referenceHost.isFile, "not found: ${referenceHost.absolutePath} — did the host move?")
    }

    /**
     * The reference host is what `docs/guides/wasm-wasi-abi.md` points a reader at, and
     * `tools/wasi/run-all.sh` is what proves the ABI path of an export — handle lookup, options
     * staging, the host-memory round trip — actually works end to end. An export with no helper
     * there is an export no test has ever called through the ABI, which is how
     * `vcAnalyzeRacingLineJson` went unexercised: its JSON shape was pinned by
     * `WasiJsonOutputTest` while nothing checked a host could reach it.
     */
    @Test
    fun `every WasmExport is reachable from the reference host`() {
        val exported = wasmExportNames()
        assertTrue(exported.size > 25, "the regex found only ${exported.size} exports — it must have broken")

        val hostText = referenceHost.readText()
        val unreachable = exported.filterNot { hostText.contains("\"$it\"") }.sorted()

        assertEquals(
            emptyList(),
            unreachable,
            "these @WasmExports have no helper in tools/wasi/host.py, so run-all.sh never calls " +
                "them through the ABI: ${unreachable.joinToString()}",
        )
    }

    @Test
    fun `every JsExport of EngineJsApi has a line in the parity table`() {
        val exported = jsExportNames()
        val tabled = tabledNames().toSet()

        assertTrue(exported.size > 20, "the regex found only ${exported.size} exports — it must have broken")

        val missing = (exported - tabled).sorted()
        if (missing.isNotEmpty()) {
            fail(
                "these EngineJsApi exports have no decision in PARITY_TABLE: ${missing.joinToString()}. " +
                    "Add a ParityEntry for each — PORTED, RESHAPED or NOT_PORTED with a reason.",
            )
        }
    }

    @Test
    fun `the parity table invents no export that EngineJsApi does not have`() {
        val exported = jsExportNames()

        val extra = (tabledNames().toSet() - exported).sorted()

        assertEquals(
            emptyList(),
            extra,
            "PARITY_TABLE names exports that no longer exist on the JS side — stale rows: ${extra.joinToString()}",
        )
    }

    @Test
    fun `no JS export is listed twice`() {
        val tabled = tabledNames()

        val duplicates =
            tabled
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys

        assertEquals(emptySet(), duplicates, "duplicated rows: ${duplicates.joinToString()}")
    }

    @Test
    fun `every not-ported decision carries a reason`() {
        val text = catalog.readText()

        Regex("""ParityEntry\(\s*"([A-Za-z0-9_]+)",\s*"[^"]*",\s*ParityDecision\.NOT_PORTED,\s*"([^"]*)"""")
            .findAll(text)
            .forEach { m ->
                assertTrue(
                    m.groupValues[2].length > 20,
                    "${m.groupValues[1]} is NOT_PORTED with a note too short to be a reason: '${m.groupValues[2]}'",
                )
            }
    }
}
