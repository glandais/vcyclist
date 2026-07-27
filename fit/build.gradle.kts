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

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { useKarma { useChromeHeadless() } } }
        binaries.executable()
        binaries.library()
        generateTypeScriptDefinitions()
        compilations.named("main") {
            packageJson {
                customField("name", "@glandais/vcyclist-fit-wasm")
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
            // `FitCourse` is built from a `Path` (task g10), and the conversion is part of the
            // public surface, so `:gpx` is exposed rather than merely used.
            api(project(":gpx"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // Official Garmin FIT SDK, same coordinates and version as gpx2web. JVM-only : the
            // JS and Wasm targets go through @garmin/fitsdk instead (task g09).
            implementation(libs.garmin.fit)
        }
        // Exact version, never a range : a binary encoder that changes behaviour on an
        // `npm install` is miserable to diagnose. 21.205.0 also matches the Java SDK version
        // exactly, so both targets encode against the same FIT profile revision.
        jsMain.dependencies {
            implementation(npm("@garmin/fitsdk", "21.205.0"))
        }
        wasmJsMain.dependencies {
            implementation(npm("@garmin/fitsdk", "21.205.0"))
            // For `org.khronos.webgl.Uint8Array` : those declarations are in the stdlib on
            // Kotlin/JS but live in kotlinx-browser on Kotlin/Wasm. Needed only to receive the
            // encoder's output buffer — `:fit` touches no DOM API.
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

// npm publishing tasks — same shape as `:engine` / `:elevation`, and wired into
// `.releaserc.json` alongside them.
//
// Note that until task g09 lands, `@glandais/vcyclist-fit` and `-fit-wasm` ship an encoder
// that throws `NotImplementedError`. The Maven Central artefact is fully functional on its
// JVM variant.
//
// The Garmin SDK licence question raised in the g08 spec does not block this : `com.garmin:fit`
// is published on Maven Central by Garmin itself, so `vcyclist-fit`'s POM merely *declares a
// dependency* on it rather than redistributing it, and the dependency is jvmMain-only so
// nothing of Garmin's ever reaches the npm bundles.
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
    description = "Publish the Kotlin/JS library to npm as @glandais/vcyclist-fit"
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
    description = "Publish the Kotlin/Wasm library to npm as @glandais/vcyclist-fit-wasm"
    dependsOn("wasmJsBrowserProductionLibraryDistribution", copyReadmeToWasmPackage)
    workingDir =
        layout.buildDirectory
            .dir("dist/wasmJs/productionLibrary")
            .get()
            .asFile
    commandLine("npm", "publish", "--access", "public")
}

// Gradle 9 strict validation : `*BrowserProductionWebpack` (binaries.executable()) reads the
// directory `*ProductionLibraryCompileSync` (binaries.library()) writes. Declare the ordering,
// exactly as `:engine` and `:elevation` do.
listOf("jsBrowserProductionWebpack", "wasmJsBrowserProductionWebpack").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        mustRunAfter("${name.substringBefore("Browser")}ProductionLibraryCompileSync")
    }
}
