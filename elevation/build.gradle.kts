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
        browser {
            commonWebpackConfig {
                outputFileName = "elevation.js"
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
    wasmJs {
        browser {
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
                customField("name", "@glandais/vcyclist-elevation-wasm")
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

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.imageio.webp)
        }
        jsMain.dependencies {
            implementation(libs.kotlinx.browser)
            implementation(npm("@jsquash/webp", "1.4.0"))
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
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

val copyReadmeToWasmPackage =
    tasks.register<Copy>("copyReadmeToWasmPackage") {
        from(rootProject.layout.projectDirectory.file("README.md"))
        into(layout.buildDirectory.dir("dist/wasmJs/productionLibrary"))
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

tasks.register<Exec>("npmPublishWasm") {
    group = "publishing"
    description = "Publish the Kotlin/Wasm library to npm as @glandais/vcyclist-elevation-wasm"
    dependsOn("wasmJsBrowserProductionLibraryDistribution", copyReadmeToWasmPackage)
    workingDir =
        layout.buildDirectory
            .dir("dist/wasmJs/productionLibrary")
            .get()
            .asFile
    commandLine("npm", "publish", "--access", "public")
}

// See engine/build.gradle.kts for the rationale.
listOf("jsBrowserProductionWebpack", "wasmJsBrowserProductionWebpack").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        mustRunAfter("${name.substringBefore("Browser")}ProductionLibraryCompileSync")
    }
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
