export interface Session {
  id: number
  title: string
  chatProviderId: number | null
  imageProviderId: number | null
  userId?: number | null
  createdAt: string
  updatedAt: string
}

export interface Attachment {
  id: number
  originalName: string
  contentType: string
  fileSize: number
  fileUrl: string
  createdAt: string
}

export interface TokenUsage {
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheWriteTokens?: number | null
  reasoningTokens?: number | null
}

export type MessageType = 'CHAT' | 'DRAW_REQUEST' | 'DRAW_RESPONSE'
export type MessageStatus = 'PENDING' | 'SUCCESS' | 'FAILED'

export interface Message {
  id: number
  role: 'USER' | 'ASSISTANT'
  messageType?: MessageType
  status?: MessageStatus
  content: string
  errorMessage?: string | null
  imageUrl: string | null
  drawPrompt?: string | null
  drawSize?: string | null
  drawQuality?: string | null
  drawFormat?: string | null
  drawProviderId?: number | null
  attachments: Attachment[]
  tokenUsage: TokenUsage | null
  parentMessageId?: number | null
  edited: boolean
  createdAt: string
  processingInfo?: string | null
  drawPlaceholder?: DrawPlaceholder
}

export interface ModelProvider {
  id: number
  apiProviderId?: number | null
  providerId: string
  name: string
  type: 'CHAT' | 'IMAGE'
  modelName: string
  baseUrl: string
  apiKey: string
  active: boolean
  createdAt: string
  updatedAt: string
  systemPrompt?: string | null
  reasoningEffort?: string | null
  temperature?: number | null
  timeoutSeconds?: number | null
  maxRetries?: number | null
  retryBackoffSeconds?: number | null
  adapterType?: string | null
  imageQueueConcurrency?: number | null
  configJson?: string | null
  supportsTextToImage?: boolean | null
  supportsImageToImage?: boolean | null
  priceCreditsMin?: number | null
  priceCreditsMax?: number | null
  priceCnyMin?: number | null
  priceCnyMax?: number | null
  priceDescription?: string | null
  billingMode?: string | null
  pricePerUnit?: number | null
  inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null
  cacheReadPricePerMillion?: number | null
}

export interface ModelProviderRequest {
  providerId: string
  name?: string
  type: 'CHAT' | 'IMAGE'
  modelName: string
  baseUrl: string
  apiKey?: string
  isActive?: boolean
  systemPrompt?: string
  reasoningEffort?: string
  temperature?: number | null
  timeoutSeconds?: number | null
  maxRetries?: number | null
  retryBackoffSeconds?: number | null
  adapterType?: string
  imageQueueConcurrency?: number | null
  configJson?: string
  supportsTextToImage?: boolean | null
  supportsImageToImage?: boolean | null
  priceCreditsMin?: number | null
  priceCreditsMax?: number | null
  priceCnyMin?: number | null
  priceCnyMax?: number | null
  priceDescription?: string | null
  billingMode?: string | null
  pricePerUnit?: number | null
  inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null
  cacheReadPricePerMillion?: number | null
}


export interface ProviderModelRequest {
  id?: number
  type: 'CHAT' | 'IMAGE'
  modelName: string
  systemPrompt?: string
  reasoningEffort?: string
  temperature?: number | null
  timeoutSeconds?: number | null
  maxRetries?: number | null
  retryBackoffSeconds?: number | null
  adapterType?: string
  imageQueueConcurrency?: number | null
  configJson?: string
  supportsTextToImage?: boolean | null
  supportsImageToImage?: boolean | null
  priceCreditsMin?: number | null
  priceCreditsMax?: number | null
  priceCnyMin?: number | null
  priceCnyMax?: number | null
  priceDescription?: string | null
  billingMode?: string | null
  pricePerUnit?: number | null
  inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null
  cacheReadPricePerMillion?: number | null
}


export interface GrsaiModelCatalogItem {
  modelName: string
  displayName: string
  family: 'GPT Image' | 'Nano Banana' | string
  supportsTextToImage: boolean
  supportsImageToImage: boolean
  priceCreditsMin: number | null
  priceCreditsMax: number | null
  priceCnyMin: number | null
  priceCnyMax: number | null
  priceDescription: string
}

export interface ProviderAccount {
  id: number
  providerKey: string
  name: string
  baseUrl: string
  apiKey: string
  models: ModelProvider[]
  createdAt: string
  updatedAt: string
}

export interface ProviderAccountRequest {
  providerKey: string
  name?: string
  baseUrl: string
  apiKey?: string
  models: ProviderModelRequest[]
}

export interface SystemModelSettings {
  defaultChatModelId: number | null
  defaultImageModelId: number | null
}

export interface GenerateRequest {
  prompt: string
  attachmentIds?: number[]
  chatProviderId?: number | null
  imageProviderId?: number | null
}

export interface DrawPlaceholder {
  size?: string
  quality?: string
  format?: string
}

export interface DrawRequest {
  prompt: string
  attachmentIds?: number[]
  imageProviderId?: number | null
  size?: string
  quality?: string
  format?: string
}

export interface RegenerateRequest {
  chatProviderId?: number | null
  imageProviderId?: number | null
}

export interface GenerateResponse {
  messageId: number | null
  optimizedPrompt: string
  imageUrl: string | null
  tokenUsage: TokenUsage | null
  status: MessageStatus | null
}

export interface UpdateMessageRequest {
  content: string
}

export interface UploadResponse {
  id: number
  originalName: string
  contentType: string
  fileSize: number
  fileUrl: string
}

export interface TestConnectionRequest {
  providerKey?: string
  baseUrl: string
  apiKey?: string
}

export interface TestConnectionResponse {
  success: boolean
  message: string
  responseTimeMs: number | null
}

export interface FetchModelsRequest {
  baseUrl: string
  apiKey?: string
}

export interface FetchModelsResponse {
  models: string[]
}

export interface LlmDebugStatus {
  enabled: boolean
  recordCount: number
  maxRecords: number
}

export interface LlmDebugExchangeSummary {
  id: string
  startedAt: string
  method: string
  url: string
  responseStatus: number | null
  elapsedMs: number | null
  requestBodyLength: number
  responseBodyLength: number | null
  completed: boolean
  errorType: string | null
  errorMessage: string | null
}

export interface LlmDebugExchange extends LlmDebugExchangeSummary {
  requestHeaders: Record<string, string[]>
  requestBodyEncoding: string
  requestBody: string
  responseHeaders: Record<string, string[]>
  responseBodyEncoding: string | null
  responseBody: string | null
}

// Billing types
export interface BillingRecord {
  id: number
  userId: number
  providerId: number | null
  providerName: string | null
  modelName: string | null
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  inputTokens?: number | null
  outputTokens?: number | null
  cacheReadTokens?: number | null
  cacheWriteTokens?: number | null
  reasoningTokens?: number | null
  billingMode: string | null
  unitPrice: number | null
  inputPricePerMillion?: number | null
  outputPricePerMillion?: number | null
  cacheReadPricePerMillion?: number | null
  amount: number | null
  description: string | null
  durationMs?: number | null
  sessionId: number | null
  messageId: number | null
  createdAt: string
}

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface MessageStatusResponse {
  messageId: number
  status: MessageStatus
  imageUrl: string | null
  content: string
  errorMessage: string | null
  processingInfo?: string | null
}

export interface UserDefaults {
  defaultChatProviderId: number | null
  defaultImageProviderId: number | null
}