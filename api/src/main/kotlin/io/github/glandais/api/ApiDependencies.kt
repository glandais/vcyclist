package io.github.glandais.api

import io.github.glandais.api.service.ElevationGateway
import io.github.glandais.map.HttpTileFetcher
import io.github.glandais.map.TileFetcher

/**
 * The collaborators a test replaces: everything that reaches the network. [production] is what
 * `Main` and the OpenAPI generator use.
 *
 * @property elevation the DEM access of `fixElevation`, of the elevation routes and of terrain maps
 * @property tileFetcher how `/map/tiles` downloads its background tiles, from the server's own
 *   `mapTileUrlTemplate` — never a URL the client names. The tiles are cached on disk under
 *   `<cacheDir>/tiles`
 */
class ApiDependencies(
    val elevation: ElevationGateway = ElevationGateway(),
    val tileFetcher: TileFetcher = HttpTileFetcher(),
) {
    companion object {
        /** The real collaborators, configured from [config]. */
        @Suppress("UNUSED_PARAMETER")
        fun production(config: ApiConfig): ApiDependencies =
            ApiDependencies(elevation = ElevationGateway(), tileFetcher = HttpTileFetcher())
    }
}
