package io.github.glandais.cli.mixin

import picocli.CommandLine
import java.io.File

/**
 * Input files and output location, shared by every subcommand.
 *
 * [collectGpxFiles] walks directories recursively and keeps only `.gpx` files, matching
 * gpxtools-cli. Unlike the reference it **returns** the list and reports problems to the caller
 * rather than logging and calling `System.exit` from inside a mixin — a parameter holder that
 * can terminate the process is untestable and surprising.
 */
class FilesMixin {
    @field:CommandLine.Option(
        names = ["-o", "--output"],
        description = ["Output folder (default: \${DEFAULT-VALUE})"],
    )
    var output: File = File("output")

    @field:CommandLine.Option(
        names = ["--cache"],
        description = ["Folder for downloaded tiles and elevation data (default: \${DEFAULT-VALUE})"],
    )
    var cache: File = File(System.getProperty("user.home"), ".vcyclist/cache")

    @field:CommandLine.Parameters(
        paramLabel = "FILE",
        description = ["GPX files or folders to process"],
    )
    var input: MutableList<File> = mutableListOf()

    /**
     * Every `.gpx` under [input], recursing into folders, in a stable order.
     *
     * Non-existent paths and non-GPX files are skipped and reported through [onSkipped] so the
     * caller decides how loudly to complain.
     */
    fun collectGpxFiles(onSkipped: (File, String) -> Unit = { _, _ -> }): List<File> {
        val found = mutableListOf<File>()
        for (entry in input) {
            collect(entry, found, onSkipped)
        }
        return found
    }

    private fun collect(
        file: File,
        found: MutableList<File>,
        onSkipped: (File, String) -> Unit,
    ) {
        when {
            !file.exists() -> onSkipped(file, "does not exist")
            file.isFile ->
                if (file.name.lowercase().endsWith(".gpx")) {
                    found.add(file)
                } else {
                    onSkipped(file, "is not a GPX file")
                }
            file.isDirectory ->
                // Sorted so a directory of files processes in a predictable order, which the
                // reference does not guarantee — `listFiles` order is filesystem-dependent.
                file.listFiles()?.sortedBy { it.name }?.forEach { collect(it, found, onSkipped) }
        }
    }

    /** `{output}/{gpx name without extension}`, created on demand. */
    fun outputFolderFor(gpxFile: File): File = File(output, gpxFile.name.removeSuffix(".gpx").removeSuffix(".GPX")).also { it.mkdirs() }
}
