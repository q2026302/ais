import type { DrawSettings, ModelProvider } from '@/types'

/**
 * 绘画参数选项集的**唯一推导来源**（PC / 移动 PWA / H5 / 生成面板共用）。
 *
 * 关键约束（对应统一模型选择需求）：
 *  - 选项集必须由**实际生效的绘画模型**推导（会话默认 → 用户默认 → 系统启用项），
 *    调用方负责传入已经解析好的那个 provider，不能再看「会话是否显式设置」。
 *  - 这里只负责选项集本身，**绝不改写任何会话存储值**。校正只影响可选范围与
 *    校验提示；将要提交给后端的值必须原样保留。
 */

/** 图片适配器类型；规则与后端模型适配器保持一致。 */
export function imageAdapterOf(provider: ModelProvider | null | undefined): string {
  const configured = provider?.adapterType?.toUpperCase()
  if (configured && configured !== 'AUTO') return configured
  const model = provider?.modelName?.toLowerCase() || ''
  const providerId = provider?.providerId?.toLowerCase() || ''
  if (providerId === 'grsai') return 'GRS_AI'
  return model.includes('gemini') ? 'GEMINI_IMAGE' : 'OPENAI_IMAGE'
}

export interface DrawOptionSets {
  size: string[]
  quality: string[]
  format: string[]
  usesRatioOptions: boolean
  isGptImageModel: boolean
}

/**
 * 按生效绘画模型推导的**基准**选项集（不含会话存储值的注入）。
 * 校验提示需要拿存储值与它比较，所以单独保留。
 */
export function drawOptionSets(provider: ModelProvider | null | undefined): DrawOptionSets {
  const adapter = imageAdapterOf(provider)
  const model = provider?.modelName?.toLowerCase() || ''
  const usesRatioOptions = adapter === 'GEMINI_IMAGE'
    || (adapter === 'GRS_AI' && model.includes('nano-banana'))
  const isGptImageModel = (adapter === 'OPENAI_IMAGE' || adapter === 'GRS_AI')
    && (model.includes('gpt-image') || model.includes('gpt image'))
  return {
    usesRatioOptions,
    isGptImageModel,
    size: usesRatioOptions
      ? ['1:1', '16:9', '9:16', '4:3', '3:4']
      : isGptImageModel
        ? ['1024x1024', '1536x1024', '1024x1536', 'auto']
        : ['1024x1024', '512x512', '768x768', '1024x1792', '1792x1024'],
    quality: usesRatioOptions
      ? ['1K', '2K', '4K']
      : isGptImageModel ? ['auto', 'low', 'medium', 'high'] : ['standard', 'hd'],
    format: usesRatioOptions ? ['png'] : ['png', 'jpeg', 'webp'],
  }
}

/**
 * 下拉框展示用选项：把当前存储值并入基准选项集（缺失时置顶），
 * 保证「显示 = 会话里存的值」而不是被静默替换。
 */
export function withStoredValue(options: string[], value: string | null | undefined): string[] {
  const current = (value || '').trim()
  if (!current || options.includes(current)) return options
  return [current, ...options]
}

/** 逐项给出「存储值超出当前模型基准选项集」的提示；无问题返回空数组。 */
export function drawOptionWarnings(
  values: Pick<DrawSettings, 'size' | 'quality' | 'format'>,
  sets: DrawOptionSets,
): string[] {
  const warnings: string[] = []
  const size = (values.size || '').trim()
  const quality = (values.quality || '').trim()
  const format = (values.format || '').trim()
  if (size && !sets.size.includes(size)) {
    warnings.push(`尺寸 / 比例「${size}」不在当前绘画模型的可选范围内，将以该值提交。`)
  }
  if (quality && !sets.quality.includes(quality)) {
    warnings.push(`质量「${quality}」不在当前绘画模型的可选范围内，将以该值提交。`)
  }
  if (format && !sets.format.includes(format)) {
    warnings.push(`格式「${format}」不在当前绘画模型的可选范围内，将以该值提交。`)
  }
  return warnings
}
