import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Favorite, Message } from '@/types'
import { favoriteApi } from '@/api/favorites'

/** Minimal shape needed to cancel one saved work (record-level). */
export type FavoriteRef = Pick<Favorite, 'id' | 'messageId'> & {
  userId?: number | null
  /**
   * Explicit own-record hint from the caller. Chat toggles always act on the
   * current user's own record, so they pass `true`; the work library passes the
   * full `Favorite` and lets the store compare owners.
   */
  own?: boolean
}

interface SyncPageOptions {
  /** The fetched page(s) cover the whole server set, so an absent record is gone. */
  complete?: boolean
  /**
   * The fetched page provably changed only by deletion (no new records were
   * inserted), so records that were on it and are now absent were removed.
   */
  clearAbsentOwn?: boolean
  /** Own records from the list that this fetch replaces (used to detect removals). */
  previousOwn?: Favorite[]
  /** Wall-clock time the request started; local mutations newer than it win. */
  fetchedAt?: number
}

/**
 * Work library (作品库 / 收藏) store.
 *
 * Cross-session, user-level aggregation: the list is independent of the active
 * session, and saved works are snapshots that survive message/session deletion.
 *
 * Cancellation is always record-scoped: the backend locator is the favourite
 * record id, so an administrator managing the all-users list removes exactly the
 * row they clicked and never another user's save of the same message.
 *
 * Per-message state comes from the server (`message.favorited` / `message.favoriteId`)
 * merged with local maps written by every toggle. Local mutations win for a
 * short while so a toggle is reflected immediately, but every newer server
 * snapshot (conversation refresh or work-library page) reconciles the maps, so a
 * stale local value can never permanently suppress the server state.
 */
export const useFavoriteStore = defineStore('favorite', () => {
  const favorites = ref<Favorite[]>([])
  const totalElements = ref(0)
  const page = ref(0)
  const size = ref(20)
  const loading = ref(false)
  const loaded = ref(false)
  /** messageId → locally known favourite state for the current user. */
  const overrides = ref<Map<number, boolean>>(new Map())
  /** messageId → the current user's own favourite record id (null = none). */
  const recordIds = ref<Map<number, number | null>>(new Map())
  /** Message ids with a favourite mutation in flight, to disable double taps. */
  const pendingMessageIds = ref<Set<number>>(new Set())
  /** Record ids with a cancellation in flight (record-level buttons). */
  const pendingRecordIds = ref<Set<number>>(new Set())
  /** Persisted current user id, used to tell own records from other users' in admin views. */
  const currentUserId = ref<number | null>(null)

  /** Monotonic id of the newest list request; stale responses are discarded. */
  let listRequestSeq = 0
  /**
   * messageId → timestamp of the last completed local mutation. A server
   * snapshot whose request started before that instant must not overwrite the
   * local value (it predates the mutation).
   */
  let mutationAt = new Map<number, number>()

  const hasMore = computed(() => favorites.value.length < totalElements.value)

  /** Called by the auth store when the signed-in identity is (re)resolved. */
  function setCurrentUser(userId: number | null) {
    currentUserId.value = userId ?? null
  }

  /** Local override wins; otherwise fall back to the server payload flag. */
  function isFavorited(messageId: number | null | undefined, serverFlag = false): boolean {
    if (messageId == null) return false
    const override = overrides.value.get(messageId)
    return override !== undefined ? override : serverFlag
  }

  /** The current user's own record id for a message (local knowledge wins). */
  function favoriteIdFor(messageId: number | null | undefined, serverFavoriteId?: number | null): number | null {
    if (messageId == null) return null
    if (recordIds.value.has(messageId)) return recordIds.value.get(messageId) ?? null
    return serverFavoriteId ?? null
  }

  function isPending(messageId: number | null | undefined): boolean {
    if (messageId == null) return false
    return pendingMessageIds.value.has(messageId)
  }

  function isRecordPending(favoriteId: number | null | undefined): boolean {
    if (favoriteId == null) return false
    return pendingRecordIds.value.has(favoriteId)
  }

  function setOverride(messageId: number, favorited: boolean) {
    const next = new Map(overrides.value)
    next.set(messageId, favorited)
    overrides.value = next
  }

  function setRecordId(messageId: number, favoriteId: number | null) {
    const next = new Map(recordIds.value)
    next.set(messageId, favoriteId)
    recordIds.value = next
  }

  function markPending(messageId: number, pending: boolean) {
    const next = new Set(pendingMessageIds.value)
    if (pending) next.add(messageId)
    else next.delete(messageId)
    pendingMessageIds.value = next
  }

  function markRecordPending(favoriteId: number, pending: boolean) {
    const next = new Set(pendingRecordIds.value)
    if (pending) next.add(favoriteId)
    else next.delete(favoriteId)
    pendingRecordIds.value = next
  }

  function markMutation(messageId: number) {
    mutationAt.set(messageId, Date.now())
  }

  /** A server snapshot may update this message only if no newer local mutation exists. */
  function canReconcile(messageId: number, fetchedAt: number): boolean {
    if (pendingMessageIds.value.has(messageId)) return false
    const mutated = mutationAt.get(messageId)
    return mutated === undefined || mutated < fetchedAt
  }

  /** Drop one work from the in-memory list without re-fetching (by record id). */
  function dropFromList(favoriteId: number) {
    const before = favorites.value.length
    favorites.value = favorites.value.filter((favorite) => favorite.id !== favoriteId)
    if (favorites.value.length !== before) {
      totalElements.value = Math.max(0, totalElements.value - 1)
    }
  }

  /** Records in a page that belong to the signed-in user. */
  function ownRecords(records: Favorite[]): Favorite[] {
    if (currentUserId.value == null) return []
    return records.filter((favorite) => favorite.userId != null && favorite.userId === currentUserId.value)
  }

  /**
   * Is this the current user's own record? Chat toggles say so explicitly. When
   * the identity could not be resolved, a record must never be misclassified as
   * someone else's, so the fallback is "own" unless the caller proves otherwise.
   */
  function isOwnRecordRef(favorite: FavoriteRef): boolean {
    const ownId = recordIds.value.get(favorite.messageId)
    if (ownId != null && ownId === favorite.id) return true
    if (favorite.own === false) return false
    if (currentUserId.value != null && favorite.userId != null) {
      return favorite.userId === currentUserId.value
    }
    return favorite.own ?? true
  }

  /**
   * Re-sync per-message flags from a fresh list page. Only the current user's
   * own records may write the per-message state: an administrator's list also
   * contains other users' saves, and those must never mark the administrator's
   * chat as "已收藏". When the page provably covers the whole set (or changed
   * only by deletion), records that vanished clear the flag instead of leaving a
   * stale local value behind.
   */
  function syncOwnStateFromPage(records: Favorite[], options: SyncPageOptions = {}) {
    if (currentUserId.value == null) return
    const { complete = false, clearAbsentOwn = false, previousOwn = [], fetchedAt = Date.now() } = options
    const nextOverrides = new Map(overrides.value)
    const nextIds = new Map(recordIds.value)
    const own = ownRecords(records)
    for (const favorite of own) {
      if (!canReconcile(favorite.messageId, fetchedAt)) continue
      nextOverrides.set(favorite.messageId, true)
      nextIds.set(favorite.messageId, favorite.id)
    }
    if (complete) {
      // The response covers the whole server set: any locally "已收藏" message
      // whose own record is absent was removed elsewhere.
      const presentOwnMessageIds = new Set(own.map((favorite) => favorite.messageId))
      for (const [messageId, favorited] of overrides.value) {
        if (!favorited || presentOwnMessageIds.has(messageId)) continue
        if (!canReconcile(messageId, fetchedAt)) continue
        nextOverrides.set(messageId, false)
        nextIds.set(messageId, null)
      }
    } else if (clearAbsentOwn) {
      // The page changed only by deletion: records that were on it and are now
      // gone are removed (an insertion at the top would have moved others down
      // the page instead, so the caller disabled this clear).
      const presentIds = new Set(records.map((favorite) => favorite.id))
      for (const favorite of previousOwn) {
        if (presentIds.has(favorite.id)) continue
        if (!canReconcile(favorite.messageId, fetchedAt)) continue
        nextOverrides.set(favorite.messageId, false)
        nextIds.set(favorite.messageId, null)
      }
    }
    overrides.value = nextOverrides
    recordIds.value = nextIds
  }

  /**
   * Reconcile per-message flags from a conversation payload. This is what makes
   * a conversation refresh honour the server's current-user state — including a
   * cancellation performed on another device. Local mutations completed after
   * the request started are preserved.
   */
  function reconcileFromMessages(messages: Message[], fetchedAt = Date.now()) {
    let nextOverrides: Map<number, boolean> | null = null
    let nextIds: Map<number, number | null> | null = null
    for (const message of messages) {
      const messageId = message?.id
      if (typeof messageId !== 'number' || messageId <= 0) continue
      if (typeof message.favorited !== 'boolean') continue
      if (!canReconcile(messageId, fetchedAt)) continue
      const favoriteId = message.favoriteId ?? null
      if (overrides.value.get(messageId) !== message.favorited) {
        nextOverrides = nextOverrides ?? new Map(overrides.value)
        nextOverrides.set(messageId, message.favorited)
      }
      if ((recordIds.value.get(messageId) ?? null) !== favoriteId) {
        nextIds = nextIds ?? new Map(recordIds.value)
        nextIds.set(messageId, favoriteId)
      }
    }
    if (nextOverrides) overrides.value = nextOverrides
    if (nextIds) recordIds.value = nextIds
  }

  /**
   * Fetch a page of the work library (defaults to the first page). Later calls
   * supersede earlier in-flight ones, so an out-of-order response can never
   * overwrite fresher data.
   */
  async function fetchFavorites(targetPage = 0, targetSize = size.value) {
    const seq = ++listRequestSeq
    const fetchedAt = Date.now()
    const previousOwn = ownRecords(favorites.value)
    const previousKnownIds = new Set(favorites.value.map((favorite) => favorite.id))
    loading.value = true
    try {
      const response = await favoriteApi.list(targetPage, targetSize)
      if (seq !== listRequestSeq) return
      // A page-0 response changed only by deletion when it introduces no record
      // we have never seen: insertions at the top would push records down a page.
      const hasNewRecords = response.content.some((favorite) => !previousKnownIds.has(favorite.id))
      favorites.value = response.content
      totalElements.value = response.totalElements
      page.value = response.number
      size.value = response.size
      if (targetPage === 0) {
        syncOwnStateFromPage(response.content, {
          complete: response.content.length >= response.totalElements,
          clearAbsentOwn: !hasNewRecords,
          previousOwn,
          fetchedAt,
        })
      }
    } finally {
      if (seq === listRequestSeq) loading.value = false
      loaded.value = true
    }
  }

  /** Load the next page and append it (infinite scroll / "load more"). */
  async function loadMore() {
    if (loading.value || !hasMore.value) return
    const seq = ++listRequestSeq
    const fetchedAt = Date.now()
    loading.value = true
    try {
      const response = await favoriteApi.list(page.value + 1, size.value)
      if (seq !== listRequestSeq) return
      const known = new Set(favorites.value.map((favorite) => favorite.id))
      const additions = response.content.filter((favorite) => !known.has(favorite.id))
      if (additions.length) {
        favorites.value = [...favorites.value, ...additions]
      }
      totalElements.value = response.totalElements
      page.value = response.number
      syncOwnStateFromPage(response.content, { fetchedAt })
    } finally {
      if (seq === listRequestSeq) loading.value = false
    }
  }

  /**
   * Re-fetch every page already loaded and rebuild the list in place, keeping
   * the number of loaded works unchanged. This is how an expired image signature
   * is recovered without truncating a multi-page library down to its last page.
   */
  async function refreshAll() {
    const lastPage = page.value
    const targetSize = size.value
    const seq = ++listRequestSeq
    const fetchedAt = Date.now()
    loading.value = true
    try {
      const responses = await Promise.all(
        Array.from({ length: lastPage + 1 }, (_, index) => favoriteApi.list(index, targetSize)),
      )
      if (seq !== listRequestSeq) return
      const seen = new Set<number>()
      const merged: Favorite[] = []
      for (const response of responses) {
        for (const favorite of response.content) {
          if (seen.has(favorite.id)) continue
          seen.add(favorite.id)
          merged.push(favorite)
        }
      }
      const first = responses[0]
      favorites.value = merged
      if (first) {
        totalElements.value = first.totalElements
        page.value = first.number
        size.value = first.size
      }
      syncOwnStateFromPage(merged, { fetchedAt })
    } finally {
      if (seq === listRequestSeq) loading.value = false
      loaded.value = true
    }
  }

  /**
   * After a removal, pull one work forward so the current page stays full
   * instead of leaving a hole that only a full reload repairs. Best-effort:
   * failures leave the list self-consistent (just one item shorter).
   */
  async function backfillAfterRemoval() {
    if (!loaded.value) return
    if (favorites.value.length >= totalElements.value) return
    const seq = listRequestSeq
    const targetPage = Math.floor(favorites.value.length / Math.max(1, size.value))
    try {
      const response = await favoriteApi.list(targetPage, size.value)
      if (seq !== listRequestSeq) return
      const known = new Set(favorites.value.map((favorite) => favorite.id))
      const additions = response.content.filter((favorite) => !known.has(favorite.id))
      if (additions.length) {
        favorites.value = [...favorites.value, ...additions]
      }
      totalElements.value = response.totalElements
      page.value = Math.max(page.value, response.number)
    } catch {
      // Best-effort backfill; ignore network errors.
    }
  }

  /** Save a message's generated image. Returns the created (or existing) work. */
  async function addFavorite(messageId: number): Promise<Favorite> {
    markPending(messageId, true)
    try {
      const favorite = await favoriteApi.add(messageId)
      // Only mutate the list once it reflects a real server page; before that,
      // the next work-library load will populate it (the star uses the override).
      if (loaded.value && !favorites.value.some((item) => item.id === favorite.id)) {
        favorites.value = [favorite, ...favorites.value]
        totalElements.value += 1
      }
      setOverride(messageId, true)
      setRecordId(messageId, favorite.id)
      markMutation(messageId)
      return favorite
    } finally {
      markPending(messageId, false)
    }
  }

  /**
   * Cancel one saved work by its own record id. The per-message flag is only
   * updated when the removed record is the current user's own, so an
   * administrator cancelling another user's record leaves their own chat state
   * untouched (including its loading indicator).
   */
  async function removeFavorite(favorite: FavoriteRef): Promise<void> {
    const { id, messageId } = favorite
    const isOwnRecord = isOwnRecordRef(favorite)
    if (isOwnRecord) markPending(messageId, true)
    markRecordPending(id, true)
    try {
      await favoriteApi.remove(id)
      dropFromList(id)
      if (isOwnRecord) {
        setOverride(messageId, false)
        setRecordId(messageId, null)
        markMutation(messageId)
      }
      // Fire-and-forget so the button's loading state clears immediately.
      void backfillAfterRemoval()
    } finally {
      if (isOwnRecord) markPending(messageId, false)
      markRecordPending(id, false)
    }
  }

  /** Toggle the current user's favourite state; returns the resulting state. */
  async function toggleFavorite(
    messageId: number,
    serverFlag = false,
    serverFavoriteId: number | null = null,
  ): Promise<boolean> {
    if (isFavorited(messageId, serverFlag)) {
      const favoriteId = favoriteIdFor(messageId, serverFavoriteId)
      if (favoriteId == null) {
        throw new Error('收藏记录已失效，请刷新后重试')
      }
      await removeFavorite({ id: favoriteId, messageId, userId: currentUserId.value, own: true })
      return false
    }
    await addFavorite(messageId)
    return true
  }

  /** Clear in-memory state (logout / account switch). */
  function reset() {
    // Invalidate any in-flight request so it cannot repopulate the new account.
    listRequestSeq += 1
    favorites.value = []
    totalElements.value = 0
    page.value = 0
    loading.value = false
    loaded.value = false
    overrides.value = new Map()
    recordIds.value = new Map()
    pendingMessageIds.value = new Set()
    pendingRecordIds.value = new Set()
    mutationAt = new Map()
    currentUserId.value = null
  }

  return {
    favorites,
    totalElements,
    page,
    size,
    loading,
    loaded,
    hasMore,
    overrides,
    recordIds,
    pendingMessageIds,
    pendingRecordIds,
    currentUserId,
    isFavorited,
    favoriteIdFor,
    isPending,
    isRecordPending,
    setCurrentUser,
    fetchFavorites,
    loadMore,
    refreshAll,
    addFavorite,
    removeFavorite,
    toggleFavorite,
    reconcileFromMessages,
    dropFromList,
    reset,
  }
})
