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
                    // Mirrors `:engine` — the default 2 s is too tight for the DEM-backed
                    // `ElevationStep` tests on slow CI runners.
                    timeout = "30s"
                }
            }
        }
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        // No `binaries.library()` / `packageJson` / `npmPublish*` here on purpose : `:gpx` is
        // NOT published to npm. Its JS output is inlined into the `@glandais/vcyclist-engine`
        // bundle, so consumers keep installing a single package. See docs/guides/publishing.md.
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    // Library target only. The POC façade `GpxWasiApi` and the `binaries.executable()` that
    // linked it are gone (task w03): the single standalone `.wasm` is `:engine`'s, built from
    // `EngineWasiApi`. The target itself stays — `:engine` cannot compile for WASI without it.
    wasmWasi {
        wasmtime()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.xmlutil.core)
            implementation(libs.xmlutil.serialization)
            // `Path` builds on Coordinates / DouglasPeucker / ElevationSmoother, and
            // `ElevationStep` on ElevationProvider — all part of the public surface.
            api(project(":elevation"))
        }
        commonTest {
            // GpxFixtures is shared verbatim with `:engine`'s JS-façade tests. KMP has no
            // `java-test-fixtures` equivalent, so the single source file is compiled into both
            // test compilations rather than duplicated on disk.
            kotlin.srcDir("src/commonTestFixtures/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// Maven Central publication (coordinates, POM, signing) is configured once for every
// module in the root build.gradle.kts — see the `subprojects` block there.

// ── Source-text tests need their sources declared as inputs ──────────────────────────────────
//
// `DocumentedFieldCountTest` reads four prose documents and compares the number they quote to
// `PointField.COUNT`. Its own KDoc recorded the caveat that they were not declared inputs, so the
// task went UP-TO-DATE on a prose-only change and a breakage sat unnoticed for two commits. This
// closes it.
tasks.named<Test>("jvmTest") {
    inputs
        .files(
            rootProject.file("CLAUDE.md"),
            rootProject.file("CONTRIBUTING.md"),
            rootProject.file("demo/README.md"),
            rootProject.file("docs/guides/using-from-javascript.md"),
        ).withPropertyName("documentedFieldCountClaims")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
