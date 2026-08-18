package io.github.glandais.codegen.surface

import io.github.glandais.engine.Bike
import io.github.glandais.engine.Cyclist
import io.github.glandais.engine.EnhanceOptions
import io.github.glandais.engine.climb.ClimbOptions
import io.github.glandais.engine.io.CsvOptions
import io.github.glandais.engine.io.JsonOptions
import io.github.glandais.engine.physics.CyclistPowerSpec
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createInstance
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

/**
 * The one hand-written declaration of which core options cross which door.
 *
 * ## Why it holds symbols and not strings
 *
 * `GeneratePath.FIELDS` is a hand-copied mirror of `PointField.kt`, guarded only by
 * `EXPECTED_COUNT = 43`. It works, but it is a second source of truth for the same list, and a
 * catalog that restated option names as strings would be that mistake again with more surface.
 *
 * So an [Opt] names a **[path] into the real class**, resolved by reflection against a real
 * instance. `Opt("simplifyToleranceM", path = "simplifyPath.toleranceM")` cannot outlive the
 * property it points at: rename the property and the catalog throws, naming it.
 *
 * And completeness is **derived, never declared**: [OptionGroup.coverage] walks the class's
 * `primaryConstructor.parameters` and demands that *every* parameter appear either as an [Opt] or
 * as a [CoreOnly] carrying a reason. A field added to `CsvOptions` with no catalog entry fails the
 * build; it cannot be quietly left off a door.
 *
 * ## Why it lives in `:codegen` and not in `commonMain`
 *
 * Two reasons, both load-bearing. `engine/build.gradle.kts` enforces a wasm module size budget, and
 * ~40 descriptors with their help text would be dead weight in every target's binary — the catalog
 * is a build-time artefact, not a runtime one. And a `commonMain` catalog **cannot reflect**:
 * `kotlin-reflect` is JVM-only, so the derived-completeness property above would be impossible and
 * the catalog would have to declare its own field list. Which is the mistake it exists to avoid.
 *
 * ## What it is for, in order
 *
 * 1. **Checking** — `DoorParityTest` and `DoorDefaultsTest` (step S4). This is the whole deliverable
 *    until it has been green for a while.
 * 2. **Generating** — the WASI readers and the JVM factories, which are pure mechanism (step S10).
 *    Never picocli, never the demo UI: see the task's Notes for why.
 */
object OptionCatalog {
    /** An option that crosses at least one door. */
    data class Opt(
        /**
         * The name on the wire — a JSON key, a JS parameter. It is **not** always the property
         * name: `ClimbOptions.maxDiffRealGradeRatio` is published as `maxDiffRealGrade`, and the
         * published name is the one that cannot change.
         */
        val wireName: String,
        /**
         * Dotted path from the options object to the value, e.g. `"toleranceM"` or
         * `"simplifyPath.toleranceM"`. Defaults to [wireName] when the two agree.
         */
        val path: String = wireName,
        /** Doors that expose it today. A door missing here is a gap the tests will report. */
        val doors: Set<Door>,
        /**
         * The picocli flag, when [Door.CLI] is claimed. Not derivable — `simplifyEnabled` is
         * `--simplify` and `racingLineRoadWidthM` is `--road-width` — so it is declared, and
         * `CliSurfaceTest` checks the flag actually exists in the CLI sources.
         */
        val cliFlag: String? = null,
        /** Why this option has no CLI door, when it claims none. */
        val cliExempt: String? = null,
    )

    /** A constructor parameter that deliberately crosses no door, with the reason written down. */
    data class CoreOnly(
        val path: String,
        val reason: String,
    )

    /**
     * A wire key that maps to **no single constructor parameter**, with the reason written down.
     *
     * `roadCondition` is the case that forced this to exist: it is not a field of [Cyclist], it is
     * a preset that resolves into two of them. Modelling it as an `Opt` would have meant inventing
     * a path that does not exist; leaving it out made the WASI reader look like it accepted a key
     * nothing reads, which is exactly the alarm this catalog should raise for a real stray key. A
     * third category is the honest answer, and it stays small on purpose.
     */
    data class WireOnly(
        val wireName: String,
        val doors: Set<Door>,
        val reason: String,
    )

    enum class Door { CLI, JS, WASI }

    /**
     * One options class and its entries.
     *
     * @param optionsClass the real class, so [defaultOf] can read the real defaults.
     * @param wasiKeySet the name of the `private val …_KEYS` set that guards it in `WasiOptions.kt`,
     *   or `null` when the class has no WASI door at all.
     * @param jsFunction the `@JsExport` function whose parameters are its JS door, or `null` when
     *   the JS door is a DTO instead (those are `DoorKeyParityTest`'s business).
     */
    data class OptionGroup(
        val optionsClass: KClass<*>,
        val options: List<Opt>,
        val coreOnly: List<CoreOnly> = emptyList(),
        val wireOnly: List<WireOnly> = emptyList(),
        val wasiKeySet: String? = null,
        /**
         * The `internal fun JsonObj?.to<X>()` that reads it, when its name is not `to<ClassName>`.
         * `CyclistPowerSpec` is read by `toPowerSpec`, so the name cannot simply be derived.
         */
        val wasiReader: String? = null,
        /**
         * The binding a WASI reader falls back through. `null` means `val d = <ClassName>()`.
         *
         * `EnhanceOptions` is different on purpose: the WASI door has its own defaults — no DEM
         * fetch, no 1 Hz resample, no simplify — exactly as the JS door does, so it binds
         * `val defaults = defaultWasiOptions()`. Reading fallbacks off *that* is still reading them
         * off one place rather than writing them out, which is what the check is for.
         */
        val wasiDefaultsBinding: String? = null,
        val jsFunction: String? = null,
        /**
         * The `external interface` that is its JS door, when the door is a DTO rather than a
         * function's parameters. `DoorKeyParityTest` compares these across doors; this catalog
         * compares them to the **core class**, which is the part no door-to-door check can do.
         */
        val jsDto: String? = null,
        /**
         * Why [Door.CLI] appears on none of this group's options. The CLI is checked from S9 on;
         * until then this is the honest record of a gap rather than a silent omission.
         */
        val cliNote: String = "",
    ) {
        val name: String get() = optionsClass.simpleName!!

        /** Every primary-constructor parameter name — the class's own account of its field set. */
        fun constructorParameters(): Set<String> =
            optionsClass.primaryConstructor
                ?.parameters
                ?.mapNotNull { it.name }
                ?.toSet()
                ?: error("$name has no primary constructor; the catalog cannot derive its fields")

        /** Every declared path, whether it crosses a door or is deliberately core-only. */
        fun paths(): List<String> = options.map { it.path } + coreOnly.map { it.path }

        /** Every wire key this group's doors accept, including the ones with no field behind them. */
        fun wireNames(door: Door): Set<String> =
            (options.filter { door in it.doors }.map { it.wireName } + wireOnly.filter { door in it.doors }.map { it.wireName })
                .toSet()

        /** The name of the WASI reader function. */
        fun wasiReaderName(): String = wasiReader ?: "to$name"

        /** The binding its fallbacks come through. */
        fun wasiDefaults(): String = wasiDefaultsBinding ?: "d"

        /** What the catalog claims to cover at the top level. */
        fun coverage(): Set<String> = paths().map { it.substringBefore('.') }.toSet()

        /** Resolve [path] against a default-constructed instance, so defaults come from the class. */
        fun defaultOf(path: String): Any? {
            var current: Any? = optionsClass.createInstance()
            for (segment in path.split('.')) {
                val owner = current ?: error("$name.$path : a null appeared before the end of the path")

                @Suppress("UNCHECKED_CAST")
                val property =
                    owner::class.memberProperties.firstOrNull { it.name == segment } as? KProperty1<Any, *>
                        ?: error(
                            "$name.$path : no property '$segment' on ${owner::class.simpleName}. " +
                                "The catalog points at something that no longer exists — that is the " +
                                "whole reason it holds paths into real classes instead of strings.",
                        )
                current = property.get(owner)
            }
            return current
        }
    }

    private val ALL_WIRE = setOf(Door.CLI, Door.JS, Door.WASI)

    /** The two wire doors, for an option the CLI does not expose. Each such entry states why. */
    private val WIRE_NO_CLI = setOf(Door.JS, Door.WASI)

    /** The twenty `RacingLineOptions` fields that deliberately reach no door. See [groups]. */
    private val RACING_LINE_CORE_ONLY_REASON =
        "Tuned by measurement, not reasoning (docs/guides/racing-line.md). The doors expose " +
            "enabled, corridor and defaultRoadWidthM only, and EngineModelJvmCoverageTest pins the " +
            "Java factory at those three so that widening it is a decision rather than an accident."

    private fun racingLineCoreOnly(): List<CoreOnly> =
        listOf(
            "edgeMarginM",
            "cornerEnterRadiusM",
            "cornerExitRadiusM",
            "minCornerLengthM",
            "minCornerTurnDeg",
            "hairpinTurnDeg",
            "gentleRadiusM",
            "regularityFactor",
            "selfProximityGapM",
            "straightRunM",
            "straightRadiusM",
            "widthSmoothWindowM",
            "objectiveRadiusM",
            "steeringLengthM",
            "centeringLengthM",
            "maxNewtonIterations",
            "gradientTolerance",
            "boundEpsilonM",
            "simplifyToleranceCapM",
        ).map { CoreOnly("racingLine.$it", RACING_LINE_CORE_ONLY_REASON) } +
            CoreOnly(
                "racingLine.curvature",
                "The racing line's OWN nested CurvatureOptions, which is NOT what the doors' " +
                    "`curvatureEnabled` targets — that one is EnhanceOptions.curvature. Two " +
                    "distinct settings one letter apart in the docs; giving this one a door would " +
                    "need a name that says which is which. " + RACING_LINE_CORE_ONLY_REASON,
            )

    /**
     * S4 covered the three smallest groups, which were also the three with live gaps; S9 adds the
     * five that carry the pipeline. Still check-only: no emitter exists, and per the S4
     * reassessment none should until the checking half has been green for a while.
     */
    val groups =
        listOf(
            OptionGroup(
                optionsClass = ClimbOptions::class,
                jsFunction = "detectClimbsWithOptions",
                wasiKeySet = "CLIMB_KEYS",
                cliNote = "The CLI has no climb command at all — `grep -ri climb cli/src/main` is empty.",
                options =
                    listOf(
                        Opt("minMinClimbElevationM", doors = WIRE_NO_CLI),
                        Opt("maxMinClimbElevationM", doors = WIRE_NO_CLI),
                        Opt("minClimbElevationRatio", doors = WIRE_NO_CLI),
                        Opt("minGradePercent", doors = WIRE_NO_CLI),
                        // The published name has never matched the property name.
                        Opt("maxDiffRealGrade", path = "maxDiffRealGradeRatio", doors = WIRE_NO_CLI),
                        Opt("booster", doors = WIRE_NO_CLI),
                        Opt("maxAnalysisPoints", doors = WIRE_NO_CLI),
                    ),
            ),
            OptionGroup(
                optionsClass = CsvOptions::class,
                jsFunction = "pathToCsv",
                wasiKeySet = "CSV_KEYS",
                cliNote = "`enhance --csv` and `export --csv` pass no CsvOptions at all.",
                options =
                    listOf(
                        Opt("separator", doors = WIRE_NO_CLI),
                        Opt("unitsInHeader", doors = WIRE_NO_CLI),
                        Opt("decimals", doors = WIRE_NO_CLI),
                        Opt(
                            "lineSeparator",
                            doors = WIRE_NO_CLI,
                            cliExempt = "Neither --csv command passes CsvOptions; a gap, recorded.",
                        ),
                    ),
                coreOnly =
                    listOf(
                        CoreOnly(
                            "fields",
                            "Column selection is a List<PointField>, and no door has a way to spell a " +
                                "PointField on the wire yet — fieldDefinitions() publishes names, but " +
                                "nothing parses one back. Give it a door by adding a name list to the " +
                                "readers, not by widening this entry.",
                        ),
                    ),
            ),
            OptionGroup(
                optionsClass = EnhanceOptions::class,
                jsDto = "EnhanceOptionsDto",
                wasiKeySet = "ENHANCE_KEYS",
                wasiDefaultsBinding = "defaults",
                cliNote = "Checked from S9 on; the CLI spells these as --simplify, --no-fix-elevation, etc.",
                options =
                    listOf(
                        Opt("fixElevation", doors = ALL_WIRE, cliFlag = "--fix-elevation"),
                        Opt(
                            "computeMaxSpeeds",
                            doors = WIRE_NO_CLI,
                            cliExempt =
                                "EnhanceCommand.pipelineOptions() hardcodes it to true; turning the speed ceiling off from a CLI has " +
                                    "no use case anybody has asked for.",
                        ),
                        Opt("virtualizeTrack", doors = ALL_WIRE, cliFlag = "--virtualize"),
                        Opt("computeOnePointPerSecond", doors = ALL_WIRE, cliFlag = "--one-point-per-second"),
                        Opt("simplifyEnabled", path = "simplifyPath.enabled", doors = ALL_WIRE, cliFlag = "--simplify"),
                        Opt("simplifyToleranceM", path = "simplifyPath.toleranceM", doors = ALL_WIRE, cliFlag = "--simplify-tolerance"),
                        Opt(
                            "simplifyZExaggeration",
                            path = "simplifyPath.zExaggeration",
                            doors = WIRE_NO_CLI,
                            cliExempt =
                                "The CLI exposes --simplify-tolerance and not the z exaggeration. A gap, not a decision — recorded so " +
                                    "it is visible.",
                        ),
                        Opt(
                            "wPrimeBalanceEnabled",
                            path = "wPrimeBalance.enabled",
                            doors = WIRE_NO_CLI,
                            cliExempt =
                                "The CLI has no W-prime-balance flags at all; the field is written with the engine defaults. A gap, " +
                                    "recorded.",
                        ),
                        Opt(
                            "wPrimeBalanceCriticalPower",
                            path = "wPrimeBalance.criticalPowerW",
                            doors = WIRE_NO_CLI,
                            cliExempt = "As wPrimeBalanceEnabled. Note --cyclist-cp configures the POWER MODEL, not this output field.",
                        ),
                        Opt(
                            "wPrimeBalanceWPrime",
                            path = "wPrimeBalance.wPrimeJ",
                            doors = WIRE_NO_CLI,
                            cliExempt = "As wPrimeBalanceEnabled. Note --cyclist-wprime configures the power model, not this output field.",
                        ),
                        Opt("curvatureEnabled", path = "curvature.enabled", doors = ALL_WIRE, cliFlag = "--curvature"),
                        Opt("racingLineEnabled", path = "racingLine.enabled", doors = ALL_WIRE, cliFlag = "--racing-line"),
                        Opt("racingLineCorridor", path = "racingLine.corridor", doors = ALL_WIRE, cliFlag = "--corridor"),
                        Opt("racingLineRoadWidthM", path = "racingLine.defaultRoadWidthM", doors = ALL_WIRE, cliFlag = "--road-width"),
                        Opt(
                            "elevationSmoothWindowM",
                            doors = ALL_WIRE,
                            cliFlag = "--elevation-smooth-window",
                        ),
                        Opt(
                            "elevationGainPreset",
                            path = "elevationGain.preset",
                            doors = ALL_WIRE,
                            cliFlag = "--elevation-gain-preset",
                        ),
                        Opt(
                            "elevationGainThresholdM",
                            path = "elevationGain.thresholdM",
                            doors = ALL_WIRE,
                            cliFlag = "--elevation-gain-threshold",
                        ),
                        Opt(
                            "elevationGainEnabled",
                            path = "elevationGain.enabled",
                            doors = WIRE_NO_CLI,
                            cliExempt =
                                "The CLI turns the stage off by asking for the `raw` preset, which reports the same unfiltered sum " +
                                    "the stage would otherwise be skipped for. A second spelling of one outcome is worth less than " +
                                    "the flag it costs.",
                        ),
                    ),
                coreOnly =
                    listOf(
                        CoreOnly(
                            "curvature.geometrySmoothWindowM",
                            "The curvature estimator's tuning. Only `enabled` crosses; the four " +
                                "windows were measured (ledger R23) and no door names them.",
                        ),
                        CoreOnly("curvature.curvatureWindowsM", "As curvature.geometrySmoothWindowM."),
                        CoreOnly("curvature.headingNoiseRad", "As curvature.geometrySmoothWindowM."),
                        CoreOnly("curvature.curvatureSmoothWindowM", "As curvature.geometrySmoothWindowM."),
                        CoreOnly(
                            "elevationGain.smoothWindowM",
                            "The scale the REPORTED climbing is measured at, which the preset already fixes " +
                                "(raw 0 m, barometric 15 m, dem 30 m, gps 50 m) — a threshold without a scale is not an answer, " +
                                "so the pair travels as one. Not to be confused with EnhanceOptions.elevationSmoothWindowM, " +
                                "which is the PIPELINE's kernel and does cross every door: that one decides the gradients the " +
                                "simulation rides, this one only how the summary reads. See docs/guides/elevation.md.",
                        ),
                    ) + racingLineCoreOnly(),
                wireOnly =
                    listOf(
                        WireOnly(
                            "demZoom",
                            ALL_WIRE,
                            "Not a field of EnhanceOptions: it configures the ElevationProvider, not the pipeline, so each " +
                                "door reads it where it builds that provider. CLI flag --dem-zoom; on WASI it overrides " +
                                "vcSetElevationConfig's sticky zoom for one call. Measured as worth nothing above 12 " +
                                "(ledger R30) and exposed anyway, because the sweep that showed it could not be run from " +
                                "any door without it.",
                        ),
                    ),
            ),
            OptionGroup(
                optionsClass = Cyclist::class,
                jsDto = "CyclistDto",
                wasiKeySet = "CYCLIST_KEYS",
                cliNote = "Checked from S9 on; --cyclist-weight, --cyclist-cd and friends.",
                options =
                    listOf(
                        Opt("massKg", doors = ALL_WIRE, cliFlag = "--cyclist-weight"),
                        Opt("maxBrakeG", doors = ALL_WIRE, cliFlag = "--cyclist-max-brake"),
                        Opt("cd", doors = ALL_WIRE, cliFlag = "--cyclist-cd"),
                        Opt("frontalAreaM2", doors = ALL_WIRE, cliFlag = "--cyclist-a"),
                        Opt("maxLeanAngleDeg", doors = ALL_WIRE, cliFlag = "--cyclist-max-angle"),
                        Opt("maxSpeedKmH", doors = ALL_WIRE, cliFlag = "--cyclist-max-speed"),
                    ),
                wireOnly =
                    listOf(
                        WireOnly(
                            "roadCondition",
                            ALL_WIRE,
                            "A preset, not a field: it resolves into maxLeanAngleDeg AND maxBrakeG " +
                                "together, and is the last word over both (RoadCondition.applyTo). " +
                                "CLI flag --road-condition.",
                        ),
                    ),
            ),
            OptionGroup(
                optionsClass = Bike::class,
                jsDto = "BikeDto",
                wasiKeySet = "BIKE_KEYS",
                cliNote = "Checked from S9 on; --bike-crr and friends.",
                options =
                    listOf(
                        Opt("crr", doors = ALL_WIRE, cliFlag = "--bike-crr"),
                        Opt("inertiaFront", doors = ALL_WIRE, cliFlag = "--bike-inertia-front"),
                        Opt("inertiaRear", doors = ALL_WIRE, cliFlag = "--bike-inertia-rear"),
                        Opt("wheelRadiusM", doors = ALL_WIRE, cliFlag = "--bike-wheel-radius"),
                        Opt("efficiency", doors = ALL_WIRE, cliFlag = "--bike-efficiency"),
                        Opt("maxPedalingLeanAngleDeg", doors = ALL_WIRE, cliFlag = "--bike-max-pedal-angle"),
                    ),
            ),
            OptionGroup(
                optionsClass = CyclistPowerSpec::class,
                jsDto = "PowerProviderDto",
                wasiKeySet = "POWER_KEYS",
                wasiReader = "toPowerSpec",
                cliNote = "Checked from S9 on; --cyclist-model, --cyclist-power and friends.",
                options =
                    listOf(
                        // Four of the seven are published under a different name from the property.
                        Opt("type", path = "model", doors = ALL_WIRE, cliFlag = "--cyclist-model"),
                        Opt("power", path = "powerW", doors = ALL_WIRE, cliFlag = "--cyclist-power"),
                        Opt("criticalPower", path = "criticalPowerW", doors = ALL_WIRE, cliFlag = "--cyclist-cp"),
                        Opt("wPrime", path = "wPrimeJ", doors = ALL_WIRE, cliFlag = "--cyclist-wprime"),
                        Opt("useHarmonics", doors = ALL_WIRE, cliFlag = "--cyclist-harmonics"),
                        Opt("pacing", doors = ALL_WIRE, cliFlag = "--cyclist-pacing"),
                        Opt("maxSlewWPerS", doors = ALL_WIRE, cliFlag = "--cyclist-slew"),
                    ),
            ),
            OptionGroup(
                optionsClass = JsonOptions::class,
                jsFunction = "pathToJson",
                wasiKeySet = "JSON_KEYS",
                cliNote = "`--json` passes no JsonOptions at all.",
                options =
                    listOf(
                        Opt("pretty", doors = WIRE_NO_CLI),
                        Opt("decimals", doors = WIRE_NO_CLI),
                        Opt("includeMeta", doors = WIRE_NO_CLI),
                    ),
                coreOnly = listOf(CoreOnly("fields", "Same as CsvOptions.fields.")),
            ),
        )

    /**
     * Constructor parameters of [type], or `null` when it is not a class the catalog can walk into
     * (a `Double`, an enum, a `List`). Those are leaves: a path stops there.
     */
    private fun parametersOf(type: KClass<*>): Set<String>? =
        runCatching {
            type.primaryConstructor
                ?.parameters
                ?.mapNotNull { it.name }
                ?.toSet()
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() && !type.java.isEnum }

    /** The declared type of `owner.<property>`, for recursing into a nested options class. */
    private fun propertyType(
        owner: KClass<*>,
        property: String,
    ): KClass<*>? =
        owner.memberProperties
            .firstOrNull { it.name == property }
            ?.returnType
            ?.classifier as? KClass<*>

    /**
     * Completeness, **derived and recursive**.
     *
     * At every level of the path tree, the class's own `primaryConstructor.parameters` must equal
     * the set of paths declared beneath it. So `RacingLineOptions` is not covered by the three
     * knobs the doors expose: its other twenty fields each need a [CoreOnly] naming why they stay
     * Kotlin. That is what turns "the doors expose three of twenty-three" from a sentence in a
     * ledger into something the build enforces.
     */
    private fun checkCoverage(
        type: KClass<*>,
        paths: List<String>,
        where: String,
    ) {
        val declared = parametersOf(type) ?: return
        val heads = paths.map { it.substringBefore('.') }.toSet()
        require(declared == heads) {
            "$where: the catalog covers $heads but ${type.simpleName} declares $declared. " +
                "Every constructor parameter needs an Opt or a CoreOnly with a written reason — " +
                "completeness is derived here, not declared, which is the whole point of holding " +
                "KClass references instead of strings."
        }
        for (head in heads) {
            val deeper = paths.filter { it.startsWith("$head.") }.map { it.substringAfter('.') }
            if (deeper.isEmpty()) continue
            val nested = propertyType(type, head) ?: error("$where.$head : cannot resolve its type")
            checkCoverage(nested, deeper, "$where.$head")
        }
    }

    init {
        // Fails at class load rather than inside one test, because every assertion elsewhere is
        // meaningless if the catalog does not describe the classes it claims to describe.
        for (group in groups) {
            checkCoverage(group.optionsClass, group.paths(), group.name)
        }
    }
}
