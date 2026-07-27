<script setup lang="ts">
import { computed, h, inject, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowRight,
  ChatDotRound,
  Check,
  Clock,
  Close,
  CopyDocument,
  Delete,
  Download,
  EditPen,
  FullScreen,
  MagicStick,
  Paperclip,
  Picture,
  Plus,
  Promotion,
  RefreshRight,
  UploadFilled,
} from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import { sessionApi } from '@/api/sessions'
import type { Message, ModelProvider, UploadResponse } from '@/types'
import { CHAT_COMMAND_HELP, parseChatCommand } from '@/utils/chatCommands'
import CollapsibleMessageText from '@/components/CollapsibleMessageText.vue'
import MobileImageViewer from '@/components/MobileImageViewer.vue'
import { getAttachmentThumbnailUrl, getThumbnailUrl } from '@/utils/imageUrl'
import { formatTimeHm, parseApiDate } from '@/utils/dateTime'
import { useLongPress } from '@/composables/useLongPress'
import { useImageActions } from '@/composables/useImageActions'
import type { MobileKeyboardApi } from './FeishuMobileLayout.vue'
import { isPwaEntry, readPwaKeyboardDiagnostics } from '@/utils/visualViewport'

defineOptions({
  name: 'FeishuChatPage',
})

const store = useSessionStore()
const router = useRouter()
const route = useRoute()

const entryPrefix = computed(() => route.meta.mobileEntry ?? 'mobile')

const messagesRef = ref<HTMLElement | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const inputRef = ref<HTMLTextAreaElement | null>(null)

/** Keyboard state owned by FeishuMobileLayout (single visualViewport subscription). */
const mobileKeyboard = inject<MobileKeyboardApi>('mobileKeyboard')
const inputChromeCollapsed = computed(() => mobileKeyboard?.inputChromeCollapsed.value ?? false)

const fullscreenInput = ref(false)
const inputText = ref('')
const pendingAttachments = ref<UploadResponse[]>([])
const uploading = ref(false)
const initializing = ref(true)
const mode = ref<'chat' | 'draw'>('draw')
const composerExtraVisible = ref(false)
const referenceVisible = ref(false)
const referenceImportingId = ref<number | null>(null)
const modelVisible = ref(false)
const drawSettingsVisible = ref(false)
const imageViewerVisible = ref(false)
const imageViewerImages = ref<string[]>([])
const imageViewerIndex = ref(0)
const messageActionVisible = ref(false)
const messageActionTarget = ref<Message | null>(null)
const editVisible = ref(false)
const editTargetId = ref<number | null>(null)
const editText = ref('')
const selectedChatProviderId = ref<number | null>(null)
const selectedImageProviderId = ref<number | null>(null)
const drawSize = ref('1024x1024')
const drawQuality = ref('auto')
const drawFormat = ref('png')
const originalTitle = document.title
let disposed = false
let selectionGeneration = 0

const {
  longPressTriggered,
  startLongPress,
  moveLongPress,
  cancelLongPress,
  finishLongPress,
  clearResidualSelection,
  setSelectionSuppressed,
} = useLongPress()

const {
  imageActionVisible,
  imageActionUrl,
  imageActionFilename,
  saveHelperVisible,
  saveHelperUrl,
  saveHelperFilename,
  openImageAction: openImageActionBase,
  downloadImageAction,
  downloadImage,
  shareFromHelper,
  closeSaveHelper,
} = useImageActions()

interface HistoryImageItem {
  id: string
  url: string
  thumbUrl: string
  label: string
  format: string
  messageId: number
  attachmentId?: number
}

const messageThumbFailedIds = ref<Set<number>>(new Set())
const historyThumbFailedIds = ref<Set<string>>(new Set())

function onMessageThumbError(id: number) {
  messageThumbFailedIds.value = new Set(messageThumbFailedIds.value).add(id)
}
function onHistoryThumbError(id: string) {
  historyThumbFailedIds.value = new Set(historyThumbFailedIds.value).add(id)
}
function messageDisplayUrl(message: Message) {
  if (!message.imageUrl) return ''
  return messageThumbFailedIds.value.has(message.id) ? message.imageUrl : getThumbnailUrl(message.id)
}
function historyDisplayUrl(item: HistoryImageItem) {
  if (historyThumbFailedIds.value.has(item.id)) return item.url
  return item.thumbUrl || item.url
}

const activeSession = computed(() => store.sessions.find((item) => item.id === store.activeSessionId) || null)

const generatedImages = computed(() => store.messages.filter((message) => Boolean(message.imageUrl)))

const historyImages = computed<HistoryImageItem[]>(() => {
  const items: HistoryImageItem[] = []
  for (const message of store.messages) {
    if (message.imageUrl && message.status !== 'FAILED') {
      items.push({
        id: `gen-${message.id}`,
        url: message.imageUrl,
        thumbUrl: getThumbnailUrl(message.id),
        label: message.drawPrompt || 'AI 生成图片',
        format: message.drawFormat || 'png',
        messageId: message.id,
      })
    }
    if (message.attachments?.length) {
      for (const attachment of message.attachments) {
        if (attachment.contentType?.startsWith('image/') && !attachment.originalName?.startsWith('history-')) {
          const ext = attachment.originalName?.split('.').pop() || 'png'
          items.push({
            id: `att-${message.id}-${attachment.id}`,
            url: attachment.fileUrl,
            thumbUrl: getAttachmentThumbnailUrl(attachment.id),
            label: attachment.originalName || '用户上传图片',
            format: ext,
            messageId: message.id,
            attachmentId: attachment.id,
          })
        }
      }
    }
  }
  return items.reverse()
})

const currentProviders = computed(() => mode.value === 'chat' ? store.chatProviders : store.imageProviders)
const selectedProviderId = computed(() => mode.value === 'chat' ? selectedChatProviderId.value : selectedImageProviderId.value)
const selectedProvider = computed<ModelProvider | null>(() => currentProviders.value.find((item) => item.id === selectedProviderId.value) || null)
const selectedImageProvider = computed<ModelProvider | null>(() => {
  if (selectedImageProviderId.value == null) return null
  return store.imageProviders.find((provider) => provider.id === selectedImageProviderId.value) || null
})
const imageAdapter = computed(() => {
  const configured = selectedImageProvider.value?.adapterType?.toUpperCase()
  if (configured && configured !== 'AUTO') return configured
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  const providerId = selectedImageProvider.value?.providerId?.toLowerCase() || ''
  if (providerId === 'grsai') return 'GRS_AI'
  return model.includes('gemini') ? 'GEMINI_IMAGE' : 'OPENAI_IMAGE'
})
const usesRatioOptions = computed(() => imageAdapter.value === 'GEMINI_IMAGE' || (imageAdapter.value === 'GRS_AI' && (selectedImageProvider.value?.modelName || '').toLowerCase().includes('nano-banana')))
const isGptImageModel = computed(() => {
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  return (imageAdapter.value === 'OPENAI_IMAGE' || imageAdapter.value === 'GRS_AI') && (model.includes('gpt-image') || model.includes('gpt image'))
})
const drawSizeOptions = computed(() => usesRatioOptions.value ? ['1:1', '16:9', '9:16', '4:3', '3:4'] : isGptImageModel.value ? ['1024x1024', '1536x1024', '1024x1536', 'auto'] : ['1024x1024', '512x512', '768x768', '1024x1792', '1792x1024'])
const drawQualityOptions = computed(() => usesRatioOptions.value ? ['1K', '2K', '4K'] : isGptImageModel.value ? ['auto', 'low', 'medium', 'high'] : ['standard', 'hd'])
const drawFormatOptions = computed(() => usesRatioOptions.value ? ['png'] : ['png', 'jpeg', 'webp'])
const selectedProviderLabel = computed(() => {
  const provider = selectedProvider.value
  return provider ? `${provider.name || provider.providerId} / ${provider.modelName}` : '系统默认'
})
const referenceImageCount = computed(() => pendingAttachments.value.filter((item) => item.contentType?.startsWith('image/')).length)
const editingMessage = computed(() => store.messages.find((item) => item.id === editTargetId.value) || null)
const canSubmit = computed(() => !store.loading && !uploading.value && (Boolean(inputText.value.trim()) || pendingAttachments.value.length > 0))
const activeSessionTitle = computed(() => activeSession.value?.title || 'AI 创作')

function defaultProviderId(providers: ModelProvider[]) {
  return providers.find((item) => item.active)?.id ?? null
}
function syncProviderSelection() {
  selectedChatProviderId.value = activeSession.value?.chatProviderId ?? defaultProviderId(store.chatProviders)
  selectedImageProviderId.value = activeSession.value?.imageProviderId ?? defaultProviderId(store.imageProviders)
}
function syncDrawOptions() {
  if (!drawSizeOptions.value.includes(drawSize.value)) drawSize.value = usesRatioOptions.value ? '1:1' : '1024x1024'
  if (!drawQualityOptions.value.includes(drawQuality.value)) drawQuality.value = usesRatioOptions.value ? '1K' : isGptImageModel.value ? 'auto' : 'standard'
  if (!drawFormatOptions.value.includes(drawFormat.value)) drawFormat.value = 'png'
}
async function scrollToBottom() {
  await nextTick()
  if (messagesRef.value) messagesRef.value.scrollTop = messagesRef.value.scrollHeight
}

function routeSessionId(): number | null {
  const raw = route.params.id
  const value = Array.isArray(raw) ? raw[0] : raw
  const id = Number(value)
  return Number.isFinite(id) && id > 0 ? id : null
}

function beginSelection() {
  selectionGeneration += 1
  return selectionGeneration
}

function isCurrentSelection(generation: number) {
  return !disposed && selectionGeneration === generation
}

async function selectCurrentSession(sessionId: number, generation: number) {
  if (!isCurrentSelection(generation)) return false
  await store.selectSession(sessionId)
  return isCurrentSelection(generation)
}

async function navigateToSession(id: number) {
  if (Number(route.params.id) === id) return
  await router.replace({ name: entryPrefix.value + '-chat', params: { id } })
}

async function initialize() {
  const generation = beginSelection()
  initializing.value = true
  try {
    await Promise.all([store.fetchSessions(), store.fetchProviders()])
    if (!isCurrentSelection(generation)) return

    const targetId = routeSessionId()
    if (targetId != null) {
      if (!await selectCurrentSession(targetId, generation)) return
    } else if (store.activeSessionId != null) {
      const activeId = store.activeSessionId
      if (!await selectCurrentSession(activeId, generation)) return
      if (!isCurrentSelection(generation)) return
      await navigateToSession(activeId)
    } else {
      const first = store.sessions[0]
      if (first) {
        if (!await selectCurrentSession(first.id, generation)) return
        if (!isCurrentSelection(generation)) return
        await navigateToSession(first.id)
      } else {
        const session = await store.createSession()
        if (!session || !isCurrentSelection(generation)) return
        if (!await selectCurrentSession(session.id, generation)) return
        if (!isCurrentSelection(generation)) return
        await navigateToSession(session.id)
      }
    }
    if (!isCurrentSelection(generation)) return
    syncProviderSelection()
    syncDrawOptions()
    await scrollToBottom()
  } catch (error: any) {
    if (!disposed) ElMessage.error(error.message || '初始化创作页面失败')
  } finally {
    // A route selection can supersede initialization before its request settles.
    // This page has no second initialization run, so it must always release the
    // initial loading state once this run finishes.
    if (!disposed) initializing.value = false
  }
}

async function createNewSession() {
  try {
    const session = await store.createSession()
    if (!session) return
    await store.selectSession(session.id)
    syncProviderSelection()
    inputText.value = ''
    pendingAttachments.value = []
    await navigateToSession(session.id)
    await scrollToBottom()
  } catch (error: any) {
    ElMessage.error(error.message || '新建会话失败')
  }
}

async function selectSession(id: number) {
  const generation = beginSelection()
  try {
    if (!await selectCurrentSession(id, generation)) return
    syncProviderSelection()
    if (!isCurrentSelection(generation)) return
    await navigateToSession(id)
    if (!isCurrentSelection(generation)) return
    await scrollToBottom()
  } catch (error: any) {
    if (!disposed) ElMessage.error(error.message || '加载会话失败')
  }
}

async function ensureSession() {
  if (store.activeSessionId != null) return true
  try {
    const session = await store.createSession()
    if (!session) return false
    await store.selectSession(session.id)
    syncProviderSelection()
    await navigateToSession(session.id)
    return true
  } catch (error: any) {
    ElMessage.error(error.message || '创建会话失败')
    return false
  }
}

function goSessions() {
  void router.push({ name: entryPrefix.value + '-sessions' })
}

function parseId(argument: string): number | null {
  if (!/^\d+$/.test(argument)) return null
  const id = Number(argument)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

async function showCommandHelp() {
  await ElMessageBox({
    title: '系统命令',
    message: () => h('pre', { style: 'margin: 0; white-space: pre-wrap; font: inherit; line-height: 1.65;' }, CHAT_COMMAND_HELP),
    confirmButtonText: '关闭',
  })
}

async function handleSystemCommand(prompt: string, attachments: UploadResponse[]): Promise<{ handled: boolean; keepDraft?: boolean }> {
  const command = parseChatCommand(prompt)
  if (!command) return { handled: false }
  if (attachments.length > 0 && command.name !== 'draw') {
    ElMessage.warning('系统命令不能携带附件；请移除附件后重试。')
    return { handled: true, keepDraft: true }
  }
  switch (command.name) {
    case 'help':
      await showCommandHelp()
      return { handled: true }
    case 'new': {
      const session = await store.createSession(command.argument || undefined)
      if (!session) return { handled: true }
      await store.selectSession(session.id)
      syncProviderSelection()
      inputText.value = ''
      pendingAttachments.value = []
      await navigateToSession(session.id)
      await scrollToBottom()
      ElMessage.success(`已切换到新会话 #${session.id}`)
      return { handled: true }
    }
    case 'sessions':
      await store.fetchSessions()
      goSessions()
      return { handled: true }
    case 'switch': {
      const sessionId = parseId(command.argument)
      if (sessionId == null) {
        ElMessage.warning('用法：/switch <会话ID>')
        return { handled: true }
      }
      const exists = store.sessions.some((item) => item.id === sessionId)
      if (!exists) {
        ElMessage.warning(`未找到会话 #${sessionId}`)
        return { handled: true }
      }
      await selectSession(sessionId)
      ElMessage.success(`已切换到会话 #${sessionId}`)
      return { handled: true }
    }
    case 'rename': {
      if (store.activeSessionId == null) {
        ElMessage.warning('请先创建或切换到一个会话。')
        return { handled: true }
      }
      if (!command.argument) {
        ElMessage.warning('用法：/rename <新标题>')
        return { handled: true }
      }
      const session = await store.updateSessionTitle(store.activeSessionId, command.argument)
      ElMessage.success(`会话已重命名为“${session.title}”`)
      return { handled: true }
    }
    case 'delete': {
      if (store.activeSessionId == null) {
        ElMessage.warning('当前没有可删除的会话。')
        return { handled: true }
      }
      const session = store.sessions.find((item) => item.id === store.activeSessionId)
      await ElMessageBox.confirm(`将永久删除会话“${session?.title || `#${store.activeSessionId}`}”及其中的全部消息和附件。`, '确认删除会话', {
        type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
      })
      await store.deleteSession(store.activeSessionId)
      const nextSession = store.sessions[0]
      if (nextSession) await selectSession(nextSession.id)
      else await createNewSession()
      ElMessage.success('会话已删除')
      return { handled: true }
    }
    case 'cancel':
      if (!store.canCancel) ElMessage.info('当前没有正在执行的请求。')
      else {
        store.cancelActiveRequest()
        ElMessage.info('正在终止请求…')
      }
      return { handled: true }
    case 'models':
      await store.fetchProviders()
      mode.value = 'chat'
      modelVisible.value = true
      return { handled: true }
    case 'model': {
      const providerId = parseId(command.argument)
      if (providerId == null) {
        ElMessage.warning('用法：/model <模型ID>；可使用 /models 查看列表。')
        return { handled: true }
      }
      if (store.activeSessionId == null) {
        ElMessage.warning('请先创建或切换到一个会话。')
        return { handled: true }
      }
      const provider = store.chatProviders.find((item) => item.id === providerId)
      if (!provider) {
        ElMessage.warning(`未找到对话模型 #${providerId}，可使用 /models 查看列表。`)
        return { handled: true }
      }
      await store.updateSessionProviders(provider.id, undefined)
      selectedChatProviderId.value = provider.id
      ElMessage.success(`当前会话已切换至 ${provider.name || provider.providerId} / ${provider.modelName}`)
      return { handled: true }
    }
    case 'draw':
      if (!command.argument) {
        ElMessage.warning('用法：/draw <绘图提示词>')
        return { handled: true, keepDraft: true }
      }
      mode.value = 'draw'
      inputText.value = command.argument
      return { handled: true, keepDraft: true }
    default:
      ElMessage.warning(`未知命令 /${command.rawName}；输入 /help 查看可用命令。`)
      return { handled: true, keepDraft: true }
  }
}

async function handleSubmit() {
  if (!canSubmit.value) {
    if (mode.value === 'draw') ElMessage.info('请先输入绘画描述')
    return
  }
  const prompt = inputText.value.trim()
  const attachments = [...pendingAttachments.value]
  try {
    const commandResult = await handleSystemCommand(prompt, attachments)
    if (commandResult.handled) {
      if (!commandResult.keepDraft) {
        inputText.value = ''
        pendingAttachments.value = []
      }
      return
    }
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.message || '执行系统命令失败')
    return
  }
  if (!(await ensureSession())) return
  inputText.value = ''
  pendingAttachments.value = []
  try {
    if (mode.value === 'draw') {
      await store.draw({
        prompt,
        attachmentIds: attachments.map((item) => item.id),
        imageProviderId: selectedImageProviderId.value,
        size: drawSize.value,
        quality: drawQuality.value,
        format: drawFormat.value,
      }, attachments)
    } else {
      await store.chat(prompt, attachments.map((item) => item.id), selectedChatProviderId.value, attachments)
    }
    await scrollToBottom()
  } catch (error: any) {
    if (error?.name !== 'CanceledError') ElMessage.error(error.message || '请求失败，请稍后重试')
  }
}

function handleInputKeydown(event: KeyboardEvent) {
  if (event.isComposing || (!event.ctrlKey && !event.metaKey) || event.key !== 'Enter') return
  event.preventDefault()
  void handleSubmit()
}

function openFilePicker() {
  if (!store.loading && !uploading.value) fileInputRef.value?.click()
}

function autoResizeTextarea() {
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  const lineHeight = parseInt(getComputedStyle(el).lineHeight, 10) || 20
  const maxHeight = lineHeight * 4 + 16
  el.style.height = Math.min(el.scrollHeight, maxHeight) + 'px'
  el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
}

function toggleFullscreenInput() {
  fullscreenInput.value = !fullscreenInput.value
  if (!fullscreenInput.value) void nextTick(() => autoResizeTextarea())
}

async function uploadFile(file: File) {
  if (mode.value === 'draw' && !file.type.startsWith('image/')) {
    ElMessage.warning('绘画模式仅支持添加图片参考图')
    return
  }
  if (mode.value === 'chat' && !file.type.startsWith('image/') && !/\.(pdf|docx?|txt)$/i.test(file.name)) {
    ElMessage.warning('对话模式支持图片、PDF、Word 和文本文件')
    return
  }
  uploading.value = true
  try {
    pendingAttachments.value.push(await sessionApi.uploadFile(file))
  } catch (error: any) {
    ElMessage.error(error.message || '图片上传失败')
  } finally {
    uploading.value = false
  }
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  for (const file of files) await uploadFile(file)
}

async function handlePaste(event: ClipboardEvent) {
  const files = Array.from(event.clipboardData?.items || [])
    .filter((item) => item.type.startsWith('image/'))
    .map((item) => item.getAsFile())
    .filter((file): file is File => file != null)
  if (!files.length) return
  event.preventDefault()
  for (const file of files) await uploadFile(file)
}

function removeAttachment(id: number) {
  pendingAttachments.value = pendingAttachments.value.filter((item) => item.id !== id)
}

async function selectHistoryImage(item: HistoryImageItem) {
  if (store.loading || referenceImportingId.value != null) return
  referenceImportingId.value = item.messageId
  try {
    const attachment = await sessionApi.uploadImageReference(item.url, `history-${item.messageId}.${item.format}`)
    pendingAttachments.value.push(attachment)
    referenceVisible.value = false
    ElMessage.success('历史图片已添加为参考图')
  } catch (error: any) {
    ElMessage.error(error.message || '添加历史图片失败')
  } finally {
    referenceImportingId.value = null
  }
}

async function selectModel(id: number | null) {
  if (mode.value === 'chat') selectedChatProviderId.value = id
  else selectedImageProviderId.value = id
  modelVisible.value = false
  syncDrawOptions()
  if (!store.activeSessionId) return
  try {
    await store.updateSessionProviders(mode.value === 'chat' ? id : undefined, mode.value === 'draw' ? id : undefined)
    ElMessage.success('当前会话模型已更新')
  } catch (error: any) {
    ElMessage.error(error.message || '保存模型选择失败')
  }
}

function isImageAttachment(contentType: string) {
  return contentType.startsWith('image/')
}

function imageAttachmentUrls(message: Message) {
  return (message.attachments || [])
    .filter((attachment) => isImageAttachment(attachment.contentType))
    .map((attachment) => attachment.fileUrl)
}

function openImageViewer(images: string[], index = 0) {
  const validImages = images.filter(Boolean)
  if (!validImages.length) return
  imageViewerImages.value = validImages
  imageViewerIndex.value = Math.max(0, Math.min(index, validImages.length - 1))
  imageViewerVisible.value = true
}

function handleImageClick(images: string[], index = 0) {
  if (longPressTriggered.value) {
    longPressTriggered.value = false
    return
  }
  openImageViewer(images, index)
}

function openImageAction(url: string, filename = 'ai-image.png') {
  openImageActionBase(url, filename)
  clearResidualSelection()
  window.setTimeout(() => {
    clearResidualSelection()
    setSelectionSuppressed(false)
  }, 320)
}

function openMessageAction(message: Message) {
  messageActionTarget.value = message
  messageActionVisible.value = true
  clearResidualSelection()
  window.setTimeout(() => {
    clearResidualSelection()
    setSelectionSuppressed(false)
  }, 320)
}

async function handleMessageAction(action: 'copy' | 'edit' | 'resend' | 'download' | 'delete') {
  const message = messageActionTarget.value
  messageActionVisible.value = false
  messageActionTarget.value = null
  if (!message) return
  if (action === 'copy') await copyText(messageText(message))
  else if (action === 'edit') openEdit(message)
  else if (action === 'resend') await resendMessage(message)
  else if (action === 'download' && message.imageUrl) {
    await downloadImage(message.imageUrl, `ai-image-${message.id}.${message.drawFormat || 'png'}`)
  } else if (action === 'delete') await deleteMessage(message)
}

function messageText(message: Message) {
  return message.messageType === 'DRAW_REQUEST'
    ? (message.drawPrompt || message.content.replace(/^绘画提示词：/, ''))
    : message.content
}

function openEdit(message: Message) {
  editTargetId.value = message.id
  editText.value = messageText(message)
  editVisible.value = true
}

async function saveEdit() {
  if (!editingMessage.value || !editText.value.trim()) {
    ElMessage.warning('内容不能为空')
    return
  }
  try {
    await store.editMessage(editingMessage.value.id, editText.value.trim())
    editVisible.value = false
    ElMessage.success('消息已更新')
  } catch (error: any) {
    ElMessage.error(error.message || '保存消息失败')
  }
}

function copyTextViaExecCommand(text: string): boolean {
  const textarea = document.createElement('textarea')
  textarea.value = text
  textarea.setAttribute('readonly', '')
  textarea.style.position = 'fixed'
  textarea.style.top = '0'
  textarea.style.left = '0'
  textarea.style.width = '1px'
  textarea.style.height = '1px'
  textarea.style.padding = '0'
  textarea.style.border = '0'
  textarea.style.outline = '0'
  textarea.style.boxShadow = 'none'
  textarea.style.background = 'transparent'
  textarea.style.opacity = '0'
  document.body.appendChild(textarea)

  const selection = document.getSelection()
  const previousRange = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null

  textarea.focus()
  textarea.select()
  textarea.setSelectionRange(0, textarea.value.length)

  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }

  document.body.removeChild(textarea)
  if (previousRange && selection) {
    selection.removeAllRanges()
    selection.addRange(previousRange)
  } else {
    window.getSelection()?.removeAllRanges()
  }
  return ok
}

async function copyText(text: string, successMessage = '内容已复制') {
  if (!text.trim()) return

  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      window.getSelection()?.removeAllRanges()
      ElMessage.success(successMessage)
      return
    }
  } catch {
    // Fall through to execCommand fallback (PWA / WebView / non-HTTPS).
  }

  if (copyTextViaExecCommand(text)) {
    ElMessage.success(successMessage)
    return
  }

  window.getSelection()?.removeAllRanges()
  ElMessage.error('复制失败，请手动选择复制')
}

async function deleteMessage(message: Message) {
  try {
    await ElMessageBox.confirm('删除此消息及其后续消息？', '确认删除', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消',
    })
    await store.deleteMessage(message.id)
    ElMessage.success('消息已删除')
  } catch {
    // 用户取消操作
  }
}

async function resendMessage(message: Message) {
  if (store.loading) return
  try {
    if (message.role === 'USER') {
      await store.resendUserMessage(message.id, selectedChatProviderId.value, selectedImageProviderId.value)
    } else {
      await store.regenerateMessage(message.id, selectedChatProviderId.value, selectedImageProviderId.value)
    }
    await scrollToBottom()
  } catch (error: any) {
    if (error?.name !== 'CanceledError') ElMessage.error(error.message || '重新生成失败')
  }
}

function formatTime(value: string) {
  return formatTimeHm(value, '')
}

function openComposerExtras() {
  composerExtraVisible.value = true
}

function openReferencePicker() {
  composerExtraVisible.value = false
  if (!historyImages.value.length) {
    ElMessage.info('当前会话还没有可用的历史图片')
    return
  }
  referenceVisible.value = true
}

function openModelPicker() {
  composerExtraVisible.value = false
  modelVisible.value = true
}

function openDrawSettings() {
  composerExtraVisible.value = false
  drawSettingsVisible.value = true
}

function openReferenceShortcut() {
  if (historyImages.value.length) {
    openReferencePicker()
    return
  }
  openComposerExtras()
  ElMessage.info('还没有历史图，可在创作工具中添加参考图')
}

function onComposerFocus() {
  mobileKeyboard?.setComposerFocus()
}

function onComposerBlur() {
  // Keep focus within composer / fullscreen input from flashing chrome.
  // Layout's setComposerBlur already delays and re-checks activeElement.
  mobileKeyboard?.setComposerBlur()
}

watch(() => store.messages.length, () => void scrollToBottom())
watch(() => store.activeSessionId, syncProviderSelection)
watch(inputText, () => void nextTick(() => autoResizeTextarea()))
watch(mode, () => {
  if (mode.value === 'chat' && selectedChatProviderId.value == null) {
    selectedChatProviderId.value = defaultProviderId(store.chatProviders)
  }
  if (mode.value === 'draw' && selectedImageProviderId.value == null) {
    selectedImageProviderId.value = defaultProviderId(store.imageProviders)
  }
  syncDrawOptions()
})
watch([selectedImageProviderId, () => store.imageProviders.length], syncDrawOptions)

watch(
  () => route.params.id,
  async (id) => {
    const sessionId = Number(Array.isArray(id) ? id[0] : id)
    if (!Number.isFinite(sessionId) || sessionId <= 0) return
    if (store.activeSessionId === sessionId && store.selectionTargetId === sessionId) {
      return
    }
    const generation = beginSelection()
    try {
      if (!await selectCurrentSession(sessionId, generation)) return
      syncProviderSelection()
      if (!isCurrentSelection(generation)) return
      await scrollToBottom()
    } catch (error: any) {
      if (!disposed) ElMessage.error(error.message || '加载会话失败')
    }
  },
)

onMounted(() => {
  document.title = 'AI 创作'
  void initialize()
  // Debug sampling only — does not affect keyboard pin / layout.
  bumpDebugSample()
  window.addEventListener('resize', bumpDebugSample)
  window.visualViewport?.addEventListener('resize', bumpDebugSample)
  window.visualViewport?.addEventListener('scroll', bumpDebugSample)
  debugSampleTimer = setInterval(bumpDebugSample, 500)
})

onBeforeUnmount(() => {
  disposed = true
  selectionGeneration += 1
  cancelLongPress(true)
  setSelectionSuppressed(false)
  // Ensure layout bottom-nav returns if this page unmounts while focused.
  mobileKeyboard?.setComposerBlur()
  // Invalidate the store selection even if its request has not committed yet.
  // Polling remains active so terminal updates can refresh sessions and unread.
  store.clearActiveSession()
  document.title = originalTitle
  window.removeEventListener('resize', bumpDebugSample)
  window.visualViewport?.removeEventListener('resize', bumpDebugSample)
  window.visualViewport?.removeEventListener('scroll', bumpDebugSample)
  if (debugSampleTimer != null) {
    clearInterval(debugSampleTimer)
    debugSampleTimer = null
  }
})

/** Debug info — always visible floating overlay. */
function readCssVar(name: string): string {
  if (typeof document === 'undefined') return '?'
  try {
    const val = document.documentElement.style.getPropertyValue(name)
    if (val) return val
    // Fallback: read from .feishu-layout if present
    const layout = document.querySelector('.feishu-layout') as HTMLElement | null
    return layout?.style.getPropertyValue(name) || 'not set'
  } catch { return 'err' }
}

/** Bumps so computed re-reads window / DOM geometry (no keyboard behavior change). */
const debugSampleTick = ref(0)
let debugSampleTimer: ReturnType<typeof setInterval> | null = null

function bumpDebugSample() {
  debugSampleTick.value += 1
}

function matchDisplayMode(mode: string): boolean {
  try {
    return Boolean(window.matchMedia?.(`(display-mode: ${mode})`).matches)
  } catch {
    return false
  }
}

function detectStandalone() {
  try {
    return matchDisplayMode('standalone') || Boolean((navigator as Navigator & { standalone?: boolean }).standalone)
  } catch { return false }
}

function readVirtualKeyboard(): { has: boolean; h: string; overlays: string } {
  try {
    const vk = (navigator as Navigator & {
      virtualKeyboard?: { boundingRect?: DOMRect; overlaysContent?: boolean }
    }).virtualKeyboard
    if (!vk) return { has: false, h: 'N/A', overlays: 'N/A' }
    const h = vk.boundingRect?.height
    return {
      has: true,
      h: typeof h === 'number' && Number.isFinite(h) ? String(Math.round(h)) : '0',
      overlays: typeof vk.overlaysContent === 'boolean' ? String(vk.overlaysContent) : 'N/A',
    }
  } catch {
    return { has: false, h: 'N/A', overlays: 'N/A' }
  }
}

const debugInfo = computed(() => {
  // Depend on tick so geometry re-samples after resize / vv events / interval.
  void debugSampleTick.value

  const sid = store.activeSessionId
  const s = sid != null ? store.sessions.find(item => item.id === sid) : null
  const isStandalone = typeof window !== 'undefined' && detectStandalone()
  const vv = typeof window !== 'undefined' ? window.visualViewport : null
  const layoutEl = typeof document !== 'undefined'
    ? (document.querySelector('.feishu-layout') as HTMLElement | null)
    : null
  const chatEl = typeof document !== 'undefined'
    ? (document.querySelector('.chat-page') as HTMLElement | null)
    : null
  const composerEl = typeof document !== 'undefined'
    ? (document.querySelector('.composer') as HTMLElement | null)
    : null
  const composerBottom = composerEl?.getBoundingClientRect().bottom ?? null
  const vvH = vv?.height
  const gap = (typeof vvH === 'number' && composerBottom != null)
    ? Math.round((vvH - composerBottom) * 10) / 10
    : null
  const vk = readVirtualKeyboard()
  const pwaDiagnostics = typeof window !== 'undefined'
    ? readPwaKeyboardDiagnostics({
        isPwaWorkspace: route.meta.mobileEntry === 'pwa',
        focused: mobileKeyboard?.composerFocused.value === true,
      }, window)
    : null

  const activityMs = s
    ? (parseApiDate(s.lastMessageAt || s.updatedAt)?.getTime() ?? null)
    : null
  const viewedMs = sid != null ? store.getLastViewed(sid) : null
  const gt = activityMs != null && viewedMs != null ? activityMs > viewedMs : null
  const unreadHas = sid != null ? store.unreadSessions.includes(sid) : false

  const nStandalone = (() => {
    try {
      return Boolean((navigator as Navigator & { standalone?: boolean }).standalone)
    } catch { return false }
  })()

  return {
    kbd: `colpsd=${inputChromeCollapsed.value} kbd=${mobileKeyboard?.keyboardOpen.value} focus=${mobileKeyboard?.composerFocused.value}`,
    entry: route.meta.mobileEntry ?? 'unknown',
    mode: isStandalone ? 'standalone' : 'not-standalone',
    vv: `h=${readCssVar('--vv-height')} top=${readCssVar('--vv-offset-top')} ins=${readCssVar('--vv-keyboard-inset')} open=${readCssVar('--vv-keyboard-open')}`,
    dims: `ih=${typeof window !== 'undefined' ? window.innerHeight : '?'} oh=${typeof window !== 'undefined' ? window.outerHeight : '?'} ch=${typeof document !== 'undefined' ? document.documentElement.clientHeight : '?'}`,
    dims2: `vvh=${vv?.height != null ? Math.round(vv.height) : 'N/A'} vvt=${vv?.offsetTop != null ? Math.round(vv.offsetTop) : 'N/A'} sh=${typeof screen !== 'undefined' ? screen.height : '?'} sah=${typeof screen !== 'undefined' ? screen.availHeight : '?'}`,
    dom: `lay=${layoutEl?.clientHeight ?? 'N/A'} chat=${chatEl?.clientHeight ?? 'N/A'} cbot=${composerBottom != null ? Math.round(composerBottom) : 'N/A'} gap=${gap ?? 'N/A'}`,
    api: `sa=${matchDisplayMode('standalone')} fs=${matchDisplayMode('fullscreen')} mu=${matchDisplayMode('minimal-ui')} nsa=${nStandalone} vk=${vk.has} vkh=${vk.h} vko=${vk.overlays}`,
    pwaLocation: pwaDiagnostics?.location ?? 'unavailable',
    pwaReferrer: pwaDiagnostics?.referrer ?? 'unavailable',
    pwaDisplay: `${pwaDiagnostics?.displayMode ?? 'unavailable'} nsa=${pwaDiagnostics?.navigatorStandalone ?? 'unavailable'}`,
    pwaUa: `uad=${pwaDiagnostics?.userAgentData ?? 'unavailable'} kw=${pwaDiagnostics?.userAgentKeywords ?? 'unavailable'}`,
    pwaVirtualKeyboard: pwaDiagnostics?.virtualKeyboard ?? 'unavailable',
    pwaDimensions: pwaDiagnostics?.dimensions ?? 'unavailable',
    pwaEntry: isPwaEntry() ? 'pwa' : (route.meta.mobileEntry === 'feishu' ? 'feishu' : 'mobile'),
    pwaGates: `androidRef=${pwaDiagnostics?.androidAppReferrerGate ?? 'unavailable'} allow=${pwaDiagnostics?.fallbackGate ?? 'unavailable'}`,
    red: `act=${activityMs ?? 'N/A'} view=${viewedMs ?? 'N/A'} gt=${gt ?? 'N/A'} unrd=${unreadHas}`,
    viewed: viewedMs ?? 'N/A',
    lastMsg: s?.lastMessageAt ?? s?.updatedAt ?? 'N/A',
    ex: `msg=${store.messages.length} load=${store.loading} sid=${sid}`,
  }
})
</script>

<template>
  <main
    class="chat-page"
    :class="{ 'keyboard-open': inputChromeCollapsed }"
  >
    <header class="mobile-header">
      <div class="brand-block">
        <button class="back-button" type="button" aria-label="会话列表" title="会话列表" @click="goSessions">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="15 18 9 12 15 6"/></svg>
        </button>
        <span class="brand-icon"><MagicStick /></span>
        <div class="brand-copy">
          <strong>{{ activeSessionTitle }}</strong>
          <span>{{ mode === 'draw' ? 'AI 绘画创作' : 'AI 对话助手' }}</span>
        </div>
      </div>
      <div class="header-actions">
        <button class="header-icon-button" type="button" aria-label="新建会话" title="新建会话" @click="createNewSession"><Plus /></button>
      </div>
    </header>

    <section ref="messagesRef" class="conversation" :class="{ empty: !store.messages.length }">
      <div v-if="initializing" class="center-state">
        <span class="state-orb loading"><RefreshRight /></span>
        <strong>正在准备创作空间</strong>
        <p>正在同步会话与模型配置</p>
      </div>
      <div v-else-if="store.messages.length === 0" class="welcome-card">
        <div class="welcome-heading">
          <span class="welcome-icon">✦</span>
          <div>
            <span class="welcome-label">AI CREATIVE STUDIO</span>
            <h1>今天想创作什么？</h1>
          </div>
        </div>
        <p>输入画面描述或上传参考图，也可以切换到对话模式，让 AI 帮你完善灵感。</p>
        <div class="quick-prompts">
          <button type="button" @click="inputText = '一只橘猫坐在窗边晒太阳，电影感光影，细腻插画'">
            <span>柔和插画</span><strong>橘猫与午后阳光</strong><ArrowRight />
          </button>
          <button type="button" @click="inputText = '未来城市的夜景，霓虹灯与雨幕，赛博朋克风格'">
            <span>概念场景</span><strong>赛博朋克夜景</strong><ArrowRight />
          </button>
          <button type="button" @click="inputText = '水墨山水，云雾缭绕，留白构图，中国传统美学'">
            <span>东方美学</span><strong>云雾水墨山水</strong><ArrowRight />
          </button>
        </div>
      </div>
      <article v-for="message in store.messages" :key="message.id" class="message-card" :class="message.role.toLowerCase()">
        <div class="message-meta">
          <span>{{ message.role === 'USER' ? '我' : 'AI 创作助手' }}</span>
          <time>{{ formatTime(message.createdAt) }}</time>
          <div v-if="message.status !== 'PENDING'" class="message-actions">
            <button v-if="message.content" type="button" title="复制内容" aria-label="复制内容" @click="copyText(messageText(message))"><CopyDocument /></button>
            <button v-if="message.role === 'USER'" type="button" title="编辑消息" aria-label="编辑消息" @click="openEdit(message)"><EditPen /></button>
            <button type="button" :title="message.role === 'USER' ? '再次发送' : '重新生成'" :aria-label="message.role === 'USER' ? '再次发送' : '重新生成'" @click="resendMessage(message)"><RefreshRight /></button>
            <button v-if="message.imageUrl" type="button" title="下载图片" aria-label="下载图片" @click="downloadImage(message.imageUrl || '')"><Download /></button>
            <button type="button" title="删除消息" aria-label="删除消息" @click="deleteMessage(message)"><Delete /></button>
          </div>
        </div>
        <div class="message-bubble" @touchstart="startLongPress($event, () => openMessageAction(message))" @touchmove="moveLongPress" @touchend="finishLongPress" @touchcancel="cancelLongPress(true)" @contextmenu.prevent.stop="openMessageAction(message)">
          <div v-if="message.attachments?.length" class="message-attachments">
            <template v-for="attachment in message.attachments" :key="attachment.id">
              <button
                v-if="isImageAttachment(attachment.contentType)"
                type="button"
                class="message-image-trigger"
                aria-label="查看图片"
                @click.stop="handleImageClick(imageAttachmentUrls(message), imageAttachmentUrls(message).indexOf(attachment.fileUrl))"
                @touchstart.stop="startLongPress($event, () => openImageAction(attachment.fileUrl, attachment.originalName || 'image.png'))"
                @touchmove.stop="moveLongPress"
                @touchend.stop="finishLongPress"
                @touchcancel.stop="cancelLongPress(true)"
                @contextmenu.prevent.stop="openImageAction(attachment.fileUrl, attachment.originalName || 'image.png')"
              >
                <img :src="attachment.fileUrl" alt="消息图片" loading="lazy">
              </button>
              <a v-else class="message-file" :href="attachment.fileUrl" target="_blank" rel="noopener"><Paperclip /> {{ attachment.originalName }}</a>
            </template>
          </div>
          <CollapsibleMessageText v-if="messageText(message) && message.status !== 'PENDING'" class="message-content" :content="messageText(message)" />
          <div v-if="message.status === 'PENDING'" class="message-loading" role="status" aria-live="polite">
            <span class="message-loading-dots" aria-hidden="true"><i></i><i></i><i></i></span>
            <em>{{ mode === 'draw' || message.messageType === 'DRAW_RESPONSE' || message.messageType === 'DRAW_REQUEST' ? '正在生成…' : '正在思考…' }}</em>
          </div>
          <button
            v-if="message.imageUrl"
            type="button"
            class="result-image mobile-image-trigger"
            aria-label="查看生成图片"
            @click.stop="handleImageClick(generatedImages.map((item) => item.imageUrl || ''), generatedImages.findIndex((item) => item.id === message.id))"
            @touchstart.stop="startLongPress($event, () => openImageAction(message.imageUrl || '', `ai-image-${message.id}.${message.drawFormat || 'png'}`))"
            @touchmove.stop="moveLongPress"
            @touchend.stop="finishLongPress"
            @touchcancel.stop="cancelLongPress(true)"
            @contextmenu.prevent.stop="openImageAction(message.imageUrl || '', `ai-image-${message.id}.${message.drawFormat || 'png'}`)"
          >
            <img :src="messageDisplayUrl(message)" alt="AI 生成图片" loading="lazy" @error="onMessageThumbError(message.id)">
          </button>
          <p v-if="message.status === 'FAILED'" class="error-text">{{ message.errorMessage || '请求失败，请稍后重试' }}</p>
        </div>
      </article>
    </section>

    <div
      v-if="(store.loading || store.canCancel)"
      class="operation-bar"
      role="status"
      aria-live="polite"
    >
      <span class="pulse-dot" aria-hidden="true"></span>
      <span class="operation-bar-text">{{ store.operationStage || (mode === 'draw' ? '正在生成图片…' : '正在等待模型回应…') }}</span>
      <button v-if="store.canCancel" type="button" @click="store.cancelActiveRequest">终止</button>
    </div>

    <footer class="composer">
      <div class="mode-toggle-bar">
        <button :class="{ active: mode === 'draw' }" type="button" @click="mode = 'draw'"><Picture /> 绘画</button>
        <button :class="{ active: mode === 'chat' }" type="button" @click="mode = 'chat'"><ChatDotRound /> 对话</button>
      </div>
      <section v-if="mode === 'draw'" class="draw-status-bar" aria-label="当前绘画设置">
        <button type="button" class="draw-status-chip draw-status-model" :title="selectedProviderLabel" :aria-label="`当前绘画模型：${selectedProviderLabel}，点击切换模型`" @click="openModelPicker">
          <MagicStick aria-hidden="true" />
          <span>模型</span>
          <strong>{{ selectedProviderLabel }}</strong>
        </button>
        <button type="button" class="draw-status-chip" :title="`当前尺寸或比例：${drawSize}`" :aria-label="`当前尺寸或比例：${drawSize}，点击设置`" @click="openDrawSettings">
          <span>尺寸</span>
          <strong>{{ drawSize }}</strong>
        </button>
        <button type="button" class="draw-status-chip" :title="`当前质量：${drawQuality}`" :aria-label="`当前质量：${drawQuality}，点击设置`" @click="openDrawSettings">
          <span>质量</span>
          <strong>{{ drawQuality }}</strong>
        </button>
        <button type="button" class="draw-status-chip" :title="referenceImageCount ? `已添加 ${referenceImageCount} 张参考图` : '暂无参考图'" :aria-label="referenceImageCount ? `已添加 ${referenceImageCount} 张参考图，点击管理参考图` : '暂无参考图，点击添加'" @click="openReferenceShortcut">
          <Picture aria-hidden="true" />
          <span>参考图</span>
          <strong>{{ referenceImageCount || '未添加' }}</strong>
        </button>
      </section>
      <div v-if="pendingAttachments.length" class="attachment-strip">
        <div v-for="attachment in pendingAttachments" :key="attachment.id" class="attachment-preview">
          <el-image v-if="isImageAttachment(attachment.contentType)" :src="attachment.fileUrl" fit="cover" />
          <div v-else class="attachment-file-icon"><Paperclip /><small>{{ attachment.originalName }}</small></div>
          <button type="button" aria-label="移除附件" @click="removeAttachment(attachment.id)"><Close /></button>
        </div>
      </div>
      <div class="composer-main">
        <input ref="fileInputRef" type="file" :accept="mode === 'draw' ? 'image/*' : 'image/*,.pdf,.doc,.docx,.txt'" multiple hidden @change="handleFileChange">
        <button class="upload-button" type="button" :disabled="store.loading || uploading" aria-label="更多创作选项" @click="openComposerExtras"><Plus /></button>
        <textarea
          ref="inputRef"
          v-model="inputText"
          :disabled="store.loading"
          :placeholder="mode === 'draw' ? '描述你想生成的画面…' : '输入消息，或输入 /help 查看命令…'"
          rows="1"
          enterkeyhint="enter"
          @paste="handlePaste"
          @keydown="handleInputKeydown"
          @input="autoResizeTextarea"
          @focus="onComposerFocus"
          @blur="onComposerBlur"
        ></textarea>
        <button class="send-button" :class="{ disabled: !canSubmit }" type="button" :disabled="!canSubmit" :aria-label="mode === 'draw' ? '生成图片' : '发送消息'" @click="handleSubmit"><span>{{ mode === 'draw' ? '生成' : '发送' }}</span></button>
      </div>
      <p class="composer-hint">{{ mode === 'draw' ? '点“+”添加参考图、切换模型和设置参数' : '点“+”添加附件、切换对话模型' }}</p>
    </footer>

    <div v-if="fullscreenInput" class="fullscreen-input-overlay">
      <div class="fullscreen-input-header">
        <span class="fullscreen-input-title">{{ mode === 'draw' ? '输入绘画描述' : '输入消息' }}</span>
        <button type="button" class="fullscreen-input-exit" aria-label="退出全屏" @click="toggleFullscreenInput"><Close /></button>
      </div>
      <textarea
        ref="inputRef"
        v-model="inputText"
        class="fullscreen-textarea"
        :placeholder="mode === 'draw' ? '描述你想生成的画面…' : '输入消息，或输入 /help 查看命令…'"
        @input="autoResizeTextarea"
        @focus="onComposerFocus"
        @blur="onComposerBlur"
      ></textarea>
      <div class="fullscreen-input-footer">
        <button class="send-button" :class="{ disabled: !canSubmit }" type="button" :disabled="!canSubmit" @click="handleSubmit"><Promotion /> 发送</button>
      </div>
    </div>

    <!--
      Chat-page pattern (WeChat/Feishu): when the composer is focused / keyboard is
      open, drop bottom-nav entirely so the input is the last flex child and sits
      flush above the keyboard. Nav returns on blur / keyboard close.
    -->

    <el-drawer v-model="composerExtraVisible" direction="btt" size="auto" class="h5-drawer composer-extra-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>创作工具</strong><span>附件、参考图、模型和参数都在这里</span></div></div>
      <div class="composer-extra-grid">
        <button type="button" @click="openFilePicker"><UploadFilled /><span>添加附件</span><small>{{ mode === 'draw' ? '上传参考图' : '图片或文档' }}</small></button>
        <button type="button" :disabled="!historyImages.length" @click="openReferencePicker"><Clock /><span>历史图</span><small>选择已有作品</small></button>
        <button type="button" @click="openModelPicker"><MagicStick /><span>模型</span><small>{{ selectedProviderLabel }}</small></button>
        <button type="button" @click="toggleFullscreenInput(); composerExtraVisible = false"><FullScreen /><span>扩展输入</span><small>全屏编辑内容</small></button>
      </div>
      <div class="mode-picker">
        <span>创作模式</span>
        <div class="mode-switch" aria-label="创作模式">
          <button :class="{ active: mode === 'draw' }" type="button" @click="mode = 'draw'"><Picture /> 绘画</button>
          <button :class="{ active: mode === 'chat' }" type="button" @click="mode = 'chat'"><ChatDotRound /> 对话</button>
        </div>
      </div>
      <div v-if="mode === 'draw'" class="drawer-draw-options">
        <span>绘画参数</span>
        <div class="draw-options-inline-fields">
          <label><span>尺寸</span><el-select v-model="drawSize"><el-option v-for="option in drawSizeOptions" :key="option" :label="option" :value="option" /></el-select></label>
          <label><span>质量</span><el-select v-model="drawQuality"><el-option v-for="option in drawQualityOptions" :key="option" :label="option.toUpperCase()" :value="option" /></el-select></label>
          <label><span>格式</span><el-select v-model="drawFormat"><el-option v-for="option in drawFormatOptions" :key="option" :label="option.toUpperCase()" :value="option" /></el-select></label>
        </div>
      </div>
    </el-drawer>

    <el-drawer v-model="drawSettingsVisible" direction="btt" size="auto" class="h5-drawer draw-settings-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>绘画设置</strong><span>快速调整尺寸、质量与格式</span></div></div>
      <div class="draw-settings-fields">
        <label><span>尺寸 / 比例</span><el-select v-model="drawSize" aria-label="绘画尺寸或比例"><el-option v-for="option in drawSizeOptions" :key="option" :label="option" :value="option" /></el-select></label>
        <label><span>质量</span><el-select v-model="drawQuality" aria-label="绘画质量"><el-option v-for="option in drawQualityOptions" :key="option" :label="option.toUpperCase()" :value="option" /></el-select></label>
        <label><span>格式</span><el-select v-model="drawFormat" aria-label="图片格式"><el-option v-for="option in drawFormatOptions" :key="option" :label="option.toUpperCase()" :value="option" /></el-select></label>
      </div>
    </el-drawer>

    <el-drawer v-model="referenceVisible" direction="btt" size="auto" class="h5-drawer reference-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>选择历史图片</strong><span>添加到当前绘画的参考图</span></div></div>
      <div class="history-reference-grid">
        <button v-for="item in historyImages" :key="item.id" type="button" class="history-reference-tile" :disabled="referenceImportingId != null || store.loading" @click="selectHistoryImage(item)">
          <el-image :src="historyDisplayUrl(item)" fit="cover" @error="onHistoryThumbError(item.id)" />
          <span v-if="referenceImportingId === item.messageId" class="history-reference-status">添加中…</span>
          <small>{{ item.label }}</small>
        </button>
      </div>
    </el-drawer>

    <el-drawer v-model="modelVisible" direction="btt" size="68%" class="h5-drawer model-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>选择{{ mode === 'draw' ? '绘画' : '对话' }}模型</strong><span>模型选择会保存到当前会话</span></div></div>
      <div class="model-list">
        <button class="model-row" :class="{ active: selectedProviderId === null }" type="button" @click="selectModel(null)"><span><strong>系统默认模型</strong><small>使用后台配置的默认模型</small></span><span v-if="selectedProviderId === null" class="model-check" aria-hidden="true"><Check /></span></button>
        <button v-for="provider in currentProviders" :key="provider.id" class="model-row" :class="{ active: provider.id === selectedProviderId }" type="button" @click="selectModel(provider.id)"><span><strong>{{ provider.name || provider.providerId }}</strong><small>#{{ provider.id }} · {{ provider.modelName }}</small></span><span v-if="provider.id === selectedProviderId" class="model-check" aria-hidden="true"><Check /></span></button>
      </div>
    </el-drawer>

    <el-drawer v-model="editVisible" direction="btt" size="auto" class="h5-drawer edit-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>编辑消息</strong><span>保存后将更新当前消息内容</span></div></div>
      <textarea v-model="editText" class="edit-textarea" rows="5" placeholder="请输入消息内容…"></textarea>
      <div class="edit-footer"><button type="button" @click="editVisible = false">取消</button><button type="button" class="primary" @click="saveEdit">保存</button></div>
    </el-drawer>

    <el-drawer v-model="imageActionVisible" direction="btt" size="auto" class="h5-drawer action-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>图片操作</strong><span>长按图片即可再次打开此菜单</span></div></div>
      <div class="action-list">
        <button type="button" @click="downloadImageAction"><Download /><span>下载图片</span></button>
        <button type="button" @click="imageActionVisible = false"><Close /><span>取消</span></button>
      </div>
    </el-drawer>

    <el-drawer v-model="messageActionVisible" direction="btt" size="auto" class="h5-drawer action-drawer" :with-header="false">
      <div class="drawer-title compact"><div><strong>消息操作</strong><span>长按消息气泡即可打开</span></div></div>
      <div v-if="messageActionTarget" class="action-list">
        <button v-if="messageActionTarget.content" type="button" @click="handleMessageAction('copy')"><CopyDocument /><span>复制内容</span></button>
        <button v-if="messageActionTarget.role === 'USER'" type="button" @click="handleMessageAction('edit')"><EditPen /><span>编辑消息</span></button>
        <button type="button" @click="handleMessageAction('resend')"><RefreshRight /><span>{{ messageActionTarget.role === 'USER' ? '再次发送' : '重新生成' }}</span></button>
        <button v-if="messageActionTarget.imageUrl" type="button" @click="handleMessageAction('download')"><Download /><span>下载图片</span></button>
        <button type="button" class="danger-action" @click="handleMessageAction('delete')"><Delete /><span>删除消息</span></button>
      </div>
    </el-drawer>

    <Teleport to="body">
      <Transition name="save-helper-fade">
        <div v-if="saveHelperVisible" class="save-image-helper" role="dialog" aria-modal="true" aria-label="保存图片">
          <div class="save-image-helper-backdrop" @click="closeSaveHelper"></div>
          <div class="save-image-helper-panel">
            <header>
              <strong>保存图片</strong>
              <button type="button" aria-label="关闭" @click="closeSaveHelper"><Close /></button>
            </header>
            <p class="save-image-helper-tip">部分移动端 / 内置浏览器不支持直接下载。请<strong>长按下方图片</strong>，在弹出菜单中选择“保存图片 / 存储到相册”。也可尝试“系统分享”。</p>
            <div class="save-image-helper-preview">
              <img :src="saveHelperUrl" :alt="saveHelperFilename" draggable="false">
            </div>
            <div class="save-image-helper-actions">
              <button type="button" class="primary" @click="shareFromHelper">系统分享</button>
              <button type="button" @click="copyText(saveHelperUrl)">复制图片链接</button>
              <button type="button" @click="closeSaveHelper">关闭</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
    <MobileImageViewer v-model:visible="imageViewerVisible" :images="imageViewerImages" :initial-index="imageViewerIndex" />
    <!-- Debug overlay — always visible on top -->
    <div class="debug-overlay">
      <div><code>kbd: {{ debugInfo.kbd }}</code></div>
      <div><code>entry: {{ debugInfo.entry }}  mode: {{ debugInfo.mode }}  vv: {{ debugInfo.vv }}</code></div>
      <div><code>win: {{ debugInfo.dims }}</code></div>
      <div><code>vv2: {{ debugInfo.dims2 }}</code></div>
      <div><code>dom: {{ debugInfo.dom }}</code></div>
      <div><code>api: {{ debugInfo.api }}</code></div>
      <div><code>pwa.loc: {{ debugInfo.pwaLocation }}</code></div>
      <div><code>pwa.ref: {{ debugInfo.pwaReferrer }} mode: {{ debugInfo.pwaDisplay }}</code></div>
      <div><code>pwa.ua: {{ debugInfo.pwaUa }}</code></div>
      <div><code>pwa.vk: {{ debugInfo.pwaVirtualKeyboard }}</code></div>
      <div><code>pwa.dim: {{ debugInfo.pwaDimensions }}</code></div>
      <div><code>pwa.entry: {{ debugInfo.pwaEntry }}</code></div>
      <div><code>pwa.gate: {{ debugInfo.pwaGates }}</code></div>
      <div><code>red: {{ debugInfo.red }}</code></div>
      <div><code>viewed: {{ debugInfo.viewed }}  lastMsg: {{ debugInfo.lastMsg }}</code></div>
      <div><code>{{ debugInfo.ex }}</code></div>
    </div>
  </main>
</template>

<style scoped>

.back-button {
  display: grid;
  flex: 0 0 auto;
  width: 36px;
  height: 36px;
  padding: 0;
  place-items: center;
  color: #5365cc;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #eef1ff;
}
.back-button svg { display: block; }

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

.brand-block { display: flex; min-width: 0; align-items: center; gap: 11px; }
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
.brand-copy { display: flex; min-width: 0; flex-direction: column; line-height: 1.2; }
.brand-copy strong { max-width: 52vw; overflow: hidden; color: #26334e; font-size: 16px; letter-spacing: -.2px; text-overflow: ellipsis; white-space: nowrap; }
.brand-copy span { margin-top: 4px; color: #8c97aa; font-size: 11px; font-weight: 600; }
.header-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }
.header-icon-button {
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
.header-icon-button:disabled { cursor: not-allowed; opacity: .5; }

.chat-page {
  --mobile-primary: #4f67e8;
  --mobile-primary-deep: #3d51c7;
  --mobile-text: #24314d;
  --mobile-muted: #7d899f;
  --mobile-border: #e5e9f2;
  /*
   * Nested inside FeishuMobileLayout's layout-content. Layout owns the fixed
   * shell + visualViewport geometry (pinShellToVisualViewport).
   *
   * Absolute-fill (not position:fixed, not height:100% alone):
   *  - A second position:fixed layer would ignore the parent shell's height
   *    shrink and leave the composer under overlay keyboards (Android WebAPK).
   *  - height:100% is flaky when the parent only has an intrinsic flex size;
   *    absolute inset:0 tracks the parent's concrete box as pinShell rewrites
   *    shell height when the soft keyboard opens in PWA standalone.
   * Feishu / in-app WebViews already resize the outer shell correctly, so the
   * same absolute-fill chain works there without a standalone-only branch.
   */
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  min-height: 0;
  min-width: 0;
  height: auto;
  max-height: none;
  box-sizing: border-box;
  /* Keep page non-scrolling; conversation is the scroll container. */
  overflow: hidden;
  color: var(--mobile-text);
  background:
    radial-gradient(circle at 95% -5%, rgba(106, 90, 238, .12), transparent 24rem),
    linear-gradient(180deg, #f7f9fd 0%, #f2f5fa 100%);
}
/*
 * Keyboard / composer-focus mode:
 *  - bottom-nav is removed from the layout (hideBottomNav)
 *  - composer is the last flex child → sits flush above the keyboard
 *  - drop safe-area bottom padding: the shell is already pinned above the
 *    keyboard, so env(safe-area-inset-bottom) would double-pad and push the
 *    input under the keyboard on some Android WebAPKs.
 */
.chat-page.keyboard-open .composer {
  padding-bottom: 8px;
}
.chat-page.keyboard-open .composer-hint {
  display: none;
}
.chat-page.keyboard-open .mode-toggle-bar,
.chat-page.keyboard-open .draw-status-bar {
  /* Reclaim vertical space while typing on short viewports. */
  display: none;
}
.chat-page.keyboard-open .conversation {
  /* Conversation is the only scroll container; keep it shrinkable. */
  min-height: 0;
  flex: 1 1 auto;
}

.conversation, .gallery-panel {
  min-height: 0;
  flex: 1;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
}
.conversation {
  display: flex;
  flex-direction: column;
  padding: 18px 14px 12px;
  scroll-behavior: smooth;
}
.conversation.empty { justify-content: center; }

.welcome-card {
  width: min(100%, 620px);
  margin: auto;
  padding: 22px 18px 18px;
  border: 1px solid rgba(225, 230, 242, .96);
  border-radius: 24px;
  background: rgba(255, 255, 255, .88);
  box-shadow: 0 16px 38px rgba(48, 62, 108, .08);
  backdrop-filter: blur(12px);
}
.welcome-heading { display: flex; align-items: center; gap: 13px; margin-bottom: 12px; }
.welcome-icon {
  display: grid;
  flex: 0 0 auto;
  width: 50px;
  height: 50px;
  place-items: center;
  color: #fff;
  font-size: 26px;
  border-radius: 17px;
  background: linear-gradient(145deg, #536cf0, #9b5ae4);
  box-shadow: 0 10px 22px rgba(87, 91, 218, .22);
}
.welcome-label { color: #6375d9; font-size: 9px; font-weight: 800; letter-spacing: .13em; }
.welcome-card h1 { margin: 3px 0 0; color: #25324e; font-size: 22px; line-height: 1.25; letter-spacing: -.5px; }
.welcome-card > p { margin: 0; color: #7f8a9f; font-size: 13px; line-height: 1.65; }
.quick-prompts { display: grid; gap: 9px; margin-top: 18px; }
.quick-prompts button {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  min-height: 58px;
  padding: 10px 12px;
  color: #59657d;
  text-align: left;
  cursor: pointer;
  border: 1px solid #e7eaf3;
  border-radius: 14px;
  background: #f9faff;
}
.quick-prompts button span, .quick-prompts button strong { grid-column: 1; }
.quick-prompts button span { color: #98a0b1; font-size: 10px; font-weight: 700; }
.quick-prompts button strong { margin-top: 2px; color: #44506a; font-size: 13px; }
.quick-prompts button > svg { grid-column: 2; grid-row: 1 / 3; width: 16px; align-self: center; color: #7b89dd; }

.message-card { width: fit-content; max-width: min(92%, 680px); margin: 0 0 16px; align-self: flex-start; }
.message-meta { display: flex; min-width: 0; flex-wrap: wrap; align-items: center; gap: 7px; margin: 0 4px 6px; color: #a2aabd; font-size: 10px; }
.message-meta > span { color: #6c7890; font-weight: 800; }
.message-meta time { color: #a6aec0; }
.message-bubble {
  min-width: 54px;
  padding: 12px 13px;
  border: 1px solid #e6eaf2;
  border-radius: 7px 18px 18px 18px;
  background: rgba(255, 255, 255, .96);
  box-shadow: 0 7px 20px rgba(42, 55, 97, .055);
}
.message-card.user .message-bubble {
  color: #fff;
  border: 0;
  border-radius: 18px 18px 7px 18px;
  background: linear-gradient(140deg, #536ceb, #7759d5);
  box-shadow: 0 9px 22px rgba(72, 76, 190, .18);
}
.message-content { margin: 0; font-size: 13px; line-height: 1.65; white-space: pre-wrap; word-break: break-word; }
.message-attachments { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 9px; }
.message-attachments :deep(.el-image), .message-image-trigger { width: 76px; height: 76px; overflow: hidden; border-radius: 11px; }
.message-image-trigger, .gallery-image-trigger { display: block; padding: 0; cursor: pointer; border: 0; background: transparent; }
.message-image-trigger img, .gallery-image-trigger img { display: block; width: 100%; height: 100%; object-fit: cover; }
.message-file { display: inline-flex; align-items: center; gap: 6px; max-width: 100%; padding: 7px 9px; overflow: hidden; color: #5366d3; font-size: 11px; text-decoration: none; text-overflow: ellipsis; white-space: nowrap; border: 1px solid #e3e7f5; border-radius: 9px; background: #f7f8ff; }
.message-card.user .message-file { color: #fff; border-color: rgba(255, 255, 255, .25); background: rgba(255, 255, 255, .12); }
.message-loading {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  gap: 8px;
  padding: 4px 2px;
  color: #6d7893;
  font-size: 12px;
  font-style: normal;
  font-weight: 650;
}
.message-loading em { font-style: normal; }
.message-loading-dots { display: inline-flex; gap: 4px; }
.message-loading-dots i {
  display: block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7f8db0;
  animation: bounce 1.2s infinite ease-in-out;
}
.message-loading-dots i:nth-child(2) { animation-delay: .15s; }
.message-loading-dots i:nth-child(3) { animation-delay: .3s; }
.result-image { position: relative; width: min(100%, 480px); margin-top: 10px; overflow: hidden; border: 0; border-radius: 13px; background: #f1f3f8; }
.result-image img { display: block; width: 100%; max-height: 60vh; object-fit: contain; }
.error-text { margin: 8px 0 0; color: #e15d6d; font-size: 11px; }
.message-actions { display: inline-flex; align-items: center; gap: 1px; margin-left: auto; opacity: 1; }
.message-actions button {
  display: grid;
  width: 27px;
  height: 27px;
  padding: 0;
  place-items: center;
  color: #7e89a3;
  cursor: pointer;
  border: 0;
  border-radius: 8px;
  background: transparent;
}
.message-actions button:active { color: #5366da; background: #edf0ff; }

.gallery-panel { padding: 17px 14px 20px; }
.section-heading { display: flex; align-items: flex-end; justify-content: space-between; max-width: 820px; margin: 0 auto 14px; }
.section-heading > div { display: flex; flex-direction: column; gap: 2px; }
.section-heading span { color: #8591a9; font-size: 10px; font-weight: 800; letter-spacing: .12em; }
.section-heading strong { color: #2e3b58; font-size: 20px; }
.section-heading small { padding: 4px 9px; color: #5a6bd0; font-size: 11px; font-weight: 800; border-radius: 999px; background: #e9edff; }
.image-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; max-width: 820px; margin: 0 auto; }
.image-tile { min-width: 0; overflow: hidden; border: 1px solid #e4e8f1; border-radius: 16px; background: #fff; box-shadow: 0 8px 20px rgba(47, 60, 101, .06); }
.image-tile > .gallery-image-trigger { display: block; width: 100%; aspect-ratio: 1; background: #eef1f7; }
.image-info { padding: 9px; }
.image-tile time { color: #9ca5b7; font-size: 9px; }
.image-tile p { display: -webkit-box; min-height: 34px; margin: 4px 0 8px; overflow: hidden; color: #5c6780; font-size: 11px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.gallery-actions { display: flex; gap: 6px; }
.gallery-actions button { display: inline-flex; flex: 1; min-width: 0; min-height: 32px; align-items: center; justify-content: center; gap: 4px; padding: 4px 6px; color: #65718e; font-size: 10px; cursor: pointer; border: 0; border-radius: 9px; background: #f1f3f9; }
.gallery-long-press-tip { display: inline-flex; flex: 1; min-width: 0; min-height: 32px; align-items: center; justify-content: center; color: #a0a8b8; font-size: 10px; border-radius: 9px; background: #f7f8fb; }

.center-state { display: flex; min-height: 180px; flex-direction: column; align-items: center; justify-content: center; gap: 8px; color: #5e6c86; text-align: center; }
.center-state p { margin: 0; color: #969fb1; font-size: 12px; }
.state-orb { display: grid; width: 52px; height: 52px; margin-bottom: 3px; place-items: center; color: #6275db; font-size: 23px; border-radius: 17px; background: #e9edff; }
.state-orb.loading { animation: spin 1s linear infinite; }
.gallery-empty { height: calc(100% - 58px); }
.gallery-empty button { min-height: 38px; margin-top: 7px; padding: 0 16px; color: #fff; font-size: 12px; font-weight: 700; cursor: pointer; border: 0; border-radius: 11px; background: var(--mobile-primary); }

.operation-bar {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  margin: 0 12px 8px;
  padding: 10px 12px;
  color: #6f5a16;
  font-size: 12px;
  font-weight: 650;
  border: 1px solid #f0d48a;
  border-radius: 14px;
  background: linear-gradient(180deg, #fff8e8, #fffaf0);
  box-shadow: 0 4px 14px rgba(180, 130, 20, .08);
}
.operation-bar-text { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.operation-bar .pulse-dot { flex: 0 0 auto; width: 8px; height: 8px; border-radius: 50%; background: #e8a01b; animation: pulse 1s infinite ease-in-out; }
.operation-bar button {
  flex: 0 0 auto;
  min-height: 30px;
  margin-left: 2px;
  padding: 0 10px;
  color: #b36d00;
  font-size: 12px;
  font-weight: 800;
  cursor: pointer;
  border: 1px solid #f0d48a;
  border-radius: 999px;
  background: #fff;
}
.account-tip {
  margin: 0 0 4px;
  padding: 10px 12px;
  color: #6d7890;
  font-size: 12px;
  line-height: 1.55;
  border-radius: 12px;
  background: #eef3ff;
}

.mode-toggle-bar {
  display: flex;
  gap: 4px;
  margin: 0 0 8px;
  padding: 3px;
  border-radius: 11px;
  background: #f0f2f7;
}
.mode-toggle-bar button {
  display: inline-flex;
  flex: 1;
  min-height: 30px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 8px;
  color: #7e899e;
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 8px;
  background: transparent;
}
.mode-toggle-bar button.active {
  color: #4e62d2;
  background: #fff;
  box-shadow: 0 2px 7px rgba(62, 76, 133, .1);
}
.mode-toggle-bar button svg { width: 16px; height: 16px; }
.composer { position: relative; z-index: 8; flex: 0 0 auto; padding: 8px 12px 2px; border-top: 1px solid rgba(223, 228, 239, .94); background: rgba(255, 255, 255, .94); box-shadow: 0 -8px 24px rgba(42, 54, 93, .055); backdrop-filter: blur(18px); }
.composer-toolbar { display: flex; min-width: 0; align-items: center; gap: 4px; margin-bottom: 6px; }
.draw-status-bar { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; margin: 0 0 8px; }
.draw-status-chip { display: grid; min-width: 0; min-height: 44px; align-content: center; gap: 1px; padding: 6px 8px; color: #748097; text-align: left; cursor: pointer; border: 1px solid #e1e6f1; border-radius: 12px; background: #f6f8fc; }
.draw-status-chip > svg { width: 15px; height: 15px; grid-row: span 2; align-self: center; color: #6177db; }
.draw-status-chip > span { overflow: hidden; color: #8b95a7; font-size: 10px; font-weight: 700; line-height: 1.2; text-overflow: ellipsis; white-space: nowrap; }
.draw-status-chip > strong { overflow: hidden; color: #53617b; font-size: 11px; font-weight: 750; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.draw-status-model { display: grid; grid-template-columns: auto minmax(0, 1fr); grid-column: 1 / -1; column-gap: 6px; }
.draw-status-model > span, .draw-status-model > strong { grid-column: 2; }
.draw-status-model > strong { max-width: 100%; }
.draw-settings-fields { display: grid; gap: 12px; padding: 14px 0 4px; }
.draw-settings-fields label { display: grid; grid-template-columns: 86px minmax(0, 1fr); min-height: 44px; align-items: center; gap: 10px; color: #65718a; font-size: 12px; font-weight: 700; }
.draw-settings-fields :deep(.el-select) { width: 100%; }
.draw-settings-fields :deep(.el-input__wrapper) { min-height: 44px; border-radius: 12px; box-shadow: 0 0 0 1px #e1e6ef inset; }
.draw-options-inline { margin: -2px 0 8px; padding: 0 2px; }
.draw-options-summary { display: flex; width: 100%; min-height: 29px; align-items: center; gap: 7px; padding: 0 7px; color: #77829a; font-size: 10px; text-align: left; cursor: pointer; border: 0; border-radius: 8px; background: #f5f7fb; }
.draw-options-summary strong { min-width: 0; overflow: hidden; color: #596681; font-size: 10px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.draw-options-summary i { margin-left: auto; color: #8d98ac; font-size: 16px; font-style: normal; line-height: 1; transform: translateY(-2px); transition: transform .18s ease; }
.draw-options-summary i.expanded { transform: rotate(180deg) translateY(2px); }
.draw-options-inline-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; padding: 7px 2px 0; }
.draw-options-inline-fields label { display: flex; min-width: 0; flex-direction: column; gap: 3px; color: #8b95a8; font-size: 9px; font-weight: 700; }
.draw-options-inline-fields :deep(.el-select) { width: 100%; }
.draw-options-inline-fields :deep(.el-input__wrapper) { min-height: 29px; padding: 0 7px; border-radius: 8px; box-shadow: 0 0 0 1px #e1e6ef inset; }
.draw-options-inline-fields :deep(.el-input__inner) { font-size: 11px; }
.mode-switch { display: flex; flex: 0 0 auto; padding: 3px; border-radius: 11px; background: #f0f2f7; }
.mode-switch button, .tool-button, .model-trigger { display: inline-flex; min-height: 28px; align-items: center; justify-content: center; gap: 4px; padding: 0 6px; color: #7e899e; font-size: 10px; font-weight: 700; cursor: pointer; border: 0; border-radius: 8px; background: transparent; }
.mode-switch button.active { color: #4e62d2; background: #fff; box-shadow: 0 2px 7px rgba(62, 76, 133, .1); }
.tool-button { flex: 0 0 auto; padding: 0 6px; color: #68758c; background: #f3f5f9; }
.model-trigger { min-width: 0; max-width: 190px; margin-left: auto; padding: 0 7px 0 9px; color: #65718a; background: #f3f5f9; }
.model-trigger span { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-chevron { width: 6px; height: 6px; flex: 0 0 auto; margin: -3px 2px 0 3px; border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; opacity: .72; transform: rotate(45deg); }
.attachment-strip { display: flex; gap: 8px; margin: 0 0 8px; padding-top: 3px; overflow-x: auto; }
.attachment-preview { position: relative; flex: 0 0 auto; width: 50px; height: 50px; }
.attachment-preview :deep(.el-image) { display: block; width: 50px; height: 50px; overflow: hidden; border: 1px solid #e0e5ef; border-radius: 11px; }
.attachment-preview > button { position: absolute; top: -5px; right: -5px; display: grid; width: 19px; height: 19px; padding: 0; place-items: center; color: #fff; cursor: pointer; border: 2px solid #fff; border-radius: 50%; background: #5e6b8c; }
.attachment-preview > button :deep(svg) { width: 10px; }
.attachment-file-icon { display: flex; width: 50px; height: 50px; align-items: center; justify-content: center; flex-direction: column; gap: 2px; overflow: hidden; color: #6372d4; border: 1px solid #e3e7f4; border-radius: 11px; background: #f1f3ff; }
.attachment-file-icon small { max-width: 42px; overflow: hidden; color: #77819a; font-size: 7px; text-overflow: ellipsis; white-space: nowrap; }
.composer-main { display: flex; min-height: 50px; align-items: flex-end; gap: 7px; padding: 6px 7px 6px 12px; border: 1px solid #dfe4ee; border-radius: 16px; background: #f7f9fc; transition: border-color .18s, box-shadow .18s; }
.composer-main:focus-within { border-color: #aeb8ed; box-shadow: 0 0 0 3px rgba(83, 103, 232, .08); }
.composer-main textarea { flex: 1; min-width: 0; max-height: 112px; padding: 8px 0 6px; color: #35415d; font: inherit; font-size: 14px; line-height: 1.45; resize: none; border: 0; outline: 0; background: transparent; }
.composer-main textarea::placeholder { color: #a1a9b8; }
.upload-button, .send-button { display: grid; flex: 0 0 auto; width: 30px; height: 30px; padding: 0; place-items: center; cursor: pointer; border: 0; border-radius: 9px; }
.upload-button { color: #69758e; background: #e9edf4; }
.upload-button:disabled { opacity: .5; }
.send-button { color: #fff; background: linear-gradient(140deg, #526bea, #7657d4); box-shadow: 0 5px 13px rgba(72, 83, 202, .24); }
.send-button.disabled { color: #aab1c1; background: #e6eaf1; box-shadow: none; }
.composer-hint { margin: 3px 3px 0; color: #9ba4b5; font-size: 10px; line-height: 1.35; }

.fullscreen-toggle {
  display: grid;
  flex: 0 0 auto;
  width: 26px;
  height: 26px;
  padding: 0;
  place-items: center;
  color: #8892a8;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: transparent;
  transition: background .15s;
}
.fullscreen-toggle:active { background: #e3e8f2; }
.fullscreen-toggle:disabled { opacity: .4; cursor: not-allowed; }
.fullscreen-toggle svg { width: 17px; height: 17px; }

.fullscreen-input-overlay {
  position: fixed;
  z-index: 1000;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #f7f9fd;
  padding: env(safe-area-inset-top, 0px) 0 env(safe-area-inset-bottom, 0px);
}
.fullscreen-input-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: 56px;
  padding: 10px 16px;
  border-bottom: 1px solid #e5e9f2;
  background: rgba(255, 255, 255, .96);
  backdrop-filter: blur(18px);
}
.fullscreen-input-title { color: #2e3b58; font-size: 16px; font-weight: 700; }
.fullscreen-input-exit {
  display: grid;
  width: 38px;
  height: 38px;
  padding: 0;
  place-items: center;
  color: #5a6a8a;
  cursor: pointer;
  border: 0;
  border-radius: 11px;
  background: #edf0f6;
}
.fullscreen-textarea {
  flex: 1;
  display: block;
  width: 100%;
  margin: 0;
  padding: 18px 16px;
  color: #2e3b58;
  font: inherit;
  font-size: 16px;
  line-height: 1.6;
  resize: none;
  border: 0;
  outline: 0;
  background: transparent;
  box-sizing: border-box;
}
.fullscreen-textarea::placeholder { color: #a1a9b8; }
.fullscreen-input-footer {
  flex: 0 0 auto;
  display: flex;
  justify-content: flex-end;
  padding: 10px 16px calc(10px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid #e5e9f2;
  background: rgba(255, 255, 255, .96);
  backdrop-filter: blur(18px);
}
.fullscreen-input-footer .send-button { width: auto; min-width: 80px; padding: 0 18px; font-size: 14px; font-weight: 700; gap: 6px; }

:deep(.h5-drawer.el-drawer) { max-width: 760px; margin: 0 auto; border-radius: 24px 24px 0 0; background: #fff; box-shadow: 0 -18px 50px rgba(30, 42, 78, .2); }
:deep(.h5-drawer .el-drawer__body) { padding: 18px 16px calc(18px + env(safe-area-inset-bottom)); overflow-y: auto; }
/* Model picker: fixed panel height + internal list scroll so many models stay selectable on short screens. */
:deep(.model-drawer.el-drawer) {
  height: 68% !important;
  max-height: min(78vh, 720px);
}
:deep(.model-drawer .el-drawer__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  overflow: hidden;
  padding-top: 14px;
  padding-bottom: calc(12px + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.drawer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 15px;
  border-bottom: 1px solid #edf0f5;
  -webkit-user-select: none;
  user-select: none;
  -webkit-touch-callout: none;
}
.drawer-title > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.drawer-title strong { color: #303d58; font-size: 17px; -webkit-user-select: none; user-select: none; }
.drawer-title span { color: #929bad; font-size: 11px; -webkit-user-select: none; user-select: none; }
.drawer-title > button { display: inline-flex; flex: 0 0 auto; min-height: 36px; align-items: center; gap: 5px; padding: 0 11px; color: #5064d2; font-size: 12px; font-weight: 700; cursor: pointer; border: 0; border-radius: 10px; background: #edf0ff; }
.drawer-title.compact { flex: 0 0 auto; padding-bottom: 13px; }
.session-list { padding-top: 9px; }
.session-row, .model-row { display: flex; width: 100%; min-height: 58px; align-items: center; gap: 8px; padding: 7px 6px 7px 10px; color: #51607b; text-align: left; border: 0; border-bottom: 1px solid #eff1f5; background: transparent; }
.session-row.active { border-radius: 12px; background: #f2f4ff; }
.session-select { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; padding: 6px 0; color: inherit; text-align: left; cursor: pointer; border: 0; background: transparent; }
.session-select span { overflow: hidden; font-size: 13px; font-weight: 750; text-overflow: ellipsis; white-space: nowrap; }
.session-select small { color: #9ba4b6; font-size: 10px; }
.session-action { display: grid; flex: 0 0 auto; width: 34px; height: 34px; padding: 0; place-items: center; color: #919caf; cursor: pointer; border: 0; border-radius: 10px; background: #f3f5f8; }
.session-action.danger { color: #d0717d; background: #fff2f4; }
.session-row > svg { flex: 0 0 auto; width: 18px; height: 18px; color: #6072db; }
.model-list {
  display: flex;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  gap: 6px;
  overflow-x: hidden;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior: contain;
  padding-top: 10px;
  padding-bottom: 4px;
}
.model-row {
  flex: 0 0 auto;
  justify-content: space-between;
  min-height: 48px;
  max-height: 64px;
  margin-bottom: 0;
  padding: 8px 12px;
  cursor: pointer;
  border: 1px solid #e9ecf2;
  border-radius: 12px;
}
.model-check {
  display: inline-flex;
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  align-items: center;
  justify-content: center;
  color: #6072db;
}
.model-check :deep(svg),
.model-row > svg {
  display: block;
  width: 18px !important;
  height: 18px !important;
  max-width: 18px;
  max-height: 18px;
  flex: 0 0 auto;
  color: #6072db;
}
.model-row > span { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.model-row strong {
  overflow: hidden;
  color: #46526d;
  font-size: 13px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-row small {
  overflow: hidden;
  color: #96a0b2;
  font-size: 11px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.model-row.active { border-color: #ced6ff; background: #f1f4ff; }
.model-row.active strong { color: #4f62d2; }

.account-summary { display: flex; align-items: center; gap: 13px; padding: 4px 3px 16px; border-bottom: 1px solid #edf0f5; }
.account-avatar { display: grid; width: 48px; height: 48px; place-items: center; color: #fff; font-size: 22px; border-radius: 16px; background: linear-gradient(145deg, #4f68e8, #8b5dde); box-shadow: 0 8px 18px rgba(75, 85, 202, .2); }
.account-summary > div { display: flex; flex-direction: column; gap: 3px; }
.account-summary strong { color: #303d58; font-size: 17px; }
.account-summary span { color: #929bad; font-size: 11px; }
.app-menu { display: grid; gap: 7px; padding-top: 12px; }
.app-menu > button { display: grid; grid-template-columns: 42px minmax(0, 1fr) 18px; min-height: 64px; align-items: center; gap: 10px; padding: 8px 11px; color: #536079; text-align: left; cursor: pointer; border: 1px solid #e9ecf3; border-radius: 14px; background: #fafbfe; }
.menu-icon { display: grid; width: 38px; height: 38px; place-items: center; color: #586bd4; font-size: 18px; border-radius: 12px; background: #edf0ff; }
.app-menu button > span:nth-child(2) { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.app-menu strong { color: #414e69; font-size: 13px; }
.app-menu small { overflow: hidden; color: #98a1b2; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.app-menu button > svg { width: 16px; color: #a1a9b9; }
.app-menu .danger-menu .menu-icon { color: #cf6572; background: #fff0f2; }
.app-menu .danger-menu strong { color: #b95564; }
.install-guide-steps { display: grid; gap: 9px; padding: 15px 1px 4px; }
.install-guide-steps p { display: flex; align-items: center; gap: 9px; margin: 0; color: #56627c; font-size: 13px; font-weight: 650; }
.install-guide-steps span { display: grid; width: 23px; height: 23px; flex: 0 0 auto; place-items: center; color: #5368d8; font-size: 11px; font-weight: 800; border-radius: 50%; background: #edf0ff; }
.install-guide-confirm { width: 100%; min-height: 40px; margin-top: 17px; color: #fff; font-size: 13px; font-weight: 750; cursor: pointer; border: 0; border-radius: 11px; background: linear-gradient(140deg, #536bea, #7657d4); }

.history-reference-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 9px; max-height: min(46vh, 330px); overflow-y: auto; padding-top: 11px; }
.history-reference-tile { position: relative; min-width: 0; padding: 0; overflow: hidden; text-align: left; cursor: pointer; border: 1px solid #e5e9f2; border-radius: 11px; background: #f5f6fa; }
.history-reference-tile:disabled { cursor: wait; opacity: .7; }
.history-reference-tile :deep(.el-image) { display: block; width: 100%; height: 94px; }
.history-reference-tile small { display: block; overflow: hidden; padding: 7px; color: #68738d; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; background: #fff; }
.history-reference-status { position: absolute; top: 6px; right: 6px; padding: 3px 6px; color: #fff; font-size: 9px; border-radius: 99px; background: rgba(65, 78, 151, .88); }
.draw-options { display: grid; gap: 12px; padding-top: 15px; }
.draw-options label { display: grid; grid-template-columns: 86px minmax(0, 1fr); align-items: center; gap: 10px; color: #65718a; font-size: 12px; }
.draw-options label > span { font-weight: 700; }
.draw-options :deep(.el-select) { width: 100%; }
.edit-textarea { display: block; width: 100%; min-height: 130px; margin-top: 14px; padding: 11px 12px; color: #36415e; font: inherit; font-size: 13px; line-height: 1.6; resize: vertical; border: 1px solid #dfe4ed; border-radius: 12px; outline: none; background: #f8f9fc; box-sizing: border-box; }
.edit-textarea:focus { border-color: #8795e4; box-shadow: 0 0 0 3px rgba(111, 126, 230, .1); }
.edit-footer { display: flex; justify-content: flex-end; gap: 8px; padding-top: 12px; }
.edit-footer button { min-width: 72px; min-height: 38px; padding: 0 12px; color: #65718c; font-size: 12px; font-weight: 700; cursor: pointer; border: 0; border-radius: 10px; background: #eef1f6; }
.edit-footer button.primary { color: #fff; background: linear-gradient(140deg, #536bea, #7657d4); box-shadow: 0 4px 10px rgba(83, 96, 229, .2); }

.action-list { display: grid; gap: 7px; padding-top: 11px; -webkit-user-select: none; user-select: none; }
.action-list button {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 10px;
  padding: 0 13px;
  color: #53617c;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  cursor: pointer;
  border: 0;
  border-radius: 12px;
  background: #f5f7fb;
  -webkit-user-select: none;
  user-select: none;
  -webkit-touch-callout: none;
}
.action-list button span { -webkit-user-select: none; user-select: none; }
.action-list button :deep(svg) { width: 18px; color: #6d7bd5; }
.action-list button.danger-action { color: #b95564; background: #fff1f3; }
.action-list button.danger-action :deep(svg) { color: #cf6572; }
:deep(.action-drawer .el-drawer__body),
:deep(.action-drawer .el-drawer__body *) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}
:deep(.action-drawer .drawer-title),
:deep(.action-drawer .drawer-title strong),
:deep(.action-drawer .drawer-title span) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}

/* WeChat-inspired mobile layout refinements */
.conversation { padding: 22px 20px 16px; background: #f4f5f7; }
.message-card { max-width: min(86%, 680px); margin-bottom: 22px; }
.message-meta { gap: 5px; margin: 0 5px 7px; color: #a8afb9; font-size: 10px; }
.message-meta > span { color: #9199a5; font-weight: 600; }
.message-meta time { color: #b6bcc5; }
.message-bubble {
  padding: 13px 14px;
  border: 0;
  border-radius: 12px;
  background: #fff;
  box-shadow: 0 1px 1px rgba(30, 41, 59, .04);
}
.message-card.user { align-self: flex-end; }
.message-card.user .message-meta { justify-content: flex-end; }
.message-card.user .message-bubble {
  color: #fff;
  border-radius: 12px;
  background: #5b8ff9;
  box-shadow: 0 2px 6px rgba(65, 119, 225, .16);
}
.message-content { font-size: 14px; line-height: 1.72; }
.message-attachments { gap: 8px; margin-bottom: 10px; }
.result-image { border-radius: 12px; }
.composer {
  padding: 12px 14px 8px;
  border-top-color: #e8eaee;
  background: rgba(255, 255, 255, .98);
  box-shadow: 0 -4px 16px rgba(37, 49, 72, .04);
}
.composer-main { min-height: 48px; gap: 9px; padding: 5px 5px 5px 8px; border: 0; border-radius: 12px; background: #f1f2f4; }
.composer-main:focus-within { border-color: transparent; box-shadow: 0 0 0 2px rgba(91, 143, 249, .18); }
.composer-main textarea { padding: 9px 2px 8px; font-size: 15px; line-height: 1.6; }
.upload-button { width: 34px; height: 34px; color: #657080; border-radius: 9px; background: transparent; }
.upload-button svg { width: 21px; height: 21px; }
.send-button { width: auto; min-width: 54px; height: 34px; padding: 0 11px; font-size: 13px; font-weight: 700; border-radius: 9px; background: #5b8ff9; box-shadow: none; }
.send-button.disabled { color: #a7adb7; background: #e3e5e8; }
.composer-hint { margin: 6px 3px 0; color: #a3a9b2; font-size: 10px; line-height: 1.45; }
.composer-extra-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; padding: 15px 0; }
.composer-extra-grid button { display: flex; min-width: 0; min-height: 92px; align-items: center; justify-content: center; flex-direction: column; gap: 4px; padding: 8px 5px; color: #4e5b6e; cursor: pointer; border: 0; border-radius: 12px; background: #f5f6f8; }
.composer-extra-grid button:disabled { color: #a9afb8; cursor: not-allowed; opacity: .65; }
.composer-extra-grid button svg { width: 22px; height: 22px; color: #5b8ff9; }
.composer-extra-grid button span { overflow: hidden; max-width: 100%; font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.composer-extra-grid button small { overflow: hidden; max-width: 100%; color: #9aa1ab; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.mode-picker, .drawer-draw-options { padding: 13px 0; border-top: 1px solid #edf0f3; }
.mode-picker > span, .drawer-draw-options > span { display: block; margin-bottom: 9px; color: #697485; font-size: 12px; font-weight: 700; }
.mode-picker .mode-switch { display: grid; grid-template-columns: repeat(2, 1fr); gap: 8px; }
.mode-picker .mode-switch button { display: inline-flex; min-height: 40px; align-items: center; justify-content: center; gap: 6px; color: #6e7785; cursor: pointer; border: 0; border-radius: 10px; background: #f3f4f6; }
.mode-picker .mode-switch button.active { color: #3979e8; background: #eaf2ff; }
.mode-picker .mode-switch svg { width: 17px; }
.draw-options-inline-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; }
.draw-options-inline-fields label { display: grid; gap: 5px; min-width: 0; color: #8a93a0; font-size: 10px; }
.draw-options-inline-fields :deep(.el-select) { width: 100%; }
/* Mobile administrator workspace remains in-page and never navigates to desktop routes. */
.mobile-admin-overlay { position: fixed; z-index: 2100; inset: 0; display: flex; flex-direction: column; color: #303744; background: #f5f6f8; }
.mobile-admin-header { display: grid; grid-template-columns: 40px minmax(0, 1fr) 40px; flex: 0 0 auto; min-height: calc(56px + env(safe-area-inset-top)); align-items: end; padding: env(safe-area-inset-top) 12px 8px; border-bottom: 1px solid #e8eaed; background: rgba(255, 255, 255, .97); box-sizing: border-box; }
.mobile-admin-header button { display: grid; width: 36px; height: 36px; place-items: center; color: #4f5b6d; cursor: pointer; border: 0; border-radius: 10px; background: transparent; }
.mobile-admin-header strong { overflow: hidden; color: #252c36; font-size: 17px; text-align: center; text-overflow: ellipsis; white-space: nowrap; }
.mobile-admin-content { min-height: 0; flex: 1; overflow-y: auto; padding: 16px 16px calc(24px + env(safe-area-inset-bottom)); }
.mobile-admin-tip { margin: 0 0 14px; padding: 11px 12px; color: #788291; font-size: 12px; line-height: 1.65; border-radius: 12px; background: #edf3ff; }
.mobile-admin-menu, .mobile-admin-list { display: grid; gap: 10px; }
.mobile-admin-menu button { display: grid; grid-template-columns: 40px minmax(0, 1fr) 18px; min-height: 70px; align-items: center; gap: 10px; padding: 10px; color: #596475; text-align: left; cursor: pointer; border: 0; border-radius: 12px; background: #fff; box-shadow: 0 1px 2px rgba(31, 41, 55, .04); }
.mobile-admin-menu button > span:nth-child(2), .mobile-admin-row > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.mobile-admin-menu strong, .mobile-admin-row strong, .mobile-admin-log strong { color: #354052; font-size: 14px; }
.mobile-admin-menu small, .mobile-admin-row small, .mobile-admin-log small { overflow: hidden; color: #9aa2ad; font-size: 11px; line-height: 1.45; text-overflow: ellipsis; white-space: nowrap; }
.mobile-admin-menu > svg, .session-admin-row > svg { width: 17px; color: #b0b6be; }
.mobile-admin-toolbar { display: flex; min-height: 40px; align-items: center; gap: 8px; margin-bottom: 13px; color: #717b89; font-size: 13px; }
.mobile-admin-toolbar input { min-width: 0; flex: 1; height: 40px; padding: 0 12px; color: #3f4856; font: inherit; font-size: 13px; border: 0; border-radius: 10px; outline: 0; background: #fff; box-sizing: border-box; }
.mobile-admin-toolbar button, .mobile-admin-row > button { min-height: 34px; padding: 0 11px; color: #3979e8; font-size: 12px; font-weight: 650; cursor: pointer; border: 0; border-radius: 8px; background: #eaf2ff; }
.mobile-admin-row { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto; min-height: 64px; align-items: center; gap: 10px; padding: 9px 10px; border-radius: 12px; background: #fff; box-shadow: 0 1px 2px rgba(31, 41, 55, .04); }
.mobile-admin-row > button.danger { color: #ca5964; background: #fff0f1; }
.mobile-admin-row.session-admin-row { grid-template-columns: 38px minmax(0, 1fr) 18px; }
.mobile-admin-empty { margin: 34px 0; color: #9ca4ae; font-size: 13px; text-align: center; }
.mobile-admin-log { padding: 12px; border-radius: 12px; background: #fff; box-shadow: 0 1px 2px rgba(31, 41, 55, .04); }
.mobile-admin-log > div { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 10px; }
.mobile-admin-log p { margin: 7px 0 0; color: #6e7785; font-size: 12px; line-height: 1.6; word-break: break-word; }

@keyframes bounce { 0%, 80%, 100% { opacity: .35; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-4px); } }
@keyframes pulse { 50% { opacity: .35; transform: scale(.72); } }
@keyframes spin { to { transform: rotate(360deg); } }

@media (min-width: 700px) {
  .conversation { padding-right: max(24px, calc((100vw - 760px) / 2)); padding-left: max(24px, calc((100vw - 760px) / 2)); }
  .composer { padding-right: max(18px, calc((100vw - 760px) / 2)); padding-left: max(18px, calc((100vw - 760px) / 2)); }
  .image-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 700px) {
  .action-list button,
  .action-list button span,
  .drawer-title,
  .drawer-title strong,
  .drawer-title span,
  .mobile-image-trigger,
  .mobile-image-trigger img {
    -webkit-user-select: none;
    user-select: none;
    -webkit-touch-callout: none;
  }
}

@media (max-width: 600px) {
  .message-actions { display: none; }
  .message-bubble,
  .message-bubble * {
    -webkit-user-select: none;
    user-select: none;
    -webkit-touch-callout: none;
  }
  .composer-main textarea, .edit-textarea { font-size: 16px; }
}

@media (max-width: 390px) {
  .mobile-header { padding-right: 11px; padding-left: 11px; }
  .brand-icon { width: 37px; height: 37px; border-radius: 12px; }
  .brand-copy strong { max-width: 38vw; }
  .header-icon-button { width: 37px; height: 37px; }
  .conversation { padding-right: 11px; padding-left: 11px; }
  .welcome-card { padding: 18px 14px 15px; border-radius: 20px; }
  .welcome-card h1 { font-size: 20px; }
  .composer { padding-right: 9px; padding-left: 9px; }
  .composer-main textarea { font-size: 16px; }
  .mode-toggle-bar button { font-size: 10px; }
  .composer-hint { display: none; }
  .draw-status-chip { padding-right: 6px; padding-left: 6px; }
  .draw-status-chip > strong { font-size: 10px; }
  .history-reference-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .draw-options label, .draw-settings-fields label { grid-template-columns: 74px minmax(0, 1fr); }
}

@media (max-height: 620px) {
  .mobile-header { min-height: calc(56px + env(safe-area-inset-top)); padding-top: calc(7px + env(safe-area-inset-top)); padding-bottom: 7px; }
  .brand-icon { width: 36px; height: 36px; }
  .composer-hint { display: none; }
  :deep(.model-drawer.el-drawer) {
    height: 78% !important;
    max-height: 90vh;
  }
  .model-row { min-height: 44px; padding: 6px 10px; }
}

@media (hover: hover) and (pointer: fine) {
  .header-icon-button:hover, .mode-switch button:hover, .tool-button:hover, .model-trigger:hover { filter: brightness(.97); }
  .message-actions { opacity: 0; transition: opacity .18s ease; }
  .message-card:hover .message-actions, .message-card:focus-within .message-actions { opacity: 1; }
  .quick-prompts button:hover, .app-menu > button:hover { border-color: #cfd6f4; background: #f4f6ff; transform: translateY(-1px); }
}

@media (prefers-reduced-motion: reduce) {
  .message-loading span, .pulse-dot, .state-orb.loading { animation: none; }
  .quick-prompts button, .app-menu > button { transition: none; }
}

/* Feishu / restricted WebView save surface: keep native long-press callout enabled. */
.save-image-helper {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: grid;
  place-items: end center;
  padding: 0;
}
.save-image-helper-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(12, 16, 28, .55);
}
.save-image-helper-panel {
  position: relative;
  z-index: 1;
  width: min(100%, 560px);
  max-height: min(88vh, 760px);
  overflow: auto;
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  border-radius: 22px 22px 0 0;
  background: #fff;
  box-shadow: 0 -16px 40px rgba(20, 30, 60, .22);
  -webkit-overflow-scrolling: touch;
}
.save-image-helper-panel header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.save-image-helper-panel header strong {
  color: #303d58;
  font-size: 17px;
}
.save-image-helper-panel header button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #6b7690;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #f1f3f8;
}
.save-image-helper-tip {
  margin: 0 0 12px;
  color: #6b7690;
  font-size: 12px;
  line-height: 1.55;
}
.save-image-helper-tip strong { color: #3f4f7d; }
.save-image-helper-preview {
  display: grid;
  place-items: center;
  min-height: 180px;
  max-height: 52vh;
  overflow: auto;
  padding: 10px;
  border-radius: 14px;
  background: #f4f6fb;
}
.save-image-helper-preview img {
  display: block;
  max-width: 100%;
  max-height: 48vh;
  object-fit: contain;
  /* Allow native long-press "save image" menus in Feishu / iOS. */
  -webkit-user-select: auto;
  user-select: auto;
  -webkit-touch-callout: default;
  pointer-events: auto;
}
.save-image-helper-actions {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}
.save-image-helper-actions button {
  min-height: 44px;
  padding: 0 12px;
  color: #53617c;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 12px;
  background: #f1f4f9;
}
.save-image-helper-actions button.primary {
  color: #fff;
  background: linear-gradient(140deg, #536bea, #7657d4);
  box-shadow: 0 6px 14px rgba(83, 96, 229, .22);
}
.save-helper-fade-enter-active,
.save-helper-fade-leave-active { transition: opacity .18s ease; }
.save-helper-fade-enter-active .save-image-helper-panel,
.save-helper-fade-leave-active .save-image-helper-panel { transition: transform .2s ease; }
.save-helper-fade-enter-from,
.save-helper-fade-leave-to { opacity: 0; }
.save-helper-fade-enter-from .save-image-helper-panel,
.save-helper-fade-leave-to .save-image-helper-panel { transform: translateY(18px); }


/* While a long-press is armed / opening menus, kill sticky text selection in WebViews. */
:global(html.h5-suppress-selection),
:global(html.h5-suppress-selection body),
:global(html.h5-suppress-selection body *) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}

/* Debug overlay — persistent floating debug info */
.debug-overlay {
  position: fixed;
  top: 0;
  left: 0;
  z-index: 9999;
  max-width: 85vw;
  padding: 5px 8px;
  font-size: 10px;
  line-height: 1.4;
  color: rgba(0, 255, 0, .85);
  background: rgba(0, 0, 0, .65);
  border-radius: 0 0 6px 0;
  pointer-events: none;
  font-family: monospace;
}
.debug-overlay code {
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
