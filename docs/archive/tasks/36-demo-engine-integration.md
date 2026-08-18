# 36 — Démo Vue/Vite : intégration moteur (upload → enhance)

## Goal

Brancher le moteur Kotlin/JS dans la démo : un utilisateur peut **uploader un GPX**, **configurer cyclist / bike / wind / power**, **lancer enhance**, et récupérer le `Path` enrichi. À l'issue de cette tâche, la console DevTools affiche `pathSize`, `totalDistance`, `durationMs`, `elevationGain` avant et après enhance.

Aucune UI graphique encore (chart/map en task 37) — juste un `<input type="file">`, des boutons "Load sample" / "Enhance", et des `<pre>` qui dumpent les stats.

## Depends on

- Task 34 (façade `enhanceWithCourse`, `getField`, `fieldDefinitions`)
- Task 35 (demo bootstrap)

## Inputs

- `virtual-cyclist/demo/src/types.ts` (intégral, lignes 1-144) — référence des types `Config`, `PowerSourceType`, `PRESETS`, `DEFAULT_CONFIG`.
- `virtual-cyclist/demo/src/composables/useGPXDemo.ts` — référence du flow `parse → getCourse → enhance`.
- `virtual-cyclist/demo/src/composables/useConfigPersistence.ts` — persistance localStorage du Config.
- `virtual-cyclist/demo/src/composables/useHoverSync.ts` — sync chart ↔ map (utilisé plus tard en task 37, à porter quand même ici car référencé par App.vue).
- `engine/src/jsMain/.../EngineJsApi.kt` — façade étendue de task 34.

## Steps

### 1. Porter `src/types.ts`

Copier intégralement `virtual-cyclist/demo/src/types.ts` dans `demo/src/types.ts` en adaptant les noms de champs aux DTO de task 34 :

| TS (CyclistProperties) | Kotlin/JS (CyclistDto) |
|---|---|
| `mKg` | `massKg` |
| `a` | `frontalAreaM2` |
| `maxAngleDeg` | `maxLeanAngleDeg` |
| `maxBrakeG` | `maxBrakeG` (idem) |
| `maxSpeedKmH` | `maxSpeedKmH` (idem) |
| `cd` | `cd` (idem) |

| TS (BikeProperties) | Kotlin/JS (BikeDto) |
|---|---|
| `wheelRadius` | `wheelRadiusM` |
| autres | identiques |

Renommer aussi le type local `Config.bike` / `Config.cyclist` en `CyclistDto` / `BikeDto` importés depuis `~/engine-shim`.

`PRESETS` et `DEFAULT_CONFIG` doivent être adaptés aux nouveaux noms (cf. mapping ci-dessus).

### 2. Porter `useGPXDemo.ts`

`demo/src/composables/useGPXDemo.ts` :

```ts
import type { Ref } from 'vue';
import { ref } from 'vue';
import type { Path, EnhanceOptionsDto, PowerProviderDto, WindDto } from '~/engine-shim';
import { parseGpx, enhanceWithCourse, pathSize, pathTotalDistance, pathDurationMs } from '~/engine-shim';
import { type Config, PowerSourceType } from '~/types';

export function useGPXDemo(config: Ref<Config>) {
    const originalPath = ref<Path | null>(null);
    const currentPath = ref<Path | null>(null);
    const isProcessing = ref(false);
    const statusText = ref('');
    const fileName = ref('');

    const setProcessing = (p: boolean, msg = '') => {
        isProcessing.value = p;
        statusText.value = msg;
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
            case PowerSourceType.source:
                return { type: 'from_data' };
        }
    };

    const buildWindDto = (): WindDto => ({
        windSpeed: config.value.wind.windSpeed,
        windDirection: config.value.wind.windDirection,
    });

    const loadGPXFile = async (url: string) => {
        if (isProcessing.value) return;
        setProcessing(true, 'Loading GPX file...');
        try {
            const response = await fetch(url);
            if (!response.ok) throw new Error(`Failed to load GPX: ${response.status}`);
            const gpxContent = await response.text();
            await parseGPX(gpxContent, url.split('/').pop() ?? 'Unknown');
        } finally {
            setProcessing(false);
        }
    };

    const handleFileUpload = async (file: File) => {
        if (isProcessing.value) return;
        setProcessing(true, 'Reading uploaded file...');
        try {
            const content = await file.text();
            await parseGPX(content, file.name);
        } finally {
            setProcessing(false);
        }
    };

    const parseGPX = async (gpxContent: string, filename: string) => {
        setProcessing(true, 'Parsing GPX data...');
        const path = parseGpx(gpxContent);
        originalPath.value = path;
        currentPath.value = path;
        fileName.value = filename;
        console.log('GPX parsed:', {
            filename,
            points: pathSize(path),
            distance: pathTotalDistance(path),
        });
    };

    const enhancePath = async () => {
        if (isProcessing.value || !originalPath.value) return;
        setProcessing(true, 'Enhancing path...');
        try {
            const opts: EnhanceOptionsDto = {
                fixElevation: config.value.enhance.fixElevation,
                computeMaxSpeeds: config.value.enhance.computeMaxSpeeds,
                virtualizeTrack: config.value.enhance.virtualizeTrack,
                computeOnePointPerSecond: config.value.enhance.computeOnePointPerSecond,
                simplifyEnabled: config.value.enhance.simplifyPath.enable,
                simplifyToleranceM: config.value.enhance.simplifyPath.tolerance,
                simplifyZExaggeration: config.value.enhance.simplifyPath.zExaggeration,
            };
            const enhanced = await enhanceWithCourse(
                originalPath.value,
                config.value.cyclist,  // already CyclistDto-shaped
                config.value.bike,     // already BikeDto-shaped
                buildWindDto(),
                buildPowerProviderDto(),
                opts,
            );
            currentPath.value = enhanced;
            console.log('Enhanced:', {
                points: pathSize(enhanced),
                distance: pathTotalDistance(enhanced),
                durationMs: pathDurationMs(enhanced),
            });
        } finally {
            setProcessing(false);
        }
    };

    return { currentPath, isProcessing, statusText, fileName, loadGPXFile, handleFileUpload, enhancePath };
}
```

### 3. Porter `useConfigPersistence.ts`

Copier intégralement depuis `virtual-cyclist/demo/src/composables/useConfigPersistence.ts`. Aucun appel moteur à adapter ; seul changement : importer `Config`, `DEFAULT_CONFIG` depuis `~/types` (renommé) et adapter la `selectedFields` Set sérialisation si besoin.

Vérifier que le `localStorage` key (`'virtual-cyclist-config'`) doit être renommé en `'vcyclist-demo-config'` pour éviter les conflits avec l'ancien demo en cas de domaine partagé. (Sinon, garder identique.)

### 4. Porter `useHoverSync.ts`

Copier depuis `virtual-cyclist/demo/src/composables/useHoverSync.ts` (utilisé par App.vue + DataChart + MapView en task 37). Adapter les accès au Path :

```ts
import { getField, pathSize } from '~/engine-shim';

// path.getLatitudeDeg(i) → getField(path, i, 'latitude') × RAD_TO_DEG
// path.getLongitudeDeg(i) → getField(path, i, 'longitude') × RAD_TO_DEG
// path.getDistance(i) → getField(path, i, 'distance')
```

⚠ `PointField.LATITUDE.unit == "radians"`. Confirmer en task 34 si `getField(path, i, 'latitude')` renvoie radians ou degrés ; si radians, multiplier par `180 / Math.PI`. Sinon, ajouter `pathLatitudeDeg`/`pathLongitudeDeg` à la façade de task 34 (alternative recommandée pour éviter les bugs côté demo).

### 5. App.vue : UI minimale d'intégration

Remplacer le contenu de `App.vue` par :

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useGPXDemo } from '~/composables/useGPXDemo';
import { useConfigPersistence, loadConfig } from '~/composables/useConfigPersistence';
import { pathSize, pathTotalDistance, pathDurationMs, pathElevationGain, pathElevationLoss } from '~/engine-shim';

const config = ref(loadConfig());
useConfigPersistence(config);

const { currentPath, isProcessing, statusText, fileName, loadGPXFile, handleFileUpload, enhancePath } = useGPXDemo(config);

const fileInput = ref<HTMLInputElement | null>(null);

const stats = computed(() => {
    const p = currentPath.value;
    if (!p) return null;
    return {
        points: pathSize(p),
        distanceKm: (pathTotalDistance(p) / 1000).toFixed(2),
        durationMin: (pathDurationMs(p) / 60_000).toFixed(1),
        elevationGain: pathElevationGain(p).toFixed(0),
        elevationLoss: pathElevationLoss(p).toFixed(0),
    };
});

const onFile = async (e: Event) => {
    const f = (e.target as HTMLInputElement).files?.[0];
    if (f) await handleFileUpload(f);
};

onMounted(() => {
    console.log('Demo ready');
});
</script>

<template>
    <div id="app" class="h-screen flex flex-col bg-white/95">
        <header class="bg-gradient-to-r from-slate-700 to-blue-500 text-white p-6 text-center shadow-md">
            <h1 class="text-4xl mb-2 font-light">🚴‍♂️ vcyclist — Kotlin/JS Demo</h1>
        </header>
        <main class="flex-1 p-4 space-y-4">
            <div class="space-x-2">
                <input ref="fileInput" type="file" accept=".gpx" @change="onFile" />
                <button class="px-4 py-2 bg-blue-500 text-white rounded" :disabled="isProcessing"
                        @click="loadGPXFile('./gpx/stelvio.gpx')">
                    Load stelvio.gpx (sample)
                </button>
                <button class="px-4 py-2 bg-green-500 text-white rounded" :disabled="isProcessing || !currentPath"
                        @click="enhancePath">
                    Enhance
                </button>
            </div>
            <p v-if="isProcessing" class="text-orange-600">{{ statusText }}</p>
            <p v-if="fileName" class="text-sm text-gray-600">File: {{ fileName }}</p>
            <pre v-if="stats" class="bg-gray-100 p-4 rounded">{{ JSON.stringify(stats, null, 2) }}</pre>
        </main>
    </div>
</template>
```

### 6. GPX d'exemple

Pour ce smoke, copier juste `virtual-cyclist/demo/public/gpx/stelvio.gpx` dans `demo/public/gpx/stelvio.gpx`. Les autres samples (task 38).

### 7. Smoke test manuel

```bash
cd vcyclist
./gradlew :engine:jsBrowserProductionLibraryDistribution
cd demo
npm install
npm run dev
```

Ouvrir `localhost:3000` :

1. Cliquer "Load stelvio.gpx (sample)" → stats affichent `points: ~5000`, `distanceKm: ~25`.
2. Cliquer "Enhance" → stats se mettent à jour : `points` change, `durationMin` apparaît plausible (~60-90 min pour stelvio en virtuel).
3. Recharger la page → la config persiste (vérifier dans DevTools localStorage).
4. Uploader un GPX externe via `<input type="file">` → stats affichées.

Vérifier la console : pas d'erreur, logs `GPX parsed` + `Enhanced` cohérents.

## Outputs

Créés :

- `demo/src/types.ts`
- `demo/src/composables/useGPXDemo.ts`
- `demo/src/composables/useConfigPersistence.ts`
- `demo/src/composables/useHoverSync.ts`
- `demo/public/gpx/stelvio.gpx`

Modifiés :

- `demo/src/App.vue` (réécrit avec l'UI d'intégration minimale)

## Validation

```bash
cd demo
npm run typecheck
npm run lint
npm run build
```

Critères :

- `npm run typecheck` retourne 0 erreur (les types DTO de `engine-shim.ts` doivent s'aligner avec les passages d'objets dans `useGPXDemo.ts`).
- Smoke manuel : upload + enhance fonctionnent, console montre `Enhanced: { points: ..., durationMs: ... }` cohérent.
- Persistance localStorage : modifier `DEFAULT_CONFIG.cyclist.massKg` à 100, recharger, vérifier la valeur restaurée.

## Done when

- [x] `types.ts` porté avec les noms de champs DTO (massKg, frontalAreaM2, etc.)
- [x] `useGPXDemo.ts` porté ; `parseGpx` + `enhanceWithCourse` invoqués
- [x] `useConfigPersistence.ts` porté (localStorage persistence)
- [x] `useHoverSync.ts` porté (utilisé en task 37)
- [x] `App.vue` montre Load/Enhance + stats
- [x] `stelvio.gpx` copié dans `public/gpx/`
- [x] Smoke manuel : Load + Enhance sur stelvio, durée virtualisée ~10 min plausible (le sample stelvio.gpx contient 259 points / 3.57 km — segment court raide, pas le col entier comme estimé initialement dans le spec)
- [x] `npm run typecheck` 0 erreur
- [x] Toutes les checkboxes cochées

## Notes

- **Latitude/longitude en radians** : `PointField.LATITUDE.unit == "radians"`. Si task 34 n'a pas exposé `pathLatitudeDeg(i)` direct, `getField(path, i, 'latitude') * 180/Math.PI` est le pont. Décider en début de task 36 : si > 3 sites utilisent la conversion, demander à task 34 d'ajouter les helpers `pathLatitudeDeg`/`pathLongitudeDeg` plutôt que de répéter la conversion partout.
- **`Config.bike.wheelRadius` vs `BikeDto.wheelRadiusM`** : le renommage doit cascader sur les composants tabs (en task 37). Garder une note dans le code en utilisant `wheelRadiusM` directement pour éviter une double conversion.
- **PowerSourceType en string** : côté TS l'enum a déjà `'constant' | 'constant_tiring' | 'source'`. Côté `PowerProviderDto`, on utilise `'constant' | 'constant_tiring' | 'from_data'` (pas `'source'`). Le mapping se fait dans `buildPowerProviderDto`. Renommer l'enum `PowerSourceType.source` en `PowerSourceType.from_data` pour cohérence (touche `types.ts` + futur PowerTab en task 37).
- **`enhance` (single-arg) vs `enhanceWithCourse`** : la démo utilise toujours `enhanceWithCourse` (5 paramètres + options) — `enhance` reste exposé pour les consommateurs Node de Phase 3.
- **Erreurs en `await`** : `enhanceWithCourse` renvoie un `Promise` Kotlin. `await` standard JS l'unwrap correctement (vérifié en task 33). Si une exception KMP fuit avec un message verbeux Kotlin, ajouter un `.catch` dans `enhancePath` qui re-lance avec un message friendlier.
