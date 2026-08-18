import com.github.gradle.node.npm.task.NpmTask

plugins {
    alias(libs.plugins.node.gradle)
}

node {
    download.set(true)
    version.set("22.12.0")
    npmVersion.set("10.9.0")
    workDir.set(layout.buildDirectory.dir("nodejs"))
    nodeProjectDir.set(projectDir)
}

// The demo resolves both npm packages through a vite alias onto these directories (see
// vite.config.ts), so they must exist before npm runs. `generateTsFacade` is in the chain because
// tsconfig.json type-checks against the GENERATED index.d.ts, not the Kotlin-emitted one.
val engineDist =
    tasks.register("engineDist") {
        description = "Builds the Kotlin/JS libraries and the TypeScript facade this demo aliases."
        dependsOn(
            ":engine:jsBrowserProductionLibraryDistribution",
            ":elevation:jsBrowserProductionLibraryDistribution",
            ":codegen:generateTsFacade",
        )
    }

tasks.named("npmInstall") {
    dependsOn(engineDist)
}

val npmBuild =
    tasks.register<NpmTask>("npmBuild") {
        group = "build"
        description = "Run `npm run build` to produce the static Vue/Vite bundle under demo/dist."
        dependsOn("npmInstall")
        args.set(listOf("run", "build"))
        inputs.dir("src")
        inputs.file("package.json")
        inputs.file("package-lock.json")
        inputs.file("vite.config.ts")
        inputs.file("tsconfig.json")
        inputs.file("tsconfig.node.json")
        inputs.file("index.html")
        inputs.dir("public")
        outputs.dir("dist")
    }

val npmTypecheck =
    tasks.register<NpmTask>("npmTypecheck") {
        group = "verification"
        description = "Run vue-tsc to verify TypeScript correctness."
        dependsOn("npmInstall")
        args.set(listOf("run", "typecheck"))
    }

val npmLint =
    tasks.register<NpmTask>("npmLint") {
        group = "verification"
        description = "Run ESLint over the demo sources."
        dependsOn("npmInstall")
        args.set(listOf("run", "lint"))
    }

tasks.register("assemble") {
    group = "build"
    description = "Build the demo's static dist/ bundle."
    dependsOn(npmBuild)
}

tasks.register("check") {
    group = "verification"
    description = "Type-check + lint the demo (no UI tests yet)."
    dependsOn(npmTypecheck, npmLint)
}

tasks.register<Delete>("clean") {
    group = "build"
    description = "Remove dist/ and node_modules/."
    delete("dist", "node_modules")
}
