import client from './client'

export type ExportSection = 'sessions' | 'providers' | 'settings' | 'files'
export type ImportMode = 'merge' | 'replace'

export interface ExportRequest {
  sections: ExportSection[]
  includeApiKeys?: boolean
  sessionIds?: number[]
}

export interface ImportOptions {
  mode: ImportMode
  sections?: ExportSection[]
  includeApiKeys?: boolean
}

export interface ExportPreview {
  sessions: number
  messages: number
  attachments: number
  providers: number
  models: number
  settings: number
  uploadRoot: string
}

export interface SecuritySettings {
  maxFailures: number
  failureWindowMinutes: number
  lockDurationMinutes: number
  captchaEnabled: boolean
  updatedAt?: string | null
}


export interface OperationLog {
  id: number
  userId: number | null
  username: string | null
  action: string
  targetType: string | null
  targetId: string | null
  detail: string | null
  ip: string | null
  createdAt: string
}

export interface OperationLogQuery {
  page?: number
  size?: number
  username?: string
  action?: string
  start?: string
  end?: string
}

export interface LoginSecurityEvent {
  id: number | null
  eventType: 'IP_LOCKED' | 'ACCOUNT_BLOCKED'
  username: string | null
  ipAddress: string | null
  failureCount: number | null
  lockedUntil: string | null
  occurredAt: string
  detail: string
}

export const adminApi = {
  preview(): Promise<ExportPreview> {
    return client.get('/api/admin/export/preview').then((r) => r.data)
  },

  async exportData(payload: ExportRequest): Promise<{ blob: Blob; filename: string }> {
    const response = await client.post('/api/admin/export', payload, {
      responseType: 'blob',
    })
    const disposition = String(response.headers['content-disposition'] || '')
    const matched = disposition.match(/filename\*?=(?:UTF-8''|")?([^\";]+)/i)
    const filename = matched?.[1]
      ? decodeURIComponent(matched[1].replace(/"/g, ''))
      : `ais-export-${Date.now()}.zip`
    return { blob: response.data as Blob, filename }
  },

  importData(file: File, options: ImportOptions): Promise<Record<string, unknown>> {
    const form = new FormData()
    form.append('file', file)
    form.append(
      'options',
      new Blob([JSON.stringify(options)], { type: 'application/json' }),
    )
    return client
      .post('/api/admin/import', form, {
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then((r) => r.data)
  },

  getSecuritySettings(): Promise<SecuritySettings> {
    return client.get('/api/admin/security-settings').then((r) => r.data)
  },

  updateSecuritySettings(payload: SecuritySettings): Promise<SecuritySettings> {
    return client.put('/api/admin/security-settings', payload).then((r) => r.data)
  },

  getSecurityEvents(limit = 100): Promise<LoginSecurityEvent[]> {
    return client.get('/api/admin/security-events', { params: { limit } }).then((r) => r.data)
  },

  getOperationLogs(query: OperationLogQuery = {}): Promise<{ content: OperationLog[]; totalElements: number; totalPages: number; number: number; size: number }> {
    return client.get('/api/admin/operation-logs', { params: query }).then((r) => r.data)
  },

  updateModelBilling(modelId: number, data: { billingMode?: string | null; pricePerUnit?: number | null; inputPricePerMillion?: number | null; outputPricePerMillion?: number | null; cacheReadPricePerMillion?: number | null }): Promise<Record<string, unknown>> {
    return client.put(`/api/admin/models/${modelId}/billing`, data).then((r) => r.data)
  },
}
