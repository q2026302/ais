<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Delete, EditPen, Plus } from '@element-plus/icons-vue'
import { useSessionStore } from '@/stores/session'
import { formatTimeHm } from '@/utils/dateTime'

defineOptions({
  name: 'FeishuSessionsPage',
})

const store = useSessionStore()
const route = useRoute()
const router = useRouter()

const entryPrefix = computed(() => (route.meta.mobileEntry === 'mobile' ? 'mobile' : 'feishu'))
const sessionQuery = ref('')
const loadingList = ref(false)

const filteredSessions = computed(() => {
  const keyword = sessionQuery.value.trim().toLowerCase()
  if (!keyword) return store.sessions
  return store.sessions.filter((session) => {
    const title = (session.title || '新建会话').toLowerCase()
    const idText = String(session.id)
    return title.includes(keyword) || idText.includes(keyword)
  })
})

function formatTime(value: string) {
  return formatTimeHm(value, '')
}

async function goToChat(sessionId: number) {
  await router.push({ name: `${entryPrefix.value}-chat`, params: { id: String(sessionId) } })
}

async function ensureSessionsLoaded() {
  if (store.sessions.length) return
  loadingList.value = true
  try {
    await store.fetchSessions()
  } finally {
    loadingList.value = false
  }
}

async function refreshSessions() {
  loadingList.value = true
  try {
    await store.fetchSessions()
  } catch (error: any) {
    ElMessage.error(error?.message || '刷新会话失败')
  } finally {
    loadingList.value = false
  }
}

async function createNewSession() {
  if (store.loading) return
  try {
    const session = await store.createSession()
    if (!session) return
    await store.selectSession(session.id)
    await goToChat(session.id)
  } catch (error: any) {
    ElMessage.error(error?.message || '新建会话失败')
  }
}

async function selectSession(id: number) {
  if (store.loading) return
  try {
    await store.selectSession(id)
    await goToChat(id)
  } catch (error: any) {
    ElMessage.error(error?.message || '加载会话失败')
  }
}

async function renameSession(sessionId: number, currentTitle: string) {
  try {
    const result = await ElMessageBox.prompt('为会话设置一个容易识别的名称', '重命名会话', {
      inputValue: currentTitle || '新会话',
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (value) => Boolean(value?.trim()) || '请输入会话名称',
    })
    await store.updateSessionTitle(sessionId, result.value.trim())
    ElMessage.success('会话名称已更新')
  } catch {
    // 用户取消操作
  }
}

async function deleteSession(sessionId: number, title: string) {
  try {
    await ElMessageBox.confirm(
      `将永久删除会话“${title || '新会话'}”及其中的全部消息和附件。`,
      '确认删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await store.deleteSession(sessionId)
    ElMessage.success('会话已删除')
  } catch {
    // 用户取消操作
  }
}

onMounted(() => {
  void ensureSessionsLoaded()
})
</script>

<template>
  <section class="sessions-panel">
    <div class="section-heading">
      <div>
        <span>会话历史</span>
        <strong>我的对话</strong>
      </div>
      <small>{{ store.sessions.length }} 个</small>
    </div>

    <div class="sessions-toolbar">
      <label class="session-search">
        <span class="sr-only">搜索会话</span>
        <input
          v-model="sessionQuery"
          type="search"
          placeholder="搜索会话名称或编号"
          enterkeyhint="search"
          autocomplete="off"
        >
      </label>
      <button type="button" class="new-session-btn" :disabled="store.loading" @click="createNewSession">
        <Plus /> 新建会话
      </button>
    </div>

    <div v-if="loadingList && !store.sessions.length" class="center-state">
      <strong>正在加载会话…</strong>
    </div>

    <div v-else-if="filteredSessions.length" class="session-list">
      <div
        v-for="session in filteredSessions"
        :key="session.id"
        class="session-row"
        :class="{ active: session.id === store.activeSessionId }"
      >
        <button type="button" class="session-select" @click="selectSession(session.id)">
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

    <div v-else class="center-state sessions-empty">
      <strong>{{ sessionQuery.trim() ? '没有匹配的会话' : '还没有会话' }}</strong>
      <p>
        {{
          sessionQuery.trim()
            ? '试试其他关键词，或清空搜索。'
            : '新建一个会话开始创作。'
        }}
      </p>
      <button v-if="!sessionQuery.trim()" type="button" @click="createNewSession">新建会话</button>
      <button v-else type="button" class="ghost" @click="sessionQuery = ''">清空搜索</button>
      <button v-if="!store.sessions.length" type="button" class="ghost" @click="refreshSessions">刷新列表</button>
    </div>
  </section>
</template>

<style scoped>
.sessions-panel {
  min-height: 100%;
  padding: 17px 14px 20px;
  box-sizing: border-box;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  max-width: 820px;
  margin: 0 auto 14px;
}

.section-heading > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.section-heading span {
  color: #8591a9;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .12em;
}

.section-heading strong {
  color: #2e3b58;
  font-size: 20px;
}

.section-heading small {
  padding: 4px 9px;
  color: #5a6bd0;
  font-size: 11px;
  font-weight: 800;
  border-radius: 999px;
  background: #e9edff;
}

.sessions-toolbar {
  display: flex;
  max-width: 820px;
  margin: 0 auto 12px;
  align-items: center;
  gap: 8px;
}

.session-search {
  display: block;
  min-width: 0;
  flex: 1;
}

.session-search input {
  width: 100%;
  min-height: 40px;
  padding: 0 12px;
  color: #3a4665;
  font-size: 13px;
  border: 1px solid #e4e8f1;
  border-radius: 12px;
  background: #fff;
  outline: none;
  box-sizing: border-box;
}

.session-search input:focus {
  border-color: #c5cef8;
  box-shadow: 0 0 0 3px rgba(95, 112, 232, .12);
}

.new-session-btn {
  display: inline-flex;
  flex: 0 0 auto;
  min-height: 40px;
  align-items: center;
  gap: 5px;
  padding: 0 12px;
  color: #5064d2;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 12px;
  background: #edf0ff;
}

.new-session-btn:disabled {
  cursor: not-allowed;
  opacity: .55;
}

.new-session-btn :deep(svg) {
  width: 14px;
  height: 14px;
}

.session-list {
  max-width: 820px;
  margin: 0 auto;
  padding-top: 4px;
  border-radius: 16px;
  background: rgba(255, 255, 255, .72);
  border: 1px solid #e7ebf3;
  box-shadow: 0 8px 20px rgba(47, 60, 101, .05);
  overflow: hidden;
}

.session-row {
  display: flex;
  width: 100%;
  min-height: 58px;
  align-items: center;
  gap: 8px;
  padding: 7px 10px 7px 12px;
  color: #51607b;
  text-align: left;
  border: 0;
  border-bottom: 1px solid #eff1f5;
  background: transparent;
  box-sizing: border-box;
}

.session-row:last-child {
  border-bottom: 0;
}

.session-row.active {
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

.session-action :deep(svg),
.session-row > svg {
  display: block;
  width: 16px;
  height: 16px;
}

.session-row > svg {
  flex: 0 0 auto;
  color: #6072db;
}

.center-state {
  display: flex;
  max-width: 820px;
  min-height: 42vh;
  margin: 0 auto;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 18px;
  text-align: center;
  color: #7d899f;
}

.center-state strong {
  color: #2e3b58;
  font-size: 16px;
}

.center-state p {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.6;
}

.center-state > button {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(135deg, #506cf1, #7a5de8);
}

.center-state > button.ghost {
  color: #5365cc;
  background: #eef1ff;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
