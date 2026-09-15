<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import type { Message, ModelProvider } from '@/types'
import { providerLabel, sessionDefaultUsageHint, temporaryUsageHint } from '@/utils/modelDisplay'

const props = defineProps<{
  visible: boolean
  action: 'regenerate' | 'resend'
  message: Message | null
  chatProviders: ModelProvider[]
  imageProviders: ModelProvider[]
  defaultChatProviderId: number | null
  defaultImageProviderId: number | null
  loading: boolean
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  confirm: [payload: {
    chatProviderId: number | null
    imageProviderId: number | null
  }]
}>()

const selectedProviderId = ref<number | null>(null)

const isDraw = computed(() => props.message?.messageType === 'DRAW_REQUEST'
  || props.message?.messageType === 'DRAW_RESPONSE')
const providers = computed(() => isDraw.value ? props.imageProviders : props.chatProviders)
/** Session default this dialog would otherwise use — only for the visible hint. */
const sessionDefaultProviderId = computed(() => isDraw.value
  ? props.defaultImageProviderId
  : props.defaultChatProviderId)

function resolveProviderId(preferredId: number | null | undefined, options: ModelProvider[]) {
  if (preferredId != null && options.some((provider) => provider.id === preferredId)) {
    return preferredId
  }
  return options.find((provider) => provider.active)?.id ?? null
}

/**
 * The model this message actually used. Falls back to the session default only
 * when the message carries no usable record (legacy rows / user messages), never
 * to an unrelated current selection.
 */
function messageUsedProviderId(): number | null {
  const message = props.message
  if (!message) return null
  if (isDraw.value) {
    return message.drawProviderId ?? props.defaultImageProviderId
  }
  return message.chatProviderId ?? props.defaultChatProviderId
}

const dialogTitle = computed(() => {
  if (props.action === 'resend') return isDraw.value ? '再次生成图片' : '再次发送消息'
  return isDraw.value ? '重新生成图片' : '重新生成回复'
})
const actionLabel = computed(() => props.action === 'resend' ? '再次发送' : '重新生成')
const selectedProvider = computed(() => selectedProviderId.value == null
  ? null
  : providers.value.find((provider) => provider.id === selectedProviderId.value) || null)
const sessionDefaultProvider = computed(() => sessionDefaultProviderId.value == null
  ? null
  : providers.value.find((provider) => provider.id === sessionDefaultProviderId.value) || null)

/** Unified with every other temporary-switch entry point. */
const usageText = computed(() => {
  const selected = selectedProvider.value
  if (!selected) return ''
  const label = providerLabel(selected)
  if (selectedProviderId.value === sessionDefaultProviderId.value) {
    return sessionDefaultUsageHint(providerLabel(sessionDefaultProvider.value, '系统默认'))
  }
  return temporaryUsageHint(label)
})

watch(() => props.visible, (visible) => {
  if (!visible) return
  selectedProviderId.value = resolveProviderId(messageUsedProviderId(), providers.value)
})

function handleConfirm() {
  emit('confirm', {
    chatProviderId: isDraw.value ? null : selectedProviderId.value,
    imageProviderId: isDraw.value ? selectedProviderId.value : null,
  })
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="dialogTitle"
    width="520px"
    :close-on-click-modal="!loading"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="regenerate-dialog">
      <div class="message-preview">
        <span class="preview-label">{{ action === 'resend' ? '原消息' : '对应内容' }}</span>
        <div>{{ message?.drawPrompt || message?.content || '无文本内容' }}</div>
      </div>

      <el-form label-width="88px" label-position="left">
        <el-form-item :label="isDraw ? '绘图模型' : '对话模型'">
          <div class="provider-field">
            <el-select v-model="selectedProviderId" placeholder="暂无可用模型">
              <el-option
                v-for="provider in providers"
                :key="provider.id"
                :label="providerLabel(provider)"
                :value="provider.id"
              />
            </el-select>
            <span class="field-hint">默认选中这条消息当时实际使用的模型；可临时改选，仅影响本次{{ actionLabel }}，不修改会话默认模型。</span>
          </div>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="selectedProvider"
        type="info"
        :closable="false"
        show-icon
      >
        <template #title>
          {{ usageText }}
        </template>
      </el-alert>
      <el-alert
        v-else
        type="warning"
        :closable="false"
        show-icon
        title="当前没有可用的模型配置。"
      />
    </div>

    <template #footer>
      <el-button :disabled="loading" @click="emit('update:visible', false)">取消</el-button>
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="handleConfirm">
        {{ actionLabel }}
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.regenerate-dialog {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message-preview {
  max-height: 150px;
  overflow: auto;
  padding: 12px;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  background: #f7f8fa;
  color: #606266;
  font-size: 13px;
  white-space: pre-wrap;
  word-break: break-word;
}

.preview-label {
  display: block;
  margin-bottom: 6px;
  color: #909399;
  font-size: 12px;
}

.provider-field {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-hint {
  color: #909399;
  font-size: 12px;
}
</style>
