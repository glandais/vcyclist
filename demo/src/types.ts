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
    // Replaced 'constant_tiring' (ledger R17): the elapsed-time decay was removed from the
    // engine outright, in favour of a fade driven by work accumulated above CP.
    durability = 'durability',
    // Renamed from 'source' for parity with the Kotlin/JS PowerProviderDto type.
    from_data = 'from_data',
}

export interface PowerParams {
    type: PowerSourceType;
    power: number;
    useHarmonics: boolean;
    // Critical power (W). The durability model fades power with the work accumulated above it.
    criticalPower: number;
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
    // Critical power (W), used by the durability model. Set at ~90 % of the preset's target
    // power so the fade has something to bite on; the engine default is 250 W.
    criticalPower: number;
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
            maxBrakeG: 0.35,
            cd: 0.8,
            frontalAreaM2: 0.53,
            maxSpeedKmH: 60,
        },
        power: 180,
        criticalPower: 160,
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
            maxBrakeG: 0.4,
            cd: 0.7,
            frontalAreaM2: 0.45,
            maxLeanAngleDeg: 42,
            maxSpeedKmH: 80,
        },
        power: 230,
        criticalPower: 210,
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
            maxBrakeG: 0.5,
            cd: 0.6,
            frontalAreaM2: 0.39,
            maxLeanAngleDeg: 50,
            maxSpeedKmH: 120,
        },
        power: 340,
        criticalPower: 310,
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
        criticalPower: PRESETS.recreational.criticalPower,
    },
};
