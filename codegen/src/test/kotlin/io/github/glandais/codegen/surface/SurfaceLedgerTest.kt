package io.github.glandais.codegen.surface

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The committed per-option table must equal what the catalog renders.
 *
 * Generate-and-compare, the trick task `w10` used for the ABI table: the file is committed so it is
 * readable on GitHub without running anything, and a test regenerates it in memory and fails if the
 * two differ. A generated file that can go stale in the repository is worse than no generated file,
 * because it reads as authoritative.
 *
 * The fix, when this fails, is `./gradlew :codegen:generateSurfaceLedger` — never a hand edit.
 */
class SurfaceLedgerTest {
    private val ledger = File(repositoryRoot(), "docs/ledgers/surface-coverage.md")

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    @Test
    fun `the ledger is where this test thinks it is, and still has its markers`() {
        assertTrue(ledger.isFile, "not found: ${ledger.absolutePath}")
        val text = ledger.readText()
        assertTrue(SurfaceLedger.BEGIN in text, "the generated section's BEGIN marker is gone")
        assertTrue(SurfaceLedger.END in text, "the generated section's END marker is gone")
    }

    @Test
    fun `the committed table is what the catalog renders today`() {
        val committed = ledger.readText()

        assertEquals(
            GenerateSurfaceLedger.replaceSection(committed),
            committed,
            "the per-option table is stale. Run `./gradlew :codegen:generateSurfaceLedger` — do not " +
                "edit the section between the markers by hand.",
        )
    }

    /** The self-check: an empty render would make the comparison above pass for the wrong reason. */
    @Test
    fun `the renderer produces a table for every catalogued group`() {
        val rendered = SurfaceLedger.render()

        for (group in OptionCatalog.groups) {
            assertTrue("### `${group.name}`" in rendered, "${group.name} is missing from the rendered table")
        }
        assertTrue(rendered.count { it == '\n' } > 60, "the render is too short to be the real table")
    }
}
