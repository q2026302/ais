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
  const messages = ref<Message[]>([])
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
   * Latest activity the user can already see for a session, on the server
   * timeline. Prefer session.lastMessageAt; when the session is open and
   * messages are loaded, also cover any message createdAt that the list
   * endpoint has not caught up with yet.
   */
  function visibleActivityTime(sessionId: number): number {
    const session = sessions.value.find((item) => item.id === sessionId)
    let latest = session ? sessionActivityTime(session) : 0
    if (activeSessionId.value === sessionId) {
      for (const message of messages.value) {
        const ts = parseApiDate(message.createdAt)?.getTime() ?? 0
        if (ts > latest) latest = ts
      }
    }
    return latest
  }

  function recordLastViewed(sessionId: number, at?: number) {
    const activity = visibleActivityTime(sessionId)
    // Stay on the server-activity timeline. Mixing Date.now() with server
    // lastMessageAt makes `activity > viewed` fail under even small clock skew
    // (client ahead of server → every later message still looks "already seen").
    let ts: number
    if (typeof at === 'number' && Number.isFinite(at) && at > 0) {
      ts = at
    } else if (activity > 0) {
      ts = activity
    } else {
      // Empty session with no server activity yet — client stamp is fine until
      // the first message arrives; syncAutoUnread also runs after fetchSessions.
      ts = Date.now()
    }
    // Always cover activity already visible while the user is viewing.
    if (activity > ts) ts = activity
    const prev = getLastViewed(sessionId)
    if (prev != null && prev >= ts) return
    console.warn('[UNREAD] recordLastViewed', { sessionId, activity, ts, prev, active: activeSessionId.value })
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

  function sessionActivityTime(session: Session): number {
    const raw = session.lastMessageAt || session.updatedAt
    // parseApiDate handles UTC `Z` / offset / legacy zoneless values correctly.
    // Date.parse alone mis-handles zoneless backend strings on some engines.
    const date = parseApiDate(raw)
    return date ? date.getTime() : 0
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

  function isUnread(sessionId: number) {
    if (activeSessionId.value === sessionId) return false
    if (unreadSessions.value.includes(sessionId)) return true

    const session = sessions.value.find((item) => item.id === sessionId)
    if (!session || !sessionHasMessages(session)) return false

    const viewed = getLastViewed(sessionId)
    // Never opened in this client → no auto red-dot until the user has viewed it once
    // and a later message arrives. Manual mark-as-unread still works via unreadSessions.
    if (viewed == null) return false

    const activity = sessionActivityTime(session)
    const result = activity > viewed
    console.warn('[UNREAD] isUnread', { sessionId, active: activeSessionId.value, activity, viewed, result })
    return result
  }

  /**
   * After sessions list refresh, promote sessions with newer activity than last-viewed
   * into the manual unread set so the red-dot persists even if timestamps are equalized later.
   *
   * For the session currently open, bump lastViewed up to the fresh activity so
   * leaving the chat does not flash a red-dot for messages the user already saw.
   */
  function syncAutoUnreadFromSessions() {
    let unreadChanged = false
    let viewedChanged = false
    const nextUnread = new Set(unreadSessions.value)
    let nextViewed = lastViewedAt.value

    for (const session of sessions.value) {
      const activity = sessionActivityTime(session)
      console.warn('[UNREAD] syncAuto', { sessionId: session.id, activity, viewed: getLastViewed(session.id), isActive: activeSessionId.value === session.id })
      if (activeSessionId.value === session.id) {
        // User is looking at this session — whatever just arrived is already seen.
        if (activity > 0) {
          const viewed = getLastViewed(session.id)
          if (viewed == null || activity > viewed) {
            if (nextViewed === lastViewedAt.value) nextViewed = { ...lastViewedAt.value }
            nextViewed[String(session.id)] = activity
            viewedChanged = true
          }
        }
        continue
      }
      if (!sessionHasMessages(session)) continue
      const viewed = getLastViewed(session.id)
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

  function sessionSortTime(session: Session): number {
    return sessionActivityTime(session)
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

    if (activeSessionId.value === sessionId && messages.value.length > 0) {
      const last = messages.value[messages.value.length - 1]!
      let preview = (last.content || '').trim()
      if (!preview && last.imageUrl) preview = '[图片]'
      if (!preview && last.messageType === 'DRAW_REQUEST') preview = last.drawPrompt || '[绘画请求]'
      if (preview.length > 60) preview = `${preview.slice(0, 60)}…`
      return { preview, at: last.createdAt || session.updatedAt }
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
    try {
      sessions.value = await sessionApi.list()
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
      activeSessionId.value = null
      messages.value = []
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

  async function selectSession(id: number) {
    activeSessionId.value = id
    markAsRead(id)
    recordLastViewed(id)
    const selectedMessages = await sessionApi.getMessages(id)
    if (activeSessionId.value === id) {
      messages.value = selectedMessages
      // Keep last-viewed ahead of any messages just loaded for this session.
      recordLastViewed(id)
      // Resume polling for any PENDING draw messages
      for (const msg of selectedMessages) {
        if (msg.status === 'PENDING' && msg.messageType === 'DRAW_RESPONSE') {
          startPolling(id, msg.id)
        }
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
    activeSessionId.value = null
    messages.value = []
  }

  async function generate(
    prompt: string,
    attachmentIds?: number[],
    chatProviderId?: number | null,
    imageProviderId?: number | null,
  ) {
    if (!activeSessionId.value) return
    loading.value = true
    try {
      const result = await sessionApi.generate(activeSessionId.value, {
        prompt,
        attachmentIds,
        chatProviderId,
        imageProviderId,
      })
      await selectSession(activeSessionId.value)
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
    if (sessionId == null) return
    loading.value = true
    const tempAssistantId = addChatPlaceholder(prompt, attachmentFiles)
    const controller = beginOperation(sessionId, 'CHAT', tempAssistantId, '正在等待模型回应，可随时终止')
    try {
      const result = await sessionApi.chat(sessionId, {
        prompt,
        attachmentIds,
        chatProviderId: chatProviderId ?? null,
      }, { signal: controller.signal })
      if (activeSessionId.value === sessionId) await selectSession(sessionId)
      await fetchSessions()
      return result
    } catch (e) {
      if (!isCancelled(controller, e)) markPendingChatFailed(tempAssistantId, e)
      if (activeSessionId.value === sessionId) {
        try { await selectSession(sessionId) } catch { /* ignore reload errors */ }
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
    if (activeSessionId.value !== sessionId) return
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

  async function pollMessageStatus(sessionId: number, messageId: number) {
    const status = await sessionApi.getMessageStatus(sessionId, messageId)
    polledMessageStatuses.value.set(messageId, {
      status: status.status,
      stage: status.processingInfo || '',
    })
    await applyMessageStatus(sessionId, status)
    return status
  }

  /**
   * Terminal status for a polled message (SUCCESS / FAILED).
   * Always refresh the sessions list so previews + auto-unread red-dots update,
   * even when the user has left this session. Only reload messages when the user
   * is still viewing the same session.
   */
  async function handlePolledTerminalStatus(sessionId: number) {
    if (activeSessionId.value === sessionId) {
      try {
        await selectSession(sessionId)
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
    const interval = setInterval(async () => {
      try {
        const status = await pollMessageStatus(sessionId, messageId)
        if (status.status === 'SUCCESS' || status.status === 'FAILED') {
          stopPolling(messageId)
          await handlePolledTerminalStatus(sessionId)
        }
      } catch {
        // Keep the placeholder visible and retry on the next interval. A transient
        // network failure should not turn a durable queued message into a failure.
      }
    }, 3000)
    pollingIntervals.value.set(messageId, interval)
    void pollMessageStatus(sessionId, messageId).then(async (status) => {
      if (status.status === 'SUCCESS' || status.status === 'FAILED') {
        stopPolling(messageId)
        await handlePolledTerminalStatus(sessionId)
      }
    }).catch(() => undefined)
  }

  function stopPolling(messageId: number) {
    const interval = pollingIntervals.value.get(messageId)
    if (interval) {
      clearInterval(interval)
      pollingIntervals.value.delete(messageId)
    }
    polledMessageStatuses.value.delete(messageId)
  }

  function stopAllPolling() {
    pollingIntervals.value.forEach((interval) => clearInterval(interval))
    pollingIntervals.value.clear()
    polledMessageStatuses.value.clear()
  }

  async function draw(request: DrawRequest, referenceFiles: UploadResponse[] = []) {
    const sessionId = activeSessionId.value
    if (sessionId == null) return
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
      if (activeSessionId.value === sessionId) await selectSession(sessionId)
      await fetchSessions()
      return result
    } catch (e) {
      if (!isCancelled(controller, e)) {
        const pending = messages.value.find((item) => item.id === tempAssistantId)
        if (pending) {
          pending.status = 'FAILED'
          pending.content = '图片生成失败。请稍后重试。'
          pending.errorMessage = e instanceof Error ? e.message : String(e)
          pending.drawPlaceholder = undefined
        }
        if (activeSessionId.value === sessionId) {
          try { await selectSession(sessionId) } catch { /* ignore reload errors */ }
        }
      }
      throw e
    } finally {
      if (finishOperation(controller)) loading.value = false
    }
  }

  // Manual refresh for a message that's still PENDING
  async function manualRefreshMessage(messageId: number) {
    const sessionId = activeSessionId.value
    if (sessionId == null) return
    try {
      const status = await sessionApi.getMessageStatus(sessionId, messageId)
      await applyMessageStatus(sessionId, status)
      if (status.status === 'SUCCESS' || status.status === 'FAILED') {
        stopPolling(messageId)
        await selectSession(sessionId)
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
    if (sessionId == null) return
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
      if (activeSessionId.value === sessionId) await selectSession(sessionId)
      return result
    } catch (e) {
      if (!isCancelled(controller, e)) {
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
    if (sessionId == null) return
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
      if (activeSessionId.value === sessionId) await selectSession(sessionId)
      return result
    } catch (e) {
      if (!isCancelled(controller, e)) {
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
    getLastMessage,
  }
})