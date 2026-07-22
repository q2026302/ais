/**
 * Mobile / Feishu-friendly image download helpers.
 *
 * Feishu and many in-app browsers ignore the HTML download attribute and reject
 * blob: URL downloads (often showing a system toast like "无法下载"). Prefer the
 * Web Share API when available, then anchor download, then open a long-press save surface.
 *
 * In restricted WebViews (Feishu/WeChat/etc.), never call a[download] — that is the
 * most common source of the "无法下载" system toast. Prefer openForSave (long-press
 * surface) when the caller provides one.
 */

export type DownloadImageMode = 'shared' | 'downloaded' | 'opened' | 'cancelled'

export interface DownloadImageResult {
  mode: DownloadImageMode
  message: string
}

export interface DownloadImageOptions {
  /** Prefer opening a long-press surface instead of a new tab when share/download fail. */
  openForSave?: (url: string, filename: string) => void
  /**
   * When true (default in restricted WebViews that pass openForSave), skip Web Share
   * and anchor download entirely and go straight to the save surface. Share often
   * surfaces the same "无法下载" toast inside Feishu.
   */
  preferSaveSurface?: boolean
}

function isAbortError(error: unknown): boolean {
  if (!error || typeof error !== 'object') return false
  const name = (error as { name?: string }).name
  return name === 'AbortError' || name === 'NotAllowedError'
}

export function isRestrictedWebView(): boolean {
  if (typeof navigator === 'undefined') return false
  const ua = navigator.userAgent || ''
  if (/Lark|Feishu|LarkLocale|MicroMessenger|QQ\//i.test(ua)) return true
  const win = window as Window & { tt?: unknown; h5sdk?: unknown; Lark?: unknown }
  return Boolean(win.tt || win.h5sdk || win.Lark)
}

export function isLikelyMobile(): boolean {
  if (typeof navigator === 'undefined') return false
  if (/Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent || '')) return true
  return navigator.maxTouchPoints > 1 && typeof window !== 'undefined'
    && window.matchMedia('(max-width: 900px)').matches
}

export function toAbsoluteUrl(url: string): string {
  if (!url) return url
  try {
    return new URL(url, typeof window !== 'undefined' ? window.location.href : undefined).href
  } catch {
    return url
  }
}

function guessMimeType(filename: string): string {
  const lower = filename.toLowerCase()
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg'
  if (lower.endsWith('.webp')) return 'image/webp'
  if (lower.endsWith('.gif')) return 'image/gif'
  if (lower.endsWith('.bmp')) return 'image/bmp'
  return 'image/png'
}

function canShareFiles(file: File): boolean {
  if (typeof navigator === 'undefined' || typeof navigator.share !== 'function') return false
  if (typeof navigator.canShare !== 'function') {
    // Some WebViews expose share() without canShare(); try files anyway later.
    return true
  }
  try {
    return navigator.canShare({ files: [file] })
  } catch {
    return false
  }
}

function triggerAnchorDownload(url: string, filename: string) {
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.rel = 'noopener'
  link.target = '_self'
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

async function fetchImageBlob(url: string): Promise<Blob> {
  const absoluteUrl = toAbsoluteUrl(url)
  const response = await fetch(absoluteUrl, {
    method: 'GET',
    credentials: 'same-origin',
    cache: 'default',
  })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  const blob = await response.blob()
  if (!blob || blob.size === 0) {
    throw new Error('empty image')
  }
  return blob
}

async function shareImageFile(file: File): Promise<boolean> {
  if (!canShareFiles(file)) return false
  try {
    await navigator.share({
      files: [file],
      title: file.name,
      text: file.name,
    })
    return true
  } catch (error) {
    if (isAbortError(error)) {
      const cancelled = new Error('用户取消分享')
      cancelled.name = 'AbortError'
      throw cancelled
    }
    return false
  }
}

function openImageUrl(url: string): boolean {
  try {
    const opened = window.open(url, '_blank', 'noopener,noreferrer')
    if (opened) return true
  } catch {
    // ignore
  }
  try {
    // Last-resort navigation keeps the image address usable for OS save menus.
    const frame = document.createElement('iframe')
    frame.style.display = 'none'
    frame.src = url
    document.body.appendChild(frame)
    window.setTimeout(() => frame.remove(), 60_000)
    return true
  } catch {
    return false
  }
}

/**
 * Download / share an image with progressive fallbacks for Feishu and mobile WebViews.
 */
export async function downloadImage(
  url: string,
  filename = 'ai-image.png',
  options: DownloadImageOptions = {},
): Promise<DownloadImageResult> {
  if (!url) {
    throw new Error('图片地址为空')
  }

  const absoluteUrl = toAbsoluteUrl(url)
  const safeName = filename.trim() || 'ai-image.png'
  const restricted = isRestrictedWebView()
  const mobile = isLikelyMobile()
  const preferSaveSurface = options.preferSaveSurface
    ?? (restricted && typeof options.openForSave === 'function')

  // Feishu / WeChat: a[download] and many share paths surface a system "无法下载" toast.
  // When the caller provides a long-press save surface, go there immediately.
  if (preferSaveSurface && options.openForSave) {
    options.openForSave(absoluteUrl, safeName)
    return {
      mode: 'opened',
      message: '当前环境不支持直接下载，请长按图片保存到相册',
    }
  }

  let blob: Blob | null = null
  try {
    blob = await fetchImageBlob(absoluteUrl)
  } catch {
    blob = null
  }

  if (blob) {
    const type = blob.type && blob.type.startsWith('image/') ? blob.type : guessMimeType(safeName)
    const file = new File([blob], safeName, { type })

    // Mobile / restricted: Web Share is the next-best "save to album" path when no save surface.
    if (restricted || mobile) {
      try {
        if (await shareImageFile(file)) {
          return {
            mode: 'shared',
            message: '请在系统面板中选择“存储到相册”或转发保存',
          }
        }
      } catch (error) {
        if (error instanceof Error && error.name === 'AbortError') {
          return { mode: 'cancelled', message: '已取消' }
        }
      }
    }

    // Standard browsers only: blob + download attribute (delay revoke so download can start).
    // Never do this in Feishu/WeChat — it produces the system toast "无法下载".
    if (!restricted) {
      const objectUrl = URL.createObjectURL(blob)
      try {
        triggerAnchorDownload(objectUrl, safeName)
        window.setTimeout(() => URL.revokeObjectURL(objectUrl), 60_000)
        return { mode: 'downloaded', message: '图片下载已开始' }
      } catch {
        URL.revokeObjectURL(objectUrl)
      }
    }
  }

  // Restricted WebViews: expose a long-press surface instead of a[download].
  if (options.openForSave) {
    options.openForSave(absoluteUrl, safeName)
    return {
      mode: 'opened',
      message: restricted
        ? '当前环境不支持直接下载，请长按图片保存到相册'
        : '请长按图片保存，或使用系统分享',
    }
  }

  if (openImageUrl(absoluteUrl)) {
    return {
      mode: 'opened',
      message: restricted
        ? '已打开原图，请长按图片保存到相册'
        : '已打开图片，如未自动下载请长按保存',
    }
  }

  // Absolute last resort for normal browsers only.
  if (!restricted) {
    try {
      triggerAnchorDownload(absoluteUrl, safeName)
      return {
        mode: 'downloaded',
        message: '已尝试开始下载；如无反应请长按图片保存',
      }
    } catch {
      // fall through
    }
  }

  throw new Error('当前环境无法下载图片，请长按图片保存到相册')
}

/**
 * Attempt Web Share only (used by the in-page save helper's "系统分享" button).
 * Returns false when share is unavailable or fails without user cancel.
 */
export async function shareImage(
  url: string,
  filename = 'ai-image.png',
): Promise<DownloadImageResult> {
  if (!url) throw new Error('图片地址为空')
  const absoluteUrl = toAbsoluteUrl(url)
  const safeName = filename.trim() || 'ai-image.png'
  let blob: Blob
  try {
    blob = await fetchImageBlob(absoluteUrl)
  } catch {
    throw new Error('图片加载失败，请长按上方图片保存')
  }
  const type = blob.type && blob.type.startsWith('image/') ? blob.type : guessMimeType(safeName)
  const file = new File([blob], safeName, { type })
  try {
    if (await shareImageFile(file)) {
      return {
        mode: 'shared',
        message: '请在系统面板中选择“存储到相册”或转发保存',
      }
    }
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') {
      return { mode: 'cancelled', message: '已取消' }
    }
  }
  throw new Error('系统分享不可用，请长按上方图片保存到相册')
}
