package io.github.glandais.cli

import picocli.CommandLine
import kotlin.system.exitProcess

/**
 * Entry point. Delegates to picocli, which handles parsing, help, version and error reporting,
 * and returns the exit code it decided on.
 */
fun main(args: Array<String>) {
    exitProcess(CommandLine(RootCommand()).execute(*args))
}
