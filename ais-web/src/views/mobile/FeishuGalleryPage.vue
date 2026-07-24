<script setup lang="ts">
import { computed, ref } from 'vue'
import { useSessionStore } from '@/stores/session'
import { useRouter } from 'vue-router'
import { CopyDocument, Close, Picture } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { getThumbnailUrl } from '@/utils/imageUrl'
import { formatTimeHm } from '@/utils/dateTime'
import { useLongPress } from '@/composables/useLongPress'
import { useImageActions } from '@/composables/useImageActions'

defineOptions({
  name: 'FeishuGalleryPage',
})

const store = useSessionStore()
const router = useRouter()

const { startLongPress, moveLongPress, cancelLongPress, finishLongPress, longPressTriggered } = useLongPress()
const {
  imageActionVisible,
  imageActionUrl,
  imageActionFilename,
  saveHelperVisible,
  saveHelperUrl,
  saveHelperFilename,
  openImageAction,
  downloadImageAction,
  downloadImage,
  shareFromHelper,
  closeSaveHelper,
} = useImageActions()

const galleryThumbFailedIds = ref<Set<number>>(new Set())

function onGalleryThumbError(id: number) {
  galleryThumbFailedIds.value = new Set(galleryThumbFailedIds.value).add(id)
}

function galleryDisplayUrl(message: { id: number; imageUrl?: string | null }) {
  if (!message.imageUrl) return ''
  return galleryThumbFailedIds.value.has(message.id) ? message.imageUrl : getThumbnailUrl(message.id)
}

const generatedImages = computed(() => store.messages.filter((message) => Boolean(message.imageUrl)))

// Inline image viewer
const imageViewerVisible = ref(false)
const imageViewerImages = ref<string[]>([])
const imageViewerIndex = ref(0)

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

function formatTime(value: string) {
  return formatTimeHm(value, '')
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
</script>

<template>
  <main class="gallery-page">
    <div class="gallery-panel">
      <div class="section-heading">
        <div>
          <span>作品库</span>
          <strong>我的 AI 作品</strong>
        </div>
        <small>{{ generatedImages.length }} 张</small>
      </div>

      <div v-if="generatedImages.length" class="image-grid">
        <article
          v-for="message in generatedImages"
          :key="message.id"
          class="image-tile"
        >
          <button
            type="button"
            class="gallery-image-trigger mobile-image-trigger"
            aria-label="查看作品图片"
            @click="handleImageClick(generatedImages.map((item) => item.imageUrl || ''), generatedImages.findIndex((item) => item.id === message.id))"
            @touchstart.stop="startLongPress($event, () => openImageAction(message.imageUrl || '', `ai-image-${message.id}.${message.drawFormat || 'png'}`))"
            @touchmove.stop="moveLongPress"
            @touchend.stop="finishLongPress"
            @touchcancel.stop="cancelLongPress(true)"
            @contextmenu.prevent.stop="openImageAction(message.imageUrl || '', `ai-image-${message.id}.${message.drawFormat || 'png'}`)"
          >
            <img
              :src="galleryDisplayUrl(message)"
              alt="AI 作品"
              loading="lazy"
              @error="onGalleryThumbError(message.id)"
            >
          </button>
          <div class="image-info">
            <time>{{ formatTime(message.createdAt) }}</time>
            <p>{{ message.drawPrompt || message.content || 'AI 生成图片' }}</p>
            <div class="gallery-actions">
              <button
                type="button"
                @click="copyText(message.drawPrompt || message.content || '')"
              >
                <CopyDocument /> 复制提示词
              </button>
              <span class="gallery-long-press-tip">长按图片操作</span>
            </div>
          </div>
        </article>
      </div>

      <div v-else class="center-state gallery-empty">
        <span class="state-orb"><Picture /></span>
        <strong>还没有生成作品</strong>
        <p>返回「创作」生成第一张图片。</p>
      </div>
    </div>

    <!-- Image viewer overlay -->
    <Teleport to="body">
      <Transition name="viewer-fade">
        <div
          v-if="imageViewerVisible"
          class="mobile-image-viewer"
          role="dialog"
          aria-modal="true"
          aria-label="图片查看器"
          @click="imageViewerVisible = false"
        >
          <button
            class="viewer-close"
            type="button"
            aria-label="关闭查看器"
            @click="imageViewerVisible = false"
          >
            <Close />
          </button>
          <div class="viewer-nav" @click.stop>
            <button
              v-if="imageViewerImages.length > 1"
              class="viewer-nav-btn"
              type="button"
              :disabled="imageViewerIndex <= 0"
              @click="imageViewerIndex--"
            >
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="15 18 9 12 15 6"/></svg>
            </button>
            <div class="viewer-counter">{{ imageViewerIndex + 1 }} / {{ imageViewerImages.length }}</div>
            <button
              v-if="imageViewerImages.length > 1"
              class="viewer-nav-btn"
              type="button"
              :disabled="imageViewerIndex >= imageViewerImages.length - 1"
              @click="imageViewerIndex++"
            >
              <svg width="28" height="28" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"/></svg>
            </button>
          </div>
          <img
            :src="imageViewerImages[imageViewerIndex]"
            alt="查看图片"
            class="viewer-image"
            @click.stop
          >
        </div>
      </Transition>
    </Teleport>

    <!-- Image action drawer -->
    <Teleport to="body">
      <Transition name="action-drawer-fade">
        <div
          v-if="imageActionVisible"
          class="action-drawer-overlay"
          role="dialog"
          aria-modal="true"
          aria-label="图片操作"
        >
          <div class="action-drawer-backdrop" @click="imageActionVisible = false"></div>
          <div class="action-drawer-panel">
            <div class="drawer-title compact">
              <div>
                <strong>图片操作</strong>
                <span>长按图片即可再次打开此菜单</span>
              </div>
            </div>
            <div class="action-list">
              <button type="button" @click="downloadImageAction">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                <span>下载图片</span>
              </button>
              <button type="button" @click="imageActionVisible = false">
                <Close /><span>取消</span>
              </button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- Save helper -->
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
            <p class="save-image-helper-tip">部分移动端 / 内置浏览器不支持直接下载。请<strong>长按下方图片</strong>，在弹出菜单中选择"保存图片 / 存储到相册"。也可尝试"系统分享"。</p>
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
  </main>
</template>

<style scoped>
.gallery-page {
  --mobile-primary: #4f67e8;
  --mobile-text: #24314d;
  --mobile-muted: #7d899f;

  position: fixed;
  top: 0;
  left: 0;
  z-index: 1;
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  height: 100dvh;
  overflow: hidden;
  color: var(--mobile-text);
  background:
    radial-gradient(circle at 95% -5%, rgba(106, 90, 238, .12), transparent 24rem),
    linear-gradient(180deg, #f7f9fd 0%, #f2f5fa 100%);
}

.gallery-panel {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  -webkit-overflow-scrolling: touch;
  padding: 17px 14px 20px;
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
  background: #eef1f7;
}

.gallery-image-trigger {
  display: block;
  padding: 0;
  cursor: pointer;
  border: 0;
  background: transparent;
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
}

.gallery-actions {
  display: flex;
  gap: 6px;
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
  height: calc(100% - 58px);
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

/* Image viewer */
.mobile-image-viewer {
  position: fixed;
  z-index: 2000;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  background: rgba(0, 0, 0, .92);
}

.viewer-close {
  position: absolute;
  top: calc(12px + env(safe-area-inset-top));
  right: 12px;
  z-index: 2;
  display: grid;
  width: 38px;
  height: 38px;
  padding: 0;
  place-items: center;
  color: #fff;
  cursor: pointer;
  border: 0;
  border-radius: 10px;
  background: rgba(255, 255, 255, .15);
}

.viewer-nav {
  position: absolute;
  bottom: calc(20px + env(safe-area-inset-bottom));
  left: 50%;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 16px;
  transform: translateX(-50%);
}

.viewer-nav-btn {
  display: grid;
  width: 40px;
  height: 40px;
  padding: 0;
  place-items: center;
  color: #fff;
  cursor: pointer;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, .15);
}

.viewer-nav-btn:disabled {
  opacity: .3;
  cursor: not-allowed;
}

.viewer-counter {
  color: #fff;
  font-size: 14px;
  font-weight: 600;
}

.viewer-image {
  display: block;
  max-width: 94%;
  max-height: 80vh;
  object-fit: contain;
  border-radius: 8px;
  user-select: none;
  -webkit-user-drag: none;
}

/* Action drawer */
.action-drawer-overlay {
  position: fixed;
  z-index: 2100;
  inset: 0;
  display: grid;
  place-items: end center;
}

.action-drawer-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(12, 16, 28, .45);
}

.action-drawer-panel {
  position: relative;
  z-index: 1;
  width: min(100%, 560px);
  max-height: min(70vh, 600px);
  overflow: auto;
  padding: 16px 16px calc(16px + env(safe-area-inset-bottom));
  border-radius: 22px 22px 0 0;
  background: #fff;
  box-shadow: 0 -16px 40px rgba(20, 30, 60, .2);
  -webkit-overflow-scrolling: touch;
}

.drawer-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 2px 2px 15px;
  border-bottom: 1px solid #edf0f5;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

.drawer-title > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.drawer-title strong {
  color: #303d58;
  font-size: 17px;
  user-select: none;
  -webkit-user-select: none;
}

.drawer-title span {
  color: #929bad;
  font-size: 11px;
  user-select: none;
  -webkit-user-select: none;
}

.drawer-title.compact {
  flex: 0 0 auto;
  padding-bottom: 13px;
}

.action-list {
  display: grid;
  gap: 7px;
  padding-top: 11px;
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
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
  user-select: none;
  -webkit-user-select: none;
  -webkit-touch-callout: none;
}

.action-list button span {
  user-select: none;
  -webkit-user-select: none;
}

.action-list button svg {
  flex: 0 0 auto;
  color: #6d7bd5;
}

/* Save helper */
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
  box-shadow: 0 6px 14px rgba(83, 96, 229, .22);
}

@media (min-width: 700px) {
  .image-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

.viewer-fade-enter-active,
.viewer-fade-leave-active {
  transition: opacity .2s ease;
}

.viewer-fade-enter-from,
.viewer-fade-leave-to {
  opacity: 0;
}

.action-drawer-fade-enter-active,
.action-drawer-fade-leave-active {
  transition: opacity .18s ease;
}

.action-drawer-fade-enter-from,
.action-drawer-fade-leave-to {
  opacity: 0;
}

.save-helper-fade-enter-active,
.save-helper-fade-leave-active {
  transition: opacity .18s ease;
}

.save-helper-fade-enter-active .save-image-helper-panel,
.save-helper-fade-leave-active .save-image-helper-panel {
  transition: transform .2s ease;
}

.save-helper-fade-enter-from,
.save-helper-fade-leave-to {
  opacity: 0;
}

.save-helper-fade-enter-from .save-image-helper-panel,
.save-helper-fade-leave-to .save-image-helper-panel {
  transform: translateY(18px);
}
</style>
