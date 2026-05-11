import { computed, type Ref, ref } from 'vue';
import { getField, pathLatitudeDeg, pathLongitudeDeg, pathSize, type Path } from '~/engine-shim';

export interface HoverInfo {
    index: number;
    distance: number;
    lat: number;
    lon: number;
}

export function useHoverSync(currentPath: Ref<Path | null>) {
    const hoveredIndex = ref<number | null>(null);

    const hoveredInfo = computed<HoverInfo | null>(() => {
        if (hoveredIndex.value === null || !currentPath.value) {
            return null;
        }

        const path = currentPath.value;
        const idx = hoveredIndex.value;

        if (idx < 0 || idx >= pathSize(path)) {
            return null;
        }

        return {
            index: idx,
            distance: getField(path, idx, 'distance'),
            lat: pathLatitudeDeg(path, idx),
            lon: pathLongitudeDeg(path, idx),
        };
    });

    const setHoveredIndex = (index: number | null) => {
        hoveredIndex.value = index;
    };

    const clearHover = () => {
        hoveredIndex.value = null;
    };

    return {
        hoveredIndex,
        hoveredInfo,
        setHoveredIndex,
        clearHover,
    };
}
