<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Delete, Plus } from '@element-plus/icons-vue'
import type {
  ProviderAccount,
  ProviderAccountRequest,
  ProviderModelRequest,
  GrsaiModelCatalogItem,
  TestConnectionRequest,
} from '@/types'
import { modelCatalogApi, providerAccountApi, providerApi } from '@/api/providers'
import {
  PROVIDER_PRESETS,
  findPresetById,
  inferPresetIdFromProviderKey,
  type ProviderPresetId,
} from '@/constants/providerPresets'

const props = defineProps<{
  provider?: ProviderAccount | null
}>()

const emit = defineEmits<{
  submit: [data: ProviderAccountRequest]
}>()

const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const apiKeyMask = ref('')
const apiKeyInput = ref('')
const apiKeyEdited = ref(false)
const testing = ref(false)
const fetching = ref(false)
const modelOptions = ref<string[]>([])
const grsaiCatalog = ref<GrsaiModelCatalogItem[]>([])
const catalogRefreshing = ref(false)
const testResult = ref<{ success: boolean; message: string } | null>(null)

const form = ref<ProviderAccountRequest>(emptyForm())
const editing = computed(() => editingId.value != null)
/** Selected built-in type when adding; inferred when editing. */
const providerType = ref<ProviderPresetId>('custom')
const isGrsai = computed(() => form.value.providerKey.trim().toLowerCase() === 'grsai')
const selectedPreset = computed(() => findPresetById(providerType.value))
const providerKeyLocked = computed(() => !editing.value && providerType.value !== 'custom')
const GRS_AI_BASE_URL = 'https://grsai.dakka.com.cn'

function emptyForm(): ProviderAccountRequest {
  return {
    providerKey: '',
    name: '',
    baseUrl: '',
    models: [newModel('CHAT')],
  }
}

function newModel(type: 'CHAT' | 'IMAGE'): ProviderModelRequest {
  return {
    type,
    modelName: '',
    systemPrompt: '',
    reasoningEffort: type === 'CHAT' ? 'default' : undefined,
    temperature: type === 'CHAT' ? 0.7 : null,
    timeoutSeconds: 300,
    maxRetries: type === 'IMAGE' ? 2 : null,
    retryBackoffSeconds: type === 'IMAGE' ? 20 : null,
    adapterType: type === 'IMAGE' ? 'AUTO' : undefined,
    configJson: '',
  }
}

function open(provider?: ProviderAccount | null) {
  // An explicit null means "new provider"; do not fall back to the last prop.
  const source = provider === undefined ? (props.provider || null) : provider
  editingId.value = source?.id ?? null
  apiKeyMask.value = source?.apiKey || ''
  apiKeyInput.value = ''
  apiKeyEdited.value = false
  modelOptions.value = []
  grsaiCatalog.value = []
  testResult.value = null
  if (source) {
    providerType.value = inferPresetIdFromProviderKey(source.providerKey)
    form.value = {
      providerKey: source.providerKey,
      name: source.name === source.providerKey ? '' : source.name,
      baseUrl: source.baseUrl,
      models: source.models.map((model) => ({
        id: model.id,
        type: model.type,
        modelName: model.modelName,
        systemPrompt: model.systemPrompt || '',
        reasoningEffort: model.reasoningEffort || 'default',
        temperature: model.temperature ?? (model.type === 'CHAT' ? 0.7 : null),
        timeoutSeconds: model.timeoutSeconds ?? 300,
        maxRetries: model.maxRetries ?? (model.type === 'IMAGE' ? 2 : null),
        retryBackoffSeconds: model.retryBackoffSeconds ?? (model.type === 'IMAGE' ? 20 : null),
        adapterType: model.adapterType || (model.type === 'IMAGE' ? 'AUTO' : undefined),
        configJson: model.configJson || '',
        supportsTextToImage: model.supportsTextToImage,
        supportsImageToImage: model.supportsImageToImage,
        priceCreditsMin: model.priceCreditsMin,
        priceCreditsMax: model.priceCreditsMax,
        priceCnyMin: model.priceCnyMin,
        priceCnyMax: model.priceCnyMax,
        priceDescription: model.priceDescription,
        billingMode: model.billingMode,
        pricePerUnit: model.pricePerUnit,
        inputPricePerMillion: model.inputPricePerMillion,
        outputPricePerMillion: model.outputPricePerMillion,
        cacheReadPricePerMillion: model.cacheReadPricePerMillion,
      })),
    }
  } else {
    // Default type is custom; fields stay free-form until a preset is chosen.
    providerType.value = 'custom'
    form.value = emptyForm()
  }
  dialogVisible.value = true
  if (source?.providerKey.toLowerCase() === 'grsai' || providerType.value === 'grsai') {
    void loadGrsaiCatalog(false)
  }
}

function buildSeedModels(typeId: ProviderPresetId): ProviderModelRequest[] {
  const preset = findPresetById(typeId)
  if (!preset.seedModels?.length) {
    return [newModel(typeId === 'grsai' ? 'IMAGE' : 'CHAT')]
  }
  return preset.seedModels.map((seed) => {
    const model = newModel(seed.type)
    if (seed.modelName != null) model.modelName = seed.modelName
    if (seed.adapterType) model.adapterType = seed.adapterType
    if (typeId === 'grsai' && model.type === 'IMAGE') model.adapterType = 'GRS_AI'
    return model
  })
}

/** Apply a built-in supplier type when adding (or when switching type in add mode). */
function applyPreset(typeId: ProviderPresetId) {
  const preset = findPresetById(typeId)
  providerType.value = preset.id
  form.value.providerKey = preset.providerKey
  form.value.name = preset.name
  form.value.baseUrl = preset.baseUrl
  form.value.models = buildSeedModels(preset.id)
  modelOptions.value = (preset.seedModels || [])
    .map((item) => item.modelName || '')
    .filter(Boolean)
  grsaiCatalog.value = []
  testResult.value = null
  if (preset.id === 'grsai') {
    void loadGrsaiCatalog(false)
  }
}

function handleProviderTypeChange(typeId: ProviderPresetId) {
  if (editing.value) {
    // Edit mode: type is informative only; do not overwrite existing fields.
    providerType.value = typeId
    return
  }
  applyPreset(typeId)
}

function close() {
  dialogVisible.value = false
}

function addModel(type: 'CHAT' | 'IMAGE') {
  const model = newModel(type)
  if (type === 'IMAGE' && isGrsai.value) model.adapterType = 'GRS_AI'
  form.value.models.push(model)
}

function removeModel(index: number) {
  if (form.value.models.length <= 1) {
    ElMessage.warning('每个供应商至少需要保留一个模型')
    return
  }
  form.value.models.splice(index, 1)
}

function handleTypeChange(model: ProviderModelRequest) {
  const defaults = newModel(model.type)
  model.systemPrompt = defaults.systemPrompt
  model.reasoningEffort = defaults.reasoningEffort
  model.temperature = defaults.temperature
  model.timeoutSeconds = defaults.timeoutSeconds
  model.maxRetries = defaults.maxRetries
  model.retryBackoffSeconds = defaults.retryBackoffSeconds
  model.adapterType = model.type === 'IMAGE' && isGrsai.value ? 'GRS_AI' : defaults.adapterType
  if (model.type !== 'IMAGE') clearImageMetadata(model)
}

function clearImageMetadata(model: ProviderModelRequest) {
  model.supportsTextToImage = null
  model.supportsImageToImage = null
  model.priceCreditsMin = null
  model.priceCreditsMax = null
  model.priceCnyMin = null
  model.priceCnyMax = null
  model.priceDescription = null
}

function applyGrsaiCatalogModel(model: ProviderModelRequest) {
  if (!isGrsai.value || model.type !== 'IMAGE') return
  const item = grsaiCatalog.value.find((candidate) => candidate.modelName === model.modelName)
  model.adapterType = 'GRS_AI'
  if (!item) return
  model.supportsTextToImage = item.supportsTextToImage
  model.supportsImageToImage = item.supportsImageToImage
  model.priceCreditsMin = item.priceCreditsMin
  model.priceCreditsMax = item.priceCreditsMax
  model.priceCnyMin = item.priceCnyMin
  model.priceCnyMax = item.priceCnyMax
  model.priceDescription = item.priceDescription
}

/**
 * Only show models that have not already been added to this supplier for the
 * current model type. The current model is kept in the list so it remains
 * visible when an existing provider is edited.
 */
function selectedModelNames(except: ProviderModelRequest, type: 'CHAT' | 'IMAGE'): Set<string> {
  return new Set(
    form.value.models
      .filter((item) => item !== except && item.type === type)
      .map((item) => item.modelName.trim().toLowerCase())
      .filter(Boolean),
  )
}

/**
 * Only show models that have not already been added to this supplier for the
 * current model type. The current model is kept in the list so it remains
 * visible when an existing provider is edited.
 */
function availableModelOptions(model: ProviderModelRequest): string[] {
  const selectedByOtherRows = selectedModelNames(model, model.type)
  const source = isGrsai.value && model.type === 'IMAGE'
    ? grsaiCatalog.value.map((item) => item.modelName)
    : modelOptions.value
  const names = new Set<string>()
  return source.filter((name) => {
    const normalized = name.trim().toLowerCase()
    if (!normalized || selectedByOtherRows.has(normalized) || names.has(normalized)) return false
    names.add(normalized)
    return true
  })
}

/** Grsai catalog rows still available for this model slot (deduped by other rows). */
function availableGrsaiCatalog(model: ProviderModelRequest): GrsaiModelCatalogItem[] {
  const selectedByOtherRows = selectedModelNames(model, 'IMAGE')
  return grsaiCatalog.value.filter((item) => {
    const normalized = item.modelName.trim().toLowerCase()
    return normalized && !selectedByOtherRows.has(normalized)
  })
}

async function loadGrsaiCatalog(refresh: boolean) {
  catalogRefreshing.value = true
  try {
    grsaiCatalog.value = refresh
      ? await modelCatalogApi.refreshGrsai()
      : await modelCatalogApi.grsai()
    modelOptions.value = grsaiCatalog.value.map((item) => item.modelName)
    if (refresh) ElMessage.success(`已刷新 ${grsaiCatalog.value.length} 个 Grsai 图片模型`)
  } catch (error: any) {
    ElMessage.error(error.message || '获取 Grsai 模型目录失败')
  } finally {
    catalogRefreshing.value = false
  }
}

watch(() => form.value.providerKey, (value) => {
  if (value.trim().toLowerCase() !== 'grsai') return
  if (!form.value.name?.trim()) form.value.name = 'Grsai'
  if (!form.value.baseUrl.trim()) form.value.baseUrl = GRS_AI_BASE_URL
  const firstModel = form.value.models[0]
  if (form.value.models.length === 1 && firstModel && !firstModel.modelName.trim()) {
    const grsaiModel = newModel('IMAGE')
    grsaiModel.adapterType = 'GRS_AI'
    form.value.models[0] = grsaiModel
  }
  void loadGrsaiCatalog(false)
})

function normalizeApiRequestUrl(value: string): string {
  const trimmed = value.trim()
  if (!trimmed) return ''
  const withProtocol = /^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(trimmed)
    ? trimmed
    : `https://${trimmed}`
  try {
    const url = new URL(withProtocol)
    if ((!url.pathname || url.pathname === '/') && !isGrsai.value) url.pathname = '/v1'
    return url.toString().replace(/\/+$/, '')
  } catch {
    return withProtocol.replace(/\/+$/, '')
  }
}

function connectionOverrides(): TestConnectionRequest {
  const data: TestConnectionRequest = {
    providerKey: form.value.providerKey.trim(),
    baseUrl: normalizeApiRequestUrl(form.value.baseUrl),
  }
  if (apiKeyEdited.value && apiKeyInput.value.trim()) data.apiKey = apiKeyInput.value.trim()
  return data
}

async function testConnection() {
  if (!form.value.baseUrl.trim()) {
    ElMessage.warning('请先填写 API 请求地址')
    return
  }
  form.value.baseUrl = normalizeApiRequestUrl(form.value.baseUrl)
  testing.value = true
  testResult.value = null
  try {
    const result = editingId.value != null
      ? await providerAccountApi.testConnection(editingId.value, connectionOverrides())
      : await providerApi.testConnection(connectionOverrides())
    testResult.value = result
    result.success ? ElMessage.success('连接成功') : ElMessage.error(`连接失败: ${result.message}`)
  } catch (error: any) {
    testResult.value = { success: false, message: error.message || '连接失败' }
    ElMessage.error(testResult.value.message)
  } finally {
    testing.value = false
  }
}

async function fetchModels() {
  if (isGrsai.value) {
    await loadGrsaiCatalog(false)
    ElMessage.success(`获取到 ${grsaiCatalog.value.length} 个支持文生图/图生图的 Grsai 模型`)
    return
  }
  if (!form.value.baseUrl.trim()) {
    ElMessage.warning('请先填写 API 请求地址')
    return
  }
  form.value.baseUrl = normalizeApiRequestUrl(form.value.baseUrl)
  fetching.value = true
  try {
    const result = editingId.value != null
      ? await providerAccountApi.fetchModels(editingId.value, connectionOverrides())
      : await providerApi.fetchModels(connectionOverrides())
    modelOptions.value = result.models || []
    ElMessage.success(`获取到 ${modelOptions.value.length} 个模型`)
  } catch (error: any) {
    ElMessage.error(error.message || '获取模型列表失败')
  } finally {
    fetching.value = false
  }
}

function validateConfigJson(model: ProviderModelRequest): boolean {
  if (!model.configJson?.trim()) return true
  try {
    JSON.parse(model.configJson)
    return true
  } catch {
    ElMessage.error(`模型「${model.modelName || '未命名'}」的额外参数不是合法 JSON`)
    return false
  }
}

function formatConfigJson(model: ProviderModelRequest) {
  if (!model.configJson?.trim()) return
  try {
    model.configJson = JSON.stringify(JSON.parse(model.configJson), null, 2)
  } catch {
    ElMessage.error('JSON 格式不正确')
  }
}

function clearBilling(model: ProviderModelRequest) {
  model.billingMode = undefined
  model.pricePerUnit = null
  model.inputPricePerMillion = null
  model.outputPricePerMillion = null
  model.cacheReadPricePerMillion = null
}

function validateBilling(model: ProviderModelRequest): boolean {
  if (!model.billingMode) {
    clearBilling(model)
    return true
  }
  if (model.billingMode === 'PER_CALL') {
    if (model.pricePerUnit == null || model.pricePerUnit <= 0) {
      ElMessage.warning(`模型「${model.modelName || '未命名'}」的按次单价必须大于 0`)
      return false
    }
    return true
  }
  if ([model.inputPricePerMillion, model.outputPricePerMillion, model.cacheReadPricePerMillion]
    .some((value) => value == null || value <= 0)) {
    ElMessage.warning(`模型「${model.modelName || '未命名'}」的输入、输出和缓存读取单价都必须大于 0`)
    return false
  }
  return true
}

function handleBillingModeChange(model: ProviderModelRequest) {
  if (!model.billingMode) clearBilling(model)
}

function handleSubmit() {
  form.value.providerKey = form.value.providerKey.trim()
  form.value.name = form.value.name?.trim()
  form.value.baseUrl = normalizeApiRequestUrl(form.value.baseUrl)

  if (!form.value.providerKey) return ElMessage.warning('请输入供应商标识')
  if (!form.value.baseUrl) return ElMessage.warning('请输入 API 请求地址')
  if (!form.value.models.length) return ElMessage.warning('请至少添加一个模型')
  if (form.value.models.some((model) => !model.modelName.trim())) {
    return ElMessage.warning('请填写所有模型名称')
  }
  const modelKeys = new Set<string>()
  for (const model of form.value.models) {
    const key = `${model.type}:${model.modelName.trim().toLowerCase()}`
    if (modelKeys.has(key)) {
      return ElMessage.warning(`同一供应商不能重复添加${model.type === 'CHAT' ? '对话' : '图像'}模型「${model.modelName.trim()}」`)
    }
    modelKeys.add(key)
  }
  if (!form.value.models.every(validateConfigJson)) return
  if (!form.value.models.every(validateBilling)) return

  const payload: ProviderAccountRequest = {
    providerKey: form.value.providerKey.trim(),
    name: form.value.name,
    baseUrl: form.value.baseUrl,
    models: form.value.models.map((model) => ({ ...model, modelName: model.modelName.trim() })),
  }
  if (apiKeyEdited.value) payload.apiKey = apiKeyInput.value.trim()
  emit('submit', payload)
  close()
}

defineExpose({ open })
</script>

<template>
  <el-dialog
    v-model="dialogVisible"
    :title="editing ? '编辑供应商' : '添加供应商'"
    width="900px"
    top="5vh"
    destroy-on-close
  >
    <el-form label-width="118px" class="provider-form">
      <el-divider content-position="left">供应商连接配置</el-divider>
      <el-form-item label="供应商类型" required>
        <div class="stacked-field">
          <el-select
            v-model="providerType"
            :disabled="editing"
            filterable
            popper-class="provider-type-select-dropdown"
            placeholder="选择内置类型或自定义"
            @change="handleProviderTypeChange"
          >
            <el-option
              v-for="preset in PROVIDER_PRESETS"
              :key="preset.id"
              :label="preset.label"
              :value="preset.id"
            >
              <div class="preset-option">
                <span>{{ preset.label }}</span>
                <small>{{ preset.description }}</small>
              </div>
            </el-option>
          </el-select>
          <small v-if="!editing" class="field-hint">{{ selectedPreset.description }}；默认为「自定义」</small>
          <small v-else class="field-hint">编辑时类型由供应商标识推断，不可切换覆盖现有配置</small>
        </div>
      </el-form-item>
      <el-form-item label="供应商标识" required>
        <el-input
          v-model="form.providerKey"
          :readonly="providerKeyLocked"
          :placeholder="providerType === 'custom' ? '例如 openai、company-gateway' : '由所选类型预填'"
        />
      </el-form-item>
      <el-form-item label="显示名称">
        <el-input v-model="form.name" placeholder="留空时使用供应商标识" />
      </el-form-item>
      <el-alert
        v-if="isGrsai"
        type="info"
        :closable="false"
        show-icon
        title="Grsai 为内置供应商类型：已预填基础地址并提供图片模型目录；填写 API Key 后即可使用，基础地址仍可按需修改。"
      />
      <el-form-item label="API 请求地址" required>
        <div class="inline-field">
          <el-input v-model="form.baseUrl" placeholder="https://api.example.com/v1" @blur="form.baseUrl = normalizeApiRequestUrl(form.baseUrl)" />
          <el-button :loading="testing" @click="testConnection">测试连接</el-button>
          <el-button :loading="fetching || catalogRefreshing" @click="fetchModels">获取模型</el-button>
          <el-button v-if="isGrsai" :loading="catalogRefreshing" @click="loadGrsaiCatalog(true)">刷新官网目录</el-button>
        </div>
      </el-form-item>
      <el-form-item label="API Key">
        <div class="stacked-field">
          <el-input
            v-model="apiKeyInput"
            type="password"
            show-password
            :placeholder="editing && apiKeyMask ? `已保存 ${apiKeyMask}，留空不修改` : '可选'"
            @input="apiKeyEdited = true"
          />
          <el-alert
            v-if="testResult"
            :type="testResult.success ? 'success' : 'error'"
            :title="testResult.message"
            :closable="false"
            show-icon
          />
        </div>
      </el-form-item>

      <el-divider content-position="left">模型配置</el-divider>
      <div class="model-actions">
        <span>同一个供应商可同时配置多个对话模型和图像模型。</span>
        <div>
          <el-button v-if="!isGrsai" size="small" :icon="Plus" @click="addModel('CHAT')">添加对话模型</el-button>
          <el-button size="small" :icon="Plus" @click="addModel('IMAGE')">添加图像模型</el-button>
        </div>
      </div>

      <div v-for="(model, index) in form.models" :key="model.id ?? `new-${index}`" class="model-card">
        <div class="model-card-header">
          <div class="model-title">
            <el-tag :type="model.type === 'CHAT' ? 'primary' : 'success'">
              {{ model.type === 'CHAT' ? '对话模型' : '图像模型' }}
            </el-tag>
            <strong>{{ model.modelName || `模型 ${index + 1}` }}</strong>
          </div>
          <el-button text type="danger" :icon="Delete" @click="removeModel(index)">删除</el-button>
        </div>

        <div class="model-grid">
          <el-form-item label="模型类型" required>
            <el-select v-model="model.type" @change="handleTypeChange(model)">
              <el-option v-if="!isGrsai" label="对话模型" value="CHAT" />
              <el-option label="图像模型" value="IMAGE" />
            </el-select>
          </el-form-item>
          <el-form-item label="模型名称" required>
            <el-select
              v-model="model.modelName"
              filterable
              allow-create
              default-first-option
              placeholder="输入或选择模型名称（支持手工输入）"
              @change="applyGrsaiCatalogModel(model)"
            >
              <template v-if="isGrsai && model.type === 'IMAGE'">
                <el-option
                  v-for="item in availableGrsaiCatalog(model)"
                  :key="item.modelName"
                  :label="`${item.modelName} · 文生图/图生图 · ${item.priceDescription}`"
                  :value="item.modelName"
                />
              </template>
              <template v-else>
                <el-option
                  v-for="name in availableModelOptions(model)"
                  :key="name"
                  :label="name"
                  :value="name"
                />
              </template>
            </el-select>
          </el-form-item>
          <el-form-item label="请求超时（秒）">
            <el-input-number v-model="model.timeoutSeconds" :min="30" :max="3600" controls-position="right" />
          </el-form-item>
          <el-form-item v-if="model.type === 'CHAT'" label="推理强度">
            <el-select v-model="model.reasoningEffort">
              <el-option label="默认" value="default" />
              <el-option label="低" value="low" />
              <el-option label="中" value="medium" />
              <el-option label="高" value="high" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="model.type === 'CHAT'" label="温度">
            <el-input-number v-model="model.temperature" :min="0" :max="2" :step="0.1" controls-position="right" />
          </el-form-item>
          <el-form-item v-if="model.type === 'IMAGE'" label="图片适配器">
            <el-select v-model="model.adapterType">
              <el-option label="自动识别" value="AUTO" />
              <el-option label="OpenAI Images" value="OPENAI_IMAGE" />
              <el-option label="Gemini Image" value="GEMINI_IMAGE" />
              <el-option label="Grsai 异步生图" value="GRS_AI" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="model.type === 'IMAGE' && model.priceDescription" label="模型能力/价格">
            <div class="catalog-meta">
              <el-tag v-if="model.supportsTextToImage" size="small" effect="plain">文生图</el-tag>
              <el-tag v-if="model.supportsImageToImage" size="small" type="success" effect="plain">图生图</el-tag>
              <span>{{ model.priceDescription }}</span>
            </div>
          </el-form-item>
          <el-form-item v-if="model.type === 'IMAGE'" label="失败重试" class="model-grid-span">
            <div class="retry-field">
              <el-input-number v-model="model.maxRetries" :min="0" :max="10" controls-position="right" />
              <span class="retry-text">次</span>
              <span class="retry-text">退避</span>
              <el-input-number v-model="model.retryBackoffSeconds" :min="1" :max="120" controls-position="right" />
              <span class="retry-text">秒</span>
            </div>
          </el-form-item>
          <el-form-item label="计费模式">
            <el-select v-model="model.billingMode" clearable placeholder="不收费（默认）" @change="handleBillingModeChange(model)">
              <el-option label="按次收费" value="PER_CALL" />
              <el-option label="按 Token 收费" value="PER_TOKEN" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="model.billingMode === 'PER_CALL'" label="单价" required>
            <el-input-number v-model="model.pricePerUnit" :min="0.000001" :precision="6" :step="0.01" controls-position="right" />
            <small class="unit-hint">元/次</small>
          </el-form-item>
          <template v-if="model.billingMode === 'PER_TOKEN'">
            <el-form-item label="输入单价" required>
              <el-input-number v-model="model.inputPricePerMillion" :min="0.000001" :precision="6" :step="0.01" controls-position="right" />
              <small class="unit-hint">元/百万 Token</small>
            </el-form-item>
            <el-form-item label="输出单价" required>
              <el-input-number v-model="model.outputPricePerMillion" :min="0.000001" :precision="6" :step="0.01" controls-position="right" />
              <small class="unit-hint">元/百万 Token</small>
            </el-form-item>
            <el-form-item label="缓存读取单价" required>
              <el-input-number v-model="model.cacheReadPricePerMillion" :min="0.000001" :precision="6" :step="0.01" controls-position="right" />
              <small class="unit-hint">元/百万 Token</small>
            </el-form-item>
          </template>
        </div>

        <el-form-item v-if="model.type === 'CHAT'" label="System Prompt">
          <el-input v-model="model.systemPrompt" type="textarea" :autosize="{ minRows: 2, maxRows: 5 }" />
        </el-form-item>
        <el-form-item label="模型额外参数">
          <div class="stacked-field">
            <div><el-button size="small" @click="formatConfigJson(model)">格式化 JSON</el-button></div>
            <el-input
              v-model="model.configJson"
              type="textarea"
              :autosize="{ minRows: 3, maxRows: 8 }"
              placeholder="留空时使用接口默认参数；填写时必须是 JSON 对象"
            />
          </div>
        </el-form-item>
      </div>
    </el-form>

    <template #footer>
      <el-button @click="close">取消</el-button>
      <el-button type="primary" @click="handleSubmit">保存供应商</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.provider-form {
  max-height: 72vh;
  padding-right: 8px;
  overflow-y: auto;
}
.inline-field {
  display: flex;
  width: 100%;
  gap: 8px;
}
.inline-field .el-input { flex: 1; }
.inline-field :deep(.el-button) { border-radius: 8px; }
.stacked-field {
  display: flex;
  flex-direction: column;
  width: 100%;
  gap: 8px;
}
.model-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
  color: #6d7891;
  font-size: 13px;
}
.model-card {
  padding: 16px 16px 6px;
  margin-bottom: 16px;
  border: 1px solid #e5e9f5;
  border-radius: 12px;
  background: linear-gradient(145deg, #fbfcff, #f7f8fd);
}
.model-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.model-title {
  display: flex;
  align-items: center;
  gap: 10px;
  color: #44516d;
}
.model-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  column-gap: 18px;
}
.model-grid-span {
  grid-column: 1 / -1;
}
.model-grid :deep(.el-form-item:not(.model-grid-span) .el-select),
.model-grid :deep(.el-form-item:not(.model-grid-span) .el-input-number) {
  width: 100%;
}

/* Real rounded borders inside dialog (kill EP inset square shadows) */
.provider-form :deep(.el-input__wrapper),
.provider-form :deep(.el-textarea__inner),
.provider-form :deep(.el-select__wrapper) {
  border: 1px solid #e0e5f5 !important;
  border-radius: 10px !important;
  box-shadow: none !important;
  transition: border-color .18s ease, box-shadow .18s ease;
}
.provider-form :deep(.el-input__wrapper:hover),
.provider-form :deep(.el-textarea__inner:hover),
.provider-form :deep(.el-select__wrapper.is-hovering:not(.is-focused)) {
  border-color: #c5cbe3 !important;
  box-shadow: none !important;
}
.provider-form :deep(.el-input__wrapper.is-focus),
.provider-form :deep(.el-textarea__inner:focus),
.provider-form :deep(.el-select__wrapper.is-focused) {
  border-color: rgba(82, 103, 246, .65) !important;
  box-shadow: 0 0 0 3px rgba(82, 103, 246, .14) !important;
}
.provider-form :deep(.el-input__inner),
.provider-form :deep(.el-select__input) {
  border: 0;
  outline: none;
  box-shadow: none;
  background: transparent;
}
.provider-form :deep(.el-input-number__increase),
.provider-form :deep(.el-input-number__decrease) {
  border: 0;
  background: #f5f7fc;
}
.provider-form :deep(.el-input-number.is-controls-right .el-input-number__increase),
.provider-form :deep(.el-input-number.is-controls-right .el-input-number__decrease) {
  border-radius: 0 9px 9px 0;
}
.provider-form :deep(.el-input-number.is-controls-right .el-input-number__decrease) {
  border-radius: 0 0 9px 0;
}
.provider-form :deep(.el-input-number.is-controls-right .el-input-number__increase) {
  border-radius: 0 9px 0 0;
}
.provider-form :deep(.el-input-number .el-input__wrapper) {
  padding-left: 12px;
  padding-right: 42px;
}

.retry-field {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 8px;
  min-width: 0;
  color: #8d97ac;
  font-size: 12px;
  white-space: nowrap;
}
.retry-text {
  flex: 0 0 auto;
  white-space: nowrap;
}
.retry-field :deep(.el-input-number) {
  flex: 0 0 auto;
  width: 128px;
}
.catalog-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  color: #65718a;
  font-size: 12px;
}

.preset-option {
  display: flex;
  min-height: 42px;
  flex-direction: column;
  gap: 3px;
  justify-content: center;
  line-height: 1.35;
  padding: 5px 0;
  white-space: normal;
}
.preset-option small {
  color: #8d97ac;
  font-size: 12px;
}
.unit-hint {
  margin-left: 8px;
  color: #929bb0;
  font-size: 12px;
  white-space: nowrap;
}
.field-hint {
  color: #8d97ac;
  font-size: 12px;
  line-height: 1.4;
}

@media (max-width: 800px) {
  .model-grid { grid-template-columns: 1fr; }
  .model-actions {
    align-items: flex-start;
    gap: 10px;
    flex-direction: column;
  }
  .inline-field { flex-wrap: wrap; }
  .inline-field .el-input { flex-basis: 100%; }
  .retry-field { flex-wrap: wrap; white-space: normal; }
}
</style>

<!-- Dropdown is teleported to body; scoped :deep styles under .provider-form cannot reach it. -->
<style>
.provider-type-select-dropdown.el-select-dropdown .el-select-dropdown__item,
.provider-type-select-dropdown .el-select-dropdown__item {
  height: auto !important;
  min-height: 52px;
  line-height: 1.35 !important;
  padding-top: 8px;
  padding-bottom: 8px;
  white-space: normal;
}
.provider-type-select-dropdown .preset-option {
  display: flex;
  min-height: 42px;
  flex-direction: column;
  gap: 3px;
  justify-content: center;
  line-height: 1.35;
  padding: 0;
  white-space: normal;
}
.provider-type-select-dropdown .preset-option small {
  color: #8d97ac;
  font-size: 12px;
}
</style>
