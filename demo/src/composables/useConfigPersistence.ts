import type { Ref } from 'vue';
import { onUnmounted, watch } from 'vue';
import { type Config, DEFAULT_CONFIG, PowerSourceType } from '~/types';

const STORAGE_KEY = 'vcyclist-demo-config';
const DEBOUNCE_MS = 1000;

// Serializable version of Config with Set converted to Array.
type SerializableConfig = Omit<Config, 'selectedFields'> & {
    selectedFields: string[];
};

/**
 * Convert Config to a JSON-serializable format.
 */
const serializeConfig = (config: Config): SerializableConfig => {
    return {
        ...config,
        selectedFields: Array.from(config.selectedFields),
    };
};

/**
 * Bring a config saved by an older build up to the current shape.
 *
 * The engine dropped the elapsed-time fatigue provider in ledger R17, so a stored
 * `power.type === 'constant_tiring'` now makes `enhanceWithCourse` throw
 * (`Unknown PowerProviderDto.type`). Map it onto the durability model that replaced it; the
 * old `tiringDuration` has no equivalent — fatigue is driven by supra-CP work, not by a
 * duration — so it is dropped and CP falls back to the default.
 */
const migrateConfig = (data: SerializableConfig): SerializableConfig => {
    // Read as an untyped bag: the value on disk may predate the current PowerParams shape.
    const power = data.power as unknown as
        | (Omit<Partial<Config['power']>, 'type'> & { type?: string })
        | undefined;
    if (power?.type === 'constant_tiring') {
        data.power = {
            ...DEFAULT_CONFIG.power,
            type: PowerSourceType.durability,
            power: power.power ?? DEFAULT_CONFIG.power.power,
            useHarmonics: power.useHarmonics ?? DEFAULT_CONFIG.power.useHarmonics,
        };
    } else if (power) {
        // Fill in whatever the saved build did not know about (wPrime, pacing, slew…). A missing
        // key must not reach the DTO as `undefined`: the engine reads that as "use my default",
        // which is indistinguishable from a deliberate choice and hides the gap.
        data.power = { ...DEFAULT_CONFIG.power, ...power } as Config['power'];
    }

    if (data.enhance) {
        data.enhance = {
            ...data.enhance,
            wPrimeBalance: {
                ...DEFAULT_CONFIG.enhance.wPrimeBalance,
                ...(data.enhance.wPrimeBalance ?? {}),
            },
        };
    }

    if (data.cyclist && data.cyclist.roadCondition === undefined) {
        data.cyclist = { ...data.cyclist, roadCondition: 'dry' };
    }

    return data;
};

/**
 * Convert serializable format back to Config.
 */
const deserializeConfig = (data: SerializableConfig): Config => {
    const migrated = migrateConfig(data);
    return {
        ...migrated,
        selectedFields: new Set<string>(migrated.selectedFields),
    };
};

/**
 * Load config from localStorage, falling back to a deep-cloned `DEFAULT_CONFIG`.
 */
export const loadConfig = (): Config => {
    try {
        const saved = window.localStorage.getItem(STORAGE_KEY);
        if (saved) {
            const parsed = JSON.parse(saved) as SerializableConfig;
            return deserializeConfig(parsed);
        }
    } catch (error) {
        console.warn('Failed to load config from localStorage:', error);
    }
    return structuredClone(DEFAULT_CONFIG);
};

/**
 * Save config to localStorage.
 */
export const saveConfig = (config: Config): void => {
    try {
        const serialized = serializeConfig(config);
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(serialized));
    } catch (error) {
        console.warn('Failed to save config to localStorage:', error);
    }
};

/**
 * Set up auto-save with debouncing. Clears the pending timer on component unmount.
 */
export const useConfigPersistence = (config: Ref<Config>): void => {
    let timeoutId: number | undefined;

    watch(
        config,
        newConfig => {
            if (timeoutId !== undefined) {
                window.clearTimeout(timeoutId);
            }
            timeoutId = window.setTimeout(() => {
                saveConfig(newConfig);
            }, DEBOUNCE_MS);
        },
        { deep: true }
    );

    onUnmounted(() => {
        if (timeoutId !== undefined) {
            window.clearTimeout(timeoutId);
        }
    });
};
