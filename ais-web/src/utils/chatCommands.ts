export type ChatCommandName =
  | 'help'
  | 'new'
  | 'sessions'
  | 'switch'
  | 'rename'
  | 'delete'
  | 'cancel'
  | 'models'
  | 'model'
  | 'draw'
  | 'unknown'

export interface ChatCommand {
  name: ChatCommandName
  argument: string
  rawName: string
}

const aliases: Record<string, Exclude<ChatCommandName, 'unknown'>> = {
  help: 'help',
  '?': 'help',
  new: 'new',
  sessions: 'sessions',
  list: 'sessions',
  switch: 'switch',
  use: 'switch',
  rename: 'rename',
  delete: 'delete',
  remove: 'delete',
  cancel: 'cancel',
  stop: 'cancel',
  models: 'models',
  model: 'model',
  draw: 'draw',
  image: 'draw',
}

/** Returns null for a normal LLM prompt and a command for every slash-prefixed input. */
export function parseChatCommand(value: string): ChatCommand | null {
  const trimmed = value.trim()
  if (!trimmed.startsWith('/')) return null

  const match = trimmed.match(/^\/([^\s]+)(?:\s+([\s\S]*))?$/)
  if (!match) return { name: 'unknown', argument: '', rawName: trimmed.slice(1) }

  const rawName = (match[1] || '').toLowerCase()
  return {
    name: aliases[rawName] || 'unknown',
    argument: (match[2] || '').trim(),
    rawName,
  }
}

export const CHAT_COMMAND_HELP = `系统命令（命令不会发送给 AI）：
/help 或 /?                 查看本说明
/new [标题]                 新建并切换到会话
/sessions                   查看会话列表
/switch <会话ID>            切换会话
/rename <标题>              修改当前会话标题
/delete                     删除当前会话（需确认）
/cancel                     终止当前生成或对话请求
/models                     查看可用对话模型
/model <模型ID>             切换当前会话的对话模型
/draw <提示词>              打开绘图窗口并带入提示词`
