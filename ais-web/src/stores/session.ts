import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { Session, Message, ModelProvider, DrawRequest, UploadResponse, Attachment, MessageStatusResponse } from '@/types'
import { sessionApi } from '@/api/sessions'
import { providerApi } from '@/api/providers'
import { parseApiDate } from '@/utils/dateTime'

const PINNED_STORAGE_KEY = 'ais_pinned'
const UNREAD_STORAGE_KEY = 'ais_unread'
const LAST_VIEWED_STORAGE_KEY = 'ais_last_viewed'

function loadIdList(key: string): number[] {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return []
    const parsed = JSON.parse(raw) as unknown
    if (!Array.isArray(parsed)) return []
    return parsed
      .map((item) => Number(item))
      .filter((id) => Number.isFinite(id) && id > 0)
  } catch {
    return []
  }
}

function saveIdList(key: string, ids: number[]) {
  try {
    localStorage.setItem(key, JSON.stringify(ids))
  } catch {
    // ignore quota / private mode errors
  }
}

function loadLastViewedMap(): Record<string, number> {
  try {
    const raw = localStorage.getItem(LAST_VIEWED_STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw) as unknown
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {}
    const result: Record<string, number> = {}
    for (const [key, value] of Object.entries(parsed as Record<string, unknown>)) {
      const ts = Number(value)
      if (Number.isFinite(ts) && ts > 0) result[key] = ts
    }
    return result
  } catch {
    return {}
  }
}

function saveLastViewedMap(map: Record<string, number>) {
  try {
    localStorage.setItem(LAST_VIEWED_STORAGE_KEY, JSON.stringify(map))
  } catch {
    // ignore quota / private mode errors
  }
}

export const useSessionStore = defineStore('session', () => {
  const sessions = ref<Session[]>([])
  const activeSessionId = ref<number | null>(null)
  const selectionTargetId = ref<number | null>(null)
  const messages = ref<Message[]>([])
  // A session becomes active only after its messages load successfully. This
  // prevents a sessions refresh from acknowledging a merely requested session.
  let sessionSelectionGeneration = 0
  let sessionsFetchGeneration = 0
  const loading = ref(false)
  const activeRequestController = ref<AbortController | null>(null)
  const activeOperationType = ref<'CHAT' | 'DRAW' | 'REGENERATE' | null>(null)
  const activeOperationSessionId = ref<number | null>(null)
  const activeOperationMessageId = ref<number | null>(null)
  const operationStage = ref('')
  const operationStartedAt = ref<number | null>(null)
  const canCancel = computed(() => activeRequestController.value != null)

  const chatProviders = ref<ModelProvider[]>([])
  const imageProviders = ref<ModelProvider[]>([])
  const editingMessageId = ref<number | null>(null)

  // Local-only pin / unread state (persisted in localStorage)
  // pinnedSessions order: newest pin first (index 0)
  const pinnedSessions = ref<number[]>(loadIdList(PINNED_STORAGE_KEY))
  const unreadSessions = ref<number[]>(loadIdList(UNREAD_STORAGE_KEY))
  // sessionId -> last viewed timestamp (ms). Used for automatic unread dots.
  const lastViewedAt = ref<Record<string, number>>(loadLastViewedMap())

  // Polling state for draw generation
  const pollingIntervals = ref<Map<number, ReturnType<typeof setInterval>>>(new Map())
  const polledMessageStatuses = ref<Map<number, { status: string; stage: string }>>(new Map())
  const pollingRuns = new Map<number, { generation: number; inFlight: boolean }>()
  let pollingGeneration = 0

  function togglePin(sessionId: number) {
    const idx = pinnedSessions.value.indexOf(sessionId)
    if (idx >= 0) {
      pinnedSessions.value = pinnedSessions.value.filter((id) => id !== sessionId)
    } else {
      pinnedSessions.value = [sessionId, ...pinnedSessions.value]
    }
    saveIdList(PINNED_STORAGE_KEY, pinnedSessions.value)
  }

  function getLastViewed(sessionId: number): number | null {
    const ts = lastViewedAt.value[String(sessionId)]
    return typeof ts === 'number' && Number.isFinite(ts) && ts > 0 ? ts : null
  }

  /**
   * Per-message activity on the server timeline. Must match backend
   * SessionService.messageActivityAt / sessions-list lastMessageAt:
   * coalesce(updatedAt, createdAt). updatedAt advances on PENDING →
   * SUCCESS/FAILED so completion after the user leaves still counts as
   * newer activity; legacy rows without updatedAt fall back to createdAt.
   */
  function messageActivityTime(message: Pick<Message, 'createdAt' | 'updatedAt'>): number {
    return parseApiDate(message.updatedAt || message.createdAt)?.getTime() ?? 0
  }

  /**
   * Latest activity the user can already see for a session, on the server
   * timeline. Prefer session.lastMessageAt; when the session is open and
   * messages are loaded, also cover any in-view message activity the list
   * endpoint has not caught up with yet (same coalesce clock).
   */
  function visibleActivityTime(sessionId: number): number {
    const session = sessions.value.find((item) => item.id === sessionId)
    let latest = session ? sessionActivityTime(session) : 0
    if (isViewingSession(sessionId)) {
      for (const message of messages.value) {
        const ts = messageActivityTime(message)
        if (ts > latest) latest = ts
      }
    }
    return latest
  }

  /**
   * Old clients wrote Date.now() into lastViewedAt. Those client-wall-clock
   * values sit permanently above server activity timestamps, so
   * `activity > viewed` stays false forever (prev >= ts early-return also
   * blocks later server-timeline writes). Clamp polluted entries down to the
   * known server activity.
   *
   * Ceiling uses visibleActivityTime (not just list lastMessageAt): while a
   * session is open, recordLastViewed may advance viewed to a message
   * activity the list endpoint has not caught up with yet. Treating that
   * gap as pollution would clamp the watermark back and flash a false
   * red-dot after leaving. For inactive sessions visibleActivityTime falls
   * back to list activity, so obvious Date.now() pollution is still fixed.
   */
  function sanitizePollutedLastViewed(
    map: Record<string, number> = lastViewedAt.value,
  ): { map: Record<string, number>; changed: boolean } {
    if (sessions.value.length === 0) return { map, changed: false }

    let next = map
    let changed = false

    for (const session of sessions.value) {
      const key = String(session.id)
      const viewed = next[key]
      if (typeof viewed !== 'number' || !Number.isFinite(viewed) || viewed <= 0) continue
      // Include in-view message activity for the active session so a lagging
      // sessions-list lastMessageAt cannot classify a legitimate server-timeline
      // watermark as Date.now() pollution.
      const activity = visibleActivityTime(session.id)
      if (activity > 0 && viewed > activity) {
        if (next === map) next = { ...map }
        next[key] = activity
        changed = true
      }
    }

    return { map: next, changed }
  }

  function recordLastViewed(sessionId: number, at?: number) {
    // A loaded-but-no-longer-targeted session is no longer visible to the
    // user. Do not let its completion advance the viewed watermark while a
    // newer selection is still loading.
    if (!isViewingSession(sessionId)) return

    // First, repair any historical Date.now() pollution for this session so the
    // prev >= ts early-return cannot permanently pin an inflated watermark.
    const activity = visibleActivityTime(sessionId)
    const prevRaw = getLastViewed(sessionId)
    if (prevRaw != null && activity > 0 && prevRaw > activity) {
      lastViewedAt.value = { ...lastViewedAt.value, [String(sessionId)]: activity }
      saveLastViewedMap(lastViewedAt.value)
    }

    // Stay on the server-activity timeline. Never write Date.now() as a long-term
    // watermark — client wall clock mixes with server lastMessageAt and, under
    // even small clock skew, makes every later message look "already seen".
    let ts: number
    if (typeof at === 'number' && Number.isFinite(at) && at > 0) {
      ts = at
    } else if (activity > 0) {
      ts = activity
    } else {
      // Empty session / list not loaded: do not write a client stamp that would
      // suppress future server activity. Leave lastViewed unset until we have
      // a server timeline value.
      return
    }
    // Always cover activity already visible while the user is viewing.
    if (activity > ts) ts = activity
    // Explicit `at` must also stay on the server timeline when activity is known:
    // never let a caller push viewed past current server activity.
    if (activity > 0 && ts > activity) ts = activity

    const prev = getLastViewed(sessionId)
    if (prev != null && prev >= ts) return
    lastViewedAt.value = { ...lastViewedAt.value, [String(sessionId)]: ts }
    saveLastViewedMap(lastViewedAt.value)
  }

  function clearLastViewed(sessionId: number) {
    if (!(String(sessionId) in lastViewedAt.value)) return
    const next = { ...lastViewedAt.value }
    delete next[String(sessionId)]
    lastViewedAt.value = next
    saveLastViewedMap(lastViewedAt.value)
  }

  /**
   * Auto-unread activity watermark for a session.
   * Prefer lastMessageAt (message coalesce clock from the list endpoint). Do
   * NOT fold in session.updatedAt here — title renames bump session.updatedAt
   * without new message activity and would flash false red-dots.
   * Fall back to session.updatedAt only for empty / older payloads.
   */
  function sessionActivityTime(session: Session): number {
    const lastMessageMs = parseApiDate(session.lastMessageAt)?.getTime() ?? 0
    if (lastMessageMs > 0) return lastMessageMs
    return parseApiDate(session.updatedAt)?.getTime() ?? 0
  }

  /** List sort clock: message activity, or title-touch updatedAt when newer. */
  function sessionSortTime(session: Session): number {
    const lastMessageMs = parseApiDate(session.lastMessageAt)?.getTime() ?? 0
    const updatedMs = parseApiDate(session.updatedAt)?.getTime() ?? 0
    return Math.max(lastMessageMs, updatedMs)
  }

  function sessionHasMessages(session: Session): boolean {
    if (session.lastMessageAt) return true
    return Boolean((session.lastMessagePreview || '').trim())
  }

  function markAsUnread(sessionId: number) {
    if (unreadSessions.value.includes(sessionId)) return
    unreadSessions.value = [...unreadSessions.value, sessionId]
    saveIdList(UNREAD_STORAGE_KEY, unreadSessions.value)
  }

  function markAsRead(sessionId: number) {
    if (!unreadSessions.value.includes(sessionId)) return
    unreadSessions.value = unreadSessions.value.filter((id) => id !== sessionId)
    saveIdList(UNREAD_STORAGE_KEY, unreadSessions.value)
  }

  function isPinned(sessionId: number) {
    return pinnedSessions.value.includes(sessionId)
  }

  function isViewingSession(sessionId: number) {
    return activeSessionId.value === sessionId && selectionTargetId.value === sessionId
  }

  function isUnread(sessionId: number) {
    if (isViewingSession(sessionId)) return false
    if (unreadSessions.value.includes(sessionId)) return true

    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session || !sessionHasMessages(session)) return false

    const viewed = getLastViewed(sessionId)
    // Never opened in this client → no auto red-dot until the user has viewed it once
    // and a later message arrives. Manual mark-as-unread still works via unreadSessions.
    if (viewed == null) return false

    return sessionActivityTime(session) > viewed
  }

  /**
   * After sessions list refresh, promote sessions with newer activity than last-viewed
   * into the manual unread set so the red-dot persists even if timestamps are equalized later.
   *
   * For the session currently open, bump lastViewed up to the fresh activity so
   * leaving the chat does not flash a red-dot for messages the user already saw.
   *
   * Also clamps any lastViewedAt entries that sit above the server activity
   * timeline (legacy Date.now() pollution).
   */
  function syncAutoUnreadFromSessions() {
    let unreadChanged = false
    let viewedChanged = false
    const nextUnread = new Set(unreadSessions.value)
    const viewedSessionId = activeSessionId.value != null && selectionTargetId.value === activeSessionId.value
      ? activeSessionId.value
      : null

    // Repair polluted watermarks first so subsequent comparisons use server timeline.
    const sanitized = sanitizePollutedLastViewed(lastViewedAt.value)
    let nextViewed = sanitized.map
    if (sanitized.changed) viewedChanged = true

    for (const session of sessions.value) {
      const activity = sessionActivityTime(session)
      const key = String(session.id)
      if (viewedSessionId === session.id) {
        // User is looking at this session — whatever just arrived is already seen.
        if (nextUnread.delete(session.id)) unreadChanged = true
        if (activity > 0) {
          const viewed = typeof nextViewed[key] === 'number' ? nextViewed[key] : null
          if (viewed == null || activity > viewed) {
            if (nextViewed === lastViewedAt.value) nextViewed = { ...lastViewedAt.value }
            nextViewed[key] = activity
            viewedChanged = true
          }
        }
        continue
      }
      if (!sessionHasMessages(session)) continue
      const viewed = typeof nextViewed[key] === 'number' ? nextViewed[key] : null
      if (viewed == null) continue
      if (activity > viewed && !nextUnread.has(session.id)) {
        nextUnread.add(session.id)
        unreadChanged = true
      }
    }

    if (viewedChanged) {
      lastViewedAt.value = nextViewed
      saveLastViewedMap(lastViewedAt.value)
    }
    if (unreadChanged) {
      unreadSessions.value = Array.from(nextUnread)
      saveIdList(UNREAD_STORAGE_KEY, unreadSessions.value)
    }
  }

  const sortedSessions = computed(() => {
    const pinOrder = new Map(pinnedSessions.value.map((id, index) => [id, index]))
    return [...sessions.value].sort((a, b) => {
      const aPinned = pinOrder.has(a.id)
      const bPinned = pinOrder.has(b.id)
      if (aPinned && !bPinned) return -1
      if (!aPinned && bPinned) return 1
      if (aPinned && bPinned) {
        return (pinOrder.get(a.id) ?? 0) - (pinOrder.get(b.id) ?? 0)
      }
      return sessionSortTime(b) - sessionSortTime(a)
    })
  })

  function getLastMessage(sessionId: number): { preview: string; at: string } | null {
    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session) return null

    if (isViewingSession(sessionId) && messages.value.length > 0) {
      const last = messages.value[messages.value.length - 1]!
      let preview = (last.content || '').trim()
      if (!preview && last.imageUrl) preview = '[图片]'
      if (!preview && last.messageType === 'DRAW_REQUEST') preview = last.drawPrompt || '[绘画请求]'
      if (preview.length > 60) preview = `${preview.slice(0, 60)}…`
      // Same activity clock as list lastMessageAt / last-viewed watermark.
      return { preview, at: last.updatedAt || last.createdAt || session.updatedAt }
    }

    const preview = (session.lastMessagePreview || '').trim()
    return {
      preview: preview.length > 60 ? `${preview.slice(0, 60)}…` : preview,
      at: session.lastMessageAt || session.updatedAt,
    }
  }

  function beginOperation(
    sessionId: number,
    type: 'CHAT' | 'DRAW' | 'REGENERATE',
    messageId: number | null,
    stage: string,
  ) {
    activeRequestController.value?.abort()
    const controller = new AbortController()
    activeRequestController.value = controller
    activeOperationType.value = type
    activeOperationSessionId.value = sessionId
    activeOperationMessageId.value = messageId
    operationStage.value = stage
    operationStartedAt.value = Date.now()
    return controller
  }

  function finishOperation(controller: AbortController) {
    if (activeRequestController.value !== controller) return false
    activeRequestController.value = null
    activeOperationType.value = null
    activeOperationSessionId.value = null
    activeOperationMessageId.value = null
    operationStage.value = ''
    operationStartedAt.value = null
    return true
  }

  function markMessageCancelled(messageId: number | null) {
    if (messageId == null) return
    const message = messages.value.find((item) => item.id === messageId)
    if (!message) return
    message.status = 'FAILED'
    message.errorMessage = '用户已终止本次请求。'
    message.content = message.messageType === 'DRAW_RESPONSE'
      ? '图片生成已终止。您可以点击重新生成再次尝试。'
      : 'AI 请求已终止。您可以点击重新生成再次尝试。'
    if (message.messageType === 'DRAW_RESPONSE') message.drawPlaceholder = undefined
  }

  function cancelActiveRequest() {
    const controller = activeRequestController.value
    if (!controller) return
    const sessionId = activeOperationSessionId.value
    markMessageCancelled(activeOperationMessageId.value)
    operationStage.value = '正在终止请求...'
    controller.abort()

    if (sessionId != null) {
      const cancelOnServer = () => sessionApi.cancelPending(sessionId).catch(() => undefined)
      void cancelOnServer().then((result) => {
        if (!result?.cancelled) {
          window.setTimeout(() => void cancelOnServer(), 350)
        }
      })
    }
  }

  function isCancelled(controller: AbortController, error: unknown) {
    return controller.signal.aborted || (error instanceof Error && error.name === 'CanceledError')
  }

  async function fetchSessions() {
    const generation = ++sessionsFetchGeneration
    try {
      const fetchedSessions = await sessionApi.list()
      if (generation !== sessionsFetchGeneration) return
      sessions.value = fetchedSessions
      syncAutoUnreadFromSessions()
    } catch (e) {
      console.error('Failed to fetch sessions', e)
    }
  }

  async function fetchProviders() {
    try {
      chatProviders.value = await providerApi.list('CHAT')
      imageProviders.value = await providerApi.list('IMAGE')
    } catch (e) {
      console.error('Failed to fetch providers', e)
    }
  }

  async function createSession(title?: string) {
    const session = await sessionApi.create(title)
    await fetchSessions()
    return session
  }

  async function updateSessionTitle(id: number, title: string) {
    const session = await sessionApi.updateTitle(id, title)
    await fetchSessions()
    return session
  }

  async function deleteSession(id: number) {
    await sessionApi.delete(id)
    if (activeSessionId.value === id) {
      clearActiveSession()
    }
    if (pinnedSessions.value.includes(id)) {
      pinnedSessions.value = pinnedSessions.value.filter((item) => item !== id)
      saveIdList(PINNED_STORAGE_KEY, pinnedSessions.value)
    }
    if (unreadSessions.value.includes(id)) {
      unreadSessions.value = unreadSessions.value.filter((item) => item !== id)
      saveIdList(UNREAD_STORAGE_KEY, unreadSessions.value)
    }
    clearLastViewed(id)
    await fetchSessions()
  }

  function beginSessionSelection(id: number) {
    sessionSelectionGeneration += 1
    selectionTargetId.value = id
    return sessionSelectionGeneration
  }

  async function selectSession(id: number) {
    const generation = beginSessionSelection(id)
    const selectedMessages = await sessionApi.getMessages(id)
    if (generation !== sessionSelectionGeneration || selectionTargetId.value !== id) return

    activeSessionId.value = id
    messages.value = selectedMessages
    // Mark read only once the requested conversation has loaded.
    markAsRead(id)
    // Keep last-viewed ahead of any messages just loaded for this session.
    recordLastViewed(id)
    // Resume polling for any PENDING draw messages.
    for (const msg of selectedMessages) {
      if (msg.status === 'PENDING' && msg.messageType === 'DRAW_RESPONSE') {
        startPolling(id, msg.id)
      }
    }
  }

  /**
   * Leave the current chat without deleting it.
   * Mobile list pages call this so isUnread / syncAutoUnreadFromSessions can
   * surface red-dots for the session the user just left (activeSessionId would
   * otherwise suppress them).
   */
  function clearActiveSession() {
    sessionSelectionGeneration += 1
    selectionTargetId.value = null
    activeSessionId.value = null
    messages.value = []
  }

  async function reloadSessionIfCurrent(sessionId: number) {
    if (!isViewingSession(sessionId)) return false
    const selectedMessages = await sessionApi.getMessages(sessionId)
    if (!isViewingSession(sessionId)) return false

    messages.value = selectedMessages
    recordLastViewed(sessionId)
    for (const msg of selectedMessages) {
      if (msg.status === 'PENDING' && msg.messageType === 'DRAW_RESPONSE') {
        startPolling(sessionId, msg.id)
      }
    }
    return true
  }

  async function generate(
    prompt: string,
    attachmentIds?: number[],
    chatProviderId?: number | null,
    imageProviderId?: number | null,
  ) {
    const sessionId = activeSessionId.value
    if (sessionId == null || !isViewingSession(sessionId)) return
    loading.value = true
    try {
      const result = await sessionApi.generate(sessionId, {
        prompt,
        attachmentIds,
        chatProviderId,
        imageProviderId,
      })
      await reloadSessionIfCurrent(sessionId)
      await fetchSessions()
      return result
    } finally {
      loading.value = false
    }
  }

  function addChatPlaceholder(prompt: string, attachmentFiles: UploadResponse[] = []) {
    const now = new Date().toISOString()
    const tempBase = -Date.now()
    messages.value.push({
      id: tempBase,
      role: 'USER',
      messageType: 'CHAT',
      status: 'SUCCESS',
      content: prompt,
      imageUrl: null,
      attachments: attachmentFiles.map(uploadToAttachment),
      tokenUsage: null,
      edited: false,
      createdAt: now,
    })
    messages.value.push({
      id: tempBase - 1,
      role: 'ASSISTANT',
      messageType: 'CHAT',
      status: 'PENDING',
      content: '等待回应...',
      imageUrl: null,
      attachments: [],
      tokenUsage: null,
      parentMessageId: tempBase,
      edited: false,
      createdAt: now,
    })
    return tempBase - 1
  }

  function markPendingChatFailed(tempAssistantId: number, error: unknown) {
    const message = messages.value.find((item) => item.id === tempAssistantId)
    if (!message) return
    message.status = 'FAILED'
    message.content = 'AI 回应失败。请检查对话模型供应商或稍后重试。'
    message.errorMessage = error instanceof Error ? error.message : String(error || '请求失败')
  }

  async function chat(
    prompt: string,
    attachmentIds?: number[],
    chatProviderId?: number | null,
    attachmentFiles: UploadResponse[] = [],
  ) {
    const sessionId = activeSessionId.value
    if (sessionId == null || !isViewingSession(sessionId)) return
    loading.value = true
    const tempAssistantId = addChatPlaceholder(prompt, attachmentFiles)
    const controller = beginOperation(sessionId, 'CHAT', tempAssistantId, '正在等待模型回应，可随时终止')
    try {
      const result = await sessionApi.chat(sessionId, {
        prompt,
        attachmentIds,
        chatProviderId: chatProviderId ?? null,
      }, { signal: controller.signal })
      await reloadSessionIfCurrent(sessionId)
      await fetchSessions()
      return result
    } catch (e) {
      if (!isCancelled(controller, e) && isViewingSession(sessionId)) {
        markPendingChatFailed(tempAssistantId, e)
      }
      if (isViewingSession(sessionId)) {
        try { await reloadSessionIfCurrent(sessionId) } catch { /* ignore reload errors */ }
      }
      throw e
    } finally {
      if (finishOperation(controller)) loading.value = false
    }
  }

  function uploadToAttachment(upload: UploadResponse): Attachment {
    return {
      id: upload.id,
      originalName: upload.originalName,
      contentType: upload.contentType,
      fileSize: upload.fileSize,
      fileUrl: upload.fileUrl,
      createdAt: new Date().toISOString(),
    }
  }

  function addDrawPlaceholder(request: DrawRequest, referenceFiles: UploadResponse[] = []) {
    const now = new Date().toISOString()
    const tempBase = -Date.now()
    const optionParts = [
      request.size ? `尺寸 ${request.size}` : '',
      request.quality ? `质量 ${request.quality}` : '',
      request.format ? `格式 ${request.format}` : '',
    ].filter(Boolean)
    const userContent = optionParts.length > 0
      ? `绘画提示词：${request.prompt}\n输出配置：${optionParts.join('；')}`
      : `绘画提示词：${request.prompt}`

    messages.value.push({
      id: tempBase,
      role: 'USER',
      messageType: 'DRAW_REQUEST',
      status: 'SUCCESS',
      content: userContent,
      imageUrl: null,
      drawPrompt: request.prompt,
      drawSize: request.size,
      drawQuality: request.quality,
      drawFormat: request.format,
      drawProviderId: request.imageProviderId ?? null,
      attachments: referenceFiles.map(uploadToAttachment),
      tokenUsage: null,
      edited: false,
      createdAt: now,
    })
    messages.value.push({
      id: tempBase - 1,
      role: 'ASSISTANT',
      messageType: 'DRAW_RESPONSE',
      status: 'PENDING',
      content: '正在生成图片...',
      imageUrl: null,
      drawPrompt: request.prompt,
      drawSize: request.size,
      drawQuality: request.quality,
      drawFormat: request.format,
      drawProviderId: request.imageProviderId ?? null,
      attachments: [],
      tokenUsage: null,
      parentMessageId: tempBase,
      edited: false,
      createdAt: now,
      drawPlaceholder: {
        size: request.size,
        quality: request.quality,
        format: request.format,
      },
    })
    return tempBase - 1
  }

  // Polling for draw generation status
  async function applyMessageStatus(sessionId: number, status: MessageStatusResponse) {
    if (!isViewingSession(sessionId)) return
    const message = messages.value.find((item) => item.id === status.messageId)
    if (message) {
      message.status = status.status
      message.imageUrl = status.imageUrl
      message.content = status.content || message.content
      message.errorMessage = status.errorMessage
      message.processingInfo = status.processingInfo || null
      if (status.status === 'SUCCESS' || status.status === 'FAILED') {
        message.drawPlaceholder = status.status === 'SUCCESS' ? message.drawPlaceholder : undefined
      }
    }
  }

  async function pollMessageStatus(sessionId: number, messageId: number, generation: number) {
    const run = pollingRuns.get(messageId)
    if (!run || run.generation !== generation || run.inFlight) return null

    run.inFlight = true
    try {
      const status = await sessionApi.getMessageStatus(sessionId, messageId)
      const current = pollingRuns.get(messageId)
      if (!current || current.generation !== generation) return null

      polledMessageStatuses.value.set(messageId, {
        status: status.status,
        stage: status.processingInfo || '',
      })
      await applyMessageStatus(sessionId, status)
      return status
    } finally {
      const current = pollingRuns.get(messageId)
      if (current?.generation === generation) current.inFlight = false
    }
  }

  /**
   * Terminal status for a polled message (SUCCESS / FAILED).
   * Always refresh the sessions list so previews + auto-unread red-dots update,
   * even when the user has left this session. Only reload messages when the user
   * is still viewing the same session.
   */
  async function handlePolledTerminalStatus(sessionId: number) {
    if (isViewingSession(sessionId)) {
      try {
        await reloadSessionIfCurrent(sessionId)
      } catch {
        /* ignore reload errors; still refresh list below */
      }
    }
    // Always sync list + unread, including when the user is on another session
    // or the sessions list page (activeSessionId may be null / different).
    await fetchSessions()
  }

  function startPolling(sessionId: number, messageId: number) {
    stopPolling(messageId)
    const generation = ++pollingGeneration
    pollingRuns.set(messageId, { generation, inFlight: false })

    const pollOnce = async () => {
      try {
        const status = await pollMessageStatus(sessionId, messageId, generation)
        if (!status || status.status === 'PENDING') return
        const current = pollingRuns.get(messageId)
        if (!current || current.generation !== generation) return
        stopPolling(messageId)
        await handlePolledTerminalStatus(sessionId)
      } catch {
        // Keep the placeholder visible and retry on the next interval. A transient
        // network failure should not turn a durable queued message into a failure.
      }
    }

    const interval = setInterval(() => void pollOnce(), 3000)
    pollingIntervals.value.set(messageId, interval)
    void pollOnce()
  }

  function stopPolling(messageId: number) {
    pollingRuns.delete(messageId)
    const interval = pollingIntervals.value.get(messageId)
    if (interval) {
      clearInterval(interval)
      pollingIntervals.value.delete(messageId)
    }
    polledMessageStatuses.value.delete(messageId)
  }

  function stopAllPolling() {
    pollingRuns.clear()
    pollingIntervals.value.forEach((interval) => clearInterval(interval))
    pollingIntervals.value.clear()
    polledMessageStatuses.value.clear()
  }

  async function draw(request: DrawRequest, referenceFiles: UploadResponse[] = []) {
    const sessionId = activeSessionId.value
    if (sessionId == null || !isViewingSession(sessionId)) return
    loading.value = true
    const tempAssistantId = addDrawPlaceholder(request, referenceFiles)
    const controller = beginOperation(
      sessionId,
      'DRAW',
      tempAssistantId,
      '图片生成可能耗时较长；服务异常时将自动退避重试，可随时终止',
    )
    try {
      const result = await sessionApi.draw(sessionId, request, { signal: controller.signal })
      // Start polling if the backend returned PENDING (queue-based)
      if (result.status === 'PENDING' && result.assistantMessageId) {
        startPolling(sessionId, result.assistantMessageId)
      }
      await reloadSessionIfCurrent(sessionId)
      await fetchSessions()
      return result
    } catch (e) {
      if (!isCancelled(controller, e) && isViewingSession(sessionId)) {
        const pending = messages.value.find((item) => item.id === tempAssistantId)
        if (pending) {
          pending.status = 'FAILED'
          pending.content = '图片生成失败。请稍后重试。'
          pending.errorMessage = e instanceof Error ? e.message : String(e)
          pending.drawPlaceholder = undefined
        }
        try { await reloadSessionIfCurrent(sessionId) } catch { /* ignore reload errors */ }
      }
      throw e
    } finally {
      if (finishOperation(controller)) loading.value = false
    }
  }

  // Manual refresh for a message that's still PENDING
  async function manualRefreshMessage(messageId: number) {
    const sessionId = activeSessionId.value
    if (sessionId == null || !isViewingSession(sessionId)) return
    try {
      const status = await sessionApi.getMessageStatus(sessionId, messageId)
      await applyMessageStatus(sessionId, status)
      if (status.status === 'SUCCESS' || status.status === 'FAILED') {
        stopPolling(messageId)
        await reloadSessionIfCurrent(sessionId)
        await fetchSessions()
        return true
      } else {
        // Still pending, resume polling
        startPolling(sessionId, messageId)
        return false
      }
    } catch {
      return false
    }
  }

  async function editMessage(messageId: number, newContent: string) {
    if (!activeSessionId.value) return
    await sessionApi.editMessage(activeSessionId.value, messageId, { content: newContent })
    await selectSession(activeSessionId.value)
    editingMessageId.value = null
  }

  async function regenerateMessage(
    messageId: number,
    chatProviderId?: number | null,
    imageProviderId?: number | null,
  ) {
    const sessionId = activeSessionId.value
    if (sessionId == null || !isViewingSession(sessionId)) return
    const target = messages.value.find((item) => item.id === messageId)
    if (!target) return
    const isDraw = target.messageType === 'DRAW_RESPONSE'
    target.status = 'PENDING'
    target.errorMessage = null
    target.content = isDraw
      ? '图片重新生成中；服务异常时将自动退避重试...'
      : '等待模型重新回应...'
    if (isDraw) {
      target.imageUrl = null
      target.drawPlaceholder = {
        size: target.drawSize || undefined,
        quality: target.drawQuality || undefined,
        format: target.drawFormat || undefined,
      }
    }

    loading.value = true
    const controller = beginOperation(
      sessionId,
      'REGENERATE',
      messageId,
      isDraw ? '正在重新生成图片；失败时将自动退避重试，可随时终止' : '正在等待模型重新回应，可随时终止',
    )
    try {
      const result = await sessionApi.regenerateMessage(
        sessionId,
        messageId,
        { chatProviderId, imageProviderId },
        {
          signal: controller.signal,
          timeout: isDraw ? 15 * 60 * 1000 : 5 * 60 * 1000,
        },
      )
      if (result.status === 'PENDING' && result.messageId) {
        startPolling(sessionId, result.messageId)
      }
      await reloadSessionIfCurrent(sessionId)
      await fetchSessions()
      return result
    } catch (e) {
      if (!isCancelled(controller, e) && isViewingSession(sessionId)) {
        target.status = 'FAILED'
        target.errorMessage = e instanceof Error ? e.message : String(e)
        target.content = isDraw ? '图片重新生成失败。' : 'AI 重新回应失败。'
        if (isDraw) target.drawPlaceholder = undefined
      }
      throw e
    } finally {
      if (finishOperation(controller)) loading.value = false
    }
  }

  function addResendPlaceholder(userMessage: Message) {
    const isDraw = userMessage.messageType === 'DRAW_REQUEST'
    const placeholder: Message = {
      id: -Date.now(),
      role: 'ASSISTANT',
      messageType: isDraw ? 'DRAW_RESPONSE' : 'CHAT',
      status: 'PENDING',
      content: isDraw ? '图片生成中...' : '等待回应...',
      imageUrl: null,
      drawPrompt: userMessage.drawPrompt,
      drawSize: userMessage.drawSize,
      drawQuality: userMessage.drawQuality,
      drawFormat: userMessage.drawFormat,
      drawProviderId: userMessage.drawProviderId,
      attachments: [],
      tokenUsage: null,
      parentMessageId: userMessage.id,
      edited: false,
      createdAt: new Date().toISOString(),
      drawPlaceholder: isDraw ? {
        size: userMessage.drawSize || undefined,
        quality: userMessage.drawQuality || undefined,
        format: userMessage.drawFormat || undefined,
      } : undefined,
    }

    const userIndex = messages.value.findIndex((item) => item.id === userMessage.id)
    let insertIndex = userIndex >= 0 ? userIndex + 1 : messages.value.length
    while (
      insertIndex < messages.value.length
      && messages.value[insertIndex]?.role === 'ASSISTANT'
      && messages.value[insertIndex]?.parentMessageId === userMessage.id
    ) {
      insertIndex++
    }
    messages.value.splice(insertIndex, 0, placeholder)
    return placeholder
  }

  async function resendUserMessage(
    messageId: number,
    chatProviderId?: number | null,
    imageProviderId?: number | null,
  ) {
    const sessionId = activeSessionId.value
    if (sessionId == null || !isViewingSession(sessionId)) return
    const userMessage = messages.value.find((item) => item.id === messageId)
    if (!userMessage || userMessage.role !== 'USER') return

    const isDraw = userMessage.messageType === 'DRAW_REQUEST'
    const placeholder = addResendPlaceholder(userMessage)
    loading.value = true
    const controller = beginOperation(
      sessionId,
      'REGENERATE',
      placeholder.id,
      isDraw ? '正在再次生成图片，可随时终止' : '正在再次发送消息并等待模型回应，可随时终止',
    )
    try {
      const result = await sessionApi.regenerateMessage(
        sessionId,
        messageId,
        { chatProviderId, imageProviderId },
        {
          signal: controller.signal,
          timeout: isDraw ? 15 * 60 * 1000 : 5 * 60 * 1000,
        },
      )
      if (result.status === 'PENDING' && result.messageId) {
        startPolling(sessionId, result.messageId)
      }
      await reloadSessionIfCurrent(sessionId)
      await fetchSessions()
      return result
    } catch (e) {
      if (!isCancelled(controller, e) && isViewingSession(sessionId)) {
        placeholder.status = 'FAILED'
        placeholder.errorMessage = e instanceof Error ? e.message : String(e)
        placeholder.content = isDraw ? '图片再次生成失败。' : 'AI 再次回应失败。'
        placeholder.drawPlaceholder = undefined
      }
      throw e
    } finally {
      if (finishOperation(controller)) loading.value = false
    }
  }

  async function deleteMessage(messageId: number) {
    if (!activeSessionId.value) return
    await sessionApi.deleteMessage(activeSessionId.value, messageId)
    await selectSession(activeSessionId.value)
  }

  async function uploadFiles(files: File[]): Promise<number[]> {
    const responses = await sessionApi.uploadFiles(files)
    return responses.map((r) => r.id)
  }

  async function updateSessionProviders(chatProviderId?: number | null, imageProviderId?: number | null) {
    if (!activeSessionId.value) return
    await sessionApi.updateSessionProviders(activeSessionId.value, { chatProviderId, imageProviderId })
    await fetchSessions()
    await selectSession(activeSessionId.value)
  }

  function startEditing(messageId: number) {
    editingMessageId.value = messageId
  }

  function cancelEditing() {
    editingMessageId.value = null
  }

  return {
    sessions,
    sortedSessions,
    activeSessionId,
    selectionTargetId,
    messages,
    loading,
    canCancel,
    activeOperationType,
    operationStage,
    operationStartedAt,
    chatProviders,
    imageProviders,
    editingMessageId,
    pollingIntervals,
    polledMessageStatuses,
    pinnedSessions,
    unreadSessions,
    fetchSessions,
    fetchProviders,
    createSession,
    updateSessionTitle,
    deleteSession,
    selectSession,
    clearActiveSession,
    generate,
    chat,
    draw,
    editMessage,
    resendUserMessage,
    regenerateMessage,
    deleteMessage,
    uploadFiles,
    updateSessionProviders,
    startEditing,
    cancelEditing,
    cancelActiveRequest,
    startPolling,
    stopPolling,
    stopAllPolling,
    manualRefreshMessage,
    togglePin,
    markAsUnread,
    markAsRead,
    isPinned,
    isUnread,
    getLastViewed,
    getLastMessage,
  }
})