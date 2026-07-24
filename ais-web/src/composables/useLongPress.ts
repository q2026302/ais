import { nextTick, onBeforeUnmount, ref } from 'vue'

/**
 * Long-press gesture helpers extracted from FeishuH5View.vue.
 *
 * Opens an action only after the finger lifts so the drawer title is not
 * selected under a still-down touch. Also suppresses residual text selection
 * that mobile WebViews leave after a long-press.
 */
export function useLongPress() {
  const longPressTriggered = ref(false)
  let longPressTimer: number | null = null
  let longPressStartX = 0
  let longPressStartY = 0
  let pendingLongPressAction: (() => void) | null = null
  let selectionGuardCleanup: (() => void) | null = null

  function clearResidualSelection() {
    // Long-press leaves sticky text selection on mobile WebViews; clear across layout frames.
    const clear = () => {
      const selection = window.getSelection()
      if (selection && selection.rangeCount > 0) selection.removeAllRanges()
      const active = document.activeElement
      if (active instanceof HTMLElement && active !== document.body && typeof active.blur === 'function') {
        // Avoid stealing focus from inputs the user is actively editing.
        if (active.tagName !== 'INPUT' && active.tagName !== 'TEXTAREA' && !active.isContentEditable) {
          active.blur()
        }
      }
    }
    clear()
    void nextTick(() => {
      clear()
      window.setTimeout(clear, 0)
      window.setTimeout(clear, 80)
      window.setTimeout(clear, 180)
    })
  }

  function setSelectionSuppressed(active: boolean) {
    if (typeof document === 'undefined') return
    if (active) {
      if (selectionGuardCleanup) return
      const root = document.documentElement
      root.classList.add('h5-suppress-selection')
      const kill = () => {
        const selection = window.getSelection()
        if (selection && selection.rangeCount > 0) selection.removeAllRanges()
      }
      kill()
      document.addEventListener('selectionchange', kill)
      selectionGuardCleanup = () => {
        document.removeEventListener('selectionchange', kill)
        root.classList.remove('h5-suppress-selection')
        selectionGuardCleanup = null
      }
    } else if (selectionGuardCleanup) {
      selectionGuardCleanup()
    }
  }

  function startLongPress(event: TouchEvent, action: () => void) {
    cancelLongPress(true)
    longPressTriggered.value = false
    pendingLongPressAction = null
    const touch = event.touches[0]
    longPressStartX = touch?.clientX ?? 0
    longPressStartY = touch?.clientY ?? 0
    // Open only after the finger lifts so the drawer title is not selected under the still-down touch.
    longPressTimer = window.setTimeout(() => {
      longPressTimer = null
      longPressTriggered.value = true
      pendingLongPressAction = action
      setSelectionSuppressed(true)
      clearResidualSelection()
      try {
        navigator.vibrate?.(12)
      } catch {
        // ignore
      }
    }, 480)
  }

  function moveLongPress(event: TouchEvent) {
    if (longPressTimer == null && !pendingLongPressAction) return
    const touch = event.touches[0]
    if (!touch) return
    const dx = touch.clientX - longPressStartX
    const dy = touch.clientY - longPressStartY
    if ((dx * dx) + (dy * dy) > 120) {
      cancelLongPress(true)
    }
  }

  function cancelLongPress(resetTriggered = false) {
    if (longPressTimer != null) {
      window.clearTimeout(longPressTimer)
      longPressTimer = null
    }
    pendingLongPressAction = null
    if (resetTriggered) {
      longPressTriggered.value = false
      setSelectionSuppressed(false)
    }
  }

  function finishLongPress() {
    if (longPressTimer != null) {
      window.clearTimeout(longPressTimer)
      longPressTimer = null
    }
    const action = pendingLongPressAction
    pendingLongPressAction = null
    if (!action) {
      setSelectionSuppressed(false)
      return
    }
    clearResidualSelection()
    action()
    clearResidualSelection()
    window.setTimeout(() => {
      clearResidualSelection()
      setSelectionSuppressed(false)
      longPressTriggered.value = false
    }, 360)
  }

  onBeforeUnmount(() => {
    cancelLongPress(true)
    setSelectionSuppressed(false)
  })

  return {
    longPressTriggered,
    startLongPress,
    moveLongPress,
    cancelLongPress,
    finishLongPress,
    clearResidualSelection,
    setSelectionSuppressed,
  }
}
