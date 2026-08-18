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
    type RacingLineReportDto,
} from '~/engine-shim';
import { gradeColor } from './useClimbs';
import type { HoverInfo } from './useHoverSync';

export function useMap(
    mapContainer: Ref<HTMLElement | null>,
    currentPath: Ref<Path | null>,
    hoveredInfo: Ref<HoverInfo | null>,
    onHoverChange: (index: number | null) => void,
    climbs: Ref<ClimbDto[]>,
    racingLineReport: Ref<RacingLineReportDto | null>,
    originalPath: Ref<Path | null>
) {
    const mapInstance = shallowRef<L.Map | null>(null);
    const routeLayer = shallowRef<L.Polyline | null>(null);
    const climbLayer = shallowRef<L.LayerGroup | null>(null);
    const racingLineLayer = shallowRef<L.LayerGroup | null>(null);
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
        drawRacingLine();
        drawClimbs();

        mapInstance.value.fitBounds(polyline.getBounds(), {
            padding: [50, 50],
        });
    };

    /**
     * Draw what the racing-line stage did: the road the file described, the corridor the solver
     * was allowed to use, and the line it chose.
     *
     * ## Why this exists at all
     *
     * The stage **replaces every coordinate**. Without this, the map would quietly show a route
     * that is not the one the user loaded — the single most misleading thing this demo could do.
     * The originals survive in `sourceLatitude` / `sourceLongitude`, so both can be drawn.
     *
     * ## Reconstructing the corridor
     *
     * The report gives `corridorLo` / `corridorHi` as signed metres from the reference line,
     * positive to the LEFT of travel, but not as coordinates — placing them needs a normal the
     * report does not carry. It is rebuilt here in a local east-north tangent plane: the tangent
     * comes from the neighbouring points, and the left normal is that tangent rotated a quarter
     * turn counter-clockwise, matching `RacingLine`'s own `(−sin θ, cos θ)`.
     *
     * That is a flat-earth approximation, and it is a fair one at this scale: the offsets are
     * metres, over which the meridian convergence the engine's planar frame corrects for is far
     * below a pixel. The reconstruction is checked against the engine rather than trusted —
     * applying `lateralOffsetM` to the reference must reproduce the racing line the engine
     * actually built, and `racingLineDrift()` below reports how far off it is.
     */
    const METRES_PER_DEGREE = 111_320;

    /** Plain coordinates of `path`, in degrees. */
    const coordinatesOf = (path: Path, count: number): Array<[number, number]> => {
        const out: Array<[number, number]> = [];
        for (let i = 0; i < count; i++) {
            out.push([pathLatitudeDeg(path, i), pathLongitudeDeg(path, i)]);
        }
        return out;
    };

    /**
     * Where each point of `path` was before the stage moved it.
     *
     * `sourceLatitude` / `sourceLongitude` are NaN on a point the stage never touched, which is
     * every point when it did not run — so this falls back to the current position and the caller
     * gets the road either way.
     */
    const sourceCoordinates = (path: Path, count: number): Array<[number, number]> => {
        const out: Array<[number, number]> = [];
        for (let i = 0; i < count; i++) {
            const lat = getField(path, i, 'sourceLatitude');
            const lon = getField(path, i, 'sourceLongitude');
            out.push(
                Number.isFinite(lat) && Number.isFinite(lon)
                    ? [(lat * 180) / Math.PI, (lon * 180) / Math.PI]
                    : [pathLatitudeDeg(path, i), pathLongitudeDeg(path, i)]
            );
        }
        return out;
    };

    /**
     * Half-span, in metres, of the window the tangent is measured over.
     *
     * A **distance** and not a point count, which is the whole of ledger R23: a fixed number of
     * points makes the answer depend on the resampler, and the two callers here are sampled
     * differently — the recorded path at whatever spacing the file has, the enhanced one at 1 Hz.
     *
     * Kept short, on measurement rather than on theory. Widening it to 5 m to match the engine's
     * smoothing scale seemed the obvious move and made things **worse** — 0.51 m of disagreement
     * became 0.69 m — because a 10 m chord says nothing useful across a 5 m-radius hairpin, and
     * hairpins are exactly where the offsets are largest. At 1 m it degenerates to the nearest
     * neighbours on the pipeline's 2 m grid, while still being immune to a change of resampler.
     */
    const TANGENT_HALF_SPAN_M = 1;

    /** Index at least `TANGENT_HALF_SPAN_M` away from `i` in direction `step`, or the end. */
    const spanEnd = (
        reference: Array<[number, number]>,
        i: number,
        step: -1 | 1
    ): [number, number] => {
        const [lat0, lon0] = reference[i];
        const cosLat = Math.cos((lat0 * Math.PI) / 180);
        let j = i;
        for (;;) {
            const k = j + step;
            if (k < 0 || k >= reference.length) {
                break;
            }
            j = k;
            const dNorth = (reference[j][0] - lat0) * METRES_PER_DEGREE;
            const dEast = (reference[j][1] - lon0) * METRES_PER_DEGREE * cosLat;
            if (Math.hypot(dEast, dNorth) >= TANGENT_HALF_SPAN_M) {
                break;
            }
        }
        return reference[j];
    };

    /** Unit left normal at `i`, in (east, north). */
    const leftNormal = (reference: Array<[number, number]>, i: number): [number, number] => {
        const prev = spanEnd(reference, i, -1);
        const next = spanEnd(reference, i, 1);
        const cosLat = Math.cos((reference[i][0] * Math.PI) / 180);
        const east = (next[1] - prev[1]) * cosLat;
        const north = next[0] - prev[0];
        const length = Math.hypot(east, north);
        if (length === 0) {
            return [0, 0];
        }
        // Tangent rotated a quarter turn counter-clockwise: (e, n) -> (-n, e). Matches
        // RacingLine's own left normal (−sin θ, cos θ) with x = east, y = north.
        return [-north / length, east / length];
    };

    /** Move `[lat, lon]` by `offsetM` metres along the left normal at `i`. */
    const offsetPoint = (
        reference: Array<[number, number]>,
        i: number,
        offsetM: number
    ): [number, number] => {
        if (!Number.isFinite(offsetM)) {
            return reference[i];
        }
        const [nEast, nNorth] = leftNormal(reference, i);
        const [lat, lon] = reference[i];
        const cosLat = Math.cos((lat * Math.PI) / 180);
        return [
            lat + (offsetM * nNorth) / METRES_PER_DEGREE,
            lon + (offsetM * nEast) / (METRES_PER_DEGREE * cosLat),
        ];
    };

    /**
     * Largest disagreement, in metres, between where the engine put each point and where this
     * file's normal says it should be.
     *
     * Uses the enhanced path alone, so it is exact and index-aligned: `sourceLatitude` /
     * `sourceLongitude` plus the `lateralOffset` field, both written by the stage, must reproduce
     * `latitude` / `longitude`. That exercises the same normal the corridor is drawn with — a
     * mirrored normal would show about twice the offset rather than a few centimetres, while the
     * corridor itself would look entirely plausible on the map.
     */
    const racingLineDrift = (): number => {
        const path = currentPath.value;
        if (!path) {
            return 0;
        }
        const count = pathSize(path);
        const source = sourceCoordinates(path, count);
        let worst = 0;
        for (let i = 0; i < count; i++) {
            const offset = getField(path, i, 'lateralOffset');
            if (!Number.isFinite(offset)) {
                continue;
            }
            const [lat, lon] = offsetPoint(source, i, offset);
            const dLat = (lat - pathLatitudeDeg(path, i)) * METRES_PER_DEGREE;
            const dLon =
                (lon - pathLongitudeDeg(path, i)) *
                METRES_PER_DEGREE *
                Math.cos((lat * Math.PI) / 180);
            worst = Math.max(worst, Math.hypot(dLat, dLon));
        }
        return worst;
    };

    const drawRacingLine = () => {
        if (!mapInstance.value) {
            return;
        }
        if (racingLineLayer.value) {
            mapInstance.value.removeLayer(racingLineLayer.value);
            racingLineLayer.value = null;
        }

        const path = currentPath.value;
        const report = racingLineReport.value;
        const input = originalPath.value;
        if (!path || !report || !input) {
            return;
        }

        // The corridor indexes the INPUT path, which is what was analysed. The ridden line and
        // the road it departed from come from the enhanced path, which is denser. Three curves in
        // space; they need not share an index space to be drawn together.
        const corridorCount = Math.min(pathSize(input), report.size);
        const reference = coordinatesOf(input, corridorCount);
        const lo: L.LatLngExpression[] = [];
        const hi: L.LatLngExpression[] = [];
        for (let i = 0; i < corridorCount; i++) {
            lo.push(offsetPoint(reference, i, report.corridorLo[i]));
            hi.push(offsetPoint(reference, i, report.corridorHi[i]));
        }

        const enhancedCount = pathSize(path);
        const road = sourceCoordinates(path, enhancedCount);
        const chosen = coordinatesOf(path, enhancedCount);

        // The corridor as one closed band: down one edge and back along the other.
        const band = L.polygon([...hi, ...[...lo].reverse()], {
            color: '#7c3aed',
            weight: 1.5,
            opacity: 0.7,
            fillColor: '#7c3aed',
            // A corridor is a few metres wide, so even at maximum zoom it competes with the road
            // casing underneath it. Faint enough to read as background, solid enough to find.
            fillOpacity: 0.28,
            interactive: false,
        });
        // Dashed and thin: this is the road as the file described it, context rather than result.
        const original = L.polyline(road, {
            color: '#64748b',
            weight: 2,
            opacity: 0.85,
            dashArray: '4 4',
            interactive: false,
        });
        const line = L.polyline(chosen, {
            color: '#dc2626',
            weight: 3,
            opacity: 0.95,
            interactive: false,
        });

        racingLineLayer.value = L.layerGroup([band, original, line]).addTo(mapInstance.value);

        // Check the reconstruction against the engine rather than trusting it. The corridor is
        // drawn from a normal computed here; if its sign or scale were wrong the band would sit
        // beside the road, or mirrored across it, and still look perfectly plausible. Applying
        // `lateralOffsetM` to the same normal must land on the line the engine actually built, so
        // any real drift means the corridor is a lie.
        // Threshold set to mean "structurally wrong", not "hairpin". About 0.5 m of disagreement
        // is the floor on a route like Stelvio and cannot be removed from out here: the engine
        // takes its normal from a smoothed frame, and no chord through the recorded points
        // reproduces that where the road turns hard between samples. A mirrored or mis-scaled
        // normal, the failure actually worth catching, shows up as roughly twice the offset —
        // several metres — so 1.5 m separates the two without crying wolf on every alp.
        const drift = racingLineDrift();
        if (drift > 1.5) {
            console.warn(
                `[racing line] corridor geometry is off by ${drift.toFixed(2)} m — ` +
                    'the drawn corridor cannot be trusted. Check the left-normal reconstruction.'
            );
        }
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

    /** A Leaflet map laid out while hidden renders grey tiles until it is told to re-measure. */
    const invalidateSize = () => {
        mapInstance.value?.invalidateSize();
    };

    const destroyMap = () => {
        if (mapInstance.value) {
            mapInstance.value.remove();
            mapInstance.value = null;
        }
        routeLayer.value = null;
        racingLineLayer.value = null;
        hoverMarker.value = null;
        hoverPopup.value = null;
        nearestPointCache = [];
    };

    watch(currentPath, updateRoute);
    watch(racingLineReport, drawRacingLine);
    watch(climbs, drawClimbs);
    watch(hoveredInfo, updateHoverMarker);

    onUnmounted(destroyMap);

    return {
        mapInstance,
        createMap,
        updateRoute,
        fitBounds,
        focusOnClimb,
        invalidateSize,
        destroyMap,
        racingLineDrift,
    };
}
