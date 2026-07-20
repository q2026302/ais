<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Paperclip, Picture } from '@element-plus/icons-vue'
import type { ModelProvider, UploadResponse } from '@/types'
import { sessionApi } from '@/api/sessions'

const props = defineProps<{
  loading: boolean
  cancelable?: boolean
  providerOptions: ModelProvider[]
  activeSessionId: number | null
  activeChatProviderId: number | null
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

const canSend = computed(() => inputText.value.trim().length > 0 || pendingAttachments.value.length > 0)
const defaultProviderId = computed(() => {
  if (
    props.activeChatProviderId != null
    && props.providerOptions.some((provider) => provider.id === props.activeChatProviderId)
  ) {
    return props.activeChatProviderId
  }
  return props.providerOptions.find((provider) => provider.active)?.id ?? null
})

// Sync the actual effective default model to local temporary selection.
watch(
  () => [props.activeSessionId, defaultProviderId.value] as const,
  ([, providerId]) => {
    selectedProviderId.value = providerId
  },
  { immediate: true },
)

function isImage(contentType: string): boolean {
  return contentType.startsWith('image/')
}

async function handleFileSelect(file: any) {
  const raw = file.raw as File
  if (!raw) return
  await uploadFile(raw)
}

async function uploadFile(file: File) {
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
    for (const f of imageFiles) {
      await uploadFile(f)
    }
  }
}

function removeAttachment(id: number) {
  pendingAttachments.value = pendingAttachments.value.filter((a) => a.id !== id)
}

function handleSend() {
  if (!canSend.value || props.loading) return
  const chatProviderId = selectedProviderId.value
  emit('send', {
    prompt: inputText.value.trim(),
    attachmentIds: pendingAttachments.value.map((a) => a.id),
    attachments: [...pendingAttachments.value],
    chatProviderId,
  })
  inputText.value = ''
  pendingAttachments.value = []
  selectedProviderId.value = defaultProviderId.value
}

function handleInputKeydown(event: KeyboardEvent) {
  if (event.isComposing || !event.ctrlKey || event.key !== 'Enter') return
  event.preventDefault()
  handleSend()
}

function emitProviderChange(val: number | null) {
  selectedProviderId.value = val
}

function clearDraft() {
  inputText.value = ''
  pendingAttachments.value = []
}

function handleDraw() {
  if (props.loading) return
  emit('draw', {
    prompt: inputText.value.trim(),
    attachmentIds: pendingAttachments.value.map((a) => a.id),
    attachments: [...pendingAttachments.value],
    chatProviderId: selectedProviderId.value,
  })
}

defineExpose({ clearDraft })
</script>

<template>
  <div class="chat-input">
    <!-- Attachment preview bar -->
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

    <!-- Input row -->
    <div class="input-row">
      <el-input
        v-model="inputText"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        :disabled="loading"
        placeholder="输入消息，或输入 /help 查看系统命令 (Ctrl+V 粘贴图片)"
        resize="none"
        @paste="handlePaste"
        @keydown="handleInputKeydown"
      />
    </div>

    <!-- Toolbar -->
    <div class="toolbar">
      <el-upload
        :show-file-list="false"
        :auto-upload="false"
        :on-change="handleFileSelect"
        accept="image/*,.pdf,.doc,.docx,.txt"
      >
        <el-button :icon="Paperclip" size="small" text :loading="uploading" aria-label="添加附件" title="添加附件（支持图片、PDF、文档和文本）" />
      </el-upload>

      <el-select
        :model-value="selectedProviderId"
        placeholder="暂无可用对话模型"
        size="small"
        class="provider-select"
        @change="emitProviderChange"
      >
        <el-option
          v-for="p in providerOptions"
          :key="p.id"
          :label="`${p.name || p.providerId} / ${p.modelName}`"
          :value="p.id"
        />
      </el-select>

      <span class="temporary-model-hint">发送时使用的对话模型</span>
      <span class="input-hint">Enter 换行，Ctrl+Enter 发送</span>

      <el-button
        type="success"
        :disabled="loading"
        :icon="Picture"
        @click="handleDraw"
        class="draw-btn"
        title="打开绘图面板"
      >
        绘图
      </el-button>

      <el-button
        v-if="loading && cancelable"
        type="danger"
        plain
        @click="emit('cancel')"
        class="cancel-btn"
        title="终止当前请求"
      >
        终止
      </el-button>
      <el-button
        v-else
        type="primary"
        :loading="loading"
        :disabled="!canSend"
        @click="handleSend"
        class="send-btn"
        title="发送消息（Ctrl+Enter）"
      >
        发送
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.chat-input {
  margin: 0 clamp(12px, 3vw, 36px) 18px;
  padding: 11px 13px 10px;
  border: 1px solid rgba(220, 225, 244, .95);
  border-radius: 18px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 12px 32px rgba(46, 59, 117, .1);
  backdrop-filter: blur(12px);
}
.attachment-bar { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 2px 9px; border-bottom: 1px solid #edf0f8; }
.attachment-chip { display: flex; align-items: center; gap: 7px; padding: 5px 6px 5px 7px; font-size: 12px; border: 1px solid #e5e9f7; border-radius: 9px; background: #f7f8fe; }
.attachment-thumb { width: 30px; height: 30px; border-radius: 6px; }
.attachment-icon { color: #8791ab; font-size: 16px; }
.attachment-name { max-width: 120px; overflow: hidden; color: #59647d; text-overflow: ellipsis; white-space: nowrap; }
.input-row { padding-top: 2px; }
.input-row :deep(.el-textarea__inner),
.input-row :deep(.el-textarea__inner:hover),
.input-row :deep(.el-textarea__inner:focus) { padding: 11px 12px; color: #38435f; border: 0; border-radius: 12px; background: transparent; box-shadow: none; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-top: 3px; padding: 8px 2px 0; border-top: 1px solid #eef0f7; }
.toolbar :deep(.el-button.is-text) { color: #697691; border-radius: 8px; }
.toolbar :deep(.el-button.is-text:hover) { color: var(--app-primary); background: #f0f2ff; }
.provider-select { width: 205px; }
.provider-select :deep(.el-select__wrapper) { min-height: 30px; border-radius: 8px; background: #f7f8fd; box-shadow: none; }
.temporary-model-hint { padding: 3px 7px; color: #7d87a1; font-size: 10px; white-space: nowrap; border-radius: 5px; background: #f2f3f9; }
.input-hint { flex: 1; margin-right: 3px; color: #adb4c5; font-size: 11px; text-align: right; }
.draw-btn, .send-btn, .cancel-btn { flex-shrink: 0; min-width: 70px; border-radius: 9px; }
.draw-btn { border: 0; background: linear-gradient(135deg, #21a777, #31bd9a); box-shadow: 0 5px 12px rgba(31, 167, 119, .2); }
.draw-btn:hover { background: linear-gradient(135deg, #1d956b, #28a986); transform: translateY(-1px); }
.send-btn:hover { transform: translateY(-1px); }

@media (max-width: 780px) { .chat-input { margin: 0 12px 12px; } .temporary-model-hint { display: none; } .provider-select { width: 170px; } }
@media (max-width: 560px) { .toolbar { flex-wrap: wrap; } .input-hint { display: none; } .provider-select { flex: 1; } .send-btn, .draw-btn { min-width: 48px; } }
</style>
