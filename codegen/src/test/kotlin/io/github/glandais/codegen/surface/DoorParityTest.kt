package io.github.glandais.codegen.surface

import io.github.glandais.codegen.surface.OptionCatalog.Door
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every option the catalog says crosses a door must actually be spelled on that door.
 *
 * Where `DoorKeyParityTest` compares the doors to **each other** — which catches them drifting
 * apart but not all of them being behind the core together — this compares them to the **core
 * class**, through [OptionCatalog], whose completeness is derived from
 * `primaryConstructor.parameters`. That is the difference that matters: `ClimbOptions` has seven
 * parameters and both wire doors expose six, in perfect agreement with each other.
 *
 * The extractors read source text for the reason given at length in `DoorKeyParityTest`: no other
 * source set is visible from here. Each one asserts it found a plausible number of names, because
 * a regex that matches nothing turns every assertion below into a tautology.
 */
class DoorParityTest {
    private val root = repositoryRoot()
    private val jsApi = File(root, "engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt")
    private val wasiOptions = File(root, "engine/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/WasiOptions.kt")

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    /** Parameter names of a top-level `fun <name>(…)`, minus the leading `path` receiver. */
    private fun jsFunctionParameters(function: String): Set<String> {
        val signature =
            Regex("""\nfun $function\(([^)]*)\)""")
                .find(jsApi.readText())
                ?.groupValues
                ?.get(1)
                ?: fail("no `fun $function(...)` in ${jsApi.name} — renamed, or no longer top-level?")
        return Regex("""^\s{4}([A-Za-z0-9_]+)\s*:""", RegexOption.MULTILINE)
            .findAll(signature)
            .map { it.groupValues[1] }
            .filterNot { it == "path" || it == "paths" }
            .toSet()
    }

    /** `val name: Type` inside `external interface <dto> { … }` — the DTO-shaped JS doors. */
    private fun jsDtoProperties(dto: String): Set<String> {
        val body =
            Regex("""external interface $dto \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
                .find(jsApi.readText())
                ?.groupValues
                ?.get(1)
                ?: fail("no `external interface $dto` in ${jsApi.name}")
        return Regex("""^\s{4}val\s+([A-Za-z0-9_]+)\s*:""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    /** The quoted names of a `private val <set> = setOf(…)`, comment lines dropped. */
    private fun wasiKeys(setName: String): Set<String> {
        val body =
            Regex("""private val $setName\s*=\s*setOf\((.*?)\)""", RegexOption.DOT_MATCHES_ALL)
                .find(wasiOptions.readText())
                ?.groupValues
                ?.get(1)
                ?: fail("no `private val $setName = setOf(...)` in ${wasiOptions.name}")
        val withoutComments = body.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n")
        return Regex(""""([A-Za-z0-9_]+)"""").findAll(withoutComments).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the sources this test reads are where it thinks they are`() {
        assertTrue(jsApi.isFile, "not found: ${jsApi.absolutePath}")
        assertTrue(wasiOptions.isFile, "not found: ${wasiOptions.absolutePath}")
    }

    /**
     * The self-check. Without it every assertion below passes on an empty extraction.
     *
     * Non-empty is the only threshold that holds: `pathToJson` legitimately takes a single option,
     * so "at least two" was a wrong guard, not a finding — it fired on the first run here.
     */
    @Test
    fun `each extractor finds something`() {
        for (group in OptionCatalog.groups) {
            group.jsFunction?.let {
                assertTrue(
                    jsFunctionParameters(it).isNotEmpty(),
                    "$it: the JS parameter extractor found nothing — the regex must have broken",
                )
            }
            group.jsDto?.let {
                assertTrue(jsDtoProperties(it).isNotEmpty(), "$it: the JS DTO extractor found nothing")
            }
            group.wasiKeySet?.let {
                assertTrue(wasiKeys(it).isNotEmpty(), "$it: the WASI key extractor found nothing")
            }
        }
    }

    @Test
    fun `every option the catalog gives a JS door has one`() {
        // Accumulated across groups rather than asserted per group: the first `assertEquals` to
        // throw would hide every later gap, and a partial list of what is missing reads as a
        // complete one.
        val gaps =
            OptionCatalog.groups.mapNotNull { group ->
                val function = group.jsFunction ?: return@mapNotNull null
                val missing =
                    group.options
                        .filter { Door.JS in it.doors }
                        .map { it.wireName }
                        .toSet() -
                        jsFunctionParameters(function)
                if (missing.isEmpty()) null else "${group.name}.$function is missing ${missing.sorted()}"
            }

        assertEquals(
            emptyList(),
            gaps,
            "a JS caller cannot reach options the core has. Widen the function, or move the entry " +
                "to CoreOnly with a written reason.",
        )
    }

    @Test
    fun `every option the catalog gives a WASI door has one`() {
        val gaps =
            OptionCatalog.groups.mapNotNull { group ->
                val keySet = group.wasiKeySet ?: return@mapNotNull null
                val expected =
                    group.options
                        .filter { Door.WASI in it.doors }
                        .map { it.wireName }
                        .toSet()
                val missing = expected - wasiKeys(keySet)
                if (missing.isEmpty()) null else "$keySet is missing ${missing.sorted()}"
            }

        assertEquals(
            emptyList(),
            gaps,
            "`requireOnly` HARD-REJECTS a host that sends these — not a silent omission, an error " +
                "the host sees for an option the core supports.",
        )
    }

    @Test
    fun `no WASI reader accepts a key the catalog does not know`() {
        val strays =
            OptionCatalog.groups.mapNotNull { group ->
                val keySet = group.wasiKeySet ?: return@mapNotNull null
                val extra = wasiKeys(keySet) - group.wireNames(Door.WASI)
                if (extra.isEmpty()) null else "$keySet also accepts ${extra.sorted()}"
            }

        assertEquals(
            emptyList(),
            strays,
            "either the catalog is behind the reader, or the reader accepts a key nothing reads.",
        )
    }

    /**
     * A claimed door must be a checked door. The CLI was deliberately unclaimed until S9 gave it an
     * extractor (`CliSurfaceTest`), because an unverified tick is what the coverage ledger exists
     * to prevent. This now asserts the inverse: every claim has a checker behind it.
     */
    @Test
    fun `every claimed door has an extractor behind it`() {
        for (group in OptionCatalog.groups) {
            if (group.options.any { Door.JS in it.doors }) {
                assertTrue(
                    group.jsFunction != null || group.jsDto != null,
                    "${group.name} claims a JS door with neither a function nor a DTO to read",
                )
            }
            if (group.options.any { Door.WASI in it.doors }) {
                assertTrue(group.wasiKeySet != null, "${group.name} claims a WASI door with no key set to read")
            }
            for (option in group.options.filter { Door.CLI in it.doors }) {
                assertTrue(
                    option.cliFlag != null,
                    "${group.name}.${option.wireName} claims the CLI door without naming its flag. " +
                        "Flag names are not derivable — simplifyEnabled is --simplify — so they are " +
                        "declared and CliSurfaceTest checks they exist.",
                )
            }
            // A per-option reason, or a group-level one when the whole group has no CLI door:
            // "the CLI has no climb command at all" is a fact about ClimbOptions, not about each
            // of its seven fields, and repeating it seven times would read as seven decisions.
            for (option in group.options.filterNot { Door.CLI in it.doors }) {
                assertTrue(
                    !option.cliExempt.isNullOrBlank() || group.cliNote.isNotBlank(),
                    "${group.name}.${option.wireName} has no CLI door and no written reason. " +
                        "A gap with a reason is a decision; a gap without one is an oversight.",
                )
            }
        }
    }
}
