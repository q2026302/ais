export interface Session {
  id: number
  title: string
  chatProviderId: number | null
  imageProviderId: number | null
  userId?: number | null
  createdAt: string
  updatedAt: string
  lastMessageAt?: string
  lastMessagePreview?: string
  /**
   * 会话级设置（后端 `sessions.settings` 通用 JSON 列的**生效值**：注册表默认值
   * 已由后端补齐，未识别的键已被丢弃）。首批内容：
   * `{ draw: { size, quality, format } }`。
   *
   * 新增参数/分组时后端只需注册表加一项，这里通过索引签名保持前向兼容。
   */
  settings?: SessionSettings | null
}

/** 绘画参数分组（后端 `draw` 组的参数注册表）。 */
export interface DrawSettings {
  size: string
  quality: string
  format: string
}

/**
 * 会话设置：`draw` 是已知分组；未知分组由后端注册表决定，前端原样保存、不解读，
 * 这样后端新增用途分组时前端不需要跟着改类型或接口。
 */
export interface SessionSettings {
  draw: DrawSettings
  [group: string]: unknown
}

/**
 * 稀疏更新补丁（`PATCH /api/sessions/{id}/settings`）：
 * - 分组内只覆盖传入的 key（合并语义）；
 * - 分组显式 `null` 清空该组、回注册表默认值；
 * - 未出现的键一律不动；
 * - 未知键由后端忽略，不会报错。
 */
export interface SessionSettingsPatch {
  chatProviderId?: number | null
  imageProviderId?: number | null
  draw?: Partial<DrawSettings> | null
  [group: string]: unknown
}

export interface Attachment {
  id: number
  originalName: string
  contentType: string
  fileSize: number
  fileUrl: string
  /** Signed thumbnail URL (server-generated); append ?size=small|medium at render time. */
  thumbnailUrl?: string | null
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
  /** Signed thumbnail URL (server-generated); append ?size=small|medium at render time. */
  thumbnailUrl?: string | null
  drawPrompt?: string | null
  drawSize?: string | null
  drawQuality?: string | null
  drawFormat?: string | null
  drawProviderId?: number | null
  chatProviderId?: number | null
  /**
   * Name snapshot of the model that actually produced this message, captured at
   * write time. Lets history keep showing the real model even after the provider
   * is renamed or deleted; null for legacy rows (frontend then falls back to
   * resolving `chatProviderId`/`drawProviderId`, and finally to 「未记录」).
   */
  chatProviderName?: string | null
  drawProviderName?: string | null
  attachments: Attachment[]
  tokenUsage: TokenUsage | null
  parentMessageId?: number | null
  edited: boolean
  deleted?: boolean
  /** Current user's own work-library state (never another user's records). */
  favorited?: boolean
  /** Current user's own favourite record id; null when this user has not saved it. */
  favoriteId?: number | null
  createdAt: string
  /** Advances on content/status updates; auto-unread uses coalesce(updatedAt, createdAt). */
  updatedAt?: string | null
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
  /** Existing server-side file URLs to reuse directly (history generated image / existing attachment). */
  referenceUrls?: string[]
  imageProviderId?: number | null
  size?: string
  quality?: string
  format?: string
}

/**
 * A reference image in the draw composer. `kind === 'upload'` is a real attachment
 * (sent via `attachmentIds`); `kind === 'history'` reuses an existing server-side
 * file (sent via `referenceUrls`) without creating a new attachment or copying bytes.
 */
export interface DrawReference {
  /** Stable unique key within the current draft list. */
  key: string
  name: string
  contentType: string
  fileSize: number
  /** Original (full-size) signed URL — used for "view original" and sent to the model. */
  url: string
  /** Thumbnail signed URL (empty string when unavailable) — used for grid/list display. */
  thumbnailUrl: string
  kind: 'upload' | 'history'
  /** Attachment id, present only for `kind === 'upload'`. */
  attachmentId?: number
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
  /** Signed thumbnail URL (server-generated); append ?size=small|medium at render time. */
  thumbnailUrl?: string | null
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

/** One reference image captured in a saved-work snapshot. */
export interface FavoriteReferenceImage {
  fileUrl: string
  /** Signed thumbnail URL; append ?size=small|medium at render time. */
  thumbnailUrl?: string | null
}

/**
 * A saved work (作品库 / 收藏). Snapshot semantics: it stays complete after the
 * source message and/or session are deleted, so never resolve its fields from
 * `store.messages`.
 */
export interface Favorite {
  id: number
  /** Owning user id. Regular users only ever receive their own records. */
  userId?: number | null
  /** Owning username; the admin work library uses it to tell records apart. */
  userName?: string | null
  messageId: number
  /** Source session id; `sessionAvailable` tells whether it can still be opened. */
  sessionId: number | null
  sessionAvailable: boolean
  imageUrl: string
  /** Signed thumbnail URL; append ?size=small|medium at render time. */
  thumbnailUrl?: string | null
  drawPrompt?: string | null
  drawSize?: string | null
  drawQuality?: string | null
  drawFormat?: string | null
  referenceImages: FavoriteReferenceImage[]
  /** Time the work was favourited. */
  createdAt: string
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