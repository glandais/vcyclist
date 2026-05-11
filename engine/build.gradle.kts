plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
    js(IR) { nodejs() }

    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs {
        browser { testTask { useKarma { useChromeHeadless() } } }
        binaries.executable()
        generateTypeScriptDefinitions()
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
        wasmJsMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
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
