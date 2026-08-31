import { useSessionStore } from '@/stores/session'
import { signedUrlExpired } from '@/utils/imageUrl'

/**
 * Expiry-recovery coordinator for signed image/attachment URLs.
 *
 * Signed URLs expire after 1h. A long-lived page that renders Pinia-cached
 * messages will eventually fail to load those images. On an `<img>` / `<el-image>`
 * `@error`, components call {@link recoverImage} with the failing URL:
 *
 *  - If the URL's signature is *not* expired (or absent), the failure is treated
 *    as a genuine 404/broken resource and nothing is done — this is what makes
 *    the recovery loop-free: a refresh only ever fires for an expired signature,
 *    and the refreshed response always carries a fresh (non-expired) signature.
 *  - If the signature *is* expired, the active session's messages are re-fetched
 *    once (`forceRefreshSession`), which re-signs every URL in the response.
 *
 * A short throttle plus an in-flight flag coalesce the burst of simultaneous
 * `@error` events (a whole gallery expiring at once) into a single refresh.
 */

const REFRESH_THROTTLE_MS = 3000

interface SessionRefreshState {
  inFlight: boolean
  lastRefreshAt: number
}

const sessionStates = new Map<number, SessionRefreshState>()

export function useSignedUrlRefresh() {
  const store = useSessionStore()

  function requestRefresh(sessionId: number | null | undefined): void {
    if (sessionId == null) return
    const now = Date.now()
    let state = sessionStates.get(sessionId)
    if (!state) {
      state = { inFlight: false, lastRefreshAt: 0 }
      sessionStates.set(sessionId, state)
    }
    if (state.inFlight) return
    if (now - state.lastRefreshAt < REFRESH_THROTTLE_MS) return
    state.inFlight = true
    state.lastRefreshAt = now
    store.forceRefreshSession(sessionId)
      .catch(() => { /* a failed background refresh must not surface or loop */ })
      .finally(() => {
        state!.inFlight = false
      })
  }

  /** Handle an image load failure, refreshing signed URLs only on expiry. */
  function recoverImage(failedUrl: string): void {
    if (!signedUrlExpired(failedUrl)) return
    requestRefresh(store.activeSessionId)
  }

  return { recoverImage, requestRefresh }
}
