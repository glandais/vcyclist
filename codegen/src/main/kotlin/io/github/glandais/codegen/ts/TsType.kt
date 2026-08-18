package io.github.glandais.codegen.ts

/**
 * A rendered TypeScript type, carrying the identifiers it references so the emitter can prove none
 * of them dangles.
 *
 * The dangling reference is not hypothetical: the `.d.ts` Kotlin/JS ships today names
 * `EnhanceOptionsDto`, `PointDto` and sixteen more without ever declaring them, and `tsc` rejects
 * it outside `skipLibCheck`. [TsFacade] asserts this set is covered.
 */
data class TsType(
    val rendered: String,
    val references: Set<String> = emptySet(),
) {
    /** `T | null` — Kotlin's `T?` on a property or a parameter that accepts an explicit null. */
    fun nullable(): TsType = copy(rendered = "$rendered | null")

    fun array(): TsType = copy(rendered = if (rendered.contains(' ')) "($rendered)[]" else "$rendered[]")

    fun promise(): TsType = copy(rendered = "Promise<$rendered>")

    companion object {
        fun primitive(name: String) = TsType(name)

        fun reference(name: String) = TsType(name, setOf(name))

        /** A closed set of string literals, derived from a wire catalogue. */
        fun union(values: List<String>) = TsType(values.joinToString(" | ") { "'$it'" })
    }
}
