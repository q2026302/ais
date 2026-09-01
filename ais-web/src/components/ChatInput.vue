<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Check,
  Close,
  FolderOpened,
  FullScreen,
  Paperclip,
  Picture,
  Plus,
  Promotion,
  Setting,
  View,
} from '@element-plus/icons-vue'
import type { Message, ModelProvider, UploadResponse, DrawReference } from '@/types'
import { sessionApi } from '@/api/sessions'
import { getAttachmentThumbnailUrl, getThumbnailUrl } from '@/utils/imageUrl'
import { referenceFromHistory, referenceFromUpload, referenceUrlForBackend } from '@/utils/historyReference'
import { getAppBasePath } from '@/utils/appBasePath'
import { getMessageEditableText } from '@/utils/messageText'
import { useSignedUrlRefresh } from '@/composables/useSignedUrlRefresh'

const props = defineProps<{
  loading: boolean
  cancelable?: boolean
  providerOptions: ModelProvider[]
  imageProviders: ModelProvider[]
  activeSessionId: number | null
  activeChatProviderId: number | null
  activeImageProviderId: number | null
  historyMessages?: Message[]
  editingMessage?: Message | null
  editingAction?: 'edit' | 'resend' | null
}>()

const emit = defineEmits<{
  send: [payload: { prompt: string; attachmentIds: number[]; attachments: UploadResponse[]; chatProviderId: number | null }]
  draw: [payload: { prompt: string; attachmentIds: number[]; referenceUrls: string[]; references: DrawReference[]; chatProviderId: number | null; size: string; quality: string; format: string }]
  cancel: []
  editSave: [payload: { messageId: number; content: string; chatProviderId: number | null; action: 'edit' | 'resend' }]
  editCancel: []
  imageProviderChange: [id: number | null]
  fullscreenChange: [value: boolean]
}>()

const { recoverImage } = useSignedUrlRefresh()

const inputText = ref('')
const selectedProviderId = ref<number | null>(null)
const pendingAttachments = ref<UploadResponse[]>([])
/** History references (draw mode only): reused server-side files, no new attachment. */
const historyReferences = ref<DrawReference[]>([])
const uploading = ref(false)
const mode = ref<'chat' | 'draw'>('chat')
const fullscreenInput = ref(false)
const settingsVisible = ref(false)
const drawSettingsVisible = ref(false)
const drawModelVisible = ref(false)
const drawSize = ref('1024x1024')
const drawQuality = ref('auto')
const drawFormat = ref('png')
const referenceVisible = ref(false)
const referenceAdding = ref(false)
const previewVisible = ref(false)
// PC 输入区浮层层级收口，自上而下：预览浮层 > el-drawer > 抽屉遮罩。
// 各抽屉虽通过 :z-index 传值(2100~2102)，但都被全局
// `.desktop-composer-drawer.el-drawer { z-index: 3001 !important }` 统一收口为 3001。
// 预览浮层必须稳定高于所有抽屉，故固定为更高的常量；后续新增抽屉只要沿用
// `.desktop-composer-drawer` 类（或被收口到 ≤ 3001），预览就仍在其上，不会回归。
const PREVIEW_Z_INDEX = 4000
const selectedHistoryIds = ref<string[]>([])
const plusMenuVisible = ref(false)
const plusMenuRef = ref<HTMLElement | null>(null)
const localFileInputRef = ref<HTMLInputElement | null>(null)
const isDragging = ref(false)
const dragDepth = ref(0)
const inputRef = ref<HTMLTextAreaElement | null>(null)
const fullscreenInputRef = ref<HTMLTextAreaElement | null>(null)
const composerRootRef = ref<HTMLElement | null>(null)

const canSend = computed(() => inputText.value.trim().length > 0 || pendingAttachments.value.length > 0 || historyReferences.value.length > 0)
const isEditing = computed(() => props.editingMessage != null && props.editingAction != null)
const canEditSend = computed(() => isEditing.value && inputText.value.trim().length > 0)
const referenceImageCount = computed(
  () => pendingAttachments.value.filter((item) => item.contentType?.startsWith('image/')).length + historyReferences.value.length,
)
/** Merged draft references (uploads + history) for the composer strip. */
const composerItems = computed<DrawReference[]>(() => [
  ...pendingAttachments.value.map(referenceFromUpload),
  ...historyReferences.value,
])
const defaultProviderId = computed(() => {
  if (
    props.activeChatProviderId != null
    && props.providerOptions.some((provider) => provider.id === props.activeChatProviderId)
  ) {
    return props.activeChatProviderId
  }
  return props.providerOptions.find((provider) => provider.active)?.id ?? null
})
const selectedProviderLabel = computed(() => {
  const provider = props.providerOptions.find((item) => item.id === selectedProviderId.value)
  return provider ? `${provider.name || provider.providerId} / ${provider.modelName}` : '系统默认'
})

// Draw settings: mirror the mobile draw-settings drawer. The option sets depend
// on the resolved image provider's adapter (Gemini / Grsai Nano Banana / GPT).
const selectedImageProvider = computed<ModelProvider | null>(() => {
  if (props.activeImageProviderId == null) return null
  return props.imageProviders.find((provider) => provider.id === props.activeImageProviderId) || null
})
const imageAdapter = computed(() => {
  const configured = selectedImageProvider.value?.adapterType?.toUpperCase()
  if (configured && configured !== 'AUTO') return configured
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  const providerId = selectedImageProvider.value?.providerId?.toLowerCase() || ''
  if (providerId === 'grsai') return 'GRS_AI'
  return model.includes('gemini') ? 'GEMINI_IMAGE' : 'OPENAI_IMAGE'
})
const usesRatioOptions = computed(() => imageAdapter.value === 'GEMINI_IMAGE'
  || (imageAdapter.value === 'GRS_AI' && (selectedImageProvider.value?.modelName || '').toLowerCase().includes('nano-banana')))
const isGptImageModel = computed(() => {
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  return (imageAdapter.value === 'OPENAI_IMAGE' || imageAdapter.value === 'GRS_AI')
    && (model.includes('gpt-image') || model.includes('gpt image'))
})
const drawSizeOptions = computed(() => usesRatioOptions.value
  ? ['1:1', '16:9', '9:16', '4:3', '3:4']
  : isGptImageModel.value
    ? ['1024x1024', '1536x1024', '1024x1536', 'auto']
    : ['1024x1024', '512x512', '768x768', '1024x1792', '1792x1024'])
const drawQualityOptions = computed(() => usesRatioOptions.value
  ? ['1K', '2K', '4K']
  : isGptImageModel.value ? ['auto', 'low', 'medium', 'high'] : ['standard', 'hd'])
const drawFormatOptions = computed(() => usesRatioOptions.value ? ['png'] : ['png', 'jpeg', 'webp'])
const drawProviderLabel = computed(() => {
  const provider = selectedImageProvider.value
  return provider ? `${provider.name || provider.providerId} / ${provider.modelName}` : '系统默认'
})

interface HistoryImageItem {
  id: string
  url: string
  thumbUrl: string
  label: string
  format: string
  messageId: number
}

const historyImages = computed<HistoryImageItem[]>(() => {
  const items: HistoryImageItem[] = []
  for (const message of props.historyMessages || []) {
    if (message.imageUrl && message.status !== 'FAILED') {
      items.push({
        id: `gen-${message.id}`,
        url: message.imageUrl,
        thumbUrl: getThumbnailUrl(message, 'small'),
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
            thumbUrl: getAttachmentThumbnailUrl(attachment, 'small'),
            label: attachment.originalName || '用户上传图片',
            format: ext,
            messageId: message.id,
          })
        }
      }
    }
  }
  return items.reverse()
})

const selectedHistoryItems = computed(() =>
  selectedHistoryIds.value
    .map((id) => historyImages.value.find((item) => item.id === id))
    .filter((item): item is HistoryImageItem => item != null),
)
const referenceSelectionCount = computed(() => selectedHistoryIds.value.length)
const canConfirmReference = computed(() => referenceSelectionCount.value > 0 && !referenceAdding.value && !props.loading)
const referencePreviewItems = computed(() =>
  selectedHistoryItems.value.map((item) => ({
    id: item.id,
    url: item.thumbUrl || item.url,
    kind: 'history' as const,
  })),
)
const referencePreviewUrls = computed(() => selectedHistoryItems.value.map((item) => item.url))

watch(
  () => [props.activeSessionId, defaultProviderId.value] as const,
  ([, providerId]) => {
    selectedProviderId.value = providerId
  },
  { immediate: true },
)

// Close any open composer drawers when switching sessions (PC: 参考图/模型
// 选择框切会话后必须关闭，否则会被对话窗口挡住且无法关闭).
watch(
  () => props.activeSessionId,
  () => {
    settingsVisible.value = false
    referenceVisible.value = false
    drawSettingsVisible.value = false
    drawModelVisible.value = false
    fullscreenInput.value = false
    resetReferencePanel()
    backfillDrawSettingsFromHistory()
  },
)

// Keep draw params valid when the resolved image provider (and thus its option
// set) changes, e.g. after switching the model in the header or the drawer.
watch([() => props.activeImageProviderId, () => props.imageProviders.length], syncDrawOptions)

watch(inputText, () => {
  void nextTick(() => autoResizeTextarea())
})

// Let HomeView collapse the message list while the composer is expanded so the
// textarea can claim the whole pane below the chat header (Feishu-style).
watch(fullscreenInput, (value) => emit('fullscreenChange', value))

// When a message enters the composer for edit / resend, load its editable text
// (pure prompt for DRAW_REQUEST). When editing exits, clear the draft.
watch(
  () => props.editingMessage?.id,
  (id, prevId) => {
    if (id != null && props.editingMessage) {
      fullscreenInput.value = false
      inputText.value = getMessageEditableText(props.editingMessage)
      void nextTick(() => inputRef.value?.focus())
    } else if (prevId != null) {
      inputText.value = ''
    }
    void nextTick(() => autoResizeTextarea())
  },
)

function isImage(contentType: string): boolean {
  return contentType.startsWith('image/')
}

function autoResizeTextarea() {
  // Fullscreen textarea fills the available height via flex, no auto-resize.
  if (fullscreenInput.value) return
  const el = inputRef.value
  if (!el) return
  el.style.height = 'auto'
  const lineHeight = parseInt(getComputedStyle(el).lineHeight, 10) || 22
  const maxHeight = lineHeight * 6 + 12
  el.style.height = `${Math.min(el.scrollHeight, maxHeight)}px`
  el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
}

async function uploadFile(file: File) {
  if (mode.value === 'draw' && !file.type.startsWith('image/')) {
    ElMessage.warning('绘画模式仅支持添加图片参考图')
    return
  }
  uploading.value = true
  try {
    const resp = await sessionApi.uploadFile(file)
    pendingAttachments.value.push(resp)
  } catch (e: any) {
    ElMessage.error('文件上传失败: ' + e.message)
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

async function handlePaste(e: ClipboardEvent) {
  const items = e.clipboardData?.items
  if (!items) return
  const imageFiles: File[] = []
  for (let i = 0; i < items.length; i++) {
    const item = items[i]
    if (!item) continue
    if (item.type.startsWith('image/')) {
      const file = item.getAsFile()
      if (file) imageFiles.push(file)
    }
  }
  if (imageFiles.length > 0) {
    e.preventDefault()
    for (const f of imageFiles) await uploadFile(f)
  }
}

function onDragEnter(event: DragEvent) {
  if (!event.dataTransfer?.types?.includes('Files')) return
  event.preventDefault()
  dragDepth.value += 1
  isDragging.value = true
}

function onDragOver(event: DragEvent) {
  if (!event.dataTransfer?.types?.includes('Files')) return
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
  isDragging.value = true
}

function onDragLeave(event: DragEvent) {
  if (!event.dataTransfer?.types?.includes('Files')) return
  event.preventDefault()
  dragDepth.value = Math.max(0, dragDepth.value - 1)
  if (dragDepth.value === 0) isDragging.value = false
}

async function onDrop(event: DragEvent) {
  event.preventDefault()
  dragDepth.value = 0
  isDragging.value = false
  if (props.loading || uploading.value) return
  const files = Array.from(event.dataTransfer?.files || [])
  for (const file of files) await uploadFile(file)
}

function removeComposerItem(ref: DrawReference) {
  if (ref.kind === 'upload') {
    pendingAttachments.value = pendingAttachments.value.filter((a) => a.id !== ref.attachmentId)
  } else {
    historyReferences.value = historyReferences.value.filter((r) => r.key !== ref.key)
  }
}

function openReferencePanel() {
  clearReferenceSelection()
  referenceVisible.value = true
}

function togglePlusMenu() {
  plusMenuVisible.value = !plusMenuVisible.value
}

function openHistoryPanel() {
  plusMenuVisible.value = false
  openReferencePanel()
}

function openLocalFilePicker() {
  plusMenuVisible.value = false
  localFileInputRef.value?.click()
}

function openSettingsPanel() {
  if (mode.value === 'draw') {
    // Painting mode: open only the parameter drawer (size/quality/format),
    // never the DrawDialog / send flow.
    openDrawSettings()
    return
  }
  settingsVisible.value = true
}

function openDrawSettings() {
  syncDrawOptions()
  drawSettingsVisible.value = true
}

function openDrawModelPicker() {
  drawSettingsVisible.value = false
  drawModelVisible.value = true
}

function selectImageProvider(id: number | null) {
  emit('imageProviderChange', id)
  drawModelVisible.value = false
  syncDrawOptions()
}

function syncDrawOptions() {
  if (!drawSizeOptions.value.includes(drawSize.value)) drawSize.value = usesRatioOptions.value ? '1:1' : '1024x1024'
  if (!drawQualityOptions.value.includes(drawQuality.value)) drawQuality.value = usesRatioOptions.value ? '1K' : isGptImageModel.value ? 'auto' : 'standard'
  if (!drawFormatOptions.value.includes(drawFormat.value)) drawFormat.value = 'png'
}

function backfillDrawSettingsFromHistory() {
  const messages = props.historyMessages || []
  for (let i = messages.length - 1; i >= 0; i--) {
    const message = messages[i]
    if (message?.messageType === 'DRAW_REQUEST') {
      if (message.drawSize) drawSize.value = message.drawSize
      if (message.drawQuality) drawQuality.value = message.drawQuality
      if (message.drawFormat) drawFormat.value = message.drawFormat
      break
    }
  }
  syncDrawOptions()
}

function toggleMode() {
  mode.value = mode.value === 'draw' ? 'chat' : 'draw'
  // History references only apply to drawing; drop them when leaving draw mode.
  historyReferences.value = []
}

function toggleFullscreenInput() {
  fullscreenInput.value = !fullscreenInput.value
  void nextTick(() => {
    autoResizeTextarea()
    const el = fullscreenInput.value ? fullscreenInputRef.value : inputRef.value
    el?.focus()
  })
}

function handleSend() {
  if (isEditing.value) {
    handleEditSend()
    return
  }
  if (!canSend.value || props.loading) return
  const chatProviderId = selectedProviderId.value
  if (mode.value === 'draw') {
    emit('draw', {
      prompt: inputText.value.trim(),
      attachmentIds: pendingAttachments.value.map((a) => a.id),
      referenceUrls: historyReferences.value.map((ref) => referenceUrlForBackend(ref.url, getAppBasePath())),
      references: [...composerItems.value],
      chatProviderId,
      size: drawSize.value,
      quality: drawQuality.value,
      format: drawFormat.value,
    })
    return
  }
  const payload = {
    prompt: inputText.value.trim(),
    attachmentIds: pendingAttachments.value.map((a) => a.id),
    attachments: [...pendingAttachments.value],
    chatProviderId,
  }
  emit('send', payload)
  inputText.value = ''
  pendingAttachments.value = []
  selectedProviderId.value = defaultProviderId.value
  fullscreenInput.value = false
}

function handleEditSend() {
  const content = inputText.value.trim()
  if (!content || !props.editingMessage) return
  emit('editSave', {
    messageId: props.editingMessage.id,
    content,
    chatProviderId: selectedProviderId.value,
    action: props.editingAction ?? 'edit',
  })
}

function handleEditCancel() {
  emit('editCancel')
}

function handleInputKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (isEditing.value) {
    // Edit mode: Enter (or Shift+Enter) sends; only Ctrl/Cmd/Meta + Enter
    // inserts a newline (the browser default), so we must not preventDefault
    // for those combinations.
    if (event.key === 'Enter' && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault()
      handleEditSend()
    }
    return
  }
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

function isHistorySelected(id: string) {
  return selectedHistoryIds.value.includes(id)
}

function onHistoryThumbError(item: HistoryImageItem) {
  recoverImage(item.thumbUrl || item.url)
}

function toggleHistorySelection(item: HistoryImageItem) {
  if (props.loading || referenceAdding.value) return
  if (isHistorySelected(item.id)) {
    selectedHistoryIds.value = selectedHistoryIds.value.filter((id) => id !== item.id)
  } else {
    selectedHistoryIds.value = [...selectedHistoryIds.value, item.id]
  }
}

function removeHistorySelection(id: string) {
  selectedHistoryIds.value = selectedHistoryIds.value.filter((item) => item !== id)
}

function clearReferenceSelection() {
  selectedHistoryIds.value = []
}

function resetReferencePanel() {
  clearReferenceSelection()
  referenceAdding.value = false
}

function openReferencePreview() {
  if (!referencePreviewUrls.value.length) return
  previewVisible.value = true
}

function closeReferencePanel() {
  referenceVisible.value = false
  resetReferencePanel()
}

async function confirmReferenceSelection() {
  if (!canConfirmReference.value) return
  referenceAdding.value = true
  uploading.value = true
  let added = 0
  try {
    if (mode.value === 'draw') {
      // Drawing: reuse the server-side file directly — no new attachment, no copy.
      for (const item of selectedHistoryItems.value) {
        if (historyReferences.value.some((ref) => ref.url === item.url)) continue
        historyReferences.value.push(referenceFromHistory(
          { url: item.url, thumbUrl: item.thumbUrl },
          `history-${item.messageId}.${item.format}`,
          `image/${item.format === 'jpg' ? 'jpeg' : item.format}`,
        ))
        added += 1
      }
    } else {
      // Chat: history images are real multimodal attachments (existing path).
      for (const item of selectedHistoryItems.value) {
        const attachment = await sessionApi.reuseAttachment(
          item.url,
          `history-${item.messageId}.${item.format}`,
          `image/${item.format === 'jpg' ? 'jpeg' : item.format}`,
        )
        pendingAttachments.value.push(attachment)
        added += 1
      }
    }
    referenceVisible.value = false
    resetReferencePanel()
    if (added > 0) ElMessage.success(added === 1 ? '参考图已添加' : `已添加 ${added} 张参考图`)
  } catch (error: any) {
    ElMessage.error(error.message || '添加参考图失败')
  } finally {
    referenceAdding.value = false
    uploading.value = false
  }
}

function clearDraft() {
  inputText.value = ''
  pendingAttachments.value = []
  historyReferences.value = []
}

onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  clearReferenceSelection()
})

function onDocumentPointerDown(event: PointerEvent) {
  if (!plusMenuVisible.value) return
  const target = event.target as Node | null
  if (target && plusMenuRef.value && !plusMenuRef.value.contains(target)) {
    plusMenuVisible.value = false
  }
}

defineExpose({ clearDraft })
</script>

<template>
  <div
    ref="composerRootRef"
    class="chat-input"
    :class="{ dragging: isDragging, 'is-fullscreen': fullscreenInput }"
    @dragenter="onDragEnter"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <div v-if="isDragging" class="drag-overlay" aria-hidden="true">
      <el-icon><Picture /></el-icon>
      <span>拖放文件到此处上传</span>
    </div>

    <div v-if="!fullscreenInput && composerItems.length > 0" class="attachment-bar">
      <div
        v-for="ref in composerItems"
        :key="ref.key"
        class="attachment-chip"
      >
        <el-image
          v-if="isImage(ref.contentType)"
          :src="ref.thumbnailUrl || ref.url"
          class="attachment-thumb"
          fit="cover"
          :preview-src-list="[ref.url]"
          preview-teleported
        />
        <el-icon v-else class="attachment-icon"><Paperclip /></el-icon>
        <span class="attachment-name">{{ ref.name }}</span>
        <el-button
          text
          size="small"
          type="danger"
          @click="removeComposerItem(ref)"
        >
          ✕
        </el-button>
      </div>
    </div>

    <div v-if="!fullscreenInput" class="composer-main">
      <textarea
        ref="inputRef"
        v-model="inputText"
        :disabled="loading"
        :placeholder="mode === 'draw' ? '描述你想生成的画面...' : '输入消息或 /help 查看命令'"
        rows="1"
        @paste="handlePaste"
        @keydown="handleInputKeydown"
        @input="autoResizeTextarea"
      />
    </div>

    <div v-if="!fullscreenInput" class="composer-toolbar" role="toolbar" aria-label="创作工具">
      <template v-if="!isEditing">
        <button
          class="tool-btn mode-btn"
          type="button"
          :title="mode === 'draw' ? '当前：绘画，点击切换到对话' : '当前：对话，点击切换到绘画'"
          @click="toggleMode"
        >
          <el-icon v-if="mode === 'draw'"><Picture /></el-icon>
          <el-icon v-else><ChatDotRound /></el-icon>
          <span>{{ mode === 'draw' ? '绘画' : '对话' }}</span>
        </button>

        <div ref="plusMenuRef" class="plus-menu-wrap">
          <button
            class="tool-btn"
            type="button"
            :disabled="loading || uploading"
            :title="referenceImageCount ? `添加参考图 · ${referenceImageCount}` : '添加参考图'"
            :aria-label="referenceImageCount ? `添加参考图（已添加 ${referenceImageCount} 张）` : '添加参考图'"
            :aria-expanded="plusMenuVisible"
            @click="togglePlusMenu"
          >
            <el-icon><Plus /></el-icon>
            <em v-if="referenceImageCount" class="tool-badge">{{ referenceImageCount }}</em>
          </button>

          <transition name="plus-menu-fade">
            <div v-if="plusMenuVisible" class="plus-menu" role="menu" aria-label="添加参考图">
              <button
                type="button"
                class="plus-menu-item"
                role="menuitem"
                @click="openHistoryPanel"
              >
                <el-icon><Picture /></el-icon>
                <span>历史图片</span>
              </button>
              <button
                type="button"
                class="plus-menu-item"
                role="menuitem"
                @click="openLocalFilePicker"
              >
                <el-icon><FolderOpened /></el-icon>
                <span>本地文件</span>
              </button>
            </div>
          </transition>
        </div>

        <input
          ref="localFileInputRef"
          type="file"
          class="plus-local-input"
          :accept="mode === 'draw' ? 'image/*' : 'image/*,.pdf,.doc,.docx,.txt'"
          multiple
          :disabled="loading || uploading"
          @change="handleFileChange"
        >

        <button
          class="tool-btn"
          type="button"
          :title="mode === 'draw' ? '绘画设置' : `对话模型 · ${selectedProviderLabel}`"
          :aria-label="mode === 'draw' ? '绘画设置' : '选择对话模型'"
          @click="openSettingsPanel"
        >
          <el-icon><Setting /></el-icon>
        </button>

        <button
          class="tool-btn"
          type="button"
          title="全屏编辑"
          aria-label="全屏编辑"
          @click="toggleFullscreenInput"
        >
          <el-icon><FullScreen /></el-icon>
        </button>
      </template>

      <button
        v-if="isEditing"
        class="tool-btn edit-cancel-btn"
        type="button"
        title="取消编辑"
        @click="handleEditCancel"
      >
        取消
      </button>

      <span class="input-hint">{{ isEditing ? 'Ctrl + Enter 换行' : 'Enter 发送, Shift+Enter 换行 · 支持拖拽/粘贴' }}</span>

      <el-button
        v-if="loading && cancelable"
        type="danger"
        plain
        class="cancel-btn"
        title="终止当前请求"
        @click="emit('cancel')"
      >
        终止
      </el-button>
      <button
        v-else
        class="send-button"
        type="button"
        :class="{ disabled: isEditing ? !canEditSend || loading : !canSend || loading }"
        :disabled="isEditing ? !canEditSend || loading : !canSend || loading"
        :title="isEditing ? '保存并重新发送（Enter）' : (mode === 'draw' ? '打开绘画面板' : '发送消息（Enter）')"
        @click="handleSend"
      >
        <el-icon v-if="mode === 'chat'"><Promotion /></el-icon>
        <span>{{ isEditing ? '发送' : (mode === 'draw' ? '生成' : '发送') }}</span>
      </button>
    </div>

    <div v-if="fullscreenInput" class="fullscreen-input-overlay">
      <div class="fullscreen-input-header">
        <span class="fullscreen-input-title">{{ mode === 'draw' ? '输入绘画描述' : '输入消息' }}</span>
        <button type="button" class="fullscreen-input-exit" aria-label="退出全屏" @click="toggleFullscreenInput">
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <textarea
        ref="fullscreenInputRef"
        v-model="inputText"
        class="fullscreen-textarea"
        :placeholder="mode === 'draw' ? '描述你想生成的画面...' : '输入消息或 /help 查看命令'"
        @paste="handlePaste"
        @keydown="handleInputKeydown"
      />
      <div class="fullscreen-input-footer">
        <span class="fullscreen-input-hint">{{ isEditing ? 'Ctrl + Enter 换行' : 'Enter 发送 · Shift+Enter 换行' }}</span>
        <button
          v-if="isEditing"
          type="button"
          class="fullscreen-input-cancel"
          @click="handleEditCancel"
        >
          取消
        </button>
        <button
          class="send-button"
          type="button"
          :class="{ disabled: isEditing ? !canEditSend || loading : !canSend || loading }"
          :disabled="isEditing ? !canEditSend || loading : !canSend || loading"
          @click="handleSend"
        >
          <el-icon><Promotion /></el-icon>
          <span>{{ isEditing ? '发送' : (mode === 'draw' ? '生成' : '发送') }}</span>
        </button>
      </div>
    </div>

    <el-drawer
      v-model="drawSettingsVisible"
      direction="btt"
      size="auto"
      class="desktop-composer-drawer draw-settings-composer-drawer"
      modal-class="desktop-composer-overlay"
      :append-to-body="true"
      :with-header="false"
      :z-index="2102"
    >
      <div class="drawer-title">
        <div>
          <strong>绘画设置</strong>
          <span>调整尺寸、质量与格式 · 当前模型 {{ drawProviderLabel }}</span>
        </div>
        <button
          type="button"
          class="drawer-title-close draw-model-switch"
          aria-label="换模型"
          title="换模型"
          @click="openDrawModelPicker"
        >
          换模型
        </button>
      </div>
      <div class="draw-settings-fields">
        <label>
          <span>尺寸 / 比例</span>
          <el-select v-model="drawSize" aria-label="绘画尺寸或比例">
            <el-option v-for="option in drawSizeOptions" :key="option" :label="option" :value="option" />
          </el-select>
        </label>
        <label>
          <span>质量</span>
          <el-select v-model="drawQuality" aria-label="绘画质量">
            <el-option v-for="option in drawQualityOptions" :key="option" :label="option.toUpperCase()" :value="option" />
          </el-select>
        </label>
        <label>
          <span>格式</span>
          <el-select v-model="drawFormat" aria-label="图片格式">
            <el-option v-for="option in drawFormatOptions" :key="option" :label="option.toUpperCase()" :value="option" />
          </el-select>
        </label>
      </div>
    </el-drawer>

    <el-drawer
      v-model="drawModelVisible"
      direction="btt"
      size="auto"
      class="desktop-composer-drawer model-composer-drawer"
      modal-class="desktop-composer-overlay"
      :append-to-body="true"
      :with-header="false"
      :z-index="2102"
    >
      <div class="drawer-title">
        <div>
          <strong>选择绘画模型</strong>
          <span>模型选择会保存到当前会话</span>
        </div>
      </div>
      <div class="model-list">
        <button
          type="button"
          class="model-row"
          :class="{ active: activeImageProviderId == null }"
          @click="selectImageProvider(null)"
        >
          <span>
            <strong>系统默认模型</strong>
            <small>使用后台或会话默认配置</small>
          </span>
        </button>
        <button
          v-for="provider in imageProviders"
          :key="provider.id"
          type="button"
          class="model-row"
          :class="{ active: provider.id === activeImageProviderId }"
          @click="selectImageProvider(provider.id)"
        >
          <span>
            <strong>{{ provider.name || provider.providerId }}</strong>
            <small>#{{ provider.id }} · {{ provider.modelName }}</small>
          </span>
        </button>
      </div>
    </el-drawer>

    <el-drawer
      v-model="settingsVisible"
      direction="btt"
      size="auto"
      class="desktop-composer-drawer model-composer-drawer"
      modal-class="desktop-composer-overlay"
      :append-to-body="true"
      :with-header="false"
      :z-index="2101"
    >
      <div class="drawer-title">
        <div>
          <strong>选择对话模型</strong>
          <span>仅影响下一次发送，不会改写会话默认模型</span>
        </div>
      </div>
      <div class="model-list">
        <button
          type="button"
          class="model-row"
          :class="{ active: selectedProviderId == null }"
          @click="selectedProviderId = null; settingsVisible = false"
        >
          <span>
            <strong>系统默认模型</strong>
            <small>使用后台或会话默认配置</small>
          </span>
        </button>
        <button
          v-for="provider in providerOptions"
          :key="provider.id"
          type="button"
          class="model-row"
          :class="{ active: provider.id === selectedProviderId }"
          @click="selectedProviderId = provider.id; settingsVisible = false"
        >
          <span>
            <strong>{{ provider.name || provider.providerId }}</strong>
            <small>#{{ provider.id }} · {{ provider.modelName }}</small>
          </span>
        </button>
      </div>
    </el-drawer>

    <el-drawer
      v-model="referenceVisible"
      direction="btt"
      size="auto"
      class="desktop-composer-drawer reference-composer-drawer"
      modal-class="desktop-composer-overlay reference-composer-overlay"
      :append-to-body="true"
      :with-header="false"
      :z-index="2100"
      @closed="resetReferencePanel"
    >
      <div class="drawer-title">
        <div>
          <strong>选择历史图片</strong>
          <span>多选历史作品作为参考图，确认后添加</span>
        </div>
        <button
          type="button"
          class="drawer-title-close"
          aria-label="关闭"
          title="关闭"
          @click="closeReferencePanel"
        >
          <el-icon><Close /></el-icon>
        </button>
      </div>
      <div class="reference-panel">
        <div class="reference-body">
          <div class="reference-main">
            <template v-if="historyImages.length">
              <div class="history-reference-grid">
                <button
                  v-for="item in historyImages"
                  :key="item.id"
                  type="button"
                  class="history-reference-tile"
                  :class="{ selected: isHistorySelected(item.id) }"
                  :disabled="referenceAdding || loading"
                  :aria-pressed="isHistorySelected(item.id)"
                  @click="toggleHistorySelection(item)"
                >
                  <el-image :src="item.thumbUrl || item.url" fit="cover" @error="onHistoryThumbError(item)" />
                  <span class="history-check" :class="{ checked: isHistorySelected(item.id) }" aria-hidden="true">
                    <el-icon v-if="isHistorySelected(item.id)"><Check /></el-icon>
                  </span>
                </button>
              </div>
            </template>
            <p v-else class="reference-empty-hint">当前会话还没有历史作品，可先通过「+」菜单上传本地图片。</p>
          </div>
        </div>
        <div class="reference-footer">
          <div class="reference-preview-strip" aria-label="已选参考图">
            <template v-if="referencePreviewItems.length">
              <div
                v-for="(item, index) in referencePreviewItems"
                :key="item.id"
                class="reference-preview-chip"
              >
                <el-image :src="item.url" fit="cover" />
                <em v-if="index === 0 && referenceSelectionCount > 1" class="reference-preview-count">{{ referenceSelectionCount }}</em>
                <button
                  type="button"
                  class="reference-preview-remove"
                  aria-label="移除已选图片"
                  @click.stop="removeHistorySelection(item.id)"
                >
                  <el-icon><Close /></el-icon>
                </button>
              </div>
            </template>
            <span v-else class="reference-preview-empty">未选择图片</span>
          </div>
          <button
            type="button"
            class="reference-preview-btn"
            :disabled="!referenceSelectionCount"
            title="预览已选图片"
            @click="openReferencePreview"
          >
            <el-icon><View /></el-icon>
            <span>预览</span>
          </button>
          <button
            type="button"
            class="reference-add-btn"
            :class="{ active: canConfirmReference }"
            :disabled="!canConfirmReference"
            @click="confirmReferenceSelection"
          >
            {{ referenceAdding ? '添加中…' : '添加' }}
          </button>
          <button
            type="button"
            class="reference-cancel-btn"
            @click="closeReferencePanel"
          >
            取消
          </button>
        </div>
      </div>
    </el-drawer>

    <el-image-viewer
      v-if="previewVisible"
      :url-list="referencePreviewUrls"
      :initial-index="0"
      :z-index="PREVIEW_Z_INDEX"
      teleported
      :hide-on-click-modal="true"
      @close="previewVisible = false"
    />
  </div>
</template>

<style scoped>
.chat-input {
  position: relative;
  margin: 0 clamp(12px, 3vw, 36px) 18px;
  padding: 12px 14px 10px;
  border: 1px solid rgba(220, 225, 244, .95);
  border-radius: 18px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 12px 32px rgba(46, 59, 117, .1);
  backdrop-filter: blur(12px);
}
.chat-input.dragging {
  border-color: #8ea0f5;
  box-shadow: 0 0 0 3px rgba(83, 103, 232, .12), 0 12px 32px rgba(46, 59, 117, .1);
}
/* Feishu-style fullscreen editor: expand inside the chat pane (below the header)
   instead of covering the whole viewport, so the left session list stays visible. */
.chat-input.is-fullscreen {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  margin: 0;
  padding: 0;
  border: 0;
  border-radius: 0;
  background: #f7f9fd;
  box-shadow: none;
  backdrop-filter: none;
}
.drag-overlay {
  position: absolute;
  z-index: 5;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #4e62d2;
  font-size: 14px;
  font-weight: 700;
  pointer-events: none;
  border-radius: 18px;
  background: rgba(239, 243, 255, .92);
  border: 2px dashed #8ea0f5;
}
.drag-overlay .el-icon { font-size: 28px; }
.attachment-bar { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 2px 9px; border-bottom: 1px solid #edf0f8; margin-bottom: 8px; }
.attachment-chip { display: flex; align-items: center; gap: 7px; padding: 5px 6px 5px 7px; font-size: 12px; border: 1px solid #e5e9f7; border-radius: 9px; background: #f7f8fe; }
.attachment-thumb { width: 30px; height: 30px; border-radius: 6px; }
.attachment-icon { color: #8791ab; font-size: 16px; }
.attachment-name { max-width: 120px; overflow: hidden; color: #59647d; text-overflow: ellipsis; white-space: nowrap; }

.composer-main {
  display: flex;
  min-height: 44px;
  align-items: flex-end;
  padding: 8px 12px;
  border: 1px solid #e4e8f4;
  border-radius: 14px;
  background: #f7f8fd;
  transition: border-color .18s, box-shadow .18s;
}
.composer-main:focus-within {
  border-color: #aeb8ed;
  box-shadow: 0 0 0 3px rgba(83, 103, 232, .08);
  background: #fff;
}
.composer-main textarea {
  flex: 1;
  min-width: 0;
  max-height: 160px;
  padding: 2px 0;
  color: #38435f;
  font: inherit;
  font-size: 14px;
  line-height: 1.55;
  resize: none;
  border: 0;
  outline: 0;
  background: transparent;
}
.composer-main textarea::placeholder { color: #a1a9b8; }

.composer-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 8px;
  padding-top: 2px;
}
.tool-btn {
  position: relative;
  display: inline-flex;
  flex: 0 0 auto;
  min-width: 34px;
  min-height: 34px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 8px;
  color: #697691;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: transparent;
}
.tool-btn:hover { color: var(--app-primary, #536bf5); background: #f0f2ff; }
.tool-btn:disabled,
.tool-btn.disabled { opacity: .45; cursor: not-allowed; pointer-events: none; }
.tool-btn.mode-btn {
  color: #4e62d2;
  background: #eef1ff;
  padding: 0 10px;
}
.tool-badge {
  position: absolute;
  top: 1px;
  right: 1px;
  min-width: 14px;
  height: 14px;
  padding: 0 3px;
  color: #fff;
  font-size: 9px;
  font-style: normal;
  font-weight: 800;
  line-height: 14px;
  text-align: center;
  border-radius: 99px;
  background: #5b8ff9;
}
.plus-menu-wrap {
  position: relative;
  flex: 0 0 auto;
}
.plus-menu {
  position: absolute;
  bottom: calc(100% + 10px);
  left: 0;
  z-index: 3200;
  display: grid;
  min-width: 152px;
  gap: 4px;
  padding: 6px;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 18px 44px rgba(30, 42, 78, .18), 0 2px 8px rgba(30, 42, 78, .08);
}
.plus-menu-item {
  display: flex;
  min-height: 42px;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  color: #3a4661;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: transparent;
}
.plus-menu-item:hover { color: #4e62d2; background: #f0f2ff; }
.plus-menu-item .el-icon { font-size: 17px; color: #6b7bd6; }
.plus-menu-item span { white-space: nowrap; }
.plus-local-input {
  position: fixed;
  top: -9999px;
  left: -9999px;
  width: 1px;
  height: 1px;
  opacity: 0;
}
.plus-menu-fade-enter-active,
.plus-menu-fade-leave-active { transition: opacity .16s ease, transform .16s ease; }
.plus-menu-fade-enter-from,
.plus-menu-fade-leave-to { opacity: 0; transform: translateY(6px); }
.input-hint {
  flex: 1;
  margin: 0 8px;
  color: #adb4c5;
  font-size: 11px;
  text-align: right;
  white-space: nowrap;
}
.send-button {
  display: inline-flex;
  flex: 0 0 auto;
  min-width: 72px;
  height: 34px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 14px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: linear-gradient(110deg, #536bf5, #795be8);
  box-shadow: 0 5px 12px rgba(82, 103, 246, .22);
}
.send-button:hover:not(.disabled) { transform: translateY(-1px); }
.send-button.disabled {
  color: #aab1c1;
  cursor: not-allowed;
  background: #e6eaf1;
  box-shadow: none;
}
.cancel-btn { flex-shrink: 0; min-width: 70px; border-radius: 9px; }
.edit-cancel-btn {
  flex: 0 0 auto;
  color: #7a8498;
  background: #f0f2f7;
}
.edit-cancel-btn:hover { color: #55627c; background: #e6eaf2; }

.fullscreen-input-overlay {
  flex: 1 1 auto;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: #f7f9fd;
}
.fullscreen-input-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: 48px;
  padding: 6px 14px;
  border-bottom: 1px solid #e5e9f2;
  background: rgba(255, 255, 255, .96);
}
.fullscreen-input-title { color: #2e3b58; font-size: 14px; font-weight: 700; }
.fullscreen-input-exit {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  color: #5a6a8a;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: #edf0f6;
}
.fullscreen-textarea {
  flex: 1;
  width: 100%;
  margin: 0;
  padding: 18px 20px;
  color: #2e3b58;
  font: inherit;
  font-size: 16px;
  line-height: 1.65;
  resize: none;
  border: 0;
  outline: 0;
  background: transparent;
  box-sizing: border-box;
}
.fullscreen-input-footer {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  padding: 10px 14px;
  border-top: 1px solid #e5e9f2;
  background: rgba(255, 255, 255, .96);
}
.fullscreen-input-hint { margin-right: auto; color: #a3abbb; font-size: 12px; }
.fullscreen-input-cancel {
  flex: 0 0 auto;
  min-height: 34px;
  padding: 0 14px;
  color: #5a6790;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: #eef1f7;
}
.fullscreen-input-cancel:hover { color: #55627c; background: #e6eaf2; }

.drawer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 14px;
  border-bottom: 1px solid #edf0f5;
}
.drawer-title > div { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.drawer-title strong { color: #303d58; font-size: 16px; }
.drawer-title span { color: #929bad; font-size: 12px; }
.drawer-title-close {
  display: grid;
  flex: 0 0 auto;
  width: 32px;
  height: 32px;
  place-items: center;
  color: #6b7690;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: #f1f3f8;
}
.drawer-title-close:hover { color: #55627c; background: #e6eaf2; }
.draw-model-switch {
  width: auto;
  min-width: 64px;
  padding: 0 12px;
  color: #4e62d2;
  font-size: 13px;
  font-weight: 700;
  background: #eef1ff;
}
.draw-model-switch:hover { color: #3f51c4; background: #e3e8ff; }
.draw-settings-fields {
  display: grid;
  gap: 12px;
  padding-top: 14px;
}
.draw-settings-fields label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.draw-settings-fields label > span {
  flex: 0 0 auto;
  color: #4a5674;
  font-size: 13px;
  font-weight: 700;
}
.draw-settings-fields :deep(.el-select) {
  width: 200px;
}
.model-list { display: grid; gap: 8px; padding: 12px 0 4px; max-height: min(52vh, 420px); overflow-y: auto; }
.model-row {
  display: flex;
  min-height: 52px;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  text-align: left;
  cursor: pointer;
  border: 1px solid #e7ebf4;
  border-radius: 12px;
  background: #f8f9fd;
}
.model-row.active { border-color: #b7c1f4; background: #eef1ff; }
.model-row span { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.model-row strong { color: #314063; font-size: 13px; }
.model-row small { color: #8a94aa; font-size: 11px; }

.reference-panel { display: grid; gap: 12px; padding-top: 8px; }
.reference-body {
  display: flex;
  flex-direction: column;
  min-height: min(46vh, 380px);
  overflow: hidden;
  border: 1px solid #e7ebf3;
  border-radius: 14px;
  background: #fff;
}
.reference-main {
  min-width: 0;
  flex: 1;
  padding: 12px;
  background: #fff;
  overflow: hidden;
}
.reference-empty-hint {
  display: grid;
  min-height: 180px;
  place-items: center;
  margin: 0;
  padding: 16px 12px;
  color: #9aa3b5;
  font-size: 12px;
  line-height: 1.5;
  text-align: center;
}
.reference-footer {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 56px;
}
.reference-original {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 6px;
  margin: 0;
  color: #5d6a84;
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  cursor: pointer;
}
.reference-original input {
  width: 15px;
  height: 15px;
  accent-color: #536bea;
}
.reference-preview-strip {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 8px;
  overflow-x: auto;
  padding: 2px 0;
}
.reference-preview-empty { color: #a0a8b8; font-size: 12px; }
.reference-preview-chip {
  position: relative;
  flex: 0 0 auto;
  width: 42px;
  height: 42px;
}
.reference-preview-chip :deep(.el-image) {
  display: block;
  width: 42px;
  height: 42px;
  overflow: hidden;
  border: 1px solid #dfe4ef;
  border-radius: 10px;
}
.reference-preview-count {
  position: absolute;
  top: -5px;
  right: -5px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 800;
  line-height: 16px;
  text-align: center;
  border-radius: 99px;
  background: #5b8ff9;
}
.reference-preview-remove {
  position: absolute;
  top: -5px;
  left: -5px;
  display: grid;
  width: 16px;
  height: 16px;
  place-items: center;
  padding: 0;
  color: #fff;
  cursor: pointer;
  border: 0;
  border-radius: 50%;
  background: rgba(55, 64, 88, .88);
}
.reference-preview-btn,
.reference-add-btn,
.reference-cancel-btn {
  display: inline-flex;
  min-height: 36px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 0 12px;
  font-size: 12px;
  font-weight: 750;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
}
.reference-preview-btn {
  color: #5a6790;
  background: #eef1f7;
}
.reference-preview-btn:disabled,
.reference-add-btn:disabled {
  opacity: .5;
  cursor: not-allowed;
}
.reference-add-btn {
  min-width: 72px;
  color: #fff;
  background: #c5cad6;
}
.reference-add-btn.active {
  background: linear-gradient(140deg, #536bea, #7657d4);
  box-shadow: 0 4px 10px rgba(83, 96, 229, .2);
}
.reference-cancel-btn {
  flex: 0 0 auto;
  color: #5a6790;
  background: #eef1f7;
}
.reference-cancel-btn:hover { color: #55627c; background: #e6eaf2; }
.history-reference-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  max-height: min(42vh, 360px);
  overflow-y: auto;
  padding: 2px;
}
.history-reference-tile {
  position: relative;
  min-width: 0;
  /* 正方形(1:1)缩略图：图片由 el-image 的 fit="cover" 等比居中裁切，不拉伸不变形。
     PC 浏览器对 aspect-ratio 支持良好；移动端 FeishuH5View 仍用固定高度方案，互不影响。 */
  aspect-ratio: 1 / 1;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  border: 2px solid transparent;
  border-radius: 11px;
  background: #f5f6fa;
}
.history-reference-tile.selected {
  border-color: #5b8ff9;
  box-shadow: 0 0 0 1px rgba(91, 143, 249, .25);
}
.history-reference-tile:disabled { cursor: wait; opacity: .7; }
.history-reference-tile :deep(.el-image) {
  display: block;
  width: 100%;
  height: 100%;
}
.history-check {
  position: absolute;
  top: 6px;
  right: 6px;
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  color: transparent;
  border: 1.5px solid rgba(255, 255, 255, .95);
  border-radius: 50%;
  background: rgba(255, 255, 255, .35);
  box-shadow: 0 1px 4px rgba(20, 28, 48, .18);
}
.history-check.checked {
  color: #fff;
  border-color: #5b8ff9;
  background: #5b8ff9;
}
.history-check .el-icon { font-size: 12px; }

:global(.desktop-composer-drawer.el-drawer) {
  max-width: 720px;
  margin: 0 auto;
  border-radius: 20px 20px 0 0;
  z-index: 3001 !important;
}
/* The painting-settings sheet is anchored to the viewport bottom. Cap its
   height so the OS taskbar never hides the controls, and let the body scroll
   when the viewport is short. */
:global(.draw-settings-composer-drawer.el-drawer) {
  max-height: min(560px, calc(100vh - 80px));
}
:global(.draw-settings-composer-drawer .el-drawer__body) {
  overflow-y: auto;
  padding-bottom: calc(18px + env(safe-area-inset-bottom, 0px));
}
:global(.desktop-composer-drawer .el-drawer__body) {
  padding: 18px 18px calc(18px + env(safe-area-inset-bottom, 0px));
}
:global(.desktop-composer-overlay) {
  z-index: 3000 !important;
}
:global(.desktop-composer-overlay.reference-composer-overlay) {
  z-index: 2999 !important;
}

@media (max-width: 780px) {
  .chat-input { margin: 0 12px 12px; }
  .input-hint { display: none; }
  .desktop-only { display: none; }
}
@media (max-width: 560px) {
  .composer-toolbar { flex-wrap: wrap; }
  .send-button, .cancel-btn { min-width: 64px; }
  .history-reference-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .reference-footer { flex-wrap: wrap; }
  .reference-preview-strip { flex: 1 1 100%; order: -1; }
}
</style>
