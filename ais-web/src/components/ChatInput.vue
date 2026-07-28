<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  ChatDotRound,
  Close,
  FullScreen,
  Paperclip,
  Picture,
  Promotion,
  Setting,
  UploadFilled,
} from '@element-plus/icons-vue'
import type { Message, ModelProvider, UploadResponse } from '@/types'
import { sessionApi } from '@/api/sessions'
import { getAttachmentThumbnailUrl, getThumbnailUrl } from '@/utils/imageUrl'

const props = defineProps<{
  loading: boolean
  cancelable?: boolean
  providerOptions: ModelProvider[]
  activeSessionId: number | null
  activeChatProviderId: number | null
  historyMessages?: Message[]
}>()

const emit = defineEmits<{
  send: [payload: { prompt: string; attachmentIds: number[]; attachments: UploadResponse[]; chatProviderId: number | null }]
  draw: [payload: { prompt: string; attachmentIds: number[]; attachments: UploadResponse[]; chatProviderId: number | null }]
  cancel: []
}>()

const inputText = ref('')
const selectedProviderId = ref<number | null>(null)
const pendingAttachments = ref<UploadResponse[]>([])
const uploading = ref(false)
const mode = ref<'chat' | 'draw'>('chat')
const fullscreenInput = ref(false)
const settingsVisible = ref(false)
const referenceVisible = ref(false)
const referenceImportingId = ref<number | null>(null)
const fileInputRef = ref<HTMLInputElement | null>(null)
const imageFileInputRef = ref<HTMLInputElement | null>(null)
const inputRef = ref<HTMLTextAreaElement | null>(null)
const fullscreenInputRef = ref<HTMLTextAreaElement | null>(null)

const canSend = computed(() => inputText.value.trim().length > 0 || pendingAttachments.value.length > 0)
const referenceImageCount = computed(
  () => pendingAttachments.value.filter((item) => item.contentType?.startsWith('image/')).length,
)
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
          })
        }
      }
    }
  }
  return items.reverse()
})

watch(
  () => [props.activeSessionId, defaultProviderId.value] as const,
  ([, providerId]) => {
    selectedProviderId.value = providerId
  },
  { immediate: true },
)

watch(inputText, () => {
  void nextTick(() => autoResizeTextarea())
})

function isImage(contentType: string): boolean {
  return contentType.startsWith('image/')
}

function autoResizeTextarea() {
  const el = fullscreenInput.value ? fullscreenInputRef.value : inputRef.value
  if (!el) return
  el.style.height = 'auto'
  const lineHeight = parseInt(getComputedStyle(el).lineHeight, 10) || 22
  const maxRows = fullscreenInput.value ? 20 : 6
  const maxHeight = lineHeight * maxRows + 12
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

function removeAttachment(id: number) {
  pendingAttachments.value = pendingAttachments.value.filter((a) => a.id !== id)
}

function openFilePicker() {
  if (props.loading || uploading.value) return
  fileInputRef.value?.click()
}

function openImageFilePicker() {
  if (props.loading || uploading.value) return
  imageFileInputRef.value?.click()
}

function openReferencePanel() {
  referenceVisible.value = true
}

function openSettingsPanel() {
  if (mode.value === 'draw') {
    // Keep DrawDialog as the desktop painting settings surface.
    emit('draw', {
      prompt: inputText.value.trim(),
      attachmentIds: pendingAttachments.value.map((a) => a.id),
      attachments: [...pendingAttachments.value],
      chatProviderId: selectedProviderId.value,
    })
    return
  }
  settingsVisible.value = true
}

function toggleMode() {
  mode.value = mode.value === 'draw' ? 'chat' : 'draw'
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
  if (!canSend.value || props.loading) return
  const chatProviderId = selectedProviderId.value
  const payload = {
    prompt: inputText.value.trim(),
    attachmentIds: pendingAttachments.value.map((a) => a.id),
    attachments: [...pendingAttachments.value],
    chatProviderId,
  }
  if (mode.value === 'draw') {
    emit('draw', payload)
    return
  }
  emit('send', payload)
  inputText.value = ''
  pendingAttachments.value = []
  selectedProviderId.value = defaultProviderId.value
  fullscreenInput.value = false
}

function handleInputKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    handleSend()
  }
}

async function selectHistoryImage(item: HistoryImageItem) {
  if (props.loading || referenceImportingId.value != null) return
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

function clearDraft() {
  inputText.value = ''
  pendingAttachments.value = []
}

defineExpose({ clearDraft })
</script>

<template>
  <div class="chat-input">
    <div v-if="pendingAttachments.length > 0" class="attachment-bar">
      <div
        v-for="att in pendingAttachments"
        :key="att.id"
        class="attachment-chip"
      >
        <el-image
          v-if="isImage(att.contentType)"
          :src="att.fileUrl"
          class="attachment-thumb"
          fit="cover"
        />
        <el-icon v-else class="attachment-icon"><Paperclip /></el-icon>
        <span class="attachment-name">{{ att.originalName }}</span>
        <el-button
          text
          size="small"
          type="danger"
          @click="removeAttachment(att.id)"
        >
          ✕
        </el-button>
      </div>
    </div>

    <div class="composer-main">
      <input
        ref="fileInputRef"
        type="file"
        :accept="mode === 'draw' ? 'image/*' : 'image/*,.pdf,.doc,.docx,.txt'"
        multiple
        hidden
        @change="handleFileChange"
      >
      <input
        ref="imageFileInputRef"
        type="file"
        accept="image/*"
        multiple
        hidden
        @change="handleFileChange"
      >
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

    <div class="composer-toolbar" role="toolbar" aria-label="创作工具">
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

      <button
        class="tool-btn"
        type="button"
        :disabled="loading || uploading"
        title="上传文件"
        aria-label="上传文件"
        @click="openFilePicker"
      >
        <el-icon><Paperclip /></el-icon>
      </button>

      <button
        class="tool-btn"
        type="button"
        :disabled="loading || uploading"
        :title="referenceImageCount ? `参考图 · ${referenceImageCount}` : '参考图'"
        :aria-label="referenceImageCount ? `参考图（已添加 ${referenceImageCount} 张）` : '添加参考图'"
        @click="openReferencePanel"
      >
        <el-icon><Picture /></el-icon>
        <em v-if="referenceImageCount" class="tool-badge">{{ referenceImageCount }}</em>
      </button>

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

      <span class="input-hint">Enter 发送, Shift+Enter 换行</span>

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
        :class="{ disabled: !canSend || loading }"
        :disabled="!canSend || loading"
        :title="mode === 'draw' ? '打开绘画面板' : '发送消息（Enter）'"
        @click="handleSend"
      >
        <el-icon v-if="mode === 'chat'"><Promotion /></el-icon>
        <span>{{ mode === 'draw' ? '生成' : '发送' }}</span>
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
        @input="autoResizeTextarea"
      />
      <div class="fullscreen-input-footer">
        <button
          class="send-button"
          type="button"
          :class="{ disabled: !canSend || loading }"
          :disabled="!canSend || loading"
          @click="handleSend"
        >
          <el-icon><Promotion /></el-icon>
          <span>{{ mode === 'draw' ? '生成' : '发送' }}</span>
        </button>
      </div>
    </div>

    <el-drawer v-model="settingsVisible" direction="btt" size="auto" class="desktop-composer-drawer" :with-header="false">
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

    <el-drawer v-model="referenceVisible" direction="btt" size="auto" class="desktop-composer-drawer" :with-header="false">
      <div class="drawer-title">
        <div>
          <strong>添加参考图</strong>
          <span>从系统上传或选择历史作品</span>
        </div>
      </div>
      <div class="reference-panel">
        <button
          type="button"
          class="reference-upload-entry"
          :disabled="loading || uploading"
          @click="openImageFilePicker"
        >
          <el-icon><UploadFilled /></el-icon>
          <span>
            <strong>从系统上传</strong>
            <small>打开文件选择器添加本地图片</small>
          </span>
        </button>
        <template v-if="historyImages.length">
          <div class="reference-history-label">从历史作品选择</div>
          <div class="history-reference-grid">
            <button
              v-for="item in historyImages"
              :key="item.id"
              type="button"
              class="history-reference-tile"
              :disabled="referenceImportingId != null || loading"
              @click="selectHistoryImage(item)"
            >
              <el-image :src="item.thumbUrl || item.url" fit="cover" />
              <span v-if="referenceImportingId === item.messageId" class="history-reference-status">添加中…</span>
              <small>{{ item.label }}</small>
            </button>
          </div>
        </template>
        <p v-else class="reference-empty-hint">当前会话还没有历史作品，可先从系统上传。</p>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.chat-input {
  margin: 0 clamp(12px, 3vw, 36px) 18px;
  padding: 12px 14px 10px;
  border: 1px solid rgba(220, 225, 244, .95);
  border-radius: 18px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 12px 32px rgba(46, 59, 117, .1);
  backdrop-filter: blur(12px);
}
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
.tool-btn:disabled { opacity: .45; cursor: not-allowed; }
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

.fullscreen-input-overlay {
  position: fixed;
  z-index: 2200;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #f7f9fd;
}
.fullscreen-input-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: 56px;
  padding: 10px 18px;
  border-bottom: 1px solid #e5e9f2;
  background: rgba(255, 255, 255, .96);
}
.fullscreen-input-title { color: #2e3b58; font-size: 16px; font-weight: 700; }
.fullscreen-input-exit {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  color: #5a6a8a;
  cursor: pointer;
  border: 0;
  border-radius: 11px;
  background: #edf0f6;
}
.fullscreen-textarea {
  flex: 1;
  width: 100%;
  margin: 0;
  padding: 22px 24px;
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
  justify-content: flex-end;
  padding: 12px 18px;
  border-top: 1px solid #e5e9f2;
  background: rgba(255, 255, 255, .96);
}

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
.reference-upload-entry {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  min-height: 64px;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  text-align: left;
  cursor: pointer;
  border: 1px solid #e1e7f5;
  border-radius: 14px;
  background: linear-gradient(180deg, #f7f9ff 0%, #eef3ff 100%);
}
.reference-upload-entry:disabled { opacity: .55; cursor: not-allowed; }
.reference-upload-entry .el-icon { font-size: 26px; color: #5b8ff9; }
.reference-upload-entry span { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.reference-upload-entry strong { color: #314063; font-size: 14px; }
.reference-upload-entry small { color: #8a94aa; font-size: 11px; }
.reference-history-label { color: #6d7890; font-size: 12px; font-weight: 700; }
.reference-empty-hint { margin: 0; padding: 8px 4px; color: #9aa3b5; font-size: 12px; text-align: center; }
.history-reference-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  max-height: min(42vh, 360px);
  overflow-y: auto;
}
.history-reference-tile {
  position: relative;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  border: 1px solid #e5e9f2;
  border-radius: 11px;
  background: #f5f6fa;
}
.history-reference-tile:disabled { cursor: wait; opacity: .7; }
.history-reference-tile :deep(.el-image) { display: block; width: 100%; height: 88px; }
.history-reference-tile small {
  display: block;
  overflow: hidden;
  padding: 7px;
  color: #68738d;
  font-size: 10px;
  text-overflow: ellipsis;
  white-space: nowrap;
  background: #fff;
}
.history-reference-status {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 3px 6px;
  color: #fff;
  font-size: 9px;
  border-radius: 99px;
  background: rgba(65, 78, 151, .88);
}

:deep(.desktop-composer-drawer.el-drawer) {
  max-width: 720px;
  margin: 0 auto;
  border-radius: 20px 20px 0 0;
}
:deep(.desktop-composer-drawer .el-drawer__body) {
  padding: 18px 18px calc(18px + env(safe-area-inset-bottom, 0px));
}

@media (max-width: 780px) {
  .chat-input { margin: 0 12px 12px; }
  .input-hint { display: none; }
  .history-reference-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 560px) {
  .composer-toolbar { flex-wrap: wrap; }
  .send-button, .cancel-btn { min-width: 64px; }
  .history-reference-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
