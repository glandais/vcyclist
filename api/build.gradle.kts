import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // `describe {}` / `hide()` of ktor-server-routing-openapi are experimental. Opted in for
        // the whole module rather than per call site: the OpenAPI contract test sees any break.
        optIn.add("io.ktor.utils.io.ExperimentalKtorApi")
    }
}

dependencies {
    // Pulls :gpx, :elevation and :fit transitively. `:api` is an application, like `:cli`: it may
    // depend on `:map`, and nothing may depend on `:api`.
    implementation(project(":engine"))
    implementation(project(":map"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.routing.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly(libs.logback.classic)
    testImplementation(kotlin("test"))
    // HttpDoorParityTest / HttpForwardingTest read OptionCatalog by reflection. Test scope only,
    // and no cycle: `:codegen` does not depend on `:api` (nothing does).
    testImplementation(project(":codegen"))
    testImplementation(kotlin("reflect"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
}

// GpxFixtures is a plain Kotlin `object` in :gpx's common test fixtures; wired in as a source
// directory, as :engine does, rather than published as a test artefact.
sourceSets.named("test") {
    kotlin.srcDir(rootProject.file("gpx/src/commonTestFixtures/kotlin"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // OpenApiContractTest compares the committed contract with the rendered one. Declared as a
    // DIRECTORY (CLAUDE.md): a hand-edited contract must invalidate the cached test result.
    inputs
        .dir(layout.projectDirectory.dir("contract"))
        .withPropertyName("committedOpenApiContract")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

// The engine version reported by `GET /api/v1/version`, written at build time like :cli's.
val generateVersionProperties =
    tasks.register("generateVersionProperties") {
        val output = layout.buildDirectory.file("generated/version/vcyclist-api.properties")
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

// `./gradlew :api:run -Pargs="--port 8081"`, mirroring :cli.
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run the vcyclist HTTP API"
    dependsOn("classes")
    mainClass.set("io.github.glandais.api.MainKt")
    classpath = sourceSets.named("main").get().runtimeClasspath
    args = (project.findProperty("args") as String?)?.split(" ")?.filter { it.isNotBlank() } ?: emptyList()
}

// `./gradlew :api:generateOpenApi` — renders the contract from the routing tree and writes
// api/contract/openapi.yaml. Never edit that file by hand; OpenApiContractTest fails when stale.
tasks.register<JavaExec>("generateOpenApi") {
    group = "documentation"
    description = "Regenerate api/contract/openapi.yaml from the Ktor routing tree"
    dependsOn("classes")
    workingDir = rootDir
    mainClass.set("io.github.glandais.api.openapi.GenerateOpenApiKt")
    classpath = sourceSets.named("main").get().runtimeClasspath
}

// Executable fat jar, not published to Maven Central (an application, like :cli).
tasks.register<Jar>("executableJar") {
    group = "distribution"
    description = "Build a self-contained executable jar of the HTTP API"
    archiveBaseName.set("vcyclist-api")
    archiveClassifier.set("all")
    manifest {
        attributes("Main-Class" to "io.github.glandais.api.MainKt")
    }
    val runtimeClasspath = configurations.named("runtimeClasspath")
    from(sourceSets.named("main").get().output)
    dependsOn(runtimeClasspath)
    from({
        runtimeClasspath
            .get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
        exclude("module-info.class")
        // ServiceLoader files are merged below, never first-one-wins.
        exclude("META-INF/services/**")
    }
    // Ktor finds its engine, serialization and config loaders through ServiceLoader; several
    // jars ship the same META-INF/services file, and DuplicatesStrategy.EXCLUDE would keep only
    // the first one. Concatenate them instead.
    val mergedServices = layout.buildDirectory.dir("merged-services")
    doFirst {
        val dir = mergedServices.get().asFile.resolve("META-INF/services")
        dir.deleteRecursively()
        dir.mkdirs()
        val entries = sortedMapOf<String, MutableSet<String>>()
        runtimeClasspath.get().filter { it.name.endsWith("jar") }.forEach { jar ->
            ZipFile(jar).use { zip ->
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }
                    .forEach { entry ->
                        val lines =
                            zip
                                .getInputStream(entry)
                                .bufferedReader()
                                .readLines()
                                .map { it.substringBefore('#').trim() }
                                .filter { it.isNotEmpty() }
                        entries.getOrPut(entry.name.removePrefix("META-INF/services/")) { linkedSetOf() }.addAll(lines)
                    }
            }
        }
        entries.forEach { (name, lines) -> dir.resolve(name).writeText(lines.joinToString("\n", postfix = "\n")) }
    }
    from(mergedServices)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
