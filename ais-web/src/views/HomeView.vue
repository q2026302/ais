<script setup lang="ts">
import { computed, ref, onMounted, onActivated, onDeactivated, nextTick, watch, h } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Menu } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import SessionSidebar from '@/components/SessionSidebar.vue'
import ChatMessage from '@/components/ChatMessage.vue'
import ChatInput from '@/components/ChatInput.vue'
import ModelSelector from '@/components/ModelSelector.vue'
import DrawDialog from '@/components/DrawDialog.vue'
import RegenerateDialog from '@/components/RegenerateDialog.vue'
import ImageGallery from '@/components/ImageGallery.vue'
import type { Message, ModelProvider, UploadResponse } from '@/types'
import { CHAT_COMMAND_HELP, parseChatCommand } from '@/utils/chatCommands'

const store = useSessionStore()
const messagesContainer = ref<HTMLElement | null>(null)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)
const sending = ref(false)
const regenerateDialogVisible = ref(false)
const regenerateAction = ref<'regenerate' | 'resend'>('regenerate')
const regenerateTargetId = ref<number | null>(null)

const drawDialogVisible = ref(false)
const drawInitialPrompt = ref('')
const drawInitialReferences = ref<UploadResponse[]>([])
const drawSmartParseInitialPrompt = ref(false)
const showScrollToBottom = ref(false)
const viewMode = ref<'conversation' | 'gallery'>('conversation')
const savedScrollTop = ref(0)
const initialized = ref(false)
const sidebarOpen = ref(false)

const chatProviderId = ref<number | null>(null)
const imageProviderId = ref<number | null>(null)

const activeSessionTitle = computed(() => {
  if (!store.activeSessionId) return 'AI 图像创作'
  const session = store.sessions.find((item) => item.id === store.activeSessionId)
  return session?.title || '未命名会话'
})

const selectedChatProvider = computed<ModelProvider | null>(() => {
  if (chatProviderId.value != null) {
    return store.chatProviders.find((provider) => provider.id === chatProviderId.value) || null
  }
  return store.chatProviders.find((provider) => provider.active) || null
})

const regenerateTarget = computed<Message | null>(() => {
  if (regenerateTargetId.value == null) return null
  return store.messages.find((message) => message.id === regenerateTargetId.value) || null
})

watch(() => store.activeSessionId, (sessionId) => {
  if (sessionId == null) return
  const session = store.sessions.find((item) => item.id === sessionId)
  if (!session) return
  chatProviderId.value = session.chatProviderId
  imageProviderId.value = session.imageProviderId
})

onMounted(async () => {
  await Promise.all([store.fetchSessions(), store.fetchProviders()])
  if (store.activeSessionId == null && store.sessions.length > 0) {
    const first = store.sessions[0]
    if (first) {
      await store.selectSession(first.id)
      await nextTick()
      scrollToBottom()
    }
  } else if (store.activeSessionId != null && store.messages.length === 0) {
    await store.selectSession(store.activeSessionId)
    await nextTick()
    scrollToBottom()
  }
  initialized.value = true
})

onDeactivated(() => {
  savedScrollTop.value = messagesContainer.value?.scrollTop || 0
})

onActivated(async () => {
  if (!initialized.value) return
  await store.fetchProviders()
  await nextTick()
  if (messagesContainer.value) {
    messagesContainer.value.scrollTop = savedScrollTop.value
    updateScrollToBottomVisibility()
  }
})

watch(() => store.messages.length, () => {
  nextTick(updateScrollToBottomVisibility)
})

type ChatInputPayload = {
  prompt: string
  attachmentIds: number[]
  attachments?: UploadResponse[]
  chatProviderId: number | null
}

async function showCommandDialog(title: string, content: string) {
  await ElMessageBox({
    title,
    message: () => h('pre', { style: 'margin: 0; white-space: pre-wrap; font: inherit; line-height: 1.65;' }, content),
    confirmButtonText: '关闭',
  })
}

function parseId(argument: string): number | null {
  if (!/^\d+$/.test(argument)) return null
  const id = Number(argument)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

function sessionListText() {
  if (store.sessions.length === 0) return '暂无会话。使用 /new 创建一个新会话。'
  return store.sessions.map((session) => {
    const active = session.id === store.activeSessionId ? '（当前）' : ''
    return `#${session.id}  ${session.title || '新会话'} ${active}`
  }).join('\n')
}

function modelListText() {
  if (store.chatProviders.length === 0) return '暂无可用对话模型。请先在管理页面配置模型。'
  return store.chatProviders.map((provider) => {
    const active = provider.id === chatProviderId.value ? '（当前会话）' : provider.active ? '（系统默认）' : ''
    return `#${provider.id}  ${provider.name || provider.providerId} / ${provider.modelName} ${active}`
  }).join('\n')
}

async function handleSystemCommand(payload: ChatInputPayload): Promise<boolean> {
  const command = parseChatCommand(payload.prompt)
  if (!command) return false

  if (payload.attachmentIds.length > 0 && command.name !== 'draw') {
    ElMessage.warning('系统命令不能携带附件；请移除附件后重试。')
    return true
  }

  switch (command.name) {
    case 'help':
      await showCommandDialog('系统命令', CHAT_COMMAND_HELP)
      return true
    case 'new': {
      const session = await store.createSession(command.argument || undefined)
      await store.selectSession(session.id)
      ElMessage.success(`已切换到新会话 #${session.id}`)
      return true
    }
    case 'sessions':
      await store.fetchSessions()
      await showCommandDialog('会话列表', sessionListText())
      return true
    case 'switch': {
      const sessionId = parseId(command.argument)
      if (sessionId == null) {
        ElMessage.warning('用法：/switch <会话ID>')
        return true
      }
      if (!store.sessions.some((session) => session.id === sessionId)) {
        ElMessage.warning(`未找到会话 #${sessionId}，可使用 /sessions 查看列表。`)
        return true
      }
      await store.selectSession(sessionId)
      ElMessage.success(`已切换到会话 #${sessionId}`)
      await nextTick()
      scrollToBottom()
      return true
    }
    case 'rename': {
      if (store.activeSessionId == null) {
        ElMessage.warning('请先创建或切换到一个会话。')
        return true
      }
      if (!command.argument) {
        ElMessage.warning('用法：/rename <新标题>')
        return true
      }
      const session = await store.updateSessionTitle(store.activeSessionId, command.argument)
      ElMessage.success(`会话已重命名为“${session.title}”`)
      return true
    }
    case 'delete': {
      if (store.activeSessionId == null) {
        ElMessage.warning('当前没有可删除的会话。')
        return true
      }
      const session = store.sessions.find((item) => item.id === store.activeSessionId)
      await ElMessageBox.confirm(
        `将永久删除会话“${session?.title || `#${store.activeSessionId}`}”及其中的全部消息和附件。`,
        '确认删除会话',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
      )
      await store.deleteSession(store.activeSessionId)
      const nextSession = store.sessions[0]
      if (nextSession) await store.selectSession(nextSession.id)
      ElMessage.success('会话已删除')
      return true
    }
    case 'cancel':
      if (!store.canCancel) {
        ElMessage.info('当前没有正在执行的请求。')
      } else {
        store.cancelActiveRequest()
        ElMessage.info('正在终止请求…')
      }
      return true
    case 'models':
      await store.fetchProviders()
      await showCommandDialog('可用对话模型', modelListText())
      return true
    case 'model': {
      const providerId = parseId(command.argument)
      if (providerId == null) {
        ElMessage.warning('用法：/model <模型ID>；可使用 /models 查看列表。')
        return true
      }
      if (store.activeSessionId == null) {
        ElMessage.warning('请先创建或切换到一个会话。')
        return true
      }
      const provider = store.chatProviders.find((item) => item.id === providerId)
      if (!provider) {
        ElMessage.warning(`未找到对话模型 #${providerId}，可使用 /models 查看列表。`)
        return true
      }
      await store.updateSessionProviders(provider.id, undefined)
      chatProviderId.value = provider.id
      ElMessage.success(`当前会话已切换至 ${provider.name || provider.providerId} / ${provider.modelName}`)
      return true
    }
    case 'draw':
      if (!command.argument) {
        ElMessage.warning('用法：/draw <绘图提示词>')
        return true
      }
      handleDraw({ ...payload, prompt: command.argument, attachments: payload.attachments || [] })
      return true
    default:
      ElMessage.warning(`未知命令 /${command.rawName}；输入 /help 查看可用命令。`)
      return true
  }
}

async function handleSend(payload: ChatInputPayload) {
  try {
    if (await handleSystemCommand(payload)) return
  } catch (e: any) {
    // Element Plus uses this value when the user closes a confirmation dialog.
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '执行系统命令失败')
    return
  }

  if (!store.activeSessionId) {
    const session = await store.createSession()
    await store.selectSession(session.id)
  }

  sending.value = true
  try {
    const chatPromise = store.chat(
      payload.prompt,
      payload.attachmentIds,
      payload.chatProviderId,
      payload.attachments || [],
    )
    await nextTick()
    scrollToBottom()
    const result = await chatPromise
    if (result?.status === 'FAILED') {
      ElMessage.error(result.errorMessage || 'AI 回应失败')
    }
    await nextTick()
    scrollToBottom()
  } catch (e: any) {
    if (e?.name === 'CanceledError') ElMessage.info('请求已终止')
    else ElMessage.error(e.message || '发送失败')
  } finally {
    sending.value = false
  }
}

function getLastAssistantPrompt() {
  for (let i = store.messages.length - 1; i >= 0; i--) {
    const message = store.messages[i]
    if (
      message?.role === 'ASSISTANT'
      && (message.messageType == null || message.messageType === 'CHAT')
      && message.status !== 'FAILED'
      && !message.imageUrl
      && message.content?.trim()
    ) {
      return message.content.trim()
    }
  }
  return ''
}

function isImageAttachment(item: UploadResponse) {
  return item.contentType?.startsWith('image/')
}

function handleDraw(payload: {
  prompt: string
  attachmentIds: number[]
  attachments: UploadResponse[]
  chatProviderId: number | null
}) {
  const references = payload.attachments.filter(isImageAttachment)
  const userPrompt = payload.prompt.trim()
  drawInitialReferences.value = references
  drawSmartParseInitialPrompt.value = !userPrompt
  drawInitialPrompt.value = userPrompt || getLastAssistantPrompt()
  drawDialogVisible.value = true
}

async function handleDrawGenerate(payload: {
  prompt: string
  attachmentIds: number[]
  references: UploadResponse[]
  size: string
  quality: string
  format: string
  imageProviderId: number | null
}) {
  if (!store.activeSessionId) {
    const session = await store.createSession()
    await store.selectSession(session.id)
  }

  drawDialogVisible.value = false
  chatInputRef.value?.clearDraft()
  sending.value = true
  try {
    const drawPromise = store.draw({
      prompt: payload.prompt,
      attachmentIds: payload.attachmentIds,
      imageProviderId: payload.imageProviderId,
      size: payload.size,
      quality: payload.quality,
      format: payload.format,
    }, payload.references)
    await nextTick()
    scrollToBottom()
    const result = await drawPromise
    if (result?.status === 'FAILED') {
      ElMessage.error(result.errorMessage || '图片生成失败')
    }
    await nextTick()
    scrollToBottom()
  } catch (e: any) {
    if (e?.name === 'CanceledError') ElMessage.info('图片生成已终止')
    else ElMessage.error(e.message || '图片生成失败')
  } finally {
    sending.value = false
  }
}

function updateScrollToBottomVisibility() {
  const el = messagesContainer.value
  if (!el) {
    showScrollToBottom.value = false
    return
  }
  const distanceToBottom = el.scrollHeight - el.scrollTop - el.clientHeight
  showScrollToBottom.value = distanceToBottom > 260
}

function scrollToBottom() {
  nextTick(() => {
    if (messagesContainer.value) {
      messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight
      showScrollToBottom.value = false
    }
  })
}

async function handleEditMessage(messageId: number) {
  store.startEditing(messageId)
}

async function handleSaveEdit(messageId: number, content: string) {
  await store.editMessage(messageId, content)
}

function handleRegenerate(messageId: number) {
  if (store.loading) return
  regenerateTargetId.value = messageId
  regenerateAction.value = 'regenerate'
  regenerateDialogVisible.value = true
}

function handleResend(messageId: number) {
  if (store.loading) return
  regenerateTargetId.value = messageId
  regenerateAction.value = 'resend'
  regenerateDialogVisible.value = true
}

async function handleRegenerateConfirm(payload: {
  chatProviderId: number | null
  imageProviderId: number | null
}) {
  const messageId = regenerateTargetId.value
  if (messageId == null) return
  const action = regenerateAction.value
  regenerateDialogVisible.value = false
  try {
    if (action === 'resend') {
      await store.resendUserMessage(messageId, payload.chatProviderId, payload.imageProviderId)
    } else {
      await store.regenerateMessage(messageId, payload.chatProviderId, payload.imageProviderId)
    }
    await nextTick()
    scrollToBottom()
  } catch (e: any) {
    if (e?.name === 'CanceledError') {
      ElMessage.info(action === 'resend' ? '再次发送已终止' : '重新生成已终止')
    } else {
      ElMessage.error(e.message || (action === 'resend' ? '再次发送失败' : '重新生成失败'))
    }
  }
}

async function handleRefreshMessage(messageId: number) {
  const completed = await store.manualRefreshMessage(messageId)
  if (completed) {
    await nextTick()
    scrollToBottom()
  } else {
    ElMessage.info('图片仍在队列中，已继续轮询状态')
  }
}

async function handleDeleteMessage(messageId: number) {
  try {
    await ElMessageBox.confirm('确定删除此消息及后续消息？', '确认删除', {
      type: 'warning',
    })
    await store.deleteMessage(messageId)
  } catch {
    // cancelled
  }
}

function handleCopy(content: string) {
  // copy is handled inside ChatMessage, just a hook for potential future use
}

async function handleChatProviderChange(id: number | null) {
  chatProviderId.value = id
  if (store.activeSessionId) {
    await store.updateSessionProviders(id, imageProviderId.value)
  }
}

async function handleImageProviderChange(id: number | null) {
  imageProviderId.value = id
  if (store.activeSessionId) {
    await store.updateSessionProviders(chatProviderId.value, id)
  }
}

function fillExample(text: string) {
  // Example clicks are now handled via ChatInput
}
</script>

<template>
  <div class="home-view" v-loading="store.loading || sending">
    <SessionSidebar :mobile-open="sidebarOpen" @close="sidebarOpen = false" />
    <div class="chat-area">
      <!-- Chat header with view and model controls -->
      <div class="chat-header">
        <el-button
          class="mobile-sidebar-button"
          text
          :icon="Menu"
          aria-label="打开会话列表"
          title="打开会话列表"
          @click="sidebarOpen = true"
        />
        <div class="chat-heading">
          <span class="chat-eyebrow">创作空间</span>
          <span class="chat-title">{{ activeSessionTitle }}</span>
        </div>
        <div class="header-actions">
          <el-radio-group v-model="viewMode" size="small">
            <el-radio-button label="conversation">对话</el-radio-button>
            <el-radio-button label="gallery">图片墙</el-radio-button>
          </el-radio-group>
          <ModelSelector
            :chat-providers="store.chatProviders"
            :image-providers="store.imageProviders"
            :active-chat-id="chatProviderId"
            :active-image-id="imageProviderId"
            @update:active-chat-id="handleChatProviderChange"
            @update:active-image-id="handleImageProviderChange"
          />
        </div>
      </div>

      <!-- Messages -->
      <div v-if="viewMode === 'conversation'" ref="messagesContainer" class="messages" @scroll="updateScrollToBottomVisibility">
        <div v-if="store.messages.length === 0" class="welcome">
          <div class="welcome-orb"><span class="welcome-icon">✦</span></div>
          <span class="welcome-badge">AI IMAGE STUDIO</span>
          <h2>把灵感变成画面</h2>
          <p>输入想法、粘贴参考图，或打开绘画面板开始创作。</p>
          <div class="examples">
            <div
              class="example-item"
              @click="handleSend({ prompt: '一只橘猫坐在窗台上晒太阳，油画风格', attachmentIds: [], chatProviderId })"
            >
              一只橘猫坐在窗台上晒太阳，油画风格
            </div>
            <div
              class="example-item"
              @click="handleSend({ prompt: '未来城市夜景，赛博朋克风格', attachmentIds: [], chatProviderId })"
            >
              未来城市夜景，赛博朋克风格
            </div>
            <div
              class="example-item"
              @click="handleSend({ prompt: '水墨画风格的山水，雾气缭绕的群山', attachmentIds: [], chatProviderId })"
            >
              水墨画风格的山水，雾气缭绕的群山
            </div>
          </div>
        </div>
        <ChatMessage
          v-for="msg in store.messages"
          :key="msg.id"
          :message="msg"
          :editing-message-id="store.editingMessageId"
          :chat-provider="selectedChatProvider"
          @edit="handleEditMessage"
          @resend="handleResend"
          @regenerate="handleRegenerate"
          @delete="handleDeleteMessage"
          @refresh="handleRefreshMessage"
          @copy="handleCopy"
          @save-edit="handleSaveEdit"
        />
      </div>
      <ImageGallery v-else :messages="store.messages" />

      <Transition name="scroll-bottom-fade">
        <button
          v-if="showScrollToBottom"
          class="scroll-bottom-button"
          type="button"
          @click="scrollToBottom"
        >
          <span class="scroll-bottom-icon">↓</span>
          最新消息
        </button>
      </Transition>

      <!-- Chat Input -->
      <div v-if="store.canCancel" class="operation-status">
        <span class="operation-spinner"></span>
        <span>{{ store.operationStage || '正在处理请求...' }}</span>
        <el-button text type="danger" size="small" @click="store.cancelActiveRequest">终止</el-button>
      </div>
      <ChatInput
        ref="chatInputRef"
        :loading="sending || store.loading"
        :cancelable="store.canCancel"
        :provider-options="store.chatProviders"
        :active-session-id="store.activeSessionId"
        :active-chat-provider-id="chatProviderId"
        @send="handleSend"
        @draw="handleDraw"
        @cancel="store.cancelActiveRequest"
      />

      <DrawDialog
        v-model:visible="drawDialogVisible"
        :initial-prompt="drawInitialPrompt"
        :initial-references="drawInitialReferences"
        :history-messages="store.messages"
        :smart-parse-initial-prompt="drawSmartParseInitialPrompt"
        :image-providers="store.imageProviders"
        :default-image-provider-id="imageProviderId"
        :loading="sending || store.loading"
        @generate="handleDrawGenerate"
      />

      <RegenerateDialog
        v-model:visible="regenerateDialogVisible"
        :action="regenerateAction"
        :message="regenerateTarget"
        :chat-providers="store.chatProviders"
        :image-providers="store.imageProviders"
        :default-chat-provider-id="chatProviderId"
        :default-image-provider-id="imageProviderId"
        :loading="store.loading"
        @confirm="handleRegenerateConfirm"
      />
    </div>
  </div>
</template>

<style scoped>
.home-view {
  display: flex;
  height: 100%;
  min-width: 0;
}

.chat-area {
  position: relative;
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  background:
    radial-gradient(circle at 88% 4%, rgba(144, 111, 255, .12), transparent 24rem),
    radial-gradient(circle at 28% 100%, rgba(67, 173, 235, .1), transparent 28rem),
    #f7f8fe;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 68px;
  padding: 10px 32px;
  border-bottom: 1px solid rgba(224, 228, 243, .9);
  background: rgba(255, 255, 255, .76);
  backdrop-filter: blur(12px);
}

.mobile-sidebar-button { display: none; }
.chat-heading { display: flex; flex-direction: column; gap: 1px; }
.chat-eyebrow { color: var(--app-primary); font-size: 10px; font-weight: 800; letter-spacing: .11em; text-transform: uppercase; }
.chat-title { color: #263452; font-size: 15px; font-weight: 700; }
.header-actions { display: flex; align-items: center; gap: 12px; }
.header-actions :deep(.el-radio-group) { padding: 3px; border-radius: 10px; background: #f0f2fa; }
.header-actions :deep(.el-radio-button__inner) { padding: 6px 11px; color: #75809b; border: 0; border-radius: 7px; background: transparent; box-shadow: none; }
.header-actions :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { color: var(--app-primary); background: #fff; box-shadow: 0 2px 7px rgba(68, 82, 143, .12); }

.messages {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
  padding: 28px clamp(20px, 4vw, 56px) 18px;
  scroll-behavior: smooth;
}

.welcome {
  width: min(100%, 600px);
  margin: auto;
  padding: 40px 28px;
  text-align: center;
  border: 1px solid rgba(231, 234, 248, .9);
  border-radius: 24px;
  background: rgba(255, 255, 255, .7);
  box-shadow: 0 18px 55px rgba(72, 88, 150, .09);
  backdrop-filter: blur(12px);
}

.welcome-orb {
  display: grid;
  width: 68px;
  height: 68px;
  margin: 0 auto 16px;
  place-items: center;
  color: #fff;
  border-radius: 22px;
  background: linear-gradient(135deg, #4f6df6, #a05cf3);
  box-shadow: 0 14px 28px rgba(91, 91, 236, .27);
  transform: rotate(-7deg);
}
.welcome-icon { font-size: 34px; transform: rotate(7deg); }
.welcome-badge { display: inline-block; margin-bottom: 10px; color: var(--app-primary); font-size: 10px; font-weight: 800; letter-spacing: .14em; }
.welcome h2 { margin: 0 0 9px; color: #263452; font-size: 27px; letter-spacing: -.5px; }
.welcome p { margin: 0 0 28px; color: #7d88a2; }
.examples { display: grid; gap: 9px; text-align: left; }
.example-item {
  position: relative;
  padding: 12px 16px 12px 38px;
  overflow: hidden;
  color: #53607b;
  font-size: 13px;
  line-height: 1.45;
  cursor: pointer;
  border: 1px solid #e7eaf6;
  border-radius: 12px;
  background: rgba(255, 255, 255, .85);
  transition: transform .18s ease, border-color .18s ease, box-shadow .18s ease, color .18s ease;
}
.example-item::before { position: absolute; left: 15px; top: 11px; content: '✦'; color: #a064ef; font-size: 15px; }
.example-item:hover { color: #4356c8; border-color: #c9d0ff; box-shadow: 0 7px 18px rgba(82, 103, 246, .1); transform: translateY(-2px); }

.operation-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 clamp(18px, 4vw, 52px);
  padding: 8px 12px;
  color: #9a6200;
  font-size: 12px;
  border: 1px solid #ffe0a5;
  border-radius: 12px 12px 0 0;
  background: #fffaf0;
}
.operation-spinner { width: 13px; height: 13px; border: 2px solid #f5d797; border-top-color: #df9400; border-radius: 50%; animation: operation-spin .8s linear infinite; }
@keyframes operation-spin { to { transform: rotate(360deg); } }

.scroll-bottom-button {
  position: absolute;
  right: clamp(18px, 3vw, 42px);
  bottom: 132px;
  z-index: 10;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 9px 14px;
  color: #4f60d7;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 1px solid #d9def8;
  border-radius: 999px;
  background: rgba(255, 255, 255, .94);
  box-shadow: 0 10px 28px rgba(41, 55, 117, .14);
  backdrop-filter: blur(8px);
  transition: transform .16s ease, box-shadow .16s ease;
}
.scroll-bottom-button:hover { box-shadow: 0 13px 30px rgba(41, 55, 117, .2); transform: translateY(-2px); }
.scroll-bottom-icon { font-size: 16px; line-height: 1; }
.scroll-bottom-fade-enter-active, .scroll-bottom-fade-leave-active { transition: opacity .16s ease, transform .16s ease; }
.scroll-bottom-fade-enter-from, .scroll-bottom-fade-leave-to { opacity: 0; transform: translateY(8px); }

@media (max-width: 900px) {
  .chat-header { gap: 8px; padding: 9px 18px; }
  .messages { padding: 20px 18px 12px; }
  .mobile-sidebar-button { display: inline-flex; flex: 0 0 auto; }
  .chat-heading { display: flex; min-width: 0; }
  .chat-title { max-width: 42vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .header-actions { width: auto; min-width: 0; flex: 1; justify-content: flex-end; }
}
@media (max-width: 620px) {
  .header-actions :deep(.model-selector) { max-width: 170px; }
  .welcome { padding: 30px 16px; border-radius: 18px; }
  .welcome h2 { font-size: 23px; }
}
</style>
