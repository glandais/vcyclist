package io.github.glandais.engine.path

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the field count **quoted in prose** against [PointField.COUNT].
 *
 * Three documents stated three different numbers (39, 38 and 36) while the enum held 43, because
 * nothing connected the sentence to the code. It has since caught two rephrasings that
 * silently removed the claim: `CLAUDE.md` in 529e782, and `README.md` when the module table moved
 * to `CONTRIBUTING.md`. Adding a field is a documented, four-step operation (see `CLAUDE.md`) and
 * this is the step no reviewer can be relied upon to perform.
 *
 * **Caveat.** The documents are not declared Gradle inputs, so this task goes `UP-TO-DATE` when
 * only prose changes — the 529e782 breakage sat unnoticed for two commits for exactly that
 * reason. It is caught on any run that recompiles the test classpath, and on CI, which is clean.
 *
 * JVM-only by necessity : reading the repository from a test is not portable. The test locates the
 * repository root by walking up to `settings.gradle.kts`, and skips silently if it cannot — a
 * consumer running these tests from a published artefact has no repository to check.
 */
class DocumentedFieldCountTest {
    /** `(file, regex)` — every prose mention of the field count, one per document. */
    private val claims =
        listOf(
            "CLAUDE.md" to Regex("""stores (\d+) fields as flat `DoubleArray`s"""),
            // Moved out of README.md with the module table when the README became user-facing.
            "CONTRIBUTING.md" to Regex("""`?Path`? model \((\d+) fields × `DoubleArray`\)"""),
            "demo/README.md" to Regex("""zoom, crosshair, all (\d+) fields"""),
            "docs/guides/using-from-javascript.md" to Regex("""the (\d+)-field catalog"""),
        )

    @Test
    fun documented_field_counts_match_PointField_COUNT() {
        val root = repositoryRoot() ?: return

        for ((path, regex) in claims) {
            val file = File(root, path)
            assertTrue(file.exists(), "$path is missing — update this test if the file moved")
            val match =
                regex.find(file.readText())
                    ?: error("$path no longer states the field count in the form /${regex.pattern}/")
            assertEquals(
                PointField.COUNT,
                match.groupValues[1].toInt(),
                "$path says ${match.groupValues[1]} fields, PointField.COUNT is ${PointField.COUNT}",
            )
        }
    }

    private fun repositoryRoot(): File? {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        println("[DocumentedFieldCountTest] Skipped : no repository root found from ${File(".").absolutePath}")
        return null
    }
}
