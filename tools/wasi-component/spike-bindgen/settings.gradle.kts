// Standalone build on purpose: this spike is NOT part of the vcyclist Gradle build (task w13
// forbids touching it). It consumes `:engine` from mavenLocal — `./gradlew publishToMavenLocal`
// in the repo root first.
rootProject.name = "wasi-component-bindgen-spike"
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        mavenCentral()
    }
}
