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
        // bundle, so consumers keep installing a single package. See docs/publishing.md.
    }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { useKarma { useChromeHeadless() } } }
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
            // GpxFixtures is shared verbatim with `:engine`'s parity and JS-façade tests. KMP
            // has no `java-test-fixtures` equivalent, so the single source file is compiled
            // into both test compilations rather than duplicated on disk.
            kotlin.srcDir("src/commonTestFixtures/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
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
