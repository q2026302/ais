<script setup lang="ts">
import { computed } from 'vue'
import { Close, Delete, Plus } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { useSessionStore } from '@/stores/session'

const store = useSessionStore()

const props = withDefaults(defineProps<{ mobileOpen?: boolean }>(), { mobileOpen: false })
const emit = defineEmits<{ close: [] }>()

const sessions = computed(() => store.sessions)
const activeId = computed(() => store.activeSessionId)

async function handleNew() {
  const session = await store.createSession()
  if (session) {
    store.selectSession(session.id)
    emit('close')
  }
}

async function handleSelect(id: number) {
  store.selectSession(id)
  emit('close')
}

async function handleDelete(id: number) {
  const session = sessions.value.find((item) => item.id === id)
  try {
    await ElMessageBox.confirm(
      `将永久删除会话“${session?.title || '新会话'}”及其中的全部消息和附件。`,
      '确认删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await store.deleteSession(id)
  } catch {
    // 用户取消删除
  }
}

function formatTime(dateStr: string): string {
  const d = new Date(dateStr)
  const now = new Date()
  const diff = now.getTime() - d.getTime()
  if (diff < 86400000) {
    return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
  }
  return d.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' })
}
</script>

<template>
  <div class="sidebar" :class="{ 'is-mobile-open': props.mobileOpen }">
    <div v-if="props.mobileOpen" class="sidebar-backdrop" aria-hidden="true" @click="emit('close')"></div>
    <div class="sidebar-header">
      <div class="sidebar-heading">
        <span class="sidebar-title">历史会话</span>
        <small>{{ sessions.length }} 个会话</small>
      </div>
      <el-button
        v-if="props.mobileOpen"
        text
        :icon="Close"
        aria-label="关闭会话列表"
        title="关闭会话列表"
        class="mobile-close"
        @click="emit('close')"
      />
      <el-button type="primary" size="small" @click="handleNew" :icon="Plus">
        新建
      </el-button>
    </div>
    <div class="session-list">
      <div
        v-for="s in sessions"
        :key="s.id"
        class="session-item"
        :class="{ active: s.id === activeId }"
        @click="handleSelect(s.id)"
      >
        <div class="session-title">{{ s.title || '新会话' }}</div>
        <div class="session-meta">
          <span class="session-time">{{ formatTime(s.updatedAt) }}</span>
          <el-button
            text
            size="small"
            type="danger"
            class="delete-btn"
            :icon="Delete"
            :aria-label="`删除会话 ${s.title || '新会话'}`"
            title="删除会话"
            @click.stop="handleDelete(s.id)"
          />
        </div>
      </div>
      <div v-if="sessions.length === 0" class="empty-hint">
        暂无会话，点击「新建」开始
      </div>
    </div>
  </div>
</template>

<style scoped>
.sidebar {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: 272px;
  overflow: hidden;
  border-right: 1px solid #e5e8f4;
  background: linear-gradient(180deg, #f9faff 0%, #f3f5fc 100%);
}

.sidebar-backdrop { display: none; }
.sidebar-header, .session-list { position: relative; z-index: 1; }

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 16px 14px;
  border-bottom: 1px solid rgba(227, 230, 243, .8);
}
.sidebar-heading { display: flex; align-items: baseline; gap: 7px; min-width: 0; }
.sidebar-title { color: #3a4665; font-size: 13px; font-weight: 800; letter-spacing: .02em; }
.sidebar-heading small { color: #a0a8ba; font-size: 10px; }
.sidebar-header :deep(.el-button) { height: 30px; padding: 0 10px; border-radius: 9px; }
.mobile-close { display: none; }

.session-list { flex: 1; overflow-y: auto; padding: 10px 9px 16px; }
.session-item {
  position: relative;
  margin-bottom: 5px;
  padding: 11px 11px 10px 14px;
  cursor: pointer;
  border: 1px solid transparent;
  border-radius: 12px;
  transition: background .16s ease, border-color .16s ease, box-shadow .16s ease, transform .16s ease;
}
.session-item::before { position: absolute; left: 0; top: 12px; bottom: 12px; width: 3px; content: ''; border-radius: 0 4px 4px 0; background: transparent; }
.session-item:hover { border-color: #e4e8f8; background: rgba(255, 255, 255, .78); transform: translateX(1px); }
.session-item.active { border-color: #dbe0ff; background: linear-gradient(105deg, #ebefff, #f5f2ff); box-shadow: 0 6px 17px rgba(79, 94, 196, .1); }
.session-item.active::before { background: linear-gradient(180deg, #546af7, #9c60ee); }
.session-title { margin-bottom: 5px; overflow: hidden; color: #3c4764; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.session-item.active .session-title { color: #4658ca; }
.session-meta { display: flex; align-items: center; justify-content: space-between; }
.session-time { color: #99a2b9; font-size: 11px; }
.delete-btn { min-height: 24px; padding: 0 4px; font-size: 13px; opacity: 0; transition: opacity .15s, color .15s, background .15s; }
.delete-btn:hover { background: #fff0f1; }
.session-item:hover .delete-btn, .session-item.active .delete-btn { opacity: 1; }
.empty-hint { margin: 20px 7px; padding: 32px 16px; color: #929bb2; font-size: 12px; line-height: 1.7; text-align: center; border: 1px dashed #d8ddee; border-radius: 12px; }

@media (max-width: 760px) { .sidebar { width: 208px; } .sidebar-backdrop { display: none; }
.sidebar-header, .session-list { position: relative; z-index: 1; }

.sidebar-header { padding-left: 12px; padding-right: 12px; } }
@media (max-width: 600px) {
  .sidebar { display: none; }
  .sidebar.is-mobile-open {
    position: fixed;
    inset: 0 auto 0 0;
    z-index: 100;
    display: flex;
    width: min(84vw, 300px);
    box-shadow: 18px 0 45px rgba(25, 37, 86, .18);
  }
  .sidebar.is-mobile-open .sidebar-backdrop {
    position: fixed;
    inset: 0;
    z-index: 0;
    display: block;
    width: 100vw;
    background: rgba(27, 36, 78, .34);
  }
  .sidebar.is-mobile-open .mobile-close { display: inline-flex; }
}
</style>
