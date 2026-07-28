package io.github.glandais.cli

/**
 * Process exit codes, stable so the CLI can be scripted.
 *
 * Values follow the `sysexits.h` convention already used by `EngineCli`, which task g18
 * replaces — keeping them identical means an existing script survives the switch.
 *
 * They are `const` because picocli's `exitCodeList` annotation needs compile-time constants.
 */
object ExitCodes {
    /** Bad or missing arguments (`EX_USAGE`). */
    const val USAGE: Int = 64

    /** No readable input file (`EX_NOINPUT`). */
    const val NO_INPUT: Int = 66

    /** At least one file failed during processing (`EX_SOFTWARE`). */
    const val RUNTIME: Int = 70
}
