package io.github.glandais.fit

/**
 * Shared message for the JS and Wasm [FitEncoder] placeholders, so both targets fail with the
 * same, actionable text instead of a bare `NotImplementedError`.
 */
internal const val NOT_IMPLEMENTED_MESSAGE: String =
    "FIT encoding is only available on the JVM target for now. The JS and Wasm encoders " +
        "(@garmin/fitsdk) arrive in gpx2web task g09."
