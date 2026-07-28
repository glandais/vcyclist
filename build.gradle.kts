plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.node.gradle) apply false
}

allprojects {
    if (name != "demo") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
    group = rootProject.group
    version = rootProject.version
}

// ── Maven Central publication, configured once for every module that opts in ────────────────
//
// Before task g19 this ~35-line POM block was copy-pasted into five build files. Five drifting
// copies of the same metadata is a problem that surfaces at the worst possible moment — during
// a release — so it lives here, applied reactively to whichever modules apply the publish
// plugin. A module opts in simply by declaring `alias(libs.plugins.vanniktech.publish)`.
//
// Works for both KMP modules and plain Kotlin/JVM ones (`:map`), which was the open question
// from g13.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        // Captured OUTSIDE the `pom { }` block below: inside it, `name` resolves to the POM's
        // own `name` property, not the project's, which silently produced an artefact named
        // "vcyclist-property 'name'". Caught by reading a generated POM rather than by the build
        // succeeding — it succeeded perfectly well.
        val moduleName = name

        extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
            publishToMavenCentral()

            // Sign only when a key is actually available — i.e. in CI, where the release
            // workflow provides GPG_PRIVATE_KEY. Signing unconditionally makes
            // `./gradlew publishToMavenLocal` fail on a developer machine, which is precisely
            // the command `docs/publishing.md` tells you to run to inspect an artefact before a
            // release, and the one task g19 needs in order to verify a real consumer.
            val hasSigningKey =
                providers.gradleProperty("signingInMemoryKey").isPresent ||
                    providers.gradleProperty("signing.keyId").isPresent ||
                    providers.environmentVariable("ORG_GRADLE_PROJECT_signingInMemoryKey").isPresent
            if (hasSigningKey) {
                signAllPublications()
            }

            coordinates(group.toString(), "vcyclist-$moduleName", version.toString())
            pom {
                name.set("vcyclist-$moduleName")
                description.set(
                    "Physics-based cycling simulator ported to Kotlin Multiplatform — $moduleName module",
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
    }
}

tasks.register("regeneratePath") {
    description = "Regenerate engine/.../path/{GeneratedPath,pointFieldAccessors}.kt from PointField.kt"
    group = "codegen"
    dependsOn(":codegen:run")
}
