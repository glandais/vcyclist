plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // Pulls :gpx and :elevation transitively, and :fit since task g10.
    implementation(project(":engine"))
    implementation(project(":map"))
    implementation(libs.picocli)
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// picocli-codegen is deliberately NOT wired in. It exists to emit reflection metadata for a
// GraalVM native image, which this project does not build; picocli works by reflection without
// it. Adding kapt or KSP to the build for an optimisation nobody needs would cost every
// contributor build time forever.

// The version shown by `--version`, written into a resource at build time so the CLI reports the
// same string the artefact was published under, and so tests can read it without a manifest.
val generateVersionProperties by tasks.registering {
    val output = layout.buildDirectory.file("generated/version/vcyclist-cli.properties")
    val projectVersion = project.version.toString()
    inputs.property("version", projectVersion)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText("version=$projectVersion\n")
    }
}

sourceSets.named("main") {
    resources.srcDir(layout.buildDirectory.dir("generated/version"))
}

tasks.named("processResources") {
    dependsOn(generateVersionProperties)
}

// `./gradlew :cli:run -Pargs="process --help"`, mirroring the :engine convention.
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the vcyclist CLI"
    dependsOn("classes")
    mainClass.set("io.github.glandais.cli.MainKt")
    classpath = sourceSets.named("main").get().runtimeClasspath
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

// Executable fat jar.
//
// `:cli` is an application, not a library, so it is NOT published to Maven Central — nobody
// should be compiling against a command-line tool. This jar is the distributable, intended for
// attachment to a GitHub release. Recorded for g19.
tasks.register<Jar>("executableJar") {
    group = "distribution"
    description = "Build a self-contained executable jar of the CLI"
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "io.github.glandais.cli.MainKt")
    }
    from(sourceSets.named("main").get().output)
    dependsOn(configurations.named("runtimeClasspath"))
    from({
        configurations
            .named("runtimeClasspath")
            .get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        // Dependency jars carry their own signatures and module descriptors; merging them into
        // one jar makes those invalid, so they are dropped rather than shipped broken.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
        exclude("module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
