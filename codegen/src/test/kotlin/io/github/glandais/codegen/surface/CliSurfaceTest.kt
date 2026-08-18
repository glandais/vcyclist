package io.github.glandais.codegen.surface

import io.github.glandais.codegen.surface.OptionCatalog.Door
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The CLI door, checked at last.
 *
 * Until S9 the catalog was **forbidden** from claiming [Door.CLI], because there was no extractor
 * behind the claim and an unverified tick is what `docs/ledgers/surface-coverage.md` exists to
 * prevent. This is that extractor, so the claim is now allowed and checked.
 *
 * ## Why the flag names are declared and not derived
 *
 * They are not derivable. `simplifyEnabled` is `--simplify`, `racingLineRoadWidthM` is
 * `--road-width`, `maxSlewWPerS` is `--cyclist-slew`, `type` is `--cyclist-model`. Any rule that
 * produced those from the property names would be a lookup table wearing a disguise. So the catalog
 * names the flag, and this test checks the flag exists — which is the half that actually drifts,
 * since a rename lands in the mixin and nowhere else.
 *
 * That is also why S4's Notes reject *generating* picocli: negatable booleans, `${DEFAULT-VALUE}`
 * interpolation and bespoke validation timing make the annotation the source of truth. Checking it
 * is the right instrument; replacing it is not.
 */
class CliSurfaceTest {
    private val root = repositoryRoot()
    private val cliSources =
        File(root, "cli/src/main/kotlin/io/github/glandais/cli")
            .walkTopDown()
            .filter { it.extension == "kt" }
            .toList()

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    /** Every `names = ["--flag", …]` picocli declares, across the commands and mixins. */
    private fun declaredFlags(): Set<String> =
        cliSources
            .flatMap { file -> Regex(""""(-{1,2}[a-z][a-z0-9-]*)"""").findAll(file.readText()).map { it.groupValues[1] } }
            .toSet()

    @Test
    fun `the CLI sources are where this test thinks they are`() {
        assertTrue(cliSources.isNotEmpty(), "no Kotlin under cli/src/main — did the module move?")
    }

    /** The self-check: a regex that matched nothing would make every assertion below vacuous. */
    @Test
    fun `the flag extractor finds the CLI's options`() {
        val flags = declaredFlags()

        assertTrue(flags.size > 30, "found only ${flags.size} flags — the extractor must have broken")
        assertTrue("--cyclist-weight" in flags, "a flag known to exist is missing: $flags")
    }

    @Test
    fun `every option claiming the CLI door names a flag the CLI actually declares`() {
        val flags = declaredFlags()

        val missing =
            OptionCatalog.groups.flatMap { group ->
                group.options
                    .filter { Door.CLI in it.doors }
                    .filter { it.cliFlag !in flags }
                    .map { "${group.name}.${it.wireName} claims ${it.cliFlag}, which no mixin declares" }
            }

        assertEquals(
            emptyList(),
            missing,
            "a renamed flag lands in the mixin and nowhere else; this is what notices.",
        )
    }

    /**
     * The inverse, and the one with teeth: an option with no CLI door must say why.
     *
     * Four of `EnhanceOptions`' fourteen options are in this state and none of them was recorded
     * anywhere before S9 — `computeMaxSpeeds` is hardcoded `true` in `pipelineOptions()`, and the
     * three `wPrimeBalance*` keys plus `simplifyZExaggeration` simply have no flag. Two of those
     * are decisions and two are gaps; the reasons say which.
     */
    @Test
    fun `every option without a CLI door carries a written reason`() {
        val unexplained =
            OptionCatalog.groups.flatMap { group ->
                group.options
                    .filterNot { Door.CLI in it.doors }
                    .filter { it.cliExempt.isNullOrBlank() && group.cliNote.isBlank() }
                    .map { "${group.name}.${it.wireName}" }
            }

        assertEquals(emptyList(), unexplained, "a gap with a reason is a decision; without one it is an oversight.")
    }
}
