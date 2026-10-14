import { computed, ref } from 'vue';
import {
    elevationAttribution,
    newElevationProvider,
    type ElevationProvider,
} from '@glandais/vcyclist-elevation';
import type { DemSource } from '~/types';

// One provider per source for the whole app: each owns an LRU tile cache, so recreating it on
// every mount, tab switch or source toggle would refetch DEM tiles the user already paid for.
const providers = new Map<DemSource, ElevationProvider>();

// Shared by the map and the profile, so a point and a path never read two different DEMs.
// Mapterhorn, not ElevationSource.DEFAULT: the explorer reads whole map areas, often abroad, and
// IGN's z15 tiles cost ~30 times Mapterhorn's bytes. IGN stays one click away.
const source = ref<DemSource>('mapterhorn');

export function useElevationProvider() {
    const provider = computed(() => {
        let p = providers.get(source.value);
        if (!p) {
            p = newElevationProvider({ source: source.value });
            providers.set(source.value, p);
        }
        return p;
    });
    // The source's own credit: IGN's licence requires it to be displayed, like Mapterhorn's.
    const attribution = computed(() => elevationAttribution(provider.value));
    return { source, provider, attribution };
}
