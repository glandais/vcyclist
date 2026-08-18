package io.github.glandais.cli

import io.github.glandais.cli.command.EnhanceCommand
import io.github.glandais.cli.command.ExportCommand
import picocli.CommandLine
import java.util.Properties

/**
 * Root of the vcyclist CLI.
 *
 * Subcommands are added in task g17; this is the frame — help, version, and the shared
 * parameter mixins.
 */
@CommandLine.Command(
    name = "vcyclist",
    mixinStandardHelpOptions = true,
    versionProvider = BuildVersionProvider::class,
    description = ["Turn GPS traces into physics-aware virtualized rides."],
    subcommands = [EnhanceCommand::class, ExportCommand::class, CommandLine.HelpCommand::class],
)
class RootCommand : Runnable {
    @CommandLine.Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    /** With no subcommand, print usage rather than doing nothing silently. */
    override fun run() {
        spec.commandLine().usage(spec.commandLine().out)
    }
}

/**
 * Reports the version the artefact was built as.
 *
 * Read from a properties file the build generates rather than from the jar manifest, so it is
 * also correct when running from a classpath — `./gradlew :cli:run` and the tests included.
 */
class BuildVersionProvider : CommandLine.IVersionProvider {
    override fun getVersion(): Array<String> = arrayOf("vcyclist ${readVersion()}")

    companion object {
        internal const val RESOURCE = "/vcyclist-cli.properties"

        internal fun readVersion(): String =
            BuildVersionProvider::class.java.getResourceAsStream(RESOURCE)?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version")
            } ?: "unknown"
    }
}
