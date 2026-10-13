package io.github.glandais.api

import io.github.glandais.api.service.ElevationGateway

/**
 * The collaborators a test replaces: everything that reaches the network or the disk.
 *
 * The map tile fetcher joins them when the map routes land. [production] is what `Main` and the
 * OpenAPI generator use.
 *
 * @property elevation the DEM access of `fixElevation` and of the elevation routes
 */
class ApiDependencies(
    val elevation: ElevationGateway = ElevationGateway(),
) {
    companion object {
        /** The real collaborators, configured from [config]. */
        @Suppress("UNUSED_PARAMETER")
        fun production(config: ApiConfig): ApiDependencies = ApiDependencies(elevation = ElevationGateway())
    }
}
