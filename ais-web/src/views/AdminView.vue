<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Connection, DataAnalysis, Download, Lock, Monitor, Picture, Plus, Search, Upload, UserFilled } from '@element-plus/icons-vue'
import type {
  ModelProvider,
  ProviderAccount,
  ProviderAccountRequest,
  SystemModelSettings,
} from '@/types'
import { providerAccountApi } from '@/api/providers'
import {
  adminApi,
  type ExportPreview,
  type ExportSection,
  type ImportMode,
  type LoginSecurityEvent,
  type SecuritySettings,
} from '@/api/admin'
import { useSessionStore } from '@/stores/session'
import { usersApi, type ManagedUser } from '@/api/users'
import { billingApi } from '@/api/billing'
import { adminApi } from '@/api/admin'
import type { AuthRole } from '@/api/auth'
import ProviderDialog from '@/components/ProviderDialog.vue'
import LlmDebugPanel from '@/components/LlmDebugPanel.vue'

const router = useRouter()

function routeWithSource(path: string) {
  return router.currentRoute.value.query.source === 'feishu'
    ? { path, query: { source: 'feishu' } }
    : { path }
}
const sessionStore = useSessionStore()
type AdminSection = 'models' | 'security' | 'data' | 'tools' | 'users' | 'billing' | 'billing-logs'
const activeSection = ref<AdminSection>('models')
const adminMenuGroups = [
  {
    label: '业务配置',
    items: [
      { key: 'models' as AdminSection, label: '模型与供应商', hint: '默认模型和接口配置', icon: Connection },
      { key: 'billing' as AdminSection, label: '计费管理', hint: '模型计费配置', icon: DataAnalysis },
    ],
  },
  {
    label: '系统管理',
    items: [
      { key: 'security' as AdminSection, label: '安全防护', hint: '登录与封锁策略', icon: Lock },
      { key: 'data' as AdminSection, label: '数据管理', hint: '备份与系统迁移', icon: DataAnalysis },
      { key: 'tools' as AdminSection, label: '诊断工具', hint: '查看模型通信记录', icon: Monitor },
    ],
  },
  {
    label: '账户管理',
    items: [
      { key: 'users' as AdminSection, label: '用户管理', hint: '账号与角色维护', icon: UserFilled },
      { key: 'billing-logs' as AdminSection, label: '消费日志', hint: '用户消费记录', icon: DataAnalysis },
    ],
  },
]
const accounts = ref<ProviderAccount[]>([])
const defaults = ref<SystemModelSettings>({ defaultChatModelId: null, defaultImageModelId: null })
const loading = ref(false)
const savingDefaults = ref(false)
const dialogRef = ref<InstanceType<typeof ProviderDialog> | null>(null)
const editingAccount = ref<ProviderAccount | null>(null)
const keyword = ref('')

const exportSections = ref<ExportSection[]>(['sessions', 'providers', 'settings', 'files'])
const includeApiKeys = ref(false)
const importMode = ref<ImportMode>('merge')
const importSections = ref<ExportSection[]>(['sessions', 'providers', 'settings', 'files'])
const exportPreview = ref<ExportPreview | null>(null)
const exporting = ref(false)
const importing = ref(false)
const importFile = ref<File | null>(null)

const securitySettings = ref<SecuritySettings>({
  maxFailures: 5,
  failureWindowMinutes: 15,
  lockDurationMinutes: 30,
  captchaEnabled: true,
})
const savingSecurity = ref(false)
const securityEvents = ref<LoginSecurityEvent[]>([])
const loadingSecurityEvents = ref(false)
const securitySectionLoaded = ref(false)
const dataSectionLoaded = ref(false)

const sectionOptions: { label: string; value: ExportSection; hint: string }[] = [
  { label: '会话消息', value: 'sessions', hint: '会话、消息与附件元数据' },
  { label: '供应商配置', value: 'providers', hint: '供应商账号与模型' },
  { label: '系统设置', value: 'settings', hint: '默认对话/图像模型' },
  { label: '文件', value: 'files', hint: '数据目录下的图片与附件' },
]

const filteredAccounts = computed(() => {
  const term = keyword.value.trim().toLowerCase()
  if (!term) return accounts.value
  return accounts.value.filter((account) => [
    account.providerKey,
    account.name,
    account.baseUrl,
    ...account.models.map((model) => model.modelName),
  ].some((value) => (value || '').toLowerCase().includes(term)))
})

const chatGroups = computed(() => modelGroups('CHAT'))
const imageGroups = computed(() => modelGroups('IMAGE'))

function selectAdminSection(section: AdminSection | 'users') {
  if (section === 'users') {
    activeSection.value = 'users'
    return
  }
  activeSection.value = section
}

onMounted(async () => {
  await refreshAll()
})

watch(activeSection, (section) => {
  void loadSectionData(section)
}, { immediate: true })

async function refreshDataPreview() {
  await loadExportPreview()
  dataSectionLoaded.value = true
}

async function loadSectionData(section: AdminSection) {
  if (section === 'security' && !securitySectionLoaded.value) {
    await Promise.all([loadSecuritySettings(), loadSecurityEvents()])
    securitySectionLoaded.value = true
  }
  if (section === 'data' && !dataSectionLoaded.value) {
    await loadExportPreview()
    dataSectionLoaded.value = true
  }
}

async function loadExportPreview() {
  try {
    exportPreview.value = await adminApi.preview()
  } catch (error: any) {
    ElMessage.error(error.message || '加载导出预览失败')
  }
}

async function loadSecuritySettings() {
  try {
    securitySettings.value = await adminApi.getSecuritySettings()
  } catch (error: any) {
    ElMessage.error(error.message || '加载登录防护配置失败')
  }
}

async function saveSecuritySettings() {
  savingSecurity.value = true
  try {
    securitySettings.value = await adminApi.updateSecuritySettings(securitySettings.value)
    ElMessage.success('登录防护配置已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存登录防护配置失败')
  } finally {
    savingSecurity.value = false
  }
}

async function loadSecurityEvents() {
  loadingSecurityEvents.value = true
  try {
    securityEvents.value = await adminApi.getSecurityEvents()
  } catch (error: any) {
    ElMessage.error(error.message || '加载登录封锁日志失败')
  } finally {
    loadingSecurityEvents.value = false
  }
}

function securityEventTypeLabel(eventType: LoginSecurityEvent['eventType']) {
  return eventType === 'IP_LOCKED' ? 'IP 已封锁' : '账号已封锁'
}

function securityEventTagType(eventType: LoginSecurityEvent['eventType']) {
  return eventType === 'IP_LOCKED' ? 'warning' : 'danger'
}

function formatSecurityEventTime(value: string | null) {
  return value ? new Date(value).toLocaleString() : '—'
}

async function handleExport() {
  if (exportSections.value.length === 0) {
    ElMessage.warning('请至少选择一个导出分区')
    return
  }
  if (includeApiKeys.value) {
    try {
      await ElMessageBox.confirm(
        '导出包将包含供应商 API Key 明文，请妥善保管。确认继续？',
        '敏感信息确认',
        { type: 'warning' },
      )
    } catch {
      return
    }
  }
  exporting.value = true
  try {
    const { blob, filename } = await adminApi.exportData({
      sections: exportSections.value,
      includeApiKeys: includeApiKeys.value,
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
    ElMessage.success('导出完成')
  } catch (error: any) {
    ElMessage.error(error.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

function handleImportFileChange(uploadFile: { raw?: File | null }) {
  if (uploadFile.raw) {
    importFile.value = uploadFile.raw
  }
}

function handleImportFileRemove() {
  importFile.value = null
}

async function handleImport() {
  if (!importFile.value) {
    ElMessage.warning('请先选择 ZIP 文件')
    return
  }
  if (importSections.value.length === 0) {
    ElMessage.warning('请至少选择一个导入分区')
    return
  }
  if (importMode.value === 'replace') {
    try {
      await ElMessageBox.confirm(
        '覆盖模式会先清空所选分区的现有数据再写入，此操作不可撤销。确认继续？',
        '危险操作确认',
        { type: 'warning', confirmButtonText: '确认覆盖导入' },
      )
    } catch {
      return
    }
  }
  importing.value = true
  try {
    const result = await adminApi.importData(importFile.value, {
      mode: importMode.value,
      sections: importSections.value,
      includeApiKeys: true,
    })
    ElMessage.success(`导入完成：${JSON.stringify(result)}`)
    importFile.value = null
    await Promise.all([refreshProviderLists(), loadExportPreview()])
  } catch (error: any) {
    ElMessage.error(error.message || '导入失败')
  } finally {
    importing.value = false
  }
}

function modelGroups(type: ModelProvider['type']) {
  return accounts.value
    .map((account) => ({
      label: account.name || account.providerKey,
      options: account.models.filter((model) => model.type === type),
    }))
    .filter((group) => group.options.length > 0)
}

async function refreshAll() {
  loading.value = true
  try {
    const [providerAccounts, settings] = await Promise.all([
      providerAccountApi.list(),
      providerAccountApi.getDefaults(),
    ])
    accounts.value = providerAccounts
    defaults.value = settings
  } catch (error: any) {
    ElMessage.error(`加载模型配置失败: ${error.message || '未知错误'}`)
  } finally {
    loading.value = false
  }
}

async function refreshProviderLists() {
  await Promise.all([refreshAll(), sessionStore.fetchProviders()])
}

function handleAdd() {
  editingAccount.value = null
  dialogRef.value?.open(null)
}

function handleEdit(account: ProviderAccount) {
  editingAccount.value = account
  dialogRef.value?.open(account)
}

async function handleSubmit(data: ProviderAccountRequest) {
  try {
    if (editingAccount.value) {
      await providerAccountApi.update(editingAccount.value.id, data)
      ElMessage.success('供应商更新成功')
    } else {
      await providerAccountApi.create(data)
      ElMessage.success('供应商添加成功')
    }
    await refreshProviderLists()
  } catch (error: any) {
    ElMessage.error(error.message || '保存供应商失败')
  }
}

async function handleDelete(account: ProviderAccount) {
  try {
    await ElMessageBox.confirm(
      `确定删除供应商「${account.name || account.providerKey}」及其 ${account.models.length} 个模型吗？`,
      '确认删除',
      { type: 'warning' },
    )
    await providerAccountApi.delete(account.id)
    ElMessage.success('供应商已删除')
    await refreshProviderLists()
  } catch {
    // cancelled
  }
}

async function handleTestConnection(account: ProviderAccount) {
  try {
    const result = await providerAccountApi.testConnection(account.id)
    result.success
      ? ElMessage.success(`连接成功（${result.responseTimeMs ?? 0}ms）`)
      : ElMessage.error(`连接失败: ${result.message}`)
  } catch (error: any) {
    ElMessage.error(error.message || '连接测试失败')
  }
}

async function saveDefaults() {
  savingDefaults.value = true
  try {
    defaults.value = await providerAccountApi.updateDefaults(defaults.value)
    await sessionStore.fetchProviders()
    ElMessage.success('系统默认模型已保存')
  } catch (error: any) {
    ElMessage.error(error.message || '保存默认模型失败')
  } finally {
    savingDefaults.value = false
  }
}

function countModels(account: ProviderAccount, type: ModelProvider['type']) {
  return account.models.filter((model) => model.type === type).length
}

function formatApiKey(value: string) {
  return value || '未配置'
}
</script>

<template>
  <div class="admin-view">
    <div class="admin-header">
      <div>
        <div class="admin-eyebrow">ADMIN CONSOLE</div>
        <h2>管理中心</h2>
        <p>配置模型服务、账户安全和数据运维，保持核心业务设置清晰可见。</p>
      </div>
      <div class="admin-header-actions">
        <el-button v-if="activeSection === 'models'" type="primary" :icon="Plus" @click="handleAdd">添加供应商</el-button>
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
              :class="{ active: activeSection === item.key }"
              @click="selectAdminSection(item.key)"
            >
              <el-icon><component :is="item.icon" /></el-icon>
              <span>
                <strong>{{ item.label }}</strong>
                <small>{{ item.hint }}</small>
              </span>
            </button>
          </template>
        </nav>
      </aside>

      <main class="admin-content">
        <section v-if="activeSection === 'models'" class="admin-section">
          <div class="section-intro">
            <div>
              <span class="section-kicker">CORE CONFIGURATION</span>
              <h3>模型与供应商</h3>
              <p>维护 API 供应商、模型能力以及系统默认的对话和图像模型。</p>
            </div>
            <el-button plain @click="router.push(routeWithSource('/admin/users'))">进入用户与权限</el-button>
          </div>

          <el-card class="defaults-card" shadow="never">
            <template #header>
              <div class="card-header">
                <div>
                  <strong>系统默认模型</strong>
                  <span>默认对话模型与默认图像模型是独立配置项</span>
                </div>
                <el-button type="primary" :loading="savingDefaults" @click="saveDefaults">保存默认配置</el-button>
              </div>
            </template>
            <div class="default-selectors">
              <div class="default-field">
                <label><el-icon><ChatDotRound /></el-icon> 默认对话模型</label>
                <el-select v-model="defaults.defaultChatModelId" clearable filterable placeholder="请选择默认对话模型">
                  <el-option-group v-for="group in chatGroups" :key="group.label" :label="group.label">
                    <el-option
                      v-for="model in group.options"
                      :key="model.id"
                      :label="`${group.label} / ${model.modelName}`"
                      :value="model.id"
                    />
                  </el-option-group>
                </el-select>
              </div>
              <div class="default-field">
                <label><el-icon><Picture /></el-icon> 默认图像模型</label>
                <el-select v-model="defaults.defaultImageModelId" clearable filterable placeholder="请选择默认图像模型">
                  <el-option-group v-for="group in imageGroups" :key="group.label" :label="group.label">
                    <el-option
                      v-for="model in group.options"
                      :key="model.id"
                      :label="`${group.label} / ${model.modelName}`"
                      :value="model.id"
                    />
                  </el-option-group>
                </el-select>
              </div>
            </div>
          </el-card>

          <div class="list-header">
            <div>
              <h3>供应商列表</h3>
              <span>共 {{ filteredAccounts.length }} 个供应商，{{ accounts.reduce((sum, item) => sum + item.models.length, 0) }} 个模型</span>
            </div>
            <el-input
              v-model="keyword"
              clearable
              class="keyword-filter"
              placeholder="搜索供应商、模型或 API 地址"
              :prefix-icon="Search"
            />
          </div>

          <el-table
            v-loading="loading"
            :data="filteredAccounts"
            row-key="id"
            stripe
            empty-text="暂无供应商配置"
          >
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expanded-models">
                  <div class="expanded-title">此供应商提供的模型</div>
                  <el-table :data="row.models" size="small" border>
                    <el-table-column label="类型" width="120">
                      <template #default="{ row: model }">
                        <el-tag :type="model.type === 'CHAT' ? 'primary' : 'success'" size="small">
                          {{ model.type === 'CHAT' ? '对话' : '图像' }}
                        </el-tag>
                      </template>
                    </el-table-column>
                    <el-table-column prop="modelName" label="模型名称" min-width="200" />
                    <el-table-column label="能力" width="155">
                      <template #default="{ row: model }">
                        <div v-if="model.type === 'IMAGE'" class="capability-tags">
                          <el-tag v-if="model.supportsTextToImage" size="small" effect="plain">文生图</el-tag>
                          <el-tag v-if="model.supportsImageToImage" size="small" type="success" effect="plain">图生图</el-tag>
                          <span v-if="!model.supportsTextToImage && !model.supportsImageToImage" class="muted">未记录</span>
                        </div>
                        <span v-else class="muted">—</span>
                      </template>
                    </el-table-column>
                    <el-table-column label="价格" min-width="170">
                      <template #default="{ row: model }"><span>{{ model.priceDescription || '—' }}</span></template>
                    </el-table-column>
                    <el-table-column prop="timeoutSeconds" label="超时（秒）" width="110" />
                    <el-table-column label="默认状态" width="140">
                      <template #default="{ row: model }">
                        <el-tag v-if="model.id === defaults.defaultChatModelId" type="success" size="small">默认对话</el-tag>
                        <el-tag v-else-if="model.id === defaults.defaultImageModelId" type="success" size="small">默认图像</el-tag>
                        <span v-else class="muted">非默认</span>
                      </template>
                    </el-table-column>
                    <el-table-column prop="adapterType" label="图片适配器" width="140" />
                  </el-table>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="供应商" min-width="190">
              <template #default="{ row }">
                <div class="provider-name">
                  <strong>{{ row.name || row.providerKey }}</strong>
                  <span>{{ row.providerKey }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column prop="baseUrl" label="API 请求地址" min-width="260" show-overflow-tooltip />
            <el-table-column label="API Key" width="150">
              <template #default="{ row }">{{ formatApiKey(row.apiKey) }}</template>
            </el-table-column>
            <el-table-column label="模型数量" width="210">
              <template #default="{ row }">
                <div class="model-counts">
                  <el-tag type="primary" effect="plain" size="small">对话 {{ countModels(row, 'CHAT') }}</el-tag>
                  <el-tag type="success" effect="plain" size="small">图像 {{ countModels(row, 'IMAGE') }}</el-tag>
                  <span>共 {{ row.models.length }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <div class="actions">
                  <el-button size="small" :icon="Connection" @click="handleTestConnection(row)">测试</el-button>
                  <el-button size="small" @click="handleEdit(row)">编辑</el-button>
                  <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </section>

        <section v-else-if="activeSection === 'security'" class="admin-section">
          <div class="section-intro">
            <div>
              <span class="section-kicker">SECURITY</span>
              <h3>安全防护</h3>
              <p>调整登录验证码、失败锁定策略，并查看近期封锁事件。</p>
            </div>
          </div>
          <el-card class="defaults-card security-card" shadow="never">
            <template #header>
              <div class="card-header">
                <div><strong>登录防护</strong><span>验证码、IP 连续失败锁定阈值与锁定时长可在此调整</span></div>
                <el-button type="primary" :loading="savingSecurity" @click="saveSecuritySettings">保存防护配置</el-button>
              </div>
            </template>
            <div class="security-grid">
              <div class="default-field"><label>失败次数阈值</label><el-input-number v-model="securitySettings.maxFailures" :min="1" :max="100" /><small>同一 IP 在时间窗口内达到该次数后触发锁定</small></div>
              <div class="default-field"><label>统计窗口（分钟）</label><el-input-number v-model="securitySettings.failureWindowMinutes" :min="1" :max="1440" /><small>超过窗口后失败计数清零重新累计</small></div>
              <div class="default-field"><label>锁定时长（分钟）</label><el-input-number v-model="securitySettings.lockDurationMinutes" :min="1" :max="10080" /><small>锁定期间拒绝该 IP 的登录请求</small></div>
              <div class="default-field"><label>启用验证码</label><el-switch v-model="securitySettings.captchaEnabled" /><small>开启后登录页需要图形验证码</small></div>
            </div>
          </el-card>
          <el-card class="defaults-card" shadow="never">
            <template #header>
              <div class="card-header"><div><strong>登录封锁日志</strong><span>记录 IP 达到失败阈值以及禁用账号的登录尝试</span></div><el-button text type="primary" :loading="loadingSecurityEvents" @click="loadSecurityEvents">刷新日志</el-button></div>
            </template>
            <el-table v-loading="loadingSecurityEvents" :data="securityEvents" stripe>
              <el-table-column label="类型" min-width="110"><template #default="{ row }"><el-tag :type="securityEventTagType(row.eventType)" size="small">{{ securityEventTypeLabel(row.eventType) }}</el-tag></template></el-table-column>
              <el-table-column prop="username" label="账号" min-width="140"><template #default="{ row }">{{ row.username || '—' }}</template></el-table-column>
              <el-table-column prop="ipAddress" label="IP 地址" min-width="150"><template #default="{ row }">{{ row.ipAddress || '—' }}</template></el-table-column>
              <el-table-column prop="failureCount" label="失败次数" width="100"><template #default="{ row }">{{ row.failureCount ?? '—' }}</template></el-table-column>
              <el-table-column label="封锁截止" min-width="175"><template #default="{ row }">{{ formatSecurityEventTime(row.lockedUntil) }}</template></el-table-column>
              <el-table-column label="发生时间" min-width="175"><template #default="{ row }">{{ formatSecurityEventTime(row.occurredAt) }}</template></el-table-column>
              <el-table-column prop="detail" label="说明" min-width="210" />
            </el-table>
            <el-empty v-if="!loadingSecurityEvents && securityEvents.length === 0" description="暂无登录封锁日志" />
          </el-card>
        </section>

        <section v-else-if="activeSection === 'data'" class="admin-section">
          <div class="section-intro"><div><span class="section-kicker">DATA OPERATIONS</span><h3>数据管理</h3><p>按分区备份、恢复和迁移系统数据，危险操作会要求二次确认。</p></div></div>
          <el-card class="defaults-card migration-card" shadow="never">
            <template #header><div class="card-header"><div><strong>数据迁移</strong><span>按分区导出/导入，便于备份、库切换与系统迁移</span></div><el-button text type="primary" @click="refreshDataPreview">刷新统计</el-button></div></template>
            <div class="migration-grid">
              <div class="migration-panel">
                <h4>导出</h4>
                <p v-if="exportPreview" class="migration-stats">会话 {{ exportPreview.sessions }} · 消息 {{ exportPreview.messages }} · 附件 {{ exportPreview.attachments }} · 供应商 {{ exportPreview.providers }} · 模型 {{ exportPreview.models }}</p>
                <el-checkbox-group v-model="exportSections" class="section-group"><el-checkbox v-for="item in sectionOptions" :key="item.value" :label="item.value">{{ item.label }}<small>{{ item.hint }}</small></el-checkbox></el-checkbox-group>
                <el-checkbox v-model="includeApiKeys">导出 API Key 明文（默认脱敏）</el-checkbox>
                <el-button type="primary" :icon="Download" :loading="exporting" @click="handleExport">导出 ZIP</el-button>
              </div>
              <div class="migration-panel">
                <h4>导入</h4>
                <el-radio-group v-model="importMode" class="mode-group"><el-radio-button value="merge">合并 (merge)</el-radio-button><el-radio-button value="replace">覆盖 (replace)</el-radio-button></el-radio-group>
                <el-checkbox-group v-model="importSections" class="section-group"><el-checkbox v-for="item in sectionOptions" :key="`import-${item.value}`" :label="item.value">{{ item.label }}</el-checkbox></el-checkbox-group>
                <el-upload :auto-upload="false" :show-file-list="true" :limit="1" accept=".zip,application/zip" :on-change="handleImportFileChange" :on-remove="handleImportFileRemove"><el-button :icon="Upload">选择 ZIP</el-button></el-upload>
                <el-button type="warning" :loading="importing" @click="handleImport">开始导入</el-button>
              </div>
            </div>
          </el-card>
        </section>

        <section v-else class="admin-section">
          <div class="section-intro"><div><span class="section-kicker">DIAGNOSTICS</span><h3>诊断工具</h3><p>查看最近一次模型调用的请求、响应和调试信息，仅在需要排查问题时使用。</p></div></div>
          <LlmDebugPanel class="debug-panel" />
        </section>
      </main>
    </div>

    <ProviderDialog ref="dialogRef" :provider="editingAccount" @submit="handleSubmit" />
  </div>
</template>

<style scoped>
.admin-view { height: 100%; overflow-y: auto; box-sizing: border-box; padding: clamp(20px, 3vw, 36px); background: radial-gradient(circle at 95% 0%, rgba(150,103,244,.12), transparent 25rem), #f7f8fe; }
.admin-header { display: flex; align-items: center; justify-content: space-between; margin: 2px 0 24px; }.admin-header h2 { margin: 0; color: #263452; font-size: 25px; letter-spacing: -.45px; }.admin-header p { max-width: 700px; margin: 8px 0 0; color: #7c87a1; font-size: 13px; }.admin-header :deep(.el-button) { min-height: 38px; padding: 0 15px; border-radius: 10px; }
.defaults-card, .debug-panel { margin-bottom: 20px; overflow: hidden; border-radius: 16px; box-shadow: 0 9px 26px rgba(44,57,112,.06); }.defaults-card :deep(.el-card__header) { padding: 15px 19px; border-bottom-color: #edf0f7; background: linear-gradient(90deg, #fbfcff, #f5f3ff); }.defaults-card :deep(.el-card__body) { padding: 20px; }
.security-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.security-grid .default-field small { color: #929bb0; font-size: 11px; line-height: 1.45; }
.migration-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
@media (max-width: 900px) { .security-grid { grid-template-columns: 1fr; } }
.migration-panel { display: flex; flex-direction: column; gap: 12px; padding: 14px; border: 1px solid #edf0f8; border-radius: 12px; background: #fafbff; }
.migration-panel h4 { margin: 0; color: #35415f; font-size: 14px; }
.migration-stats { margin: 0; color: #8992a9; font-size: 12px; line-height: 1.5; }
.section-group { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.section-group small { margin-left: 6px; color: #929bb0; font-size: 11px; }
.mode-group { align-self: flex-start; }
@media (max-width: 900px) { .migration-grid { grid-template-columns: 1fr; } }
.card-header { display: flex; align-items: center; justify-content: space-between; }.card-header strong { margin-right: 12px; color: #35415f; font-size: 15px; }.card-header span { color: #8992a9; font-size: 12px; }.card-header :deep(.el-button) { border-radius: 9px; }
.default-selectors { display: grid; grid-template-columns: repeat(2, minmax(0,1fr)); gap: 22px; }.default-field { display: flex; flex-direction: column; gap: 9px; padding: 14px; border: 1px solid #edf0f8; border-radius: 12px; background: #fafbff; }.default-field label { display: flex; align-items: center; gap: 7px; color: #56617c; font-size: 13px; font-weight: 700; }.default-field label .el-icon { color: var(--app-primary); }.default-field .el-select { width: 100%; }
.list-header { display: flex; align-items: end; justify-content: space-between; margin: 28px 0 13px; }.list-header h3 { margin: 0 0 5px; color: #34415e; font-size: 17px; }.list-header span { color: #8992a9; font-size: 12px; }.keyword-filter { width: 330px; }.keyword-filter :deep(.el-input__wrapper) { border-radius: 10px; background: rgba(255,255,255,.86); box-shadow: 0 0 0 1px #e6e9f6 inset; }
.admin-view > :deep(.el-table) { overflow: hidden; border: 1px solid #e5e8f4; border-radius: 16px; background: rgba(255,255,255,.93); box-shadow: 0 12px 30px rgba(42,55,110,.07); }.admin-view > :deep(.el-table th.el-table__cell) { color: #69758f; font-size: 12px; font-weight: 800; background: #f5f6fc; }.admin-view > :deep(.el-table tr) { background: transparent; }.admin-view > :deep(.el-table .el-table__row:hover > td.el-table__cell) { background: #f7f8ff; }.admin-view > :deep(.el-table td.el-table__cell), .admin-view > :deep(.el-table th.el-table__cell) { padding: 13px 0; border-bottom-color: #edf0f7; }
.provider-name { display: flex; flex-direction: column; gap: 4px; }.provider-name strong { color: #3d4966; }.provider-name span { color: #919ab0; font-size: 12px; }.model-counts { display: flex; align-items: center; gap: 6px; }.model-counts > span { color: #9099af; font-size: 12px; }.actions { display: flex; gap: 7px; white-space: nowrap; }.actions .el-button { margin-left: 0; border-radius: 8px; }.expanded-models { padding: 12px 50px 20px; background: #fafbff; }.expanded-title { margin-bottom: 10px; color: #56617d; font-size: 13px; font-weight: 800; }.muted { color: #929bae; font-size: 12px; }.capability-tags { display: flex; flex-wrap: wrap; gap: 4px; }
@media (max-width: 900px) { .default-selectors { grid-template-columns: 1fr; }.list-header { align-items: stretch; flex-direction: column; gap: 12px; }.keyword-filter { width: 100%; }.admin-header { align-items: flex-start; gap: 14px; flex-direction: column; } }
@media (max-width: 600px) { .admin-view { padding: 18px 14px; }.admin-header h2 { font-size: 21px; }.card-header { align-items: flex-start; gap: 11px; flex-direction: column; }.expanded-models { padding: 10px; } }


/* Admin navigation keeps the core model workflow visible and folds secondary tools away. */
.admin-view {
  display: flex;
  flex-direction: column;
  min-height: 100%;
}

.admin-eyebrow {
  margin-bottom: 5px;
  color: #8490b4;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .16em;
}

.admin-layout {
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  align-items: start;
  gap: 22px;
  min-width: 0;
}

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

.subnav-heading {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 4px 8px 12px;
  border-bottom: 1px solid #edf0f7;
}

.subnav-heading strong { color: #35415f; font-size: 14px; }
.subnav-heading span { color: #a0a8ba; font-size: 11px; }
.subnav-list { display: flex; flex-direction: column; gap: 4px; padding-top: 10px; }
.subnav-group-label { padding: 10px 8px 4px; color: #a0a8ba; font-size: 10px; font-weight: 800; letter-spacing: .08em; }
.subnav-group-label:first-child { padding-top: 2px; }
.account-group-label { margin-top: 6px; border-top: 1px solid #edf0f7; }

.subnav-item {
  display: flex;
  align-items: center;
  width: 100%;
  gap: 10px;
  padding: 11px 10px;
  color: #697590;
  text-align: left;
  cursor: pointer;
  border: 0;
  border-radius: 11px;
  background: transparent;
  transition: color .18s ease, background-color .18s ease, transform .18s ease;
}

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

.admin-content :deep(.el-table) {
  overflow: hidden;
  border: 1px solid #e5e8f4;
  border-radius: 16px;
  background: rgba(255, 255, 255, .93);
  box-shadow: 0 12px 30px rgba(42, 55, 110, .07);
}
.admin-content :deep(.el-table th.el-table__cell) { color: #69758f; font-size: 12px; font-weight: 800; background: #f5f6fc; }
.admin-content :deep(.el-table tr) { background: transparent; }
.admin-content :deep(.el-table .el-table__row:hover > td.el-table__cell) { background: #f7f8ff; }
.admin-content :deep(.el-table td.el-table__cell), .admin-content :deep(.el-table th.el-table__cell) { padding: 13px 0; border-bottom-color: #edf0f7; }

@keyframes admin-section-in {
  from { opacity: 0; transform: translateY(4px); }
  to { opacity: 1; transform: translateY(0); }
}

@media (max-width: 900px) {
  .admin-layout { grid-template-columns: 1fr; gap: 15px; }
  .admin-subnav { position: static; padding: 10px; }
  .subnav-heading { padding: 3px 7px 9px; }
  .subnav-list { display: grid; grid-template-columns: repeat(5, minmax(120px, 1fr)); overflow-x: auto; }
  .subnav-item { min-width: 120px; }
  .subnav-group-label { display: none; }
}

@media (max-width: 600px) {
  .admin-layout { gap: 12px; }
  .subnav-list { grid-template-columns: repeat(5, minmax(108px, 1fr)); }
  .subnav-item { min-width: 108px; padding: 9px 8px; }
  .subnav-item small { display: none; }
  .section-intro { align-items: stretch; flex-direction: column; }
  .section-intro :deep(.el-button) { align-self: flex-start; }
}

</style>
