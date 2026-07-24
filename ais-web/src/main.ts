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

subscribeVisualViewport(() => {}, { cssTarget: document.documentElement })

function isEditableField(el: EventTarget | null): el is HTMLElement {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function scrollEditableIntoView(el: HTMLElement): void {
  applyVisualViewportCssVars(document.documentElement, readVisualViewport())
  requestAnimationFrame(() => {
    el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'smooth' })
  })
}

// When an editable field receives focus, wait 350 ms for the soft keyboard
// to finish opening, then update CSS vars and scroll the field into view.
// A single-shot visualViewport.resize listener catches delayed animations.
document.addEventListener('focusin', ((event: FocusEvent) => {
  const raw = event.target
  if (!isEditableField(raw)) return
  const el: HTMLElement = raw

  let settled = false
  let timerId: ReturnType<typeof setTimeout> | undefined

  function scrollAndUpdate() {
    if (settled) return
    settled = true
    clearTimeout(timerId)
    scrollEditableIntoView(el)
    window.visualViewport?.removeEventListener('resize', onViewportChange)
  }

  function onViewportChange() {
    scrollAndUpdate()
  }

  timerId = setTimeout(scrollAndUpdate, 350)
  window.visualViewport?.addEventListener('resize', onViewportChange, { once: true })

  const onBlur = () => {
    clearTimeout(timerId)
    window.visualViewport?.removeEventListener('resize', onViewportChange)
    el.removeEventListener('blur', onBlur)
  }
  el.addEventListener('blur', onBlur, { once: true })
}) as EventListener, true)
