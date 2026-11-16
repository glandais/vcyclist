plugins {
    `java-platform`
    alias(libs.plugins.vanniktech.publish)
}

// `io.github.glandais:vcyclist-bom` — a Maven BOM, nothing else: no code, no jar, one POM whose
// `<dependencyManagement>` pins every artefact one vcyclist release publishes **and** the
// `fit-kotlin-sdk` that release was built and tested against.
//
// Why it exists. `:fit` declares `fit-kotlin-sdk` as `implementation` (see fit/build.gradle.kts):
// `FitCourse` is the public model and the SDK's `com.garmin.fit.*` message classes are a detail of
// the encoder, so the published POM carries the SDK in `runtime` scope. That is right for a
// consumer who only encodes — and leaves a consumer who also *decodes* FIT itself with one option:
// declare `fit-kotlin-sdk` a second time, with a version written by hand. Two versions of
// `com.garmin.fit.*` cannot share a classpath, so the hand-written one silently wins or loses
// against vcyclist's the day a dependency bot bumps it alone. Importing this BOM removes the
// second version number: the consumer names the SDK, the BOM says which one.
//
// Why not `api` in `:fit` instead. It would hand the consumer the right version, and turn every
// generated message class of the SDK into public API of `vcyclist-fit`: a profile bump that
// renames a field would then be a breaking change of vcyclist. A BOM aligns the version without
// widening the surface. The reasoning is recorded in docs/guides/publishing.md.
//
// A `java-platform` has no Kotlin targets, so every per-target artefact is named here by hand.
// Plain Maven does not read Gradle Module Metadata: it needs the `-jvm` coordinates spelled out,
// the root coordinates alone would manage nothing for it.
val kmpModules = listOf("engine", "gpx", "elevation", "fit")
val kmpTargets = listOf("", "-jvm", "-js", "-wasm-wasi")

// `fit-kotlin-sdk` publishes one target vcyclist does not build (`wasm-js`); it is managed too,
// since a consumer of that target is aligned by the same number.
val fitSdkTargets = kmpTargets + "-wasm-js"
val fitSdk =
    libs.fit.kotlin.sdk
        .get()

dependencies {
    constraints {
        kmpModules.forEach { module ->
            kmpTargets.forEach { target -> api("$group:vcyclist-$module$target:$version") }
        }
        // `:map` is Kotlin/JVM only: one artefact, no per-target suffix.
        api("$group:vcyclist-map:$version")

        fitSdkTargets.forEach { target ->
            api("${fitSdk.module.group}:${fitSdk.module.name}$target:${fitSdk.versionConstraint.requiredVersion}")
        }
    }
}

// Coordinates, POM metadata and signing come from the root build.gradle.kts `subprojects` block,
// like every other published module: this one is `vcyclist-bom`.
