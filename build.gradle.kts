plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.vanniktech.publish) apply false
    alias(libs.plugins.node.gradle) apply false
}

allprojects {
    if (name != "demo") {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
    }
    group = rootProject.group
    version = rootProject.version
}

tasks.register("regeneratePath") {
    description = "Regenerate engine/.../path/{GeneratedPath,pointFieldAccessors}.kt from PointField.kt"
    group = "codegen"
    dependsOn(":codegen:run")
}
