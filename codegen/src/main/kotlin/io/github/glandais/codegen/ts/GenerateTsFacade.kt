package io.github.glandais.codegen.ts

import java.io.File

/**
 * Writes the generated TypeScript surface into `<module>/src/jsMain/typescript/`, beside the façade
 * it mirrors. `:engine`/`:elevation` copy it into the npm distribution at publish time.
 *
 * Committed rather than build-only, on the same terms as the per-option table of
 * `surface-coverage.md`: a type change becomes a reviewable diff in the pull request, which is the
 * visibility the surface ledger exists to produce. `TsFacadeTest` is the regenerate-and-compare
 * that keeps it honest.
 *
 * Run with `./gradlew :codegen:generateTsFacade` (its working directory is the repository root).
 */
object GenerateTsFacade {
    fun run(root: File = File(".")) {
        require(File(root, "settings.gradle.kts").isFile) {
            "not the repository root: ${root.absolutePath}"
        }
        val enums = TsFacade.enumCatalog(root)
        for (module in TsFacade.modules(root)) {
            require(module.facade.isFile) { "not found: ${module.facade.path}" }
            val rendered = TsFacade.render(module, enums)
            module.outputDirectory.mkdirs()
            write(File(module.outputDirectory, "index.d.ts"), rendered.declarations)
            write(File(module.outputDirectory, "index.mjs"), rendered.esModule)
            write(File(module.outputDirectory, "index.cjs"), rendered.commonJs)
        }
    }

    private fun write(
        file: File,
        content: String,
    ) {
        file.writeText(content)
        println("[ts-facade] wrote ${file.path}")
    }
}

fun main() = GenerateTsFacade.run()
