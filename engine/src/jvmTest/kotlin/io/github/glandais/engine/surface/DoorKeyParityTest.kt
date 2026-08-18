package io.github.glandais.engine.surface

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The five option DTOs cross three hand-written doors, and each spells their key set out **again**.
 * This test asserts the three spellings agree.
 *
 * A fourth used to be here: the demo's TypeScript mirror in `demo/src/engine-shim.ts`. It is gone
 * because the mirror is gone — `@glandais/vcyclist-engine`'s `index.d.ts` is generated from this
 * very `external interface` by `:codegen:generateTsFacade`, so the two can no longer disagree, and
 * an assertion between them could not fail. Generation replaced that check with something strictly
 * stronger: it carries the property **types** too, which this test never compared (its extractor
 * discarded everything after the colon), and it covers all twelve DTOs rather than five.
 *
 * ## Why it is a text scan
 *
 * Nothing else can see two of these at once :
 *
 * - `ENHANCE_OPTIONS_KEYS` is `private` in `jsMain`, `ENHANCE_KEYS` is `private` in `wasmWasiMain`,
 *   and neither source set is visible to any other;
 * - nothing on the JVM can reflect over a JS or wasm declaration.
 *
 * So the only place where all four are simultaneously visible is a JVM test reading the repository
 * as text — the idiom `WasiParityTableTest` established, and the only mechanism in this repository
 * that has ever caught cross-source-set drift.
 *
 * ## What it would have caught
 *
 * `ENHANCE_OPTIONS_KEYS` has no compiler link to `EnhanceOptionsDto`: adding a property to the
 * `external interface` without editing the hand-written `Set<String>` compiles cleanly and ships a
 * façade that rejects its own documented option. That is not hypothetical — the comment above the
 * four racing-line keys records it happening across the task `43`/`44` merge, where the WASI side
 * conflicted loudly and the JS side merged clean and broken.
 *
 * ## Extractor discipline
 *
 * Every extractor asserts it found a plausible number of names. A regex that silently matches
 * nothing turns this test into four `assertEquals(emptySet, emptySet)`, which passes forever and
 * guards nothing — a failure mode strictly worse than not having the test.
 */
class DoorKeyParityTest {
    private val jsApi = File("src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt")
    private val wasiOptions = File("src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/WasiOptions.kt")

    /**
     * One DTO, and what each door calls its key set.
     *
     * The names differ per door and that is not cosmetic: `ENHANCE_OPTIONS_KEYS` on the JS side is
     * `ENHANCE_KEYS` on the WASI one, and `POWER_PROVIDER_KEYS` is `POWER_KEYS`. A test that
     * assumed one naming would simply not find the other set.
     */
    private data class Dto(
        val name: String,
        val jsKeySet: String,
        val wasiKeySet: String,
        val expectedSize: Int,
    )

    private val dtos =
        listOf(
            Dto("EnhanceOptionsDto", "ENHANCE_OPTIONS_KEYS", "ENHANCE_KEYS", 19),
            Dto("CyclistDto", "CYCLIST_KEYS", "CYCLIST_KEYS", 7),
            Dto("BikeDto", "BIKE_KEYS", "BIKE_KEYS", 6),
            Dto("WindDto", "WIND_KEYS", "WIND_KEYS", 2),
            Dto("PowerProviderDto", "POWER_PROVIDER_KEYS", "POWER_KEYS", 7),
        )

    // ── Extractors ───────────────────────────────────────────────────────────────────────────

    /** `val name: Type` inside `external interface <dto> { … }`, KDoc and blank lines skipped. */
    private fun kotlinInterfaceProperties(dto: String): Set<String> {
        val body =
            Regex("""external interface $dto \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
                .find(jsApi.readText())
                ?.groupValues
                ?.get(1)
                ?: fail("no `external interface $dto` in ${jsApi.path} — did it move or get renamed?")
        return Regex("""^\s{4}val\s+([A-Za-z0-9_]+)\s*:""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** The quoted names of a `private val <set> = setOf("a", "b", …)`, comment lines dropped. */
    private fun keySet(
        file: File,
        setName: String,
    ): Set<String> {
        val body =
            Regex("""private val $setName\s*=\s*setOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(file.readText())
                ?.groupValues
                ?.get(1)
                ?: fail("no `private val $setName = setOf(...)` in ${file.path}")
        val withoutComments = body.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        return Regex(""""([A-Za-z0-9_]+)"""").findAll(withoutComments).map { it.groupValues[1] }.toSet()
    }

    // ── Tests ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every source is where this test thinks it is`() {
        assertTrue(jsApi.isFile, "not found: ${jsApi.absolutePath}")
        assertTrue(wasiOptions.isFile, "not found: ${wasiOptions.absolutePath}")
    }

    /**
     * The self-check. Without it a broken regex makes every comparison below trivially true.
     * The sizes are pinned per DTO rather than as one total, so a change is attributed.
     */
    @Test
    fun `each extractor finds the number of names it should`() {
        for (dto in dtos) {
            assertEquals(
                dto.expectedSize,
                kotlinInterfaceProperties(dto.name).size,
                "${dto.name}: the Kotlin external interface. If you added an option on purpose, " +
                    "bump expectedSize here — but only after the parity assertions are green.",
            )
            assertEquals(dto.expectedSize, keySet(jsApi, dto.jsKeySet).size, "${dto.name}: ${dto.jsKeySet}")
            assertEquals(dto.expectedSize, keySet(wasiOptions, dto.wasiKeySet).size, "${dto.name}: ${dto.wasiKeySet}")
        }
    }

    @Test
    fun `the JS allowlist covers exactly the JS DTO it guards`() {
        for (dto in dtos) {
            val declared = kotlinInterfaceProperties(dto.name)
            val allowed = keySet(jsApi, dto.jsKeySet)

            assertEquals(
                emptySet(),
                declared - allowed,
                "${dto.name} declares properties that ${dto.jsKeySet} does not allow, so " +
                    "requireOnlyKeys throws on the façade's own documented options. This is the " +
                    "task 43/44 failure exactly: the interface and the Set have no compiler link.",
            )
            assertEquals(
                emptySet(),
                allowed - declared,
                "${dto.jsKeySet} allows keys ${dto.name} does not declare — a stale entry left " +
                    "behind by a rename, which silently accepts a key nothing reads.",
            )
        }
    }

    @Test
    fun `the WASI reader accepts exactly what the JS door accepts`() {
        for (dto in dtos) {
            assertEquals(
                keySet(jsApi, dto.jsKeySet),
                keySet(wasiOptions, dto.wasiKeySet),
                "${dto.name}: the two wire doors disagree. Every capability crosses both or " +
                    "neither — see docs/ledgers/surface-coverage.md.",
            )
        }
    }
}
