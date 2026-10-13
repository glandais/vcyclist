package io.github.glandais.api

/**
 * The collaborators a test replaces: everything that reaches the network or the disk.
 *
 * Empty for now; the elevation gateway (DEM tiles) and the map tile fetcher join it as the routes
 * that need them land. [production] is what `Main` and the OpenAPI generator use.
 */
class ApiDependencies {
    companion object {
        /** The real collaborators, configured from [config]. */
        @Suppress("UNUSED_PARAMETER")
        fun production(config: ApiConfig): ApiDependencies = ApiDependencies()
    }
}
