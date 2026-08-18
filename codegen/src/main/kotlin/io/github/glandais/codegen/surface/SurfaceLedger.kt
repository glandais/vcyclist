package io.github.glandais.codegen.surface

import io.github.glandais.codegen.surface.OptionCatalog.Door

/**
 * Renders the per-option door table of `docs/ledgers/surface-coverage.md` **from the catalog**.
 *
 * ## Why this is generated and the État table above it is not
 *
 * They answer different questions. The État table is per *capability* — "can a human export FIT
 * from the demo" — and its Démo column is a claim about the UI that no static check can make, so it
 * stays hand-written and reviewed. This table is per *option*, and every cell in it is already
 * derived: the catalog knows which doors an option crosses, `CliSurfaceTest` and `DoorParityTest`
 * check that against the sources, and rendering it by hand would be a second copy of data the build
 * already verifies.
 *
 * ## Why there is no TSV
 *
 * The task spec proposed a hand-authored `surface-matrix.tsv` with a test comparing every cell to
 * the extractors. That is `GeneratePath.FIELDS` again: a file whose only correct content is what
 * the code already knows, kept honest by a check that must never disagree. If every cell is
 * verified against a derivation, the derivation is the source of truth and the file is a cache. So
 * the catalog is the file, and this renders it.
 *
 * `SurfaceLedgerTest` regenerates in memory and compares with what is committed, so a stale table
 * fails the build — the generate-and-compare trick task `w10` used for the ABI table.
 */
object SurfaceLedger {
    const val BEGIN = "<!-- BEGIN GENERATED: options-par-porte -->"
    const val END = "<!-- END GENERATED: options-par-porte -->"

    private fun cell(
        crosses: Boolean,
        note: String?,
    ): String =
        if (crosses) {
            "✅"
        } else if (note != null) {
            "❌"
        } else {
            "❌"
        }

    /** The table, between its markers, ending with a newline. */
    fun render(): String {
        val sb = StringBuilder()
        sb.append(BEGIN).append('\n')
        sb.append("<!-- Engendré par `./gradlew :codegen:generateSurfaceLedger` depuis OptionCatalog. -->\n")
        sb.append("<!-- Ne pas éditer à la main : `SurfaceLedgerTest` compare cette section à ce que le catalogue rend. -->\n\n")
        sb.append("Une ligne par **option d'entrée** cataloguée, une colonne par porte fil. C'est la moitié\n")
        sb.append("dérivable du tableau ci-dessus : chaque cellule vient d'`OptionCatalog` et est vérifiée contre\n")
        sb.append("les sources par `DoorParityTest` et `CliSurfaceTest`. Les colonnes JVM/Java et Démo n'y sont\n")
        sb.append("pas — elles ne se dérivent pas, et restent écrites à la main dans l'État.\n\n")

        for (group in OptionCatalog.groups) {
            sb.append("### `").append(group.name).append("`\n\n")
            if (group.cliNote.isNotBlank()) {
                sb.append("> CLI : ").append(group.cliNote).append("\n\n")
            }
            sb.append("| Option (nom fil) | Champ du cœur | CLI | JS | WASI |\n")
            sb.append("|---|---|---|---|---|\n")
            for (option in group.options.sortedBy { it.wireName }) {
                val cli = if (Door.CLI in option.doors) "✅ `${option.cliFlag}`" else "❌"
                sb
                    .append("| `")
                    .append(option.wireName)
                    .append("` | `")
                    .append(option.path)
                    .append("` | ")
                    .append(cli)
                    .append(" | ")
                    .append(cell(Door.JS in option.doors, null))
                    .append(" | ")
                    .append(cell(Door.WASI in option.doors, null))
                    .append(" |\n")
            }
            for (wire in group.wireOnly.sortedBy { it.wireName }) {
                sb
                    .append("| `")
                    .append(wire.wireName)
                    .append("` | *(préréglage, aucun champ)* | ")
                    .append(if (Door.CLI in wire.doors) "✅" else "❌")
                    .append(" | ")
                    .append(if (Door.JS in wire.doors) "✅" else "❌")
                    .append(" | ")
                    .append(if (Door.WASI in wire.doors) "✅" else "❌")
                    .append(" |\n")
            }
            sb.append('\n')

            val exempt = group.options.filter { it.cliExempt != null }
            if (exempt.isNotEmpty()) {
                sb.append("Sans porte CLI, avec la raison :\n\n")
                for (option in exempt.sortedBy { it.wireName }) {
                    sb
                        .append("- `")
                        .append(option.wireName)
                        .append("` — ")
                        .append(option.cliExempt)
                        .append('\n')
                }
                sb.append('\n')
            }
            if (group.coreOnly.isNotEmpty()) {
                sb
                    .append("**Cœur seulement** (")
                    .append(group.coreOnly.size)
                    .append(if (group.coreOnly.size == 1) " champ) : " else " champs) : ")
                    .append(group.coreOnly.sortedBy { it.path }.joinToString(", ") { "`${it.path}`" })
                    .append(". ")
                    .append(
                        group.coreOnly
                            .map { it.reason }
                            .distinct()
                            .first(),
                    ).append('\n')
                    .append('\n')
            }
        }
        sb.append(END).append('\n')
        return sb.toString()
    }
}
