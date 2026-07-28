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
                useMocha { timeout = "30s" }
            }
        }
        browser {
            commonWebpackConfig {
                outputFileName = "fit.js"
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
                customField("name", "@glandais/vcyclist-fit")
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

    // Library target only : `:fit` produces a klib, never a standalone `.wasm` (the single
    // executable binary of the project is `:engine`'s, see w06). The `actual` encoder throws —
    // there is no FIT SDK under WASI, see `FitEncoder.wasmWasi.kt` and task w12.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        wasmtime()
    }

    sourceSets {
        commonMain.dependencies {
            // `FitCourse` is built from a `Path` (task g10), and the conversion is part of the
            // public surface, so `:gpx` is exposed rather than merely used.
            api(project(":gpx"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // Tests that actually call `FitEncoder`, compiled only into the targets that have an SDK
        // behind it. wasmWasi's actual throws (task w01), so they cannot pass there ; the stub's
        // own contract is pinned by `FitEncoderStubTest`. Same single-file-two-compilations
        // trick as `commonTestFixtures` in gpx/build.gradle.kts.
        jvmTest { kotlin.srcDir("src/encodingTest/kotlin") }
        jsTest { kotlin.srcDir("src/encodingTest/kotlin") }
        jvmMain.dependencies {
            // Official Garmin FIT SDK, same coordinates and version as gpx2web. JVM-only : the
            // JS target goes through @garmin/fitsdk instead (task g09).
            implementation(libs.garmin.fit)
        }
        // Exact version, never a range : a binary encoder that changes behaviour on an
        // `npm install` is miserable to diagnose. 21.205.0 also matches the Java SDK version
        // exactly, so both targets encode against the same FIT profile revision.
        jsMain.dependencies {
            implementation(npm("@garmin/fitsdk", "21.205.0"))
        }
    }
}

// Maven Central publication (coordinates, POM, signing) is configured once for every
// module in the root build.gradle.kts — see the `subprojects` block there.
// npm publishing tasks — same shape as `:engine` / `:elevation`, and wired into
// `.releaserc.json` alongside them.
//
// Note that until task g09 lands, `@glandais/vcyclist-fit` ships an encoder that throws
// `NotImplementedError`. The Maven Central artefact is fully functional on its JVM variant.
//
// The Garmin SDK licence question raised in the g08 spec is settled in g19, and the reasoning is
// worth stating exactly, because the obvious summary is wrong.
//
// vcyclist redistributes **none of Garmin's bytes**, on any target. Garmin publishes the SDK
// itself — `com.garmin:fit` on Maven Central, `@garmin/fitsdk` on npm — and both are reached the
// way the publisher intended : by declaring a dependency coordinate that the consumer's own
// resolver fetches from Garmin's distribution. `vcyclist-fit`'s POM names `com.garmin:fit`; its
// npm `package.json` names `@garmin/fitsdk`. Neither embeds a line of it.
//
// The correction : an earlier version of this note claimed the dependency was jvmMain-only and
// that "nothing of Garmin's ever reaches the npm bundles". That is false — see the `npm(...)`
// declarations above, and note that `:engine` does `api(project(":fit"))`, so **every** install
// of `@glandais/vcyclist-engine` pulls `@garmin/fitsdk` in transitively, whether or not the
// consumer ever writes a FIT file. The conclusion is unchanged (a coordinate, not a copy), but
// the reach is wider than that note implied, and `docs/publishing.md` records it as such.
val copyReadmeToJsPackage =
    tasks.register<Copy>("copyReadmeToJsPackage") {
        from(rootProject.layout.projectDirectory.file("README.md"))
        into(layout.buildDirectory.dir("dist/js/productionLibrary"))
    }

tasks.register<Exec>("npmPublishJs") {
    group = "publishing"
    description = "Publish the Kotlin/JS library to npm as @glandais/vcyclist-fit"
    dependsOn("jsBrowserProductionLibraryDistribution", copyReadmeToJsPackage)
    workingDir =
        layout.buildDirectory
            .dir("dist/js/productionLibrary")
            .get()
            .asFile
    commandLine("npm", "publish", "--access", "public")
}

// Gradle 9 strict validation : `jsBrowserProductionWebpack` (binaries.executable()) reads the
// directory `jsProductionLibraryCompileSync` (binaries.library()) writes. Declare the ordering,
// exactly as `:engine` and `:elevation` do.
tasks.matching { it.name == "jsBrowserProductionWebpack" }.configureEach {
    mustRunAfter("jsProductionLibraryCompileSync")
}

tasks.matching { it.name.endsWith("ProductionLibraryDistribution") }.configureEach {
    mustRunAfter("jsProductionExecutableCompileSync")
}
