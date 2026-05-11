import Aura from '@primeuix/themes/aura';
import PrimeVue from 'primevue/config';
import ToastService from 'primevue/toastservice';
import { createApp } from 'vue';
import App from '~/App.vue';
import '~/assets/main.css';

const app = createApp(App);
app.use(PrimeVue, {
    theme: {
        preset: Aura,
        options: { prefix: 'p', darkModeSelector: '.dark', cssLayer: false },
    },
    ripple: true,
});
app.use(ToastService);
app.mount('#app');
