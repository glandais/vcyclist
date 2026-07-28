plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.publish)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":gpx"))
    // Not used yet; g15 renders an elevation profile and will need it. Declared here so the
    // module's dependency surface is settled once rather than churned per task.
    api(project(":elevation"))
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The FIRST JVM-only module in the repo. `:map` renders with java.awt / ImageIO, which have no
// Kotlin/JS or Wasm equivalent, so it deliberately does not use the multiplatform plugin.
//
// The invariant that commonMain compiles on four targets is not at risk — `:map` has no
// commonMain — but nothing in `:gpx`, `:engine` or `:fit` may ever depend on it. The dependency
// only points this way.
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "vcyclist-${project.name}", version.toString())
    pom {
        name.set("vcyclist-${project.name}")
        description.set(
            "Physics-based cycling simulator ported to Kotlin Multiplatform — ${project.name} module (JVM only)",
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
