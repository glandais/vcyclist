plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // The surface catalog holds `KClass` references to the real core options classes and reaches
    // their defaults by reflection, so it cannot drift from them the way a list of strings can.
    // `:engine` brings `:gpx` with it through `api`. There is no cycle: `:gpx` does not depend on
    // `:codegen` — `GeneratePath` writes into it, which Gradle never sees.
    implementation(project(":engine"))
    implementation(kotlin("reflect"))

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // These tests read the other doors as TEXT (see `DoorParityTest`'s KDoc), and Gradle cannot
    // infer that. Without the declaration the task stays UP-TO-DATE on exactly the edits it
    // watches.
    //
    // **Declare the DIRECTORY an extractor walks, not the file you happen to be thinking of.**
    // This has now caught the same mistake three times: `DoorKeyParityTest` missed two of its four
    // cases when `WasiOptions.kt` and `engine-shim.ts` were absent, and `CliSurfaceTest` and
    // `DemoReachabilityTest` both landed green-but-blind against a single-file list, because they
    // walk `cli/src/main` and `demo/src` whole. A directory input cannot fall behind a new
    // extractor the way a file list does.
    inputs
        .files(
            rootProject.file("engine/src/jsMain/kotlin/io/github/glandais/engine/EngineJsApi.kt"),
            rootProject.file("engine/src/wasmWasiMain/kotlin/io/github/glandais/engine/wasi/WasiOptions.kt"),
        ).withPropertyName("doorSourcesUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(rootProject.file("cli/src/main"))
        .withPropertyName("cliSourcesUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .files(rootProject.file("docs/ledgers/surface-coverage.md"))
        .withPropertyName("generatedLedgerUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs
        .dir(rootProject.file("demo/src"))
        .withPropertyName("demoSourcesUnderTest")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `TsFacadeTest` renders both JS façades and compares against the committed TypeScript, and
    // `EnumCatalog` WALKS the commonMain trees looking for the wire catalogues. Directories, per
    // the rule above — and this one bit for the fourth time before it was written down: with the
    // inputs missing, breaking the committed index.d.ts by hand left the task UP-TO-DATE and the
    // staleness and dangling-reference guards both reported green.
    for (module in listOf("engine", "gpx", "elevation")) {
        inputs
            .dir(rootProject.file("$module/src/commonMain/kotlin"))
            .withPropertyName("${module}WireCataloguesUnderTest")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
    for (module in listOf("engine", "elevation")) {
        inputs
            .dir(rootProject.file("$module/src/jsMain"))
            .withPropertyName("${module}JsFacadeUnderTest")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

application {
    mainClass.set("io.github.glandais.codegen.GeneratePathKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}

/**
 * Renders the per-option door table into `docs/ledgers/surface-coverage.md` from `OptionCatalog`.
 * `SurfaceLedgerTest` fails the build when what is committed differs, so this is how you update it.
 */
tasks.register<JavaExec>("generateSurfaceLedger") {
    group = "documentation"
    description = "Regenerate the per-option table of docs/ledgers/surface-coverage.md"
    workingDir = rootDir
    mainClass.set("io.github.glandais.codegen.surface.GenerateSurfaceLedgerKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/**
 * Renders the TypeScript surface of the JS façades into `<module>/src/jsMain/typescript/`.
 * `TsFacadeTest` fails the build when what is committed differs, so this is how you update it.
 */
tasks.register<JavaExec>("generateTsFacade") {
    group = "documentation"
    description = "Regenerate index.d.ts / index.mjs / index.cjs for the published npm packages"
    workingDir = rootDir
    mainClass.set("io.github.glandais.codegen.ts.GenerateTsFacadeKt")
    classpath = sourceSets["main"].runtimeClasspath
}
