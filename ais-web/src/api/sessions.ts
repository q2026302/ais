import client from './client'
import { getAppBasePath, resolveAppUrl } from '@/utils/appBasePath'
import type {
  Session,
  Message,
  GenerateRequest,
  GenerateResponse,
  UpdateMessageRequest,
  UploadResponse,
  TokenUsage,
  DrawRequest,
  MessageStatus,
  RegenerateRequest,
  MessageStatusResponse,
} from '@/types'

export interface RequestOptions {
  signal?: AbortSignal
  timeout?: number
}

const CHAT_TIMEOUT = 5 * 60 * 1000
const IMAGE_TIMEOUT = 15 * 60 * 1000

function normalizeUpload(upload: UploadResponse): UploadResponse {
  return { ...upload, fileUrl: resolveAppUrl(upload.fileUrl) || upload.fileUrl }
}

function normalizeMessage(message: Message): Message {
  return {
    ...message,
    imageUrl: resolveAppUrl(message.imageUrl) || null,
    attachments: (message.attachments || []).map((attachment) => ({
      ...attachment,
      fileUrl: resolveAppUrl(attachment.fileUrl) || attachment.fileUrl,
    })),
  }
}

export const sessionApi = {
  list(): Promise<Session[]> {
    return client.get('/api/sessions').then((r) => r.data)
  },

  create(title?: string): Promise<Session> {
    return client.post('/api/sessions', title?.trim() ? { title: title.trim() } : null).then((r) => r.data)
  },

  get(id: number): Promise<Session> {
    return client.get(`/api/sessions/${id}`).then((r) => r.data)
  },

  delete(id: number): Promise<void> {
    return client.delete(`/api/sessions/${id}`)
  },

  updateTitle(id: number, title: string): Promise<Session> {
    return client.patch(`/api/sessions/${id}/title`, { title }).then((r) => r.data)
  },

  cancelPending(sessionId: number): Promise<{ cancelled: boolean; messageId: number }> {
    return client.post(`/api/sessions/${sessionId}/cancel`, null, {
      timeout: 10_000,
    }).then((r) => r.data)
  },

  getMessages(id: number): Promise<Message[]> {
    return client.get(`/api/sessions/${id}/messages`).then((r) => (r.data as Message[]).map(normalizeMessage))
  },

  getMessageStatus(sessionId: number, messageId: number): Promise<MessageStatusResponse> {
    return client.get(`/api/sessions/${sessionId}/messages/${messageId}/status`).then((r) => {
      const data = r.data as MessageStatusResponse
      return { ...data, imageUrl: resolveAppUrl(data.imageUrl) || null }
    })
  },

  generate(id: number, data: GenerateRequest, options: RequestOptions = {}): Promise<GenerateResponse> {
    return client.post(`/api/sessions/${id}/messages`, data, {
      signal: options.signal,
      timeout: options.timeout ?? IMAGE_TIMEOUT,
    }).then((r) => {
      const data = r.data as GenerateResponse
      return data
    })
  },

  editMessage(sessionId: number, messageId: number, data: UpdateMessageRequest): Promise<Message> {
    return client.put(`/api/sessions/${sessionId}/messages/${messageId}`, data).then((r) => r.data)
  },

  regenerateMessage(
    sessionId: number,
    messageId: number,
    data: RegenerateRequest = {},
    options: RequestOptions = {},
  ): Promise<GenerateResponse> {
    return client.post(`/api/sessions/${sessionId}/messages/${messageId}/regenerate`, data, {
      signal: options.signal,
      timeout: options.timeout ?? IMAGE_TIMEOUT,
    }).then((r) => r.data)
  },

  deleteMessage(sessionId: number, messageId: number): Promise<void> {
    return client.delete(`/api/sessions/${sessionId}/messages/${messageId}`)
  },

  updateSessionProviders(sessionId: number, data: { chatProviderId?: number | null; imageProviderId?: number | null }): Promise<Session> {
    return client.patch(`/api/sessions/${sessionId}/providers`, data).then((r) => r.data)
  },

  chat(
    id: number,
    data: GenerateRequest,
    options: RequestOptions = {},
  ): Promise<{ content: string; assistantMessageId: number; tokenUsage: TokenUsage | null; status: MessageStatus; errorMessage: string | null }> {
    return client.post(`/api/sessions/${id}/chat`, data, {
      signal: options.signal,
      timeout: options.timeout ?? CHAT_TIMEOUT,
    }).then((r) => r.data)
  },

  draw(
    id: number,
    data: DrawRequest,
    options: RequestOptions = {},
  ): Promise<{ assistantMessageId: number; imageUrl: string | null; prompt: string; status: MessageStatus; errorMessage: string | null }> {
    return client.post(`/api/sessions/${id}/draw`, data, {
      signal: options.signal,
      timeout: options.timeout ?? IMAGE_TIMEOUT,
    }).then((r) => {
      const data = r.data as { assistantMessageId: number; imageUrl: string | null; prompt: string; status: MessageStatus; errorMessage: string | null }
      return { ...data, imageUrl: resolveAppUrl(data.imageUrl) || null }
    })
  },

  uploadFile(file: File): Promise<UploadResponse> {
    const formData = new FormData()
    formData.append('file', file)
    return client.post('/api/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((r) => normalizeUpload(r.data))
  },

  async uploadImageReference(fileUrl: string, filename = 'history-reference.png'): Promise<UploadResponse> {
    const base = getAppBasePath().replace(/\/$/, '')
    const rawUrl = fileUrl.startsWith(base) ? fileUrl.slice(base.length) || '/' : fileUrl
    const response = await client.get(rawUrl, { responseType: 'blob' })
    const blob = response.data as Blob
    const file = new File([blob], filename, { type: blob.type || 'image/png' })
    return this.uploadFile(file)
  },

  uploadFiles(files: File[]): Promise<UploadResponse[]> {
    const formData = new FormData()
    files.forEach((f) => formData.append('files', f))
    return client.post('/api/upload/multiple', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }).then((r) => (r.data as UploadResponse[]).map(normalizeUpload))
  },
}