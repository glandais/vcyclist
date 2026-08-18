package io.github.glandais.codegen.surface

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * An export is not a surface crossing.
 *
 * `docs/ledgers/surface-coverage.md` defines its Démo column as "reachable by a human in the UI",
 * and the distinction is not theoretical: `writeGpx` sat exported with no caller from task `g29`,
 * and would have shown a ✅ under the other reading for four tasks running.
 *
 * The half this used to check — that the demo's shim bound every `@JsExport` — is gone, and
 * deliberately not replaced: `@glandais/vcyclist-engine`'s `index.d.ts` is now **generated** from
 * the façade, so the two sets are equal by construction and an assertion between them could not
 * fail. Its anti-vacuity pin moved to `TsFacadeTest.expectedFunctionCount`. Deleting a tautological
 * test is the point of generating the file ; keeping it would be the kind of green-but-blind check
 * this suite exists to avoid.
 *
 * What remains is the half generation cannot reach: whether anything in `demo/src` **calls** each
 * export, and whether `demo/src/engine-coverage.md` tells the truth about the ones that do not.
 *
 * What no static scan can check is whether a *control* exists. "A human can reach it" is a claim
 * about the UI, not about calls, which is why the ledger's Démo cells are still written by hand.
 */
class DemoReachabilityTest {
    private val root = repositoryRoot()
    private val declarations = File(root, "engine/src/jsMain/typescript/index.d.ts")
    private val coverage = File(root, "demo/src/engine-coverage.md")
    private val demoSources =
        File(root, "demo/src")
            .walkTopDown()
            .filter { it.isFile && (it.extension == "ts" || it.extension == "vue") }
            .toList()

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    /** Everything a demo component can import today: the generated package's exported functions. */
    private fun boundSymbols(): Set<String> =
        Regex("""(?m)^export declare function (\w+)""")
            .findAll(declarations.readText())
            .map { it.groupValues[1] }
            .toSet()

    /**
     * Names `demo/src/engine-coverage.md` declares as exported-but-unreached.
     *
     * Only the part of each indented line **before the em dash** counts. The first version of this
     * swept every lowercase word in the block, prose included, and duly reported four symbols as
     * claimed-unreached because the explanations mention them by name. The list is data ; the
     * sentence after the dash is not.
     */
    private fun declaredUnreached(): Set<String> {
        val block = coverage.readText().substringAfter("Declared but not reached by any UI control", "")
        if (block.isBlank()) fail("${coverage.path} no longer carries its 'declared but not reached' block")
        return block
            .lines()
            .filter { it.startsWith("    ") }
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
    fun `the demo and the generated package are where this test thinks they are`() {
        assertTrue(declarations.isFile, "not found: ${declarations.absolutePath} — run :codegen:generateTsFacade")
        assertTrue(coverage.isFile, "not found: ${coverage.absolutePath}")
        assertTrue(demoSources.size > 10, "found only ${demoSources.size} demo sources")
        assertTrue(
            boundSymbols().size > 25,
            "the export extractor found only ${boundSymbols().size} — it broke, and every assertion " +
                "below would then compare two empty sets and pass forever.",
        )
    }

    @Test
    fun `every unreached export is named in the demo's coverage list`() {
        val unreached = boundSymbols() - calledSymbols()

        assertEquals(
            emptySet(),
            unreached - declaredUnreached(),
            "these are exported and called by nothing, and demo/src/engine-coverage.md does not say " +
                "so. Either wire a control, or add them to that list with a reason — an export that " +
                "looks like coverage is exactly the writeGpx failure.",
        )
    }

    @Test
    fun `the coverage list does not claim a symbol that is in fact used`() {
        val staleClaims = declaredUnreached().intersect(calledSymbols())

        assertEquals(
            emptySet(),
            staleClaims,
            "engine-coverage.md says these reach no UI control, but the demo calls them. The list " +
                "has to stay true in both directions or it is worse than nothing.",
        )
    }
}
