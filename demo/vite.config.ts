import tailwindcss from '@tailwindcss/vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';
import { defineConfig } from 'vite';

function manualChunks(id: string): string | null {
    if (id.includes('node_modules/primevue')) {
        return 'primevue1';
    }
    if (id.includes('node_modules/@primevue')) {
        return 'primevue2';
    }
    if (id.includes('node_modules/@primeuix')) {
        return 'primeuix';
    }
    if (id.includes('node_modules/leaflet')) {
        return 'leaflet';
    }
    if (id.includes('node_modules/chart.js')) {
        return 'chartjs';
    }
    if (id.includes('node_modules/@glandais/vcyclist-engine')) {
        return 'engine';
    }
    if (id.includes('node_modules')) {
        return 'vendor';
    }
    return null;
}

export default defineConfig({
    plugins: [vue(), tailwindcss()],
    resolve: {
        alias: [
            { find: /^~\/(.*)$/, replacement: path.resolve(__dirname, './src/$1') },
            // The engine bundle is linked with `file:` and lives outside this directory, so its
            // own `import '@garmin/fitsdk'` (pulled in since the engine gained FIT export in
            // task g10) resolves relative to `engine/build/dist/...`, where there is no
            // node_modules. Point it at the copy installed here instead.
            {
                find: '@garmin/fitsdk',
                replacement: path.resolve(__dirname, './node_modules/@garmin/fitsdk/src/index.js'),
            },
        ],
        extensions: ['.mjs', '.js', '.ts', '.jsx', '.tsx', '.json', '.vue'],
    },
    base: process.env.DEPLOY_TARGET === 'gh-pages' ? '/vcyclist/' : './',
    build: {
        sourcemap: true,
        outDir: 'dist',
        emptyOutDir: true,
        rollupOptions: {
            output: {
                manualChunks: manualChunks,
            },
        },
    },
    server: {
        port: 3000,
    },
    optimizeDeps: {
        include: ['@glandais/vcyclist-engine', 'chart.js', 'chartjs-plugin-zoom'],
    },
});
