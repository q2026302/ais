<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Check, Close, Delete, EditPen, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useSessionStore } from '@/stores/session'
import { formatTimeHm } from '@/utils/dateTime'

defineOptions({
  name: 'FeishuSessionsPage',
})

const router = useRouter()
const route = useRoute()
const store = useSessionStore()

const entryPrefix = computed(() => route.meta.mobileEntry === 'mobile' ? 'mobile' : 'feishu')

// Context menu (rename/delete) for session rows
const contextSessionId = ref<number | null>(null)

function formatTime(value: string) {
  return formatTimeHm(value, '')
}

async function selectSession(id: number) {
  if (store.loading) return
  try {
    await store.selectSession(id)
    await router.push({ name: entryPrefix.value + '-chat', params: { id } })
  } catch (error: any) {
    ElMessage.error(error.message || '加载会话失败')
  }
}

async function createNewSession() {
  if (store.loading) return
  try {
    const session = await store.createSession()
    if (!session) return
    await store.selectSession(session.id)
    await router.push({ name: entryPrefix.value + '-chat', params: { id: session.id } })
  } catch (error: any) {
    ElMessage.error(error.message || '新建会话失败')
  }
}

async function renameSession(sessionId: number, currentTitle: string) {
  try {
    const result = await ElMessageBox.prompt(
      '为会话设置一个容易识别的名称',
      '重命名会话',
      {
        inputValue: currentTitle || '新会话',
        confirmButtonText: '保存',
        cancelButtonText: '取消',
        inputValidator: (value: string) => Boolean(value?.trim()) || '请输入会话名称',
      },
    )
    await store.updateSessionTitle(sessionId, result.value.trim())
    ElMessage.success('会话名称已更新')
  } catch {
    // 用户取消操作
  }
}

async function deleteSession(sessionId: number, title: string) {
  try {
    await ElMessageBox.confirm(
      `将永久删除会话"${title || '新会话'}"及其中的全部消息和附件。`,
      '确认删除会话',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
      },
    )
    await store.deleteSession(sessionId)
    ElMessage.success('会话已删除')
  } catch {
    // 用户取消操作
  }
}

async function goBack() {
  let sessionId = store.activeSessionId
  if (sessionId == null) {
    try {
      const session = await store.createSession()
      if (!session) return
      await store.selectSession(session.id)
      sessionId = session.id
    } catch (error: any) {
      ElMessage.error(error.message || '新建会话失败')
      return
    }
  }
  await router.push({ name: entryPrefix.value + '-chat', params: { id: sessionId } })
}

onMounted(() => {
  store.fetchSessions()
})
</script>

<template>
  <main class="sessions-page">
    <header class="sessions-header">
      <button type="button" class="back-button" aria-label="返回" @click="goBack">
        <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
      </button>
      <strong class="sessions-title">会话历史</strong>
      <button type="button" class="new-session-btn" aria-label="新建会话" @click="createNewSession">
        <Plus /> 新建会话
      </button>
    </header>

    <div class="sessions-body">
      <div v-if="!store.sessions.length" class="sessions-empty">
        <p>还没有会话</p>
        <button type="button" @click="createNewSession">创建第一个会话</button>
      </div>

      <div v-else class="session-list">
        <div
          v-for="session in store.sessions"
          :key="session.id"
          class="session-row"
          :class="{ active: session.id === store.activeSessionId }"
        >
          <button
            type="button"
            class="session-select"
            @click="selectSession(session.id)"
          >
            <span>{{ session.title || '新建会话' }}</span>
            <small>#{{ session.id }} · {{ formatTime(session.updatedAt) }}</small>
          </button>
          <button
            type="button"
            class="session-action"
            title="重命名会话"
            aria-label="重命名会话"
            @click="renameSession(session.id, session.title || '')"
          >
            <EditPen />
          </button>
          <button
            type="button"
            class="session-action danger"
            title="删除会话"
            aria-label="删除会话"
            @click="deleteSession(session.id, session.title || '')"
          >
            <Delete />
          </button>
          <Check v-if="session.id === store.activeSessionId" />
        </div>
      </div>
    </div>
  </main>
</template>

<style scoped>
.sessions-page {
  --mobile-primary: #4f67e8;
  --mobile-text: #24314d;
  --mobile-muted: #7d899f;
  --mobile-border: #e5e9f2;

  position: fixed;
  top: 0;
  left: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  height: 100dvh;
  overflow: hidden;
  color: var(--mobile-text);
  background:
    radial-gradient(circle at 95% -5%, rgba(106, 90, 238, .12), transparent 24rem),
    linear-gradient(180deg, #f7f9fd 0%, #f2f5fa 100%);
}

.sessions-header {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 10px;
  min-height: calc(56px + env(safe-area-inset-top));
  padding: env(safe-area-inset-top) 14px 10px;
  border-bottom: 1px solid rgba(225, 230, 240, .9);
  background: rgba(255, 255, 255, .92);
  backdrop-filter: blur(18px);
}

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

.sessions-title {
  flex: 1;
  color: #26334e;
  font-size: 17px;
  font-weight: 700;
}

.new-session-btn {
  display: inline-flex;
  flex: 0 0 auto;
  min-height: 36px;
  align-items: center;
  gap: 5px;
  padding: 0 11px;
  color: #5064d2;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #edf0ff;
}

.new-session-btn svg {
  width: 15px;
  height: 15px;
}

.sessions-body {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  padding: 6px 14px calc(20px + env(safe-area-inset-bottom));
}

.sessions-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 40vh;
  gap: 12px;
  color: #7d899f;
}

.sessions-empty p {
  margin: 0;
  font-size: 14px;
}

.sessions-empty button {
  min-height: 40px;
  padding: 0 18px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 11px;
  background: var(--mobile-primary);
}

.session-list {
  padding-top: 4px;
}

.session-row {
  display: flex;
  width: 100%;
  min-height: 58px;
  align-items: center;
  gap: 8px;
  padding: 7px 6px 7px 10px;
  color: #51607b;
  text-align: left;
  border: 0;
  border-bottom: 1px solid #eff1f5;
  background: transparent;
}

.session-row.active {
  border-radius: 12px;
  background: #f2f4ff;
}

.session-select {
  display: flex;
  min-width: 0;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  padding: 6px 0;
  color: inherit;
  text-align: left;
  cursor: pointer;
  border: 0;
  background: transparent;
}

.session-select span {
  overflow: hidden;
  font-size: 13px;
  font-weight: 750;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-select small {
  color: #9ba4b6;
  font-size: 10px;
}

.session-action {
  display: grid;
  flex: 0 0 auto;
  width: 34px;
  height: 34px;
  padding: 0;
  place-items: center;
  color: #919caf;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #f3f5f8;
}

.session-action.danger {
  color: #d0717d;
  background: #fff2f4;
}

.session-row > svg {
  flex: 0 0 auto;
  width: 18px;
  height: 18px;
  color: #6072db;
}
</style>
