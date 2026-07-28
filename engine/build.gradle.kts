import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
}

kotlin {
    jvmToolchain(21)
    jvm()

    js(IR) {
        nodejs {
            testTask {
                useMocha {
                    // Default 2 s is too tight for INTEGRATION=1 tests that fetch DEM tiles and
                    // run the full `enhance` pipeline. 30 s matches `:elevation` and is generous
                    // enough for slow CI runners while still failing fast on hangs.
                    timeout = "30s"
                }
            }
        }
        browser {
            commonWebpackConfig {
                outputFileName = "engine.js"
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
        binaries.library()
        generateTypeScriptDefinitions()
        compilations.named("main") {
            packageJson {
                customField("name", "@glandais/vcyclist-engine")
                customField("publishConfig", mapOf("access" to "public"))
                customField("license", "Apache-2.0")
                customField(
                    "repository",
                    mapOf(
                        "type" to "git",
                        "url" to "https://github.com/glandais/vcyclist.git",
                    ),
                )
            }
        }
    }

    // The module that produces the standalone `.wasm` of the whole core: `:engine` sits on top
    // of `:gpx`, `:elevation` and `:fit` through `api(...)`, so one binary carries everything.
    // `binaries.executable()` is what links it (see docs/kotlin-wasm-wasi.md §5) ; the exported
    // façade lands here in task w03, the distribution task in w06.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        wasmtime()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // Re-exports Path + PointField + the resamplers + GPX I/O, which moved to `:gpx`
            // in task g01. `api` keeps source compatibility for existing consumers : the
            // package names did not change, so no import breaks.
            api(project(":gpx"))
            // Transitive via `:gpx`, but kept explicit : `physics/` and `Enhancer` use
            // Coordinates directly.
            api(project(":elevation"))
            // FIT export, exposed through `pathToFit` on the JS facade (task g10).
            //
            // The facade has to live here rather than in `:fit`'s own bundle: `:gpx` is not a
            // separate npm package (task g01 inlines it into `@glandais/vcyclist-engine`), so a
            // `Path` handed to a separately-bundled `@glandais/vcyclist-fit` would not be the
            // same JS class. One bundle keeps the types identical. The cost is that
            // `@garmin/fitsdk` becomes a dependency of the engine package.
            api(project(":fit"))
        }
        commonTest {
            // Shared GPX strings, compiled from `:gpx`'s test-fixtures directory. See the
            // matching comment in gpx/build.gradle.kts.
            kotlin.srcDir(file("../gpx/src/commonTestFixtures/kotlin"))
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

// Maven Central publication (coordinates, POM, signing) is configured once for every
// module in the root build.gradle.kts — see the `subprojects` block there.
// npm publishing tasks.
//
// `binaries.library()` on `js(IR)` produces a distributable npm package under
// `build/dist/js/productionLibrary/`. The package.json there is auto-generated from
// the `compilations["main"].packageJson { … }` customFields above (scoped name, license,
// publishConfig.access=public, repository).
//
// Run `./gradlew :engine:jsBrowserProductionLibraryDistribution` once to confirm the path —
// if Kotlin 2.3 emits the bundle under a different directory, adjust `workingDir` below.
val copyReadmeToJsPackage =
    tasks.register<Copy>("copyReadmeToJsPackage") {
        from(rootProject.layout.projectDirectory.file("README.md"))
        into(layout.buildDirectory.dir("dist/js/productionLibrary"))
    }

tasks.register<Exec>("npmPublishJs") {
    group = "publishing"
    description = "Publish the Kotlin/JS library to npm as @glandais/vcyclist-engine"
    dependsOn("jsBrowserProductionLibraryDistribution", copyReadmeToJsPackage)
    workingDir =
        layout.buildDirectory
            .dir("dist/js/productionLibrary")
            .get()
            .asFile
    commandLine("npm", "publish", "--access", "public")
}

// Gradle 9 strict validation catches that `jsBrowserProductionWebpack` (from
// binaries.executable()) reads `build/js/packages/<name>/` while
// `jsProductionLibraryCompileSync` (from binaries.library()) writes there. We declare the
// ordering explicitly so `./gradlew assemble` or any task graph that schedules both can
// run them in the right order. Without this, Gradle 9 fails with an implicit-dependency
// validation error.
tasks.matching { it.name == "jsBrowserProductionWebpack" }.configureEach {
    mustRunAfter("jsProductionLibraryCompileSync")
}

// The same conflict exists in the other direction, and only shows up once a task graph
// schedules both binaries: `jsBrowserProductionLibraryDistribution` copies
// `build/js/packages/<name>/kotlin`, which `jsProductionExecutableCompileSync` also writes.
// Both syncs materialise the same compiler output, so the order is arbitrary — it just has
// to be declared.
tasks.matching { it.name.endsWith("ProductionLibraryDistribution") }.configureEach {
    mustRunAfter("jsProductionExecutableCompileSync")
}

// Propagate the `INTEGRATION` environment variable from the shell to KotlinJsTest tasks
// (Gradle does NOT inherit env by default), so `process.env.INTEGRATION` is visible inside
// the Node test runtime — mirrors the JVM-side gate in `ElevationProviderIntegrationTest`.
tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>().configureEach {
    environment(
        "INTEGRATION",
        providers.environmentVariable("INTEGRATION").orElse("").get(),
    )
}

// The JVM `run` task that used to launch `EngineCli` is gone: task g18 removed that entry point
// in favour of the `:cli` module, which covers it and all of gpxtools-cli. Use:
//
//   ./gradlew :cli:run -Pargs="enhance input.gpx --gpx /tmp/out.gpx"
//
// `FullPipelineSmokeTest` (jvmTest) still exercises the whole pipeline from this module, so
// removing the CLI did not cost any coverage here.

// ── Standalone .wasm distribution (Phase WASI task w06) ──────────────────────────────────────
//
// `binaries.executable()` (w01) leaves the linked module under an implementation-detail path,
// `build/compileSync/wasmWasi/main/productionExecutable/optimized/`, under a name KGP chooses.
// A publishable artefact needs a stable name, a stable place, and a size somebody watches.
//
// The file name carries **no version**: a host hard-codes it, and the version belongs in the
// Maven coordinates or the release tag (w07). The binary is never committed — it rebuilds.

/** Where KGP leaves the binaryen-optimized module. */
val wasmOptimizedDir =
    layout.buildDirectory.dir("compileSync/wasmWasi/main/productionExecutable/optimized")

/** Where this project publishes it from. */
val wasmDistDir = layout.buildDirectory.dir("wasm")

/** The published file name — stable, versionless. */
val wasmFileName = "vcyclist-engine.wasm"

/**
 * Ceiling for the binary, in bytes. Measured at 300 408 B when w05 landed (the full ABI plus
 * host-served elevation); the limit is roughly twice that, so ordinary growth is silent and a
 * doubling is not. Raise it deliberately with a note, or find out what grew — `-PwasmSizeLimit=…`
 * overrides it for a one-off experiment.
 */
val wasmSizeLimit = (providers.gradleProperty("wasmSizeLimit").orNull ?: "600000").toLong()

val wasmModule =
    tasks.register<Copy>("wasmModule") {
        group = "distribution"
        description = "Copy the standalone WASI module to build/wasm/$wasmFileName, with its sha256"
        dependsOn("compileProductionExecutableKotlinWasmWasiOptimize")

        // The optimized directory, not the one next to it: `productionExecutable/kotlin/` holds the
        // pre-binaryen module, which is three times the size and works just as well — exactly the
        // kind of mistake that only shows up as a mysteriously fat release asset.
        from(wasmOptimizedDir) { include("*.wasm") }
        into(wasmDistDir)
        rename { wasmFileName }

        val distDir = wasmDistDir
        doLast {
            val wasm = distDir.get().asFile.resolve(wasmFileName)
            check(wasm.isFile) {
                "no .wasm found in ${wasmOptimizedDir.get().asFile} — did binaries.executable() go away?"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(wasm.readBytes())
            val hex = digest.joinToString("") { byte -> "%02x".format(byte) }
            // Same `<sha>  <file>` shape as `sha256sum`, so `sha256sum -c` accepts the file as is.
            wasm.resolveSibling("$wasmFileName.sha256").writeText("$hex  $wasmFileName\n")
            logger.lifecycle("wasm module: ${wasm.length()} bytes, sha256 $hex")
        }
    }

tasks.register("checkWasmModuleSize") {
    group = "verification"
    description = "Fail if the standalone WASI module grew past $wasmSizeLimit bytes"
    dependsOn(wasmModule)

    val distDir = wasmDistDir
    val limit = wasmSizeLimit
    doLast {
        val size =
            distDir
                .get()
                .asFile
                .resolve(wasmFileName)
                .length()
        check(size <= limit) {
            "$wasmFileName is $size bytes, over the $limit-byte ceiling. Either something pulled a " +
                "chunk of the engine into the reachable graph (check what the exports now touch — " +
                "DCE keeps only that), or the growth is legitimate: raise `wasmSizeLimit` in " +
                "engine/build.gradle.kts and say why in the commit."
        }
        logger.lifecycle("$wasmFileName: $size bytes, ${limit - size} under the ceiling")
    }
}

// Packaging, not verification: `assemble` builds it, `check` does not. Deliberate — a `check`
// that also links a Wasm binary makes every test run slower for no test-related reason.
tasks.named("assemble") { dependsOn(wasmModule) }
