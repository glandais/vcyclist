# 27 — Engine : `EngineCli` (JVM smoke entry point)

## Goal

Fournir un point d'entrée **JVM-only** (`main()`) qui démontre le pipeline complet bout-en-bout sur un GPX d'entrée. Usage :

```bash
./gradlew :engine:run --args="enhance ../virtual-cyclist/gpx/sample.gpx -o /tmp/out.gpx"
```

Le CLI :
1. Parse un fichier GPX d'entrée
2. Lance `Enhancer.enhanceCourseDefault(path, elevationProvider = null, options = EnhanceOptions.DEFAULT.copy(fixElevation = false))`
3. Sérialise le path résultat en GPX et l'écrit dans le fichier de sortie
4. Affiche un récapitulatif (taille input, taille output, distance, durée, gain alt)

API minimaliste — pas de parser CLI sophistiqué (Clikt etc.). Arguments positionnels simples.

**Hors scope** : interface interactive, options multiples (juste `enhance <input> -o <output>` pour démarrer). Élargir plus tard.

## Depends on

- `25-engine-enhancer` (pipeline)
- `14-engine-gpx-parser` + `15-engine-gpx-writer`

## Inputs

- `/home/glandais/code/perso/vcyclist-all/virtual-cyclist/` (TS, pour inspiration du CLI s'il existe — sinon créer from scratch)
- `gpx2web/gpxtools-cli/` (Kotlin/Java CLI référence — inspiration sur les arguments)

## Steps

### 1. `EngineCli.kt`

`engine/src/jvmMain/kotlin/io/github/glandais/engine/EngineCli.kt` :

```kotlin
package io.github.glandais.engine

import io.github.glandais.engine.gpx.GpxParser
import io.github.glandais.engine.gpx.GpxWriter
import io.github.glandais.engine.gpx.firstTrackAsPath
import io.github.glandais.engine.gpx.toGpxDocument
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * JVM entry point for the engine CLI. Minimal usage :
 *
 * ```
 * engine enhance <input.gpx> -o <output.gpx>
 * ```
 *
 * Runs [Enhancer.enhanceCourseDefault] with default options (no elevation fix, since this
 * runs without a network ElevationProvider).
 */
object EngineCli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            printUsage()
            exitProcess(64)  // EX_USAGE
        }
        when (args[0]) {
            "enhance" -> enhance(args.drop(1))
            "help", "-h", "--help" -> printUsage()
            else -> {
                System.err.println("Unknown command : ${args[0]}")
                printUsage()
                exitProcess(64)
            }
        }
    }

    private fun enhance(args: List<String>) {
        if (args.isEmpty()) {
            System.err.println("Missing input file")
            printUsage()
            exitProcess(64)
        }
        val input = File(args[0])
        val outputIdx = args.indexOf("-o")
        val output = if (outputIdx >= 0 && outputIdx + 1 < args.size) File(args[outputIdx + 1]) else null

        if (!input.exists()) {
            System.err.println("Input file does not exist : ${input.absolutePath}")
            exitProcess(66) // EX_NOINPUT
        }

        println("Reading $input")
        val xml = input.readText()
        val doc = GpxParser.parse(xml)
        val inputPath = doc.firstTrackAsPath()
        println("  ↳ ${inputPath.size} points, ${"%.1f".format(inputPath.totalDistance)} m, gain ${"%.1f".format(inputPath.elevationGain)} m")

        println("Running pipeline (fixElevation=false)…")
        val result = runBlocking {
            Enhancer.enhanceCourseDefault(
                inputPath,
                elevationProvider = null,
                options = EnhanceOptions.DEFAULT.copy(fixElevation = false),
            )
        }
        println("  ↳ ${result.size} points, ${"%.1f".format(result.totalDistance)} m, duration ${"%.1f".format(result.durationMs / 1000.0)} s")

        if (output != null) {
            val outXml = GpxWriter.write(result.toGpxDocument(name = input.nameWithoutExtension, trackName = "virtualized"))
            output.parentFile?.mkdirs()
            output.writeText(outXml)
            println("Wrote $output")
        } else {
            println("(no -o flag : skipping output)")
        }
    }

    private fun printUsage() {
        println("""
            |Usage : engine enhance <input.gpx> [-o <output.gpx>]
            |        engine help
            |
            |Runs the virtual-cyclist enhancement pipeline on the input GPX file with default
            |Cyclist (80 kg / 280 W) and Bike (Crr 0.004) parameters. No elevation correction
            |is performed (no HTTP access).
        """.trimMargin())
    }
}
```

### 2. Configuration Gradle `:engine`

`engine/build.gradle.kts` — ajouter la cible runnable JVM :

```kotlin
kotlin {
    // ...
    jvm {
        // already present
    }
}

// At root of file or after kotlin block:
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Run EngineCli"
    classpath = kotlin.targets.getByName("jvm").compilations.getByName("main").let { c ->
        c.output.classesDirs + c.runtimeDependencyFiles
    }
    mainClass.set("io.github.glandais.engine.EngineCli")
    // Forward args via -Pargs="..."
    args = (project.findProperty("args") as String?)?.split(" ") ?: emptyList()
}
```

Vérifier que `kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles` existe sur la version Kotlin Multiplatform actuelle. Sinon, utiliser une approche alternative :

```kotlin
val engineCliRun by tasks.registering(JavaExec::class) {
    dependsOn("jvmJar")
    classpath = files(tasks.named<Jar>("jvmJar").get().archiveFile) +
        configurations.getByName("jvmRuntimeClasspath")
    mainClass.set("io.github.glandais.engine.EngineCli")
}
```

**Note** : KMP n'expose pas directement `application { mainClass.set(...) }`. La task `JavaExec` est le pattern usuel.

### 3. Tests `EngineCliSmokeTest.kt`

`engine/src/jvmTest/kotlin/io/github/glandais/engine/EngineCliSmokeTest.kt`. JVM-only.

Cas (≥ 3) :

| # | Cas | Attendu |
|---|---|---|
| 1 | `EngineCli.main(emptyArray())` avec capture System.err → message d'usage | propriété |
| 2 | `EngineCli.main(arrayOf("enhance", "/nonexistent.gpx"))` → exit code 66 (EX_NOINPUT), message d'erreur | propriété |
| 3 | Pipeline complet avec fichier réel : créer un fichier temporaire avec `GpxFixtures.SAMPLE_GPX_XML`, lancer `enhance`, vérifier fichier de sortie existe et parseable | propriété |

Pour capturer `System.err`/`exitProcess` en test : difficile (`exitProcess` tue la JVM). Utiliser plutôt **un point d'entrée testable** qui retourne un `Int` exit code au lieu d'appeler `exitProcess` directement :

```kotlin
fun runCli(args: Array<String>): Int { ... return exitCode }

@JvmStatic
fun main(args: Array<String>) {
    val code = runCli(args)
    if (code != 0) exitProcess(code)
}
```

Test :
```kotlin
@Test fun `usage when no args`() {
    val code = EngineCli.runCli(emptyArray())
    assertEquals(64, code)
}
```

### 4. Vérification ktlint + smoke test manuel

```bash
./gradlew :engine:build
./gradlew :engine:run -Pargs="enhance ../virtual-cyclist/gpx/sample.gpx -o /tmp/sample-virtualized.gpx"
cat /tmp/sample-virtualized.gpx | head -20
```

L'output doit être un GPX bien formé contenant la trace simulée.

## Outputs

Créés :

- `engine/src/jvmMain/kotlin/io/github/glandais/engine/EngineCli.kt`
- `engine/src/jvmTest/kotlin/io/github/glandais/engine/EngineCliSmokeTest.kt`

Modifié :

- `engine/build.gradle.kts` (ajout task `run`)

## Validation

```bash
./gradlew :engine:build
./gradlew :engine:jvmTest --tests '*EngineCliSmoke*'
./gradlew :engine:allTests
./gradlew :elevation:allTests
./gradlew ktlintCheck
```

Critères :

- `EngineCli.runCli` retourne le bon exit code dans 3 scénarios.
- `./gradlew :engine:run -Pargs="enhance <gpx> -o <out>"` produit un fichier GPX valide (vérification manuelle ou via smoke test).
- `:elevation:allTests` toujours vert.

## Done when

- [x] `EngineCli.kt` créé en `jvmMain`
- [x] Task Gradle `run` opérationnelle
- [x] `EngineCliSmokeTest.kt` ≥ 3 tests verts
- [x] Smoke test manuel passé : produit un fichier `.gpx` valide
- [x] `:engine:allTests` vert ; `:elevation:allTests` toujours vert
- [x] `ktlintCheck` vert
- [x] Toutes les checkboxes cochées

## Notes

- **JVM-only** : `EngineCli` vit en `jvmMain/`, pas en `commonMain`. Utilise `java.io.File`, `runBlocking`, `exitProcess`.
- **`runBlocking`** : pour appeler `Enhancer.enhanceCourse` (suspend) depuis `main`. Pas idéal côté API mais standard pour un CLI.
- **`runCli` testable** : sépare la logique de `exitProcess` pour permettre les tests sans tuer la JVM.
- **Format de sortie GPX** : `GpxWriter.write(path.toGpxDocument(...))` — réutilise les tâches 14/15.
- **Pas de parser CLI** : args positionnels simples + flag `-o`. Si on veut plus de flexibilité plus tard, ajouter `clikt` ou `kotlinx-cli`. Mais pour un smoke test, tout-en-Kotlin standard suffit.
- **`-Pargs` Gradle property** : pattern usuel pour passer des arguments à `JavaExec`. Documenté dans `printUsage`.
- **Préparation tâche 28** : API JS/Wasm exposant `Enhancer.enhanceCourse` via `@JsExport` (cf. bonus commit `a095ff8` sur le module `:elevation`).
