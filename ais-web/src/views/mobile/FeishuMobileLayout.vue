<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, provide, ref, type ComputedRef, type Ref } from 'vue'
import { useRoute } from 'vue-router'
import { MagicStick, MoreFilled, Picture, Plus, ChatDotRound, User } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import {
  pinShellToVisualViewport,
  readVisualViewport,
  isPwaEntry,
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

/** Soft keyboard reported by visualViewport / PWA standalone fallback. */
const keyboardOpen = ref(false)
/** Composer textarea focused — hide bottom-nav immediately (before VV reports). */
const composerFocused = ref(false)
/**
 * Effective "input mode": hide bottom-nav and pin shell so the composer sits
 * flush above the keyboard (WeChat/Feishu chat-page pattern).
 */
const inputChromeCollapsed = computed(() => keyboardOpen.value || composerFocused.value)

/**
 * Focus-session latch: once an *unforced* VV sample reports keyboardOpen during
 * this focus, stop the artificial standalone overlay fallback. System-keyboard
 * dismiss often leaves the input focused; without this latch force keeps the
 * shell shrunk until an explicit blur.
 *
 * Sampling (trackUnforcedKeyboard) is separate from the force decision below.
 */
let sawRealKeyboardOpen = false
/**
 * Consecutive unforced keyboardOpen=false samples while sawRealKeyboardOpen.
 * Require CLOSE_CONFIRM_SAMPLES before restoring the shell so mid-animation
 * single-frame false positives do not unforce early.
 */
let unforcedCloseConfirmCount = 0
/** Consecutive closed samples required before dismiss-without-blur restore. */
const CLOSE_CONFIRM_SAMPLES = 2

let stopVisualViewport: (() => void) | null = null
let stopFocusKeyboardWatch: (() => void) | null = null
/** Pending setComposerBlur delay; cleared on unmount / re-focus. */
let composerBlurTimer: ReturnType<typeof setTimeout> | null = null

/** Hunt for a real VV open after focus (ms). Pure-overlay PWAs stop after this. */
const FOCUS_OPEN_PROBE_MS = 2500
/** Poll while focused until real open / dismiss / probe timeout. */
const FOCUS_KEYBOARD_POLL_MS = 120

const entryPrefix = computed(() => route.meta.mobileEntry ?? 'mobile')

/** Hide layout header on chat routes — FeishuChatPage renders its own header with back button. */
const showHeader = computed(() => route.name?.toString().endsWith('-chat') !== true)

const activeSessionTitle = computed(() => {
  const session = store.sessions.find((item) => item.id === store.activeSessionId)
  return session?.title || 'AI 创作'
})

const hideBottomNav = computed(
  () => route.meta?.hideBottomNav === true || inputChromeCollapsed.value,
)

/** Unforced VV read — force path always reports keyboardOpen=true. */
function sampleUnforcedViewport(): VisualViewportState | null {
  if (typeof window === 'undefined') return null
  return readVisualViewport(window, {
    forceKeyboardFallback: false,
    useScreenHeightBaseline: isPwaEntry(),
  })
}

/**
 * State detection only: update the real-open latch from an unforced sample.
 * Returns whether the unforced sample currently says the keyboard is open.
 * Also resets close-confirm streak when a real open is seen again.
 */
function trackUnforcedKeyboard(state?: VisualViewportState | null): boolean {
  if (!composerFocused.value) return false
  const sample = state ?? sampleUnforcedViewport()
  if (!sample) return false
  if (sample.keyboardOpen) {
    sawRealKeyboardOpen = true
    unforcedCloseConfirmCount = 0
    return true
  }
  return false
}

function clearComposerBlurTimer() {
  if (composerBlurTimer != null) {
    clearTimeout(composerBlurTimer)
    composerBlurTimer = null
  }
}

/**
 * Pure force policy (no sampling side effects):
 * The dedicated PWA entry path is authoritative. Other contexts retain the
 * standalone-display fallback so existing installed paths continue to work.
 */
function shouldForceKeyboardFallback(): boolean {
  if (!composerFocused.value) return false
  if (isPwaEntry()) return true
  if (sawRealKeyboardOpen) return false
  return shouldForceOverlayKeyboardFallback(true)
}

/**
 * Sample then decide force. Used by pin / subscribe / focus watch so a pure VV
 * resize can clear force after a real open without burying sampling in policy.
 */
function resolveForceKeyboardFallback(forceFallback?: boolean): boolean {
  if (composerFocused.value) trackUnforcedKeyboard()
  if (!composerFocused.value) return false
  if (isPwaEntry()) return true
  if (sawRealKeyboardOpen) return false
  if (forceFallback === true) return shouldForceOverlayKeyboardFallback(true)
  return shouldForceKeyboardFallback()
}

/**
 * After the shell is pinned, check if the composer element is visible and
 * fine-tune the shell height to avoid over-shrinking (pinyin keyboard) or
 * under-shrinking (handwriting keyboard). This handles keyboard types that
 * report no usable inset via VK API or VV.
 */
function adjustComposerVisibility() {
  if (!composerFocused.value || !pageRef.value || typeof window === 'undefined') return
  const composer = pageRef.value.querySelector('.composer') as HTMLElement | null
  if (!composer) return

  // Gap between composer bottom and shell bottom.
  // Positive = space remaining within the shell (below composer).
  // Negative = composer extends beyond shell bottom (behind keyboard overlay).
  //
  // KEY BUGFIX: previously used `window.visualViewport.height - composerRect.bottom`
  // as the gap reference. In PWA overlay mode the visualViewport does NOT shrink
  // when the keyboard opens, so the gap was always large (> 40), triggering the
  // "expand" branch and undoing the keyboard fallback shrink — causing ALL input
  // methods (pinyin, handwriting) to be obscured.
  //
  // The correct reference is the shell's own bottom edge. After pinShellToVisualViewport
  // with forceKeyboardFallback=true, the shell is already shrunk by the estimated
  // keyboard size. The gap then measures remaining headroom within that shrunk area.
  const gap = pageRef.value.getBoundingClientRect().bottom - composer.getBoundingClientRect().bottom

  if (gap < -5) {
    // Composer extends beyond shell bottom — shrink shell more.
    // This handles taller keyboards (handwriting ~50%) that exceed the
    // fallback ratio estimate (~35%).
    const additionalShrink = Math.abs(gap) + 12
    const newH = Math.max(60, pageRef.value.clientHeight - additionalShrink)
    pageRef.value.style.height = `${newH}px`
    pageRef.value.style.maxHeight = `${newH}px`
  } else if (gap > 40 && pageRef.value.style.height) {
    // Too much empty space below composer — expand shell slightly.
    // This handles shorter keyboards (pinyin ~30%) so there's less
    // dead space above the keyboard.
    const currentH = pageRef.value.clientHeight
    const expandBy = Math.min(gap - 20, 80)
    // Use visualViewport as the ceiling so shell never exceeds visible area.
    const visualBottom = window.visualViewport
      ? window.visualViewport.height
      : window.innerHeight
    const newH = Math.min(currentH + expandBy, visualBottom)
    pageRef.value.style.height = `${newH}px`
    pageRef.value.style.maxHeight = `${newH}px`
  }
}

function applyViewportState(state: VisualViewportState) {
  keyboardOpen.value = state.keyboardOpen
}

function pinPageShell(forceFallback?: boolean) {
  const force = resolveForceKeyboardFallback(forceFallback)
  const state = pinShellToVisualViewport(pageRef.value, { forceKeyboardFallback: force })
  applyViewportState(state)
  // Fine-tune shell height based on composer's actual position relative to VV.
  // This adapts to different keyboard types (pinyin ~35%, handwriting ~50%)
  // without a hard-coded fallback ratio.
  adjustComposerVisibility()
  // pinShell writes left via CSS vars only; keep explicit left for wide-screen
  // centered column so PC Feishu does not jump when keyboard geometry updates.
  if (pageRef.value && typeof window !== 'undefined' && window.matchMedia('(min-width: 769px)').matches) {
    pageRef.value.style.left = '50%'
    pageRef.value.style.right = 'auto'
    pageRef.value.style.width = 'min(100%, 860px)'
  } else if (pageRef.value) {
    pageRef.value.style.left = `${state.offsetLeft}px`
    pageRef.value.style.right = '0'
    pageRef.value.style.width = '100%'
  }
  // If a real open was first observed via subscribe / ais event after the open
  // probe timed out, restart sampling so a later dismiss-without-blur still
  // restores the shell (force is already off once the latch is set).
  if (
    composerFocused.value &&
    sawRealKeyboardOpen &&
    !stopFocusKeyboardWatch &&
    sampleUnforcedViewport()?.keyboardOpen
  ) {
    startFocusKeyboardWatch()
  }
  return state
}

/**
 * While composer is focused, poll *unforced* VV so that:
 *  1) a delayed real open clears the force latch and pins real geometry;
 *  2) keyboard dismiss without blur (and sometimes without VV events) restores
 *     the shell via a no-force pin once keyboardOpen=false is observed.
 *
 * Adaptive lifetime — not a permanent high-frequency timer:
 *  - no real open yet → stop after FOCUS_OPEN_PROBE_MS (overlay path keeps
 *    force via shouldForceKeyboardFallback until blur);
 *  - real open observed → keep polling until unforced close or blur, then stop.
 * Also listens to VV/window resize so event-driven dismiss reacts immediately.
 */
function clearFocusKeyboardWatch() {
  stopFocusKeyboardWatch?.()
  stopFocusKeyboardWatch = null
}

function startFocusKeyboardWatch() {
  clearFocusKeyboardWatch()
  if (typeof window === 'undefined') return

  const win = window
  const started =
    typeof performance !== 'undefined' ? performance.now() : Date.now()
  let stopped = false
  let intervalId: ReturnType<typeof setInterval> | undefined

  const now = () =>
    typeof performance !== 'undefined' ? performance.now() : Date.now()

  const cancel = () => {
    if (stopped) return
    stopped = true
    if (intervalId != null) clearInterval(intervalId)
    intervalId = undefined
    vv?.removeEventListener('resize', onViewportEvent)
    vv?.removeEventListener('scroll', onViewportEvent)
    win.removeEventListener('resize', onViewportEvent)
    if (stopFocusKeyboardWatch === cancel) stopFocusKeyboardWatch = null
  }

  const tick = () => {
    if (stopped || !composerFocused.value) {
      cancel()
      return
    }

    const unforced = sampleUnforcedViewport()
    if (!unforced) return

    const open = trackUnforcedKeyboard(unforced)

    if (open) {
      // Real keyboard geometry — pin without force (resolveForce also yields).
      // trackUnforcedKeyboard already cleared the close-confirm streak.
      pinPageShell(false)
      return
    }

    if (sawRealKeyboardOpen) {
      // Require consecutive closed unforced samples so open/close animation
      // frames do not restore the shell early. No fixed screen-size guessing:
      // pure-overlay dismiss that never emits VV stays latched until blur.
      unforcedCloseConfirmCount += 1
      if (unforcedCloseConfirmCount < CLOSE_CONFIRM_SAMPLES) {
        return
      }
      // Real keyboard was open and is now confirmed closed while focus is retained.
      // Explicit no-force pin restores shell height; do not wait for blur.
      pinPageShell(false)
      cancel()
      return
    }

    // Still waiting for a real open: keep standalone overlay force if applicable.
    pinPageShell(shouldForceKeyboardFallback())
    if (now() - started >= FOCUS_OPEN_PROBE_MS) {
      // Pure overlay keyboard: stop polling; force stays until blur / real open
      // observed via subscribeVisualViewport / ais:visual-viewport.
      cancel()
    }
  }

  const onViewportEvent = () => tick()
  const vv = win.visualViewport
  vv?.addEventListener('resize', onViewportEvent)
  vv?.addEventListener('scroll', onViewportEvent)
  win.addEventListener('resize', onViewportEvent)

  intervalId = setInterval(tick, FOCUS_KEYBOARD_POLL_MS)
  stopFocusKeyboardWatch = cancel
  tick()
}

function setComposerFocus() {
  clearComposerBlurTimer()
  composerFocused.value = true
  sawRealKeyboardOpen = false
  unforcedCloseConfirmCount = 0
  // Immediate pin + overlay fallback (standalone PWA only) so Android WebAPK
  // does not wait for VV / VK geometry.
  pinPageShell(shouldForceKeyboardFallback())
  // Short unforced sampling covers delayed open + dismiss-without-blur.
  startFocusKeyboardWatch()
}

function setComposerBlur() {
  // Delay slightly so focus moving between composer controls does not flash nav.
  clearComposerBlurTimer()
  composerBlurTimer = window.setTimeout(() => {
    composerBlurTimer = null
    const active = document.activeElement
    if (active instanceof HTMLElement && active.closest?.('.composer, .fullscreen-input-overlay')) {
      return
    }
    composerFocused.value = false
    sawRealKeyboardOpen = false
    unforcedCloseConfirmCount = 0
    clearFocusKeyboardWatch()
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
    () => {
      // All visual-viewport events reconcile through the same shell writer as
      // focus polling and blur, so fallback/latch policy cannot race itself.
      pinPageShell()
    },
    {
      forceKeyboardFallback: () => resolveForceKeyboardFallback(),
    },
  )
  // Initial pin so the shell has concrete top/height before first paint settles.
  pinPageShell(false)
})

onBeforeUnmount(() => {
  clearComposerBlurTimer()
  clearFocusKeyboardWatch()
  stopVisualViewport?.()
  stopVisualViewport = null
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
   *
   * Always fill the viewport (including PC Feishu / wide screens) so the
   * embedded H5 never collapses to a blank zero-height page.
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
  min-width: 0;
  min-height: 0;
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

/* PC Feishu / wide embedded browsers: keep a readable column without blank sides. */
@media (min-width: 769px) {
  .feishu-layout {
    left: 50%;
    right: auto;
    width: min(100%, 860px);
    max-width: 860px;
    transform: translateX(-50%);
    border-left: 1px solid rgba(225, 230, 240, .85);
    border-right: 1px solid rgba(225, 230, 240, .85);
    box-shadow: 0 18px 60px rgba(41, 55, 94, .08);
  }
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
