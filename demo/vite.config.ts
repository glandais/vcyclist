import ui from '@nuxt/ui/vite';
import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';
import { defineConfig } from 'vite';

// The Kotlin/JS engine is consumed straight out of the Gradle build directory rather than as an
// npm dependency. A `file:` dependency would point at a gitignored build output, which Dependabot
// cannot resolve when it clones the repo without building — it silently skipped every update for
// this whole directory. An alias keeps the same import specifier with a manifest Dependabot can
// read. `predev` / `prebuild` in package.json still produce the directory below.
const enginePackage = path.resolve(__dirname, '../engine/build/dist/js/productionLibrary');

function manualChunks(id: string): string | null {
    if (id.includes('node_modules/@nuxt/ui')) {
        return 'nuxtui';
    }
    if (id.includes('node_modules/reka-ui')) {
        return 'rekaui';
    }
    if (id.includes('node_modules/leaflet')) {
        return 'leaflet';
    }
    if (id.includes('node_modules/chart.js')) {
        return 'chartjs';
    }
    if (id.includes(enginePackage)) {
        return 'engine';
    }
    if (id.includes('node_modules')) {
        return 'vendor';
    }
    return null;
}

export default defineConfig({
    plugins: [vue(), ui({ colorMode: false }), tailwindcss()],
    resolve: {
        alias: [
            { find: /^~\/(.*)$/, replacement: path.resolve(__dirname, './src/$1') },
            { find: /^@glandais\/vcyclist-engine$/, replacement: enginePackage },
        ],
        extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue'],
    },
    base: process.env.DEPLOY_TARGET === 'gh-pages' ? '/vcyclist/' : './',
    build: {
        sourcemap: true,
        outDir: 'dist',
        emptyOutDir: true,
        // The engine bundle is UMD and now lives outside node_modules, which the default
        // `[/node_modules/]` filter would leave untransformed.
        commonjsOptions: {
            include: [
                /node_modules/,
                new RegExp(enginePackage.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')),
            ],
        },
        rollupOptions: {
            output: {
                manualChunks: manualChunks,
            },
        },
    },
    server: {
        port: 3000,
        // enginePackage sits above the Vite root, which fs strictness blocks by default.
        fs: {
            allow: [path.resolve(__dirname), enginePackage],
        },
    },
    optimizeDeps: {
        include: ['@glandais/vcyclist-engine', 'chart.js', 'chartjs-plugin-zoom'],
    },
});
