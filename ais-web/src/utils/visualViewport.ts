/**
 * Visual Viewport helpers for mobile soft-keyboard layout.
 *
 * On iOS Safari / installed PWAs, `100vh` / `100dvh` often track the layout
 * viewport and do not shrink when the keyboard opens. `window.visualViewport`
 * reflects the actually visible area; we mirror it into CSS custom properties
 * so full-height shells can pin to the keyboard-safe region.
 */

export interface VisualViewportState {
  height: number
  width: number
  offsetTop: number
  offsetLeft: number
  scale: number
  /** True when visual height is meaningfully smaller than the layout viewport. */
  keyboardOpen: boolean
}

export type VisualViewportListener = (state: VisualViewportState) => void

/** Keyboard detection threshold (px). Small toolbars / browser UI churn is ignored. */
export const KEYBOARD_OPEN_THRESHOLD_PX = 120

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
}

/** Pure computation — safe to unit-test without a browser. */
export function computeVisualViewportState(env: VisualViewportEnv): VisualViewportState {
  const vv = env.visualViewport
  if (vv) {
    const height = Math.max(0, vv.height)
    const width = Math.max(0, vv.width)
    const layoutHeight = Math.max(env.innerHeight, height)
    return {
      height,
      width,
      offsetTop: Math.max(0, vv.offsetTop),
      offsetLeft: Math.max(0, vv.offsetLeft),
      scale: vv.scale || 1,
      keyboardOpen: layoutHeight - height > KEYBOARD_OPEN_THRESHOLD_PX,
    }
  }

  return {
    height: Math.max(0, env.innerHeight),
    width: Math.max(0, env.innerWidth),
    offsetTop: 0,
    offsetLeft: 0,
    scale: 1,
    keyboardOpen: false,
  }
}

export function readVisualViewport(
  win: Window & typeof globalThis = window,
): VisualViewportState {
  if (typeof win === 'undefined') {
    return {
      height: 0,
      width: 0,
      offsetTop: 0,
      offsetLeft: 0,
      scale: 1,
      keyboardOpen: false,
    }
  }

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
  style.setProperty('--vv-keyboard-open', state.keyboardOpen ? '1' : '0')
  target.dataset.keyboardOpen = state.keyboardOpen ? 'true' : 'false'
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
  },
): () => void {
  const win = options?.win ?? (typeof window !== 'undefined' ? window : undefined)
  if (!win) return () => {}

  const resolveTarget = () => {
    const raw = options?.cssTarget
    if (typeof raw === 'function') return raw() ?? null
    return raw ?? null
  }

  const notify = () => {
    const state = readVisualViewport(win)
    const target = resolveTarget()
    if (target) applyVisualViewportCssVars(target, state)
    listener(state)
  }

  notify()

  const vv = win.visualViewport
  if (vv) {
    vv.addEventListener('resize', notify)
    vv.addEventListener('scroll', notify)
    win.addEventListener('orientationchange', notify)
    // Some Android keyboards only fire window resize.
    win.addEventListener('resize', notify)
    return () => {
      vv.removeEventListener('resize', notify)
      vv.removeEventListener('scroll', notify)
      win.removeEventListener('orientationchange', notify)
      win.removeEventListener('resize', notify)
    }
  }

  win.addEventListener('resize', notify)
  win.addEventListener('orientationchange', notify)
  return () => {
    win.removeEventListener('resize', notify)
    win.removeEventListener('orientationchange', notify)
  }
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
