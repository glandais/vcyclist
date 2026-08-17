import type { Ref } from 'vue';
import { ref } from 'vue';
import {
    enhanceWithCourse,
    parseGpx,
    pathDurationMs,
    pathSize,
    pathTotalDistance,
    type EnhanceOptionsDto,
    type Path,
    type PowerProviderDto,
    type WindDto,
} from '~/engine-shim';
import { type Config, PowerSourceType, SLEW_W_PER_S } from '~/types';

export interface UseGPXDemoReturn {
    currentPath: Ref<Path | null>;
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
        };
    };

    const parseAndStore = async (gpxContent: string, filename: string) => {
        setProcessing(true, 'Parsing GPX data...');
        try {
            const path = parseGpx(gpxContent);
            originalPath.value = path;
            currentPath.value = path;
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
                config.value.cyclist,
                config.value.bike,
                buildWindDto(),
                buildPowerProviderDto(),
                buildEnhanceOptionsDto()
            );
            currentPath.value = enhanced;
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
        isProcessing,
        statusText,
        fileName,
        loadGPXFile,
        handleFileUpload,
        enhancePath,
    };
}
