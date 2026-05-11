import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { onUnmounted, type Ref, shallowRef, watch } from 'vue';
import { getField, pathLatitudeDeg, pathLongitudeDeg, pathSize, type Path } from '~/engine-shim';
import type { HoverInfo } from './useHoverSync';

export function useMap(
    mapContainer: Ref<HTMLElement | null>,
    currentPath: Ref<Path | null>,
    hoveredInfo: Ref<HoverInfo | null>,
    onHoverChange: (index: number | null) => void
) {
    const mapInstance = shallowRef<L.Map | null>(null);
    const routeLayer = shallowRef<L.Polyline | null>(null);
    const hoverMarker = shallowRef<L.CircleMarker | null>(null);
    const hoverPopup = shallowRef<L.Popup | null>(null);
    let nearestPointCache: Array<{ lat: number; lon: number; index: number }> = [];

    const createMap = () => {
        if (!mapContainer.value || mapInstance.value) {
            return;
        }

        const map = L.map(mapContainer.value, {
            center: [0, 0],
            zoom: 13,
            zoomControl: true,
        });

        L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
            attribution:
                '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
            maxZoom: 18,
        }).addTo(map);

        const marker = L.circleMarker([0, 0], {
            radius: 8,
            fillColor: '#ff0000',
            color: '#ffffff',
            weight: 2,
            opacity: 0,
            fillOpacity: 0,
        }).addTo(map);

        const popup = L.popup({
            closeButton: false,
            autoClose: false,
            closeOnClick: false,
        });

        mapInstance.value = map;
        hoverMarker.value = marker;
        hoverPopup.value = popup;

        map.on('mousemove', handleMapHover);
        map.on('mouseout', () => onHoverChange(null));
    };

    const updateRoute = () => {
        if (!mapInstance.value || !currentPath.value) {
            return;
        }

        const path = currentPath.value;
        const pointCount = pathSize(path);

        if (pointCount === 0) {
            return;
        }

        if (routeLayer.value) {
            mapInstance.value.removeLayer(routeLayer.value);
        }

        const coordinates: L.LatLngExpression[] = [];
        nearestPointCache = [];

        for (let i = 0; i < pointCount; i++) {
            const lat = pathLatitudeDeg(path, i);
            const lon = pathLongitudeDeg(path, i);
            coordinates.push([lat, lon]);
            nearestPointCache.push({ lat, lon, index: i });
        }

        const polyline = L.polyline(coordinates, {
            color: '#3388ff',
            weight: 3,
            opacity: 0.7,
        }).addTo(mapInstance.value);

        routeLayer.value = polyline;

        mapInstance.value.fitBounds(polyline.getBounds(), {
            padding: [50, 50],
        });
    };

    const updateHoverMarker = () => {
        if (!hoverMarker.value || !hoverPopup.value || !currentPath.value) {
            return;
        }

        if (!hoveredInfo.value) {
            hoverMarker.value.setStyle({ opacity: 0, fillOpacity: 0 });
            hoverPopup.value.close();
            return;
        }

        const info = hoveredInfo.value;
        const path = currentPath.value;

        hoverMarker.value.setLatLng([info.lat, info.lon]);
        hoverMarker.value.setStyle({ opacity: 1, fillOpacity: 0.8 });

        const elevation = getField(path, info.index, 'elevation');
        const speed = getField(path, info.index, 'speed');
        const distanceKm = (info.distance / 1000).toFixed(2);

        let content = `<strong>Distance:</strong> ${distanceKm} km<br/>`;
        if (Number.isFinite(elevation)) {
            content += `<strong>Elevation:</strong> ${elevation.toFixed(0)} m<br/>`;
        }
        if (Number.isFinite(speed)) {
            content += `<strong>Speed:</strong> ${(speed * 3.6).toFixed(1)} km/h`;
        }

        hoverPopup.value
            .setLatLng([info.lat, info.lon])
            .setContent(content)
            .openOn(mapInstance.value!);
    };

    const handleMapHover = (e: L.LeafletMouseEvent) => {
        if (!currentPath.value || nearestPointCache.length === 0) {
            return;
        }

        const mouseLatLng = e.latlng;
        let nearestIndex = 0;
        let minDistance = Infinity;

        for (let i = 0; i < nearestPointCache.length; i++) {
            const point = nearestPointCache[i];
            const distance =
                Math.pow(point.lat - mouseLatLng.lat, 2) + Math.pow(point.lon - mouseLatLng.lng, 2);

            if (distance < minDistance) {
                minDistance = distance;
                nearestIndex = i;
            }
        }

        const threshold = 0.001; // roughly 100m
        if (minDistance < threshold) {
            onHoverChange(nearestIndex);
        } else {
            onHoverChange(null);
        }
    };

    const fitBounds = () => {
        if (!mapInstance.value || !routeLayer.value) {
            return;
        }

        mapInstance.value.fitBounds(routeLayer.value.getBounds(), {
            padding: [50, 50],
        });
    };

    const destroyMap = () => {
        if (mapInstance.value) {
            mapInstance.value.remove();
            mapInstance.value = null;
        }
        routeLayer.value = null;
        hoverMarker.value = null;
        hoverPopup.value = null;
        nearestPointCache = [];
    };

    watch(currentPath, updateRoute);
    watch(hoveredInfo, updateHoverMarker);

    onUnmounted(destroyMap);

    return {
        mapInstance,
        createMap,
        updateRoute,
        fitBounds,
        destroyMap,
    };
}
