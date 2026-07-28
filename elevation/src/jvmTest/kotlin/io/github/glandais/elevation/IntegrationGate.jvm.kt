package io.github.glandais.elevation

actual fun integrationEnabled(): Boolean = System.getenv("INTEGRATION") == "1" || System.getProperty("integration") == "true"
