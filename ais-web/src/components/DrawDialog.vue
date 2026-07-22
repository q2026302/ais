<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Paperclip, Picture } from '@element-plus/icons-vue'
import type { Message, ModelProvider, UploadResponse } from '@/types'
import { sessionApi } from '@/api/sessions'
import { getAttachmentThumbnailUrl, getThumbnailUrl } from '@/utils/imageUrl'

const props = defineProps<{
  visible: boolean
  initialPrompt: string
  initialReferences: UploadResponse[]
  historyMessages: Message[]
  smartParseInitialPrompt?: boolean
  imageProviders: ModelProvider[]
  defaultImageProviderId: number | null
  loading: boolean
}>()

const emit = defineEmits<{
  'update:visible': [visible: boolean]
  generate: [payload: {
    prompt: string
    attachmentIds: number[]
    references: UploadResponse[]
    size: string
    quality: string
    format: string
    imageProviderId: number | null
  }]
}>()

const prompt = ref('')
const references = ref<UploadResponse[]>([])
const size = ref('1024x1024')
const quality = ref('auto')
const format = ref('png')
const selectedProviderId = ref<number | null>(null)
const uploadTaskCount = ref(0)
const uploading = computed(() => uploadTaskCount.value > 0)
const historyImportingId = ref<string | null>(null)
const historySelectedIds = ref<string[]>([])
const historyThumbFailedIds = ref<Set<string>>(new Set())
const isDraggingReference = ref(false)
let referenceDragDepth = 0

interface HistoryImageItem {
  id: string
  url: string
  thumbUrl: string
  label: string
  format: string
  sourceKey: string
}

function onHistoryThumbError(id: string) {
  historyThumbFailedIds.value = new Set(historyThumbFailedIds.value).add(id)
}
function historyDisplayUrl(item: HistoryImageItem) {
  if (historyThumbFailedIds.value.has(item.id)) return item.url
  return item.thumbUrl || item.url
}

function resolveDefaultImageProviderId() {
  if (
    props.defaultImageProviderId != null
    && props.imageProviders.some((provider) => provider.id === props.defaultImageProviderId)
  ) {
    return props.defaultImageProviderId
  }
  return props.imageProviders.find((provider) => provider.active)?.id ?? null
}

const selectedImageProvider = computed<ModelProvider | null>(() => selectedProviderId.value == null
  ? null
  : props.imageProviders.find((provider) => provider.id === selectedProviderId.value) || null)

const providerAdapter = computed(() => {
  const configured = selectedImageProvider.value?.adapterType?.toUpperCase()
  if (configured && configured !== 'AUTO') return configured
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  const providerId = selectedImageProvider.value?.providerId?.toLowerCase() || ''
  if (providerId === 'grsai') return 'GRS_AI'
  return model.includes('gemini') ? 'GEMINI_IMAGE' : 'OPENAI_IMAGE'
})
const isGeminiImage = computed(() => providerAdapter.value === 'GEMINI_IMAGE')
const isGrsaiImage = computed(() => providerAdapter.value === 'GRS_AI')
const isNanoBananaModel = computed(() => {
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  return isGrsaiImage.value && model.includes('nano-banana')
})
const usesRatioOptions = computed(() => isGeminiImage.value || isNanoBananaModel.value)
const isGptImageModel = computed(() => {
  const model = selectedImageProvider.value?.modelName?.toLowerCase() || ''
  return (providerAdapter.value === 'OPENAI_IMAGE' || isGrsaiImage.value)
    && (model.includes('gpt-image') || model.includes('gpt image'))
})
const isReferenceMode = computed(() => references.value.length > 0)
const historyImages = computed<HistoryImageItem[]>(() => {
  const items: HistoryImageItem[] = []
  for (const message of props.historyMessages) {
    if (message.imageUrl && message.status !== 'FAILED') {
      items.push({
        id: `gen-${message.id}`,
        url: message.imageUrl,
        thumbUrl: getThumbnailUrl(message.id),
        label: message.drawPrompt || '历史图片',
        format: message.drawFormat || 'png',
        sourceKey: `gen-${message.id}`,
      })
    }
    if (message.attachments?.length) {
      for (const attachment of message.attachments) {
        if (attachment.contentType?.startsWith('image/') && !attachment.originalName?.startsWith('history-')) {
          const ext = attachment.originalName?.split('.').pop() || 'png'
          items.push({
            id: `att-${attachment.id}`,
            url: attachment.fileUrl,
            thumbUrl: getAttachmentThumbnailUrl(attachment.id),
            label: attachment.originalName || '用户上传图片',
            format: ext,
            sourceKey: `att-${attachment.id}`,
          })
        }
      }
    }
  }
  return items.reverse()
})

const sizeOptions = computed(() => usesRatioOptions.value
  ? ['1:1', '16:9', '9:16', '4:3', '3:4']
  : isGptImageModel.value
    ? ['1024x1024', '1536x1024', '1024x1536', 'auto']
    : ['1024x1024', '512x512', '768x768', '1024x1792', '1792x1024'])

const qualityOptions = computed(() => usesRatioOptions.value
  ? ['1K', '2K', '4K']
  : isGptImageModel.value ? ['auto', 'low', 'medium', 'high'] : ['standard', 'hd'])

const formatOptions = computed(() => usesRatioOptions.value ? ['png'] : ['png', 'jpeg', 'webp'])

watch(() => props.visible, (visible) => {
  if (!visible) {
    referenceDragDepth = 0
    isDraggingReference.value = false
    return
  }
  selectedProviderId.value = resolveDefaultImageProviderId()
  const parsed = props.smartParseInitialPrompt
    ? parseAssistantDrawSuggestion(props.initialPrompt || '')
    : { prompt: props.initialPrompt || '' }

  prompt.value = parsed.prompt || props.initialPrompt || ''
  references.value = [...props.initialReferences]
  historyImportingId.value = null
  historySelectedIds.value = []
  size.value = normalizeOption(parsed.size, sizeOptions.value)
    || (usesRatioOptions.value ? '1:1' : '1024x1024')
  quality.value = normalizeQualityOption(parsed.quality, qualityOptions.value)
    || (usesRatioOptions.value ? '1K' : isGptImageModel.value ? 'auto' : 'standard')
  format.value = normalizeOption(parsed.format, formatOptions.value) || 'png'
}, { immediate: true })

watch(selectedProviderId, () => {
  size.value = normalizeOption(size.value, sizeOptions.value)
    || (usesRatioOptions.value ? '1:1' : '1024x1024')
  quality.value = normalizeQualityOption(quality.value, qualityOptions.value)
    || (usesRatioOptions.value ? '1K' : isGptImageModel.value ? 'auto' : 'standard')
  format.value = normalizeOption(format.value, formatOptions.value) || 'png'
})

function normalizeOption(value: string | undefined, options: string[]) {
  if (!value) return ''
  const normalized = value.trim().toLowerCase().replace(/[×*]/g, 'x')
  return options.find((item) => item.toLowerCase() === normalized) || ''
}


function normalizeQualityOption(value: string | undefined, options: string[]) {
  const direct = normalizeOption(value, options)
  if (direct || !value) return direct
  const normalized = value.trim().toLowerCase()
  if (normalized === 'high' && options.includes('hd')) return 'hd'
  if ((normalized === 'medium' || normalized === 'low' || normalized === 'auto') && options.includes('standard')) {
    return 'standard'
  }
  return ''
}

function parseAssistantDrawSuggestion(text: string): {
  prompt: string
  size?: string
  quality?: string
  format?: string
} {
  const source = text.trim()
  if (!source) return { prompt: '' }

  const prompt = extractPrompt(source)
  return {
    prompt: prompt || source,
    size: extractSize(source),
    quality: extractQuality(source),
    format: extractFormat(source),
  }
}

function extractPrompt(text: string) {
  const codeBlock = text.match(/```(?:[a-zA-Z-]+)?\s*([\s\S]*?)```/)
  if (codeBlock?.[1]?.trim()) {
    return stripPromptLabel(codeBlock[1].trim())
  }

  const lines = text
    .replace(/```/g, '')
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)

  const labelIndex = lines.findIndex((line) => isPromptLabelLine(line))
  if (labelIndex >= 0) {
    const firstLine = stripPromptLabel(lines[labelIndex] || '')
    const collected: string[] = []
    if (firstLine) collected.push(firstLine)

    for (let i = labelIndex + 1; i < lines.length; i++) {
      const line = lines[i] || ''
      if (isConfigOrAdviceLine(line)) break
      collected.push(cleanListPrefix(line))
    }

    const result = collected.join('\n').trim()
    if (result) return result
  }

  const filtered = lines
    .map(cleanListPrefix)
    .filter((line) => !isConfigOrAdviceLine(line))
    .join('\n')
    .trim()

  return filtered || text.trim()
}

function isPromptLabelLine(line: string) {
  return /^(?:[-*•\d.、）)\s]*)?(?:绘图|生图|图像|图片)?\s*(?:提示词|prompt)\s*[：:]/i.test(line)
}

function stripPromptLabel(line: string) {
  return trimInlineConfig(cleanListPrefix(line)
    .replace(/^(?:绘图|生图|图像|图片)?\s*(?:提示词|prompt)\s*[：:]\s*/i, '')
    .trim())
}

function trimInlineConfig(text: string) {
  return text
    .split(/[，,；;。]\s*(?:尺寸|大小|比例|分辨率|画幅|质量|品质|格式|输出格式|size|quality|format|output_format)\s*[：:]/i)[0]
    ?.trim() || text.trim()
}

function cleanListPrefix(line: string) {
  return line.replace(/^(?:[-*•]|\d+[.、）)])\s*/, '').trim()
}

function isConfigOrAdviceLine(line: string) {
  const normalized = cleanListPrefix(line)
  return /^(?:size|quality|format|output_format)\b/i.test(normalized)
    || /^(?:尺寸|大小|比例|分辨率|画幅|质量|品质|清晰度|格式|输出格式|图片格式|参考图|源图|模型|参数|输出配置|配置)(?:\s|[：:]|$)/.test(normalized)
    || /^建议(?:尺寸|大小|比例|分辨率|画幅|质量|品质|格式|使用|配置)?(?:\s|[：:]|$)/.test(normalized)
    || /^(?:说明|备注)(?:\s|[：:]|$)/.test(normalized)
}

function extractSize(text: string) {
  const option = sizeOptions.value.find((item) => item !== 'auto' && text.includes(item))
  if (option) return option
  const match = text.match(/(?:尺寸|大小|分辨率|画幅|size)?\s*[：:]?\s*(auto|\d{3,4}\s*[x×*]\s*\d{3,4})/i)
  return match?.[1]?.replace(/\s+/g, '').replace(/[×*]/g, 'x')
}

function extractQuality(text: string) {
  const lower = text.toLowerCase()
  if (/\bhigh\b|高质量|高清|高品质|(?:质量|品质)[：:\s]*高/.test(lower)) return 'high'
  if (/\bmedium\b|中等|标准质量|(?:质量|品质)[：:\s]*中/.test(lower)) return 'medium'
  if (/\blow\b|低质量|草稿|(?:质量|品质)[：:\s]*低/.test(lower)) return 'low'
  if (/\bhd\b/.test(lower)) return 'hd'
  if (/\bstandard\b|标准/.test(lower)) return 'standard'
  if (/\bauto\b|自动/.test(lower)) return 'auto'
}

function extractFormat(text: string) {
  const lower = text.toLowerCase()
  if (/\bwebp\b/.test(lower)) return 'webp'
  if (/\bpng\b|透明背景/.test(lower)) return 'png'
  if (/\bjpeg\b|\bjpg\b/.test(lower)) return 'jpeg'
}

function isImage(contentType: string) {
  return contentType?.startsWith('image/')
}

async function handleFileSelect(file: any) {
  const raw = file.raw as File
  if (!raw) return
  await addReferenceFiles([raw])
}

async function addReferenceFiles(files: File[]) {
  const imageFiles = files.filter((file) => file.type.startsWith('image/'))
  const ignoredCount = files.length - imageFiles.length

  if (ignoredCount > 0) {
    ElMessage.warning(ignoredCount === files.length
      ? '参考图仅支持图片文件'
      : `已忽略 ${ignoredCount} 个非图片文件`)
  }
  if (imageFiles.length === 0) return

  uploadTaskCount.value += 1
  let failedCount = 0
  try {
    for (const file of imageFiles) {
      try {
        const resp = await sessionApi.uploadFile(file)
        references.value.push(resp)
      } catch {
        failedCount += 1
      }
    }
  } finally {
    uploadTaskCount.value -= 1
  }

  if (failedCount > 0) {
    ElMessage.error(failedCount === imageFiles.length
      ? '参考图上传失败'
      : `${failedCount} 张参考图上传失败，其余图片已添加`)
  }
}

function getClipboardImages(event: ClipboardEvent) {
  return Array.from(event.clipboardData?.items || [])
    .filter((item) => item.type.startsWith('image/'))
    .map((item) => item.getAsFile())
    .filter((file): file is File => file !== null)
}

function handleReferencePaste(event: ClipboardEvent) {
  if (!props.visible) return
  const files = getClipboardImages(event)
  if (files.length === 0) return

  event.preventDefault()
  void addReferenceFiles(files)
}

function hasDraggedFiles(event: DragEvent) {
  return Array.from(event.dataTransfer?.types || []).includes('Files')
}

function handleReferenceDragEnter(event: DragEvent) {
  if (!hasDraggedFiles(event)) return
  referenceDragDepth += 1
  isDraggingReference.value = true
}

function handleReferenceDragOver(event: DragEvent) {
  if (!hasDraggedFiles(event)) return
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'copy'
}

function handleReferenceDragLeave() {
  if (!isDraggingReference.value) return
  referenceDragDepth = Math.max(0, referenceDragDepth - 1)
  if (referenceDragDepth === 0) isDraggingReference.value = false
}

function handleReferenceDrop(event: DragEvent) {
  referenceDragDepth = 0
  isDraggingReference.value = false
  const files = Array.from(event.dataTransfer?.files || [])
  if (files.length > 0) void addReferenceFiles(files)
}

onMounted(() => window.addEventListener('paste', handleReferencePaste))
onBeforeUnmount(() => window.removeEventListener('paste', handleReferencePaste))

function removeReference(id: number) {
  references.value = references.value.filter((item) => item.id !== id)
}

function historyImageFilename(item: HistoryImageItem) {
  const url = item.url || ''
  const raw = (url.split('?')[0] || url).split('/').filter(Boolean).pop()
  if (raw?.includes('.')) return raw
  return `history-${item.id}.${item.format || 'png'}`
}

async function selectHistoryImage(item: HistoryImageItem) {
  if (!item.url || historyImportingId.value != null) return
  if (historySelectedIds.value.includes(item.id)) {
    ElMessage.info('这张图片已经添加为参考图')
    return
  }

  historyImportingId.value = item.id
  uploadTaskCount.value += 1
  try {
    const uploaded = await sessionApi.uploadImageReference(
      item.url,
      historyImageFilename(item),
    )
    references.value.push(uploaded)
    historySelectedIds.value.push(item.id)
    ElMessage.success('已从会话历史添加参考图')
  } catch (error: any) {
    ElMessage.error(error?.message || '历史图片添加失败')
  } finally {
    uploadTaskCount.value -= 1
    historyImportingId.value = null
  }
}

function handleCancel() {
  emit('update:visible', false)
}

function handleGenerate() {
  if (uploading.value) {
    ElMessage.warning('请等待参考图上传完成')
    return
  }
  const text = prompt.value.trim()
  if (!text) {
    ElMessage.warning('请输入绘画提示词')
    return
  }
  emit('generate', {
    prompt: text,
    attachmentIds: references.value.map((item) => item.id),
    references: [...references.value],
    size: size.value,
    quality: quality.value,
    format: format.value,
    imageProviderId: selectedProviderId.value,
  })
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    title="绘画参数"
    width="720px"
    :close-on-click-modal="!loading"
    @update:model-value="emit('update:visible', $event)"
  >
    <div class="draw-dialog">
      <el-form label-width="86px" label-position="left">
        <el-form-item label="本次模型">
          <div class="provider-field">
            <el-select v-model="selectedProviderId" placeholder="暂无可用绘图模型">
              <el-option
                v-for="provider in imageProviders"
                :key="provider.id"
                :label="`${provider.name || provider.providerId} / ${provider.modelName}`"
                :value="provider.id"
              />
            </el-select>
            <span class="field-hint">仅影响本次生成，不修改会话默认模型。</span>
          </div>
        </el-form-item>

        <el-alert
          v-if="selectedImageProvider"
          type="info"
          :closable="false"
          show-icon
          class="provider-alert"
        >
          <template #title>
            将使用：{{ selectedImageProvider.name || selectedImageProvider.providerId }} / {{ selectedImageProvider.modelName }}（{{ isGrsaiImage ? 'Grsai' : isGeminiImage ? 'Gemini Image' : 'OpenAI Images' }}）
          </template>
        </el-alert>
        <el-alert
          v-else
          type="warning"
          :closable="false"
          show-icon
          class="provider-alert"
          title="尚未找到可用绘图供应商，将由后端解析默认绘图供应商。"
        />
        <el-form-item label="提示词">
          <el-input
            v-model="prompt"
            type="textarea"
            :autosize="{ minRows: 5, maxRows: 10 }"
            resize="none"
            placeholder="描述要生成或编辑的图像..."
          />
        </el-form-item>

        <el-form-item label="参考图">
          <div class="reference-field">
          <div v-if="references.length > 0" class="reference-list">
              <div v-for="item in references" :key="item.id" class="reference-item">
                <el-image
                  v-if="isImage(item.contentType)"
                  :src="item.fileUrl"
                  fit="cover"
                  class="reference-thumb"
                  :preview-src-list="[item.fileUrl]"
                  preview-teleported
                />
                <span class="reference-name">{{ item.originalName }}</span>
                <el-button text size="small" type="danger" @click="removeReference(item.id)">移除</el-button>
              </div>
            </div>
            <div v-if="historyImages.length > 0" class="history-reference-picker">
              <div class="history-reference-heading">
                <strong>从会话历史选择</strong>
                <span>点击图片即可添加为参考图</span>
              </div>
              <div class="history-image-grid">
                <button
                  v-for="item in historyImages"
                  :key="item.id"
                  type="button"
                  class="history-image-card"
                  :class="{ selected: historySelectedIds.includes(item.id) }"
                  :disabled="historyImportingId != null || loading"
                  :title="item.label || '会话历史图片'"
                  @click="selectHistoryImage(item)"
                >
                  <el-image :src="historyDisplayUrl(item)" fit="cover" @error="onHistoryThumbError(item.id)" />
                  <span v-if="historyImportingId === item.id" class="history-image-loading">正在添加…</span>
                  <span v-else-if="historySelectedIds.includes(item.id)" class="history-image-selected">已添加</span>
                  <span class="history-image-caption">{{ item.label || '历史图片' }}</span>
                </button>
              </div>
            </div>
            <div
              class="reference-drop-zone"
              :class="{ 'is-dragging': isDraggingReference }"
              @dragenter.prevent="handleReferenceDragEnter"
              @dragover.prevent="handleReferenceDragOver"
              @dragleave.prevent="handleReferenceDragLeave"
              @drop.prevent="handleReferenceDrop"
            >
              <el-upload
                :show-file-list="false"
                :auto-upload="false"
                :on-change="handleFileSelect"
                accept="image/*"
                multiple
              >
                <el-button :icon="Paperclip" :loading="uploading">选择参考图</el-button>
              </el-upload>
              <div class="reference-drop-hint">
                <strong>{{ isDraggingReference ? '松开鼠标添加图片' : '粘贴或拖拽图片到此处' }}</strong>
                <span>支持 Ctrl/Cmd+V，可一次添加多张图片</span>
              </div>
            </div>
            <span class="field-hint">{{ isReferenceMode ? '当前为参考图/图片编辑模式。' : '上传后将作为绘图模型的参考图或源图。' }}</span>
          </div>
        </el-form-item>

        <el-form-item label="输出配置">
          <span v-if="isGeminiImage" class="model-hint">Gemini 使用画幅比例和图片尺寸；参考图将随请求自动适配。</span>
          <span v-else-if="isGrsaiImage" class="model-hint">Grsai 通过异步任务生成图片；Nano Banana 使用画幅比例和 1K/2K/4K，参考图会以 Base64 Data URL 发送。</span>
          <div class="output-grid">
            <el-select v-model="size" placeholder="尺寸">
              <el-option v-for="item in sizeOptions" :key="item" :label="item" :value="item" />
            </el-select>
            <el-select v-model="quality" placeholder="质量">
              <el-option v-for="item in qualityOptions" :key="item" :label="item" :value="item" />
            </el-select>
            <el-select v-model="format" placeholder="格式">
              <el-option v-for="item in formatOptions" :key="item" :label="item.toUpperCase()" :value="item" />
            </el-select>
          </div>
        </el-form-item>
      </el-form>
    </div>

    <template #footer>
      <el-button :disabled="loading" @click="handleCancel">取消</el-button>
      <el-button type="primary" :icon="Picture" :loading="loading" :disabled="uploading" @click="handleGenerate">生成</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.draw-dialog {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.provider-alert {
  margin-bottom: 16px;
}

.provider-field {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
}

.provider-field :deep(.el-select) {
  flex: 1;
}

.reference-field {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.reference-list {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.reference-drop-zone {
  min-height: 78px;
  border: 1px dashed #c0c4cc;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  background: #fafafa;
  transition: border-color 0.2s, background-color 0.2s, box-shadow 0.2s;
}

.reference-drop-zone.is-dragging {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  box-shadow: 0 0 0 2px var(--el-color-primary-light-8) inset;
}

.reference-drop-hint {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
  color: #606266;
  font-size: 13px;
}

.reference-drop-hint strong {
  font-weight: 500;
}

.reference-drop-hint span {
  color: #909399;
  font-size: 12px;
}

.reference-item {
  width: 150px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  padding: 8px;
  background: #fafafa;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.history-reference-picker {
  padding: 11px 12px 12px;
  border: 1px solid #e4e8f6;
  border-radius: 10px;
  background: linear-gradient(135deg, #fafbff, #f5f7ff);
}

.history-reference-heading {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 9px;
}

.history-reference-heading strong {
  color: #4c5a78;
  font-size: 13px;
}

.history-reference-heading span {
  color: #929bb0;
  font-size: 11px;
}

.history-image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(100px, 1fr));
  gap: 8px;
  max-height: 180px;
  padding: 2px;
  overflow-y: auto;
}

.history-image-card {
  position: relative;
  min-width: 0;
  padding: 0;
  overflow: hidden;
  text-align: left;
  cursor: pointer;
  border: 2px solid transparent;
  border-radius: 9px;
  background: #e9ecf8;
  transition: border-color .18s ease, transform .18s ease, box-shadow .18s ease;
}

.history-image-card:hover:not(:disabled) {
  border-color: #9ba8ff;
  box-shadow: 0 4px 12px rgba(81, 98, 213, .18);
  transform: translateY(-1px);
}

.history-image-card.selected {
  border-color: #5267f6;
  box-shadow: 0 0 0 2px rgba(82, 103, 246, .14);
}

.history-image-card:disabled { cursor: wait; opacity: .78; }
.history-image-card :deep(.el-image) { display: block; width: 100%; height: 82px; }
.history-image-caption { display: block; padding: 5px 6px; overflow: hidden; color: #66718c; font-size: 10px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; background: #fff; }
.history-image-selected, .history-image-loading { position: absolute; top: 5px; right: 5px; padding: 2px 5px; color: #fff; font-size: 10px; border-radius: 999px; background: rgba(49, 62, 145, .82); }

.reference-thumb {
  width: 100%;
  height: 92px;
  border-radius: 8px;
}

.reference-name {
  font-size: 12px;
  color: #606266;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.field-hint {
  color: #909399;
  font-size: 12px;
}

.output-grid {
  width: 100%;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

:deep(.el-form-item__label) {
  white-space: nowrap;
}
</style>
