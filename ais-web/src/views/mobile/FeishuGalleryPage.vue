<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, CopyDocument, Download, Picture } from '@element-plus/icons-vue'
import MobileImageViewer from '@/components/MobileImageViewer.vue'
import { useImageActions } from '@/composables/useImageActions'
import { useLongPress } from '@/composables/useLongPress'
import { useSessionStore } from '@/stores/session'
import type { Message } from '@/types'
import { formatTimeHm } from '@/utils/dateTime'
import { getThumbnailUrl } from '@/utils/imageUrl'
import { mobileWorkspacePath } from '@/utils/mobileWorkspace'

defineOptions({
  name: 'FeishuGalleryPage',
})
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

const store = useSessionStore()
const route = useRoute()
const router = useRouter()

const mobileSource = computed(() => route.meta.mobileEntry ?? 'mobile')

const {
  imageActionVisible,
  saveHelperVisible,
  saveHelperUrl,
  saveHelperFilename,
  openImageAction: openImageActionBase,
  downloadImageAction,
  shareFromHelper,
  closeSaveHelper,
} = useImageActions()
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

const {
  longPressTriggered,
  startLongPress,
  moveLongPress,
  cancelLongPress,
  finishLongPress,
  clearResidualSelection,
  setSelectionSuppressed,
} = useLongPress()
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

const imageViewerVisible = ref(false)
const imageViewerImages = ref<string[]>([])
const imageViewerIndex = ref(0)
const galleryThumbFailedIds = ref<Set<number>>(new Set())

/** Generated images in the active session (messages that have an imageUrl). */
const generatedImages = computed(() => store.messages.filter((message) => Boolean(message.imageUrl)))

function onGalleryThumbError(id: number) {
  galleryThumbFailedIds.value = new Set(galleryThumbFailedIds.value).add(id)
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

function galleryDisplayUrl(message: Message) {
  if (!message.imageUrl) return ''
  return galleryThumbFailedIds.value.has(message.id) ? message.imageUrl : getThumbnailUrl(message.id, 'small')
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

function formatTime(value: string) {
  return formatTimeHm(value, '')
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

function openImageViewer(images: string[], index = 0) {
  const validImages = images.filter(Boolean)
  if (!validImages.length) return
  imageViewerImages.value = validImages
  imageViewerIndex.value = Math.max(0, Math.min(index, validImages.length - 1))
  imageViewerVisible.value = true
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

function handleImageClick(images: string[], index = 0) {
  if (longPressTriggered.value) {
    longPressTriggered.value = false
    return
  }
  openImageViewer(images, index)
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

/** Match FeishuH5View openImageAction: clear residual selection after opening. */
function openImageAction(url: string, filename = 'ai-image.png') {
  openImageActionBase(url, filename)
  clearResidualSelection()
  window.setTimeout(() => {
    clearResidualSelection()
    setSelectionSuppressed(false)
  }, 320)
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

  const base = mobileWorkspacePath(mobileSource.value)
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
  if (store.activeSessionId != null) {
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
    await router.push(`${base}/chat/${store.activeSessionId}`)
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
    return
  }
  await router.push(`${base}/sessions`)
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
watch(() => store.activeSessionId, () => {
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
</script>

<template>
  <section class="gallery-panel">
    <div class="section-heading">
      <div>
        <span>作品库</span>
        <strong>我的 AI 作品</strong>
      </div>
      <small>{{ generatedImages.length }} 张</small>
    </div>

    <div v-if="generatedImages.length" class="image-grid">
      <article v-for="message in generatedImages" :key="message.id" class="image-tile">
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
            <button type="button" @click="copyText(message.drawPrompt || message.content || '')">
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
      <button type="button" @click="goToCreate">开始创作</button>
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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.section-heading {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  max-width: 820px;
  margin: 0 auto 14px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.section-heading > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.section-heading span {
  color: #8591a9;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: .12em;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.section-heading strong {
  color: #2e3b58;
  font-size: 20px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.section-heading small {
  padding: 4px 9px;
  color: #5a6bd0;
  font-size: 11px;
  font-weight: 800;
  border-radius: 999px;
  background: #e9edff;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.image-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 11px;
  max-width: 820px;
  margin: 0 auto;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.image-tile {
  min-width: 0;
  overflow: hidden;
  border: 1px solid #e4e8f1;
  border-radius: 16px;
  background: #fff;
  box-shadow: 0 8px 20px rgba(47, 60, 101, .06);
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.image-tile > .gallery-image-trigger {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  padding: 0;
  cursor: pointer;
  border: 0;
  background: #eef1f7;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.gallery-image-trigger img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.image-info {
  padding: 9px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.image-tile time {
  color: #9ca5b7;
  font-size: 9px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.gallery-actions {
  display: flex;
  gap: 6px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.gallery-actions button :deep(svg) {
  width: 12px;
  height: 12px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.center-state p {
  margin: 0;
  color: #969fb1;
  font-size: 12px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.gallery-empty {
  height: calc(100% - 58px);
  min-height: 280px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.drawer-title.compact {
  padding-bottom: 12px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.drawer-title > div {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.drawer-title strong {
  color: #2f3c58;
  font-size: 16px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.drawer-title span {
  color: #8b95a8;
  font-size: 11px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.action-list {
  display: grid;
  gap: 7px;
  padding-top: 11px;
  -webkit-user-select: none;
  user-select: none;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.action-list button span {
  -webkit-user-select: none;
  user-select: none;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.action-list button :deep(svg) {
  width: 18px;
  color: #6d7bd5;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

:deep(.h5-drawer.el-drawer) {
  max-width: 760px;
  margin: 0 auto;
  border-radius: 24px 24px 0 0;
  background: #fff;
  box-shadow: 0 -18px 50px rgba(30, 42, 78, .2);
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

:deep(.h5-drawer .el-drawer__body) {
  padding: 18px 16px calc(18px + env(safe-area-inset-bottom));
  overflow-y: auto;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

:deep(.action-drawer .el-drawer__body),
:deep(.action-drawer .el-drawer__body *) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

:deep(.action-drawer .drawer-title),
:deep(.action-drawer .drawer-title strong),
:deep(.action-drawer .drawer-title span) {
  -webkit-user-select: none !important;
  user-select: none !important;
  -webkit-touch-callout: none !important;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

/* Feishu / restricted WebView save surface */
.save-image-helper {
  position: fixed;
  inset: 0;
  z-index: 3200;
  display: grid;
  place-items: end center;
  padding: 0;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-backdrop {
  position: absolute;
  inset: 0;
  background: rgba(12, 16, 28, .55);
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-panel header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-panel header strong {
  color: #303d58;
  font-size: 17px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-tip {
  margin: 0 0 12px;
  color: #6b7690;
  font-size: 12px;
  line-height: 1.55;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-tip strong {
  color: #3f4f7d;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-actions {
  display: grid;
  gap: 8px;
  margin-top: 14px;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-image-helper-actions button.primary {
  color: #fff;
  background: linear-gradient(140deg, #536bea, #7657d4);
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-helper-fade-enter-active,
.save-helper-fade-leave-active {
  transition: opacity .18s ease;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

.save-helper-fade-enter-from,
.save-helper-fade-leave-to {
  opacity: 0;
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

@media (min-width: 700px) {
  .image-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})

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
  imageViewerVisible.value = false
  imageViewerImages.value = []
  imageViewerIndex.value = 0
})
</style>
