import type { Message } from '@/types'

/**
 * Extract the pure prompt for a DRAW_REQUEST message content.
 * The stored content looks like:
 *   `绘画提示词：<prompt>` or `绘画提示词：<prompt>\n输出配置：尺寸 X；质量 Y；格式 Z`
 * For editing we only want `<prompt>` (no label prefix, no 输出配置 section).
 */
function stripDrawRequestContent(content: string): string {
  const withoutPrefix = content.replace(/^绘画提示词：/, '')
  const parts = withoutPrefix.split(/\n输出配置：/)
  return (parts[0] ?? withoutPrefix).trim()
}

/**
 * Return the text a user message should expose when entering the composer
 * edit / resend state. DRAW_REQUEST messages expose the pure prompt only.
 */
export function getMessageEditableText(message: Message): string {
  if (message.messageType === 'DRAW_REQUEST') {
    if (message.drawPrompt?.trim()) return message.drawPrompt.trim()
    return stripDrawRequestContent(message.content)
  }
  return message.content
}
