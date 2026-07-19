<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { llmDebugApi } from '@/api/llmDebug'
import type {
  LlmDebugExchange,
  LlmDebugExchangeSummary,
  LlmDebugStatus,
} from '@/types'

const status = ref<LlmDebugStatus>({ enabled: false, recordCount: 0, maxRecords: 20 })
const exchanges = ref<LlmDebugExchangeSummary[]>([])
const loading = ref(false)
const toggleLoading = ref(false)
const detailLoading = ref(false)
const detailVisible = ref(false)
const selectedExchange = ref<LlmDebugExchange | null>(null)
let refreshTimer: ReturnType<typeof setInterval> | null = null

const statusText = computed(() => status.value.enabled ? '正在记录' : '已关闭')

onMounted(async () => {
  await refreshAll()
  refreshTimer = setInterval(() => {
    if (status.value.enabled && !loading.value) void refreshAll(false)
  }, 3000)
})

onBeforeUnmount(() => {
  if (refreshTimer) clearInterval(refreshTimer)
})

async function refreshAll(showError = true) {
  loading.value = true
  try {
    const [nextStatus, nextExchanges] = await Promise.all([
      llmDebugApi.getStatus(),
      llmDebugApi.listExchanges(200),
    ])
    status.value = nextStatus
    exchanges.value = nextExchanges
  } catch (error: any) {
    if (showError) ElMessage.error(error.message || '获取 LLM 调试数据失败')
  } finally {
    loading.value = false
  }
}

async function handleToggle(value: string | number | boolean) {
  const enabled = Boolean(value)
  toggleLoading.value = true
  try {
    status.value = await llmDebugApi.setEnabled(enabled)
    ElMessage.success(enabled ? 'LLM 通信调试已开启，无需重启服务' : 'LLM 通信调试已关闭')
    await refreshAll(false)
  } catch (error: any) {
    status.value.enabled = !enabled
    ElMessage.error(error.message || '更新调试开关失败')
  } finally {
    toggleLoading.value = false
  }
}

async function openExchange(row: LlmDebugExchangeSummary) {
  detailVisible.value = true
  detailLoading.value = true
  selectedExchange.value = null
  try {
    selectedExchange.value = await llmDebugApi.getExchange(row.id)
  } catch (error: any) {
    detailVisible.value = false
    ElMessage.error(error.message || '获取完整数据包失败')
  } finally {
    detailLoading.value = false
  }
}

async function clearExchanges() {
  try {
    await ElMessageBox.confirm(
      '确定清空当前实例内存中缓存的所有 LLM 请求和响应数据包吗？',
      '清空调试数据',
      { type: 'warning' },
    )
    status.value = await llmDebugApi.clearExchanges()
    exchanges.value = []
    selectedExchange.value = null
    detailVisible.value = false
    ElMessage.success('调试数据已清空')
  } catch (error: any) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error.message || '清空调试数据失败')
  }
}

function formatTime(value: string) {
  if (!value) return '-'
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value))
}

function formatBytes(value: number | null) {
  if (value == null) return '-'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(2)} MB`
}

function responseTagType(row: LlmDebugExchangeSummary) {
  if (row.errorType) return 'danger'
  if (!row.completed) return 'warning'
  if (row.responseStatus == null) return 'info'
  if (row.responseStatus >= 200 && row.responseStatus < 300) return 'success'
  if (row.responseStatus >= 400) return 'danger'
  return 'warning'
}

function responseLabel(row: LlmDebugExchangeSummary) {
  if (row.errorType) return '传输错误'
  if (!row.completed) return '请求中'
  return row.responseStatus == null ? '无状态码' : String(row.responseStatus)
}

function prettyHeaders(headers: Record<string, string[]> | null | undefined) {
  return JSON.stringify(headers || {}, null, 2)
}

async function copyText(text: string | null | undefined, label: string) {
  const value = text || ''
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value)
    } else {
      const textarea = document.createElement('textarea')
      textarea.value = value
      textarea.style.position = 'fixed'
      textarea.style.opacity = '0'
      document.body.appendChild(textarea)
      textarea.select()
      document.execCommand('copy')
      textarea.remove()
    }
    ElMessage.success(`${label}已复制`)
  } catch {
    ElMessage.error(`${label}复制失败`)
  }
}

function downloadExchange() {
  if (!selectedExchange.value) return
  const content = JSON.stringify(selectedExchange.value, null, 2)
  const blob = new Blob([content], { type: 'application/json;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `llm-exchange-${selectedExchange.value.id}.json`
  link.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <el-card class="debug-panel" shadow="never">
    <template #header>
      <div class="debug-header">
        <div>
          <div class="debug-title-row">
            <strong>LLM 通信调试</strong>
            <el-tag :type="status.enabled ? 'warning' : 'info'" size="small">
              {{ statusText }}
            </el-tag>
          </div>
          <p>运行时查看发送给模型供应商的完整请求和返回数据，无需登录服务器或重启服务。</p>
        </div>
        <div class="debug-actions">
          <span class="switch-label">记录完整数据包</span>
          <el-switch
            v-model="status.enabled"
            :loading="toggleLoading"
            inline-prompt
            active-text="开"
            inactive-text="关"
            @change="handleToggle"
          />
          <el-button :loading="loading" @click="refreshAll()">刷新</el-button>
          <el-button type="danger" plain :disabled="exchanges.length === 0" @click="clearExchanges">
            清空
          </el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="status.enabled"
      type="warning"
      :closable="false"
      show-icon
      class="debug-warning"
      title="完整数据包可能包含提示词、会话内容及图片 Base64。排查完成后请及时关闭。认证头和常见密钥查询参数会自动脱敏。"
    />

    <div class="record-summary">
      当前缓存 {{ status.recordCount }} / {{ status.maxRecords }} 条，开启时每 3 秒自动刷新；超过上限会删除最早的数据。
    </div>

    <el-table
      v-loading="loading"
      :data="exchanges"
      stripe
      empty-text="暂无 LLM 通信数据，开启调试后发送一条消息或执行一次绘图即可看到记录"
      class="debug-table"
    >
      <el-table-column label="时间" width="145">
        <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
      </el-table-column>
      <el-table-column prop="method" label="方法" width="72" />
      <el-table-column prop="url" label="请求地址" min-width="280" show-overflow-tooltip />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="responseTagType(row)" size="small">{{ responseLabel(row) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="耗时" width="90">
        <template #default="{ row }">{{ row.elapsedMs == null ? '-' : `${row.elapsedMs} ms` }}</template>
      </el-table-column>
      <el-table-column label="请求体" width="95">
        <template #default="{ row }">{{ formatBytes(row.requestBodyLength) }}</template>
      </el-table-column>
      <el-table-column label="响应体" width="95">
        <template #default="{ row }">{{ formatBytes(row.responseBodyLength) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openExchange(row)">查看完整包</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-drawer
    v-model="detailVisible"
    title="LLM 完整通信数据包"
    size="82%"
    destroy-on-close
  >
    <div v-loading="detailLoading" class="packet-detail">
      <template v-if="selectedExchange">
        <div class="packet-toolbar">
          <el-descriptions :column="3" border size="small" class="packet-meta">
            <el-descriptions-item label="请求 ID">{{ selectedExchange.id }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ formatTime(selectedExchange.startedAt) }}</el-descriptions-item>
            <el-descriptions-item label="耗时">
              {{ selectedExchange.elapsedMs == null ? '-' : `${selectedExchange.elapsedMs} ms` }}
            </el-descriptions-item>
            <el-descriptions-item label="方法">{{ selectedExchange.method }}</el-descriptions-item>
            <el-descriptions-item label="响应状态">{{ responseLabel(selectedExchange) }}</el-descriptions-item>
            <el-descriptions-item label="地址">{{ selectedExchange.url }}</el-descriptions-item>
          </el-descriptions>
          <el-button type="primary" plain @click="downloadExchange">下载 JSON</el-button>
        </div>

        <el-alert
          v-if="selectedExchange.errorType"
          type="error"
          :closable="false"
          show-icon
          class="packet-error"
          :title="selectedExchange.errorType"
          :description="selectedExchange.errorMessage || ''"
        />

        <el-tabs>
          <el-tab-pane label="请求数据包">
            <section class="packet-section">
              <div class="packet-section-title">
                <strong>请求头</strong>
                <el-button link type="primary" @click="copyText(prettyHeaders(selectedExchange.requestHeaders), '请求头')">复制</el-button>
              </div>
              <pre class="packet-content">{{ prettyHeaders(selectedExchange.requestHeaders) }}</pre>
            </section>
            <section class="packet-section">
              <div class="packet-section-title">
                <strong>请求体</strong>
                <span>{{ formatBytes(selectedExchange.requestBodyLength) }} · {{ selectedExchange.requestBodyEncoding }}</span>
                <el-button link type="primary" @click="copyText(selectedExchange.requestBody, '请求体')">复制</el-button>
              </div>
              <pre class="packet-content packet-body">{{ selectedExchange.requestBody }}</pre>
            </section>
          </el-tab-pane>

          <el-tab-pane label="响应数据包">
            <section class="packet-section">
              <div class="packet-section-title">
                <strong>响应头</strong>
                <el-button link type="primary" @click="copyText(prettyHeaders(selectedExchange.responseHeaders), '响应头')">复制</el-button>
              </div>
              <pre class="packet-content">{{ prettyHeaders(selectedExchange.responseHeaders) }}</pre>
            </section>
            <section class="packet-section">
              <div class="packet-section-title">
                <strong>响应体</strong>
                <span>{{ formatBytes(selectedExchange.responseBodyLength) }} · {{ selectedExchange.responseBodyEncoding || '-' }}</span>
                <el-button link type="primary" @click="copyText(selectedExchange.responseBody, '响应体')">复制</el-button>
              </div>
              <pre class="packet-content packet-body">{{ selectedExchange.responseBody ?? (selectedExchange.completed ? '<empty>' : '<等待响应>') }}</pre>
            </section>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.debug-panel { margin-bottom: 20px; overflow: hidden; border: 1px solid #e4e8f6; border-radius: 16px; background: rgba(255,255,255,.94); box-shadow: 0 9px 25px rgba(44,57,112,.055); }.debug-header,.debug-actions,.debug-title-row,.packet-toolbar,.packet-section-title { display:flex; align-items:center; }.debug-header { justify-content:space-between; gap:20px; padding: 2px 1px; }.debug-title-row { gap:8px; color:#3b4765; }.debug-header p { margin:6px 0 0; color:#8993aa; font-size:13px; }.debug-actions { gap:10px; flex-shrink:0; }.switch-label,.record-summary,.packet-section-title span { color:#8c96ac; font-size:12px; }.debug-warning { margin-bottom:12px; }.record-summary { margin: 12px 0 10px; }.debug-table { width:100%; overflow:hidden; border:1px solid #edf0f7; border-radius:10px; }.debug-table :deep(th.el-table__cell) { color:#68738d; background:#f6f7fc; }.packet-detail { min-height:240px; }.packet-toolbar { align-items:flex-start; gap:12px; margin-bottom:16px; }.packet-meta { flex:1; }.packet-error { margin-bottom:14px; }.packet-section { margin-bottom:18px; }.packet-section-title { gap:10px; margin-bottom:8px; color:#46516d; }.packet-section-title .el-button { margin-left:auto; }.packet-content { box-sizing:border-box; width:100%; max-height:260px; margin:0; padding:12px; overflow:auto; color:#33405d; font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace; font-size:12px; line-height:1.55; white-space:pre-wrap; overflow-wrap:anywhere; border:1px solid #e5e9f5; border-radius:10px; background:#f7f8fd; }.packet-body { max-height:52vh; } @media(max-width:900px){.debug-header,.packet-toolbar{align-items:stretch;flex-direction:column;}.debug-actions{flex-wrap:wrap;}}
</style>
