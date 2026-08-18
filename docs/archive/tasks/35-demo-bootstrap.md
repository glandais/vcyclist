# 35 — Démo Vue/Vite : bootstrap

## Goal

Créer le module `demo/` à la racine du repo vcyclist : application **Vue 3 + Vite + TypeScript** copiée et adaptée depuis `virtual-cyclist/demo/`, mais qui **consomme la sortie Kotlin/JS** (`@glandais/vcyclist-engine` produit par `:engine`) à la place du moteur TypeScript.

Cette tâche s'arrête au **shell vide** : `npm run dev` ouvre `http://localhost:3000` qui affiche le header + toolbar sans erreur, le module Kotlin/JS est résolu et imprime `console.log` au boot, mais aucune UI fonctionnelle (chart/map/tabs) n'est encore branchée. La porte d'entrée est posée ; les tâches 36/37/38 la remplissent.

## Depends on

- Task 34 (façade `EngineJsApi` étendue avec `enhanceWithCourse`, `getField`, `fieldDefinitions`)

## Inputs

- `virtual-cyclist/demo/package.json` — dépendances Vue/Vite/PrimeVue/Leaflet/Chart.js à reprendre.
- `virtual-cyclist/demo/vite.config.ts` — configuration Vite à adapter (alias).
- `virtual-cyclist/demo/tsconfig.json` + `tsconfig.node.json` — config TypeScript.
- `virtual-cyclist/demo/index.html`, `src/main.ts`, `src/App.vue` — shell minimal.
- `virtual-cyclist/demo/src/assets/main.css` + `custom.css` — styles globaux.
- `virtual-cyclist/demo/eslint.config.cjs` + `.prettierrc` (s'il existe).
- `engine/build/dist/js/productionLibrary/` — sortie attendue après `./gradlew :engine:jsBrowserProductionLibraryDistribution` (package npm avec `kotlin/vcyclist-engine.mjs` + `.d.ts`).

## Steps

### 1. Arborescence

```
demo/
├── package.json
├── package-lock.json     (généré par npm install)
├── vite.config.ts
├── tsconfig.json
├── tsconfig.node.json
├── eslint.config.cjs
├── index.html
├── public/               (vide pour l'instant, gpx samples en task 38)
└── src/
    ├── main.ts
    ├── App.vue
    ├── engine-shim.ts    (NOUVEAU — wrapper TS sur le bundle Kotlin/JS)
    └── assets/
        ├── main.css
        └── custom.css
```

### 2. `package.json`

Repartir du `virtual-cyclist/demo/package.json` et :

- Renommer en `@glandais/vcyclist-demo` (privé).
- Remplacer `"@glandais/elevation": "^3.2.2"` par `"@glandais/vcyclist-engine": "file:../engine/build/dist/js/productionLibrary"`.
- Scripts ajustés :

```json
{
  "scripts": {
    "predev": "cd .. && ./gradlew :engine:jsBrowserProductionLibraryDistribution",
    "prebuild": "cd .. && ./gradlew :engine:jsBrowserProductionLibraryDistribution",
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview",
    "typecheck": "vue-tsc --noEmit",
    "lint": "eslint . --ext .vue,.js,.jsx,.cjs,.mjs,.ts,.tsx,.cts,.mts",
    "lint:fix": "eslint . --ext .vue,.js,.jsx,.cjs,.mjs,.ts,.tsx,.cts,.mts --fix"
  }
}
```

Garder les `devDependencies` (vue-tsc, typescript, vite, @vitejs/plugin-vue, tailwindcss, eslint, prettier).

### 3. `vite.config.ts`

```ts
import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';
import { defineConfig } from 'vite';

export default defineConfig({
    plugins: [vue(), tailwindcss()],
    resolve: {
        alias: [
            { find: /^~\/(.*)$/, replacement: path.resolve(__dirname, './src/$1') },
        ],
        extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue'],
    },
    base: './',
    build: {
        sourcemap: true,
        outDir: 'dist',
        emptyOutDir: true,
        rollupOptions: {
            output: {
                manualChunks(id) {
                    if (id.includes('node_modules/primevue')) return 'primevue1';
                    if (id.includes('node_modules/@primevue')) return 'primevue2';
                    if (id.includes('node_modules/@primeuix')) return 'primeuix';
                    if (id.includes('node_modules/leaflet')) return 'leaflet';
                    if (id.includes('node_modules/chart.js')) return 'chartjs';
                    if (id.includes('node_modules/@glandais/vcyclist-engine')) return 'engine';
                    if (id.includes('node_modules')) return 'vendor';
                    return null;
                },
            },
        },
    },
    server: { port: 3000 },
    optimizeDeps: {
        include: ['@glandais/vcyclist-engine', 'chart.js', 'chartjs-plugin-zoom'],
    },
});
```

**Différences clés vs `virtual-cyclist/demo/vite.config.ts`** :
- L'alias `@lib`/`@/*` qui pointait sur `../src/` (TS engine) est **supprimé** — la démo n'importe plus rien depuis `virtual-cyclist`.
- Nouveau chunk dédié `engine` pour isoler le bundle Kotlin/JS.

### 4. `engine-shim.ts`

Wrapper TS qui re-exporte le bundle Kotlin/JS avec des types friendly :

```ts
// Kotlin/JS preserves the package namespace under the root export.
// e.g. `mod.io.github.glandais.engine.parseGpx`. We unwrap that here so the
// rest of the demo can import flat symbols.
import * as engineRaw from '@glandais/vcyclist-engine';

// The `.d.ts` Kotlin generates exposes types under `io.github.glandais.engine.*`.
// Re-export the subset we use so consumers can `import type { Path, CyclistDto }`.
type EngineNamespace = typeof engineRaw extends { io: { github: { glandais: { engine: infer T } } } }
    ? T
    : typeof engineRaw;

const ns = (engineRaw as any).io?.github?.glandais?.engine ?? engineRaw;
const engine = ns as EngineNamespace;

export const parseGpx = engine.parseGpx;
export const writeGpx = engine.writeGpx;
export const enhance = engine.enhance;
export const enhanceWithCourse = engine.enhanceWithCourse;
export const pathSize = engine.pathSize;
export const pathTotalDistance = engine.pathTotalDistance;
export const pathDurationMs = engine.pathDurationMs;
export const pathElevationGain = engine.pathElevationGain;
export const pathElevationLoss = engine.pathElevationLoss;
export const pointAt = engine.pointAt;
export const getField = engine.getField;
export const fieldDefinitions = engine.fieldDefinitions;

export type Path = ReturnType<typeof engine.parseGpx>;
export type PointDto = ReturnType<typeof engine.pointAt>;
export type CyclistDto = Parameters<typeof engine.enhanceWithCourse>[1] extends infer T | null ? T : never;
export type BikeDto = Parameters<typeof engine.enhanceWithCourse>[2] extends infer T | null ? T : never;
export type WindDto = Parameters<typeof engine.enhanceWithCourse>[3] extends infer T | null ? T : never;
export type PowerProviderDto = Parameters<typeof engine.enhanceWithCourse>[4] extends infer T | null ? T : never;
export type EnhanceOptionsDto = Parameters<typeof engine.enhanceWithCourse>[5] extends infer T | null ? T : never;
export type FieldDefinitionDto = ReturnType<typeof engine.fieldDefinitions>[number];
```

Si la résolution des types via `Parameters<>`/`ReturnType<>` est trop fragile, importer directement depuis le `.d.ts` Kotlin :

```ts
import type {
    io_github_glandais_engine_CyclistDto as CyclistDto,
    io_github_glandais_engine_BikeDto as BikeDto,
    // …
} from '@glandais/vcyclist-engine';
```

(les noms exacts dépendent de la convention Kotlin → TS — vérifier dans le `.d.ts` généré).

### 5. `src/main.ts` + `src/App.vue` (shell minimal)

```ts
// src/main.ts
import Aura from '@primeuix/themes/aura';
import PrimeVue from 'primevue/config';
import ToastService from 'primevue/toastservice';
import { createApp } from 'vue';
import App from '~/App.vue';
import '~/assets/main.css';

const app = createApp(App);
app.use(PrimeVue, {
    theme: { preset: Aura, options: { prefix: 'p', darkModeSelector: '.dark', cssLayer: false } },
    ripple: true,
});
app.use(ToastService);
app.mount('#app');
```

```vue
<!-- src/App.vue -->
<script setup lang="ts">
import { onMounted } from 'vue';
import { fieldDefinitions } from '~/engine-shim';

onMounted(() => {
    const defs = fieldDefinitions();
    console.log(`vcyclist-engine loaded: ${defs.length} fields available`);
});
</script>

<template>
    <div id="app" class="h-screen flex flex-col bg-white/95 mx-auto w-full shadow-2xl overflow-hidden">
        <header class="bg-gradient-to-r from-slate-700 to-blue-500 text-white p-6 text-center shadow-md flex-shrink-0">
            <h1 class="text-4xl mb-2 font-light">🚴‍♂️ vcyclist — Kotlin/JS Demo</h1>
            <p class="text-lg opacity-90">
                Vue + Vite demo consuming the Kotlin/JS engine bundle (Phase 9).
            </p>
        </header>
        <main class="flex-1 p-4">
            <p>Shell ready. Engine integration coming in task 36.</p>
        </main>
    </div>
</template>
```

### 6. `index.html` + `tsconfig.json`

Copier `virtual-cyclist/demo/index.html` (juste `<div id="app"></div>` + `<script type="module" src="/src/main.ts"></script>`).

`tsconfig.json` : copier celui du demo TS et retirer la référence aux paths `@lib`/`@/*`. Garder `paths: { "~/*": ["./src/*"] }`.

### 7. `settings.gradle.kts`

Ne **pas** ajouter `:demo` au build Gradle dans cette tâche — la wrapper Gradle vient en task 38. Pour l'instant, la démo se gère uniquement via npm scripts (qui appellent `./gradlew :engine:jsBrowserProductionLibraryDistribution` en `predev`/`prebuild`).

### 8. Smoke test manuel

```bash
cd vcyclist
./gradlew :engine:jsBrowserProductionLibraryDistribution
cd demo
npm install
npm run dev
```

Ouvrir `http://localhost:3000`, vérifier :

- Header + paragraphe rendus.
- Console DevTools : `vcyclist-engine loaded: 36 fields available`.
- Pas d'erreur réseau ou TS.

```bash
npm run build
ls dist/
# Doit contenir index.html + assets/ + au moins un chunk "engine-*.js"
```

```bash
npm run typecheck
# Doit passer (0 erreur)
```

## Outputs

Créés :

- `demo/package.json`
- `demo/package-lock.json` (généré par `npm install`)
- `demo/vite.config.ts`
- `demo/tsconfig.json` + `tsconfig.node.json`
- `demo/eslint.config.cjs`
- `demo/index.html`
- `demo/src/main.ts`
- `demo/src/App.vue`
- `demo/src/engine-shim.ts`
- `demo/src/assets/main.css`
- `demo/src/assets/custom.css`
- `demo/.gitignore` (au minimum : `node_modules/`, `dist/`)

Modifiés : aucun.

## Validation

```bash
# depuis vcyclist/
./gradlew :engine:jsBrowserProductionLibraryDistribution
cd demo
npm install
npm run typecheck
npm run lint
npm run build
```

Critères :

- `npm install` réussit (le `file:../engine/build/dist/js/productionLibrary` résout).
- `npm run typecheck` retourne 0 erreur.
- `npm run lint` clean (ou seulement des warnings cosmétiques).
- `npm run build` produit `demo/dist/` < 2 MB.
- `npm run dev` ouvre `http://localhost:3000` et la console affiche `vcyclist-engine loaded: 36 fields available`.

## Done when

- [x] `demo/` créé avec arborescence complète
- [x] `package.json` dépend de `@glandais/vcyclist-engine` via `file:../engine/build/dist/js/productionLibrary`
- [x] `vite.config.ts` n'a aucun alias résiduel vers `virtual-cyclist/src/`
- [x] `engine-shim.ts` re-exporte les 12 symboles attendus
- [x] `App.vue` boot loggue le count de fields = 36
- [x] `npm install` + `npm run dev` + `npm run typecheck` + `npm run build` verts
- [x] `.gitignore` ignore `node_modules/`, `dist/`
- [x] `package-lock.json` commit
- [x] Toutes les checkboxes cochées

## Notes

- **Pourquoi `predev`/`prebuild` shell out vers Gradle** : le bundle Kotlin/JS est sous `engine/build/dist/...`, lié via `file:`. Si Gradle ne l'a pas généré, `npm install` plante. Le hook `predev` garantit que la dépendance existe à chaque dev session. Coût : ~1 s en cache chaud, ~15 s à froid.
- **Path résolution `file:`** : npm crée un symlink (sur Linux/macOS) ou copie (sur Windows). Si l'engine est régénéré, npm ne re-résout pas automatiquement la dépendance — sur Linux le symlink suffit, sur Windows il faut `npm install` à nouveau. La doc README de task 38 documentera ce point.
- **Pourquoi pas d'alias `@/*`** : il pointait sur `virtual-cyclist/src/` (le TS engine). On utilise uniquement `~/*` (chemin relatif au demo) et l'import explicite `@glandais/vcyclist-engine`.
- **`engine-shim.ts` peut être supprimé** si les types `.d.ts` Kotlin sont assez ergonomiques. Le shim est inséré ici comme tampon : si Kotlin/JS génère `kotlin.Promise<Path>` qui ne s'auto-cast pas en `Promise<Path>` standard, le shim corrige. À évaluer en task 36 ; si superflu, l'élaguer.
- **PrimeVue + Tailwind v4** : le demo TS utilise Tailwind v4 (CSS-first config). Reprendre tel quel. Si conflit avec PrimeVue Aura preset, copier `custom.css` du demo TS qui contient déjà les overrides nécessaires.
