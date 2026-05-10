plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint)
}

allprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}

tasks.register("regeneratePath") {
    description = "Regenerate engine/.../path/{GeneratedPath,pointFieldAccessors}.kt from PointField.kt"
    group = "codegen"
    dependsOn(":codegen:run")
}
