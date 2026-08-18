package io.github.glandais.codegen.ts

/**
 * Kotlin type → TypeScript type, for the closed vocabulary a `@JsExport` signature may use.
 *
 * **Fails closed.** An unrecognised type throws instead of emitting `any`. That rule is the whole
 * reason this generator can replace a check: the `.d.ts` Kotlin/JS ships today writes
 * `any/* io.github.glandais.engine.path.Path */` for every opaque handle, and `any` accepts
 * anything and permits any property access — strictly worse than the branded handle emitted here.
 *
 * @param handles types that cross the boundary as opaque instances (`Path`, `ElevationProvider`).
 *   Kotlin/JS classes are first-class JS objects, but they are not `@JsExport`ed and have no TS
 *   surface, so they are emitted as branded types no literal can forge.
 */
class TypeMapper(
    private val enums: EnumCatalog,
    private val handles: Set<String>,
) {
    private val primitives =
        mapOf(
            "Double" to "number",
            "Int" to "number",
            "Long" to "bigint",
            "Float" to "number",
            "Boolean" to "boolean",
            "String" to "string",
            "Unit" to "void",
            "DoubleArray" to "Float64Array",
            "IntArray" to "Int32Array",
            "ByteArray" to "Int8Array",
        )

    /** Every DTO name the façade declares — filled in before mapping, so references can be checked. */
    var known: Set<String> = emptySet()

    /**
     * Alias → literal spellings, for every bound union this façade actually reached. The emitter
     * declares these as named types, so `PointFieldProp` is written once and importable rather
     * than inlined into both signatures that use it.
     */
    val unions = linkedMapOf<String, Union>()

    /** A named union, kept with the enum it came from so the emitted comment can name it. */
    data class Union(
        val enum: String,
        val values: List<String>,
    )

    fun map(
        kotlinType: String,
        site: String,
        doc: String?,
    ): TsType {
        val trimmed = kotlinType.trim()
        if (trimmed.endsWith("?")) {
            return map(trimmed.dropLast(1), site, doc).nullable()
        }
        Regex("""^Array<(.+)>$""").find(trimmed)?.let {
            return map(it.groupValues[1], site, doc).array()
        }
        Regex("""^Promise<(.+)>$""").find(trimmed)?.let {
            return map(it.groupValues[1], site, doc).promise()
        }
        if (trimmed == "String") {
            val bound = StringUnions.boundAt(site)
            if (bound == null) return TsType.primitive("string")
            unions.getOrPut(bound.alias) { Union(bound.enum, enums.values(bound.enum, bound.style)) }
            return TsType.reference(bound.alias)
        }
        primitives[trimmed]?.let { return TsType.primitive(it) }
        if (trimmed in handles) return TsType.reference(trimmed)
        if (trimmed.endsWith("Dto")) return TsType.reference(trimmed)
        error(
            "$site: no TypeScript mapping for the Kotlin type `$trimmed`. Teach TypeMapper the " +
                "type, or stop exposing it — emitting `any` is what the shipped .d.ts already does " +
                "and is what this generator exists to stop.",
        )
    }
}
