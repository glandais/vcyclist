plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.github.glandais.codegen.GeneratePathKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
