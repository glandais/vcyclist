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
    // executable binary of the project is `:engine`'s, see w06). Since w12 the encoder is
    // commonMain over a multiplatform SDK, so FIT export works here like anywhere else.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        wasmtime()
    }

    sourceSets {
        commonMain.dependencies {
            // `FitCourse` is built from a `Path` (task g10), and the conversion is part of the
            // public surface, so `:gpx` is exposed rather than merely used.
            api(project(":gpx"))
            // Kotlin Multiplatform FIT SDK, generated from the same 21.205.0 profile revision the
            // two official Garmin SDKs implemented before w12. It is stdlib-only commonMain code,
            // so the encoder is written once and runs on JVM, JS *and* wasmWasi — which is what
            // let `expect`/`actual` FitEncoder, `com.garmin:fit` and `@garmin/fitsdk` all go away.
            // `implementation`, not `api` : `FitCourse` is the public model, the SDK's message
            // classes stay an implementation detail of the encoder.
            implementation(libs.fit.kotlin.sdk)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // `@garmin/fitsdk` is **test-only** since w12, and JS-only. Nothing in the encoder needs
        // it any more, but replaying its output through the vendor's own decoder is the strongest
        // check there is that this port emits real FIT — that is all `FitEncoderJsTest` does now.
        // A JS consumer of `@glandais/vcyclist-fit` no longer installs it.
        //
        // There is no JVM counterpart on purpose: `com.garmin:fit` declares the same
        // `com.garmin.fit.*` class names as `fit-kotlin-sdk`, so the two cannot share a
        // classpath. The Java SDK is gone from the project entirely.
        jsTest.dependencies {
            implementation(npm("@garmin/fitsdk", "21.205.0"))
        }
    }
}

// Maven Central publication (coordinates, POM, signing) is configured once for every
// module in the root build.gradle.kts — see the `subprojects` block there.
// npm publishing tasks — same shape as `:engine` / `:elevation`, and wired into
// `.releaserc.json` alongside them.
//
// The Garmin SDK licence question that dominated this file until w12 is **moot**: no
// Garmin-published package is a dependency of anything vcyclist ships any more. The encoder sits
// on `io.github.glandais:fit-kotlin-sdk`, generated from the public FIT profile and published
// from the same account as vcyclist itself; `com.garmin:fit` is gone from the build, and
// `@garmin/fitsdk` is a *test* dependency of the JS target only, where it decodes what the
// encoder wrote. No `npm install @glandais/vcyclist-engine` pulls 1.3 MB of vendor SDK for a
// consumer who never writes a FIT file.
//
// The FIT format is still Garmin's, so `fit-kotlin-sdk` carries the FIT Protocol License and a
// consumer accepts those terms in practice. `docs/publishing.md` records the full reasoning,
// including the pre-w12 argument it replaces.
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
