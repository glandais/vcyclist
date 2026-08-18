package io.github.glandais.codegen.surface

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A shim re-export is not a surface crossing.
 *
 * `docs/ledgers/surface-coverage.md` defines its Démo column as "reachable by a human in the UI",
 * and the distinction is not theoretical: `writeGpx` sat in `engine-shim.ts` from task `g29` with
 * no caller, and would have shown a ✅ under the other reading for four tasks running.
 *
 * This checks the two halves that a text scan honestly can:
 *
 * 1. every symbol the shim binds is either **imported by name** somewhere in `demo/src`, or listed
 *    in the shim's own "declared but not reached" block — so the list stays true;
 * 2. the shim binds every `@JsExport` the engine has, so a new export cannot go unnoticed.
 *
 * What it cannot check is whether a *control* exists. "A human can reach it" is a claim about the
 * UI, not about imports, and no static scan makes it. That part stays a review, which is why the
 * ledger's Démo cells are still written by hand.
 */
class DemoReachabilityTest {
    private val root = repositoryRoot()
    private val shim = File(root, "demo/src/engine-shim.ts")
    private val demoSources =
        File(root, "demo/src")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "ts" || it.extension == "vue") && it.name != "engine-shim.ts" }
            .toList()
    private val jsApi = File(root, "engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt")

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    private fun boundSymbols(): Set<String> = Regex("""export const (\w+)""").findAll(shim.readText()).map { it.groupValues[1] }.toSet()

    /**
     * Names the shim itself declares as bound-but-unreached, in its closing comment block.
     *
     * Only the part of each `//   name, name` line **before the em dash** counts. The first version
     * of this swept every lowercase word in the block, prose included, and duly reported four
     * symbols as claimed-unreached because the explanations mention them by name. The list is data;
     * the sentence after the dash is not.
     */
    private fun declaredUnreached(): Set<String> {
        val block = shim.readText().substringAfter("Declared but not reached by any UI control", "")
        if (block.isBlank()) fail("engine-shim.ts no longer carries its 'declared but not reached' block")
        return block
            .lines()
            .filter { it.startsWith("//   ") }
            .map { it.removePrefix("//   ").substringBefore('—') }
            // In this block a BARE identifier is an entry and a `backticked` one is prose. Without
            // this, an entry's wrapped explanation contributed its own symbol names as entries.
            .map { it.replace(Regex("`[^`]*`"), " ") }
            .flatMap { Regex("""\b([a-zA-Z]\w+)\b""").findAll(it).map { m -> m.groupValues[1] } }
            .toSet()
    }

    /**
     * Symbols actually **called** in the demo. Named imports alone would not do: a symbol can be
     * imported and never used, which TypeScript tolerates in a re-export chain.
     */
    private fun calledSymbols(): Set<String> {
        val body = demoSources.joinToString("\n") { it.readText() }
        return boundSymbols().filter { Regex("""\b$it\s*\(""").containsMatchIn(body) }.toSet()
    }

    @Test
    fun `the demo and the facade are where this test thinks they are`() {
        assertTrue(shim.isFile, "not found: ${shim.absolutePath}")
        assertTrue(jsApi.isFile, "not found: ${jsApi.absolutePath}")
        assertTrue(demoSources.size > 10, "found only ${demoSources.size} demo sources")
    }

    @Test
    fun `the shim binds every JsExport the engine has`() {
        val exported =
            Regex("""@JsExport\s+(?:@\S+\s+)*fun\s+(\w+)""")
                .findAll(jsApi.readText())
                .map { it.groupValues[1] }
                .toSet()
        assertTrue(exported.size > 25, "the export extractor found only ${exported.size} — it broke")

        assertEquals(
            emptySet(),
            exported - boundSymbols(),
            "engine-shim.ts does not bind these, so no demo component can reach them without " +
                "editing the shim first. Ten of thirty were unbound before S2.",
        )
    }

    @Test
    fun `every unreached binding is named in the shim's own list`() {
        val unreached = boundSymbols() - calledSymbols()

        assertEquals(
            emptySet(),
            unreached - declaredUnreached(),
            "these are bound and called by nothing, and the shim's closing comment does not say so. " +
                "Either wire a control, or add them to that list with a reason — a binding that " +
                "looks like coverage is exactly the writeGpx failure.",
        )
    }

    @Test
    fun `the shim's list does not claim a symbol that is in fact used`() {
        val staleClaims = declaredUnreached().intersect(calledSymbols())

        assertEquals(
            emptySet(),
            staleClaims,
            "the shim says these reach no UI control, but the demo calls them. The list has to stay " +
                "true in both directions or it is worse than nothing.",
        )
    }
}
