import {
    newElevationProvider,
    type ElevationProvider,
    type ElevationProviderConfigDto,
} from '@glandais/vcyclist-elevation';

// One provider for the whole app: it owns an LRU tile cache, so recreating it on every mount
// (or on every tab switch) would refetch DEM tiles the user already paid for.
let provider: ElevationProvider | null = null;

export function useElevationProvider(config: ElevationProviderConfigDto | null = null) {
    if (provider === null) {
        provider = newElevationProvider(config);
    }
    return provider;
}
