import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { registerPwaUpdates } from './pwa'
import {
  subscribeVisualViewport,
  applyVisualViewportCssVars,
  watchViewportWhileFocused,
  scrollElementIntoVisualViewport,
  isStandaloneDisplayMode,
} from '@/utils/visualViewport'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')

registerPwaUpdates()

// Global CSS tokens on :root — shells read --vv-height / --vv-offset-top.
subscribeVisualViewport(() => {}, { cssTarget: document.documentElement })

function isEditableField(el: EventTarget | null): el is HTMLElement {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

/**
 * Chat / fixed full-height shells already pin themselves via pinShell.
 * For those, a global center scrollIntoView fights the flex layout and can
 * scroll the wrong container on Android PWA. Only auto-scroll free-form pages
 * (login, admin forms, etc.).
 */
function shouldAutoScrollOnFocus(el: HTMLElement): boolean {
  return !el.closest('.feishu-page')
}

let cancelFocusWatch: (() => void) | null = null

// When an editable field receives focus, keep CSS vars in sync while the soft
// keyboard animates open. Android standalone PWAs often never fire a useful
// visualViewport.resize, so we also force a keyboard-height fallback there.
document.addEventListener(
  'focusin',
  ((event: FocusEvent) => {
    const raw = event.target
    if (!isEditableField(raw)) return
    const el: HTMLElement = raw

    cancelFocusWatch?.()
    cancelFocusWatch = null

    const forceFallback = () => isStandaloneDisplayMode()

    cancelFocusWatch = watchViewportWhileFocused(
      (state) => {
        applyVisualViewportCssVars(document.documentElement, state)
        // Notify listeners that pin their own shell (FeishuH5View).
        window.dispatchEvent(
          new CustomEvent('ais:visual-viewport', { detail: state }),
        )
        if (shouldAutoScrollOnFocus(el)) {
          scrollElementIntoVisualViewport(el, {
            block: 'center',
            behavior: 'smooth',
          })
        }
      },
      {
        forceKeyboardFallback: forceFallback,
        durationMs: 1400,
        intervalMs: 100,
      },
    )

    const onBlur = () => {
      cancelFocusWatch?.()
      cancelFocusWatch = null
      // Re-measure without the focus fallback so the shell expands again.
      applyVisualViewportCssVars(document.documentElement)
      window.dispatchEvent(
        new CustomEvent('ais:visual-viewport', {
          detail: undefined,
        }),
      )
      el.removeEventListener('blur', onBlur)
    }
    el.addEventListener('blur', onBlur, { once: true })
  }) as EventListener,
  true,
)
