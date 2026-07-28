import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { onUnmounted, type Ref, shallowRef, watch } from 'vue';
import {
    type ClimbDto,
    getField,
    pathLatitudeDeg,
    pathLongitudeDeg,
    pathSize,
    type Path,
} from '~/engine-shim';
import { gradeColor } from './useClimbs';
import type { HoverInfo } from './useHoverSync';

export function useMap(
    mapContainer: Ref<HTMLElement | null>,
    currentPath: Ref<Path | null>,
    hoveredInfo: Ref<HoverInfo | null>,
    onHoverChange: (index: number | null) => void,
    climbs: Ref<ClimbDto[]>
) {
    const mapInstance = shallowRef<L.Map | null>(null);
    const routeLayer = shallowRef<L.Polyline | null>(null);
    const climbLayer = shallowRef<L.LayerGroup | null>(null);
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
        drawClimbs();

        mapInstance.value.fitBounds(polyline.getBounds(), {
            padding: [50, 50],
        });
    };

    /**
     * Overlay each ClimbPart on top of the route in its grade colour, so a climb reads at a
     * glance and its steep sections stand out. Drawn thicker than the base trace and added
     * after it, so it wins the z-order.
     */
    const drawClimbs = () => {
        if (!mapInstance.value || !currentPath.value) {
            return;
        }
        if (climbLayer.value) {
            mapInstance.value.removeLayer(climbLayer.value);
            climbLayer.value = null;
        }
        if (climbs.value.length === 0) {
            return;
        }

        const path = currentPath.value;
        const pointCount = pathSize(path);
        const group = L.layerGroup();

        // Read each point's distance and position ONCE. Every one of these is a call across the
        // JS/Kotlin boundary, so the obvious nested loop — for each part, scan the climb's points
        // — would cross it parts x length times (~20 000 on sample.gpx). One pass instead.
        const distances = new Float64Array(pointCount);
        const positions: Array<[number, number]> = new Array(pointCount);
        for (let i = 0; i < pointCount; i++) {
            distances[i] = getField(path, i, 'distance');
            positions[i] = [pathLatitudeDeg(path, i), pathLongitudeDeg(path, i)];
        }

        for (const climb of climbs.value) {
            const last = Math.min(climb.endIndex, pointCount - 1);
            // Parts are contiguous and ordered, so a single cursor walks the climb once.
            let cursor = climb.startIndex;
            for (const part of climb.parts) {
                const coords: L.LatLngExpression[] = [];
                // Include the point that opens the part so consecutive parts join up visually.
                while (cursor > climb.startIndex && distances[cursor - 1] >= part.startDistanceM) {
                    cursor--;
                }
                while (cursor <= last && distances[cursor] <= part.endDistanceM) {
                    coords.push(positions[cursor]);
                    cursor++;
                }
                if (coords.length >= 2) {
                    L.polyline(coords, {
                        color: gradeColor(part.grade),
                        weight: 6,
                        opacity: 0.85,
                    }).addTo(group);
                }
            }
        }

        group.addTo(mapInstance.value);
        climbLayer.value = group;
    };

    /** Recentre the map on a climb, used when its row is clicked. */
    const focusOnClimb = (climb: ClimbDto) => {
        if (!mapInstance.value || !currentPath.value) {
            return;
        }
        const path = currentPath.value;
        const pointCount = pathSize(path);
        const coords: L.LatLngExpression[] = [];
        for (let i = climb.startIndex; i <= climb.endIndex && i < pointCount; i++) {
            coords.push([pathLatitudeDeg(path, i), pathLongitudeDeg(path, i)]);
        }
        if (coords.length >= 2) {
            mapInstance.value.fitBounds(L.latLngBounds(coords), { padding: [40, 40] });
        }
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
    watch(climbs, drawClimbs);
    watch(hoveredInfo, updateHoverMarker);

    onUnmounted(destroyMap);

    return {
        mapInstance,
        createMap,
        updateRoute,
        fitBounds,
        focusOnClimb,
        destroyMap,
    };
}
