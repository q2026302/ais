<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Lock, Picture, User } from '@element-plus/icons-vue'
import { authApi, type UserProfile } from '@/api/auth'
import { providerApi } from '@/api/providers'
import { billingApi, userDefaultsApi } from '@/api/billing'
import type { BillingRecord, ModelProvider } from '@/types'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const saving = ref(false)
const changingPassword = ref(false)
const profile = reactive({ displayName: '', email: '' })
const account = ref<UserProfile | null>(null)
const password = reactive({ current: '', next: '', confirm: '' })
const activeTab = ref('profile')

// Model selectors
const chatModels = ref<ModelProvider[]>([])
const imageModels = ref<ModelProvider[]>([])
const defaultChatModelId = ref<number | null>(null)
const defaultImageModelId = ref<number | null>(null)
const loadingModels = ref(false)
const savingModels = ref(false)

// Billing logs
const billingLogs = ref<BillingRecord[]>([])
const billingLogsLoading = ref(false)
const billingLogsPage = ref(0)
const billingLogsTotal = ref(0)
const billingFrom = ref(new Date().toISOString().slice(0, 10))
const billingTo = ref(billingFrom.value)

async function load() {
  loading.value = true
  try {
    const result = await authApi.me()
    if (!result.authenticated) {
      await router.replace({ name: 'login' })
      return
    }
    account.value = {
      id: result.id ?? null,
      username: result.username || result.subject || '',
      subject: result.subject,
      displayName: result.displayName ?? null,
      email: result.email ?? null,
      role: result.role || 'USER',
      enabled: result.enabled ?? true,
      createdAt: result.createdAt ?? null,
      updatedAt: result.updatedAt ?? null,
    }
    profile.displayName = account.value.displayName || ''
    profile.email = account.value.email || ''
  } catch (error: any) {
    ElMessage.error(error.message || '加载个人信息失败')
  } finally {
    loading.value = false
  }
}

async function loadModels() {
  loadingModels.value = true
  try {
    const [chat, image, defaults] = await Promise.all([
      providerApi.list('CHAT'),
      providerApi.list('IMAGE'),
      userDefaultsApi.get().catch(() => ({ defaultChatProviderId: null, defaultImageProviderId: null })),
    ])
    chatModels.value = chat
    imageModels.value = image
    defaultChatModelId.value = defaults.defaultChatProviderId
    defaultImageModelId.value = defaults.defaultImageProviderId
  } catch (error: any) {
    ElMessage.error(error.message || '加载模型列表失败')
  } finally {
    loadingModels.value = false
  }
}

async function saveModels() {
  savingModels.value = true
  try {
    const result = await userDefaultsApi.update({
      defaultChatProviderId: defaultChatModelId.value,
      defaultImageProviderId: defaultImageModelId.value,
    })
    defaultChatModelId.value = result.defaultChatProviderId
    defaultImageModelId.value = result.defaultImageProviderId
    ElMessage.success('默认模型偏好已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存默认模型失败')
  } finally {
    savingModels.value = false
  }
}

async function loadBillingLogs() {
  billingLogsLoading.value = true
  try {
    const result = await billingApi.myLogs(billingLogsPage.value, 20, billingFrom.value, billingTo.value)
    billingLogs.value = result.content
    billingLogsTotal.value = result.totalElements
  } catch (error: any) {
    ElMessage.error(error.message || '加载消费日志失败')
  } finally {
    billingLogsLoading.value = false
  }
}

function formatTime(value: string) {
  return new Date(value).toLocaleString()
}

function applyBillingFilter() {
  if (!billingFrom.value || !billingTo.value) return ElMessage.warning('请选择完整的日志日期范围')
  const span = (new Date(billingTo.value).getTime() - new Date(billingFrom.value).getTime()) / 86400000
  if (span < 0) return ElMessage.warning('结束日期不能早于开始日期')
  if (span > 31) return ElMessage.warning('查询时间范围不能超过 31 天')
  billingLogsPage.value = 0
  void loadBillingLogs()
}


async function saveProfile() {
  saving.value = true
  try {
    const result = await authApi.updateProfile(profile)
    if (account.value) {
      account.value.displayName = result.displayName
      account.value.email = result.email
    }
    ElMessage.success('个人信息已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存个人信息失败')
  } finally {
    saving.value = false
  }
}

async function changePassword() {
  if (!password.current || !password.next) {
    ElMessage.warning('请输入当前密码和新密码')
    return
  }
  if (password.next.length < 6) {
    ElMessage.warning('新密码至少需要 6 个字符')
    return
  }
  if (password.next !== password.confirm) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  changingPassword.value = true
  try {
    await authApi.changePassword(password.current, password.next)
    ElMessage.success('密码已修改，请使用新密码重新登录')
    await auth.logout()
    await router.replace({ name: 'login' })
  } catch (error: any) {
    ElMessage.error(error.message || '修改密码失败')
  } finally {
    password.current = ''
    password.next = ''
    password.confirm = ''
    changingPassword.value = false
  }
}

onMounted(async () => {
  await load()
  await loadModels()
})
</script>

<template>
  <div class="profile-view" v-loading="loading">
    <div class="profile-header">
      <div class="profile-identity">
        <div class="profile-avatar" aria-hidden="true">
          {{ (account?.displayName || account?.username || 'AI').slice(0, 1).toUpperCase() }}
        </div>
        <div>
          <div class="eyebrow">ACCOUNT CENTER</div>
          <h1>个人中心</h1>
          <p>维护个人资料、模型偏好并查看消费记录。</p>
          <div v-if="account" class="account-meta">
            <span>@{{ account.username }}</span>
            <span class="meta-separator">·</span>
            <span class="status-dot">账号已启用</span>
          </div>
        </div>
      </div>
      <el-tag v-if="account" :type="account.role === 'ADMIN' ? 'warning' : 'primary'" effect="light">
        {{ account.role === 'ADMIN' ? '管理员' : '普通用户' }}
      </el-tag>
    </div>

    <el-tabs v-model="activeTab" class="profile-tabs" @tab-change="(tab: string) => { if (tab === 'billing') loadBillingLogs() }">
      <el-tab-pane label="基本信息" name="profile">
        <div class="profile-grid">
          <el-card class="profile-card" shadow="never">
            <template #header>
              <div class="card-title"><User /> <strong>基本信息</strong></div>
            </template>
            <el-form label-position="top" @submit.prevent="saveProfile">
              <el-form-item label="用户名">
                <el-input :model-value="account?.username" disabled />
                <small class="hint">用户名用于登录，创建后不能修改。</small>
              </el-form-item>
              <el-form-item label="显示名称">
                <el-input v-model="profile.displayName" maxlength="100" show-word-limit placeholder="例如：张三" />
              </el-form-item>
              <el-form-item label="邮箱">
                <el-input v-model="profile.email" maxlength="254" placeholder="用于联系的邮箱（可选）" />
              </el-form-item>
              <el-button type="primary" :loading="saving" @click="saveProfile">保存个人信息</el-button>
            </el-form>
          </el-card>

          <el-card class="profile-card" shadow="never">
            <template #header>
              <div class="card-title"><Lock /> <strong>修改密码</strong></div>
            </template>
            <el-form label-position="top" @submit.prevent="changePassword">
              <el-form-item label="当前密码">
                <el-input v-model="password.current" type="password" show-password autocomplete="current-password" />
              </el-form-item>
              <el-form-item label="新密码">
                <el-input v-model="password.next" type="password" show-password autocomplete="new-password" />
              </el-form-item>
              <el-form-item label="确认新密码">
                <el-input v-model="password.confirm" type="password" show-password autocomplete="new-password" />
              </el-form-item>
              <p class="hint">密码仅在浏览器端计算摘要后通过 RSA 加密传输。</p>
              <el-button type="primary" :loading="changingPassword" @click="changePassword">修改密码</el-button>
            </el-form>
          </el-card>
        </div>
      </el-tab-pane>

      <el-tab-pane label="模型偏好" name="models">
        <el-card class="profile-card" shadow="never">
          <template #header>
            <div class="card-title"><ChatDotRound /> <strong>默认模型偏好</strong></div>
          </template>
          <div class="default-selectors">
            <div class="default-field">
              <label><el-icon><ChatDotRound /></el-icon> 默认对话模型</label>
              <el-select v-model="defaultChatModelId" clearable filterable placeholder="请选择默认对话模型" :loading="loadingModels">
                <el-option
                  v-for="model in chatModels"
                  :key="model.id"
                  :label="`${model.name || model.providerId} / ${model.modelName}`"
                  :value="model.id"
                />
              </el-select>
              <small class="hint">新建会话时自动使用此对话模型</small>
            </div>
            <div class="default-field">
              <label><el-icon><Picture /></el-icon> 默认图像模型</label>
              <el-select v-model="defaultImageModelId" clearable filterable placeholder="请选择默认图像模型" :loading="loadingModels">
                <el-option
                  v-for="model in imageModels"
                  :key="model.id"
                  :label="`${model.name || model.providerId} / ${model.modelName}`"
                  :value="model.id"
                />
              </el-select>
              <small class="hint">新建会话时自动使用此图像模型</small>
            </div>
          </div>
          <div class="save-section">
            <el-button type="primary" :loading="savingModels" @click="saveModels">保存模型偏好</el-button>
          </div>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="消费日志" name="billing">
        <el-card class="profile-card" shadow="never">
          <template #header>
            <div class="card-title"><Lock /> <strong>我的消费记录</strong></div>
          </template>
          <div class="billing-filter">
            <el-date-picker v-model="billingFrom" type="date" value-format="YYYY-MM-DD" placeholder="开始日期" />
            <span>至</span>
            <el-date-picker v-model="billingTo" type="date" value-format="YYYY-MM-DD" placeholder="结束日期" />
            <el-button type="primary" @click="applyBillingFilter">查询</el-button>
          </div>
          <el-table v-loading="billingLogsLoading" :data="billingLogs" stripe empty-text="暂无消费记录">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="providerName" label="供应商" min-width="140">
              <template #default="{ row }">{{ row.providerName || '—' }}</template>
            </el-table-column>
            <el-table-column prop="modelName" label="模型" min-width="160">
              <template #default="{ row }">{{ row.modelName || '—' }}</template>
            </el-table-column>
            <el-table-column label="计费模式" width="120">
              <template #default="{ row }">
                <el-tag v-if="row.billingMode === 'per_request'" size="small">按次</el-tag>
                <el-tag v-else-if="row.billingMode === 'per_token'" type="warning" size="small">按 Token</el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="Token 用量" min-width="230">
            <template #default="{ row }">
              输入 {{ row.inputTokens ?? row.promptTokens ?? 0 }} · 输出 {{ row.outputTokens ?? row.completionTokens ?? 0 }} · 缓存读取 {{ row.cacheReadTokens ?? 0 }}
            </template>
          </el-table-column>
          <el-table-column label="费用" width="100">
              <template #default="{ row }">
                {{ row.amount != null ? row.amount : row.totalTokens != null ? `${row.totalTokens} tokens` : '—' }}
              </template>
            </el-table-column>
            <el-table-column prop="description" label="说明" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.description || '—' }}</template>
            </el-table-column>
            <el-table-column label="时间" min-width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
          <div v-if="billingLogsTotal > 20" class="pagination-bar">
            <el-pagination
              v-model:current-page="billingLogsPage"
              :page-size="20"
              :total="billingLogsTotal"
              layout="prev, pager, next"
              @current-change="loadBillingLogs"
            />
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.billing-filter { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; color: #7c87a1; font-size: 13px; }
.billing-filter :deep(.el-date-editor) { width: 150px; }

.profile-view {
  position: relative;
  width: min(100%, 1120px);
  min-height: 100%;
  margin: 0 auto;
  padding: clamp(22px, 4vw, 42px) clamp(14px, 3vw, 32px) 56px;
}

.profile-view::before {
  position: absolute;
  z-index: -1;
  top: 0;
  right: 5%;
  width: 280px;
  height: 180px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(136, 113, 255, .16), transparent 70%);
  content: '';
  pointer-events: none;
}

.profile-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: clamp(20px, 3vw, 28px);
  margin-bottom: 22px;
  border: 1px solid rgba(218, 222, 247, .9);
  border-radius: 22px;
  background: linear-gradient(120deg, rgba(255, 255, 255, .96), rgba(246, 247, 255, .92));
  box-shadow: 0 12px 30px rgba(56, 69, 139, .08);
}

.profile-identity { display: flex; align-items: center; gap: 16px; min-width: 0; }
.profile-avatar {
  display: grid;
  flex: 0 0 auto;
  width: 58px;
  height: 58px;
  place-items: center;
  color: #fff;
  font-size: 24px;
  font-weight: 800;
  border: 4px solid rgba(255, 255, 255, .85);
  border-radius: 18px;
  background: linear-gradient(135deg, #586bf7, #9b5cf6);
  box-shadow: 0 9px 18px rgba(82, 93, 220, .25);
}

.eyebrow { color: #7f8ab0; font-size: 11px; font-weight: 800; letter-spacing: .14em; }
h1 { margin: 5px 0 4px; color: #273453; font-size: clamp(24px, 3vw, 30px); letter-spacing: -.03em; }
.profile-header p { margin: 0; color: #8791aa; }
.account-meta { display: flex; align-items: center; gap: 7px; margin-top: 9px; color: #7a86a5; font-size: 12px; }
.meta-separator { color: #bbc1d4; }
.status-dot::before { display: inline-block; width: 6px; height: 6px; margin: 0 5px 1px 0; border-radius: 50%; background: #2fc58b; content: ''; }
.profile-header :deep(.el-tag) { flex: 0 0 auto; margin-top: 2px; border: 0; border-radius: 999px; font-weight: 700; }

.profile-tabs { margin-top: 6px; }
.profile-tabs :deep(.el-tabs__header) { margin: 0 0 18px; border-bottom: 1px solid #edf0f7; }
.profile-tabs :deep(.el-tabs__item) { height: 42px; color: #7a86a5; font-size: 14px; font-weight: 700; }
.profile-tabs :deep(.el-tabs__item.is-active) { color: #5368f6; }
.profile-tabs :deep(.el-tabs__active-bar) { background: #5368f6; }

.profile-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; align-items: stretch; }
.profile-card { border: 1px solid rgba(224, 227, 243, .95); border-radius: 18px; background: rgba(255, 255, 255, .95); box-shadow: 0 10px 28px rgba(56, 69, 139, .06); }
.profile-card :deep(.el-card__header) { padding: 18px 22px 15px; border-bottom-color: #edf0fa; }
.profile-card :deep(.el-card__body) { padding: 20px 22px 22px; }
.card-title { display: flex; align-items: center; gap: 9px; color: #3d4966; font-size: 15px; }
.card-title svg { width: 17px; color: #5267f6; }
.profile-card :deep(.el-form-item) { margin-bottom: 17px; }
.profile-card :deep(.el-form-item__label) { padding-bottom: 6px; color: #66718d; font-size: 13px; font-weight: 700; }
.profile-card :deep(.el-button) { min-height: 38px; margin-top: 2px; border-radius: 10px; }
.hint { display: block; margin-top: 4px; color: #929bae; font-size: 12px; line-height: 1.5; }

.default-selectors { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 22px; }
.default-field { display: flex; flex-direction: column; gap: 9px; padding: 14px; border: 1px solid #edf0f8; border-radius: 12px; background: #fafbff; }
.default-field label { display: flex; align-items: center; gap: 7px; color: #56617c; font-size: 13px; font-weight: 700; }
.default-field label .el-icon { color: var(--app-primary); }
.default-field .el-select { width: 100%; }
.default-field .hint { margin-top: 2px; color: #929bae; font-size: 11px; }

.save-section { margin-top: 18px; }

.pagination-bar { display: flex; justify-content: center; padding: 18px 0 4px; }
.muted { color: #929bae; font-size: 12px; }

@media (max-width: 760px) {
  .profile-grid { grid-template-columns: 1fr; }
  .profile-header { flex-direction: column; }
  .profile-header :deep(.el-tag) { margin-top: 0; }
  .default-selectors { grid-template-columns: 1fr; }
}

@media (max-width: 460px) {
  .profile-identity { align-items: flex-start; }
  .profile-avatar { width: 48px; height: 48px; font-size: 20px; border-radius: 15px; }
  .profile-card :deep(.el-card__body), .profile-card :deep(.el-card__header) { padding-right: 16px; padding-left: 16px; }
}
</style>