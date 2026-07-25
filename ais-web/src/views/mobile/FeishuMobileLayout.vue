<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref, type ComputedRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { MagicStick, MoreFilled, Picture, Plus, ChatDotRound, User } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import {
  applyVisualViewportCssVars,
  pinShellToVisualViewport,
  shouldForceOverlayKeyboardFallback,
  subscribeVisualViewport,
  type VisualViewportState,
} from '@/utils/visualViewport'

defineOptions({
  name: 'FeishuMobileLayout',
})

/** Shared keyboard / composer state provided to nested pages (e.g. FeishuChatPage). */
export interface MobileKeyboardApi {
  keyboardOpen: Ref<boolean>
  composerFocused: Ref<boolean>
  inputChromeCollapsed: ComputedRef<boolean>
  setComposerFocus: () => void
  setComposerBlur: () => void
}

const store = useSessionStore()
const route = useRoute()
const pageRef = ref<HTMLElement | null>(null)

/** Soft keyboard reported by visualViewport / VirtualKeyboard / standalone fallback. */
const keyboardOpen = ref(false)
/** Composer textarea focused — hide bottom-nav immediately (before VV reports). */
const composerFocused = ref(false)
/**
 * Effective "input mode": hide bottom-nav and pin shell so the composer sits
 * flush above the keyboard (WeChat/Feishu chat-page pattern).
 */
const inputChromeCollapsed = computed(() => keyboardOpen.value || composerFocused.value)

let stopVisualViewport: (() => void) | null = null
let onAisVisualViewport: ((event: Event) => void) | null = null

const entryPrefix = computed(() => (route.meta.mobileEntry === 'mobile' ? 'mobile' : 'feishu'))

/** Hide layout header on chat routes — FeishuChatPage renders its own header with back button. */
const showHeader = computed(() => route.name?.toString().endsWith('-chat') !== true)

const activeSessionTitle = computed(() => {
  const session = store.sessions.find((item) => item.id === store.activeSessionId)
  return session?.title || 'AI 创作'
})

const hideBottomNav = computed(
  () => route.meta?.hideBottomNav === true || inputChromeCollapsed.value,
)

/**
 * Soft keyboards that overlay content without shrinking visualViewport:
 *  - Android standalone / WebAPK (display-mode: standalone|fullscreen|minimal-ui)
 *  - Android browser / WebView with VirtualKeyboard API in overlay mode but
 *    zero geometry (vv/innerHeight stay full-screen; vk.boundingRect.height=0)
 *
 * Ordinary mobile browsers without VirtualKeyboard already resize correctly,
 * so we deliberately do NOT force the height fallback there.
 */
function shouldForceKeyboardFallback(): boolean {
  return shouldForceOverlayKeyboardFallback(composerFocused.value)
}

function applyViewportState(state: VisualViewportState) {
  keyboardOpen.value = state.keyboardOpen
}

function applyShellGeometry(state: VisualViewportState) {
  const el = pageRef.value
  if (!el) return
  applyVisualViewportCssVars(el, state)
  // Explicit geometry — more reliable than CSS vars alone on some Android PWAs.
  el.style.top = `${state.offsetTop}px`
  el.style.height = `${state.height}px`
  el.style.maxHeight = `${state.height}px`
  applyViewportState(state)
}

function pinPageShell(forceFallback?: boolean) {
  const force = forceFallback === true || shouldForceKeyboardFallback()
  const state = pinShellToVisualViewport(pageRef.value, { forceKeyboardFallback: force })
  applyViewportState(state)
  return state
}

function setComposerFocus() {
  composerFocused.value = true
  // Immediate pin + overlay fallback so Android does not wait for VV / VK geometry.
  pinPageShell(shouldForceKeyboardFallback())
  // Re-pin on the next frames so delayed keyboard animations still shrink the shell.
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(() => {
      if (composerFocused.value) pinPageShell(shouldForceKeyboardFallback())
      requestAnimationFrame(() => {
        if (composerFocused.value) pinPageShell(shouldForceKeyboardFallback())
      })
    })
  }
}

function setComposerBlur() {
  // Delay slightly so focus moving between composer controls does not flash nav.
  window.setTimeout(() => {
    const active = document.activeElement
    if (active instanceof HTMLElement && active.closest?.('.composer, .fullscreen-input-overlay')) {
      return
    }
    composerFocused.value = false
    pinPageShell(false)
  }, 80)
}

provide<MobileKeyboardApi>('mobileKeyboard', {
  keyboardOpen,
  composerFocused,
  inputChromeCollapsed,
  setComposerFocus,
  setComposerBlur,
})

function handleCreateSession() {
  void store.createSession()
}

onMounted(() => {
  if (typeof window === 'undefined') return
  stopVisualViewport = subscribeVisualViewport(
    (state) => {
      // subscribeVisualViewport already pins when pinShell:true; just sync flag.
      applyViewportState(state)
    },
    {
      cssTarget: () => pageRef.value,
      pinShell: true,
      forceKeyboardFallback: shouldForceKeyboardFallback,
    },
  )
  // main.ts focus watch measures with a keyboard-height fallback and dispatches
  // here. Prefer that detail for geometry so we do not re-read without fallback
  // before the composer focus handler has set composerFocused.
  onAisVisualViewport = (event: Event) => {
    const detail = (event as CustomEvent<VisualViewportState | undefined>).detail
    if (detail) {
      // Prefer the measured detail, but if the composer is focused and the
      // report still claims the keyboard is closed (overlay keyboards), re-pin
      // with the local fallback so we do not expand back over the input.
      if (composerFocused.value && !detail.keyboardOpen && shouldForceKeyboardFallback()) {
        pinPageShell(true)
      } else {
        applyShellGeometry(detail)
      }
    } else {
      pinPageShell(false)
    }
  }
  window.addEventListener('ais:visual-viewport', onAisVisualViewport)
  // Initial pin so the shell has concrete top/height before first paint settles.
  pinPageShell(false)
})

onBeforeUnmount(() => {
  stopVisualViewport?.()
  stopVisualViewport = null
  if (typeof window !== 'undefined' && onAisVisualViewport) {
    window.removeEventListener('ais:visual-viewport', onAisVisualViewport)
    onAisVisualViewport = null
  }
})
</script>

<template>
  <main
    ref="pageRef"
    class="feishu-layout"
    :class="{ 'keyboard-open': inputChromeCollapsed, 'chat-route': !showHeader }"
  >
    <header v-if="showHeader" class="mobile-header">
      <div class="brand-block">
        <span class="brand-icon"><MagicStick /></span>
        <div class="brand-copy">
          <strong>{{ activeSessionTitle }}</strong>
          <span>AI 创作助手</span>
        </div>
      </div>
      <div class="header-actions">
        <button
          class="header-icon-button"
          type="button"
          aria-label="新建会话"
          title="新建会话"
          @click="handleCreateSession"
        >
          <Plus />
        </button>
        <button
          class="more-button"
          type="button"
          aria-label="更多操作"
        >
          <MoreFilled />
        </button>
      </div>
    </header>

    <div class="layout-content">
      <router-view />
    </div>

    <footer v-if="!hideBottomNav" class="bottom-nav">
      <router-link
        :to="{ name: `${entryPrefix}-sessions` }"
        class="nav-tab"
        :class="{ active: route.name === `${entryPrefix}-sessions` }"
      >
        <ChatDotRound />
        <span>会话</span>
      </router-link>
      <router-link
        :to="{ name: `${entryPrefix}-gallery` }"
        class="nav-tab"
        :class="{ active: route.name === `${entryPrefix}-gallery` }"
      >
        <Picture />
        <span>作品</span>
      </router-link>
      <router-link
        :to="{ name: `${entryPrefix}-profile` }"
        class="nav-tab"
        :class="{ active: route.name === `${entryPrefix}-profile` }"
      >
        <User />
        <span>我的</span>
      </router-link>
    </footer>
  </main>
</template>

<style scoped>
.feishu-layout {
  --mobile-primary: #4f67e8;
  --mobile-primary-deep: #3d51c7;
  --mobile-text: #24314d;
  --mobile-muted: #7d899f;
  --mobile-border: #e5e9f2;

  /*
   * Fixed shell pinned to visualViewport via pinShellToVisualViewport().
   * Explicit top/height are also written inline so Android WebAPK cannot
   * ignore CSS custom properties when the soft keyboard opens.
   */
  position: fixed;
  top: var(--vv-offset-top, 0px);
  left: var(--vv-offset-left, 0px);
  right: 0;
  bottom: auto;
  z-index: 1;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  box-sizing: border-box;
  height: 100%;
  height: 100dvh;
  height: var(--vv-height, 100dvh);
  max-height: var(--vv-height, 100dvh);
  overflow: hidden;
  color: var(--mobile-text);
  background:
    radial-gradient(circle at 95% -5%, rgba(106, 90, 238, .12), transparent 24rem),
    linear-gradient(180deg, #f7f9fd 0%, #f2f5fa 100%);
}

/* Composer-focused / keyboard-open: drop any residual bottom chrome. */
.feishu-layout.keyboard-open {
  /* Ensure the flex column reflows when height is rewritten inline. */
  min-height: 0;
}

.mobile-header {
  position: relative;
  z-index: 10;
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: calc(64px + env(safe-area-inset-top));
  padding: calc(10px + env(safe-area-inset-top)) 14px 10px;
  border-bottom: 1px solid rgba(225, 230, 240, .9);
  background: rgba(255, 255, 255, .92);
  box-shadow: 0 3px 16px rgba(41, 55, 94, .04);
  backdrop-filter: blur(18px);
}

.brand-block {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 11px;
}

.brand-icon {
  display: grid;
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  place-items: center;
  color: #fff;
  font-size: 20px;
  border-radius: 13px;
  background: linear-gradient(145deg, #506cf1, #8b5be0);
  box-shadow: 0 8px 18px rgba(75, 91, 211, .24);
}

.brand-copy {
  display: flex;
  min-width: 0;
  flex-direction: column;
  line-height: 1.2;
}

.brand-copy strong {
  max-width: 52vw;
  overflow: hidden;
  color: #26334e;
  font-size: 16px;
  letter-spacing: -.2px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.brand-copy span {
  margin-top: 4px;
  color: #8c97aa;
  font-size: 11px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
}

.header-icon-button,
.more-button {
  display: grid;
  width: 40px;
  height: 40px;
  padding: 0;
  place-items: center;
  cursor: pointer;
  border: 0;
  border-radius: 13px;
  color: #5365cc;
  background: #eef1ff;
}

.header-icon-button:disabled {
  cursor: not-allowed;
  opacity: .5;
}

.layout-content {
  position: relative;
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
  min-width: 0;
  /* Let the flex item actually shrink when the shell height collapses. */
  flex-basis: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}

/* Chat page owns its internal scroll; keep content region non-scrolling so the
   nested chat shell can size to the remaining viewport above the keyboard. */
.feishu-layout.chat-route .layout-content {
  position: relative;
  overflow: hidden;
  /* Force a definite height for percentage/absolute children (chat-page).
     Using 100% of the pinned shell (not auto) so the nested chat shell
     receives a concrete containing block while the keyboard is open.
     Absolute-fill children track this box as the shell height is rewritten
     by pinShellToVisualViewport (PWA standalone overlay keyboards). */
  height: 100%;
  max-height: 100%;
  flex: 1 1 0;
  min-height: 0;
}

.bottom-nav {
  display: flex;
  flex: 0 0 auto;
  align-items: stretch;
  justify-content: space-around;
  padding: 6px 0 calc(6px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--mobile-border);
  background: rgba(255, 255, 255, .96);
  backdrop-filter: blur(18px);
}

.nav-tab {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 6px 0;
  cursor: pointer;
  text-decoration: none;
  color: var(--mobile-muted);
  font-size: 11px;
  border: 0;
  background: transparent;
  transition: color .15s;
}

.nav-tab svg {
  width: 22px;
  height: 22px;
}

.nav-tab.active {
  color: var(--mobile-primary);
  font-weight: 600;
}
</style>
