import ui from '@nuxt/ui/vue-plugin';
import { createApp } from 'vue';
import { createRouter, createWebHashHistory } from 'vue-router';
import App from '~/App.vue';
import '~/assets/main.css';

const app = createApp(App);

// Hash history, not web history: GitHub Pages serves a static index.html with no rewrite rules,
// so a path-based deep link would 404.
const router = createRouter({
    history: createWebHashHistory(),
    routes: [
        {
            path: '/',
            name: 'gpx',
            component: () => import('~/views/GpxAnalysisView.vue'),
        },
        {
            path: '/elevation',
            name: 'elevation',
            component: () => import('~/views/ElevationExplorerView.vue'),
        },
        { path: '/:pathMatch(.*)*', redirect: '/' },
    ],
});

app.use(router);
app.use(ui);

app.mount('#app');
