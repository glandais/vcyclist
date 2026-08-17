import type { Ref } from 'vue';
import { ref } from 'vue';
import {
    analyzeRacingLine,
    enhanceWithCourse,
    parseGpx,
    pathDurationMs,
    pathSize,
    pathTotalDistance,
    type BikeDto,
    type CyclistDto,
    type EnhanceOptionsDto,
    type Path,
    type PowerProviderDto,
    type RacingLineReportDto,
    type WindDto,
} from '~/engine-shim';
import { type Config, PowerSourceType, SLEW_W_PER_S } from '~/types';

export interface UseGPXDemoReturn {
    currentPath: Ref<Path | null>;
    /** The path as parsed, before enhancement — the reference the racing-line report indexes. */
    originalPath: Ref<Path | null>;
    /** The corridor and offsets behind the current path, or `null` when the stage did not run. */
    racingLineReport: Ref<RacingLineReportDto | null>;
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
    const racingLineReport: Ref<RacingLineReportDto | null> = ref(null);
    const isProcessing = ref(false);
    const statusText = ref('');
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
        };
    };

    const parseAndStore = async (gpxContent: string, filename: string) => {
        setProcessing(true, 'Parsing GPX data...');
        try {
            const path = parseGpx(gpxContent);
            originalPath.value = path;
            currentPath.value = path;
            racingLineReport.value = null;
            fileName.value = filename;
            console.log('GPX parsed successfully:', {
                filename,
                points: pathSize(path),
                distance: pathTotalDistance(path),
            });
        } catch (error) {
            throw new Error('Failed to parse GPX: ' + (error as Error).message, { cause: error });
        }
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
        try {
            const enhanced = await enhanceWithCourse(
                originalPath.value,
                buildCyclistDto(),
                buildBikeDto(),
                buildWindDto(),
                buildPowerProviderDto(),
                buildEnhanceOptionsDto()
            );
            currentPath.value = enhanced;
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

    return {
        currentPath,
        originalPath,
        racingLineReport,
        isProcessing,
        statusText,
        fileName,
        loadGPXFile,
        handleFileUpload,
        enhancePath,
    };
}
