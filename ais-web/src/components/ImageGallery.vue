<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Download, Select } from '@element-plus/icons-vue'
import type { Message } from '@/types'
import { getThumbnailUrl } from '@/utils/imageUrl'
import { formatDateTime, formatLocalDateKey } from '@/utils/dateTime'

const props = defineProps<{ messages: Message[] }>()
const selectedIds = ref<number[]>([])
const downloading = ref(false)
const thumbFailedIds = ref<Set<number>>(new Set())

function onThumbError(message: Message) {
  thumbFailedIds.value.add(message.id)
}

function displayUrl(message: Message): string {
  return thumbFailedIds.value.has(message.id) ? (message.imageUrl || '') : getThumbnailUrl(message.id)
}

const images = computed(() => props.messages.filter((message) => !!message.imageUrl))
const groups = computed(() => {
  const result = new Map<string, Message[]>()
  for (const message of images.value) {
    const day = formatLocalDateKey(message.createdAt) || '未知日期'
    const group = result.get(day) || []
    group.push(message)
    result.set(day, group)
  }
  return [...result.entries()].sort((a, b) => b[0].localeCompare(a[0]))
})

function filename(message: Message) {
  const url = message.imageUrl || ''
  const raw = (url.split('?')[0] || url).split('/').filter(Boolean).pop() || `generated-${message.id}.png`
  return raw.includes('.') ? raw : `${raw}.${message.drawFormat || 'png'}`
}

function formatTime(value: string) {
  return formatDateTime(value, '')
}

function toggleAll() {
  selectedIds.value = selectedIds.value.length === images.value.length
    ? []
    : images.value.map((message) => message.id)
}

async function download(message: Message) {
  if (!message.imageUrl) return
  try {
    const response = await fetch(message.imageUrl)
    if (!response.ok) throw new Error(`HTTP ${response.status}`)
    const blob = await response.blob()
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename(message)
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    const link = document.createElement('a')
    link.href = message.imageUrl
    link.download = filename(message)
    link.click()
  }
}

async function downloadSelected() {
  const selected = images.value.filter((message) => selectedIds.value.includes(message.id))
  if (!selected.length) {
    ElMessage.info('请先选择图片')
    return
  }
  downloading.value = true
  try {
    for (const message of selected) {
      await download(message)
      await new Promise((resolve) => setTimeout(resolve, 120))
    }
    ElMessage.success(`已开始下载 ${selected.length} 张图片`)
  } finally {
    downloading.value = false
  }
}

async function copyPrompt(message: Message) {
  const prompt = (message.drawPrompt || message.content || '').trim()
  if (!prompt) {
    ElMessage.info('该图片没有可复制的提示词')
    return
  }
  try {
    await navigator.clipboard.writeText(prompt)
    ElMessage.success('提示词已复制')
  } catch {
    ElMessage.error('复制提示词失败，请手动选择复制')
  }
}
</script>

<template>
  <div class="gallery">
    <div class="gallery-toolbar">
      <span class="gallery-count">共 {{ images.length }} 张生成图片</span>
      <div class="gallery-actions">
        <el-button size="small" :icon="Select" @click="toggleAll">
          {{ selectedIds.length === images.length && images.length ? '取消全选' : '全选' }}
        </el-button>
        <el-button size="small" type="primary" :icon="Download" :loading="downloading" @click="downloadSelected">
          下载选中（{{ selectedIds.length }}）
        </el-button>
      </div>
    </div>

    <el-empty v-if="images.length === 0" description="当前会话还没有生成图片" />
    <section v-for="[day, items] in groups" :key="day" class="gallery-day">
      <h3>{{ day }}</h3>
      <div class="gallery-grid">
        <article v-for="message in items" :key="message.id" class="gallery-card">
          <div class="gallery-image-wrap">
            <el-checkbox v-model="selectedIds" :label="message.id" class="gallery-check">
              <span class="sr-only">选择图片</span>
            </el-checkbox>
            <el-image
              :src="displayUrl(message)"
              fit="cover"
              class="gallery-image"
              :preview-src-list="[message.imageUrl || '']"
              preview-teleported
              @error="onThumbError(message)"
            />
          </div>
          <div class="gallery-meta">
            <div class="gallery-time">{{ formatTime(message.createdAt) }}</div>
            <div v-if="message.drawSize" class="gallery-detail">尺寸：{{ message.drawSize }}</div>
            <div v-if="message.drawQuality" class="gallery-detail">质量：{{ message.drawQuality }}</div>
            <div class="gallery-prompt" :title="message.drawPrompt || message.content">
              提示词：{{ message.drawPrompt || message.content || '—' }}
            </div>
            <div class="gallery-card-actions">
              <el-button text type="primary" size="small" :icon="CopyDocument" @click="copyPrompt(message)">复制提示词</el-button>
              <el-button text type="primary" size="small" :icon="Download" @click="download(message)">下载</el-button>
            </div>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.gallery { height: 100%; overflow-y: auto; padding: 24px clamp(20px,4vw,48px) 42px; background: transparent; }.gallery-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }.gallery-count { color: #65718c; font-size: 13px; font-weight: 700; }.gallery-actions { display: flex; gap: 8px; }.gallery-day h3 { margin: 24px 0 12px; color: #3b4764; font-size: 14px; }.gallery-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(205px,1fr)); gap: 16px; }.gallery-card { overflow: hidden; border: 1px solid #e5e9f5; border-radius: 14px; background: rgba(255,255,255,.94); box-shadow: 0 7px 20px rgba(48,61,113,.06); transition: transform .18s ease, box-shadow .18s ease; }.gallery-card:hover { box-shadow: 0 14px 29px rgba(48,61,113,.13); transform: translateY(-3px); }.gallery-image-wrap { position: relative; height: 192px; background: #f0f2fa; }.gallery-image { width: 100%; height: 100%; cursor: zoom-in; }.gallery-check { position: absolute; z-index: 2; top: 9px; left: 9px; padding: 4px; border-radius: 6px; background: rgba(255,255,255,.92); box-shadow: 0 2px 7px rgba(45,55,94,.15); }.gallery-meta { padding: 11px 12px; }.gallery-time { color: #959fb5; font-size: 11px; }.gallery-detail { margin-top: 3px; color: #67728c; font-size: 12px; }.gallery-prompt { display: -webkit-box; overflow: hidden; margin: 8px 0 5px; color: #404c69; font-size: 12px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }.gallery-card-actions { display: flex; align-items: center; justify-content: space-between; gap: 6px; }.sr-only { position:absolute; width:1px; height:1px; overflow:hidden; clip:rect(0,0,0,0); } @media(max-width:700px){.gallery { padding:18px 14px 30px; }.gallery-toolbar { align-items:flex-start; flex-direction:column; gap:10px; }.gallery-grid { grid-template-columns: repeat(auto-fill,minmax(155px,1fr)); gap:11px; }.gallery-image-wrap { height:160px; }}
</style>
