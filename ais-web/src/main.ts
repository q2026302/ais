import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { registerPwaUpdates } from './pwa'
import {
  subscribeVisualViewport,
  readVisualViewport,
  applyVisualViewportCssVars,
} from '@/utils/visualViewport'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')

registerPwaUpdates()

// Track visual viewport → CSS vars for keyboard-safe mobile layout.
subscribeVisualViewport(() => {}, { cssTarget: document.documentElement })

// Scroll focused inputs into view when keyboard opens (critical for Android PWA
// where visualViewport resize events may not fire reliably).
document.addEventListener('focusin', ((event: FocusEvent) => {
  const target = event.target
  if (!(target instanceof HTMLElement)) return
  const tag = target.tagName
  if (tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT' && !target.isContentEditable) return
  // Force-update CSS vars even when visualViewport events haven't fired
  // (common in Android PWA standalone mode).
  applyVisualViewportCssVars(document.documentElement, readVisualViewport())
  // Wait two frames for the keyboard + CSS var to settle, then scroll.
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' })
    })
  })
}) as EventListener, true)
