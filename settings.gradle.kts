rootProject.name = "vcyclist"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(":elevation", ":gpx", ":engine", ":fit", ":map", ":cli", ":codegen", ":demo")

// Numeric parity harness against the TypeScript reference (not published; see
// tools/parity/README.md).
include(":tools:parity")
