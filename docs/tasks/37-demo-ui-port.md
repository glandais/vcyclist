# 37 — Démo Vue/Vite : portage UI complète (chart + map + tabs + sidebar)

## Goal

Atteindre la **parité visuelle et fonctionnelle** avec la démo TS d'origine : header + toolbar, FileSection (upload), ConfigModal avec 6 tabs (Cyclist, Bike, Power, Wind, Enhance, Fields), FieldsSidebar (sélection des 36 champs), DataChart (Chart.js + zoom + crosshair + sync hover), MapView (Leaflet + track + hover marker + popup), persistance des préférences UI.

Aucune logique moteur nouvelle — uniquement portage de composants Vue, avec **trois adaptations clés** :

1. `path.getField(i, pointField)` → `getField(path, i, fieldProp: string)` (string-keyed, exposé par task 34).
2. `path.getLatitudeDeg(i)` / `getLongitudeDeg(i)` → `getField(path, i, 'latitude') * 180/Math.PI` (radians → degrés ; cf. task 36 notes — si task 34 a exposé `pathLatitudeDeg`, utiliser ces helpers à la place).
3. `FIELD_DEFINITIONS` (nested, par catégorie) → `fieldDefinitions()` (plat, un DTO par champ) → reconstruction des catégories côté `fieldConfig.ts`.

## Depends on

- Task 34 (`getField`, `fieldDefinitions`)
- Task 35, 36 (shell + intégration moteur fonctionnelle)

## Inputs

Composants à porter intégralement (16 fichiers `.vue`, 5 composables, 1 config) :

```
virtual-cyclist/demo/src/components/
  Toolbar.vue                — header actions
  FileSection.vue            — file picker + sample selector
  ConfigModal.vue            — modal wrapper for tabs
  Modal.vue                  — generic modal
  CyclistTab.vue             — cyclist sliders
  BikeTab.vue                — bike sliders
  PowerTab.vue               — power source + harmonics
  WindTab.vue                — wind speed/direction
  EnhanceOptionsTab.vue      — pipeline flags
  FieldsTab.vue              — field selection (in ConfigModal)
  FieldsSidebar.vue          — field selection (always-visible sidebar)
  DataChart.vue              — Chart.js wrapper
  DataPanel.vue              — stats panel below chart (?)
  MapView.vue                — Leaflet wrapper
  ControlPanel.vue           — minor controls
  VisualizationControls.vue  — chart/map controls
  SliderInput.vue            — generic slider + input

virtual-cyclist/demo/src/composables/
  useChart.ts                — Chart.js setup + update + zoom
  useMap.ts                  — Leaflet setup + route + hover

virtual-cyclist/demo/src/config/fieldConfig.ts
```

## Steps

### 1. Copier les composants verbatim, puis adapter

```bash
cp -r virtual-cyclist/demo/src/components/* vcyclist/demo/src/components/
cp virtual-cyclist/demo/src/composables/{useChart,useMap}.ts vcyclist/demo/src/composables/
cp -r virtual-cyclist/demo/src/config vcyclist/demo/src/config/
```

Puis, dans chaque fichier, remplacer les imports :

- `from '@lib/types'` → `from '~/engine-shim'` (Path, PointDto, etc.)
- `from '@lib/types/path'` → idem
- `from '@/types/path'` → idem (FieldDefinition)
- `from '@/types'` → `from '~/types'` (Config, PRESETS, DEFAULT_CONFIG)

⚠ Si un import TS pointe sur `@/physics` ou `@lib/enhancer` (moteur), il ne devrait pas exister dans les composants UI ; le seul site d'appel moteur est `useGPXDemo.ts` (déjà porté en task 36). Si une fuite est détectée, la corriger en passant par le Path déjà enrichi.

### 2. Adapter `useChart.ts`

Changements vs TS :

```ts
// AVANT
import { fieldToPointField } from '@lib/types';
// pour chaque point :
const value = path.getField(i, fieldToPointField[fieldKey]);

// APRÈS
import { getField, pathSize, type Path } from '~/engine-shim';
// pour chaque point :
const value = getField(path, i, fieldKey);
```

`pointCount = path.getPointCount()` → `pointCount = pathSize(path)`.

`path.getDistance(i)` → `getField(path, i, 'distance')`.

### 3. Adapter `useMap.ts`

```ts
// AVANT
const lat = path.getLatitudeDeg(i);
const lon = path.getLongitudeDeg(i);
const elevation = path.getElevation?.(info.index) ?? null;
const speed = path.getSpeed?.(info.index) ?? null;

// APRÈS
const RAD_TO_DEG = 180 / Math.PI;
const lat = getField(path, i, 'latitude') * RAD_TO_DEG;
const lon = getField(path, i, 'longitude') * RAD_TO_DEG;
const elevation = getField(path, info.index, 'elevation');
const speed = getField(path, info.index, 'speed');
```

(Ou utiliser `pathLatitudeDeg(path, i)` si task 34 expose ce helper — recommandé pour réduire les sites à patcher.)

`path.getPointCount()` → `pathSize(path)`.

### 4. Adapter `fieldConfig.ts`

La structure TS `FIELD_DEFINITIONS` est nested : `Array<{ id, name, notSelectable, fields: Array<FieldDefinition> }>`. Le `fieldDefinitions()` Kotlin/JS de task 34 retourne un array plat de `FieldDefinitionDto` avec `categoryId` + `categoryName`. Reconstruction :

```ts
import { fieldDefinitions, type FieldDefinitionDto } from '~/engine-shim';
import type { CategoryConfig, DemoFieldDefinition } from '~/types';

function getFieldConfig(): Record<string, CategoryConfig> {
    const result: Record<string, CategoryConfig> = {};
    const defs = fieldDefinitions().filter(d => !d.notSelectable);
    const count = defs.length;
    // Group flat list by categoryId
    for (const d of defs) {
        if (!result[d.categoryId]) {
            result[d.categoryId] = {
                name: d.categoryName,
                axis: d.categoryId,
                unit: '',
                fields: {},
            };
        }
        const cat = result[d.categoryId];
        cat.fields[d.prop] = { ...d, color: 'rgba(255, 0, 0, 0.8)' };
        // Compute unit per category
        if (cat.unit === '') {
            cat.unit = d.unit;
        } else if (cat.unit !== d.unit) {
            cat.unit = '?';
        }
    }
    // Assign HSL colors
    let i = 0;
    const dh = 360 / count;
    for (const categoryId in result) {
        const categoryConfig = result[categoryId];
        for (const fieldId in categoryConfig.fields) {
            const h = i++ * dh;
            categoryConfig.fields[fieldId].color = `hsl(${h} 50% 50%)`;
        }
    }
    return result;
}

export const fieldConfig: Record<string, CategoryConfig> = getFieldConfig();
```

### 5. App.vue : composition finale

Reprendre la structure de `virtual-cyclist/demo/src/App.vue` :

```vue
<script setup lang="ts">
import Toast from 'primevue/toast';
import { useToast } from 'primevue/usetoast';
import { computed, nextTick, onMounted, ref, watch } from 'vue';
import ConfigModal from '~/components/ConfigModal.vue';
import DataChart from '~/components/DataChart.vue';
import FieldsSidebar from '~/components/FieldsSidebar.vue';
import FileSection from '~/components/FileSection.vue';
import MapView from '~/components/MapView.vue';
import Toolbar from '~/components/Toolbar.vue';
import { loadConfig, useConfigPersistence } from '~/composables/useConfigPersistence';
import { useGPXDemo } from '~/composables/useGPXDemo';
import { useHoverSync } from '~/composables/useHoverSync';
// … (rest identical to virtual-cyclist/demo/src/App.vue, lines 14-160)
</script>
```

Modifier le `onMounted` initial pour pointer sur le path public local :

```ts
onMounted(() => {
    console.log('Demo initialized');
    loadGPXFile('./gpx/stelvio.gpx').then(() => enhancePath());
});
```

(Le `./` au lieu de `/` permet de servir derrière n'importe quel sous-chemin — utile pour GitHub Pages en task 39.)

### 6. CSS

Copier `virtual-cyclist/demo/src/assets/main.css` et `custom.css` tels quels.

### 7. Smoke manuel

```bash
cd demo
npm install                              # si pas déjà fait
npm run dev
```

Sur `localhost:3000` :

1. La page charge stelvio.gpx automatiquement + lance enhance.
2. La carte Leaflet affiche le track Stelvio (zoom auto-fit).
3. DataChart affiche elevation + speed sur l'axe X = distance.
4. FieldsSidebar liste 14 catégories, chaque catégorie contenant ses champs ; cocher "power_total" ajoute la trace sur le chart.
5. Hover sur le chart → marker rouge sur la carte + popup distance/elevation/speed. Inverse aussi (hover map → vertical line sur chart).
6. Ouvrir ConfigModal : 6 tabs Cyclist / Bike / Power / Wind / Enhance / Fields. Modifier `cyclist.massKg` à 110 + cliquer "Enhance" depuis la toolbar → la durée recalculée doit être ≥ avec 80 kg défaut.
7. Bouton "Reset zoom" → chart + map reviennent à la vue d'ensemble.
8. Recharger la page → tous les paramètres (config + UI state) restaurés depuis localStorage.

## Outputs

Créés (16 + 2 + 1 = 19 fichiers) :

- `demo/src/components/*.vue` (16 fichiers)
- `demo/src/composables/useChart.ts`
- `demo/src/composables/useMap.ts`
- `demo/src/config/fieldConfig.ts`

Modifiés :

- `demo/src/App.vue` (réécrit avec toolbar + sidebar + chart + map + modals)

## Validation

```bash
cd demo
npm run typecheck
npm run lint
npm run build
```

Critères :

- `typecheck` 0 erreur.
- `lint` clean.
- `npm run build` produit `dist/` < 3 MB total (chunks séparés primevue/leaflet/chartjs/engine).
- Smoke manuel passe les 8 points ci-dessus.

## Done when

- [x] 16 composants `.vue` portés et imports adaptés
- [x] `useChart.ts` porté : `getField(path, i, fieldKey)` au lieu de `path.getField(i, pointField)`
- [x] `useMap.ts` porté : conversion radians → degrés (ou helpers task 34)
- [x] `fieldConfig.ts` reconstruit les catégories depuis `fieldDefinitions()` plat
- [x] `App.vue` réécrit avec toolbar + sidebar + chart + map + modals
- [x] CSS (main + custom) copiés
- [x] Smoke manuel passe sur stelvio.gpx : map ✓, chart ✓, hover sync ✓, tabs ✓, persistance ✓
- [x] `npm run typecheck` + `npm run lint` + `npm run build` verts
- [x] Toutes les checkboxes cochées

## Notes

- **Charge initiale 36 champs** : `fieldDefinitions()` est invoqué une fois au boot. Si > 50 ms, mettre en cache au top-level (ce que le code recommandé fait déjà via `const fieldConfig = getFieldConfig()`).
- **PrimeVue tabs** : la sélection de tab par défaut + l'état "Cyclist actif" doivent être préservés via la persistance UI (`virtual-cyclist-ui-state` key dans `App.vue`, ligne 47-78). Renommer en `vcyclist-demo-ui-state` pour éviter collision.
- **Chart.js performances** : sur stelvio.gpx (~5000 pts × 5 fields), Chart.js doit rester fluide. Si lag, activer `decimation` Chart.js (cf. docs Chart.js v4) — peut être traité en hot-fix après cette tâche.
- **Leaflet et SSR** : Vite ne fait pas de SSR pour cette démo, donc l'import direct de Leaflet dans `useMap.ts` est OK (pas besoin du `onMounted` dance).
- **Field colors** : la palette HSL en `fieldConfig.ts` itère sur les champs dans l'ordre d'apparition. Si on veut une palette stable inter-runs, trier les catégories par `categoryId` avant l'attribution des H. (Optionnel ; le code actuel produit déjà un ordre stable car `fieldDefinitions()` est déterministe.)
- **Si task 34 expose `pathLatitudeDeg` / `pathLongitudeDeg`** : utiliser ces helpers à la place du `* RAD_TO_DEG` manuel — moins de bugs sur latitude négative.
