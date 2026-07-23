import { ElNotification } from 'element-plus'
import { registerSW } from 'virtual:pwa-register'

/** Registers the production worker and lets the creator choose when to refresh. */
export function registerPwaUpdates() {
  if (!import.meta.env.PROD || !('serviceWorker' in navigator)) return

  let refreshPromptVisible = false
  const updateServiceWorker = registerSW({
    immediate: true,
    onNeedRefresh() {
      if (refreshPromptVisible) return
      refreshPromptVisible = true

      const notification = ElNotification({
        title: '发现新版本',
        message: '点击刷新使用新版',
        type: 'info',
        duration: 0,
        position: 'bottom-right',
        onClick: () => {
          notification.close()
          void updateServiceWorker(true)
        },
        onClose: () => {
          refreshPromptVisible = false
        },
      })
    },
  })
}
