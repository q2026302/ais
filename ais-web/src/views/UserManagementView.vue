<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, DataAnalysis, Lock, Monitor, Plus, Refresh, Search, UserFilled } from '@element-plus/icons-vue'
import { usersApi, type ManagedUser } from '@/api/users'
import type { AuthRole } from '@/api/auth'

const router = useRouter()

function routeWithSource(path: string) {
  return router.currentRoute.value.query.source === 'feishu'
    ? { path, query: { source: 'feishu' } }
    : { path }
}

type AdminMenuKey = 'models' | 'security' | 'data' | 'tools' | 'users'

const adminMenuGroups: Array<{
  label: string
  items: Array<{ key: Exclude<AdminMenuKey, 'users'>; label: string; hint: string; icon: typeof Connection }>
}> = [
  {
    label: '业务配置',
    items: [{ key: 'models', label: '模型与供应商', hint: '默认模型和接口配置', icon: Connection }],
  },
  {
    label: '系统管理',
    items: [
      { key: 'security', label: '安全防护', hint: '登录与封锁策略', icon: Lock },
      { key: 'data', label: '数据管理', hint: '备份与系统迁移', icon: DataAnalysis },
      { key: 'tools', label: '诊断工具', hint: '查看模型通信记录', icon: Monitor },
    ],
  },
]

const users = ref<ManagedUser[]>([])
const loading = ref(false)
const keyword = ref('')
const dialogVisible = ref(false)
const passwordDialogVisible = ref(false)
const editing = ref<ManagedUser | null>(null)
const target = ref<ManagedUser | null>(null)
const saving = ref(false)
const resetting = ref(false)
const form = reactive({ username: '', displayName: '', email: '', role: 'USER' as AuthRole, enabled: true, password: '' })
const resetPassword = ref('')

const filteredUsers = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  if (!term) return users.value
  return users.value.filter((user) => [user.username, user.displayName, user.email]
    .some((value) => (value || '').toLowerCase().includes(term)))
})

function selectAdminSection(section: AdminMenuKey) {
  if (section === 'users') return
  router.push(routeWithSource('/admin'))
}

async function load() {
  loading.value = true
  try {
    users.value = await usersApi.list()
  } catch (error: any) {
    ElMessage.error(error.message || '加载用户列表失败')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  editing.value = null
  Object.assign(form, { username: '', displayName: '', email: '', role: 'USER', enabled: true, password: '' })
  dialogVisible.value = true
}

function openEdit(user: ManagedUser) {
  editing.value = user
  Object.assign(form, {
    username: user.username,
    displayName: user.displayName || '',
    email: user.email || '',
    role: user.role,
    enabled: user.enabled,
    password: '',
  })
  dialogVisible.value = true
}

async function submit() {
  if (!form.username.trim()) {
    ElMessage.warning('请输入用户名')
    return
  }
  if (!editing.value && form.password.length < 6) {
    ElMessage.warning('初始密码至少需要 6 个字符')
    return
  }
  saving.value = true
  try {
    if (editing.value) {
      await usersApi.update(editing.value.id!, {
        displayName: form.displayName,
        email: form.email,
        role: form.role,
        enabled: form.enabled,
      })
      ElMessage.success('用户信息已更新')
    } else {
      await usersApi.create({
        username: form.username.trim(),
        displayName: form.displayName,
        email: form.email,
        role: form.role,
        enabled: form.enabled,
      }, form.password)
      ElMessage.success('用户已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error: any) {
    ElMessage.error(error.message || '保存用户失败')
  } finally {
    saving.value = false
  }
}

async function remove(user: ManagedUser) {
  try {
    await ElMessageBox.confirm(`确认删除用户“${user.username}”？该操作不可撤销。`, '删除用户', { type: 'warning' })
  } catch {
    return
  }
  try {
    await usersApi.remove(user.id!)
    ElMessage.success('用户已删除')
    await load()
  } catch (error: any) {
    ElMessage.error(error.message || '删除用户失败')
  }
}

function openReset(user: ManagedUser) {
  target.value = user
  resetPassword.value = ''
  passwordDialogVisible.value = true
}

async function submitReset() {
  if (resetPassword.value.length < 6) {
    ElMessage.warning('新密码至少需要 6 个字符')
    return
  }
  if (!target.value) return
  resetting.value = true
  try {
    await usersApi.resetPassword(target.value.id!, resetPassword.value)
    ElMessage.success('密码已重置')
    passwordDialogVisible.value = false
  } catch (error: any) {
    ElMessage.error(error.message || '重置密码失败')
  } finally {
    resetting.value = false
  }
}

function roleLabel(role: AuthRole) { return role === 'ADMIN' ? '管理员' : '普通用户' }
function roleType(role: AuthRole) { return role === 'ADMIN' ? 'warning' : 'primary' }
function formatTime(value: string | null) { return value ? new Date(value).toLocaleString() : '—' }

onMounted(load)
</script>

<template>
  <div class="admin-view users-view">
    <div class="admin-header">
      <div>
        <div class="admin-eyebrow">ADMIN CONSOLE</div>
        <h2>管理中心</h2>
        <p>配置模型服务、账户安全和数据运维，保持核心业务设置清晰可见。</p>
      </div>
      <div class="admin-header-actions">
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新用户</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新增用户</el-button>
      </div>
    </div>

    <div class="admin-layout">
      <aside class="admin-subnav" aria-label="管理二级菜单">
        <div class="subnav-heading">
          <strong>管理导航</strong>
          <span>系统设置</span>
        </div>
        <nav class="subnav-list">
          <template v-for="group in adminMenuGroups" :key="group.label">
            <div class="subnav-group-label">{{ group.label }}</div>
            <button
              v-for="item in group.items"
              :key="item.key"
              type="button"
              class="subnav-item"
              @click="selectAdminSection(item.key)"
            >
              <el-icon><component :is="item.icon" /></el-icon>
              <span>
                <strong>{{ item.label }}</strong>
                <small>{{ item.hint }}</small>
              </span>
            </button>
          </template>
          <div class="subnav-group-label account-group-label">账户管理</div>
          <button type="button" class="subnav-item active" aria-current="page" @click="selectAdminSection('users')">
            <el-icon><UserFilled /></el-icon>
            <span>
              <strong>用户与权限</strong>
              <small>账号与角色维护</small>
            </span>
          </button>
        </nav>
      </aside>

      <main class="admin-content">
        <section class="admin-section">
          <div class="section-intro">
            <div>
              <span class="section-kicker">ACCOUNT MANAGEMENT</span>
              <h3>用户与权限</h3>
              <p>创建和维护应用用户、角色、账号状态及密码。</p>
            </div>
            <el-button plain :icon="Refresh" :loading="loading" @click="load">刷新列表</el-button>
          </div>

          <div class="list-header">
            <div>
              <h3>用户列表</h3>
              <span>共 {{ filteredUsers.length }} 个用户<span v-if="keyword.trim()">，当前为筛选结果</span></span>
            </div>
            <el-input
              v-model="keyword"
              class="keyword-filter"
              clearable
              :prefix-icon="Search"
              placeholder="搜索用户名、显示名称或邮箱"
            />
          </div>

          <el-table
            v-loading="loading"
            :data="filteredUsers"
            row-key="id"
            stripe
            empty-text="暂无用户"
          >
            <el-table-column prop="username" label="用户名" min-width="150" />
            <el-table-column label="显示名称" min-width="150">
              <template #default="{ row }">{{ row.displayName || '—' }}</template>
            </el-table-column>
            <el-table-column label="邮箱" min-width="190">
              <template #default="{ row }">{{ row.email || '—' }}</template>
            </el-table-column>
            <el-table-column label="角色" width="110">
              <template #default="{ row }"><el-tag :type="roleType(row.role)">{{ roleLabel(row.role) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '禁用' }}</el-tag></template>
            </el-table-column>
            <el-table-column label="更新时间" width="180">
              <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="230" fixed="right">
              <template #default="{ row }">
                <div class="actions">
                  <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
                  <el-button link type="warning" @click="openReset(row)">重置密码</el-button>
                  <el-button link type="danger" @click="remove(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </main>
    </div>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新增用户'" width="520px">
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" :disabled="Boolean(editing)" maxlength="64" autocomplete="username" placeholder="请输入登录用户名" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="form.displayName" maxlength="100" placeholder="可选，用于页面展示" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="form.email" maxlength="254" autocomplete="email" placeholder="可选，用于联系和识别" />
        </el-form-item>
        <el-form-item label="角色">
          <el-radio-group v-model="form.role">
            <el-radio value="USER">普通用户</el-radio>
            <el-radio value="ADMIN">管理员</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.enabled" active-text="启用" inactive-text="禁用" />
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码" required>
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" placeholder="至少 6 个字符" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="passwordDialogVisible" title="重置密码" width="420px">
      <p class="dialog-description">为用户“{{ target?.username }}”设置新密码。</p>
      <el-input v-model="resetPassword" type="password" show-password autocomplete="new-password" placeholder="至少 6 个字符" />
      <template #footer>
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitReset">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.users-view {
  min-height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
  padding: clamp(20px, 3vw, 36px);
  background: radial-gradient(circle at 95% 0%, rgba(150, 103, 244, .12), transparent 25rem), #f7f8fe;
}

.admin-header { display: flex; align-items: center; justify-content: space-between; margin: 2px 0 24px; }
.admin-header h2 { margin: 0; color: #263452; font-size: 25px; letter-spacing: -.45px; }
.admin-header p { max-width: 700px; margin: 8px 0 0; color: #7c87a1; font-size: 13px; }
.admin-header-actions { display: flex; gap: 8px; }
.admin-header :deep(.el-button) { min-height: 38px; padding: 0 15px; border-radius: 10px; }
.admin-eyebrow { margin-bottom: 5px; color: #8490b4; font-size: 10px; font-weight: 800; letter-spacing: .16em; }

.admin-layout { display: grid; grid-template-columns: 232px minmax(0, 1fr); align-items: start; gap: 22px; min-width: 0; }
.admin-subnav {
  position: sticky;
  top: 0;
  padding: 14px;
  border: 1px solid rgba(222, 226, 244, .95);
  border-radius: 18px;
  background: rgba(255, 255, 255, .86);
  box-shadow: 0 10px 28px rgba(44, 57, 112, .06);
  backdrop-filter: blur(12px);
}
.subnav-heading { display: flex; align-items: baseline; justify-content: space-between; padding: 4px 8px 12px; border-bottom: 1px solid #edf0f7; }
.subnav-heading strong { color: #35415f; font-size: 14px; }
.subnav-heading span { color: #a0a8ba; font-size: 11px; }
.subnav-list { display: flex; flex-direction: column; gap: 4px; padding-top: 10px; }
.subnav-group-label { padding: 10px 8px 4px; color: #a0a8ba; font-size: 10px; font-weight: 800; letter-spacing: .08em; }
.subnav-group-label:first-child { padding-top: 2px; }
.account-group-label { margin-top: 6px; border-top: 1px solid #edf0f7; }
.subnav-item { display: flex; align-items: center; width: 100%; gap: 10px; padding: 11px 10px; color: #697590; text-align: left; cursor: pointer; border: 0; border-radius: 11px; background: transparent; transition: color .18s ease, background-color .18s ease, transform .18s ease; }
.subnav-item:hover { color: #5267d8; background: #f5f6ff; transform: translateX(1px); }
.subnav-item.active { color: #4f62d8; background: linear-gradient(105deg, #edf0ff, #f6f1ff); box-shadow: inset 3px 0 #596cf1; }
.subnav-item .el-icon { flex: 0 0 auto; width: 20px; font-size: 17px; }
.subnav-item > span { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.subnav-item strong { font-size: 13px; font-weight: 750; }
.subnav-item small { color: #9aa3b6; font-size: 10px; }
.subnav-item.active small { color: #7f8ce0; }

.admin-content { min-width: 0; }
.admin-section { min-width: 0; animation: admin-section-in .2s ease-out; }
.section-intro { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin: 1px 0 18px; }
.section-kicker { color: #8792b5; font-size: 10px; font-weight: 800; letter-spacing: .13em; }
.section-intro h3 { margin: 4px 0 4px; color: #2e3b59; font-size: 21px; letter-spacing: -.3px; }
.section-intro p { margin: 0; color: #8993ab; font-size: 12px; }
.section-intro :deep(.el-button) { flex: 0 0 auto; border-radius: 9px; }
.list-header { display: flex; align-items: end; justify-content: space-between; margin: 28px 0 13px; }
.list-header h3 { margin: 0 0 5px; color: #34415e; font-size: 17px; }
.list-header span { color: #8992a9; font-size: 12px; }
.keyword-filter { width: 330px; }
.keyword-filter :deep(.el-input__wrapper) { border-radius: 10px; background: rgba(255, 255, 255, .86); box-shadow: 0 0 0 1px #e6e9f6 inset; }
.admin-content :deep(.el-table) { overflow: hidden; border: 1px solid #e5e8f4; border-radius: 16px; background: rgba(255, 255, 255, .93); box-shadow: 0 12px 30px rgba(42, 55, 110, .07); }
.admin-content :deep(.el-table th.el-table__cell) { color: #69758f; font-size: 12px; font-weight: 800; background: #f5f6fc; }
.admin-content :deep(.el-table tr) { background: transparent; }
.admin-content :deep(.el-table .el-table__row:hover > td.el-table__cell) { background: #f7f8ff; }
.admin-content :deep(.el-table td.el-table__cell), .admin-content :deep(.el-table th.el-table__cell) { padding: 13px 0; border-bottom-color: #edf0f7; }
.actions { display: flex; gap: 7px; white-space: nowrap; }
.actions .el-button { margin-left: 0; border-radius: 8px; }
.dialog-description { margin: 0 0 15px; color: #697590; }

@keyframes admin-section-in { from { opacity: 0; transform: translateY(4px); } to { opacity: 1; transform: translateY(0); } }

@media (max-width: 900px) {
  .admin-layout { grid-template-columns: 1fr; gap: 15px; }
  .admin-subnav { position: static; padding: 10px; }
  .subnav-heading { padding: 3px 7px 9px; }
  .subnav-list { display: grid; grid-template-columns: repeat(5, minmax(120px, 1fr)); overflow-x: auto; }
  .subnav-item { min-width: 120px; }
  .subnav-group-label { display: none; }
  .list-header { align-items: stretch; flex-direction: column; gap: 12px; }
  .keyword-filter { width: 100%; }
  .admin-header { align-items: flex-start; gap: 14px; flex-direction: column; }
}

@media (max-width: 600px) {
  .users-view { padding: 18px 14px; }
  .admin-header h2 { font-size: 21px; }
  .admin-header-actions { width: 100%; }
  .admin-header-actions :deep(.el-button) { flex: 1; }
  .subnav-list { grid-template-columns: repeat(5, minmax(108px, 1fr)); }
  .subnav-item { min-width: 108px; padding: 9px 8px; }
  .subnav-item small { display: none; }
  .section-intro { align-items: stretch; flex-direction: column; }
  .section-intro :deep(.el-button) { align-self: flex-start; }
  .list-header { margin-top: 23px; }
}
</style>
