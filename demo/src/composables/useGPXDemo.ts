import type { Ref } from 'vue';
import { ref, shallowRef } from 'vue';
import {
    analyzeRacingLine,
    enhanceWithCourse,
    parseGpxTracks,
    parseGpxWaypoints,
    pathDurationMs,
    pathElevationCorrections,
    pathSize,
    pathTotalDistance,
    type BikeDto,
    type CyclistDto,
    type EnhanceOptionsDto,
    type Path,
    type PowerProviderDto,
    type RacingLineReportDto,
    type WaypointDto,
    type WindDto,
} from '@glandais/vcyclist-engine';
import { type Config, PowerSourceType, SLEW_W_PER_S } from '~/types';

export interface UseGPXDemoReturn {
    currentPath: Ref<Path | null>;
    /** The path as parsed, before enhancement — the reference the racing-line report indexes. */
    originalPath: Ref<Path | null>;
    /** Every `<trk>` and `<rte>` the file carried, in document order. Never empty once loaded. */
    tracks: Ref<Path[]>;
    /** Which of {@link tracks} the rest of the UI is looking at. */
    selectedTrackIndex: Ref<number>;
    /** The document's `<wpt>` entries, kept so the GPX download can write them back. */
    waypoints: Ref<WaypointDto[]>;
    selectTrack: (index: number) => void;
    /** The corridor and offsets behind the current path, or `null` when the stage did not run. */
    racingLineReport: Ref<RacingLineReportDto | null>;
    /**
     * The same ride enhanced again with both elevation corrections off, so the chart can show what
     * they changed. `null` until it lands, and whenever the corrections did nothing.
     */
    comparisonPath: Ref<Path | null>;
    /** The comparison run is in flight. It never blocks the UI. */
    comparisonPending: Ref<boolean>;
    isProcessing: Ref<boolean>;
    statusText: Ref<string>;
    fileName: Ref<string>;
    loadGPXFile: (url: string) => Promise<void>;
    handleFileUpload: (file: File) => Promise<void>;
    enhancePath: () => Promise<void>;
}

export function useGPXDemo(config: Ref<Config>): UseGPXDemoReturn {
    const originalPath: Ref<Path | null> = ref(null);
    const currentPath: Ref<Path | null> = ref(null);
    const tracks: Ref<Path[]> = shallowRef([]);
    const selectedTrackIndex = ref(0);
    const waypoints: Ref<WaypointDto[]> = shallowRef([]);
    const racingLineReport: Ref<RacingLineReportDto | null> = ref(null);
    const comparisonPath: Ref<Path | null> = shallowRef(null);
    const comparisonPending = ref(false);
    const isProcessing = ref(false);
    const statusText = ref('');
    // Bumped by every load, track switch and enhance. A comparison run whose generation is no
    // longer current is stale: the engine's promise cannot be aborted, so its result is dropped.
    let generation = 0;
    const invalidateComparison = () => {
        generation++;
        comparisonPath.value = null;
        comparisonPending.value = false;
    };
    const fileName = ref('');

    const setProcessing = (processing: boolean, message = '') => {
        isProcessing.value = processing;
        statusText.value = message;
    };

    const buildPowerProviderDto = (): PowerProviderDto => {
        const p = config.value.power;
        // Pacing (R19) and the slew limit (R18) are decorators in the engine: they compose over
        // whichever model was picked, so they are spread onto every branch rather than being
        // models of their own.
        const decorators = {
            pacing: p.pacing,
            maxSlewWPerS: p.slew ? SLEW_W_PER_S : 0,
        };
        switch (p.type) {
            case PowerSourceType.constant:
                return {
                    type: 'constant',
                    power: p.power,
                    useHarmonics: p.useHarmonics,
                    ...decorators,
                };
            case PowerSourceType.durability:
                return {
                    type: 'durability',
                    power: p.power,
                    useHarmonics: p.useHarmonics,
                    criticalPower: p.criticalPower,
                    ...decorators,
                };
            case PowerSourceType.critical_power:
                return {
                    type: 'critical-power',
                    power: p.power,
                    useHarmonics: p.useHarmonics,
                    criticalPower: p.criticalPower,
                    wPrime: p.wPrime,
                    ...decorators,
                };
            case PowerSourceType.from_data:
                // No decorators: the UI hides them for this model, and replayed power is a
                // recording, not a choice the rider is making — pacing or smoothing it would
                // silently rewrite the data the user asked to see.
                return { type: 'from_data' };
        }
    };

    const buildWindDto = (): WindDto => ({
        windSpeed: config.value.wind.windSpeed,
        windDirection: config.value.wind.windDirection,
    });

    // Cyclist and bike are spelled out field by field rather than passed straight from the config,
    // for the same reason wind and power always were. The config is restored from localStorage and
    // may carry keys an older build wrote; since task 43 the engine rejects a DTO key it does not
    // read, so forwarding the stored object wholesale would turn a harmless leftover into a hard
    // failure at enhance time.
    const buildCyclistDto = (): CyclistDto => {
        const c = config.value.cyclist;
        return {
            massKg: c.massKg,
            cd: c.cd,
            frontalAreaM2: c.frontalAreaM2,
            maxLeanAngleDeg: c.maxLeanAngleDeg,
            maxBrakeG: c.maxBrakeG,
            maxSpeedKmH: c.maxSpeedKmH,
            roadCondition: c.roadCondition,
        };
    };

    const buildBikeDto = (): BikeDto => {
        const b = config.value.bike;
        return {
            crr: b.crr,
            inertiaFront: b.inertiaFront,
            inertiaRear: b.inertiaRear,
            wheelRadiusM: b.wheelRadiusM,
            efficiency: b.efficiency,
            maxPedalingLeanAngleDeg: b.maxPedalingLeanAngleDeg,
        };
    };

    const buildEnhanceOptionsDto = (): EnhanceOptionsDto => {
        const e = config.value.enhance;
        return {
            fixElevation: e.fixElevation,
            demSource: e.demSource,
            computeMaxSpeeds: e.computeMaxSpeeds,
            virtualizeTrack: e.virtualizeTrack,
            computeOnePointPerSecond: e.computeOnePointPerSecond,
            simplifyEnabled: e.simplifyPath.enable,
            simplifyToleranceM: e.simplifyPath.tolerance,
            simplifyZExaggeration: e.simplifyPath.zExaggeration,
            wPrimeBalanceEnabled: e.wPrimeBalance.enabled,
            // When linked, the reported W′ trace describes the rider actually being simulated.
            // Unlinked, it reports that rider's effort against a different physiology — legitimate,
            // but it has to be asked for.
            wPrimeBalanceCriticalPower: e.wPrimeBalance.linkToPowerModel
                ? config.value.power.criticalPower
                : e.wPrimeBalance.criticalPower,
            wPrimeBalanceWPrime: e.wPrimeBalance.linkToPowerModel
                ? config.value.power.wPrime
                : e.wPrimeBalance.wPrime,
            curvatureEnabled: e.curvature.enabled,
            racingLineEnabled: e.racingLine.enabled,
            racingLineCorridor: e.racingLine.corridor,
            racingLineRoadWidthM: e.racingLine.roadWidthM,
            elevationGainEnabled: e.elevationGain.enabled,
            elevationGainPreset: e.elevationGain.preset,
            elevationSmoothWindowM: e.elevationSmoothWindowM,
            bridgeDecksEnabled: e.elevationCorrections.bridgeDecks,
            tunnelsEnabled: e.elevationCorrections.tunnels,
            tunnelScope: e.elevationCorrections.tunnelScope,
        };
    };

    /**
     * Everything the file carried, not just the first track.
     *
     * This used to call `parseGpx`, which is `firstTrackAsPath` — a two-track file lost its second
     * `<trk>` at load with nothing said, and the waypoints were never read at all, so the GPX
     * download destroyed every `<wpt>` of the source. Both are kept now: the tracks so a human can
     * pick one, the waypoints so `writeGpxTracks` can put them back.
     */
    const parseAndStore = async (gpxContent: string, filename: string) => {
        setProcessing(true, 'Parsing GPX data...');
        try {
            const parsed = parseGpxTracks(gpxContent);
            if (parsed.length === 0) {
                throw new Error('no <trk> or <rte> in this file');
            }
            invalidateComparison();
            tracks.value = parsed;
            waypoints.value = parseGpxWaypoints(gpxContent);
            selectedTrackIndex.value = 0;
            originalPath.value = parsed[0];
            currentPath.value = parsed[0];
            racingLineReport.value = null;
            fileName.value = filename;
            console.log('GPX parsed successfully:', {
                filename,
                tracks: parsed.length,
                waypoints: waypoints.value.length,
                points: pathSize(parsed[0]),
                distance: pathTotalDistance(parsed[0]),
            });
        } catch (error) {
            throw new Error('Failed to parse GPX: ' + (error as Error).message, { cause: error });
        }
    };

    /**
     * Look at another track of the same file. The enhancement does not carry over — it was
     * computed for a different road — so this drops back to the parsed path, exactly as a fresh
     * load would.
     */
    const selectTrack = (index: number) => {
        const path = tracks.value[index];
        if (!path || index === selectedTrackIndex.value) {
            return;
        }
        invalidateComparison();
        selectedTrackIndex.value = index;
        originalPath.value = path;
        currentPath.value = path;
        racingLineReport.value = null;
    };

    const loadGPXFile = async (url: string) => {
        if (isProcessing.value) {
            return;
        }
        setProcessing(true, 'Loading GPX file...');
        try {
            const response = await fetch(url);
            if (!response.ok) {
                throw new Error(`Failed to load GPX: ${response.status} ${response.statusText}`);
            }
            const gpxContent = await response.text();
            await parseAndStore(gpxContent, url.split('/').pop() ?? 'Unknown');
        } catch (error) {
            console.error('Error loading GPX file:', error);
            throw error;
        } finally {
            setProcessing(false);
        }
    };

    const handleFileUpload = async (file: File) => {
        if (isProcessing.value) {
            return;
        }
        setProcessing(true, 'Reading uploaded file...');
        try {
            const content = await file.text();
            await parseAndStore(content, file.name);
        } catch (error) {
            console.error('Error handling file upload:', error);
            throw error;
        } finally {
            setProcessing(false);
        }
    };

    const enhancePath = async () => {
        if (isProcessing.value || !originalPath.value) {
            return;
        }
        setProcessing(true, 'Enhancing path with virtual cyclist...');
        invalidateComparison();
        const runGeneration = generation;
        const source = originalPath.value;
        // Built once and shared with the comparison run, so the two differ by the two correction
        // flags only, even if the config is edited while the second one is in flight.
        const course = [
            buildCyclistDto(),
            buildBikeDto(),
            buildWindDto(),
            buildPowerProviderDto(),
        ] as const;
        const options = buildEnhanceOptionsDto();
        try {
            const enhanced = await enhanceWithCourse(source, ...course, options);
            currentPath.value = enhanced;
            void runComparison(runGeneration, source, course, options, enhanced);
            // Analyse the INPUT path, not the enhanced one. The enhanced path has already had
            // the stage applied, so analysing it describes the corridor around a second
            // optimisation pass — a corridor nobody rode in. Caught by the map's own geometry
            // check, which reported a 4.5 m disagreement before this was the input path.
            //
            // The sampling still differs from what the stage saw: the pipeline densifies to ~2 m
            // before the trajectory stage, so the corridor drawn here is computed on the recorded
            // spacing. Same road and same options, slightly coarser outline.
            racingLineReport.value = config.value.enhance.racingLine.enabled
                ? analyzeRacingLine(originalPath.value, buildEnhanceOptionsDto())
                : null;
            console.log('Path enhancement completed:', {
                points: pathSize(enhanced),
                distance: pathTotalDistance(enhanced),
                durationMs: pathDurationMs(enhanced),
            });
        } catch (error) {
            console.error('Error enhancing path:', error);
            throw error;
        } finally {
            setProcessing(false);
        }
    };

    /**
     * Enhance `source` again with both corrections off, after the main run and without blocking
     * the UI. Skipped when the corrections changed nothing (no DEM lookup, both disabled, or
     * nothing found): the two profiles would be identical, and a second DEM fetch is not free.
     */
    const runComparison = async (
        runGeneration: number,
        source: Path,
        course: readonly [CyclistDto, BikeDto, WindDto, PowerProviderDto],
        options: EnhanceOptionsDto,
        enhanced: Path
    ) => {
        const report = pathElevationCorrections(enhanced);
        if (!report || report.bridgeDecksApplied + report.tunnelsApplied === 0) {
            return;
        }
        comparisonPending.value = true;
        try {
            const without = await enhanceWithCourse(source, ...course, {
                ...options,
                bridgeDecksEnabled: false,
                tunnelsEnabled: false,
            });
            if (runGeneration === generation) {
                comparisonPath.value = without;
            }
        } catch (error) {
            // An overlay that failed is not worth a toast over a ride that succeeded.
            console.warn('Comparison run without elevation corrections failed:', error);
        } finally {
            if (runGeneration === generation) {
                comparisonPending.value = false;
            }
        }
    };

    return {
        currentPath,
        originalPath,
        comparisonPath,
        comparisonPending,
        tracks,
        selectedTrackIndex,
        waypoints,
        selectTrack,
        racingLineReport,
        isProcessing,
        statusText,
        fileName,
        loadGPXFile,
        handleFileUpload,
        enhancePath,
    };
}
