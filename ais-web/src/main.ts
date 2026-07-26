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
  shouldForceOverlayKeyboardFallback,
} from '@/utils/visualViewport'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(ElementPlus)
app.mount('#app')

registerPwaUpdates()

function isEditableField(el: EventTarget | null): el is HTMLElement {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable
}

function isMobileShellField(el: HTMLElement): boolean {
  return Boolean(el.closest('.feishu-layout'))
}

/**
 * True when an editable field is focused in an installed standalone PWA
 * (overlay keyboard). The nested mobile shell owns its own fallback geometry,
 * so the root must stay unforced there.
 */
function shouldForceRootKeyboardFallback(): boolean {
  if (typeof document === 'undefined' || typeof window === 'undefined') return false
  const active = document.activeElement
  return isEditableField(active)
    && !isMobileShellField(active)
    && shouldForceOverlayKeyboardFallback(true)
}

function shouldDeferRootViewportGeometry(): boolean {
  if (typeof document === 'undefined') return false
  const active = document.activeElement
  return isEditableField(active)
    && isMobileShellField(active)
    && shouldForceOverlayKeyboardFallback(true)
}

// The nested mobile workbench owns its focused standalone-PWA shell geometry.
// Keep root measurements for normal mobile/browser resizing, but do not let
// #app-container apply a second fallback while the composer owns the keyboard.
subscribeVisualViewport(() => {}, {
  cssTarget: () => (
    shouldDeferRootViewportGeometry() ? null : document.documentElement
  ),
  forceKeyboardFallback: shouldForceRootKeyboardFallback,
})

/**
 * Chat / fixed full-height shells already pin themselves via pinShell.
 * For those, a global center scrollIntoView fights the flex layout and can
 * scroll the wrong container on Android PWA. Only auto-scroll free-form pages
 * (login, admin forms, etc.).
 *
 * Covers both the legacy single-page shell (`.feishu-page`) and the nested
 * mobile layout (`.feishu-layout` / `.chat-page`).
 */
function shouldAutoScrollOnFocus(el: HTMLElement): boolean {
  return !el.closest('.feishu-page, .feishu-layout, .chat-page')
}

let cancelFocusWatch: (() => void) | null = null

// When an editable field receives focus, keep CSS vars in sync while the soft
// keyboard animates open. Android overlay keyboards often never fire a useful
// visualViewport.resize / VirtualKeyboard geometry, so we force a height
// fallback in those environments.
document.addEventListener(
  'focusin',
  ((event: FocusEvent) => {
    const raw = event.target
    if (!isEditableField(raw)) return
    const el: HTMLElement = raw

    // FeishuMobileLayout is the sole geometry owner for the nested mobile
    // workspace. A second forced root measurement clips that shell when a
    // standalone PWA reports real resizes-content geometry.
    if (isMobileShellField(el)) return

    cancelFocusWatch?.()
    cancelFocusWatch = null

    // Installed PWA overlay keyboards leave visualViewport.height unchanged.
    // Force a height fallback only in standalone display mode so shells that
    // listen for `ais:visual-viewport` still shrink above the keyboard without
    // double-shrinking ordinary browsers / Feishu WebViews that already resize.
    const forceFallback = () => shouldForceOverlayKeyboardFallback(true)

    cancelFocusWatch = watchViewportWhileFocused(
      (state) => {
        applyVisualViewportCssVars(document.documentElement, state)
        // Notify listeners that pin their own shell (FeishuH5View / FeishuMobileLayout).
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
        durationMs: 1800,
        intervalMs: 80,
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
