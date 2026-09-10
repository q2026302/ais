<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, CopyDocument, Delete, Download, Link, Picture } from '@element-plus/icons-vue'
import MobileImageViewer from '@/components/MobileImageViewer.vue'
import { useImageActions } from '@/composables/useImageActions'
import { useLongPress } from '@/composables/useLongPress'
import { useFavoriteStore } from '@/stores/favorite'
import { useAuthStore } from '@/stores/auth'
import type { Favorite } from '@/types'
import { formatDateTimeSeconds } from '@/utils/dateTime'
import { getFavoriteReferenceThumbnailUrl, getFavoriteThumbnailUrl } from '@/utils/imageUrl'
import { mobileWorkspacePath } from '@/utils/mobileWorkspace'
import { useSignedUrlRefresh } from '@/composables/useSignedUrlRefresh'

defineOptions({
  name: 'FeishuGalleryPage',
})

const route = useRoute()
const router = useRouter()
const favoriteStore = useFavoriteStore()
const authStore = useAuthStore()

const mobileSource = computed(() => route.meta.mobileEntry ?? 'mobile')

const {
  imageActionVisible,
  saveHelperVisible,
  saveHelperUrl,
  saveHelperFilename,
  openImageAction: openImageActionBase,
  downloadImageAction,
  downloadImage,
  shareFromHelper,
  closeSaveHelper,
} = useImageActions()

const {
  longPressTriggered,
  startLongPress,
  moveLongPress,
  cancelLongPress,
  finishLongPress,
  clearResidualSelection,
  setSelectionSuppressed,
} = useLongPress()

/** Work owning the image currently open in the image-action drawer (null for reference images). */
const imageActionFavorite = ref<Favorite | null>(null)
const imageViewerVisible = ref(false)
const imageViewerImages = ref<string[]>([])
const imageViewerIndex = ref(0)
const galleryThumbFailedIds = ref<Set<number>>(new Set())
const referenceThumbFailed = ref<Set<string>>(new Set())
const { recoverFavoriteImage } = useSignedUrlRefresh()

/** Saved works (作品库): cross-session, user-level, independent of any session. */
const favorites = computed(() => favoriteStore.favorites)

onMounted(() => {
  void loadFavorites()
})

onActivated(() => {
  void loadFavorites()
})

async function loadFavorites() {
  try {
    await favoriteStore.fetchFavorites(0)
    // Close the viewer if the works it showed are gone (e.g. unfavourited elsewhere).
    if (imageViewerVisible.value && favorites.value.length === 0) {
      imageViewerVisible.value = false
      imageViewerImages.value = []
      imageViewerIndex.value = 0
    }
  } catch (error: any) {
    ElMessage.error(error?.message || '作品库加载失败')
  }
}

function favoriteImageUrls(): string[] {
  return favorites.value.map((favorite) => favorite.imageUrl).filter(Boolean)
}

function onGalleryThumbError(favorite: Favorite) {
  const failedUrl = galleryDisplayUrl(favorite)
  galleryThumbFailedIds.value = new Set(galleryThumbFailedIds.value).add(favorite.id)
  recoverFavoriteImage(failedUrl)
}

function galleryDisplayUrl(favorite: Favorite) {
  if (!favorite.imageUrl) return ''
  if (galleryThumbFailedIds.value.has(favorite.id)) return favorite.imageUrl
  return getFavoriteThumbnailUrl(favorite, 'small') || favorite.imageUrl
}

function referenceDisplayUrl(fileUrl: string, reference: { fileUrl: string; thumbnailUrl?: string | null }) {
  if (referenceThumbFailed.value.has(fileUrl)) return reference.fileUrl
  return getFavoriteReferenceThumbnailUrl(reference, 'small') || reference.fileUrl
}

function onReferenceThumbError(fileUrl: string) {
  const next = new Set(referenceThumbFailed.value)
  next.add(fileUrl)
  referenceThumbFailed.value = next
  recoverFavoriteImage(fileUrl)
}

// Re-arm thumbnails after a refresh delivers fresh signed URLs.
const galleryUrlKey = computed(() => favorites.value
  .map((favorite) => `${favorite.imageUrl ?? ''}|${favorite.thumbnailUrl ?? ''}`)
  .join('#'))
watch(galleryUrlKey, () => {
  galleryThumbFailedIds.value = new Set()
  referenceThumbFailed.value = new Set()
})

function formatFavoriteTime(value: string) {
  return formatDateTimeSeconds(value, '')
}

function openImageViewer(images: string[], index = 0) {
  const validImages = images.filter(Boolean)
  if (!validImages.length) return
  imageViewerImages.value = validImages
  imageViewerIndex.value = Math.max(0, Math.min(index, validImages.length - 1))
  imageViewerVisible.value = true
}

function handleImageClick(images: string[], index = 0) {
  if (longPressTriggered.value) {
    longPressTriggered.value = false
    return
  }
  openImageViewer(images, index)
}

/** Match FeishuH5View openImageAction: clear residual selection after opening. */
function openImageAction(url: string, filename = 'ai-image.png') {
  openImageActionBase(url, filename)
  clearResidualSelection()
  window.setTimeout(() => {
    clearResidualSelection()
    setSelectionSuppressed(false)
  }, 320)
}

function openFavoriteAction(favorite: Favorite) {
  imageActionFavorite.value = favorite
  openImageAction(favorite.imageUrl, favoriteFilename(favorite))
}

/** Reference images are not separate works, so the favourite action is hidden. */
function openReferenceAction(reference: { fileUrl: string }) {
  imageActionFavorite.value = null
  openImageAction(reference.fileUrl, 'reference.png')
}

async function removeFavoriteFromAction() {
  const favorite = imageActionFavorite.value
  imageActionVisible.value = false
  imageActionFavorite.value = null
  if (favorite) await removeFavorite(favorite)
}

function favoriteFilename(favorite: Favorite) {
  const url = favorite.imageUrl || ''
  const rawName = (url.split('?')[0] || url).split('/').filter(Boolean).pop() || `favorite-${favorite.id}.png`
  return rawName.includes('.') ? rawName : `${rawName}.${favorite.drawFormat || 'png'}`
}

async function copyText(text: string, successMessage = '内容已复制') {
  if (!text.trim()) return
  try {
    await navigator.clipboard.writeText(text)
    window.getSelection()?.removeAllRanges()
    ElMessage.success(successMessage)
  } catch {
    window.getSelection()?.removeAllRanges()
    ElMessage.error('复制失败，请手动选择复制')
  }
}

async function removeFavorite(favorite: Favorite) {
  try {
    await favoriteStore.removeFavorite(favorite)
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

async function openOriginalSession(favorite: Favorite) {
  if (!favorite.sessionAvailable || favorite.sessionId == null) {
    ElMessage.info('原会话已删除，仅可查看作品')
    return
  }
  await router.push(`${mobileWorkspacePath(mobileSource.value)}/chat/${favorite.sessionId}`)
}

async function goToCreate() {
  const base = mobileWorkspacePath(mobileSource.value)
  await router.push(`${base}/sessions`)
}
</script>

<template>
  <section class="gallery-panel">
    <div class="section-heading">
      <div>
        <span>作品库</span>
        <strong>我的 AI 作品</strong>
      </div>
      <small>{{ favorites.length }} / {{ favoriteStore.totalElements }} 张</small>
    </div>

    <div v-if="!favorites.length && !favoriteStore.loaded" class="center-state gallery-loading">
      <span class="state-orb"><Picture /></span>
      <strong>正在加载作品库…</strong>
    </div>

    <div v-else-if="favorites.length" class="image-grid">
      <article v-for="favorite in favorites" :key="favorite.id" class="image-tile">
        <button
          type="button"
          class="gallery-image-trigger mobile-image-trigger"
          aria-label="查看作品图片"
          @click="handleImageClick(favoriteImageUrls(), favoriteImageUrls().indexOf(favorite.imageUrl))"
          @touchstart.stop="startLongPress($event, () => openFavoriteAction(favorite))"
          @touchmove.stop="moveLongPress"
          @touchend.stop="finishLongPress"
          @touchcancel.stop="cancelLongPress(true)"
          @contextmenu.prevent.stop="openFavoriteAction(favorite)"
        >
          <img
            :src="galleryDisplayUrl(favorite)"
            alt="AI 作品"
            loading="lazy"
            @error="onGalleryThumbError(favorite)"
          >
        </button>
        <div class="image-info">
          <time>收藏于 {{ formatFavoriteTime(favorite.createdAt) }}</time>
          <p class="owner-label" v-if="authStore.isAdmin">归属：{{ ownerLabel(favorite) }}</p>
          <p>{{ favorite.drawPrompt || 'AI 生成图片' }}</p>
          <div v-if="favorite.referenceImages.length" class="reference-strip">
            <span class="reference-label">参考图</span>
            <div class="reference-list">
              <button
                v-for="(reference, refIndex) in favorite.referenceImages"
                :key="`${favorite.id}-reference-${refIndex}`"
                type="button"
                class="reference-thumb"
                aria-label="查看参考图片"
                @click.stop="openImageViewer(favorite.referenceImages.map((item) => item.fileUrl), refIndex)"
                @touchstart.stop="startLongPress($event, () => openReferenceAction(reference))"
                @touchmove.stop="moveLongPress"
                @touchend.stop="finishLongPress"
                @touchcancel.stop="cancelLongPress(true)"
                @contextmenu.prevent.stop="openReferenceAction(reference)"
              >
                <img
                  :src="referenceDisplayUrl(reference.fileUrl, reference)"
                  alt="参考图片"
                  loading="lazy"
                  @error="onReferenceThumbError(reference.fileUrl)"
                >
              </button>
            </div>
          </div>
          <div class="gallery-actions">
            <button type="button" @click="copyText(favorite.drawPrompt || '', '提示词已复制')">
              <CopyDocument /> 复制提示词
            </button>
            <button type="button" @click="downloadImage(favorite.imageUrl, favoriteFilename(favorite))">
              下载
            </button>
          </div>
          <div class="gallery-actions">
            <button
              v-if="favorite.sessionAvailable && favorite.sessionId != null"
              type="button"
              @click="openOriginalSession(favorite)"
            >
              <Link /> 回原会话
            </button>
            <button
              type="button"
              class="danger-action"
              :disabled="favoriteStore.isRecordPending(favorite.id)"
              @click="removeFavorite(favorite)"
            >
              <Delete /> 取消收藏
            </button>
          </div>
        </div>
      </article>
    </div>

    <div v-if="favoriteStore.hasMore" class="load-more">
      <button type="button" :disabled="favoriteStore.loading" @click="favoriteStore.loadMore()">
        {{ favoriteStore.loading ? '加载中…' : '加载更多' }}
      </button>
    </div>

    <div v-else-if="!favorites.length && favoriteStore.loaded" class="center-state gallery-empty">
      <span class="state-orb"><Picture /></span>
      <strong>作品库还是空的</strong>
      <p>在对话中对生成的图片点击「收藏」，就会出现在这里。</p>
      <button type="button" @click="goToCreate">去创作</button>
    </div>

    <el-drawer
      v-model="imageActionVisible"
      direction="btt"
      size="auto"
      class="h5-drawer action-drawer"
      :with-header="false"
    >
      <div class="drawer-title compact">
        <div>
          <strong>图片操作</strong>
          <span>长按图片即可再次打开此菜单</span>
        </div>
      </div>
      <div class="action-list">
        <button
          v-if="imageActionFavorite"
          type="button"
          class="danger-action"
          :disabled="favoriteStore.isRecordPending(imageActionFavorite.id)"
          @click="removeFavoriteFromAction"
        >
          <Delete /><span>取消收藏</span>
        </button>
        <button type="button" @click="downloadImageAction">
          <Download /><span>下载图片</span>
        </button>
        <button type="button" @click="imageActionVisible = false">
          <Close /><span>取消</span>
        </button>
      </div>
    </el-drawer>

    <Teleport to="body">
      <Transition name="save-helper-fade">
        <div
          v-if="saveHelperVisible"
          class="save-image-helper"
          role="dialog"
          aria-modal="true"
          aria-label="保存图片"
        >
          <div class="save-image-helper-backdrop" @click="closeSaveHelper"></div>
          <div class="save-image-helper-panel">
            <header>
              <strong>保存图片</strong>
              <button type="button" aria-label="关闭" @click="closeSaveHelper"><Close /></button>
            </header>
            <p class="save-image-helper-tip">
              部分移动端 / 内置浏览器不支持直接下载。请<strong>长按下方图片</strong>，在弹出菜单中选择“保存图片 / 存储到相册”。也可尝试“系统分享”。
            </p>
            <div class="save-image-helper-preview">
              <img :src="saveHelperUrl" :alt="saveHelperFilename" draggable="false">
            </div>
            <div class="save-image-helper-actions">
              <button type="button" class="primary" @click="shareFromHelper">系统分享</button>
              <button type="button" @click="copyText(saveHelperUrl)">复制图片链接</button>
              <button type="button" @click="closeSaveHelper">关闭</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <MobileImageViewer
      v-model:visible="imageViewerVisible"
      :images="imageViewerImages"
      :initial-index="imageViewerIndex"
    />
  </section>
</template>

<style scoped>
.gallery-panel {
  min-height: 100%;
  padding: 17px 14px 20px;
  box-sizing: border-box;
}

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  max-width: 820px;
  margin: 0 auto 14px;
}

.section-heading > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.section-heading span {
  color: #8591a9;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .12em;
}

.section-heading strong {
  color: #2e3b58;
  font-size: 20px;
}

.section-heading small {
  padding: 4px 9px;
  color: #5a6bd0;
  font-size: 11px;
  font-weight: 800;
  border-radius: 999px;
  background: #e9edff;
}

.image-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 11px;
  max-width: 820px;
  margin: 0 auto;
}

.image-tile {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e4e8f1;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 8px 20px rgba(47, 60, 101, .06);
}

.image-tile > .gallery-image-trigger {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  padding: 0;
  cursor: pointer;
  border: 0;
  background: #eef1f7;
}

.gallery-image-trigger img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.image-info {
  padding: 9px;
}

.image-tile time {
  color: #9ca5b7;
  font-size: 9px;
}

.image-tile p {
  display: -webkit-box;
  min-height: 34px;
  margin: 4px 0 8px;
  overflow: hidden;
  color: #5c6780;
  font-size: 11px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
}

/* Admin-only ownership line: compact, single line, before the prompt. */
.image-tile p.owner-label {
  min-height: 0;
  margin: 2px 0 4px;
  color: #7b5ea7;
  font-weight: 700;
  -webkit-line-clamp: 1;
  line-clamp: 1;
}

.gallery-actions {
  display: flex;
  gap: 6px;
  margin-top: 6px;
}

.gallery-actions button {
  display: inline-flex;
  flex: 1;
  min-width: 0;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 4px 6px;
  color: #65718e;
  font-size: 10px;
  cursor: pointer;
  border: 0;
  border-radius: 9px;
  background: #f1f3f9;
}

.gallery-actions button:disabled {
  opacity: .55;
}

.gallery-actions button.danger-action {
  color: #b95564;
  background: #fff1f3;
}

.gallery-actions button :deep(svg) {
  width: 12px;
  height: 12px;
}

.danger-action :deep(svg) {
  color: #cf6572;
}

/* Reference images captured in the saved-work snapshot. */
.reference-strip {
  margin-top: 6px;
}

.reference-label {
  display: block;
  margin-bottom: 4px;
  color: #9ca5b7;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: .06em;
}

.reference-list {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.reference-thumb {
  width: 42px;
  height: 42px;
  padding: 0;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid #e4e8f1;
  border-radius: 9px;
  background: #eef1f7;
}

.reference-thumb img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.load-more {
  display: flex;
  justify-content: center;
  margin: 16px auto 0;
}

.load-more button {
  min-height: 36px;
  padding: 0 18px;
  color: #5a6bd0;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 1px solid #dbe0ff;
  border-radius: 11px;
  background: #eef1ff;
}

.load-more button:disabled {
  opacity: .6;
}

.gallery-long-press-tip {
  display: inline-flex;
  flex: 1;
  min-width: 0;
  min-height: 32px;
  align-items: center;
  justify-content: center;
  color: #a0a8b8;
  font-size: 10px;
  border-radius: 9px;
  background: #f7f8fb;
}

.center-state {
  display: flex;
  min-height: 180px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #5e6c86;
  text-align: center;
}

.center-state p {
  margin: 0;
  color: #969fb1;
  font-size: 12px;
}

.state-orb {
  display: grid;
  width: 52px;
  height: 52px;
  margin-bottom: 3px;
  place-items: center;
  color: #6275db;
  font-size: 23px;
  border-radius: 17px;
  background: #e9edff;
}

.gallery-empty {
  height: calc(100% - 58px);
  min-height: 280px;
}

.gallery-loading {
  min-height: 280px;
}

.gallery-empty button {
  min-height: 38px;
  margin-top: 7px;
  padding: 0 16px;
  color: #fff;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 11px;
  background: var(--mobile-primary, #4f67e8);
}

.drawer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 15px;
  border-bottom: 1px solid #edf0f5;
  -webkit-user-select: none;
  user-select: none;
}

.drawer-title.compact {
  padding-bottom: 12px;
}

.drawer-title > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.drawer-title strong {
  color: #2f3c58;
  font-size: 16px;
}

.drawer-title span {
  color: #8b95a8;
  font-size: 11px;
}

.action-list {
  display: grid;
  gap: 7px;
  padding-top: 11px;
  -webkit-user-select: none;
  user-select: none;
}

.action-list button {
  display: flex;
  width: 100%;
  min-height: 48px;
  align-items: center;
  gap: 10px;
  padding: 0 13px;
  color: #53617c;
  font-size: 13px;
  font-weight: 700;
  text-align: left;
  cursor: pointer;
  border: 0;
  border-radius: 12px;
  background: #f5f7fb;
  -webkit-user-select: none;
  user-select: none;
  -webkit-touch-callout: none;
}

.action-list button span {
  -webkit-user-select: none;
  user-select: none;
}

.action-list button :deep(svg) {
  width: 18px;
  color: #6d7bd5;
}

:deep(.h5-drawer.el-drawer) {
  max-width: 760px;
  margin: 0 auto;
  border-radius: 24px 24px 0 0;
  background: #fff;
  box-shadow: 0 -18px 50px rgba(30, 42, 78, .2);
}

:deep(.h5-drawer .el-drawer__body) {
  padding: 18px 16px calc(18px + env(safe-area-inset-bottom));
  overflow-y: auto;
}

:deep(.action-drawer .el-drawer__body),
:deep(.action-drawer .el-drawer__body *) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}

:deep(.action-drawer .drawer-title),
:deep(.action-drawer .drawer-title strong),
:deep(.action-drawer .drawer-title span) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}

/* Feishu / restricted WebView save surface */
.save-image-helper {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: grid;
  place-items: end center;
  padding: 0;
}

.save-image-helper-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(12, 16, 28, .55);
}

.save-image-helper-panel {
  position: relative;
  z-index: 1;
  width: min(100%, 560px);
  max-height: min(88vh, 760px);
  overflow: auto;
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  border-radius: 22px 22px 0 0;
  background: #fff;
  box-shadow: 0 -16px 40px rgba(20, 30, 60, .22);
  -webkit-overflow-scrolling: touch;
}

.save-image-helper-panel header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}

.save-image-helper-panel header strong {
  color: #303d58;
  font-size: 17px;
}

.save-image-helper-panel header button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  color: #6b7690;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: #f1f3f8;
}

.save-image-helper-tip {
  margin: 0 0 12px;
  color: #6b7690;
  font-size: 12px;
  line-height: 1.55;
}

.save-image-helper-tip strong {
  color: #3f4f7d;
}

.save-image-helper-preview {
  display: grid;
  place-items: center;
  min-height: 180px;
  max-height: 52vh;
  overflow: auto;
  padding: 10px;
  border-radius: 14px;
  background: #f4f6fb;
}

.save-image-helper-preview img {
  display: block;
  max-width: 100%;
  max-height: 48vh;
  object-fit: contain;
  -webkit-user-select: auto;
  user-select: auto;
  -webkit-touch-callout: default;
  pointer-events: auto;
}

.save-image-helper-actions {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}

.save-image-helper-actions button {
  min-height: 44px;
  padding: 0 12px;
  color: #53617c;
  font-size: 13px;
  font-weight: 700;
  cursor: pointer;
  border: 0;
  border-radius: 12px;
  background: #f1f4f9;
}

.save-image-helper-actions button.primary {
  color: #fff;
  background: linear-gradient(140deg, #536bea, #7657d4);
}

.save-helper-fade-enter-active,
.save-helper-fade-leave-active {
  transition: opacity .18s ease;
}

.save-helper-fade-enter-from,
.save-helper-fade-leave-to {
  opacity: 0;
}

@media (min-width: 700px) {
  .image-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .action-list button,
  .action-list button span,
  .drawer-title,
  .drawer-title strong,
  .drawer-title span,
  .mobile-image-trigger,
  .mobile-image-trigger img {
    -webkit-user-select: none;
    user-select: none;
    -webkit-touch-callout: none;
  }
}
</style>
