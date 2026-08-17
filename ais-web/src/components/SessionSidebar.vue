<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Close, Delete, Expand, Fold, Plus } from '@element-plus/icons-vue'
import { ElMessageBox } from 'element-plus'
import { useSessionStore } from '@/stores/session'
import { formatRelativeSessionTime } from '@/utils/dateTime'

const store = useSessionStore()

const props = withDefaults(defineProps<{ mobileOpen?: boolean }>(), { mobileOpen: false })
const emit = defineEmits<{ close: [] }>()

const sessions = computed(() => store.sessions)
const activeId = computed(() => store.activeSessionId)

// Desktop resizable / collapsible sidebar. The width lives in a CSS variable so
// the mobile fixed-drawer rules (which set their own width) can still win.
const SIDEBAR_MIN_WIDTH = 200
const SIDEBAR_MAX_WIDTH = 400
const SIDEBAR_DEFAULT_WIDTH = 272
const sidebarWidth = ref(SIDEBAR_DEFAULT_WIDTH)
const collapsed = ref(false)
const resizing = ref(false)

const sidebarStyle = computed(() => ({ '--sidebar-width': `${sidebarWidth.value}px` }))

let resizeStartX = 0
let resizeStartWidth = SIDEBAR_DEFAULT_WIDTH

function startResize(event: PointerEvent) {
  event.preventDefault()
  resizing.value = true
  resizeStartX = event.clientX
  resizeStartWidth = sidebarWidth.value
  document.body.classList.add('sidebar-resizing')
  document.addEventListener('pointermove', onResizeMove)
  document.addEventListener('pointerup', stopResize, { once: true })
}

function onResizeMove(event: PointerEvent) {
  const delta = event.clientX - resizeStartX
  sidebarWidth.value = Math.min(
    SIDEBAR_MAX_WIDTH,
    Math.max(SIDEBAR_MIN_WIDTH, resizeStartWidth + delta),
  )
}

function stopResize() {
  resizing.value = false
  document.body.classList.remove('sidebar-resizing')
  document.removeEventListener('pointermove', onResizeMove)
}

onBeforeUnmount(() => {
  document.body.classList.remove('sidebar-resizing')
  document.removeEventListener('pointermove', onResizeMove)
  document.removeEventListener('pointerup', stopResize)
})

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
  return formatRelativeSessionTime(dateStr, '')
}
</script>

<template>
  <div
    class="sidebar"
    :class="{ 'is-mobile-open': props.mobileOpen, 'is-collapsed': collapsed, 'is-resizing': resizing }"
    :style="sidebarStyle"
  >
    <div v-if="props.mobileOpen" class="sidebar-backdrop" aria-hidden="true" @click="emit('close')"></div>

    <button
      v-if="collapsed"
      type="button"
      class="sidebar-expand"
      aria-label="展开会话列表"
      title="展开会话列表"
      @click="collapsed = false"
    >
      <el-icon><Expand /></el-icon>
    </button>

    <template v-else>
      <div class="sidebar-header">
        <div class="sidebar-heading">
          <span class="sidebar-title">历史会话</span>
          <small>{{ sessions.length }} 个会话</small>
        </div>
        <div class="sidebar-actions">
          <el-button
            v-if="props.mobileOpen"
            text
            :icon="Close"
            aria-label="关闭会话列表"
            title="关闭会话列表"
            class="mobile-close"
            @click="emit('close')"
          />
          <el-button
            text
            :icon="Fold"
            aria-label="折叠会话列表"
            title="折叠会话列表"
            class="collapse-btn"
            @click="collapsed = true"
          />
          <el-button type="primary" size="small" @click="handleNew" :icon="Plus">
            新建
          </el-button>
        </div>
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
    </template>

    <div v-if="!collapsed" class="resize-handle" aria-hidden="true" @pointerdown="startResize"></div>
  </div>
</template>

<style scoped>
.sidebar {
  position: relative;
  z-index: 2;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  width: var(--sidebar-width, 272px);
  overflow: hidden;
  border-right: 1px solid #e5e8f4;
  background: linear-gradient(180deg, #f9faff 0%, #f3f5fc 100%);
}

.sidebar-backdrop { display: none; }
.sidebar-header, .session-list { position: relative; z-index: 1; }

.sidebar.is-collapsed {
  width: 40px;
  align-items: center;
  padding-top: 14px;
}
.sidebar-expand {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  padding: 0;
  color: #6b7690;
  cursor: pointer;
  border: 0;
  border-radius: 8px;
  background: transparent;
}
.sidebar-expand:hover { color: #4e62d2; background: #eef1ff; }

.resize-handle {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  z-index: 4;
  width: 5px;
  cursor: col-resize;
  background: transparent;
  transition: background .16s ease;
}
.resize-handle:hover,
.sidebar.is-resizing .resize-handle { background: #b9c2ec; }

.sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 18px 14px 14px 16px;
  border-bottom: 1px solid rgba(227, 230, 243, .8);
}
.sidebar-heading { display: flex; align-items: baseline; gap: 7px; min-width: 0; }
.sidebar-title { color: #3a4665; font-size: 13px; font-weight: 800; letter-spacing: .02em; }
.sidebar-heading small { color: #a0a8ba; font-size: 10px; }
.sidebar-actions { display: flex; align-items: center; gap: 4px; }
.sidebar-header :deep(.el-button) { height: 30px; padding: 0 10px; border-radius: 9px; }
.collapse-btn { color: #7a84a0; }
.collapse-btn:hover { color: #4e62d2; background: #eef1ff; }
.mobile-close { display: none; }

:global(body.sidebar-resizing) {
  cursor: col-resize;
  user-select: none;
}

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
.resize-handle { display: none; }

.sidebar-header { padding-left: 12px; padding-right: 12px; } }
@media (max-width: 600px) {
  .sidebar { display: none; }
  .collapse-btn { display: none; }
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
