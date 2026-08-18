package io.github.glandais.codegen.surface

import java.io.File

/**
 * Writes [SurfaceLedger.render] into `docs/ledgers/surface-coverage.md`, between its markers.
 *
 * Run with `./gradlew :codegen:run --args=ledger` (the `run` task's working directory is the
 * repository root). Everything outside the markers — the État table, the prose, the footnotes — is
 * left untouched, because that half is reviewed rather than derived.
 */
object GenerateSurfaceLedger {
    private val ledgerFile = File("docs/ledgers/surface-coverage.md")

    /** The document with the generated section replaced. Pure, so a test can call it. */
    fun replaceSection(document: String): String {
        val begin = document.indexOf(SurfaceLedger.BEGIN)
        val end = document.indexOf(SurfaceLedger.END)
        require(begin >= 0 && end > begin) {
            "surface-coverage.md has lost its ${SurfaceLedger.BEGIN} / ${SurfaceLedger.END} markers"
        }
        return document.substring(0, begin) + SurfaceLedger.render() + document.substring(end + SurfaceLedger.END.length + 1)
    }

    fun run() {
        require(ledgerFile.isFile) { "not found: ${ledgerFile.absolutePath} — run from the repository root" }
        val updated = replaceSection(ledgerFile.readText())
        ledgerFile.writeText(updated)
        println("[surface-ledger] wrote ${ledgerFile.path}")
    }
}

fun main() = GenerateSurfaceLedger.run()
