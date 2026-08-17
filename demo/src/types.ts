import type { BikeDto, CyclistDto } from '~/engine-shim';

export interface DemoFieldDefinition {
    prop: string;
    unit: string;
    shortDescription: string;
    categoryId: string;
    categoryName: string;
    notSelectable: boolean;
    anglesInRadians: boolean;
    color: string;
}

export interface CategoryConfig {
    name: string;
    axis: string;
    unit: string;
    fields: Record<string, DemoFieldDefinition>;
}

export interface WindDemo {
    windSpeed: number;
    windDirection: number;
}

export enum PowerSourceType {
    constant = 'constant',
    constant_tiring = 'constant_tiring',
    // Renamed from 'source' for parity with the Kotlin/JS PowerProviderDto type.
    from_data = 'from_data',
}

export interface PowerParams {
    type: PowerSourceType;
    power: number;
    useHarmonics: boolean;
    // Duration in seconds after which power stabilizes at 50%
    tiringDuration: number;
}

export interface DemoEnhanceOptions {
    fixElevation: boolean;
    computeMaxSpeeds: boolean;
    virtualizeTrack: boolean;
    computeOnePointPerSecond: boolean;
    simplifyPath: {
        enable: boolean;
        tolerance: number;
        zExaggeration: number;
    };
}

export interface Config {
    selectedFields: Set<string>;
    bike: BikeDto;
    cyclist: CyclistDto;
    wind: WindDemo;
    enhance: DemoEnhanceOptions;
    power: PowerParams;
}

export interface Preset {
    bike: BikeDto;
    cyclist: CyclistDto;
    power: number;
    // Duration in seconds after which power stabilizes at 50%
    tiringDuration: number;
}

export const PRESETS: Record<'beginner' | 'recreational' | 'pro', Preset> = {
    beginner: {
        bike: {
            crr: 0.005,
            inertiaFront: 0.06,
            inertiaRear: 0.08,
            wheelRadiusM: 0.35,
            efficiency: 0.96,
        },
        cyclist: {
            massKg: 90,
            maxLeanAngleDeg: 35,
            maxBrakeG: 0.4,
            cd: 0.8,
            frontalAreaM2: 0.53,
            maxSpeedKmH: 60,
        },
        power: 180,
        tiringDuration: 3600,
    },
    recreational: {
        bike: {
            crr: 0.004,
            inertiaFront: 0.05,
            inertiaRear: 0.07,
            wheelRadiusM: 0.35,
            efficiency: 0.976,
        },
        cyclist: {
            massKg: 80,
            maxBrakeG: 0.6,
            cd: 0.7,
            frontalAreaM2: 0.45,
            maxLeanAngleDeg: 42,
            maxSpeedKmH: 80,
        },
        power: 230,
        tiringDuration: 7200,
    },
    pro: {
        bike: {
            crr: 0.003,
            inertiaFront: 0.04,
            inertiaRear: 0.06,
            wheelRadiusM: 0.35,
            efficiency: 0.985,
        },
        cyclist: {
            massKg: 73,
            maxBrakeG: 0.7,
            cd: 0.6,
            frontalAreaM2: 0.39,
            maxLeanAngleDeg: 50,
            maxSpeedKmH: 120,
        },
        power: 340,
        tiringDuration: 14400,
    },
};

export const DEFAULT_CONFIG: Config = {
    selectedFields: new Set<string>(['elevation', 'speed']),
    bike: PRESETS.recreational.bike,
    cyclist: PRESETS.recreational.cyclist,
    wind: { windSpeed: 0, windDirection: 0 },
    enhance: {
        fixElevation: true,
        computeMaxSpeeds: true,
        virtualizeTrack: true,
        computeOnePointPerSecond: true,
        simplifyPath: {
            enable: false,
            tolerance: 10,
            zExaggeration: 3,
        },
    },
    power: {
        type: PowerSourceType.constant,
        power: PRESETS.recreational.power,
        useHarmonics: false,
        tiringDuration: PRESETS.recreational.tiringDuration,
    },
};
