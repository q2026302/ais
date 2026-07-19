import client from './client'
import type { PageResponse, BillingRecord } from '@/types'

export const billingApi = {
  myLogs(page = 0, size = 20): Promise<PageResponse<BillingRecord>> {
    return client.get('/api/billing/my-logs', { params: { page, size } }).then((r) => r.data)
  },

  adminLogs(userId?: number | null, page = 0, size = 20): Promise<PageResponse<BillingRecord>> {
    const params: Record<string, number | string> = { page, size }
    if (userId != null) params.userId = userId
    return client.get('/api/admin/billing/logs', { params }).then((r) => r.data)
  },
}

export const userDefaultsApi = {
  get(): Promise<{ defaultChatProviderId: number | null; defaultImageProviderId: number | null }> {
    return client.get('/api/admin/users/defaults').then((r) => r.data)
  },

  update(data: { defaultChatProviderId?: number | null; defaultImageProviderId?: number | null }): Promise<{ defaultChatProviderId: number | null; defaultImageProviderId: number | null }> {
    return client.put('/api/admin/users/defaults', data).then((r) => r.data)
  },
}