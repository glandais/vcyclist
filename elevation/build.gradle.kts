plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
}

kotlin {
    jvmToolchain(21)

    jvm()

    js(IR) {
        nodejs()
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
        }
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "vcyclist-${project.name}", version.toString())
    pom {
        name.set("vcyclist-${project.name}")
        description.set(
            "Physics-based cycling simulator ported to Kotlin Multiplatform — ${project.name} module",
        )
        url.set("https://github.com/glandais/vcyclist")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("glandais")
                name.set("Gabriel Landais")
                url.set("https://github.com/glandais")
            }
        }
        scm {
            url.set("https://github.com/glandais/vcyclist")
            connection.set("scm:git:git://github.com/glandais/vcyclist.git")
            developerConnection.set("scm:git:git@github.com:glandais/vcyclist.git")
        }
    }
}

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
