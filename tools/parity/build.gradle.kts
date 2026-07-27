plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":engine"))
    implementation(project(":elevation"))
    implementation(libs.kotlinx.coroutines.core)
}

/**
 * Kotlin side of the end-to-end parity cascade. Mirrors `tools/parity/ts/pipelineDump.ts`.
 *
 *   ./gradlew :tools:parity:dumpPipeline -Pargs="--gpx <file.gpx> --out <dir> [--simplify]"
 */
tasks.register<JavaExec>("dumpPipeline") {
    group = "parity"
    description = "Dump the Kotlin Enhancer pipeline stage by stage (see tools/parity/README.md)"
    mainClass.set("io.github.glandais.parity.PipelineDumpKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

/** Kotlin side of the unit-level (pure function) parity sweep. */
tasks.register<JavaExec>("dumpUnits") {
    group = "parity"
    description = "Evaluate the shared sentinel cases against the Kotlin implementations"
    mainClass.set("io.github.glandais.parity.UnitDumpKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

/** Elevation (DEM) parity sweep against live Terrarium tiles. Needs network. */
tasks.register<JavaExec>("dumpElevation") {
    group = "parity"
    description = "Resolve the shared coordinate list through the JVM WebP decoder"
    mainClass.set("io.github.glandais.parity.ElevationDumpKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}
