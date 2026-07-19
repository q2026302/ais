import client from './client'
import type {
  FetchModelsRequest,
  FetchModelsResponse,
  GrsaiModelCatalogItem,
  ModelProvider,
  ModelProviderRequest,
  ProviderAccount,
  ProviderAccountRequest,
  SystemModelSettings,
  TestConnectionRequest,
  TestConnectionResponse,
} from '@/types'

export const providerApi = {
  list(type?: string): Promise<ModelProvider[]> {
    const params = type ? { type } : {}
    return client.get('/api/providers', { params }).then((r) => r.data)
  },

  getActive(type: string): Promise<ModelProvider> {
    return client.get('/api/providers/active', { params: { type } }).then((r) => r.data)
  },

  get(id: number): Promise<ModelProvider> {
    return client.get(`/api/providers/${id}`).then((r) => r.data)
  },

  create(data: ModelProviderRequest): Promise<ModelProvider> {
    return client.post('/api/providers', data).then((r) => r.data)
  },

  update(id: number, data: ModelProviderRequest): Promise<ModelProvider> {
    return client.put(`/api/providers/${id}`, data).then((r) => r.data)
  },

  delete(id: number): Promise<void> {
    return client.delete(`/api/providers/${id}`)
  },

  activate(id: number): Promise<ModelProvider> {
    return client.patch(`/api/providers/${id}/activate`).then((r) => r.data)
  },

  testConnection(data: TestConnectionRequest): Promise<TestConnectionResponse> {
    return client.post('/api/providers/test', data).then((r) => r.data)
  },

  testProviderConnection(id: number, data?: Partial<TestConnectionRequest>): Promise<TestConnectionResponse> {
    return client.post(`/api/providers/${id}/test`, data ?? {}).then((r) => r.data)
  },

  fetchModels(data: FetchModelsRequest): Promise<FetchModelsResponse> {
    return client.post('/api/providers/models', data).then((r) => r.data)
  },

  fetchProviderModels(id: number, data?: Partial<FetchModelsRequest>): Promise<FetchModelsResponse> {
    return client.post(`/api/providers/${id}/models`, data ?? {}).then((r) => r.data)
  },
}

export const providerAccountApi = {
  list(): Promise<ProviderAccount[]> {
    return client.get('/api/provider-accounts').then((r) => r.data)
  },

  get(id: number): Promise<ProviderAccount> {
    return client.get(`/api/provider-accounts/${id}`).then((r) => r.data)
  },

  create(data: ProviderAccountRequest): Promise<ProviderAccount> {
    return client.post('/api/provider-accounts', data).then((r) => r.data)
  },

  update(id: number, data: ProviderAccountRequest): Promise<ProviderAccount> {
    return client.put(`/api/provider-accounts/${id}`, data).then((r) => r.data)
  },

  delete(id: number): Promise<void> {
    return client.delete(`/api/provider-accounts/${id}`)
  },

  getDefaults(): Promise<SystemModelSettings> {
    return client.get('/api/provider-accounts/defaults').then((r) => r.data)
  },

  updateDefaults(data: SystemModelSettings): Promise<SystemModelSettings> {
    return client.put('/api/provider-accounts/defaults', data).then((r) => r.data)
  },

  testConnection(id: number, data?: Partial<TestConnectionRequest>): Promise<TestConnectionResponse> {
    return client.post(`/api/provider-accounts/${id}/test`, data ?? {}).then((r) => r.data)
  },

  fetchModels(id: number, data?: Partial<FetchModelsRequest>): Promise<FetchModelsResponse> {
    return client.post(`/api/provider-accounts/${id}/models`, data ?? {}).then((r) => r.data)
  },
}

export const modelCatalogApi = {
  grsai(): Promise<GrsaiModelCatalogItem[]> {
    return client.get('/api/model-catalogs/grsai').then((r) => r.data)
  },

  refreshGrsai(): Promise<GrsaiModelCatalogItem[]> {
    return client.post('/api/model-catalogs/grsai/refresh').then((r) => r.data)
  },
}
