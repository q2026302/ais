/**
 * Visual Viewport helpers for mobile soft-keyboard layout.
 *
 * On iOS Safari / installed PWAs, `100vh` / `100dvh` often track the layout
 * viewport and do not shrink when the keyboard opens. `window.visualViewport`
 * reflects the actually visible area; we mirror it into CSS custom properties
 * so full-height shells can pin to the keyboard-safe region.
 *
 * Android installed PWAs (esp. Xiaomi / WebAPK) frequently overlay the keyboard
 * without shrinking visualViewport or firing reliable resize events. For those
 * cases we combine:
 *  - min(visualViewport.height, innerHeight, clientHeight)
 *  - VirtualKeyboard API geometry when available
 *  - focus-driven fallback inset in standalone display mode
 */

export interface VisualViewportState {
  height: number
  width: number
  offsetTop: number
  offsetLeft: number
  scale: number
  /** Estimated soft-keyboard (or browser chrome) inset from the bottom of the layout viewport. */
  keyboardInset: number
  /** True when visual height is meaningfully smaller than the layout viewport, or a focused fallback is active. */
  keyboardOpen: boolean
}

export type VisualViewportListener = (state: VisualViewportState) => void

/** Keyboard detection threshold (px). Small toolbars / browser UI churn is ignored. */
export const KEYBOARD_OPEN_THRESHOLD_PX = 120

/** Fallback keyboard fraction of the layout height when VV does not shrink (Android PWA overlay). */
export const STANDALONE_KEYBOARD_FALLBACK_RATIO = 0.42

/** Cap for the standalone overlay fallback so tablets / landscape stay usable. */
export const STANDALONE_KEYBOARD_FALLBACK_MAX_PX = 360

export interface VisualViewportEnv {
  visualViewport?: {
    height: number
    width: number
    offsetTop: number
    offsetLeft: number
    scale: number
  } | null
  innerHeight: number
  innerWidth: number
  clientHeight?: number
  /** Optional inset from VirtualKeyboard API (px). */
  virtualKeyboardInset?: number
  /**
   * When true and measured inset is still tiny, apply a standalone-PWA fallback
   * height reduction so bottom composers are not covered by overlay keyboards.
   */
  forceKeyboardFallback?: boolean
}

/** Pure computation — safe to unit-test without a browser. */
export function computeVisualViewportState(env: VisualViewportEnv): VisualViewportState {
  const vv = env.visualViewport
  const layoutHeight = Math.max(
    env.innerHeight || 0,
    env.clientHeight || 0,
    vv?.height || 0,
  )

  let height: number
  let width: number
  let offsetTop: number
  let offsetLeft: number
  let scale: number

  if (vv) {
    // Prefer the smaller of visual / layout heights: resizes-content shrinks
    // innerHeight, while overlay keyboards may only shrink visualViewport (or neither).
    const candidates = [vv.height, env.innerHeight, env.clientHeight ?? 0].filter(
      (n) => typeof n === 'number' && n > 0,
    )
    height = candidates.length ? Math.min(...candidates) : Math.max(0, vv.height)
    width = Math.max(0, Math.min(vv.width || env.innerWidth, env.innerWidth || vv.width || 0) || vv.width)
    offsetTop = Math.max(0, vv.offsetTop)
    offsetLeft = Math.max(0, vv.offsetLeft)
    scale = vv.scale || 1
  } else {
    height = Math.max(0, env.clientHeight || env.innerHeight)
    width = Math.max(0, env.innerWidth)
    offsetTop = 0
    offsetLeft = 0
    scale = 1
  }

  // Keyboard inset relative to the layout viewport bottom.
  let keyboardInset = Math.max(0, layoutHeight - height - offsetTop)
  const vkInset = Math.max(0, env.virtualKeyboardInset ?? 0)
  if (vkInset > keyboardInset) {
    keyboardInset = vkInset
    height = Math.max(0, layoutHeight - vkInset)
    offsetTop = 0
  }

  let keyboardOpen = keyboardInset > KEYBOARD_OPEN_THRESHOLD_PX

  // Android standalone PWA: keyboard often overlays without reporting inset.
  if (env.forceKeyboardFallback && keyboardInset < KEYBOARD_OPEN_THRESHOLD_PX) {
    const fallback = Math.min(
      STANDALONE_KEYBOARD_FALLBACK_MAX_PX,
      Math.round(layoutHeight * STANDALONE_KEYBOARD_FALLBACK_RATIO),
    )
    if (fallback > keyboardInset) {
      keyboardInset = fallback
      height = Math.max(0, layoutHeight - fallback)
      offsetTop = 0
      keyboardOpen = true
    }
  }

  return {
    height: Math.max(0, height),
    width: Math.max(0, width),
    offsetTop: Math.max(0, offsetTop),
    offsetLeft: Math.max(0, offsetLeft),
    scale,
    keyboardInset,
    keyboardOpen,
  }
}

export function isStandaloneDisplayMode(
  win: Window & typeof globalThis = window,
): boolean {
  if (typeof win === 'undefined') return false
  try {
    if (win.matchMedia?.('(display-mode: standalone)').matches) return true
    if (win.matchMedia?.('(display-mode: fullscreen)').matches) return true
    if (win.matchMedia?.('(display-mode: minimal-ui)').matches) return true
  } catch {
    /* ignore */
  }
  const nav = win.navigator as Navigator & { standalone?: boolean }
  return Boolean(nav?.standalone)
}

function readVirtualKeyboardInset(win: Window & typeof globalThis): number {
  const vk = (win.navigator as Navigator & {
    virtualKeyboard?: { boundingRect?: DOMRectReadOnly }
  }).virtualKeyboard
  const rect = vk?.boundingRect
  if (!rect) return 0
  return Math.max(0, rect.height || 0)
}

export function readVisualViewport(
  win: Window & typeof globalThis = window,
  options?: { forceKeyboardFallback?: boolean },
): VisualViewportState {
  if (typeof win === 'undefined') {
    return {
      height: 0,
      width: 0,
      offsetTop: 0,
      offsetLeft: 0,
      scale: 1,
      keyboardInset: 0,
      keyboardOpen: false,
    }
  }

  const docEl = win.document?.documentElement

  return computeVisualViewportState({
    visualViewport: win.visualViewport
      ? {
          height: win.visualViewport.height,
          width: win.visualViewport.width,
          offsetTop: win.visualViewport.offsetTop,
          offsetLeft: win.visualViewport.offsetLeft,
          scale: win.visualViewport.scale,
        }
      : null,
    innerHeight: win.innerHeight,
    innerWidth: win.innerWidth,
    clientHeight: docEl?.clientHeight,
    virtualKeyboardInset: readVirtualKeyboardInset(win),
    forceKeyboardFallback: options?.forceKeyboardFallback === true,
  })
}

/** Write layout tokens used by mobile shells (height / offset / keyboard flag). */
export function applyVisualViewportCssVars(
  target: HTMLElement,
  state: VisualViewportState = readVisualViewport(),
): void {
  const style = target.style
  style.setProperty('--vv-height', `${state.height}px`)
  style.setProperty('--vv-width', `${state.width}px`)
  style.setProperty('--vv-offset-top', `${state.offsetTop}px`)
  style.setProperty('--vv-offset-left', `${state.offsetLeft}px`)
  style.setProperty('--vv-keyboard-inset', `${state.keyboardInset}px`)
  style.setProperty('--vv-keyboard-open', state.keyboardOpen ? '1' : '0')
  target.dataset.keyboardOpen = state.keyboardOpen ? 'true' : 'false'
}

/**
 * Pin a fixed full-screen shell (chat page) to the visible viewport.
 * Writes both CSS vars and explicit top/height so Android WebAPK cannot ignore vars.
 */
export function pinShellToVisualViewport(
  target: HTMLElement | null | undefined,
  options?: { forceKeyboardFallback?: boolean; win?: Window & typeof globalThis },
): VisualViewportState {
  const win = options?.win ?? (typeof window !== 'undefined' ? window : undefined)
  if (!win) {
    return {
      height: 0,
      width: 0,
      offsetTop: 0,
      offsetLeft: 0,
      scale: 1,
      keyboardInset: 0,
      keyboardOpen: false,
    }
  }
  const state = readVisualViewport(win, {
    forceKeyboardFallback: options?.forceKeyboardFallback,
  })
  if (target) {
    applyVisualViewportCssVars(target, state)
    // Explicit geometry — more reliable than CSS vars alone on some Android PWAs.
    target.style.top = `${state.offsetTop}px`
    target.style.height = `${state.height}px`
    target.style.maxHeight = `${state.height}px`
  }
  return state
}

/**
 * Subscribe to visual-viewport changes. Uses `visualViewport` when present;
 * otherwise falls back to `window` resize / orientationchange.
 * Always returns a cleanup that removes every listener it registered.
 */
export function subscribeVisualViewport(
  listener: VisualViewportListener,
  options?: {
    /** Element (or lazy getter) that receives `--vv-*` CSS variables. */
    cssTarget?: HTMLElement | null | (() => HTMLElement | null | undefined)
    win?: Window & typeof globalThis
    /**
     * When true (or a getter returns true), apply standalone overlay keyboard
     * fallback if the browser does not report an inset.
     */
    forceKeyboardFallback?: boolean | (() => boolean)
    /** Also pin explicit top/height on the cssTarget (chat shells). */
    pinShell?: boolean
  },
): () => void {
  const win = options?.win ?? (typeof window !== 'undefined' ? window : undefined)
  if (!win) return () => {}

  const resolveTarget = () => {
    const raw = options?.cssTarget
    if (typeof raw === 'function') return raw() ?? null
    return raw ?? null
  }

  const resolveFallback = () => {
    const raw = options?.forceKeyboardFallback
    if (typeof raw === 'function') return raw() === true
    return raw === true
  }

  const notify = () => {
    const forceFallback = resolveFallback()
    const state = readVisualViewport(win, { forceKeyboardFallback: forceFallback })
    const target = resolveTarget()
    if (target) {
      if (options?.pinShell) {
        pinShellToVisualViewport(target, {
          forceKeyboardFallback: forceFallback,
          win,
        })
      } else {
        applyVisualViewportCssVars(target, state)
      }
    }
    listener(state)
  }

  notify()

  const vv = win.visualViewport
  const cleanups: Array<() => void> = []

  if (vv) {
    vv.addEventListener('resize', notify)
    vv.addEventListener('scroll', notify)
    cleanups.push(() => {
      vv.removeEventListener('resize', notify)
      vv.removeEventListener('scroll', notify)
    })
  }

  win.addEventListener('orientationchange', notify)
  // Some Android keyboards only fire window resize.
  win.addEventListener('resize', notify)
  cleanups.push(() => {
    win.removeEventListener('orientationchange', notify)
    win.removeEventListener('resize', notify)
  })

  // VirtualKeyboard API (Chromium) — geometry of the software keyboard.
  const nav = win.navigator as Navigator & {
    virtualKeyboard?: {
      overlaysContent?: boolean
      addEventListener?: (type: string, listener: () => void) => void
      removeEventListener?: (type: string, listener: () => void) => void
    }
  }
  const vk = nav.virtualKeyboard
  if (vk) {
    try {
      // Let the page own layout instead of the browser pushing content.
      vk.overlaysContent = true
    } catch {
      /* ignore */
    }
    if (typeof vk.addEventListener === 'function') {
      vk.addEventListener('geometrychange', notify)
      cleanups.push(() => vk.removeEventListener?.('geometrychange', notify))
    }
  }

  return () => {
    for (const dispose of cleanups) dispose()
  }
}

/**
 * After an editable field focuses, poll the viewport for a short window so
 * delayed Android keyboard animations still update CSS vars / shell size.
 * Returns a cancel function.
 */
export function watchViewportWhileFocused(
  onTick: (state: VisualViewportState) => void,
  options?: {
    win?: Window & typeof globalThis
    forceKeyboardFallback?: boolean | (() => boolean)
    /** Total watch duration (ms). Default 1200. */
    durationMs?: number
    /** Poll interval (ms). Default 100. */
    intervalMs?: number
  },
): () => void {
  const win = options?.win ?? (typeof window !== 'undefined' ? window : undefined)
  if (!win) return () => {}

  const durationMs = options?.durationMs ?? 1200
  const intervalMs = options?.intervalMs ?? 100
  const started = typeof performance !== 'undefined' ? performance.now() : Date.now()
  let stopped = false
  let rafId = 0
  let intervalId: ReturnType<typeof setInterval> | undefined

  const resolveFallback = () => {
    const raw = options?.forceKeyboardFallback
    if (typeof raw === 'function') return raw() === true
    return raw === true
  }

  const tick = () => {
    if (stopped) return
    onTick(readVisualViewport(win, { forceKeyboardFallback: resolveFallback() }))
  }

  tick()

  const vv = win.visualViewport
  const onViewport = () => tick()
  vv?.addEventListener('resize', onViewport)
  vv?.addEventListener('scroll', onViewport)
  win.addEventListener('resize', onViewport)

  intervalId = setInterval(() => {
    if (stopped) return
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now()
    if (now - started >= durationMs) {
      cancel()
      return
    }
    tick()
  }, intervalMs)

  // One extra rAF pair for the first frame after keyboard chrome settles.
  if (typeof requestAnimationFrame === 'function') {
    rafId = requestAnimationFrame(() => {
      requestAnimationFrame(tick)
    })
  }

  function cancel() {
    if (stopped) return
    stopped = true
    if (intervalId != null) clearInterval(intervalId)
    if (rafId) cancelAnimationFrame(rafId)
    vv?.removeEventListener('resize', onViewport)
    vv?.removeEventListener('scroll', onViewport)
    win?.removeEventListener('resize', onViewport)
  }

  return cancel
}

/**
 * Scroll a focused control into the visual viewport (login / form pages).
 * No-ops when VisualViewport is missing; uses layout window as fallback.
 */
export function scrollElementIntoVisualViewport(
  element: Element | null | undefined,
  options?: ScrollIntoViewOptions,
): void {
  if (!element || typeof element.scrollIntoView !== 'function') return
  const run = () => {
    element.scrollIntoView({
      block: 'center',
      inline: 'nearest',
      behavior: 'smooth',
      ...options,
    })
  }
  // Wait one frame so the keyboard / visualViewport can settle.
  if (typeof requestAnimationFrame === 'function') {
    requestAnimationFrame(() => {
      requestAnimationFrame(run)
    })
  } else {
    run()
  }
}
