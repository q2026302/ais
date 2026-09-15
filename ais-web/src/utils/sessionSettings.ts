import type { DrawSettings, SessionSettings } from '@/types'

/**
 * 绘画参数注册表默认值的**前端镜像**。
 *
 * 唯一真源是后端 `SessionSettingsRegistry`；会话读接口回显的 `settings` 已经补齐了
 * 默认值，所以这里的常量只用于两种情况：
 *  1. 旧会话/旧后端响应里没有 `settings` 字段时的兜底；
 *  2. 请求失败后把本地状态恢复到已知默认值。
 * 新增参数时后端注册表加一项即可，前端只有在需要展示/兜底该参数时才需要动这里。
 */
export const DRAW_SETTINGS_DEFAULTS: Readonly<DrawSettings> = Object.freeze({
  size: '1024x1024',
  quality: 'auto',
  format: 'png',
})

/** 读取会话设置里的绘画参数，缺失时逐项回退注册表默认值。 */
export function resolveDrawSettings(settings: SessionSettings | null | undefined): DrawSettings {
  const draw = settings?.draw
  return {
    size: draw?.size || DRAW_SETTINGS_DEFAULTS.size,
    quality: draw?.quality || DRAW_SETTINGS_DEFAULTS.quality,
    format: draw?.format || DRAW_SETTINGS_DEFAULTS.format,
  }
}

/** 会话未加载时的默认设置（与后端注册表一致）。 */
export function defaultSessionSettings(): SessionSettings {
  return { draw: { ...DRAW_SETTINGS_DEFAULTS } }
}
