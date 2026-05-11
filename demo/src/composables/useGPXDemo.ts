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
import { type Config, PowerSourceType } from '~/types';

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
        switch (p.type) {
            case PowerSourceType.constant:
                return { type: 'constant', power: p.power, useHarmonics: p.useHarmonics };
            case PowerSourceType.constant_tiring:
                return {
                    type: 'constant_tiring',
                    power: p.power,
                    useHarmonics: p.useHarmonics,
                    tiringDuration: p.tiringDuration,
                };
            case PowerSourceType.from_data:
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
