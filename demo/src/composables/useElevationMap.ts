import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
// Registers `L.gridLayer.relief`. Its ESM build imports leaflet itself, so import order is not
// load-bearing, but keeping it after the leaflet import matches how the plugin documents itself.
import 'leaflet-relief';
import { onUnmounted, type Ref, shallowRef, watch } from 'vue';
import { getElevation, type CoordinatesDto } from '~/elevation-shim';
import { useElevationProvider } from './useElevationProvider';
import type { ExplorerMode } from './useElevationExplorer';

export type ReliefMode = 'off' | 'hillshade' | 'slope';

const PATH_COLOR = '#2c5aa0';

const formatElevation = (elevation: number) => `${elevation.toFixed(1)} m`;
const formatCoordinates = (lat: number, lng: number) => `${lat.toFixed(5)}, ${lng.toFixed(5)}`;

export function useElevationMap(
    mapContainer: Ref<HTMLElement | null>,
    mode: Ref<ExplorerMode>,
    points: Ref<CoordinatesDto[]>,
    onPointClick: (latitude: number, longitude: number) => void
) {
    const provider = useElevationProvider();

    const mapInstance = shallowRef<L.Map | null>(null);
    const reliefLayer = shallowRef<L.GridLayer | null>(null);
    const pathLayer = shallowRef<L.LayerGroup | null>(null);
    const pointMarker = shallowRef<L.CircleMarker | null>(null);
    const reliefMode = shallowRef<ReliefMode>('hillshade');

    const createMap = () => {
        if (!mapContainer.value || mapInstance.value) {
            return;
        }

        const map = L.map(mapContainer.value, {
            center: [45.8, 8.6],
            zoom: 7,
            zoomControl: true,
        });

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution:
                '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 18,
        }).addTo(map);

        pathLayer.value = L.layerGroup().addTo(map);
        mapInstance.value = map;

        map.on('click', handleClick);
        applyRelief(reliefMode.value);
        drawPath();
    };

    const handleClick = (e: L.LeafletMouseEvent) => {
        const { lat, lng } = e.latlng;
        if (mode.value === 'point') {
            void showSinglePoint(lat, lng);
        } else {
            onPointClick(lat, lng);
        }
    };

    // `L.marker` needs its three icon PNGs resolved relative to the CSS, which Vite rewrites —
    // `useMap.ts` sidesteps that with circleMarker for the same reason, so do the same here.
    const markerAt = (lat: number, lng: number, color: string) =>
        L.circleMarker([lat, lng], {
            radius: 6,
            color: '#ffffff',
            weight: 2,
            fillColor: color,
            fillOpacity: 1,
        });

    const showSinglePoint = async (lat: number, lng: number) => {
        const map = mapInstance.value;
        if (!map) {
            return;
        }

        if (pointMarker.value) {
            map.removeLayer(pointMarker.value);
        }

        const marker = markerAt(lat, lng, '#d64545')
            .addTo(map)
            .bindPopup('Getting elevation…', { autoClose: false })
            .openPopup();
        pointMarker.value = marker;

        try {
            const elevation = await getElevation(provider, lat, lng, true);
            marker.setPopupContent(
                `<strong>Elevation: ${formatElevation(elevation)}</strong><br>${formatCoordinates(lat, lng)}`
            );
        } catch (error) {
            marker.setPopupContent(
                `<strong>Error: ${(error as Error).message}</strong><br>${formatCoordinates(lat, lng)}`
            );
        }
    };

    const drawPath = () => {
        const map = mapInstance.value;
        const layer = pathLayer.value;
        if (!map || !layer) {
            return;
        }

        layer.clearLayers();
        const track = points.value;
        if (track.length === 0) {
            return;
        }

        if (track.length > 1) {
            L.polyline(
                track.map(p => [p.latitude, p.longitude] as L.LatLngExpression),
                { color: PATH_COLOR, weight: 3 }
            ).addTo(layer);
        }

        // A loaded GPX track is thousands of points; one marker each would freeze the map. Only
        // hand-clicked paths get per-point markers with their elevation popup.
        if (track.length <= 50) {
            track.forEach((p, i) => {
                const marker = markerAt(p.latitude, p.longitude, PATH_COLOR)
                    .addTo(layer)
                    .bindPopup(`Point ${i + 1}: getting elevation…`);
                void getElevation(provider, p.latitude, p.longitude, true)
                    .then(elevation =>
                        marker.setPopupContent(
                            `<strong>Point ${i + 1}</strong><br>Elevation: ${formatElevation(elevation)}<br>${formatCoordinates(p.latitude, p.longitude)}`
                        )
                    )
                    .catch((error: Error) =>
                        marker.setPopupContent(`<strong>Point ${i + 1} — ${error.message}</strong>`)
                    );
            });
        }
    };

    const fitBounds = () => {
        const map = mapInstance.value;
        if (!map || points.value.length < 2) {
            return;
        }
        map.fitBounds(
            L.latLngBounds(points.value.map(p => [p.latitude, p.longitude] as L.LatLngExpression)),
            { padding: [20, 20] }
        );
    };

    const applyRelief = (next: ReliefMode) => {
        reliefMode.value = next;
        const map = mapInstance.value;
        if (!map) {
            return;
        }

        if (reliefLayer.value) {
            map.removeLayer(reliefLayer.value);
            reliefLayer.value = null;
        }
        if (next !== 'off') {
            reliefLayer.value = L.gridLayer.relief({ mode: next, opacity: 0.6 }).addTo(map);
        }
    };

    /** A Leaflet map laid out while hidden renders grey tiles until it is told to re-measure. */
    const invalidateSize = () => {
        mapInstance.value?.invalidateSize();
    };

    const clearPointMarker = () => {
        if (mapInstance.value && pointMarker.value) {
            mapInstance.value.removeLayer(pointMarker.value);
        }
        pointMarker.value = null;
    };

    const destroyMap = () => {
        if (mapInstance.value) {
            mapInstance.value.remove();
            mapInstance.value = null;
        }
        reliefLayer.value = null;
        pathLayer.value = null;
        pointMarker.value = null;
    };

    watch(points, drawPath, { deep: false });
    watch(mode, next => {
        if (next === 'path') {
            clearPointMarker();
        }
    });

    onUnmounted(destroyMap);

    return {
        mapInstance,
        reliefMode,
        createMap,
        drawPath,
        fitBounds,
        applyRelief,
        invalidateSize,
        destroyMap,
    };
}
