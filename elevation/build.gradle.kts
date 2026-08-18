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
                    // Default 2 s is too tight for INTEGRATION=1 tests that fetch tiles over the
                    // network and compile the @jsquash/webp WASM module on first call. 30 s is
                    // generous enough for slow CI runners while still failing fast on hangs.
                    timeout = "30s"
                }
            }
        }
        // Kept for `jsBrowserTest` only: the module no longer produces an executable bundle,
        // its browser demo now lives in the Vue app under `demo/` (route `#/elevation`).
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.library()
        generateTypeScriptDefinitions()
        compilations.named("main") {
            packageJson {
                customField("name", "@glandais/vcyclist-elevation")
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

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        wasmtime()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // One test, compiled into the two targets that have a tile *transport*. It used to hold
        // the seven decode tests too, exiled by w01 because wasmWasi stubbed `decodeTileBytes`;
        // w11 gave that target a pure-Kotlin decoder and they went back to `commonTest`. What
        // stays here reaches `fetchAndDecodeTile`, hence the `vcyclist.fetch_tile` host import,
        // which the KGP runner cannot supply — and reachability is static, so an `INTEGRATION`
        // gate would not save the suite. Same single-file-two-compilations trick as
        // `commonTestFixtures` in gpx/build.gradle.kts.
        jvmTest { kotlin.srcDir("src/decodingTest/kotlin") }
        jsTest { kotlin.srcDir("src/decodingTest/kotlin") }
        jvmMain.dependencies {
            implementation(libs.imageio.webp)
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(npm("@jsquash/webp", "1.4.0"))
        }
    }
}

// Maven Central publication (coordinates, POM, signing) is configured once for every
// module in the root build.gradle.kts — see the `subprojects` block there.
// See engine/build.gradle.kts for path-verification notes on the productionLibrary dirs.
val copyReadmeToJsPackage =
    tasks.register<Copy>("copyReadmeToJsPackage") {
        from(rootProject.layout.projectDirectory.file("README.md"))
        into(layout.buildDirectory.dir("dist/js/productionLibrary"))
    }

tasks.register<Exec>("npmPublishJs") {
    group = "publishing"
    description = "Publish the Kotlin/JS library to npm as @glandais/vcyclist-elevation"
    dependsOn("jsBrowserProductionLibraryDistribution", copyReadmeToJsPackage)
    workingDir =
        layout.buildDirectory
            .dir("dist/js/productionLibrary")
            .get()
            .asFile
    commandLine("npm", "publish", "--access", "public")
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
