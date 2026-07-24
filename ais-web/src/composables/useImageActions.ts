import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import {
  downloadImage as downloadImageAsset,
  shareImage as shareImageAsset,
} from '@/utils/downloadImage'
import { useSessionStore } from '@/stores/session'
import type { Message } from '@/types'

/**
 * Image-action helpers extracted from FeishuH5View.vue.
 *
 * Provides long-press image action drawer, download with fallback,
 * and a long-press save-helper overlay for restricted WebViews.
 */
export function useImageActions() {
  const store = useSessionStore()
  const imageActionVisible = ref(false)
  const imageActionUrl = ref('')
  const imageActionFilename = ref('ai-image.png')
  const saveHelperVisible = ref(false)
  const saveHelperUrl = ref('')
  const saveHelperFilename = ref('ai-image.png')

  function openImageAction(url: string, filename = 'ai-image.png') {
    imageActionUrl.value = url
    imageActionFilename.value = filename
    imageActionVisible.value = true
  }

  async function downloadImageAction() {
    const url = imageActionUrl.value
    imageActionVisible.value = false
    if (url) await downloadImage(url, imageActionFilename.value)
  }

  function openSaveHelper(url: string, filename = 'ai-image.png') {
    saveHelperUrl.value = url
    saveHelperFilename.value = filename
    saveHelperVisible.value = true
  }

  function closeSaveHelper() {
    saveHelperVisible.value = false
  }

  async function downloadImage(url: string, filename = 'ai-image.png') {
    if (!url) return
    try {
      const result = await downloadImageAsset(url, filename, {
        // In Feishu/WeChat, openForSave is preferred automatically (avoids "无法下载").
        openForSave: (absoluteUrl, safeName) => openSaveHelper(absoluteUrl, safeName),
      })
      if (result.mode === 'cancelled') return
      if (result.mode === 'opened') ElMessage.info(result.message)
      else ElMessage.success(result.message)
    } catch (error: any) {
      ElMessage.error(error?.message || '下载失败，请长按图片保存到相册')
      // Still open the long-press surface so users are not stuck.
      openSaveHelper(url, filename)
    }
  }

  async function shareFromHelper() {
    if (!saveHelperUrl.value) return
    try {
      const result = await shareImageAsset(saveHelperUrl.value, saveHelperFilename.value)
      if (result.mode === 'cancelled') return
      ElMessage.success(result.message)
    } catch (error: any) {
      ElMessage.warning(error?.message || '系统分享不可用，请长按上方图片保存')
    }
  }

  return {
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
    openSaveHelper,
    closeSaveHelper,
  }
}
