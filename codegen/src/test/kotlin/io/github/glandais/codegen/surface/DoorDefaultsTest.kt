package io.github.glandais.codegen.surface

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A door must take its defaults **from the options class**, never restate them as literals.
 *
 * This is the `250 W against the CLI's 280 W` failure class, and it is the one drift that no key
 * check can see: every door lists the same keys, every door parses them, and they quietly disagree
 * about what happens when the caller says nothing. `EngineJsApi`'s own KDoc forbids it and the file
 * did it anyway, for `10.0`, `3.0` and `true`.
 *
 * ## What is asserted, and what is not
 *
 * The catalog resolves each option's default by reflection against a real default-constructed
 * instance ([OptionCatalog.OptionGroup.defaultOf]), so the expected value cannot be stale. What is
 * checked here is the **shape** of the reader: that it reads through a `val d = <Options>()` and
 * spells `d.<property>` as the fallback, rather than writing the number again.
 *
 * That is a weaker claim than "the values are equal", and deliberately so — comparing values would
 * mean evaluating wasm and JS from a JVM test, which is the thing none of these source sets can do.
 * A reader that says `d.toleranceM` cannot drift; a reader that says `10.0` can, and this fails it.
 */
class DoorDefaultsTest {
    private val root = repositoryRoot()
    private val wasiOptions = File(root, "engine/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/WasiOptions.kt")

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    /** The body of a group's WASI reader, whatever it is called. */
    private fun wasiReaderBody(group: OptionCatalog.OptionGroup): String {
        val reader = group.wasiReaderName()
        return Regex("""internal fun JsonObj\?\.$reader\(\): ${group.name} \{(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL)
            .find(wasiOptions.readText())
            ?.groupValues
            ?.get(1)
            ?: fail("no `internal fun JsonObj?.$reader(): ${group.name}` in ${wasiOptions.name}")
    }

    @Test
    fun `the catalog resolves every path against the real class`() {
        // Not a formality: this is what proves a path is a lens into a live property rather than a
        // string that happens to look like one. `defaultOf` throws, naming the class, if it is not.
        var resolved = 0
        for (group in OptionCatalog.groups) {
            for (option in group.options) {
                group.defaultOf(option.path)
                resolved++
            }
        }
        assertTrue(resolved >= 14, "only $resolved paths resolved — the catalog must have shrunk")
    }

    @Test
    fun `every WASI reader binds the options class before reading it`() {
        for (group in OptionCatalog.groups) {
            if (group.wasiKeySet == null) continue
            val body = wasiReaderBody(group)
            val binding = group.wasiDefaults()

            assertTrue(
                Regex("""val $binding = \w+\(\)""").containsMatchIn(body),
                "${group.name}: the WASI reader does not bind `val $binding = …()`. Every fallback " +
                    "has to come off one instance — a literal here is how the façades once defended " +
                    "250 W while the CLI defended 280 W. A door with its own defaults (EnhanceOptions) " +
                    "binds its own function; that is still one place, not none.",
            )
        }
    }

    @Test
    fun `no WASI reader restates a default the options class already holds`() {
        val restated = mutableListOf<String>()
        for (group in OptionCatalog.groups) {
            if (group.wasiKeySet == null) continue
            val body = wasiReaderBody(group)
            val binding = group.wasiDefaults()

            for (option in group.options) {
                // The FULL path, not its last segment: the reader spells
                // `defaults.simplifyPath.toleranceM`, and looking for `defaults.toleranceM` found
                // nothing and reported every nested option as restated. Wrong check, not a finding.
                val property = option.path
                // `d.<property>` anywhere in the reader, not only on the read line.
                //
                // The narrower "the fallback argument itself must be `d.x`" version of this check
                // reported `decimals` on both writers, and was wrong: their `Double.NaN` is an
                // ABSENCE SENTINEL for a nullable Int, and the real fallback is `d.decimals` on
                // the next line. A default that is restated has no `d.<property>` at all, which is
                // the thing worth asserting.
                if (!body.contains("$binding.$property")) {
                    restated.add(
                        "${group.name}.${option.wireName} never mentions `$binding.$property` — " +
                            "its default is written out instead of read",
                    )
                }
            }
        }

        assertEquals(
            emptyList(),
            restated,
            "these WASI readers spell a default instead of reading it off the options class, so " +
                "changing the class moves the CLI and leaves the wire door behind.",
        )
    }
}
