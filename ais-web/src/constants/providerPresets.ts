import type { ProviderModelRequest } from '@/types'

/**
 * Built-in supplier types shown when adding a provider.
 * These are type templates only — they do NOT appear in the provider list
 * until an admin explicitly adds one. `custom` is the default selection.
 */
export type ProviderPresetId =
  | 'custom'
  | 'grsai'
  | 'deepseek'
  | 'openai'
  | 'moonshot'
  | 'qwen'
  | 'zhipu'
  | 'siliconflow'
  | 'openrouter'
  | 'gemini'
  | 'volcengine'
  | 'minimax'

export interface ProviderPreset {
  id: ProviderPresetId
  /** Shown in the type selector */
  label: string
  /** Short helper under the select */
  description: string
  providerKey: string
  name: string
  baseUrl: string
  /** Seed models applied only when creating a new supplier */
  seedModels?: Array<Partial<ProviderModelRequest> & { type: 'CHAT' | 'IMAGE'; modelName?: string }>
  /** When true, UI treats this as Grsai (catalog, IMAGE + GRS_AI, etc.) */
  isGrsai?: boolean
}

export const PROVIDER_PRESETS: ProviderPreset[] = [
  {
    id: 'custom',
    label: '自定义',
    description: '自行填写供应商标识、地址与模型（默认）',
    providerKey: '',
    name: '',
    baseUrl: '',
  },
  {
    id: 'grsai',
    label: 'Grsai',
    description: '内置图片模型目录，适配器 GRS_AI',
    providerKey: 'grsai',
    name: 'Grsai',
    baseUrl: 'https://grsai.dakka.com.cn',
    isGrsai: true,
    seedModels: [{ type: 'IMAGE', modelName: '', adapterType: 'GRS_AI' }],
  },
  {
    id: 'deepseek',
    label: 'DeepSeek',
    description: 'OpenAI 兼容对话接口',
    providerKey: 'deepseek',
    name: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com/v1',
    seedModels: [
      { type: 'CHAT', modelName: 'deepseek-chat' },
      { type: 'CHAT', modelName: 'deepseek-reasoner' },
    ],
  },
  {
    id: 'openai',
    label: 'OpenAI',
    description: '官方 OpenAI API',
    providerKey: 'openai',
    name: 'OpenAI',
    baseUrl: 'https://api.openai.com/v1',
    seedModels: [{ type: 'CHAT', modelName: 'gpt-4o-mini' }],
  },
  {
    id: 'moonshot',
    label: 'Moonshot (Kimi)',
    description: '月之暗面 Kimi OpenAI 兼容',
    providerKey: 'moonshot',
    name: 'Moonshot',
    baseUrl: 'https://api.moonshot.cn/v1',
    seedModels: [{ type: 'CHAT', modelName: 'moonshot-v1-8k' }],
  },
  {
    id: 'qwen',
    label: '通义千问 (Qwen)',
    description: '阿里云 DashScope 兼容模式',
    providerKey: 'qwen',
    name: '通义千问',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    seedModels: [{ type: 'CHAT', modelName: 'qwen-plus' }],
  },
  {
    id: 'zhipu',
    label: '智谱 AI (GLM)',
    description: 'BigModel OpenAI 兼容',
    providerKey: 'zhipu',
    name: '智谱 AI',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    seedModels: [{ type: 'CHAT', modelName: 'glm-4-flash' }],
  },
  {
    id: 'siliconflow',
    label: 'SiliconFlow',
    description: '硅基流动 OpenAI 兼容',
    providerKey: 'siliconflow',
    name: 'SiliconFlow',
    baseUrl: 'https://api.siliconflow.cn/v1',
    seedModels: [{ type: 'CHAT', modelName: 'deepseek-ai/DeepSeek-V3' }],
  },
  {
    id: 'openrouter',
    label: 'OpenRouter',
    description: '多模型聚合 OpenAI 兼容',
    providerKey: 'openrouter',
    name: 'OpenRouter',
    baseUrl: 'https://openrouter.ai/api/v1',
    seedModels: [{ type: 'CHAT', modelName: 'openai/gpt-4o-mini' }],
  },
  {
    id: 'gemini',
    label: 'Google Gemini',
    description: 'Gemini OpenAI 兼容端点',
    providerKey: 'gemini',
    name: 'Google Gemini',
    baseUrl: 'https://generativelanguage.googleapis.com/v1beta/openai/',
    seedModels: [{ type: 'CHAT', modelName: 'gemini-2.0-flash' }],
  },
  {
    id: 'volcengine',
    label: '火山方舟 (豆包)',
    description: '火山引擎方舟 OpenAI 兼容',
    providerKey: 'volcengine',
    name: '火山方舟',
    baseUrl: 'https://ark.cn-beijing.volces.com/api/v3',
    seedModels: [{ type: 'CHAT', modelName: '' }],
  },
  {
    id: 'minimax',
    label: 'MiniMax',
    description: 'MiniMax OpenAI 兼容',
    providerKey: 'minimax',
    name: 'MiniMax',
    baseUrl: 'https://api.minimax.chat/v1',
    seedModels: [{ type: 'CHAT', modelName: 'MiniMax-Text-01' }],
  },
]

export function findPresetById(id: string | null | undefined): ProviderPreset {
  return PROVIDER_PRESETS.find((item) => item.id === id) ?? PROVIDER_PRESETS[0]!
}

export function inferPresetIdFromProviderKey(providerKey: string | null | undefined): ProviderPresetId {
  const key = (providerKey || '').trim().toLowerCase()
  if (!key) return 'custom'
  const match = PROVIDER_PRESETS.find((item) => item.id !== 'custom' && item.providerKey === key)
  return match?.id ?? 'custom'
}
