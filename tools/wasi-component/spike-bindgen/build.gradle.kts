plugins {
    kotlin("multiplatform") version "2.4.20-Beta2"
}

repositories { mavenLocal(); mavenCentral() }

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmWasi {
        binaries.executable()
    }
    sourceSets {
        val wasmWasiMain by getting {
            dependencies {
                // The version comes from the repo root's gradle.properties; reproduce.sh passes it.
                implementation("io.github.glandais:vcyclist-engine:${findProperty("engineVersion") ?: "3.0.0"}")
            }
        }
    }
}
