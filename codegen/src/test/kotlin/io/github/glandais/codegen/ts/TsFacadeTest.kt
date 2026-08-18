package io.github.glandais.codegen.ts

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The committed TypeScript surface must equal what the façades render, must declare every name it
 * mentions, and must leave no stringly-typed site undecided.
 *
 * The third assertion is the one with history. The `.d.ts` Kotlin/JS ships today names
 * `EnhanceOptionsDto`, `PointDto` and sixteen more without ever declaring them — 18 `TS2304`s under
 * `skipLibCheck: false` — because `external` means "already exists in JS, do not emit". Nothing
 * caught that for as long as the package has been published, since the only consumer that looked at
 * the file, the demo, set `skipLibCheck: true` and cast the namespace to `any`.
 *
 * The fix, when the first test fails, is `./gradlew :codegen:generateTsFacade` — never a hand edit.
 */
class TsFacadeTest {
    private val root = repositoryRoot()
    private val enums = TsFacade.enumCatalog(root)
    private val modules = TsFacade.modules(root)

    private fun repositoryRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        fail("no repository root above ${File(".").absolutePath}")
    }

    @Test
    fun `every facade this test claims to read is where it thinks it is`() {
        for (module in modules) {
            assertTrue(module.facade.isFile, "not found: ${module.facade.absolutePath}")
            assertTrue(module.outputDirectory.isDirectory, "not found: ${module.outputDirectory.absolutePath}")
        }
        assertEquals(2, modules.size, "a published npm package appeared or vanished")
    }

    @Test
    fun `the committed surface is what the facades render`() {
        for (module in modules) {
            val rendered = TsFacade.render(module, enums)
            val stale =
                "${module.name}: the committed TypeScript surface is stale. Run " +
                    "`./gradlew :codegen:generateTsFacade` — do not edit the generated files by hand."
            assertEquals(rendered.declarations, File(module.outputDirectory, "index.d.ts").readText(), stale)
            assertEquals(rendered.esModule, File(module.outputDirectory, "index.mjs").readText(), stale)
            assertEquals(rendered.commonJs, File(module.outputDirectory, "index.cjs").readText(), stale)
        }
    }

    /**
     * The defect that shipped: a declaration file may not reference a type it does not declare.
     *
     * Checked structurally rather than by running `tsc`, so the guard costs nothing and needs no
     * npm toolchain on the JVM side.
     */
    @Test
    fun `the declarations reference no type they do not declare`() {
        for (module in modules) {
            val declarations = File(module.outputDirectory, "index.d.ts").readText()
            val declared =
                Regex("""(?m)^(?:export )?(?:interface|type|declare function|declare const) (\w+)""")
                    .findAll(declarations)
                    .map { it.groupValues[1] }
                    .toSet()
            val least = module.expectedDtoCount + module.expectedFunctionCount + module.handles.size
            assertTrue(
                declared.size >= least,
                "${module.name}: found only ${declared.size} declarations, expected at least $least " +
                    "(${module.expectedDtoCount} DTOs + ${module.expectedFunctionCount} functions + " +
                    "${module.handles.size} handles). The extractor is broken, and a regex that matches " +
                    "nothing turns this assertion into a tautology.",
            )
            // Every capitalised token left after the prose and the string literals are stripped is a
            // type reference — parameters included. Scanning only return types is how the first
            // version of this guard reported green while `WindDto` was missing from the file.
            val code =
                declarations
                    .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
                    .replace(Regex("""(?m)//.*$"""), " ")
                    .replace(Regex("""'[^']*'"""), " ")
            val builtIn =
                setOf(
                    "Promise",
                    "Array",
                    "Float64Array",
                    "Int32Array",
                    "Int8Array",
                    "Uint8Array",
                    "Record",
                    "Readonly",
                    "Partial",
                )
            val referenced =
                Regex("""\b([A-Z]\w*)\b""")
                    .findAll(code)
                    .map { it.groupValues[1] }
                    .toSet()
            assertEquals(
                emptySet(),
                referenced - declared - builtIn,
                "${module.name}: index.d.ts names types it never declares. This is exactly the defect " +
                    "the Kotlin-emitted .d.ts ships — `tsc` rejects the package outside skipLibCheck.",
            )
        }
    }

    /**
     * Every `String` the façades expose is either a closed set derived from a wire catalogue, or
     * free text with a written reason. Completeness is derived from the parsed façade, not declared
     * — the same rule `OptionCatalog.checkCoverage` enforces for the option groups.
     */
    @Test
    fun `no stringly-typed site reaches TypeScript undecided`() {
        val undecided = mutableListOf<String>()
        var sites = 0
        for (module in modules) {
            val source = module.facade.readText()
            val types = TypeMapper(enums, module.handles)
            for (dto in KotlinFacadeParser.dtos(source, types)) {
                for (property in dto.properties) {
                    if (!isString(source, dto.name, property.name)) continue
                    sites++
                    val site = "${dto.name}.${property.name}"
                    if (!StringUnions.isDeclared(site)) undecided += site
                }
            }
            for (function in KotlinFacadeParser.functions(source, types)) {
                for (parameter in function.parameters) {
                    if (parameter.type.rendered.substringBefore(' ') != "string") continue
                    sites++
                    val site = "${function.name}.${parameter.name}"
                    if (!StringUnions.isDeclared(site)) undecided += site
                }
            }
        }
        assertTrue(sites > 20, "found only $sites stringly-typed sites — the extractor is broken")
        assertEquals(
            emptyList(),
            undecided,
            "these String sites are neither bound to a wire catalogue nor declared free text. Add a " +
                "StringUnions.Bound so TypeScript gets the closed set, or a FreeText with the reason " +
                "it cannot be closed — a bare `string` must be a decision somebody wrote down.",
        )
    }

    @Test
    fun `every declared union and exemption still names a real site`() {
        val sites = mutableSetOf<String>()
        for (module in modules) {
            val source = module.facade.readText()
            val types = TypeMapper(enums, module.handles)
            KotlinFacadeParser.dtos(source, types).forEach { dto ->
                dto.properties.forEach { sites += "${dto.name}.${it.name}" }
            }
            KotlinFacadeParser.functions(source, types).forEach { function ->
                function.parameters.forEach { sites += "${function.name}.${it.name}" }
            }
        }
        val declared = StringUnions.bound.map { it.site } + StringUnions.freeText.map { it.site }
        assertEquals(
            emptyList(),
            declared.filterNot { it in sites },
            "StringUnions names sites the façades no longer have — a stale entry is a claim nothing " +
                "checks, and it hides the next real one.",
        )
    }

    /**
     * The published defaults are the engine's, not a transcription.
     *
     * `demo/src/composables/useClimbs.ts` spelled six of `ClimbOptions`' defaults as literals until
     * these were generated, and nothing compared them — the same shape as the façades defending
     * 250 W against the CLI's 280 W.
     */
    @Test
    fun `the emitted defaults are the ones the option catalog resolves`() {
        val engine = modules.first { it.name == "engine" }
        val functions =
            KotlinFacadeParser
                .functions(engine.facade.readText(), TypeMapper(enums, engine.handles))
                .map { it.name }
                .toSet()
        val groups = OptionDefaults.forFunctions(functions)

        assertEquals(
            listOf("climbDefaults", "csvDefaults", "jsonDefaults"),
            groups.map { it.constant },
            "the positional JS doors are the ones that need published defaults — a DTO door does " +
                "not, since omitting a field already gets the default from defaultJsOptions().",
        )

        val declarations = File(engine.outputDirectory, "index.d.ts").readText()
        val runtime = File(engine.outputDirectory, "index.mjs").readText()
        var pinned = 0
        for (group in groups) {
            assertTrue(
                group.entries.isNotEmpty(),
                "${group.constant}: no entries — the catalog lookup broke, and an empty constant " +
                    "would ship as an authoritative-looking `{}`.",
            )
            assertTrue("export declare const ${group.constant}: {" in declarations, "${group.constant} not declared")
            for (entry in group.entries) {
                val site = "${group.constant}.${entry.wireName}"
                assertTrue(
                    "    ${entry.wireName}: ${OptionDefaults.literal(entry.value, site)}," in runtime,
                    "$site: index.mjs does not carry the value OptionCatalog resolves " +
                        "(${entry.value}). The runtime constant is what callers actually read.",
                )
                pinned++
            }
        }
        assertTrue(pinned >= 14, "only $pinned defaults pinned — the extractor is broken")
    }

    /** Enum spellings come from `commonMain`, so a constant added there reaches TypeScript. */
    @Test
    fun `the wire catalogues resolve to the spellings the doors parse`() {
        assertEquals(listOf("dry", "wet"), enums.values("RoadCondition", EnumCatalog.Style.WIRE))
        assertEquals(
            listOf("input", "computed", "computed-or-input"),
            enums.values("GpxPowerSource", EnumCatalog.Style.WIRE),
        )
        assertEquals(
            listOf("constant", "durability", "critical-power", "from_data"),
            enums.values("PowerModel", EnumCatalog.Style.WIRE),
        )
        assertEquals(
            listOf("GENTLE", "CORNER", "HAIRPIN"),
            enums.values("CornerKind", EnumCatalog.Style.NAME),
        )
        assertEquals(
            44,
            enums.values("PointField", EnumCatalog.Style.WIRE).size,
            "PointField's props feed `getField`'s union; the count is the three-way sync of CLAUDE.md",
        )
    }

    private fun isString(
        source: String,
        dto: String,
        property: String,
    ): Boolean =
        Regex("""(?m)^external interface $dto \{(.*?)\n\}$""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.groupValues
            ?.get(1)
            ?.let { Regex("""(?m)^ {4}val $property: String\??$""").containsMatchIn(it) }
            ?: false
}
