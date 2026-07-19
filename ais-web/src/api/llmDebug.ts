import client from './client'
import type {
  LlmDebugExchange,
  LlmDebugExchangeSummary,
  LlmDebugStatus,
} from '@/types'

export const llmDebugApi = {
  getStatus(): Promise<LlmDebugStatus> {
    return client.get('/api/admin/llm-debug').then((response) => response.data)
  },

  setEnabled(enabled: boolean): Promise<LlmDebugStatus> {
    return client.put('/api/admin/llm-debug', { enabled }).then((response) => response.data)
  },

  listExchanges(limit = 50): Promise<LlmDebugExchangeSummary[]> {
    return client.get('/api/admin/llm-debug/exchanges', { params: { limit } })
      .then((response) => response.data)
  },

  getExchange(id: string): Promise<LlmDebugExchange> {
    return client.get(`/api/admin/llm-debug/exchanges/${id}`).then((response) => response.data)
  },

  clearExchanges(): Promise<LlmDebugStatus> {
    return client.delete('/api/admin/llm-debug/exchanges').then((response) => response.data)
  },
}
