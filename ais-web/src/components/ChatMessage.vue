<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Message, ModelProvider } from '@/types'
import { ElMessage } from 'element-plus'
import { CopyDocument, Edit, Refresh, Delete, Download } from '@element-plus/icons-vue'
import CollapsibleMessageText from '@/components/CollapsibleMessageText.vue'
import { getThumbnailUrl } from '@/utils/imageUrl'
import { formatDateTimeSeconds } from '@/utils/dateTime'

const props = defineProps<{
  message: Message
  editingMessageId: number | null
  chatProvider?: ModelProvider | null
}>()

const emit = defineEmits<{
  edit: [messageId: number]
  resend: [messageId: number]
  regenerate: [messageId: number]
  delete: [messageId: number]
  copy: [content: string]
  saveEdit: [messageId: number, content: string]
  refresh: [messageId: number]
}>()

const editContent = ref('')
const thumbFailed = ref(false)
const thumbUrl = computed(() => thumbFailed.value ? props.message.imageUrl : getThumbnailUrl(props.message.id))
function onThumbError() { thumbFailed.value = true }

const displayName = computed(() => {
  if (props.message.role === 'USER') return '我'
  if (!props.chatProvider) return 'LLM'
  const providerName = props.chatProvider.providerId || props.chatProvider.name
  return `${providerName}|${props.chatProvider.modelName}`
})

watch(() => props.editingMessageId, (val) => {
  if (val === props.message.id) {
    editContent.value = props.message.content
  }
})

function copyContent() {
  if (props.message.content) {
    navigator.clipboard.writeText(props.message.content)
    ElMessage.success('已复制')
    emit('copy', props.message.content)
  }
}

async function copyPrompt() {
  const prompt = props.message.drawPrompt?.trim()
  if (!prompt) return
  try {
    await navigator.clipboard.writeText(prompt)
    ElMessage.success('提示词已复制')
  } catch {
    ElMessage.error('复制提示词失败，请手动选择复制')
  }
}

function handleSaveEdit() {
  if (editContent.value.trim()) {
    emit('saveEdit', props.message.id, editContent.value.trim())
  }
}

function handleCancelEdit() {
  emit('saveEdit', props.message.id, props.message.content)
}

function isImageUrl(url: string | null): boolean {
  if (!url) return false
  const cleanUrl = url.split('?')[0]?.toLowerCase() || ''
  return ['.png', '.jpg', '.jpeg', '.webp', '.gif'].some((ext) => cleanUrl.endsWith(ext))
}

function imageFilename(url: string) {
  const path = url.split('?')[0] || url
  const rawName = path.split('/').filter(Boolean).pop() || `generated-image.${props.message.drawFormat || 'png'}`
  return rawName.includes('.') ? rawName : `${rawName}.${props.message.drawFormat || 'png'}`
}

async function downloadImage() {
  const url = props.message.imageUrl
  if (!url) return
  const filename = imageFilename(url)
  try {
    const response = await fetch(url)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const blob = await response.blob()
    const objectUrl = URL.createObjectURL(blob)
    triggerDownload(objectUrl, filename)
    URL.revokeObjectURL(objectUrl)
  } catch (e) {
    // Fall back to a normal browser download if fetch is blocked.
    triggerDownload(url, filename)
  }
}

function triggerDownload(url: string, filename: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.rel = 'noopener'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}


function isAttachmentImage(contentType: string): boolean {
  return contentType.startsWith('image/')
}


const isDrawResponse = computed(() => props.message.messageType === 'DRAW_RESPONSE')
const isDrawPending = computed(() => isDrawResponse.value && props.message.status === 'PENDING')
const isDrawFailed = computed(() => isDrawResponse.value && props.message.status === 'FAILED')
const isChatPending = computed(() => props.message.role === 'ASSISTANT'
  && (props.message.messageType == null || props.message.messageType === 'CHAT')
  && props.message.status === 'PENDING')
const isChatFailed = computed(() => props.message.role === 'ASSISTANT'
  && (props.message.messageType == null || props.message.messageType === 'CHAT')
  && props.message.status === 'FAILED')
const isPending = computed(() => isDrawPending.value || isChatPending.value)
const drawProcessingText = computed(() => props.message.processingInfo || '正在排队')


const placeholderStyle = computed(() => {
  const size = props.message.drawSize || props.message.drawPlaceholder?.size || '1024x1024'
  const match = size.match(/(\d+)x(\d+)/)
  const width = match ? Number(match[1]) : 1024
  const height = match ? Number(match[2]) : 1024
  const max = 260
  const ratio = Math.min(max / width, max / height, 1)
  return {
    width: `${Math.max(120, Math.round(width * ratio))}px`,
    height: `${Math.max(120, Math.round(height * ratio))}px`,
  }
})

function formatTokens(tokens: number | null | undefined): string {
  if (tokens == null) return '-'
  if (tokens >= 1000) return (tokens / 1000).toFixed(1) + 'k'
  return String(tokens)
}

function formatDateTime(dateStr: string): string {
  return formatDateTimeSeconds(dateStr, dateStr)
}
</script>

<template>
  <div class="message" :class="message.role.toLowerCase()">
    <div class="avatar">
      {{ message.role === 'USER' ? '👤' : '🤖' }}
    </div>
    <div class="message-body">
      <div class="message-header">
        <div class="message-name" :title="displayName">{{ displayName }}</div>
        <div class="message-date">{{ formatDateTime(message.createdAt) }}</div>
      </div>
      <div class="bubble">
      <!-- Inline edit mode for user messages -->
      <div v-if="editingMessageId === message.id" class="edit-mode">
        <el-input
          v-model="editContent"
          type="textarea"
          :rows="3"
          placeholder="编辑消息..."
        />
        <div class="edit-actions">
          <el-button size="small" @click="handleCancelEdit">取消</el-button>
          <el-button size="small" type="primary" @click="handleSaveEdit">保存并重新生成</el-button>
        </div>
      </div>

      <template v-else>
        <!-- Attachment display (user messages) -->
        <div v-if="message.attachments && message.attachments.length > 0" class="attachments">
          <div
            v-for="att in message.attachments"
            :key="att.id"
            class="attachment-item"
          >
            <el-image
              v-if="isAttachmentImage(att.contentType)"
              :src="att.fileUrl"
              class="msg-attachment-thumb"
              fit="cover"
              :preview-src-list="[att.fileUrl]"
              preview-teleported
            />
            <a v-else :href="att.fileUrl" target="_blank" class="attachment-link">
              {{ att.originalName }}
            </a>
          </div>
        </div>

        <!-- Text content -->
        <CollapsibleMessageText
          v-if="message.content && !isChatPending"
          class="text"
          :content="message.content"
        />

        <div v-if="isChatPending" class="typing-indicator" aria-label="等待 AI 回应">
          <span></span>
          <span></span>
          <span></span>
        </div>

        <!-- Generated image (assistant messages) -->
        <div v-if="message.imageUrl && isImageUrl(message.imageUrl)" class="image-container">
          <el-image
            :src="thumbUrl"
            fit="contain"
            :preview-src-list="[message.imageUrl]"
            preview-teleported
            class="generated-image"
            @error="onThumbError"
          />
          <div class="image-info">
            <span v-if="message.drawSize">尺寸：{{ message.drawSize }}</span>
            <span v-if="message.drawQuality">质量：{{ message.drawQuality }}</span>
            <span>生成时间：{{ formatDateTime(message.createdAt) }}</span>
            <span v-if="message.drawPrompt" class="image-prompt" :title="message.drawPrompt">
              提示词：{{ message.drawPrompt }}
              <el-button text size="small" :icon="CopyDocument" class="prompt-copy" @click="copyPrompt">复制</el-button>
            </span>
          </div>
          <div class="image-actions">
            <el-button size="small" :icon="Download" @click="downloadImage">下载图片</el-button>
          </div>
        </div>

        <div
          v-else-if="isDrawPending || message.drawPlaceholder"
          class="draw-placeholder"
          :style="placeholderStyle"
        >
          <div class="placeholder-icon">🎨</div>
          <div class="placeholder-text">{{ drawProcessingText }}</div>
          <div class="placeholder-meta">
            {{ message.drawSize || message.drawPlaceholder?.size || '默认尺寸' }}
            <span v-if="message.drawFormat || message.drawPlaceholder?.format">
              · {{ (message.drawFormat || message.drawPlaceholder?.format || '').toUpperCase() }}
            </span>
          </div>
        </div>

        <div v-if="isDrawFailed" class="draw-error">
          <div class="draw-error-title">图片生成失败</div>
          <div class="draw-error-detail">{{ message.errorMessage || '模型未返回错误详情，请稍后重试。' }}</div>
        </div>

        <div v-if="isChatFailed" class="draw-error">
          <div class="draw-error-title">AI 回应失败</div>
          <div class="draw-error-detail">{{ message.errorMessage || '模型未返回错误详情，请稍后重试。' }}</div>
        </div>

        <!-- Footer: edited badge, time, token count -->
        <div class="message-footer">
          <span v-if="message.edited" class="edited-badge">(已编辑)</span>
          <span v-if="isDrawPending" class="status-badge pending">{{ drawProcessingText }}</span>
          <span v-else-if="isChatPending" class="status-badge pending">等待回应</span>
          <span v-else-if="isDrawFailed || isChatFailed" class="status-badge failed">失败</span>
          <span v-if="message.tokenUsage" class="token-usage">
            Tokens: {{ formatTokens(message.tokenUsage.promptTokens) }}/{{
              formatTokens(message.tokenUsage.completionTokens)
            }}/{{ formatTokens(message.tokenUsage.totalTokens) }}
          </span>
        </div>
      </template>

      <!-- Action buttons (hover reveal) -->
      <div v-if="editingMessageId !== message.id" class="actions">
        <el-button text size="small" @click="copyContent" :icon="CopyDocument" title="复制" />
        <el-button
          v-if="message.role === 'USER'"
          text
          size="small"
          @click="emit('edit', message.id)"
          :icon="Edit"
          title="编辑"
        />
        <el-button
          v-if="message.role === 'USER'"
          text
          size="small"
          @click="emit('resend', message.id)"
          :icon="Refresh"
          title="再次发送"
        />
        <el-button
          v-if="message.role === 'ASSISTANT' && !isPending"
          text
          size="small"
          @click="emit('regenerate', message.id)"
          :icon="Refresh"
          title="重新生成"
        />
        <el-button
          v-if="isDrawPending"
          text
          size="small"
          @click="emit('refresh', message.id)"
          :icon="Refresh"
          title="刷新生成状态"
        />
        <el-button
          text
          size="small"
          type="danger"
          @click="emit('delete', message.id)"
          :icon="Delete"
          title="删除"
        />
      </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.message { display: flex; gap: 11px; max-width: min(87%, 860px); margin-bottom: 22px; align-self: flex-start; }
.avatar { display: flex; flex: 0 0 auto; align-items: center; justify-content: center; width: 36px; height: 36px; font-size: 17px; border: 1px solid #e5e8f7; border-radius: 12px; background: linear-gradient(145deg, #fff, #edf0fb); box-shadow: 0 5px 12px rgba(62, 76, 134, .08); }
.message.user .avatar { border: 0; background: linear-gradient(135deg, #5b6df7, #9b5cf3); box-shadow: 0 7px 14px rgba(83, 96, 231, .24); }
.message-body { min-width: 0; max-width: 100%; }
.message-header { display: flex; flex-direction: column; gap: 2px; margin: 0 5px 6px; }
.message-name { max-width: 100%; overflow: hidden; color: #495572; font-size: 12px; font-weight: 800; text-overflow: ellipsis; white-space: nowrap; }
.message-date { color: #a0a8ba; font-size: 10px; line-height: 1.4; }
.message.user .message-name { color: #5968d0; }
.bubble { position: relative; padding: 13px 16px; border: 1px solid #e8ebf6; border-radius: 5px 15px 15px 15px; background: rgba(255, 255, 255, .94); box-shadow: 0 8px 21px rgba(42, 55, 113, .065); }
.message.user .bubble { color: #fff; border: 0; border-radius: 5px 15px 15px 15px; background: linear-gradient(135deg, #5b70f7 0%, #805de8 100%); box-shadow: 0 9px 20px rgba(83, 90, 222, .22); }
.text { font-size: 14px; line-height: 1.68; white-space: pre-wrap; word-break: break-word; }
.attachments { display: flex; flex-wrap: wrap; gap: 7px; margin-bottom: 9px; }
.attachment-item { overflow: hidden; border-radius: 10px; }
.msg-attachment-thumb { width: 82px; height: 82px; cursor: pointer; border: 1px solid rgba(255, 255, 255, .65); border-radius: 10px; }
.attachment-link { color: inherit; font-size: 12px; text-decoration: underline; text-underline-offset: 2px; }
.image-container { max-width: 520px; margin-top: 9px; overflow: hidden; border: 1px solid #e9ecf7; border-radius: 13px; background: #f7f8fc; }
.generated-image { display: block; width: 100%; border-radius: 12px 12px 0 0; }
.image-info { display: flex; flex-wrap: wrap; gap: 5px 11px; padding: 9px 10px 0; color: #8690a9; font-size: 11px; }
.image-prompt { width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.prompt-copy { margin-left: 3px; }
.image-actions { display: flex; justify-content: flex-end; padding: 6px 7px 8px; }
.typing-indicator { display: inline-flex; align-items: center; gap: 5px; min-width: 52px; padding: 8px 2px; }
.typing-indicator span { width: 7px; height: 7px; border-radius: 50%; background: #7784e8; animation: typing-bounce 1s infinite ease-in-out; }
.typing-indicator span:nth-child(2) { animation-delay: .15s; }.typing-indicator span:nth-child(3) { animation-delay: .3s; }
@keyframes typing-bounce { 0%,80%,100% { opacity: .4; transform: translateY(0); } 40% { opacity: 1; transform: translateY(-5px); } }
.draw-placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; min-width: 120px; min-height: 120px; margin-top: 8px; color: #5569e8; border: 1px dashed #b9c2ff; border-radius: 13px; background: linear-gradient(135deg, #f6f7ff, #eef1ff); }
.placeholder-icon { font-size: 29px; }.placeholder-text { font-size: 13px; font-weight: 700; }.placeholder-meta { color: #8290e9; font-size: 11px; }
.message-footer { display: flex; align-items: center; gap: 8px; margin-top: 7px; }.edited-badge { color: #909ab2; font-size: 11px; }.message.user .edited-badge { color: rgba(255,255,255,.75); }.token-usage { color: #acb3c6; font-family: monospace; font-size: 10px; }.message.user .token-usage { color: rgba(255,255,255,.68); }
.draw-error { margin-top: 9px; padding: 11px 12px; color: #bd4d56; font-size: 13px; border: 1px solid #ffd9dc; border-radius: 10px; background: #fff4f4; }.draw-error-title { margin-bottom: 4px; font-weight: 700; }.draw-error-detail { max-height: 240px; overflow: auto; white-space: pre-wrap; word-break: break-word; opacity: .9; }
.status-badge { padding: 2px 7px; font-size: 10px; border-radius: 999px; }.status-badge.pending { color: #5064d9; background: #edf0ff; }.status-badge.failed { color: #d65760; background: #fff0f1; }
.actions { position: absolute; top: -31px; right: 0; display: flex; gap: 2px; padding: 3px 4px; opacity: 0; border: 1px solid #e8ebf6; border-radius: 9px; background: rgba(255,255,255,.96); box-shadow: 0 5px 15px rgba(37,48,100,.12); transition: opacity .15s, transform .15s; transform: translateY(3px); }.message:hover .actions { opacity: 1; transform: translateY(0); }
.edit-mode { display: flex; flex-direction: column; gap: 8px; }.edit-actions { display: flex; justify-content: flex-end; gap: 8px; }
@media (max-width: 600px) { .message { max-width: 96%; margin-bottom: 17px; } .avatar { width: 31px; height: 31px; font-size: 15px; } .bubble { padding: 11px 13px; } .image-container { max-width: 100%; } }
</style>
