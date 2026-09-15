import type { Message, ModelProvider } from '@/types'

/**
 * Shown when a message carries no usable record of the model that actually
 * produced it. Historical rows written before model recording existed, or rows
 * whose provider has since been deleted without a name snapshot, land here.
 *
 * Deliberately NOT the currently selected / session-default model: a history
 * label must never drift when the user switches models later.
 */
export const MODEL_NOT_RECORDED = '未记录'

/** Human-readable label for one provider row: `名称 / 模型名`. */
export function providerLabel(provider: ModelProvider | null | undefined, fallback = ''): string {
  if (!provider) return fallback
  const name = provider.name || provider.providerId
  return provider.modelName ? `${name} / ${provider.modelName}` : name
}

/** Resolve a provider row by id without ever inventing a fallback. */
export function findProvider(
  providers: ModelProvider[] | null | undefined,
  id: number | null | undefined,
): ModelProvider | null {
  if (id == null || !providers) return null
  return providers.find((provider) => provider.id === id) || null
}

/** 临时切换（仅本次）时的可见提示，三端共用同一文案。 */
export function temporaryUsageHint(label: string): string {
  return `本次使用：${label}（仅本次）`
}

/** 未做临时切换、跟随会话默认时的可见提示，三端共用同一文案。 */
export function sessionDefaultUsageHint(label: string): string {
  return `本次使用：会话默认 ${label}`
}

/** 「本次使用」提示：临时覆盖优先，否则显示会话默认。 */
export function usageHint(options: { temporaryLabel?: string | null; defaultLabel: string }): string {
  const temporaryLabel = (options.temporaryLabel || '').trim()
  return temporaryLabel
    ? temporaryUsageHint(temporaryLabel)
    : sessionDefaultUsageHint(options.defaultLabel)
}

export interface MessageModelContext {
  chatProviders?: ModelProvider[]
  imageProviders?: ModelProvider[]
}

function isDrawMessage(message: Message): boolean {
  return message.messageType === 'DRAW_RESPONSE' || message.messageType === 'DRAW_REQUEST'
}

/**
 * The model name a message must display, based ONLY on what was recorded when
 * the message was produced:
 *
 * 1. the provider name snapshot persisted alongside the message, else
 * 2. the provider row resolved from the recorded provider id, else
 * 3. {@link MODEL_NOT_RECORDED}.
 *
 * It never falls back to the current selection, so changing the session default
 * cannot rewrite history.
 */
export function messageModelName(message: Message, context: MessageModelContext = {}): string {
  if (isDrawMessage(message)) {
    const snapshot = (message.drawProviderName || '').trim()
    if (snapshot) return snapshot
    const provider = findProvider(context.imageProviders, message.drawProviderId)
    return provider ? providerLabel(provider) : MODEL_NOT_RECORDED
  }
  const snapshot = (message.chatProviderName || '').trim()
  if (snapshot) return snapshot
  const provider = findProvider(context.chatProviders, message.chatProviderId)
  return provider ? providerLabel(provider) : MODEL_NOT_RECORDED
}

/** Speaker label used above a message bubble (user side stays 我 / 绘图请求). */
export function messageSpeakerName(message: Message, context: MessageModelContext = {}): string {
  if (message.role === 'USER') {
    return message.messageType === 'DRAW_REQUEST' ? '绘图请求' : '我'
  }
  const label = messageModelName(message, context)
  if (message.messageType === 'DRAW_RESPONSE') {
    return label === MODEL_NOT_RECORDED ? `[绘图] ${MODEL_NOT_RECORDED}` : `[绘图] ${label}`
  }
  return label
}
