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

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { useKarma { useChromeHeadless() } } }
        binaries.executable()
        binaries.library()
        generateTypeScriptDefinitions()
        compilations.named("main") {
            packageJson {
                customField("name", "@glandais/vcyclist-engine-wasm")
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
            implementation(libs.kotlinx.datetime)
            implementation(libs.xmlutil.core)
            implementation(libs.xmlutil.serialization)
            api(project(":elevation"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
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

// npm publishing tasks.
//
// `binaries.library()` on `js(IR)` and `wasmJs` produces a distributable npm package under
// `build/dist/{js,wasmJs}/productionLibrary/`. The package.json there is auto-generated from
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

val copyReadmeToWasmPackage =
    tasks.register<Copy>("copyReadmeToWasmPackage") {
        from(rootProject.layout.projectDirectory.file("README.md"))
        into(layout.buildDirectory.dir("dist/wasmJs/productionLibrary"))
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

tasks.register<Exec>("npmPublishWasm") {
    group = "publishing"
    description = "Publish the Kotlin/Wasm library to npm as @glandais/vcyclist-engine-wasm"
    dependsOn("wasmJsBrowserProductionLibraryDistribution", copyReadmeToWasmPackage)
    workingDir =
        layout.buildDirectory
            .dir("dist/wasmJs/productionLibrary")
            .get()
            .asFile
    commandLine("npm", "publish", "--access", "public")
}

// Gradle 9 strict validation catches that `*BrowserProductionWebpack` (from
// binaries.executable()) reads `build/js/packages/<name>/` while
// `*ProductionLibraryCompileSync` (from binaries.library()) writes there. We declare the
// ordering explicitly so `./gradlew assemble` or any task graph that schedules both can
// run them in the right order. Without this, Gradle 9 fails with an implicit-dependency
// validation error.
listOf("jsBrowserProductionWebpack", "wasmJsBrowserProductionWebpack").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        mustRunAfter("${name.substringBefore("Browser")}ProductionLibraryCompileSync")
    }
}

// JVM `run` task for the EngineCli smoke entry point (task 27). KMP doesn't expose the
// usual `application {}` plugin, so we register a JavaExec ourselves. The classpath is
// the compiled `jvmMain` classes plus the resolved `jvmRuntimeClasspath` configuration.
// Args are forwarded via `-Pargs="..."` :
//
//   ./gradlew :engine:run -Pargs="enhance input.gpx -o /tmp/out.gpx"
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run io.github.glandais.engine.EngineCli (JVM smoke entry point)"
    dependsOn("jvmMainClasses")
    val jvmMain =
        kotlin.targets
            .getByName("jvm")
            .compilations
            .getByName("main")
    classpath = files(jvmMain.output.allOutputs) + configurations.getByName("jvmRuntimeClasspath")
    mainClass.set("io.github.glandais.engine.EngineCli")
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
