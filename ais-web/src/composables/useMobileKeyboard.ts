import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  applyVisualViewportCssVars,
  pinShellToVisualViewport,
  shouldForceOverlayKeyboardFallback,
  subscribeVisualViewport,
  type VisualViewportState,
} from '@/utils/visualViewport'

/**
 * Mobile keyboard / composer state extracted from FeishuH5View.vue.
 *
 * Tracks soft keyboard visibility via visualViewport and composer focus,
 * and provides helpers to pin the page shell geometry so the composer
 * sits flush above the keyboard (WeChat/Feishu chat-page pattern).
 */
export function useMobileKeyboard() {
  /** Soft keyboard reported by visualViewport / VirtualKeyboard / PWA standalone fallback. */
  const keyboardOpen = ref(false)
  /** Composer textarea focused — hide bottom-nav immediately (before VV reports). */
  const composerFocused = ref(false)
  /**
   * Effective "input mode": hide bottom-nav and pin shell so the composer sits
   * flush above the keyboard (WeChat/Feishu chat-page pattern).
   */
  const inputChromeCollapsed = computed(() => keyboardOpen.value || composerFocused.value)
  let stopVisualViewport: (() => void) | null = null

  function applyViewportState(state: VisualViewportState) {
    keyboardOpen.value = state.keyboardOpen
  }

  /** Write explicit shell geometry from a measured state (CSS vars alone are flaky on WebAPK). */
  function applyShellGeometry(el: HTMLElement | null, state: VisualViewportState) {
    if (!el) return
    applyVisualViewportCssVars(el, state)
    el.style.top = `${state.offsetTop}px`
    el.style.height = `${state.height}px`
    el.style.maxHeight = `${state.height}px`
    applyViewportState(state)
  }

  function pinPageShell(el: HTMLElement | null, forceFallback?: boolean) {
    const force =
      forceFallback === true ||
      shouldForceOverlayKeyboardFallback(composerFocused.value)
    const state = pinShellToVisualViewport(el, { forceKeyboardFallback: force })
    applyViewportState(state)
    return state
  }

  function onComposerFocus(el?: HTMLElement | null) {
    composerFocused.value = true
    // Immediate pin + overlay fallback (standalone PWA only) so Android WebAPK
    // does not wait for VV / VK geometry.
    pinPageShell(el ?? null, shouldForceOverlayKeyboardFallback(true))
  }

  function onComposerBlur(el?: HTMLElement | null) {
    // Delay slightly so focus moving between composer controls does not flash nav.
    window.setTimeout(() => {
      const active = document.activeElement
      if (active instanceof HTMLElement && active.closest?.('.composer, .fullscreen-input-overlay')) {
        return
      }
      if (active && active !== document.body && (el ? el.contains(active) : false)) {
        return
      }
      composerFocused.value = false
      pinPageShell(el ?? null, false)
    }, 80)
  }

  onMounted(() => {
    stopVisualViewport = subscribeVisualViewport(
      (state) => applyViewportState(state),
      {
        pinShell: false,
      },
    )
  })

  onBeforeUnmount(() => {
    stopVisualViewport?.()
    stopVisualViewport = null
  })

  return {
    keyboardOpen,
    composerFocused,
    inputChromeCollapsed,
    applyVisualViewportCssVars,
    onComposerFocus,
    onComposerBlur,
    applyViewportState,
    applyShellGeometry,
    pinPageShell,
  }
}
