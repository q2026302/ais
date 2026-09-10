<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument, Delete, Download, Link, Select } from '@element-plus/icons-vue'
import type { Favorite, FavoriteReferenceImage } from '@/types'
import { useFavoriteStore } from '@/stores/favorite'
import { useAuthStore } from '@/stores/auth'
import { getFavoriteReferenceThumbnailUrl, getFavoriteThumbnailUrl } from '@/utils/imageUrl'
import { downloadImage as downloadImageAsset } from '@/utils/downloadImage'
import { formatDateTime, formatLocalDateKey } from '@/utils/dateTime'
import { useSignedUrlRefresh } from '@/composables/useSignedUrlRefresh'

/**
 * PC work library (作品库). Cross-session, user-level aggregation of saved works
 * — a snapshot list, never derived from the active session's messages.
 */
const props = withDefaults(defineProps<{ active?: boolean }>(), { active: true })
const emit = defineEmits<{ openSession: [sessionId: number] }>()

const favoriteStore = useFavoriteStore()
const authStore = useAuthStore()
const { recoverFavoriteImage } = useSignedUrlRefresh()

const selectedIds = ref<number[]>([])
const downloading = ref(false)
const thumbFailedIds = ref<Set<number>>(new Set())
const referenceThumbFailed = ref<Set<string>>(new Set())

const favorites = computed(() => favoriteStore.favorites)

function displayUrl(favorite: Favorite): string {
  if (thumbFailedIds.value.has(favorite.id)) return favorite.imageUrl
  return getFavoriteThumbnailUrl(favorite, 'medium') || favorite.imageUrl
}

function onThumbError(favorite: Favorite) {
  const failedUrl = displayUrl(favorite)
  thumbFailedIds.value = new Set(thumbFailedIds.value).add(favorite.id)
  recoverFavoriteImage(failedUrl)
}

/** Reference thumbnails fall back to the full image, matching the mobile view. */
function referenceDisplayUrl(reference: FavoriteReferenceImage): string {
  if (referenceThumbFailed.value.has(reference.fileUrl)) return reference.fileUrl
  return getFavoriteReferenceThumbnailUrl(reference, 'small') || reference.fileUrl
}

function onReferenceThumbError(reference: FavoriteReferenceImage) {
  const failedUrl = referenceDisplayUrl(reference)
  referenceThumbFailed.value = new Set(referenceThumbFailed.value).add(reference.fileUrl)
  recoverFavoriteImage(failedUrl)
}

// Re-arm thumbnails after a refresh delivers fresh signed URLs.
const urlKey = computed(() => favorites.value
  .map((favorite) => {
    const references = favorite.referenceImages
      .map((reference) => `${reference.fileUrl ?? ''}|${reference.thumbnailUrl ?? ''}`)
      .join(',')
    return `${favorite.imageUrl ?? ''}|${favorite.thumbnailUrl ?? ''}|${references}`
  })
  .join('#'))
watch(urlKey, () => {
  thumbFailedIds.value = new Set()
  referenceThumbFailed.value = new Set()
})

const groups = computed(() => {
  const result = new Map<string, Favorite[]>()
  for (const favorite of favorites.value) {
    const day = formatLocalDateKey(favorite.createdAt) || '未知日期'
    const group = result.get(day) || []
    group.push(favorite)
    result.set(day, group)
  }
  return [...result.entries()].sort((a, b) => b[0].localeCompare(a[0]))
})

function filename(favorite: Favorite) {
  const url = favorite.imageUrl || ''
  const raw = (url.split('?')[0] || url).split('/').filter(Boolean).pop() || `favorite-${favorite.id}.png`
  return raw.includes('.') ? raw : `${raw}.${favorite.drawFormat || 'png'}`
}

function formatTime(value: string) {
  return formatDateTime(value, '')
}

function toggleAll() {
  selectedIds.value = selectedIds.value.length === favorites.value.length
    ? []
    : favorites.value.map((favorite) => favorite.id)
}

async function download(favorite: Favorite) {
  if (!favorite.imageUrl) return
  const result = await downloadImageAsset(favorite.imageUrl, filename(favorite))
  if (result.mode === 'cancelled') return
}

async function downloadSelected() {
  const selected = favorites.value.filter((favorite) => selectedIds.value.includes(favorite.id))
  if (!selected.length) {
    ElMessage.info('请先选择作品')
    return
  }
  downloading.value = true
  try {
    for (const favorite of selected) {
      await download(favorite)
      await new Promise((resolve) => setTimeout(resolve, 120))
    }
    ElMessage.success(`已开始下载 ${selected.length} 张图片`)
  } finally {
    downloading.value = false
  }
}

async function copyPrompt(favorite: Favorite) {
  const prompt = (favorite.drawPrompt || '').trim()
  if (!prompt) {
    ElMessage.info('该作品没有可复制的提示词')
    return
  }
  try {
    await navigator.clipboard.writeText(prompt)
    ElMessage.success('提示词已复制')
  } catch {
    ElMessage.error('复制提示词失败，请手动选择复制')
  }
}

async function removeFavorite(favorite: Favorite) {
  try {
    await favoriteStore.removeFavorite(favorite)
    selectedIds.value = selectedIds.value.filter((id) => id !== favorite.id)
    ElMessage.success('已取消收藏')
  } catch (error: any) {
    ElMessage.error(error?.message || '取消收藏失败')
  }
}

/** Owner label for the all-users (admin) view so each record can be managed individually. */
function ownerLabel(favorite: Favorite): string {
  if (favorite.userName) return favorite.userName
  if (favorite.userId != null) return `用户 #${favorite.userId}`
  return '未知用户'
}

function openSession(favorite: Favorite) {
  if (!favorite.sessionAvailable || favorite.sessionId == null) return
  emit('openSession', favorite.sessionId)
}

async function loadWorks() {
  try {
    await favoriteStore.fetchFavorites(0)
  } catch (error: any) {
    ElMessage.error(error?.message || '作品库加载失败')
  }
}

onMounted(() => {
  if (props.active) void loadWorks()
})

// Reload when the view becomes active so other devices' changes show up.
watch(() => props.active, (active) => {
  if (active) void loadWorks()
})
</script>

<template>
  <div class="works">
    <div class="works-toolbar">
      <span class="works-count">共 {{ favoriteStore.totalElements }} 件收藏作品</span>
      <div class="works-actions">
        <el-button size="small" :icon="Select" :disabled="favorites.length === 0" @click="toggleAll">
          {{ selectedIds.length === favorites.length && favorites.length ? '取消全选' : '全选' }}
        </el-button>
        <el-button size="small" type="primary" :icon="Download" :loading="downloading" @click="downloadSelected">
          下载选中（{{ selectedIds.length }}）
        </el-button>
      </div>
    </div>

    <div v-if="!favorites.length && !favoriteStore.loaded" class="works-loading">正在加载作品库…</div>
    <el-empty v-else-if="favorites.length === 0" description="作品库还没有收藏；在对话中对生成图片点击「收藏」即可保存" />

    <section v-for="[day, items] in groups" :key="day" class="works-day">
      <h3>{{ day }}</h3>
      <div class="works-grid">
        <article v-for="favorite in items" :key="favorite.id" class="works-card">
          <div class="works-image-wrap">
            <el-checkbox v-model="selectedIds" :label="favorite.id" class="works-check">
              <span class="sr-only">选择作品</span>
            </el-checkbox>
            <el-image
              :src="displayUrl(favorite)"
              fit="cover"
              class="works-image"
              :preview-src-list="[favorite.imageUrl || '']"
              preview-teleported
              @error="onThumbError(favorite)"
            />
          </div>
          <div class="works-meta">
            <div class="works-time">收藏于 {{ formatTime(favorite.createdAt) }}</div>
            <div v-if="authStore.isAdmin" class="works-owner">归属：{{ ownerLabel(favorite) }}</div>
            <div v-if="favorite.drawSize" class="works-detail">尺寸：{{ favorite.drawSize }}</div>
            <div v-if="favorite.drawQuality" class="works-detail">质量：{{ favorite.drawQuality }}</div>
            <div class="works-prompt" :title="favorite.drawPrompt || '—'">
              提示词：{{ favorite.drawPrompt || '—' }}
            </div>
            <div v-if="favorite.referenceImages.length" class="works-references">
              <span class="works-references-label">参考图（{{ favorite.referenceImages.length }}）</span>
              <div class="works-references-list">
                <el-image
                  v-for="(reference, index) in favorite.referenceImages"
                  :key="`${favorite.id}-ref-${index}`"
                  :src="referenceDisplayUrl(reference)"
                  fit="cover"
                  class="works-reference"
                  :preview-src-list="[reference.fileUrl]"
                  preview-teleported
                  @error="onReferenceThumbError(reference)"
                />
              </div>
            </div>
            <div class="works-card-actions">
              <el-button text type="primary" size="small" :icon="CopyDocument" @click="copyPrompt(favorite)">复制提示词</el-button>
              <el-button text type="primary" size="small" :icon="Download" @click="download(favorite)">下载</el-button>
              <el-button
                v-if="favorite.sessionAvailable && favorite.sessionId != null"
                text
                type="primary"
                size="small"
                :icon="Link"
                @click="openSession(favorite)"
              >回原会话</el-button>
              <el-button
                text
                type="danger"
                size="small"
                :icon="Delete"
                :loading="favoriteStore.isRecordPending(favorite.id)"
                :disabled="favoriteStore.isRecordPending(favorite.id)"
                @click="removeFavorite(favorite)"
              >取消收藏</el-button>
            </div>
          </div>
        </article>
      </div>
    </section>

    <div v-if="favoriteStore.hasMore" class="works-more">
      <el-button size="small" :loading="favoriteStore.loading" @click="favoriteStore.loadMore()">加载更多</el-button>
    </div>
  </div>
</template>

<style scoped>
.works { height: 100%; overflow-y: auto; padding: 24px clamp(20px,4vw,48px) 42px; background: transparent; }
.works-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20px; }
.works-count { color: #65718c; font-size: 13px; font-weight: 700; }
.works-actions { display: flex; gap: 8px; }
.works-loading { padding: 40px 0; color: #8a93a9; font-size: 13px; text-align: center; }
.works-day h3 { margin: 24px 0 12px; color: #3b4764; font-size: 14px; }
.works-grid { display: grid; grid-template-columns: repeat(auto-fill,minmax(215px,1fr)); gap: 16px; }
.works-card { overflow: hidden; border: 1px solid #e5e9f5; border-radius: 14px; background: rgba(255,255,255,.94); box-shadow: 0 7px 20px rgba(48,61,113,.06); transition: transform .18s ease, box-shadow .18s ease; }
.works-card:hover { box-shadow: 0 14px 29px rgba(48,61,113,.13); transform: translateY(-3px); }
.works-image-wrap { position: relative; height: 196px; background: #f0f2fa; }
.works-image { width: 100%; height: 100%; cursor: zoom-in; }
.works-check { position: absolute; z-index: 2; top: 9px; left: 9px; padding: 4px; border-radius: 6px; background: rgba(255,255,255,.92); box-shadow: 0 2px 7px rgba(45,55,94,.15); }
.works-meta { padding: 11px 12px; }
.works-time { color: #959fb5; font-size: 11px; }
.works-owner { margin-top: 3px; color: #7b5ea7; font-size: 11px; font-weight: 700; }
.works-detail { margin-top: 3px; color: #67728c; font-size: 12px; }
.works-prompt { display: -webkit-box; overflow: hidden; margin: 8px 0 5px; color: #404c69; font-size: 12px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.works-references { margin: 6px 0 4px; }
.works-references-label { display: block; margin-bottom: 5px; color: #959fb5; font-size: 10px; font-weight: 700; }
.works-references-list { display: flex; flex-wrap: wrap; gap: 6px; }
.works-reference { width: 44px; height: 44px; cursor: zoom-in; border: 1px solid #e5e9f5; border-radius: 8px; background: #f4f6fb; }
.works-card-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 2px 6px; margin-top: 4px; }
.works-more { display: flex; justify-content: center; margin-top: 24px; }
.sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); }
@media (max-width:700px){ .works { padding: 18px 14px 30px; } .works-toolbar { align-items: flex-start; flex-direction: column; gap: 10px; } .works-grid { grid-template-columns: repeat(auto-fill,minmax(160px,1fr)); gap: 11px; } .works-image-wrap { height: 164px; } }
</style>
