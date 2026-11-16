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

include(":elevation", ":gpx", ":engine", ":fit", ":map", ":bom", ":cli", ":api", ":codegen", ":demo")
