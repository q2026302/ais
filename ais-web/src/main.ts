import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { registerPwaUpdates } from './pwa'
import { subscribeVisualViewport } from '@/utils/visualViewport'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')

registerPwaUpdates()

// Track visual viewport → CSS vars for keyboard-safe mobile layout.
subscribeVisualViewport(() => {}, { cssTarget: document.documentElement })
