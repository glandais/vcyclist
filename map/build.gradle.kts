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
    // `SrtmMapProducer` wraps the provider's suspend API in `runBlocking`: this module is
    // JVM-only and rendering is synchronous by nature, so `suspend` stops here rather than
    // leaking through the whole drawing API.
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// The FIRST JVM-only module in the repo. `:map` renders with java.awt / ImageIO, which have no
// Kotlin/JS equivalent, so it deliberately does not use the multiplatform plugin.
//
// The invariant that commonMain compiles on three targets is not at risk — `:map` has no
// commonMain — but nothing in `:gpx`, `:engine` or `:fit` may ever depend on it. The dependency
// only points this way.
// Maven Central publication (coordinates, POM, signing) is configured once for every
// module in the root build.gradle.kts — see the `subprojects` block there.
